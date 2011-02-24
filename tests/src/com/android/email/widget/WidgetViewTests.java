/* Copyright (C) 2011 The Android Open Source Project
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

package com.android.email.widget;

import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.provider.EmailContent.Message;

import android.content.Context;
import android.test.ProviderTestCase2;

/**
 * Tests of EmailWidget
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.widget.WidgetView email
 */
public class WidgetViewTests extends ProviderTestCase2<EmailProvider> {
    private Context mMockContext;

    public WidgetViewTests() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    private int getMessageCount(WidgetView view) {
        return EmailContent.count(mMockContext, Message.CONTENT_URI,
                view.getSelection(), view.getSelectionArgs());
    }

    private int getUnreadCount(WidgetView view) {
        return view.getUnreadCount(mMockContext);
    }

    private static Message createMessage(Context c, Mailbox b, boolean starred, boolean read,
            int flagLoaded) {
        Message message = ProviderTestUtils.setupMessage(
                "1", b.mAccountKey, b.mId, true, false, c, starred, read);
        message.mFlagLoaded = flagLoaded;
        message.save(c);
        return message;
    }

    public void testGetNext() {
        // Test with 1 account.
        final Account a1 = ProviderTestUtils.setupAccount("account1", true, mMockContext);

        WidgetView view = WidgetView.ALL_UNREAD;

        // all unread -> all starred
        view = view.getNext(mMockContext);
        assertEquals(WidgetView.ALL_STARRED, view);

        // all starred -> account 1 inbox
        view = view.getNext(mMockContext);
        assertTrue(view.isPerAccount());
        assertEquals(Long.toString(a1.mId), view.getSelectionArgs()[0]);

        // account 1 inbox -> all unread
        view = view.getNext(mMockContext);
        assertEquals(WidgetView.ALL_UNREAD, view);

        // Next, test with 2 accounts.
        final Account a2 = ProviderTestUtils.setupAccount("account2", true, mMockContext);

        // Still all unread
        assertEquals(WidgetView.ALL_UNREAD, view);

        // all unread -> all starred
        view = view.getNext(mMockContext);
        assertEquals(WidgetView.ALL_STARRED, view);

        // all starred -> all inboxes, as there are more than 1 account.
        view = view.getNext(mMockContext);
        assertEquals(WidgetView.ALL_INBOX, view);

        // all inbox -> account 1 inbox
        view = view.getNext(mMockContext);
        assertTrue(view.isPerAccount());
        assertEquals(Long.toString(a1.mId), view.getSelectionArgs()[0]);

        // account 1 inbox -> account 2 inbox
        view = view.getNext(mMockContext);
        assertTrue(view.isPerAccount());
        assertEquals(Long.toString(a2.mId), view.getSelectionArgs()[0]);

        // account 2 inbox -> all unread
        view = view.getNext(mMockContext);
        assertEquals(WidgetView.ALL_UNREAD, view);
    }

    public void testIsValid() {
        // with 0 accounts
        assertTrue(WidgetView.ALL_UNREAD.isValid(mMockContext));
        assertTrue(WidgetView.ALL_STARRED.isValid(mMockContext));
        assertFalse(WidgetView.ALL_INBOX.isValid(mMockContext));

        // Test with 1 account.
        final Account a1 = ProviderTestUtils.setupAccount("account1", true, mMockContext);
        assertTrue(WidgetView.ALL_UNREAD.isValid(mMockContext));
        assertTrue(WidgetView.ALL_STARRED.isValid(mMockContext));
        assertFalse(WidgetView.ALL_INBOX.isValid(mMockContext)); // only 1 account -- still invalid

        final WidgetView account1View = WidgetView.ALL_INBOX.getNext(mMockContext);
        assertEquals(Long.toString(a1.mId), account1View.getSelectionArgs()[0]);
        assertTrue(account1View.isValid(mMockContext));

        // Test with 2 accounts.
        final Account a2 = ProviderTestUtils.setupAccount("account2", true, mMockContext);
        assertTrue(WidgetView.ALL_UNREAD.isValid(mMockContext));
        assertTrue(WidgetView.ALL_STARRED.isValid(mMockContext));
        assertTrue(WidgetView.ALL_INBOX.isValid(mMockContext)); // now it's valid

        final WidgetView account2View = account1View.getNext(mMockContext);
        assertEquals(Long.toString(a2.mId), account2View.getSelectionArgs()[0]);
        assertTrue(account2View.isValid(mMockContext));

        // Remove account 1
        ProviderTestUtils.deleteAccount(mMockContext, a1.mId);

        assertTrue(WidgetView.ALL_UNREAD.isValid(mMockContext));
        assertTrue(WidgetView.ALL_STARRED.isValid(mMockContext));
        assertFalse(WidgetView.ALL_INBOX.isValid(mMockContext)); // only 1 account -- now invalid

        assertFalse(account1View.isValid(mMockContext));
        assertTrue(account2View.isValid(mMockContext));

        // Remove account 2
        ProviderTestUtils.deleteAccount(mMockContext, a2.mId);

        assertTrue(WidgetView.ALL_UNREAD.isValid(mMockContext));
        assertTrue(WidgetView.ALL_STARRED.isValid(mMockContext));
        assertFalse(WidgetView.ALL_INBOX.isValid(mMockContext)); // still invalid

        assertFalse(account1View.isValid(mMockContext));
        assertFalse(account2View.isValid(mMockContext));
    }

