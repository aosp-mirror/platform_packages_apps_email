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

package com.android.email.activity;

import com.android.email.DBTestHelper;
import com.android.email.MockClock;
import com.android.email.provider.ContentCache;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Mailbox;

import android.content.Context;
import android.test.AndroidTestCase;

import java.util.ArrayList;

/**
 * Tests for the recent mailbox manager.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.activity.RecentMailboxManagerTest email
 */
public class RecentMailboxManagerTest extends AndroidTestCase {

    private Context mMockContext;
    private MockClock mMockClock;
    private RecentMailboxManager mManager;
    private Mailbox[] mMailboxArray;

    public RecentMailboxManagerTest() {
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(
                getContext());
        mMockClock = new MockClock();
        RecentMailboxManager.sClock = mMockClock;
        mManager = RecentMailboxManager.getInstance(mMockContext);
        mMailboxArray = new Mailbox[] {
            ProviderTestUtils.setupMailbox("inbox", 1L, true, mMockContext, Mailbox.TYPE_INBOX),
            ProviderTestUtils.setupMailbox("drafts", 1L, true, mMockContext, Mailbox.TYPE_DRAFTS),
            ProviderTestUtils.setupMailbox("outbox", 1L, true, mMockContext, Mailbox.TYPE_OUTBOX),
            ProviderTestUtils.setupMailbox("sent", 1L, true, mMockContext, Mailbox.TYPE_SENT),
            ProviderTestUtils.setupMailbox("trash", 1L, true, mMockContext, Mailbox.TYPE_TRASH),
            ProviderTestUtils.setupMailbox("junk", 1L, true, mMockContext, Mailbox.TYPE_JUNK),
            ProviderTestUtils.setupMailbox("abbott", 1L, true, mMockContext, Mailbox.TYPE_MAIL),
            ProviderTestUtils.setupMailbox("costello", 1L, true, mMockContext, Mailbox.TYPE_MAIL),
            ProviderTestUtils.setupMailbox("bud_lou", 1L, true, mMockContext, Mailbox.TYPE_MAIL),
            ProviderTestUtils.setupMailbox("laurel", 1L, true, mMockContext, Mailbox.TYPE_MAIL),
            ProviderTestUtils.setupMailbox("hardy", 1L, true, mMockContext, Mailbox.TYPE_MAIL),
        };
    }

    @Override
    protected void tearDown() throws Exception {
        RecentMailboxManager.sInstance = null;
        super.tearDown();
    }

    public void testTouch() throws Exception {
        // Ensure all accounts can be touched
        for (Mailbox mailbox : mMailboxArray) {
            // Safety ... default touch time
            Mailbox untouchedMailbox = Mailbox.restoreMailboxWithId(mMockContext, mailbox.mId);
            assertEquals(0L, untouchedMailbox.mLastTouchedTime);

            // Touch the mailbox
            mManager.touch(mailbox.mId).get();

            // Touch time is actually set
            Mailbox touchedMailbox = Mailbox.restoreMailboxWithId(mMockContext, mailbox.mId);
            assertEquals(mMockClock.getTime(), touchedMailbox.mLastTouchedTime);

            mMockClock.advance(1000L);
        }
        // Now ensure touching one didn't affect the others
        long touchTime = MockClock.DEFAULT_TIME;
        for (Mailbox mailbox : mMailboxArray) {
            // Touch time is actually set
            Mailbox touchedMailbox = Mailbox.restoreMailboxWithId(mMockContext, mailbox.mId);
            assertEquals(touchTime, touchedMailbox.mLastTouchedTime);
            touchTime += 1000L;
        }
    }

    /** Test default list */
    public void testGetMostRecent01() throws Exception {
        ArrayList<Long> testList;

        // test default list
        // With exclusions
        testList = mManager.getMostRecent(1L, true);
        assertEquals("w/ exclusions", 0, testList.size());

        // Without exclusions -- we'll get "default" list.
        testList = mManager.getMostRecent(1L, false);
        assertEquals("w/o exclusions", 2, testList.size());

        assertEquals(mMailboxArray[1].mId, (long) testList.get(0)); // Drafts
        assertEquals(mMailboxArray[3].mId, (long) testList.get(1)); // Sent
    }

