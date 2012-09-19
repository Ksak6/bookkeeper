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
package org.apache.hedwig.client.netty;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.hedwig.client.exceptions.NoResponseHandlerException;
import org.apache.hedwig.protocol.PubSubProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;

import com.google.protobuf.ByteString;

import org.apache.bookkeeper.util.MathUtils;
import org.apache.hedwig.client.api.MessageHandler;
import org.apache.hedwig.client.api.Subscriber;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.client.data.PubSubData;
import org.apache.hedwig.client.data.TopicSubscriber;
import org.apache.hedwig.client.exceptions.AlreadyStartDeliveryException;
import org.apache.hedwig.client.exceptions.InvalidSubscriberIdException;
import org.apache.hedwig.client.handlers.PubSubCallback;
import org.apache.hedwig.filter.ClientMessageFilter;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.exceptions.PubSubException.ClientAlreadySubscribedException;
import org.apache.hedwig.exceptions.PubSubException.ClientNotSubscribedException;
import org.apache.hedwig.exceptions.PubSubException.CouldNotConnectException;
import org.apache.hedwig.exceptions.PubSubException.ServiceDownException;
import org.apache.hedwig.protocol.PubSubProtocol.ConsumeRequest;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.OperationType;
import org.apache.hedwig.protocol.PubSubProtocol.ProtocolVersion;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest;
import org.apache.hedwig.protocol.PubSubProtocol.UnsubscribeRequest;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionOptions;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionPreferences;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.protoextensions.SubscriptionStateUtils;
import org.apache.hedwig.util.Callback;

/**
 * This is the Hedwig Netty specific implementation of the Subscriber interface.
 *
 */
public class HedwigSubscriber implements Subscriber {

    private static Logger logger = LoggerFactory.getLogger(HedwigSubscriber.class);

    // Concurrent Map to store the cached Channel connections on the client side
    // to a server host for a given Topic + SubscriberId combination. For each
    // TopicSubscriber, we want a unique Channel connection to the server for
    // it. We can also get the ResponseHandler tied to the Channel via the
    // Channel Pipeline.
    protected final ConcurrentMap<TopicSubscriber, Channel> topicSubscriber2Channel = new ConcurrentHashMap<TopicSubscriber, Channel>();
    protected final ConcurrentMap<TopicSubscriber, SubscriptionPreferences> topicSubscriber2Preferences =
        new ConcurrentHashMap<TopicSubscriber, SubscriptionPreferences>();

    // Concurrent Map to store Message handler for each topic + sub id combination.
    // Store it here instead of in SubscriberResponseHandler as we don't want to lose the handler
    // user set when connection is recovered
    protected final ConcurrentMap<TopicSubscriber, MessageHandler> topicSubscriber2MessageHandler= new ConcurrentHashMap<TopicSubscriber, MessageHandler>();

    protected final HedwigClientImpl client;
    protected final ClientConfiguration cfg;
    private final Object closeLock = new Object();
    private boolean closed = false;

    public HedwigSubscriber(HedwigClientImpl client) {
        this.client = client;
        this.cfg = client.getConfiguration();
    }

    // Private method that holds the common logic for doing synchronous
    // Subscribe or Unsubscribe requests. This is for code reuse since these
    // two flows are very similar. The assumption is that the input
    // OperationType is either SUBSCRIBE or UNSUBSCRIBE.
    private void subUnsub(ByteString topic, ByteString subscriberId, OperationType operationType,
                          SubscriptionOptions options)
            throws CouldNotConnectException, ClientAlreadySubscribedException,
        ClientNotSubscribedException, ServiceDownException {
        if (logger.isDebugEnabled()) {
            StringBuilder debugMsg = new StringBuilder().append("Calling a sync subUnsub request for topic: ")
                                     .append(topic.toStringUtf8()).append(", subscriberId: ")
                                     .append(subscriberId.toStringUtf8()).append(", operationType: ")
                                     .append(operationType);
            if (null != options) {
                debugMsg.append(", createOrAttach: ").append(options.getCreateOrAttach())
                        .append(", messageBound: ").append(options.getMessageBound());
            }
            logger.debug(debugMsg.toString());
        }
        PubSubData pubSubData = new PubSubData(topic, null, subscriberId, operationType, options, null, null);
        synchronized (pubSubData) {
            PubSubCallback pubSubCallback = new PubSubCallback(pubSubData);
            asyncSubUnsub(topic, subscriberId, pubSubCallback, null, operationType, options);
            try {
                while (!pubSubData.isDone)
                    pubSubData.wait();
            } catch (InterruptedException e) {
                throw new ServiceDownException("Interrupted Exception while waiting for async subUnsub call");
            }
            // Check from the PubSubCallback if it was successful or not.
            if (!pubSubCallback.getIsCallSuccessful()) {
                // See what the exception was that was thrown when the operation
                // failed.
                PubSubException failureException = pubSubCallback.getFailureException();
                if (failureException == null) {
                    // This should not happen as the operation failed but a null
                    // PubSubException was passed. Log a warning message but
                    // throw a generic ServiceDownException.
                    logger.error("Sync SubUnsub operation failed but no PubSubException was passed!");
                    throw new ServiceDownException("Server ack response to SubUnsub request is not successful");
                }
                // For the expected exceptions that could occur, just rethrow
                // them.
                else if (failureException instanceof CouldNotConnectException)
                    throw (CouldNotConnectException) failureException;
                else if (failureException instanceof ClientAlreadySubscribedException)
                    throw (ClientAlreadySubscribedException) failureException;
                else if (failureException instanceof ClientNotSubscribedException)
                    throw (ClientNotSubscribedException) failureException;
                else if (failureException instanceof ServiceDownException)
                    throw (ServiceDownException) failureException;
                else {
                    logger.error("Unexpected PubSubException thrown: " + failureException.toString());
                    // Throw a generic ServiceDownException but wrap the
                    // original PubSubException within it.
                    throw new ServiceDownException(failureException);
                }
            }
        }
    }

