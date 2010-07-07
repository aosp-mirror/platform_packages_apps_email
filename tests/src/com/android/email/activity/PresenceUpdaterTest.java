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

package com.android.email.activity;

import com.android.email.R;
import com.android.email.TestUtils;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.ContactsContract.StatusUpdates;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;

/**
 * Test case for {@link PresenceUpdater}.
 *
 * We need to use {@link InstrumentationTestCase} so that we can create AsyncTasks on the UI thread
 * using {@link InstrumentationTestCase#runTestOnUiThread}.
 */
@LargeTest
public class PresenceUpdaterTest extends InstrumentationTestCase {
    /**
     * Email address that's (most probably) not in Contacts.
     */
    private static final String NON_EXISTENT_EMAIL_ADDRESS = "no.such.email.address@a.a";

    /**
     * Timeout used for async tests.
     */
    private static final int TIMEOUT_SECONDS = 10;

    private Context getContext() {
        return getInstrumentation().getTargetContext();
    }

    public void testSetPresenceIcon() {
        assertEquals(StatusUpdates.getPresenceIconResourceId(StatusUpdates.AWAY),
                PresenceUpdater.getPresenceIconResourceId(StatusUpdates.AWAY));

        // Special case: unknown
        assertEquals(R.drawable.presence_inactive, PresenceUpdater.getPresenceIconResourceId(null));
    }

    /** Call {@link PresenceUpdater#checkPresence} on the UI thread. */
    private void checkPresenceOnUiThread(final PresenceUpdater pu, final String emailAddress,
            final PresenceUpdater.Callback callback) throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                pu.checkPresence(emailAddress, callback);
            }
        });
    }

    private static void waitForAllTasksToFinish(String message, final PresenceUpdater pu) {
        TestUtils.waitUntil(message, new TestUtils.Condition() {
            @Override public boolean isMet() {
                return pu.getTaskListSizeForTest() == 0;
            }
        }, TIMEOUT_SECONDS);
    }

    /**
     * Verify that:
     * - {@link PresenceUpdater#checkPresence} starts an AsyncTask.
     * - {@link PresenceUpdater#cancelAll} cancels all AsyncTasks.
     *
     * It uses {@link PresenceUpdaterBlocking} to test cancellation.
     */
    public void testQueueTasksAndCancelAll() throws Throwable {
        // Use blocking one.
        PresenceUpdaterBlocking pu = new PresenceUpdaterBlocking(getContext());
        MockCallback callback = new MockCallback();

        // Start presence check.
        checkPresenceOnUiThread(pu, "dummy@dummy.com", callback);

        // There should be 1 task running.
        assertEquals(1, pu.getTaskListSizeForTest());

        // Start another presence check.
        checkPresenceOnUiThread(pu, "dummy2@dummy.com", callback);

        // There should be 2 tasks running.
        assertEquals(2, pu.getTaskListSizeForTest());

        assertFalse(callback.mCalled);

        // === Test for cancelAll() ===

        // Cancel all tasks.  Callback shouldn't get called.
        callback.reset();
        pu.cancelAll();

        waitForAllTasksToFinish("testQueueTaskAndCancelAll", pu);

        assertFalse(callback.mCalled);
    }

    /**
     * Verify that
     * - {@link PresenceUpdater#checkPresence} calls {@link PresenceUpdater.Callback} within
     * timeout.
     *
     * It uses the actual contacts provider.
     */
    public void testUpdateImageUnknownEmailAddress() throws Throwable {
        PresenceUpdater pu = new PresenceUpdater(getContext());
        MockCallback callback = new MockCallback();

        // Start presence check.
        checkPresenceOnUiThread(pu, NON_EXISTENT_EMAIL_ADDRESS, callback);

        waitForAllTasksToFinish("testUpdateImageUnknownEmailAddress", pu);

        // Check status
        assertTrue(callback.mCalled);
        assertNull(callback.mPresenceStatus);

        // There should be no running tasks.
        assertEquals(0, pu.getTaskListSizeForTest());
    }

    /**
     * Verify that
     * - startUpdate really updates image's resource ID before timeout for *known* email address.
     *
     * It uses {@link PresenceUpdaterWithMockCursor} to inject a mock cursor with a dummy presence
     * information.
     */
    public void testUpdateImage() throws Throwable {
        PresenceUpdaterWithMockCursor pu = new PresenceUpdaterWithMockCursor(getContext(),
                StatusUpdates.AVAILABLE);
        MockCallback callback = new MockCallback();

        // Start presence check.
        checkPresenceOnUiThread(pu, NON_EXISTENT_EMAIL_ADDRESS, callback);

        waitForAllTasksToFinish("testUpdateImage", pu);

        // Check status
        assertTrue(callback.mCalled);
        assertEquals((Integer) StatusUpdates.AVAILABLE, callback.mPresenceStatus);
    }

    private static class MockCallback implements PresenceUpdater.Callback {
        public boolean mCalled;
        public Integer mPresenceStatus;

        public void reset() {
            mPresenceStatus = null;
            mCalled = false;
        }

        @Override
        public void onPresenceResult(String emailAddress, Integer presenceStatus) {
            mPresenceStatus = presenceStatus;
            mCalled = true;
        }
    }

    /**
     * A subclass of {@link PresenceUpdater} whose async task waits for an Object to be notified.
     */
    private static class PresenceUpdaterBlocking extends PresenceUpdater {
        public final Object mWaitForObject = new Object();

        public PresenceUpdaterBlocking(Context context) {
            super(context);
        }

        @Override Integer getPresenceStatus(String emailAddress) {
            synchronized (mWaitForObject) {
                try {
                    mWaitForObject.wait();
                } catch (InterruptedException ignore) {
                    // Canceled
                    return null;
                }
            }
            return super.getPresenceStatus(emailAddress);
        }
    }

    /**
     * A subclass of {@link PresenceUpdater} that injects a MatrixCursor as a mock.
     */
    private static class PresenceUpdaterWithMockCursor extends PresenceUpdater {
        public final int mPresenceStatus;

        public PresenceUpdaterWithMockCursor(Context context, int presenceStatus) {
            super(context);
            mPresenceStatus = presenceStatus;
        }

        /**
         * Override to inject a mock cursor.
         */
        @Override Cursor openPresenceCheckCursor(String emailAddress) {
            MatrixCursor c = new MatrixCursor(PresenceUpdater.PRESENCE_STATUS_PROJECTION);
            c.addRow(new Object[] {mPresenceStatus});
            return c;
        }
    }
}
