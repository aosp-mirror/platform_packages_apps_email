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

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.test.ProviderTestCase2;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.Suppress;

import com.android.email.AttachmentInfo;
import com.android.email.R;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.AttachmentUtilities;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Tests of the Email Attachments provider.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.provider.AttachmentProviderTests email
 */
@Suppress
public class AttachmentProviderTests extends ProviderTestCase2<AttachmentProvider> {

    EmailProvider mEmailProvider;
    Context mMockContext;
    ContentResolver mMockResolver;

    public AttachmentProviderTests() {
        super(AttachmentProvider.class, Attachment.ATTACHMENT_PROVIDER_LEGACY_URI_PREFIX);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        mMockResolver = mMockContext.getContentResolver();

        // Spin up an Email provider as well and put it under the same mock test framework
        mEmailProvider = new EmailProvider();
        mEmailProvider.attachInfo(mMockContext, null);
        assertNotNull(mEmailProvider);
        ((MockContentResolver) mMockResolver)
                .addProvider(EmailContent.AUTHORITY, mEmailProvider);
    }

    /**
     * test query()
     *  - item found
     *  - item not found
     *  - permuted projection
     */
    public void testQuery() throws MessagingException {
        Account account1 = ProviderTestUtils.setupAccount("attachment-query", false, mMockContext);
        account1.save(mMockContext);
        final long message1Id = 1;
        long attachment1Id = 1;
        long attachment2Id = 2;
        long attachment3Id = 3;

        // Note:  There is an implicit assumption in this test sequence that the first
        // attachment we add will be id=1 and the 2nd will have id=2.  This could fail on
        // a legitimate implementation.  Asserts below will catch this and fail the test
        // if necessary.
        Uri attachment1Uri = AttachmentUtilities.getAttachmentUri(account1.mId,
                attachment1Id);
        Uri attachment2Uri = AttachmentUtilities.getAttachmentUri(account1.mId,
                attachment2Id);
        Uri attachment3Uri = AttachmentUtilities.getAttachmentUri(account1.mId,
                attachment3Id);

        // Test with no attachment found - should return null
        Cursor c = mMockResolver.query(attachment1Uri, (String[])null, null, (String[])null, null);
        assertNull(c);

        // Add a couple of attachment entries.  Note, query() just uses the DB, and does not
        // sample the files, so we won't bother creating the files
        Attachment newAttachment1 = ProviderTestUtils.setupAttachment(message1Id, "file1", 100,
                false, mMockContext);
        newAttachment1.setContentUri(
            AttachmentUtilities.getAttachmentUri(account1.mId, attachment1Id).toString());
        attachment1Id = addAttachmentToDb(account1, newAttachment1);
        assertEquals("Broken test:  Unexpected id assignment", 1, attachment1Id);

        Attachment newAttachment2 = ProviderTestUtils.setupAttachment(message1Id, "file2", 200,
                false, mMockContext);
        newAttachment2.setContentUri(
            AttachmentUtilities.getAttachmentUri(account1.mId, attachment2Id).toString());
        attachment2Id = addAttachmentToDb(account1, newAttachment2);
        assertEquals("Broken test:  Unexpected id assignment", 2, attachment2Id);

        Attachment newAttachment3 = ProviderTestUtils.setupAttachment(message1Id, "file3", 300,
                false, mMockContext);
        newAttachment3.setContentUri(
            AttachmentUtilities.getAttachmentUri(account1.mId, attachment3Id).toString());
        attachment3Id = addAttachmentToDb(account1, newAttachment3);
        assertEquals("Broken test:  Unexpected id assignment", 3, attachment3Id);

        // Return a row with all columns specified
        attachment2Uri = AttachmentUtilities.getAttachmentUri(account1.mId, attachment2Id);
        c = mMockResolver.query(
                attachment2Uri,
                new String[] { AttachmentUtilities.Columns._ID,
                               AttachmentUtilities.Columns.DATA,
                               AttachmentUtilities.Columns.DISPLAY_NAME,
                               AttachmentUtilities.Columns.SIZE },
                null, null, null);
        assertEquals(1, c.getCount());
        assertTrue(c.moveToFirst());
        assertEquals(attachment2Id, c.getLong(0));                  // id
        assertEquals(attachment2Uri.toString(), c.getString(1));    // content URI
        assertEquals("file2", c.getString(2));                      // display name
        assertEquals(200, c.getInt(3));                             // size

        // Return a row with permuted columns
        attachment3Uri = AttachmentUtilities.getAttachmentUri(account1.mId, attachment3Id);
        c = mMockResolver.query(
                attachment3Uri,
                new String[] { AttachmentUtilities.Columns.SIZE,
                               AttachmentUtilities.Columns.DISPLAY_NAME,
                               AttachmentUtilities.Columns.DATA,
                               AttachmentUtilities.Columns._ID },
                null, null, null);
        assertEquals(1, c.getCount());
        assertTrue(c.moveToFirst());
        assertEquals(attachment3Id, c.getLong(3));                  // id
        assertEquals(attachment3Uri.toString(), c.getString(2));    // content URI
        assertEquals("file3", c.getString(1));                      // display name
        assertEquals(300, c.getInt(0));                             // size
    }

