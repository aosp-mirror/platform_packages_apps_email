/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.os.Handler;
import android.os.Message;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.mail.utils.Clock;
import com.android.mail.utils.Throttle;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@SmallTest
public class ThrottleTest extends AndroidTestCase {
    private static final int MIN_TIMEOUT = 100;
    private static final int MAX_TIMEOUT = 500;

    private final CountingRunnable mRunnable = new CountingRunnable();
    private final MockClock mClock = new MockClock();
    private final MockTimer mTimer = new MockTimer(mClock);
    private final Throttle mTarget = new Throttle("test", mRunnable, new CallItNowHandler(),
            MIN_TIMEOUT, MAX_TIMEOUT, mClock, mTimer);

    /**
     * Advance the clock.
     */
    private void advanceClock(int milliseconds) {
        mClock.advance(milliseconds);
        mTimer.runExpiredTasks();
    }

    /**
     * Gets two events.  They're far apart enough that the timeout won't be extended.
     */
    public void testSingleCalls() {
        // T + 0
        mTarget.onEvent();
        advanceClock(0);
        assertEquals(0, mRunnable.mCounter);

        // T + 99
        advanceClock(99);
        assertEquals(0, mRunnable.mCounter); // Still not called

        // T + 100
        advanceClock(1);
        assertEquals(1, mRunnable.mCounter); // Called

        // T + 10100
        advanceClock(10000);
        assertEquals(1, mRunnable.mCounter);

        // Do the same thing again.  Should work in the same way.

        // T + 0
        mTarget.onEvent();
        advanceClock(0);
        assertEquals(1, mRunnable.mCounter);

        // T + 99
        advanceClock(99);
        assertEquals(1, mRunnable.mCounter); // Still not called

        // T + 100
        advanceClock(1);
        assertEquals(2, mRunnable.mCounter); // Called

        // T + 10100
        advanceClock(10000);
        assertEquals(2, mRunnable.mCounter);
    }

    /**
     * Gets 5 events in a row in a short period.
     *
     * We only roughly check the consequence, as the detailed spec isn't really important.
     * Here, we check if the timeout is extended, and the callback get called less than
     * 5 times.
     */
    public void testMultiCalls() {
        mTarget.onEvent();
        advanceClock(1);
        mTarget.onEvent();
        advanceClock(1);
        mTarget.onEvent();
        advanceClock(1);
        mTarget.onEvent();
        advanceClock(1);
        mTarget.onEvent();

        // Timeout should be extended
        assertTrue(mTarget.getTimeoutForTest() > 100);

        // Shouldn't result in 5 callback calls.
        advanceClock(2000);
        assertTrue(mRunnable.mCounter < 5);
    }

    public void testUpdateTimeout() {
        // Check initial value
        assertEquals(100, mTarget.getTimeoutForTest());

        // First call -- won't change the timeout
        mTarget.updateTimeout();
        assertEquals(100, mTarget.getTimeoutForTest());

        // Call again in 10 ms -- will extend timeout.
        mClock.advance(10);
        mTarget.updateTimeout();
        assertEquals(200, mTarget.getTimeoutForTest());

        // Call again in TIMEOUT_EXTEND_INTERAVL ms -- will extend timeout.
        mClock.advance(Throttle.TIMEOUT_EXTEND_INTERVAL);
        mTarget.updateTimeout();
        assertEquals(400, mTarget.getTimeoutForTest());

        // Again -- timeout reaches max.
        mClock.advance(Throttle.TIMEOUT_EXTEND_INTERVAL);
        mTarget.updateTimeout();
        assertEquals(500, mTarget.getTimeoutForTest());

        // Call in TIMEOUT_EXTEND_INTERAVL + 1 ms -- timeout will get reset.
        mClock.advance(Throttle.TIMEOUT_EXTEND_INTERVAL + 1);
        mTarget.updateTimeout();
        assertEquals(100, mTarget.getTimeoutForTest());
    }

    private static class CountingRunnable implements Runnable {
        public int mCounter;

        @Override
        public void run() {
            mCounter++;
        }
    }

    /**
     * Dummy {@link Handler} that executes {@link Runnable}s passed to {@link Handler#post}
     * immediately on the current thread.
     */
    private static class CallItNowHandler extends Handler {
        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            msg.getCallback().run();
            return true;
        }
    }

    /**
     * Substitute for {@link Timer} that works based on the provided {@link Clock}.
     */
    private static class MockTimer extends Timer {
        private final Clock mClock;

        private static class Entry {
            public long mScheduledTime;
            public TimerTask mTask;
        }

        private final BlockingQueue<Entry> mTasks = new LinkedBlockingQueue<Entry>();

        public MockTimer(Clock clock) {
            mClock = clock;
        }

        @Override
        public void schedule(TimerTask task, long delay) {
            if (delay == 0) {
                task.run();
            } else {
                Entry e = new Entry();
                e.mScheduledTime = mClock.getTime() + delay;
                e.mTask = task;
                mTasks.offer(e);
            }
        }

        /**
         * {@link MockTimer} can't know when the clock advances.  This method must be called
         * whenever the (mock) current time changes.
         */
        public void runExpiredTasks() {
            while (!mTasks.isEmpty()) {
                Entry e = mTasks.peek();
                if (e.mScheduledTime > mClock.getTime()) {
                    break;
                }
                e.mTask.run();
                mTasks.poll();
            }
        }
    }
}
