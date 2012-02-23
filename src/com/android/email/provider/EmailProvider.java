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
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.Log;

import com.android.common.content.ProjectionMap;
import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.provider.ContentCache.CacheToken;
import com.android.email.service.AttachmentDownloadService;
import com.android.email.service.EmailServiceUtils;
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
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.provider.QuickResponse;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.ConversationPriority;
import com.android.mail.providers.UIProvider.ConversationSendingState;
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
    private static final int UI_FOLDER = UI_BASE + 11;

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

    // For undo handling
    private int mLastSequence = -1;
    private ArrayList<ContentProviderOperation> mLastSequenceOps =
            new ArrayList<ContentProviderOperation>();

    // Query parameter indicating the command came from UIProvider
    private static final String IS_UIPROVIDER = "is_uiprovider";

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

        matcher.addURI(EmailContent.AUTHORITY, "uifolders/*", UI_FOLDERS);
        matcher.addURI(EmailContent.AUTHORITY, "uisubfolders/#", UI_SUBFOLDERS);
        matcher.addURI(EmailContent.AUTHORITY, "uimessages/#", UI_MESSAGES);
        matcher.addURI(EmailContent.AUTHORITY, "uimessage/#", UI_MESSAGE);
        matcher.addURI(EmailContent.AUTHORITY, "uisendmail/*", UI_SENDMAIL);
        matcher.addURI(EmailContent.AUTHORITY, "uiundo/*", UI_UNDO);
        matcher.addURI(EmailContent.AUTHORITY, "uisavedraft/*", UI_SAVEDRAFT);
        matcher.addURI(EmailContent.AUTHORITY, "uiupdatedraft/#", UI_UPDATEDRAFT);
        matcher.addURI(EmailContent.AUTHORITY, "uisenddraft/#", UI_SENDDRAFT);
        matcher.addURI(EmailContent.AUTHORITY, "uirefresh/#", UI_FOLDER_REFRESH);
        matcher.addURI(EmailContent.AUTHORITY, "uifolder/#", UI_FOLDER);
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
            if (match == MESSAGE_ID || match == SYNCED_MESSAGE_ID) {
                if (!uri.getBooleanQueryParameter(IS_UIPROVIDER, false)) {
                    notifyUIProvider("Delete");
                }
            }
            switch (match) {
                case UI_MESSAGE:
                    return uiDeleteMessage(uri);
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

    private static final Uri UIPROVIDER_MESSAGE_NOTIFIER =
            Uri.parse("content://" + UIProvider.AUTHORITY + "/uimessages");
    private static final Uri UIPROVIDER_MAILBOX_NOTIFIER =
            Uri.parse("content://" + UIProvider.AUTHORITY + "/uifolder");

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
                case UI_SAVEDRAFT:
                    return uiSaveDraft(uri, values);
                case UI_SENDMAIL:
                    return uiSendMail(uri, values);
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
                        case MESSAGE:
                            if (!uri.getBooleanQueryParameter(IS_UIPROVIDER, false)) {
                                notifyUIProvider("Insert");
                            }
                            break;
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
                // First, dispatch queries from UnfiedEmail
                case UI_UNDO:
                    return uiUndo(uri, projection);
                case UI_SUBFOLDERS:
                case UI_FOLDERS:
                case UI_MESSAGES:
                case UI_MESSAGE:
                case UI_FOLDER:
                    // For now, we don't allow selection criteria within these queries
                    if (selection != null || selectionArgs != null) {
                        throw new IllegalArgumentException("UI queries can't have selection/args");
                    }
                    c = uiQuery(match, uri, projection);
                    return c;
                case UI_FOLDER_REFRESH:
                    return uiFolderRefresh(uri, projection);
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
            if (match == MESSAGE_ID || match == SYNCED_MESSAGE_ID) {
                if (!uri.getBooleanQueryParameter(IS_UIPROVIDER, false)) {
                    notifyUIProvider("Update");
                }
            }
outer:
            switch (match) {
                case UI_UPDATEDRAFT:
                    return uiUpdateDraft(uri, values);
                case UI_SENDDRAFT:
                    return uiSendDraft(uri, values);
                case UI_MESSAGE:
                    return uiUpdateMessage(uri, values);
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
                    } else if (match == MAILBOX_ID && values.containsKey(Mailbox.UI_SYNC_STATUS)) {
                        Uri notifyUri =
                                UIPROVIDER_MAILBOX_NOTIFIER.buildUpon().appendPath(id).build();
                        resolver.notifyChange(notifyUri, null);
                        // TODO: Remove logging
                        Log.d(TAG, "Notifying mailbox " + id + " status: " +
                                values.getAsInteger(Mailbox.UI_SYNC_STATUS));
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

    /**
     * Support for UnifiedEmail below
     */

    private static final String NOT_A_DRAFT_STRING =
        Integer.toString(UIProvider.DraftType.NOT_A_DRAFT);

    /**
     * Mapping of UIProvider columns to EmailProvider columns for the message list (called the
     * conversation list in UnifiedEmail)
     */
    private static final ProjectionMap sMessageListMap = ProjectionMap.builder()
        .add(BaseColumns._ID, MessageColumns.ID)
        .add(UIProvider.ConversationColumns.URI, uriWithId("uimessage"))
        .add(UIProvider.ConversationColumns.MESSAGE_LIST_URI, uriWithId("uimessage"))
        .add(UIProvider.ConversationColumns.SUBJECT, MessageColumns.SUBJECT)
        .add(UIProvider.ConversationColumns.SNIPPET, MessageColumns.SNIPPET)
        .add(UIProvider.ConversationColumns.SENDER_INFO, MessageColumns.FROM_LIST)
        .add(UIProvider.ConversationColumns.DATE_RECEIVED_MS, MessageColumns.TIMESTAMP)
        .add(UIProvider.ConversationColumns.HAS_ATTACHMENTS, MessageColumns.FLAG_ATTACHMENT)
        .add(UIProvider.ConversationColumns.NUM_MESSAGES, "1")
        .add(UIProvider.ConversationColumns.NUM_DRAFTS, "0")
        .add(UIProvider.ConversationColumns.SENDING_STATE,
                Integer.toString(ConversationSendingState.OTHER))
        .add(UIProvider.ConversationColumns.PRIORITY, Integer.toString(ConversationPriority.LOW))
        .add(UIProvider.ConversationColumns.READ, MessageColumns.FLAG_READ)
        .add(UIProvider.ConversationColumns.STARRED, MessageColumns.FLAG_FAVORITE)
        .add(UIProvider.ConversationColumns.FOLDER_LIST, MessageColumns.MAILBOX_KEY)
        .build();

    /**
     * Mapping of UIProvider columns to EmailProvider columns for a detailed message view in
     * UnifiedEmail
     */
    private static final ProjectionMap sMessageViewMap = ProjectionMap.builder()
        .add(BaseColumns._ID, Message.TABLE_NAME + "." + EmailContent.MessageColumns.ID)
        .add(UIProvider.MessageColumns.SERVER_ID, SyncColumns.SERVER_ID)
        .add(UIProvider.MessageColumns.URI, uriWithFQId("uimessage", Message.TABLE_NAME))
        .add(UIProvider.MessageColumns.CONVERSATION_ID,
                uriWithFQId("uimessage", Message.TABLE_NAME))
        .add(UIProvider.MessageColumns.SUBJECT, EmailContent.MessageColumns.SUBJECT)
        .add(UIProvider.MessageColumns.SNIPPET, EmailContent.MessageColumns.SNIPPET)
        .add(UIProvider.MessageColumns.FROM, EmailContent.MessageColumns.FROM_LIST)
        .add(UIProvider.MessageColumns.TO, EmailContent.MessageColumns.TO_LIST)
        .add(UIProvider.MessageColumns.CC, EmailContent.MessageColumns.CC_LIST)
        .add(UIProvider.MessageColumns.BCC, EmailContent.MessageColumns.BCC_LIST)
        .add(UIProvider.MessageColumns.REPLY_TO, EmailContent.MessageColumns.REPLY_TO_LIST)
        .add(UIProvider.MessageColumns.DATE_RECEIVED_MS, EmailContent.MessageColumns.TIMESTAMP)
        .add(UIProvider.MessageColumns.BODY_HTML, Body.HTML_CONTENT)
        .add(UIProvider.MessageColumns.BODY_TEXT, Body.TEXT_CONTENT)
        .add(UIProvider.MessageColumns.EMBEDS_EXTERNAL_RESOURCES, "0")
        .add(UIProvider.MessageColumns.REF_MESSAGE_ID, "0")
        .add(UIProvider.MessageColumns.DRAFT_TYPE, NOT_A_DRAFT_STRING)
        .add(UIProvider.MessageColumns.APPEND_REF_MESSAGE_CONTENT, "0")
        .add(UIProvider.MessageColumns.HAS_ATTACHMENTS, EmailContent.MessageColumns.FLAG_ATTACHMENT)
        .add(UIProvider.MessageColumns.ATTACHMENT_LIST_URI,
                uriWithFQId("uiattachments", Message.TABLE_NAME))
        .add(UIProvider.MessageColumns.MESSAGE_FLAGS, "0")
        .add(UIProvider.MessageColumns.SAVE_MESSAGE_URI,
                uriWithFQId("uiupdatedraft", Message.TABLE_NAME))
        .add(UIProvider.MessageColumns.SEND_MESSAGE_URI,
                uriWithFQId("uisenddraft", Message.TABLE_NAME))
        .build();

    /**
     * Mapping of UIProvider columns to EmailProvider columns for the folder list in UnifiedEmail
     */
    private static String getFolderCapabilities() {
        return "CASE WHEN (" + MailboxColumns.FLAGS + "&" + Mailbox.FLAG_ACCEPTS_MOVED_MAIL +
                ") !=0 THEN " + UIProvider.FolderCapabilities.CAN_ACCEPT_MOVED_MESSAGES +
                " ELSE 0 END";
    }

    private static final ProjectionMap sFolderListMap = ProjectionMap.builder()
        .add(BaseColumns._ID, MessageColumns.ID)
        .add(UIProvider.FolderColumns.URI, uriWithId("uifolder"))
        .add(UIProvider.FolderColumns.NAME, "displayName")
        .add(UIProvider.FolderColumns.HAS_CHILDREN,
                MailboxColumns.FLAGS + "&" + Mailbox.FLAG_HAS_CHILDREN)
        .add(UIProvider.FolderColumns.CAPABILITIES, getFolderCapabilities())
        .add(UIProvider.FolderColumns.SYNC_WINDOW, "3")
        .add(UIProvider.FolderColumns.CONVERSATION_LIST_URI, uriWithId("uimessages"))
        .add(UIProvider.FolderColumns.CHILD_FOLDERS_LIST_URI, uriWithId("uisubfolders"))
        .add(UIProvider.FolderColumns.UNREAD_COUNT, MailboxColumns.UNREAD_COUNT)
        .add(UIProvider.FolderColumns.TOTAL_COUNT, MailboxColumns.MESSAGE_COUNT)
        .add(UIProvider.FolderColumns.REFRESH_URI, uriWithId("uirefresh"))
        .add(UIProvider.FolderColumns.SYNC_STATUS, MailboxColumns.UI_SYNC_STATUS)
        .add(UIProvider.FolderColumns.LAST_SYNC_RESULT, MailboxColumns.UI_LAST_SYNC_RESULT)
        .build();

    /**
     * The "ORDER BY" clause for top level folders
     */
    private static final String MAILBOX_ORDER_BY = "CASE " + MailboxColumns.TYPE
        + " WHEN " + Mailbox.TYPE_INBOX   + " THEN 0"
        + " WHEN " + Mailbox.TYPE_DRAFTS  + " THEN 1"
        + " WHEN " + Mailbox.TYPE_OUTBOX  + " THEN 2"
        + " WHEN " + Mailbox.TYPE_SENT    + " THEN 3"
        + " WHEN " + Mailbox.TYPE_TRASH   + " THEN 4"
        + " WHEN " + Mailbox.TYPE_JUNK    + " THEN 5"
        // Other mailboxes (i.e. of Mailbox.TYPE_MAIL) are shown in alphabetical order.
        + " ELSE 10 END"
        + " ," + MailboxColumns.DISPLAY_NAME + " COLLATE LOCALIZED ASC";

    /**
     * Generate the SELECT clause using a specified mapping and the original UI projection
     * @param map the ProjectionMap to use for this projection
     * @param projection the projection as sent by UnifiedEmail
     * @return a StringBuilder containing the SELECT expression for a SQLite query
     */
    private StringBuilder genSelect(ProjectionMap map, String[] projection) {
        StringBuilder sb = new StringBuilder("SELECT ");
        boolean first = true;
        for (String column: projection) {
            if (first) {
                first = false;
            } else {
                sb.append(',');
            }
            String val = map.get(column);
            // If we don't have the column, be permissive, returning "0 AS <column>", and warn
            if (val == null) {
                Log.w(TAG, "UIProvider column not found, returning 0: " + column);
                val = "0 AS " + column;
            }
            sb.append(val);
        }
        return sb;
    }

    /**
     * Convenience method to create a Uri string given the "type" of query; we append the type
     * of the query and the id column name (_id)
     *
     * @param type the "type" of the query, as defined by our UriMatcher definitions
     * @return a Uri string
     */
    private static String uriWithId(String type) {
        return "'content://" + EmailContent.AUTHORITY + "/" + type + "/' || _id";
    }

    /**
     * Convenience method to create a Uri string given the "type" of query and the table name to
     * which it applies; we append the type of the query and the fully qualified (FQ) id column
     * (i.e. including the table name); we need this for join queries where _id would otherwise
     * be ambiguous
     *
     * @param type the "type" of the query, as defined by our UriMatcher definitions
     * @param tableName the name of the table whose _id is referred to
     * @return a Uri string
     */
    private static String uriWithFQId(String type, String tableName) {
        return "'content://" + EmailContent.AUTHORITY + "/" + type + "/' || " + tableName + "._id";
    }

    /**
     * Generate the "view message" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private String genQueryViewMessage(String[] uiProjection) {
        StringBuilder sb = genSelect(sMessageViewMap, uiProjection);
        sb.append(" FROM " + Message.TABLE_NAME + "," + Body.TABLE_NAME + " WHERE " +
                Body.MESSAGE_KEY + "=" + Message.TABLE_NAME + "." + Message.RECORD_ID + " AND " +
                Message.TABLE_NAME + "." + Message.RECORD_ID + "=?");
        return sb.toString();
    }

    /**
     * Generate the "message list" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private String genQueryMailboxMessages(String[] uiProjection) {
        StringBuilder sb = genSelect(sMessageListMap, uiProjection);
        // Make constant
        sb.append(" FROM " + Message.TABLE_NAME + " WHERE " + Message.MAILBOX_KEY + "=? ORDER BY " +
                MessageColumns.TIMESTAMP + " DESC");
        return sb.toString();
    }

    /**
     * Generate the "top level folder list" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private String genQueryAccountMailboxes(String[] uiProjection) {
        StringBuilder sb = genSelect(sFolderListMap, uiProjection);
        // Make constant
        sb.append(" FROM " + Mailbox.TABLE_NAME + " WHERE " + MailboxColumns.ACCOUNT_KEY +
                "=? AND " + MailboxColumns.TYPE + " < " + Mailbox.TYPE_NOT_EMAIL +
                " AND " + MailboxColumns.PARENT_KEY + " < 0 ORDER BY ");
        sb.append(MAILBOX_ORDER_BY);
        return sb.toString();
    }

    /**
     * Generate a "single mailbox" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private String genQueryMailbox(String[] uiProjection) {
        StringBuilder sb = genSelect(sFolderListMap, uiProjection);
        sb.append(" FROM " + Mailbox.TABLE_NAME + " WHERE " + MailboxColumns.ID + "=?");
        return sb.toString();
    }

    /**
     * Generate the "subfolder list" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private String genQuerySubfolders(String[] uiProjection) {
        StringBuilder sb = genSelect(sFolderListMap, uiProjection);
        // Make constant
        sb.append(" FROM " + Mailbox.TABLE_NAME + " WHERE " + MailboxColumns.PARENT_KEY +
                " =? ORDER BY ");
        sb.append(MAILBOX_ORDER_BY);
        return sb.toString();
    }

    /**
     * Given the email address of an account, return its account id (the _id row in the Account
     * table), or NO_ACCOUNT (-1) if not found
     *
     * @param email the email address of the account
     * @return the account id for this account, or NO_ACCOUNT if not found
     */
    private long findAccountIdByName(String email) {
        Map<String, Cursor> accountCache = mCacheAccount.getSnapshot();
        Collection<Cursor> accounts = accountCache.values();
        for (Cursor accountCursor: accounts) {
            if (accountCursor.getString(Account.CONTENT_EMAIL_ADDRESS_COLUMN).equals(email)) {
                return accountCursor.getLong(Account.CONTENT_ID_COLUMN);
            }
        }
        return Account.NO_ACCOUNT;
    }

    /**
     * Handle UnifiedEmail queries here (dispatched from query())
     *
     * @param match the UriMatcher match for the original uri passed in from UnifiedEmail
     * @param uri the original uri passed in from UnifiedEmail
     * @param uiProjection the projection passed in from UnifiedEmail
     * @return the result Cursor
     */
    private Cursor uiQuery(int match, Uri uri, String[] uiProjection) {
        Context context = getContext();
        ContentResolver resolver = context.getContentResolver();
        SQLiteDatabase db = getDatabase(context);
        // Should we ever return null, or throw an exception??
        Cursor c = null;
        String id = uri.getPathSegments().get(1);
        switch(match) {
            case UI_FOLDERS:
                // We are passed the email address (unique account identifier) in the uri; we
                // need to turn this into the _id of the Account row in the EmailProvider db
                String accountName = id;
                long acctId = findAccountIdByName(accountName);
                if (acctId == Account.NO_ACCOUNT) return null;
                c = db.rawQuery(genQueryAccountMailboxes(uiProjection),
                        new String[] {Long.toString(acctId)});
                break;
            case UI_SUBFOLDERS:
                c = db.rawQuery(genQuerySubfolders(uiProjection), new String[] {id});
                break;
            case UI_MESSAGES:
                c = db.rawQuery(genQueryMailboxMessages(uiProjection), new String[] {id});
                break;
            case UI_MESSAGE:
                c = db.rawQuery(genQueryViewMessage(uiProjection), new String[] {id});
                break;
            case UI_FOLDER:
                c = db.rawQuery(genQueryMailbox(uiProjection), new String[] {id});
                // We'll notify on changes to the particular mailbox via the EmailProvider Uri
                Uri notifyUri = UIPROVIDER_MAILBOX_NOTIFIER.buildUpon().appendPath(id).build();
                c.setNotificationUri(resolver, notifyUri);
                return c;
        }
        if (c != null) {
            // Notify UIProvider on changes
            // Make this more specific to actual query later on...
            c.setNotificationUri(resolver, UIPROVIDER_MESSAGE_NOTIFIER);
        }
        return c;
    }

    /**
     * Convert a UIProvider attachment to an EmailProvider attachment (for sending); we only need
     * a few of the fields
     * @param uiAtt the UIProvider attachment to convert
     * @return the EmailProvider attachment
     */
    private Attachment convertUiAttachmentToAttachment(
            com.android.mail.providers.Attachment uiAtt) {
        Attachment att = new Attachment();
        att.mContentUri = uiAtt.contentUri;
        att.mFileName = uiAtt.name;
        att.mMimeType = uiAtt.mimeType;
        att.mSize = uiAtt.size;
        return att;
    }

    /**
     * Create a mailbox given the account and mailboxType.
     */
    private Mailbox createMailbox(long accountId, int mailboxType) {
        Context context = getContext();
        int resId = -1;
        switch (mailboxType) {
            case Mailbox.TYPE_INBOX:
                resId = R.string.mailbox_name_server_inbox;
                break;
            case Mailbox.TYPE_OUTBOX:
                resId = R.string.mailbox_name_server_outbox;
                break;
            case Mailbox.TYPE_DRAFTS:
                resId = R.string.mailbox_name_server_drafts;
                break;
            case Mailbox.TYPE_TRASH:
                resId = R.string.mailbox_name_server_trash;
                break;
            case Mailbox.TYPE_SENT:
                resId = R.string.mailbox_name_server_sent;
                break;
            case Mailbox.TYPE_JUNK:
                resId = R.string.mailbox_name_server_junk;
                break;
            default:
                throw new IllegalArgumentException("Illegal mailbox type");
        }
        Log.d(TAG, "Creating mailbox of type " + mailboxType + " for account " + accountId);
        Mailbox box = Mailbox.newSystemMailbox(accountId, mailboxType, context.getString(resId));
        box.save(context);
        return box;
    }

    /**
     * Given an account name and a mailbox type, return that mailbox, creating it if necessary
     * @param accountName the account name to use
     * @param mailboxType the type of mailbox we're trying to find
     * @return the mailbox of the given type for the account in the uri, or null if not found
     */
    private Mailbox getMailboxByUriAndType(String accountName, int mailboxType) {
        long accountId = findAccountIdByName(accountName);
        if (accountId == Account.NO_ACCOUNT) return null;
        Mailbox mailbox = Mailbox.restoreMailboxOfType(getContext(), accountId, mailboxType);
        if (mailbox == null) {
            mailbox = createMailbox(accountId, mailboxType);
        }
        return mailbox;
    }

    private Message getMessageFromPathSegments(List<String> pathSegments) {
        Message msg = null;
        if (pathSegments.size() > 2) {
            msg = Message.restoreMessageWithId(getContext(), Long.parseLong(pathSegments.get(2)));
        }
        if (msg == null) {
            msg = new Message();
        }
        return msg;
    }
    /**
     * Given a mailbox and the content values for a message, create/save the message in the mailbox
     * @param mailbox the mailbox to use
     * @param values the content values that represent message fields
     * @return the uri of the newly created message
     */
    private Uri uiSaveMessage(Message msg, Mailbox mailbox, ContentValues values) {
        Context context = getContext();
        // Fill in the message
        msg.mTo = values.getAsString(UIProvider.MessageColumns.TO);
        msg.mCc = values.getAsString(UIProvider.MessageColumns.CC);
        msg.mBcc = values.getAsString(UIProvider.MessageColumns.BCC);
        msg.mSubject = values.getAsString(UIProvider.MessageColumns.SUBJECT);
        msg.mText = values.getAsString(UIProvider.MessageColumns.BODY_TEXT);
        msg.mHtml = values.getAsString(UIProvider.MessageColumns.BODY_HTML);
        msg.mMailboxKey = mailbox.mId;
        msg.mAccountKey = mailbox.mAccountKey;
        msg.mDisplayName = msg.mTo;
        msg.mFlagLoaded = Message.FLAG_LOADED_COMPLETE;
        // Get attachments from the ContentValues
        ArrayList<com.android.mail.providers.Attachment> uiAtts =
                com.android.mail.providers.Attachment.getAttachmentsFromJoinedAttachmentInfo(
                        values.getAsString(UIProvider.MessageColumns.JOINED_ATTACHMENT_INFOS));
        ArrayList<Attachment> atts = new ArrayList<Attachment>();
        for (com.android.mail.providers.Attachment uiAtt: uiAtts) {
            // Convert to our attachments and add to the list; everything else should "just work"
            atts.add(convertUiAttachmentToAttachment(uiAtt));
        }
        if (!atts.isEmpty()) {
            msg.mAttachments = atts;
        }
        // Save it or update it...
        if (!msg.isSaved()) {
            msg.save(context);
        } else {
            // This is tricky due to how messages/attachments are saved; rather than putz with
            // what's changed, we'll delete/re-add them
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            // Delete all existing attachments
            ops.add(ContentProviderOperation.newDelete(
                    ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, msg.mId))
                    .build());
            // Delete the body
            ops.add(ContentProviderOperation.newDelete(Body.CONTENT_URI)
                    .withSelection(Body.MESSAGE_KEY + "=?", new String[] {Long.toString(msg.mId)})
                    .build());
            // Add the ops for the message, atts, and body
            msg.addSaveOps(ops);
            // Do it!
            try {
                applyBatch(ops);
            } catch (OperationApplicationException e) {
            }
        }
        return Uri.parse("content://" + EmailContent.AUTHORITY + "/uimessage/" + msg.mId);
    }

    /**
     * Create and send the message via the account indicated in the uri
     * @param uri the incoming uri
     * @param values the content values that represent message fields
     * @return the uri of the created message
     */
    private Uri uiSendMail(Uri uri, ContentValues values) {
        List<String> pathSegments = uri.getPathSegments();
        Mailbox mailbox = getMailboxByUriAndType(pathSegments.get(1), Mailbox.TYPE_OUTBOX);
        if (mailbox == null) return null;
        Message msg = getMessageFromPathSegments(pathSegments);
        try {
            return uiSaveMessage(msg, mailbox, values);
        } finally {
            // Kick observers
            getContext().getContentResolver().notifyChange(Mailbox.CONTENT_URI, null);
        }
    }

    /**
     * Create a message and save it to the drafts folder of the account indicated in the uri
     * @param uri the incoming uri
     * @param values the content values that represent message fields
     * @return the uri of the created message
     */
    private Uri uiSaveDraft(Uri uri, ContentValues values) {
        List<String> pathSegments = uri.getPathSegments();
        Mailbox mailbox = getMailboxByUriAndType(pathSegments.get(1), Mailbox.TYPE_DRAFTS);
        if (mailbox == null) return null;
        Message msg = getMessageFromPathSegments(pathSegments);
        return uiSaveMessage(msg, mailbox, values);
    }

    private int uiUpdateDraft(Uri uri, ContentValues values) {
        Context context = getContext();
        Message msg = Message.restoreMessageWithId(context,
                Long.parseLong(uri.getPathSegments().get(1)));
        if (msg == null) return 0;
        Mailbox mailbox = Mailbox.restoreMailboxWithId(context, msg.mMailboxKey);
        if (mailbox == null) return 0;
        uiSaveMessage(msg, mailbox, values);
        return 1;
    }

    private int uiSendDraft(Uri uri, ContentValues values) {
        Context context = getContext();
        Message msg = Message.restoreMessageWithId(context,
                Long.parseLong(uri.getPathSegments().get(1)));
        if (msg == null) return 0;
        long mailboxId = Mailbox.findMailboxOfType(context, msg.mAccountKey, Mailbox.TYPE_OUTBOX);
        if (mailboxId == Mailbox.NO_MAILBOX) return 0;
        Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
        if (mailbox == null) return 0;
        uiSaveMessage(msg, mailbox, values);
        // Kick observers
        context.getContentResolver().notifyChange(Mailbox.CONTENT_URI, null);
        return 1;
    }

    private void putIntegerLongOrBoolean(ContentValues values, String columnName, Object value) {
        if (value instanceof Integer) {
            Integer intValue = (Integer)value;
            values.put(columnName, intValue);
        } else if (value instanceof Boolean) {
            Boolean boolValue = (Boolean)value;
            values.put(columnName, boolValue ? 1 : 0);
        } else if (value instanceof Long) {
            Long longValue = (Long)value;
            values.put(columnName, longValue);
        }
    }

    private ContentValues convertUiMessageValues(ContentValues values) {
        ContentValues ourValues = new ContentValues();
        for (String columnName: values.keySet()) {
            Object val = values.get(columnName);
            if (columnName.equals(UIProvider.ConversationColumns.STARRED)) {
                putIntegerLongOrBoolean(ourValues, MessageColumns.FLAG_FAVORITE, val);
            } else if (columnName.equals(UIProvider.ConversationColumns.READ)) {
                putIntegerLongOrBoolean(ourValues, MessageColumns.FLAG_READ, val);
            } else if (columnName.equals(MessageColumns.MAILBOX_KEY)) {
                putIntegerLongOrBoolean(ourValues, MessageColumns.MAILBOX_KEY, val);
            } else if (columnName.equals(UIProvider.ConversationColumns.FOLDER_LIST)) {
                // Convert from folder list uri to mailbox key
                Uri uri = Uri.parse((String)val);
                Long mailboxId = Long.parseLong(uri.getLastPathSegment());
                putIntegerLongOrBoolean(ourValues, MessageColumns.MAILBOX_KEY, mailboxId);
            } else {
                throw new IllegalArgumentException("Can't update " + columnName + " in message");
            }
        }
        return ourValues;
    }

    private Uri convertToEmailProviderUri(Uri uri, boolean asProvider) {
        String idString = uri.getLastPathSegment();
        try {
            long id = Long.parseLong(idString);
            Uri ourUri = ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, id);
            if (asProvider) {
                ourUri = ourUri.buildUpon().appendQueryParameter(IS_UIPROVIDER, "true").build();
            }
            return ourUri;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Message getMessageFromLastSegment(Uri uri) {
        long messageId = Long.parseLong(uri.getLastPathSegment());
        return Message.restoreMessageWithId(getContext(), messageId);
    }

    /**
     * Add an undo operation for the current sequence; if the sequence is newer than what we've had,
     * clear out the undo list and start over
     * @param uri the uri we're working on
     * @param op the ContentProviderOperation to perform upon undo
     */
    private void addToSequence(Uri uri, ContentProviderOperation op) {
        String sequenceString = uri.getQueryParameter(UIProvider.SEQUENCE_QUERY_PARAMETER);
        if (sequenceString != null) {
            int sequence = Integer.parseInt(sequenceString);
            if (sequence > mLastSequence) {
                // Reset sequence
                mLastSequenceOps.clear();
                mLastSequence = sequence;
            }
            // TODO: Need something to indicate a change isn't ready (undoable)
            mLastSequenceOps.add(op);
        }
    }

    private int uiUpdateMessage(Uri uri, ContentValues values) {
        Uri ourUri = convertToEmailProviderUri(uri, true);
        if (ourUri == null) return 0;
        ContentValues ourValues = convertUiMessageValues(values);
        Message msg = getMessageFromLastSegment(uri);
        if (msg == null) return 0;
        ContentValues undoValues = new ContentValues();
        for (String columnName: ourValues.keySet()) {
            if (columnName.equals(MessageColumns.MAILBOX_KEY)) {
                undoValues.put(MessageColumns.MAILBOX_KEY, msg.mMailboxKey);
            } else if (columnName.equals(MessageColumns.FLAG_READ)) {
                undoValues.put(MessageColumns.FLAG_READ, msg.mFlagRead);
            } else if (columnName.equals(MessageColumns.FLAG_FAVORITE)) {
                undoValues.put(MessageColumns.FLAG_FAVORITE, msg.mFlagFavorite);
            }
        }
        ContentProviderOperation op =
                ContentProviderOperation.newUpdate(convertToEmailProviderUri(uri, false))
                        .withValues(undoValues)
                        .build();
        addToSequence(uri, op);
        return update(ourUri, ourValues, null, null);
    }

    private int uiDeleteMessage(Uri uri) {
        Context context = getContext();
        Message msg = getMessageFromLastSegment(uri);
        if (msg == null) return 0;
        Mailbox mailbox =
                Mailbox.restoreMailboxOfType(context, msg.mAccountKey, Mailbox.TYPE_TRASH);
        if (mailbox == null) return 0;
        ContentProviderOperation op =
                ContentProviderOperation.newUpdate(convertToEmailProviderUri(uri, false))
                        .withValue(Message.MAILBOX_KEY, msg.mMailboxKey)
                        .build();
        addToSequence(uri, op);
        ContentValues values = new ContentValues();
        values.put(Message.MAILBOX_KEY, mailbox.mId);
        return uiUpdateMessage(uri, values);
    }

    private Cursor uiUndo(Uri uri, String[] projection) {
        // First see if we have any operations saved
        // TODO: Make sure seq matches
        if (!mLastSequenceOps.isEmpty()) {
            try {
                // TODO Always use this projection?  Or what's passed in?
                // Not sure if UI wants it, but I'm making a cursor of convo uri's
                MatrixCursor c = new MatrixCursor(
                        new String[] {UIProvider.ConversationColumns.URI},
                        mLastSequenceOps.size());
                for (ContentProviderOperation op: mLastSequenceOps) {
                    c.addRow(new String[] {op.getUri().toString()});
                }
                // Just apply the batch and we're done!
                applyBatch(mLastSequenceOps);
                // But clear the operations
                mLastSequenceOps.clear();
                // Tell the UI there are changes
                notifyUIProvider("Undo");
                return c;
            } catch (OperationApplicationException e) {
            }
        }
        return new MatrixCursor(projection, 0);
    }

    private void notifyUIProvider(String reason) {
        getContext().getContentResolver().notifyChange(UIPROVIDER_MESSAGE_NOTIFIER, null);
        // Temporary
        Log.d(TAG, "[Notify UIProvider " + reason + "]");
    }

    /**
     * Support for services and service notifications
     */

    private final IEmailServiceCallback.Stub mServiceCallback =
            new IEmailServiceCallback.Stub() {

        @Override
        public void syncMailboxListStatus(long accountId, int statusCode, int progress)
                throws RemoteException {
        }

        @Override
        public void syncMailboxStatus(long mailboxId, int statusCode, int progress)
                throws RemoteException {
            // We'll get callbacks here from the services, which we'll pass back to the UI
            Uri uri = ContentUris.withAppendedId(FOLDER_STATUS_URI, mailboxId);
            EmailProvider.this.getContext().getContentResolver().notifyChange(uri, null);
        }

        @Override
        public void loadAttachmentStatus(long messageId, long attachmentId, int statusCode,
                int progress) throws RemoteException {
        }

        @Override
        public void sendMessageStatus(long messageId, String subject, int statusCode, int progress)
                throws RemoteException {
        }

        @Override
        public void loadMessageStatus(long messageId, int statusCode, int progress)
                throws RemoteException {
        }
    };

    private Cursor uiFolderRefresh(Uri uri, String[] projection) {
        Context context = getContext();
        String idString = uri.getPathSegments().get(1);
        long id = Long.parseLong(idString);
        Mailbox mailbox = Mailbox.restoreMailboxWithId(context, id);
        if (mailbox == null) return null;
        EmailServiceProxy service = EmailServiceUtils.getServiceForAccount(context,
                mServiceCallback, mailbox.mAccountKey);
        try {
            service.startSync(id, true);
        } catch (RemoteException e) {
        }
        return null;
    }
}