    private static Message createMessage(Context c, Mailbox b) {
        Message m = ProviderTestUtils.setupMessage("1", b.mAccountKey, b.mId, true, false, c, false,
                false);
        m.mFlagLoaded = Message.FLAG_LOADED_COMPLETE;
        m.save(c);
        return m;
    }

    public void testInboxQuery() {
        // Create 2 accounts
        Account a1 = ProviderTestUtils.setupAccount("inboxquery-1", true, mMockContext);
        Account a2 = ProviderTestUtils.setupAccount("inboxquery-2", true, mMockContext);

        // Create mailboxes for each account
        Mailbox b1 = ProviderTestUtils.setupMailbox(
                "box1", a1.mId, true, mMockContext, Mailbox.TYPE_INBOX);
        Mailbox b2 = ProviderTestUtils.setupMailbox(
                "box2", a1.mId, true, mMockContext, Mailbox.TYPE_MAIL);
        Mailbox b3 = ProviderTestUtils.setupMailbox(
                "box3", a2.mId, true, mMockContext, Mailbox.TYPE_INBOX);
        Mailbox b4 = ProviderTestUtils.setupMailbox(
                "box4", a2.mId, true, mMockContext, Mailbox.TYPE_MAIL);
        Mailbox bt = ProviderTestUtils.setupMailbox(
                "boxT", a2.mId, true, mMockContext, Mailbox.TYPE_TRASH);

        // Create some messages
        // b1 (account 1, inbox): 2 messages
        Message m11 = createMessage(mMockContext, b1);
        Message m12 = createMessage(mMockContext, b1);

        // b2 (account 1, mail): 2 messages
        Message m21 = createMessage(mMockContext, b2);
        Message m22 = createMessage(mMockContext, b2);

        // b3 (account 2, inbox): 1 message
        Message m31 = createMessage(mMockContext, b3);

        // b4 (account 2, mail) has no messages.

        // bt (account 2, trash): 1 message
        Message mt1 = createMessage(mMockContext, bt);

        // 4 attachments in the inbox, 2 different messages, 1 downloaded
        createAttachment(a1, m11.mId, null);
        createAttachment(a1, m11.mId, null);
        createAttachment(a1, m12.mId, null);
        createAttachment(a1, m12.mId, "file:///path/to/file1");

        // 3 attachments in generic mailbox, 2 different messages, 1 downloaded
        createAttachment(a1, m21.mId, null);
        createAttachment(a1, m21.mId, null);
        createAttachment(a1, m22.mId, null);
        createAttachment(a1, m22.mId, "file:///path/to/file2");

        // 1 attachment in inbox
        createAttachment(a2, m31.mId, null);

        // 2 attachments in trash, same message
        createAttachment(a2, mt1.mId, null);
        createAttachment(a2, mt1.mId, null);

        Cursor c = null;
        try {
            // count all attachments with an empty URI, regardless of mailbox location
            c = mMockContext.getContentResolver().query(
                    Attachment.CONTENT_URI, AttachmentInfo.PROJECTION,
                    EmailContent.Attachment.PRECACHE_SELECTION,
                    null, Attachment.RECORD_ID + " DESC");
            assertEquals(9, c.getCount());
        } finally {
            c.close();
        }

        try {
            // count all attachments with an empty URI, only in an inbox
            c = mMockContext.getContentResolver().query(
                    Attachment.CONTENT_URI, AttachmentInfo.PROJECTION,
                    EmailContent.Attachment.PRECACHE_INBOX_SELECTION,
                    null, Attachment.RECORD_ID + " DESC");
            assertEquals(4, c.getCount());
        } finally {
            c.close();
        }
    }