    // Private method that holds the common logic for doing asynchronous
    // Subscribe or Unsubscribe requests. This is for code reuse since these two
    // flows are very similar. The assumption is that the input OperationType is
    // either SUBSCRIBE or UNSUBSCRIBE.
    private void asyncSubUnsub(ByteString topic, ByteString subscriberId,
                               Callback<PubSubProtocol.ResponseBody> callback, Object context,
                               OperationType operationType, SubscriptionOptions options) {
        if (logger.isDebugEnabled()) {
            StringBuilder debugMsg = new StringBuilder().append("Calling a async subUnsub request for topic: ")
                                     .append(topic.toStringUtf8()).append(", subscriberId: ")
                                     .append(subscriberId.toStringUtf8()).append(", operationType: ")
                                     .append(operationType);
            if (null != options) {
                debugMsg.append(", createOrAttach: ").append(options.getCreateOrAttach())
                        .append(", messageBound: ").append(options.getMessageBound());
            }
            logger.debug(debugMsg.toString());
        }
        // Check if we know which server host is the master for the topic we are
        // subscribing to.
        PubSubData pubSubData = new PubSubData(topic, null, subscriberId, operationType, options, callback,
                                               context);

        InetSocketAddress host = client.topic2Host.get(topic);
        if (host != null) {
            Channel existingChannel = null;
            if (operationType.equals(OperationType.UNSUBSCRIBE) &&
                (existingChannel = client.getPublisher().host2Channel.get(host)) != null) {
                // For unsubscribes, we can reuse the channel connections to the
                // server host that are cached for publishes. For publish and
                // unsubscribe flows, we will thus use the same Channels and
                // will cache and store them during the ConnectCallback.
                doSubUnsub(pubSubData, existingChannel);
            } else {
                // We know which server host is the master for the topic so
                // connect to that first. For subscribes, we want a new channel
                // connection each time for the TopicSubscriber. If the
                // TopicSubscriber is already connected and subscribed,
                // we assume the server will respond with an appropriate status
                // indicating this. For unsubscribes, it is possible that the
                // client is subscribed to the topic already but does not
                // have a Channel connection yet to the server host. e.g. Client
                // goes down and comes back up but client side soft state memory
                // does not have the netty Channel connection anymore.
                client.doConnect(pubSubData, host);
            }
        } else {
            // Server host for the given topic is not known yet so use the
            // default server host/port as defined in the configs. This should
            // point to the server VIP which would redirect to a random server
            // (which might not be the server hosting the topic).
            client.doConnect(pubSubData, cfg.getDefaultServerHost());
        }
    }

    public void subscribe(ByteString topic, ByteString subscriberId, CreateOrAttach mode)
            throws CouldNotConnectException, ClientAlreadySubscribedException, ServiceDownException,
        InvalidSubscriberIdException {
        SubscriptionOptions options = SubscriptionOptions.newBuilder().setCreateOrAttach(mode).build();
        subscribe(topic, subscriberId, options, false);
    }

    public void subscribe(ByteString topic, ByteString subscriberId, SubscriptionOptions options)
            throws CouldNotConnectException, ClientAlreadySubscribedException, ServiceDownException,
         InvalidSubscriberIdException {
        subscribe(topic, subscriberId, options, false);
    }

