/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.email.mail.store;

import com.android.email.Email;
import com.android.email.mail.Address;
import com.android.email.mail.Body;
import com.android.email.mail.FetchProfile;
import com.android.email.mail.Flag;
import com.android.email.mail.Folder;
import com.android.email.mail.Message;
import com.android.email.mail.MessageTestUtils;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Part;
import com.android.email.mail.Store;
import com.android.email.mail.Folder.FolderType;
import com.android.email.mail.Folder.OpenMode;
import com.android.email.mail.Message.RecipientType;
import com.android.email.mail.MessageTestUtils.MultipartBuilder;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.mail.internet.TextBody;
import com.android.email.mail.store.LocalStore.LocalMessage;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/**
 * This is a series of unit tests for the LocalStore class.
 */
@MediumTest
public class LocalStoreUnitTests extends AndroidTestCase {
    
    public static final String DB_NAME = "com.android.email.mail.store.LocalStoreUnitTests.db";

    private static final String SENDER = "sender@android.com";
    private static final String RECIPIENT_TO = "recipient-to@android.com";
    private static final String SUBJECT = "This is the subject";
    private static final String BODY = "This is the body.  This is also the body.";
    private static final String MESSAGE_ID = "Test-Message-ID";
    private static final String MESSAGE_ID_2 = "Test-Message-ID-Second";
    
    private static final int DATABASE_VERSION = 24;
    
    private static final String FOLDER_NAME = "TEST";
    private static final String MISSING_FOLDER_NAME = "TEST-NO-FOLDER";
    
    /* These values are provided by setUp() */
    private String mLocalStoreUri = null;
    private LocalStore mStore = null;
    private LocalStore.LocalFolder mFolder = null;
    private File mCacheDir;
    
    /**
     * Setup code.  We generate a lightweight LocalStore and LocalStore.LocalFolder.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Email.setTempDirectory(getContext());
        
        // These are needed so we can get at the inner classes
        // Create a dummy database (be sure to delete it in tearDown())
        mLocalStoreUri = "local://localhost/" + getContext().getDatabasePath(DB_NAME);
        
        mStore = (LocalStore) LocalStore.newInstance(mLocalStoreUri, getContext(), null);
        mFolder = (LocalStore.LocalFolder) mStore.getFolder(FOLDER_NAME);
    }
    
    /**
     * Teardown code.  Delete the local database and any other files
     */
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (mFolder != null) {
            mFolder.close(false);
        }
        
        // First, try the official way
        if (mStore != null) {
            mStore.delete();
        }
        
        // Next, just try hacking and slashing files
        // (Mostly, this is actually copied from LocalStore.delete
        URI uri = new URI(mLocalStoreUri);
        String path = uri.getPath();
        File attachmentsDir = new File(path + "_att");

