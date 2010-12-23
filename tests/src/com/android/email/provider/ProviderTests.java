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

import com.android.email.Snippet;
import com.android.email.Utility;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.AttachmentColumns;
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.BodyColumns;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcel;
import android.test.MoreAsserts;
import android.test.ProviderTestCase2;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Tests of the Email provider.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.provider.ProviderTests email
 *
 * TODO: Add tests for cursor notification mechanism.  (setNotificationUri and notifyChange)
 * We can't test the entire notification mechanism with a mock content resolver, because which URI
 * to notify when notifyChange() is called is in the actual content resolver.
 * Implementing the same mechanism in a mock one is pointless.  Instead what we could do is check
 * what notification URI each cursor has, and with which URI is notified when
 * inserting/updating/deleting.  (The former require a new method from AbstractCursor)
 */
public class ProviderTests extends ProviderTestCase2<EmailProvider> {

    private EmailProvider mProvider;
    private Context mMockContext;

    public ProviderTests() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        mProvider = getProvider();
        // Invalidate all caches, since we reset the database for each test
        ContentCache.invalidateAllCachesForTest();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * TODO: Database upgrade tests
     */

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
     * Test simple account save/retrieve with predefined hostauth records
     */
    public void testAccountSaveHostAuth() {
        Account account1 = ProviderTestUtils.setupAccount("account-hostauth", false, mMockContext);
        // add hostauth data, which should be saved the first time
        account1.mHostAuthRecv = ProviderTestUtils.setupHostAuth("account-hostauth-recv", -1, false,
                mMockContext);
        account1.mHostAuthSend = ProviderTestUtils.setupHostAuth("account-hostauth-send", -1, false,
                mMockContext);
        account1.save(mMockContext);
        long account1Id = account1.mId;

        // Confirm account reads back correctly
        Account account1get = EmailContent.Account.restoreAccountWithId(mMockContext, account1Id);
        ProviderTestUtils.assertAccountEqual("testAccountSave", account1, account1get);

        // Confirm hostauth fields can be accessed & read back correctly
        HostAuth hostAuth1get = EmailContent.HostAuth.restoreHostAuthWithId(mMockContext,
                account1get.mHostAuthKeyRecv);
        ProviderTestUtils.assertHostAuthEqual("testAccountSaveHostAuth-recv",
                account1.mHostAuthRecv, hostAuth1get);
        HostAuth hostAuth2get = EmailContent.HostAuth.restoreHostAuthWithId(mMockContext,
                account1get.mHostAuthKeySend);
        ProviderTestUtils.assertHostAuthEqual("testAccountSaveHostAuth-send",
                account1.mHostAuthSend, hostAuth2get);
    }

    /**
     * Simple test of account parceling.  The rather tortuous path is to ensure that the
     * account is really flattened all the way down to a parcel and back.
     */
    public void testAccountParcel() {
        Account account1 = ProviderTestUtils.setupAccount("parcel", false, mMockContext);
        Bundle b = new Bundle();
        b.putParcelable("account", account1);
        Parcel p = Parcel.obtain();
        b.writeToParcel(p, 0);
        p.setDataPosition(0);       // rewind it for reading
        Bundle b2 = new Bundle(Account.class.getClassLoader());
        b2.readFromParcel(p);
        Account account2 = (Account) b2.getParcelable("account");
        p.recycle();

        ProviderTestUtils.assertAccountEqual("testAccountParcel", account1, account2);
    }

    /**
     * Test for {@link Account#getShortcutSafeUri()} and
     * {@link Account#getAccountIdForShortcutSafeUri}.
     */
    public void testAccountShortcutSafeUri() {
        final Account account1 = ProviderTestUtils.setupAccount("account-1", true, mMockContext);
        final Account account2 = ProviderTestUtils.setupAccount("account-2", true, mMockContext);
        final long account1Id = account1.mId;
        final long account2Id = account2.mId;

        final Uri uri1 = account1.getShortcutSafeUri();
        final Uri uri2 = account2.getShortcutSafeUri();

        // Check the path part of the URIs.
        MoreAsserts.assertEquals(new String[] {"account", account1.mCompatibilityUuid},
                uri1.getPathSegments().toArray());
        MoreAsserts.assertEquals(new String[] {"account", account2.mCompatibilityUuid},
                uri2.getPathSegments().toArray());

        assertEquals(account1Id, Account.getAccountIdFromShortcutSafeUri(mMockContext, uri1));
        assertEquals(account2Id, Account.getAccountIdFromShortcutSafeUri(mMockContext, uri2));

        // Test for the Eclair(2.0-2.1) style URI.
        assertEquals(account1Id, Account.getAccountIdFromShortcutSafeUri(mMockContext,
                getEclairStyleShortcutUri(account1)));
        assertEquals(account2Id, Account.getAccountIdFromShortcutSafeUri(mMockContext,
                getEclairStyleShortcutUri(account2)));
    }

    private static Uri getEclairStyleShortcutUri(Account account) {
        // We used _id instead of UUID only on Eclair(2.0-2.1).
        return Account.CONTENT_URI.buildUpon().appendEncodedPath("" + account.mId).build();
    }

    public void testGetProtocol() {
        Account account1 = ProviderTestUtils.setupAccount("account-hostauth", false, mMockContext);
        // add hostauth data, with protocol
        account1.mHostAuthRecv = ProviderTestUtils.setupHostAuth("eas", "account-hostauth-recv", -1,
                false, mMockContext);
        // Note that getProtocol uses the receive host auth, so the protocol here shouldn't matter
        // to the test result
        account1.mHostAuthSend = ProviderTestUtils.setupHostAuth("foo", "account-hostauth-send", -1,
                false, mMockContext);
        account1.save(mMockContext);
        assertEquals("eas", Account.getProtocol(mMockContext, account1.mId));
        assertEquals("eas", account1.getProtocol(mMockContext));
        Account account2 = ProviderTestUtils.setupAccount("account-nohostauth", false,
                mMockContext);
        account2.save(mMockContext);
        // Make sure that we return null when there's no host auth
        assertNull(Account.getProtocol(mMockContext, account2.mId));
        assertNull(account2.getProtocol(mMockContext));
        // And when there's no account
        assertNull(Account.getProtocol(mMockContext, 0));
    }

    public void testAccountIsValidId() {
        final Account account1 = ProviderTestUtils.setupAccount("account-1", true, mMockContext);
        final Account account2 = ProviderTestUtils.setupAccount("account-2", true, mMockContext);

        assertTrue(Account.isValidId(mMockContext, account1.mId));
        assertTrue(Account.isValidId(mMockContext, account2.mId));

        assertFalse(Account.isValidId(mMockContext, 1234567)); // Some random ID
        assertFalse(Account.isValidId(mMockContext, -1));
        assertFalse(Account.isValidId(mMockContext, -500));
    }

    private final static String[] MAILBOX_UNREAD_COUNT_PROJECTION = new String [] {
        MailboxColumns.UNREAD_COUNT
    };
    private final static int MAILBOX_UNREAD_COUNT_COLMUN = 0;

    /**
     * Get the value of the unread count in the mailbox of the account.
     * This can be different from the actual number of unread messages in that mailbox.
     * @param accountId
     * @param mailboxId
     * @return
     */
    private int getUnreadCount(long mailboxId) {
        String text = null;
        Cursor c = null;
        try {
            c = mMockContext.getContentResolver().query(
                    Mailbox.CONTENT_URI,
                    MAILBOX_UNREAD_COUNT_PROJECTION,
                    EmailContent.RECORD_ID + "=?",
                    new String[] { String.valueOf(mailboxId) },
                    null);
            c.moveToFirst();
            text = c.getString(MAILBOX_UNREAD_COUNT_COLMUN);
        } finally {
            c.close();
        }
        return Integer.valueOf(text);
    }

    /**
     * TODO: HostAuth tests
     */

    /**
     * Test the various combinations of SSL, TLS, and trust-certificates encoded as Uris
     */
    @SuppressWarnings("deprecation")
    public void testHostAuthSecurityUri() {
        HostAuth ha = ProviderTestUtils.setupHostAuth("uri-security", 1, false, mMockContext);

        final int MASK =
            HostAuth.FLAG_SSL | HostAuth.FLAG_TLS | HostAuth.FLAG_TRUST_ALL_CERTIFICATES;

        // Set various URIs and check the resulting flags
        ha.setStoreUri("protocol://user:password@server:123");
        assertEquals(0, ha.mFlags & MASK);
        ha.setStoreUri("protocol+ssl+://user:password@server:123");
        assertEquals(HostAuth.FLAG_SSL, ha.mFlags & MASK);
        ha.setStoreUri("protocol+ssl+trustallcerts://user:password@server:123");
        assertEquals(HostAuth.FLAG_SSL | HostAuth.FLAG_TRUST_ALL_CERTIFICATES, ha.mFlags & MASK);
        ha.setStoreUri("protocol+tls+://user:password@server:123");
        assertEquals(HostAuth.FLAG_TLS, ha.mFlags & MASK);
        ha.setStoreUri("protocol+tls+trustallcerts://user:password@server:123");
        assertEquals(HostAuth.FLAG_TLS | HostAuth.FLAG_TRUST_ALL_CERTIFICATES, ha.mFlags & MASK);

        // Now check the retrival method (building URI from flags)
        ha.mFlags &= ~MASK;
        String uriString = ha.getStoreUri();
        assertTrue(uriString.startsWith("protocol://"));
        ha.mFlags |= HostAuth.FLAG_SSL;
        uriString = ha.getStoreUri();
        assertTrue(uriString.startsWith("protocol+ssl+://"));
        ha.mFlags |= HostAuth.FLAG_TRUST_ALL_CERTIFICATES;
        uriString = ha.getStoreUri();
        assertTrue(uriString.startsWith("protocol+ssl+trustallcerts://"));
        ha.mFlags &= ~MASK;
        ha.mFlags |= HostAuth.FLAG_TLS;
        uriString = ha.getStoreUri();
        assertTrue(uriString.startsWith("protocol+tls+://"));
        ha.mFlags |= HostAuth.FLAG_TRUST_ALL_CERTIFICATES;
        uriString = ha.getStoreUri();
        assertTrue(uriString.startsWith("protocol+tls+trustallcerts://"));
    }

