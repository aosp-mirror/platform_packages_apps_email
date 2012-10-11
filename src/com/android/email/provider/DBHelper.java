/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.ContactsContract;
import android.util.Log;

import com.android.email.Email;
import com.android.emailcommon.AccountManagerTypes;
import com.android.emailcommon.CalendarProviderStub;
import com.android.emailcommon.mail.Address;
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

public final class DBHelper {
    private static final String TAG = "EmailProvider";

    private static final String WHERE_ID = EmailContent.RECORD_ID + "=?";

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
    // Version 29: Add protocolPoliciesEnforced and protocolPoliciesUnsupported to Policy
    // Version 30: Use CSV of RFC822 addresses instead of "packed" values
    // Version 31: Add columns to mailbox for ui status/last result
    // Version 32: Add columns to mailbox for last notified message key/count; insure not null
    //             for "notified" columns
    // Version 33: Add columns to attachment for ui provider columns
    // Version 34: Add total count to mailbox
    // Version 35: Set up defaults for lastTouchedCount for drafts and sent
    // Version 36: mblank intentionally left this space
    // Version 37: Add flag for settings support in folders
    // Version 38&39: Add threadTopic to message (for future support)

    // Versions 100+ are in Email2

    public static final int DATABASE_VERSION = 39;

    // Any changes to the database format *must* include update-in-place code.
    // Original version: 2
    // Version 3: Add "sourceKey" column
    // Version 4: Database wipe required; changing AccountManager interface w/Exchange
    // Version 5: Database wipe required; changing AccountManager interface w/Exchange
    // Version 6: Adding Body.mIntroText column
    // Version 7/8: Adding quoted text start pos

    // Versions 100+ are in Email2