    /**
     * test getType()
     *  - regular file
     *  - thumbnail
     */
    public void testGetType() throws MessagingException {
        Account account1 = ProviderTestUtils.setupAccount("get-type", false, mMockContext);
        account1.save(mMockContext);
        final long message1Id = 1;
        long attachment1Id = 1;
        long attachment2Id = 2;
        long attachment3Id = 3;
        long attachment4Id = 4;
        long attachment5Id = 5;
        long attachment6Id = 6;

        Uri attachment1Uri = AttachmentUtilities.getAttachmentUri(account1.mId,
                attachment1Id);

        // Test with no attachment found - should return null
        String type = mMockResolver.getType(attachment1Uri);
        assertNull(type);

        // Add a couple of attachment entries.  Note, getType() just uses the DB, and does not
        // sample the files, so we won't bother creating the files
        Attachment newAttachment2 = ProviderTestUtils.setupAttachment(message1Id, "file2", 100,
                false, mMockContext);
        newAttachment2.mMimeType = "image/jpg";
        attachment2Id = addAttachmentToDb(account1, newAttachment2);

        Attachment newAttachment3 = ProviderTestUtils.setupAttachment(message1Id, "file3", 100,
                false, mMockContext);
        newAttachment3.mMimeType = "text/plain";
        attachment3Id = addAttachmentToDb(account1, newAttachment3);

        Attachment newAttachment4 = ProviderTestUtils.setupAttachment(message1Id, "file4.doc", 100,
                false, mMockContext);
        newAttachment4.mMimeType = "application/octet-stream";
        attachment4Id = addAttachmentToDb(account1, newAttachment4);

        Attachment newAttachment5 = ProviderTestUtils.setupAttachment(message1Id, "file5.xyz", 100,
                false, mMockContext);
        newAttachment5.mMimeType = "application/octet-stream";
        attachment5Id = addAttachmentToDb(account1, newAttachment5);

        Attachment newAttachment6 = ProviderTestUtils.setupAttachment(message1Id, "file6", 100,
                false, mMockContext);
        newAttachment6.mMimeType = "";
        attachment6Id = addAttachmentToDb(account1, newAttachment6);

        // Check the returned filetypes
        Uri uri = AttachmentUtilities.getAttachmentUri(account1.mId, attachment2Id);
        type = mMockResolver.getType(uri);
        assertEquals("image/jpg", type);
        uri = AttachmentUtilities.getAttachmentUri(account1.mId, attachment3Id);
        type = mMockResolver.getType(uri);
        assertEquals("text/plain", type);
        uri = AttachmentUtilities.getAttachmentUri(account1.mId, attachment4Id);
        type = mMockResolver.getType(uri);
        assertEquals("application/msword", type);
        uri = AttachmentUtilities.getAttachmentUri(account1.mId, attachment5Id);
        type = mMockResolver.getType(uri);
        assertEquals("application/xyz", type);
        uri = AttachmentUtilities.getAttachmentUri(account1.mId, attachment6Id);
        type = mMockResolver.getType(uri);
        assertEquals("application/octet-stream", type);

        // Check the returned filetypes for the thumbnails
        uri = AttachmentUtilities.getAttachmentThumbnailUri(account1.mId, attachment2Id, 62,
                62);
        type = mMockResolver.getType(uri);
        assertEquals("image/png", type);
        uri = AttachmentUtilities.getAttachmentThumbnailUri(account1.mId, attachment3Id, 62,
                62);
        type = mMockResolver.getType(uri);
        assertEquals("image/png", type);
    }

