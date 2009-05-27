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

import java.util.ArrayList;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

/**
 * 
 * Conventions used in naming columns:
 *   RECORD_ID is the primary key for all Email records
 *   The SyncColumns interface is used by all classes that are synced to the server directly 
 *   (Mailbox and Email)
 *   
 *   <name>_KEY always refers to a foreign key
 *   <name>_ID always refers to a unique identifier (whether on client, server, etc.)
 *
 */

public class EmailStore {
    //private static final String TAG = "Email";
    public static final String AUTHORITY = EmailProvider.EMAIL_AUTHORITY;
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    
    /**
     * no public constructor since this is a utility class
     */
    private EmailStore() {
    }

    // All classes share this
    public static final String RECORD_ID = "_id";
    
    public interface SyncColumns {
        // source (account name and type) : foreign key into the AccountsProvider
        public static final String ACCOUNT_KEY = "syncAccountKey";
        // source id (string) : the source's name of this item
        public static final String SERVER_ID = "syncServerId";
        // source version (string) : the source's concept of the version of this item
        public static final String SERVER_VERSION = "syncServerVersion";
        // source sync data (string) : any other data needed to sync this item back to the source
        public static final String DATA = "syncData";
        // dirty count (boolean) : the number of times this item has changed since the last time it
        // was synced to the server
        public static final String DIRTY_COUNT = "syncDirtyCount";
   }
    
    public interface BodyColumns {
        // Foreign key to the message corresponding to this body
        public static final String MESSAGE_KEY = "messageKey";      
        // The html content itself
        public static final String HTML_CONTENT = "htmlContent";    
        // The plain text content itself
        public static final String TEXT_CONTENT = "textContent";    
    }

    public static final class Body implements BodyColumns {
        /**
         * no public constructor since this is a utility class
         */
        private Body() {
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse(EmailStore.CONTENT_URI + "/body");
    }

    public interface MessageColumns {
        // Basic columns used in message list presentation
        // The name as shown to the user in a message list
        public static final String DISPLAY_NAME = "displayName";    
        // The time (millis) as shown to the user in a message list [INDEX]
        public static final String TIMESTAMP = "timeStamp";         
        // Message subject
        public static final String SUBJECT = "subject";             
        // A preview, as might be shown to the user in a message list
        public static final String PREVIEW = "preview";             
        // Boolean, unread = 0, read = 1 [INDEX]
        public static final String FLAG_READ = "flagRead";          
        // Three state, unloaded = 0, loaded = 1, partially loaded (optional) = 2 [INDEX]
        public static final String FLAG_LOADED = "flagLoaded";      
        // Boolean, unflagged = 0, flagged (favorite) = 1
        public static final String FLAG_FAVORITE = "flagFavorite";  
        // Boolean, no attachment = 0, attachment = 1
        public static final String FLAG_ATTACHMENT = "flagAttachment";  
        // Bit field, e.g. replied, deleted
        public static final String FLAGS = "flags";                 
        
        // Body related
        // charset: U = us-ascii; 8 = utf-8; I = iso-8559-1; others literally (e.g. KOI8-R)
        // encodings: B = base64; Q = quoted printable; X = none
        // Information about the text part (if any) in form <location>;<encoding>;<charset>;<length>
        public static final String TEXT_INFO = "textInfo";          
        // Information about the html part (if any) in form <location>;<encoding>;<charset>;<length>
        public static final String HTML_INFO = "htmlInfo";          
        // Foreign key to the Body content of this message (text and/or html)
        public static final String BODY_ID = "bodyId";              
        
        // Sync related identifiers
        // Any client-required identifier
        public static final String CLIENT_ID = "clientId";          
        // The message-id in the message's header
        public static final String MESSAGE_ID = "messageId";        
        // Thread identifier
        public static final String THREAD_ID = "threadId";
 
        // References to other Email objects in the database
        // Foreign key to the Mailbox holding this message [INDEX]
        public static final String MAILBOX_KEY = "mailboxKey";      
        // Foreign key to the Account holding this message
        public static final String ACCOUNT_KEY = "accountKey";      
        // Foreign key to a referenced Message (e.g. for a reply/forward)
        public static final String REFERENCE_KEY = "referenceKey";  
                
        // Address lists, of the form <address> [, <address> ...]
        public static final String SENDER_LIST = "senderList";
        public static final String FROM_LIST = "fromList";
        public static final String TO_LIST = "toList";
        public static final String CC_LIST = "ccList";
        public static final String BCC_LIST = "bccList";
        public static final String REPLY_TO_LIST = "replyToList";
      }
    
    public static final class Message extends EmailContent implements SyncColumns, MessageColumns {
           