    public static final int BODY_DATABASE_VERSION = 8;

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
            + MessageColumns.PROTOCOL_SEARCH_INFO + " text, "
            + MessageColumns.THREAD_TOPIC + " text"
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
            + PolicyColumns.PASSWORD_RECOVERY_ENABLED + " integer, "
            + PolicyColumns.PROTOCOL_POLICIES_ENFORCED + " text, "
            + PolicyColumns.PROTOCOL_POLICIES_UNSUPPORTED + " text"
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
            + MailboxColumns.LAST_TOUCHED_TIME + " integer default 0, "
            + MailboxColumns.UI_SYNC_STATUS + " integer default 0, "
            + MailboxColumns.UI_LAST_SYNC_RESULT + " integer default 0, "
            + MailboxColumns.LAST_NOTIFIED_MESSAGE_KEY + " integer not null default 0, "
            + MailboxColumns.LAST_NOTIFIED_MESSAGE_COUNT + " integer not null default 0, "
            + MailboxColumns.TOTAL_COUNT + " integer, "
            + MailboxColumns.LAST_SEEN_MESSAGE_KEY + " integer"
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
            + AttachmentColumns.ACCOUNT_KEY + " integer, "
            + AttachmentColumns.UI_STATE + " integer, "
            + AttachmentColumns.UI_DESTINATION + " integer, "
            + AttachmentColumns.UI_DOWNLOADED_SIZE + " integer"
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
            + BodyColumns.INTRO_TEXT + " text, "
            + BodyColumns.QUOTED_TEXT_START_POS + " integer"
            + ");";
        db.execSQL("create table " + Body.TABLE_NAME + s);
        db.execSQL(createIndex(Body.TABLE_NAME, BodyColumns.MESSAGE_KEY));
    }

    static void upgradeBodyTable(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 5) {
            try {
                db.execSQL("drop table " + Body.TABLE_NAME);
                createBodyTable(db);
                oldVersion = 5;
            } catch (SQLException e) {
            }
        }
        if (oldVersion == 5) {
            try {
                db.execSQL("alter table " + Body.TABLE_NAME
                        + " add " + BodyColumns.INTRO_TEXT + " text");
            } catch (SQLException e) {
                // Shouldn't be needed unless we're debugging and interrupt the process
                Log.w(TAG, "Exception upgrading EmailProviderBody.db from v5 to v6", e);
            }
            oldVersion = 6;
        }
        if (oldVersion == 6 || oldVersion ==7) {
            try {
                db.execSQL("alter table " + Body.TABLE_NAME
                        + " add " + BodyColumns.QUOTED_TEXT_START_POS + " integer");
            } catch (SQLException e) {
                // Shouldn't be needed unless we're debugging and interrupt the process
                Log.w(TAG, "Exception upgrading EmailProviderBody.db from v6 to v8", e);
            }
            oldVersion = 8;
        }
    }

    protected static class BodyDatabaseHelper extends SQLiteOpenHelper {
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
            try {
                // Cleanup some nasty records
                db.execSQL("delete from " + Account.TABLE_NAME
                         + " WHERE " + AccountColumns.DISPLAY_NAME + " ISNULL;");
                db.execSQL("delete from " + HostAuth.TABLE_NAME
                         + " WHERE " + HostAuthColumns.PROTOCOL + " ISNULL;");
            } catch (SQLException e) {
                // Shouldn't be needed unless we're debugging and interrupt the process
                Log.w(TAG, "Exception cleaning EmailProvider.db" + e);
            }
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

    protected static class DatabaseHelper extends SQLiteOpenHelper {
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
                try {
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + Mailbox.LAST_SEEN_MESSAGE_KEY + " integer;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 20 to 21 " + e);
                }
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
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 27 to 28 " + e);
                }
                oldVersion = 28;
            }
            if (oldVersion == 28) {
                try {
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + Policy.PROTOCOL_POLICIES_ENFORCED + " text;");
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + Policy.PROTOCOL_POLICIES_UNSUPPORTED + " text;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 28 to 29 " + e);
                }
                oldVersion = 29;
            }
            if (oldVersion == 29) {
                upgradeFromVersion29ToVersion30(db);
                oldVersion = 30;
            }
            if (oldVersion == 30) {
                try {
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + Mailbox.UI_SYNC_STATUS + " integer;");
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + Mailbox.UI_LAST_SYNC_RESULT + " integer;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 30 to 31 " + e);
                }
                oldVersion = 31;
            }
            if (oldVersion == 31) {
                try {
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + Mailbox.LAST_NOTIFIED_MESSAGE_KEY + " integer;");
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + Mailbox.LAST_NOTIFIED_MESSAGE_COUNT + " integer;");
                    db.execSQL("update Mailbox set " + Mailbox.LAST_NOTIFIED_MESSAGE_KEY +
                            "=0 where " + Mailbox.LAST_NOTIFIED_MESSAGE_KEY + " IS NULL");
                    db.execSQL("update Mailbox set " + Mailbox.LAST_NOTIFIED_MESSAGE_COUNT +
                            "=0 where " + Mailbox.LAST_NOTIFIED_MESSAGE_COUNT + " IS NULL");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 31 to 32 " + e);
                }
                oldVersion = 32;
            }
            if (oldVersion == 32) {
                try {
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + Attachment.UI_STATE + " integer;");
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + Attachment.UI_DESTINATION + " integer;");
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + Attachment.UI_DOWNLOADED_SIZE + " integer;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 32 to 33 " + e);
                }
                oldVersion = 33;
            }
            if (oldVersion == 33) {
                try {
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + MailboxColumns.TOTAL_COUNT + " integer;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 33 to 34 " + e);
                }
                oldVersion = 34;
            }
            if (oldVersion == 34) {
                oldVersion = 35;
            }
            if (oldVersion == 35 || oldVersion == 36) {
                oldVersion = 37;
            }
            if (oldVersion == 37) {
                try {
                    db.execSQL("alter table " + Message.TABLE_NAME
                            + " add column " + MessageColumns.THREAD_TOPIC + " text;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 37 to 38 " + e);
                }
                oldVersion = 38;
            }
            if (oldVersion == 38) {
                try {
                    db.execSQL("alter table " + Message.DELETED_TABLE_NAME
                            + " add column " + MessageColumns.THREAD_TOPIC + " text;");
                    db.execSQL("alter table " + Message.UPDATED_TABLE_NAME
                            + " add column " + MessageColumns.THREAD_TOPIC + " text;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    Log.w(TAG, "Exception upgrading EmailProvider.db from 38 to 39 " + e);
                }
                oldVersion = 39;
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
        }
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

    /** Upgrades the database from v29 to v30 by updating all address fields in Message */
    private static final int[] ADDRESS_COLUMN_INDICES = new int[] {
        Message.CONTENT_BCC_LIST_COLUMN, Message.CONTENT_CC_LIST_COLUMN,
        Message.CONTENT_FROM_LIST_COLUMN, Message.CONTENT_REPLY_TO_COLUMN,
        Message.CONTENT_TO_LIST_COLUMN
    };
    private static final String[] ADDRESS_COLUMN_NAMES = new String[] {
        Message.BCC_LIST, Message.CC_LIST, Message.FROM_LIST, Message.REPLY_TO_LIST, Message.TO_LIST
    };

    private static void upgradeFromVersion29ToVersion30(SQLiteDatabase db) {
        try {
            // Loop through all messages, updating address columns to new format (CSV, RFC822)
            Cursor messageCursor = db.query(Message.TABLE_NAME, Message.CONTENT_PROJECTION, null,
                    null, null, null, null);
            ContentValues cv = new ContentValues();
            String[] whereArgs = new String[1];
            try {
                while (messageCursor.moveToNext()) {
                    for (int i = 0; i < ADDRESS_COLUMN_INDICES.length; i++) {
                        Address[] addrs =
                                Address.unpack(messageCursor.getString(ADDRESS_COLUMN_INDICES[i]));
                        cv.put(ADDRESS_COLUMN_NAMES[i], Address.pack(addrs));
                    }
                    whereArgs[0] = messageCursor.getString(Message.CONTENT_ID_COLUMN);
                    db.update(Message.TABLE_NAME, cv, WHERE_ID, whereArgs);
                }
            } finally {
                messageCursor.close();
            }
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            Log.w(TAG, "Exception upgrading EmailProvider.db from 29 to 30 " + e);
        }
    }

}
