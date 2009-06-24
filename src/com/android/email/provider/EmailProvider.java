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
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.AttachmentColumns;
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.BodyColumns;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.provider.EmailContent.HostAuthColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.provider.EmailContent.SyncColumns;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Config;
import android.util.Log;

import java.util.ArrayList;

public class EmailProvider extends ContentProvider {

    private static final String TAG = "EmailProvider";

    static final String DATABASE_NAME = "EmailProvider.db";
    static final String BODY_DATABASE_NAME = "EmailProviderBody.db";
    
    // In these early versions, updating the database version will cause all tables to be deleted
    // Obviously, we'll handle upgrades differently once things are a bit stable
    public static final int DATABASE_VERSION = 13;
    public static final int BODY_DATABASE_VERSION = 1;

    public static final String EMAIL_AUTHORITY = "com.android.email.provider";

    private static final int ACCOUNT_BASE = 0;
    private static final int ACCOUNT = ACCOUNT_BASE;
    private static final int ACCOUNT_MAILBOXES = ACCOUNT_BASE + 1;
    private static final int ACCOUNT_ID = ACCOUNT_BASE + 2;
 
    private static final int MAILBOX_BASE = 0x1000;
    private static final int MAILBOX = MAILBOX_BASE;
    private static final int MAILBOX_MESSAGES = MAILBOX_BASE + 1;
    private static final int MAILBOX_ID = MAILBOX_BASE + 2;
    
    private static final int MESSAGE_BASE = 0x2000;
    private static final int MESSAGE = MESSAGE_BASE;
    private static final int MESSAGE_ATTACHMENTS = MESSAGE_BASE + 1;
    private static final int MESSAGE_ID = MESSAGE_BASE + 2;
    
    private static final int ATTACHMENT_BASE = 0x3000;
    private static final int ATTACHMENT = ATTACHMENT_BASE;
    private static final int ATTACHMENT_CONTENT = ATTACHMENT_BASE + 1;
    private static final int ATTACHMENT_ID = ATTACHMENT_BASE + 2;
    
     // TEMPORARY UNTIL ACCOUNT MANAGER CAN BE USED
    private static final int HOSTAUTH_BASE = 0x4000;
    private static final int HOSTAUTH = HOSTAUTH_BASE;
    private static final int HOSTAUTH_ID = HOSTAUTH_BASE + 1;
    
    private static final int UPDATED_MESSAGE_BASE = 0x5000;
    private static final int UPDATED_MESSAGE = UPDATED_MESSAGE_BASE;
    private static final int UPDATED_MESSAGE_ATTACHMENTS = UPDATED_MESSAGE_BASE + 1;
    private static final int UPDATED_MESSAGE_ID = UPDATED_MESSAGE_BASE + 2;
    
    // BODY_BASE MAY BE CHANGED BUT IT MUST BE HIGHEST BASE VALUE (it's in a different database!)
    private static final int BODY_BASE = 0x6000;
    private static final int BODY = BODY_BASE;
    private static final int BODY_ID = BODY_BASE + 1;
    private static final int BODY_MESSAGE_ID = BODY_BASE + 2;
    private static final int BODY_HTML = BODY_BASE + 3;
    private static final int BODY_TEXT = BODY_BASE + 4;
   
    
    private static final int BASE_SHIFT = 12;  // 12 bits to the base type: 0, 0x1000, 0x2000, etc.
    
