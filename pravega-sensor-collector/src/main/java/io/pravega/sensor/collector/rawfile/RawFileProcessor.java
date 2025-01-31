/**
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.sensor.collector.rawfile;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.io.CountingInputStream;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.pravega.client.EventStreamClientFactory;
import io.pravega.client.stream.EventWriterConfig;
import io.pravega.client.stream.TxnFailedException;
import io.pravega.client.stream.impl.ByteArraySerializer;
import io.pravega.sensor.collector.util.EventWriter;
import io.pravega.sensor.collector.util.PersistentId;
import io.pravega.sensor.collector.util.TransactionCoordinator;

/**
 * Get list of files obtained from config. Process each file for ingestion.
 * Keep track of new files and delete ingested files if "DELETE_COMPLETED_FILES"=true. 
 */
public class RawFileProcessor {
    private static final Logger log = LoggerFactory.getLogger(RawFileIngestService.class);
    
    private final RawFileConfig config;
    private final RawFileState state;
    private final EventWriter<byte[]> writer;
    private final TransactionCoordinator transactionCoordinator;
    private final EventGenerator eventGenerator;

    public RawFileProcessor(RawFileConfig config, RawFileState state, EventWriter<byte[]> writer, TransactionCoordinator transactionCoordinator, EventGenerator eventGenerator) {
        this.config = config;
        this.state = state;
        this.writer = writer;
        this.transactionCoordinator = transactionCoordinator;
        this.eventGenerator = eventGenerator;
    }

    public static RawFileProcessor create(RawFileConfig config, EventStreamClientFactory clientFactory){
        final Connection connection = RawFileState.createDatabase(config.stateDatabaseFileName);

        final String writerId = new PersistentId(connection).getPersistentId().toString();
        log.info("Writer ID: {}", writerId);

        final EventWriter<byte[]> writer = EventWriter.create(
                clientFactory,
                writerId,
                config.streamName,
                new ByteArraySerializer(),
                EventWriterConfig.builder()
                        .enableConnectionPooling(false)
                        .transactionTimeoutTime((long) (config.transactionTimeoutMinutes * 60.0 * 1000.0))
                        .build(),
                config.exactlyOnce);

        final TransactionCoordinator transactionCoordinator = new TransactionCoordinator(connection, writer);
        transactionCoordinator.performRecovery();

        final EventGenerator eventGenerator = EventGenerator.create(
                config.routingKey,
                config.eventTemplateStr,
                writerId);
        final RawFileState state = new RawFileState(connection, transactionCoordinator);
        return new RawFileProcessor(config, state, writer, transactionCoordinator, eventGenerator);
    }

    public void ingestRawFiles() throws Exception {
        log.trace("ingestRawFiles: BEGIN");
        // delete leftover completed files
        if (config.enableDeleteCompletedFiles) {
            deleteCompletedFiles();
        }
        findAndRecordNewFiles();
        processNewFiles();
        log.trace("ingestRawFiles: END");
    }

    public void processNewFiles() throws Exception {
        for (;;) {
            final Pair<FileNameWithOffset, Long> nextFile = state.getNextPendingFile();
            if (nextFile == null) {
                log.trace("No more files to ingest");
                break;
            } else {
                processFile(nextFile.getLeft(), nextFile.getRight());
            }
        }
    }

    protected void findAndRecordNewFiles() throws Exception {
        final List<FileNameWithOffset> directoryListing = getDirectoryListing();
        final List<FileNameWithOffset> completedFiles = state.getCompletedFiles();
        final List<FileNameWithOffset> newFiles = getNewFiles(directoryListing, completedFiles);
        state.addPendingFiles(newFiles);
    }

    /**
     * @return list of file name and file size in bytes
     */
    protected List<FileNameWithOffset> getDirectoryListing() throws IOException {
        log.trace("getDirectoryListing: fileSpec={}", config.fileSpec);
        final List<FileNameWithOffset> directoryListing = getDirectoryListing(config.fileSpec, config.fileExtension);
        log.trace("getDirectoryListing: directoryListing={}", directoryListing);
        return directoryListing;
    }

    /**
     * @return list of file name and file size in bytes
     */
    static protected List<FileNameWithOffset> getDirectoryListing(String fileSpec, String fileExtension) throws IOException {
        final Path pathSpec = Paths.get(fileSpec);
        List<FileNameWithOffset> directoryListing = new ArrayList<>();
        try(DirectoryStream<Path> dirStream=Files.newDirectoryStream(pathSpec)){
            for(Path path: dirStream){
                if(Files.isDirectory(path))         
                    directoryListing.addAll(getDirectoryListing(path.toString(), fileExtension));
                else {
                    FileNameWithOffset fileEntry = new FileNameWithOffset(path.toAbsolutePath().toString(), path.toFile().length());
                    // If extension is null, ingest all files 
                    if(fileExtension.isEmpty() || fileExtension.equals(fileEntry.fileName.substring(fileEntry.fileName.lastIndexOf(".")+1)))
                        directoryListing.add(fileEntry);            
                }
            }
        }
        return directoryListing;
    }

