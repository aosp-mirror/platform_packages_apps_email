/*
 * Copyright (C) 2009 The Android Open Source Project
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
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.test.ProviderTestCase2;

/**
 * Tests of the Email provider.
 * 
 * You can run this entire test case with:
 *   runtest -c com.android.email.provider.ProviderTests email
 */
public class ProviderTests extends ProviderTestCase2<EmailProvider> {
    
    EmailProvider mProvider;
    Context mMockContext;

    public ProviderTests() {
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
     * Test simple account save/retrieve
     */
    public void testAccountSave() {
        Account account1 = ProviderTestUtils.setupAccount("account-save", true, mMockContext);
        long account1Id = account1.mId;
        
        Account account2 = EmailContent.Account.restoreAccountWithId(mMockContext, account1Id);
        
        ProviderTestUtils.assertAccountEqual("testAccountSave", account1, account2);
    }
    
    /**
     * TODO: HostAuth tests
     */
    
    /**
     * Test simple mailbox save/retrieve
     */
    public void testMailboxSave() {
        Account account1 = ProviderTestUtils.setupAccount("mailbox-save", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true,
                mMockContext);
        long box1Id = box1.mId;
        
        Mailbox box2 = EmailContent.Mailbox.restoreMailboxWithId(mMockContext, box1Id);
        
        ProviderTestUtils.assertMailboxEqual("testMailboxSave", box1, box2);
    }
    
    /**
     * Test simple message save/retrieve
     * 
     * TODO: attachments
     * TODO: serverId vs. serverIntId
     */
    public void testMessageSave() {
        Account account1 = ProviderTestUtils.setupAccount("message-save", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;

        // Test a simple message (saved with no body)
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;
        Message message1get = EmailContent.Message.restoreMessageWithId(mMockContext, message1Id);
        ProviderTestUtils.assertMessageEqual("testMessageSave", message1, message1get);

        // Test a message saved with a body
        // Note that it will read back w/o the text & html so we must extract those
        Message message2 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, true,
                true, mMockContext);
        long message2Id = message2.mId;
        String text2 = message2.mText;
        String html2 = message2.mHtml;
        message2.mText = null;
        message2.mHtml = null;
        Message message2get = EmailContent.Message.restoreMessageWithId(mMockContext, message2Id);
        ProviderTestUtils.assertMessageEqual("testMessageSave", message2, message2get);
        
        // Now see if there's a body saved with the right stuff
        // TODO it might make sense to add a function to restore the body with the message id
        Cursor c = null;
        try {
            c = mMockContext.getContentResolver().query(
                    EmailContent.Body.CONTENT_URI,
                    EmailContent.Body.CONTENT_PROJECTION,
                    EmailContent.Body.MESSAGE_KEY + "=?",
                    new String[] {
                            String.valueOf(message2Id)
                    }, 
                    null);
            int numBodies = c.getCount();
            assertEquals(1, numBodies);
            c.moveToFirst();
            Body body2 = EmailContent.getContent(c, Body.class);
            assertEquals("body text", text2, body2.mTextContent);
            assertEquals("body html", html2, body2.mHtmlContent);
        } finally {
            if (c != null) c.close();
        }
    }
    
    /**
     * TODO: update account
     */
    
    /**
     * TODO: update mailbox
     */
    
    /**
     * TODO: update message
     */
    
    /**
     * Test delete account
     * TODO: hostauth
     */
    public void testAccountDelete() {
        Account account1 = ProviderTestUtils.setupAccount("account-delete-1", true, mMockContext);
        long account1Id = account1.mId;
        Account account2 = ProviderTestUtils.setupAccount("account-delete-2", true, mMockContext);
        long account2Id = account2.mId;

        // make sure there are two accounts
        int numBoxes = EmailContent.count(mMockContext, Account.CONTENT_URI, null, null);
        assertEquals(2, numBoxes);

        // now delete one of them
        Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, account1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there's only one account now
        numBoxes = EmailContent.count(mMockContext, Account.CONTENT_URI, null, null);
        assertEquals(1, numBoxes);

        // now delete the other one
        uri = ContentUris.withAppendedId(Account.CONTENT_URI, account2Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there are no accounts now
        numBoxes = EmailContent.count(mMockContext, Account.CONTENT_URI, null, null);
        assertEquals(0, numBoxes);
    }
    
    /**
     * Test delete mailbox
     */
    public void testMailboxDelete() {
        Account account1 = ProviderTestUtils.setupAccount("mailbox-delete", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", account1Id, true, mMockContext);
        long box2Id = box2.mId;
        
        String selection = EmailContent.MailboxColumns.ACCOUNT_KEY + "=?";
        String[] selArgs = new String[] { String.valueOf(account1Id) };

        // make sure there are two mailboxes
        int numBoxes = EmailContent.count(mMockContext, Mailbox.CONTENT_URI, selection, selArgs);
        assertEquals(2, numBoxes);

        // now delete one of them
        Uri uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, box1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there's only one mailbox now
        numBoxes = EmailContent.count(mMockContext, Mailbox.CONTENT_URI, selection, selArgs);
        assertEquals(1, numBoxes);

        // now delete the other one
        uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, box2Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there are no mailboxes now
        numBoxes = EmailContent.count(mMockContext, Mailbox.CONTENT_URI, selection, selArgs);
        assertEquals(0, numBoxes);
    }
    
    /**
     * Test delete message
     * TODO: body
     * TODO: attachments
     */
    public void testMessageDelete() {
        Account account1 = ProviderTestUtils.setupAccount("message-delete", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;
        Message message2 = ProviderTestUtils.setupMessage("message2", account1Id, box1Id, false,
                true, mMockContext);
        long message2Id = message2.mId;

        String selection = EmailContent.MessageColumns.ACCOUNT_KEY + "=? AND " +
                EmailContent.MessageColumns.MAILBOX_KEY + "=?";
        String[] selArgs = new String[] { String.valueOf(account1Id), String.valueOf(box1Id) };

        // make sure there are two messages
        int numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(2, numMessages);

        // now delete one of them
        Uri uri = ContentUris.withAppendedId(Message.CONTENT_URI, message1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there's only one message now
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(1, numMessages);

        // now delete the other one
        uri = ContentUris.withAppendedId(Message.CONTENT_URI, message2Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there are no messages now
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(0, numMessages);
    }
    
    /**
     * Test delete synced message
     * TODO: body
     * TODO: attachments
     */
    public void testSyncedMessageDelete() {
        Account account1 = ProviderTestUtils.setupAccount("synced-message-delete", true,
                mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;
        Message message2 = ProviderTestUtils.setupMessage("message2", account1Id, box1Id, false,
                true, mMockContext);
        long message2Id = message2.mId;

        String selection = EmailContent.MessageColumns.ACCOUNT_KEY + "=? AND "
                + EmailContent.MessageColumns.MAILBOX_KEY + "=?";
        String[] selArgs = new String[] {
            String.valueOf(account1Id), String.valueOf(box1Id)
        };

        // make sure there are two messages
        int numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(2, numMessages);

        // make sure we start with no synced deletions
        numMessages = EmailContent.count(mMockContext, Message.DELETED_CONTENT_URI, selection,
                selArgs);
        assertEquals(0, numMessages);

        // now delete one of them SYNCED
        Uri uri = ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, message1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there's only one message now
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(1, numMessages);

        // make sure there's one synced deletion now
        numMessages = EmailContent.count(mMockContext, Message.DELETED_CONTENT_URI, selection,
                selArgs);
        assertEquals(1, numMessages);

        // now delete the other one NOT SYNCED
        uri = ContentUris.withAppendedId(Message.CONTENT_URI, message2Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there are no messages now
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(0, numMessages);

        // make sure there's still one deletion now
        numMessages = EmailContent.count(mMockContext, Message.DELETED_CONTENT_URI, selection,
                selArgs);
        assertEquals(1, numMessages);
    }

    /**
     * Test message update
     * TODO: body
     * TODO: attachments
     */
    public void testMessageUpdate() {
        Account account1 = ProviderTestUtils.setupAccount("message-update", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;
        Message message2 = ProviderTestUtils.setupMessage("message2", account1Id, box1Id, false,
                true, mMockContext);
        long message2Id = message2.mId;
        ContentResolver cr = mMockContext.getContentResolver();

        String selection = EmailContent.MessageColumns.ACCOUNT_KEY + "=? AND "
                + EmailContent.MessageColumns.MAILBOX_KEY + "=?";
        String[] selArgs = new String[] {
            String.valueOf(account1Id), String.valueOf(box1Id)
        };

        // make sure there are two messages
        int numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(2, numMessages);

        // change the first one
        Uri uri = ContentUris.withAppendedId(Message.CONTENT_URI, message1Id);
        ContentValues cv = new ContentValues();
        cv.put(MessageColumns.FROM_LIST, "from-list");
        cr.update(uri, cv, null, null);

        // make sure there's no updated message
        numMessages = EmailContent.count(mMockContext, Message.UPDATED_CONTENT_URI, selection,
                selArgs);
        assertEquals(0, numMessages);

        // get the message back from the provider, make sure the change "stuck"
        Message restoredMessage = Message.restoreMessageWithId(mMockContext, message1Id);
        assertEquals("from-list", restoredMessage.mFrom);

        // change the second one
        uri = ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, message2Id);
        cv = new ContentValues();
        cv.put(MessageColumns.FROM_LIST, "from-list");
        cr.update(uri, cv, null, null);

        // make sure there's one updated message
        numMessages = EmailContent.count(mMockContext, Message.UPDATED_CONTENT_URI, selection,
                selArgs);
        assertEquals(1, numMessages);

        // get the message back from the provider, make sure the change "stuck",
        // as before
        restoredMessage = Message.restoreMessageWithId(mMockContext, message2Id);
        assertEquals("from-list", restoredMessage.mFrom);

        // get the original message back from the provider
        Cursor c = cr.query(Message.UPDATED_CONTENT_URI, Message.CONTENT_PROJECTION, null, null,
                null);
        try {
            assertTrue(c.moveToFirst());
            Message originalMessage = EmailContent.getContent(c, Message.class);
            // make sure this has the original value
            assertEquals("from message2", originalMessage.mFrom);
            // Should only be one
            assertFalse(c.moveToNext());
        } finally {
            c.close();
        }

        // delete the second message
        cr.delete(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, message2Id), null, null);

        // hey, presto! the change should be gone
        numMessages = EmailContent.count(mMockContext, Message.UPDATED_CONTENT_URI, selection,
                selArgs);
        assertEquals(0, numMessages);

        // and there should now be a deleted record
        numMessages = EmailContent.count(mMockContext, Message.DELETED_CONTENT_URI, selection,
                selArgs);
        assertEquals(1, numMessages);
    }

    /**
     * TODO: cascaded delete account
     * TODO: hostauth
     * TODO: body
     * TODO: attachments
     * TODO: create other account, mailbox & messages and confirm the right objects were deleted
     */
    public void testCascadeDeleteAccount() {
        Account account1 = ProviderTestUtils.setupAccount("account-delete-cascade", true,
                mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        /* Message message1 = */ ProviderTestUtils.setupMessage("message1", account1Id, box1Id,
                false, true, mMockContext);
        /* Message message2 = */ ProviderTestUtils.setupMessage("message2", account1Id, box1Id,
                false, true, mMockContext);

        // make sure there is one account, one mailbox, and two messages
        int numAccounts = EmailContent.count(mMockContext, Account.CONTENT_URI, null, null);
        assertEquals(1, numAccounts);
        int numBoxes = EmailContent.count(mMockContext, Mailbox.CONTENT_URI, null, null);
        assertEquals(1, numBoxes);
        int numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, null, null);
        assertEquals(2, numMessages);

        // delete the account
        Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, account1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there are no accounts, mailboxes, or messages
        numAccounts = EmailContent.count(mMockContext, Account.CONTENT_URI, null, null);
        assertEquals(0, numAccounts);
        numBoxes = EmailContent.count(mMockContext, Mailbox.CONTENT_URI, null, null);
        assertEquals(0, numBoxes);
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, null, null);
        assertEquals(0, numMessages);
    }
    
    /**
     * Test cascaded delete mailbox
     * TODO: body
     * TODO: attachments
     * TODO: create other mailbox & messages and confirm the right objects were deleted
     */
    public void testCascadeDeleteMailbox() {
        Account account1 = ProviderTestUtils.setupAccount("mailbox-delete-cascade", true,
                mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        /* Message message1 = */ ProviderTestUtils.setupMessage("message1", account1Id, box1Id,
                false, true, mMockContext);
        /* Message message2 = */ ProviderTestUtils.setupMessage("message2", account1Id, box1Id,
                false, true, mMockContext);

        String selection = EmailContent.MessageColumns.ACCOUNT_KEY + "=? AND " +
                EmailContent.MessageColumns.MAILBOX_KEY + "=?";
        String[] selArgs = new String[] { String.valueOf(account1Id), String.valueOf(box1Id) };

        // make sure there are two messages
        int numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(2, numMessages);
        
        // now delete the mailbox
        Uri uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, box1Id);
        mMockContext.getContentResolver().delete(uri, null, null);
        
        // there should now be zero messages
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(0, numMessages);
    }
    
    /**
     * TODO: Test cascaded delete message
     * TODO: body
     * TODO: attachments
     */

}
