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
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;

import android.content.Context;
import android.database.Cursor;
import android.test.ProviderTestCase2;

import junit.framework.Assert;

public class MailboxFragmentAdapterTest extends ProviderTestCase2<EmailProvider> {

    private Context mMockContext;

    public MailboxFragmentAdapterTest() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
    }

    public void testBuildCombinedMailboxes() {
        final Context c = mMockContext;

        // Prepare test data
        Account a1 = ProviderTestUtils.setupAccount("a1", true, c);
        Account a2 = ProviderTestUtils.setupAccount("a2", true, c);
        Account a3 = ProviderTestUtils.setupAccount("a3", true, c);

        Mailbox b1i = ProviderTestUtils.setupMailbox("box1i", a1.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox b2i = ProviderTestUtils.setupMailbox("box2i", a2.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox b3i = ProviderTestUtils.setupMailbox("box3i", a3.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox b1o = ProviderTestUtils.setupMailbox("box1i", a1.mId, true, c, Mailbox.TYPE_OUTBOX);
        Mailbox b2o = ProviderTestUtils.setupMailbox("box2i", a2.mId, true, c, Mailbox.TYPE_OUTBOX);
        Mailbox b1d = ProviderTestUtils.setupMailbox("box1d", a1.mId, true, c, Mailbox.TYPE_DRAFTS);
        Mailbox b2d = ProviderTestUtils.setupMailbox("box2d", a2.mId, true, c, Mailbox.TYPE_DRAFTS);
        Mailbox b1t = ProviderTestUtils.setupMailbox("box1t", a1.mId, true, c, Mailbox.TYPE_TRASH);
        Mailbox b2t = ProviderTestUtils.setupMailbox("box2t", a2.mId, true, c, Mailbox.TYPE_TRASH);

        createMessage(c, b1i, false, false, Message.FLAG_LOADED_COMPLETE);
        createMessage(c, b2i, true, true, Message.FLAG_LOADED_COMPLETE);
        createMessage(c, b2i, true, false, Message.FLAG_LOADED_COMPLETE);
        // "unloaded" messages will not affect 'favorite' message count
        createMessage(c, b3i, true, true, Message.FLAG_LOADED_UNLOADED);

        createMessage(c, b1o, true, true, Message.FLAG_LOADED_COMPLETE);
        createMessage(c, b2o, false, true, Message.FLAG_LOADED_COMPLETE);

        createMessage(c, b1d, false, true, Message.FLAG_LOADED_COMPLETE);
        createMessage(c, b2d, false, true, Message.FLAG_LOADED_COMPLETE);
        createMessage(c, b2d, false, true, Message.FLAG_LOADED_COMPLETE);
        createMessage(c, b2d, false, true, Message.FLAG_LOADED_COMPLETE);

        // Starred message in trash; All Starred excludes it.
        createMessage(c, b2t, true, true, Message.FLAG_LOADED_UNLOADED);

        // Kick the method
        Cursor cursor = MailboxFragmentAdapter.CombinedMailboxLoader.buildCombinedMailboxes(c,
                null);

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

    private static Message createMessage(Context c, Mailbox b, boolean starred, boolean read,
            int flagLoaded) {
        Message message = ProviderTestUtils.setupMessage(
                "1", b.mAccountKey, b.mId, true, false, c, starred, read);
        message.mFlagLoaded = flagLoaded;
        message.save(c);
        return message;
    }

    private static void checkSpecialMailboxRow(Cursor cursor, long id, int type,
            int count) {
        // _id must always be >= 0; otherwise ListView gets confused.
        Assert.assertTrue(cursor.getLong(cursor.getColumnIndex("_id")) >= 0);
        Assert.assertEquals(id, MailboxFragmentAdapter.getId(cursor));
        Assert.assertEquals(type, MailboxFragmentAdapter.getType(cursor));
        Assert.assertEquals(count, MailboxFragmentAdapter.getMessageCount(cursor));
        Assert.assertEquals(count, MailboxFragmentAdapter.getUnreadCount(cursor));
        Assert.assertEquals(Account.ACCOUNT_ID_COMBINED_VIEW,
                MailboxFragmentAdapter.getAccountId(cursor));
    }
}