    /**
     * test openFile()
     *  - regular file
     *  - TODO: variations on the content URI
     */
    public void testOpenFile() throws MessagingException, IOException {
        Account account1 = ProviderTestUtils.setupAccount("open-file", false, mMockContext);
        account1.save(mMockContext);
        final long message1Id = 1;
        long attachment1Id = 1;
        long attachment2Id = 2;

        // Note:  There is an implicit assumption in this test sequence that the first
        // attachment we add will be id=1 and the 2nd will have id=2.  This could fail on
        // a legitimate implementation.  Asserts below will catch this and fail the test
        // if necessary.
        Uri file1Uri = AttachmentUtilities.getAttachmentUri(account1.mId, attachment1Id);
        Uri file2Uri = AttachmentUtilities.getAttachmentUri(account1.mId, attachment2Id);

        // Test with no attachment found
        AssetFileDescriptor afd;
        try {
            afd = mMockResolver.openAssetFileDescriptor(file1Uri, "r");
            fail("Should throw an exception on a missing attachment entry");
        } catch (FileNotFoundException fnf) {
            // expected
        }

        // Add an attachment (but no associated file)
        Attachment newAttachment = ProviderTestUtils.setupAttachment(message1Id, "file", 100,
                false, mMockContext);
        attachment1Id = addAttachmentToDb(account1, newAttachment);
        assertEquals("Broken test:  Unexpected id assignment", 1, attachment1Id);

        // Test with an attached database, attachment entry found, but no attachment found
        try {
            afd = mMockResolver.openAssetFileDescriptor(file1Uri, "r");
            fail("Should throw an exception on a missing attachment file");
        } catch (FileNotFoundException fnf) {
            // expected
        }

        // Create an "attachment" by copying an image resource into a file
        /* String fileName = */ createAttachmentFile(account1, attachment2Id);
        Attachment newAttachment2 = ProviderTestUtils.setupAttachment(message1Id, "file", 100,
                false, mMockContext);
        newAttachment2.mContentId = null;
        newAttachment2.setContentUri(
                AttachmentUtilities.getAttachmentUri(account1.mId, attachment2Id)
                .toString());
        newAttachment2.mMimeType = "image/png";
        attachment2Id = addAttachmentToDb(account1, newAttachment2);
        assertEquals("Broken test:  Unexpected id assignment", 2, attachment2Id);

        // Test with an attached database, attachment entry found - returns a file
        afd = mMockResolver.openAssetFileDescriptor(file2Uri, "r");
        assertNotNull(afd);
        // TODO: Confirm it's the "right" file?
        afd.close();
    }

    /**
     * test openFile()
     *  - thumbnail
     * @throws IOException
     *
     * TODO:  The thumbnail mode returns null for its failure cases (and in one case, throws
     * an SQLiteException).  The ContentResolver contract requires throwing FileNotFoundException
     * in all of the non-success cases, and the provider should be fixed for consistency.
     */
    public void testOpenThumbnail() throws MessagingException, IOException {
        Account account1 = ProviderTestUtils.setupAccount("open-thumbnail", false, mMockContext);
        account1.save(mMockContext);
        final long message1Id = 1;
        long attachment1Id = 1;
        long attachment2Id = 2;

        // Note:  There is an implicit assumption in this test sequence that the first
        // attachment we add will be id=1 and the 2nd will have id=2.  This could fail on
        // a legitimate implementation.  Asserts below will catch this and fail the test
        // if necessary.
        Uri thumb1Uri = AttachmentUtilities.getAttachmentThumbnailUri(account1.mId,
                attachment1Id, 62, 62);
        Uri thumb2Uri = AttachmentUtilities.getAttachmentThumbnailUri(account1.mId,
                attachment2Id, 62, 62);

        // Test with an attached database, but no attachment found
        AssetFileDescriptor afd = mMockResolver.openAssetFileDescriptor(thumb1Uri, "r");
        assertNull(afd);

        // Add an attachment (but no associated file)
        Attachment newAttachment = ProviderTestUtils.setupAttachment(message1Id, "file", 100,
                false, mMockContext);
        attachment1Id = addAttachmentToDb(account1, newAttachment);
        assertEquals("Broken test:  Unexpected id assignment", 1, attachment1Id);

        // Test with an attached database, attachment entry found, but no attachment found
        afd = mMockResolver.openAssetFileDescriptor(thumb1Uri, "r");
        assertNull(afd);

        // Create an "attachment" by copying an image resource into a file
        /* String fileName = */ createAttachmentFile(account1, attachment2Id);
        Attachment newAttachment2 = ProviderTestUtils.setupAttachment(message1Id, "file", 100,
                false, mMockContext);
        newAttachment2.mContentId = null;
        newAttachment2.setContentUri(
                AttachmentUtilities.getAttachmentUri(account1.mId, attachment2Id)
                .toString());
        newAttachment2.mMimeType = "image/png";
        attachment2Id = addAttachmentToDb(account1, newAttachment2);
        assertEquals("Broken test:  Unexpected id assignment", 2, attachment2Id);

        // Test with an attached database, attachment entry found - returns a thumbnail
        afd = mMockResolver.openAssetFileDescriptor(thumb2Uri, "r");
        assertNotNull(afd);
        // TODO: Confirm it's the "right" file?
        afd.close();
    }

