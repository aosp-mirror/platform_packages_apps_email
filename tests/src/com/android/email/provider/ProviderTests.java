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

import android.content.ContentUris;
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
        Account account1 = setupAccount("account-save", true);
        long account1Id = account1.mId;
        
        Account account2 = EmailContent.Account.restoreAccountWithId(mMockContext, account1Id);
        
        assertAccountEqual("testAccountSave", account1, account2);
    }
    
    /**
     * TODO: HostAuth tests
     */
    
    /**
     * Test simple mailbox save/retrieve
     */
    public void testMailboxSave() {
        Account account1 = setupAccount("mailbox-save", true);
        long account1Id = account1.mId;
        Mailbox box1 = setupMailbox("box1", account1Id, true);
        long box1Id = box1.mId;
        
        Mailbox box2 = EmailContent.Mailbox.restoreMailboxWithId(mMockContext, box1Id);
        
        assertMailboxEqual("testMailboxSave", box1, box2);
    }
    
    /**
     * Test simple message save/retrieve
     * 
     * TODO: attachments
     * TODO: serverId vs. serverIntId
     */
    public void testMessageSave() {
        Account account1 = setupAccount("message-save", true);
        long account1Id = account1.mId;
        Mailbox box1 = setupMailbox("box1", account1Id, true);
        long box1Id = box1.mId;

        // Test a simple message (saved with no body)
        Message message1 = setupMessage("message1", account1Id, box1Id, false, true);
        long message1Id = message1.mId;
        Message message1get = EmailContent.Message.restoreMessageWithId(mMockContext, message1Id);
        assertMessageEqual("testMessageSave", message1, message1get);

        // Test a message saved with a body
        // Note that it will read back w/o the text & html so we must extract those
        Message message2 = setupMessage("message1", account1Id, box1Id, true, true);
        long message2Id = message2.mId;
        String text2 = message2.mText;
        String html2 = message2.mHtml;
        message2.mText = null;
        message2.mHtml = null;
        Message message2get = EmailContent.Message.restoreMessageWithId(mMockContext, message2Id);
        assertMessageEqual("testMessageSave", message2, message2get);
        
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
        Account account1 = setupAccount("account-delete-1", true);
        long account1Id = account1.mId;
        Account account2 = setupAccount("account-delete-2", true);
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
        Account account1 = setupAccount("mailbox-delete", true);
        long account1Id = account1.mId;
        Mailbox box1 = setupMailbox("box1", account1Id, true);
        long box1Id = box1.mId;
        Mailbox box2 = setupMailbox("box2", account1Id, true);
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
        Account account1 = setupAccount("message-delete", true);
        long account1Id = account1.mId;
        Mailbox box1 = setupMailbox("box1", account1Id, true);
        long box1Id = box1.mId;
        Message message1 = setupMessage("message1", account1Id, box1Id, false, true);
        long message1Id = message1.mId;
        Message message2 = setupMessage("message2", account1Id, box1Id, false, true);
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
     * TODO: cascaded delete account
     * TODO: hostauth
     * TODO: body
     * TODO: attachments
     * TODO: create other account, mailbox & messages and confirm the right objects were deleted
     */
    public void testCascadeDeleteAccount() {
        Account account1 = setupAccount("account-delete-cascade", true);
        long account1Id = account1.mId;
        Mailbox box1 = setupMailbox("box1", account1Id, true);
        long box1Id = box1.mId;
        /* Message message1 = */ setupMessage("message1", account1Id, box1Id, false, true);
        /* Message message2 = */ setupMessage("message2", account1Id, box1Id, false, true);

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
        Account account1 = setupAccount("mailbox-delete-cascade", true);
        long account1Id = account1.mId;
        Mailbox box1 = setupMailbox("box1", account1Id, true);
        long box1Id = box1.mId;
        /* Message message1 = */ setupMessage("message1", account1Id, box1Id, false, true);
        /* Message message2 = */ setupMessage("message2", account1Id, box1Id, false, true);

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
    
    /**
     * Create an account for test purposes
     */
    private Account setupAccount(String name, boolean saveIt) {
        Account account = new Account();
        
        account.mDisplayName = name;
        account.mEmailAddress = name + "@android.com";
        account.mSyncKey = "sync-key-" + name;
        account.mSyncLookback = 1;
        account.mSyncFrequency = EmailContent.Account.CHECK_INTERVAL_NEVER;
        account.mHostAuthKeyRecv = 2;
        account.mHostAuthKeySend = 3;
        account.mFlags = 4;
        account.mIsDefault = true;
        account.mCompatibilityUuid = "test-uid-" + name;
        account.mSenderName = name;
        account.mRingtoneUri = "content://ringtone-" + name;
        
        if (saveIt) {
            account.saveOrUpdate(mMockContext);
        }
        return account;
    }
    
    /**
     * Create a mailbox for test purposes
     */
    private Mailbox setupMailbox(String name, long accountId, boolean saveIt) {
        Mailbox box = new Mailbox();
        
        box.mDisplayName = name;
        box.mServerId = "serverid-" + name;
        box.mParentServerId = "parent-serverid-" + name;
        box.mAccountKey = accountId;
        box.mType = Mailbox.TYPE_MAIL;
        box.mDelimiter = 1;
        box.mSyncKey = "sync-key-" + name;
        box.mSyncLookback = 2;
        box.mSyncFrequency = EmailContent.Account.CHECK_INTERVAL_NEVER;
        box.mSyncTime = 3;
        box.mUnreadCount = 4;
        box.mFlagVisible = true;
        box.mFlags = 5;
        box.mVisibleLimit = 6;
        
        if (saveIt) {
            box.saveOrUpdate(mMockContext);
        }
        return box;
    }
    
    /**
     * Create a message for test purposes
     * 
     * TODO: body
     * TODO: attachments
     */
    private Message setupMessage(String name, long accountId, long mailboxId, boolean addBody,
            boolean saveIt) {
        Message message = new Message();
        
        message.mDisplayName = name;
        message.mTimeStamp = 1;
        message.mSubject = "subject " + name;
        message.mPreview = "preview " + name;
        message.mFlagRead = true;
        message.mFlagLoaded = Message.NOT_LOADED;
        message.mFlagFavorite = true;
        message.mFlagAttachment = true;
        message.mFlags = 2;

        message.mTextInfo = "textinfo " + name;
        message.mHtmlInfo = "htmlinfo " + name;

        message.mServerId = "serverid " + name;
        message.mServerIntId = 0;
        message.mClientId = "clientid " + name;
        message.mMessageId = "messageid " + name;
        message.mThreadId = "threadid " + name;

        message.mMailboxKey = mailboxId;
        message.mAccountKey = accountId;
        message.mReferenceKey = 4;

        message.mSender = "sender " + name;
        message.mFrom = "from " + name;
        message.mTo = "to " + name;
        message.mCc = "cc " + name;
        message.mBcc = "bcc " + name;
        message.mReplyTo = "replyto " + name;
        
        message.mServerVersion = "serverversion " + name;
        
        if (addBody) {
            message.mText = "body text " + name;
            message.mHtml = "body html " + name;
        }

        if (saveIt) {
            message.saveOrUpdate(mMockContext);
        }
        return message;
    }
    
    /**
     * Compare two accounts for equality
     * 
     * TODO: check host auth?
     */
    private void assertAccountEqual(String caller, Account expect, Account actual) {
        if (expect == actual) {
            return;
        }
        
        assertEquals(caller + " mDisplayName", expect.mDisplayName, actual.mDisplayName);
        assertEquals(caller + " mEmailAddress", expect.mEmailAddress, actual.mEmailAddress);
        assertEquals(caller + " mSyncKey", expect.mSyncKey, actual.mSyncKey);
        
        assertEquals(caller + " mSyncLookback", expect.mSyncLookback, actual.mSyncLookback);
        assertEquals(caller + " mSyncFrequency", expect.mSyncFrequency, actual.mSyncFrequency);
        assertEquals(caller + " mHostAuthKeyRecv", expect.mHostAuthKeyRecv,
                actual.mHostAuthKeyRecv);
        assertEquals(caller + " mHostAuthKeySend", expect.mHostAuthKeySend,
                actual.mHostAuthKeySend);
        assertEquals(caller + " mFlags", expect.mFlags, actual.mFlags);
        assertEquals(caller + " mIsDefault", expect.mIsDefault, actual.mIsDefault);
        assertEquals(caller + " mCompatibilityUuid", expect.mCompatibilityUuid,
                actual.mCompatibilityUuid);
        assertEquals(caller + " mSenderName", expect.mSenderName, actual.mSenderName);
        assertEquals(caller + " mRingtoneUri", expect.mRingtoneUri, actual.mRingtoneUri);
    }
    
    /**
     * Compare two mailboxes for equality
     */
    private void assertMailboxEqual(String caller, Mailbox expect, Mailbox actual) {
        if (expect == actual) {
            return;
        }
        
        assertEquals(caller + " mDisplayName", expect.mDisplayName, actual.mDisplayName);
        assertEquals(caller + " mServerId", expect.mServerId, actual.mServerId);
        assertEquals(caller + " mParentServerId", expect.mParentServerId, actual.mParentServerId);
        assertEquals(caller + " mAccountKey", expect.mAccountKey, actual.mAccountKey);
        assertEquals(caller + " mType", expect.mType, actual.mType);
        assertEquals(caller + " mDelimiter", expect.mDelimiter, actual.mDelimiter);
        assertEquals(caller + " mSyncKey", expect.mSyncKey, actual.mSyncKey);
        assertEquals(caller + " mSyncLookback", expect.mSyncLookback, actual.mSyncLookback);
        assertEquals(caller + " mSyncFrequency", expect.mSyncFrequency, actual.mSyncFrequency);
        assertEquals(caller + " mSyncTime", expect.mSyncTime, actual.mSyncTime);
        assertEquals(caller + " mUnreadCount", expect.mUnreadCount, actual.mUnreadCount);
        assertEquals(caller + " mFlagVisible", expect.mFlagVisible, actual.mFlagVisible);
        assertEquals(caller + " mFlags", expect.mFlags, actual.mFlags);
        assertEquals(caller + " mVisibleLimit", expect.mVisibleLimit, actual.mVisibleLimit);
    }
    
    /**
     * Compare two messages for equality
     * 
     * TODO: body?
     * TODO: attachments?
     */
    private void assertMessageEqual(String caller, Message expect, Message actual) {
        if (expect == actual) {
            return;
        }
        
        assertEquals(caller + " mDisplayName", expect.mDisplayName, actual.mDisplayName);
        assertEquals(caller + " mTimeStamp", expect.mTimeStamp, actual.mTimeStamp);
        assertEquals(caller + " mSubject", expect.mSubject, actual.mSubject);
        assertEquals(caller + " mPreview", expect.mPreview, actual.mPreview);
        assertEquals(caller + " mFlagRead = false", expect.mFlagRead, actual.mFlagRead);
        assertEquals(caller + " mFlagLoaded", expect.mFlagLoaded, actual.mFlagLoaded);
        assertEquals(caller + " mFlagFavorite", expect.mFlagFavorite, actual.mFlagFavorite);
        assertEquals(caller + " mFlagAttachment", expect.mFlagAttachment, actual.mFlagAttachment);
        assertEquals(caller + " mFlags", expect.mFlags, actual.mFlags);

        assertEquals(caller + " mTextInfo", expect.mTextInfo, actual.mTextInfo);
        assertEquals(caller + " mHtmlInfo", expect.mHtmlInfo, actual.mHtmlInfo);

        assertEquals(caller + " mServerId", expect.mServerId, actual.mServerId);
        assertEquals(caller + " mServerIntId", expect.mServerIntId, actual.mServerIntId);
        assertEquals(caller + " mClientId", expect.mClientId, actual.mClientId);
        assertEquals(caller + " mMessageId", expect.mMessageId, actual.mMessageId);
        assertEquals(caller + " mThreadId", expect.mThreadId, actual.mThreadId);

        assertEquals(caller + " mMailboxKey", expect.mMailboxKey, actual.mMailboxKey);
        assertEquals(caller + " mAccountKey", expect.mAccountKey, actual.mAccountKey);
        assertEquals(caller + " mReferenceKey", expect.mReferenceKey, actual.mReferenceKey);

        assertEquals(caller + " mSender", expect.mSender, actual.mSender);
        assertEquals(caller + " mFrom", expect.mFrom, actual.mFrom);
        assertEquals(caller + " mTo", expect.mTo, actual.mTo);
        assertEquals(caller + " mCc", expect.mCc, actual.mCc);
        assertEquals(caller + " mBcc", expect.mBcc, actual.mBcc);
        assertEquals(caller + " mReplyTo", expect.mReplyTo, actual.mReplyTo);
        
        assertEquals(caller + " mServerVersion", expect.mServerVersion, actual.mServerVersion);

        assertEquals(caller + " mText", expect.mText, actual.mText);
        assertEquals(caller + " mHtml", expect.mHtml, actual.mHtml);
    }
}
