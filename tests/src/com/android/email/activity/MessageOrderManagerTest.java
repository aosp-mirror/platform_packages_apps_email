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

import com.android.email.provider.EmailProvider;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.utility.DelayedOperations;

import android.content.Context;
import android.database.AbstractCursor;
import android.database.Cursor;
import android.os.Handler;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.Assert;

@SmallTest
public class MessageOrderManagerTest extends ProviderTestCase2<EmailProvider> {

    private MyCallback mCallback;

    @Override protected void setUp() throws Exception {
        super.setUp();
        mCallback = new MyCallback();
    }

    public MessageOrderManagerTest() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    private static void assertCanMove(MessageOrderManager mom, boolean newer, boolean older) {
        Assert.assertEquals(older, mom.canMoveToOlder());
        Assert.assertEquals(newer, mom.canMoveToNewer());
    }

    public void testBasic() {
        MessageOrderManagerForTest mom = new MessageOrderManagerForTest(getContext(), 1, mCallback);
        mom.assertStartQueryCalledAndReset();

        // moveTo not called, so it returns -1
        assertEquals(-1, mom.getCurrentMessageId());

        // Task not finished, so all returns false.
        assertCanMove(mom, false, false);
        assertFalse(mom.moveToNewer());
        assertFalse(mom.moveToOlder());

        // Set current message
        mom.moveTo(54);
        assertEquals(54, mom.getCurrentMessageId());

        // Task still not finished, so all returns false.
        assertCanMove(mom, false, false);
        assertFalse(mom.moveToNewer());
        assertFalse(mom.moveToOlder());

        // Both callbacks shouldn't have called.
        mCallback.assertCallbacksCalled(false, false);

        // Cursor not open yet, so these are both 0.
        assertEquals(0, mom.getCurrentPosition());
        assertEquals(0, mom.getTotalMessageCount());
    }

    /**
     * Test with actual message list.
     *
     * In this test, {@link MessageOrderManager#moveTo} is called AFTER the cursor opens.
     */
    public void testWithList() {
        MessageOrderManagerForTest mom = new MessageOrderManagerForTest(getContext(), 1, mCallback);
        mom.assertStartQueryCalledAndReset();

        // Callback not called yet.
        mCallback.assertCallbacksCalled(false, false);

        // Inject mock cursor.  (Imitate async query done.)
        MyCursor cursor = new MyCursor(11, 22, 33, 44); // Newer to older
        mom.onCursorOpenDone(cursor);

        assertEquals(0, mom.getCurrentPosition());
        assertEquals(4, mom.getTotalMessageCount());

        // Current message id not set yet, so callback should have called yet.
        mCallback.assertCallbacksCalled(false, false);

        // Set current message id -- now onMessagesChanged() should get called.
        mom.moveTo(22);
        assertEquals(1, mom.getCurrentPosition());
        mCallback.assertCallbacksCalled(true, false);
        assertEquals(22, mom.getCurrentMessageId());
        assertCanMove(mom, true, true);

        // Move to row 1
        assertTrue(mom.moveToNewer());
        assertEquals(0, mom.getCurrentPosition());
        assertEquals(11, mom.getCurrentMessageId());
        assertCanMove(mom, false, true);
        mCallback.assertCallbacksCalled(true, false);

        // Try to move to newer, but no newer messages
        assertFalse(mom.moveToNewer());
        assertEquals(0, mom.getCurrentPosition());
        assertEquals(11, mom.getCurrentMessageId()); // Still row 1
        mCallback.assertCallbacksCalled(false, false);

        // Move to row 2
        assertTrue(mom.moveToOlder());
        assertEquals(1, mom.getCurrentPosition());
        assertEquals(22, mom.getCurrentMessageId());
        assertCanMove(mom, true, true);
        mCallback.assertCallbacksCalled(true, false);

        // Move to row 3
        assertTrue(mom.moveToOlder());
        assertEquals(2, mom.getCurrentPosition());
        assertEquals(33, mom.getCurrentMessageId());
        assertCanMove(mom, true, true);
        mCallback.assertCallbacksCalled(true, false);

        // Move to row 4
        assertTrue(mom.moveToOlder());
        assertEquals(3, mom.getCurrentPosition());
        assertEquals(44, mom.getCurrentMessageId());
        assertCanMove(mom, true, false);
        mCallback.assertCallbacksCalled(true, false);

        // Try to move older, but no Older messages
        assertFalse(mom.moveToOlder());
        assertEquals(3, mom.getCurrentPosition());
        mCallback.assertCallbacksCalled(false, false);

        // Move to row 3
        assertTrue(mom.moveToNewer());
        assertEquals(2, mom.getCurrentPosition());
        assertEquals(33, mom.getCurrentMessageId());
        assertCanMove(mom, true, true);
        mCallback.assertCallbacksCalled(true, false);
    }

