/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.persistit;

import static com.persistit.TransactionStatus.ABORTED;
import static com.persistit.TransactionStatus.UNCOMMITTED;

import java.util.concurrent.atomic.AtomicLong;

import junit.framework.TestCase;

public class TransactionIndexTest extends TestCase {

    private final TimestampAllocator _tsa = new TimestampAllocator();

    public void testBasicMethods() throws Exception {
        final TransactionIndex ti = new TransactionIndex(_tsa, 1);

        TransactionStatus ts1 = ti.registerTransaction();
        ti.updateActiveTransactionCache();
        assertTrue(ti.hasConcurrentTransaction(0, ts1.getTs() + 1));
        ts1.commit(_tsa.updateTimestamp());

        TransactionStatus ts2 = ti.registerTransaction();
        /*
         * True because the ActiveTransactionCache hasn't been updated yet.
         */
        assertTrue(ti.hasConcurrentTransaction(0, ts1.getTs() + 1));
        assertTrue(ti.hasConcurrentTransaction(0, ts2.getTs() + 1));
        ti.updateActiveTransactionCache();
        /*
         * False (correctly) after update.
         */
        assertFalse(ti.hasConcurrentTransaction(0, ts1.getTs() + 1));
        assertTrue(ti.hasConcurrentTransaction(0, ts2.getTs() + 1));
        /*
         * Same transaction - illusion that it has committed.
         */
        assertTrue(isCommitted(ti.commitStatus(TransactionIndex.ts2vh(ts2.getTs()), ts2.getTs(), 0)));
        /*
         * Step policy
         */
        assertFalse(isCommitted(ti.commitStatus(TransactionIndex.ts2vh(ts2.getTs()) + 1, ts2.getTs(), 1)));
        assertTrue(isCommitted(ti.commitStatus(TransactionIndex.ts2vh(ts2.getTs()) + 1, ts2.getTs(), 2)));

        TransactionStatus ts3 = ti.registerTransaction();
        TransactionStatus ts4 = ti.registerTransaction();

        ts2.commit(_tsa.updateTimestamp());
        ti.updateActiveTransactionCache();
        assertFalse(ti.hasConcurrentTransaction(0, ts2.getTs() + 1));
        _tsa.updateTimestamp();
        assertEquals(UNCOMMITTED, ti.commitStatus(TransactionIndex.ts2vh(ts3.getTs()), _tsa
                .getCurrentTimestamp(), 0));
        assertEquals(ts3.getTs(), ti.commitStatus(TransactionIndex.ts2vh(ts3.getTs()), ts3.getTs(), 0));
        ts3.incrementMvvCount();
        ts3.abort();
        assertEquals(ABORTED, ti.commitStatus(TransactionIndex.ts2vh(ts3.getTs()), _tsa
                .getCurrentTimestamp(), 0));
        assertEquals(4, ti.getCurrentCount());
        ti.notifyCompleted(ts1, -ts1.getTc() + 1);
        /*
         * ts1 committed and not concurrent with ts2
         */
        assertTrue(isCommitted(ti.commitStatus(TransactionIndex.ts2vh(ts1.getTs()), ts2.getTs(), 0)));
        ti.notifyCompleted(ts2, _tsa.updateTimestamp());
        /*
         * ts2 committed but ts4 is concurrent
         */
        assertFalse(isCommitted(ti.commitStatus(TransactionIndex.ts2vh(ts2.getTs()), ts4.getTs(), 0)));
        ts4.commit(_tsa.updateTimestamp());
        ti.notifyCompleted(ts3, -ts3.getTc() + 1);
        ti.updateActiveTransactionCache();

        ti.notifyCompleted(ts4, -ts4.getTc() + 1);
        assertEquals(0, ti.getCurrentCount());
        assertEquals(3, ti.getFreeCount());
        assertEquals(1, ti.getAbortedCount());
        ts3.decrementMvvCount();

        ti.cleanup(); // compute canonical form

        assertEquals(0, ti.getCurrentCount());
        assertEquals(4, ti.getFreeCount());
        assertEquals(0, ti.getAbortedCount());
    }

    private boolean isCommitted(final long tc) {
        return tc >= 0 && tc != UNCOMMITTED;
    }

