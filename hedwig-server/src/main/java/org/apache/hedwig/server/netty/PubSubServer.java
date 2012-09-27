/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hedwig.server.netty;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.protobuf.ByteString;
import com.twitter.common.application.ShutdownRegistry;
import com.twitter.common.base.Supplier;
import com.twitter.common.net.http.handlers.VarsJsonHandler;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.stats.JvmStats;
import com.twitter.common.stats.Stat;
import com.twitter.common.stats.Stats;
import com.twitter.common.net.http.handlers.VarsHandler;
import com.twitter.common.stats.TimeSeriesRepository;
import com.twitter.common.stats.TimeSeriesRepositoryImpl;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.util.MathUtils;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.lang.StringUtils;
import org.apache.hedwig.protocol.PubSubProtocol;
import org.apache.hedwig.server.stats.*;
import org.apache.hedwig.util.Pair;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.ServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.logging.Log4JLoggerFactory;
import org.eclipse.jetty.server.Server;

import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol.OperationType;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.common.TerminateJVMExceptionHandler;
import org.apache.hedwig.server.delivery.DeliveryManager;
import org.apache.hedwig.server.delivery.FIFODeliveryManager;
import org.apache.hedwig.server.handlers.ConsumeHandler;
import org.apache.hedwig.server.handlers.Handler;
import org.apache.hedwig.server.handlers.NettyHandlerBean;
import org.apache.hedwig.server.handlers.PublishHandler;
import org.apache.hedwig.server.handlers.SubscribeHandler;
import org.apache.hedwig.server.handlers.UnsubscribeHandler;
import org.apache.hedwig.server.jmx.HedwigMBeanRegistry;
import org.apache.hedwig.server.meta.MetadataManagerFactory;
import org.apache.hedwig.server.meta.ZkMetadataManagerFactory;
import org.apache.hedwig.server.persistence.BookkeeperPersistenceManager;
import org.apache.hedwig.server.persistence.LocalDBPersistenceManager;
import org.apache.hedwig.server.persistence.PersistenceManager;
import org.apache.hedwig.server.persistence.PersistenceManagerWithRangeScan;
import org.apache.hedwig.server.persistence.ReadAheadCache;
import org.apache.hedwig.server.regions.HedwigHubClientFactory;
import org.apache.hedwig.server.regions.RegionManager;
import org.apache.hedwig.server.ssl.SslServerContextFactory;
import org.apache.hedwig.server.stats.HedwigServerStatsLogger.PerTopicStatType;
import org.apache.hedwig.server.subscriptions.InMemorySubscriptionManager;
import org.apache.hedwig.server.subscriptions.SubscriptionManager;
import org.apache.hedwig.server.subscriptions.MMSubscriptionManager;
import org.apache.hedwig.server.topics.MMTopicManager;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.server.topics.TrivialOwnAllTopicManager;
import org.apache.hedwig.server.topics.ZkTopicManager;
import org.apache.hedwig.util.ConcurrencyUtils;
import org.apache.hedwig.util.Either;
import org.apache.hedwig.zookeeper.SafeAsyncCallback;

public class PubSubServer {

    static Logger logger = LoggerFactory.getLogger(PubSubServer.class);

    private static final String JMXNAME_PREFIX = "PubSubServer_";

    // Netty related variables
    ServerSocketChannelFactory serverChannelFactory;
    ClientSocketChannelFactory clientChannelFactory;
    ServerConfiguration conf;
    org.apache.hedwig.client.conf.ClientConfiguration clientConfiguration;
    ChannelGroup allChannels;

    // Manager components that make up the PubSubServer
    PersistenceManager pm;
    DeliveryManager dm;
    TopicManager tm;
    SubscriptionManager sm;
    RegionManager rm;

    // Metadata Manager Factory
    MetadataManagerFactory mm;

    ZooKeeper zk; // null if we are in standalone mode
    BookKeeper bk; // null if we are in standalone mode

    // we use this to prevent long stack chains from building up in callbacks
    ScheduledExecutorService scheduler;