    protected void subscribe(ByteString topic, ByteString subscriberId, SubscriptionOptions options, boolean isHub)
            throws CouldNotConnectException, ClientAlreadySubscribedException, ServiceDownException,
        InvalidSubscriberIdException {
        // Validate that the format of the subscriberId is valid either as a
        // local or hub subscriber.
        if (!isValidSubscriberId(subscriberId, isHub)) {
            throw new InvalidSubscriberIdException("SubscriberId passed is not valid: " + subscriberId.toStringUtf8()
                                                   + ", isHub: " + isHub);
        }
        try {
            subUnsub(topic, subscriberId, OperationType.SUBSCRIBE, options);
        } catch (ClientNotSubscribedException e) {
            logger.error("Unexpected Exception thrown: " + e.toString());
            // This exception should never be thrown here. But just in case,
            // throw a generic ServiceDownException but wrap the original
            // Exception within it.
            throw new ServiceDownException(e);
        }
    }

    public void asyncSubscribe(ByteString topic, ByteString subscriberId, CreateOrAttach mode, Callback<Void> callback,
                               Object context) {
        SubscriptionOptions options = SubscriptionOptions.newBuilder().setCreateOrAttach(mode).build();
        asyncSubscribe(topic, subscriberId, options, callback, context, false);
    }

    public void asyncSubscribe(ByteString topic, ByteString subscriberId, SubscriptionOptions options,
                               Callback<Void> callback, Object context) {
        asyncSubscribe(topic, subscriberId, options, callback, context, false);
    }

    protected void asyncSubscribe(ByteString topic, ByteString subscriberId,
                                  SubscriptionOptions options,
                                  Callback<Void> callback, Object context, boolean isHub) {
        // Validate that the format of the subscriberId is valid either as a
        // local or hub subscriber.
        if (!isValidSubscriberId(subscriberId, isHub)) {
            callback.operationFailed(context, new ServiceDownException(new InvalidSubscriberIdException(
                                         "SubscriberId passed is not valid: " + subscriberId.toStringUtf8() + ", isHub: " + isHub)));
            return;
        }
        asyncSubUnsub(topic, subscriberId, new VoidCallbackAdapter<PubSubProtocol.ResponseBody>(callback),
            context, OperationType.SUBSCRIBE, options);
    }

    public void unsubscribe(ByteString topic, ByteString subscriberId) throws CouldNotConnectException,
        ClientNotSubscribedException, ServiceDownException, InvalidSubscriberIdException {
        unsubscribe(topic, subscriberId, false);
    }

    protected void unsubscribe(ByteString topic, ByteString subscriberId, boolean isHub)
            throws CouldNotConnectException, ClientNotSubscribedException, ServiceDownException,
        InvalidSubscriberIdException {
        // Validate that the format of the subscriberId is valid either as a
        // local or hub subscriber.
        if (!isValidSubscriberId(subscriberId, isHub)) {
            throw new InvalidSubscriberIdException("SubscriberId passed is not valid: " + subscriberId.toStringUtf8()
                                                   + ", isHub: " + isHub);
        }
        // Synchronously close the subscription on the client side. Even
        // if the unsubscribe request to the server errors out, we won't be
        // delivering messages for this subscription to the client. The client
        // can later retry the unsubscribe request to the server so they are
        // "fully" unsubscribed from the given topic.
        closeSubscription(topic, subscriberId);
        try {
            subUnsub(topic, subscriberId, OperationType.UNSUBSCRIBE, null);
        } catch (ClientAlreadySubscribedException e) {
            logger.error("Unexpected Exception thrown: " + e.toString());
            // This exception should never be thrown here. But just in case,
            // throw a generic ServiceDownException but wrap the original
            // Exception within it.
            throw new ServiceDownException(e);
        }
    }

    public void asyncUnsubscribe(final ByteString topic, final ByteString subscriberId, final Callback<Void> callback,
                                 final Object context) {
        doAsyncUnsubscribe(topic, subscriberId,
            new VoidCallbackAdapter<PubSubProtocol.ResponseBody>(callback), context, false);
    }

    protected void asyncUnsubscribe(final ByteString topic, final ByteString subscriberId,
                                    final Callback<Void> callback, final Object context, boolean isHub) {
        doAsyncUnsubscribe(topic, subscriberId,
            new VoidCallbackAdapter<PubSubProtocol.ResponseBody>(callback), context, isHub);
    }