    /**
     * @return sorted list of file name and file size in bytes
     */
    static protected List<FileNameWithOffset> getNewFiles(List<FileNameWithOffset> directoryListing, List<FileNameWithOffset> completedFiles) {
        final ArrayList<FileNameWithOffset> sortedDirectoryListing = new ArrayList<>(directoryListing);
        Collections.sort(sortedDirectoryListing);
        final List<FileNameWithOffset> newFiles = new ArrayList<>();
        final Set<FileNameWithOffset> setCompletedFiles = new HashSet<>(completedFiles);
        log.trace("setCompletedFiles={}", setCompletedFiles);
        sortedDirectoryListing.forEach(dirFile -> {
            if (!setCompletedFiles.contains(dirFile)) {
                newFiles.add(new FileNameWithOffset(dirFile.fileName, 0));
            }
        });
        if(!newFiles.isEmpty())
            log.info("{} New file(s) = {}", newFiles.size(), newFiles);
        return newFiles;
    }


    void processFile(FileNameWithOffset fileNameWithBeginOffset, long firstSequenceNumber) throws Exception {
        log.info("processFile: Ingesting file {}; beginOffset={}, firstSequenceNumber={}",
                fileNameWithBeginOffset.fileName, fileNameWithBeginOffset.offset, firstSequenceNumber);
        
        AtomicLong numofbytes = new AtomicLong(0);
        long timestamp = System.nanoTime();

        // In case a previous iteration encountered an error, we need to ensure that
        // previous flushed transactions are committed and any unflushed transactions as aborted.
        transactionCoordinator.performRecovery();
        writer.abort();

        try (final InputStream inputStream = new FileInputStream(fileNameWithBeginOffset.fileName)) {
            try(final CountingInputStream countingInputStream = new CountingInputStream(inputStream)) {
                countingInputStream.skip(fileNameWithBeginOffset.offset);
                final Pair<Long,Long> result = eventGenerator.generateEventsFromInputStream(countingInputStream, firstSequenceNumber,
                        e -> {
                            log.trace("processFile: event={}", e);
                            try {
                                writer.writeEvent(e.routingKey, e.bytes);
                                numofbytes.addAndGet(e.bytes.length);

                            } catch (TxnFailedException ex) {
                                throw new RuntimeException(ex);
                            }
                        });
                final Optional<UUID> txnId = writer.flush();
                final long nextSequenceNumber = result.getLeft();
                final long endOffset = result.getRight();
                state.addCompletedFile(fileNameWithBeginOffset.fileName, fileNameWithBeginOffset.offset, endOffset, nextSequenceNumber, txnId);
                // injectCommitFailure();
                writer.commit();
                state.deleteTransactionToCommit(txnId);
            
                double elapsedSec = (System.nanoTime() - timestamp) / 1_000_000_000.0;
                double megabyteCount = numofbytes.getAndSet(0) / 1_000_000.0;
                double megabytesPerSec = megabyteCount / elapsedSec;
                log.info("processFile: Finished ingesting file {}; endOffset={}, nextSequenceNumber={}",
                        fileNameWithBeginOffset.fileName, endOffset, nextSequenceNumber);
                log.info("Sent {} MB in {} sec", megabyteCount, elapsedSec );
                log.info("Transfer rate: {} MB/sec", megabytesPerSec);            
            }
        }

        // Delete file right after ingesting
        if (config.enableDeleteCompletedFiles) {
            deleteCompletedFiles();
        }
    }

    void deleteCompletedFiles() throws Exception {
        final List<FileNameWithOffset> completedFiles = state.getCompletedFiles();
        completedFiles.forEach(file -> {
            //Obtain a lock on file
            try(FileChannel channel = FileChannel.open(Paths.get(file.fileName),StandardOpenOption.WRITE)){
                try(FileLock lock = channel.tryLock()) {
                    if(lock!=null){
                        Files.deleteIfExists(Paths.get(file.fileName));
                        log.info("deleteCompletedFiles: Deleted file {}", file.fileName);
                        lock.release();
                        // Only remove from database if we could delete file.
                        state.deleteCompletedFile(file.fileName);                        
                    }
                    else{
                        log.warn("Unable to obtain lock on file {}. File is locked by another process.", file.fileName);    
                        throw new Exception();
                    }
                }
            } catch (Exception e) {
                log.warn("Unable to delete ingested file {}", e.getMessage());
                log.warn("Deletion will be retried on the next iteration.");
                // We can continue on this error. Deletion will be retried on the next iteration.
            }
        });
    }

    /**
     * Inject a failure before commit for testing.
     */
    protected void injectCommitFailure() {
        if (Math.random() < 0.3) {
            throw new RuntimeException("injectCommitFailure: Commit failure test exception");
        }
    }    
}