    // JMX Beans
    NettyHandlerBean jmxNettyBean;
    PubSubServerBean jmxServerBean;
    final ThreadGroup tg;

    // Export stats
    private ShutdownRegistry.ShutdownRegistryImpl shutDownRegistry;
    private Server jettyServer;

    protected PersistenceManager instantiatePersistenceManager(TopicManager topicMgr) throws IOException,
        InterruptedException {

        PersistenceManagerWithRangeScan underlyingPM;

        if (conf.isStandalone()) {

            underlyingPM = LocalDBPersistenceManager.instance();

        } else {
            try {
                ClientConfiguration bkConf = new ClientConfiguration();
                bkConf.addConfiguration(conf.getConf());
                bk = new BookKeeper(bkConf, zk, clientChannelFactory);
            } catch (KeeperException e) {
                logger.error("Could not instantiate bookkeeper client", e);
                throw new IOException(e);
            }
            underlyingPM = new BookkeeperPersistenceManager(bk, mm, topicMgr, conf, scheduler);

        }

        PersistenceManager pm = underlyingPM;

        if (conf.getReadAheadEnabled()) {
            pm = new ReadAheadCache(underlyingPM, conf).start();
        }

        return pm;
    }

    protected SubscriptionManager instantiateSubscriptionManager(TopicManager tm, PersistenceManager pm) {
        if (conf.isStandalone()) {
            return new InMemorySubscriptionManager(tm, pm, conf, scheduler);
        } else {
            return new MMSubscriptionManager(mm, tm, pm, conf, scheduler);
        }

    }

    protected RegionManager instantiateRegionManager(PersistenceManager pm, ScheduledExecutorService scheduler) {
        return new RegionManager(pm, conf, zk, scheduler, new HedwigHubClientFactory(conf, clientConfiguration,
                clientChannelFactory));
    }