    private void doAsyncUnsubscribe(final ByteString topic, final ByteString subscriberId,
                                    final Callback<PubSubProtocol.ResponseBody> callback,
                                    final Object context, boolean isHub) {
        // Validate that the format of the subscriberId is valid either as a
        // local or hub subscriber.
        if (!isValidSubscriberId(subscriberId, isHub)) {
            callback.operationFailed(context, new ServiceDownException(new InvalidSubscriberIdException(
                                         "SubscriberId passed is not valid: " + subscriberId.toStringUtf8() + ", isHub: " + isHub)));
            return;
        }
        // Asynchronously close the subscription. On the callback to that
        // operation once it completes, post the async unsubscribe request.
        doAsyncCloseSubscription(topic, subscriberId, new Callback<PubSubProtocol.ResponseBody>() {
            @Override
            public void operationFinished(Object ctx, PubSubProtocol.ResponseBody resultOfOperation) {
                asyncSubUnsub(topic, subscriberId, callback, context, OperationType.UNSUBSCRIBE, null);
            }

            @Override
            public void operationFailed(Object ctx, PubSubException exception) {
                callback.operationFailed(context, exception);
            }
        }, null);
    }

    // This is a helper method to determine if a subscriberId is valid as either
    // a hub or local subscriber
    private boolean isValidSubscriberId(ByteString subscriberId, boolean isHub) {
        if ((isHub && !SubscriptionStateUtils.isHubSubscriber(subscriberId))
                || (!isHub && SubscriptionStateUtils.isHubSubscriber(subscriberId)))
            return false;
        else
            return true;
    }

    public void consume(ByteString topic, ByteString subscriberId, MessageSeqId messageSeqId)
            throws ClientNotSubscribedException {
        if (logger.isDebugEnabled())
            logger.debug("Calling consume for topic: " + topic.toStringUtf8() + ", subscriberId: "
                         + subscriberId.toStringUtf8() + ", messageSeqId: " + messageSeqId);
        TopicSubscriber topicSubscriber = new TopicSubscriber(topic, subscriberId);
        // Check that this topic subscription on the client side exists.
        Channel channel = topicSubscriber2Channel.get(topicSubscriber);
        if (channel == null) {
            throw new ClientNotSubscribedException(
                "Cannot send consume message since client is not subscribed to topic: " + topic.toStringUtf8()
                + ", subscriberId: " + subscriberId.toStringUtf8());
        }
        PubSubData pubSubData = new PubSubData(topic, null, subscriberId, OperationType.CONSUME, null, null, null);
        // Send the consume message to the server using the same subscribe
        // channel that the topic subscription uses.
        doConsume(pubSubData, channel, messageSeqId);
    }

    /**
     * Convert client-side subscription options to subscription preferences
     *
     * @param options
     *          Client-Side subscription options
     */
    protected SubscriptionPreferences.Builder options2Preferences(SubscriptionOptions options) {
        // prepare subscription preferences
        SubscriptionPreferences.Builder preferencesBuilder = SubscriptionPreferences.newBuilder();

        // set message bound
        if (options.getMessageBound() > 0) {
            preferencesBuilder.setMessageBound(options.getMessageBound());
        } else if (cfg.getSubscriptionMessageBound() > 0) {
            preferencesBuilder.setMessageBound(cfg.getSubscriptionMessageBound());
        }

        // set message filter
        if (options.hasMessageFilter()) {
            preferencesBuilder.setMessageFilter(options.getMessageFilter());
        }

        // set user options
        if (options.hasOptions()) {
            preferencesBuilder.setOptions(options.getOptions());
        }

        return preferencesBuilder;
    }