    private static final String[] TABLE_NAMES = {
        EmailContent.Account.TABLE_NAME,
        EmailContent.Mailbox.TABLE_NAME,
        EmailContent.Message.TABLE_NAME,
        EmailContent.Attachment.TABLE_NAME,
        EmailContent.HostAuth.TABLE_NAME,
        EmailContent.Message.UPDATES_TABLE_NAME,
        EmailContent.Body.TABLE_NAME
    };
 
    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        // Email URI matching table
        UriMatcher matcher = sURIMatcher;
        // All accounts
        matcher.addURI(EMAIL_AUTHORITY, "account", ACCOUNT); // IMPLEMENTED
        // A specific account
        // insert into this URI causes a mailbox to be added to the account 
        matcher.addURI(EMAIL_AUTHORITY, "account/#", ACCOUNT_ID);  // IMPLEMENTED
        // The mailboxes in a specific account
        matcher.addURI(EMAIL_AUTHORITY, "account/#/mailbox", ACCOUNT_MAILBOXES);
        // All mailboxes
        matcher.addURI(EMAIL_AUTHORITY, "mailbox", MAILBOX);  // IMPLEMENTED
        // A specific mailbox
        // insert into this URI causes a message to be added to the mailbox
        // ** NOTE For now, the accountKey must be set manually in the values!
        matcher.addURI(EMAIL_AUTHORITY, "mailbox/#", MAILBOX_ID);  // IMPLEMENTED
        // The messages in a specific mailbox
        matcher.addURI(EMAIL_AUTHORITY, "mailbox/#/message", MAILBOX_MESSAGES);
        // All messages
        matcher.addURI(EMAIL_AUTHORITY, "message", MESSAGE); // IMPLEMENTED
        // A specific message
        // insert into this URI causes an attachment to be added to the message 
        matcher.addURI(EMAIL_AUTHORITY, "message/#", MESSAGE_ID); // IMPLEMENTED
        // The attachments of a specific message
        matcher.addURI(EMAIL_AUTHORITY, "message/#/attachment", MESSAGE_ATTACHMENTS); // IMPLEMENTED
        // All updated messages
        matcher.addURI(EMAIL_AUTHORITY, "updatedMessage", UPDATED_MESSAGE); // IMPLEMENTED
        // A specific updated message
        matcher.addURI(EMAIL_AUTHORITY, "updatedMessage/#", UPDATED_MESSAGE_ID); // IMPLEMENTED
        // The attachments of a specific updated message
        matcher.addURI(EMAIL_AUTHORITY, "updatedMessage/#/attachment", UPDATED_MESSAGE_ATTACHMENTS);
        // A specific attachment
        matcher.addURI(EMAIL_AUTHORITY, "attachment", ATTACHMENT); // IMPLEMENTED
        // A specific attachment (the header information)
        matcher.addURI(EMAIL_AUTHORITY, "attachment/#", ATTACHMENT_ID);  // IMPLEMENTED
        // The content for a specific attachment
        matcher.addURI(EMAIL_AUTHORITY, "attachment/content/*", ATTACHMENT_CONTENT);

        // All mail bodies
        matcher.addURI(EMAIL_AUTHORITY, "body", BODY);
        // A specific mail body
        matcher.addURI(EMAIL_AUTHORITY, "body/#", BODY_ID);
        // The body for a specific message
        matcher.addURI(EMAIL_AUTHORITY, "body/message/#", BODY_MESSAGE_ID);
        // The HTML part of a specific mail body
        matcher.addURI(EMAIL_AUTHORITY, "body/#/html", BODY_HTML);
        // The plain text part of a specific mail body
        matcher.addURI(EMAIL_AUTHORITY, "body/#/text", BODY_TEXT);
        