    public void testNonBlockingWwDependency() throws Exception {
        final TransactionIndex ti = new TransactionIndex(_tsa, 1);
        final TransactionStatus ts1 = ti.registerTransaction();
        final TransactionStatus ts2 = ti.registerTransaction();
        ts1.commit(_tsa.updateTimestamp());
        ti.notifyCompleted(ts1, _tsa.updateTimestamp());
        /*
         * Should return 0 because ts1 has committed and is now primordial.
         */
        assertTrue(isCommitted(ti.wwDependency(TransactionIndex.ts2vh(ts1.getTs()), ts2, 1000)));
        final TransactionStatus ts3 = ti.registerTransaction();
        ts2.abort();
        ti.notifyCompleted(ts2, _tsa.updateTimestamp());
        /*
         * Should return false because ts1 and ts3 are not concurrent
         */
        assertTrue(isCommitted(ti.wwDependency(TransactionIndex.ts2vh(ts1.getTs()), ts3, 1000)));
        /*
         * Should return false because ts2 aborted
         */
        assertTrue(isCommitted(ti.wwDependency(TransactionIndex.ts2vh(ts2.getTs()), ts3, 1000)));
        ts3.commit(_tsa.updateTimestamp());
    }

    public void testReduce() throws Exception {
        final TransactionStatus[] array = new TransactionStatus[100];
        final TransactionIndex ti = new TransactionIndex(_tsa, 1);

        for (int count = 0; count < array.length; count++) {
            array[count] = ti.registerTransaction();
            array[count].incrementMvvCount();
        }
        assertEquals(ti.getLongRunningThreshold(), ti.getCurrentCount());
        assertEquals(array.length - ti.getLongRunningThreshold(), ti.getLongRunningCount());

        for (int count = 20; count < 70; count++) {
            array[count].abort();
            ti.notifyCompleted(array[count], _tsa.getCurrentTimestamp());
        }
        for (int count = 50; count < 60; count++) {
            array[count].decrementMvvCount();
        }
        assertEquals(ti.getLongRunningThreshold(), ti.getCurrentCount());
        assertEquals(array.length - ti.getLongRunningThreshold() - ti.getAbortedCount() - ti.getFreeCount(), ti
                .getLongRunningCount());
        assertEquals(50, ti.getAbortedCount());
        for (int count = 0; count < 20; count++) {
            array[count].commit(array[20].getTs());
            ti.notifyCompleted(array[count], array[20].getTs());
        }
        ti.updateActiveTransactionCache();
        ti.cleanup();
        assertEquals(ti.getMaxFreeListSize(), ti.getFreeCount());
        assertEquals(50, ti.getAbortedCount());
        assertEquals(ti.getLongRunningThreshold(), ti.getCurrentCount());
        assertEquals(array.length - ti.getCurrentCount() - ti.getAbortedCount() - ti.getFreeCount()
                - ti.getDroppedCount(), ti.getLongRunningCount());

        ti.updateActiveTransactionCache();
        /*
         * aborted set retained due to currently active transactions that
         * started before the mvvCount was decremented
         */
        assertEquals(50, ti.getAbortedCount());
        /*
         * Commit all remaining transactions so that there no currently active
         * transactions.
         */
        for (int count = 70; count < array.length; count++) {
            array[count].commit(_tsa.getCurrentTimestamp());
            ti.notifyCompleted(array[count], _tsa.updateTimestamp());
        }
        /*
         * Compute canonical form. 40 aborted transactions should be left over
         * because their mvv counts were not decremented.
         */
        ti.cleanup();
        assertEquals(40, ti.getAbortedCount());
    }

    public void testBlockingWwDependency() throws Exception {
        final TransactionIndex ti = new TransactionIndex(_tsa, 1);
        final TransactionStatus ts1 = ti.registerTransaction();
        final TransactionStatus ts2 = ti.registerTransaction();
        final AtomicLong elapsed = new AtomicLong();
        final boolean result1 = tryBlockingWwDependency(ti, ts1, ts2, 1000, 10000, elapsed, true);
        assertTrue(result1);
        assertTrue(elapsed.get() >= 900);
        final TransactionStatus ts3 = ti.registerTransaction();
        final boolean result2 = tryBlockingWwDependency(ti, ts2, ts3, 1000, 10000, elapsed, false);
        assertFalse(result2);
        assertTrue(elapsed.get() >= 900);
    }

    boolean tryBlockingWwDependency(final TransactionIndex ti, final TransactionStatus target,
            final TransactionStatus source, final long wait, final long timeout, final AtomicLong elapsed,
            boolean commit) throws Exception {
        final AtomicLong result = new AtomicLong();
        final Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    final long start = System.currentTimeMillis();
                    result.set(ti.wwDependency(TransactionIndex.ts2vh(target.getTs()), source, timeout));
                    elapsed.set(System.currentTimeMillis() - start);
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        t.start();
        Thread.sleep(wait);
        if (commit) {
            target.commit(_tsa.getCurrentTimestamp());
        } else {
            target.abort();
        }
        ti.notifyCompleted(target, _tsa.updateTimestamp());
        t.join();
        return result.get() > 0;
    }
}