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

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.UriMatcher;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.provider.ContentCache.CacheToken;
import com.android.email.service.AttachmentDownloadService;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.AttachmentColumns;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.BodyColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.PolicyColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.provider.QuickResponse;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author mblank
 *
 */
public class EmailProvider extends ContentProvider {

    private static final String TAG = "EmailProvider";

    public static final String EMAIL_APP_MIME_TYPE = "application/email-ls";

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
    public static final Uri FOLDER_STATUS_URI =
            Uri.parse("content://" + EmailContent.AUTHORITY + "/status");
    public static final Uri FOLDER_REFRESH_URI =
            Uri.parse("content://" + EmailContent.AUTHORITY + "/refresh");

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
    private static final int MAILBOX_ID_ADD_TO_FIELD = MAILBOX_BASE + 3;
    private static final int MAILBOX_NOTIFICATION = MAILBOX_BASE + 4;
    private static final int MAILBOX_MOST_RECENT_MESSAGE = MAILBOX_BASE + 5;

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

    private static final int UI_BASE = 0x9000;
    private static final int UI_FOLDERS = UI_BASE;
    private static final int UI_SUBFOLDERS = UI_BASE + 1;
    private static final int UI_MESSAGES = UI_BASE + 2;
    private static final int UI_MESSAGE = UI_BASE + 3;
    private static final int UI_SENDMAIL = UI_BASE + 4;
    private static final int UI_UNDO = UI_BASE + 5;
    private static final int UI_SAVEDRAFT = UI_BASE + 6;
    private static final int UI_UPDATEDRAFT = UI_BASE + 7;
    private static final int UI_SENDDRAFT = UI_BASE + 8;
    private static final int UI_FOLDER_REFRESH = UI_BASE + 9;
    private static final int UI_FOLDER = UI_BASE + 10;
    private static final int UI_ACCOUNT = UI_BASE + 11;
    private static final int UI_ACCTS = UI_BASE + 12;
    private static final int UI_ATTACHMENTS = UI_BASE + 13;
    private static final int UI_ATTACHMENT = UI_BASE + 14;
    private static final int UI_SEARCH = UI_BASE + 15;
    private static final int UI_ACCOUNT_DATA = UI_BASE + 16;
    private static final int UI_FOLDER_LOAD_MORE = UI_BASE + 17;
    private static final int UI_CONVERSATION = UI_BASE + 18;
    private static final int UI_RECENT_FOLDERS = UI_BASE + 19;

    // MUST ALWAYS EQUAL THE LAST OF THE PREVIOUS BASE CONSTANTS
    private static final int LAST_EMAIL_PROVIDER_DB_BASE = UI_BASE;

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
        null,  // UI
        Body.TABLE_NAME,
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
        null, // Body
        null  // UI
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
        null,  // Body
        null   // UI
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
        matcher.addURI(EmailContent.AUTHORITY, "mailboxNotification/#", MAILBOX_NOTIFICATION);
        matcher.addURI(EmailContent.AUTHORITY, "mailboxMostRecentMessage/#",
                MAILBOX_MOST_RECENT_MESSAGE);

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

        matcher.addURI(EmailContent.AUTHORITY, "uifolders/#", UI_FOLDERS);
        matcher.addURI(EmailContent.AUTHORITY, "uisubfolders/#", UI_SUBFOLDERS);
        matcher.addURI(EmailContent.AUTHORITY, "uimessages/#", UI_MESSAGES);
        matcher.addURI(EmailContent.AUTHORITY, "uimessage/#", UI_MESSAGE);
        matcher.addURI(EmailContent.AUTHORITY, "uisendmail/#", UI_SENDMAIL);
        matcher.addURI(EmailContent.AUTHORITY, "uiundo", UI_UNDO);
        matcher.addURI(EmailContent.AUTHORITY, "uisavedraft/#", UI_SAVEDRAFT);
        matcher.addURI(EmailContent.AUTHORITY, "uiupdatedraft/#", UI_UPDATEDRAFT);
        matcher.addURI(EmailContent.AUTHORITY, "uisenddraft/#", UI_SENDDRAFT);
        matcher.addURI(EmailContent.AUTHORITY, "uirefresh/#", UI_FOLDER_REFRESH);
        matcher.addURI(EmailContent.AUTHORITY, "uifolder/#", UI_FOLDER);
        matcher.addURI(EmailContent.AUTHORITY, "uiaccount/#", UI_ACCOUNT);
        matcher.addURI(EmailContent.AUTHORITY, "uiaccts", UI_ACCTS);
        matcher.addURI(EmailContent.AUTHORITY, "uiattachments/#", UI_ATTACHMENTS);
        matcher.addURI(EmailContent.AUTHORITY, "uiattachment/#", UI_ATTACHMENT);
        matcher.addURI(EmailContent.AUTHORITY, "uisearch/#", UI_SEARCH);
        matcher.addURI(EmailContent.AUTHORITY, "uiaccountdata/#", UI_ACCOUNT_DATA);
        matcher.addURI(EmailContent.AUTHORITY, "uiloadmore/#", UI_FOLDER_LOAD_MORE);
        matcher.addURI(EmailContent.AUTHORITY, "uiconversation/#", UI_CONVERSATION);
        matcher.addURI(EmailContent.AUTHORITY, "uirecentfolders/#", UI_RECENT_FOLDERS);
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

