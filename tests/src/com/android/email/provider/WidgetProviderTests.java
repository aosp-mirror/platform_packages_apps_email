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

package com.android.email.provider;

import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.WidgetProvider;
import com.android.email.provider.WidgetProvider.EmailWidget;
import com.android.email.provider.WidgetProvider.ViewType;
import com.android.email.provider.WidgetProvider.WidgetViewSwitcher;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.test.ProviderTestCase2;

import java.util.concurrent.ExecutionException;

/**
 * Tests of WidgetProvider
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.provider.WidgetProviderTests email
 */
public class WidgetProviderTests extends ProviderTestCase2<EmailProvider> {
    private Context mMockContext;

    public WidgetProviderTests() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
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

    /**
     * Switch views synchronously without loading
     */
    private void switchSync(EmailWidget widget) {
        WidgetViewSwitcher switcher = new WidgetProvider.WidgetViewSwitcher(widget);
        try {
            switcher.disableLoadAfterSwitchForTest();
            switcher.execute().get();
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        }
    }

    private int getMessageCount(ViewType view) {
        int messageCount = 0;
        ContentResolver cr = mMockContext.getContentResolver();
        Cursor c = cr.query(Message.CONTENT_URI, WidgetProvider.EmailWidget.WIDGET_PROJECTION,
                view.selection, view.selectionArgs, null);
        try {
            messageCount = c.getCount();
        } finally {
            c.close();
        }
        return messageCount;
    }

    private static Message createMessage(Context c, Mailbox b, boolean starred, boolean read,
            int flagLoaded) {
        Message message = ProviderTestUtils.setupMessage(
                "1", b.mAccountKey, b.mId, true, false, c, starred, read);
        message.mFlagLoaded = flagLoaded;
        message.save(c);
        return message;
    }

    public void testWidgetSwitcher() {
        // Create account
        ProviderTestUtils.setupAccount("account1", true, mMockContext);
        // Manually set up context
        WidgetProvider.setContextForTest(mMockContext);
        // Create a widget
        EmailWidget widget = new EmailWidget(1);
        // Since there is one account, this should switch to the ACCOUNT view
        switchSync(widget);
        assertEquals(WidgetProvider.ViewType.ACCOUNT, widget.mViewType);

        // Create account
        ProviderTestUtils.setupAccount("account2", true, mMockContext);
        // Create a widget
        widget = new EmailWidget(2);
        // Since there are two accounts, this should switch to the ALL_INBOX view
        switchSync(widget);
        assertEquals(WidgetProvider.ViewType.ALL_INBOX, widget.mViewType);

        // The next two switches should be to the two accounts
        switchSync(widget);
        assertEquals(WidgetProvider.ViewType.ACCOUNT, widget.mViewType);
        switchSync(widget);
        assertEquals(WidgetProvider.ViewType.ACCOUNT, widget.mViewType);
        switchSync(widget);
        assertEquals(WidgetProvider.ViewType.UNREAD, widget.mViewType);
        switchSync(widget);
        assertEquals(WidgetProvider.ViewType.STARRED, widget.mViewType);
    }

    /**
     * Test the message counts returned by the ViewType selectors.
     */
    public void testCursorCount() {
        // Create 2 accounts
        Account a1 = ProviderTestUtils.setupAccount("account1", true, mMockContext);
        Account a2 = ProviderTestUtils.setupAccount("account2", true, mMockContext);

        // Create 2 mailboxes for each account
        Mailbox b1 = ProviderTestUtils.setupMailbox(
                "box1", a1.mId, true, mMockContext, Mailbox.TYPE_INBOX);
        Mailbox b2 = ProviderTestUtils.setupMailbox(
                "box2", a1.mId, true, mMockContext, Mailbox.TYPE_OUTBOX);
        Mailbox b3 = ProviderTestUtils.setupMailbox(
                "box3", a2.mId, true, mMockContext, Mailbox.TYPE_INBOX);
        Mailbox b4 = ProviderTestUtils.setupMailbox(
                "box4", a2.mId, true, mMockContext, Mailbox.TYPE_OUTBOX);
        Mailbox bt = ProviderTestUtils.setupMailbox(
                "boxT", a2.mId, true, mMockContext, Mailbox.TYPE_TRASH);

        // Create some messages
        // b1 (account 1, inbox): 2 messages, including 1 starred, 1 unloaded
        Message m11 = createMessage(mMockContext, b1, true, false, Message.FLAG_LOADED_COMPLETE);
        Message m12 = createMessage(mMockContext, b1, false, false, Message.FLAG_LOADED_UNLOADED);

        // b2 (account 1, outbox): 2 messages, including 1 starred
        Message m21 = createMessage(mMockContext, b2, false, false, Message.FLAG_LOADED_COMPLETE);
        Message m22 = createMessage(mMockContext, b2, true, true, Message.FLAG_LOADED_COMPLETE);

        // b3 (account 2, inbox): 4 messages, including 1 starred, 1 unloaded
        Message m31 = createMessage(mMockContext, b3, false, false, Message.FLAG_LOADED_COMPLETE);
        Message m32 = createMessage(mMockContext, b3, false, true, Message.FLAG_LOADED_COMPLETE);
        Message m33 = createMessage(mMockContext, b3, true, true, Message.FLAG_LOADED_COMPLETE);
        Message m34 = createMessage(mMockContext, b3, true, true, Message.FLAG_LOADED_UNLOADED);

        // b4 (account 2, outbox) has no messages.

        // bt (account 2, trash): 3 messages, including 2 starred
        Message mt1 = createMessage(mMockContext, bt, true, false, Message.FLAG_LOADED_COMPLETE);
        Message mt2 = createMessage(mMockContext, bt, true, true, Message.FLAG_LOADED_COMPLETE);
        Message mt3 = createMessage(mMockContext, bt, false, false, Message.FLAG_LOADED_COMPLETE);

        assertEquals(4, getMessageCount(ViewType.ALL_INBOX));
        assertEquals(3, getMessageCount(ViewType.STARRED));
        assertEquals(2, getMessageCount(ViewType.UNREAD));
    }

}
