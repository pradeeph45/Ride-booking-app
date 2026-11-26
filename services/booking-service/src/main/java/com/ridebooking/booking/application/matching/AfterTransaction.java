package com.ridebooking.booking.application.matching;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs Redis clean-up only once the database outcome is known. A reservation must be released after an accept or a
 * reject has really committed, never before: otherwise a rollback would leave a pending offer with its driver
 * un-reserved. Outside a transaction the action simply runs at once.
 */
public final class AfterTransaction {

    private AfterTransaction() {}

    public static void onCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /** Runs when the transaction ends WITHOUT committing. */
    public static void onRollback(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    action.run();
                }
            }
        });
    }
}