        // A specific attachment
        matcher.addURI(EMAIL_AUTHORITY, "hostauth", HOSTAUTH); // IMPLEMENTED
        // A specific attachment (the header information)
        matcher.addURI(EMAIL_AUTHORITY, "hostauth/#", HOSTAUTH_ID);  // IMPLEMENTED
   
    }

    static void createMessageTable(SQLiteDatabase db) {
        String s = " (" + EmailContent.RECORD_ID + " integer primary key autoincrement, " 
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
        db.execSQL("create table " + Message.TABLE_NAME + s);
        db.execSQL("create table " + Message.UPDATES_TABLE_NAME + s);
        db.execSQL("create index message_" + MessageColumns.TIMESTAMP 
                + " on " + Message.TABLE_NAME + " (" + MessageColumns.TIMESTAMP + ");");
        db.execSQL("create index message_" + MessageColumns.FLAG_READ 
                + " on " + Message.TABLE_NAME + " (" + MessageColumns.FLAG_READ + ");");
        db.execSQL("create index message_" + MessageColumns.FLAG_LOADED 
                + " on " + Message.TABLE_NAME + " (" + MessageColumns.FLAG_LOADED + ");");
        db.execSQL("create index message_" + MessageColumns.MAILBOX_KEY 
                + " on " + Message.TABLE_NAME + " (" + MessageColumns.MAILBOX_KEY + ");");
        db.execSQL("create index message_" + SyncColumns.SERVER_ID 
                + " on " + Message.TABLE_NAME + " (" + SyncColumns.SERVER_ID + ");");

        // Deleting a Message deletes associated Attachments
        // Deleting the associated Body cannot be done in a trigger, because the Body is stored
        // in a separate database, and trigger cannot operate on attached databases.
        db.execSQL("create trigger message_delete before delete on " + Message.TABLE_NAME + 
                " begin delete from " + Attachment.TABLE_NAME +
                "  where " + AttachmentColumns.MESSAGE_KEY + "=old." + EmailContent.RECORD_ID + 
                "; end");
    }

    static void upgradeMessageTable(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("drop table " + Message.TABLE_NAME);
        db.execSQL("drop table " + Message.UPDATES_TABLE_NAME);
        createMessageTable(db);
    }

    static void createAccountTable(SQLiteDatabase db) {
        String s = " (" + EmailContent.RECORD_ID + " integer primary key autoincrement, " 
            + AccountColumns.DISPLAY_NAME + " text, "
            + AccountColumns.EMAIL_ADDRESS + " text, "
            + AccountColumns.SYNC_KEY + " text, "
            + AccountColumns.SYNC_LOOKBACK + " integer, "
            + AccountColumns.SYNC_FREQUENCY + " text, "
            + AccountColumns.HOST_AUTH_KEY_RECV + " integer, "
            + AccountColumns.HOST_AUTH_KEY_SEND + " integer, "
            + AccountColumns.FLAGS + " integer, "
            + AccountColumns.IS_DEFAULT + " integer, "
            + AccountColumns.COMPATIBILITY_UUID + " text, "
            + AccountColumns.SENDER_NAME + " text, "
            + AccountColumns.RINGTONE_URI + " text "
            + ");";
        db.execSQL("create table " + Account.TABLE_NAME + s);
        // Deleting an account deletes associated Mailboxes and HostAuth's
        db.execSQL("create trigger account_delete before delete on " + Account.TABLE_NAME + 
                " begin delete from " + Mailbox.TABLE_NAME +
                " where " + MailboxColumns.ACCOUNT_KEY + "=old." + EmailContent.RECORD_ID + 
                "; delete from " + HostAuth.TABLE_NAME +
                " where " + HostAuthColumns.ACCOUNT_KEY + "=old." + EmailContent.RECORD_ID +
                "; end");
   }

    static void upgradeAccountTable(SQLiteDatabase db, int oldVersion, int newVersion) {
        try {
            db.execSQL("drop table " +  Account.TABLE_NAME);
        } catch (SQLException e) {
        }
        createAccountTable(db);
    }
 
    static void createHostAuthTable(SQLiteDatabase db) {
        String s = " (" + EmailContent.RECORD_ID + " integer primary key autoincrement, " 
            + HostAuthColumns.PROTOCOL + " text, "
            + HostAuthColumns.ADDRESS + " text, "
            + HostAuthColumns.PORT + " integer, "
            + HostAuthColumns.FLAGS + " integer, "
            + HostAuthColumns.LOGIN + " text, "
            + HostAuthColumns.PASSWORD + " text, "
            + HostAuthColumns.DOMAIN + " text, "
            + HostAuthColumns.ACCOUNT_KEY + " integer"
            + ");";
        db.execSQL("create table " + HostAuth.TABLE_NAME + s);
    }

    static void upgradeHostAuthTable(SQLiteDatabase db, int oldVersion, int newVersion) {
        try {
            db.execSQL("drop table " + HostAuth.TABLE_NAME);
        } catch (SQLException e) {
        }
        createHostAuthTable(db);
    }

   static void createMailboxTable(SQLiteDatabase db) {
        String s = " (" + EmailContent.RECORD_ID + " integer primary key autoincrement, " 
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
            db.execSQL("create table " + Mailbox.TABLE_NAME + s);
        db.execSQL("create index mailbox_" + MailboxColumns.SERVER_ID 
                + " on " + Mailbox.TABLE_NAME + " (" + MailboxColumns.SERVER_ID + ")");
        db.execSQL("create index mailbox_" + MailboxColumns.ACCOUNT_KEY 
                + " on " + Mailbox.TABLE_NAME + " (" + MailboxColumns.ACCOUNT_KEY + ")");
        // Deleting a Mailbox deletes associated Messages
        db.execSQL("create trigger mailbox_delete before delete on " + Mailbox.TABLE_NAME + 
                " begin delete from " + Message.TABLE_NAME +
                "  where " + MessageColumns.MAILBOX_KEY + "=old." + EmailContent.RECORD_ID + 
                "; end");
    }

    static void upgradeMailboxTable(SQLiteDatabase db, int oldVersion, int newVersion) {
        try {
            db.execSQL("drop table " + Mailbox.TABLE_NAME);
        } catch (SQLException e) {
        }
        createMailboxTable(db);
    }
    
    static void createAttachmentTable(SQLiteDatabase db) {
        String s = " (" + EmailContent.RECORD_ID + " integer primary key autoincrement, " 
            + AttachmentColumns.FILENAME + " text, "
            + AttachmentColumns.MIME_TYPE + " text, "
            + AttachmentColumns.SIZE + " integer, "
            + AttachmentColumns.CONTENT_ID + " text, "
            + AttachmentColumns.CONTENT_URI + " text, "
            + AttachmentColumns.MESSAGE_KEY + " integer, "
            + AttachmentColumns.LOCATION + " text, "
            + AttachmentColumns.ENCODING + " text"
            + ");";
        db.execSQL("create table " + Attachment.TABLE_NAME + s);
    }

    static void upgradeAttachmentTable(SQLiteDatabase db, int oldVersion, int newVersion) {
        try {
            db.execSQL("drop table " + Attachment.TABLE_NAME);
        } catch (SQLException e) {
        }
        createAttachmentTable(db);
    }

    static void createBodyTable(SQLiteDatabase db) {
        String s = " (" + EmailContent.RECORD_ID + " integer primary key autoincrement, " 
            + BodyColumns.MESSAGE_KEY + " integer, "
            + BodyColumns.HTML_CONTENT + " text, "
            + BodyColumns.TEXT_CONTENT + " text"
            + ");";
        db.execSQL("create table " + Body.TABLE_NAME + s);
    }

    static void upgradeBodyTable(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("drop table " + Body.TABLE_NAME);
        createBodyTable(db);
    }


    
    private final int mDatabaseVersion = DATABASE_VERSION;
    private final int mBodyDatabaseVersion = BODY_DATABASE_VERSION;
    
    private SQLiteDatabase mDatabase;
    private SQLiteDatabase mBodyDatabase;
    
    public SQLiteDatabase getDatabase(Context context) {
        if (mDatabase !=  null) {
            return mDatabase;
        }
        DatabaseHelper helper = new DatabaseHelper(context, DATABASE_NAME);
        mDatabase = helper.getWritableDatabase();
        if (mDatabase != null) {
            mDatabase.setLockingEnabled(true);
        }
        return mDatabase;
    }

    public SQLiteDatabase getBodyDatabase(Context context) {
        if (mBodyDatabase !=  null) {
            return mBodyDatabase;
        }
        BodyDatabaseHelper helper = new BodyDatabaseHelper(context, BODY_DATABASE_NAME);
        mBodyDatabase = helper.getWritableDatabase();
        if (mBodyDatabase != null) {
            mBodyDatabase.setLockingEnabled(true);
        }
        return mBodyDatabase;
    }

    private class BodyDatabaseHelper extends SQLiteOpenHelper {
        BodyDatabaseHelper(Context context, String name) {
             super(context, name, null, mBodyDatabaseVersion);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Create all tables here; each class has its own method
            createBodyTable(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            upgradeBodyTable(db, oldVersion, newVersion);
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
        }
    }
    
    private class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context, String name) {
             super(context, name, null, mDatabaseVersion);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Create all tables here; each class has its own method
            createMessageTable(db);
            createAttachmentTable(db);
            createMailboxTable(db);
            createHostAuthTable(db);
            createAccountTable(db);
       }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            upgradeMessageTable(db, oldVersion, newVersion);
            upgradeAttachmentTable(db, oldVersion, newVersion);
            upgradeMailboxTable(db, oldVersion, newVersion);
            upgradeHostAuthTable(db, oldVersion, newVersion);
            upgradeAccountTable(db, oldVersion, newVersion);
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int match = sURIMatcher.match(uri);
        Context context = getContext();
        SQLiteDatabase db = (match >= BODY_BASE) ? getBodyDatabase(context) : getDatabase(context);
        int table = match >> BASE_SHIFT;
        String id = "0";
        boolean attachBodyDb = false;
        boolean deleteOrphanedBodies = false;
        
        if (Config.LOGV) {
            Log.v(TAG, "EmailProvider.delete: uri=" + uri + ", match is " + match);
        }

        int result;
        
        try {
            switch (match) {
                // These are cases in which one or more Messages might get deleted, either by
                // cascade or explicitly
                case MAILBOX_ID:
                case MAILBOX:
                case ACCOUNT_ID:
                case ACCOUNT:
                case MESSAGE:
                case MESSAGE_ID:
                    // Handle lost Body records here, since this cannot be done in a trigger
                    // The process is:
                    //  0) Activate the body database (bootstrap step, if doesn't exist yet)
                    //  1) Attach the Body database
                    //  2) Begin a transaction, ensuring that both databases are affected atomically
                    //  3) Do the requested deletion, with cascading deletions handled in triggers
                    //  4) End the transaction, committing all changes atomically
                    //  5) Detach the Body database
                    attachBodyDb = true;
                    getBodyDatabase(context);
                    String bodyFileName = context.getDatabasePath(BODY_DATABASE_NAME)
                            .getAbsolutePath();
                    db.execSQL("attach \"" + bodyFileName + "\" as BodyDatabase");
                    db.beginTransaction();
                    if (match != MESSAGE_ID) {
                        deleteOrphanedBodies = true;
                    }
                    break;
            }
            switch (match) {
                case BODY_ID:
                case MESSAGE_ID:
                case UPDATED_MESSAGE_ID:
                case ATTACHMENT_ID:
                case MAILBOX_ID:
                case ACCOUNT_ID:
                case HOSTAUTH_ID:
                    id = uri.getPathSegments().get(1);
                    result = db.delete(TABLE_NAMES[table], whereWithId(id, selection),
                            selectionArgs);
                    break;
                case BODY:
                case MESSAGE:
                case UPDATED_MESSAGE:
                case ATTACHMENT:
                case MAILBOX:
                case ACCOUNT:
                case HOSTAUTH:
                    result = db.delete(TABLE_NAMES[table], selection, selectionArgs);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown URI " + uri);
            }
            if (attachBodyDb) {
                if (deleteOrphanedBodies) {
                    // Delete any orphaned Body records
                    db.execSQL("delete from " + Body.TABLE_NAME +
                            " where " + EmailContent.RECORD_ID + " in " +
                            "(select " + EmailContent.RECORD_ID + " from " + Body.TABLE_NAME +
                            " except select " + EmailContent.RECORD_ID +
                            " from " + Message.TABLE_NAME + ")");
                    db.setTransactionSuccessful();
                } else {
                    // Delete the Body record associated with the deleted message
                    db.execSQL("delete from " + Body.TABLE_NAME +
                            " where " + EmailContent.RECORD_ID + "=" + id);
                    db.setTransactionSuccessful();
                 }
            }
        } finally {
            if (attachBodyDb) {
                db.endTransaction();
                db.execSQL("detach BodyDatabase");
            }
        }
        getContext().getContentResolver().notifyChange(uri, null);
        return result;
    }

    @Override
    // Use the email- prefix because message, mailbox, and account are so generic (e.g. SMS, IM)
    public String getType(Uri uri) {
        int match = sURIMatcher.match(uri);
        switch (match) {
            case BODY_ID:
                return "vnd.android.cursor.item/email-body";
            case BODY:
                return "vnd.android.cursor.dir/email-message";
            case UPDATED_MESSAGE_ID:
            case MESSAGE_ID:
                return "vnd.android.cursor.item/email-message";
            case MAILBOX_MESSAGES:
            case UPDATED_MESSAGE:
            case MESSAGE:
                return "vnd.android.cursor.dir/email-message";
            case ACCOUNT_MAILBOXES:
            case MAILBOX:
                return "vnd.android.cursor.dir/email-mailbox";
            case MAILBOX_ID:
                return "vnd.android.cursor.item/email-mailbox";
            case ACCOUNT:
                return "vnd.android.cursor.dir/email-account";
            case ACCOUNT_ID:
                return "vnd.android.cursor.item/email-account";
            case MESSAGE_ATTACHMENTS:
            case ATTACHMENT:
                return "vnd.android.cursor.dir/email-attachment";
            case ATTACHMENT_ID:
                return "vnd.android.cursor.item/email-attachment";
            case HOSTAUTH:
                return "vnd.android.cursor.dir/email-hostauth";
            case HOSTAUTH_ID:
                return "vnd.android.cursor.item/email-hostauth";
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        int match = sURIMatcher.match(uri);
        Context context = getContext();
        SQLiteDatabase db = (match >= BODY_BASE) ? getBodyDatabase(context) : getDatabase(context);
        int table = match >> BASE_SHIFT;
        long id;
        
        if (Config.LOGV) {
            Log.v(TAG, "EmailProvider.insert: uri=" + uri + ", match is " + match);
        }

        Uri resultUri = null;
         
        switch (match) {
            case BODY:
            case MESSAGE:
            case ATTACHMENT:
            case MAILBOX:
            case ACCOUNT:
            case HOSTAUTH:
                // Make sure all new message records have dirty count of 0
                if (match == MESSAGE) {
                    values.put(SyncColumns.DIRTY_COUNT, 0);
                }
                id = db.insert(TABLE_NAMES[table], "foo", values);
                resultUri = ContentUris.withAppendedId(uri, id);
                break;
            case MAILBOX_ID:
                // This implies adding a message to a mailbox
                // Hmm, one problem here is that we can't link the account as well, so it must be
                // already in the values...
                id = Long.parseLong(uri.getPathSegments().get(1));
                values.put(MessageColumns.MAILBOX_KEY, id);
                resultUri = insert(Message.CONTENT_URI, values);
                break;
            case MESSAGE_ID:
                // This implies adding an attachment to a message.
                id = Long.parseLong(uri.getPathSegments().get(1));
                values.put(AttachmentColumns.MESSAGE_KEY, id);
                resultUri = insert(Attachment.CONTENT_URI, values);
                break;
            case ACCOUNT_ID:
                // This implies adding a mailbox to an account.
                id = Long.parseLong(uri.getPathSegments().get(1));
                values.put(MailboxColumns.ACCOUNT_KEY, id);
                resultUri = insert(Mailbox.CONTENT_URI, values);
                break;
            case MESSAGE_ATTACHMENTS:
                id = db.insert(TABLE_NAMES[table], "foo", values);
                resultUri = ContentUris.withAppendedId(Attachment.CONTENT_URI, id);
                break;
            default:
                throw new IllegalArgumentException("Unknown URL " + uri);
        }
        
        // Notify with the base uri, not the new uri (nobody is watching a new record)
        getContext().getContentResolver().notifyChange(uri, null);
        return resultUri;
    }

    @Override
    public boolean onCreate() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, 
            String sortOrder) {
        Cursor c = null;
        Uri notificationUri = EmailContent.CONTENT_URI;
        int match = sURIMatcher.match(uri);
        Context context = getContext();
        SQLiteDatabase db = (match >= BODY_BASE) ? getBodyDatabase(context) : getDatabase(context);
        int table = match >> BASE_SHIFT;
        String id;
        
        if (Config.LOGV) {
            Log.v(TAG, "EmailProvider.query: uri=" + uri + ", match is " + match);
        }

        switch (match) {
            case BODY:
            case MESSAGE:
            case UPDATED_MESSAGE:
            case ATTACHMENT:
            case MAILBOX:
            case ACCOUNT:
            case HOSTAUTH:
                c = db.query(TABLE_NAMES[table], projection, 
                        selection, selectionArgs, null, null, sortOrder);
                break;
            case BODY_ID:
            case MESSAGE_ID:
            case UPDATED_MESSAGE_ID:
            case ATTACHMENT_ID:
            case MAILBOX_ID:
            case ACCOUNT_ID:
            case HOSTAUTH_ID:
                id = uri.getPathSegments().get(1);
                c = db.query(TABLE_NAMES[table], projection, 
                        whereWithId(id, selection), selectionArgs, null, null, sortOrder);
                break;
            case MESSAGE_ATTACHMENTS:
                // All attachments for the given message
                id = uri.getPathSegments().get(1);
                c = db.query(Attachment.TABLE_NAME, projection, 
                        whereWith(Attachment.MESSAGE_KEY + "=" + id, selection), 
                        selectionArgs, null, null, sortOrder);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        if ((c != null) && !isTemporary()) {
            c.setNotificationUri(getContext().getContentResolver(), notificationUri);
        }
        return c;
    }

    private String whereWithId(String id, String selection) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("_id=");
        sb.append(id);
        if (selection != null) {
            sb.append(" AND ");
            sb.append(selection);
        }
        return sb.toString();
    }
    
    private String whereWith(String where, String selection) {
        StringBuilder sb = new StringBuilder(where);
        if (selection != null) {
            sb.append(" AND ");
            sb.append(selection);
        }
        return sb.toString();
    }
    
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int match = sURIMatcher.match(uri);
        Context context = getContext();
        SQLiteDatabase db = (match >= BODY_BASE) ? getBodyDatabase(context) : getDatabase(context);
        int table = match >> BASE_SHIFT;
        if (Config.LOGV) {
            Log.v(TAG, "EmailProvider.update: uri=" + uri + ", match is " + match);
        }

        int result;

        // Set the dirty bit for messages
        if ((match == MESSAGE_ID || match == MESSAGE) 
                && values.get(SyncColumns.DIRTY_COUNT) == null) {
            values.put(SyncColumns.DIRTY_COUNT, 1);
        }

        switch (match) {
            case BODY_ID:
            case MESSAGE_ID:
            case UPDATED_MESSAGE_ID:
            case ATTACHMENT_ID:
            case MAILBOX_ID:
            case ACCOUNT_ID:
            case HOSTAUTH_ID:
                String id = uri.getPathSegments().get(1);
                // Set dirty if nobody is setting this manually
                result = db.update(TABLE_NAMES[table], values, whereWithId(id, selection), 
                        selectionArgs);
                break;
            case BODY:
            case MESSAGE:
            case UPDATED_MESSAGE:
            case ATTACHMENT:
            case MAILBOX:
            case ACCOUNT:
            case HOSTAUTH:
                result = db.update(TABLE_NAMES[table], values, selection, selectionArgs);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);
        return result;
    }
    
    /* (non-Javadoc)
     * @see android.content.ContentProvider#applyBatch(android.content.ContentProviderOperation)
     * 
     * TODO: How do we call notifyChange() or do we need to - does this call the various
     * update/insert/delete calls?
     */
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        SQLiteDatabase db = getDatabase(getContext());
        db.beginTransaction();
        try {
            ContentProviderResult[] results = super.applyBatch(operations);
            db.setTransactionSuccessful();
            return results;
        } finally {
            db.endTransaction();
        }
    }
}