    protected void instantiateZookeeperClient() throws Exception {
        if (!conf.isStandalone()) {
            final CountDownLatch signalZkReady = new CountDownLatch(1);

            zk = new ZooKeeper(conf.getZkHost(), conf.getZkTimeout(), new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    if(Event.KeeperState.SyncConnected.equals(event.getState())) {
                        signalZkReady.countDown();
                    }
                }
            });
            // wait until connection is effective
            if (!signalZkReady.await(conf.getZkTimeout()*2, TimeUnit.MILLISECONDS)) {
                logger.error("Could not establish connection with ZooKeeper after zk_timeout*2 = " +
                             conf.getZkTimeout()*2 + " ms. (Default value for zk_timeout is 2000).");
                throw new Exception("Could not establish connection with ZooKeeper after zk_timeout*2 = " +
                                    conf.getZkTimeout()*2 + " ms. (Default value for zk_timeout is 2000).");
            }
        }
    }

    protected void instantiateMetadataManagerFactory() throws Exception {
        if (conf.isStandalone()) {
            return;
        }
        mm = MetadataManagerFactory.newMetadataManagerFactory(conf, zk);
    }

    protected TopicManager instantiateTopicManager() throws IOException {
        TopicManager tm;

        if (conf.isStandalone()) {
            tm = new TrivialOwnAllTopicManager(conf, scheduler);
        } else {
            try {
                if (conf.isMetadataManagerBasedTopicManagerEnabled()) {
                    tm = new MMTopicManager(conf, zk, mm, scheduler);
                } else {
                    if (!(mm instanceof ZkMetadataManagerFactory)) {
                        throw new IOException("Uses " + mm.getClass().getName() + " to store hedwig metadata, "
                                            + "but uses zookeeper ephemeral znodes to store topic ownership. "
                                            + "Check your configuration as this could lead to scalability issues.");
                    }
                    tm = new ZkTopicManager(zk, conf, scheduler);
                }
            } catch (PubSubException e) {
                logger.error("Could not instantiate TopicOwnershipManager based topic manager", e);
                throw new IOException(e);
            }
        }
        return tm;
    }

    protected Map<OperationType, Handler> initializeNettyHandlers(TopicManager tm, DeliveryManager dm,
            PersistenceManager pm, SubscriptionManager sm) {
        Map<OperationType, Handler> handlers = new HashMap<OperationType, Handler>();
        handlers.put(OperationType.PUBLISH, new PublishHandler(tm, pm, conf));
        handlers.put(OperationType.SUBSCRIBE, new SubscribeHandler(tm, dm, pm, sm, conf));
        handlers.put(OperationType.UNSUBSCRIBE, new UnsubscribeHandler(tm, conf, sm, dm));
        handlers.put(OperationType.CONSUME, new ConsumeHandler(tm, sm, conf));
        handlers = Collections.unmodifiableMap(handlers);
        return handlers;
    }

    protected void initializeNetty(SslServerContextFactory sslFactory, Map<OperationType, Handler> handlers) {
        boolean isSSLEnabled = (sslFactory != null) ? true : false;
        InternalLoggerFactory.setDefaultFactory(new Log4JLoggerFactory());
        ServerBootstrap bootstrap = new ServerBootstrap(serverChannelFactory);
        UmbrellaHandler umbrellaHandler = new UmbrellaHandler(allChannels, handlers, isSSLEnabled);
        PubSubServerPipelineFactory pipeline = new PubSubServerPipelineFactory(umbrellaHandler, sslFactory, conf
                .getMaximumMessageSize());

        bootstrap.setPipelineFactory(pipeline);
        bootstrap.setOption("child.tcpNoDelay", true);
        bootstrap.setOption("child.keepAlive", true);
        bootstrap.setOption("reuseAddress", true);

        // Bind and start to accept incoming connections.
        allChannels.add(bootstrap.bind(isSSLEnabled ? new InetSocketAddress(conf.getSSLServerPort())
                                       : new InetSocketAddress(conf.getServerPort())));
        logger.info("Going into receive loop");
    }

    public void shutdown() {
        // TODO: tell bk to close logs

        // Stop topic manager first since it is core of Hub server
        tm.stop();

        // Stop the RegionManager.
        rm.stop();

        // Stop the DeliveryManager and ReadAheadCache threads (if
        // applicable).
        dm.stop();
        pm.stop();

        // Stop the SubscriptionManager if needed.
        sm.stop();

        // Shutdown metadata manager if needed
        if (null != mm) {
            try {
                mm.shutdown();
            } catch (IOException ie) {
                logger.error("Error while shutdown metadata manager factory!", ie);
            }
        }

        // Shutdown the ZooKeeper and BookKeeper clients only if we are
        // not in stand-alone mode.
        try {
            if (bk != null)
                bk.close();
            if (zk != null)
                zk.close();
        } catch (InterruptedException e) {
            logger.error("Error while closing ZooKeeper client : ", e);
        } catch (BKException bke) {
            logger.error("Error while closing BookKeeper client : ", bke);
        }

        // Close and release the Netty channels and resources
        allChannels.close().awaitUninterruptibly();
        serverChannelFactory.releaseExternalResources();
        clientChannelFactory.releaseExternalResources();
        scheduler.shutdown();

        // unregister jmx
        unregisterJMX();

        // stop exporting stats
        try {
            stopStatsExporter();
        } catch (Exception e) {
            logger.error("Error while stopping the stats exporter");
        }
    }

    protected void registerJMX(Map<OperationType, Handler> handlers) {
        try {
            String jmxName = JMXNAME_PREFIX + conf.getServerPort() + "_"
                                            + conf.getSSLServerPort();
            jmxServerBean = new PubSubServerBean(jmxName);
            HedwigMBeanRegistry.getInstance().register(jmxServerBean, null);
            try {
                jmxNettyBean = new NettyHandlerBean(handlers);
                HedwigMBeanRegistry.getInstance().register(jmxNettyBean, jmxServerBean);
            } catch (Exception e) {
                logger.warn("Failed to register with JMX", e);
                jmxNettyBean = null;
            }
        } catch (Exception e) {
            logger.warn("Failed to register with JMX", e);
            jmxServerBean = null;
        }
        if (pm instanceof ReadAheadCache) {
            ((ReadAheadCache)pm).registerJMX(jmxServerBean);
        }
    }

    protected void unregisterJMX() {
        if (pm != null && pm instanceof ReadAheadCache) {
            ((ReadAheadCache)pm).unregisterJMX();
        }
        try {
            if (jmxNettyBean != null) {
                HedwigMBeanRegistry.getInstance().unregister(jmxNettyBean);
            }
        } catch (Exception e) {
            logger.warn("Failed to unregister with JMX", e);
        }
        try {
            if (jmxServerBean != null) {
                HedwigMBeanRegistry.getInstance().unregister(jmxServerBean);
            }
        } catch (Exception e) {
            logger.warn("Failed to unregister with JMX", e);
        }
        jmxNettyBean = null;
        jmxServerBean = null;
    }

    /**
     * Starts a Jetty HTTP server and necessary servlets using which stats are exported.
     */
    protected void startStatsExporter() throws Exception {
        // Create the ShutdownRegistry needed for our sampler
        this.shutDownRegistry = new ShutdownRegistry.ShutdownRegistryImpl();

        // Start the sampler. Sample every 1 second and retain for 1 hour
        // TODO(Aniruddha): Make this configurable if needed.
        TimeSeriesRepository sampler = new TimeSeriesRepositoryImpl(Stats.STAT_REGISTRY,
                Amount.of(1L, Time.SECONDS), Amount.of(1L, Time.HOURS));
        sampler.start(this.shutDownRegistry);

        // Export JVM stats
        JvmStats.export();
        // Configure handlers
        Supplier<Iterable<Stat<?>>> supplier = new Supplier<Iterable<Stat<?>>>() {
            @Override
            public Iterable<Stat<?>> get() {
                return Stats.getVariables();
            }
        };
        // Start jetty.
        this.jettyServer = new Server(conf.getStatsHttpPort());
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        this.jettyServer.setHandler(context);
        context.addServlet(new ServletHolder(new VarsHandler(supplier)), "/vars");
        context.addServlet(new ServletHolder(new VarsJsonHandler(supplier)), "/vars.json");
        this.jettyServer.start();

        // Print the pending message value per topic every 1 minute
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                String newLine = System.getProperty("line.separator");
                StringBuilder sb = new StringBuilder("Number of messages pending delivery to the most lagging subscriber.");
                sb.append(newLine);
                sb.append("\tTopic\tMessages pending delivery").append(newLine);
                ConcurrentMap<ByteString, PerTopicStat> topicMap = ServerStatsProvider.getStatsLoggerInstance().getPerTopicLogger(
                        PerTopicStatType.LOCAL_PENDING);
                long totalTopics = 0;
                long maxPending = 0;
                for (ConcurrentMap.Entry<ByteString, PerTopicStat> entry : topicMap.entrySet()) {
                    PerTopicPendingMessageStat value = (PerTopicPendingMessageStat)entry.getValue();
                    ByteString topic = entry.getKey();
                    AtomicLong pendingValue;
                    if (null == (pendingValue = value.getPending())) {
                        // The mapped value is null. Move on.
                        continue;
                    }
                    totalTopics++;
                    maxPending = Math.max(maxPending, pendingValue.get());
                    sb.append("\t");
                    sb.append(topic.toStringUtf8());
                    sb.append("\t");
                    sb.append(value.getPending().get());
                    sb.append("\t").append(newLine);
                }
                sb.append("Total Topics : ").append(totalTopics)
                        .append(", Max Pending : ").append(maxPending).append(newLine).append(newLine);
                logger.info(sb.toString());
            }
        }, 60, 60, TimeUnit.SECONDS);

        // Print the per topic cross region received messages every 5 minutes.
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                String newLine = System.getProperty("line.separator");
                StringBuilder sb = new StringBuilder("Cross region received messages.");
                sb.append(newLine);
                sb.append("\tTopic\tInfo").append(newLine);
                ConcurrentMap<ByteString, PerTopicStat> topicMap = ServerStatsProvider.getStatsLoggerInstance().getPerTopicLogger(
                        PerTopicStatType.CROSS_REGION);
                for (ConcurrentMap.Entry<ByteString, PerTopicStat> entry : topicMap.entrySet()) {
                    PerTopicCrossRegionStat value = (PerTopicCrossRegionStat)entry.getValue();
                    ByteString topic = entry.getKey();
                    ConcurrentMap<ByteString, Pair<PubSubProtocol.Message, Long>> regionMap = value.getRegionMap();
                    sb.append("\t").append(topic.toStringUtf8()).append("\t");
                    for (ConcurrentMap.Entry<ByteString, Pair<PubSubProtocol.Message, Long>> regionEntry : regionMap.entrySet()) {
                        sb.append("region:").append(regionEntry.getKey().toStringUtf8()).append(", ");
                        sb.append("age-millis:").append(MathUtils.now() - regionEntry.getValue().second()).append(", ");
                        PubSubProtocol.Message message = regionEntry.getValue().first();
                        sb.append("Vector Clock:[localComponent:");
                        sb.append(message.getMsgId().getLocalComponent()).append(", remoteComponents:[");
                        for (PubSubProtocol.RegionSpecificSeqId seqId : message.getMsgId().getRemoteComponentsList()) {
                            sb.append(seqId.getRegion().toStringUtf8()).append(":").append(seqId.getSeqId()).append(",");
                        }
                        sb.append("]]");
                        sb.append("\t").append(StringUtils.repeat(" ", topic.toStringUtf8().length())).append("\t");
                    }
                    sb.append(newLine);
                }
                sb.append(newLine).append(newLine);
                logger.info(sb.toString());
            }
        }, 300, 300, TimeUnit.SECONDS);
    }

    /**
     * Stop all operations that were started for exporting stats.
     */
    protected void stopStatsExporter() throws Exception {

        if (this.jettyServer != null) {
            this.jettyServer.stop();
        }
        if (this.shutDownRegistry != null) {
            this.shutDownRegistry.execute();
        }
    }

    /**
     * Starts the hedwig server on the given port
     *
     * @param port
     * @throws ConfigurationException
     *             if there is something wrong with the given configuration
     * @throws IOException
     * @throws InterruptedException
     * @throws ConfigurationException
     */
    public PubSubServer(final ServerConfiguration serverConfiguration,
                        final org.apache.hedwig.client.conf.ClientConfiguration clientConfiguration,
                        final Thread.UncaughtExceptionHandler exceptionHandler)
            throws ConfigurationException {

        // First validate the serverConfiguration
        this.conf = serverConfiguration;
        serverConfiguration.validate();

        // Validate the client configuration
        this.clientConfiguration = clientConfiguration;
        clientConfiguration.validate();

        // We need a custom thread group, so that we can override the uncaught
        // exception method
        tg = new ThreadGroup("hedwig") {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                exceptionHandler.uncaughtException(t, e);
            }
        };
        // ZooKeeper threads register their own handler. But if some work that
        // we do in ZK threads throws an exception, we want our handler to be
        // called, not theirs.
        SafeAsyncCallback.setUncaughtExceptionHandler(exceptionHandler);
    }

    public void start() throws Exception {
        final SynchronousQueue<Either<Object, Exception>> queue = new SynchronousQueue<Either<Object, Exception>>();

        new Thread(tg, new Runnable() {
            @Override
            public void run() {
                try {
                    // Since zk is needed by almost everyone,try to see if we
                    // need that first
                    scheduler = Executors.newSingleThreadScheduledExecutor();
                    serverChannelFactory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool(), Executors
                            .newCachedThreadPool());
                    clientChannelFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors
                            .newCachedThreadPool());

                    instantiateZookeeperClient();
                    instantiateMetadataManagerFactory();
                    tm = instantiateTopicManager();
                    pm = instantiatePersistenceManager(tm);
                    dm = new FIFODeliveryManager(pm, conf);
                    dm.start();

                    sm = instantiateSubscriptionManager(tm, pm);
                    rm = instantiateRegionManager(pm, scheduler);
                    sm.addListener(rm);

                    allChannels = new DefaultChannelGroup("hedwig");
                    // Initialize the Netty Handlers (used by the
                    // UmbrellaHandler) once so they can be shared by
                    // both the SSL and non-SSL channels.
                    Map<OperationType, Handler> handlers = initializeNettyHandlers(tm, dm, pm, sm);
                    // Initialize Netty for the regular non-SSL channels
                    initializeNetty(null, handlers);
                    if (conf.isSSLEnabled()) {
                        initializeNetty(new SslServerContextFactory(conf), handlers);
                    }
                    // register jmx
                    registerJMX(handlers);

                    // Start the HTTP server for exposing stats.
                    if (conf.getStatsExport()) {
                        startStatsExporter();
                    }
                } catch (Exception e) {
                    ConcurrencyUtils.put(queue, Either.right(e));
                    return;
                }

                ConcurrencyUtils.put(queue, Either.of(new Object(), (Exception) null));
            }

        }).start();

        Either<Object, Exception> either = ConcurrencyUtils.take(queue);
        if (either.left() == null) {
            throw either.right();
        }
    }

    public PubSubServer(ServerConfiguration serverConfiguration,
                        org.apache.hedwig.client.conf.ClientConfiguration clientConfiguration) throws Exception {
        this(serverConfiguration, clientConfiguration, new TerminateJVMExceptionHandler());
    }

    public PubSubServer(ServerConfiguration serverConfiguration) throws Exception {
        this(serverConfiguration, new org.apache.hedwig.client.conf.ClientConfiguration());
    }

    /**
     *
     * @param msg
     * @param rc
     *            : code to exit with
     */
    public static void errorMsgAndExit(String msg, Throwable t, int rc) {
        logger.error(msg, t);
        System.err.println(msg);
        System.exit(rc);
    }

    public final static int RC_INVALID_CONF_FILE = 1;
    public final static int RC_MISCONFIGURED = 2;
    public final static int RC_OTHER = 3;

    /**
     * @param args
     */
    public static void main(String[] args) {

        logger.info("Attempting to start Hedwig");
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        // The client configuration for the hedwig client in the region manager.
        org.apache.hedwig.client.conf.ClientConfiguration clientConfiguration
                = new org.apache.hedwig.client.conf.ClientConfiguration();
        if (args.length > 0) {
            String confFile = args[0];
            try {
                serverConfiguration.loadConf(new File(confFile).toURI().toURL());
            } catch (MalformedURLException e) {
                String msg = "Could not open server configuration file: " + confFile;
                errorMsgAndExit(msg, e, RC_INVALID_CONF_FILE);
            } catch (ConfigurationException e) {
                String msg = "Malformed server configuration file: " + confFile;
                errorMsgAndExit(msg, e, RC_MISCONFIGURED);
            }
            logger.info("Using configuration file " + confFile);
        }
        if (args.length > 1) {
            // args[1] is the client configuration file.
            String confFile = args[1];
            try {
                clientConfiguration.loadConf(new File(confFile).toURI().toURL());
            } catch (MalformedURLException e) {
                String msg = "Could not open client configuration file: " + confFile;
                errorMsgAndExit(msg, e, RC_INVALID_CONF_FILE);
            } catch (ConfigurationException e) {
                String msg = "Malformed client configuration file: " + confFile;
                errorMsgAndExit(msg, e, RC_MISCONFIGURED);
            }
        }
        try {
            new PubSubServer(serverConfiguration, clientConfiguration).start();
        } catch (Throwable t) {
            errorMsgAndExit("Error during startup", t, RC_OTHER);
        }
    }
}