    private Uri createAttachment(Account account, long messageId, String contentUriStr) {
        // Add an attachment entry.
        Attachment newAttachment = ProviderTestUtils.setupAttachment(messageId, "file", 100,
                false, mMockContext);
        newAttachment.setContentUri(contentUriStr);
        long attachmentId = addAttachmentToDb(account, newAttachment);
        Uri attachmentUri = AttachmentUtilities.getAttachmentUri(account.mId, attachmentId);
        return attachmentUri;
    }

    /**
     * test resolveAttachmentIdToContentUri()
     *  - without DB
     *  - not in DB
     *  - in DB, with not-null contentUri
     *  - in DB, with null contentUri
     */
    public void testResolveAttachmentIdToContentUri() throws MessagingException {
        Account account1 = ProviderTestUtils.setupAccount("attachment-query", false, mMockContext);
        account1.save(mMockContext);
        final long message1Id = 1;
        // We use attachmentId == 1 but any other id would do
        final long attachment1Id = 1;
        final Uri attachment1Uri = AttachmentUtilities.getAttachmentUri(account1.mId,
                attachment1Id);

        // Test with no attachment found - should return input
        // We know that the attachmentId 1 does not exist because there are no attachments
        // created at this point
        Uri result = AttachmentUtilities.resolveAttachmentIdToContentUri(
                mMockResolver, attachment1Uri);
        assertEquals(attachment1Uri, result);

        // Test with existing attachement and contentUri != null
        // Note, resolveAttachmentIdToContentUri() just uses
        // the DB, and does not sample the files, so we won't bother creating the files
        {
            Uri attachmentUri = createAttachment(account1, message1Id, "file:///path/to/file");
            Uri contentUri = AttachmentUtilities.resolveAttachmentIdToContentUri(
                    mMockResolver, attachmentUri);
            // When the attachment is found, return the stored content_uri value
            assertEquals("file:///path/to/file", contentUri.toString());
        }

        // Test with existing attachement and contentUri == null
        {
            Uri attachmentUri = createAttachment(account1, message1Id, null);
            Uri contentUri = AttachmentUtilities.resolveAttachmentIdToContentUri(
                    mMockResolver, attachmentUri);
            // When contentUri is null should return input
            assertEquals(attachmentUri, contentUri);
        }
    }

