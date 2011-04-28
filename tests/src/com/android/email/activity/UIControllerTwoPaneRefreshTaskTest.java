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

import com.android.email.Clock;
import com.android.email.Controller;
import com.android.email.MockClock;
import com.android.email.RefreshManager;

import android.content.Context;
import android.os.Handler;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.Assert;

/**
 * Tests for {@link UIControllerTwoPane.RefreshTask}.
 *
 * TOOD Add more tests.
 * Right now, it only has tests for the "shouldXxx" methods, because it's hard to notice when
 * they're subtly broken.  (And the spec may change.)
 */
@SmallTest
public class UIControllerTwoPaneRefreshTaskTest extends AndroidTestCase {
    private MockClock mClock = new MockClock();
    private MockRefreshManager mRefreshManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mRefreshManager = new MockRefreshManager(getContext(), Controller.getInstance(getContext()),
                mClock, null);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mRefreshManager.cleanUpForTest();
    }

    public void testShouldRefreshMailboxList() {
        final long ACCOUNT_ID = 5;
        final long MAILBOX_ID = 10;

        UIControllerTwoPane.RefreshTask task = new UIControllerTwoPane.RefreshTask(null,
                getContext(), ACCOUNT_ID, MAILBOX_ID, mClock, mRefreshManager);

        mRefreshManager.mExpectedAccountId = ACCOUNT_ID;

        mClock.mTime = 123456789;

        // Not refreshing, never refreshed == should sync
        mRefreshManager.mIsMailboxListRefreshing = false;
        mRefreshManager.mLastMailboxListRefresTime = 0;
        assertTrue(task.shouldRefreshMailboxList());

        // IS refreshing, never refreshed == should NOT sync
        mRefreshManager.mIsMailboxListRefreshing = true;
        mRefreshManager.mLastMailboxListRefresTime = 0;
        assertFalse(task.shouldRefreshMailboxList());

        // Not refreshing, just refreshed == should NOT sync
        mRefreshManager.mIsMailboxListRefreshing = false;
        mRefreshManager.mLastMailboxListRefresTime = 1234567890;
        mClock.mTime = mRefreshManager.mLastMailboxListRefresTime;
        assertFalse(task.shouldRefreshMailboxList());

        // Not refreshing, refreshed 1 ms ago == should NOT sync
        mRefreshManager.mLastMailboxListRefresTime = 1234567890;
        mClock.mTime = mRefreshManager.mLastMailboxListRefresTime + 1;
        assertFalse(task.shouldRefreshMailboxList());

        // Not refreshing, refreshed TIMEOUT-1 ago == should NOT sync
        mRefreshManager.mLastMailboxListRefresTime = 1234567890;
        mClock.mTime = mRefreshManager.mLastMailboxListRefresTime
            + UIControllerTwoPane.MAILBOX_REFRESH_MIN_INTERVAL - 1;
        assertFalse(task.shouldRefreshMailboxList());

        // 1 ms laster... should sync.
        mClock.advance();
        assertTrue(task.shouldRefreshMailboxList());
    }

    public void testShouldAutoRefreshInbox() {
        final long ACCOUNT_ID = 5;
        final long MAILBOX_ID = 10;

        UIControllerTwoPane.RefreshTask task = new UIControllerTwoPane.RefreshTask(null,
                getContext(), ACCOUNT_ID, MAILBOX_ID, mClock, mRefreshManager);

        mRefreshManager.mExpectedAccountId = ACCOUNT_ID;

        mClock.mTime = 123456789;

        // Current mailbox != inbox, not refreshing, never refreshed == should sync
        mRefreshManager.mIsMessageListRefreshing = false;
        mRefreshManager.mLastMessageListRefresTime = 0;
        task.mInboxId = MAILBOX_ID + 1;
        mRefreshManager.mExpectedMailboxId = MAILBOX_ID + 1;
        assertTrue(task.shouldAutoRefreshInbox());

        // Current mailbox == inbox should NOT sync.
        task.mInboxId = MAILBOX_ID;
        mRefreshManager.mExpectedMailboxId = MAILBOX_ID;
        assertFalse(task.shouldAutoRefreshInbox());

        // Fron here, Current mailbox != inbox
        task.mInboxId = MAILBOX_ID + 1;
        mRefreshManager.mExpectedMailboxId = MAILBOX_ID + 1;

        // IS refreshing, never refreshed == should NOT sync
        mRefreshManager.mIsMessageListRefreshing = true;
        mRefreshManager.mLastMessageListRefresTime = 0;
        assertFalse(task.shouldAutoRefreshInbox());

        // Not refreshing, just refreshed == should NOT sync
        mRefreshManager.mIsMessageListRefreshing = false;
        mRefreshManager.mLastMessageListRefresTime = 1234567890;
        mClock.mTime = mRefreshManager.mLastMessageListRefresTime;
        assertFalse(task.shouldAutoRefreshInbox());

        // Not refreshing, refreshed 1 ms ago == should NOT sync
        mRefreshManager.mLastMessageListRefresTime = 1234567890;
        mClock.mTime = mRefreshManager.mLastMessageListRefresTime + 1;
        assertFalse(task.shouldAutoRefreshInbox());

        // Not refreshing, refreshed TIMEOUT-1 ago == should NOT sync
        mRefreshManager.mLastMessageListRefresTime = 1234567890;
        mClock.mTime = mRefreshManager.mLastMessageListRefresTime
            + UIControllerTwoPane.INBOX_AUTO_REFRESH_MIN_INTERVAL - 1;
        assertFalse(task.shouldAutoRefreshInbox());

        // 1 ms laster... should sync.
        mClock.advance();
        assertTrue(task.shouldAutoRefreshInbox());
    }

    private static class MockRefreshManager extends RefreshManager {
        public long mExpectedAccountId;
        public long mExpectedMailboxId;
        public boolean mIsMailboxListRefreshing;
        public long mLastMailboxListRefresTime;
        public boolean mIsMessageListRefreshing;
        public long mLastMessageListRefresTime;

        protected MockRefreshManager(
                Context context, Controller controller, Clock clock, Handler handler) {
            super(context, controller, clock, handler);
        }

        @Override
        public boolean isMailboxListRefreshing(long accountId) {
            Assert.assertEquals(mExpectedAccountId, accountId);
            return mIsMailboxListRefreshing;
        }

        @Override
        public long getLastMailboxListRefreshTime(long accountId) {
            Assert.assertEquals(mExpectedAccountId, accountId);
            return mLastMailboxListRefresTime;
        }

        @Override
        public boolean isMessageListRefreshing(long mailboxId) {
            Assert.assertEquals(mExpectedMailboxId, mailboxId);
            return mIsMessageListRefreshing;
        }

        @Override
        public long getLastMessageListRefreshTime(long mailboxId) {
            Assert.assertEquals(mExpectedMailboxId, mailboxId);
            return mLastMessageListRefresTime;
        }
    }
}
