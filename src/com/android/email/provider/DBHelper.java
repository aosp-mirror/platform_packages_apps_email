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
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.provider.BaseColumns;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.text.TextUtils;

import com.android.email.R;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Credential;
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
import com.android.emailcommon.provider.MessageChangeLogTable;
import com.android.emailcommon.provider.MessageMove;
import com.android.emailcommon.provider.MessageStateChange;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.provider.QuickResponse;
import com.android.emailcommon.service.LegacyPolicySet;
import com.android.emailcommon.service.SyncWindow;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;

public final class DBHelper {
    private static final String TAG = "EmailProvider";

    private static final String LEGACY_SCHEME_IMAP = "imap";
    private static final String LEGACY_SCHEME_POP3 = "pop3";
    private static final String LEGACY_SCHEME_EAS = "eas";


    private static final String WHERE_ID = BaseColumns._ID + "=?";

    private static final String TRIGGER_MAILBOX_DELETE =
        "create trigger mailbox_delete before delete on " + Mailbox.TABLE_NAME +
        " begin" +
        " delete from " + Message.TABLE_NAME +
        "  where " + MessageColumns.MAILBOX_KEY + "=old." + BaseColumns._ID +
        "; delete from " + Message.UPDATED_TABLE_NAME +
        "  where " + MessageColumns.MAILBOX_KEY + "=old." + BaseColumns._ID +
        "; delete from " + Message.DELETED_TABLE_NAME +
        "  where " + MessageColumns.MAILBOX_KEY + "=old." + BaseColumns._ID +
        "; end";

    private static final String TRIGGER_ACCOUNT_DELETE =
        "create trigger account_delete before delete on " + Account.TABLE_NAME +
        " begin delete from " + Mailbox.TABLE_NAME +
        " where " + MailboxColumns.ACCOUNT_KEY + "=old." + BaseColumns._ID +
        "; delete from " + HostAuth.TABLE_NAME +
        " where " + BaseColumns._ID + "=old." + AccountColumns.HOST_AUTH_KEY_RECV +
        "; delete from " + HostAuth.TABLE_NAME +
        " where " + BaseColumns._ID + "=old." + AccountColumns.HOST_AUTH_KEY_SEND +
        "; delete from " + Policy.TABLE_NAME +
        " where " + BaseColumns._ID + "=old." + AccountColumns.POLICY_KEY +
        "; end";

    private static final String TRIGGER_HOST_AUTH_DELETE =
            "create trigger host_auth_delete after delete on " + HostAuth.TABLE_NAME +
            " begin delete from " + Credential.TABLE_NAME +
            " where " + Credential._ID + "=old." + HostAuthColumns.CREDENTIAL_KEY +
            " and (select count(*) from " + HostAuth.TABLE_NAME + " where " +
            HostAuthColumns.CREDENTIAL_KEY + "=old." + HostAuthColumns.CREDENTIAL_KEY + ")=0" +
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
    // Version 39 is last Email1 version
    // Version 100 is first Email2 version
    // Version 101 SHOULD NOT BE USED
    // Version 102&103: Add hierarchicalName to Mailbox
    // Version 104&105: add syncData to Message
    // Version 106: Add certificate to HostAuth
    // Version 107: Add a SEEN column to the message table
    // Version 108: Add a cachedFile column to the attachments table
    // Version 109: Migrate the account so they have the correct account manager types
    // Version 110: Stop updating message_count, don't use auto lookback, and don't use
    //              ping/push_hold sync states. Note that message_count updating is restored in 113.
    // Version 111: Delete Exchange account mailboxes.
    // Version 112: Convert Mailbox syncInterval to a boolean (whether or not this mailbox
    //              syncs along with the account).
    // Version 113: Restore message_count to being useful.
    // Version 114: Add lastFullSyncTime column
    // Version 115: Add pingDuration column
    // Version 116: Add MessageMove & MessageStateChange tables.
    // Version 117: Add trigger to delete duplicate messages on sync.
    // Version 118: Set syncInterval to 0 for all IMAP mailboxes
    // Version 119: Disable syncing of DRAFTS type folders.
    // Version 120: Changed duplicateMessage deletion trigger to ignore search mailboxes.
    // Version 121: Add mainMailboxKey, which will be set for messages that are in the fake
    //              "search_results" folder to reflect the mailbox that the server considers
    //              the message to be in. Also, wipe out any stale search_result folders.
    // Version 122: Need to update Message_Updates and Message_Deletes to match previous.
    // Version 123: Changed the duplicateMesage deletion trigger to ignore accounts that aren't
    //              exchange accounts.
    // Version 124: Added MAX_ATTACHMENT_SIZE to the account table
    // Version 125: Add credentials table for OAuth.
    // Version 126: Decode address lists for To, From, Cc, Bcc and Reply-To columns in Message.
    // Version 127: Force mFlags to contain the correct flags for EAS accounts given a protocol
    //              version above 12.0
    public static final int DATABASE_VERSION = 127;

    // Any changes to the database format *must* include update-in-place code.
    // Original version: 2
    // Version 3: Add "sourceKey" column
    // Version 4: Database wipe required; changing AccountManager interface w/Exchange
    // Version 5: Database wipe required; changing AccountManager interface w/Exchange
    // Version 6: Adding Body.mIntroText column
    // Version 7/8: Adding quoted text start pos
    // Version 8 is last Email1 version
    // Version 100 is the first Email2 version
    // Version 101: Move body contents to external files
    public static final int BODY_DATABASE_VERSION = 101;

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

    static void createMessageCountTriggers(final SQLiteDatabase db) {
        // Insert a message.
        db.execSQL("create trigger message_count_message_insert after insert on " +
                Message.TABLE_NAME +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.MESSAGE_COUNT +
                '=' + MailboxColumns.MESSAGE_COUNT + "+1" +
                "  where " + BaseColumns._ID + "=NEW." + MessageColumns.MAILBOX_KEY +
                "; end");

        // Delete a message.
        db.execSQL("create trigger message_count_message_delete after delete on " +
                Message.TABLE_NAME +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.MESSAGE_COUNT +
                '=' + MailboxColumns.MESSAGE_COUNT + "-1" +
                "  where " + BaseColumns._ID + "=OLD." + MessageColumns.MAILBOX_KEY +
                "; end");

        // Change a message's mailbox.
        db.execSQL("create trigger message_count_message_move after update of " +
                MessageColumns.MAILBOX_KEY + " on " + Message.TABLE_NAME +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.MESSAGE_COUNT +
                '=' + MailboxColumns.MESSAGE_COUNT + "-1" +
                "  where " + BaseColumns._ID + "=OLD." + MessageColumns.MAILBOX_KEY +
                "; update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.MESSAGE_COUNT +
                '=' + MailboxColumns.MESSAGE_COUNT + "+1" +
                " where " + BaseColumns._ID + "=NEW." + MessageColumns.MAILBOX_KEY +
                "; end");
    }

    static void createCredentialsTable(SQLiteDatabase db) {
        String s = " (" + Credential._ID + " integer primary key autoincrement, "
                + Credential.PROVIDER_COLUMN + " text,"
                + Credential.ACCESS_TOKEN_COLUMN + " text,"
                + Credential.REFRESH_TOKEN_COLUMN + " text,"
                + Credential.EXPIRATION_COLUMN + " integer"
                + ");";
        db.execSQL("create table " + Credential.TABLE_NAME + s);
        db.execSQL(TRIGGER_HOST_AUTH_DELETE);
    }

    static void dropDeleteDuplicateMessagesTrigger(final SQLiteDatabase db) {
        db.execSQL("drop trigger message_delete_duplicates_on_insert");
    }

