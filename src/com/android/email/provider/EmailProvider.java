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

import com.android.email.Email;
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
import android.util.Log;

import java.util.ArrayList;

public class EmailProvider extends ContentProvider {

    private static final String TAG = "EmailProvider";

    static final String DATABASE_NAME = "EmailProvider.db";
    static final String BODY_DATABASE_NAME = "EmailProviderBody.db";

    // In these early versions, updating the database version will cause all tables to be deleted
    // Obviously, we'll handle upgrades differently once things are a bit stable
    // version 15: changed Address.pack() format.
    // version 16: added protocolVersion column to Account
    // version 17: prevent duplication of mailboxes with the same serverId
    // version 18: renamed syncFrequency to syncInterval for Account and Mailbox
    // version 19: added triggers to keep track of unreadCount by Mailbox
    // version 20: changed type of EAS account mailbox, making old databases invalid for EAS
    // version 21: fixed broken trigger linking account deletion to the deletion of its HostAuth's
    // version 22: added syncStatus column to Mailbox

    public static final int DATABASE_VERSION = 22;
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
    private static final int MESSAGE_ID = MESSAGE_BASE + 1;
    private static final int SYNCED_MESSAGE_ID = MESSAGE_BASE + 2;

    private static final int ATTACHMENT_BASE = 0x3000;
    private static final int ATTACHMENT = ATTACHMENT_BASE;
    private static final int ATTACHMENT_CONTENT = ATTACHMENT_BASE + 1;
    private static final int ATTACHMENT_ID = ATTACHMENT_BASE + 2;
    private static final int ATTACHMENTS_MESSAGE_ID = ATTACHMENT_BASE + 3;

    private static final int HOSTAUTH_BASE = 0x4000;
    private static final int HOSTAUTH = HOSTAUTH_BASE;
    private static final int HOSTAUTH_ID = HOSTAUTH_BASE + 1;

    private static final int UPDATED_MESSAGE_BASE = 0x5000;
    private static final int UPDATED_MESSAGE = UPDATED_MESSAGE_BASE;
    private static final int UPDATED_MESSAGE_ID = UPDATED_MESSAGE_BASE + 1;

    private static final int DELETED_MESSAGE_BASE = 0x6000;
    private static final int DELETED_MESSAGE = DELETED_MESSAGE_BASE;
    private static final int DELETED_MESSAGE_ID = DELETED_MESSAGE_BASE + 1;
    private static final int DELETED_MESSAGE_MAILBOX = DELETED_MESSAGE_BASE + 2;

    // MUST ALWAYS EQUAL THE LAST OF THE PREVIOUS BASE CONSTANTS
    private static final int LAST_EMAIL_PROVIDER_DB_BASE = DELETED_MESSAGE_BASE;