    /**
     * Test port assignments made from Uris
     */
    @SuppressWarnings("deprecation")
    public void testHostAuthPortAssignments() {
        HostAuth ha = ProviderTestUtils.setupHostAuth("uri-port", 1, false, mMockContext);

        // Set various URIs and check the resulting flags
        // Hardwired port
        ha.setStoreUri("imap://user:password@server:123");
        assertEquals(123, ha.mPort);
        // Auto-assigned ports
        ha.setStoreUri("imap://user:password@server");
        assertEquals(143, ha.mPort);
        ha.setStoreUri("imap+ssl://user:password@server");
        assertEquals(993, ha.mPort);
        ha.setStoreUri("imap+ssl+trustallcerts://user:password@server");
        assertEquals(993, ha.mPort);
        ha.setStoreUri("imap+tls://user:password@server");
        assertEquals(143, ha.mPort);
        ha.setStoreUri("imap+tls+trustallcerts://user:password@server");
        assertEquals(143, ha.mPort);

        // Hardwired port
        ha.setStoreUri("pop3://user:password@server:123");
        assertEquals(123, ha.mPort);
        // Auto-assigned ports
        ha.setStoreUri("pop3://user:password@server");
        assertEquals(110, ha.mPort);
        ha.setStoreUri("pop3+ssl://user:password@server");
        assertEquals(995, ha.mPort);
        ha.setStoreUri("pop3+ssl+trustallcerts://user:password@server");
        assertEquals(995, ha.mPort);
        ha.setStoreUri("pop3+tls://user:password@server");
        assertEquals(110, ha.mPort);
        ha.setStoreUri("pop3+tls+trustallcerts://user:password@server");
        assertEquals(110, ha.mPort);

        // Hardwired port
        ha.setStoreUri("eas://user:password@server:123");
        assertEquals(123, ha.mPort);
        // Auto-assigned ports
        ha.setStoreUri("eas://user:password@server");
        assertEquals(80, ha.mPort);
        ha.setStoreUri("eas+ssl://user:password@server");
        assertEquals(443, ha.mPort);
        ha.setStoreUri("eas+ssl+trustallcerts://user:password@server");
        assertEquals(443, ha.mPort);

        // Hardwired port
        ha.setStoreUri("smtp://user:password@server:123");
        assertEquals(123, ha.mPort);
        // Auto-assigned ports
        ha.setStoreUri("smtp://user:password@server");
        assertEquals(587, ha.mPort);
        ha.setStoreUri("smtp+ssl://user:password@server");
        assertEquals(465, ha.mPort);
        ha.setStoreUri("smtp+ssl+trustallcerts://user:password@server");
        assertEquals(465, ha.mPort);
        ha.setStoreUri("smtp+tls://user:password@server");
        assertEquals(587, ha.mPort);
        ha.setStoreUri("smtp+tls+trustallcerts://user:password@server");
        assertEquals(587, ha.mPort);
    }

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

    private static String[] expectedAttachmentNames =
        new String[] {"attachment1.doc", "attachment2.xls", "attachment3"};
    // The lengths need to be kept in ascending order
    private static long[] expectedAttachmentSizes = new long[] {31415L, 97701L, 151213L};

    /*
     * Returns null if the message has no body.
     */
    private Body loadBodyForMessageId(long messageId) {
        Cursor c = null;
        try {
            c = mMockContext.getContentResolver().query(
                    EmailContent.Body.CONTENT_URI,
                    EmailContent.Body.CONTENT_PROJECTION,
                    EmailContent.Body.MESSAGE_KEY + "=?",
                    new String[] {String.valueOf(messageId)},
                    null);
            int numBodies = c.getCount();
            assertTrue("at most one body", numBodies < 2);
            return c.moveToFirst() ? EmailContent.getContent(c, Body.class) : null;
        } finally {
            c.close();
        }
    }

