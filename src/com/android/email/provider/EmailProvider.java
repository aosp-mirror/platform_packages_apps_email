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
import com.android.email.provider.ContentCache.CacheToken;
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
import com.android.email.service.AttachmentDownloadService;

import android.accounts.AccountManager;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;

public class EmailProvider extends ContentProvider {

    private static final String TAG = "EmailProvider";

    protected static final String DATABASE_NAME = "EmailProvider.db";
    protected static final String BODY_DATABASE_NAME = "EmailProviderBody.db";

    public static final String ACTION_ATTACHMENT_UPDATED = "com.android.email.ATTACHMENT_UPDATED";
    public static final String ATTACHMENT_UPDATED_EXTRA_FLAGS =
        "com.android.email.ATTACHMENT_UPDATED_FLAGS";

    public static final String EMAIL_MESSAGE_MIME_TYPE =
        "vnd.android.cursor.item/email-message";
    public static final String EMAIL_ATTACHMENT_MIME_TYPE =
        "vnd.android.cursor.item/email-attachment";

    public static final Uri INTEGRITY_CHECK_URI =
        Uri.parse("content://" + EmailContent.AUTHORITY + "/integrityCheck");

    // Definitions for our queries looking for orphaned messages
    private static final String[] ORPHANS_PROJECTION
        = new String[] {MessageColumns.ID, MessageColumns.MAILBOX_KEY};
    private static final int ORPHANS_ID = 0;
    private static final int ORPHANS_MAILBOX_KEY = 1;

    private static final String WHERE_ID = EmailContent.RECORD_ID + "=?";

    // We'll cache the following four tables; sizes are best estimates of effective values
    private static final ContentCache sCacheAccount =
        new ContentCache("Account", Account.CONTENT_PROJECTION, 4);
    private static final ContentCache sCacheHostAuth =
        new ContentCache("HostAuth", HostAuth.CONTENT_PROJECTION, 8);
    /*package*/ static final ContentCache sCacheMailbox =
        new ContentCache("Mailbox", Mailbox.CONTENT_PROJECTION, 8);
    private static final ContentCache sCacheMessage =
        new ContentCache("Message", Message.CONTENT_PROJECTION, 3);

    // Any changes to the database format *must* include update-in-place code.
    // Original version: 3
    // Version 4: Database wipe required; changing AccountManager interface w/Exchange
    // Version 5: Database wipe required; changing AccountManager interface w/Exchange
    // Version 6: Adding Message.mServerTimeStamp column
    // Version 7: Replace the mailbox_delete trigger with a version that removes orphaned messages
    //            from the Message_Deletes and Message_Updates tables
    // Version 8: Add security flags column to accounts table
    // Version 9: Add security sync key and signature to accounts table
    // Version 10: Add meeting info to message table
    // Version 11: Add content and flags to attachment table
    // Version 12: Add content_bytes to attachment table. content is deprecated.
    // Version 13: Add messageCount to Mailbox table.
    // Version 14: Add snippet to Message table
    // Version 15: Fix upgrade problem in version 14.
    // Version 16: Add accountKey to Attachment table
    public static final int DATABASE_VERSION = 16;

    // Any changes to the database format *must* include update-in-place code.
    // Original version: 2
    // Version 3: Add "sourceKey" column
    // Version 4: Database wipe required; changing AccountManager interface w/Exchange
    // Version 5: Database wipe required; changing AccountManager interface w/Exchange
    // Version 6: Adding Body.mIntroText column
    public static final int BODY_DATABASE_VERSION = 6;

    public static final String EMAIL_AUTHORITY = "com.android.email.provider";
    // The notifier authority is used to send notifications regarding changes to messages (insert,
    // delete, or update) and is intended as an optimization for use by clients of message list
    // cursors (initially, the email AppWidget).
    public static final String EMAIL_NOTIFIER_AUTHORITY = "com.android.email.notifier";

    private static final int ACCOUNT_BASE = 0;
    private static final int ACCOUNT = ACCOUNT_BASE;
    private static final int ACCOUNT_ID = ACCOUNT_BASE + 1;
    private static final int ACCOUNT_ID_ADD_TO_FIELD = ACCOUNT_BASE + 2;
    private static final int ACCOUNT_RESET_NEW_COUNT = ACCOUNT_BASE + 3;
    private static final int ACCOUNT_RESET_NEW_COUNT_ID = ACCOUNT_BASE + 4;

    private static final int MAILBOX_BASE = 0x1000;
    private static final int MAILBOX = MAILBOX_BASE;
    private static final int MAILBOX_ID = MAILBOX_BASE + 1;
    private static final int MAILBOX_ID_ADD_TO_FIELD = MAILBOX_BASE + 2;

    private static final int MESSAGE_BASE = 0x2000;
    private static final int MESSAGE = MESSAGE_BASE;
    private static final int MESSAGE_ID = MESSAGE_BASE + 1;
    private static final int SYNCED_MESSAGE_ID = MESSAGE_BASE + 2;

    private static final int ATTACHMENT_BASE = 0x3000;
    private static final int ATTACHMENT = ATTACHMENT_BASE;
    private static final int ATTACHMENT_ID = ATTACHMENT_BASE + 1;
    private static final int ATTACHMENTS_MESSAGE_ID = ATTACHMENT_BASE + 2;

    private static final int HOSTAUTH_BASE = 0x4000;
    private static final int HOSTAUTH = HOSTAUTH_BASE;
    private static final int HOSTAUTH_ID = HOSTAUTH_BASE + 1;

    private static final int UPDATED_MESSAGE_BASE = 0x5000;
    private static final int UPDATED_MESSAGE = UPDATED_MESSAGE_BASE;
    private static final int UPDATED_MESSAGE_ID = UPDATED_MESSAGE_BASE + 1;

    private static final int DELETED_MESSAGE_BASE = 0x6000;
    private static final int DELETED_MESSAGE = DELETED_MESSAGE_BASE;
    private static final int DELETED_MESSAGE_ID = DELETED_MESSAGE_BASE + 1;

    // MUST ALWAYS EQUAL THE LAST OF THE PREVIOUS BASE CONSTANTS
    private static final int LAST_EMAIL_PROVIDER_DB_BASE = DELETED_MESSAGE_BASE;