    private SQLiteDatabase mDatabase;
    private SQLiteDatabase mBodyDatabase;

    public static Uri uiUri(String type, long id) {
        return Uri.parse(uiUriString(type, id));
    }

    public static String uiUriString(String type, long id) {
        return "content://" + EmailContent.AUTHORITY + "/" + type + ((id == -1) ? "" : ("/" + id));
    }

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

        DBHelper.DatabaseHelper helper = new DBHelper.DatabaseHelper(context, DATABASE_NAME);
        mDatabase = helper.getWritableDatabase();
        DBHelper.BodyDatabaseHelper bodyHelper =
                new DBHelper.BodyDatabaseHelper(context, BODY_DATABASE_NAME);
        mBodyDatabase = bodyHelper.getWritableDatabase();
        if (mBodyDatabase != null) {
            String bodyFileName = mBodyDatabase.getPath();
            mDatabase.execSQL("attach \"" + bodyFileName + "\" as BodyDatabase");
        }

        // Restore accounts if the database is corrupted...
        restoreIfNeeded(context, mDatabase);
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
        preCacheData();
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
        DBHelper.DatabaseHelper helper = new DBHelper.DatabaseHelper(context, DATABASE_NAME);
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
        try {
            if (c.moveToFirst()) {
                if (Email.DEBUG) {
                    Log.w(TAG, "restoreIfNeeded: Account exists.");
                }
                return; // At least one account exists.
            }
        } finally {
            c.close();
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
                case UI_MESSAGE:
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
                    } else if (match == ATTACHMENT) {
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
        Email.setServicesEnabledAsync(getContext());
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
                case MAILBOX_NOTIFICATION:
                    c = notificationQuery(uri);
                    return c;
                case MAILBOX_MOST_RECENT_MESSAGE:
                    c = mostRecentMessageQuery(uri);
                    return c;
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

        // Lock both databases; for the "from" database, we don't want anyone changing it from
        // under us; for the "to" database, we want to make the operation atomic
        int copyCount = 0;
        fromDatabase.beginTransaction();
        try {
            toDatabase.beginTransaction();
            try {
                // Delete anything hanging around here
                toDatabase.delete(Account.TABLE_NAME, null, null);
                toDatabase.delete(HostAuth.TABLE_NAME, null, null);

                // Get our account cursor
                Cursor c = fromDatabase.query(Account.TABLE_NAME, Account.CONTENT_PROJECTION,
                        null, null, null, null, null);
                if (c == null) return 0;
                Log.d(TAG, "fromDatabase accounts: " + c.getCount());
                try {
                    // Loop through accounts, copying them and associated host auth's
                    while (c.moveToNext()) {
                        Account account = new Account();
                        account.restore(c);

                        // Clear security sync key and sync key, as these were specific to the
                        // state of the account, and we've reset that...
                        // Clear policy key so that we can re-establish policies from the server
                        // TODO This is pretty EAS specific, but there's a lot of that around
                        account.mSecuritySyncKey = null;
                        account.mSyncKey = null;
                        account.mPolicyKey = 0;

                        // Copy host auth's and update foreign keys
                        HostAuth hostAuth = restoreHostAuth(fromDatabase,
                                account.mHostAuthKeyRecv);

                        // The account might have gone away, though very unlikely
                        if (hostAuth == null) continue;
                        account.mHostAuthKeyRecv = toDatabase.insert(HostAuth.TABLE_NAME, null,
                                hostAuth.toContentValues());

                        // EAS accounts have no send HostAuth
                        if (account.mHostAuthKeySend > 0) {
                            hostAuth = restoreHostAuth(fromDatabase, account.mHostAuthKeySend);
                            // Belt and suspenders; I can't imagine that this is possible,
                            // since we checked the validity of the account above, and the
                            // database is now locked
                            if (hostAuth == null) continue;
                            account.mHostAuthKeySend = toDatabase.insert(
                                    HostAuth.TABLE_NAME, null, hostAuth.toContentValues());
                        }

                        // Now, create the account in the "to" database
                        toDatabase.insert(Account.TABLE_NAME, null, account.toContentValues());
                        copyCount++;
                    }
                } finally {
                    c.close();
                }

                // Say it's ok to commit
                toDatabase.setTransactionSuccessful();
            } finally {
                // STOPSHIP: Remove logging here and in at endTransaction() below
                Log.d(TAG, "ending toDatabase transaction; copyCount = " + copyCount);
                toDatabase.endTransaction();
            }
        } catch (SQLiteException ex) {
            Log.w(TAG, "Exception while copying account tables", ex);
            copyCount = -1;
        } finally {
            Log.d(TAG, "ending fromDatabase transaction; copyCount = " + copyCount);
            fromDatabase.endTransaction();
        }
        return copyCount;
    }