    /**
     * Test with actual message list.
     *
     * In this test, {@link MessageOrderManager#moveTo} is called BEFORE the cursor opens.
     */
    public void testWithList2() {
        MessageOrderManagerForTest mom = new MessageOrderManagerForTest(getContext(), 1, mCallback);
        mom.assertStartQueryCalledAndReset();

        // Callback not called yet.
        mCallback.assertCallbacksCalled(false, false);

        mom.moveTo(22);
        mCallback.assertCallbacksCalled(false, false); // Cursor not open, callback not called yet.
        assertEquals(22, mom.getCurrentMessageId());

        // cursor not open yet
        assertEquals(0, mom.getCurrentPosition());
        assertEquals(0, mom.getTotalMessageCount());

        // Inject mock cursor.  (Imitate async query done.)
        MyCursor cursor = new MyCursor(11, 22, 33, 44); // Newer to older
        mom.onCursorOpenDone(cursor);

        // As soon as the cursor opens, callback gets called.
        mCallback.assertCallbacksCalled(true, false);
        assertEquals(22, mom.getCurrentMessageId());

        assertEquals(1, mom.getCurrentPosition());
        assertEquals(4, mom.getTotalMessageCount());
    }

    public void testContentChanged() {
        MessageOrderManagerForTest mom = new MessageOrderManagerForTest(getContext(), 1, mCallback);

        // Inject mock cursor.  (Imitate async query done.)
        MyCursor cursor = new MyCursor(11, 22, 33, 44); // Newer to older
        mom.onCursorOpenDone(cursor);

        // Move to 22
        mom.moveTo(22);
        mCallback.assertCallbacksCalled(true, false);
        assertEquals(22, mom.getCurrentMessageId());
        assertCanMove(mom, true, true);

        // Delete 33
        mom.updateMessageList(11, 22, 44);

        mCallback.assertCallbacksCalled(true, false);
        assertEquals(22, mom.getCurrentMessageId());
        assertCanMove(mom, true, true);

        // Delete 44
        mom.updateMessageList(11, 22);

        mCallback.assertCallbacksCalled(true, false);
        assertEquals(22, mom.getCurrentMessageId());
        assertCanMove(mom, true, false); // Can't move to older

        // Append 55
        mom.updateMessageList(11, 22, 55);

        mCallback.assertCallbacksCalled(true, false);
        assertEquals(22, mom.getCurrentMessageId());
        assertCanMove(mom, true, true);

        // Delete 11
        mom.updateMessageList(22, 55);

        mCallback.assertCallbacksCalled(true, false);
        assertEquals(22, mom.getCurrentMessageId());
        assertCanMove(mom, false, true);

        // Delete 55
        mom.updateMessageList(22);

        mCallback.assertCallbacksCalled(true, false);
        assertEquals(22, mom.getCurrentMessageId());
        assertCanMove(mom, false, false); // Can't move either way

        // Delete 22 -- no messages left.
        mom.updateMessageList();
        mCallback.assertCallbacksCalled(false, true);

        // Test for the case where list is not empty, but the current message is gone.
        // First, set up a list with 22 as the current message.
        mom.updateMessageList(11, 22, 33, 44);
        mom.moveTo(22);
        assertEquals(22, mom.getCurrentMessageId());
        mCallback.assertCallbacksCalled(true, false);

        // Then remove the current message.
        mom.updateMessageList(11, 33, 44);
        mCallback.assertCallbacksCalled(false, true);
    }