        public static final String KEY_TIMESTAMP_DESC = MessageColumns.TIMESTAMP + " desc";

        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
        public static final int CONTENT_TIMESTAMP_COLUMN = 2;
        public static final int CONTENT_SUBJECT_COLUMN = 3;
        public static final int CONTENT_PREVIEW_COLUMN = 4;
        public static final int CONTENT_FLAG_READ_COLUMN = 5;
        public static final int CONTENT_FLAG_LOADED_COLUMN = 6;
        public static final int CONTENT_FLAG_FAVORITE_COLUMN = 7;
        public static final int CONTENT_FLAG_ATTACHMENT_COLUMN = 8;
        public static final int CONTENT_FLAGS_COLUMN = 9;
        public static final int CONTENT_TEXT_INFO_COLUMN = 10;
        public static final int CONTENT_HTML_INFO_COLUMN = 11;
        public static final int CONTENT_BODY_ID_COLUMN = 12;
        public static final int CONTENT_SERVER_ID_COLUMN = 13;
        public static final int CONTENT_CLIENT_ID_COLUMN = 14;
        public static final int CONTENT_MESSAGE_ID_COLUMN = 15;
        public static final int CONTENT_THREAD_ID_COLUMN = 16;
        public static final int CONTENT_MAILBOX_KEY_COLUMN = 17;
        public static final int CONTENT_ACCOUNT_KEY_COLUMN = 18;
        public static final int CONTENT_REFERENCE_KEY_COLUMN = 19;
        public static final int CONTENT_SENDER_LIST_COLUMN = 20;
        public static final int CONTENT_FROM_LIST_COLUMN = 21;
        public static final int CONTENT_TO_LIST_COLUMN = 22;
        public static final int CONTENT_CC_LIST_COLUMN = 23;
        public static final int CONTENT_BCC_LIST_COLUMN = 24;
        public static final int CONTENT_REPLY_TO_COLUMN = 25;
        public static final String[] CONTENT_PROJECTION = new String[] { 
            RECORD_ID, MessageColumns.DISPLAY_NAME, MessageColumns.TIMESTAMP, 
            MessageColumns.SUBJECT, MessageColumns.PREVIEW, MessageColumns.FLAG_READ,
            MessageColumns.FLAG_LOADED, MessageColumns.FLAG_FAVORITE,
            MessageColumns.FLAG_ATTACHMENT, MessageColumns.FLAGS, MessageColumns.TEXT_INFO,
            MessageColumns.HTML_INFO, MessageColumns.BODY_ID, SyncColumns.SERVER_ID,
            MessageColumns.CLIENT_ID, MessageColumns.MESSAGE_ID, MessageColumns.THREAD_ID,
            MessageColumns.MAILBOX_KEY, MessageColumns.ACCOUNT_KEY, MessageColumns.REFERENCE_KEY,
            MessageColumns.SENDER_LIST, MessageColumns.FROM_LIST, MessageColumns.TO_LIST,
            MessageColumns.CC_LIST, MessageColumns.BCC_LIST, MessageColumns.REPLY_TO_LIST
        };

        public static final int LIST_ID_COLUMN = 0;
        public static final int LIST_DISPLAY_NAME_COLUMN = 1;
        public static final int LIST_TIMESTAMP_COLUMN = 2;
        public static final int LIST_SUBJECT_COLUMN = 3;
        public static final int LIST_PREVIEW_COLUMN = 4;
        public static final int LIST_READ_COLUMN = 5;
        public static final int LIST_LOADED_COLUMN = 6;
        public static final int LIST_FAVORITE_COLUMN = 7;
        public static final int LIST_ATTACHMENT_COLUMN = 8;
        public static final int LIST_FLAGS_COLUMN = 9;
        public static final int LIST_MAILBOX_KEY_COLUMN = 10;
        public static final int LIST_ACCOUNT_KEY_COLUMN = 11;
        public static final int LIST_SERVER_ID_COLUMN = 12;
 
        // Public projection for common list columns
        public static final String[] LIST_PROJECTION = new String[] { 
            RECORD_ID, MessageColumns.DISPLAY_NAME, MessageColumns.TIMESTAMP,
            MessageColumns.SUBJECT, MessageColumns.PREVIEW, MessageColumns.FLAG_READ,
            MessageColumns.FLAG_LOADED, MessageColumns.FLAG_FAVORITE,
            MessageColumns.FLAG_ATTACHMENT, MessageColumns.FLAGS, MessageColumns.MAILBOX_KEY,
            MessageColumns.ACCOUNT_KEY , SyncColumns.SERVER_ID
        };
        
        public static final int LOAD_BODY_ID_COLUMN = 0;
        public static final int LOAD_BODY_SERVER_ID_COLUMN = 1;
        public static final int LOAD_BODY_TEXT_INFO_COLUMN = 2;
        public static final int LOAD_BODY_HTML_INFO_COLUMN  = 3;
        public static final String[] LOAD_BODY_PROJECTION = new String[] {
            RECORD_ID, SyncColumns.SERVER_ID, MessageColumns.TEXT_INFO, MessageColumns.HTML_INFO
        };
        
        public static final int ID_COLUMNS_ID_COLUMN = 0;
        public static final int ID_COLUMNS_SYNC_SERVER_ID = 1;
        public static final String[] ID_COLUMNS_PROJECTION = new String[] {
            RECORD_ID, SyncColumns.SERVER_ID
        };
        
        public static final String[] ID_COLUMN_PROJECTION = new String[] { RECORD_ID };
        
        // _id field is in AbstractContent
        public String displayName;
        public long timeStamp;
        public String subject;
        public String preview;
        public boolean flagRead = false;
        public int flagLoaded = 0;
        public boolean flagFavorite = false;
        public boolean flagAttachment = false;
        public int flags = 0;
        
        public String textInfo;
        public String htmlInfo;
        
        public String serverId;
        public int serverIntId;
        public String clientId;
        public String messageId;
        public String threadId;
        
        public long bodyKey;
        public long mailboxKey;
        public long accountKey;
        public long referenceKey;
        
        public String sender;
        public String from;
        public String to;
        public String cc;
        public String bcc;
        public String replyTo;
        
        // THROW THIS AWAY; use tempObject
        transient public String temp;
        
        // Can be used while building messages, but is NOT saved by the Provider
        transient public ArrayList<Attachment> attachments = null;
        
        public static final int UNREAD = 0;
        public static final int READ = 1;
        public static final int DELETED = 2;
        
        public static final int NOT_LOADED = 0;
        public static final int LOADED = 1;
        public static final int PARTIALLY_LOADED = 2;
        
        /**
         * no public constructor since this is a utility class
         */
        public Message() {
             baseUri = CONTENT_URI;
        }
        
        public static final String TABLE_NAME = "Message";
        public static final String DELETED_TABLE_NAME = "Deleted_Message";
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse(EmailStore.CONTENT_URI + "/message");
        
        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();

