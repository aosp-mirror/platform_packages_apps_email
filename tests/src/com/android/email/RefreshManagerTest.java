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

import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;

import android.content.Context;
import android.test.AndroidTestCase;
import android.test.MoreAsserts;
import android.util.Log;

import java.util.ArrayList;

import junit.framework.Assert;

public class RefreshManagerTest extends AndroidTestCase {
    private MockClock mClock;
    private MockController mController;
    private RefreshManager mTarget;
    private RefreshListener mListener;

    // Isolated Context for providers.
    private Context mProviderContext;

    private static final MessagingException EXCEPTION = new MessagingException("test");

    // Looks silly, but it'll make it more readable.
    private static final long ACCOUNT_1 = 1;
    private static final long ACCOUNT_2 = 2;
    private static final long MAILBOX_1 = 3;
    private static final long MAILBOX_2 = 4;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mClock = new MockClock();
        mController = new MockController(getContext());
        mListener = new RefreshListener();
        mProviderContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(
                mContext, EmailProvider.class);
        mTarget = new RefreshManager(mProviderContext, mController, mClock, null);
        mTarget.registerListener(mListener);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mController.cleanupForTest();
    }

    public void testRegisterUnregisterListener() {
        // mListener is already registered
        assertEquals(1, mTarget.getListenersForTest().size());

        mTarget.unregisterListener(mListener);
        assertEquals(0, mTarget.getListenersForTest().size());
    }

    public void testRefreshStatus() {
        RefreshManager.Status s = new RefreshManager.Status();
        assertFalse(s.isRefreshing());
        assertTrue(s.canRefresh());
        assertEquals(0, s.getLastRefreshTime());

        // Request refresh
        s.onRefreshRequested();
        assertTrue(s.isRefreshing());
        assertFalse(s.canRefresh());
        assertEquals(0, s.getLastRefreshTime());

        // Refresh start
        s.onCallback(null, 0, mClock);
        assertTrue(s.isRefreshing());
        assertFalse(s.canRefresh());
        assertEquals(0, s.getLastRefreshTime());

        // Refresh 50% done -- nothing changes
        s.onCallback(null, 50, mClock);
        assertTrue(s.isRefreshing());
        assertFalse(s.canRefresh());
        assertEquals(0, s.getLastRefreshTime());

        // Refresh finish
        s.onCallback(null, 100, mClock);
        assertFalse(s.isRefreshing());
        assertTrue(s.canRefresh());
        assertEquals(mClock.mTime, s.getLastRefreshTime());

        // Refresh start without request
        s.onCallback(null, 0, mClock);
        assertTrue(s.isRefreshing());
        assertFalse(s.canRefresh());
        assertEquals(mClock.mTime, s.getLastRefreshTime());

        mClock.advance();

        // Refresh finish with error.
        s.onCallback(EXCEPTION, 0, mClock);
        assertFalse(s.isRefreshing());
        assertTrue(s.canRefresh());
        assertEquals(mClock.mTime, s.getLastRefreshTime());
    }

    public void testRefreshMailboxList() {
        // request refresh for account 1
        assertTrue(mTarget.refreshMailboxList(ACCOUNT_1));

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_1, mListener.mAccountId);
        assertEquals(-1, mListener.mMailboxId);
        mListener.reset();
        assertTrue(mController.mCalledUpdateMailboxList);
        assertEquals(ACCOUNT_1, mController.mAccountId);
        assertEquals(-1, mController.mMailboxId);
        mController.reset();
        assertTrue(mTarget.isMailboxListRefreshing(ACCOUNT_1));
        assertTrue(mTarget.isRefreshingAnyMailboxList());

        // Request again -- shouldn't be accepted.
        assertFalse(mTarget.refreshMailboxList(ACCOUNT_1));

        assertFalse(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        mListener.reset();
        assertFalse(mController.mCalledUpdateMailboxList);
        mController.reset();

        // request refresh for account 2
        assertTrue(mTarget.refreshMailboxList(ACCOUNT_2));

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_2, mListener.mAccountId);
        assertEquals(-1, mListener.mMailboxId);
        mListener.reset();
        assertTrue(mController.mCalledUpdateMailboxList);
        assertEquals(ACCOUNT_2, mController.mAccountId);
        assertEquals(-1, mController.mMailboxId);
        mController.reset();
        assertTrue(mTarget.isMailboxListRefreshing(ACCOUNT_2));
        assertTrue(mTarget.isRefreshingAnyMailboxList());

        // Refreshing for account 1...
        mController.mListener.updateMailboxListCallback(null, ACCOUNT_1, 0);

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_1, mListener.mAccountId);
        assertEquals(-1, mListener.mMailboxId);
        mListener.reset();
        assertTrue(mTarget.isMailboxListRefreshing(ACCOUNT_1));
        assertEquals(0, mTarget.getMailboxListStatusForTest(ACCOUNT_1).getLastRefreshTime());

        // Done.
        Log.w(Email.LOG_TAG, "" + mController.mListener.getClass());
        mController.mListener.updateMailboxListCallback(null, ACCOUNT_1, 100);

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_1, mListener.mAccountId);
        assertEquals(-1, mListener.mMailboxId);
        mListener.reset();
        assertFalse(mTarget.isMailboxListRefreshing(ACCOUNT_1));
        assertEquals(mClock.mTime, mTarget.getMailboxListStatusForTest(ACCOUNT_1)
                .getLastRefreshTime());

        // Check "any" method.
        assertTrue(mTarget.isRefreshingAnyMailboxList()); // still refreshing account 2

        // Refreshing for account 2...
        mClock.advance();

        mController.mListener.updateMailboxListCallback(null, ACCOUNT_2, 0);

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_2, mListener.mAccountId);
        assertEquals(-1, mListener.mMailboxId);
        mListener.reset();
        assertTrue(mTarget.isMailboxListRefreshing(ACCOUNT_2));
        assertEquals(0, mTarget.getMailboxListStatusForTest(ACCOUNT_2).getLastRefreshTime());

        // Done with exception.
        mController.mListener.updateMailboxListCallback(EXCEPTION, ACCOUNT_2, 0);

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertTrue(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_2, mListener.mAccountId);
        assertEquals(-1, mListener.mMailboxId);
        assertEquals(EXCEPTION.getUiErrorMessage(mContext), mListener.mMessage);
        mListener.reset();
        assertFalse(mTarget.isMailboxListRefreshing(ACCOUNT_2));
        assertEquals(mClock.mTime, mTarget.getMailboxListStatusForTest(ACCOUNT_2)
                .getLastRefreshTime());

        // Check "any" method.
        assertFalse(mTarget.isRefreshingAnyMailboxList());
    }

    public void testRefreshMessageList() {
        // request refresh mailbox 1
        assertTrue(mTarget.refreshMessageList(ACCOUNT_1, MAILBOX_1));

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_1, mListener.mAccountId);
        assertEquals(MAILBOX_1, mListener.mMailboxId);
        mListener.reset();
        assertTrue(mController.mCalledUpdateMailbox);
        assertEquals(ACCOUNT_1, mController.mAccountId);
        assertEquals(MAILBOX_1, mController.mMailboxId);
        mController.reset();
        assertTrue(mTarget.isMessageListRefreshing(MAILBOX_1));
        assertTrue(mTarget.isRefreshingAnyMessageList());

        // Request again -- shouldn't be accepted.
        assertFalse(mTarget.refreshMessageList(ACCOUNT_1, MAILBOX_1));

        assertFalse(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        mListener.reset();
        assertFalse(mController.mCalledUpdateMailbox);
        mController.reset();

        // request refresh mailbox 2
        assertTrue(mTarget.refreshMessageList(ACCOUNT_2, MAILBOX_2));

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_2, mListener.mAccountId);
        assertEquals(MAILBOX_2, mListener.mMailboxId);
        mListener.reset();
        assertTrue(mController.mCalledUpdateMailbox);
        assertEquals(ACCOUNT_2, mController.mAccountId);
        assertEquals(MAILBOX_2, mController.mMailboxId);
        mController.reset();
        assertTrue(mTarget.isMessageListRefreshing(MAILBOX_2));
        assertTrue(mTarget.isRefreshingAnyMessageList());

        // Refreshing mailbox 1...
        mController.mListener.updateMailboxCallback(null, ACCOUNT_1, MAILBOX_1, 0, 0);

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_1, mListener.mAccountId);
        assertEquals(MAILBOX_1, mListener.mMailboxId);
        mListener.reset();
        assertTrue(mTarget.isMessageListRefreshing(MAILBOX_1));
        assertEquals(0, mTarget.getMessageListStatusForTest(MAILBOX_1).getLastRefreshTime());

        // Done.
        Log.w(Email.LOG_TAG, "" + mController.mListener.getClass());
        mController.mListener.updateMailboxCallback(null, ACCOUNT_1, MAILBOX_1, 100, 0);

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_1, mListener.mAccountId);
        assertEquals(MAILBOX_1, mListener.mMailboxId);
        mListener.reset();
        assertFalse(mTarget.isMessageListRefreshing(MAILBOX_1));
        assertEquals(mClock.mTime, mTarget.getMessageListStatusForTest(MAILBOX_1)
                .getLastRefreshTime());

        // Check "any" method.
        assertTrue(mTarget.isRefreshingAnyMessageList()); // still refreshing mailbox 2

        // Refreshing mailbox 2...
        mClock.advance();

        mController.mListener.updateMailboxCallback(null, ACCOUNT_2, MAILBOX_2, 0, 0);

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_2, mListener.mAccountId);
        assertEquals(MAILBOX_2, mListener.mMailboxId);
        mListener.reset();
        assertTrue(mTarget.isMessageListRefreshing(MAILBOX_2));
        assertEquals(0, mTarget.getMessageListStatusForTest(MAILBOX_2).getLastRefreshTime());

        // Done with exception.
        mController.mListener.updateMailboxCallback(EXCEPTION, ACCOUNT_2, MAILBOX_2, 0, 0);

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertTrue(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_2, mListener.mAccountId);
        assertEquals(MAILBOX_2, mListener.mMailboxId);
        assertEquals(EXCEPTION.getUiErrorMessage(mContext), mListener.mMessage);
        mListener.reset();
        assertFalse(mTarget.isMessageListRefreshing(MAILBOX_2));
        assertEquals(mClock.mTime, mTarget.getMessageListStatusForTest(MAILBOX_2)
                .getLastRefreshTime());

        // Check "any" method.
        assertFalse(mTarget.isRefreshingAnyMessageList());
    }

    public void testSendPendingMessages() {
        // request sending for account 1
        assertTrue(mTarget.sendPendingMessages(ACCOUNT_1));

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_1, mListener.mAccountId);
        assertEquals(-1, mListener.mMailboxId);
        mListener.reset();
        assertTrue(mController.mCalledSendPendingMessages);
        assertEquals(ACCOUNT_1, mController.mAccountId);
        assertEquals(-1, mController.mMailboxId);
        mController.reset();
        assertTrue(mTarget.isSendingMessage(ACCOUNT_1));
        assertTrue(mTarget.isSendingAnyMessage());

        // Request again -- shouldn't be accepted.
        assertFalse(mTarget.sendPendingMessages(ACCOUNT_1));

        assertFalse(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        mListener.reset();
        assertFalse(mController.mCalledSendPendingMessages);
        mController.reset();

        // request sending for account 2
        assertTrue(mTarget.sendPendingMessages(ACCOUNT_2));

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_2, mListener.mAccountId);
        assertEquals(-1, mListener.mMailboxId);
        mListener.reset();
        assertTrue(mController.mCalledSendPendingMessages);
        assertEquals(ACCOUNT_2, mController.mAccountId);
        assertEquals(-1, mController.mMailboxId);
        mController.reset();
        assertTrue(mTarget.isSendingMessage(ACCOUNT_2));
        assertTrue(mTarget.isSendingAnyMessage());

        // sending for account 1...
        mController.mListener.sendMailCallback(null, ACCOUNT_1, -1, 0);

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_1, mListener.mAccountId);
        assertEquals(-1, mListener.mMailboxId);
        mListener.reset();
        assertTrue(mTarget.isSendingMessage(ACCOUNT_1));
        assertEquals(0, mTarget.getOutboxStatusForTest(ACCOUNT_1).getLastRefreshTime());

        // Per message callback (1)
        mController.mListener.sendMailCallback(null, ACCOUNT_1, 100, 0);
        mController.mListener.sendMailCallback(null, ACCOUNT_1, 101, 0);

        // No callback per message
        assertFalse(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        mListener.reset();

        // Exception -- first error will be reported.
        mController.mListener.sendMailCallback(EXCEPTION, ACCOUNT_1, 102, 0);

        assertFalse(mListener.mCalledOnRefreshStatusChanged);
        assertTrue(mListener.mCalledOnConnectionError);
        assertEquals(EXCEPTION.getUiErrorMessage(mContext), mListener.mMessage);
        mListener.reset();

        // Exception again -- no more error callbacks
        mController.mListener.sendMailCallback(null, ACCOUNT_1, 103, 0);
        mController.mListener.sendMailCallback(EXCEPTION, ACCOUNT_1, 104, 0);

        assertFalse(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        mListener.reset();

        // Done.
        Log.w(Email.LOG_TAG, "" + mController.mListener.getClass());
        mController.mListener.sendMailCallback(null, ACCOUNT_1, -1, 100);

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_1, mListener.mAccountId);
        assertEquals(-1, mListener.mMailboxId);
        mListener.reset();
        assertFalse(mTarget.isSendingMessage(ACCOUNT_1));
        assertEquals(mClock.mTime, mTarget.getOutboxStatusForTest(ACCOUNT_1)
                .getLastRefreshTime());

        // Check "any" method.
        assertTrue(mTarget.isSendingAnyMessage()); // still sending for account 2

        // sending for account 2...
        mClock.advance();

        mController.mListener.sendMailCallback(null, ACCOUNT_2, -1, 0);

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertFalse(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_2, mListener.mAccountId);
        assertEquals(-1, mListener.mMailboxId);
        mListener.reset();
        assertTrue(mTarget.isSendingMessage(ACCOUNT_2));
        assertEquals(0, mTarget.getOutboxStatusForTest(ACCOUNT_2).getLastRefreshTime());

        // Done with exception.
        mController.mListener.sendMailCallback(EXCEPTION, ACCOUNT_2, -1, 0);

        assertTrue(mListener.mCalledOnRefreshStatusChanged);
        assertTrue(mListener.mCalledOnConnectionError);
        assertEquals(ACCOUNT_2, mListener.mAccountId);
        assertEquals(-1, mListener.mMailboxId);
        assertEquals(EXCEPTION.getUiErrorMessage(mContext), mListener.mMessage);
        mListener.reset();
        assertFalse(mTarget.isSendingMessage(ACCOUNT_2));
        assertEquals(mClock.mTime, mTarget.getOutboxStatusForTest(ACCOUNT_2)
                .getLastRefreshTime());

        // Check "any" method.
        assertFalse(mTarget.isSendingAnyMessage());
    }

    public void testSendPendingMessagesForAllAccounts() {
        Account acct1 = ProviderTestUtils.setupAccount("acct1", true, mProviderContext);
        Account acct2 = ProviderTestUtils.setupAccount("acct2", true, mProviderContext);

        mTarget.sendPendingMessagesForAllAccountsSync();
        assertTrue(mController.mCalledSendPendingMessages);

        MoreAsserts.assertEquals(new Long[] {acct1.mId, acct2.mId}, mListener.getAccountIds());
    }

    private static class MockController extends Controller {
        public long mAccountId = -1;
        public long mMailboxId = -1;
        public boolean mCalledSendPendingMessages;
        public boolean mCalledUpdateMailbox;
        public boolean mCalledUpdateMailboxList;
        public Result mListener;

        protected MockController(Context context) {
            super(context);
        }

        public void reset() {
            mAccountId = -1;
            mMailboxId = -1;
            mCalledSendPendingMessages = false;
            mCalledUpdateMailbox = false;
            mCalledUpdateMailboxList = false;
        }

        @Override
        public void sendPendingMessages(long accountId) {
            mCalledSendPendingMessages = true;
            mAccountId = accountId;
        }

        @Override
        public void updateMailbox(long accountId, long mailboxId) {
            mCalledUpdateMailbox = true;
            mAccountId = accountId;
            mMailboxId = mailboxId;
        }

        @Override
        public void updateMailboxList(long accountId) {
            mCalledUpdateMailboxList = true;
            mAccountId = accountId;
        }

        @Override
        public void addResultCallback(Result listener) {
            Assert.assertTrue(mListener == null);
            mListener = listener;
        }
    }

    private static class RefreshListener implements RefreshManager.Listener {
        public long mAccountId = -1;
        public long mMailboxId = -1;
        public String mMessage;
        public boolean mCalledOnConnectionError;
        public boolean mCalledOnRefreshStatusChanged;
        private final ArrayList<Long> mAccountIds = new ArrayList<Long>();

        public void reset() {
            mAccountId = -1;
            mMailboxId = -1;
            mMessage = null;
            mAccountIds.clear();
            mCalledOnConnectionError = false;
            mCalledOnRefreshStatusChanged = false;
        }

        @Override
        public void onRefreshStatusChanged(long accountId, long mailboxId) {
            mAccountId = accountId;
            mMailboxId = mailboxId;
            mAccountIds.add(mAccountId);
            mCalledOnRefreshStatusChanged = true;
        }

        @Override
        public void onMessagingError(long accountId, long mailboxId, String message) {
            mAccountId = accountId;
            mMailboxId = mailboxId;
            mMessage = message;
            mAccountIds.add(mAccountId);
            mCalledOnConnectionError = true;
        }

        public Long[] getAccountIds() {
            return mAccountIds.toArray(new Long[0]);
        }
    }
}