    /**
     * Test the functionality of deleting all attachment files for a given message.
     */
    public void testDeleteFiles() throws IOException {
        Account account1 = ProviderTestUtils.setupAccount("attachment-query", false, mMockContext);
        account1.save(mMockContext);
        final long message1Id = 1;      // 1 attachment, 1 file
        final long message2Id = 2;      // 2 attachments, 2 files
        final long message3Id = 3;      // 1 attachment, missing file
        final long message4Id = 4;      // no attachments

        // Add attachment entries for various test messages
        Attachment newAttachment1 = ProviderTestUtils.setupAttachment(message1Id, "file1", 100,
                true, mMockContext);
        Attachment newAttachment2 = ProviderTestUtils.setupAttachment(message2Id, "file2", 200,
                true, mMockContext);
        Attachment newAttachment3 = ProviderTestUtils.setupAttachment(message2Id, "file3", 100,
                true, mMockContext);
        Attachment newAttachment4 = ProviderTestUtils.setupAttachment(message3Id, "file4", 100,
                true, mMockContext);

        // Create test files
        createAttachmentFile(account1, newAttachment1.mId);
        createAttachmentFile(account1, newAttachment2.mId);
        createAttachmentFile(account1, newAttachment3.mId);

        // Confirm 3 attachment files found
        File attachmentsDir = AttachmentUtilities.getAttachmentDirectory(mMockContext,
                account1.mId);
        assertEquals(3, attachmentsDir.listFiles().length);

        // Command deletion of some files and check for results

        // Message 4 has no attachments so no files should be deleted
        AttachmentUtilities.deleteAllAttachmentFiles(mMockContext, account1.mId,
                message4Id);
        assertEquals(3, attachmentsDir.listFiles().length);

        // Message 3 has no attachment files so no files should be deleted
        AttachmentUtilities.deleteAllAttachmentFiles(mMockContext, account1.mId,
                message3Id);
        assertEquals(3, attachmentsDir.listFiles().length);

        // Message 2 has 2 attachment files so this should delete 2 files
        AttachmentUtilities.deleteAllAttachmentFiles(mMockContext, account1.mId,
                message2Id);
        assertEquals(1, attachmentsDir.listFiles().length);

        // Message 1 has 1 attachment file so this should delete the last file
        AttachmentUtilities.deleteAllAttachmentFiles(mMockContext, account1.mId,
                message1Id);
        assertEquals(0, attachmentsDir.listFiles().length);
    }

    /**
     * Test the functionality of deleting an entire mailbox's attachments.
     */
    public void testDeleteMailbox() throws IOException {
        Account account1 = ProviderTestUtils.setupAccount("attach-mbox-del", false, mMockContext);
        account1.save(mMockContext);
        long account1Id = account1.mId;
        Mailbox mailbox1 = ProviderTestUtils.setupMailbox("mbox1", account1Id, true, mMockContext);
        long mailbox1Id = mailbox1.mId;
        Mailbox mailbox2 = ProviderTestUtils.setupMailbox("mbox2", account1Id, true, mMockContext);
        long mailbox2Id = mailbox2.mId;

        // Fill each mailbox with messages & attachments
        populateAccountMailbox(account1, mailbox1Id, 3);
        populateAccountMailbox(account1, mailbox2Id, 1);

        // Confirm four attachment files found
        File attachmentsDir = AttachmentUtilities.getAttachmentDirectory(mMockContext,
                account1.mId);
        assertEquals(4, attachmentsDir.listFiles().length);

        // Command the deletion of mailbox 1 - we should lose 3 attachment files
        AttachmentUtilities.deleteAllMailboxAttachmentFiles(mMockContext, account1Id,
                mailbox1Id);
        assertEquals(1, attachmentsDir.listFiles().length);

        // Command the deletion of mailbox 2 - we should lose 1 attachment file
        AttachmentUtilities.deleteAllMailboxAttachmentFiles(mMockContext, account1Id,
                mailbox2Id);
        assertEquals(0, attachmentsDir.listFiles().length);
    }

