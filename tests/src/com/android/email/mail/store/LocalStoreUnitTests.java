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

import com.android.email.mail.Address;
import com.android.email.mail.Folder;
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Folder.FolderType;
import com.android.email.mail.Folder.OpenMode;
import com.android.email.mail.Message.RecipientType;
import com.android.email.mail.internet.BinaryTempFileBody;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.internet.TextBody;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/**
 * This is a series of unit tests for the LocalStore class.
 */
@SmallTest
public class LocalStoreUnitTests extends AndroidTestCase {
    
    private final String dbName = "com.android.email.mail.store.LocalStoreUnitTests.db";

    private static final String SENDER = "sender@android.com";
    private static final String RECIPIENT_TO = "recipient-to@android.com";
    private static final String SUBJECT = "This is the subject";
    private static final String BODY = "This is the body.  This is also the body.";
    private static final String MESSAGE_ID = "Test-Message-ID";
    private static final String MESSAGE_ID_2 = "Test-Message-ID-Second";
    
    private static final int DATABASE_VERSION = 21;
    
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
        
        // These are needed so we can get at the inner classes
        // Create a dummy database (be sure to delete it in tearDown())
        mLocalStoreUri = "local://localhost/" + getContext().getDatabasePath(dbName);
        
        mStore = (LocalStore) LocalStore.newInstance(mLocalStoreUri, getContext(), null);
        mFolder = (LocalStore.LocalFolder) mStore.getFolder("TEST");
        
        // This is needed for parsing mime messages
        mCacheDir = getContext().getCacheDir();
        BinaryTempFileBody.setTempDirectory(mCacheDir);
    }
    
    /**
     * Teardown code.  Delete the local database and any other files
     */
    @Override
    protected void tearDown() throws Exception {
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
        assertEquals(MESSAGE_ID, retrieved.getMessageId());
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
        assertEquals(MESSAGE_ID_2, retrieved.getMessageId());
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
     * Test functionality of setting & saving store persistence values
     */
    public void testPersistentStorage() throws MessagingException {
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
                        "internal_date" }
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
