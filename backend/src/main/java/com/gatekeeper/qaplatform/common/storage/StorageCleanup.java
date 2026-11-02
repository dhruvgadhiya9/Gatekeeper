package com.gatekeeper.qaplatform.common.storage;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Deletes stored files only after the database change commits, so a rollback can't leave rows
 * pointing at files that are gone. A failed delete leaves an unused file, which is logged and
 * doesn't undo the change.
 */
@Component
public class StorageCleanup {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanup.class);

    private final ObjectStorage storage;

    public StorageCleanup(ObjectStorage storage) {
        this.storage = storage;
    }

    public void deleteAfterCommit(List<String> keys) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                keys.forEach(StorageCleanup.this::deleteQuietly);
            }
        });
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete stored file {}: {}", key, e.getClass().getSimpleName());
        }
    }
}