    /**
     * Test the message counts returned by the ViewType selectors.
     */
    public void testCursorCount() {
        // Create 2 accounts
        Account a1 = ProviderTestUtils.setupAccount("account1", true, mMockContext);
        Account a2 = ProviderTestUtils.setupAccount("account2", true, mMockContext);

        // Create 2 mailboxes for each account
        Mailbox b11 = ProviderTestUtils.setupMailbox(
                "box11", a1.mId, true, mMockContext, Mailbox.TYPE_INBOX);
        Mailbox b12 = ProviderTestUtils.setupMailbox(
                "box12", a1.mId, true, mMockContext, Mailbox.TYPE_OUTBOX);
        Mailbox b21 = ProviderTestUtils.setupMailbox(
                "box21", a2.mId, true, mMockContext, Mailbox.TYPE_INBOX);
        Mailbox b22 = ProviderTestUtils.setupMailbox(
                "box22", a2.mId, true, mMockContext, Mailbox.TYPE_OUTBOX);
        Mailbox b2t = ProviderTestUtils.setupMailbox(
                "box2T", a2.mId, true, mMockContext, Mailbox.TYPE_TRASH);

        // Create some messages
        // b11 (account 1, inbox): 2 messages
        //                                              star  read
        Message m11a = createMessage(mMockContext, b11, true, false, Message.FLAG_LOADED_COMPLETE);
        Message m11b = createMessage(mMockContext, b11, false, false, Message.FLAG_LOADED_UNLOADED);

        // b12 (account 1, outbox): 2 messages
        Message m12a = createMessage(mMockContext, b12, false, false, Message.FLAG_LOADED_COMPLETE);
        Message m12b = createMessage(mMockContext, b12, true, true, Message.FLAG_LOADED_COMPLETE);

        // b21 (account 2, inbox): 4 messages
        Message m21a = createMessage(mMockContext, b21, false, false, Message.FLAG_LOADED_COMPLETE);
        Message m21b = createMessage(mMockContext, b21, false, true, Message.FLAG_LOADED_COMPLETE);
        Message m21c = createMessage(mMockContext, b21, true, true, Message.FLAG_LOADED_COMPLETE);
        Message m21d = createMessage(mMockContext, b21, true, true, Message.FLAG_LOADED_UNLOADED);

        // b22 (account 2, outbox) has no messages.

        // bt (account 2, trash): 3 messages
        Message mt1 = createMessage(mMockContext, b2t, true, false, Message.FLAG_LOADED_COMPLETE);
        Message mt2 = createMessage(mMockContext, b2t, true, true, Message.FLAG_LOADED_COMPLETE);
        Message mt3 = createMessage(mMockContext, b2t, false, false, Message.FLAG_LOADED_COMPLETE);

        assertEquals(4, getMessageCount(WidgetView.ALL_INBOX));
        assertEquals(2, getUnreadCount(WidgetView.ALL_INBOX));

        assertEquals(3, getMessageCount(WidgetView.ALL_STARRED));
        assertEquals(1, getUnreadCount(WidgetView.ALL_STARRED));

        assertEquals(2, getMessageCount(WidgetView.ALL_UNREAD));
        assertEquals(2, getUnreadCount(WidgetView.ALL_UNREAD));

        final WidgetView account1View = WidgetView.ALL_INBOX.getNext(mMockContext);
        assertEquals(Long.toString(a1.mId), account1View.getSelectionArgs()[0]);
        assertEquals(1, getMessageCount(account1View));
        assertEquals(1, getUnreadCount(account1View));

        final WidgetView account2View = account1View.getNext(mMockContext);
        assertEquals(Long.toString(a2.mId), account2View.getSelectionArgs()[0]);
        assertEquals(3, getMessageCount(account2View));
        assertEquals(1, getUnreadCount(account2View));
    }
}
