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
import com.android.email.mail.MessageRetrievalListener;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Folder.OpenMode;
import com.android.email.mail.Message.RecipientType;
import com.android.email.mail.internet.BinaryTempFileBody;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.internet.TextBody;

import android.app.Application;
import android.test.AndroidTestCase;
import android.test.mock.MockApplication;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.File;
import java.net.URI;

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
        BinaryTempFileBody.setTempDirectory(this.getContext().getCacheDir());
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
    

}