    /**
     * Add a trigger to delete duplicate server side messages before insertion.
     * This should delete any messages older messages that have the same serverId and account as
     * the new message, if:
     *    Neither message is in a SEARCH type mailbox, and
     *    The new message's mailbox's account is an exchange account.
     *
     * Here is the plain text of this sql:
     *   create trigger message_delete_duplicates_on_insert before insert on
     *   Message for each row when new.syncServerId is not null and
     *    (select type from Mailbox where _id=new.mailboxKey) != 8 and
     *    (select HostAuth.protocol from HostAuth, Account where
     *       new.accountKey=account._id and account.hostAuthKeyRecv=hostAuth._id) = 'gEas'
     *   begin delete from Message where new.syncServerId=syncSeverId and
     *   new.accountKey=accountKey and
     *    (select Mailbox.type from Mailbox where _id=mailboxKey) != 8; end
     */
    static void createDeleteDuplicateMessagesTrigger(final Context context,
            final SQLiteDatabase db) {
        db.execSQL("create trigger message_delete_duplicates_on_insert before insert on "
                + Message.TABLE_NAME + " for each row when new." + SyncColumns.SERVER_ID
                + " is not null and "
                + "(select " + MailboxColumns.TYPE + " from " + Mailbox.TABLE_NAME
                + " where " + MailboxColumns._ID + "=new."
                + MessageColumns.MAILBOX_KEY + ")!=" + Mailbox.TYPE_SEARCH
                + " and (select "
                + HostAuth.TABLE_NAME + "." + HostAuthColumns.PROTOCOL + " from "
                + HostAuth.TABLE_NAME + "," + Account.TABLE_NAME
                + " where new." + MessageColumns.ACCOUNT_KEY
                + "=" + Account.TABLE_NAME + "." + AccountColumns._ID
                + " and " + Account.TABLE_NAME + "." + AccountColumns.HOST_AUTH_KEY_RECV
                + "=" + HostAuth.TABLE_NAME + "." + HostAuthColumns._ID
                + ")='" + context.getString(R.string.protocol_eas) + "'"
                + " begin delete from " + Message.TABLE_NAME + " where new."
                + SyncColumns.SERVER_ID + "=" + SyncColumns.SERVER_ID + " and new."
                + MessageColumns.ACCOUNT_KEY + "=" + MessageColumns.ACCOUNT_KEY
                + " and (select " + Mailbox.TABLE_NAME + "." + MailboxColumns.TYPE + " from "
                + Mailbox.TABLE_NAME + " where " + MailboxColumns._ID + "="
                + MessageColumns.MAILBOX_KEY + ")!=" + Mailbox.TYPE_SEARCH +"; end");
    }

    static void createMessageTable(Context context, SQLiteDatabase db) {
        String messageColumns = MessageColumns.DISPLAY_NAME + " text, "
            + MessageColumns.TIMESTAMP + " integer, "
            + MessageColumns.SUBJECT + " text, "
            + MessageColumns.FLAG_READ + " integer, "
            + MessageColumns.FLAG_LOADED + " integer, "
            + MessageColumns.FLAG_FAVORITE + " integer, "
            + MessageColumns.FLAG_ATTACHMENT + " integer, "
            + MessageColumns.FLAGS + " integer, "
            + MessageColumns.DRAFT_INFO + " integer, "
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
            + MessageColumns.THREAD_TOPIC + " text, "
            + MessageColumns.SYNC_DATA + " text, "
            + MessageColumns.FLAG_SEEN + " integer, "
            + MessageColumns.MAIN_MAILBOX_KEY + " integer"
            + ");";

        // This String and the following String MUST have the same columns, except for the type
        // of those columns!
        String createString = " (" + BaseColumns._ID + " integer primary key autoincrement, "
            + SyncColumns.SERVER_ID + " text, "
            + SyncColumns.SERVER_TIMESTAMP + " integer, "
            + messageColumns;

        // For the updated and deleted tables, the id is assigned, but we do want to keep track
        // of the ORDER of updates using an autoincrement primary key.  We use the DATA column
        // at this point; it has no other function
        String altCreateString = " (" + BaseColumns._ID + " integer unique, "
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
                "  where " + AttachmentColumns.MESSAGE_KEY + "=old." + BaseColumns._ID +
                "; end");

        // Add triggers to keep unread count accurate per mailbox

        // NOTE: SQLite's before triggers are not safe when recursive triggers are involved.
        // Use caution when changing them.

        // Insert a message; if flagRead is zero, add to the unread count of the message's mailbox
        db.execSQL("create trigger unread_message_insert before insert on " + Message.TABLE_NAME +
                " when NEW." + MessageColumns.FLAG_READ + "=0" +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.UNREAD_COUNT +
                '=' + MailboxColumns.UNREAD_COUNT + "+1" +
                "  where " + BaseColumns._ID + "=NEW." + MessageColumns.MAILBOX_KEY +
                "; end");

        // Delete a message; if flagRead is zero, decrement the unread count of the msg's mailbox
        db.execSQL("create trigger unread_message_delete before delete on " + Message.TABLE_NAME +
                " when OLD." + MessageColumns.FLAG_READ + "=0" +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.UNREAD_COUNT +
                '=' + MailboxColumns.UNREAD_COUNT + "-1" +
                "  where " + BaseColumns._ID + "=OLD." + MessageColumns.MAILBOX_KEY +
                "; end");

        // Change a message's mailbox
        db.execSQL("create trigger unread_message_move before update of " +
                MessageColumns.MAILBOX_KEY + " on " + Message.TABLE_NAME +
                " when OLD." + MessageColumns.FLAG_READ + "=0" +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.UNREAD_COUNT +
                '=' + MailboxColumns.UNREAD_COUNT + "-1" +
                "  where " + BaseColumns._ID + "=OLD." + MessageColumns.MAILBOX_KEY +
                "; update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.UNREAD_COUNT +
                '=' + MailboxColumns.UNREAD_COUNT + "+1" +
                " where " + BaseColumns._ID + "=NEW." + MessageColumns.MAILBOX_KEY +
                "; end");

        // Change a message's read state
        db.execSQL("create trigger unread_message_read before update of " +
                MessageColumns.FLAG_READ + " on " + Message.TABLE_NAME +
                " when OLD." + MessageColumns.FLAG_READ + "!=NEW." + MessageColumns.FLAG_READ +
                " begin update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.UNREAD_COUNT +
                '=' + MailboxColumns.UNREAD_COUNT + "+ case OLD." + MessageColumns.FLAG_READ +
                " when 0 then -1 else 1 end" +
                "  where " + BaseColumns._ID + "=OLD." + MessageColumns.MAILBOX_KEY +
                "; end");