    /**
     * Test the functionality of deleting an entire account's attachments.
     */
    public void testDeleteAccount() throws IOException {
        Account account1 = ProviderTestUtils.setupAccount("attach-acct-del1", false, mMockContext);
        account1.save(mMockContext);
        long account1Id = account1.mId;
        Mailbox mailbox1 = ProviderTestUtils.setupMailbox("mbox1", account1Id, true, mMockContext);
        long mailbox1Id = mailbox1.mId;
        Mailbox mailbox2 = ProviderTestUtils.setupMailbox("mbox2", account1Id, true, mMockContext);
        long mailbox2Id = mailbox2.mId;

        // Repeat for account #2
        Account account2 = ProviderTestUtils.setupAccount("attach-acct-del2", false, mMockContext);
        account2.save(mMockContext);
        long account2Id = account2.mId;
        Mailbox mailbox3 = ProviderTestUtils.setupMailbox("mbox3", account2Id, true, mMockContext);
        long mailbox3Id = mailbox3.mId;
        Mailbox mailbox4 = ProviderTestUtils.setupMailbox("mbox4", account2Id, true, mMockContext);
        long mailbox4Id = mailbox4.mId;

        // Fill each mailbox with messages & attachments
        populateAccountMailbox(account1, mailbox1Id, 3);
        populateAccountMailbox(account1, mailbox2Id, 1);
        populateAccountMailbox(account2, mailbox3Id, 5);
        populateAccountMailbox(account2, mailbox4Id, 2);

        // Confirm eleven attachment files found
        File directory1 = AttachmentUtilities.getAttachmentDirectory(mMockContext,
                account1.mId);
        assertEquals(4, directory1.listFiles().length);
        File directory2 = AttachmentUtilities.getAttachmentDirectory(mMockContext,
                account2.mId);
        assertEquals(7, directory2.listFiles().length);

        // Command the deletion of account 1 - we should lose 4 attachment files
        AttachmentUtilities.deleteAllAccountAttachmentFiles(mMockContext, account1Id);
        assertEquals(0, directory1.listFiles().length);
        assertEquals(7, directory2.listFiles().length);

        // Command the deletion of account 2 - we should lose 7 attachment file
        AttachmentUtilities.deleteAllAccountAttachmentFiles(mMockContext, account2Id);
        assertEquals(0, directory1.listFiles().length);
        assertEquals(0, directory2.listFiles().length);
    }

    /**
     * Create a set of attachments for a given test account and mailbox.  Creates the following:
     *  Two messages per mailbox, one w/attachments, one w/o attachments
     *  Any number of attachments (on the first message)
     *  @param account the account to populate
     *  @param mailboxId the mailbox to populate
     *  @param numAttachments how many attachments to create
     */
    private void populateAccountMailbox(Account account, long mailboxId, int numAttachments)
            throws IOException {
        long accountId = account.mId;

        // two messages per mailbox, one w/attachments, one w/o attachments
        Message message1a = ProviderTestUtils.setupMessage(
                "msg1a", accountId, mailboxId, false, true, mMockContext);
        /* Message message1b = */ ProviderTestUtils.setupMessage(
                "msg1b", accountId, mailboxId, false, true, mMockContext);

        // Create attachment records & files
        for (int count = 0; count < numAttachments; count++) {
            Attachment newAttachment = ProviderTestUtils.setupAttachment(message1a.mId,
                    "file" + count, 100 * count, true, mMockContext);
            createAttachmentFile(account, newAttachment.mId);
        }
    }

    /**
     * Create an attachment by copying an image resource into a file.  Uses "real" resources
     * to get a real image from Email
     */
    private String createAttachmentFile(Account forAccount, long id) throws IOException {
        File outFile = getAttachmentFile(forAccount, id);
        Bitmap bitmap = BitmapFactory.decodeResource(getContext().getResources(),
                R.drawable.ic_attach_file_20dp);
        FileOutputStream out = new FileOutputStream(outFile);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();

        return outFile.getAbsolutePath();
    }

    /**
     * Record an attachment in the attachments database
     * @return the id of the attachment just created
     */
    private long addAttachmentToDb(Account forAccount, Attachment newAttachment) {
        newAttachment.save(mMockContext);
        return newAttachment.mId;
    }

    /**
     * Map from account, attachment ID to attachment file
     */
    private File getAttachmentFile(Account forAccount, long id) {
        String idString = Long.toString(id);
        File attachmentsDir = mMockContext.getDatabasePath(forAccount.mId + ".db_att");
        if (!attachmentsDir.exists()) {
            attachmentsDir.mkdirs();
        }
        return new File(attachmentsDir, idString);
    }
}