    // DO NOT CHANGE BODY_BASE!!
    private static final int BODY_BASE = LAST_EMAIL_PROVIDER_DB_BASE + 0x1000;
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
        EmailContent.Message.UPDATED_TABLE_NAME,
        EmailContent.Message.DELETED_TABLE_NAME,
        EmailContent.Body.TABLE_NAME
    };

    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    /**
     * Let's only generate these SQL strings once, as they are used frequently
     * Note that this isn't relevant for table creation strings, since they are used only once
     */
    private static final String UPDATED_MESSAGE_INSERT = "insert or ignore into " +
        Message.UPDATED_TABLE_NAME + " select * from " + Message.TABLE_NAME + " where " +
        EmailContent.RECORD_ID + '=';

    private static final String UPDATED_MESSAGE_DELETE = "delete from " +
        Message.UPDATED_TABLE_NAME + " where " + EmailContent.RECORD_ID + '=';

    private static final String DELETED_MESSAGE_INSERT = "insert or replace into " +
        Message.DELETED_TABLE_NAME + " select * from " + Message.TABLE_NAME + " where " +
        EmailContent.RECORD_ID + '=';

    private static final String DELETE_ORPHAN_BODIES = "delete from " + Body.TABLE_NAME +
        " where " + EmailContent.RECORD_ID + " in " + "(select " + EmailContent.RECORD_ID +
        " from " + Body.TABLE_NAME + " except select " + EmailContent.RECORD_ID + " from " +
        Message.TABLE_NAME + ')';

    private static final String DELETE_BODY = "delete from " + Body.TABLE_NAME +
        " where " + EmailContent.RECORD_ID + '=';

    static {
        // Email URI matching table
        UriMatcher matcher = sURIMatcher;

        // All accounts
        matcher.addURI(EMAIL_AUTHORITY, "account", ACCOUNT);
        // A specific account
        // insert into this URI causes a mailbox to be added to the account
        matcher.addURI(EMAIL_AUTHORITY, "account/#", ACCOUNT_ID);
        // The mailboxes in a specific account
        matcher.addURI(EMAIL_AUTHORITY, "account/#/mailbox", ACCOUNT_MAILBOXES);

        // All mailboxes
        matcher.addURI(EMAIL_AUTHORITY, "mailbox", MAILBOX);
        // A specific mailbox
        // insert into this URI causes a message to be added to the mailbox
        // ** NOTE For now, the accountKey must be set manually in the values!
        matcher.addURI(EMAIL_AUTHORITY, "mailbox/#", MAILBOX_ID);
        // The messages in a specific mailbox
        matcher.addURI(EMAIL_AUTHORITY, "mailbox/#/message", MAILBOX_MESSAGES);

        // All messages
        matcher.addURI(EMAIL_AUTHORITY, "message", MESSAGE);
        // A specific message
        // insert into this URI causes an attachment to be added to the message
        matcher.addURI(EMAIL_AUTHORITY, "message/#", MESSAGE_ID);

        // A specific attachment
        matcher.addURI(EMAIL_AUTHORITY, "attachment", ATTACHMENT);
        // A specific attachment (the header information)
        matcher.addURI(EMAIL_AUTHORITY, "attachment/#", ATTACHMENT_ID);
        // The content for a specific attachment
        // NOT IMPLEMENTED
        matcher.addURI(EMAIL_AUTHORITY, "attachment/content/*", ATTACHMENT_CONTENT);
        // The attachments of a specific message (query only) (insert & delete TBD)
        matcher.addURI(EMAIL_AUTHORITY, "attachment/message/#", ATTACHMENTS_MESSAGE_ID);

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

        // All hostauth records
        matcher.addURI(EMAIL_AUTHORITY, "hostauth", HOSTAUTH);
        // A specific hostauth
        matcher.addURI(EMAIL_AUTHORITY, "hostauth/#", HOSTAUTH_ID);

        /**
         * THIS URI HAS SPECIAL SEMANTICS
         * ITS USE IS INDENTED FOR THE UI APPLICATION TO MARK CHANGES THAT NEED TO BE SYNCED BACK
         * TO A SERVER VIA A SYNC ADAPTER
         */
        matcher.addURI(EMAIL_AUTHORITY, "syncedMessage/#", SYNCED_MESSAGE_ID);

        /**
         * THE URIs BELOW THIS POINT ARE INTENDED TO BE USED BY SYNC ADAPTERS ONLY
         * THEY REFER TO DATA CREATED AND MAINTAINED BY CALLS TO THE SYNCED_MESSAGE_ID URI
         * BY THE UI APPLICATION
         */
        // All deleted messages
        matcher.addURI(EMAIL_AUTHORITY, "deletedMessage", DELETED_MESSAGE);
        // A specific deleted message
        matcher.addURI(EMAIL_AUTHORITY, "deletedMessage/#", DELETED_MESSAGE_ID);
        // All deleted messages from a specific mailbox
        // NOT IMPLEMENTED; do we need this as a convenience?
        matcher.addURI(EMAIL_AUTHORITY, "deletedMessage/mailbox/#", DELETED_MESSAGE_MAILBOX);

        // All updated messages
        matcher.addURI(EMAIL_AUTHORITY, "updatedMessage", UPDATED_MESSAGE);
        // A specific updated message
        matcher.addURI(EMAIL_AUTHORITY, "updatedMessage/#", UPDATED_MESSAGE_ID);
    }

    static void createMessageTable(SQLiteDatabase db) {
        String messageColumns = MessageColumns.DISPLAY_NAME + " text, "
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

        // This String and the following String MUST have the same columns, except for the type
        // of those columns!
        String createString = " (" + EmailContent.RECORD_ID + " integer primary key autoincrement, "
            + SyncColumns.ACCOUNT_KEY + " integer, "
            + SyncColumns.SERVER_ID + " integer, "
            + SyncColumns.SERVER_VERSION + " integer, "
            + SyncColumns.DATA + " text, "
            + SyncColumns.DIRTY_COUNT + " integer, "
            + messageColumns;

        // For the updated and deleted tables, the id is assigned, but we do want to keep track
        // of the ORDER of updates using an autoincrement primary key.  We use the DATA column
        // at this point; it has no other function
        String altCreateString = " (" + EmailContent.RECORD_ID + " integer unique, "
            + SyncColumns.ACCOUNT_KEY + " integer, "
            + SyncColumns.SERVER_ID + " integer, "
            + SyncColumns.SERVER_VERSION + " integer, "
            + SyncColumns.DATA + " integer primary key autoincrement, "
            + SyncColumns.DIRTY_COUNT + " integer, "
            + messageColumns;

        // The three tables have the same schema
        db.execSQL("create table " + Message.TABLE_NAME + createString);
        db.execSQL("create table " + Message.UPDATED_TABLE_NAME + altCreateString);
        db.execSQL("create table " + Message.DELETED_TABLE_NAME + altCreateString);

        // For now, indices only on the Message table
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

        // Deleting a Message deletes all associated Attachments
        // Deleting the associated Body cannot be done in a trigger, because the Body is stored
        // in a separate database, and trigger cannot operate on attached databases.
        db.execSQL("create trigger message_delete before delete on " + Message.TABLE_NAME +
                " begin delete from " + Attachment.TABLE_NAME +
                "  where " + AttachmentColumns.MESSAGE_KEY + "=old." + EmailContent.RECORD_ID +
                "; end");

        // Add triggers to keep unread count accurate per mailbox

        // Insert a message; if flagRead is zero, add to the unread count of the message's mailbox
        db.execSQL("create trigger unread_message_insert before insert on " + Message.TABLE_NAME +
                " when NEW." + MessageColumns.FLAG_READ + "=0" +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.UNREAD_COUNT +
                '=' + MailboxColumns.UNREAD_COUNT + "+1" +
                "  where " + EmailContent.RECORD_ID + "=NEW." + MessageColumns.MAILBOX_KEY +
                "; end");

        // Delete a message; if flagRead is zero, decrement the unread count of the msg's mailbox
        db.execSQL("create trigger unread_message_delete before delete on " + Message.TABLE_NAME +
                " when OLD." + MessageColumns.FLAG_READ + "=0" +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.UNREAD_COUNT +
                '=' + MailboxColumns.UNREAD_COUNT + "-1" +
                "  where " + EmailContent.RECORD_ID + "=OLD." + MessageColumns.MAILBOX_KEY +
                "; end");

        // Change a message's mailbox
        db.execSQL("create trigger unread_message_move before update of " +
                MessageColumns.MAILBOX_KEY + " on " + Message.TABLE_NAME +
                " when OLD." + MessageColumns.FLAG_READ + "=0" +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.UNREAD_COUNT +
                '=' + MailboxColumns.UNREAD_COUNT + "-1" +
                "  where " + EmailContent.RECORD_ID + "=OLD." + MessageColumns.MAILBOX_KEY +
                "; update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.UNREAD_COUNT +
                '=' + MailboxColumns.UNREAD_COUNT + "+1" +
                " where " + EmailContent.RECORD_ID + "=NEW." + MessageColumns.MAILBOX_KEY +
                "; end");

        // Change a message's read state
        db.execSQL("create trigger unread_message_read before update of " +
                MessageColumns.FLAG_READ + " on " + Message.TABLE_NAME +
                " when OLD." + MessageColumns.FLAG_READ + "!=NEW." + MessageColumns.FLAG_READ +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.UNREAD_COUNT +
                '=' + MailboxColumns.UNREAD_COUNT + "+ case OLD." + MessageColumns.FLAG_READ +
                " when 0 then -1 else 1 end" +
                "  where " + EmailContent.RECORD_ID + "=OLD." + MessageColumns.MAILBOX_KEY +
                "; end");
   }

    static void upgradeMessageTable(SQLiteDatabase db, int oldVersion, int newVersion) {
        try {
            db.execSQL("drop table " + Message.TABLE_NAME);
            db.execSQL("drop table " + Message.UPDATED_TABLE_NAME);
            db.execSQL("drop table " + Message.DELETED_TABLE_NAME);
        } catch (SQLException e) {
        }
        createMessageTable(db);
    }

    static void createAccountTable(SQLiteDatabase db) {
        String s = " (" + EmailContent.RECORD_ID + " integer primary key autoincrement, "
            + AccountColumns.DISPLAY_NAME + " text, "
            + AccountColumns.EMAIL_ADDRESS + " text, "
            + AccountColumns.SYNC_KEY + " text, "
            + AccountColumns.SYNC_LOOKBACK + " integer, "
            + AccountColumns.SYNC_INTERVAL + " text, "
            + AccountColumns.HOST_AUTH_KEY_RECV + " integer, "
            + AccountColumns.HOST_AUTH_KEY_SEND + " integer, "
            + AccountColumns.FLAGS + " integer, "
            + AccountColumns.IS_DEFAULT + " integer, "
            + AccountColumns.COMPATIBILITY_UUID + " text, "
            + AccountColumns.SENDER_NAME + " text, "
            + AccountColumns.RINGTONE_URI + " text, "
            + AccountColumns.PROTOCOL_VERSION + " text"
            + ");";
        db.execSQL("create table " + Account.TABLE_NAME + s);
        // Deleting an account deletes associated Mailboxes and HostAuth's
        db.execSQL("create trigger account_delete before delete on " + Account.TABLE_NAME +
                " begin delete from " + Mailbox.TABLE_NAME +
                " where " + MailboxColumns.ACCOUNT_KEY + "=old." + EmailContent.RECORD_ID +
                "; delete from " + HostAuth.TABLE_NAME +
                " where " + EmailContent.RECORD_ID + "=old." + AccountColumns.HOST_AUTH_KEY_RECV +
                "; delete from " + HostAuth.TABLE_NAME +
                " where " + EmailContent.RECORD_ID + "=old." + AccountColumns.HOST_AUTH_KEY_SEND +
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
            + MailboxColumns.SERVER_ID + " text unique on conflict replace, "
            + MailboxColumns.PARENT_SERVER_ID + " text, "
            + MailboxColumns.ACCOUNT_KEY + " integer, "
            + MailboxColumns.TYPE + " integer, "
            + MailboxColumns.DELIMITER + " integer, "
            + MailboxColumns.SYNC_KEY + " text, "
            + MailboxColumns.SYNC_LOOKBACK + " integer, "
            + MailboxColumns.SYNC_INTERVAL + " integer, "
            + MailboxColumns.SYNC_TIME + " integer, "
            + MailboxColumns.UNREAD_COUNT + " integer, "
            + MailboxColumns.FLAG_VISIBLE + " integer, "
            + MailboxColumns.FLAGS + " integer, "
            + MailboxColumns.VISIBLE_LIMIT + " integer, "
            + MailboxColumns.SYNC_STATUS + " text"
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
        try {
            db.execSQL("drop table " + Body.TABLE_NAME);
        } catch (SQLException e) {
        }
        createBodyTable(db);
    }

    private final int mDatabaseVersion = DATABASE_VERSION;
    private final int mBodyDatabaseVersion = BODY_DATABASE_VERSION;

    private SQLiteDatabase mDatabase;
    private SQLiteDatabase mBodyDatabase;
    private boolean mInTransaction = false;

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
        // Pick the correct database for this operation
        // If we're in a transaction already (which would happen during applyBatch), then the
        // body database is already attached to the email database and any attempt to use the
        // body database directly will result in a SQLiteException (the database is locked)
        SQLiteDatabase db = ((match >= BODY_BASE) && !mInTransaction) ? getBodyDatabase(context)
                : getDatabase(context);
        int table = match >> BASE_SHIFT;
        String id = "0";
        boolean messageDeletion = false;
        boolean deleteOrphanedBodies = false;

        if (Email.LOGD) {
            Log.v(TAG, "EmailProvider.delete: uri=" + uri + ", match is " + match);
        }

        int result = -1;

        try {
            switch (match) {
                // These are cases in which one or more Messages might get deleted, either by
                // cascade or explicitly
                case MAILBOX_ID:
                case MAILBOX:
                case ACCOUNT_ID:
                case ACCOUNT:
                case MESSAGE:
                case SYNCED_MESSAGE_ID:
                case MESSAGE_ID:
                    // Handle lost Body records here, since this cannot be done in a trigger
                    // The process is:
                    //  0) Open the body database (bootstrap step, if doesn't exist yet)
                    //  1) Attach the Body database
                    //  2) Begin a transaction, ensuring that both databases are affected atomically
                    //  3) Do the requested deletion, with cascading deletions handled in triggers
                    //  4) End the transaction, committing all changes atomically
                    //  5) Detach the Body database

                    // Note that batch operations always attach the body database, so we don't
                    // do the database attach/detach or any transaction operations here
                    messageDeletion = true;
                    if (!mInTransaction) {
                        getBodyDatabase(context);
                        String bodyFileName = mBodyDatabase.getPath();
                        db.execSQL("attach \"" + bodyFileName + "\" as BodyDatabase");
                        db.beginTransaction();
                    }
                    if (match != MESSAGE_ID) {
                        deleteOrphanedBodies = true;
                    }
                    break;
            }
            switch (match) {
                case BODY_ID:
                case DELETED_MESSAGE_ID:
                case SYNCED_MESSAGE_ID:
                case MESSAGE_ID:
                case UPDATED_MESSAGE_ID:
                case ATTACHMENT_ID:
                case MAILBOX_ID:
                case ACCOUNT_ID:
                case HOSTAUTH_ID:
                    id = uri.getPathSegments().get(1);
                    if (match == SYNCED_MESSAGE_ID) {
                        // For synced messages, first copy the old message to the deleted table and
                        // delete it from the updated table (in case it was updated first)
                        // Note that this is all within a transaction, for atomicity
                        db.execSQL(DELETED_MESSAGE_INSERT + id);
                        db.execSQL(UPDATED_MESSAGE_DELETE + id);
                    }
                    result = db.delete(TABLE_NAMES[table], whereWithId(id, selection),
                            selectionArgs);
                    break;
                case BODY:
                case MESSAGE:
                case DELETED_MESSAGE:
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
            if (messageDeletion) {
                if (deleteOrphanedBodies) {
                    // Delete any orphaned Body records
                    db.execSQL(DELETE_ORPHAN_BODIES);
                } else {
                    // Delete the Body record associated with the deleted message
                    db.execSQL(DELETE_BODY + id);
                }
                if (!mInTransaction) {
                    db.setTransactionSuccessful();
                }
            }
        } finally {
            if (messageDeletion) {
                if (!mInTransaction) {
                    db.endTransaction();
                    db.execSQL("detach BodyDatabase");
                }
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
            case ATTACHMENTS_MESSAGE_ID:
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
        // See the comment at delete(), above
        SQLiteDatabase db = ((match >= BODY_BASE) && !mInTransaction) ? getBodyDatabase(context)
                : getDatabase(context);
        int table = match >> BASE_SHIFT;
        long id;

        if (Email.LOGD) {
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
            case ATTACHMENTS_MESSAGE_ID:
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
        // See the comment at delete(), above
        SQLiteDatabase db = ((match >= BODY_BASE) && !mInTransaction) ? getBodyDatabase(context)
                : getDatabase(context);
        int table = match >> BASE_SHIFT;
        String id;

        if (Email.LOGD) {
            Log.v(TAG, "EmailProvider.query: uri=" + uri + ", match is " + match);
        }

        switch (match) {
            case BODY:
            case MESSAGE:
            case UPDATED_MESSAGE:
            case DELETED_MESSAGE:
            case ATTACHMENT:
            case MAILBOX:
            case ACCOUNT:
            case HOSTAUTH:
                c = db.query(TABLE_NAMES[table], projection,
                        selection, selectionArgs, null, null, sortOrder);
                break;
            case BODY_ID:
            case MESSAGE_ID:
            case DELETED_MESSAGE_ID:
            case UPDATED_MESSAGE_ID:
            case ATTACHMENT_ID:
            case MAILBOX_ID:
            case ACCOUNT_ID:
            case HOSTAUTH_ID:
                id = uri.getPathSegments().get(1);
                c = db.query(TABLE_NAMES[table], projection,
                        whereWithId(id, selection), selectionArgs, null, null, sortOrder);
                break;
            case ATTACHMENTS_MESSAGE_ID:
                // All attachments for the given message
                id = uri.getPathSegments().get(2);
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
        // See the comment at delete(), above
        SQLiteDatabase db = ((match >= BODY_BASE) && !mInTransaction) ? getBodyDatabase(context)
                : getDatabase(context);
        int table = match >> BASE_SHIFT;
        int result;

        if (Email.LOGD) {
            Log.v(TAG, "EmailProvider.update: uri=" + uri + ", match is " + match);
        }

        // We do NOT allow setting of unreadCount via the provider
        // This column is maintained via triggers
        if (match == MAILBOX_ID || match == MAILBOX) {
            values.remove(MailboxColumns.UNREAD_COUNT);
        }

        switch (match) {
            case BODY_ID:
            case MESSAGE_ID:
            case SYNCED_MESSAGE_ID:
            case UPDATED_MESSAGE_ID:
            case ATTACHMENT_ID:
            case MAILBOX_ID:
            case ACCOUNT_ID:
            case HOSTAUTH_ID:
                String id = uri.getPathSegments().get(1);
                if (match == SYNCED_MESSAGE_ID) {
                    // For synced messages, first copy the old message to the updated table
                    // Note the insert or ignore semantics, guaranteeing that only the first
                    // update will be reflected in the updated message table; therefore this row
                    // will always have the "original" data
                    db.execSQL(UPDATED_MESSAGE_INSERT + id);
                } else if (match == MESSAGE_ID) {
                    db.execSQL(UPDATED_MESSAGE_DELETE + id);
                }
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
     */
    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        Context context = getContext();
        SQLiteDatabase db = getDatabase(context);
        getBodyDatabase(context);
        String bodyFileName = mBodyDatabase.getPath();
        db.execSQL("attach \"" + bodyFileName + "\" as BodyDatabase");
        db.beginTransaction();
        mInTransaction = true;
        try {
            ContentProviderResult[] results = super.applyBatch(operations);
            db.setTransactionSuccessful();
            return results;
        } finally {
            db.endTransaction();
            mInTransaction = false;
            db.execSQL("detach BodyDatabase");
        }
    }
}