    /**
     * This is a helper method to write the actual subscribe/unsubscribe message
     * once the client is connected to the server and a Channel is available.
     *
     * @param pubSubData
     *            Subscribe/Unsubscribe call's data wrapper object. We assume
     *            that the operationType field is either SUBSCRIBE or
     *            UNSUBSCRIBE.
     * @param channel
     *            Netty I/O channel for communication between the client and
     *            server
     */
    protected void doSubUnsub(PubSubData pubSubData, Channel channel) {
        // Create a PubSubRequest
        PubSubRequest.Builder pubsubRequestBuilder = PubSubRequest.newBuilder();
        pubsubRequestBuilder.setProtocolVersion(ProtocolVersion.VERSION_ONE);
        pubsubRequestBuilder.setType(pubSubData.operationType);
        if (pubSubData.triedServers != null && pubSubData.triedServers.size() > 0) {
            pubsubRequestBuilder.addAllTriedServers(pubSubData.triedServers);
        }
        long txnId = client.globalCounter.incrementAndGet();
        pubsubRequestBuilder.setTxnId(txnId);
        pubsubRequestBuilder.setShouldClaim(pubSubData.shouldClaim);
        pubsubRequestBuilder.setTopic(pubSubData.topic);

        // Create either the Subscribe or Unsubscribe Request
        if (pubSubData.operationType.equals(OperationType.SUBSCRIBE)) {
            // Create the SubscribeRequest
            SubscribeRequest.Builder subscribeRequestBuilder = SubscribeRequest.newBuilder();
            subscribeRequestBuilder.setSubscriberId(pubSubData.subscriberId);
            subscribeRequestBuilder.setCreateOrAttach(pubSubData.options.getCreateOrAttach());
            // For now, all subscribes should wait for all cross-regional
            // subscriptions to be established before returning.
            subscribeRequestBuilder.setSynchronous(true);
            // set subscription preferences
            SubscriptionPreferences.Builder preferencesBuilder =
                options2Preferences(pubSubData.options);
            // backward compatable with 4.1.0
            if (preferencesBuilder.hasMessageBound()) {
                subscribeRequestBuilder.setMessageBound(preferencesBuilder.getMessageBound());
            }
            subscribeRequestBuilder.setPreferences(preferencesBuilder);

            // Set the SubscribeRequest into the outer PubSubRequest
            pubsubRequestBuilder.setSubscribeRequest(subscribeRequestBuilder);
        } else {
            // Create the UnSubscribeRequest
            UnsubscribeRequest.Builder unsubscribeRequestBuilder = UnsubscribeRequest.newBuilder();
            unsubscribeRequestBuilder.setSubscriberId(pubSubData.subscriberId);

            // Set the UnsubscribeRequest into the outer PubSubRequest
            pubsubRequestBuilder.setUnsubscribeRequest(unsubscribeRequestBuilder);
        }

        // Update the PubSubData with the txnId and the requestWriteTime
        pubSubData.txnId = txnId;
        pubSubData.requestWriteTime = MathUtils.now();

        // Before we do the write, store this information into the
        // ResponseHandler so when the server responds, we know what
        // appropriate Callback Data to invoke for the given txn ID.
        try {
            HedwigClientImpl.getResponseHandlerFromChannel(channel).txn2PubSubData.put(txnId, pubSubData);
        } catch (Exception e) {
            logger.error("No response handler found while storing the subscribe callback.");
            // Call operationFailed on the pubsubdata callback to indicate failure
            pubSubData.getCallback().operationFailed(pubSubData.context, new CouldNotConnectException("No response " +
                    "handler found while attempting to subscribe."));
            return;
        }

        // Finally, write the Subscribe request through the Channel.
        if (logger.isDebugEnabled())
            logger.debug("Writing a SubUnsub request to host: " + HedwigClientImpl.getHostFromChannel(channel)
                         + " for pubSubData: " + pubSubData);
        ChannelFuture future = channel.write(pubsubRequestBuilder.build());
        future.addListener(new WriteCallback(pubSubData, client));
    }

