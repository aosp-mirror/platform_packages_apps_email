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

import android.accounts.AccountManager;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.UriMatcher;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.Log;

import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.provider.ContentCache.CacheToken;
import com.android.email.service.AttachmentDownloadService;
import com.android.emailcommon.AccountManagerTypes;
import com.android.emailcommon.CalendarProviderStub;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.AttachmentColumns;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.BodyColumns;
import com.android.emailcommon.provider.EmailContent.HostAuthColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.PolicyColumns;
import com.android.emailcommon.provider.EmailContent.QuickResponseColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.provider.QuickResponse;
import com.android.emailcommon.service.LegacyPolicySet;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EmailProvider extends ContentProvider {

    private static final String TAG = "EmailProvider";

    protected static final String DATABASE_NAME = "EmailProvider.db";
    protected static final String BODY_DATABASE_NAME = "EmailProviderBody.db";
    protected static final String BACKUP_DATABASE_NAME = "EmailProviderBackup.db";

    public static final String ACTION_ATTACHMENT_UPDATED = "com.android.email.ATTACHMENT_UPDATED";
    public static final String ATTACHMENT_UPDATED_EXTRA_FLAGS =
        "com.android.email.ATTACHMENT_UPDATED_FLAGS";

    /**
     * Notifies that changes happened. Certain UI components, e.g., widgets, can register for this
     * {@link android.content.Intent} and update accordingly. However, this can be very broad and
     * is NOT the preferred way of getting notification.
     */
    public static final String ACTION_NOTIFY_MESSAGE_LIST_DATASET_CHANGED =
            "com.android.email.MESSAGE_LIST_DATASET_CHANGED";

    public static final String EMAIL_MESSAGE_MIME_TYPE =
        "vnd.android.cursor.item/email-message";
    public static final String EMAIL_ATTACHMENT_MIME_TYPE =
        "vnd.android.cursor.item/email-attachment";

    public static final Uri INTEGRITY_CHECK_URI =
        Uri.parse("content://" + EmailContent.AUTHORITY + "/integrityCheck");
    public static final Uri ACCOUNT_BACKUP_URI =
        Uri.parse("content://" + EmailContent.AUTHORITY + "/accountBackup");

    /** Appended to the notification URI for delete operations */
    public static final String NOTIFICATION_OP_DELETE = "delete";
    /** Appended to the notification URI for insert operations */
    public static final String NOTIFICATION_OP_INSERT = "insert";
    /** Appended to the notification URI for update operations */
    public static final String NOTIFICATION_OP_UPDATE = "update";

    // Definitions for our queries looking for orphaned messages
    private static final String[] ORPHANS_PROJECTION
        = new String[] {MessageColumns.ID, MessageColumns.MAILBOX_KEY};
    private static final int ORPHANS_ID = 0;
    private static final int ORPHANS_MAILBOX_KEY = 1;

    private static final String WHERE_ID = EmailContent.RECORD_ID + "=?";

    // This is not a hard limit on accounts, per se, but beyond this, we can't guarantee that all
    // critical mailboxes, host auth's, accounts, and policies are cached
    private static final int MAX_CACHED_ACCOUNTS = 16;
    // Inbox, Drafts, Sent, Outbox, Trash, and Search (these boxes are cached when possible)
    private static final int NUM_ALWAYS_CACHED_MAILBOXES = 6;

    // We'll cache the following four tables; sizes are best estimates of effective values
    private final ContentCache mCacheAccount =
        new ContentCache("Account", Account.CONTENT_PROJECTION, MAX_CACHED_ACCOUNTS);
    private final ContentCache mCacheHostAuth =
        new ContentCache("HostAuth", HostAuth.CONTENT_PROJECTION, MAX_CACHED_ACCOUNTS * 2);
    /*package*/ final ContentCache mCacheMailbox =
        new ContentCache("Mailbox", Mailbox.CONTENT_PROJECTION,
                MAX_CACHED_ACCOUNTS * (NUM_ALWAYS_CACHED_MAILBOXES + 2));
    private final ContentCache mCacheMessage =
        new ContentCache("Message", Message.CONTENT_PROJECTION, 8);
    private final ContentCache mCachePolicy =
        new ContentCache("Policy", Policy.CONTENT_PROJECTION, MAX_CACHED_ACCOUNTS);

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
    // Version 17: Add parentKey to Mailbox table
    // Version 18: Copy Mailbox.displayName to Mailbox.serverId for all IMAP & POP3 mailboxes.
    //             Column Mailbox.serverId is used for the server-side pathname of a mailbox.
    // Version 19: Add Policy table; add policyKey to Account table and trigger to delete an
    //             Account's policy when the Account is deleted
    // Version 20: Add new policies to Policy table
    // Version 21: Add lastSeenMessageKey column to Mailbox table
    // Version 22: Upgrade path for IMAP/POP accounts to integrate with AccountManager
    // Version 23: Add column to mailbox table for time of last access
    // Version 24: Add column to hostauth table for client cert alias
    // Version 25: Added QuickResponse table
    // Version 26: Update IMAP accounts to add FLAG_SUPPORTS_SEARCH flag
    // Version 27: Add protocolSearchInfo to Message table
    // Version 28: Add notifiedMessageId and notifiedMessageCount to Account

    public static final int DATABASE_VERSION = 28;

    // Any changes to the database format *must* include update-in-place code.
    // Original version: 2
    // Version 3: Add "sourceKey" column
    // Version 4: Database wipe required; changing AccountManager interface w/Exchange
    // Version 5: Database wipe required; changing AccountManager interface w/Exchange
    // Version 6: Adding Body.mIntroText column
    public static final int BODY_DATABASE_VERSION = 6;

    private static final int ACCOUNT_BASE = 0;
    private static final int ACCOUNT = ACCOUNT_BASE;
    private static final int ACCOUNT_ID = ACCOUNT_BASE + 1;
    private static final int ACCOUNT_ID_ADD_TO_FIELD = ACCOUNT_BASE + 2;
    private static final int ACCOUNT_RESET_NEW_COUNT = ACCOUNT_BASE + 3;
    private static final int ACCOUNT_RESET_NEW_COUNT_ID = ACCOUNT_BASE + 4;
    private static final int ACCOUNT_DEFAULT_ID = ACCOUNT_BASE + 5;

    private static final int MAILBOX_BASE = 0x1000;
    private static final int MAILBOX = MAILBOX_BASE;
    private static final int MAILBOX_ID = MAILBOX_BASE + 1;
    private static final int MAILBOX_ID_FROM_ACCOUNT_AND_TYPE = MAILBOX_BASE + 2;
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

    private static final int POLICY_BASE = 0x7000;
    private static final int POLICY = POLICY_BASE;
    private static final int POLICY_ID = POLICY_BASE + 1;

    private static final int QUICK_RESPONSE_BASE = 0x8000;
    private static final int QUICK_RESPONSE = QUICK_RESPONSE_BASE;
    private static final int QUICK_RESPONSE_ID = QUICK_RESPONSE_BASE + 1;
    private static final int QUICK_RESPONSE_ACCOUNT_ID = QUICK_RESPONSE_BASE + 2;

    // MUST ALWAYS EQUAL THE LAST OF THE PREVIOUS BASE CONSTANTS
    private static final int LAST_EMAIL_PROVIDER_DB_BASE = QUICK_RESPONSE_BASE;

    // DO NOT CHANGE BODY_BASE!!
    private static final int BODY_BASE = LAST_EMAIL_PROVIDER_DB_BASE + 0x1000;
    private static final int BODY = BODY_BASE;
    private static final int BODY_ID = BODY_BASE + 1;

    private static final int BASE_SHIFT = 12;  // 12 bits to the base type: 0, 0x1000, 0x2000, etc.

    // TABLE_NAMES MUST remain in the order of the BASE constants above (e.g. ACCOUNT_BASE = 0x0000,
    // MESSAGE_BASE = 0x1000, etc.)
    private static final String[] TABLE_NAMES = {
        Account.TABLE_NAME,
        Mailbox.TABLE_NAME,
        Message.TABLE_NAME,
        Attachment.TABLE_NAME,
        HostAuth.TABLE_NAME,
        Message.UPDATED_TABLE_NAME,
        Message.DELETED_TABLE_NAME,
        Policy.TABLE_NAME,
        QuickResponse.TABLE_NAME,
        Body.TABLE_NAME
    };

    // CONTENT_CACHES MUST remain in the order of the BASE constants above
    private final ContentCache[] mContentCaches = {
        mCacheAccount,
        mCacheMailbox,
        mCacheMessage,
        null, // Attachment
        mCacheHostAuth,
        null, // Updated message
        null, // Deleted message
        mCachePolicy,
        null, // Quick response
        null  // Body
    };

    // CACHE_PROJECTIONS MUST remain in the order of the BASE constants above
    private static final String[][] CACHE_PROJECTIONS = {
        Account.CONTENT_PROJECTION,
        Mailbox.CONTENT_PROJECTION,
        Message.CONTENT_PROJECTION,
        null, // Attachment
        HostAuth.CONTENT_PROJECTION,
        null, // Updated message
        null, // Deleted message
        Policy.CONTENT_PROJECTION,
        null,  // Quick response
        null  // Body
    };

    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final String MAILBOX_PRE_CACHE_SELECTION = MailboxColumns.TYPE + " IN (" +
        Mailbox.TYPE_INBOX + "," + Mailbox.TYPE_DRAFTS + "," + Mailbox.TYPE_TRASH + "," +
        Mailbox.TYPE_SENT + "," + Mailbox.TYPE_SEARCH + "," + Mailbox.TYPE_OUTBOX + ")";

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

    private static final String TRIGGER_ACCOUNT_DELETE =
        "create trigger account_delete before delete on " + Account.TABLE_NAME +
        " begin delete from " + Mailbox.TABLE_NAME +
        " where " + MailboxColumns.ACCOUNT_KEY + "=old." + EmailContent.RECORD_ID +
        "; delete from " + HostAuth.TABLE_NAME +
        " where " + EmailContent.RECORD_ID + "=old." + AccountColumns.HOST_AUTH_KEY_RECV +
        "; delete from " + HostAuth.TABLE_NAME +
        " where " + EmailContent.RECORD_ID + "=old." + AccountColumns.HOST_AUTH_KEY_SEND +
        "; delete from " + Policy.TABLE_NAME +
        " where " + EmailContent.RECORD_ID + "=old." + AccountColumns.POLICY_KEY +
        "; end";

    private static final ContentValues CONTENT_VALUES_RESET_NEW_MESSAGE_COUNT;

    public static final String MESSAGE_URI_PARAMETER_MAILBOX_ID = "mailboxId";

    static {
        // Email URI matching table
        UriMatcher matcher = sURIMatcher;

        // All accounts
        matcher.addURI(EmailContent.AUTHORITY, "account", ACCOUNT);
        // A specific account
        // insert into this URI causes a mailbox to be added to the account
        matcher.addURI(EmailContent.AUTHORITY, "account/#", ACCOUNT_ID);
        matcher.addURI(EmailContent.AUTHORITY, "account/default", ACCOUNT_DEFAULT_ID);

        // Special URI to reset the new message count.  Only update works, and content values
        // will be ignored.
        matcher.addURI(EmailContent.AUTHORITY, "resetNewMessageCount",
                ACCOUNT_RESET_NEW_COUNT);
        matcher.addURI(EmailContent.AUTHORITY, "resetNewMessageCount/#",
                ACCOUNT_RESET_NEW_COUNT_ID);

        // All mailboxes
        matcher.addURI(EmailContent.AUTHORITY, "mailbox", MAILBOX);
        // A specific mailbox
        // insert into this URI causes a message to be added to the mailbox
        // ** NOTE For now, the accountKey must be set manually in the values!
        matcher.addURI(EmailContent.AUTHORITY, "mailbox/#", MAILBOX_ID);
        matcher.addURI(EmailContent.AUTHORITY, "mailboxIdFromAccountAndType/#/#",
                MAILBOX_ID_FROM_ACCOUNT_AND_TYPE);
        // All messages
        matcher.addURI(EmailContent.AUTHORITY, "message", MESSAGE);
        // A specific message
        // insert into this URI causes an attachment to be added to the message
        matcher.addURI(EmailContent.AUTHORITY, "message/#", MESSAGE_ID);

        // A specific attachment
        matcher.addURI(EmailContent.AUTHORITY, "attachment", ATTACHMENT);
        // A specific attachment (the header information)
        matcher.addURI(EmailContent.AUTHORITY, "attachment/#", ATTACHMENT_ID);
        // The attachments of a specific message (query only) (insert & delete TBD)
        matcher.addURI(EmailContent.AUTHORITY, "attachment/message/#",
                ATTACHMENTS_MESSAGE_ID);

        // All mail bodies
        matcher.addURI(EmailContent.AUTHORITY, "body", BODY);
        // A specific mail body
        matcher.addURI(EmailContent.AUTHORITY, "body/#", BODY_ID);

        // All hostauth records
        matcher.addURI(EmailContent.AUTHORITY, "hostauth", HOSTAUTH);
        // A specific hostauth
        matcher.addURI(EmailContent.AUTHORITY, "hostauth/#", HOSTAUTH_ID);

        // Atomically a constant value to a particular field of a mailbox/account
        matcher.addURI(EmailContent.AUTHORITY, "mailboxIdAddToField/#",
                MAILBOX_ID_ADD_TO_FIELD);
        matcher.addURI(EmailContent.AUTHORITY, "accountIdAddToField/#",
                ACCOUNT_ID_ADD_TO_FIELD);

        /**
         * THIS URI HAS SPECIAL SEMANTICS
         * ITS USE IS INTENDED FOR THE UI APPLICATION TO MARK CHANGES THAT NEED TO BE SYNCED BACK
         * TO A SERVER VIA A SYNC ADAPTER
         */
        matcher.addURI(EmailContent.AUTHORITY, "syncedMessage/#", SYNCED_MESSAGE_ID);

        /**
         * THE URIs BELOW THIS POINT ARE INTENDED TO BE USED BY SYNC ADAPTERS ONLY
         * THEY REFER TO DATA CREATED AND MAINTAINED BY CALLS TO THE SYNCED_MESSAGE_ID URI
         * BY THE UI APPLICATION
         */
        // All deleted messages
        matcher.addURI(EmailContent.AUTHORITY, "deletedMessage", DELETED_MESSAGE);
        // A specific deleted message
        matcher.addURI(EmailContent.AUTHORITY, "deletedMessage/#", DELETED_MESSAGE_ID);

        // All updated messages
        matcher.addURI(EmailContent.AUTHORITY, "updatedMessage", UPDATED_MESSAGE);
        // A specific updated message
        matcher.addURI(EmailContent.AUTHORITY, "updatedMessage/#", UPDATED_MESSAGE_ID);

        CONTENT_VALUES_RESET_NEW_MESSAGE_COUNT = new ContentValues();
        CONTENT_VALUES_RESET_NEW_MESSAGE_COUNT.put(Account.NEW_MESSAGE_COUNT, 0);

        matcher.addURI(EmailContent.AUTHORITY, "policy", POLICY);
        matcher.addURI(EmailContent.AUTHORITY, "policy/#", POLICY_ID);

        // All quick responses
        matcher.addURI(EmailContent.AUTHORITY, "quickresponse", QUICK_RESPONSE);
        // A specific quick response
        matcher.addURI(EmailContent.AUTHORITY, "quickresponse/#", QUICK_RESPONSE_ID);
        // All quick responses associated with a particular account id
        matcher.addURI(EmailContent.AUTHORITY, "quickresponse/account/#",
                QUICK_RESPONSE_ACCOUNT_ID);
    }

    /**
     * Wrap the UriMatcher call so we can throw a runtime exception if an unknown Uri is passed in
     * @param uri the Uri to match
     * @return the match value
     */
    private static int findMatch(Uri uri, String methodName) {
        int match = sURIMatcher.match(uri);
        if (match < 0) {
            throw new IllegalArgumentException("Unknown uri: " + uri);
        } else if (Logging.LOGD) {
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
            + MessageColumns.SNIPPET + " text, "
            + MessageColumns.PROTOCOL_SEARCH_INFO + " text"
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

    @SuppressWarnings("deprecation")
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
            + AccountColumns.SIGNATURE + " text, "
            + AccountColumns.POLICY_KEY + " integer, "
            + AccountColumns.NOTIFIED_MESSAGE_ID + " integer, "
            + AccountColumns.NOTIFIED_MESSAGE_COUNT + " integer"
            + ");";
        db.execSQL("create table " + Account.TABLE_NAME + s);
        // Deleting an account deletes associated Mailboxes and HostAuth's
        db.execSQL(TRIGGER_ACCOUNT_DELETE);
    }

    static void resetAccountTable(SQLiteDatabase db, int oldVersion, int newVersion) {
        try {
            db.execSQL("drop table " +  Account.TABLE_NAME);
        } catch (SQLException e) {
        }
        createAccountTable(db);
    }

    static void createPolicyTable(SQLiteDatabase db) {
        String s = " (" + EmailContent.RECORD_ID + " integer primary key autoincrement, "
            + PolicyColumns.PASSWORD_MODE + " integer, "
            + PolicyColumns.PASSWORD_MIN_LENGTH + " integer, "
            + PolicyColumns.PASSWORD_EXPIRATION_DAYS + " integer, "
            + PolicyColumns.PASSWORD_HISTORY + " integer, "
            + PolicyColumns.PASSWORD_COMPLEX_CHARS + " integer, "
            + PolicyColumns.PASSWORD_MAX_FAILS + " integer, "
            + PolicyColumns.MAX_SCREEN_LOCK_TIME + " integer, "
            + PolicyColumns.REQUIRE_REMOTE_WIPE + " integer, "
            + PolicyColumns.REQUIRE_ENCRYPTION + " integer, "
            + PolicyColumns.REQUIRE_ENCRYPTION_EXTERNAL + " integer, "
            + PolicyColumns.REQUIRE_MANUAL_SYNC_WHEN_ROAMING + " integer, "
            + PolicyColumns.DONT_ALLOW_CAMERA + " integer, "
            + PolicyColumns.DONT_ALLOW_ATTACHMENTS + " integer, "
            + PolicyColumns.DONT_ALLOW_HTML + " integer, "
            + PolicyColumns.MAX_ATTACHMENT_SIZE + " integer, "
            + PolicyColumns.MAX_TEXT_TRUNCATION_SIZE + " integer, "
            + PolicyColumns.MAX_HTML_TRUNCATION_SIZE + " integer, "
            + PolicyColumns.MAX_EMAIL_LOOKBACK + " integer, "
            + PolicyColumns.MAX_CALENDAR_LOOKBACK + " integer, "
            + PolicyColumns.PASSWORD_RECOVERY_ENABLED + " integer"
            + ");";
        db.execSQL("create table " + Policy.TABLE_NAME + s);
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
            + HostAuthColumns.ACCOUNT_KEY + " integer,"
            + HostAuthColumns.CLIENT_CERT_ALIAS + " text"
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
            + MailboxColumns.PARENT_KEY + " integer, "
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
            + MailboxColumns.MESSAGE_COUNT + " integer not null default 0, "
            + MailboxColumns.LAST_SEEN_MESSAGE_KEY + " integer, "
            + MailboxColumns.LAST_TOUCHED_TIME + " integer default 0"
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

    static void createQuickResponseTable(SQLiteDatabase db) {
        String s = " (" + EmailContent.RECORD_ID + " integer primary key autoincrement, "
                + QuickResponseColumns.TEXT + " text, "
                + QuickResponseColumns.ACCOUNT_KEY + " integer"
                + ");";
        db.execSQL("create table " + QuickResponse.TABLE_NAME + s);
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

    /**
     * Orphan record deletion utility.  Generates a sqlite statement like:
     *  delete from <table> where <column> not in (select <foreignColumn> from <foreignTable>)
     * @param db the EmailProvider database
     * @param table the table whose orphans are to be removed
     * @param column the column deletion will be based on
     * @param foreignColumn the column in the foreign table whose absence will trigger the deletion
     * @param foreignTable the foreign table
     */
    @VisibleForTesting
    void deleteUnlinked(SQLiteDatabase db, String table, String column, String foreignColumn,
            String foreignTable) {
        int count = db.delete(table, column + " not in (select " + foreignColumn + " from " +
                foreignTable + ")", null);
        if (count > 0) {
            Log.w(TAG, "Found " + count + " orphaned row(s) in " + table);
        }
    }

    @VisibleForTesting
    synchronized SQLiteDatabase getDatabase(Context context) {
        // Always return the cached database, if we've got one
        if (mDatabase != null) {
            return mDatabase;
        }

        // Whenever we create or re-cache the databases, make sure that we haven't lost one
        // to corruption
        checkDatabases();

        DatabaseHelper helper = new DatabaseHelper(context, DATABASE_NAME);
        mDatabase = helper.getWritableDatabase();
        mDatabase.setLockingEnabled(true);
        BodyDatabaseHelper bodyHelper = new BodyDatabaseHelper(context, BODY_DATABASE_NAME);
        mBodyDatabase = bodyHelper.getWritableDatabase();
        if (mBodyDatabase != null) {
            mBodyDatabase.setLockingEnabled(true);
            String bodyFileName = mBodyDatabase.getPath();
            mDatabase.execSQL("attach \"" + bodyFileName + "\" as BodyDatabase");
        }

        // Restore accounts if the database is corrupted...
        restoreIfNeeded(context, mDatabase);

        if (Email.DEBUG) {
            Log.d(TAG, "Deleting orphans...");
        }
        // Check for any orphaned Messages in the updated/deleted tables
        deleteMessageOrphans(mDatabase, Message.UPDATED_TABLE_NAME);
        deleteMessageOrphans(mDatabase, Message.DELETED_TABLE_NAME);
        // Delete orphaned mailboxes/messages/policies (account no longer exists)
        deleteUnlinked(mDatabase, Mailbox.TABLE_NAME, MailboxColumns.ACCOUNT_KEY, AccountColumns.ID,
                Account.TABLE_NAME);
        deleteUnlinked(mDatabase, Message.TABLE_NAME, MessageColumns.ACCOUNT_KEY, AccountColumns.ID,
                Account.TABLE_NAME);
        deleteUnlinked(mDatabase, Policy.TABLE_NAME, PolicyColumns.ID, AccountColumns.POLICY_KEY,
                Account.TABLE_NAME);

        if (Email.DEBUG) {
            Log.d(TAG, "EmailProvider pre-caching...");
        }
        preCacheData();
        if (Email.DEBUG) {
            Log.d(TAG, "EmailProvider ready.");
        }
        return mDatabase;
    }

    /**
     * Pre-cache all of the items in a given table meeting the selection criteria
     * @param tableUri the table uri
     * @param baseProjection the base projection of that table
     * @param selection the selection criteria
     */
    private void preCacheTable(Uri tableUri, String[] baseProjection, String selection) {
        Cursor c = query(tableUri, EmailContent.ID_PROJECTION, selection, null, null);
        try {
            while (c.moveToNext()) {
                long id = c.getLong(EmailContent.ID_PROJECTION_COLUMN);
                Cursor cachedCursor = query(ContentUris.withAppendedId(
                        tableUri, id), baseProjection, null, null, null);
                if (cachedCursor != null) {
                    // For accounts, create a mailbox type map entry (if necessary)
                    if (tableUri == Account.CONTENT_URI) {
                        getOrCreateAccountMailboxTypeMap(id);
                    }
                    cachedCursor.close();
                }
            }
        } finally {
            c.close();
        }
    }

    private final HashMap<Long, HashMap<Integer, Long>> mMailboxTypeMap =
        new HashMap<Long, HashMap<Integer, Long>>();

    private HashMap<Integer, Long> getOrCreateAccountMailboxTypeMap(long accountId) {
        synchronized(mMailboxTypeMap) {
            HashMap<Integer, Long> accountMailboxTypeMap = mMailboxTypeMap.get(accountId);
            if (accountMailboxTypeMap == null) {
                accountMailboxTypeMap = new HashMap<Integer, Long>();
                mMailboxTypeMap.put(accountId, accountMailboxTypeMap);
            }
            return accountMailboxTypeMap;
        }
    }

    private void addToMailboxTypeMap(Cursor c) {
        long accountId = c.getLong(Mailbox.CONTENT_ACCOUNT_KEY_COLUMN);
        int type = c.getInt(Mailbox.CONTENT_TYPE_COLUMN);
        synchronized(mMailboxTypeMap) {
            HashMap<Integer, Long> accountMailboxTypeMap =
                getOrCreateAccountMailboxTypeMap(accountId);
            accountMailboxTypeMap.put(type, c.getLong(Mailbox.CONTENT_ID_COLUMN));
        }
    }

    private long getMailboxIdFromMailboxTypeMap(long accountId, int type) {
        synchronized(mMailboxTypeMap) {
            HashMap<Integer, Long> accountMap = mMailboxTypeMap.get(accountId);
            Long mailboxId = null;
            if (accountMap != null) {
                mailboxId = accountMap.get(type);
            }
            if (mailboxId == null) return Mailbox.NO_MAILBOX;
            return mailboxId;
        }
    }

    private void preCacheData() {
        synchronized(mMailboxTypeMap) {
            mMailboxTypeMap.clear();

            // Pre-cache accounts, host auth's, policies, and special mailboxes
            preCacheTable(Account.CONTENT_URI, Account.CONTENT_PROJECTION, null);
            preCacheTable(HostAuth.CONTENT_URI, HostAuth.CONTENT_PROJECTION, null);
            preCacheTable(Policy.CONTENT_URI, Policy.CONTENT_PROJECTION, null);
            preCacheTable(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                    MAILBOX_PRE_CACHE_SELECTION);

            // Create a map from account,type to a mailbox
            Map<String, Cursor> snapshot = mCacheMailbox.getSnapshot();
            Collection<Cursor> values = snapshot.values();
            if (values != null) {
                for (Cursor c: values) {
                    if (c.moveToFirst()) {
                        addToMailboxTypeMap(c);
                    }
                }
            }
        }
    }

    /*package*/ static SQLiteDatabase getReadableDatabase(Context context) {
        DatabaseHelper helper = new DatabaseHelper(context, DATABASE_NAME);
        return helper.getReadableDatabase();
    }

    /**
     * Restore user Account and HostAuth data from our backup database
     */
    public static void restoreIfNeeded(Context context, SQLiteDatabase mainDatabase) {
        if (Email.DEBUG) {
            Log.w(TAG, "restoreIfNeeded...");
        }
        // Check for legacy backup
        String legacyBackup = Preferences.getLegacyBackupPreference(context);
        // If there's a legacy backup, create a new-style backup and delete the legacy backup
        // In the 1:1000000000 chance that the user gets an app update just as his database becomes
        // corrupt, oh well...
        if (!TextUtils.isEmpty(legacyBackup)) {
            backupAccounts(context, mainDatabase);
            Preferences.clearLegacyBackupPreference(context);
            Log.w(TAG, "Created new EmailProvider backup database");
            return;
        }

        // If we have accounts, we're done
        Cursor c = mainDatabase.query(Account.TABLE_NAME, EmailContent.ID_PROJECTION, null, null,
                null, null, null);
        if (c.moveToFirst()) {
            if (Email.DEBUG) {
                Log.w(TAG, "restoreIfNeeded: Account exists.");
            }
            return; // At least one account exists.
        }
        restoreAccounts(context, mainDatabase);
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

    /*package*/ static void deleteMessageOrphans(SQLiteDatabase database, String tableName) {
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

    private static class DatabaseHelper extends SQLiteOpenHelper {
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
            createPolicyTable(db);
            createQuickResponseTable(db);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // For versions prior to 5, delete all data
            // Versions >= 5 require that data be preserved!
            if (oldVersion < 5) {
                android.accounts.Account[] accounts = AccountManager.get(mContext)
                        .getAccountsByType(AccountManagerTypes.TYPE_EXCHANGE);
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
            if (oldVersion == 16) {
                try {
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + Mailbox.PARENT_KEY + " integer;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 16 to 17 " + e);
                }
                oldVersion = 17;
            }
            if (oldVersion == 17) {
                upgradeFromVersion17ToVersion18(db);
                oldVersion = 18;
            }
            if (oldVersion == 18) {
                try {
                    db.execSQL("alter table " + Account.TABLE_NAME
                            + " add column " + Account.POLICY_KEY + " integer;");
                    db.execSQL("drop trigger account_delete;");
                    db.execSQL(TRIGGER_ACCOUNT_DELETE);
                    createPolicyTable(db);
                    convertPolicyFlagsToPolicyTable(db);
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 18 to 19 " + e);
                }
                oldVersion = 19;
            }
            if (oldVersion == 19) {
                try {
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + PolicyColumns.REQUIRE_MANUAL_SYNC_WHEN_ROAMING +
                            " integer;");
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + PolicyColumns.DONT_ALLOW_CAMERA + " integer;");
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + PolicyColumns.DONT_ALLOW_ATTACHMENTS + " integer;");
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + PolicyColumns.DONT_ALLOW_HTML + " integer;");
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + PolicyColumns.MAX_ATTACHMENT_SIZE + " integer;");
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + PolicyColumns.MAX_TEXT_TRUNCATION_SIZE +
                            " integer;");
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + PolicyColumns.MAX_HTML_TRUNCATION_SIZE +
                            " integer;");
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + PolicyColumns.MAX_EMAIL_LOOKBACK + " integer;");
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + PolicyColumns.MAX_CALENDAR_LOOKBACK + " integer;");
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + PolicyColumns.PASSWORD_RECOVERY_ENABLED +
                            " integer;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 19 to 20 " + e);
                }
                oldVersion = 20;
            }
            if (oldVersion == 20) {
                upgradeFromVersion20ToVersion21(db);
                oldVersion = 21;
            }
            if (oldVersion == 21) {
                upgradeFromVersion21ToVersion22(db, mContext);
                oldVersion = 22;
            }
            if (oldVersion == 22) {
                upgradeFromVersion22ToVersion23(db);
                oldVersion = 23;
            }
            if (oldVersion == 23) {
                upgradeFromVersion23ToVersion24(db);
                oldVersion = 24;
            }
            if (oldVersion == 24) {
                upgradeFromVersion24ToVersion25(db);
                oldVersion = 25;
            }
            if (oldVersion == 25) {
                upgradeFromVersion25ToVersion26(db);
                oldVersion = 26;
            }
            if (oldVersion == 26) {
                try {
                    db.execSQL("alter table " + Message.TABLE_NAME
                            + " add column " + Message.PROTOCOL_SEARCH_INFO + " text;");
                    db.execSQL("alter table " + Message.DELETED_TABLE_NAME
                            + " add column " + Message.PROTOCOL_SEARCH_INFO +" text" + ";");
                    db.execSQL("alter table " + Message.UPDATED_TABLE_NAME
                            + " add column " + Message.PROTOCOL_SEARCH_INFO +" text" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 26 to 27 " + e);
                }
                oldVersion = 27;
            }
            if (oldVersion == 27) {
                try {
                    db.execSQL("alter table " + Account.TABLE_NAME
                            + " add column " + Account.NOTIFIED_MESSAGE_ID + " integer;");
                    db.execSQL("alter table " + Account.TABLE_NAME
                            + " add column " + Account.NOTIFIED_MESSAGE_COUNT + " integer;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 27 to 27 " + e);
                }
                oldVersion = 28;
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

        ContentCache cache = mContentCaches[table];
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
                case POLICY_ID:
                case QUICK_RESPONSE_ID:
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
                                    mCacheMailbox.invalidate("Delete", uri, selection);
                                    mCacheHostAuth.invalidate("Delete", uri, selection);
                                    mCachePolicy.invalidate("Delete", uri, selection);
                                    //$FALL-THROUGH$
                                case MAILBOX_ID:
                                    // Mailbox deletion will clear the Message cache
                                    mCacheMessage.invalidate("Delete", uri, selection);
                                    //$FALL-THROUGH$
                                case SYNCED_MESSAGE_ID:
                                case MESSAGE_ID:
                                case HOSTAUTH_ID:
                                case POLICY_ID:
                                    cache.invalidate("Delete", uri, selection);
                                    // Make sure all data is properly cached
                                    if (match != MESSAGE_ID) {
                                        preCacheData();
                                    }
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
                case POLICY:
                    switch(match) {
                        // See the comments above for deletion of ACCOUNT_ID, etc
                        case ACCOUNT:
                            mCacheMailbox.invalidate("Delete", uri, selection);
                            mCacheHostAuth.invalidate("Delete", uri, selection);
                            mCachePolicy.invalidate("Delete", uri, selection);
                            //$FALL-THROUGH$
                        case MAILBOX:
                            mCacheMessage.invalidate("Delete", uri, selection);
                            //$FALL-THROUGH$
                        case MESSAGE:
                        case HOSTAUTH:
                        case POLICY:
                            cache.invalidate("Delete", uri, selection);
                            break;
                    }
                    result = db.delete(tableName, selection, selectionArgs);
                    switch(match) {
                        case ACCOUNT:
                        case MAILBOX:
                        case HOSTAUTH:
                        case POLICY:
                            // Make sure all data is properly cached
                            preCacheData();
                            break;
                    }
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

        // Notify all notifier cursors
        sendNotifierChange(getBaseNotificationUri(match), NOTIFICATION_OP_DELETE, id);

        // Notify all email content cursors
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
        String id = "0";
        long longId;

        // We do NOT allow setting of unreadCount/messageCount via the provider
        // These columns are maintained via triggers
        if (match == MAILBOX_ID || match == MAILBOX) {
            values.put(MailboxColumns.UNREAD_COUNT, 0);
            values.put(MailboxColumns.MESSAGE_COUNT, 0);
        }

        Uri resultUri = null;

        try {
            switch (match) {
                // NOTE: It is NOT legal for production code to insert directly into UPDATED_MESSAGE
                // or DELETED_MESSAGE; see the comment below for details
                case UPDATED_MESSAGE:
                case DELETED_MESSAGE:
                case MESSAGE:
                case BODY:
                case ATTACHMENT:
                case MAILBOX:
                case ACCOUNT:
                case HOSTAUTH:
                case POLICY:
                case QUICK_RESPONSE:
                    longId = db.insert(TABLE_NAMES[table], "foo", values);
                    resultUri = ContentUris.withAppendedId(uri, longId);
                    switch(match) {
                        case MAILBOX:
                            if (values.containsKey(MailboxColumns.TYPE)) {
                                // Only cache special mailbox types
                                int type = values.getAsInteger(MailboxColumns.TYPE);
                                if (type != Mailbox.TYPE_INBOX && type != Mailbox.TYPE_OUTBOX &&
                                        type != Mailbox.TYPE_DRAFTS && type != Mailbox.TYPE_SENT &&
                                        type != Mailbox.TYPE_TRASH && type != Mailbox.TYPE_SEARCH) {
                                    break;
                                }
                            }
                            //$FALL-THROUGH$
                        case ACCOUNT:
                        case HOSTAUTH:
                        case POLICY:
                            // Cache new account, host auth, policy, and some mailbox rows
                            Cursor c = query(resultUri, CACHE_PROJECTIONS[table], null, null, null);
                            if (c != null) {
                                if (match == MAILBOX) {
                                    addToMailboxTypeMap(c);
                                } else if (match == ACCOUNT) {
                                    getOrCreateAccountMailboxTypeMap(longId);
                                }
                                c.close();
                            }
                            break;
                    }
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
                        mAttachmentService.attachmentChanged(getContext(), longId, flags);
                    }
                    break;
                case MAILBOX_ID:
                    // This implies adding a message to a mailbox
                    // Hmm, a problem here is that we can't link the account as well, so it must be
                    // already in the values...
                    longId = Long.parseLong(uri.getPathSegments().get(1));
                    values.put(MessageColumns.MAILBOX_KEY, longId);
                    return insert(Message.CONTENT_URI, values); // Recurse
                case MESSAGE_ID:
                    // This implies adding an attachment to a message.
                    id = uri.getPathSegments().get(1);
                    longId = Long.parseLong(id);
                    values.put(AttachmentColumns.MESSAGE_KEY, longId);
                    return insert(Attachment.CONTENT_URI, values); // Recurse
                case ACCOUNT_ID:
                    // This implies adding a mailbox to an account.
                    longId = Long.parseLong(uri.getPathSegments().get(1));
                    values.put(MailboxColumns.ACCOUNT_KEY, longId);
                    return insert(Mailbox.CONTENT_URI, values); // Recurse
                case ATTACHMENTS_MESSAGE_ID:
                    longId = db.insert(TABLE_NAMES[table], "foo", values);
                    resultUri = ContentUris.withAppendedId(Attachment.CONTENT_URI, longId);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown URL " + uri);
            }
        } catch (SQLiteException e) {
            checkDatabases();
            throw e;
        }

        // Notify all notifier cursors
        sendNotifierChange(getBaseNotificationUri(match), NOTIFICATION_OP_INSERT, id);

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
        int match;
        try {
            match = findMatch(uri, "query");
        } catch (IllegalArgumentException e) {
            String uriString = uri.toString();
            // If we were passed an illegal uri, see if it ends in /-1
            // if so, and if substituting 0 for -1 results in a valid uri, return an empty cursor
            if (uriString != null && uriString.endsWith("/-1")) {
                uri = Uri.parse(uriString.substring(0, uriString.length() - 2) + "0");
                match = findMatch(uri, "query");
                switch (match) {
                    case BODY_ID:
                    case MESSAGE_ID:
                    case DELETED_MESSAGE_ID:
                    case UPDATED_MESSAGE_ID:
                    case ATTACHMENT_ID:
                    case MAILBOX_ID:
                    case ACCOUNT_ID:
                    case HOSTAUTH_ID:
                    case POLICY_ID:
                        return new MatrixCursor(projection, 0);
                }
            }
            throw e;
        }
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
            cache = mContentCaches[table];
        }
        if (cache == null) {
            ContentCache.notCacheable(uri, selection);
        }

        try {
            switch (match) {
                case ACCOUNT_DEFAULT_ID:
                    // Start with a snapshot of the cache
                    Map<String, Cursor> accountCache = mCacheAccount.getSnapshot();
                    long accountId = Account.NO_ACCOUNT;
                    // Find the account with "isDefault" set, or the lowest account ID otherwise.
                    // Note that the snapshot from the cached isn't guaranteed to be sorted in any
                    // way.
                    Collection<Cursor> accounts = accountCache.values();
                    for (Cursor accountCursor: accounts) {
                        // For now, at least, we can have zero count cursors (e.g. if someone looks
                        // up a non-existent id); we need to skip these
                        if (accountCursor.moveToFirst()) {
                            boolean isDefault =
                                accountCursor.getInt(Account.CONTENT_IS_DEFAULT_COLUMN) == 1;
                            long iterId = accountCursor.getLong(Account.CONTENT_ID_COLUMN);
                            // We'll remember this one if it's the default or the first one we see
                            if (isDefault) {
                                accountId = iterId;
                                break;
                            } else if ((accountId == Account.NO_ACCOUNT) || (iterId < accountId)) {
                                accountId = iterId;
                            }
                        }
                    }
                    // Return a cursor with an id projection
                    MatrixCursor mc = new MatrixCursor(EmailContent.ID_PROJECTION);
                    mc.addRow(new Object[] {accountId});
                    c = mc;
                    break;
                case MAILBOX_ID_FROM_ACCOUNT_AND_TYPE:
                    // Get accountId and type and find the mailbox in our map
                    List<String> pathSegments = uri.getPathSegments();
                    accountId = Long.parseLong(pathSegments.get(1));
                    int type = Integer.parseInt(pathSegments.get(2));
                    long mailboxId = getMailboxIdFromMailboxTypeMap(accountId, type);
                    // Return a cursor with an id projection
                    mc = new MatrixCursor(EmailContent.ID_PROJECTION);
                    mc.addRow(new Object[] {mailboxId});
                    c = mc;
                    break;
                case BODY:
                case MESSAGE:
                case UPDATED_MESSAGE:
                case DELETED_MESSAGE:
                case ATTACHMENT:
                case MAILBOX:
                case ACCOUNT:
                case HOSTAUTH:
                case POLICY:
                case QUICK_RESPONSE:
                    // Special-case "count of accounts"; it's common and we always know it
                    if (match == ACCOUNT && Arrays.equals(projection, EmailContent.COUNT_COLUMNS) &&
                            selection == null && limit.equals("1")) {
                        int accountCount = mMailboxTypeMap.size();
                        // In the rare case there are MAX_CACHED_ACCOUNTS or more, we can't do this
                        if (accountCount < MAX_CACHED_ACCOUNTS) {
                            mc = new MatrixCursor(projection, 1);
                            mc.addRow(new Object[] {accountCount});
                            c = mc;
                            break;
                        }
                    }
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
                case POLICY_ID:
                case QUICK_RESPONSE_ID:
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
                case QUICK_RESPONSE_ACCOUNT_ID:
                    // All quick responses for the given account
                    id = uri.getPathSegments().get(2);
                    c = db.query(QuickResponse.TABLE_NAME, projection,
                            whereWith(QuickResponse.ACCOUNT_KEY + "=" + id, selection),
                            selectionArgs, null, null, sortOrder);
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
            if (cache != null && c != null && Email.DEBUG) {
                cache.recordQueryTime(c, System.nanoTime() - time);
            }
            if (c == null) {
                // This should never happen, but let's be sure to log it...
                Log.e(TAG, "Query returning null for uri: " + uri + ", selection: " + selection);
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

    /**
     * Restore a HostAuth from a database, given its unique id
     * @param db the database
     * @param id the unique id (_id) of the row
     * @return a fully populated HostAuth or null if the row does not exist
     */
    private static HostAuth restoreHostAuth(SQLiteDatabase db, long id) {
        Cursor c = db.query(HostAuth.TABLE_NAME, HostAuth.CONTENT_PROJECTION,
                HostAuth.RECORD_ID + "=?", new String[] {Long.toString(id)}, null, null, null);
        try {
            if (c.moveToFirst()) {
                HostAuth hostAuth = new HostAuth();
                hostAuth.restore(c);
                return hostAuth;
            }
            return null;
        } finally {
            c.close();
        }
    }

    /**
     * Copy the Account and HostAuth tables from one database to another
     * @param fromDatabase the source database
     * @param toDatabase the destination database
     * @return the number of accounts copied, or -1 if an error occurred
     */
    private static int copyAccountTables(SQLiteDatabase fromDatabase, SQLiteDatabase toDatabase) {
        if (fromDatabase == null || toDatabase == null) return -1;
        int copyCount = 0;
        try {
            // Lock both databases; for the "from" database, we don't want anyone changing it from
            // under us; for the "to" database, we want to make the operation atomic
            fromDatabase.beginTransaction();
            toDatabase.beginTransaction();
            // Delete anything hanging around here
            toDatabase.delete(Account.TABLE_NAME, null, null);
            toDatabase.delete(HostAuth.TABLE_NAME, null, null);
            // Get our account cursor
            Cursor c = fromDatabase.query(Account.TABLE_NAME, Account.CONTENT_PROJECTION,
                    null, null, null, null, null);
            boolean noErrors = true;
            try {
                // Loop through accounts, copying them and associated host auth's
                while (c.moveToNext()) {
                    Account account = new Account();
                    account.restore(c);

                    // Clear security sync key and sync key, as these were specific to the state of
                    // the account, and we've reset that...
                    // Clear policy key so that we can re-establish policies from the server
                    // TODO This is pretty EAS specific, but there's a lot of that around
                    account.mSecuritySyncKey = null;
                    account.mSyncKey = null;
                    account.mPolicyKey = 0;

                    // Copy host auth's and update foreign keys
                    HostAuth hostAuth = restoreHostAuth(fromDatabase, account.mHostAuthKeyRecv);
                    // The account might have gone away, though very unlikely
                    if (hostAuth == null) continue;
                    account.mHostAuthKeyRecv = toDatabase.insert(HostAuth.TABLE_NAME, null,
                            hostAuth.toContentValues());
                    // EAS accounts have no send HostAuth
                    if (account.mHostAuthKeySend > 0) {
                        hostAuth = restoreHostAuth(fromDatabase, account.mHostAuthKeySend);
                        // Belt and suspenders; I can't imagine that this is possible, since we
                        // checked the validity of the account above, and the database is now locked
                        if (hostAuth == null) continue;
                        account.mHostAuthKeySend = toDatabase.insert(HostAuth.TABLE_NAME, null,
                                hostAuth.toContentValues());
                    }
                    // Now, create the account in the "to" database
                    toDatabase.insert(Account.TABLE_NAME, null, account.toContentValues());
                    copyCount++;
                }
            } catch (SQLiteException e) {
                noErrors = false;
                copyCount = -1;
            } finally {
                fromDatabase.endTransaction();
                if (noErrors) {
                    // Say it's ok to commit
                    toDatabase.setTransactionSuccessful();
                }
                toDatabase.endTransaction();
                c.close();
            }
        } catch (SQLiteException e) {
            copyCount = -1;
        }
        return copyCount;
    }

    private static SQLiteDatabase getBackupDatabase(Context context) {
        DatabaseHelper helper = new DatabaseHelper(context, BACKUP_DATABASE_NAME);
        return helper.getWritableDatabase();
    }

    /**
     * Backup account data, returning the number of accounts backed up
     */
    private static int backupAccounts(Context context, SQLiteDatabase mainDatabase) {
        if (Email.DEBUG) {
            Log.d(TAG, "backupAccounts...");
        }
        SQLiteDatabase backupDatabase = getBackupDatabase(context);
        try {
            int numBackedUp = copyAccountTables(mainDatabase, backupDatabase);
            if (numBackedUp < 0) {
                Log.e(TAG, "Account backup failed!");
            } else if (Email.DEBUG) {
                Log.d(TAG, "Backed up " + numBackedUp + " accounts...");
            }
            return numBackedUp;
        } finally {
            if (backupDatabase != null) {
                backupDatabase.close();
            }
        }
    }

    /**
     * Restore account data, returning the number of accounts restored
     */
    private static int restoreAccounts(Context context, SQLiteDatabase mainDatabase) {
        if (Email.DEBUG) {
            Log.d(TAG, "restoreAccounts...");
        }
        SQLiteDatabase backupDatabase = getBackupDatabase(context);
        try {
            int numRecovered = copyAccountTables(backupDatabase, mainDatabase);
            if (numRecovered > 0) {
                Log.e(TAG, "Recovered " + numRecovered + " accounts!");
            } else if (numRecovered < 0) {
                Log.e(TAG, "Account recovery failed?");
            } else if (Email.DEBUG) {
                Log.d(TAG, "No accounts to restore...");
            }
            return numRecovered;
        } finally {
            if (backupDatabase != null) {
                backupDatabase.close();
            }
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // Handle this special case the fastest possible way
        if (uri == INTEGRITY_CHECK_URI) {
            checkDatabases();
            return 0;
        } else if (uri == ACCOUNT_BACKUP_URI) {
            return backupAccounts(getContext(), getDatabase(getContext()));
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

        ContentCache cache = mContentCaches[table];
        String tableName = TABLE_NAMES[table];
        String id = "0";

        try {
outer:
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
                case UPDATED_MESSAGE_ID:
                case MESSAGE_ID:
                case BODY_ID:
                case ATTACHMENT_ID:
                case MAILBOX_ID:
                case ACCOUNT_ID:
                case HOSTAUTH_ID:
                case QUICK_RESPONSE_ID:
                case POLICY_ID:
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
                            mAttachmentService.attachmentChanged(getContext(),
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
                case POLICY:
                    switch(match) {
                        // To avoid invalidating the cache on updates, we execute them one at a
                        // time using the XXX_ID uri; these are all executed atomically
                        case ACCOUNT:
                        case MAILBOX:
                        case HOSTAUTH:
                        case POLICY:
                            Cursor c = db.query(tableName, EmailContent.ID_PROJECTION,
                                    selection, selectionArgs, null, null, null);
                            db.beginTransaction();
                            result = 0;
                            try {
                                while (c.moveToNext()) {
                                    update(ContentUris.withAppendedId(
                                                uri, c.getLong(EmailContent.ID_PROJECTION_COLUMN)),
                                            values, null, null);
                                    result++;
                                }
                                db.setTransactionSuccessful();
                            } finally {
                                db.endTransaction();
                                c.close();
                            }
                            break outer;
                        // Any cached table other than those above should be invalidated here
                        case MESSAGE:
                            // If we're doing some generic update, the whole cache needs to be
                            // invalidated.  This case should be quite rare
                            cache.invalidate("Update", uri, selection);
                            //$FALL-THROUGH$
                        default:
                            result = db.update(tableName, values, selection, selectionArgs);
                            break outer;
                    }
                case ACCOUNT_RESET_NEW_COUNT_ID:
                    id = uri.getPathSegments().get(1);
                    if (cache != null) {
                        cache.lock(id);
                    }
                    ContentValues newMessageCount = CONTENT_VALUES_RESET_NEW_MESSAGE_COUNT;
                    if (values != null) {
                        Long set = values.getAsLong(EmailContent.SET_COLUMN_NAME);
                        if (set != null) {
                            newMessageCount = new ContentValues();
                            newMessageCount.put(Account.NEW_MESSAGE_COUNT, set);
                        }
                    }
                    try {
                        result = db.update(tableName, newMessageCount,
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

        // Notify all notifier cursors
        sendNotifierChange(getBaseNotificationUri(match), NOTIFICATION_OP_UPDATE, id);

        resolver.notifyChange(notificationUri, null);
        return result;
    }

    /**
     * Returns the base notification URI for the given content type.
     *
     * @param match The type of content that was modified.
     */
    private Uri getBaseNotificationUri(int match) {
        Uri baseUri = null;
        switch (match) {
            case MESSAGE:
            case MESSAGE_ID:
            case SYNCED_MESSAGE_ID:
                baseUri = Message.NOTIFIER_URI;
                break;
            case ACCOUNT:
            case ACCOUNT_ID:
                baseUri = Account.NOTIFIER_URI;
                break;
        }
        return baseUri;
    }

    /**
     * Sends a change notification to any cursors observers of the given base URI. The final
     * notification URI is dynamically built to contain the specified information. It will be
     * of the format <<baseURI>>/<<op>>/<<id>>; where <<op>> and <<id>> are optional depending
     * upon the given values.
     * NOTE: If <<op>> is specified, notifications for <<baseURI>>/<<id>> will NOT be invoked.
     * If this is necessary, it can be added. However, due to the implementation of
     * {@link ContentObserver}, observers of <<baseURI>> will receive multiple notifications.
     *
     * @param baseUri The base URI to send notifications to. Must be able to take appended IDs.
     * @param op Optional operation to be appended to the URI.
     * @param id If a positive value, the ID to append to the base URI. Otherwise, no ID will be
     *           appended to the base URI.
     */
    private void sendNotifierChange(Uri baseUri, String op, String id) {
        if (baseUri == null) return;

        final ContentResolver resolver = getContext().getContentResolver();

        // Append the operation, if specified
        if (op != null) {
            baseUri = baseUri.buildUpon().appendEncodedPath(op).build();
        }

        long longId = 0L;
        try {
            longId = Long.valueOf(id);
        } catch (NumberFormatException ignore) {}
        if (longId > 0) {
            resolver.notifyChange(ContentUris.withAppendedId(baseUri, longId), null);
        } else {
            resolver.notifyChange(baseUri, null);
        }

        // We want to send the message list changed notification if baseUri is Message.NOTIFIER_URI.
        if (baseUri.equals(Message.NOTIFIER_URI)) {
            sendMessageListDataChangedNotification();
        }
    }

    private void sendMessageListDataChangedNotification() {
        final Context context = getContext();
        final Intent intent = new Intent(ACTION_NOTIFY_MESSAGE_LIST_DATASET_CHANGED);
        // Ideally this intent would contain information about which account changed, to limit the
        // updates to that particular account.  Unfortunately, that information is not available in
        // sendNotifierChange().
        context.sendBroadcast(intent);
    }

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

    /** Counts the number of messages in each mailbox, and updates the message count column. */
    @VisibleForTesting
    static void recalculateMessageCount(SQLiteDatabase db) {
        db.execSQL("update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.MESSAGE_COUNT +
                "= (select count(*) from " + Message.TABLE_NAME +
                " where " + Message.MAILBOX_KEY + " = " +
                    Mailbox.TABLE_NAME + "." + EmailContent.RECORD_ID + ")");
    }

    @VisibleForTesting
    @SuppressWarnings("deprecation")
    static void convertPolicyFlagsToPolicyTable(SQLiteDatabase db) {
        Cursor c = db.query(Account.TABLE_NAME,
                new String[] {EmailContent.RECORD_ID /*0*/, AccountColumns.SECURITY_FLAGS /*1*/},
                AccountColumns.SECURITY_FLAGS + ">0", null, null, null, null);
        ContentValues cv = new ContentValues();
        String[] args = new String[1];
        while (c.moveToNext()) {
            long securityFlags = c.getLong(1 /*SECURITY_FLAGS*/);
            Policy policy = LegacyPolicySet.flagsToPolicy(securityFlags);
            long policyId = db.insert(Policy.TABLE_NAME, null, policy.toContentValues());
            cv.put(AccountColumns.POLICY_KEY, policyId);
            cv.putNull(AccountColumns.SECURITY_FLAGS);
            args[0] = Long.toString(c.getLong(0 /*RECORD_ID*/));
            db.update(Account.TABLE_NAME, cv, EmailContent.RECORD_ID + "=?", args);
        }
    }

    /** Upgrades the database from v17 to v18 */
    @VisibleForTesting
    static void upgradeFromVersion17ToVersion18(SQLiteDatabase db) {
        // Copy the displayName column to the serverId column. In v18 of the database,
        // we use the serverId for IMAP/POP3 mailboxes instead of overloading the
        // display name.
        //
        // For posterity; this is the command we're executing:
        //sqlite> UPDATE mailbox SET serverid=displayname WHERE mailbox._id in (
        //        ...> SELECT mailbox._id FROM mailbox,account,hostauth WHERE
        //        ...> (mailbox.parentkey isnull OR mailbox.parentkey=0) AND
        //        ...> mailbox.accountkey=account._id AND
        //        ...> account.hostauthkeyrecv=hostauth._id AND
        //        ...> (hostauth.protocol='imap' OR hostauth.protocol='pop3'));
        try {
            db.execSQL(
                    "UPDATE " + Mailbox.TABLE_NAME + " SET "
                    + MailboxColumns.SERVER_ID + "=" + MailboxColumns.DISPLAY_NAME
                    + " WHERE "
                    + Mailbox.TABLE_NAME + "." + MailboxColumns.ID + " IN ( SELECT "
                    + Mailbox.TABLE_NAME + "." + MailboxColumns.ID + " FROM "
                    + Mailbox.TABLE_NAME + "," + Account.TABLE_NAME + ","
                    + HostAuth.TABLE_NAME + " WHERE "
                    + "("
                    + Mailbox.TABLE_NAME + "." + MailboxColumns.PARENT_KEY + " isnull OR "
                    + Mailbox.TABLE_NAME + "." + MailboxColumns.PARENT_KEY + "=0 "
                    + ") AND "
                    + Mailbox.TABLE_NAME + "." + MailboxColumns.ACCOUNT_KEY + "="
                    + Account.TABLE_NAME + "." + AccountColumns.ID + " AND "
                    + Account.TABLE_NAME + "." + AccountColumns.HOST_AUTH_KEY_RECV + "="
                    + HostAuth.TABLE_NAME + "." + HostAuthColumns.ID + " AND ( "
                    + HostAuth.TABLE_NAME + "." + HostAuthColumns.PROTOCOL + "='imap' OR "
                    + HostAuth.TABLE_NAME + "." + HostAuthColumns.PROTOCOL + "='pop3' ) )");
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            Log.w(TAG, "Exception upgrading EmailProvider.db from 17 to 18 " + e);
        }
        ContentCache.invalidateAllCaches();
    }

    /** Upgrades the database from v20 to v21 */
    private static void upgradeFromVersion20ToVersion21(SQLiteDatabase db) {
        try {
            db.execSQL("alter table " + Mailbox.TABLE_NAME
                    + " add column " + Mailbox.LAST_SEEN_MESSAGE_KEY + " integer;");
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            Log.w(TAG, "Exception upgrading EmailProvider.db from 20 to 21 " + e);
        }
    }

    /**
     * Upgrade the database from v21 to v22
     * This entails creating AccountManager accounts for all pop3 and imap accounts
     */

    private static final String[] V21_ACCOUNT_PROJECTION =
        new String[] {AccountColumns.HOST_AUTH_KEY_RECV, AccountColumns.EMAIL_ADDRESS};
    private static final int V21_ACCOUNT_RECV = 0;
    private static final int V21_ACCOUNT_EMAIL = 1;

    private static final String[] V21_HOSTAUTH_PROJECTION =
        new String[] {HostAuthColumns.PROTOCOL, HostAuthColumns.PASSWORD};
    private static final int V21_HOSTAUTH_PROTOCOL = 0;
    private static final int V21_HOSTAUTH_PASSWORD = 1;

    static private void createAccountManagerAccount(Context context, String login,
            String password) {
        AccountManager accountManager = AccountManager.get(context);
        android.accounts.Account amAccount =
            new android.accounts.Account(login, AccountManagerTypes.TYPE_POP_IMAP);
        accountManager.addAccountExplicitly(amAccount, password, null);
        ContentResolver.setIsSyncable(amAccount, EmailContent.AUTHORITY, 1);
        ContentResolver.setSyncAutomatically(amAccount, EmailContent.AUTHORITY, true);
        ContentResolver.setIsSyncable(amAccount, ContactsContract.AUTHORITY, 0);
        ContentResolver.setIsSyncable(amAccount, CalendarProviderStub.AUTHORITY, 0);
    }

    @VisibleForTesting
    static void upgradeFromVersion21ToVersion22(SQLiteDatabase db, Context accountManagerContext) {
        try {
            // Loop through accounts, looking for pop/imap accounts
            Cursor accountCursor = db.query(Account.TABLE_NAME, V21_ACCOUNT_PROJECTION, null,
                    null, null, null, null);
            try {
                String[] hostAuthArgs = new String[1];
                while (accountCursor.moveToNext()) {
                    hostAuthArgs[0] = accountCursor.getString(V21_ACCOUNT_RECV);
                    // Get the "receive" HostAuth for this account
                    Cursor hostAuthCursor = db.query(HostAuth.TABLE_NAME,
                            V21_HOSTAUTH_PROJECTION, HostAuth.RECORD_ID + "=?", hostAuthArgs,
                            null, null, null);
                    try {
                        if (hostAuthCursor.moveToFirst()) {
                            String protocol = hostAuthCursor.getString(V21_HOSTAUTH_PROTOCOL);
                            // If this is a pop3 or imap account, create the account manager account
                            if (HostAuth.SCHEME_IMAP.equals(protocol) ||
                                    HostAuth.SCHEME_POP3.equals(protocol)) {
                                if (Email.DEBUG) {
                                    Log.d(TAG, "Create AccountManager account for " + protocol +
                                            "account: " +
                                            accountCursor.getString(V21_ACCOUNT_EMAIL));
                                }
                                createAccountManagerAccount(accountManagerContext,
                                        accountCursor.getString(V21_ACCOUNT_EMAIL),
                                        hostAuthCursor.getString(V21_HOSTAUTH_PASSWORD));
                            // If an EAS account, make Email sync automatically (equivalent of
                            // checking the "Sync Email" box in settings
                            } else if (HostAuth.SCHEME_EAS.equals(protocol)) {
                                android.accounts.Account amAccount =
                                        new android.accounts.Account(
                                                accountCursor.getString(V21_ACCOUNT_EMAIL),
                                                AccountManagerTypes.TYPE_EXCHANGE);
                                ContentResolver.setIsSyncable(amAccount, EmailContent.AUTHORITY, 1);
                                ContentResolver.setSyncAutomatically(amAccount,
                                        EmailContent.AUTHORITY, true);

                            }
                        }
                    } finally {
                        hostAuthCursor.close();
                    }
                }
            } finally {
                accountCursor.close();
            }
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            Log.w(TAG, "Exception upgrading EmailProvider.db from 20 to 21 " + e);
        }
    }

    /** Upgrades the database from v22 to v23 */
    private static void upgradeFromVersion22ToVersion23(SQLiteDatabase db) {
        try {
            db.execSQL("alter table " + Mailbox.TABLE_NAME
                    + " add column " + Mailbox.LAST_TOUCHED_TIME + " integer default 0;");
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            Log.w(TAG, "Exception upgrading EmailProvider.db from 22 to 23 " + e);
        }
    }

    /** Adds in a column for information about a client certificate to use. */
    private static void upgradeFromVersion23ToVersion24(SQLiteDatabase db) {
        try {
            db.execSQL("alter table " + HostAuth.TABLE_NAME
                    + " add column " + HostAuth.CLIENT_CERT_ALIAS + " text;");
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            Log.w(TAG, "Exception upgrading EmailProvider.db from 23 to 24 " + e);
        }
    }

    /** Upgrades the database from v24 to v25 by creating table for quick responses */
    private static void upgradeFromVersion24ToVersion25(SQLiteDatabase db) {
        try {
            createQuickResponseTable(db);
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            Log.w(TAG, "Exception upgrading EmailProvider.db from 24 to 25 " + e);
        }
    }

    private static final String[] V25_ACCOUNT_PROJECTION =
        new String[] {AccountColumns.ID, AccountColumns.FLAGS, AccountColumns.HOST_AUTH_KEY_RECV};
    private static final int V25_ACCOUNT_ID = 0;
    private static final int V25_ACCOUNT_FLAGS = 1;
    private static final int V25_ACCOUNT_RECV = 2;

    private static final String[] V25_HOSTAUTH_PROJECTION = new String[] {HostAuthColumns.PROTOCOL};
    private static final int V25_HOSTAUTH_PROTOCOL = 0;

    /** Upgrades the database from v25 to v26 by adding FLAG_SUPPORTS_SEARCH to IMAP accounts */
    private static void upgradeFromVersion25ToVersion26(SQLiteDatabase db) {
        try {
            // Loop through accounts, looking for imap accounts
            Cursor accountCursor = db.query(Account.TABLE_NAME, V25_ACCOUNT_PROJECTION, null,
                    null, null, null, null);
            ContentValues cv = new ContentValues();
            try {
                String[] hostAuthArgs = new String[1];
                while (accountCursor.moveToNext()) {
                    hostAuthArgs[0] = accountCursor.getString(V25_ACCOUNT_RECV);
                    // Get the "receive" HostAuth for this account
                    Cursor hostAuthCursor = db.query(HostAuth.TABLE_NAME,
                            V25_HOSTAUTH_PROJECTION, HostAuth.RECORD_ID + "=?", hostAuthArgs,
                            null, null, null);
                    try {
                        if (hostAuthCursor.moveToFirst()) {
                            String protocol = hostAuthCursor.getString(V25_HOSTAUTH_PROTOCOL);
                            // If this is an imap account, add the search flag
                            if (HostAuth.SCHEME_IMAP.equals(protocol)) {
                                String id = accountCursor.getString(V25_ACCOUNT_ID);
                                int flags = accountCursor.getInt(V25_ACCOUNT_FLAGS);
                                cv.put(AccountColumns.FLAGS, flags | Account.FLAGS_SUPPORTS_SEARCH);
                                db.update(Account.TABLE_NAME, cv, Account.RECORD_ID + "=?",
                                        new String[] {id});
                            }
                        }
                    } finally {
                        hostAuthCursor.close();
                    }
                }
            } finally {
                accountCursor.close();
            }
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            Log.w(TAG, "Exception upgrading EmailProvider.db from 25 to 26 " + e);
        }
    }

        /**
     * For testing purposes, check whether a given row is cached
     * @param baseUri the base uri of the EmailContent
     * @param id the row id of the EmailContent
     * @return whether or not the row is currently cached
     */
    @VisibleForTesting
    protected boolean isCached(Uri baseUri, long id) {
        int match = findMatch(baseUri, "isCached");
        int table = match >> BASE_SHIFT;
        ContentCache cache = mContentCaches[table];
        if (cache == null) return false;
        Cursor cc = cache.get(Long.toString(id));
        return (cc != null);
    }

    public static interface AttachmentService {
        /**
         * Notify the service that an attachment has changed.
         */
        void attachmentChanged(Context context, long id, int flags);
    }

    private final AttachmentService DEFAULT_ATTACHMENT_SERVICE = new AttachmentService() {
        @Override
        public void attachmentChanged(Context context, long id, int flags) {
            // The default implementation delegates to the real service.
            AttachmentDownloadService.attachmentChanged(context, id, flags);
        }
    };
    private AttachmentService mAttachmentService = DEFAULT_ATTACHMENT_SERVICE;

    /**
     * Injects a custom attachment service handler. If null is specified, will reset to the
     * default service.
     */
    public void injectAttachmentService(AttachmentService as) {
        mAttachmentService = (as == null) ? DEFAULT_ATTACHMENT_SERVICE : as;
    }
}