    private static SQLiteDatabase getBackupDatabase(Context context) {
        DBHelper.DatabaseHelper helper = new DBHelper.DatabaseHelper(context, BACKUP_DATABASE_NAME);
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
                        long attId = Integer.parseInt(id);
                        if (values.containsKey(Attachment.FLAGS)) {
                            int flags = values.getAsInteger(Attachment.FLAGS);
                            mAttachmentService.attachmentChanged(context, attId, flags);
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
     * upon the given values.  For message-related notifications, we also notify the widget
     * provider.
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
        Uri uri = baseUri;

        // Append the operation, if specified
        if (op != null) {
            uri = baseUri.buildUpon().appendEncodedPath(op).build();
        }

        long longId = 0L;
        try {
            longId = Long.valueOf(id);
        } catch (NumberFormatException ignore) {}

        final ContentResolver resolver = getContext().getContentResolver();
        if (longId > 0) {
            resolver.notifyChange(ContentUris.withAppendedId(uri, longId), null);
        } else {
            resolver.notifyChange(uri, null);
        }

        // If a message has changed, notify any widgets
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

    // SELECT DISTINCT Boxes._id, Boxes.unreadCount count(Message._id) from Message,
    //   (SELECT _id, unreadCount, messageCount, lastNotifiedMessageCount, lastNotifiedMessageKey
    //   FROM Mailbox WHERE accountKey=6 AND ((type = 0) OR (syncInterval!=0 AND syncInterval!=-1)))
    //      AS Boxes
    // WHERE Boxes.messageCount!=Boxes.lastNotifiedMessageCount
    //   OR (Boxes._id=Message.mailboxKey AND Message._id>Boxes.lastNotifiedMessageKey)
    //   AND flagRead = 0 AND timeStamp != 0
    // TODO: This query can be simplified a bit
    private static final String NOTIFICATION_QUERY =
        "SELECT DISTINCT Boxes." + MailboxColumns.ID + ", Boxes." + MailboxColumns.UNREAD_COUNT +
            ", count(" + Message.TABLE_NAME + "." + MessageColumns.ID + ")" +
        " FROM " +
            Message.TABLE_NAME + "," +
            "(SELECT " + MailboxColumns.ID + "," + MailboxColumns.UNREAD_COUNT + "," +
                MailboxColumns.MESSAGE_COUNT + "," + MailboxColumns.LAST_NOTIFIED_MESSAGE_COUNT +
                "," + MailboxColumns.LAST_NOTIFIED_MESSAGE_KEY + " FROM " + Mailbox.TABLE_NAME +
                " WHERE " + MailboxColumns.ACCOUNT_KEY + "=?" +
                " AND (" + MailboxColumns.TYPE + "=" + Mailbox.TYPE_INBOX + " OR ("
                + MailboxColumns.SYNC_INTERVAL + "!=0 AND " +
                MailboxColumns.SYNC_INTERVAL + "!=-1))) AS Boxes " +
        "WHERE Boxes." + MailboxColumns.ID + '=' + Message.TABLE_NAME + "." +
                MessageColumns.MAILBOX_KEY + " AND " + Message.TABLE_NAME + "." +
                MessageColumns.ID + ">Boxes." + MailboxColumns.LAST_NOTIFIED_MESSAGE_KEY +
                " AND " + MessageColumns.FLAG_READ + "=0 AND " + MessageColumns.TIMESTAMP + "!=0";

    public Cursor notificationQuery(Uri uri) {
        SQLiteDatabase db = getDatabase(getContext());
        String accountId = uri.getLastPathSegment();
        return db.rawQuery(NOTIFICATION_QUERY, new String[] {accountId});
   }

    public Cursor mostRecentMessageQuery(Uri uri) {
        SQLiteDatabase db = getDatabase(getContext());
        String mailboxId = uri.getLastPathSegment();
        return db.rawQuery("select max(_id) from Message where mailboxKey=?",
                new String[] {mailboxId});
   }
}