    /** Test recent list not full */
    public void testGetMostRecent02() throws Exception {
        ArrayList<Long> testList;
        // touch some mailboxes
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[0].mId); // inbox
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[3].mId); // sent
        // need to wait for the last one to ensure getMostRecent() has something to work on
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[7].mId).get(); // user mailbox #2

        // test recent list not full
        testList = mManager.getMostRecent(1L, false);
        assertEquals(3, testList.size());
        assertEquals(mMailboxArray[7].mId, (long) testList.get(0));
        assertEquals(mMailboxArray[0].mId, (long) testList.get(1));
        assertEquals(mMailboxArray[3].mId, (long) testList.get(2));
        testList = mManager.getMostRecent(1L, true);
        assertEquals(1, testList.size());
        assertEquals(mMailboxArray[7].mId, (long) testList.get(0));
    }

    /** Test full recent list */
    public void testGetMostRecent03() throws Exception {
        ArrayList<Long> testList;

        // touch some more mailboxes
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[3].mId); // sent
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[4].mId); // trash
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[2].mId); // outbox
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[8].mId); // user mailbox #3
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[7].mId).get(); // user mailbox #2

        // test full recent list
        testList = mManager.getMostRecent(1L, false);
        assertEquals(5, testList.size());
        assertEquals(mMailboxArray[8].mId, (long) testList.get(0));
        assertEquals(mMailboxArray[7].mId, (long) testList.get(1));
        assertEquals(mMailboxArray[2].mId, (long) testList.get(2));
        assertEquals(mMailboxArray[3].mId, (long) testList.get(3));
        assertEquals(mMailboxArray[4].mId, (long) testList.get(4));
        testList = mManager.getMostRecent(1L, true);
        assertEquals(2, testList.size());
        assertEquals(mMailboxArray[8].mId, (long) testList.get(0));
        assertEquals(mMailboxArray[7].mId, (long) testList.get(1));
    }

    /** Test limit for system mailboxes */
    public void testGetMostRecent04() throws Exception {
        ArrayList<Long> testList;

        mMockClock.advance(1000L); mManager.touch(mMailboxArray[0].mId); // inbox
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[1].mId); // drafts
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[2].mId); // outbox
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[3].mId); // sent
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[4].mId).get(); // trash

        // nothing but system mailboxes
        testList = mManager.getMostRecent(1L, false);
        assertEquals(5, testList.size());
        assertEquals(mMailboxArray[1].mId, (long) testList.get(0));
        assertEquals(mMailboxArray[0].mId, (long) testList.get(1));
        assertEquals(mMailboxArray[2].mId, (long) testList.get(2));
        assertEquals(mMailboxArray[3].mId, (long) testList.get(3));
        assertEquals(mMailboxArray[4].mId, (long) testList.get(4));
        testList = mManager.getMostRecent(1L, true);
        assertEquals(0, testList.size());
    }

    /** Test limit for user mailboxes */
    public void testGetMostRecent05() throws Exception {
        ArrayList<Long> testList;

        // test limit for the filtered list
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[6].mId); // abbott
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[7].mId); // costello
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[8].mId); // bud_lou
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[9].mId); // laurel
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[10].mId); // hardy
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[0].mId); // inbox
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[1].mId); // drafts
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[2].mId); // outbox
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[3].mId); // sent
        mMockClock.advance(1000L); mManager.touch(mMailboxArray[4].mId).get(); // trash

        // nothing but user mailboxes
        testList = mManager.getMostRecent(1L, false);
        assertEquals(5, testList.size());
        assertEquals(mMailboxArray[1].mId, (long) testList.get(0));
        assertEquals(mMailboxArray[0].mId, (long) testList.get(1));
        assertEquals(mMailboxArray[2].mId, (long) testList.get(2));
        assertEquals(mMailboxArray[3].mId, (long) testList.get(3));
        assertEquals(mMailboxArray[4].mId, (long) testList.get(4));
        testList = mManager.getMostRecent(1L, true);
        assertEquals(5, testList.size());
        assertEquals(mMailboxArray[6].mId, (long) testList.get(0));
        assertEquals(mMailboxArray[8].mId, (long) testList.get(1));
        assertEquals(mMailboxArray[7].mId, (long) testList.get(2));
        assertEquals(mMailboxArray[10].mId, (long) testList.get(3));
        assertEquals(mMailboxArray[9].mId, (long) testList.get(4));
    }
}
