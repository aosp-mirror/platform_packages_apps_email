/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.emailcommon.utility;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.Suppress;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Suppress
public class DelayedOperationsTests extends AndroidTestCase {
    private DelayedOperationsForTest mDelayedOperations;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mDelayedOperations = new DelayedOperationsForTest();
    }

    public void brokentestEnueue() {
        // Can pass only final vars, so AtomicInteger.
        final AtomicInteger i = new AtomicInteger(1);

        mDelayedOperations.post(new Runnable() {
            @Override public void run() {
                i.addAndGet(2);
            }
        });

        mDelayedOperations.post(new Runnable() {
            @Override public void run() {
                i.addAndGet(4);
            }
        });

        // 2 ops queued.
        assertEquals(2, mDelayedOperations.mPendingOperations.size());

        // Value still not changed.
        assertEquals(1, i.get());

        // Execute all pending tasks!
        mDelayedOperations.runQueuedOperations();

        // 1 + 2 + 4 = 7
        assertEquals(7, i.get());

        // No pending tasks.
        assertEquals(0, mDelayedOperations.mPendingOperations.size());
    }

    public void brokentestCancel() {
        // Can pass only final vars, so AtomicInteger.
        final AtomicInteger i = new AtomicInteger(1);

        // Post & cancel it immediately
        Runnable r;
        mDelayedOperations.post(r = new Runnable() {
            @Override public void run() {
                i.addAndGet(2);
            }
        });
        mDelayedOperations.removeCallbacks(r);

        mDelayedOperations.post(new Runnable() {
            @Override public void run() {
                i.addAndGet(4);
            }
        });

        // 1 op queued.
        assertEquals(1, mDelayedOperations.mPendingOperations.size());

        // Value still not changed.
        assertEquals(1, i.get());

        // Execute all pending tasks!
        mDelayedOperations.runQueuedOperations();

        // 1 + 4 = 5
        assertEquals(5, i.get());

        // No pending tasks.
        assertEquals(0, mDelayedOperations.mPendingOperations.size());
    }

    public void brokentestCancelAll() {
        // Can pass only final vars, so AtomicInteger.
        final AtomicInteger i = new AtomicInteger(1);

        mDelayedOperations.post(new Runnable() {
            @Override public void run() {
                i.addAndGet(2);
            }
        });

        mDelayedOperations.post(new Runnable() {
            @Override public void run() {
                i.addAndGet(4);
            }
        });

        // 2 op queued.
        assertEquals(2, mDelayedOperations.mPendingOperations.size());

        // Value still not changed.
        assertEquals(1, i.get());

        // Cancel all!!
        mDelayedOperations.removeCallbacks();

        // There should be no pending tasks in handler.
        assertEquals(0, mDelayedOperations.mPostedToHandler.size());

        // Nothing should have changed.
        assertEquals(1, i.get());

        // No pending tasks.
        assertEquals(0, mDelayedOperations.mPendingOperations.size());
    }

    private static class DelayedOperationsForTest extends DelayedOperations {
        // Represents all runnables pending in the handler.
        public final ArrayList<Runnable> mPostedToHandler = new ArrayList<Runnable>();

        public DelayedOperationsForTest() {
            super(null);
        }

        // Emulate Handler.post
        @Override
        void postRunnable(Runnable r) {
            mPostedToHandler.add(r);
        }

        // Emulate Handler.removeCallbacks
        @Override
        void cancelRunnable(Runnable r) {
            mPostedToHandler.remove(r);
        }

        public void runQueuedOperations() {
            for (Runnable r : mPostedToHandler) {
                r.run();
            }
            mPostedToHandler.clear();
        }
    }
}