    /**
     * Test simple message save/retrieve
     *
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
        String textReply2 = message2.mTextReply;
        String htmlReply2 = message2.mHtmlReply;
        long sourceKey2 = message2.mSourceKey;
        String introText2 = message2.mIntroText;
        message2.mText = null;
        message2.mHtml = null;
        message2.mTextReply = null;
        message2.mHtmlReply = null;
        message2.mSourceKey = 0;
        message2.mIntroText = null;
        Message message2get = EmailContent.Message.restoreMessageWithId(mMockContext, message2Id);
        ProviderTestUtils.assertMessageEqual("testMessageSave", message2, message2get);

        // Now see if there's a body saved with the right stuff
        Body body2 = loadBodyForMessageId(message2Id);
        assertEquals("body text", text2, body2.mTextContent);
        assertEquals("body html", html2, body2.mHtmlContent);
        assertEquals("reply text", textReply2, body2.mTextReply);
        assertEquals("reply html", htmlReply2, body2.mHtmlReply);
        assertEquals("source key", sourceKey2, body2.mSourceKey);
        assertEquals("intro text", introText2, body2.mIntroText);

        // Message with attachments and body
        Message message3 = ProviderTestUtils.setupMessage("message3", account1Id, box1Id, true,
                false, mMockContext);
        ArrayList<Attachment> atts = new ArrayList<Attachment>();
        for (int i = 0; i < 3; i++) {
            atts.add(ProviderTestUtils.setupAttachment(
                    -1, expectedAttachmentNames[i], expectedAttachmentSizes[i],
                    false, mMockContext));
        }
        message3.mAttachments = atts;
        message3.save(mMockContext);
        long message3Id = message3.mId;

        // Now check the attachments; there should be three and they should match name and size
        Cursor c = null;
        try {
            // Note that there is NO guarantee of the order of returned records in the general case,
            // so we specifically ask for ordering by size.  The expectedAttachmentSizes array must
            // be kept sorted by size (ascending) for this test to work properly
            c = mMockContext.getContentResolver().query(
                    Attachment.CONTENT_URI,
                    Attachment.CONTENT_PROJECTION,
                    Attachment.MESSAGE_KEY + "=?",
                    new String[] {
                            String.valueOf(message3Id)
                    },
                    Attachment.SIZE);
            int numAtts = c.getCount();
            assertEquals(3, numAtts);
            int i = 0;
            while (c.moveToNext()) {
                Attachment actual = EmailContent.getContent(c, Attachment.class);
                ProviderTestUtils.assertAttachmentEqual("save-message3", atts.get(i), actual);
                i++;
            }
        } finally {
            c.close();
        }

        // Message with attachments but no body
        Message message4 = ProviderTestUtils.setupMessage("message4", account1Id, box1Id, false,
                false, mMockContext);
        atts = new ArrayList<Attachment>();
        for (int i = 0; i < 3; i++) {
            atts.add(ProviderTestUtils.setupAttachment(
                    -1, expectedAttachmentNames[i], expectedAttachmentSizes[i],
                    false, mMockContext));
        }
        message4.mAttachments = atts;
        message4.save(mMockContext);
        long message4Id = message4.mId;

        // Now check the attachments; there should be three and they should match name and size
        c = null;

        try {
            // Note that there is NO guarantee of the order of returned records in the general case,
            // so we specifically ask for ordering by size.  The expectedAttachmentSizes array must
            // be kept sorted by size (ascending) for this test to work properly
            c = mMockContext.getContentResolver().query(
                    Attachment.CONTENT_URI,
                    Attachment.CONTENT_PROJECTION,
                    Attachment.MESSAGE_KEY + "=?",
                    new String[] {
                            String.valueOf(message4Id)
                    },
                    Attachment.SIZE);
            int numAtts = c.getCount();
            assertEquals(3, numAtts);
            int i = 0;
            while (c.moveToNext()) {
                Attachment actual = EmailContent.getContent(c, Attachment.class);
                ProviderTestUtils.assertAttachmentEqual("save-message4", atts.get(i), actual);
                i++;
            }
        } finally {
            c.close();
        }

        // test EmailContent.restoreAttachmentsWitdMessageId()
        Attachment[] attachments =
            Attachment.restoreAttachmentsWithMessageId(mMockContext, message4Id);
        int size = attachments.length;
        assertEquals(3, size);
        for (int i = 0; i < size; ++i) {
            ProviderTestUtils.assertAttachmentEqual("save-message4", atts.get(i), attachments[i]);
        }
    }

    /**
     * Test that saving a message creates the proper snippet for that message
     */
    public void testMessageSaveAddsSnippet() {
        Account account = ProviderTestUtils.setupAccount("message-snippet", true, mMockContext);
        Mailbox box = ProviderTestUtils.setupMailbox("box1", account.mId, true, mMockContext);

        // Create a message without a body, unsaved
        Message message = ProviderTestUtils.setupMessage("message", account.mId, box.mId, false,
                false, mMockContext);
        message.mText = "This is some text";
        message.mHtml = "<html>This is some text</html>";
        message.save(mMockContext);
        Message restoredMessage = Message.restoreMessageWithId(mMockContext, message.mId);
        // We should have the plain text as the snippet
        assertEquals(restoredMessage.mSnippet, Snippet.fromPlainText(message.mText));

        // Start again
        message = ProviderTestUtils.setupMessage("message", account.mId, box.mId, false,
                false, mMockContext);
        message.mText = null;
        message.mHtml = "<html>This is some text</html>";
        message.save(mMockContext);
        restoredMessage = Message.restoreMessageWithId(mMockContext, message.mId);
        // We should have the plain text as the snippet
        assertEquals(restoredMessage.mSnippet, Snippet.fromHtmlText(message.mHtml));
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
     * Test for Body.lookupBodyIdWithMessageId()
     * Verifies that:
     * - for a message without body, -1 is returned.
     * - for a mesage with body, the id matches the one from loadBodyForMessageId.
     */
    public void testLookupBodyIdWithMessageId() {
        final ContentResolver resolver = mMockContext.getContentResolver();
        Account account1 = ProviderTestUtils.setupAccount("orphaned body", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;

        // 1. create message with no body, check that returned bodyId is -1
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;
        long bodyId1 = Body.lookupBodyIdWithMessageId(mMockContext, message1Id);
        assertEquals(bodyId1, -1);

        // 2. create message with body, check that returned bodyId is correct
        Message message2 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, true,
                true, mMockContext);
        long message2Id = message2.mId;
        long bodyId2 = Body.lookupBodyIdWithMessageId(mMockContext, message2Id);
        Body body = loadBodyForMessageId(message2Id);
        assertNotNull(body);
        assertEquals(body.mId, bodyId2);
    }

    /**
     * Test for Body.updateBodyWithMessageId().
     * 1. - create message without body,
     *    - update its body (set TEXT_CONTENT)
     *    - check correct updated body is read back
     *
     * 2. - create message with body,
     *    - update body (set TEXT_CONTENT)
     *    - check correct updated body is read back
     */
    public void testUpdateBodyWithMessageId() {
        Account account1 = ProviderTestUtils.setupAccount("orphaned body", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;

        final String textContent = "foobar some odd text";
        final String htmlContent = "and some html";
        final String textReply = "plain text reply";
        final String htmlReply = "or the html reply";
        final String introText = "fred wrote:";

        ContentValues values = new ContentValues();
        values.put(BodyColumns.TEXT_CONTENT, textContent);
        values.put(BodyColumns.HTML_CONTENT, htmlContent);
        values.put(BodyColumns.TEXT_REPLY, textReply);
        values.put(BodyColumns.HTML_REPLY, htmlReply);
        values.put(BodyColumns.SOURCE_MESSAGE_KEY, 17);
        values.put(BodyColumns.INTRO_TEXT, introText);

        // 1
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;
        Body body1 = loadBodyForMessageId(message1Id);
        assertNull(body1);
        Body.updateBodyWithMessageId(mMockContext, message1Id, values);
        body1 = loadBodyForMessageId(message1Id);
        assertNotNull(body1);
        assertEquals(body1.mTextContent, textContent);
        assertEquals(body1.mHtmlContent, htmlContent);
        assertEquals(body1.mTextReply, textReply);
        assertEquals(body1.mHtmlReply, htmlReply);
        assertEquals(body1.mSourceKey, 17);
        assertEquals(body1.mIntroText, introText);

        // 2
        Message message2 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, true,
                true, mMockContext);
        long message2Id = message2.mId;
        Body body2 = loadBodyForMessageId(message2Id);
        assertNotNull(body2);
        assertTrue(!body2.mTextContent.equals(textContent));
        Body.updateBodyWithMessageId(mMockContext, message2Id, values);
        body2 = loadBodyForMessageId(message1Id);
        assertNotNull(body2);
        assertEquals(body2.mTextContent, textContent);
        assertEquals(body2.mHtmlContent, htmlContent);
        assertEquals(body2.mTextReply, textReply);
        assertEquals(body2.mHtmlReply, htmlReply);
        assertEquals(body2.mSourceKey, 17);
        assertEquals(body2.mIntroText, introText);
    }

    /**
     * Test body retrieve methods
     */
    public void testBodyRetrieve() {
        // No account needed
        // No mailbox needed
        Message message1 = ProviderTestUtils.setupMessage("bodyretrieve", 1, 1, true,
                true, mMockContext);
        long messageId = message1.mId;

        assertEquals(message1.mText,
                Body.restoreBodyTextWithMessageId(mMockContext, messageId));
        assertEquals(message1.mHtml,
                Body.restoreBodyHtmlWithMessageId(mMockContext, messageId));
        assertEquals(message1.mTextReply,
                Body.restoreReplyTextWithMessageId(mMockContext, messageId));
        assertEquals(message1.mHtmlReply,
                Body.restoreReplyHtmlWithMessageId(mMockContext, messageId));
        assertEquals(message1.mIntroText,
                Body.restoreIntroTextWithMessageId(mMockContext, messageId));
        assertEquals(message1.mSourceKey,
                Body.restoreBodySourceKey(mMockContext, messageId));
    }

    /**
     * Test delete body.
     * 1. create message without body (message id 1)
     * 2. create message with body (message id 2. The body has _id 1 and messageKey 2).
     * 3. delete first message.
     * 4. verify that body for message 2 has not been deleted.
     * 5. delete message 2, verify body is deleted.
     */
    public void testDeleteBody() {
        final ContentResolver resolver = mMockContext.getContentResolver();

        // Create account and mailboxes
        Account account1 = ProviderTestUtils.setupAccount("orphaned body", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;

        // 1. create message without body
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;

        // 2. create message with body
        Message message2 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, true,
                true, mMockContext);
        long message2Id = message2.mId;
        // verify body is there
        assertNotNull(loadBodyForMessageId(message2Id));

        // 3. delete first message
        resolver.delete(ContentUris.withAppendedId(Message.CONTENT_URI, message1Id), null, null);

        // 4. verify body for second message wasn't deleted
        assertNotNull(loadBodyForMessageId(message2Id));

        // 5. delete second message, check its body is deleted
        resolver.delete(ContentUris.withAppendedId(Message.CONTENT_URI, message2Id), null, null);
        assertNull(loadBodyForMessageId(message2Id));
    }

    /**
     * Test delete orphan bodies.
     * 1. create message without body (message id 1)
     * 2. create message with body (message id 2. Body has _id 1 and messageKey 2).
     * 3. delete first message.
     * 4. delete some other mailbox -- this triggers delete orphan bodies.
     * 5. verify that body for message 2 has not been deleted.
     */
    public void testDeleteOrphanBodies() {
        final ContentResolver resolver = mMockContext.getContentResolver();

        // Create account and two mailboxes
        Account account1 = ProviderTestUtils.setupAccount("orphaned body", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", account1Id, true, mMockContext);
        long box2Id = box2.mId;

        // 1. create message without body
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;

        // 2. create message with body
        Message message2 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, true,
                true, mMockContext);
        long message2Id = message2.mId;
        //verify body is there
        assertNotNull(loadBodyForMessageId(message2Id));

        // 3. delete first message
        resolver.delete(ContentUris.withAppendedId(Message.CONTENT_URI, message1Id), null, null);

        // 4. delete some mailbox (because it triggers "delete orphan bodies")
        resolver.delete(ContentUris.withAppendedId(Mailbox.CONTENT_URI, box2Id), null, null);

        // 5. verify body for second message wasn't deleted during "delete orphan bodies"
        assertNotNull(loadBodyForMessageId(message2Id));
    }

    /**
     * Note that we can't use EmailContent.count() here because it uses a projection including
     * count(*), and count(*) is incompatible with a LIMIT (i.e. the limit would be applied to the
     * single column returned with count(*), rather than to the query itself)
     */
    private int count(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor c = context.getContentResolver().query(uri, EmailContent.ID_PROJECTION, selection,
                selectionArgs, null);
        try {
            return c.getCount();
        } finally {
            c.close();
        }
    }

    public void testMessageQueryWithLimit() {
        final Context context = mMockContext;

        // Create account and two mailboxes
        Account acct = ProviderTestUtils.setupAccount("orphaned body", true, context);
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", acct.mId, true, context);
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", acct.mId, true, context);

        // Create 4 messages in box1
        ProviderTestUtils.setupMessage("message1", acct.mId, box1.mId, false, true, context);
        ProviderTestUtils.setupMessage("message2", acct.mId, box1.mId, false, true, context);
        ProviderTestUtils.setupMessage("message3", acct.mId, box1.mId, false, true, context);
        ProviderTestUtils.setupMessage("message4", acct.mId, box1.mId, false, true, context);

        // Create 4 messages in box2
        ProviderTestUtils.setupMessage("message1", acct.mId, box2.mId, false, true, context);
        ProviderTestUtils.setupMessage("message2", acct.mId, box2.mId, false, true, context);
        ProviderTestUtils.setupMessage("message3", acct.mId, box2.mId, false, true, context);
        ProviderTestUtils.setupMessage("message4", acct.mId, box2.mId, false, true, context);

        // Check normal case, special case (limit 1), and arbitrary limits
        assertEquals(8, count(mMockContext, Message.CONTENT_URI, null, null));
        assertEquals(1, count(mMockContext, EmailContent.uriWithLimit(Message.CONTENT_URI, 1),
                null, null));
        assertEquals(3, count(mMockContext, EmailContent.uriWithLimit(Message.CONTENT_URI, 3),
                null, null));
        assertEquals(8, count(mMockContext, EmailContent.uriWithLimit(Message.CONTENT_URI, 100),
                null, null));

        // Check that it works with selection/selection args
        String[] args = new String[] {Long.toString(box1.mId)};
        assertEquals(4, count(mMockContext, Message.CONTENT_URI,
                MessageColumns.MAILBOX_KEY + "=?", args));
        assertEquals(1, count(mMockContext,
                EmailContent.uriWithLimit(Message.CONTENT_URI, 1),
                MessageColumns.MAILBOX_KEY + "=?", args));
    }

    /**
     * Test delete orphan messages
     * 1. create message without body (message id 1)
     * 2. create message with body (message id 2. Body has _id 1 and messageKey 2).
     * 3. delete first message.
     * 4. delete some other mailbox -- this triggers delete orphan bodies.
     * 5. verify that body for message 2 has not been deleted.
     */
     public void testDeleteOrphanMessages() {
        final ContentResolver resolver = mMockContext.getContentResolver();
        final Context context = mMockContext;

        // Create account and two mailboxes
        Account acct = ProviderTestUtils.setupAccount("orphaned body", true, context);
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", acct.mId, true, context);
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", acct.mId, true, context);

        // Create 4 messages in box1
        Message msg1_1 =
            ProviderTestUtils.setupMessage("message1", acct.mId, box1.mId, false, true, context);
        Message msg1_2 =
            ProviderTestUtils.setupMessage("message2", acct.mId, box1.mId, false, true, context);
        Message msg1_3 =
            ProviderTestUtils.setupMessage("message3", acct.mId, box1.mId, false, true, context);
        Message msg1_4 =
            ProviderTestUtils.setupMessage("message4", acct.mId, box1.mId, false, true, context);

        // Create 4 messages in box2
        Message msg2_1 =
            ProviderTestUtils.setupMessage("message1", acct.mId, box2.mId, false, true, context);
        Message msg2_2 =
            ProviderTestUtils.setupMessage("message2", acct.mId, box2.mId, false, true, context);
        Message msg2_3 =
            ProviderTestUtils.setupMessage("message3", acct.mId, box2.mId, false, true, context);
        Message msg2_4 =
            ProviderTestUtils.setupMessage("message4", acct.mId, box2.mId, false, true, context);

        // Delete 2 from each mailbox
        resolver.delete(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, msg1_1.mId),
                null, null);
        resolver.delete(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, msg1_2.mId),
                null, null);
        resolver.delete(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, msg2_1.mId),
                null, null);
        resolver.delete(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, msg2_2.mId),
                null, null);

        // There should be 4 items in the deleted item table
        assertEquals(4, EmailContent.count(context, Message.DELETED_CONTENT_URI, null, null));

        // Update 2 from each mailbox
        ContentValues v = new ContentValues();
        v.put(MessageColumns.DISPLAY_NAME, "--updated--");
        resolver.update(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, msg1_3.mId),
                v, null, null);
        resolver.update(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, msg1_4.mId),
                v, null, null);
        resolver.update(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, msg2_3.mId),
                v, null, null);
        resolver.update(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, msg2_4.mId),
                v, null, null);

         // There should be 4 items in the updated item table
        assertEquals(4, EmailContent.count(context, Message.UPDATED_CONTENT_URI, null, null));

        // Manually add 2 messages from a "deleted" mailbox to deleted and updated tables
        // Use a value > 2 for the deleted box id
        long delBoxId = 10;
        // Create 4 messages in the "deleted" mailbox
        Message msgX_A =
            ProviderTestUtils.setupMessage("messageA", acct.mId, delBoxId, false, false, context);
        Message msgX_B =
            ProviderTestUtils.setupMessage("messageB", acct.mId, delBoxId, false, false, context);
        Message msgX_C =
            ProviderTestUtils.setupMessage("messageC", acct.mId, delBoxId, false, false, context);
        Message msgX_D =
            ProviderTestUtils.setupMessage("messageD", acct.mId, delBoxId, false, false, context);

        ContentValues cv;
        // We have to assign id's manually because there are no autoincrement id's for these tables
        // Start with an id that won't exist, since id's in these tables must be unique
        long msgId = 10;
        // It's illegal to manually insert these, so we need to catch the exception
        // NOTE: The insert succeeds, and then throws the exception
        try {
            cv = msgX_A.toContentValues();
            cv.put(EmailContent.RECORD_ID, msgId++);
            resolver.insert(Message.DELETED_CONTENT_URI, cv);
        } catch (IllegalArgumentException e) {
        }
        try {
            cv = msgX_B.toContentValues();
            cv.put(EmailContent.RECORD_ID, msgId++);
            resolver.insert(Message.DELETED_CONTENT_URI, cv);
        } catch (IllegalArgumentException e) {
        }
        try {
            cv = msgX_C.toContentValues();
            cv.put(EmailContent.RECORD_ID, msgId++);
            resolver.insert(Message.UPDATED_CONTENT_URI, cv);
        } catch (IllegalArgumentException e) {
        }
        try {
            cv = msgX_D.toContentValues();
            cv.put(EmailContent.RECORD_ID, msgId++);
            resolver.insert(Message.UPDATED_CONTENT_URI, cv);
        } catch (IllegalArgumentException e) {
        }

        // There should be 6 items in the deleted and updated tables
        assertEquals(6, EmailContent.count(context, Message.UPDATED_CONTENT_URI, null, null));
        assertEquals(6, EmailContent.count(context, Message.DELETED_CONTENT_URI, null, null));

        // Delete the orphans
        EmailProvider.deleteOrphans(EmailProvider.getReadableDatabase(context),
                Message.DELETED_TABLE_NAME);
        EmailProvider.deleteOrphans(EmailProvider.getReadableDatabase(context),
                Message.UPDATED_TABLE_NAME);

        // There should now be 4 messages in each of the deleted and updated tables again
        assertEquals(4, EmailContent.count(context, Message.UPDATED_CONTENT_URI, null, null));
        assertEquals(4, EmailContent.count(context, Message.DELETED_CONTENT_URI, null, null));
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
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id,
                false, true, mMockContext);
        Message message2 = ProviderTestUtils.setupMessage("message2", account1Id, box1Id,
                false, true, mMockContext);
        Message message3 = ProviderTestUtils.setupMessage("message3", account1Id, box1Id,
                false, true, mMockContext);
        Message message4 = ProviderTestUtils.setupMessage("message4", account1Id, box1Id,
                false, true, mMockContext);
        ProviderTestUtils.setupMessage("message5", account1Id, box1Id, false, true, mMockContext);
        ProviderTestUtils.setupMessage("message6", account1Id, box1Id, false, true, mMockContext);

        String selection = EmailContent.MessageColumns.ACCOUNT_KEY + "=? AND " +
                EmailContent.MessageColumns.MAILBOX_KEY + "=?";
        String[] selArgs = new String[] { String.valueOf(account1Id), String.valueOf(box1Id) };

        // make sure there are six messages
        int numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(6, numMessages);

        ContentValues cv = new ContentValues();
        cv.put(Message.SERVER_ID, "SERVER_ID");
        ContentResolver resolver = mMockContext.getContentResolver();

        // Update two messages
        resolver.update(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, message1.mId),
                cv, null, null);
        resolver.update(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, message2.mId),
                cv, null, null);
        // Delete two messages
        resolver.delete(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, message3.mId),
                null, null);
        resolver.delete(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, message4.mId),
                null, null);

        // There should now be two messages in updated/deleted, and 4 in messages
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(4, numMessages);
        numMessages = EmailContent.count(mMockContext, Message.DELETED_CONTENT_URI, selection,
                selArgs);
        assertEquals(2, numMessages);
        numMessages = EmailContent.count(mMockContext, Message.UPDATED_CONTENT_URI, selection,
                selArgs);
        assertEquals(2, numMessages);

        // now delete the mailbox
        Uri uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, box1Id);
        resolver.delete(uri, null, null);

        // there should now be zero messages in all three tables
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(0, numMessages);
        numMessages = EmailContent.count(mMockContext, Message.DELETED_CONTENT_URI, selection,
                selArgs);
        assertEquals(0, numMessages);
        numMessages = EmailContent.count(mMockContext, Message.UPDATED_CONTENT_URI, selection,
                selArgs);
        assertEquals(0, numMessages);
    }

    /**
     * Test cascaded delete message
     * Confirms that deleting a message will also delete its body & attachments
     */
    public void testCascadeMessageDelete() {
        Account account1 = ProviderTestUtils.setupAccount("message-cascade", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;

        // Each message has a body, and also give each 2 attachments
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, true,
                false, mMockContext);
        ArrayList<Attachment> atts = new ArrayList<Attachment>();
        for (int i = 0; i < 2; i++) {
            atts.add(ProviderTestUtils.setupAttachment(
                    -1, expectedAttachmentNames[i], expectedAttachmentSizes[i],
                    false, mMockContext));
        }
        message1.mAttachments = atts;
        message1.save(mMockContext);
        long message1Id = message1.mId;

        Message message2 = ProviderTestUtils.setupMessage("message2", account1Id, box1Id, true,
                false, mMockContext);
        atts = new ArrayList<Attachment>();
        for (int i = 0; i < 2; i++) {
            atts.add(ProviderTestUtils.setupAttachment(
                    -1, expectedAttachmentNames[i], expectedAttachmentSizes[i],
                    false, mMockContext));
        }
        message2.mAttachments = atts;
        message2.save(mMockContext);
        long message2Id = message2.mId;

        // Set up to test total counts of bodies & attachments for our test messages
        String bodySelection = BodyColumns.MESSAGE_KEY + " IN (?,?)";
        String attachmentSelection = AttachmentColumns.MESSAGE_KEY + " IN (?,?)";
        String[] selArgs = new String[] { String.valueOf(message1Id), String.valueOf(message2Id) };

        // make sure there are two bodies
        int numBodies = EmailContent.count(mMockContext, Body.CONTENT_URI, bodySelection, selArgs);
        assertEquals(2, numBodies);

        // make sure there are four attachments
        int numAttachments = EmailContent.count(mMockContext, Attachment.CONTENT_URI,
                attachmentSelection, selArgs);
        assertEquals(4, numAttachments);

        // now delete one of the messages
        Uri uri = ContentUris.withAppendedId(Message.CONTENT_URI, message1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // there should be one body and two attachments
        numBodies = EmailContent.count(mMockContext, Body.CONTENT_URI, bodySelection, selArgs);
        assertEquals(1, numBodies);

        numAttachments = EmailContent.count(mMockContext, Attachment.CONTENT_URI,
                attachmentSelection, selArgs);
        assertEquals(2, numAttachments);

        // now delete the other message
        uri = ContentUris.withAppendedId(Message.CONTENT_URI, message2Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there are no bodies or attachments
        numBodies = EmailContent.count(mMockContext, Body.CONTENT_URI, bodySelection, selArgs);
        assertEquals(0, numBodies);

        numAttachments = EmailContent.count(mMockContext, Attachment.CONTENT_URI,
                attachmentSelection, selArgs);
        assertEquals(0, numAttachments);
    }

    /**
     * Test that our unique file name algorithm works as expected.  Since this test requires an
     * SD card, we check the environment first, and return immediately if none is mounted.
     * @throws IOException
     */
    public void testCreateUniqueFile() throws IOException {
        // Delete existing files, if they exist
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            return;
        }
        try {
            String fileName = "A11achm3n1.doc";
            File uniqueFile = Attachment.createUniqueFile(fileName);
            assertEquals(fileName, uniqueFile.getName());
            if (uniqueFile.createNewFile()) {
                uniqueFile = Attachment.createUniqueFile(fileName);
                assertEquals("A11achm3n1-2.doc", uniqueFile.getName());
                if (uniqueFile.createNewFile()) {
                    uniqueFile = Attachment.createUniqueFile(fileName);
                    assertEquals("A11achm3n1-3.doc", uniqueFile.getName());
                }
           }
            fileName = "A11achm3n1";
            uniqueFile = Attachment.createUniqueFile(fileName);
            assertEquals(fileName, uniqueFile.getName());
            if (uniqueFile.createNewFile()) {
                uniqueFile = Attachment.createUniqueFile(fileName);
                assertEquals("A11achm3n1-2", uniqueFile.getName());
            }
        } finally {
            File directory = Environment.getExternalStorageDirectory();
            // These are the files that should be created earlier in the test.  Make sure
            // they are deleted for the next go-around
            String[] fileNames = new String[] {"A11achm3n1.doc", "A11achm3n1-2.doc", "A11achm3n1"};
            int length = fileNames.length;
            for (int i = 0; i < length; i++) {
                File file = new File(directory, fileNames[i]);
                if (file.exists()) {
                    file.delete();
                }
            }
        }
    }

    /**
     * Test retrieving attachments by message ID (using EmailContent.Attachment.MESSAGE_ID_URI)
     */
    public void testGetAttachmentByMessageIdUri() {

        // Note, we don't strictly need accounts, mailboxes or messages to run this test.
        Attachment a1 = ProviderTestUtils.setupAttachment(1, "a1", 100, true, mMockContext);
        Attachment a2 = ProviderTestUtils.setupAttachment(1, "a2", 200, true, mMockContext);
        ProviderTestUtils.setupAttachment(2, "a3", 300, true, mMockContext);
        ProviderTestUtils.setupAttachment(2, "a4", 400, true, mMockContext);

        // Now ask for the attachments of message id=1
        // Note: Using the "sort by size" trick to bring them back in expected order
        Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, 1);
        Cursor c = mMockContext.getContentResolver().query(uri, Attachment.CONTENT_PROJECTION,
                null, null, Attachment.SIZE);
        assertEquals(2, c.getCount());

        try {
            c.moveToFirst();
            Attachment a1Get = EmailContent.getContent(c, Attachment.class);
            ProviderTestUtils.assertAttachmentEqual("getAttachByUri-1", a1, a1Get);
            c.moveToNext();
            Attachment a2Get = EmailContent.getContent(c, Attachment.class);
            ProviderTestUtils.assertAttachmentEqual("getAttachByUri-2", a2, a2Get);
        } finally {
            c.close();
        }
    }

    /**
     * Test deleting attachments by message ID (using EmailContent.Attachment.MESSAGE_ID_URI)
     */
    public void testDeleteAttachmentByMessageIdUri() {
        ContentResolver mockResolver = mMockContext.getContentResolver();

        // Note, we don't strictly need accounts, mailboxes or messages to run this test.
        ProviderTestUtils.setupAttachment(1, "a1", 100, true, mMockContext);
        ProviderTestUtils.setupAttachment(1, "a2", 200, true, mMockContext);
        Attachment a3 = ProviderTestUtils.setupAttachment(2, "a3", 300, true, mMockContext);
        Attachment a4 = ProviderTestUtils.setupAttachment(2, "a4", 400, true, mMockContext);

        // Delete all attachments for message id=1
        Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, 1);
        mockResolver.delete(uri, null, null);

        // Read back all attachments and confirm that we have the expected remaining attachments
        // (the attachments that are set for message id=2).  Note order-by size to simplify test.
        Cursor c = mockResolver.query(Attachment.CONTENT_URI, Attachment.CONTENT_PROJECTION,
                null, null, Attachment.SIZE);
        assertEquals(2, c.getCount());

        try {
            c.moveToFirst();
            Attachment a3Get = EmailContent.getContent(c, Attachment.class);
            ProviderTestUtils.assertAttachmentEqual("getAttachByUri-3", a3, a3Get);
            c.moveToNext();
            Attachment a4Get = EmailContent.getContent(c, Attachment.class);
            ProviderTestUtils.assertAttachmentEqual("getAttachByUri-4", a4, a4Get);
        } finally {
            c.close();
        }
    }

    /**
     * Tests of default account behavior
     *
     * 1.  Simple set/get
     * 2.  Moving default between 3 accounts
     * 3.  Delete default, make sure another becomes default
     */
    public void testSetGetDefaultAccount() {
        // There should be no default account if there are no accounts
        long defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(-1, defaultAccountId);

        Account account1 = ProviderTestUtils.setupAccount("account-default-1", true, mMockContext);
        long account1Id = account1.mId;
        Account account2 = ProviderTestUtils.setupAccount("account-default-2", true, mMockContext);
        long account2Id = account2.mId;
        Account account3 = ProviderTestUtils.setupAccount("account-default-3", true, mMockContext);
        long account3Id = account3.mId;

        // With three accounts, but none marked default, confirm that some default account
        // is returned.  Which one is undefined here.
        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertTrue(defaultAccountId == account1Id
                    || defaultAccountId == account2Id
                    || defaultAccountId == account3Id);

        updateIsDefault(account1, true);
        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(account1Id, defaultAccountId);

        updateIsDefault(account2, true);
        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(account2Id, defaultAccountId);

        updateIsDefault(account3, true);
        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(account3Id, defaultAccountId);

        // Now delete a non-default account and confirm no change
        Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, account1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(account3Id, defaultAccountId);

        // Now confirm deleting the default account and it switches to another one
        uri = ContentUris.withAppendedId(Account.CONTENT_URI, account3Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(account2Id, defaultAccountId);

        // Now delete the final account and confirm there are no default accounts again
        uri = ContentUris.withAppendedId(Account.CONTENT_URI, account2Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(-1, defaultAccountId);
    }

    private void updateIsDefault(Account account, boolean newState) {
        account.setDefaultAccount(newState);
        ContentValues cv = new ContentValues();
        cv.put(AccountColumns.IS_DEFAULT, account.mIsDefault);
        account.update(mMockContext, cv);
    }

    public static Message setupUnreadMessage(String name, long accountId, long mailboxId,
            boolean addBody, boolean saveIt, Context context) {
        Message msg =
            ProviderTestUtils.setupMessage(name, accountId, mailboxId, addBody, false, context);
        msg.mFlagRead = false;
        if (saveIt) {
            msg.save(context);
        }
        return msg;
    }

    public void testUnreadCountTriggers() {
        // Start with one account and three mailboxes
        Account account = ProviderTestUtils.setupAccount("triggers", true, mMockContext);
        Mailbox boxA = ProviderTestUtils.setupMailbox("boxA", account.mId, true, mMockContext);
        Mailbox boxB = ProviderTestUtils.setupMailbox("boxB", account.mId, true, mMockContext);
        Mailbox boxC = ProviderTestUtils.setupMailbox("boxC", account.mId, true, mMockContext);

        // Make sure there are no unreads
        assertEquals(0, getUnreadCount(boxA.mId));
        assertEquals(0, getUnreadCount(boxB.mId));
        assertEquals(0, getUnreadCount(boxC.mId));

        // Create 4 unread messages (only 3 named) in boxA
        Message message1 = setupUnreadMessage("message1", account.mId, boxA.mId,
                false, true, mMockContext);
        Message message2= setupUnreadMessage("message2", account.mId, boxA.mId,
                false, true, mMockContext);
        Message message3 =  setupUnreadMessage("message3", account.mId, boxA.mId,
                false, true, mMockContext);
        setupUnreadMessage("message4", account.mId, boxC.mId, false, true, mMockContext);

        // Make sure the unreads are where we expect them
        assertEquals(3, getUnreadCount(boxA.mId));
        assertEquals(0, getUnreadCount(boxB.mId));
        assertEquals(1, getUnreadCount(boxC.mId));

        // After deleting message 1, the count in box A should be decremented (to 2)
        ContentResolver cr = mMockContext.getContentResolver();
        Uri uri = ContentUris.withAppendedId(Message.CONTENT_URI, message1.mId);
        cr.delete(uri, null, null);
        assertEquals(2, getUnreadCount(boxA.mId));
        assertEquals(0, getUnreadCount(boxB.mId));
        assertEquals(1, getUnreadCount(boxC.mId));

        // Move message 2 to box B, leaving 1 in box A and 1 in box B
        message2.mMailboxKey = boxB.mId;
        ContentValues cv = new ContentValues();
        cv.put(MessageColumns.MAILBOX_KEY, boxB.mId);
        cr.update(ContentUris.withAppendedId(Message.CONTENT_URI, message2.mId), cv, null, null);
        assertEquals(1, getUnreadCount(boxA.mId));
        assertEquals(1, getUnreadCount(boxB.mId));
        assertEquals(1, getUnreadCount(boxC.mId));

        // Mark message 3 (from box A) read, leaving 0 in box A
        cv.clear();
        cv.put(MessageColumns.FLAG_READ, 1);
        cr.update(ContentUris.withAppendedId(Message.CONTENT_URI, message3.mId), cv, null, null);
        assertEquals(0, getUnreadCount(boxA.mId));
        assertEquals(1, getUnreadCount(boxB.mId));
        assertEquals(1, getUnreadCount(boxC.mId));

        // Move message 3 to box C; should be no change (it's read)
        message3.mMailboxKey = boxC.mId;
        cv.clear();
        cv.put(MessageColumns.MAILBOX_KEY, boxC.mId);
        cr.update(ContentUris.withAppendedId(Message.CONTENT_URI, message3.mId), cv, null, null);
        assertEquals(0, getUnreadCount(boxA.mId));
        assertEquals(1, getUnreadCount(boxB.mId));
        assertEquals(1, getUnreadCount(boxC.mId));

        // Mark message 3 unread; it's now in box C, so that box's count should go up to 3
        cv.clear();
        cv.put(MessageColumns.FLAG_READ, 0);
        cr.update(ContentUris.withAppendedId(Message.CONTENT_URI, message3.mId), cv, null, null);
        assertEquals(0, getUnreadCount(boxA.mId));
        assertEquals(1, getUnreadCount(boxB.mId));
        assertEquals(2, getUnreadCount(boxC.mId));
    }

    /**
     * Test for EmailProvider.createIndex().
     * Check that it returns exacly the same string as the one used previously for index creation.
     */
    public void testCreateIndex() {
        String oldStr = "create index message_" + MessageColumns.TIMESTAMP
            + " on " + Message.TABLE_NAME + " (" + MessageColumns.TIMESTAMP + ");";
        String newStr = EmailProvider.createIndex(Message.TABLE_NAME, MessageColumns.TIMESTAMP);
        assertEquals(newStr, oldStr);
    }

    public void testDatabaseCorruptionRecovery() {
        final ContentResolver resolver = mMockContext.getContentResolver();
        final Context context = mMockContext;

        // Create account and two mailboxes
        Account acct = ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", acct.mId, true, context);

        // Create 4 messages in box1 with bodies
        ProviderTestUtils.setupMessage("message1", acct.mId, box1.mId, true, true, context);
        ProviderTestUtils.setupMessage("message2", acct.mId, box1.mId, true, true, context);
        ProviderTestUtils.setupMessage("message3", acct.mId, box1.mId, true, true, context);
        ProviderTestUtils.setupMessage("message4", acct.mId, box1.mId, true, true, context);

        // Confirm there are four messages
        int count = EmailContent.count(mMockContext, Message.CONTENT_URI, null, null);
        assertEquals(4, count);
        // Confirm there are four bodies
        count = EmailContent.count(mMockContext, Body.CONTENT_URI, null, null);
        assertEquals(4, count);

        // Find the EmailProvider.db file
        File dbFile = mMockContext.getDatabasePath(EmailProvider.DATABASE_NAME);
        // The EmailProvider.db database should exist (the provider creates it automatically)
        assertTrue(dbFile != null);
        assertTrue(dbFile.exists());
        // Delete it, and confirm it is gone
        assertTrue(dbFile.delete());
        assertFalse(dbFile.exists());

        // Find the EmailProviderBody.db file
        dbFile = mMockContext.getDatabasePath(EmailProvider.BODY_DATABASE_NAME);
        // The EmailProviderBody.db database should still exist
        assertTrue(dbFile != null);
        assertTrue(dbFile.exists());

        // URI to uncache the databases
        // This simulates the Provider starting up again (otherwise, it will still be pointing to
        // the already opened files)
        // Note that we only have access to the EmailProvider via the ContentResolver; therefore,
        // we cannot directly call into the provider and use a URI for this
        resolver.update(EmailProvider.INTEGRITY_CHECK_URI, null, null, null);

        // TODO We should check for the deletion of attachment files once this is implemented in
        // the provider

        // Explanation for what happens below...
        // The next time the database is created by the provider, it will notice that there's
        // already a EmailProviderBody.db file.  In this case, it will delete that database to
        // ensure that both are in sync (and empty)

        // Confirm there are no bodies
        count = EmailContent.count(mMockContext, Body.CONTENT_URI, null, null);
        assertEquals(0, count);

        // Confirm there are no messages
        count = EmailContent.count(mMockContext, Message.CONTENT_URI, null, null);
        assertEquals(0, count);
    }

    public void testBodyDatabaseCorruptionRecovery() {
        final ContentResolver resolver = mMockContext.getContentResolver();
        final Context context = mMockContext;

        // Create account and two mailboxes
        Account acct = ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", acct.mId, true, context);

        // Create 4 messages in box1 with bodies
        ProviderTestUtils.setupMessage("message1", acct.mId, box1.mId, true, true, context);
        ProviderTestUtils.setupMessage("message2", acct.mId, box1.mId, true, true, context);
        ProviderTestUtils.setupMessage("message3", acct.mId, box1.mId, true, true, context);
        ProviderTestUtils.setupMessage("message4", acct.mId, box1.mId, true, true, context);

        // Confirm there are four messages
        int count = EmailContent.count(mMockContext, Message.CONTENT_URI, null, null);
        assertEquals(4, count);
        // Confirm there are four bodies
        count = EmailContent.count(mMockContext, Body.CONTENT_URI, null, null);
        assertEquals(4, count);

        // Find the EmailProviderBody.db file
        File dbFile = mMockContext.getDatabasePath(EmailProvider.BODY_DATABASE_NAME);
        // The EmailProviderBody.db database should exist (the provider creates it automatically)
        assertTrue(dbFile != null);
        assertTrue(dbFile.exists());
        // Delete it, and confirm it is gone
        assertTrue(dbFile.delete());
        assertFalse(dbFile.exists());

        // Find the EmailProvider.db file
        dbFile = mMockContext.getDatabasePath(EmailProvider.DATABASE_NAME);
        // The EmailProviderBody.db database should still exist
        assertTrue(dbFile != null);
        assertTrue(dbFile.exists());

        // URI to uncache the databases
        // This simulates the Provider starting up again (otherwise, it will still be pointing to
        // the already opened files)
        // Note that we only have access to the EmailProvider via the ContentResolver; therefore,
        // we cannot directly call into the provider and use a URI for this
        resolver.update(EmailProvider.INTEGRITY_CHECK_URI, null, null, null);

        // TODO We should check for the deletion of attachment files once this is implemented in
        // the provider

        // Explanation for what happens below...
        // The next time the body database is created by the provider, it will notice that there's
        // already a populated EmailProvider.db file.  In this case, it will delete that database to
        // ensure that both are in sync (and empty)

        // Confirm there are no messages
        count = EmailContent.count(mMockContext, Message.CONTENT_URI, null, null);
        assertEquals(0, count);

        // Confirm there are no bodies
        count = EmailContent.count(mMockContext, Body.CONTENT_URI, null, null);
        assertEquals(0, count);
    }

    public void testFindMailboxOfType() {
        final Context context = mMockContext;

        // Create two accounts and a variety of mailbox types
        Account acct1 = ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox acct1Inbox =
            ProviderTestUtils.setupMailbox("Inbox1", acct1.mId, true, context, Mailbox.TYPE_INBOX);
        Mailbox acct1Calendar
        = ProviderTestUtils.setupMailbox("Cal1", acct1.mId, true, context, Mailbox.TYPE_CALENDAR);
        Mailbox acct1Contacts =
            ProviderTestUtils.setupMailbox("Con1", acct1.mId, true, context, Mailbox.TYPE_CONTACTS);
        Account acct2 = ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox acct2Inbox =
            ProviderTestUtils.setupMailbox("Inbox2", acct2.mId, true, context, Mailbox.TYPE_INBOX);
        Mailbox acct2Calendar =
            ProviderTestUtils.setupMailbox("Cal2", acct2.mId, true, context, Mailbox.TYPE_CALENDAR);
        Mailbox acct2Contacts =
            ProviderTestUtils.setupMailbox("Con2", acct2.mId, true, context, Mailbox.TYPE_CONTACTS);

        // Check that we can find them by type
        assertEquals(acct1Inbox.mId,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_INBOX));
        assertEquals(acct2Inbox.mId,
                Mailbox.findMailboxOfType(context, acct2.mId, Mailbox.TYPE_INBOX));
        assertEquals(acct1Calendar.mId,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_CALENDAR));
        assertEquals(acct2Calendar.mId,
                Mailbox.findMailboxOfType(context, acct2.mId, Mailbox.TYPE_CALENDAR));
        assertEquals(acct1Contacts.mId,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_CONTACTS));
        assertEquals(acct2Contacts.mId,
                Mailbox.findMailboxOfType(context, acct2.mId, Mailbox.TYPE_CONTACTS));

        // Check that nonexistent mailboxes are not returned
        assertEquals(Mailbox.NO_MAILBOX,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_DRAFTS));
        assertEquals(Mailbox.NO_MAILBOX,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_OUTBOX));

        // delete account 1 and confirm no mailboxes are returned
        context.getContentResolver().delete(
                ContentUris.withAppendedId(Account.CONTENT_URI, acct1.mId), null, null);
        assertEquals(Mailbox.NO_MAILBOX,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_INBOX));
        assertEquals(Mailbox.NO_MAILBOX,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_CALENDAR));
        assertEquals(Mailbox.NO_MAILBOX,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_CONTACTS));
    }

    public void testRestoreMailboxOfType() {
        final Context context = mMockContext;

        // Create two accounts and a variety of mailbox types
        Account acct1 = ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox acct1Inbox =
            ProviderTestUtils.setupMailbox("Inbox1", acct1.mId, true, context, Mailbox.TYPE_INBOX);
        Mailbox acct1Calendar
        = ProviderTestUtils.setupMailbox("Cal1", acct1.mId, true, context, Mailbox.TYPE_CALENDAR);
        Mailbox acct1Contacts =
            ProviderTestUtils.setupMailbox("Con1", acct1.mId, true, context, Mailbox.TYPE_CONTACTS);
        Account acct2 = ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox acct2Inbox =
            ProviderTestUtils.setupMailbox("Inbox2", acct2.mId, true, context, Mailbox.TYPE_INBOX);
        Mailbox acct2Calendar =
            ProviderTestUtils.setupMailbox("Cal2", acct2.mId, true, context, Mailbox.TYPE_CALENDAR);
        Mailbox acct2Contacts =
            ProviderTestUtils.setupMailbox("Con2", acct2.mId, true, context, Mailbox.TYPE_CONTACTS);

        // Check that we can find them by type
        ProviderTestUtils.assertMailboxEqual("testRestoreMailboxOfType", acct1Inbox,
                Mailbox.restoreMailboxOfType(context, acct1.mId, Mailbox.TYPE_INBOX));
        ProviderTestUtils.assertMailboxEqual("testRestoreMailboxOfType", acct2Inbox,
                Mailbox.restoreMailboxOfType(context, acct2.mId, Mailbox.TYPE_INBOX));
        ProviderTestUtils.assertMailboxEqual("testRestoreMailboxOfType", acct1Calendar,
                Mailbox.restoreMailboxOfType(context, acct1.mId, Mailbox.TYPE_CALENDAR));
        ProviderTestUtils.assertMailboxEqual("testRestoreMailboxOfType", acct2Calendar,
                Mailbox.restoreMailboxOfType(context, acct2.mId, Mailbox.TYPE_CALENDAR));
        ProviderTestUtils.assertMailboxEqual("testRestoreMailboxOfType", acct1Contacts,
                Mailbox.restoreMailboxOfType(context, acct1.mId, Mailbox.TYPE_CONTACTS));
        ProviderTestUtils.assertMailboxEqual("testRestoreMailboxOfType", acct2Contacts,
                Mailbox.restoreMailboxOfType(context, acct2.mId, Mailbox.TYPE_CONTACTS));
    }

    public void testAccountIsSecurityHold() {
        final Context context = mMockContext;
        Account acct1 = ProviderTestUtils.setupAccount("acct1", true, context);

        Account acct2 = ProviderTestUtils.setupAccount("acct2", false, context);
        acct2.mFlags |= Account.FLAGS_SECURITY_HOLD;
        acct2.save(context);

        assertFalse(Account.isSecurityHold(context, acct1.mId));
        assertTrue(Account.isSecurityHold(context, acct2.mId));
        assertFalse(Account.isSecurityHold(context, 9999999)); // No such account
   }

    public void testClearAccountHoldFlags() {
        Account a1 = ProviderTestUtils.setupAccount("holdflag-1", false, mMockContext);
        a1.mFlags = Account.FLAGS_NOTIFY_NEW_MAIL;
        a1.save(mMockContext);
        Account a2 = ProviderTestUtils.setupAccount("holdflag-2", false, mMockContext);
        a2.mFlags = Account.FLAGS_VIBRATE_ALWAYS | Account.FLAGS_SECURITY_HOLD;
        a2.save(mMockContext);

        // bulk clear
        Account.clearSecurityHoldOnAllAccounts(mMockContext);

        // confirm new values as expected - no hold flags; other flags unmolested
        Account a1a = Account.restoreAccountWithId(mMockContext, a1.mId);
        assertEquals(Account.FLAGS_NOTIFY_NEW_MAIL, a1a.mFlags);
        Account a2a = Account.restoreAccountWithId(mMockContext, a2.mId);
        assertEquals(Account.FLAGS_VIBRATE_ALWAYS, a2a.mFlags);
    }

    /**
     * @return the number of messages in a mailbox.
     */
    private int getMessageCount(long mailboxId) {
        return Utility.getFirstRowInt(mMockContext,
                ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                new String[] {MailboxColumns.MESSAGE_COUNT}, null, null, null, 0);
    }

    /** Set -1 to the message count of all mailboxes for the recalculateMessageCount test. */
    private void setMinusOneToMessageCounts() {
        ContentValues values = new ContentValues();
        values.put(MailboxColumns.MESSAGE_COUNT, -1);

        // EmailProvider.update() doesn't allow updating messageCount, so directly use the DB.
        SQLiteDatabase db = getProvider().getDatabase(mMockContext);
        db.update(Mailbox.TABLE_NAME, values, null, null);
    }

    /**
     * Test for the message count triggers (insert/delete/move mailbox), and also
     * {@link EmailProvider#recalculateMessageCount}.
     *
     * It also covers:
     * - {@link Mailbox#getMessageCountByMailboxType(Context, int)}
     * - {@link Mailbox#getUnreadCountByAccountAndMailboxType(Context, long, int)}
     * - {@link Mailbox#getUnreadCountByMailboxType(Context, int)}
     * - {@link Message#getFavoriteMessageCount(Context)}
     */
    public void testMessageCount() {
        final Context c = mMockContext;

        // Create 2 accounts
        Account a1 = ProviderTestUtils.setupAccount("holdflag-1", true, c);
        Account a2 = ProviderTestUtils.setupAccount("holdflag-2", true, c);

        // Create 2 mailboxes for each account
        Mailbox b1 = ProviderTestUtils.setupMailbox("box1", a1.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox b2 = ProviderTestUtils.setupMailbox("box2", a1.mId, true, c, Mailbox.TYPE_OUTBOX);
        Mailbox b3 = ProviderTestUtils.setupMailbox("box3", a2.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox b4 = ProviderTestUtils.setupMailbox("box4", a2.mId, true, c, Mailbox.TYPE_OUTBOX);

        // 0. Check the initial values, just in case.

        assertEquals(0, getMessageCount(b1.mId));
        assertEquals(0, getMessageCount(b2.mId));
        assertEquals(0, getMessageCount(b3.mId));
        assertEquals(0, getMessageCount(b4.mId));

        assertEquals(0, Message.getFavoriteMessageCount(c));
        assertEquals(0, Mailbox.getUnreadCountByMailboxType(c, Mailbox.TYPE_INBOX));
        assertEquals(0, Mailbox.getUnreadCountByMailboxType(c, Mailbox.TYPE_OUTBOX));
        assertEquals(0, Mailbox.getMessageCountByMailboxType(c, Mailbox.TYPE_INBOX));
        assertEquals(0, Mailbox.getMessageCountByMailboxType(c, Mailbox.TYPE_OUTBOX));

        assertEquals(0, Mailbox.getUnreadCountByAccountAndMailboxType(c,
                a1.mId, Mailbox.TYPE_INBOX));
        assertEquals(0, Mailbox.getUnreadCountByAccountAndMailboxType(c,
                a1.mId, Mailbox.TYPE_OUTBOX));
        assertEquals(0, Mailbox.getUnreadCountByAccountAndMailboxType(c,
                a2.mId, Mailbox.TYPE_INBOX));
        assertEquals(0, Mailbox.getUnreadCountByAccountAndMailboxType(c,
                a2.mId, Mailbox.TYPE_OUTBOX));

        // 1. Test for insert triggers.

        // Create some messages
        // b1 (account 1, inbox): 1 message
        Message m11 = createMessage(c, b1, true, false);

        // b2 (account 1, outbox): 2 message
        Message m21 = createMessage(c, b2, false, false);
        Message m22 = createMessage(c, b2, true, true);

        // b3 (account 2, inbox): 3 message
        Message m31 = createMessage(c, b3, false, false);
        Message m32 = createMessage(c, b3, false, false);
        Message m33 = createMessage(c, b3, true, true);

        // b4 (account 2, outbox) has no messages.

        // Check message counts
        assertEquals(1, getMessageCount(b1.mId));
        assertEquals(2, getMessageCount(b2.mId));
        assertEquals(3, getMessageCount(b3.mId));
        assertEquals(0, getMessageCount(b4.mId));

        // Check the simple counting methods.
        assertEquals(3, Message.getFavoriteMessageCount(c));
        assertEquals(3, Mailbox.getUnreadCountByMailboxType(c, Mailbox.TYPE_INBOX));
        assertEquals(1, Mailbox.getUnreadCountByMailboxType(c, Mailbox.TYPE_OUTBOX));
        assertEquals(4, Mailbox.getMessageCountByMailboxType(c, Mailbox.TYPE_INBOX));
        assertEquals(2, Mailbox.getMessageCountByMailboxType(c, Mailbox.TYPE_OUTBOX));

        assertEquals(1, Mailbox.getUnreadCountByAccountAndMailboxType(c,
                a1.mId, Mailbox.TYPE_INBOX));
        assertEquals(1, Mailbox.getUnreadCountByAccountAndMailboxType(c,
                a1.mId, Mailbox.TYPE_OUTBOX));
        assertEquals(2, Mailbox.getUnreadCountByAccountAndMailboxType(c,
                a2.mId, Mailbox.TYPE_INBOX));
        assertEquals(0, Mailbox.getUnreadCountByAccountAndMailboxType(c,
                a2.mId, Mailbox.TYPE_OUTBOX));

        // 2. test for recalculateMessageCount.

        // First, invalidate the message counts.
        setMinusOneToMessageCounts();
        assertEquals(-1, getMessageCount(b1.mId));
        assertEquals(-1, getMessageCount(b2.mId));
        assertEquals(-1, getMessageCount(b3.mId));
        assertEquals(-1, getMessageCount(b4.mId));

        // Batch update.
        SQLiteDatabase db = getProvider().getDatabase(mMockContext);
        EmailProvider.recalculateMessageCount(db);

        // Check message counts
        assertEquals(1, getMessageCount(b1.mId));
        assertEquals(2, getMessageCount(b2.mId));
        assertEquals(3, getMessageCount(b3.mId));
        assertEquals(0, getMessageCount(b4.mId));

        // 3. Check the "move mailbox" trigger.

        // Move m32 (in mailbox 3) to mailbox 4.
        ContentValues values = new ContentValues();
        values.put(MessageColumns.MAILBOX_KEY, b4.mId);

        getProvider().update(Message.CONTENT_URI, values, EmailContent.ID_SELECTION,
                new String[] {"" + m32.mId});

        // Check message counts
        assertEquals(1, getMessageCount(b1.mId));
        assertEquals(2, getMessageCount(b2.mId));
        assertEquals(2, getMessageCount(b3.mId));
        assertEquals(1, getMessageCount(b4.mId));

        // 4. Check the delete trigger.

        // Delete m11 (in mailbox 1)
        getProvider().delete(Message.CONTENT_URI, EmailContent.ID_SELECTION,
                new String[] {"" + m11.mId});
        // Delete m21 (in mailbox 2)
        getProvider().delete(Message.CONTENT_URI, EmailContent.ID_SELECTION,
                new String[] {"" + m21.mId});

        // Check message counts
        assertEquals(0, getMessageCount(b1.mId));
        assertEquals(1, getMessageCount(b2.mId));
        assertEquals(2, getMessageCount(b3.mId));
        assertEquals(1, getMessageCount(b4.mId));

        // No such mailbox type.
        assertEquals(0, Mailbox.getMessageCountByMailboxType(c, 99999));
        assertEquals(0, Mailbox.getUnreadCountByAccountAndMailboxType(c, a1.mId, 99999));
        assertEquals(0, Mailbox.getUnreadCountByMailboxType(c, 99999));

        // No such account
        assertEquals(0, Mailbox.getUnreadCountByAccountAndMailboxType(c,
                99999, Mailbox.TYPE_INBOX));
    }

    private static Message createMessage(Context c, Mailbox b, boolean starred, boolean read) {
        return ProviderTestUtils.setupMessage("1", b.mAccountKey, b.mId, true, true, c, starred,
                read);
    }

    public void testAccountIsEasAccount() {
        Account account = new Account();
        // No hostauth
        assertFalse(account.isEasAccount(mMockContext));

        checkAccountIsEasAccount(null, false);
        checkAccountIsEasAccount("", false);
        checkAccountIsEasAccount("x", false);
        checkAccountIsEasAccount("eas", true);
    }

    private void checkAccountIsEasAccount(String protocol, boolean expected) {
        Account account = ProviderTestUtils.setupAccount("account", false, mMockContext);
        account.mHostAuthRecv = ProviderTestUtils.setupHostAuth(protocol, "account-hostauth-recv",
                account.mId, false, mMockContext);
        account.save(mMockContext);
        assertEquals(expected, account.isEasAccount(mMockContext));
    }

    public void testGetKeyColumnLong() {
        final Context c = mMockContext;
        Account a = ProviderTestUtils.setupAccount("acct", true, c);
        Mailbox b1 = ProviderTestUtils.setupMailbox("box1", a.mId, true, c, Mailbox.TYPE_MAIL);
        Mailbox b2 = ProviderTestUtils.setupMailbox("box2", a.mId, true, c, Mailbox.TYPE_MAIL);
        Message m1 = createMessage(c, b1, false, false);
        Message m2 = createMessage(c, b2, false, false);
        assertEquals(a.mId, Message.getKeyColumnLong(c, m1.mId, MessageColumns.ACCOUNT_KEY));
        assertEquals(a.mId, Message.getKeyColumnLong(c, m2.mId, MessageColumns.ACCOUNT_KEY));
        assertEquals(b1.mId, Message.getKeyColumnLong(c, m1.mId, MessageColumns.MAILBOX_KEY));
        assertEquals(b2.mId, Message.getKeyColumnLong(c, m2.mId, MessageColumns.MAILBOX_KEY));
    }

    public void testGetAccountIdForMessageId() {
        final Context c = mMockContext;
        Account a1 = ProviderTestUtils.setupAccount("acct1", true, c);
        Account a2 = ProviderTestUtils.setupAccount("acct2", true, c);
        Mailbox b1 = ProviderTestUtils.setupMailbox("box1", a1.mId, true, c, Mailbox.TYPE_MAIL);
        Mailbox b2 = ProviderTestUtils.setupMailbox("box2", a2.mId, true, c, Mailbox.TYPE_MAIL);
        Message m1 = createMessage(c, b1, false, false);
        Message m2 = createMessage(c, b2, false, false);

        assertEquals(a1.mId, Account.getAccountIdForMessageId(c, m1.mId));
        assertEquals(a2.mId, Account.getAccountIdForMessageId(c, m2.mId));

        // message desn't exist
        assertEquals(-1, Account.getAccountIdForMessageId(c, 12345));
    }

    public void testGetAccountMailboxFromMessageId() {
        final Context c = mMockContext;
        Account a = ProviderTestUtils.setupAccount("acct", true, c);
        Mailbox b1 = ProviderTestUtils.setupMailbox("box1", a.mId, true, c, Mailbox.TYPE_MAIL);
        Mailbox b2 = ProviderTestUtils.setupMailbox("box2", a.mId, true, c, Mailbox.TYPE_MAIL);
        Message m1 = createMessage(c, b1, false, false);
        Message m2 = createMessage(c, b2, false, false);
        ProviderTestUtils.assertAccountEqual("x", a, Account.getAccountForMessageId(c, m1.mId));
        ProviderTestUtils.assertAccountEqual("x", a, Account.getAccountForMessageId(c, m2.mId));
        // Restore the mailboxes, since the unread & total counts will have changed
        b1 = Mailbox.restoreMailboxWithId(c, b1.mId);
        b2 = Mailbox.restoreMailboxWithId(c, b2.mId);
        ProviderTestUtils.assertMailboxEqual("x", b1, Mailbox.getMailboxForMessageId(c, m1.mId));
        ProviderTestUtils.assertMailboxEqual("x", b2, Mailbox.getMailboxForMessageId(c, m2.mId));
    }

    public void testGetAccountGetInboxIdTest() {
        final Context c = mMockContext;

        // Prepare some data with red-herrings.
        Account a1 = ProviderTestUtils.setupAccount("acct1", true, c);
        Account a2 = ProviderTestUtils.setupAccount("acct2", true, c);
        Mailbox b1i = ProviderTestUtils.setupMailbox("b1i", a1.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox b2a = ProviderTestUtils.setupMailbox("b2a", a2.mId, true, c, Mailbox.TYPE_MAIL);
        Mailbox b2i = ProviderTestUtils.setupMailbox("b2b", a2.mId, true, c, Mailbox.TYPE_INBOX);

        assertEquals(b2i.mId, Account.getInboxId(c, a2.mId));

        // No account found.
        assertEquals(-1, Account.getInboxId(c, 999999));
    }

    public void testGetMailboxType() {
        final Context c = mMockContext;

        Account a = ProviderTestUtils.setupAccount("acct1", true, c);
        Mailbox bi = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox bm = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_MAIL);

        assertEquals(Mailbox.TYPE_INBOX, Mailbox.getMailboxType(c, bi.mId));
        assertEquals(Mailbox.TYPE_MAIL, Mailbox.getMailboxType(c, bm.mId));
        assertEquals(-1, Mailbox.getMailboxType(c, 999999)); // mailbox not found
    }

    public void testGetDisplayName() {
        final Context c = mMockContext;

        Account a = ProviderTestUtils.setupAccount("acct1", true, c);
        Mailbox bi = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox bm = ProviderTestUtils.setupMailbox("b2", a.mId, true, c, Mailbox.TYPE_MAIL);

        assertEquals("b1", Mailbox.getDisplayName(c, bi.mId));
        assertEquals("b2", Mailbox.getDisplayName(c, bm.mId));
        assertEquals(null, Mailbox.getDisplayName(c, 999999)); // mailbox not found
    }

    public void testMailboxIsRefreshable() {
        final Context c = mMockContext;

        Account a = ProviderTestUtils.setupAccount("acct1", true, c);
        Mailbox bi = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox bm = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_MAIL);
        Mailbox bd = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_DRAFTS);
        Mailbox bo = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_OUTBOX);

        assertTrue(Mailbox.isRefreshable(c, bi.mId));
        assertTrue(Mailbox.isRefreshable(c, bm.mId));
        assertFalse(Mailbox.isRefreshable(c, bd.mId));
        assertFalse(Mailbox.isRefreshable(c, bo.mId));

        // No such mailbox
        assertFalse(Mailbox.isRefreshable(c, 9999999));

        // Magic mailboxes can't be refreshed.
        assertFalse(Mailbox.isRefreshable(c, Mailbox.QUERY_ALL_DRAFTS));
        assertFalse(Mailbox.isRefreshable(c, Mailbox.QUERY_ALL_INBOXES));
    }

    public void testMailboxCanMoveFrom() {
        final Context c = mMockContext;

        Account a = ProviderTestUtils.setupAccount("acct1", true, c);
        Mailbox bi = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox bm = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_MAIL);
        Mailbox bd = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_DRAFTS);
        Mailbox bo = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_OUTBOX);

        assertTrue(Mailbox.canMoveFrom(c, bi.mId));
        assertTrue(Mailbox.canMoveFrom(c, bm.mId));
        assertFalse(Mailbox.canMoveFrom(c, bd.mId));
        assertFalse(Mailbox.canMoveFrom(c, bo.mId));
    }

    /**
     * Check if update to {@link Account#RESET_NEW_MESSAGE_COUNT_URI} resets the new message count.
     */
    public void testResetNewMessageCount() {
        final Context c = mMockContext;
        final ContentResolver cr = c.getContentResolver();

        // Prepare test data
        Account a1 = ProviderTestUtils.setupAccount("acct1", false, c);
        a1.mNewMessageCount = 1;
        a1.save(c);
        Account a2 = ProviderTestUtils.setupAccount("acct2", false, c);
        a2.mNewMessageCount = 2;
        a2.save(c);
        Account a3 = ProviderTestUtils.setupAccount("acct3", false, c);
        a3.mNewMessageCount = 3;
        a3.save(c);
        Account a4 = ProviderTestUtils.setupAccount("acct4", false, c);
        a4.mNewMessageCount = 4;
        a4.save(c);
        Account a5 = ProviderTestUtils.setupAccount("acct5", false, c);
        a5.mNewMessageCount = 5;
        a5.save(c);

        // With ID in URI, no selection
        cr.update(ContentUris.withAppendedId(Account.RESET_NEW_MESSAGE_COUNT_URI, a1.mId),
                null, null, null);
        assertEquals(0, Account.restoreAccountWithId(c, a1.mId).mNewMessageCount);
        assertEquals(2, Account.restoreAccountWithId(c, a2.mId).mNewMessageCount);
        assertEquals(3, Account.restoreAccountWithId(c, a3.mId).mNewMessageCount);
        assertEquals(4, Account.restoreAccountWithId(c, a4.mId).mNewMessageCount);
        assertEquals(5, Account.restoreAccountWithId(c, a5.mId).mNewMessageCount);

        // No ID in URI, with selection
        cr.update(Account.RESET_NEW_MESSAGE_COUNT_URI, null,
                EmailContent.ID_SELECTION, new String[] {Long.toString(a2.mId)});
        assertEquals(0, Account.restoreAccountWithId(c, a1.mId).mNewMessageCount);
        assertEquals(0, Account.restoreAccountWithId(c, a2.mId).mNewMessageCount);
        assertEquals(3, Account.restoreAccountWithId(c, a3.mId).mNewMessageCount);
        assertEquals(4, Account.restoreAccountWithId(c, a4.mId).mNewMessageCount);
        assertEquals(5, Account.restoreAccountWithId(c, a5.mId).mNewMessageCount);

        // With ID, with selection
        cr.update(ContentUris.withAppendedId(Account.RESET_NEW_MESSAGE_COUNT_URI, a3.mId), null,
                EmailContent.ID_SELECTION, new String[] {Long.toString(a3.mId)});
        assertEquals(0, Account.restoreAccountWithId(c, a1.mId).mNewMessageCount);
        assertEquals(0, Account.restoreAccountWithId(c, a2.mId).mNewMessageCount);
        assertEquals(0, Account.restoreAccountWithId(c, a3.mId).mNewMessageCount);
        assertEquals(4, Account.restoreAccountWithId(c, a4.mId).mNewMessageCount);
        assertEquals(5, Account.restoreAccountWithId(c, a5.mId).mNewMessageCount);

        // No ID in URI, no selection
        cr.update(Account.RESET_NEW_MESSAGE_COUNT_URI, null, null, null);
        assertEquals(0, Account.restoreAccountWithId(c, a1.mId).mNewMessageCount);
        assertEquals(0, Account.restoreAccountWithId(c, a2.mId).mNewMessageCount);
        assertEquals(0, Account.restoreAccountWithId(c, a3.mId).mNewMessageCount);
        assertEquals(0, Account.restoreAccountWithId(c, a4.mId).mNewMessageCount);
        assertEquals(0, Account.restoreAccountWithId(c, a5.mId).mNewMessageCount);
    }

    private static Message createMessageWithTimestamp(Context c, Mailbox b, long timestamp) {
        Message m = ProviderTestUtils.setupMessage("1", b.mAccountKey, b.mId, true, false, c, false,
                false);
        m.mTimeStamp = timestamp;
        m.save(c);
        return m;
    }

    public void testMessageGetLatestIncomingMessage() {
        final Context c = mMockContext;

        // Create 2 accounts with a inbox.
        Account a1 = ProviderTestUtils.setupAccount("a1", true, c);
        Account a2 = ProviderTestUtils.setupAccount("a2", true, c);

        Mailbox b1 = ProviderTestUtils.setupMailbox("box1", a1.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox b1d = ProviderTestUtils.setupMailbox("box1d", a1.mId, true, c, Mailbox.TYPE_DRAFTS);
        Mailbox b1o = ProviderTestUtils.setupMailbox("box1o", a1.mId, true, c, Mailbox.TYPE_OUTBOX);
        Mailbox b1s = ProviderTestUtils.setupMailbox("box1s", a1.mId, true, c, Mailbox.TYPE_SENT);
        Mailbox b2 = ProviderTestUtils.setupMailbox("box2", a2.mId, true, c, Mailbox.TYPE_MAIL);

        // Create some messages
        Message m11 = createMessageWithTimestamp(c, b1, 33);
        Message m12 = createMessageWithTimestamp(c, b1, 10);
        Message m13 = createMessageWithTimestamp(c, b1, 1000); // latest incoming
        Message m1d = createMessageWithTimestamp(c, b1d, 2000);
        Message m1o = createMessageWithTimestamp(c, b1o, 2000);
        Message m1s = createMessageWithTimestamp(c, b1s, 2000);

        Message m21 = createMessageWithTimestamp(c, b2, 99); // latest incoming
        Message m22 = createMessageWithTimestamp(c, b2, 1);
        Message m23 = createMessageWithTimestamp(c, b2, 2);

        // Check!
        assertEquals(m13.mId, Message.getLatestIncomingMessage(c, a1.mId).mId);
        assertEquals(m21.mId, Message.getLatestIncomingMessage(c, a2.mId).mId);

        // No such account
        assertEquals(null, Message.getLatestIncomingMessage(c, 9999999L));
    }

    /**
     * Check if update on ACCOUNT_ID_ADD_TO_FIELD updates the cache properly.
     */
    public void testUpdateCacheAccountIdAddToField() {
        final Context c = mMockContext;
        Account a1 = ProviderTestUtils.setupAccount("a1", true, c);

        int start = Account.restoreAccountWithId(c, a1.mId).mNewMessageCount;

        // +1 to NEW_MESSAGE_COUNT
        ContentValues cv = new ContentValues();
        cv.put(EmailContent.FIELD_COLUMN_NAME, AccountColumns.NEW_MESSAGE_COUNT);
        cv.put(EmailContent.ADD_COLUMN_NAME, 1);
        mProvider.update(ContentUris.withAppendedId(Account.ADD_TO_FIELD_URI, a1.mId), cv,
                null, null);

        // Check
        assertEquals(start + 1, Account.restoreAccountWithId(c, a1.mId).mNewMessageCount);
    }

    /**
     * Check if update on ACCOUNT_RESET_NEW_COUNT updates the cache properly.
     */
    public void testUpdateCacheAccountResetNewCount() {
        final Context c = mMockContext;
        Account a1 = ProviderTestUtils.setupAccount("a1", true, c);

        // precondition
        assertTrue(Account.restoreAccountWithId(c, a1.mId).mNewMessageCount > 0);

        // Reset
        mProvider.update(Account.RESET_NEW_MESSAGE_COUNT_URI, null, null, null);

        // Check
        assertEquals(0, Account.restoreAccountWithId(c, a1.mId).mNewMessageCount);
    }

    /**
     * Check if update on ACCOUNT_RESET_NEW_COUNT_ID updates the cache properly.
     */
    public void testUpdateCacheAccountResetNewCountId() {
        final Context c = mMockContext;
        Account a1 = ProviderTestUtils.setupAccount("a1", true, c);

        // precondition
        assertTrue(Account.restoreAccountWithId(c, a1.mId).mNewMessageCount > 0);

        // Reset
        mProvider.update(ContentUris.withAppendedId(Account.RESET_NEW_MESSAGE_COUNT_URI, a1.mId),
                null, null, null);

        // Check
        assertEquals(0, Account.restoreAccountWithId(c, a1.mId).mNewMessageCount);
    }

    /**
     * Check if update on MAILBOX_ID_ADD_TO_FIELD updates the cache properly.
     */
    public void testUpdateCacheMailboxIdAddToField() {
        final Context c = mMockContext;
        Account a1 = ProviderTestUtils.setupAccount("a1", true, c);
        Mailbox b1 = ProviderTestUtils.setupMailbox("box1", a1.mId, true, c, Mailbox.TYPE_INBOX);

        int start = Mailbox.restoreMailboxWithId(c, b1.mId).mSyncInterval;

        // +1 to SYNC_INTERVAL
        ContentValues cv = new ContentValues();
        cv.put(EmailContent.FIELD_COLUMN_NAME, MailboxColumns.SYNC_INTERVAL);
        cv.put(EmailContent.ADD_COLUMN_NAME, 1);
        mProvider.update(ContentUris.withAppendedId(Mailbox.ADD_TO_FIELD_URI, a1.mId), cv,
                null, null);

        // Check
        assertEquals(start + 1, Mailbox.restoreMailboxWithId(c, b1.mId).mSyncInterval);
    }
}