            // Assign values for each row.
            values.put(MessageColumns.DISPLAY_NAME, displayName);
            values.put(MessageColumns.TIMESTAMP, timeStamp);
            values.put(MessageColumns.SUBJECT, subject);
            values.put(MessageColumns.FLAG_READ, flagRead); 
            values.put(MessageColumns.FLAG_LOADED, flagLoaded); 
            values.put(MessageColumns.FLAG_FAVORITE, flagFavorite); 
            values.put(MessageColumns.FLAG_ATTACHMENT, flagAttachment); 
            values.put(MessageColumns.FLAGS, flags);
            
            values.put(MessageColumns.TEXT_INFO, textInfo);
            values.put(MessageColumns.HTML_INFO, htmlInfo);
            
            if (serverId != null)
                values.put(SyncColumns.SERVER_ID, serverId);
            else
                values.put(SyncColumns.SERVER_ID, serverIntId);
            
            values.put(MessageColumns.CLIENT_ID, clientId);
            values.put(MessageColumns.MESSAGE_ID, messageId);

            values.put(MessageColumns.BODY_ID, bodyKey);
            values.put(MessageColumns.MAILBOX_KEY, mailboxKey);
            values.put(MessageColumns.ACCOUNT_KEY, accountKey);
            values.put(MessageColumns.REFERENCE_KEY, referenceKey);
            
            values.put(MessageColumns.SENDER_LIST, sender);
            values.put(MessageColumns.FROM_LIST, from);
            values.put(MessageColumns.TO_LIST, to);
            values.put(MessageColumns.CC_LIST, cc);
            values.put(MessageColumns.BCC_LIST, bcc);
            values.put(MessageColumns.REPLY_TO_LIST, replyTo);
 
            return values;
        }

        static void createTable(SQLiteDatabase db) {
            String s = " (" + RECORD_ID + " integer primary key autoincrement, " 
            + SyncColumns.ACCOUNT_KEY + " integer, "
            + SyncColumns.SERVER_ID + " integer, "
            + SyncColumns.SERVER_VERSION + " integer, "
            + SyncColumns.DATA + " text, "
            + SyncColumns.DIRTY_COUNT + " integer, "
            + MessageColumns.DISPLAY_NAME + " text, " 
            + MessageColumns.TIMESTAMP + " integer, " 
            + MessageColumns.SUBJECT + " text, " 
            + MessageColumns.PREVIEW + " text, "
            + MessageColumns.FLAG_READ + " integer, " 
            + MessageColumns.FLAG_LOADED + " integer, " 
            + MessageColumns.FLAG_FAVORITE + " integer, " 
            + MessageColumns.FLAG_ATTACHMENT + " integer, " 
            + MessageColumns.FLAGS + " integer, "
            + MessageColumns.TEXT_INFO + " text, "
            + MessageColumns.HTML_INFO + " text, "
            + MessageColumns.CLIENT_ID + " integer, "
            + MessageColumns.MESSAGE_ID + " text, "
            + MessageColumns.THREAD_ID + " text, "
            + MessageColumns.BODY_ID + " integer, "
            + MessageColumns.MAILBOX_KEY + " integer, "
            + MessageColumns.ACCOUNT_KEY + " integer, "
            + MessageColumns.REFERENCE_KEY + " integer, "
            + MessageColumns.SENDER_LIST + " text, "
            + MessageColumns.FROM_LIST + " text, "
            + MessageColumns.TO_LIST + " text, "
            + MessageColumns.CC_LIST + " text, "
            + MessageColumns.BCC_LIST + " text, "
            + MessageColumns.REPLY_TO_LIST + " text"
            + ");";
            db.execSQL("create table " + TABLE_NAME + s);
            db.execSQL("create table " + DELETED_TABLE_NAME + s);
            db.execSQL("create index message_" + MessageColumns.TIMESTAMP 
                    + " on " + TABLE_NAME + " (" + MessageColumns.TIMESTAMP + ");");
            db.execSQL("create index message_" + MessageColumns.FLAG_READ 
                    + " on " + TABLE_NAME + " (" + MessageColumns.FLAG_READ + ");");
            db.execSQL("create index message_" + MessageColumns.FLAG_LOADED 
                    + " on " + TABLE_NAME + " (" + MessageColumns.FLAG_LOADED + ");");
            db.execSQL("create index message_" + MessageColumns.MAILBOX_KEY 
                    + " on " + TABLE_NAME + " (" + MessageColumns.MAILBOX_KEY + ");");
            db.execSQL("create index message_" + SyncColumns.SERVER_ID 
                    + " on " + TABLE_NAME + " (" + SyncColumns.SERVER_ID + ");");
        }

        static void upgradeTable(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("drop table " + TABLE_NAME);
            db.execSQL("drop table " + DELETED_TABLE_NAME);
            createTable(db);
        }
        