    /**
     * This is a helper method to write a consume message to the server after a
     * subscribe Channel connection is made to the server and messages are being
     * consumed by the client.
     *
     * @param pubSubData
     *            Consume call's data wrapper object. We assume that the
     *            operationType field is CONSUME.
     * @param channel
     *            Netty I/O channel for communication between the client and
     *            server
     * @param messageSeqId
     *            Message Seq ID for the latest/last message the client has
     *            consumed.
     */
    public void doConsume(final PubSubData pubSubData, final Channel channel, final MessageSeqId messageSeqId) {
        // Create a PubSubRequest
        PubSubRequest.Builder pubsubRequestBuilder = PubSubRequest.newBuilder();
        pubsubRequestBuilder.setProtocolVersion(ProtocolVersion.VERSION_ONE);
        pubsubRequestBuilder.setType(OperationType.CONSUME);
        long txnId = client.globalCounter.incrementAndGet();
        pubsubRequestBuilder.setTxnId(txnId);
        pubsubRequestBuilder.setTopic(pubSubData.topic);

        // Create the ConsumeRequest
        ConsumeRequest.Builder consumeRequestBuilder = ConsumeRequest.newBuilder();
        consumeRequestBuilder.setSubscriberId(pubSubData.subscriberId);
        consumeRequestBuilder.setMsgId(messageSeqId);

        // Set the ConsumeRequest into the outer PubSubRequest
        pubsubRequestBuilder.setConsumeRequest(consumeRequestBuilder);

        // For Consume requests, we will send them from the client in a fire and
        // forget manner. We are not expecting the server to send back an ack
        // response so no need to register this in the ResponseHandler. There
        // are no callbacks to invoke since this isn't a client initiated
        // action. Instead, just have a future listener that will log an error
        // message if there was a problem writing the consume request.
        if (logger.isDebugEnabled())
            logger.debug("Writing a Consume request to host: " + HedwigClientImpl.getHostFromChannel(channel)
                         + " with messageSeqId: " + messageSeqId + " for pubSubData: " + pubSubData);
        ChannelFuture future = channel.write(pubsubRequestBuilder.build());
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    logger.error("Error writing a Consume request to host: " + HedwigClientImpl.getHostFromChannel(channel)
                                 + " with messageSeqId: " + messageSeqId + " for pubSubData: " + pubSubData);
                }
            }
        });
    }

    public boolean hasSubscription(ByteString topic, ByteString subscriberId) throws CouldNotConnectException,
        ServiceDownException {
        // The subscription type of info should be stored on the server end, not
        // the client side. Eventually, the server will have the Subscription
        // Manager part that ties into Zookeeper to manage this info.
        // Commenting out these type of API's related to that here for now until
        // this data is available on the server. Will figure out what the
        // correct way to contact the server to get this info is then.
        // The client side just has soft memory state for client subscription
        // information.
        return topicSubscriber2Channel.containsKey(new TopicSubscriber(topic, subscriberId));
    }

    public List<ByteString> getSubscriptionList(ByteString subscriberId) throws CouldNotConnectException,
        ServiceDownException {
        // Same as the previous hasSubscription method, this data should reside
        // on the server end, not the client side.
        return null;
    }

    public void startDelivery(final ByteString topic, final ByteString subscriberId, MessageHandler messageHandler)
            throws ClientNotSubscribedException, AlreadyStartDeliveryException {
        startDelivery(topic, subscriberId, messageHandler, false);
    }

    public void startDeliveryWithFilter(final ByteString topic, final ByteString subscriberId,
                                        MessageHandler messageHandler,
                                        ClientMessageFilter messageFilter)
            throws ClientNotSubscribedException, AlreadyStartDeliveryException {
        if (null == messageHandler || null == messageFilter) {
            throw new NullPointerException("Null message handler or message filter is provided.");
        }
        TopicSubscriber topicSubscriber = new TopicSubscriber(topic, subscriberId);
        SubscriptionPreferences preferences = topicSubscriber2Preferences.get(topicSubscriber);
        if (null == preferences) {
            throw new ClientNotSubscribedException("No subscription preferences found to filter messages for topic: "
                    + topic.toStringUtf8() + ", subscriberId: " + subscriberId.toStringUtf8());
        }
        // pass subscription preferences to message filter
        if (logger.isDebugEnabled()) {
            logger.debug("Start delivering messages with filter on topic: " + topic.toStringUtf8()
                         + ", subscriberId: " + subscriberId.toStringUtf8() + ", preferences: "
                         + SubscriptionStateUtils.toString(preferences));
        }
        messageFilter.setSubscriptionPreferences(topic, subscriberId, preferences);
        messageHandler = new FilterableMessageHandler(messageHandler, messageFilter);
        startDelivery(topic, subscriberId, messageHandler, false);
    }

    public void restartDelivery(final ByteString topic, final ByteString subscriberId, MessageHandler handler)
        throws ClientNotSubscribedException, AlreadyStartDeliveryException {
        // Put this message handler in the map. We do this to maintain backward compatibility as we
        // don't want to break the signature for startDelivery
        topicSubscriber2MessageHandler.put(new TopicSubscriber(topic, subscriberId), handler);
        startDelivery(topic, subscriberId, null, true);
    }

    private void startDelivery(final ByteString topic, final ByteString subscriberId,
                               MessageHandler messageHandler, boolean restart)
        throws ClientNotSubscribedException, AlreadyStartDeliveryException {
        if (logger.isDebugEnabled())
            logger.debug("Starting delivery for topic: " + topic.toStringUtf8() + ", subscriberId: "
                         + subscriberId.toStringUtf8());
        TopicSubscriber topicSubscriber = new TopicSubscriber(topic, subscriberId);
        // Make sure we know about this topic subscription on the client side
        // exists. The assumption is that the client should have in memory the
        // Channel created for the TopicSubscriber once the server has sent
        // an ack response to the initial subscribe request.
        Channel topicSubscriberChannel = topicSubscriber2Channel.get(topicSubscriber);
        if (topicSubscriberChannel == null) {
            logger.error("Client is not yet subscribed to topic: " + topic.toStringUtf8() + ", subscriberId: "
                         + subscriberId.toStringUtf8());
            throw new ClientNotSubscribedException("Client is not yet subscribed to topic: " + topic.toStringUtf8()
                                                   + ", subscriberId: " + subscriberId.toStringUtf8());
        }

        // Need to ensure the setting of handler and the readability of channel is in sync
        // as there's a race condition that connection recovery and user might call this at the same time
        MessageHandler existedMsgHandler = topicSubscriber2MessageHandler.get(topicSubscriber);
        if (restart) {
            // restart using existing msg handler
            messageHandler = existedMsgHandler;
        } else {
            // some has started delivery but not stop it
            if (null != existedMsgHandler) {
                throw new AlreadyStartDeliveryException("A message handler has been started for topic subscriber " + topicSubscriber);
            }
            if (messageHandler != null) {
                if (null != topicSubscriber2MessageHandler.putIfAbsent(topicSubscriber, messageHandler)) {
                    throw new AlreadyStartDeliveryException("Someone is also starting delivery for topic subscriber " + topicSubscriber);
                }
            }
        }
        try {
            HedwigClientImpl.getResponseHandlerFromChannel(topicSubscriberChannel).getSubscribeResponseHandler()
            .setMessageHandler(messageHandler);
        } catch (NoResponseHandlerException e) {
            // We did not find a response handler. So remove this subscription handler and throw an exception.
            topicSubscriber2MessageHandler.remove(topicSubscriber, existedMsgHandler);
            asyncCloseSubscription(topic, subscriberId, new Callback<Void>() {
                @Override
                public void operationFinished(Object ctx, Void resultOfOperation) {
                    logger.warn("Closed subscription for topic " + topic.toStringUtf8() + " and subscriber " +
                    subscriberId.toStringUtf8());
                }

                @Override
                public void operationFailed(Object ctx, PubSubException exception) {
                    logger.warn("Error while closing subscription for topic " + topic.toStringUtf8() + " and subscriber " +
                            subscriberId.toStringUtf8());
                }
            }, null);

            // We should tell the client to resubscribe.
            throw new ClientNotSubscribedException("Closed subscription for topic " + topic.toStringUtf8() + " and" +
                    "subscriber Id "  + subscriberId.toStringUtf8());
        }
        // Now make the TopicSubscriber Channel readable (it is set to not be
        // readable when the initial subscription is done). Note that this is an
        // asynchronous call. If this fails (not likely), the futureListener
        // will just log an error message for now.
        ChannelFuture future = topicSubscriberChannel.setReadable(true);
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    logger.error("Unable to make subscriber Channel readable in startDelivery call for topic: "
                                 + topic.toStringUtf8() + ", subscriberId: " + subscriberId.toStringUtf8());
                }
            }
        });
    }

    public void stopDelivery(final ByteString topic, final ByteString subscriberId) throws ClientNotSubscribedException {
        if (logger.isDebugEnabled())
            logger.debug("Stopping delivery for topic: " + topic.toStringUtf8() + ", subscriberId: "
                         + subscriberId.toStringUtf8());
        TopicSubscriber topicSubscriber = new TopicSubscriber(topic, subscriberId);
        // Make sure we know that this topic subscription on the client side
        // exists. The assumption is that the client should have in memory the
        // Channel created for the TopicSubscriber once the server has sent
        // an ack response to the initial subscribe request.
        Channel topicSubscriberChannel = topicSubscriber2Channel.get(topicSubscriber);
        if (topicSubscriberChannel == null) {
            logger.error("Client is not yet subscribed to topic: " + topic.toStringUtf8() + ", subscriberId: "
                         + subscriberId.toStringUtf8());
            throw new ClientNotSubscribedException("Client is not yet subscribed to topic: " + topic.toStringUtf8()
                                                   + ", subscriberId: " + subscriberId.toStringUtf8());
        }

        // Unregister the MessageHandler for the subscribe Channel's
        // Response Handler.
        try {
            HedwigClientImpl.getResponseHandlerFromChannel(topicSubscriberChannel).getSubscribeResponseHandler()
                .setMessageHandler(null);
        } catch (NoResponseHandlerException e) {
            // Here it's okay if we can't set the response handler's message handler to null. We should just remove it.
            logger.warn("Could not set message handler to null for subscription channel " + topicSubscriberChannel + ", ignoring.");
        }
        this.topicSubscriber2MessageHandler.remove(topicSubscriber);
        // Now make the TopicSubscriber channel not-readable. This will buffer
        // up messages if any are sent from the server. Note that this is an
        // asynchronous call. If this fails (not likely), the futureListener
        // will just log an error message for now.
        ChannelFuture future = topicSubscriberChannel.setReadable(false);
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    logger.error("Unable to make subscriber Channel not readable in stopDelivery call for topic: "
                                 + topic.toStringUtf8() + ", subscriberId: " + subscriberId.toStringUtf8());
                }
            }
        });
    }

    public void closeSubscription(ByteString topic, ByteString subscriberId) throws ServiceDownException {
        PubSubData pubSubData = new PubSubData(topic, null, subscriberId, null, null, null, null);
        synchronized (pubSubData) {
            PubSubCallback pubSubCallback = new PubSubCallback(pubSubData);
            doAsyncCloseSubscription(topic, subscriberId, pubSubCallback, null);
            try {
                while (!pubSubData.isDone)
                    pubSubData.wait();
            } catch (InterruptedException e) {
                throw new ServiceDownException("Interrupted Exception while waiting for asyncCloseSubscription call");
            }
            // Check from the PubSubCallback if it was successful or not.
            if (!pubSubCallback.getIsCallSuccessful()) {
                throw new ServiceDownException("Exception while trying to close the subscription for topic: "
                                               + topic.toStringUtf8() + ", subscriberId: " + subscriberId.toStringUtf8());
            }
        }
    }

    public void asyncCloseSubscription(final ByteString topic, final ByteString subscriberId,
                                       final Callback<Void> callback, final Object context) {
        doAsyncCloseSubscription(topic, subscriberId,
            new VoidCallbackAdapter<PubSubProtocol.ResponseBody> (callback), context);
    }

    private void doAsyncCloseSubscription(final ByteString topic, final ByteString subscriberId,
                                       final Callback<PubSubProtocol.ResponseBody> callback, final Object context) {
        if (logger.isDebugEnabled())
            logger.debug("Closing subscription asynchronously for topic: " + topic.toStringUtf8() + ", subscriberId: "
                         + subscriberId.toStringUtf8());
        // First stop delivery for this subscription. In case this subscription is started again, we don't want to find
        // an existing message handler to stop us from subscribing. Unsubscribing from a topic also invokes
        // this method, so it's better to do this here. Moreover, close subscription should leave us in a state
        // where a fresh subscribe request can succeed.
        try {
            stopDelivery(topic, subscriberId);
        } catch (ClientNotSubscribedException e) {
            logger.error("Tried to stop delivery for topic: " + topic.toStringUtf8() + " and subscriber: "
                    + subscriberId.toStringUtf8() + ", but we were not subscribed.");
        }
        TopicSubscriber topicSubscriber = new TopicSubscriber(topic, subscriberId);
        // Remove all cached references for the TopicSubscriber
        Channel channel = topicSubscriber2Channel.remove(topicSubscriber);
        if (channel != null) {
            // Close the subscribe channel asynchronously.
            try {
                HedwigClientImpl.getResponseHandlerFromChannel(channel).handleChannelClosedExplicitly();
            } catch (NoResponseHandlerException e) {
                // Don't close the channel if you can't find the handler.
                logger.warn("No response handler found, so could not close subscription channel " + channel);
            }
            // We still close the channel as this is an unexpected event and should be handled as one.
            ChannelFuture future = channel.close();
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        logger.error("Failed to close the subscription channel for topic: " + topic.toStringUtf8()
                                     + ", subscriberId: " + subscriberId.toStringUtf8());
                        callback.operationFailed(context, new ServiceDownException(
                                                     "Failed to close the subscription channel for topic: " + topic.toStringUtf8()
                                                     + ", subscriberId: " + subscriberId.toStringUtf8()));
                    } else {
                        callback.operationFinished(context, null);
                    }
                }
            });
        } else {
            logger.warn("Trying to close a subscription when we don't have a subscribe channel cached for topic: "
                        + topic.toStringUtf8() + ", subscriberId: " + subscriberId.toStringUtf8());
            callback.operationFinished(context, null);
        }
    }

    // Public getter and setters for entries in the topic2Host Map.
    // This is used for classes that need this information but are not in the
    // same classpath.
    public Channel getChannelForTopic(TopicSubscriber topic) {
        return topicSubscriber2Channel.get(topic);
    }

    public void setChannelAndPreferencesForTopic(TopicSubscriber topic, Channel channel,
                                                 SubscriptionPreferences preferences) {
        synchronized (closeLock) {
            if (closed) {
                channel.close();
                return;
            }
            Channel oldc = topicSubscriber2Channel.putIfAbsent(topic, channel);
            if (oldc != null) {
                logger.warn("Dropping new channel for " + topic + ", due to existing channel: " + oldc);
                channel.close();
            }
            if (null != preferences) {
                topicSubscriber2Preferences.put(topic, preferences);
            }
        }
    }

    public void removeTopicSubscriber(TopicSubscriber topic) {
        synchronized (topic) {
            topicSubscriber2Preferences.remove(topic);
            topicSubscriber2Channel.remove(topic);
        }
    }

    void close() {
        synchronized (closeLock) {
            closed = true;
        }

        // Close all of the open Channels.
        for (Channel channel : topicSubscriber2Channel.values()) {
            try {
                client.getResponseHandlerFromChannel(channel).handleChannelClosedExplicitly();
            } catch (NoResponseHandlerException e) {
                logger.error("No response handler found while trying to close subscription channel.");
            }
            channel.close().awaitUninterruptibly();
        }
        topicSubscriber2Channel.clear();
    }
}