        // Delete any attachments we dribbled out
        try {
            File[] attachments = attachmentsDir.listFiles();
            for (File attachment : attachments) {
                if (attachment.exists()) {
                    attachment.delete();
                }
            }
        } catch (RuntimeException e) { }
        // Delete attachments dir
        try {
            if (attachmentsDir.exists()) {
                attachmentsDir.delete();
            }
        } catch (RuntimeException e) { }
        // Delete db file
        try {
            new File(path).delete();
        }
        catch (RuntimeException e) { }
    }
    
    /**
     * Test that messages are being stored with Message-ID intact.
     * 
     * This variant tests appendMessages() and getMessage() and getMessages()
     */
    public void testMessageId_1() throws MessagingException {
        final MimeMessage message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message.setMessageId(MESSAGE_ID);
        mFolder.open(OpenMode.READ_WRITE, null);
        mFolder.appendMessages(new Message[]{ message });
        String localUid = message.getUid();
        
        // Now try to read it back from the database using getMessage()
        
        MimeMessage retrieved = (MimeMessage) mFolder.getMessage(localUid);
        assertEquals(MESSAGE_ID, retrieved.getMessageId());
        
        // Now try to read it back from the database using getMessages()
        
        Message[] retrievedArray = mFolder.getMessages(null);
        assertEquals(1, retrievedArray.length);
        MimeMessage retrievedEntry = (MimeMessage) retrievedArray[0];
        assertEquals(MESSAGE_ID, retrievedEntry.getMessageId());
    }

    /**
     * Test that messages are being stored with Message-ID intact.
     * 
     * This variant tests updateMessage() and getMessages()
     */
    public void testMessageId_2() throws MessagingException {
        final MimeMessage message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message.setMessageId(MESSAGE_ID);
        mFolder.open(OpenMode.READ_WRITE, null);
        mFolder.appendMessages(new Message[]{ message });
        String localUid = message.getUid();
        
        // Now try to read it back from the database using getMessage()
        MimeMessage retrieved = (MimeMessage) mFolder.getMessage(localUid);
        assertEquals(MESSAGE_ID, retrieved.getMessageId());

        // Now change the Message-ID and try to update() the message
        // Note, due to a weakness in the API, you have to use a message object you got from
        // LocalStore when making the update call
        retrieved.setMessageId(MESSAGE_ID_2);
        mFolder.updateMessage((LocalStore.LocalMessage)retrieved);
        
        // And read back once more to confirm the change (using getMessages() to confirm "just one")
        Message[] retrievedArray = mFolder.getMessages(null);
        assertEquals(1, retrievedArray.length);
        MimeMessage retrievedEntry = (MimeMessage) retrievedArray[0];
        assertEquals(MESSAGE_ID_2, retrievedEntry.getMessageId());
    }

    /**
     * Build a test message that can be used as input to processSourceMessage
     * 
     * @param to Recipient(s) of the message
     * @param sender Sender(s) of the message
     * @param subject Subject of the message
     * @param content Content of the message
     * @return a complete Message object
     */
    private MimeMessage buildTestMessage(String to, String sender, String subject, String content) 
            throws MessagingException {
        MimeMessage message = new MimeMessage();
        
        if (to != null) {
            Address[] addresses = Address.parse(to);
            message.setRecipients(RecipientType.TO, addresses);
        }
        
        if (sender != null) {
            Address[] addresses = Address.parse(sender);
            message.setFrom(Address.parse(sender)[0]);
        }
        
        if (subject != null) {
            message.setSubject(subject);
        }
        
        if (content != null) {
            TextBody body = new TextBody(content);
            message.setBody(body);
        }
        
        return message;
    }
    
    /**
     * Test two modes (STRUCTURE vs. BODY) of fetch()
     */
    public void testFetchModes() throws MessagingException {
        final String BODY_TEXT_PLAIN = "This is the body text.";
        final String BODY_TEXT_HTML = "But this is the HTML version of the body text.";
        
        MimeMessage message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message.setMessageId(MESSAGE_ID);
        Body body = new MultipartBuilder("multipart/mixed")
            .addBodyPart(MessageTestUtils.bodyPart("image/tiff", "cid.4@android.com"))
            .addBodyPart(new MultipartBuilder("multipart/related")
                .addBodyPart(new MultipartBuilder("multipart/alternative")
                    .addBodyPart(MessageTestUtils.textPart("text/plain", BODY_TEXT_PLAIN))
                    .addBodyPart(MessageTestUtils.textPart("text/html", BODY_TEXT_HTML))
                    .buildBodyPart())
                .buildBodyPart())
            .addBodyPart(MessageTestUtils.bodyPart("image/gif", "cid.3@android.com"))
            .build();
        message.setBody(body);

        mFolder.open(OpenMode.READ_WRITE, null);
        mFolder.appendMessages(new Message[]{ message });
        
        // Now read it back, and fetch it two ways - first, structure only
        Message[] messages = mFolder.getMessages(null);
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.STRUCTURE);
        mFolder.fetch(messages, fp, null);
        // check for empty body parts
        Part textPart = MimeUtility.findFirstPartByMimeType(messages[0], "text/plain");
        Part htmlPart = MimeUtility.findFirstPartByMimeType(messages[0], "text/html");
        assertNull(MimeUtility.getTextFromPart(textPart));
        assertNull(MimeUtility.getTextFromPart(htmlPart));

        // Next, complete body
        messages = mFolder.getMessages(null);
        fp.clear();
        fp.add(FetchProfile.Item.BODY);
        mFolder.fetch(messages, fp, null);
        // check for real body parts
        textPart = MimeUtility.findFirstPartByMimeType(messages[0], "text/plain");
        htmlPart = MimeUtility.findFirstPartByMimeType(messages[0], "text/html");
        assertEquals(BODY_TEXT_PLAIN, MimeUtility.getTextFromPart(textPart));
        assertEquals(BODY_TEXT_HTML, MimeUtility.getTextFromPart(htmlPart));
    }
    
    /**
     * Test the new store persistent data code.
     * 
     * This test, and the underlying code, reflect the essential error in the Account class.  The
     * account objects should have been singletons-per-account.  As it stands there are lots of
     * them floating around, which is very expensive (we waste a lot of effort creating them)
     * and forces slow sync hacks for dynamic data like the store's persistent data.
     */
    public void testStorePersistentData() {

        final String TEST_KEY = "the.test.key";
        final String TEST_KEY_2 = "a.different.test.key";
        final String TEST_STRING = "This is the store's persistent data.";
        final String TEST_STRING_2 = "Rewrite the store data.";

        // confirm default reads on new store
        assertEquals("the-default", mStore.getPersistentString(TEST_KEY, "the-default"));
        
        // test write/readback
        mStore.setPersistentString(TEST_KEY, TEST_STRING);
        mStore.setPersistentString(TEST_KEY_2, TEST_STRING_2);
        assertEquals(TEST_STRING, mStore.getPersistentString(TEST_KEY, null));
        assertEquals(TEST_STRING_2, mStore.getPersistentString(TEST_KEY_2, null));
    }

    /**
     * Test the callbacks for setting & getting persistent data
     */
    public void testStorePersistentCallbacks() throws MessagingException {

        final String TEST_KEY = "the.test.key";
        final String TEST_KEY_2 = "a.different.test.key";
        final String TEST_STRING = "This is the store's persistent data.";
        final String TEST_STRING_2 = "Rewrite the store data.";
        
        Store.PersistentDataCallbacks callbacks = mStore.getPersistentCallbacks();

        // confirm default reads on new store
        assertEquals("the-default", callbacks.getPersistentString(TEST_KEY, "the-default"));
        
        // test write/readback
        callbacks.setPersistentString(TEST_KEY, TEST_STRING);
        callbacks.setPersistentString(TEST_KEY_2, TEST_STRING_2);
        assertEquals(TEST_STRING, mStore.getPersistentString(TEST_KEY, null));
        assertEquals(TEST_STRING_2, mStore.getPersistentString(TEST_KEY_2, null));
    }

    /**
     * Test functionality of setting & saving store persistence values
     */
    public void testFolderPersistentStorage() throws MessagingException {
        mFolder.open(OpenMode.READ_WRITE, null);

        // set up a 2nd folder to confirm independent storage
        LocalStore.LocalFolder folder2 = (LocalStore.LocalFolder) mStore.getFolder("FOLDER-2");
        assertFalse(folder2.exists());
        folder2.create(FolderType.HOLDS_MESSAGES);
        folder2.open(OpenMode.READ_WRITE, null);

        // use the callbacks, as these are the "official" API
        Folder.PersistentDataCallbacks callbacks = mFolder.getPersistentCallbacks();
        Folder.PersistentDataCallbacks callbacks2 = folder2.getPersistentCallbacks();

        // set some values - tests independence & inserts
        callbacks.setPersistentString("key1", "value-1-1");
        callbacks.setPersistentString("key2", "value-1-2");
        callbacks2.setPersistentString("key1", "value-2-1");
        callbacks2.setPersistentString("key2", "value-2-2");

        // readback initial values
        assertEquals("value-1-1", callbacks.getPersistentString("key1", null));
        assertEquals("value-1-2", callbacks.getPersistentString("key2", null));
        assertEquals("value-2-1", callbacks2.getPersistentString("key1", null));
        assertEquals("value-2-2", callbacks2.getPersistentString("key2", null));

        // readback with default values
        assertEquals("value-1-3", callbacks.getPersistentString("key3", "value-1-3"));
        assertEquals("value-2-3", callbacks2.getPersistentString("key3", "value-2-3"));

        // partial updates
        callbacks.setPersistentString("key1", "value-1-1b");
        callbacks2.setPersistentString("key2", "value-2-2b");
        assertEquals("value-1-1b", callbacks.getPersistentString("key1", null));    // changed
        assertEquals("value-1-2", callbacks.getPersistentString("key2", null));     // same
        assertEquals("value-2-1", callbacks2.getPersistentString("key1", null));    // same
        assertEquals("value-2-2b", callbacks2.getPersistentString("key2", null));   // changed
    }
    
    /**
     * Test functionality of persistence update with bulk update
     */
    public void testFolderPersistentBulkUpdate() throws MessagingException {
        mFolder.open(OpenMode.READ_WRITE, null);
    
        // set up a 2nd folder to confirm independent storage
        LocalStore.LocalFolder folder2 = (LocalStore.LocalFolder) mStore.getFolder("FOLDER-2");
        assertFalse(folder2.exists());
        folder2.create(FolderType.HOLDS_MESSAGES);
        folder2.open(OpenMode.READ_WRITE, null);
    
        // use the callbacks, as these are the "official" API
        Folder.PersistentDataCallbacks callbacks = mFolder.getPersistentCallbacks();
        Folder.PersistentDataCallbacks callbacks2 = folder2.getPersistentCallbacks();
    
        // set some values - tests independence & inserts
        callbacks.setPersistentString("key1", "value-1-1");
        callbacks.setPersistentString("key2", "value-1-2");
        callbacks2.setPersistentString("key1", "value-2-1");
        callbacks2.setPersistentString("key2", "value-2-2");
        
        final MimeMessage message1 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message1.setFlag(Flag.X_STORE_1, false);
        message1.setFlag(Flag.X_STORE_2, false);
        
        final MimeMessage message2 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message2.setFlag(Flag.X_STORE_1, true);
        message2.setFlag(Flag.X_STORE_2, false);

        final MimeMessage message3 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message3.setFlag(Flag.X_STORE_1, false);
        message3.setFlag(Flag.X_STORE_2, true);

        final MimeMessage message4 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message4.setFlag(Flag.X_STORE_1, true);
        message4.setFlag(Flag.X_STORE_2, true);

        Message[] allOriginals = new Message[]{ message1, message2, message3, message4 };
        
        mFolder.appendMessages(allOriginals);

        // Now make a bulk update (set)
        callbacks.setPersistentStringAndMessageFlags("key1", "value-1-1a", 
                new Flag[]{ Flag.X_STORE_1 }, null);
        // And check all messages for that flag now set, but other flag was not set
        Message[] messages = mFolder.getMessages(null);
        for (Message msg : messages) {
            assertTrue(msg.isSet(Flag.X_STORE_1));
            if (msg.getUid().equals(message1.getUid())) assertFalse(msg.isSet(Flag.X_STORE_2));
            if (msg.getUid().equals(message2.getUid())) assertFalse(msg.isSet(Flag.X_STORE_2));
        }
        assertEquals("value-1-1a", callbacks.getPersistentString("key1", null));
        
        // Same test, but clearing
        callbacks.setPersistentStringAndMessageFlags("key2", "value-1-2a", 
                null, new Flag[]{ Flag.X_STORE_2 });
        // And check all messages for that flag now set, but other flag was not set
        messages = mFolder.getMessages(null);
        for (Message msg : messages) {
            assertTrue(msg.isSet(Flag.X_STORE_1));
            assertFalse(msg.isSet(Flag.X_STORE_2));
        }
        assertEquals("value-1-2a", callbacks.getPersistentString("key2", null));        
    }
    
    /**
     * Test that messages are being stored with store flags properly persisted.
     * 
     * This variant tests appendMessages() and updateMessages() and getMessage()
     */
    public void testStoreFlags() throws MessagingException {
        final MimeMessage message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message.setMessageId(MESSAGE_ID);
        message.setFlag(Flag.X_STORE_1, true);
        message.setFlag(Flag.X_STORE_2, false);
        
        mFolder.open(OpenMode.READ_WRITE, null);
        mFolder.appendMessages(new Message[]{ message });
        String localUid = message.getUid();
        
        // Now try to read it back from the database using getMessage()
        
        MimeMessage retrieved = (MimeMessage) mFolder.getMessage(localUid);
        assertEquals(MESSAGE_ID, retrieved.getMessageId());
        assertTrue(message.isSet(Flag.X_STORE_1));
        assertFalse(message.isSet(Flag.X_STORE_2));
        
        // Now try to update it using updateMessages()
        
        retrieved.setFlag(Flag.X_STORE_1, false);
        retrieved.setFlag(Flag.X_STORE_2, true);
        mFolder.updateMessage((LocalStore.LocalMessage)retrieved);
        
        // And read back once more to confirm the change (using getMessages() to confirm "just one")
        Message[] retrievedArray = mFolder.getMessages(null);
        assertEquals(1, retrievedArray.length);
        MimeMessage retrievedEntry = (MimeMessage) retrievedArray[0];
        assertEquals(MESSAGE_ID, retrieved.getMessageId());

        assertFalse(retrievedEntry.isSet(Flag.X_STORE_1));
        assertTrue(retrievedEntry.isSet(Flag.X_STORE_2));
    }
    
    /**
     * Test that messages are being stored with download & delete state flags properly persisted.
     * 
     * This variant tests appendMessages() and updateMessages() and getMessage()
     */
    public void testDownloadAndDeletedFlags() throws MessagingException {
        final MimeMessage message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message.setMessageId(MESSAGE_ID);
        message.setFlag(Flag.X_STORE_1, true);
        message.setFlag(Flag.X_STORE_2, false);
        message.setFlag(Flag.X_DOWNLOADED_FULL, true);
        message.setFlag(Flag.X_DOWNLOADED_PARTIAL, false);
        message.setFlag(Flag.DELETED, false);
        
        mFolder.open(OpenMode.READ_WRITE, null);
        mFolder.appendMessages(new Message[]{ message });
        String localUid = message.getUid();
        
        // Now try to read it back from the database using getMessage()
        
        MimeMessage retrieved = (MimeMessage) mFolder.getMessage(localUid);
        assertEquals(MESSAGE_ID, retrieved.getMessageId());
        assertTrue(retrieved.isSet(Flag.X_STORE_1));
        assertFalse(retrieved.isSet(Flag.X_STORE_2));
        assertTrue(retrieved.isSet(Flag.X_DOWNLOADED_FULL));
        assertFalse(retrieved.isSet(Flag.X_DOWNLOADED_PARTIAL));
        assertFalse(retrieved.isSet(Flag.DELETED));
        
        // Now try to update it using updateMessages()
        
        retrieved.setFlag(Flag.X_STORE_1, false);
        retrieved.setFlag(Flag.X_STORE_2, true);
        retrieved.setFlag(Flag.X_DOWNLOADED_FULL, false);
        retrieved.setFlag(Flag.X_DOWNLOADED_PARTIAL, true);
        mFolder.updateMessage((LocalStore.LocalMessage)retrieved);
        
        // And read back once more to confirm the change (using getMessages() to confirm "just one")
        Message[] retrievedArray = mFolder.getMessages(null);
        assertEquals(1, retrievedArray.length);
        MimeMessage retrievedEntry = (MimeMessage) retrievedArray[0];
        assertEquals(MESSAGE_ID, retrievedEntry.getMessageId());

        assertFalse(retrievedEntry.isSet(Flag.X_STORE_1));
        assertTrue(retrievedEntry.isSet(Flag.X_STORE_2));
        assertFalse(retrievedEntry.isSet(Flag.X_DOWNLOADED_FULL));
        assertTrue(retrievedEntry.isSet(Flag.X_DOWNLOADED_PARTIAL));
        assertFalse(retrievedEntry.isSet(Flag.DELETED));
        
        // Finally test setFlag(Flag.DELETED)
        retrievedEntry.setFlag(Flag.DELETED, true);
        mFolder.updateMessage((LocalStore.LocalMessage)retrievedEntry);
        Message[] retrievedArray2 = mFolder.getMessages(null);
        assertEquals(1, retrievedArray2.length);
        MimeMessage retrievedEntry2 = (MimeMessage) retrievedArray2[0];
        assertEquals(MESSAGE_ID, retrievedEntry2.getMessageId());

        assertFalse(retrievedEntry2.isSet(Flag.X_STORE_1));
        assertTrue(retrievedEntry2.isSet(Flag.X_STORE_2));
        assertFalse(retrievedEntry2.isSet(Flag.X_DOWNLOADED_FULL));
        assertTrue(retrievedEntry2.isSet(Flag.X_DOWNLOADED_PARTIAL));
        assertTrue(retrievedEntry2.isSet(Flag.DELETED));
    }
    
    /**
     * Test that store flags are separated into separate columns and not replicated in the
     * (should be deprecated) string flags column.
     */
    public void testStoreFlagStorage() throws MessagingException, URISyntaxException {
        final MimeMessage message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message.setMessageId(MESSAGE_ID);
        message.setFlag(Flag.SEEN, true);
        message.setFlag(Flag.FLAGGED, true);
        message.setFlag(Flag.X_STORE_1, true);
        message.setFlag(Flag.X_STORE_2, true);
        message.setFlag(Flag.X_DOWNLOADED_FULL, true);
        message.setFlag(Flag.X_DOWNLOADED_PARTIAL, true);
        message.setFlag(Flag.DELETED, true);
        
        mFolder.open(OpenMode.READ_WRITE, null);
        mFolder.appendMessages(new Message[]{ message });
        String localUid = message.getUid();
        long folderId = mFolder.getId();
        mFolder.close(false);
        
        // read back using direct db calls, to view columns
        final URI uri = new URI(mLocalStoreUri);
        final String dbPath = uri.getPath();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);

        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "SELECT flags, store_flag_1, store_flag_2," +
                    " flag_downloaded_full, flag_downloaded_partial, flag_deleted" +
                    " FROM messages" + 
                    " WHERE uid = ? AND folder_id = ?",
                    new String[] {
                            localUid, Long.toString(folderId)
                    });
            assertTrue("appended message not found", cursor.moveToNext());
            String flagString = cursor.getString(0);
            String[] flags = flagString.split(",");
            assertEquals(2, flags.length);      // 2 = SEEN & FLAGGED
            for (String flag : flags) {
                assertFalse("storeFlag1 in string", flag.equals(Flag.X_STORE_1.toString()));
                assertFalse("storeFlag2 in string", flag.equals(Flag.X_STORE_2.toString()));
                assertFalse("flag_downloaded_full in string", 
                        flag.equals(Flag.X_DOWNLOADED_FULL.toString()));
                assertFalse("flag_downloaded_partial in string", 
                        flag.equals(Flag.X_DOWNLOADED_PARTIAL.toString()));
                assertFalse("flag_deleted in string", flag.equals(Flag.DELETED.toString()));
            }
            assertEquals(1, cursor.getInt(1));  // store flag 1 is set
            assertEquals(1, cursor.getInt(2));  // store flag 2 is set
            assertEquals(1, cursor.getInt(3));  // flag_downloaded_full is set
            assertEquals(1, cursor.getInt(4));  // flag_downloaded_partial is set
            assertEquals(1, cursor.getInt(5));  // flag_deleted is set
        }
        finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
    
    /**
     * Test the new functionality of getting messages from LocalStore based on their flags.
     */
    public void testGetMessagesFlags() throws MessagingException {
        
        final MimeMessage message1 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message1.setFlag(Flag.X_STORE_1, false);
        message1.setFlag(Flag.X_STORE_2, false);

        final MimeMessage message2 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message2.setFlag(Flag.X_STORE_1, true);
        message2.setFlag(Flag.X_STORE_2, false);

        final MimeMessage message3 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message3.setFlag(Flag.X_STORE_1, false);
        message3.setFlag(Flag.X_STORE_2, true);

        final MimeMessage message4 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message4.setFlag(Flag.X_STORE_1, true);
        message4.setFlag(Flag.X_STORE_2, true);

        final MimeMessage message5 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message5.setFlag(Flag.X_DOWNLOADED_FULL, true);

        final MimeMessage message6 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message6.setFlag(Flag.X_DOWNLOADED_PARTIAL, true);

        final MimeMessage message7 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message7.setFlag(Flag.DELETED, true);

        Message[] allOriginals = new Message[] { 
                message1, message2, message3, message4, message5, message6, message7 };
        
        mFolder.open(OpenMode.READ_WRITE, null);
        mFolder.appendMessages(allOriginals);
        mFolder.close(false);
        
        // Now try getting various permutation and see if it works
        
        // Null lists are the same as empty lists - return all messages
        mFolder.open(OpenMode.READ_WRITE, null);
        Message[] getAll1 = mFolder.getMessages(null, null, null);
        checkGottenMessages("null filters", allOriginals, getAll1);
        
        Message[] getAll2 = mFolder.getMessages(new Flag[0], new Flag[0], null);
        checkGottenMessages("empty filters", allOriginals, getAll2);
        
        // Now try some selections, trying set and clear cases
        Message[] getSome1 = mFolder.getMessages(new Flag[]{ Flag.X_STORE_1 }, null, null);
        checkGottenMessages("store_1 set", new Message[]{ message2, message4 }, getSome1);

        Message[] getSome2 = mFolder.getMessages(null, new Flag[]{ Flag.X_STORE_1 }, null);
        checkGottenMessages("store_1 clear", 
                new Message[]{ message1, message3, message5, message6, message7 }, getSome2);

        Message[] getSome3 = mFolder.getMessages(new Flag[]{ Flag.X_STORE_2 }, null, null);
        checkGottenMessages("store_2 set", new Message[]{ message3, message4 }, getSome3);
        
        Message[] getSome4 = mFolder.getMessages(null, new Flag[]{ Flag.X_STORE_2 }, null);
        checkGottenMessages("store_2 clear", 
                new Message[]{ message1, message2, message5, message6, message7 }, getSome4);
        
        Message[] getOne1 = mFolder.getMessages(new Flag[]{ Flag.X_DOWNLOADED_FULL }, null, null);
        checkGottenMessages("downloaded full", new Message[]{ message5 }, getOne1);
        
        Message[] getOne2 = mFolder.getMessages(new Flag[]{ Flag.X_DOWNLOADED_PARTIAL }, null,
                null);
        checkGottenMessages("downloaded partial", new Message[]{ message6 }, getOne2);
        
        Message[] getOne3 = mFolder.getMessages(new Flag[]{ Flag.DELETED }, null, null);
        checkGottenMessages("deleted", new Message[]{ message7 }, getOne3);
        
        // Multi-flag selections
        Message[] getSingle1 = mFolder.getMessages(new Flag[]{ Flag.X_STORE_1, Flag.X_STORE_2 }, 
                null, null);
        checkGottenMessages("both set", new Message[]{ message4 }, getSingle1);
        
        Message[] getSingle2 = mFolder.getMessages(null,
                new Flag[]{ Flag.X_STORE_1, Flag.X_STORE_2 }, null);
        checkGottenMessages("both clear", new Message[]{ message1, message5, message6, message7 }, 
                getSingle2);
    }
    
    /**
     * Check for matching uid's between two lists of messages
     */
    private void checkGottenMessages(String failMessage, Message[] expected, Message[] actual) {
        HashSet<String> expectedUids = new HashSet<String>();
        for (Message message : expected) {
            expectedUids.add(message.getUid());
        }
        HashSet<String> actualUids = new HashSet<String>();
        for (Message message : actual) {
            actualUids.add(message.getUid());
        }
        assertEquals(failMessage, expectedUids, actualUids);
    }
    
    /**
     * Test for getMessageCount
     */
    public void testMessageCount() throws MessagingException {
        
        final MimeMessage message1 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message1.setFlag(Flag.X_STORE_1, false);
        message1.setFlag(Flag.X_STORE_2, false);
        message1.setFlag(Flag.X_DOWNLOADED_FULL, true);

        final MimeMessage message2 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message2.setFlag(Flag.X_STORE_1, true);
        message2.setFlag(Flag.X_STORE_2, false);

        final MimeMessage message3 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message3.setFlag(Flag.X_STORE_1, false);
        message3.setFlag(Flag.X_STORE_2, true);
        message3.setFlag(Flag.X_DOWNLOADED_FULL, true);

        final MimeMessage message4 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message4.setFlag(Flag.X_STORE_1, true);
        message4.setFlag(Flag.X_STORE_2, true);
        message4.setFlag(Flag.X_DOWNLOADED_FULL, true);

        final MimeMessage message5 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message5.setFlag(Flag.X_DOWNLOADED_FULL, true);

        final MimeMessage message6 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message6.setFlag(Flag.X_DOWNLOADED_PARTIAL, true);

        final MimeMessage message7 = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message7.setFlag(Flag.DELETED, true);

        Message[] allOriginals = new Message[] { 
                message1, message2, message3, message4, message5, message6, message7 };
        
        mFolder.open(OpenMode.READ_WRITE, null);
        mFolder.appendMessages(allOriginals);
        mFolder.close(false);
        
        // Null lists are the same as empty lists - return all messages
        mFolder.open(OpenMode.READ_WRITE, null);

        int allMessages = mFolder.getMessageCount();
        assertEquals("all messages", 7, allMessages);

        int storeFlag1 = mFolder.getMessageCount(new Flag[] { Flag.X_STORE_1 }, null);
        assertEquals("store flag 1", 2, storeFlag1);
        
        int storeFlag1NotFlag2 = mFolder.getMessageCount(
                new Flag[] { Flag.X_STORE_1 }, new Flag[] { Flag.X_STORE_2 });
        assertEquals("store flag 1, not 2", 1, storeFlag1NotFlag2);

        int downloadedFull = mFolder.getMessageCount(new Flag[] { Flag.X_DOWNLOADED_FULL }, null);
        assertEquals("downloaded full", 4, downloadedFull);
        
        int storeFlag2Full = mFolder.getMessageCount(
                new Flag[] { Flag.X_STORE_2, Flag.X_DOWNLOADED_FULL }, null);
        assertEquals("store flag 2, full", 2, storeFlag2Full);

        int notDeleted = mFolder.getMessageCount(null, new Flag[] { Flag.DELETED });
        assertEquals("not deleted", 6, notDeleted);
    }

    /**
     * Test unread messages count
     */
    public void testUnreadMessages() throws MessagingException {
        mFolder.open(OpenMode.READ_WRITE, null);

        // set up a 2nd folder to confirm independent storage
        LocalStore.LocalFolder folder2 = (LocalStore.LocalFolder) mStore.getFolder("FOLDER-2");
        assertFalse(folder2.exists());
        folder2.create(FolderType.HOLDS_MESSAGES);
        folder2.open(OpenMode.READ_WRITE, null);
        
        // read and write, look for independent storage
        mFolder.setUnreadMessageCount(400);
        folder2.setUnreadMessageCount(425);
        
        mFolder.close(false);
        folder2.close(false);
        mFolder.open(OpenMode.READ_WRITE, null);
        folder2.open(OpenMode.READ_WRITE, null);
        
        assertEquals(400, mFolder.getUnreadMessageCount());
        assertEquals(425, folder2.getUnreadMessageCount());
    }
    
    /**
     * Test unread messages count - concurrent access via two folder objects
     */
    public void testUnreadMessagesConcurrent() throws MessagingException {
        mFolder.open(OpenMode.READ_WRITE, null);
        
        // set up a 2nd folder to confirm concurrent access
        LocalStore.LocalFolder folder2 = (LocalStore.LocalFolder) mStore.getFolder(FOLDER_NAME);
        assertTrue(folder2.exists());
        folder2.open(OpenMode.READ_WRITE, null);
        
        // read and write, look for concurrent storage
        mFolder.setUnreadMessageCount(450);
        assertEquals(450, folder2.getUnreadMessageCount());
    }
    
    /**
     * Test visible limits support
     */
    public void testReadWriteVisibleLimits() throws MessagingException {
        mFolder.open(OpenMode.READ_WRITE, null);

        // set up a 2nd folder to confirm independent storage
        LocalStore.LocalFolder folder2 = (LocalStore.LocalFolder) mStore.getFolder("FOLDER-2");
        assertFalse(folder2.exists());
        folder2.create(FolderType.HOLDS_MESSAGES);
        folder2.open(OpenMode.READ_WRITE, null);
        
        // read and write, look for independent storage
        mFolder.setVisibleLimit(100);
        folder2.setVisibleLimit(200);
        
        mFolder.close(false);
        folder2.close(false);
        mFolder.open(OpenMode.READ_WRITE, null);
        folder2.open(OpenMode.READ_WRITE, null);
        
        assertEquals(100, mFolder.getVisibleLimit());
        assertEquals(200, folder2.getVisibleLimit());
    }
    
    /**
     * Test visible limits support - concurrent access via two folder objects
     */
    public void testVisibleLimitsConcurrent() throws MessagingException {
        mFolder.open(OpenMode.READ_WRITE, null);
        
        // set up a 2nd folder to confirm concurrent access
        LocalStore.LocalFolder folder2 = (LocalStore.LocalFolder) mStore.getFolder(FOLDER_NAME);
        assertTrue(folder2.exists());
        folder2.open(OpenMode.READ_WRITE, null);
        
        // read and write, look for concurrent storage
        mFolder.setVisibleLimit(300);
        assertEquals(300, folder2.getVisibleLimit());
    }
    
    /**
     * Test reset limits support
     */
    public void testResetVisibleLimits() throws MessagingException {
        mFolder.open(OpenMode.READ_WRITE, null);

        // set up a 2nd folder to confirm independent storage
        LocalStore.LocalFolder folder2 = (LocalStore.LocalFolder) mStore.getFolder("FOLDER-2");
        assertFalse(folder2.exists());
        folder2.create(FolderType.HOLDS_MESSAGES);
        folder2.open(OpenMode.READ_WRITE, null);
        
        // read and write, look for independent storage
        mFolder.setVisibleLimit(100);
        folder2.setVisibleLimit(200);
        
        mFolder.close(false);
        folder2.close(false);
        mFolder.open(OpenMode.READ_WRITE, null);
        folder2.open(OpenMode.READ_WRITE, null);
        
        mStore.resetVisibleLimits(Email.VISIBLE_LIMIT_DEFAULT);
        assertEquals(Email.VISIBLE_LIMIT_DEFAULT, mFolder.getVisibleLimit());
        assertEquals(Email.VISIBLE_LIMIT_DEFAULT, folder2.getVisibleLimit());
        
        mFolder.close(false);
        folder2.close(false);
    }
    
    /**
     * Lightweight test to confirm that LocalStore hasn't implemented any folder roles yet.
     */
    public void testNoFolderRolesYet() throws MessagingException {
        Folder[] localFolders = mStore.getPersonalNamespaces();
        for (Folder folder : localFolders) {
            assertEquals(Folder.FolderRole.UNKNOWN, folder.getRole()); 
        }
    }
    
    /**
     * Test missing folder (on open).  This should succeed because open will create it.
     */
    public void testMissingFolderOpen() throws MessagingException {
        Folder noFolder = mStore.getFolder(MISSING_FOLDER_NAME);
        noFolder.open(OpenMode.READ_WRITE, null);
        noFolder.close(false);
    }

    /**
     * Test missing folder (on getMessageCount).  This should not fail - it should return zero,
     * which is the actual count of messages in that folder.
     */
    public void testMissingFolderGetMessageCount() throws MessagingException {
        Folder noFolder = mStore.getFolder(MISSING_FOLDER_NAME);
        noFolder.open(OpenMode.READ_WRITE, null);
        
        // Now delete it behind its back
        Folder noFolder2 = mStore.getFolder(MISSING_FOLDER_NAME);
        noFolder2.delete(true);
        
        // Now try the call on the first instance
        int count = noFolder.getMessageCount();
        assertEquals(0, count);
    }

    /**
     * Test missing folder (on getUnreadMessageCount).  This should fail because we delete the 
     * open folder, simulating multi-threading behavior.
     */
    public void testMissingFolderGetUnreadMessageCount() throws MessagingException {
        Folder noFolder = mStore.getFolder(MISSING_FOLDER_NAME);
        noFolder.open(OpenMode.READ_WRITE, null);
        
        // Now delete it behind its back
        Folder noFolder2 = mStore.getFolder(MISSING_FOLDER_NAME);
        noFolder2.delete(true);
        
        // Now try the call on the first instance
        try {
            noFolder.getUnreadMessageCount();
            fail("MessagingException expected");
        } catch (MessagingException me) {
            // OK - success.
        }
    }

    /**
     * Test missing folder (on getVisibleLimit).  This should fail because we delete the 
     * open folder, simulating multi-threading behavior.
     */
    public void testMissingFolderGetVisibleLimit() throws MessagingException {
        LocalStore.LocalFolder noFolder = 
                (LocalStore.LocalFolder) mStore.getFolder(MISSING_FOLDER_NAME);
        noFolder.open(OpenMode.READ_WRITE, null);
        
        // Now delete it behind its back
        Folder noFolder2 = mStore.getFolder(MISSING_FOLDER_NAME);
        noFolder2.delete(true);
        
        // Now try the call on the first instance
        try {
            noFolder.getVisibleLimit();
            fail("MessagingException expected");
        } catch (MessagingException me) {
            // OK - success.
        }
    }

    /**
     * Test for setExtendedHeader() and getExtendedHeader()  
     */
    public void testExtendedHeader() throws MessagingException {
        MimeMessage message = new MimeMessage();
        message.setUid("message1");
        mFolder.appendMessages(new Message[] { message });

        message.setUid("message2");
        message.setExtendedHeader("X-Header1", "value1");
        message.setExtendedHeader("X-Header2", "value2\r\n value3\n value4\r\n");
        mFolder.appendMessages(new Message[] { message });
        
        LocalMessage message1 = (LocalMessage) mFolder.getMessage("message1");
        assertNull("none existent header", message1.getExtendedHeader("X-None-Existent"));
        
        LocalMessage message2 = (LocalMessage) mFolder.getMessage("message2");
        assertEquals("header 1", "value1", message2.getExtendedHeader("X-Header1"));
        assertEquals("header 2", "value2 value3 value4", message2.getExtendedHeader("X-Header2"));
        assertNull("header 3", message2.getExtendedHeader("X-Header3"));
    }
    
    /**
     * Tests for database version.
     */
    public void testDbVersion() throws MessagingException, URISyntaxException {
        // build current version database.
        LocalStore.newInstance(mLocalStoreUri, getContext(), null);
        final URI uri = new URI(mLocalStoreUri);
        final String dbPath = uri.getPath();
        final SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);

        // database version should be latest.
        assertEquals("database version should be latest", DATABASE_VERSION, db.getVersion());
        db.close();
    }
    
    /**
     * Helper function convert Cursor data to ContentValues
     */
    private ContentValues cursorToContentValues(Cursor c, String[] schema) {
        if (c.getColumnCount() != schema.length) {
            throw new IndexOutOfBoundsException("schema length is not mach with cursor columns");
        }
        
        final ContentValues cv = new ContentValues();
        for (int i = 0, count = c.getColumnCount(); i < count; ++i) {
            final String key = c.getColumnName(i);
            final String type = schema[i];
            if (type == "text") {
                cv.put(key, c.getString(i));
            } else if (type == "integer" || type == "primary") {
                cv.put(key, c.getLong(i));
            } else if (type == "numeric" || type == "real") {
                cv.put(key, c.getDouble(i));
            } else if (type == "blob") {
                cv.put(key, c.getBlob(i));
            } else {
                throw new IllegalArgumentException("unsupported type at index " + i);
            }
        }
        return cv;
    }
    
    /**
     * Helper function to read out Cursor columns
     */
    private HashSet<String> cursorToColumnNames(Cursor c) {
        HashSet<String> result = new HashSet<String>();
        for (int i = 0, count = c.getColumnCount(); i < count; ++i) {
            result.add(c.getColumnName(i));
        }
        return result;
    }
    
    /**
     * Tests for database upgrade from version 18 to current version.
     */
    public void testDbUpgrade18ToLatest() throws MessagingException, URISyntaxException {
        final URI uri = new URI(mLocalStoreUri);
        final String dbPath = uri.getPath();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);

        // create sample version 18 db tables
        createSampleDb(db, 18);

        // sample message data and expected data
        final ContentValues initialMessage = new ContentValues();
        initialMessage.put("folder_id", (long) 2);        // folder_id type integer == Long
        initialMessage.put("internal_date", (long) 3);    // internal_date type integer == Long
        final ContentValues expectedMessage = new ContentValues(initialMessage);
        expectedMessage.put("id", db.insert("messages", null, initialMessage));

        // sample attachment data and expected data
        final ContentValues initialAttachment = new ContentValues();
        initialAttachment.put("message_id", (long) 4);    // message_id type integer == Long
        initialAttachment.put("mime_type", (String) "a"); // mime_type type text == String
        final ContentValues expectedAttachment = new ContentValues(initialAttachment);
        expectedAttachment.put("id", db.insert("attachments", null, initialAttachment));
        db.close();

        // upgrade database 18 to latest
        LocalStore.newInstance(mLocalStoreUri, getContext(), null);

        // added message_id column should be initialized as null
        expectedMessage.put("message_id", (String) null);    // message_id type text == String
        // added content_id column should be initialized as null
        expectedAttachment.put("content_id", (String) null); // content_id type text == String

        // database should be upgraded
        db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
        assertEquals("database should be upgraded", DATABASE_VERSION, db.getVersion());
        Cursor c;
        
        // check for all "latest version" tables
        checkAllTablesFound(db);

        // check message table
        c = db.query("messages",
                new String[] { "id", "folder_id", "internal_date", "message_id" },
                null, null, null, null, null);
        // check if data is available
        assertTrue("messages table should have one data", c.moveToNext());
        
        // check if data are expected
        final ContentValues actualMessage = cursorToContentValues(c,
                new String[] { "primary", "integer", "integer", "text" });
        assertEquals("messages table cursor does not have expected values",
                expectedMessage, actualMessage);
        c.close();

        // check attachment table
        c = db.query("attachments",
                new String[] { "id", "message_id", "mime_type", "content_id" },
                null, null, null, null, null);
        // check if data is available
        assertTrue("attachments table should have one data", c.moveToNext());

        // check if data are expected
        final ContentValues actualAttachment = cursorToContentValues(c,
                new String[] { "primary", "integer", "text", "text" });
        assertEquals("attachment table cursor does not have expected values",
                expectedAttachment, actualAttachment);
        c.close();

        db.close();
    }

    /**
     * Tests for database upgrade from version 19 to current version.
     */
    public void testDbUpgrade19ToLatest() throws MessagingException, URISyntaxException {
        final URI uri = new URI(mLocalStoreUri);
        final String dbPath = uri.getPath();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);

        // create sample version 19 db tables
        createSampleDb(db, 19);

        // sample message data and expected data
        final ContentValues initialMessage = new ContentValues();
        initialMessage.put("folder_id", (long) 2);      // folder_id type integer == Long
        initialMessage.put("internal_date", (long) 3);  // internal_date integer == Long
        initialMessage.put("message_id", (String) "x"); // message_id text == String
        final ContentValues expectedMessage = new ContentValues(initialMessage);
        expectedMessage.put("id", db.insert("messages", null, initialMessage));

        // sample attachment data and expected data
        final ContentValues initialAttachment = new ContentValues();
        initialAttachment.put("message_id", (long) 4);  // message_id type integer == Long
        initialAttachment.put("mime_type", (String) "a"); // mime_type type text == String
        final ContentValues expectedAttachment = new ContentValues(initialAttachment);
        expectedAttachment.put("id", db.insert("attachments", null, initialAttachment));
        
        db.close();

        // upgrade database 19 to latest
        LocalStore.newInstance(mLocalStoreUri, getContext(), null);

        // added content_id column should be initialized as null
        expectedAttachment.put("content_id", (String) null);  // content_id type text == String

        // database should be upgraded
        db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
        assertEquals("database should be upgraded", DATABASE_VERSION, db.getVersion());
        Cursor c;

        // check for all "latest version" tables
        checkAllTablesFound(db);

        // check message table
        c = db.query("messages",
                new String[] { "id", "folder_id", "internal_date", "message_id" },
                null, null, null, null, null);
        // check if data is available
        assertTrue("attachments table should have one data", c.moveToNext());

        // check if data are expected
        final ContentValues actualMessage = cursorToContentValues(c,
                new String[] { "primary", "integer", "integer", "text" });
        assertEquals("messages table cursor does not have expected values",
                expectedMessage, actualMessage);

        // check attachment table
        c = db.query("attachments",
                new String[] { "id", "message_id", "mime_type", "content_id" },
                null, null, null, null, null);
        // check if data is available
        assertTrue("attachments table should have one data", c.moveToNext());

        // check if data are expected
        final ContentValues actualAttachment = cursorToContentValues(c,
                        new String[] { "primary", "integer", "text", "text" });
        assertEquals("attachment table cursor does not have expected values",
                expectedAttachment, actualAttachment);

        db.close();
    }
    
    /**
     * Check upgrade from db version 20 to latest
     */
    public void testDbUpgrade20ToLatest() throws MessagingException, URISyntaxException {
        final URI uri = new URI(mLocalStoreUri);
        final String dbPath = uri.getPath();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);

        // create sample version 20 db tables
        createSampleDb(db, 20);
        db.close();

        // upgrade database 20 to latest
        LocalStore.newInstance(mLocalStoreUri, getContext(), null);

        // database should be upgraded
        db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
        assertEquals("database should be upgraded", DATABASE_VERSION, db.getVersion());

        // check for all "latest version" tables
        checkAllTablesFound(db);
    }

    /**
     * Check upgrade from db version 21 to latest
     */
    public void testDbUpgrade21ToLatest() throws MessagingException, URISyntaxException {
        final URI uri = new URI(mLocalStoreUri);
        final String dbPath = uri.getPath();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);

        // create sample version 21 db tables
        createSampleDb(db, 21);
        db.close();

        // upgrade database 21 to latest
        LocalStore.newInstance(mLocalStoreUri, getContext(), null);

        // database should be upgraded
        db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
        assertEquals("database should be upgraded", DATABASE_VERSION, db.getVersion());

        // check for all "latest version" tables
        checkAllTablesFound(db);
    }

    /**
     * Check upgrade from db version 22 to latest.
     * Flags must be migrated to new columns.
     */
    public void testDbUpgrade22ToLatest() throws MessagingException, URISyntaxException {
        final URI uri = new URI(mLocalStoreUri);
        final String dbPath = uri.getPath();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);

        // create sample version 22 db tables
        createSampleDb(db, 22);
        
        // insert three messages, one for each migration flag
        final ContentValues inMessage1 = new ContentValues();
        inMessage1.put("message_id", (String) "x"); // message_id text == String
        inMessage1.put("flags", Flag.X_DOWNLOADED_FULL.toString());
        final ContentValues outMessage1 = new ContentValues(inMessage1);
        outMessage1.put("id", db.insert("messages", null, inMessage1));

        final ContentValues inMessage2 = new ContentValues();
        inMessage2.put("message_id", (String) "y"); // message_id text == String
        inMessage2.put("flags", Flag.X_DOWNLOADED_PARTIAL.toString());
        final ContentValues outMessage2 = new ContentValues(inMessage2);
        outMessage2.put("id", db.insert("messages", null, inMessage2));

        final ContentValues inMessage3 = new ContentValues();
        inMessage3.put("message_id", (String) "z"); // message_id text == String
        inMessage3.put("flags", Flag.DELETED.toString());
        final ContentValues outMessage3 = new ContentValues(inMessage3);
        outMessage3.put("id", db.insert("messages", null, inMessage3));

        db.close();

        // upgrade database 22 to latest
        LocalStore.newInstance(mLocalStoreUri, getContext(), null);

        // database should be upgraded
        db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
        assertEquals("database should be upgraded", DATABASE_VERSION, db.getVersion());

        // check for all "latest version" tables
        checkAllTablesFound(db);
        
        // check message table for migrated flags
        String[] columns = new String[] { "id", "message_id", "flags", 
                "flag_downloaded_full", "flag_downloaded_partial", "flag_deleted" };
        Cursor c = db.query("messages", columns, null, null, null, null, null);
        
        for (int msgNum = 0; msgNum <= 2; ++msgNum) {
            assertTrue(c.moveToNext());
            ContentValues actualMessage = cursorToContentValues(c,
                    new String[] { "primary", "text", "text", "integer", "integer", "integer" });
            String messageId = actualMessage.getAsString("message_id");
            int outDlFull = actualMessage.getAsInteger("flag_downloaded_full");
            int outDlPartial = actualMessage.getAsInteger("flag_downloaded_partial");
            int outDeleted = actualMessage.getAsInteger("flag_deleted");
            if ("x".equals(messageId)) {
                assertTrue("converted flag_downloaded_full",
                        outDlFull == 1 && outDlPartial == 0 && outDeleted == 0);
            } else if ("y".equals(messageId)) {
                assertTrue("converted flag_downloaded_partial",
                        outDlFull == 0 && outDlPartial == 1 && outDeleted == 0);
            } else if ("z".equals(messageId)) {
                assertTrue("converted flag_deleted",
                        outDlFull == 0 && outDlPartial == 0 && outDeleted == 1);
            }
        }
        c.close();
    }

    /**
     * Tests for database upgrade from version 23 to current version.
     */
    public void testDbUpgrade23ToLatest() throws MessagingException, URISyntaxException {
        final URI uri = new URI(mLocalStoreUri);
        final String dbPath = uri.getPath();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);

        // create sample version 23 db tables
        createSampleDb(db, 23);

        // sample message data and expected data
        final ContentValues initialMessage = new ContentValues();
        initialMessage.put("folder_id", (long) 2);        // folder_id type integer == Long
        initialMessage.put("internal_date", (long) 3);    // internal_date type integer == Long
        final ContentValues expectedMessage = new ContentValues(initialMessage);
        expectedMessage.put("id", db.insert("messages", null, initialMessage));

        db.close();

        // upgrade database 23 to latest
        LocalStore.newInstance(mLocalStoreUri, getContext(), null);

        // added message_id column should be initialized as null
        expectedMessage.put("message_id", (String) null);    // message_id type text == String

        // database should be upgraded
        db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
        assertEquals("database should be upgraded", DATABASE_VERSION, db.getVersion());
        Cursor c;
        
        // check for all "latest version" tables
        checkAllTablesFound(db);

        // check message table
        c = db.query("messages",
                new String[] { "id", "folder_id", "internal_date", "message_id" },
                null, null, null, null, null);
        // check if data is available
        assertTrue("messages table should have one data", c.moveToNext());
        
        // check if data are expected
        final ContentValues actualMessage = cursorToContentValues(c,
                new String[] { "primary", "integer", "integer", "text" });
        assertEquals("messages table cursor does not have expected values",
                expectedMessage, actualMessage);
        c.close();

        db.close();
    }

   /**
     * Checks the database to confirm that all tables, with all expected columns are found.
     */
    private void checkAllTablesFound(SQLiteDatabase db) {
        Cursor c;
        HashSet<String> foundNames;
        ArrayList<String> expectedNames;
        
        // check for up-to-date messages table
        c = db.query("messages",
                null,
                null, null, null, null, null);
        foundNames = cursorToColumnNames(c);
        expectedNames = new ArrayList<String>(Arrays.asList(
                new String[]{ "id", "folder_id", "uid", "subject", "date", "flags", "sender_list",
                        "to_list", "cc_list", "bcc_list", "reply_to_list",
                        "html_content", "text_content", "attachment_count",
                        "internal_date", "store_flag_1", "store_flag_2", "flag_downloaded_full",
                        "flag_downloaded_partial", "flag_deleted", "x_headers" }
                ));
        assertTrue("messages", foundNames.containsAll(expectedNames));
        
        // check for up-to-date attachments table
        c = db.query("attachments",
                null,
                null, null, null, null, null);
        foundNames = cursorToColumnNames(c);
        expectedNames = new ArrayList<String>(Arrays.asList(
                new String[]{ "id", "message_id",
                        "store_data", "content_uri", "size", "name",
                        "mime_type", "content_id" }
                ));
        assertTrue("attachments", foundNames.containsAll(expectedNames));
        
        // check for up-to-date remote_store_data table
        c = db.query("remote_store_data",
                null,
                null, null, null, null, null);
        foundNames = cursorToColumnNames(c);
        expectedNames = new ArrayList<String>(Arrays.asList(
                new String[]{ "id", "folder_id", "data_key", "data" }
                ));
        assertTrue("remote_store_data", foundNames.containsAll(expectedNames));
    }

    private static void createSampleDb(SQLiteDatabase db, int version) {
        db.execSQL("DROP TABLE IF EXISTS messages");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, folder_id INTEGER, " +
                   "uid TEXT, subject TEXT, date INTEGER, flags TEXT, sender_list TEXT, " +
                   "to_list TEXT, cc_list TEXT, bcc_list TEXT, reply_to_list TEXT, " +
                   "html_content TEXT, text_content TEXT, attachment_count INTEGER, " +
                   "internal_date INTEGER" +
                   ((version >= 19) ? ", message_id TEXT" : "") +
                   ((version >= 22) ? ", store_flag_1 INTEGER, store_flag_2 INTEGER" : "") +
                   ((version >= 23) ? 
                           ", flag_downloaded_full INTEGER, flag_downloaded_partial INTEGER" : "") +
                   ((version >= 23) ? ", flag_deleted INTEGER" : "") +
                   ((version >= 24) ? ", x_headers TEXT" : "") +
                   ")");
        db.execSQL("DROP TABLE IF EXISTS attachments");
        db.execSQL("CREATE TABLE attachments (id INTEGER PRIMARY KEY, message_id INTEGER," +
                   "store_data TEXT, content_uri TEXT, size INTEGER, name TEXT," +
                   "mime_type TEXT" +
                   ((version >= 20) ? ", content_id" : "") +
                   ")");
        
        if (version >= 21) {
            db.execSQL("DROP TABLE IF EXISTS remote_store_data");
            db.execSQL("CREATE TABLE remote_store_data "
                    + "(id INTEGER PRIMARY KEY, folder_id INTEGER, "
                    + "data_key TEXT, data TEXT)");
        }
        
        db.setVersion(version);
    }
}
