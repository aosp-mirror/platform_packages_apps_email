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

import com.android.email.R;
import com.android.email.mail.MessagingException;
import com.android.email.mail.store.LocalStore;
import com.android.email.provider.AttachmentProvider.AttachmentProviderColumns;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.test.ProviderTestCase2;
import android.test.mock.MockContentResolver;

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
public class AttachmentProviderTests extends ProviderTestCase2<AttachmentProvider> {

    /*
     * This switch will enable us to transition these tests, and the AttachmentProvider, from the
     * "old" LocalStore model to the "new" provider model.  After the transition is complete,
     * this flag (and its associated code) can be removed.
     */
    private final boolean USE_LOCALSTORE = false;
    LocalStore mLocalStore = null;

    EmailProvider mEmailProvider;
    Context mMockContext;
    ContentResolver mMockResolver;

    public AttachmentProviderTests() {
        super(AttachmentProvider.class, AttachmentProvider.AUTHORITY);
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
                .addProvider(EmailProvider.EMAIL_AUTHORITY, mEmailProvider);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();

        if (mLocalStore != null) {
            mLocalStore.delete();
        }
    }

    /**
     * test delete() - should do nothing
     * test update() - should do nothing
     * test insert() - should do nothing
     */
    public void testUnimplemented() {
        assertEquals(0, mMockResolver.delete(AttachmentProvider.CONTENT_URI, null, null));
        assertEquals(0, mMockResolver.update(AttachmentProvider.CONTENT_URI, null, null, null));
        assertEquals(null, mMockResolver.insert(AttachmentProvider.CONTENT_URI, null));
    }