    // DO NOT CHANGE BODY_BASE!!
    private static final int BODY_BASE = LAST_EMAIL_PROVIDER_DB_BASE + 0x1000;
    private static final int BODY = BODY_BASE;
    private static final int BODY_ID = BODY_BASE + 1;

    private static final int BASE_SHIFT = 12;  // 12 bits to the base type: 0, 0x1000, 0x2000, etc.

    // TABLE_NAMES MUST remain in the order of the BASE constants above (e.g. ACCOUNT_BASE = 0x0000,
    // MESSAGE_BASE = 0x1000, etc.)
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

    // CONTENT_CACHES MUST remain in the order of the BASE constants above
    private static final ContentCache[] CONTENT_CACHES = {
        sCacheAccount,
        sCacheMailbox,
        sCacheMessage,
        null,
        sCacheHostAuth,
        null,
        null,
        null};

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
        " where " + BodyColumns.MESSAGE_KEY + " in " + "(select " + BodyColumns.MESSAGE_KEY +
        " from " + Body.TABLE_NAME + " except select " + EmailContent.RECORD_ID + " from " +
        Message.TABLE_NAME + ')';

    private static final String DELETE_BODY = "delete from " + Body.TABLE_NAME +
        " where " + BodyColumns.MESSAGE_KEY + '=';

    private static final String ID_EQUALS = EmailContent.RECORD_ID + "=?";

    private static final String TRIGGER_MAILBOX_DELETE =
        "create trigger mailbox_delete before delete on " + Mailbox.TABLE_NAME +
        " begin" +
        " delete from " + Message.TABLE_NAME +
        "  where " + MessageColumns.MAILBOX_KEY + "=old." + EmailContent.RECORD_ID +
        "; delete from " + Message.UPDATED_TABLE_NAME +
        "  where " + MessageColumns.MAILBOX_KEY + "=old." + EmailContent.RECORD_ID +
        "; delete from " + Message.DELETED_TABLE_NAME +
        "  where " + MessageColumns.MAILBOX_KEY + "=old." + EmailContent.RECORD_ID +
        "; end";

    private static final ContentValues CONTENT_VALUES_RESET_NEW_MESSAGE_COUNT;

    public static final String MESSAGE_URI_PARAMETER_MAILBOX_ID = "mailboxId";

