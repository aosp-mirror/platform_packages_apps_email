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

import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;

import android.content.Context;
import android.database.Cursor;
import android.test.ProviderTestCase2;

import junit.framework.Assert;

public class MailboxesAdapterTest extends ProviderTestCase2<EmailProvider> {

    private Context mMockContext;

    public MailboxesAdapterTest() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
    }

    public void testAddSummaryMailboxRow() {
        final Context c = mMockContext;

        // Prepare test data
        Account a1 = ProviderTestUtils.setupAccount("a1", true, c);
        Account a2 = ProviderTestUtils.setupAccount("a2", true, c);

        Mailbox b1i = ProviderTestUtils.setupMailbox("box1i", a1.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox b2i = ProviderTestUtils.setupMailbox("box2i", a2.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox b1o = ProviderTestUtils.setupMailbox("box1i", a1.mId, true, c, Mailbox.TYPE_OUTBOX);
        Mailbox b2o = ProviderTestUtils.setupMailbox("box2i", a2.mId, true, c, Mailbox.TYPE_OUTBOX);
        Mailbox b1d = ProviderTestUtils.setupMailbox("box1d", a1.mId, true, c, Mailbox.TYPE_DRAFTS);
        Mailbox b2d = ProviderTestUtils.setupMailbox("box2d", a2.mId, true, c, Mailbox.TYPE_DRAFTS);
        Mailbox b1t = ProviderTestUtils.setupMailbox("box1t", a1.mId, true, c, Mailbox.TYPE_TRASH);
        Mailbox b2t = ProviderTestUtils.setupMailbox("box2t", a2.mId, true, c, Mailbox.TYPE_TRASH);

        createMessage(c, b1i, false, false);
        createMessage(c, b2i, true, true);
        createMessage(c, b2i, true, false);

        createMessage(c, b1o, true, true);
        createMessage(c, b2o, false, true);

        createMessage(c, b1d, false, true);
        createMessage(c, b2d, false, true);
        createMessage(c, b2d, false, true);
        createMessage(c, b2d, false, true);

        createMessage(c, b2t, true, true); // Starred message in trash; All Starred excludes it.

        // Kick the method
        Cursor cursor = MailboxesAdapter.getSpecialMailboxesCursor(c);

        // Check the result
        assertEquals(4, cursor.getCount());

        // Row 1 -- combined inbox (with unread count)
        assertTrue(cursor.moveToFirst());
        checkSpecialMailboxRow(cursor, Mailbox.QUERY_ALL_INBOXES, Mailbox.TYPE_INBOX, 2);

        // Row 2 -- all starred (with total count)
        assertTrue(cursor.moveToNext());
        checkSpecialMailboxRow(cursor, Mailbox.QUERY_ALL_FAVORITES, Mailbox.TYPE_MAIL, 3);

        // Row 3 -- all drafts (with total count)
        assertTrue(cursor.moveToNext());
        checkSpecialMailboxRow(cursor, Mailbox.QUERY_ALL_DRAFTS, Mailbox.TYPE_DRAFTS, 4);

        // Row 4 -- combined outbox (with total count)
        assertTrue(cursor.moveToNext());
        checkSpecialMailboxRow(cursor, Mailbox.QUERY_ALL_OUTBOX, Mailbox.TYPE_OUTBOX, 2);
    }

    private static Message createMessage(Context c, Mailbox b, boolean starred, boolean read) {
        return ProviderTestUtils.setupMessage("m", b.mAccountKey, b.mId, false, true, c, starred,
                read);
    }

    private static void checkSpecialMailboxRow(Cursor cursor, long id, int type,
            int count) {
        // _id must always be >= 0; otherwise ListView gets confused.
        Assert.assertTrue(cursor.getLong(cursor.getColumnIndex("_id")) >= 0);
        Assert.assertEquals(id, MailboxesAdapter.getIdForTest(cursor));
        Assert.assertEquals(type, MailboxesAdapter.getTypeForTest(cursor));
        Assert.assertEquals(count, MailboxesAdapter.getMessageCountForTest(cursor));
        Assert.assertEquals(count, MailboxesAdapter.getUnreadCountForTest(cursor));
    }
}
