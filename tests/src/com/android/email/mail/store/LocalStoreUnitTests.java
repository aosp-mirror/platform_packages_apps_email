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
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
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
        
        mStore = new LocalStore(mLocalStoreUri, getContext());
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
        mFolder.open(OpenMode.READ_WRITE);
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
        mFolder.open(OpenMode.READ_WRITE);
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
     * Tests for database version.
     */
    public void testDbVersion() throws MessagingException, URISyntaxException {
        final LocalStore store = new LocalStore(mLocalStoreUri, getContext());
        final URI uri = new URI(mLocalStoreUri);
        final String dbPath = uri.getPath();
        final SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);

        // database version should be latest.
        assertEquals("database version should be latest", 20, db.getVersion());
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
     * Tests for database upgrade from version 18 to version 20.
     */
    public void testDbUpgrade18To20() throws MessagingException, URISyntaxException {
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

        // upgrade database 18 to 20
        new LocalStore(mLocalStoreUri, getContext());

        // added message_id column should be initialized as null
        expectedMessage.put("message_id", (String) null);    // message_id type text == String
        // added content_id column should be initialized as null
        expectedAttachment.put("content_id", (String) null); // content_id type text == String

        // database should be upgraded
        db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
        assertEquals("database should be upgraded", 20, db.getVersion());
        Cursor c;

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
     * Tests for database upgrade from version 19 to version 20.
     */
    public void testDbUpgrade19To20() throws MessagingException, URISyntaxException {
        final URI uri = new URI(mLocalStoreUri);
        final String dbPath = uri.getPath();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);

        // create minimu version 18 db tables
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

        // upgrade database 19 to 20
        new LocalStore(mLocalStoreUri, getContext());

        // added content_id column should be initialized as null
        expectedAttachment.put("content_id", (String) null);  // content_id type text == String

        // database should be upgraded
        db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
        assertEquals(20, db.getVersion());
        Cursor c;

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
        db.setVersion(version);
    }
}