    static {
        // Email URI matching table
        UriMatcher matcher = sURIMatcher;

        // All accounts
        matcher.addURI(EMAIL_AUTHORITY, "account", ACCOUNT);
        // A specific account
        // insert into this URI causes a mailbox to be added to the account
        matcher.addURI(EMAIL_AUTHORITY, "account/#", ACCOUNT_ID);

        // Special URI to reset the new message count.  Only update works, and content values
        // will be ignored.
        matcher.addURI(EMAIL_AUTHORITY, "resetNewMessageCount", ACCOUNT_RESET_NEW_COUNT);
        matcher.addURI(EMAIL_AUTHORITY, "resetNewMessageCount/#", ACCOUNT_RESET_NEW_COUNT_ID);

        // All mailboxes
        matcher.addURI(EMAIL_AUTHORITY, "mailbox", MAILBOX);
        // A specific mailbox
        // insert into this URI causes a message to be added to the mailbox
        // ** NOTE For now, the accountKey must be set manually in the values!
        matcher.addURI(EMAIL_AUTHORITY, "mailbox/#", MAILBOX_ID);

        // All messages
        matcher.addURI(EMAIL_AUTHORITY, "message", MESSAGE);
        // A specific message
        // insert into this URI causes an attachment to be added to the message
        matcher.addURI(EMAIL_AUTHORITY, "message/#", MESSAGE_ID);

        // A specific attachment
        matcher.addURI(EMAIL_AUTHORITY, "attachment", ATTACHMENT);
        // A specific attachment (the header information)
        matcher.addURI(EMAIL_AUTHORITY, "attachment/#", ATTACHMENT_ID);
        // The attachments of a specific message (query only) (insert & delete TBD)
        matcher.addURI(EMAIL_AUTHORITY, "attachment/message/#", ATTACHMENTS_MESSAGE_ID);

        // All mail bodies
        matcher.addURI(EMAIL_AUTHORITY, "body", BODY);
        // A specific mail body
        matcher.addURI(EMAIL_AUTHORITY, "body/#", BODY_ID);

        // All hostauth records
        matcher.addURI(EMAIL_AUTHORITY, "hostauth", HOSTAUTH);
        // A specific hostauth
        matcher.addURI(EMAIL_AUTHORITY, "hostauth/#", HOSTAUTH_ID);

        // Atomically a constant value to a particular field of a mailbox/account
        matcher.addURI(EMAIL_AUTHORITY, "mailboxIdAddToField/#", MAILBOX_ID_ADD_TO_FIELD);
        matcher.addURI(EMAIL_AUTHORITY, "accountIdAddToField/#", ACCOUNT_ID_ADD_TO_FIELD);

        /**
         * THIS URI HAS SPECIAL SEMANTICS
         * ITS USE IS INTENDED FOR THE UI APPLICATION TO MARK CHANGES THAT NEED TO BE SYNCED BACK
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

        // All updated messages
        matcher.addURI(EMAIL_AUTHORITY, "updatedMessage", UPDATED_MESSAGE);
        // A specific updated message
        matcher.addURI(EMAIL_AUTHORITY, "updatedMessage/#", UPDATED_MESSAGE_ID);

        CONTENT_VALUES_RESET_NEW_MESSAGE_COUNT = new ContentValues();
        CONTENT_VALUES_RESET_NEW_MESSAGE_COUNT.put(Account.NEW_MESSAGE_COUNT, 0);
    }


    /**
     * Wrap the UriMatcher call so we can throw a runtime exception if an unknown Uri is passed in
     * @param uri the Uri to match
     * @return the match value
     */
    private static int findMatch(Uri uri, String methodName) {
        int match = sURIMatcher.match(uri);
        if (match < 0) {
            throw new IllegalArgumentException("Unknown uri: uri");
        } else if (Email.LOGD) {
            Log.v(TAG, methodName + ": uri=" + uri + ", match is " + match);
        }
        return match;
    }

    /*
     * Internal helper method for index creation.
     * Example:
     * "create index message_" + MessageColumns.FLAG_READ
     * + " on " + Message.TABLE_NAME + " (" + MessageColumns.FLAG_READ + ");"
     */
    /* package */
    static String createIndex(String tableName, String columnName) {
        return "create index " + tableName.toLowerCase() + '_' + columnName
            + " on " + tableName + " (" + columnName + ");";
    }

    static void createMessageTable(SQLiteDatabase db) {
        String messageColumns = MessageColumns.DISPLAY_NAME + " text, "
            + MessageColumns.TIMESTAMP + " integer, "
            + MessageColumns.SUBJECT + " text, "
            + MessageColumns.FLAG_READ + " integer, "
            + MessageColumns.FLAG_LOADED + " integer, "
            + MessageColumns.FLAG_FAVORITE + " integer, "
            + MessageColumns.FLAG_ATTACHMENT + " integer, "
            + MessageColumns.FLAGS + " integer, "
            + MessageColumns.CLIENT_ID + " integer, "
            + MessageColumns.MESSAGE_ID + " text, "
            + MessageColumns.MAILBOX_KEY + " integer, "
            + MessageColumns.ACCOUNT_KEY + " integer, "
            + MessageColumns.FROM_LIST + " text, "
            + MessageColumns.TO_LIST + " text, "
            + MessageColumns.CC_LIST + " text, "
            + MessageColumns.BCC_LIST + " text, "
            + MessageColumns.REPLY_TO_LIST + " text, "
            + MessageColumns.MEETING_INFO + " text, "
            + MessageColumns.SNIPPET + " text"
            + ");";

        // This String and the following String MUST have the same columns, except for the type
        // of those columns!
        String createString = " (" + EmailContent.RECORD_ID + " integer primary key autoincrement, "
            + SyncColumns.SERVER_ID + " text, "
            + SyncColumns.SERVER_TIMESTAMP + " integer, "
            + messageColumns;

        // For the updated and deleted tables, the id is assigned, but we do want to keep track
        // of the ORDER of updates using an autoincrement primary key.  We use the DATA column
        // at this point; it has no other function
        String altCreateString = " (" + EmailContent.RECORD_ID + " integer unique, "
            + SyncColumns.SERVER_ID + " text, "
            + SyncColumns.SERVER_TIMESTAMP + " integer, "
            + messageColumns;

        // The three tables have the same schema
        db.execSQL("create table " + Message.TABLE_NAME + createString);
        db.execSQL("create table " + Message.UPDATED_TABLE_NAME + altCreateString);
        db.execSQL("create table " + Message.DELETED_TABLE_NAME + altCreateString);

        String indexColumns[] = {
            MessageColumns.TIMESTAMP,
            MessageColumns.FLAG_READ,
            MessageColumns.FLAG_LOADED,
            MessageColumns.MAILBOX_KEY,
            SyncColumns.SERVER_ID
        };

        for (String columnName : indexColumns) {
            db.execSQL(createIndex(Message.TABLE_NAME, columnName));
        }

        // Deleting a Message deletes all associated Attachments
        // Deleting the associated Body cannot be done in a trigger, because the Body is stored
        // in a separate database, and trigger cannot operate on attached databases.
        db.execSQL("create trigger message_delete before delete on " + Message.TABLE_NAME +
                " begin delete from " + Attachment.TABLE_NAME +
                "  where " + AttachmentColumns.MESSAGE_KEY + "=old." + EmailContent.RECORD_ID +
                "; end");

        // Add triggers to keep unread count accurate per mailbox

        // NOTE: SQLite's before triggers are not safe when recursive triggers are involved.
        // Use caution when changing them.

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

        // Add triggers to update message count per mailbox

        // Insert a message.
        db.execSQL("create trigger message_count_message_insert after insert on " +
                Message.TABLE_NAME +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.MESSAGE_COUNT +
                '=' + MailboxColumns.MESSAGE_COUNT + "+1" +
                "  where " + EmailContent.RECORD_ID + "=NEW." + MessageColumns.MAILBOX_KEY +
                "; end");

        // Delete a message; if flagRead is zero, decrement the unread count of the msg's mailbox
        db.execSQL("create trigger message_count_message_delete after delete on " +
                Message.TABLE_NAME +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.MESSAGE_COUNT +
                '=' + MailboxColumns.MESSAGE_COUNT + "-1" +
                "  where " + EmailContent.RECORD_ID + "=OLD." + MessageColumns.MAILBOX_KEY +
                "; end");

        // Change a message's mailbox
        db.execSQL("create trigger message_count_message_move after update of " +
                MessageColumns.MAILBOX_KEY + " on " + Message.TABLE_NAME +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.MESSAGE_COUNT +
                '=' + MailboxColumns.MESSAGE_COUNT + "-1" +
                "  where " + EmailContent.RECORD_ID + "=OLD." + MessageColumns.MAILBOX_KEY +
                "; update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.MESSAGE_COUNT +
                '=' + MailboxColumns.MESSAGE_COUNT + "+1" +
                " where " + EmailContent.RECORD_ID + "=NEW." + MessageColumns.MAILBOX_KEY +
                "; end");
    }

    static void resetMessageTable(SQLiteDatabase db, int oldVersion, int newVersion) {
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
            + AccountColumns.PROTOCOL_VERSION + " text, "
            + AccountColumns.NEW_MESSAGE_COUNT + " integer, "
            + AccountColumns.SECURITY_FLAGS + " integer, "
            + AccountColumns.SECURITY_SYNC_KEY + " text, "
            + AccountColumns.SIGNATURE + " text "
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

    static void resetAccountTable(SQLiteDatabase db, int oldVersion, int newVersion) {
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

    static void resetHostAuthTable(SQLiteDatabase db, int oldVersion, int newVersion) {
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
            + MailboxColumns.SYNC_INTERVAL + " integer, "
            + MailboxColumns.SYNC_TIME + " integer, "
            + MailboxColumns.UNREAD_COUNT + " integer, "
            + MailboxColumns.FLAG_VISIBLE + " integer, "
            + MailboxColumns.FLAGS + " integer, "
            + MailboxColumns.VISIBLE_LIMIT + " integer, "
            + MailboxColumns.SYNC_STATUS + " text, "
            + MailboxColumns.MESSAGE_COUNT + " integer not null default 0"
            + ");";
        db.execSQL("create table " + Mailbox.TABLE_NAME + s);
        db.execSQL("create index mailbox_" + MailboxColumns.SERVER_ID
                + " on " + Mailbox.TABLE_NAME + " (" + MailboxColumns.SERVER_ID + ")");
        db.execSQL("create index mailbox_" + MailboxColumns.ACCOUNT_KEY
                + " on " + Mailbox.TABLE_NAME + " (" + MailboxColumns.ACCOUNT_KEY + ")");
        // Deleting a Mailbox deletes associated Messages in all three tables
        db.execSQL(TRIGGER_MAILBOX_DELETE);
    }

    static void resetMailboxTable(SQLiteDatabase db, int oldVersion, int newVersion) {
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
            + AttachmentColumns.ENCODING + " text, "
            + AttachmentColumns.CONTENT + " text, "
            + AttachmentColumns.FLAGS + " integer, "
            + AttachmentColumns.CONTENT_BYTES + " blob, "
            + AttachmentColumns.ACCOUNT_KEY + " integer"
            + ");";
        db.execSQL("create table " + Attachment.TABLE_NAME + s);
        db.execSQL(createIndex(Attachment.TABLE_NAME, AttachmentColumns.MESSAGE_KEY));
    }

    static void resetAttachmentTable(SQLiteDatabase db, int oldVersion, int newVersion) {
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
            + BodyColumns.TEXT_CONTENT + " text, "
            + BodyColumns.HTML_REPLY + " text, "
            + BodyColumns.TEXT_REPLY + " text, "
            + BodyColumns.SOURCE_MESSAGE_KEY + " text, "
            + BodyColumns.INTRO_TEXT + " text"
            + ");";
        db.execSQL("create table " + Body.TABLE_NAME + s);
        db.execSQL(createIndex(Body.TABLE_NAME, BodyColumns.MESSAGE_KEY));
    }

    static void upgradeBodyTable(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 5) {
            try {
                db.execSQL("drop table " + Body.TABLE_NAME);
                createBodyTable(db);
            } catch (SQLException e) {
            }
        } else if (oldVersion == 5) {
            try {
                db.execSQL("alter table " + Body.TABLE_NAME
                        + " add " + BodyColumns.INTRO_TEXT + " text");
            } catch (SQLException e) {
                // Shouldn't be needed unless we're debugging and interrupt the process
                Log.w(TAG, "Exception upgrading EmailProviderBody.db from v5 to v6", e);
            }
            oldVersion = 6;
        }
    }

    private SQLiteDatabase mDatabase;
    private SQLiteDatabase mBodyDatabase;

    public synchronized SQLiteDatabase getDatabase(Context context) {
        // Always return the cached database, if we've got one
        if (mDatabase != null) {
            return mDatabase;
        }

        // Whenever we create or re-cache the databases, make sure that we haven't lost one
        // to corruption
        checkDatabases();

        DatabaseHelper helper = new DatabaseHelper(context, DATABASE_NAME);
        mDatabase = helper.getWritableDatabase();
        if (mDatabase != null) {
            mDatabase.setLockingEnabled(true);
            BodyDatabaseHelper bodyHelper = new BodyDatabaseHelper(context, BODY_DATABASE_NAME);
            mBodyDatabase = bodyHelper.getWritableDatabase();
            if (mBodyDatabase != null) {
                mBodyDatabase.setLockingEnabled(true);
                String bodyFileName = mBodyDatabase.getPath();
                mDatabase.execSQL("attach \"" + bodyFileName + "\" as BodyDatabase");
            }
        }

        // Check for any orphaned Messages in the updated/deleted tables
        deleteOrphans(mDatabase, Message.UPDATED_TABLE_NAME);
        deleteOrphans(mDatabase, Message.DELETED_TABLE_NAME);

        return mDatabase;
    }

    /*package*/ static SQLiteDatabase getReadableDatabase(Context context) {
        DatabaseHelper helper = new EmailProvider().new DatabaseHelper(context, DATABASE_NAME);
        return helper.getReadableDatabase();
    }

    /** {@inheritDoc} */
    @Override
    public void shutdown() {
        if (mDatabase != null) {
            mDatabase.close();
            mDatabase = null;
        }
        if (mBodyDatabase != null) {
            mBodyDatabase.close();
            mBodyDatabase = null;
        }
    }

    /*package*/ static void deleteOrphans(SQLiteDatabase database, String tableName) {
        if (database != null) {
            // We'll look at all of the items in the table; there won't be many typically
            Cursor c = database.query(tableName, ORPHANS_PROJECTION, null, null, null, null, null);
            // Usually, there will be nothing in these tables, so make a quick check
            try {
                if (c.getCount() == 0) return;
                ArrayList<Long> foundMailboxes = new ArrayList<Long>();
                ArrayList<Long> notFoundMailboxes = new ArrayList<Long>();
                ArrayList<Long> deleteList = new ArrayList<Long>();
                String[] bindArray = new String[1];
                while (c.moveToNext()) {
                    // Get the mailbox key and see if we've already found this mailbox
                    // If so, we're fine
                    long mailboxId = c.getLong(ORPHANS_MAILBOX_KEY);
                    // If we already know this mailbox doesn't exist, mark the message for deletion
                    if (notFoundMailboxes.contains(mailboxId)) {
                        deleteList.add(c.getLong(ORPHANS_ID));
                    // If we don't know about this mailbox, we'll try to find it
                    } else if (!foundMailboxes.contains(mailboxId)) {
                        bindArray[0] = Long.toString(mailboxId);
                        Cursor boxCursor = database.query(Mailbox.TABLE_NAME,
                                Mailbox.ID_PROJECTION, WHERE_ID, bindArray, null, null, null);
                        try {
                            // If it exists, we'll add it to the "found" mailboxes
                            if (boxCursor.moveToFirst()) {
                                foundMailboxes.add(mailboxId);
                            // Otherwise, we'll add to "not found" and mark the message for deletion
                            } else {
                                notFoundMailboxes.add(mailboxId);
                                deleteList.add(c.getLong(ORPHANS_ID));
                            }
                        } finally {
                            boxCursor.close();
                        }
                    }
                }
                // Now, delete the orphan messages
                for (long messageId: deleteList) {
                    bindArray[0] = Long.toString(messageId);
                    database.delete(tableName, WHERE_ID, bindArray);
                }
            } finally {
                c.close();
            }
        }
    }

    private class BodyDatabaseHelper extends SQLiteOpenHelper {
        BodyDatabaseHelper(Context context, String name) {
            super(context, name, null, BODY_DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.d(TAG, "Creating EmailProviderBody database");
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
        Context mContext;

        DatabaseHelper(Context context, String name) {
            super(context, name, null, DATABASE_VERSION);
            mContext = context;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.d(TAG, "Creating EmailProvider database");
            // Create all tables here; each class has its own method
            createMessageTable(db);
            createAttachmentTable(db);
            createMailboxTable(db);
            createHostAuthTable(db);
            createAccountTable(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // For versions prior to 5, delete all data
            // Versions >= 5 require that data be preserved!
            if (oldVersion < 5) {
                android.accounts.Account[] accounts = AccountManager.get(mContext)
                        .getAccountsByType(Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                for (android.accounts.Account account: accounts) {
                    AccountManager.get(mContext).removeAccount(account, null, null);
                }
                resetMessageTable(db, oldVersion, newVersion);
                resetAttachmentTable(db, oldVersion, newVersion);
                resetMailboxTable(db, oldVersion, newVersion);
                resetHostAuthTable(db, oldVersion, newVersion);
                resetAccountTable(db, oldVersion, newVersion);
                return;
            }
            if (oldVersion == 5) {
                // Message Tables: Add SyncColumns.SERVER_TIMESTAMP
                try {
                    db.execSQL("alter table " + Message.TABLE_NAME
                            + " add column " + SyncColumns.SERVER_TIMESTAMP + " integer" + ";");
                    db.execSQL("alter table " + Message.UPDATED_TABLE_NAME
                            + " add column " + SyncColumns.SERVER_TIMESTAMP + " integer" + ";");
                    db.execSQL("alter table " + Message.DELETED_TABLE_NAME
                            + " add column " + SyncColumns.SERVER_TIMESTAMP + " integer" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from v5 to v6", e);
                }
                oldVersion = 6;
            }
            if (oldVersion == 6) {
                // Use the newer mailbox_delete trigger
                db.execSQL("drop trigger mailbox_delete;");
                db.execSQL(TRIGGER_MAILBOX_DELETE);
                oldVersion = 7;
            }
            if (oldVersion == 7) {
                // add the security (provisioning) column
                try {
                    db.execSQL("alter table " + Account.TABLE_NAME
                            + " add column " + AccountColumns.SECURITY_FLAGS + " integer" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 7 to 8 " + e);
                }
                oldVersion = 8;
            }
            if (oldVersion == 8) {
                // accounts: add security sync key & user signature columns
                try {
                    db.execSQL("alter table " + Account.TABLE_NAME
                            + " add column " + AccountColumns.SECURITY_SYNC_KEY + " text" + ";");
                    db.execSQL("alter table " + Account.TABLE_NAME
                            + " add column " + AccountColumns.SIGNATURE + " text" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 8 to 9 " + e);
                }
                oldVersion = 9;
            }
            if (oldVersion == 9) {
                // Message: add meeting info column into Message tables
                try {
                    db.execSQL("alter table " + Message.TABLE_NAME
                            + " add column " + MessageColumns.MEETING_INFO + " text" + ";");
                    db.execSQL("alter table " + Message.UPDATED_TABLE_NAME
                            + " add column " + MessageColumns.MEETING_INFO + " text" + ";");
                    db.execSQL("alter table " + Message.DELETED_TABLE_NAME
                            + " add column " + MessageColumns.MEETING_INFO + " text" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 9 to 10 " + e);
                }
                oldVersion = 10;
            }
            if (oldVersion == 10) {
                // Attachment: add content and flags columns
                try {
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + AttachmentColumns.CONTENT + " text" + ";");
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + AttachmentColumns.FLAGS + " integer" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 10 to 11 " + e);
                }
                oldVersion = 11;
            }
            if (oldVersion == 11) {
                // Attachment: add content_bytes
                try {
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + AttachmentColumns.CONTENT_BYTES + " blob" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 11 to 12 " + e);
                }
                oldVersion = 12;
            }
            if (oldVersion == 12) {
                try {
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + Mailbox.MESSAGE_COUNT
                                    +" integer not null default 0" + ";");
                    recalculateMessageCount(db);
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 12 to 13 " + e);
                }
                oldVersion = 13;
            }
            if (oldVersion == 13) {
                try {
                    db.execSQL("alter table " + Message.TABLE_NAME
                            + " add column " + Message.SNIPPET
                                    +" text" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 13 to 14 " + e);
                }
                oldVersion = 14;
            }
            if (oldVersion == 14) {
                try {
                    db.execSQL("alter table " + Message.DELETED_TABLE_NAME
                            + " add column " + Message.SNIPPET +" text" + ";");
                    db.execSQL("alter table " + Message.UPDATED_TABLE_NAME
                            + " add column " + Message.SNIPPET +" text" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 14 to 15 " + e);
                }
                oldVersion = 15;
            }
            if (oldVersion == 15) {
                try {
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + Attachment.ACCOUNT_KEY +" integer" + ";");
                    // Update all existing attachments to add the accountKey data
                    db.execSQL("update " + Attachment.TABLE_NAME + " set " +
                            Attachment.ACCOUNT_KEY + "= (SELECT " + Message.TABLE_NAME + "." +
                            Message.ACCOUNT_KEY + " from " + Message.TABLE_NAME + " where " +
                            Message.TABLE_NAME + "." + Message.RECORD_ID + " = " +
                            Attachment.TABLE_NAME + "." + Attachment.MESSAGE_KEY + ")");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 15 to 16 " + e);
                }
                oldVersion = 16;
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        final int match = findMatch(uri, "delete");
        Context context = getContext();
        // Pick the correct database for this operation
        // If we're in a transaction already (which would happen during applyBatch), then the
        // body database is already attached to the email database and any attempt to use the
        // body database directly will result in a SQLiteException (the database is locked)
        SQLiteDatabase db = getDatabase(context);
        int table = match >> BASE_SHIFT;
        String id = "0";
        boolean messageDeletion = false;
        ContentResolver resolver = context.getContentResolver();

        ContentCache cache = CONTENT_CACHES[table];
        String tableName = TABLE_NAMES[table];
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
                    //  1) Begin a transaction, ensuring that both databases are affected atomically
                    //  2) Do the requested deletion, with cascading deletions handled in triggers
                    //  3) End the transaction, committing all changes atomically
                    //
                    // Bodies are auto-deleted here;  Attachments are auto-deleted via trigger
                    messageDeletion = true;
                    db.beginTransaction();
                    resolver.notifyChange(Message.NOTIFIER_URI, null);
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
                    if (cache != null) {
                        cache.lock(id);
                    }
                    try {
                        result = db.delete(tableName, whereWithId(id, selection), selectionArgs);
                        if (cache != null) {
                            switch(match) {
                                case ACCOUNT_ID:
                                    // Account deletion will clear all of the caches, as HostAuth's,
                                    // Mailboxes, and Messages will be deleted in the process
                                    sCacheMailbox.invalidate("Delete", uri, selection);
                                    sCacheHostAuth.invalidate("Delete", uri, selection);
                                    //$FALL-THROUGH$
                                case MAILBOX_ID:
                                    // Mailbox deletion will clear the Message cache
                                    sCacheMessage.invalidate("Delete", uri, selection);
                                    //$FALL-THROUGH$
                                case SYNCED_MESSAGE_ID:
                                case MESSAGE_ID:
                                case HOSTAUTH_ID:
                                    cache.invalidate("Delete", uri, selection);
                                    break;
                            }
                        }
                    } finally {
                        if (cache != null) {
                            cache.unlock(id);
                        }
                   }
                    break;
                case ATTACHMENTS_MESSAGE_ID:
                    // All attachments for the given message
                    id = uri.getPathSegments().get(2);
                    result = db.delete(tableName,
                            whereWith(Attachment.MESSAGE_KEY + "=" + id, selection), selectionArgs);
                    break;

                case BODY:
                case MESSAGE:
                case DELETED_MESSAGE:
                case UPDATED_MESSAGE:
                case ATTACHMENT:
                case MAILBOX:
                case ACCOUNT:
                case HOSTAUTH:
                    switch(match) {
                        // See the comments above for deletion of ACCOUNT_ID, etc
                        case ACCOUNT:
                            sCacheMailbox.invalidate("Delete", uri, selection);
                            sCacheHostAuth.invalidate("Delete", uri, selection);
                            //$FALL-THROUGH$
                        case MAILBOX:
                            sCacheMessage.invalidate("Delete", uri, selection);
                            //$FALL-THROUGH$
                        case MESSAGE:
                        case HOSTAUTH:
                            cache.invalidate("Delete", uri, selection);
                            break;
                    }
                    result = db.delete(tableName, selection, selectionArgs);
                    break;

                default:
                    throw new IllegalArgumentException("Unknown URI " + uri);
            }
            if (messageDeletion) {
                if (match == MESSAGE_ID) {
                    // Delete the Body record associated with the deleted message
                    db.execSQL(DELETE_BODY + id);
                } else {
                    // Delete any orphaned Body records
                    db.execSQL(DELETE_ORPHAN_BODIES);
                }
                db.setTransactionSuccessful();
            }
        } catch (SQLiteException e) {
            checkDatabases();
            throw e;
        } finally {
            if (messageDeletion) {
                db.endTransaction();
            }
        }

        // Notify all existing cursors.
        resolver.notifyChange(EmailContent.CONTENT_URI, null);
        return result;
    }

    @Override
    // Use the email- prefix because message, mailbox, and account are so generic (e.g. SMS, IM)
    public String getType(Uri uri) {
        int match = findMatch(uri, "getType");
        switch (match) {
            case BODY_ID:
                return "vnd.android.cursor.item/email-body";
            case BODY:
                return "vnd.android.cursor.dir/email-body";
            case UPDATED_MESSAGE_ID:
            case MESSAGE_ID:
                // NOTE: According to the framework folks, we're supposed to invent mime types as
                // a way of passing information to drag & drop recipients.
                // If there's a mailboxId parameter in the url, we respond with a mime type that
                // has -n appended, where n is the mailboxId of the message.  The drag & drop code
                // uses this information to know not to allow dragging the item to its own mailbox
                String mimeType = EMAIL_MESSAGE_MIME_TYPE;
                String mailboxId = uri.getQueryParameter(MESSAGE_URI_PARAMETER_MAILBOX_ID);
                if (mailboxId != null) {
                    mimeType += "-" + mailboxId;
                }
                return mimeType;
            case UPDATED_MESSAGE:
            case MESSAGE:
                return "vnd.android.cursor.dir/email-message";
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
                return EMAIL_ATTACHMENT_MIME_TYPE;
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
        int match = findMatch(uri, "insert");
        Context context = getContext();
        ContentResolver resolver = context.getContentResolver();

        // See the comment at delete(), above
        SQLiteDatabase db = getDatabase(context);
        int table = match >> BASE_SHIFT;
        long id;

        // We do NOT allow setting of unreadCount/messageCount via the provider
        // These columns are maintained via triggers
        if (match == MAILBOX_ID || match == MAILBOX) {
            values.put(MailboxColumns.UNREAD_COUNT, 0);
            values.put(MailboxColumns.MESSAGE_COUNT, 0);
        }

        Uri resultUri = null;

        try {
            switch (match) {
                case MESSAGE:
                    resolver.notifyChange(Message.NOTIFIER_URI, null);
                    //$FALL-THROUGH$
                case UPDATED_MESSAGE:
                case DELETED_MESSAGE:
                case BODY:
                case ATTACHMENT:
                case MAILBOX:
                case ACCOUNT:
                case HOSTAUTH:
                    id = db.insert(TABLE_NAMES[table], "foo", values);
                    resultUri = ContentUris.withAppendedId(uri, id);
                    // Clients shouldn't normally be adding rows to these tables, as they are
                    // maintained by triggers.  However, we need to be able to do this for unit
                    // testing, so we allow the insert and then throw the same exception that we
                    // would if this weren't allowed.
                    if (match == UPDATED_MESSAGE || match == DELETED_MESSAGE) {
                        throw new IllegalArgumentException("Unknown URL " + uri);
                    }
                    if (match == ATTACHMENT) {
                        int flags = 0;
                        if (values.containsKey(Attachment.FLAGS)) {
                            flags = values.getAsInteger(Attachment.FLAGS);
                        }
                        // Report all new attachments to the download service
                        AttachmentDownloadService.attachmentChanged(id, flags);
                    }
                    break;
                case MAILBOX_ID:
                    // This implies adding a message to a mailbox
                    // Hmm, a problem here is that we can't link the account as well, so it must be
                    // already in the values...
                    id = Long.parseLong(uri.getPathSegments().get(1));
                    values.put(MessageColumns.MAILBOX_KEY, id);
                    return insert(Message.CONTENT_URI, values); // Recurse
                case MESSAGE_ID:
                    // This implies adding an attachment to a message.
                    id = Long.parseLong(uri.getPathSegments().get(1));
                    values.put(AttachmentColumns.MESSAGE_KEY, id);
                    return insert(Attachment.CONTENT_URI, values); // Recurse
                case ACCOUNT_ID:
                    // This implies adding a mailbox to an account.
                    id = Long.parseLong(uri.getPathSegments().get(1));
                    values.put(MailboxColumns.ACCOUNT_KEY, id);
                    return insert(Mailbox.CONTENT_URI, values); // Recurse
                case ATTACHMENTS_MESSAGE_ID:
                    id = db.insert(TABLE_NAMES[table], "foo", values);
                    resultUri = ContentUris.withAppendedId(Attachment.CONTENT_URI, id);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown URL " + uri);
            }
        } catch (SQLiteException e) {
            checkDatabases();
            throw e;
        }

        // Notify all existing cursors.
        resolver.notifyChange(EmailContent.CONTENT_URI, null);
        return resultUri;
    }

    @Override
    public boolean onCreate() {
        checkDatabases();
        return false;
    }

    /**
     * The idea here is that the two databases (EmailProvider.db and EmailProviderBody.db must
     * always be in sync (i.e. there are two database or NO databases).  This code will delete
     * any "orphan" database, so that both will be created together.  Note that an "orphan" database
     * will exist after either of the individual databases is deleted due to data corruption.
     */
    public void checkDatabases() {
        // Uncache the databases
        if (mDatabase != null) {
            mDatabase = null;
        }
        if (mBodyDatabase != null) {
            mBodyDatabase = null;
        }
        // Look for orphans, and delete as necessary; these must always be in sync
        File databaseFile = getContext().getDatabasePath(DATABASE_NAME);
        File bodyFile = getContext().getDatabasePath(BODY_DATABASE_NAME);

        // TODO Make sure attachments are deleted
        if (databaseFile.exists() && !bodyFile.exists()) {
            Log.w(TAG, "Deleting orphaned EmailProvider database...");
            databaseFile.delete();
        } else if (bodyFile.exists() && !databaseFile.exists()) {
            Log.w(TAG, "Deleting orphaned EmailProviderBody database...");
            bodyFile.delete();
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        long time = 0L;
        if (Email.DEBUG) {
            time = System.nanoTime();
        }
        Cursor c = null;
        int match = findMatch(uri, "query");
        Context context = getContext();
        // See the comment at delete(), above
        SQLiteDatabase db = getDatabase(context);
        int table = match >> BASE_SHIFT;
        String limit = uri.getQueryParameter(EmailContent.PARAMETER_LIMIT);
        String id;

        // Find the cache for this query's table (if any)
        ContentCache cache = null;
        String tableName = TABLE_NAMES[table];
        // We can only use the cache if there's no selection
        if (selection == null) {
            cache = CONTENT_CACHES[table];
        }
        if (cache == null) {
            ContentCache.notCacheable(uri, selection);
        }

        try {
            switch (match) {
                case BODY:
                case MESSAGE:
                case UPDATED_MESSAGE:
                case DELETED_MESSAGE:
                case ATTACHMENT:
                case MAILBOX:
                case ACCOUNT:
                case HOSTAUTH:
                    c = db.query(tableName, projection,
                            selection, selectionArgs, null, null, sortOrder, limit);
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
                    if (cache != null) {
                        c = cache.getCachedCursor(id, projection);
                    }
                    if (c == null) {
                        CacheToken token = null;
                        if (cache != null) {
                            token = cache.getCacheToken(id);
                        }
                        c = db.query(tableName, projection, whereWithId(id, selection),
                                selectionArgs, null, null, sortOrder, limit);
                        if (cache != null) {
                            c = cache.putCursor(c, id, projection, token);
                        }
                    }
                    break;
                case ATTACHMENTS_MESSAGE_ID:
                    // All attachments for the given message
                    id = uri.getPathSegments().get(2);
                    c = db.query(Attachment.TABLE_NAME, projection,
                            whereWith(Attachment.MESSAGE_KEY + "=" + id, selection),
                            selectionArgs, null, null, sortOrder, limit);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown URI " + uri);
            }
        } catch (SQLiteException e) {
            checkDatabases();
            throw e;
        } catch (RuntimeException e) {
            checkDatabases();
            e.printStackTrace();
            throw e;
        } finally {
            if (cache != null && Email.DEBUG) {
                cache.recordQueryTime(c, System.nanoTime() - time);
            }
        }

        if ((c != null) && !isTemporary()) {
            c.setNotificationUri(getContext().getContentResolver(), uri);
        }
        return c;
    }

    private String whereWithId(String id, String selection) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("_id=");
        sb.append(id);
        if (selection != null) {
            sb.append(" AND (");
            sb.append(selection);
            sb.append(')');
        }
        return sb.toString();
    }

    /**
     * Combine a locally-generated selection with a user-provided selection
     *
     * This introduces risk that the local selection might insert incorrect chars
     * into the SQL, so use caution.
     *
     * @param where locally-generated selection, must not be null
     * @param selection user-provided selection, may be null
     * @return a single selection string
     */
    private String whereWith(String where, String selection) {
        if (selection == null) {
            return where;
        }
        StringBuilder sb = new StringBuilder(where);
        sb.append(" AND (");
        sb.append(selection);
        sb.append(')');

        return sb.toString();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // Handle this special case the fastest possible way
        if (uri == INTEGRITY_CHECK_URI) {
            checkDatabases();
            return 0;
        }

        // Notify all existing cursors, except for ACCOUNT_RESET_NEW_COUNT(_ID)
        Uri notificationUri = EmailContent.CONTENT_URI;

        int match = findMatch(uri, "update");
        Context context = getContext();
        ContentResolver resolver = context.getContentResolver();
        // See the comment at delete(), above
        SQLiteDatabase db = getDatabase(context);
        int table = match >> BASE_SHIFT;
        int result;

        // We do NOT allow setting of unreadCount/messageCount via the provider
        // These columns are maintained via triggers
        if (match == MAILBOX_ID || match == MAILBOX) {
            values.remove(MailboxColumns.UNREAD_COUNT);
            values.remove(MailboxColumns.MESSAGE_COUNT);
        }

        ContentCache cache = CONTENT_CACHES[table];
        String tableName = TABLE_NAMES[table];
        String id;

        try {
            switch (match) {
                case MAILBOX_ID_ADD_TO_FIELD:
                case ACCOUNT_ID_ADD_TO_FIELD:
                    id = uri.getPathSegments().get(1);
                    String field = values.getAsString(EmailContent.FIELD_COLUMN_NAME);
                    Long add = values.getAsLong(EmailContent.ADD_COLUMN_NAME);
                    if (field == null || add == null) {
                        throw new IllegalArgumentException("No field/add specified " + uri);
                    }
                    ContentValues actualValues = new ContentValues();
                    if (cache != null) {
                        cache.lock(id);
                    }
                    try {
                        db.beginTransaction();
                        try {
                            Cursor c = db.query(tableName,
                                    new String[] {EmailContent.RECORD_ID, field},
                                    whereWithId(id, selection),
                                    selectionArgs, null, null, null);
                            try {
                                result = 0;
                                String[] bind = new String[1];
                                if (c.moveToNext()) {
                                    bind[0] = c.getString(0); // _id
                                    long value = c.getLong(1) + add;
                                    actualValues.put(field, value);
                                    result = db.update(tableName, actualValues, ID_EQUALS, bind);
                                }
                                db.setTransactionSuccessful();
                            } finally {
                                c.close();
                            }
                        } finally {
                            db.endTransaction();
                        }
                    } finally {
                        if (cache != null) {
                            cache.unlock(id, actualValues);
                        }
                    }
                    break;
                case SYNCED_MESSAGE_ID:
                    resolver.notifyChange(Message.NOTIFIER_URI, null);
                  //$FALL-THROUGH$
                case UPDATED_MESSAGE_ID:
                case MESSAGE_ID:
                case BODY_ID:
                case ATTACHMENT_ID:
                case MAILBOX_ID:
                case ACCOUNT_ID:
                case HOSTAUTH_ID:
                    id = uri.getPathSegments().get(1);
                    if (cache != null) {
                        cache.lock(id);
                    }
                    try {
                        if (match == SYNCED_MESSAGE_ID) {
                            // For synced messages, first copy the old message to the updated table
                            // Note the insert or ignore semantics, guaranteeing that only the first
                            // update will be reflected in the updated message table; therefore this
                            // row will always have the "original" data
                            db.execSQL(UPDATED_MESSAGE_INSERT + id);
                        } else if (match == MESSAGE_ID) {
                            db.execSQL(UPDATED_MESSAGE_DELETE + id);
                        }
                        result = db.update(tableName, values, whereWithId(id, selection),
                                selectionArgs);
                    } catch (SQLiteException e) {
                        // Null out values (so they aren't cached) and re-throw
                        values = null;
                        throw e;
                    } finally {
                        if (cache != null) {
                            cache.unlock(id, values);
                        }
                    }
                    if (match == ATTACHMENT_ID) {
                        if (values.containsKey(Attachment.FLAGS)) {
                            int flags = values.getAsInteger(Attachment.FLAGS);
                            AttachmentDownloadService.attachmentChanged(
                                    Integer.parseInt(id), flags);
                        }
                    }
                    break;
                case BODY:
                case MESSAGE:
                case UPDATED_MESSAGE:
                case ATTACHMENT:
                case MAILBOX:
                case ACCOUNT:
                case HOSTAUTH:
                    switch(match) {
                        case MESSAGE:
                        case ACCOUNT:
                        case MAILBOX:
                        case HOSTAUTH:
                            // If we're doing some generic update, the whole cache needs to be
                            // invalidated.  This case should be quite rare
                            cache.invalidate("Update", uri, selection);
                            break;
                    }
                    result = db.update(tableName, values, selection, selectionArgs);
                    break;
                case ACCOUNT_RESET_NEW_COUNT_ID:
                    id = uri.getPathSegments().get(1);
                    if (cache != null) {
                        cache.lock(id);
                    }
                    try {
                        result = db.update(tableName, CONTENT_VALUES_RESET_NEW_MESSAGE_COUNT,
                                whereWithId(id, selection), selectionArgs);
                    } finally {
                        if (cache != null) {
                            cache.unlock(id, values);
                        }
                    }
                    notificationUri = Account.CONTENT_URI; // Only notify account cursors.
                    break;
                case ACCOUNT_RESET_NEW_COUNT:
                    result = db.update(tableName, CONTENT_VALUES_RESET_NEW_MESSAGE_COUNT,
                            selection, selectionArgs);
                    // Affects all accounts.  Just invalidate all account cache.
                    cache.invalidate("Reset all new counts", null, null);
                    notificationUri = Account.CONTENT_URI; // Only notify account cursors.
                    break;
                default:
                    throw new IllegalArgumentException("Unknown URI " + uri);
            }
        } catch (SQLiteException e) {
            checkDatabases();
            throw e;
        }

        resolver.notifyChange(notificationUri, null);
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
        db.beginTransaction();
        try {
            ContentProviderResult[] results = super.applyBatch(operations);
            db.setTransactionSuccessful();
            return results;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Count the number of messages in each mailbox, and update the message count column.
     */
    /* package */ static void recalculateMessageCount(SQLiteDatabase db) {
        db.execSQL("update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.MESSAGE_COUNT +
                "= (select count(*) from " + Message.TABLE_NAME +
                " where " + Message.MAILBOX_KEY + " = " +
                    Mailbox.TABLE_NAME + "." + EmailContent.RECORD_ID + ")");
    }
}