        // Add triggers to maintain message_count.
        createMessageCountTriggers(db);
        createDeleteDuplicateMessagesTrigger(context, db);
    }

    static void resetMessageTable(Context context, SQLiteDatabase db,
            int oldVersion, int newVersion) {
        try {
            db.execSQL("drop table " + Message.TABLE_NAME);
            db.execSQL("drop table " + Message.UPDATED_TABLE_NAME);
            db.execSQL("drop table " + Message.DELETED_TABLE_NAME);
        } catch (SQLException e) {
        }
        createMessageTable(context, db);
    }

    /**
     * Common columns for all {@link MessageChangeLogTable} tables.
     */
    private static String MESSAGE_CHANGE_LOG_COLUMNS =
            MessageChangeLogTable.ID + " integer primary key autoincrement, "
            + MessageChangeLogTable.MESSAGE_KEY + " integer, "
            + MessageChangeLogTable.SERVER_ID + " text, "
            + MessageChangeLogTable.ACCOUNT_KEY + " integer, "
            + MessageChangeLogTable.STATUS + " integer, ";

    /**
     * Create indices common to all {@link MessageChangeLogTable} tables.
     * @param db The {@link SQLiteDatabase}.
     * @param tableName The name of this particular table.
     */
    private static void createMessageChangeLogTableIndices(final SQLiteDatabase db,
            final String tableName) {
        db.execSQL(createIndex(tableName, MessageChangeLogTable.MESSAGE_KEY));
        db.execSQL(createIndex(tableName, MessageChangeLogTable.ACCOUNT_KEY));
    }

    /**
     * Create triggers common to all {@link MessageChangeLogTable} tables.
     * @param db The {@link SQLiteDatabase}.
     * @param tableName The name of this particular table.
     */
    private static void createMessageChangeLogTableTriggers(final SQLiteDatabase db,
            final String tableName) {
        // Trigger to delete from the change log when a message is deleted.
        db.execSQL("create trigger " + tableName + "_delete_message before delete on "
                + Message.TABLE_NAME + " for each row begin delete from " + tableName
                + " where " + MessageChangeLogTable.MESSAGE_KEY + "=old." + MessageColumns._ID
                + "; end");

        // Trigger to delete from the change log when an account is deleted.
        db.execSQL("create trigger " + tableName + "_delete_account before delete on "
                + Account.TABLE_NAME + " for each row begin delete from " + tableName
                + " where " + MessageChangeLogTable.ACCOUNT_KEY + "=old." + AccountColumns._ID
                + "; end");
    }

    /**
     * Create the MessageMove table.
     * @param db The {@link SQLiteDatabase}.
     */
    private static void createMessageMoveTable(final SQLiteDatabase db) {
        db.execSQL("create table " + MessageMove.TABLE_NAME + " ("
                + MESSAGE_CHANGE_LOG_COLUMNS
                + MessageMove.SRC_FOLDER_KEY + " integer, "
                + MessageMove.DST_FOLDER_KEY + " integer, "
                + MessageMove.SRC_FOLDER_SERVER_ID + " text, "
                + MessageMove.DST_FOLDER_SERVER_ID + " text);");

        createMessageChangeLogTableIndices(db, MessageMove.TABLE_NAME);
        createMessageChangeLogTableTriggers(db, MessageMove.TABLE_NAME);
    }

    /**
     * Create the MessageStateChange table.
     * @param db The {@link SQLiteDatabase}.
     */
    private static void createMessageStateChangeTable(final SQLiteDatabase db) {
        db.execSQL("create table " + MessageStateChange.TABLE_NAME + " ("
                + MESSAGE_CHANGE_LOG_COLUMNS
                + MessageStateChange.OLD_FLAG_READ + " integer, "
                + MessageStateChange.NEW_FLAG_READ + " integer, "
                + MessageStateChange.OLD_FLAG_FAVORITE + " integer, "
                + MessageStateChange.NEW_FLAG_FAVORITE + " integer);");

        createMessageChangeLogTableIndices(db, MessageStateChange.TABLE_NAME);
        createMessageChangeLogTableTriggers(db, MessageStateChange.TABLE_NAME);
    }

    @SuppressWarnings("deprecation")
    static void createAccountTable(SQLiteDatabase db) {
        String s = " (" + AccountColumns._ID + " integer primary key autoincrement, "
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
            + AccountColumns.MAX_ATTACHMENT_SIZE + " integer, "
            + AccountColumns.PING_DURATION + " integer"
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
        String s = " (" + PolicyColumns._ID + " integer primary key autoincrement, "
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
        String s = " (" + HostAuthColumns._ID + " integer primary key autoincrement, "
            + HostAuthColumns.PROTOCOL + " text, "
            + HostAuthColumns.ADDRESS + " text, "
            + HostAuthColumns.PORT + " integer, "
            + HostAuthColumns.FLAGS + " integer, "
            + HostAuthColumns.LOGIN + " text, "
            + HostAuthColumns.PASSWORD + " text, "
            + HostAuthColumns.DOMAIN + " text, "
            + HostAuthColumns.ACCOUNT_KEY + " integer,"
            + HostAuthColumns.CLIENT_CERT_ALIAS + " text,"
            + HostAuthColumns.SERVER_CERT + " blob,"
            + HostAuthColumns.CREDENTIAL_KEY + " integer"
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

    @SuppressWarnings("deprecation")
    static void createMailboxTable(SQLiteDatabase db) {
        String s = " (" + MailboxColumns._ID + " integer primary key autoincrement, "
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
            + MailboxColumns.HIERARCHICAL_NAME + " text, "
            + MailboxColumns.LAST_FULL_SYNC_TIME + " integer"
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
        String s = " (" + AttachmentColumns._ID + " integer primary key autoincrement, "
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
            + AttachmentColumns.UI_DOWNLOADED_SIZE + " integer, "
            + AttachmentColumns.CACHED_FILE + " text"
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
        String s = " (" + QuickResponseColumns._ID + " integer primary key autoincrement, "
                + QuickResponseColumns.TEXT + " text, "
                + QuickResponseColumns.ACCOUNT_KEY + " integer"
                + ");";
        db.execSQL("create table " + QuickResponse.TABLE_NAME + s);
    }

    @SuppressWarnings("deprecation")
    static void createBodyTable(SQLiteDatabase db) {
        String s = " (" + BodyColumns._ID + " integer primary key autoincrement, "
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

    private static void upgradeBodyToVersion5(final SQLiteDatabase db) {
        try {
            db.execSQL("drop table " + Body.TABLE_NAME);
            createBodyTable(db);
        } catch (final SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            LogUtils.w(TAG, e, "Exception upgrading EmailProviderBody.db from <v5");
        }
    }

    @SuppressWarnings("deprecation")
    private static void upgradeBodyFromVersion5ToVersion6(final SQLiteDatabase db) {
        try {
            db.execSQL("alter table " + Body.TABLE_NAME
                    + " add " + BodyColumns.INTRO_TEXT + " text");
        } catch (final SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            LogUtils.w(TAG, e, "Exception upgrading EmailProviderBody.db from v5 to v6");
        }
    }

    private static void upgradeBodyFromVersion6ToVersion8(final SQLiteDatabase db) {
        try {
            db.execSQL("alter table " + Body.TABLE_NAME
                    + " add " + BodyColumns.QUOTED_TEXT_START_POS + " integer");
        } catch (final SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            LogUtils.w(TAG, e, "Exception upgrading EmailProviderBody.db from v6 to v8");
        }
    }

    /**
     * This upgrade migrates email bodies out of the database and into individual files.
     */
    private static void upgradeBodyFromVersion100ToVersion101(final Context context,
            final SQLiteDatabase db) {
        try {
            // We can't read the body parts through the cursor because they might be over 2MB
            final String projection[] = { BodyColumns.MESSAGE_KEY };
            final Cursor cursor = db.query(Body.TABLE_NAME, projection,
                    null, null, null, null, null);
            if (cursor == null) {
                throw new IllegalStateException("Could not read body table for upgrade");
            }

            final SQLiteStatement htmlSql = db.compileStatement(
                    "SELECT " + BodyColumns.HTML_CONTENT +
                            " FROM " + Body.TABLE_NAME +
                            " WHERE " + BodyColumns.MESSAGE_KEY + "=?"
            );

            final SQLiteStatement textSql = db.compileStatement(
                    "SELECT " + BodyColumns.TEXT_CONTENT +
                            " FROM " + Body.TABLE_NAME +
                            " WHERE " + BodyColumns.MESSAGE_KEY + "=?"
            );

            while (cursor.moveToNext()) {
                final long messageId = cursor.getLong(0);
                htmlSql.bindLong(1, messageId);
                try {
                    final String htmlString = htmlSql.simpleQueryForString();
                    if (!TextUtils.isEmpty(htmlString)) {
                        final File htmlFile = EmailProvider.getBodyFile(context, messageId, "html");
                        final FileWriter w = new FileWriter(htmlFile);
                        try {
                            w.write(htmlString);
                        } finally {
                            w.close();
                        }
                    }
                } catch (final SQLiteDoneException e) {
                    LogUtils.v(LogUtils.TAG, e, "Done with the HTML column");
                }
                textSql.bindLong(1, messageId);
                try {
                    final String textString = textSql.simpleQueryForString();
                    if (!TextUtils.isEmpty(textString)) {
                        final File textFile = EmailProvider.getBodyFile(context, messageId, "txt");
                        final FileWriter w = new FileWriter(textFile);
                        try {
                            w.write(textString);
                        } finally {
                            w.close();
                        }
                    }
                } catch (final SQLiteDoneException e) {
                    LogUtils.v(LogUtils.TAG, e, "Done with the text column");
                }
            }

            db.execSQL("update " + Body.TABLE_NAME +
                    " set " + BodyColumns.HTML_CONTENT + "=NULL,"
                    + BodyColumns.TEXT_CONTENT + "=NULL");
        } catch (final SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            LogUtils.w(TAG, e, "Exception upgrading EmailProviderBody.db from v100 to v101");
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }


    protected static class BodyDatabaseHelper extends SQLiteOpenHelper {
        final Context mContext;

        BodyDatabaseHelper(Context context, String name) {
            super(context, name, null, BODY_DATABASE_VERSION);
            mContext = context;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            LogUtils.d(TAG, "Creating EmailProviderBody database");
            createBodyTable(db);
        }

        @Override
        public void onUpgrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
            if (oldVersion < 5) {
                upgradeBodyToVersion5(db);
            }
            if (oldVersion < 6) {
                upgradeBodyFromVersion5ToVersion6(db);
            }
            if (oldVersion < 8) {
                upgradeBodyFromVersion6ToVersion8(db);
            }
            if (oldVersion < 101) {
                upgradeBodyFromVersion100ToVersion101(mContext, db);
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
        }
    }

    /** Counts the number of messages in each mailbox, and updates the message count column. */
    @VisibleForTesting
    static void recalculateMessageCount(SQLiteDatabase db) {
        db.execSQL("update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.MESSAGE_COUNT +
                "= (select count(*) from " + Message.TABLE_NAME +
                " where " + MessageColumns.MAILBOX_KEY + " = " +
                    Mailbox.TABLE_NAME + "." + MailboxColumns._ID + ")");
    }

    protected static class DatabaseHelper extends SQLiteOpenHelper {
        final Context mContext;

        DatabaseHelper(Context context, String name) {
            super(context, name, null, DATABASE_VERSION);
            mContext = context;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            LogUtils.d(TAG, "Creating EmailProvider database");
            // Create all tables here; each class has its own method
            createMessageTable(mContext, db);
            createAttachmentTable(db);
            createMailboxTable(db);
            createHostAuthTable(db);
            createAccountTable(db);
            createMessageMoveTable(db);
            createMessageStateChangeTable(db);
            createPolicyTable(db);
            createQuickResponseTable(db);
            createCredentialsTable(db);
        }

        @Override
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion == 101 && newVersion == 100) {
                LogUtils.d(TAG, "Downgrade from v101 to v100");
            } else {
                super.onDowngrade(db, oldVersion, newVersion);
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // For versions prior to 5, delete all data
            // Versions >= 5 require that data be preserved!
            if (oldVersion < 5) {
                android.accounts.Account[] accounts = AccountManager.get(mContext)
                        .getAccountsByType(LEGACY_SCHEME_EAS);
                for (android.accounts.Account account: accounts) {
                    AccountManager.get(mContext).removeAccount(account, null, null);
                }
                resetMessageTable(mContext, db, oldVersion, newVersion);
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
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from v5 to v6", e);
                }
            }
            // TODO: Change all these to strict inequalities
            if (oldVersion <= 6) {
                // Use the newer mailbox_delete trigger
                db.execSQL("drop trigger mailbox_delete;");
                db.execSQL(TRIGGER_MAILBOX_DELETE);
            }
            if (oldVersion <= 7) {
                // add the security (provisioning) column
                try {
                    db.execSQL("alter table " + Account.TABLE_NAME
                            + " add column " + AccountColumns.SECURITY_FLAGS + " integer" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 7 to 8 " + e);
                }
            }
            if (oldVersion <= 8) {
                // accounts: add security sync key & user signature columns
                try {
                    db.execSQL("alter table " + Account.TABLE_NAME
                            + " add column " + AccountColumns.SECURITY_SYNC_KEY + " text" + ";");
                    db.execSQL("alter table " + Account.TABLE_NAME
                            + " add column " + AccountColumns.SIGNATURE + " text" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 8 to 9 " + e);
                }
            }
            if (oldVersion <= 9) {
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
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 9 to 10 " + e);
                }
            }
            if (oldVersion <= 10) {
                // Attachment: add content and flags columns
                try {
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + AttachmentColumns.CONTENT + " text" + ";");
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + AttachmentColumns.FLAGS + " integer" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 10 to 11 " + e);
                }
            }
            if (oldVersion <= 11) {
                // Attachment: add content_bytes
                try {
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + AttachmentColumns.CONTENT_BYTES + " blob" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 11 to 12 " + e);
                }
            }
            if (oldVersion <= 12) {
                try {
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + Mailbox.MESSAGE_COUNT
                                    +" integer not null default 0" + ";");
                    recalculateMessageCount(db);
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 12 to 13 " + e);
                }
            }
            if (oldVersion <= 13) {
                try {
                    db.execSQL("alter table " + Message.TABLE_NAME
                            + " add column " + MessageColumns.SNIPPET
                                    +" text" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 13 to 14 " + e);
                }
            }
            if (oldVersion <= 14) {
                try {
                    db.execSQL("alter table " + Message.DELETED_TABLE_NAME
                            + " add column " + MessageColumns.SNIPPET +" text" + ";");
                    db.execSQL("alter table " + Message.UPDATED_TABLE_NAME
                            + " add column " + MessageColumns.SNIPPET +" text" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 14 to 15 " + e);
                }
            }
            if (oldVersion <= 15) {
                try {
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + AttachmentColumns.ACCOUNT_KEY +" integer" + ";");
                    // Update all existing attachments to add the accountKey data
                    db.execSQL("update " + Attachment.TABLE_NAME + " set " +
                            AttachmentColumns.ACCOUNT_KEY + "= (SELECT " + Message.TABLE_NAME +
                            "." + MessageColumns.ACCOUNT_KEY + " from " + Message.TABLE_NAME +
                            " where " + Message.TABLE_NAME + "." + MessageColumns._ID + " = " +
                            Attachment.TABLE_NAME + "." + AttachmentColumns.MESSAGE_KEY + ")");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 15 to 16 " + e);
                }
            }
            if (oldVersion <= 16) {
                try {
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + Mailbox.PARENT_KEY + " integer;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 16 to 17 " + e);
                }
            }
            if (oldVersion <= 17) {
                upgradeFromVersion17ToVersion18(db);
            }
            if (oldVersion <= 18) {
                try {
                    db.execSQL("alter table " + Account.TABLE_NAME
                            + " add column " + AccountColumns.POLICY_KEY + " integer;");
                    db.execSQL("drop trigger account_delete;");
                    db.execSQL(TRIGGER_ACCOUNT_DELETE);
                    createPolicyTable(db);
                    convertPolicyFlagsToPolicyTable(db);
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 18 to 19 " + e);
                }
            }
            if (oldVersion <= 19) {
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
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 19 to 20 " + e);
                }
            }
            if (oldVersion <= 21) {
                upgradeFromVersion21ToVersion22(db, mContext);
                oldVersion = 22;
            }
            if (oldVersion <= 22) {
                upgradeFromVersion22ToVersion23(db);
            }
            if (oldVersion <= 23) {
                upgradeFromVersion23ToVersion24(db);
            }
            if (oldVersion <= 24) {
                upgradeFromVersion24ToVersion25(db);
            }
            if (oldVersion <= 25) {
                upgradeFromVersion25ToVersion26(db);
            }
            if (oldVersion <= 26) {
                try {
                    db.execSQL("alter table " + Message.TABLE_NAME
                            + " add column " + MessageColumns.PROTOCOL_SEARCH_INFO + " text;");
                    db.execSQL("alter table " + Message.DELETED_TABLE_NAME
                            + " add column " + MessageColumns.PROTOCOL_SEARCH_INFO +" text" + ";");
                    db.execSQL("alter table " + Message.UPDATED_TABLE_NAME
                            + " add column " + MessageColumns.PROTOCOL_SEARCH_INFO +" text" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 26 to 27 " + e);
                }
            }
            if (oldVersion <= 28) {
                try {
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + Policy.PROTOCOL_POLICIES_ENFORCED + " text;");
                    db.execSQL("alter table " + Policy.TABLE_NAME
                            + " add column " + Policy.PROTOCOL_POLICIES_UNSUPPORTED + " text;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 28 to 29 " + e);
                }
            }
            if (oldVersion <= 29) {
                upgradeFromVersion29ToVersion30(db);
            }
            if (oldVersion <= 30) {
                try {
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + Mailbox.UI_SYNC_STATUS + " integer;");
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + Mailbox.UI_LAST_SYNC_RESULT + " integer;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 30 to 31 " + e);
                }
            }
            if (oldVersion <= 31) {
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
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 31 to 32 " + e);
                }
            }
            if (oldVersion <= 32) {
                try {
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + AttachmentColumns.UI_STATE + " integer;");
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + AttachmentColumns.UI_DESTINATION + " integer;");
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + AttachmentColumns.UI_DOWNLOADED_SIZE + " integer;");
                    // If we have a contentUri then the attachment is saved
                    // uiDestination of 0 = "cache", so we don't have to set this
                    db.execSQL("update " + Attachment.TABLE_NAME + " set " +
                            AttachmentColumns.UI_STATE + "=" + UIProvider.AttachmentState.SAVED +
                            " where " + AttachmentColumns.CONTENT_URI + " is not null;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 32 to 33 " + e);
                }
            }
            if (oldVersion <= 33) {
                try {
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + MailboxColumns.TOTAL_COUNT + " integer;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 33 to 34 " + e);
                }
            }
            if (oldVersion <= 34) {
                try {
                    db.execSQL("update " + Mailbox.TABLE_NAME + " set " +
                            MailboxColumns.LAST_TOUCHED_TIME + " = " +
                            Mailbox.DRAFTS_DEFAULT_TOUCH_TIME + " WHERE " + MailboxColumns.TYPE +
                            " = " + Mailbox.TYPE_DRAFTS);
                    db.execSQL("update " + Mailbox.TABLE_NAME + " set " +
                            MailboxColumns.LAST_TOUCHED_TIME + " = " +
                            Mailbox.SENT_DEFAULT_TOUCH_TIME + " WHERE " + MailboxColumns.TYPE +
                            " = " + Mailbox.TYPE_SENT);
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 34 to 35 " + e);
                }
            }
            if (oldVersion <= 36) {
                try {
                    // Set "supports settings" for EAS mailboxes
                    db.execSQL("update " + Mailbox.TABLE_NAME + " set " +
                            MailboxColumns.FLAGS + "=" + MailboxColumns.FLAGS + "|" +
                            Mailbox.FLAG_SUPPORTS_SETTINGS + " where (" +
                            MailboxColumns.FLAGS + "&" + Mailbox.FLAG_HOLDS_MAIL + ")!=0 and " +
                            MailboxColumns.ACCOUNT_KEY + " IN (SELECT " + Account.TABLE_NAME +
                            "." + AccountColumns._ID + " from " + Account.TABLE_NAME + "," +
                            HostAuth.TABLE_NAME + " where " + Account.TABLE_NAME + "." +
                            AccountColumns.HOST_AUTH_KEY_RECV + "=" + HostAuth.TABLE_NAME + "." +
                            HostAuthColumns._ID + " and " + HostAuthColumns.PROTOCOL + "='" +
                            LEGACY_SCHEME_EAS + "')");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 35 to 36 " + e);
                }
            }
            if (oldVersion <= 37) {
                try {
                    db.execSQL("alter table " + Message.TABLE_NAME
                            + " add column " + MessageColumns.THREAD_TOPIC + " text;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 37 to 38 " + e);
                }
            }
            if (oldVersion <= 38) {
                try {
                    db.execSQL("alter table " + Message.DELETED_TABLE_NAME
                            + " add column " + MessageColumns.THREAD_TOPIC + " text;");
                    db.execSQL("alter table " + Message.UPDATED_TABLE_NAME
                            + " add column " + MessageColumns.THREAD_TOPIC + " text;");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 38 to 39 " + e);
                }
            }
            if (oldVersion <= 39) {
                upgradeToEmail2(db);
            }
            if (oldVersion <= 102) {
                try {
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add " + MailboxColumns.HIERARCHICAL_NAME + " text");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from v10x to v103", e);
                }
            }
            if (oldVersion <= 103) {
                try {
                    db.execSQL("alter table " + Message.TABLE_NAME
                            + " add " + MessageColumns.SYNC_DATA + " text");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from v103 to v104", e);
                }
            }
            if (oldVersion <= 104) {
                try {
                    db.execSQL("alter table " + Message.UPDATED_TABLE_NAME
                            + " add " + MessageColumns.SYNC_DATA + " text");
                    db.execSQL("alter table " + Message.DELETED_TABLE_NAME
                            + " add " + MessageColumns.SYNC_DATA + " text");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from v104 to v105", e);
                }
            }
            if (oldVersion <= 105) {
                try {
                    db.execSQL("alter table " + HostAuth.TABLE_NAME
                            + " add " + HostAuthColumns.SERVER_CERT + " blob");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from v105 to v106", e);
                }
            }
            if (oldVersion <= 106) {
                try {
                    db.execSQL("alter table " + Message.TABLE_NAME
                            + " add " + MessageColumns.FLAG_SEEN + " integer");
                    db.execSQL("alter table " + Message.UPDATED_TABLE_NAME
                            + " add " + MessageColumns.FLAG_SEEN + " integer");
                    db.execSQL("alter table " + Message.DELETED_TABLE_NAME
                            + " add " + MessageColumns.FLAG_SEEN + " integer");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from v106 to v107", e);
                }
            }
            if (oldVersion <= 107) {
                try {
                    db.execSQL("alter table " + Attachment.TABLE_NAME
                            + " add column " + AttachmentColumns.CACHED_FILE +" text" + ";");
                } catch (SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from v107 to v108", e);
                }
            }
            if (oldVersion <= 108) {
                // Migrate the accounts with the correct account type
                migrateLegacyAccounts(db, mContext);
            }
            if (oldVersion <= 109) {
                // Fix any mailboxes that have ping or push_hold states.
                db.execSQL("update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.SYNC_INTERVAL
                        + "=" + Mailbox.CHECK_INTERVAL_PUSH + " where "
                        + MailboxColumns.SYNC_INTERVAL + "<" + Mailbox.CHECK_INTERVAL_PUSH);

                // Fix invalid syncLookback values.
                db.execSQL("update " + Account.TABLE_NAME + " set " + AccountColumns.SYNC_LOOKBACK
                        + "=" + SyncWindow.SYNC_WINDOW_1_WEEK + " where "
                        + AccountColumns.SYNC_LOOKBACK + " is null or "
                        + AccountColumns.SYNC_LOOKBACK + "<" + SyncWindow.SYNC_WINDOW_1_DAY + " or "
                        + AccountColumns.SYNC_LOOKBACK + ">" + SyncWindow.SYNC_WINDOW_ALL);

                db.execSQL("update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.SYNC_LOOKBACK
                        + "=" + SyncWindow.SYNC_WINDOW_ACCOUNT + " where "
                        + MailboxColumns.SYNC_LOOKBACK + " is null or "
                        + MailboxColumns.SYNC_LOOKBACK + "<" + SyncWindow.SYNC_WINDOW_1_DAY + " or "
                        + MailboxColumns.SYNC_LOOKBACK + ">" + SyncWindow.SYNC_WINDOW_ALL);
            }
            if (oldVersion <= 110) {
                // Delete account mailboxes.
                db.execSQL("delete from " + Mailbox.TABLE_NAME + " where " + MailboxColumns.TYPE
                        + "=" +Mailbox.TYPE_EAS_ACCOUNT_MAILBOX);
            }
            if (oldVersion <= 111) {
                // Mailbox sync interval now indicates whether this mailbox syncs with the rest
                // of the account. Anyone who was syncing at all, plus outboxes, are set to 1,
                // everyone else is 0.
                db.execSQL("update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.SYNC_INTERVAL
                        + "=case when " + MailboxColumns.SYNC_INTERVAL + "="
                        + Mailbox.CHECK_INTERVAL_NEVER + " then 0 else 1 end");
            }
            if (oldVersion >= 110 && oldVersion <= 112) {
                // v110 had dropped these triggers, but starting with v113 we restored them
                // (and altered the 109 -> 110 upgrade code to stop dropping them).
                // We therefore only add them back for the versions in between. We also need to
                // compute the correct value at this point as well.
                recalculateMessageCount(db);
                createMessageCountTriggers(db);
            }

            if (oldVersion <= 113) {
                try {
                    db.execSQL("alter table " + Mailbox.TABLE_NAME
                            + " add column " + MailboxColumns.LAST_FULL_SYNC_TIME +" integer" + ";");
                    final ContentValues cv = new ContentValues(1);
                    cv.put(MailboxColumns.LAST_FULL_SYNC_TIME, 0);
                    db.update(Mailbox.TABLE_NAME, cv, null, null);
                } catch (final SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from v113 to v114", e);
                }
            }

            if (oldVersion <= 114) {
                try {
                    db.execSQL("alter table " + Account.TABLE_NAME
                            + " add column " + AccountColumns.PING_DURATION +" integer" + ";");
                    final ContentValues cv = new ContentValues(1);
                    cv.put(AccountColumns.PING_DURATION, 0);
                    db.update(Account.TABLE_NAME, cv, null, null);
                } catch (final SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from v113 to v114", e);
                }
            }

            if (oldVersion <= 115) {
                createMessageMoveTable(db);
                createMessageStateChangeTable(db);
            }

            /**
             * Originally, at 116, we added a trigger to delete duplicate messages.
             * But we needed to change that trigger for version 120, so when we get
             * there, we'll drop the trigger if it exists and create a new version.
             */

            /**
             * This statement changes the syncInterval column to 0 for all IMAP mailboxes.
             * It does this by matching mailboxes against all account IDs whose receive auth is
             * either R.string.protocol_legacy_imap, R.string.protocol_imap or "imap"
             */
            if (oldVersion <= 117) {
                db.execSQL("update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.SYNC_INTERVAL
                        + "=0 where " + MailboxColumns.ACCOUNT_KEY + " in (select "
                        + Account.TABLE_NAME + "." + AccountColumns._ID + " from "
                        + Account.TABLE_NAME + " join " + HostAuth.TABLE_NAME + " where "
                        + HostAuth.TABLE_NAME + "." + HostAuthColumns._ID + "="
                        + Account.TABLE_NAME + "." + AccountColumns.HOST_AUTH_KEY_RECV
                        + " and (" + HostAuth.TABLE_NAME + "."
                        + HostAuthColumns.PROTOCOL + "='"
                        + mContext.getString(R.string.protocol_legacy_imap) + "' or "
                        + HostAuth.TABLE_NAME + "." + HostAuthColumns.PROTOCOL + "='"
                        + mContext.getString(R.string.protocol_imap) + "' or "
                        + HostAuth.TABLE_NAME + "." + HostAuthColumns.PROTOCOL + "='imap'));");
            }

            /**
             * This statement changes the sync interval column to 0 for all DRAFTS type mailboxes,
             * and deletes any messages that are:
             *   * synced from the server, and
             *   * in an exchange account draft folder
             *
             * This is primary for Exchange (b/11158759) but we don't sync draft folders for any
             * other account type anyway.
             * This will only affect people who used intermediate builds between email1 and email2,
             * it should be a no-op for most users.
             */
            if (oldVersion <= 118) {
                db.execSQL("update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.SYNC_INTERVAL
                        + "=0 where " + MailboxColumns.TYPE + "=" + Mailbox.TYPE_DRAFTS);

                db.execSQL("delete from " + Message.TABLE_NAME + " where "
                        + "(" + SyncColumns.SERVER_ID + " not null and "
                        + SyncColumns.SERVER_ID + "!='') and "
                        + MessageColumns.MAILBOX_KEY + " in (select "
                        + MailboxColumns._ID + " from " + Mailbox.TABLE_NAME + " where "
                        + MailboxColumns.TYPE + "=" + Mailbox.TYPE_DRAFTS + ")");
            }

            // We originally dropped and recreated the deleteDuplicateMessagesTrigger here at
            // version 120. We needed to update it again at version 123, so there's no reason
            // to do it twice.

            // Add the mainMailboxKey column, and get rid of any messages in the search_results
            // folder.
            if (oldVersion <= 120) {
                db.execSQL("alter table " + Message.TABLE_NAME
                        + " add " + MessageColumns.MAIN_MAILBOX_KEY + " integer");

                // Delete all TYPE_SEARCH mailboxes. These will be for stale queries anyway, and
                // the messages in them will not have the mainMailboxKey column correctly populated.
                // We have a trigger (See TRIGGER_MAILBOX_DELETE) that will delete any messages
                // in the deleted mailboxes.
                db.execSQL("delete from " + Mailbox.TABLE_NAME + " where "
                        + Mailbox.TYPE + "=" + Mailbox.TYPE_SEARCH);
            }

            if (oldVersion <= 121) {
                // The previous update omitted making these changes to the Message_Updates and
                // Message_Deletes tables. The app will actually crash in between these versions!
                db.execSQL("alter table " + Message.UPDATED_TABLE_NAME
                        + " add " + MessageColumns.MAIN_MAILBOX_KEY + " integer");
                db.execSQL("alter table " + Message.DELETED_TABLE_NAME
                        + " add " + MessageColumns.MAIN_MAILBOX_KEY + " integer");
            }

            if (oldVersion <= 122) {
                if (oldVersion >= 117) {
                    /**
                     * This trigger was originally created at version 117, but we needed to change
                     * it for version 122. So if our oldVersion is 117 or more, we know we have that
                     * trigger and must drop it before re creating it.
                     */
                    dropDeleteDuplicateMessagesTrigger(db);
                }
                createDeleteDuplicateMessagesTrigger(mContext, db);
            }

            if (oldVersion <= 123) {
                try {
                    db.execSQL("alter table " + Account.TABLE_NAME
                            + " add column " + AccountColumns.MAX_ATTACHMENT_SIZE +" integer" + ";");
                    final ContentValues cv = new ContentValues(1);
                    cv.put(AccountColumns.MAX_ATTACHMENT_SIZE, 0);
                    db.update(Account.TABLE_NAME, cv, null, null);
                } catch (final SQLException e) {
                    // Shouldn't be needed unless we're debugging and interrupt the process
                    LogUtils.w(TAG, "Exception upgrading EmailProvider.db from v123 to v124", e);
                }
            }

            if (oldVersion <= 124) {
                createCredentialsTable(db);
                // Add the credentialKey column, and set it to -1 for all pre-existing hostAuths.
                db.execSQL("alter table " + HostAuth.TABLE_NAME
                        + " add " + HostAuthColumns.CREDENTIAL_KEY + " integer");
                db.execSQL("update " + HostAuth.TABLE_NAME + " set "
                        + HostAuthColumns.CREDENTIAL_KEY + "=-1");
            }

            if (oldVersion <= 125) {
                upgradeFromVersion125ToVersion126(db);
            }

            if (oldVersion <= 126) {
                upgradeFromVersion126ToVersion127(mContext, db);
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            try {
                // Cleanup some nasty records
                db.execSQL("DELETE FROM " + Account.TABLE_NAME
                        + " WHERE " + AccountColumns.DISPLAY_NAME + " ISNULL;");
                db.execSQL("DELETE FROM " + HostAuth.TABLE_NAME
                        + " WHERE " + HostAuthColumns.PROTOCOL + " ISNULL;");
            } catch (SQLException e) {
                // Shouldn't be needed unless we're debugging and interrupt the process
                LogUtils.e(TAG, e, "Exception cleaning EmailProvider.db");
            }
        }
    }

    @VisibleForTesting
    @SuppressWarnings("deprecation")
    static void convertPolicyFlagsToPolicyTable(SQLiteDatabase db) {
        Cursor c = db.query(Account.TABLE_NAME,
                new String[] {BaseColumns._ID /*0*/, AccountColumns.SECURITY_FLAGS /*1*/},
                AccountColumns.SECURITY_FLAGS + ">0", null, null, null, null);
        try {
            ContentValues cv = new ContentValues();
            String[] args = new String[1];
            while (c.moveToNext()) {
                long securityFlags = c.getLong(1 /*SECURITY_FLAGS*/);
                Policy policy = LegacyPolicySet.flagsToPolicy(securityFlags);
                long policyId = db.insert(Policy.TABLE_NAME, null, policy.toContentValues());
                cv.put(AccountColumns.POLICY_KEY, policyId);
                cv.putNull(AccountColumns.SECURITY_FLAGS);
                args[0] = Long.toString(c.getLong(0 /*_ID*/));
                db.update(Account.TABLE_NAME, cv, BaseColumns._ID + "=?", args);
            }
        } finally {
            c.close();
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
                    + Mailbox.TABLE_NAME + "." + MailboxColumns._ID + " IN ( SELECT "
                    + Mailbox.TABLE_NAME + "." + MailboxColumns._ID + " FROM "
                    + Mailbox.TABLE_NAME + "," + Account.TABLE_NAME + ","
                    + HostAuth.TABLE_NAME + " WHERE "
                    + "("
                    + Mailbox.TABLE_NAME + "." + MailboxColumns.PARENT_KEY + " isnull OR "
                    + Mailbox.TABLE_NAME + "." + MailboxColumns.PARENT_KEY + "=0 "
                    + ") AND "
                    + Mailbox.TABLE_NAME + "." + MailboxColumns.ACCOUNT_KEY + "="
                    + Account.TABLE_NAME + "." + AccountColumns._ID + " AND "
                    + Account.TABLE_NAME + "." + AccountColumns.HOST_AUTH_KEY_RECV + "="
                    + HostAuth.TABLE_NAME + "." + HostAuthColumns._ID + " AND ( "
                    + HostAuth.TABLE_NAME + "." + HostAuthColumns.PROTOCOL + "='imap' OR "
                    + HostAuth.TABLE_NAME + "." + HostAuthColumns.PROTOCOL + "='pop3' ) )");
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 17 to 18 " + e);
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

    private static void createAccountManagerAccount(Context context, String login, String type,
            String password) {
        final AccountManager accountManager = AccountManager.get(context);

        if (isAccountPresent(accountManager, login, type)) {
            // The account already exists,just return
            return;
        }
        LogUtils.v("Email", "Creating account %s %s", login, type);
        final android.accounts.Account amAccount = new android.accounts.Account(login, type);
        accountManager.addAccountExplicitly(amAccount, password, null);
        ContentResolver.setIsSyncable(amAccount, EmailContent.AUTHORITY, 1);
        ContentResolver.setSyncAutomatically(amAccount, EmailContent.AUTHORITY, true);
        ContentResolver.setIsSyncable(amAccount, ContactsContract.AUTHORITY, 0);
        ContentResolver.setIsSyncable(amAccount, CalendarContract.AUTHORITY, 0);
    }

    private static boolean isAccountPresent(AccountManager accountManager, String name,
            String type) {
        final android.accounts.Account[] amAccounts = accountManager.getAccountsByType(type);
        if (amAccounts != null) {
            for (android.accounts.Account account : amAccounts) {
                if (TextUtils.equals(account.name, name) && TextUtils.equals(account.type, type)) {
                    return true;
                }
            }
        }
        return false;
    }

    @VisibleForTesting
    static void upgradeFromVersion21ToVersion22(SQLiteDatabase db, Context accountManagerContext) {
        migrateLegacyAccounts(db, accountManagerContext);
    }

    private static void migrateLegacyAccounts(SQLiteDatabase db, Context accountManagerContext) {
        final Map<String, String> legacyToNewTypeMap = new ImmutableMap.Builder<String, String>()
                .put(LEGACY_SCHEME_POP3,
                        accountManagerContext.getString(R.string.account_manager_type_pop3))
                .put(LEGACY_SCHEME_IMAP,
                        accountManagerContext.getString(R.string.account_manager_type_legacy_imap))
                .put(LEGACY_SCHEME_EAS,
                        accountManagerContext.getString(R.string.account_manager_type_exchange))
                .build();
        try {
            // Loop through accounts, looking for pop/imap accounts
            final Cursor accountCursor = db.query(Account.TABLE_NAME, V21_ACCOUNT_PROJECTION, null,
                    null, null, null, null);
            try {
                final String[] hostAuthArgs = new String[1];
                while (accountCursor.moveToNext()) {
                    hostAuthArgs[0] = accountCursor.getString(V21_ACCOUNT_RECV);
                    // Get the "receive" HostAuth for this account
                    final Cursor hostAuthCursor = db.query(HostAuth.TABLE_NAME,
                            V21_HOSTAUTH_PROJECTION, HostAuthColumns._ID + "=?", hostAuthArgs,
                            null, null, null);
                    try {
                        if (hostAuthCursor.moveToFirst()) {
                            final String protocol = hostAuthCursor.getString(V21_HOSTAUTH_PROTOCOL);
                            // If this is a pop3 or imap account, create the account manager account
                            if (LEGACY_SCHEME_IMAP.equals(protocol) ||
                                    LEGACY_SCHEME_POP3.equals(protocol)) {
                                // If this is a pop3 or imap account, create the account manager
                                // account
                                if (MailActivityEmail.DEBUG) {
                                    LogUtils.d(TAG, "Create AccountManager account for " + protocol
                                            + "account: "
                                            + accountCursor.getString(V21_ACCOUNT_EMAIL));
                                }
                                createAccountManagerAccount(accountManagerContext,
                                        accountCursor.getString(V21_ACCOUNT_EMAIL),
                                        legacyToNewTypeMap.get(protocol),
                                        hostAuthCursor.getString(V21_HOSTAUTH_PASSWORD));
                            } else if (LEGACY_SCHEME_EAS.equals(protocol)) {
                                // If an EAS account, make Email sync automatically (equivalent of
                                // checking the "Sync Email" box in settings

                                android.accounts.Account amAccount = new android.accounts.Account(
                                        accountCursor.getString(V21_ACCOUNT_EMAIL),
                                        legacyToNewTypeMap.get(protocol));
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
            LogUtils.w(TAG, "Exception while migrating accounts " + e);
        }
    }

    /** Upgrades the database from v22 to v23 */
    private static void upgradeFromVersion22ToVersion23(SQLiteDatabase db) {
        try {
            db.execSQL("alter table " + Mailbox.TABLE_NAME
                    + " add column " + Mailbox.LAST_TOUCHED_TIME + " integer default 0;");
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 22 to 23 " + e);
        }
    }

    /** Adds in a column for information about a client certificate to use. */
    private static void upgradeFromVersion23ToVersion24(SQLiteDatabase db) {
        try {
            db.execSQL("alter table " + HostAuth.TABLE_NAME
                    + " add column " + HostAuthColumns.CLIENT_CERT_ALIAS + " text;");
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 23 to 24 " + e);
        }
    }

    /** Upgrades the database from v24 to v25 by creating table for quick responses */
    private static void upgradeFromVersion24ToVersion25(SQLiteDatabase db) {
        try {
            createQuickResponseTable(db);
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 24 to 25 " + e);
        }
    }

    private static final String[] V25_ACCOUNT_PROJECTION =
        new String[] {AccountColumns._ID, AccountColumns.FLAGS, AccountColumns.HOST_AUTH_KEY_RECV};
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
                            V25_HOSTAUTH_PROJECTION, HostAuthColumns._ID + "=?", hostAuthArgs,
                            null, null, null);
                    try {
                        if (hostAuthCursor.moveToFirst()) {
                            String protocol = hostAuthCursor.getString(V25_HOSTAUTH_PROTOCOL);
                            // If this is an imap account, add the search flag
                            if (LEGACY_SCHEME_IMAP.equals(protocol)) {
                                String id = accountCursor.getString(V25_ACCOUNT_ID);
                                int flags = accountCursor.getInt(V25_ACCOUNT_FLAGS);
                                cv.put(AccountColumns.FLAGS, flags | Account.FLAGS_SUPPORTS_SEARCH);
                                db.update(Account.TABLE_NAME, cv, AccountColumns._ID + "=?",
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
            LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 25 to 26 " + e);
        }
    }

    /** Upgrades the database from v29 to v30 by updating all address fields in Message */
    private static final int[] ADDRESS_COLUMN_INDICES = {
            Message.CONTENT_BCC_LIST_COLUMN,
            Message.CONTENT_CC_LIST_COLUMN,
            Message.CONTENT_FROM_LIST_COLUMN,
            Message.CONTENT_REPLY_TO_COLUMN,
            Message.CONTENT_TO_LIST_COLUMN
    };
    private static final String[] ADDRESS_COLUMN_NAMES = {
            MessageColumns.BCC_LIST,
            MessageColumns.CC_LIST,
            MessageColumns.FROM_LIST,
            MessageColumns.REPLY_TO_LIST,
            MessageColumns.TO_LIST
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
                                Address.fromHeader(messageCursor.getString(ADDRESS_COLUMN_INDICES[i]));
                        cv.put(ADDRESS_COLUMN_NAMES[i], Address.toHeader(addrs));
                    }
                    whereArgs[0] = messageCursor.getString(Message.CONTENT_ID_COLUMN);
                    db.update(Message.TABLE_NAME, cv, WHERE_ID, whereArgs);
                }
            } finally {
                messageCursor.close();
            }
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 29 to 30 " + e);
        }
    }

    private static void upgradeFromVersion125ToVersion126(SQLiteDatabase db) {
        try {
            // Loop through all messages, updating address columns to their decoded form
            Cursor messageCursor = db.query(Message.TABLE_NAME, Message.CONTENT_PROJECTION, null,
                    null, null, null, null);
            ContentValues cv = new ContentValues();
            String[] whereArgs = new String[1];
            try {
                while (messageCursor.moveToNext()) {
                    for (int i = 0; i < ADDRESS_COLUMN_INDICES.length; i++) {
                        Address[] addrs =
                                Address.fromHeader(messageCursor.getString(ADDRESS_COLUMN_INDICES[i]));
                        cv.put(ADDRESS_COLUMN_NAMES[i], Address.toString(addrs));
                    }
                    whereArgs[0] = messageCursor.getString(Message.CONTENT_ID_COLUMN);
                    db.update(Message.TABLE_NAME, cv, WHERE_ID, whereArgs);
                }
            } finally {
                messageCursor.close();
            }
        } catch (SQLException e) {
            // Shouldn't be needed unless we're debugging and interrupt the process
            LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 125 to 126 " + e);
        }
    }

    /**
     * Update all accounts that are EAS v12.0 or greater with SmartForward and search flags
     */
    private static void upgradeFromVersion126ToVersion127(final Context context,
            final SQLiteDatabase db) {
        try {
            // These are the flags that we want to add to the Account table for the
            // appropriate rows.
            final long newFlags = Account.FLAGS_SUPPORTS_GLOBAL_SEARCH +
                    Account.FLAGS_SUPPORTS_SEARCH + Account.FLAGS_SUPPORTS_SMART_FORWARD;

            // For posterity; this is the command we're executing:
            // UPDATE Account SET flags=flags|[new flags] WHERE _id IN (SELECT t1._id FROM Account
            // t1 INNER JOIN HostAuth t2 ON t1.hostAuthKeyRecv=t2._id WHERE t2.protocol='gEas' AND
            // CAST(t1.protocolVersion AS REAL)>=12.0)
            db.execSQL(
                    "UPDATE " + Account.TABLE_NAME + " SET " + AccountColumns.FLAGS + "=" +
                            AccountColumns.FLAGS + "|" + Long.toString(newFlags) + " WHERE " +
                            AccountColumns._ID + " IN (SELECT t1." + AccountColumns._ID + " FROM " +
                            Account.TABLE_NAME + " t1 INNER JOIN " + HostAuth.TABLE_NAME +
                            " t2 ON t1." + AccountColumns.HOST_AUTH_KEY_RECV + "=t2._id WHERE t2." +
                            HostAuthColumns.PROTOCOL + "='" +
                            context.getString(R.string.protocol_eas) + "' AND CAST(t1." +
                            AccountColumns.PROTOCOL_VERSION + " AS REAL)>=12.0)");
        } catch (SQLException e) {
            LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 126 to 127 " + e);
        }
    }

    private static void upgradeToEmail2(SQLiteDatabase db) {
        // Perform cleanup operations from Email1 to Email2; Email1 will have added new
        // data that won't conform to what's expected in Email2

        // From 31->32 upgrade
        try {
            db.execSQL("update Mailbox set " + Mailbox.LAST_NOTIFIED_MESSAGE_KEY +
                    "=0 where " + Mailbox.LAST_NOTIFIED_MESSAGE_KEY + " IS NULL");
            db.execSQL("update Mailbox set " + Mailbox.LAST_NOTIFIED_MESSAGE_COUNT +
                    "=0 where " + Mailbox.LAST_NOTIFIED_MESSAGE_COUNT + " IS NULL");
        } catch (SQLException e) {
            LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 31 to 32/100 " + e);
        }

        // From 32->33 upgrade
        try {
            db.execSQL("update " + Attachment.TABLE_NAME + " set " + AttachmentColumns.UI_STATE +
                    "=" + UIProvider.AttachmentState.SAVED + " where " +
                    AttachmentColumns.CONTENT_URI + " is not null;");
        } catch (SQLException e) {
            LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 32 to 33/100 " + e);
        }

        // From 34->35 upgrade
        try {
            db.execSQL("update " + Mailbox.TABLE_NAME + " set " +
                    MailboxColumns.LAST_TOUCHED_TIME + " = " +
                    Mailbox.DRAFTS_DEFAULT_TOUCH_TIME + " WHERE " + MailboxColumns.TYPE +
                    " = " + Mailbox.TYPE_DRAFTS);
            db.execSQL("update " + Mailbox.TABLE_NAME + " set " +
                    MailboxColumns.LAST_TOUCHED_TIME + " = " +
                    Mailbox.SENT_DEFAULT_TOUCH_TIME + " WHERE " + MailboxColumns.TYPE +
                    " = " + Mailbox.TYPE_SENT);
        } catch (SQLException e) {
            LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 34 to 35/100 " + e);
        }

        // From 35/36->37
        try {
            db.execSQL("update " + Mailbox.TABLE_NAME + " set " +
                    MailboxColumns.FLAGS + "=" + MailboxColumns.FLAGS + "|" +
                    Mailbox.FLAG_SUPPORTS_SETTINGS + " where (" +
                    MailboxColumns.FLAGS + "&" + Mailbox.FLAG_HOLDS_MAIL + ")!=0 and " +
                    MailboxColumns.ACCOUNT_KEY + " IN (SELECT " + Account.TABLE_NAME +
                    "." + AccountColumns._ID + " from " + Account.TABLE_NAME + "," +
                    HostAuth.TABLE_NAME + " where " + Account.TABLE_NAME + "." +
                    AccountColumns.HOST_AUTH_KEY_RECV + "=" + HostAuth.TABLE_NAME + "." +
                    HostAuthColumns._ID + " and " + HostAuthColumns.PROTOCOL + "='" +
                    LEGACY_SCHEME_EAS + "')");
        } catch (SQLException e) {
            LogUtils.w(TAG, "Exception upgrading EmailProvider.db from 35/36 to 37/100 " + e);
        }
    }
}
