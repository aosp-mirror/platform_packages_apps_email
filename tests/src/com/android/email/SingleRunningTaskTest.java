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

package com.android.email;

import com.android.email.TestUtils.Condition;
import com.android.emailcommon.utility.Utility;

import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.TestCase;

public class SingleRunningTaskTest extends TestCase {

    private static class NormalTask extends SingleRunningTask<Void> {
        // # of times the task has actually run.
        public final AtomicInteger mCalledCount = new AtomicInteger(0);

        // The task will be blocked if true
        private volatile boolean mBlocked = false;

        public NormalTask() {
            super("task");
        }

        public void block() {
            mBlocked = true;
        }

        public void unblock() {
            mBlocked = false;
            synchronized (this) {
                notify();
            }
        }

        @Override
        protected void runInternal(Void param) {
            mCalledCount.incrementAndGet();
            while (mBlocked) {
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException ignore) {
                    }
                }
            }
        }
    }

    // Always throws exception
    private static class FailTask extends SingleRunningTask<Void> {
        public FailTask() {
            super("task");
        }

        @Override
        protected void runInternal(Void param) {
            throw new RuntimeException("Intentional exception");
        }
    }

    /**
     * Run 3 tasks sequentially.
     */
    public void testSequential() {
        final NormalTask e = new NormalTask();

        e.run(null);
        e.run(null);
        e.run(null);

        assertEquals(3, e.mCalledCount.get());
    }

    /**
     * Run 2 tasks in parallel, and then another call.
     */
    public void testParallel() {
        final NormalTask e = new NormalTask();

        // Block the first task
        e.block();

        // The call will be blocked, so run it on another thread.
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                e.run(null);
            }
        });

        // Wait until the task really starts.
        TestUtils.waitUntil(new Condition() {
            @Override
            public boolean isMet() {
                return e.mCalledCount.get() >= 1;
            }
        }, 10);

        // Now the task is running, blocked.

        // This call will just be ignored.
        e.run(null);

        assertEquals(1, e.mCalledCount.get());

        // Let the thread finish.
        e.unblock();

        // Wait until the task really finishes.
        TestUtils.waitUntil(new Condition() {
            @Override
            public boolean isMet() {
                return !e.isRunningForTest();
            }
        }, 10);

        // Now this should not be ignored.
        e.run(null);

        assertEquals(2, e.mCalledCount.get());
    }

    /**
     * If a task throws, isRunning should become false.
     */
    public void testException() {
        final FailTask e = new FailTask();

        try {
            e.run(null);
            fail("Didn't throw exception");
        } catch (RuntimeException expected) {
        }
        assertFalse(e.isRunningForTest());
    }
}