    /**
     * Test using the actual {@link MessageOrderManager} rather than
     * {@link MessageOrderManagerForTest}.
     */
    public void testWithActualClass() {
        // There are not many things we can test synchronously.
        // Just open & close just to make sure it won't crash.
        MessageOrderManager mom = new MessageOrderManager(getContext(), 1, new MyCallback());
        mom.moveTo(123);
        mom.close();
    }

    private static class MyCallback implements MessageOrderManager.Callback {
        public boolean mCalledOnMessageNotFound;
        public boolean mCalledOnMessagesChanged;

        @Override public void onMessagesChanged() {
            mCalledOnMessagesChanged = true;
        }

        @Override public void onMessageNotFound() {
            mCalledOnMessageNotFound = true;
        }

        /**
         * Asserts that the callbacks have/have not been called, and reset the flags.
         */
        public void assertCallbacksCalled(boolean messagesChanged, boolean messageNotFound) {
            assertEquals(messagesChanged, mCalledOnMessagesChanged);
            assertEquals(messageNotFound, mCalledOnMessageNotFound);

            mCalledOnMessagesChanged = false;
            mCalledOnMessageNotFound = false;
        }
    }

    /**
     *  "Non" delayed operation -- runs the runnable immediately
     */
    private static final class NonDelayedOperations extends DelayedOperations {
        public NonDelayedOperations() {
            super(new Handler());
        }

        @Override
        public void post(Runnable r) {
            r.run();
        }
    }

    /**
     * MessageOrderManager for test.  Overrides {@link #startQuery}
     */
    private static class MessageOrderManagerForTest extends MessageOrderManager {
        private Cursor mLastCursor;
        public boolean mStartQueryCalled;

        public MessageOrderManagerForTest(Context context, long mailboxId, Callback callback) {
            super(context, mailboxId, callback, new NonDelayedOperations());
        }

        @Override void startQuery() {
            // To make tests synchronous, we replace this method.
            mStartQueryCalled = true;
        }

        @Override /* package */ Handler getHandlerForContentObserver() {
            return null;
        }

        @Override void onCursorOpenDone(Cursor cursor) {
            super.onCursorOpenDone(cursor);
            mLastCursor =  cursor;
        }

        /**
         * Utility method to emulate data set changed.
         */
        public void updateMessageList(long... idList) {
            assertNotNull(mLastCursor); // Make sure a cursor is set.

            // Notify dataset change -- it should end up startQuery() gets called.
            ((MyCursor) mLastCursor).notifyChanged();
            assertStartQueryCalledAndReset(); // Start

            // Set a new cursor with a new list.
            onCursorOpenDone(new MyCursor(idList));
        }

        public void assertStartQueryCalledAndReset() {
            assertTrue(mStartQueryCalled);
            mStartQueryCalled = false;
        }
    }

    private static class MyCursor extends AbstractCursor {
        private long[] mList;

        public MyCursor(long... idList) {
            mList = (idList == null) ? new long[0] : idList;
        }

        public void notifyChanged() {
            onChange(false);
        }

        @Override public int getColumnCount() {
            return 1;
        }

        @Override public int getCount() {
            return mList.length;
        }

        @Override public String[] getColumnNames() {
            return new String[] {EmailContent.RECORD_ID};
        }

        @Override public long getLong(int columnIndex) {
            Assert.assertEquals(EmailContent.ID_PROJECTION_COLUMN, columnIndex);
            return mList[mPos];
        }

        @Override public double getDouble(int column) {
            throw new junit.framework.AssertionFailedError();
        }

        @Override public float getFloat(int column) {
            throw new junit.framework.AssertionFailedError();
        }

        @Override public int getInt(int column) {
            throw new junit.framework.AssertionFailedError();
        }

        @Override public short getShort(int column) {
            throw new junit.framework.AssertionFailedError();
        }

        @Override public String getString(int column) {
            throw new junit.framework.AssertionFailedError();
        }

        @Override public boolean isNull(int column) {
            throw new junit.framework.AssertionFailedError();
        }
    }
}
