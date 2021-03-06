/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.bookkeeper.bookie;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.util.concurrent.UncheckedExecutionException;

import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.meta.ActiveLedgerManager;
import org.apache.bookkeeper.bookie.LedgerDirsManager.LedgerDirsListener;
import org.apache.bookkeeper.bookie.LedgerDirsManager.NoWritableLedgerDirException;
import org.apache.bookkeeper.stats.BookkeeperServerStatsLogger;
import org.apache.bookkeeper.stats.BookkeeperServerStatsLogger.BookkeeperServerCounter;
import org.apache.bookkeeper.stats.Gauge;
import org.apache.bookkeeper.stats.ServerStatsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class IndexPersistenceMgr {
    private final static Logger LOG = LoggerFactory.getLogger(IndexPersistenceMgr.class);

    final RemovalListener<Long, FileInfo> fileInfoEvictionListener =
        new RemovalListener<Long, FileInfo>() {
            @Override
            public void onRemoval(RemovalNotification<Long, FileInfo> notification) {
                if (notification.wasEvicted()) {
                    FileInfo fileInfo = notification.getValue();
                    if (null == fileInfo) {
                        return;
                    }
                    ServerStatsProvider.getStatsLoggerInstance().getCounter(
                            BookkeeperServerCounter.LEDGER_CACHE_NUM_EVICTED_LEDGERS).inc();
                    try {
                        fileInfo.close(true);
                        numOpenLedgers.decrementAndGet();
                        LOG.info("Ledger {} is evicted from file info cache.",
                                 notification.getKey());
                    } catch (IOException ie) {
                        LOG.error("Exception when ledger {} is evicted from file info cache.", ie);
                    }
                }
            }
        };

    final Cache<Long, FileInfo> fileInfoCache;
    final AtomicInteger numOpenLedgers = new AtomicInteger(0);
    final ReentrantReadWriteLock closeLock = new ReentrantReadWriteLock();

    final int openFileLimit;
    final int pageSize;
    final int entriesPerPage;

    // Manage all active ledgers in LedgerManager
    // so LedgerManager has knowledge to garbage collect inactive/deleted ledgers
    final ActiveLedgerManager activeLedgerManager;
    private LedgerDirsManager ledgerDirsManager;

    public IndexPersistenceMgr (int pageSize,
                                int entriesPerPage,
                                ServerConfiguration conf,
                                ActiveLedgerManager activeLedgerManager,
                                LedgerDirsManager ledgerDirsManager) throws IOException {
        this.openFileLimit = conf.getOpenFileLimit();
        this.activeLedgerManager = activeLedgerManager;
        this.ledgerDirsManager = ledgerDirsManager;
        this.pageSize = pageSize;
        this.entriesPerPage = entriesPerPage;
        // Retrieve all of the active ledgers.
        getActiveLedgers();
        ledgerDirsManager.addLedgerDirsListener(getLedgerDirsListener());

        // build the file info cache
        CacheBuilder<Long, FileInfo> fileInfoCacheBuilder = CacheBuilder.newBuilder()
            .concurrencyLevel(Math.max(1, Math.max(conf.getNumAddWorkerThreads(), conf.getNumReadWorkerThreads())))
            .initialCapacity(conf.getFileInfoCacheInitialCapacity())
            .maximumSize(openFileLimit)
            .removalListener(fileInfoEvictionListener);
        if (conf.getFileInfoMaxIdleTime() > 0) {
            fileInfoCacheBuilder.expireAfterAccess(conf.getFileInfoMaxIdleTime(), TimeUnit.SECONDS);
        }
        fileInfoCache = fileInfoCacheBuilder.build();

        LOG.info("openFileLimit is {}.", openFileLimit);

        ServerStatsProvider.getStatsLoggerInstance().registerGauge(
                BookkeeperServerStatsLogger.BookkeeperServerGauge.NUM_OPEN_LEDGERS,
                new Gauge<Integer>() {
                    @Override
                    public Integer getDefaultValue() {
                        return 0;
                    }

                    @Override
                    public Integer getSample() {
                        return numOpenLedgers.get();
                    }
                }
        );
    }

    void removeFileInfo(long ledger) {
        closeLock.readLock().lock();
        try {
            fileInfoCache.invalidate(ledger);
        } finally {
            closeLock.readLock().unlock();
        }
    }

    /**
     * Handle IOException thrown on getting file info. If <i>masterKey</i> isn't null and NoLedgerException encountered,
     * we need to create a new file info with current <i>masterKey</i>.
     *
     * @param ledger
     *          ledger id.
     * @param masterKey
     *          master key to create this ledger
     * @param ioe
     *          io exception thrown on getting file info.
     * @return file info.
     * @throws IOException
     */
    private FileInfo handleIOExceptionOnGetFileInfo(final Long ledger, byte[] masterKey, IOException ioe)
            throws IOException {
        if (null == masterKey || !(ioe instanceof Bookie.NoLedgerException)) {
            throw ioe;
        }

        // We don't have a ledger index file on disk, so create it.
        File lf = getNewLedgerIndexFile(ledger, null);
        FileInfo fi = new FileInfo(lf, masterKey);
        FileInfo oldFi = fileInfoCache.asMap().putIfAbsent(ledger, fi);
        if (null != oldFi) {
            fi = oldFi;
        } else {
            // A new ledger index file has been created for this Bookie.
            LOG.debug("New ledger index file created for ledgerId: {}", ledger);
            activeLedgerManager.addActiveLedger(ledger, true);
            numOpenLedgers.incrementAndGet();
        }
        fi.use();
        return fi;
    }

    FileInfo getFileInfo(final Long ledger, final byte masterKey[]) throws IOException {
        FileInfo fi;
        closeLock.readLock().lock();
        try {
            fi = fileInfoCache.get(ledger, new Callable<FileInfo>() {
                @Override
                public FileInfo call() throws IOException {
                    // Check if the index file exists on disk.
                    File lf = findIndexFile(ledger);
                    if (null == lf) {
                        throw new Bookie.NoLedgerException(ledger);
                    }
                    FileInfo fi = new FileInfo(lf, masterKey);
                    numOpenLedgers.incrementAndGet();
                    return fi;
                }
            });
            fi.use();
            return fi;
        } catch (ExecutionException ee) {
            if (ee.getCause() instanceof IOException) {
                return handleIOExceptionOnGetFileInfo(ledger, masterKey, (IOException) ee.getCause());
            } else {
                throw new IOException("Failed to load file info for ledger " + ledger, ee);
            }
        } catch (UncheckedExecutionException uee) {
            if (uee.getCause() instanceof IOException) {
                return handleIOExceptionOnGetFileInfo(ledger, masterKey, (IOException) uee.getCause());
            } else {
                throw new IOException("Failed to load file info for ledger " + ledger, uee);
            }
        } finally {
            closeLock.readLock().unlock();
        }
    }

    private File getNewLedgerIndexFile(Long ledger, File dirExcl) throws NoWritableLedgerDirException {
        File dir = ledgerDirsManager.pickRandomWritableDir(dirExcl);
        String ledgerName = LedgerCacheImpl.getLedgerName(ledger);
        return new File(dir, ledgerName);
    }

    /**
     * This method will look within the ledger directories for the ledger index
     * files. That will comprise the set of active ledgers this particular
     * BookieServer knows about that have not yet been deleted by the BookKeeper
     * Client. This is called only once during initialization.
     */
    private void getActiveLedgers() throws IOException {
        // Ledger index files are stored in a file hierarchy with a parent and
        // grandParent directory. We'll have to go two levels deep into these
        // directories to find the index files.
        for (File ledgerDirectory : ledgerDirsManager.getAllLedgerDirs()) {
            for (File grandParent : ledgerDirectory.listFiles()) {
                if (grandParent.isDirectory()) {
                    for (File parent : grandParent.listFiles()) {
                        if (parent.isDirectory()) {
                            for (File index : parent.listFiles()) {
                                if (!index.isFile() ||
                                        (!index.getName().endsWith(LedgerCacheImpl.IDX) &&
                                                !index.getName().endsWith(LedgerCacheImpl.RLOC))) {
                                    continue;
                                }
                                // We've found a ledger index file. The file
                                // name is the HexString representation of the
                                // ledgerId.
                                String ledgerIdInHex = index.getName().replace(LedgerCacheImpl.RLOC, "")
                                        .replace(LedgerCacheImpl.IDX, "");
                                if (index.getName().endsWith(LedgerCacheImpl.RLOC)) {
                                    if (findIndexFile(Long.parseLong(ledgerIdInHex)) != null) {
                                        if (!index.delete()) {
                                            LOG.warn("Deleting the rloc file " + index + " failed");
                                        }
                                        continue;
                                    } else {
                                        File dest = new File(index.getParentFile(), ledgerIdInHex + LedgerCacheImpl.IDX);
                                        if (!index.renameTo(dest)) {
                                            throw new IOException("Renaming rloc file " + index
                                                    + " to index file has failed");
                                        }
                                    }
                                }
                                activeLedgerManager.addActiveLedger(Long.parseLong(ledgerIdInHex, 16), true);
                            }
                        }
                    }
                }
            }
        }
    }

    public void removeLedger(long ledgerId) throws IOException {
        // Delete the ledger's index file and close the FileInfo
        FileInfo fi = null;
        try {
            fi = getFileInfo(ledgerId, null);
            fi.close(false);
            fi.delete();
        } finally {
            // should release use count
            // otherwise the file channel would not be closed.
            if (null != fi) {
                fi.release();
            }
        }

        // Remove it from the active ledger manager
        activeLedgerManager.removeActiveLedger(ledgerId);
        // Now remove it from all the other lists and maps.
        removeFileInfo(ledgerId);
    }

    @VisibleForTesting
    File findIndexFile(long ledgerId) throws IOException {
        String ledgerName = LedgerCacheImpl.getLedgerName(ledgerId);
        for(File d: ledgerDirsManager.getAllLedgerDirs()) {
            File lf = new File(d, ledgerName);
            if (lf.exists()) {
                return lf;
            }
        }
        return null;
    }

    public boolean ledgerExists(long ledgerId) throws IOException {
        FileInfo fi = null;
        try {
            fi = getFileInfo(ledgerId, null);
            return true;
        } catch (Bookie.NoLedgerException nle) {
            return false;
        } finally {
            if (null != fi) {
                fi.release();
            }
        }
    }

    public int getNumOpenLedgers() {
        return numOpenLedgers.get();
    }

    public void close() throws IOException {
        closeLock.writeLock().lock();
        try {
            ConcurrentMap<Long, FileInfo> fileInfos = fileInfoCache.asMap();
            for (Map.Entry<Long, FileInfo> entry : fileInfos.entrySet()) {
                // we don't need to force a ledger index to be created, we just need to close
                // those opened file channels
                // the ledger index creation would be handle by SyncThread and Eviction only.
                entry.getValue().close(false);
            }
        } finally {
            closeLock.writeLock().unlock();
        }
    }

    Long getLastAddConfirmed(long ledgerId) throws IOException {
        FileInfo fi = null;
        try {
            fi = getFileInfo(ledgerId, null);
            return fi.getLastAddConfirmed();
        } finally {
            if (null != fi) {
                fi.release();
            }
        }
    }

    Observable waitForLastAddConfirmedUpdate(long ledgerId, long previoisLAC, Observer observer) throws IOException {
        FileInfo fi = null;
        try {
            fi = getFileInfo(ledgerId, null);
            return fi.waitForLastAddConfirmedUpdate(previoisLAC, observer);
        } finally {
            if (null != fi) {
                fi.release();
            }
        }
    }

    long updateLastAddConfirmed(long ledgerId, long lac) throws IOException {
        FileInfo fi = null;
        try {
            fi = getFileInfo(ledgerId, null);
            return fi.setLastAddConfirmed(lac);
        } finally {
            if (null != fi) {
                fi.release();
            }
        }
    }

    public byte[] readMasterKey(long ledgerId) throws IOException, BookieException {
        FileInfo fi = null;
        try {
            fi = getFileInfo(ledgerId, null);
            return fi.getMasterKey();
        } finally {
            if (null != fi) {
                fi.release();
            }
        }
    }

    public void setMasterKey(long ledgerId, byte[] masterKey) throws IOException {
        FileInfo fi = null;
        try {
            fi = getFileInfo(ledgerId, masterKey);
        } finally {
            if (null != fi) {
                fi.release();
            }
        }
    }

    boolean setFenced(long ledgerId) throws IOException {
        FileInfo fi = null;
        try {
            fi = getFileInfo(ledgerId, null);
            return fi.setFenced();
        } finally {
            if (null != fi) {
                fi.release();
            }
        }
    }

    boolean isFenced(long ledgerId) throws IOException {
        FileInfo fi = null;
        try {
            fi = getFileInfo(ledgerId, null);
            return fi.isFenced();
        } finally {
            if (null != fi) {
                fi.release();
            }
        }
    }

    public int getOpenFileLimit() {
        return openFileLimit;
    }

    private LedgerDirsListener getLedgerDirsListener() {
        return new LedgerDirsListener() {
            @Override
            public void diskFull(File disk) {
                // Nothing to handle here. Will be handled in Bookie
            }

            @Override
            public void diskAlmostFull(File disk) {
                // Nothing to handle here. Will be handled in Bookie
            }

            @Override
            public void diskFailed(File disk) {
                // Nothing to handle here. Will be handled in Bookie
            }

            @Override
            public void allDisksFull() {
                // Nothing to handle here. Will be handled in Bookie
            }

            @Override
            public void fatalError() {
                // Nothing to handle here. Will be handled in Bookie
            }

            @Override
            public void diskWritable(File disk) {
                // Nothing to handle here. Will be handled in Bookie
            }

            @Override
            public void diskJustWritable(File disk) {
                // Nothing to handle here. Will be handled in Bookie
            }
        };
    }

    private void relocateIndexFileAndFlushHeader(long ledger, FileInfo fi) throws IOException {
        File currentDir = getLedgerDirForLedger(fi);
        if (ledgerDirsManager.isDirFull(currentDir)) {
            moveLedgerIndexFile(ledger, fi);
        }
        fi.flushHeader();
    }

    /**
     * Get the ledger directory that the ledger index belongs to.
     *
     * @param fi File info of a ledger
     * @return ledger directory that the ledger belongs to.
     */
    private File getLedgerDirForLedger(FileInfo fi) {
        return fi.getLf().getParentFile().getParentFile().getParentFile();
    }

    private void moveLedgerIndexFile(Long l, FileInfo fi) throws NoWritableLedgerDirException, IOException {
        File newLedgerIndexFile = getNewLedgerIndexFile(l, getLedgerDirForLedger(fi));
        fi.moveToNewLocation(newLedgerIndexFile, fi.getSizeSinceLastwrite());
    }

    /**
     * flush ledger index header, if necessary
     */
    void flushLedgerHeader(long ledger) throws IOException {
        FileInfo fi = null;
        try {
            fi = getFileInfo(ledger, null);
            relocateIndexFileAndFlushHeader(ledger, fi);
        } catch (Bookie.NoLedgerException nle) {
            // ledger has been deleted
            LOG.info("No ledger {} found when flushing header.", ledger);
            return;
        } finally {
            if (null != fi) {
                fi.release();
            }
        }
    }

    public void flushLedgerEntries(Long ledger,
                                   List<LedgerEntryPage> entries) throws IOException {
        FileInfo fi = null;
        try {
            //TODO(Aniruddha): Move this comparator to a better place.
            Collections.sort(entries, new Comparator<LedgerEntryPage>() {
                @Override
                public int compare(LedgerEntryPage o1, LedgerEntryPage o2) {
                    return (int) (o1.getFirstEntry() - o2.getFirstEntry());
                }
            });

            //ArrayList<Integer> versions = new ArrayList<Integer>(entries.size());
            int[] versions = new int[entries.size()];
            try {
                fi = getFileInfo(ledger, null);
            } catch (Bookie.NoLedgerException nle) {
                // ledger has been deleted
                LOG.info("No ledger {} found when flushing entries.", ledger);
                return;
            }

            // flush the header if necessary
            relocateIndexFileAndFlushHeader(ledger, fi);
            int start = 0;
            long lastOffset = -1;
            for(int i = 0; i < entries.size(); i++) {
                versions[i] = entries.get(i).getVersion();
                if (lastOffset != -1 && (entries.get(i).getFirstEntry() - lastOffset) != entriesPerPage) {
                    // send up a sequential list
                    int count = i - start;
                    if (count == 0) {
                        LOG.warn("Count cannot possibly be zero!");
                    }
                    writeBuffers(ledger, entries, fi, start, count);
                    start = i;
                }
                lastOffset = entries.get(i).getFirstEntry();
            }
            if (entries.size()-start == 0 && entries.size() != 0) {
                LOG.warn("Nothing to write, but there were entries!");
            }
            writeBuffers(ledger, entries, fi, start, entries.size()-start);
            for(int i = 0; i < entries.size(); i++) {
                LedgerEntryPage lep = entries.get(i);
                lep.setClean(versions[i]);
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Flushed ledger {} with {} pages.", ledger, entries.size());
            }
        } finally {
            if (fi != null) {
                fi.release();
            }
        }
    }

    private void writeBuffers(Long ledger,
                              List<LedgerEntryPage> entries, FileInfo fi,
                              int start, int count) throws IOException {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Writing " + count + " buffers of " + Long.toHexString(ledger));
        }
        if (count == 0) {
            return;
        }
        ByteBuffer buffs[] = new ByteBuffer[count];
        for(int j = 0; j < count; j++) {
            buffs[j] = entries.get(start+j).getPageToWrite();
            if (entries.get(start+j).getLedger() != ledger) {
                throw new IOException("Writing to " + ledger + " but page belongs to "
                    + entries.get(start+j).getLedger());
            }
        }
        long totalWritten = 0;
        while(buffs[buffs.length-1].remaining() > 0) {
            long rc = fi.write(buffs, entries.get(start+0).getFirstEntry()*8);
            if (rc <= 0) {
                throw new IOException("Short write to ledger " + ledger + " rc = " + rc);
            }
            totalWritten += rc;
        }
        if (totalWritten != (long)count * (long)pageSize) {
            throw new IOException("Short write to ledger " + ledger + " wrote " + totalWritten
                + " expected " + count * pageSize);
        }
    }

    public void updatePage(LedgerEntryPage lep) throws IOException {
        if (!lep.isClean()) {
            throw new IOException("Trying to update a dirty page");
        }
        FileInfo fi = null;
        try {
            fi = getFileInfo(lep.getLedger(), null);
            long pos = lep.getFirstEntryPosition();
            if (pos >= fi.size()) {
                lep.zeroPage();
            } else {
                lep.readPage(fi);
            }
        } finally {
            if (fi != null) {
                fi.release();
            }
        }
    }

    public long getPersistEntryBeyondInMem (long ledgerId, long lastEntryInMem) throws IOException {
        FileInfo fi = null;
        long lastEntry = lastEntryInMem;
        try {
            fi = getFileInfo(ledgerId, null);
            long size = fi.size();
            // make sure the file size is aligned with index entry size
            // otherwise we may read incorret data
            if (0 != size % LedgerEntryPage.getIndexEntrySize()) {
                LOG.warn("Index file of ledger {} is not aligned with index entry size.", ledgerId);
                size = size - size % LedgerEntryPage.getIndexEntrySize();
            }
            // we may not have the last entry in the cache
            if (size > lastEntryInMem*LedgerEntryPage.getIndexEntrySize()) {
                ByteBuffer bb = ByteBuffer.allocate(pageSize);
                long position = size - pageSize;
                if (position < 0) {
                    position = 0;
                }
                // we read the last page from file size minus page size, so it should not encounter short read
                // exception. if it does, it is an unexpected situation, then throw the exception and fail it immediately.
                try {
                    fi.read(bb, position, false);
                } catch (ShortReadException sre) {
                    // throw a more meaningful exception with ledger id
                    throw new ShortReadException("Short read on ledger " + ledgerId + " : ", sre);
                }
                bb.flip();
                long startingEntryId = position/LedgerEntryPage.getIndexEntrySize();
                for(int i = entriesPerPage-1; i >= 0; i--) {
                    if (bb.getLong(i*LedgerEntryPage.getIndexEntrySize()) != 0) {
                        if (lastEntry < startingEntryId+i) {
                            lastEntry = startingEntryId+i;
                        }
                        break;
                    }
                }
            }
        } finally {
            if (fi != null) {
                fi.release();
            }
        }
        return lastEntry;
    }
}