        public static Message restoreMessageWithId(Context context, long id) {
            Uri u = ContentUris.withAppendedId(Message.CONTENT_URI, id);
            Cursor c = context.getContentResolver().query(u, Message.CONTENT_PROJECTION,
                    null, null, null);

            try {
                if (c.moveToFirst())
                    return EmailContent.getContent(c, Message.class);
                else
                    return null;
            } finally {
                c.close();
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public EmailStore.Message restore(Cursor c) {
            baseUri = EmailStore.Message.CONTENT_URI;
            displayName = c.getString(CONTENT_DISPLAY_NAME_COLUMN);
            timeStamp = c.getLong(CONTENT_TIMESTAMP_COLUMN);
            subject = c.getString(CONTENT_SUBJECT_COLUMN);
            preview = c.getString(CONTENT_PREVIEW_COLUMN);
            flagRead = c.getInt(CONTENT_FLAG_READ_COLUMN) == 1;
            flagLoaded = c.getInt(CONTENT_FLAG_LOADED_COLUMN);
            flagFavorite = c.getInt(CONTENT_FLAG_FAVORITE_COLUMN) == 1;
            flagAttachment = c.getInt(CONTENT_FLAG_ATTACHMENT_COLUMN) == 1;
            flags = c.getInt(CONTENT_FLAGS_COLUMN);
            textInfo = c.getString(CONTENT_TEXT_INFO_COLUMN);
            htmlInfo = c.getString(CONTENT_HTML_INFO_COLUMN);
            serverId = c.getString(CONTENT_SERVER_ID_COLUMN);
            serverIntId = c.getInt(CONTENT_SERVER_ID_COLUMN);
            clientId = c.getString(CONTENT_CLIENT_ID_COLUMN);
            messageId = c.getString(CONTENT_MESSAGE_ID_COLUMN);
            threadId = c.getString(CONTENT_THREAD_ID_COLUMN);
            bodyKey = c.getLong(CONTENT_BODY_ID_COLUMN);
            mailboxKey = c.getLong(CONTENT_MAILBOX_KEY_COLUMN);
            accountKey = c.getLong(CONTENT_ACCOUNT_KEY_COLUMN);
            referenceKey = c.getLong(CONTENT_REFERENCE_KEY_COLUMN);
            sender = c.getString(CONTENT_SENDER_LIST_COLUMN);
            from = c.getString(CONTENT_FROM_LIST_COLUMN);
            to = c.getString(CONTENT_TO_LIST_COLUMN);
            cc = c.getString(CONTENT_CC_LIST_COLUMN);
            bcc = c.getString(CONTENT_BCC_LIST_COLUMN);
            replyTo = c.getString(CONTENT_REPLY_TO_COLUMN);
            return this;
        }
        
        public boolean update() {
            // TODO Auto-generated method stub
            return false;
        }
        
        // Text and Html information are stored as <location>;<encoding>;<charset>;<length>
        // charset: U = us-ascii; 8 = utf-8; I = iso-8559-1; others literally (e.g. KOI8-R)
        // encodings: B = base64; Q = quoted printable; X = none
        
        
        public static final class BodyInfo {
            public String location;
            public char encoding;
            public String charset;
            public long length;
            
            static public BodyInfo expandFromTextOrHtmlInfo (String info) {
                BodyInfo b = new BodyInfo();
                int start = 0;
                int next = info.indexOf(';');
                if (next > 0) {
                    b.location = info.substring(start, next);
                    start = next + 1;
                    next = info.indexOf(';', start);
                    if (next > 0) {
                        b.encoding = info.charAt(start);
                        start = next + 1;
                        next = info.indexOf(';', start);
                        if (next > 0) {
                            String cs = info.substring(start, next);
                            if (cs.equals("U"))
                                b.charset = "us-ascii";
                            else if (cs.equals("I"))
                                b.charset = "iso-8859-1";
                            else if (cs.equals("8"))
                                b.charset = "utf-8";
                            else
                                b.charset = cs;
                            start = next + 1;
                            b.length = Integer.parseInt(info.substring(start));
                            return b;
                        }
                    }
                    
                }
                return null;
            }
        }
   }

    public interface AccountColumns {
        // The display name of the account (user-settable)
        public static final String DISPLAY_NAME = "displayName";
        // The receive protocol used by this account (e.g. IMAP, POP3, and EAS for now)
        public static final String PROTOCOL = "protocol";
        // The email address corresponding to this account
        public static final String EMAIL_ADDRESS = "emailAddress";
        // A server-based sync key on an account-wide basis (EAS needs this)
        public static final String SYNC_KEY = "syncKey";
        // The default sync lookback period for this account
        public static final String SYNC_LOOKBACK = "syncLookback";
        // The default sync frequency for this account
        public static final String SYNC_FREQUENCY = "syncFrequency";
        // A foreign key into the account manager, having host, login, password, port, and ssl flags
        public static final String HOST_AUTH_KEY = "hostAuthKey";
        // (optional) A foreign key into the account manager, having host, login, password, port,
        // and ssl flags
        public static final String SMTP_HOST_AUTH_KEY = "smtpHostAuthKey";
        // Flags
        public static final String FLAGS = "flags";
     }

    public static final class Account extends EmailContent implements AccountColumns {
        public String displayName;
        public String protocol;
        public String emailAddress;
        public String syncKey;
        public int syncLookback;
        public int syncFrequency;
        public long hostAuthKey; 
        public long smtpHostAuthKey;
        public int flags;
        
        // Convenience for creating an account
        public transient HostAuth hostAuth;
        public transient HostAuth smtpHostAuth;
        
        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
        public static final int CONTENT_PROTOCOL_COLUMN = 2;
        public static final int CONTENT_EMAIL_ADDRESS_COLUMN = 3;
        public static final int CONTENT_SYNC_KEY_COLUMN = 4;
        public static final int CONTENT_SYNC_LOOKBACK_COLUMN = 5;
        public static final int CONTENT_SYNC_FREQUENCY_COLUMN = 6;
        public static final int CONTENT_HOST_AUTH_KEY_COLUMN = 7;
        public static final int CONTENT_SMTP_HOST_AUTH_KEY_COLUMN = 8;
        public static final int CONTENT_FLAGS_COLUMN = 9;
        
        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, AccountColumns.DISPLAY_NAME, AccountColumns.PROTOCOL,
            AccountColumns.EMAIL_ADDRESS, AccountColumns.SYNC_KEY, AccountColumns.SYNC_LOOKBACK,
            AccountColumns.SYNC_FREQUENCY, AccountColumns.HOST_AUTH_KEY,
            AccountColumns.SMTP_HOST_AUTH_KEY, AccountColumns.FLAGS
        };

        /**
         * no public constructor since this is a utility class
         */
        public Account() {
            baseUri = CONTENT_URI;
        }

        public static final String TABLE_NAME = "Account";

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse(EmailStore.CONTENT_URI + "/account");

        static void createTable(SQLiteDatabase db) {
            String s = " (" + RECORD_ID + " integer primary key autoincrement, " 
            + AccountColumns.DISPLAY_NAME + " text, "
            + AccountColumns.PROTOCOL + " text, "
            + AccountColumns.EMAIL_ADDRESS + " text, "
            + AccountColumns.SYNC_KEY + " text, "
            + AccountColumns.SYNC_LOOKBACK + " integer, "
            + AccountColumns.SYNC_FREQUENCY + " text, "
            + AccountColumns.HOST_AUTH_KEY + " integer, "
            + AccountColumns.SMTP_HOST_AUTH_KEY + " integer, "
            + AccountColumns.FLAGS + " integer"
            + ");";
            db.execSQL("create table " + TABLE_NAME + s);
        }

        static void upgradeTable(SQLiteDatabase db, int oldVersion, int newVersion) {
            try {
                db.execSQL("drop table " + TABLE_NAME);
            } catch (SQLException e) {
            }
            createTable(db);
        }
        
        public static Account restoreAccountWithId(Context context, long id) {
            Uri u = ContentUris.withAppendedId(Account.CONTENT_URI, id);
            Cursor c = context.getContentResolver().query(u, Account.CONTENT_PROJECTION,
                    null, null, null);

            try {
                if (c.moveToFirst())
                    return EmailContent.getContent(c, Account.class);
                else
                    return null;
            } finally {
                c.close();
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public EmailStore.Account restore(Cursor cursor) {
            baseUri = EmailStore.Account.CONTENT_URI;
            displayName = cursor.getString(CONTENT_DISPLAY_NAME_COLUMN);
            protocol = cursor.getString(CONTENT_PROTOCOL_COLUMN);
            emailAddress = cursor.getString(CONTENT_EMAIL_ADDRESS_COLUMN);
            syncKey = cursor.getString(CONTENT_SYNC_KEY_COLUMN);
            syncLookback = cursor.getInt(CONTENT_SYNC_LOOKBACK_COLUMN);
            syncFrequency = cursor.getInt(CONTENT_SYNC_FREQUENCY_COLUMN);
            hostAuthKey = cursor.getLong(CONTENT_HOST_AUTH_KEY_COLUMN);
            smtpHostAuthKey = cursor.getLong(CONTENT_SMTP_HOST_AUTH_KEY_COLUMN);
            flags = cursor.getInt(CONTENT_FLAGS_COLUMN);
            return this;
        }

        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();
            values.put(AccountColumns.DISPLAY_NAME, displayName);
            values.put(AccountColumns.PROTOCOL, protocol);
            values.put(AccountColumns.EMAIL_ADDRESS, emailAddress);
            values.put(AccountColumns.SYNC_KEY, syncKey);
            values.put(AccountColumns.SYNC_LOOKBACK, syncLookback);
            values.put(AccountColumns.SYNC_FREQUENCY, syncFrequency);
            values.put(AccountColumns.HOST_AUTH_KEY, hostAuthKey);
            values.put(AccountColumns.SMTP_HOST_AUTH_KEY, smtpHostAuthKey);
            values.put(AccountColumns.FLAGS, flags);
            return values;
        }
    }

    public interface AttachmentColumns {
        // The display name of the attachment 
        public static final String FILENAME = "fileName";
        // The mime type of the attachment
        public static final String MIME_TYPE = "mimeType";
        // The size of the attachment in bytes
        public static final String SIZE = "size";
        // The (internal) contentId of the attachment (inline attachments will have these)
        public static final String CONTENT_ID = "contentId";
        // The location of the loaded attachment (probably a file)
        public static final String CONTENT_URI = "contentUri";
        // A foreign key into the Message table (the message owning this attachment)
        public static final String MESSAGE_KEY = "messageKey";
        // The location of the attachment on the server side
        // For IMAP, this is a part number (e.g. 2.1); for EAS, it's the internal file name
        public static final String LOCATION = "location";
        // The transfer encoding of the attachment
        public static final String ENCODING = "encoding";
     }

    public static final class Attachment extends EmailContent implements AttachmentColumns {
        
        public String fileName;
        public String mimeType;
        public long size;
        public String contentId;
        public String contentUri;
        public long messageKey;
        public String location;
        public String encoding;
        
        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_FILENAME_COLUMN = 1;
        public static final int CONTENT_MIME_TYPE_COLUMN = 2;
        public static final int CONTENT_SIZE_COLUMN = 3;
        public static final int CONTENT_CONTENT_ID_COLUMN = 4;
        public static final int CONTENT_CONTENT_URI_COLUMN = 5;
        public static final int CONTENT_MESSAGE_ID_COLUMN = 6;
        public static final int CONTENT_LOCATION_COLUMN = 7;
        public static final int CONTENT_ENCODING_COLUMN = 8;
        public static final String[] CONTENT_PROJECTION = new String[] {RECORD_ID, AttachmentColumns.FILENAME, AttachmentColumns.MIME_TYPE, AttachmentColumns.SIZE, AttachmentColumns.CONTENT_ID, AttachmentColumns.CONTENT_URI,
            AttachmentColumns.MESSAGE_KEY, AttachmentColumns.LOCATION, AttachmentColumns.ENCODING};

        /**
         * no public constructor since this is a utility class
         */
        public Attachment() {
             baseUri = CONTENT_URI;
        }

        public static final String TABLE_NAME = "Attachment";
        
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse(EmailStore.CONTENT_URI + "/attachment");

        static void createTable(SQLiteDatabase db) {
            String s = " (" + RECORD_ID + " integer primary key autoincrement, " 
            + AttachmentColumns.FILENAME + " text, "
            + AttachmentColumns.MIME_TYPE + " text, "
            + AttachmentColumns.SIZE + " integer, "
            + AttachmentColumns.CONTENT_ID + " text, "
            + AttachmentColumns.CONTENT_URI + " text, "
            + AttachmentColumns.MESSAGE_KEY + " integer, "
            + AttachmentColumns.LOCATION + " text, "
            + AttachmentColumns.ENCODING + " text"
            + ");";
            db.execSQL("create table " + TABLE_NAME + s);
        }

        static void upgradeTable(SQLiteDatabase db, int oldVersion, int newVersion) {
            try {
                db.execSQL("drop table " + TABLE_NAME);
            } catch (SQLException e) {
            }
            createTable(db);
        }
        
        public static Attachment restoreAttachmentWithId (Context context, long id) {
            Uri u = ContentUris.withAppendedId(Attachment.CONTENT_URI, id);
            Cursor c = context.getContentResolver().query(u, Attachment.CONTENT_PROJECTION,
                    null, null, null);

            try {
                if (c.moveToFirst())
                    return EmailContent.getContent(c, Attachment.class);
                else
                    return null;
            } finally {
                c.close();
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public EmailStore.Attachment restore(Cursor cursor) {
            baseUri = EmailStore.Attachment.CONTENT_URI;
            fileName= cursor.getString(CONTENT_FILENAME_COLUMN);
            mimeType = cursor.getString(CONTENT_MIME_TYPE_COLUMN);
            size = cursor.getLong(CONTENT_SIZE_COLUMN);
            contentId = cursor.getString(CONTENT_CONTENT_ID_COLUMN);
            contentUri = cursor.getString(CONTENT_CONTENT_URI_COLUMN);
            messageKey = cursor.getLong(CONTENT_MESSAGE_ID_COLUMN);
            location = cursor.getString(CONTENT_LOCATION_COLUMN);
            encoding = cursor.getString(CONTENT_ENCODING_COLUMN);
            return this;
        }

        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();
            values.put(AttachmentColumns.FILENAME, fileName);
            values.put(AttachmentColumns.MIME_TYPE, mimeType);          
            values.put(AttachmentColumns.SIZE, size);           
            values.put(AttachmentColumns.CONTENT_ID, contentId);            
            values.put(AttachmentColumns.CONTENT_URI, contentUri);          
            values.put(AttachmentColumns.MESSAGE_KEY, messageKey);          
            values.put(AttachmentColumns.LOCATION, location);           
            values.put(AttachmentColumns.ENCODING, encoding);           
            return values;
        }
    }

    public interface MailboxColumns {
        public static final String ID = "_id";
        // The display name of this mailbox [INDEX]
        static final String DISPLAY_NAME = "displayName"; 
        // The server's identifier for this mailbox
        public static final String SERVER_ID = "serverId";
        // The server's identifier for the parent of this mailbox (null = top-level)
        public static final String PARENT_SERVER_ID = "parentServerId";
        // A foreign key to the Account that owns this mailbox
        public static final String ACCOUNT_KEY = "accountKey";
        // The type (role) of this mailbox
        public static final String TYPE = "type";
        // The hierarchy separator character
        public static final String DELIMITER = "delimiter";
        // Server-based sync key or validity marker (e.g. "SyncKey" for EAS, "uidvalidity" for IMAP)
        public static final String SYNC_KEY = "syncKey";
        // The sync lookback period for this mailbox (or null if using the account default)
        public static final String SYNC_LOOKBACK = "syncLookback";
        // The sync frequency for this mailbox (or null if using the account default)
        public static final String SYNC_FREQUENCY = "syncFrequency";
        // The time of last successful sync completion (millis)
        public static final String SYNC_TIME = "syncTime";
        // Cached unread count
        public static final String UNREAD_COUNT = "unreadCount";
        // Visibility of this folder in a list of folders [INDEX]
        public static final String FLAG_VISIBLE = "flagVisible";
        // Other states, as a bit field, e.g. CHILDREN_VISIBLE, HAS_CHILDREN
        public static final String FLAGS = "flags";
        // Backward compatible
        public static final String VISIBLE_LIMIT = "visibleLimit";
      }

    public static final class Mailbox extends EmailContent implements SyncColumns, MailboxColumns {
        public String displayName;
        public String serverId;
        public String parentServerId;
        public long accountKey;
        public int type;
        public int delimiter;
        public String syncKey;
        public int syncLookback;
        public int syncFrequency;
        public long syncTime;
        public int unreadCount;
        public boolean flagVisible = true;
        public int flags;
        public int visibleLimit;
        
        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
        public static final int CONTENT_SERVER_ID_COLUMN = 2;
        public static final int CONTENT_PARENT_SERVER_ID_COLUMN = 3;
        public static final int CONTENT_ACCOUNT_KEY_COLUMN = 4;
        public static final int CONTENT_TYPE_COLUMN = 5;
        public static final int CONTENT_DELIMITER_COLUMN = 6;
        public static final int CONTENT_SYNC_KEY_COLUMN = 7;
        public static final int CONTENT_SYNC_LOOKBACK_COLUMN = 8;
        public static final int CONTENT_SYNC_FREQUENCY_COLUMN = 9;
        public static final int CONTENT_SYNC_TIME_COLUMN = 10;
        public static final int CONTENT_UNREAD_COUNT_COLUMN = 11;
        public static final int CONTENT_FLAG_VISIBLE_COLUMN = 12;
        public static final int CONTENT_FLAGS_COLUMN = 13;
        public static final int CONTENT_VISIBLE_LIMIT_COLUMN = 14;
        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, MailboxColumns.DISPLAY_NAME, MailboxColumns.SERVER_ID,
            MailboxColumns.PARENT_SERVER_ID, MailboxColumns.ACCOUNT_KEY, MailboxColumns.TYPE,
            MailboxColumns.DELIMITER, MailboxColumns.SYNC_KEY, MailboxColumns.SYNC_LOOKBACK,
            MailboxColumns.SYNC_FREQUENCY, MailboxColumns.SYNC_TIME,MailboxColumns.UNREAD_COUNT,
            MailboxColumns.FLAG_VISIBLE, MailboxColumns.FLAGS, MailboxColumns.VISIBLE_LIMIT
        };

        /**
         * no public constructor since this is a utility class
         */
        public Mailbox() {
            baseUri = CONTENT_URI;
        }

        public static final String TABLE_NAME = "Mailbox";
 
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse(EmailStore.CONTENT_URI + "/mailbox");
        
        // Types of mailboxes
        // Holds mail (generic)
        public static final int TYPE_MAIL = 0;
        // Holds deleted mail
        public static final int TYPE_TRASH = 1;
        // Holds sent mail
        public static final int TYPE_SENT = 2;
        // Holds drafts
        public static final int TYPE_DRAFTS = 3;
        // The "main" mailbox for the account, almost always referred to as "Inbox"
        public static final int TYPE_INBOX = 4;
        // The local outbox associated with the Account
        public static final int TYPE_OUTBOX = 5;
        // Holds junk mail
        public static final int TYPE_JUNK = 6;
        // Parent-only mailbox; holds no mail
        public static final int TYPE_PARENT = 7;
        
        // Bit field flags
        public static final int FLAG_HAS_CHILDREN = 1<<0;
        public static final int FLAG_CHILDREN_VISIBLE = 1<<1;
        
        static void createTable(SQLiteDatabase db) {
            String s = " (" + RECORD_ID + " integer primary key autoincrement, " 
            + MailboxColumns.DISPLAY_NAME + " text, "
            + MailboxColumns.SERVER_ID + " text, "
            + MailboxColumns.PARENT_SERVER_ID + " text, "
            + MailboxColumns.ACCOUNT_KEY + " integer, "
            + MailboxColumns.TYPE + " integer, "
            + MailboxColumns.DELIMITER + " integer, "
            + MailboxColumns.SYNC_KEY + " text, "
            + MailboxColumns.SYNC_LOOKBACK + " integer, "
            + MailboxColumns.SYNC_FREQUENCY+ " integer, "
            + MailboxColumns.SYNC_TIME + " integer, "
            + MailboxColumns.UNREAD_COUNT + " integer, "
            + MailboxColumns.FLAG_VISIBLE + " integer, "
            + MailboxColumns.FLAGS + " integer, "
            + MailboxColumns.VISIBLE_LIMIT + " integer"
            + ");";
            db.execSQL("create table " + TABLE_NAME + s);
            db.execSQL("create index mailbox_" + MailboxColumns.SERVER_ID 
                    + " on " + TABLE_NAME + " (" + MailboxColumns.SERVER_ID + ")");
            db.execSQL("create index mailbox_" + MailboxColumns.ACCOUNT_KEY 
                    + " on " + TABLE_NAME + " (" + MailboxColumns.ACCOUNT_KEY + ")");

       }

        static void upgradeTable(SQLiteDatabase db, int oldVersion, int newVersion) {
            try {
                db.execSQL("drop table " + TABLE_NAME);
            } catch (SQLException e) {
            }
            createTable(db);
        }

        public static Mailbox restoreMailboxWithId(Context context, long id) {
            Uri u = ContentUris.withAppendedId(Mailbox.CONTENT_URI, id);
            Cursor c = context.getContentResolver().query(u, Mailbox.CONTENT_PROJECTION,
                    null, null, null);

            try {
                if (c.moveToFirst())
                    return EmailContent.getContent(c, Mailbox.class);
                else
                    return null;
            } finally {
                c.close();
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public EmailStore.Mailbox restore(Cursor cursor) {
            baseUri = EmailStore.Attachment.CONTENT_URI;
            displayName = cursor.getString(CONTENT_DISPLAY_NAME_COLUMN);
            serverId = cursor.getString(CONTENT_SERVER_ID_COLUMN);
            parentServerId = cursor.getString(CONTENT_PARENT_SERVER_ID_COLUMN);
            accountKey = cursor.getLong(CONTENT_ACCOUNT_KEY_COLUMN);
            type = cursor.getInt(CONTENT_TYPE_COLUMN);
            delimiter = cursor.getInt(CONTENT_DELIMITER_COLUMN);
            syncKey = cursor.getString(CONTENT_SYNC_KEY_COLUMN);
            syncLookback = cursor.getInt(CONTENT_SYNC_LOOKBACK_COLUMN);
            syncFrequency = cursor.getInt(CONTENT_SYNC_FREQUENCY_COLUMN);
            syncTime = cursor.getLong(CONTENT_SYNC_TIME_COLUMN);
            unreadCount = cursor.getInt(CONTENT_UNREAD_COUNT_COLUMN);
            flagVisible = cursor.getInt(CONTENT_FLAG_VISIBLE_COLUMN) == 1;
            flags = cursor.getInt(CONTENT_FLAGS_COLUMN);
            visibleLimit = cursor.getInt(CONTENT_VISIBLE_LIMIT_COLUMN);
            return this;
        }

        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();
            values.put(MailboxColumns.DISPLAY_NAME, displayName);           
            values.put(MailboxColumns.SERVER_ID, serverId);         
            values.put(MailboxColumns.PARENT_SERVER_ID, parentServerId);            
            values.put(MailboxColumns.ACCOUNT_KEY, accountKey);         
            values.put(MailboxColumns.TYPE, type);          
            values.put(MailboxColumns.DELIMITER, delimiter);            
            values.put(MailboxColumns.SYNC_KEY, syncKey);           
            values.put(MailboxColumns.SYNC_LOOKBACK, syncLookback);         
            values.put(MailboxColumns.SYNC_FREQUENCY, syncFrequency);           
            values.put(MailboxColumns.SYNC_TIME, syncTime);         
            values.put(MailboxColumns.UNREAD_COUNT, unreadCount);           
            values.put(MailboxColumns.FLAG_VISIBLE, flagVisible);           
            values.put(MailboxColumns.FLAGS, flags);            
            values.put(MailboxColumns.VISIBLE_LIMIT, visibleLimit);         
            return values;
        }
    }
    
    public interface HostAuthColumns {
        public static final String ID = "_id";
        // The host address
        static final String ADDRESS = "address"; 
        // The port to use for the connection
        static final String PORT = "port"; 
        // Whether SSL is to be used
        static final String SSL = "ssl"; 
        // The login (user name)
        static final String LOGIN = "login"; 
        // Password
        static final String PASSWORD = "password"; 
        // A domain, if required (some EAS systems require this)
        static final String DOMAIN = "domain"; 
        // Whether authentication is required
        static final String FLAG_AUTHENTICATE = "flagAuthenticate";
        // Foreign key of the Account this is attached to
        static final String ACCOUNT_KEY = "accountKey";
        }

    public static final class HostAuth extends EmailContent implements HostAuthColumns {
        public String address;
        public int port;
        public boolean ssl;
        public String login;
        public String password;
        public String domain;
        public boolean flagAuthenticate;
        public long accountKey;
        
        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_ADDRESS_COLUMN = 1;
        public static final int CONTENT_PORT_COLUMN = 2;
        public static final int CONTENT_SSL_COLUMN = 3;
        public static final int CONTENT_LOGIN_COLUMN = 4;
        public static final int CONTENT_PASSWORD_COLUMN = 5;
        public static final int CONTENT_DOMAIN_COLUMN = 6;
        public static final int CONTENT_FLAG_AUTHENTICATE_COLUMN = 7;
        public static final int CONTENT_ACCOUNT_KEY_COLUMN = 8;
        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, HostAuthColumns.ADDRESS, HostAuthColumns.PORT, HostAuthColumns.SSL,
            HostAuthColumns.LOGIN, HostAuthColumns.PASSWORD, HostAuthColumns.DOMAIN,
            HostAuthColumns.FLAG_AUTHENTICATE, HostAuthColumns.ACCOUNT_KEY
        };

        /**
         * no public constructor since this is a utility class
         */
        public HostAuth() {
            baseUri = CONTENT_URI;
        }

        public static final String TABLE_NAME = "HostAuth";
 
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse(EmailStore.CONTENT_URI + "/hostauth");
         
        static void createTable(SQLiteDatabase db) {
            String s = " (" + RECORD_ID + " integer primary key autoincrement, " 
            + HostAuthColumns.ADDRESS + " text, "
            + HostAuthColumns.PORT + " integer, "
            + HostAuthColumns.SSL + " integer, "
            + HostAuthColumns.LOGIN + " text, "
            + HostAuthColumns.PASSWORD + " text, "
            + HostAuthColumns.DOMAIN + " text, "
            + HostAuthColumns.FLAG_AUTHENTICATE + " text, "
            + HostAuthColumns.ACCOUNT_KEY + " integer"
            + ");";
            db.execSQL("create table " + TABLE_NAME + s);
       }

        static void upgradeTable(SQLiteDatabase db, int oldVersion, int newVersion) {
            try {
                db.execSQL("drop table " + TABLE_NAME);
            } catch (SQLException e) {
            }
            createTable(db);
        }

        public static HostAuth restoreHostAuthWithId(Context context, long id) {
            Uri u = ContentUris.withAppendedId(EmailStore.HostAuth.CONTENT_URI, id);
            Cursor c = context.getContentResolver().query(u, HostAuth.CONTENT_PROJECTION,
                    null, null, null);

            try {
                if (c.moveToFirst())
                    return EmailContent.getContent(c, HostAuth.class);
                else
                    return null;
            } finally {
                c.close();
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public EmailStore.HostAuth restore(Cursor cursor) {
            baseUri = EmailStore.Attachment.CONTENT_URI;
            address = cursor.getString(CONTENT_ADDRESS_COLUMN);
            port = cursor.getInt(CONTENT_PORT_COLUMN);
            ssl = cursor.getInt(CONTENT_SSL_COLUMN) == 1;
            login = cursor.getString(CONTENT_LOGIN_COLUMN);
            password = cursor.getString(CONTENT_PASSWORD_COLUMN);
            domain = cursor.getString(CONTENT_DOMAIN_COLUMN);
            flagAuthenticate = cursor.getInt(CONTENT_FLAG_AUTHENTICATE_COLUMN) == 1;
            accountKey = cursor.getLong(CONTENT_ACCOUNT_KEY_COLUMN);
            return this;
        }

        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();
            values.put(HostAuthColumns.ADDRESS, address);
            values.put(HostAuthColumns.PORT, port);
            values.put(HostAuthColumns.SSL, ssl);
            values.put(HostAuthColumns.LOGIN, login);
            values.put(HostAuthColumns.PASSWORD, password);
            values.put(HostAuthColumns.DOMAIN, domain);
            values.put(HostAuthColumns.FLAG_AUTHENTICATE, flagAuthenticate);
            values.put(HostAuthColumns.ACCOUNT_KEY, accountKey);
            return values;
        }
    }

 }       
        
        