    /**
     * test query()
     *  - item found
     *  - item not found
     *  - permuted projection
     */
    public void testQuery() throws MessagingException {
        Account account1 = ProviderTestUtils.setupAccount("attachment-query", false, mMockContext);
        account1.mCompatibilityUuid = "test-UUID";
        account1.save(mMockContext);
        final long message1Id = 1;
        long attachment1Id = 1;
        long attachment2Id = 2;
        long attachment3Id = 3;

        // Note:  There is an implicit assumption in this test sequence that the first
        // attachment we add will be id=1 and the 2nd will have id=2.  This could fail on
        // a legitimate implementation.  Asserts below will catch this and fail the test
        // if necessary.
        Uri attachment1Uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment1Id);
        Uri attachment2Uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment2Id);
        Uri attachment3Uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment3Id);

        // Test with no attached database - should return null
        Cursor c = mMockResolver.query(attachment1Uri, (String[])null, null, (String[])null, null);
        assertNull(c);

        // Test with an attached database, but no attachment found - should return null
        setupAttachmentDatabase(account1);
        c = mMockResolver.query(attachment1Uri, (String[])null, null, (String[])null, null);
        assertNull(c);

        // Add a couple of attachment entries.  Note, query() just uses the DB, and does not
        // sample the files, so we won't bother creating the files
        Attachment newAttachment1 = ProviderTestUtils.setupAttachment(message1Id, "file1", 100,
                false, mMockContext);
        newAttachment1.mContentUri =
            AttachmentProvider.getAttachmentUri(account1.mId, attachment1Id).toString();
        attachment1Id = addAttachmentToDb(account1, newAttachment1);
        assertEquals("Broken test:  Unexpected id assignment", 1, attachment1Id);

        Attachment newAttachment2 = ProviderTestUtils.setupAttachment(message1Id, "file2", 200,
                false, mMockContext);
        newAttachment2.mContentUri =
            AttachmentProvider.getAttachmentUri(account1.mId, attachment2Id).toString();
        attachment2Id = addAttachmentToDb(account1, newAttachment2);
        assertEquals("Broken test:  Unexpected id assignment", 2, attachment2Id);

        Attachment newAttachment3 = ProviderTestUtils.setupAttachment(message1Id, "file3", 300,
                false, mMockContext);
        newAttachment3.mContentUri =
            AttachmentProvider.getAttachmentUri(account1.mId, attachment3Id).toString();
        attachment3Id = addAttachmentToDb(account1, newAttachment3);
        assertEquals("Broken test:  Unexpected id assignment", 3, attachment3Id);

        // Return a row with all columns specified
        attachment2Uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment2Id);
        c = mMockResolver.query(
                attachment2Uri,
                new String[] { AttachmentProviderColumns._ID, AttachmentProviderColumns.DATA,
                               AttachmentProviderColumns.DISPLAY_NAME,
                               AttachmentProviderColumns.SIZE },
                null, null, null);
        assertEquals(1, c.getCount());
        assertTrue(c.moveToFirst());
        assertEquals(attachment2Id, c.getLong(0));                  // id
        assertEquals(attachment2Uri.toString(), c.getString(1));    // content URI
        assertEquals("file2", c.getString(2));                      // display name
        assertEquals(200, c.getInt(3));                             // size

        // Return a row with permuted columns
        attachment3Uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment3Id);
        c = mMockResolver.query(
                attachment3Uri,
                new String[] { AttachmentProviderColumns.SIZE,
                               AttachmentProviderColumns.DISPLAY_NAME,
                               AttachmentProviderColumns.DATA, AttachmentProviderColumns._ID },
                null, null, null);
        assertEquals(1, c.getCount());
        assertTrue(c.moveToFirst());
        assertEquals(attachment3Id, c.getLong(3));                  // id
        assertEquals(attachment3Uri.toString(), c.getString(2));    // content URI
        assertEquals("file3", c.getString(1));                      // display name
        assertEquals(300, c.getInt(0));                             // size
    }

    /**
     * test getType()
     *  - regular file
     *  - thumbnail
     */
    public void testGetType() throws MessagingException {
        Account account1 = ProviderTestUtils.setupAccount("get-type", false, mMockContext);
        account1.mCompatibilityUuid = "test-UUID";
        account1.save(mMockContext);
        final long message1Id = 1;
        long attachment1Id = 1;
        long attachment2Id = 2;
        long attachment3Id = 3;
        long attachment4Id = 4;
        long attachment5Id = 5;
        long attachment6Id = 6;

        Uri attachment1Uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment1Id);

        // Test with no attached database - should return null
        String type = mMockResolver.getType(attachment1Uri);
        assertNull(type);

        // Test with an attached database, but no attachment found - should return null
        setupAttachmentDatabase(account1);
        type = mMockResolver.getType(attachment1Uri);
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
        Uri uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment2Id);
        type = mMockResolver.getType(uri);
        assertEquals("image/jpg", type);
        uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment3Id);
        type = mMockResolver.getType(uri);
        assertEquals("text/plain", type);
        uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment4Id);
        type = mMockResolver.getType(uri);
        assertEquals("application/msword", type);
        uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment5Id);
        type = mMockResolver.getType(uri);
        assertEquals("application/xyz", type);
        uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment6Id);
        type = mMockResolver.getType(uri);
        assertEquals("application/octet-stream", type);

        // Check the returned filetypes for the thumbnails
        uri = AttachmentProvider.getAttachmentThumbnailUri(account1.mId, attachment2Id, 62, 62);
        type = mMockResolver.getType(uri);
        assertEquals("image/png", type);
        uri = AttachmentProvider.getAttachmentThumbnailUri(account1.mId, attachment3Id, 62, 62);
        type = mMockResolver.getType(uri);
        assertEquals("image/png", type);
    }

    /**
     * Test static inferMimeType()
     * From the method doc:
     *   If the given mime type is non-empty and anything other than "application/octet-stream",
     *   just return it.  (This is the most common case.)
     *   If the filename has a recognizable extension and it converts to a mime type, return that.
     *   If the filename has an unrecognized extension, return "application/extension"
     *   Otherwise return "application/octet-stream".
     */
    public void testInferMimeType() {
        final String DEFAULT = "application/octet-stream";
        final String FILE_PDF = "myfile.false.pdf";
        final String FILE_ABC = "myfile.false.abc";
        final String FILE_NO_EXT = "myfile";

        // If the given mime type is non-empty and anything other than "application/octet-stream",
        // just return it.  (This is the most common case.)
        assertEquals("mime/type", AttachmentProvider.inferMimeType(null, "mime/type"));
        assertEquals("mime/type", AttachmentProvider.inferMimeType("", "mime/type"));
        assertEquals("mime/type", AttachmentProvider.inferMimeType(FILE_PDF, "mime/type"));

        // If the filename has a recognizable extension and it converts to a mime type, return that.
        assertEquals("application/pdf", AttachmentProvider.inferMimeType(FILE_PDF, null));
        assertEquals("application/pdf", AttachmentProvider.inferMimeType(FILE_PDF, ""));
        assertEquals("application/pdf", AttachmentProvider.inferMimeType(FILE_PDF, DEFAULT));

        // If the filename has an unrecognized extension, return "application/extension"
        assertEquals("application/abc", AttachmentProvider.inferMimeType(FILE_ABC, null));
        assertEquals("application/abc", AttachmentProvider.inferMimeType(FILE_ABC, ""));
        assertEquals("application/abc", AttachmentProvider.inferMimeType(FILE_ABC, DEFAULT));

        // Otherwise return "application/octet-stream".
        assertEquals(DEFAULT, AttachmentProvider.inferMimeType(FILE_NO_EXT, null));
        assertEquals(DEFAULT, AttachmentProvider.inferMimeType(FILE_NO_EXT, ""));
        assertEquals(DEFAULT, AttachmentProvider.inferMimeType(FILE_NO_EXT, DEFAULT));
        assertEquals(DEFAULT, AttachmentProvider.inferMimeType(null, null));
        assertEquals(DEFAULT, AttachmentProvider.inferMimeType("", ""));
    }

    /**
     * test openFile()
     *  - regular file
     *  - TODO: variations on the content URI
     */
    public void testOpenFile() throws MessagingException, IOException {
        Account account1 = ProviderTestUtils.setupAccount("open-file", false, mMockContext);
        account1.mCompatibilityUuid = "test-UUID";
        account1.save(mMockContext);
        final long message1Id = 1;
        long attachment1Id = 1;
        long attachment2Id = 2;

        // Note:  There is an implicit assumption in this test sequence that the first
        // attachment we add will be id=1 and the 2nd will have id=2.  This could fail on
        // a legitimate implementation.  Asserts below will catch this and fail the test
        // if necessary.
        Uri file1Uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment1Id);
        Uri file2Uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment2Id);

        // Test with no attached database - should throw an exception
        AssetFileDescriptor afd;
        try {
            afd = mMockResolver.openAssetFileDescriptor(file1Uri, "r");
            fail("Should throw an exception on a bad URI");
        } catch (FileNotFoundException fnf) {
            // expected
        }

        // Test with an attached database, but no attachment found
        setupAttachmentDatabase(account1);
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
        newAttachment2.mContentUri =
                AttachmentProvider.getAttachmentUri(account1.mId, attachment2Id).toString();
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
        account1.mCompatibilityUuid = "test-UUID";
        account1.save(mMockContext);
        final long message1Id = 1;
        long attachment1Id = 1;
        long attachment2Id = 2;

        // Note:  There is an implicit assumption in this test sequence that the first
        // attachment we add will be id=1 and the 2nd will have id=2.  This could fail on
        // a legitimate implementation.  Asserts below will catch this and fail the test
        // if necessary.
        Uri thumb1Uri = AttachmentProvider.getAttachmentThumbnailUri(account1.mId, attachment1Id,
                62, 62);
        Uri thumb2Uri = AttachmentProvider.getAttachmentThumbnailUri(account1.mId, attachment2Id,
                62, 62);

        // Test with no attached database - should return null (used to throw SQLiteException)
        AssetFileDescriptor afd = mMockResolver.openAssetFileDescriptor(thumb1Uri, "r");
        assertNull(afd);

        // Test with an attached database, but no attachment found
        setupAttachmentDatabase(account1);
        afd = mMockResolver.openAssetFileDescriptor(thumb1Uri, "r");
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
        newAttachment2.mContentUri =
                AttachmentProvider.getAttachmentUri(account1.mId, attachment2Id).toString();
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
        newAttachment.mContentUri = contentUriStr;
        long attachmentId = addAttachmentToDb(account, newAttachment);
        Uri attachmentUri = AttachmentProvider.getAttachmentUri(account.mId, attachmentId);
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
        account1.mCompatibilityUuid = "test-UUID";
        account1.save(mMockContext);
        final long message1Id = 1;
        // We use attachmentId == 1 but any other id would do
        final long attachment1Id = 1;
        final Uri attachment1Uri = AttachmentProvider.getAttachmentUri(account1.mId, attachment1Id);

        // Test with no attached database - should return input
        Uri result = AttachmentProvider.resolveAttachmentIdToContentUri(
                mMockResolver, attachment1Uri);
        assertEquals(attachment1Uri, result);

        setupAttachmentDatabase(account1);

        // Test with an attached database, but no attachment found - should return input
        // We know that the attachmentId 1 does not exist because there are no attachments
        // created at this point
        result = AttachmentProvider.resolveAttachmentIdToContentUri(
                mMockResolver, attachment1Uri);
        assertEquals(attachment1Uri, result);

        // Test with existing attachement and contentUri != null
        // Note, resolveAttachmentIdToContentUri() just uses
        // the DB, and does not sample the files, so we won't bother creating the files
        {
            Uri attachmentUri = createAttachment(account1, message1Id, "file:///path/to/file");
            Uri contentUri = AttachmentProvider.resolveAttachmentIdToContentUri(mMockResolver, 
                    attachmentUri);
            // When the attachment is found, return the stored content_uri value
            assertEquals("file:///path/to/file", contentUri.toString());
        }

        // Test with existing attachement and contentUri == null
        {
            Uri attachmentUri = createAttachment(account1, message1Id, null);
            Uri contentUri = AttachmentProvider.resolveAttachmentIdToContentUri(mMockResolver, 
                    attachmentUri);
            // When contentUri is null should return input
            assertEquals(attachmentUri, contentUri);
        }
    }

    /**
     * Test the functionality of deleting all attachment files for a given message.
     */
    public void testDeleteFiles() throws IOException {
        Account account1 = ProviderTestUtils.setupAccount("attachment-query", false, mMockContext);
        account1.mCompatibilityUuid = "test-UUID";
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
        File attachmentsDir = AttachmentProvider.getAttachmentDirectory(mMockContext, account1.mId);
        assertEquals(3, attachmentsDir.listFiles().length);

        // Command deletion of some files and check for results
        
        // Message 4 has no attachments so no files should be deleted
        AttachmentProvider.deleteAllAttachmentFiles(mMockContext, account1.mId, message4Id);
        assertEquals(3, attachmentsDir.listFiles().length);

        // Message 3 has no attachment files so no files should be deleted
        AttachmentProvider.deleteAllAttachmentFiles(mMockContext, account1.mId, message3Id);
        assertEquals(3, attachmentsDir.listFiles().length);

        // Message 2 has 2 attachment files so this should delete 2 files
        AttachmentProvider.deleteAllAttachmentFiles(mMockContext, account1.mId, message2Id);
        assertEquals(1, attachmentsDir.listFiles().length);

        // Message 1 has 1 attachment file so this should delete the last file
        AttachmentProvider.deleteAllAttachmentFiles(mMockContext, account1.mId, message1Id);
        assertEquals(0, attachmentsDir.listFiles().length);
    }

    /**
     * Test the functionality of deleting an entire mailbox's attachments.
     */
    public void testDeleteMailbox() throws IOException {
        Account account1 = ProviderTestUtils.setupAccount("attach-mbox-del", false, mMockContext);
        account1.mCompatibilityUuid = "test-UUID";
        account1.save(mMockContext);
        long account1Id = account1.mId;
        Mailbox mailbox1 = ProviderTestUtils.setupMailbox("mbox1", account1Id, true, mMockContext);
        long mailbox1Id = mailbox1.mId;
        Mailbox mailbox2 = ProviderTestUtils.setupMailbox("mbox2", account1Id, true, mMockContext);
        long mailbox2Id = mailbox2.mId;
        
        // two messages per mailbox, one w/attachments, one w/o attachments
        Message message1a = ProviderTestUtils.setupMessage("msg1a", account1Id, mailbox1Id, false,
                true, mMockContext);
        Message message1b = ProviderTestUtils.setupMessage("msg1b", account1Id, mailbox1Id, false,
                true, mMockContext);
        Message message2a = ProviderTestUtils.setupMessage("msg2a", account1Id, mailbox2Id, false,
                true, mMockContext);
        Message message2b = ProviderTestUtils.setupMessage("msg2b", account1Id, mailbox2Id, false,
                true, mMockContext);

        // attachments on each of the "a" messages (3 on 1a, 1 on 1b)
        Attachment newAttachment1 = ProviderTestUtils.setupAttachment(message1a.mId, "file1", 100,
                true, mMockContext);
        Attachment newAttachment2 = ProviderTestUtils.setupAttachment(message1a.mId, "file2", 200,
                true, mMockContext);
        Attachment newAttachment3 = ProviderTestUtils.setupAttachment(message1a.mId, "file3", 100,
                true, mMockContext);
        Attachment newAttachment4 = ProviderTestUtils.setupAttachment(message2a.mId, "file4", 100,
                true, mMockContext);
        
        // Create test files
        createAttachmentFile(account1, newAttachment1.mId);
        createAttachmentFile(account1, newAttachment2.mId);
        createAttachmentFile(account1, newAttachment3.mId);
        createAttachmentFile(account1, newAttachment4.mId);

        // Confirm four attachment files found
        File attachmentsDir = AttachmentProvider.getAttachmentDirectory(mMockContext, account1.mId);
        assertEquals(4, attachmentsDir.listFiles().length);

        // Command the deletion of mailbox 1 - we should lose 3 attachment files
        AttachmentProvider.deleteAllMailboxAttachmentFiles(mMockContext, account1Id, mailbox1Id);
        assertEquals(1, attachmentsDir.listFiles().length);

        // Command the deletion of mailbox 2 - we should lose 1 attachment file
        AttachmentProvider.deleteAllMailboxAttachmentFiles(mMockContext, account1Id, mailbox2Id);
        assertEquals(0, attachmentsDir.listFiles().length);
    }
    

    /**
     * Create an attachment by copying an image resource into a file.  Uses "real" resources
     * to get a real image from Email
     */
    private String createAttachmentFile(Account forAccount, long id) throws IOException {
        File outFile = getAttachmentFile(forAccount, id);
        Bitmap bitmap = BitmapFactory.decodeResource(getContext().getResources(),
                R.drawable.ic_email_attachment);
        FileOutputStream out = new FileOutputStream(outFile);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();

        return outFile.getAbsolutePath();
    }

    /**
     * Set up the attachments database.
     */
    private void setupAttachmentDatabase(Account forAccount) throws MessagingException {
        if (USE_LOCALSTORE) {
            String localStoreUri = "local://localhost/" + dbName(forAccount);
            mLocalStore = (LocalStore) LocalStore.newInstance(localStoreUri, mMockContext, null);
        } else {
            // Nothing to do - EmailProvider is already available for us
        }
    }

    /**
     * Record an attachment in the attachments database
     * @return the id of the attachment just created
     */
    private long addAttachmentToDb(Account forAccount, Attachment newAttachment) {
        long attachmentId = -1;
        if (USE_LOCALSTORE) {
            ContentValues cv = new ContentValues();
            cv.put("message_id", newAttachment.mMessageKey);
            cv.put("content_uri", newAttachment.mContentUri);
            cv.put("store_data", (String)null);
            cv.put("size", newAttachment.mSize);
            cv.put("name", newAttachment.mFileName);
            cv.put("mime_type", newAttachment.mMimeType);
            cv.put("content_id", newAttachment.mContentId);

            SQLiteDatabase db = null;
            try {
                db = SQLiteDatabase.openDatabase(dbName(forAccount), null, 0);
                attachmentId = db.insertOrThrow("attachments", "message_id", cv);
            }
            finally {
                if (db != null) {
                    db.close();
                }
            }
        } else {
            newAttachment.save(mMockContext);
            attachmentId = newAttachment.mId;
        }
        return attachmentId;
    }

    /**
     * Return the database path+name for a given account
     */
    private String dbName(Account forAccount) {
        if (USE_LOCALSTORE) {
            return mMockContext.getDatabasePath(forAccount.mCompatibilityUuid + ".db").toString();
        } else {
            throw new java.lang.UnsupportedOperationException();
        }
    }

    /**
     * Map from account, attachment ID to attachment file
     */
    private File getAttachmentFile(Account forAccount, long id) {
        String idString = Long.toString(id);
        if (USE_LOCALSTORE) {
            return new File(mMockContext.getDatabasePath(forAccount.mCompatibilityUuid + ".db_att"),
                    idString);
        } else {
            File attachmentsDir = mMockContext.getDatabasePath(forAccount.mId + ".db_att");
            if (!attachmentsDir.exists()) {
                attachmentsDir.mkdirs();
            }
            return new File(attachmentsDir, idString);
        }
    }
}
