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

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.PeriodicSync;
import android.content.UriMatcher;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Base64;
import android.util.Log;

import com.android.common.content.ProjectionMap;
import com.android.email.NotificationController;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.SecurityPolicy;
import com.android.email.service.AttachmentDownloadService;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.Address;
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
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.emailcommon.utility.Utility;
import com.android.ex.photo.provider.PhotoContract;
import com.android.mail.preferences.MailPrefs;
import com.android.mail.providers.Folder;
import com.android.mail.providers.FolderList;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.AccountCapabilities;
import com.android.mail.providers.UIProvider.AccountCursorExtraKeys;
import com.android.mail.providers.UIProvider.ConversationPriority;
import com.android.mail.providers.UIProvider.ConversationSendingState;
import com.android.mail.providers.UIProvider.DraftType;
import com.android.mail.utils.AttachmentUtils;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.MatrixCursorWithCachedColumns;
import com.android.mail.utils.MatrixCursorWithExtra;
import com.android.mail.utils.MimeType;
import com.android.mail.utils.Utils;
import com.android.mail.widget.BaseWidgetProvider;
import com.android.mail.widget.WidgetProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author mblank
 *
 */
public class EmailProvider extends ContentProvider {

    private static final String TAG = "EmailProvider";

    public static String EMAIL_APP_MIME_TYPE;

    private static final String DATABASE_NAME = "EmailProvider.db";
    private static final String BODY_DATABASE_NAME = "EmailProviderBody.db";
    private static final String BACKUP_DATABASE_NAME = "EmailProviderBackup.db";

    /**
     * Notifies that changes happened. Certain UI components, e.g., widgets, can register for this
     * {@link android.content.Intent} and update accordingly. However, this can be very broad and
     * is NOT the preferred way of getting notification.
     */
    private static final String ACTION_NOTIFY_MESSAGE_LIST_DATASET_CHANGED =
        "com.android.email.MESSAGE_LIST_DATASET_CHANGED";

    private static final String EMAIL_MESSAGE_MIME_TYPE =
        "vnd.android.cursor.item/email-message";
    private static final String EMAIL_ATTACHMENT_MIME_TYPE =
        "vnd.android.cursor.item/email-attachment";

    /** Appended to the notification URI for delete operations */
    private static final String NOTIFICATION_OP_DELETE = "delete";
    /** Appended to the notification URI for insert operations */
    private static final String NOTIFICATION_OP_INSERT = "insert";
    /** Appended to the notification URI for update operations */
    private static final String NOTIFICATION_OP_UPDATE = "update";

    /** The query string to trigger a folder refresh. */
    private static String QUERY_UIREFRESH = "uirefresh";

    // Definitions for our queries looking for orphaned messages
    private static final String[] ORPHANS_PROJECTION
        = new String[] {MessageColumns.ID, MessageColumns.MAILBOX_KEY};
    private static final int ORPHANS_ID = 0;
    private static final int ORPHANS_MAILBOX_KEY = 1;

    private static final String WHERE_ID = EmailContent.RECORD_ID + "=?";

    private static final int ACCOUNT_BASE = 0;
    private static final int ACCOUNT = ACCOUNT_BASE;
    private static final int ACCOUNT_ID = ACCOUNT_BASE + 1;
    private static final int ACCOUNT_RESET_NEW_COUNT = ACCOUNT_BASE + 2;
    private static final int ACCOUNT_RESET_NEW_COUNT_ID = ACCOUNT_BASE + 3;
    private static final int ACCOUNT_DEFAULT_ID = ACCOUNT_BASE + 4;
    private static final int ACCOUNT_CHECK = ACCOUNT_BASE + 5;
    private static final int ACCOUNT_PICK_TRASH_FOLDER = ACCOUNT_BASE + 6;
    private static final int ACCOUNT_PICK_SENT_FOLDER = ACCOUNT_BASE + 7;

    private static final int MAILBOX_BASE = 0x1000;
    private static final int MAILBOX = MAILBOX_BASE;
    private static final int MAILBOX_ID = MAILBOX_BASE + 1;
    private static final int MAILBOX_NOTIFICATION = MAILBOX_BASE + 2;
    private static final int MAILBOX_MOST_RECENT_MESSAGE = MAILBOX_BASE + 3;
    private static final int MAILBOX_MESSAGE_COUNT = MAILBOX_BASE + 4;

    private static final int MESSAGE_BASE = 0x2000;
    private static final int MESSAGE = MESSAGE_BASE;
    private static final int MESSAGE_ID = MESSAGE_BASE + 1;
    private static final int SYNCED_MESSAGE_ID = MESSAGE_BASE + 2;
    private static final int MESSAGE_SELECTION = MESSAGE_BASE + 3;

    private static final int ATTACHMENT_BASE = 0x3000;
    private static final int ATTACHMENT = ATTACHMENT_BASE;
    private static final int ATTACHMENT_ID = ATTACHMENT_BASE + 1;
    private static final int ATTACHMENTS_MESSAGE_ID = ATTACHMENT_BASE + 2;
    private static final int ATTACHMENTS_CACHED_FILE_ACCESS = ATTACHMENT_BASE + 3;

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
    private static final int UI_UNDO = UI_BASE + 4;
    private static final int UI_FOLDER_REFRESH = UI_BASE + 5;
    private static final int UI_FOLDER = UI_BASE + 6;
    private static final int UI_ACCOUNT = UI_BASE + 7;
    private static final int UI_ACCTS = UI_BASE + 8;
    private static final int UI_ATTACHMENTS = UI_BASE + 9;
    private static final int UI_ATTACHMENT = UI_BASE + 10;
    private static final int UI_SEARCH = UI_BASE + 11;
    private static final int UI_ACCOUNT_DATA = UI_BASE + 12;
    private static final int UI_FOLDER_LOAD_MORE = UI_BASE + 13;
    private static final int UI_CONVERSATION = UI_BASE + 14;
    private static final int UI_RECENT_FOLDERS = UI_BASE + 15;
    private static final int UI_DEFAULT_RECENT_FOLDERS = UI_BASE + 16;
    private static final int UI_ALL_FOLDERS = UI_BASE + 17;

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

    private static UriMatcher sURIMatcher = null;

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

    private static ContentValues CONTENT_VALUES_RESET_NEW_MESSAGE_COUNT;
    private static final ContentValues EMPTY_CONTENT_VALUES = new ContentValues();

    private static final String MESSAGE_URI_PARAMETER_MAILBOX_ID = "mailboxId";

    // For undo handling
    private int mLastSequence = -1;
    private final ArrayList<ContentProviderOperation> mLastSequenceOps =
            new ArrayList<ContentProviderOperation>();

    // Query parameter indicating the command came from UIProvider
    private static final String IS_UIPROVIDER = "is_uiprovider";

    private static final String SYNC_STATUS_CALLBACK_METHOD = "sync_status";

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

    private static Uri INTEGRITY_CHECK_URI;
    public static Uri ACCOUNT_BACKUP_URI;
    private static Uri FOLDER_STATUS_URI;

    private SQLiteDatabase mDatabase;
    private SQLiteDatabase mBodyDatabase;

    public static Uri uiUri(String type, long id) {
        return Uri.parse(uiUriString(type, id));
    }

    /**
     * Creates a URI string from a database ID (guaranteed to be unique).
     * @param type of the resource: uifolder, message, etc.
     * @param id the id of the resource.
     * @return
     */
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
    private static void deleteUnlinked(SQLiteDatabase db, String table, String column,
            String foreignColumn, String foreignTable) {
        int count = db.delete(table, column + " not in (select " + foreignColumn + " from " +
                foreignTable + ")", null);
        if (count > 0) {
            Log.w(TAG, "Found " + count + " orphaned row(s) in " + table);
        }
    }

    private synchronized SQLiteDatabase getDatabase(Context context) {
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
        initUiProvider();
        return mDatabase;
    }

    /**
     * Perform startup actions related to UI
     */
    private void initUiProvider() {
        // Clear mailbox sync status
        mDatabase.execSQL("update " + Mailbox.TABLE_NAME + " set " + MailboxColumns.UI_SYNC_STATUS +
                "=" + UIProvider.SyncStatus.NO_SYNC);
    }

    /**
     * Restore user Account and HostAuth data from our backup database
     */
    private static void restoreIfNeeded(Context context, SQLiteDatabase mainDatabase) {
        if (MailActivityEmail.DEBUG) {
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
                if (MailActivityEmail.DEBUG) {
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

    private static void deleteMessageOrphans(SQLiteDatabase database, String tableName) {
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

        String tableName = TABLE_NAMES[table];
        int result = -1;

        try {
            if (match == MESSAGE_ID || match == SYNCED_MESSAGE_ID) {
                if (!uri.getBooleanQueryParameter(IS_UIPROVIDER, false)) {
                    notifyUIConversation(uri);
                }
            }
            switch (match) {
                case UI_MESSAGE:
                    return uiDeleteMessage(uri);
                case UI_ACCOUNT_DATA:
                    return uiDeleteAccountData(uri);
                case UI_ACCOUNT:
                    return uiDeleteAccount(uri);
                case MESSAGE_SELECTION:
                    Cursor findCursor = db.query(tableName, Message.ID_COLUMN_PROJECTION, selection,
                            selectionArgs, null, null, null);
                    try {
                        if (findCursor.moveToFirst()) {
                            return delete(ContentUris.withAppendedId(
                                    Message.CONTENT_URI,
                                    findCursor.getLong(Message.ID_COLUMNS_ID_COLUMN)),
                                    null, null);
                        } else {
                            return 0;
                        }
                    } finally {
                        findCursor.close();
                    }
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

                    result = db.delete(tableName, whereWithId(id, selection), selectionArgs);

                    if (match == ACCOUNT_ID) {
                        notifyUI(UIPROVIDER_ACCOUNT_NOTIFIER, id);
                        resolver.notifyChange(UIPROVIDER_ALL_ACCOUNTS_NOTIFIER, null);
                    } else if (match == MAILBOX_ID) {
                        notifyUIFolder(id, null);
                    } else if (match == ATTACHMENT_ID) {
                        notifyUI(UIPROVIDER_ATTACHMENT_NOTIFIER, id);
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
                return null;
        }
    }

    private static final Uri UIPROVIDER_CONVERSATION_NOTIFIER =
            Uri.parse("content://" + UIProvider.AUTHORITY + "/uimessages");
    private static final Uri UIPROVIDER_FOLDER_NOTIFIER =
            Uri.parse("content://" + UIProvider.AUTHORITY + "/uifolder");
    private static final Uri UIPROVIDER_FOLDERLIST_NOTIFIER =
            Uri.parse("content://" + UIProvider.AUTHORITY + "/uifolders");
    private static final Uri UIPROVIDER_ACCOUNT_NOTIFIER =
            Uri.parse("content://" + UIProvider.AUTHORITY + "/uiaccount");
    public static final Uri UIPROVIDER_SETTINGS_NOTIFIER =
            Uri.parse("content://" + UIProvider.AUTHORITY + "/uisettings");
    private static final Uri UIPROVIDER_ATTACHMENT_NOTIFIER =
            Uri.parse("content://" + UIProvider.AUTHORITY + "/uiattachment");
    private static final Uri UIPROVIDER_ATTACHMENTS_NOTIFIER =
            Uri.parse("content://" + UIProvider.AUTHORITY + "/uiattachments");
    public static final Uri UIPROVIDER_ALL_ACCOUNTS_NOTIFIER =
            Uri.parse("content://" + UIProvider.AUTHORITY + "/uiaccts");
    private static final Uri UIPROVIDER_MESSAGE_NOTIFIER =
            Uri.parse("content://" + UIProvider.AUTHORITY + "/uimessage");
    private static final Uri UIPROVIDER_RECENT_FOLDERS_NOTIFIER =
            Uri.parse("content://" + UIProvider.AUTHORITY + "/uirecentfolders");

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
                        case MESSAGE:
                            if (!uri.getBooleanQueryParameter(IS_UIPROVIDER, false)) {
                                notifyUIConversationMailbox(values.getAsLong(Message.MAILBOX_KEY));
                            }
                            break;
                        case MAILBOX:
                            if (values.containsKey(MailboxColumns.TYPE)) {
                                // Only notify for special mailbox types
                                int type = values.getAsInteger(MailboxColumns.TYPE);
                                if (type != Mailbox.TYPE_INBOX && type != Mailbox.TYPE_OUTBOX &&
                                        type != Mailbox.TYPE_DRAFTS && type != Mailbox.TYPE_SENT &&
                                        type != Mailbox.TYPE_TRASH && type != Mailbox.TYPE_SEARCH) {
                                    break;
                                }
                            }
                            // Notify the account when a new mailbox is added
                            Long accountId = values.getAsLong(MailboxColumns.ACCOUNT_KEY);
                            if (accountId != null && accountId.longValue() > 0) {
                                notifyUI(UIPROVIDER_ACCOUNT_NOTIFIER, accountId);
                            }
                            break;
                        case ACCOUNT:
                            updateAccountSyncInterval(longId, values);
                            if (!uri.getBooleanQueryParameter(IS_UIPROVIDER, false)) {
                                notifyUIAccount(longId);
                            }
                            resolver.notifyChange(UIPROVIDER_ALL_ACCOUNTS_NOTIFIER, null);
                            break;
                        case UPDATED_MESSAGE:
                        case DELETED_MESSAGE:
                            throw new IllegalArgumentException("Unknown URL " + uri);
                        case ATTACHMENT:
                            int flags = 0;
                            if (values.containsKey(Attachment.FLAGS)) {
                                flags = values.getAsInteger(Attachment.FLAGS);
                            }
                            // Report all new attachments to the download service
                            mAttachmentService.attachmentChanged(getContext(), longId, flags);
                            break;
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
            Context context = getContext();
            EmailContent.init(context);
            if (INTEGRITY_CHECK_URI == null) {
                INTEGRITY_CHECK_URI = Uri.parse("content://" + EmailContent.AUTHORITY +
                        "/integrityCheck");
                ACCOUNT_BACKUP_URI =
                        Uri.parse("content://" + EmailContent.AUTHORITY + "/accountBackup");
                FOLDER_STATUS_URI =
                        Uri.parse("content://" + EmailContent.AUTHORITY + "/status");
                EMAIL_APP_MIME_TYPE = context.getString(R.string.application_mime_type);
            }
            checkDatabases();
            if (sURIMatcher == null) {
                sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
                // Email URI matching table
                UriMatcher matcher = sURIMatcher;

                // All accounts
                matcher.addURI(EmailContent.AUTHORITY, "account", ACCOUNT);
                // A specific account
                // insert into this URI causes a mailbox to be added to the account
                matcher.addURI(EmailContent.AUTHORITY, "account/#", ACCOUNT_ID);
                matcher.addURI(EmailContent.AUTHORITY, "account/default", ACCOUNT_DEFAULT_ID);
                matcher.addURI(EmailContent.AUTHORITY, "accountCheck/#", ACCOUNT_CHECK);

                // Special URI to reset the new message count.  Only update works, and values
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
                matcher.addURI(EmailContent.AUTHORITY, "mailbox/*", MAILBOX_ID);
                matcher.addURI(EmailContent.AUTHORITY, "mailboxNotification/#",
                        MAILBOX_NOTIFICATION);
                matcher.addURI(EmailContent.AUTHORITY, "mailboxMostRecentMessage/#",
                        MAILBOX_MOST_RECENT_MESSAGE);
                matcher.addURI(EmailContent.AUTHORITY, "mailboxCount/#", MAILBOX_MESSAGE_COUNT);

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
                matcher.addURI(EmailContent.AUTHORITY, "attachment/cachedFile",
                        ATTACHMENTS_CACHED_FILE_ACCESS);

                // All mail bodies
                matcher.addURI(EmailContent.AUTHORITY, "body", BODY);
                // A specific mail body
                matcher.addURI(EmailContent.AUTHORITY, "body/#", BODY_ID);

                // All hostauth records
                matcher.addURI(EmailContent.AUTHORITY, "hostauth", HOSTAUTH);
                // A specific hostauth
                matcher.addURI(EmailContent.AUTHORITY, "hostauth/*", HOSTAUTH_ID);

                /**
                 * THIS URI HAS SPECIAL SEMANTICS
                 * ITS USE IS INTENDED FOR THE UI TO MARK CHANGES THAT NEED TO BE SYNCED BACK
                 * TO A SERVER VIA A SYNC ADAPTER
                 */
                matcher.addURI(EmailContent.AUTHORITY, "syncedMessage/#", SYNCED_MESSAGE_ID);
                matcher.addURI(EmailContent.AUTHORITY, "messageBySelection", MESSAGE_SELECTION);

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
                matcher.addURI(EmailContent.AUTHORITY, "uiallfolders/#", UI_ALL_FOLDERS);
                matcher.addURI(EmailContent.AUTHORITY, "uisubfolders/#", UI_SUBFOLDERS);
                matcher.addURI(EmailContent.AUTHORITY, "uimessages/#", UI_MESSAGES);
                matcher.addURI(EmailContent.AUTHORITY, "uimessage/#", UI_MESSAGE);
                matcher.addURI(EmailContent.AUTHORITY, "uiundo", UI_UNDO);
                matcher.addURI(EmailContent.AUTHORITY, QUERY_UIREFRESH + "/#", UI_FOLDER_REFRESH);
                // We listen to everything trailing uifolder/ since there might be an appVersion
                // as in Utils.appendVersionQueryParameter().
                matcher.addURI(EmailContent.AUTHORITY, "uifolder/*", UI_FOLDER);
                matcher.addURI(EmailContent.AUTHORITY, "uiaccount/#", UI_ACCOUNT);
                matcher.addURI(EmailContent.AUTHORITY, "uiaccts", UI_ACCTS);
                matcher.addURI(EmailContent.AUTHORITY, "uiattachments/#", UI_ATTACHMENTS);
                matcher.addURI(EmailContent.AUTHORITY, "uiattachment/#", UI_ATTACHMENT);
                matcher.addURI(EmailContent.AUTHORITY, "uisearch/#", UI_SEARCH);
                matcher.addURI(EmailContent.AUTHORITY, "uiaccountdata/#", UI_ACCOUNT_DATA);
                matcher.addURI(EmailContent.AUTHORITY, "uiloadmore/#", UI_FOLDER_LOAD_MORE);
                matcher.addURI(EmailContent.AUTHORITY, "uiconversation/#", UI_CONVERSATION);
                matcher.addURI(EmailContent.AUTHORITY, "uirecentfolders/#", UI_RECENT_FOLDERS);
                matcher.addURI(EmailContent.AUTHORITY, "uidefaultrecentfolders/#",
                        UI_DEFAULT_RECENT_FOLDERS);
                matcher.addURI(EmailContent.AUTHORITY, "pickTrashFolder/#",
                        ACCOUNT_PICK_TRASH_FOLDER);
                matcher.addURI(EmailContent.AUTHORITY, "pickSentFolder/#",
                        ACCOUNT_PICK_SENT_FOLDER);

                // Do this last, so that EmailContent/EmailProvider are initialized
                MailActivityEmail.setServicesEnabledAsync(context);
            }
            return false;
        }

    /**
     * The idea here is that the two databases (EmailProvider.db and EmailProviderBody.db must
     * always be in sync (i.e. there are two database or NO databases).  This code will delete
     * any "orphan" database, so that both will be created together.  Note that an "orphan" database
     * will exist after either of the individual databases is deleted due to data corruption.
     */
    public synchronized void checkDatabases() {
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
        if (MailActivityEmail.DEBUG) {
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
                        return new MatrixCursorWithCachedColumns(projection, 0);
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

        String tableName = TABLE_NAMES[table];

        try {
            switch (match) {
                // First, dispatch queries from UnfiedEmail
                case UI_SEARCH:
                    c = uiSearch(uri, projection);
                    return c;
                case UI_ACCTS:
                    c = uiAccounts(projection);
                    return c;
                case UI_UNDO:
                    return uiUndo(projection);
                case UI_SUBFOLDERS:
                case UI_MESSAGES:
                case UI_MESSAGE:
                case UI_FOLDER:
                case UI_ACCOUNT:
                case UI_ATTACHMENT:
                case UI_ATTACHMENTS:
                case UI_CONVERSATION:
                case UI_RECENT_FOLDERS:
                case UI_ALL_FOLDERS:
                    // For now, we don't allow selection criteria within these queries
                    if (selection != null || selectionArgs != null) {
                        throw new IllegalArgumentException("UI queries can't have selection/args");
                    }

                    final String seenParam = uri.getQueryParameter(UIProvider.SEEN_QUERY_PARAMETER);
                    final boolean unseenOnly;
                    if (seenParam != null && Boolean.FALSE.toString().equals(seenParam)) {
                        unseenOnly = true;
                    } else {
                        unseenOnly = false;
                    }

                    c = uiQuery(match, uri, projection, unseenOnly);
                    return c;
                case UI_FOLDERS:
                    c = uiFolders(uri, projection);
                    return c;
                case UI_FOLDER_LOAD_MORE:
                    c = uiFolderLoadMore(getMailbox(uri));
                    return c;
                case UI_FOLDER_REFRESH:
                    c = uiFolderRefresh(getMailbox(uri), 0);
                    return c;
                case MAILBOX_NOTIFICATION:
                    c = notificationQuery(uri);
                    return c;
                case MAILBOX_MOST_RECENT_MESSAGE:
                    c = mostRecentMessageQuery(uri);
                    return c;
                case MAILBOX_MESSAGE_COUNT:
                    c = getMailboxMessageCount(uri);
                    return c;
                case ACCOUNT_DEFAULT_ID:
                    // We want either the row which has isDefault set, or we want the lowest valued
                    // account id if none are isDefault. I don't think there's a way to express this
                    // simply in sql so we get all account ids and loop through them manually.
                    final Cursor accounts = db.query(Account.TABLE_NAME,
                            Account.ACCOUNT_IS_DEFAULT_PROJECTION,
                            null, null, null, null, null, null);
                    long defaultAccountId = Account.NO_ACCOUNT;
                    while (accounts.moveToNext()) {
                        final long accountId =
                                accounts.getLong(Account.ACCOUNT_IS_DEFAULT_COLUMN_ID);
                        if (accounts.getInt(Account.ACCOUNT_IS_DEFAULT_COLUMN_IS_DEFAULT) == 1) {
                            defaultAccountId = accountId;
                            break;
                        } else if (defaultAccountId == Account.NO_ACCOUNT ||
                                accountId < defaultAccountId) {
                            defaultAccountId = accountId;
                        }
                    }
                    // Return a cursor with an id projection
                    final MatrixCursor mc =
                            new MatrixCursorWithCachedColumns(EmailContent.ID_PROJECTION, 1);
                    mc.addRow(new Object[] {defaultAccountId});
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
                    c = db.query(tableName, projection, whereWithId(id, selection),
                            selectionArgs, null, null, sortOrder, limit);
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
            if (c == null) {
                // This should never happen, but let's be sure to log it...
                // TODO: There are actually cases where c == null is expected, for example
                // UI_FOLDER_LOAD_MORE.
                // Demoting this to a warning for now until we figure out what to do with it.
                Log.w(TAG, "Query returning null for uri: " + uri + ", selection: " + selection);
            }
        }

        if ((c != null) && !isTemporary()) {
            c.setNotificationUri(getContext().getContentResolver(), uri);
        }
        return c;
    }

    private static String whereWithId(String id, String selection) {
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
    private static String whereWith(String where, String selection) {
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
        if (MailActivityEmail.DEBUG) {
            Log.d(TAG, "backupAccounts...");
        }
        SQLiteDatabase backupDatabase = getBackupDatabase(context);
        try {
            int numBackedUp = copyAccountTables(mainDatabase, backupDatabase);
            if (numBackedUp < 0) {
                Log.e(TAG, "Account backup failed!");
            } else if (MailActivityEmail.DEBUG) {
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
        if (MailActivityEmail.DEBUG) {
            Log.d(TAG, "restoreAccounts...");
        }
        SQLiteDatabase backupDatabase = getBackupDatabase(context);
        try {
            int numRecovered = copyAccountTables(backupDatabase, mainDatabase);
            if (numRecovered > 0) {
                Log.e(TAG, "Recovered " + numRecovered + " accounts!");
            } else if (numRecovered < 0) {
                Log.e(TAG, "Account recovery failed?");
            } else if (MailActivityEmail.DEBUG) {
                Log.d(TAG, "No accounts to restore...");
            }
            return numRecovered;
        } finally {
            if (backupDatabase != null) {
                backupDatabase.close();
            }
        }
    }

    // select count(*) from (select count(*) as dupes from Mailbox where accountKey=?
    // group by serverId) where dupes > 1;
    private static final String ACCOUNT_INTEGRITY_SQL =
            "select count(*) from (select count(*) as dupes from " + Mailbox.TABLE_NAME +
            " where accountKey=? group by " + MailboxColumns.SERVER_ID + ") where dupes > 1";

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

        String tableName = TABLE_NAMES[table];
        String id = "0";

        try {
            switch (match) {
                case ACCOUNT_PICK_TRASH_FOLDER:
                    return pickTrashFolder(uri);
                case ACCOUNT_PICK_SENT_FOLDER:
                    return pickSentFolder(uri);
                case UI_FOLDER:
                    return uiUpdateFolder(context, uri, values);
                case UI_RECENT_FOLDERS:
                    return uiUpdateRecentFolders(uri, values);
                case UI_DEFAULT_RECENT_FOLDERS:
                    return uiPopulateRecentFolders(uri);
                case UI_ATTACHMENT:
                    return uiUpdateAttachment(uri, values);
                case UI_MESSAGE:
                    return uiUpdateMessage(uri, values);
                case ACCOUNT_CHECK:
                    id = uri.getLastPathSegment();
                    // With any error, return 1 (a failure)
                    int res = 1;
                    Cursor ic = null;
                    try {
                        ic = db.rawQuery(ACCOUNT_INTEGRITY_SQL, new String[] {id});
                        if (ic.moveToFirst()) {
                            res = ic.getInt(0);
                        }
                    } finally {
                        if (ic != null) {
                            ic.close();
                        }
                    }
                    // Count of duplicated mailboxes
                    return res;
                case MESSAGE_SELECTION:
                    Cursor findCursor = db.query(tableName, Message.ID_COLUMN_PROJECTION, selection,
                            selectionArgs, null, null, null);
                    try {
                        if (findCursor.moveToFirst()) {
                            return update(ContentUris.withAppendedId(
                                    Message.CONTENT_URI,
                                    findCursor.getLong(Message.ID_COLUMNS_ID_COLUMN)),
                                    values, null, null);
                        } else {
                            return 0;
                        }
                    } finally {
                        findCursor.close();
                    }
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
                    }
                    if (match == MESSAGE_ID || match == SYNCED_MESSAGE_ID) {
                        if (!uri.getBooleanQueryParameter(IS_UIPROVIDER, false)) {
                            notifyUIConversation(uri);
                        }
                    } else if (match == ATTACHMENT_ID) {
                        long attId = Integer.parseInt(id);
                        if (values.containsKey(Attachment.FLAGS)) {
                            int flags = values.getAsInteger(Attachment.FLAGS);
                            mAttachmentService.attachmentChanged(context, attId, flags);
                        }
                        // Notify UI if necessary; there are only two columns we can change that
                        // would be worth a notification
                        if (values.containsKey(AttachmentColumns.UI_STATE) ||
                                values.containsKey(AttachmentColumns.UI_DOWNLOADED_SIZE)) {
                            // Notify on individual attachment
                            notifyUI(UIPROVIDER_ATTACHMENT_NOTIFIER, id);
                            Attachment att = Attachment.restoreAttachmentWithId(context, attId);
                            if (att != null) {
                                // And on owning Message
                                notifyUI(UIPROVIDER_ATTACHMENTS_NOTIFIER, att.mMessageKey);
                            }
                        }
                    } else if (match == MAILBOX_ID && values.containsKey(Mailbox.UI_SYNC_STATUS)) {
                        // TODO: should this notify on keys other than sync status?
                        notifyUIFolder(id, null);
                    } else if (match == ACCOUNT_ID) {
                        updateAccountSyncInterval(Long.parseLong(id), values);
                        // Notify individual account and "all accounts"
                        notifyUI(UIPROVIDER_ACCOUNT_NOTIFIER, id);
                        resolver.notifyChange(UIPROVIDER_ALL_ACCOUNTS_NOTIFIER, null);
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
                    result = db.update(tableName, values, selection, selectionArgs);
                    break;

                case ACCOUNT_RESET_NEW_COUNT_ID:
                    id = uri.getPathSegments().get(1);
                    ContentValues newMessageCount = CONTENT_VALUES_RESET_NEW_MESSAGE_COUNT;
                    if (values != null) {
                        Long set = values.getAsLong(EmailContent.SET_COLUMN_NAME);
                        if (set != null) {
                            newMessageCount = new ContentValues();
                            newMessageCount.put(Account.NEW_MESSAGE_COUNT, set);
                        }
                    }
                    result = db.update(tableName, newMessageCount,
                            whereWithId(id, selection), selectionArgs);
                    notificationUri = Account.CONTENT_URI; // Only notify account cursors.
                    break;
                case ACCOUNT_RESET_NEW_COUNT:
                    result = db.update(tableName, CONTENT_VALUES_RESET_NEW_MESSAGE_COUNT,
                            selection, selectionArgs);
                    // Affects all accounts.  Just invalidate all account cache.
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

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        LogUtils.d(TAG, "EmailProvider#call(%s, %s)", method, arg);

        // First handle sync status callbacks.
        if (TextUtils.equals(method, SYNC_STATUS_CALLBACK_METHOD)) {
            final int syncStatusType = extras.getInt(EmailServiceStatus.SYNC_STATUS_TYPE);
            switch (syncStatusType) {
                case EmailServiceStatus.SYNC_STATUS_TYPE_MAILBOX:
                    try {
                        mServiceCallback.syncMailboxStatus(
                                extras.getLong(EmailServiceStatus.SYNC_STATUS_ID),
                                extras.getInt(EmailServiceStatus.SYNC_STATUS_CODE),
                                extras.getInt(EmailServiceStatus.SYNC_STATUS_PROGRESS));
                    } catch (RemoteException re) {
                        // This can't actually happen but I have to pacify the compiler.
                    }
                    break;
                default:
                    LogUtils.e(TAG, "Sync status received of unknown type %d", syncStatusType);
                    break;
            }
            return null;
        }

        // Handle send & save.
        final Uri accountUri = Uri.parse(arg);
        final long accountId = Long.parseLong(accountUri.getPathSegments().get(1));

        Uri messageUri = null;
        if (TextUtils.equals(method, UIProvider.AccountCallMethods.SEND_MESSAGE)) {
            messageUri = uiSendDraftMessage(accountId, extras);
        } else if (TextUtils.equals(method, UIProvider.AccountCallMethods.SAVE_MESSAGE)) {
            messageUri = uiSaveDraftMessage(accountId, extras);
        }

        final Bundle result;
        if (messageUri != null) {
            result = new Bundle(1);
            result.putParcelable(UIProvider.MessageColumns.URI, messageUri);
        } else {
            result = null;
        }

        return result;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
            LogUtils.d(TAG, "EmailProvider.openFile: %s", LogUtils.contentUriToString(TAG, uri));
        }

        final int match = findMatch(uri, "openFile");
        switch (match) {
            case ATTACHMENTS_CACHED_FILE_ACCESS:
                // Parse the cache file path out from the uri
                final String cachedFilePath =
                        uri.getQueryParameter(EmailContent.Attachment.CACHED_FILE_QUERY_PARAM);

                if (cachedFilePath != null) {
                    // clearCallingIdentity means that the download manager will
                    // check our permissions rather than the permissions of whatever
                    // code is calling us.
                    long binderToken = Binder.clearCallingIdentity();
                    try {
                        LogUtils.d(TAG, "Opening attachment %s", cachedFilePath);
                        return ParcelFileDescriptor.open(
                                new File(cachedFilePath), ParcelFileDescriptor.MODE_READ_ONLY);
                    } finally {
                        Binder.restoreCallingIdentity(binderToken);
                    }
                }
                break;
        }

        throw new FileNotFoundException("unable to open file");
    }


    /**
     * Returns the base notification URI for the given content type.
     *
     * @param match The type of content that was modified.
     */
    private static Uri getBaseNotificationUri(int match) {
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
    private final AttachmentService mAttachmentService = DEFAULT_ATTACHMENT_SERVICE;

    private Cursor notificationQuery(final Uri uri) {
        final SQLiteDatabase db = getDatabase(getContext());
        final String accountId = uri.getLastPathSegment();

        final StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT ");
        sqlBuilder.append(MessageColumns.MAILBOX_KEY).append(", ");
        sqlBuilder.append("SUM(CASE ")
                .append(MessageColumns.FLAG_READ).append(" WHEN 0 THEN 1 ELSE 0 END), ");
        sqlBuilder.append("SUM(CASE ")
                .append(MessageColumns.FLAG_SEEN).append(" WHEN 0 THEN 1 ELSE 0 END)\n");
        sqlBuilder.append("FROM ");
        sqlBuilder.append(Message.TABLE_NAME).append('\n');
        sqlBuilder.append("WHERE ");
        sqlBuilder.append(MessageColumns.ACCOUNT_KEY).append(" = ?\n");
        sqlBuilder.append("GROUP BY ");
        sqlBuilder.append(MessageColumns.MAILBOX_KEY);

        final String sql = sqlBuilder.toString();

        final String[] selectionArgs = {accountId};

        return db.rawQuery(sql, selectionArgs);
    }

    public Cursor mostRecentMessageQuery(Uri uri) {
        SQLiteDatabase db = getDatabase(getContext());
        String mailboxId = uri.getLastPathSegment();
        return db.rawQuery("select max(_id) from Message where mailboxKey=?",
                new String[] {mailboxId});
    }

    private Cursor getMailboxMessageCount(Uri uri) {
        SQLiteDatabase db = getDatabase(getContext());
        String mailboxId = uri.getLastPathSegment();
        return db.rawQuery("select count(*) from Message where mailboxKey=?",
                new String[] {mailboxId});
    }

    /**
     * Support for UnifiedEmail below
     */

    private static final String NOT_A_DRAFT_STRING =
        Integer.toString(UIProvider.DraftType.NOT_A_DRAFT);

    private static final String CONVERSATION_FLAGS =
            "CASE WHEN (" + MessageColumns.FLAGS + "&" + Message.FLAG_INCOMING_MEETING_INVITE +
                ") !=0 THEN " + UIProvider.ConversationFlags.CALENDAR_INVITE +
                " ELSE 0 END + " +
            "CASE WHEN (" + MessageColumns.FLAGS + "&" + Message.FLAG_FORWARDED +
                ") !=0 THEN " + UIProvider.ConversationFlags.FORWARDED +
                " ELSE 0 END + " +
             "CASE WHEN (" + MessageColumns.FLAGS + "&" + Message.FLAG_REPLIED_TO +
                ") !=0 THEN " + UIProvider.ConversationFlags.REPLIED +
                " ELSE 0 END";

    /**
     * Array of pre-defined account colors (legacy colors from old email app)
     */
    private static final int[] ACCOUNT_COLORS = new int[] {
        0xff71aea7, 0xff621919, 0xff18462f, 0xffbf8e52, 0xff001f79,
        0xffa8afc2, 0xff6b64c4, 0xff738359, 0xff9d50a4
    };

    private static final String CONVERSATION_COLOR =
            "@CASE (" + MessageColumns.ACCOUNT_KEY + " - 1) % " + ACCOUNT_COLORS.length +
                    " WHEN 0 THEN " + ACCOUNT_COLORS[0] +
                    " WHEN 1 THEN " + ACCOUNT_COLORS[1] +
                    " WHEN 2 THEN " + ACCOUNT_COLORS[2] +
                    " WHEN 3 THEN " + ACCOUNT_COLORS[3] +
                    " WHEN 4 THEN " + ACCOUNT_COLORS[4] +
                    " WHEN 5 THEN " + ACCOUNT_COLORS[5] +
                    " WHEN 6 THEN " + ACCOUNT_COLORS[6] +
                    " WHEN 7 THEN " + ACCOUNT_COLORS[7] +
                    " WHEN 8 THEN " + ACCOUNT_COLORS[8] +
            " END";

    private static final String ACCOUNT_COLOR =
            "@CASE (" + AccountColumns.ID + " - 1) % " + ACCOUNT_COLORS.length +
                    " WHEN 0 THEN " + ACCOUNT_COLORS[0] +
                    " WHEN 1 THEN " + ACCOUNT_COLORS[1] +
                    " WHEN 2 THEN " + ACCOUNT_COLORS[2] +
                    " WHEN 3 THEN " + ACCOUNT_COLORS[3] +
                    " WHEN 4 THEN " + ACCOUNT_COLORS[4] +
                    " WHEN 5 THEN " + ACCOUNT_COLORS[5] +
                    " WHEN 6 THEN " + ACCOUNT_COLORS[6] +
                    " WHEN 7 THEN " + ACCOUNT_COLORS[7] +
                    " WHEN 8 THEN " + ACCOUNT_COLORS[8] +
            " END";
    /**
     * Mapping of UIProvider columns to EmailProvider columns for the message list (called the
     * conversation list in UnifiedEmail)
     */
    private static ProjectionMap getMessageListMap() {
        if (sMessageListMap == null) {
            sMessageListMap = ProjectionMap.builder()
                .add(BaseColumns._ID, MessageColumns.ID)
                .add(UIProvider.ConversationColumns.URI, uriWithId("uimessage"))
                .add(UIProvider.ConversationColumns.MESSAGE_LIST_URI, uriWithId("uimessage"))
                .add(UIProvider.ConversationColumns.SUBJECT, MessageColumns.SUBJECT)
                .add(UIProvider.ConversationColumns.SNIPPET, MessageColumns.SNIPPET)
                .add(UIProvider.ConversationColumns.CONVERSATION_INFO, null)
                .add(UIProvider.ConversationColumns.DATE_RECEIVED_MS, MessageColumns.TIMESTAMP)
                .add(UIProvider.ConversationColumns.HAS_ATTACHMENTS, MessageColumns.FLAG_ATTACHMENT)
                .add(UIProvider.ConversationColumns.NUM_MESSAGES, "1")
                .add(UIProvider.ConversationColumns.NUM_DRAFTS, "0")
                .add(UIProvider.ConversationColumns.SENDING_STATE,
                        Integer.toString(ConversationSendingState.OTHER))
                .add(UIProvider.ConversationColumns.PRIORITY,
                        Integer.toString(ConversationPriority.LOW))
                .add(UIProvider.ConversationColumns.READ, MessageColumns.FLAG_READ)
                .add(UIProvider.ConversationColumns.SEEN, MessageColumns.FLAG_SEEN)
                .add(UIProvider.ConversationColumns.STARRED, MessageColumns.FLAG_FAVORITE)
                .add(UIProvider.ConversationColumns.FLAGS, CONVERSATION_FLAGS)
                .add(UIProvider.ConversationColumns.ACCOUNT_URI,
                        uriWithColumn("uiaccount", MessageColumns.ACCOUNT_KEY))
                .add(UIProvider.ConversationColumns.SENDER_INFO, MessageColumns.DISPLAY_NAME)
                .build();
        }
        return sMessageListMap;
    }
    private static ProjectionMap sMessageListMap;

    /**
     * Generate UIProvider draft type; note the test for "reply all" must come before "reply"
     */
    private static final String MESSAGE_DRAFT_TYPE =
        "CASE WHEN (" + MessageColumns.FLAGS + "&" + Message.FLAG_TYPE_ORIGINAL +
            ") !=0 THEN " + UIProvider.DraftType.COMPOSE +
        " WHEN (" + MessageColumns.FLAGS + "&" + (1<<20) +
            ") !=0 THEN " + UIProvider.DraftType.REPLY_ALL +
        " WHEN (" + MessageColumns.FLAGS + "&" + Message.FLAG_TYPE_REPLY +
            ") !=0 THEN " + UIProvider.DraftType.REPLY +
        " WHEN (" + MessageColumns.FLAGS + "&" + Message.FLAG_TYPE_FORWARD +
            ") !=0 THEN " + UIProvider.DraftType.FORWARD +
            " ELSE " + UIProvider.DraftType.NOT_A_DRAFT + " END";

    private static final String MESSAGE_FLAGS =
            "CASE WHEN (" + MessageColumns.FLAGS + "&" + Message.FLAG_INCOMING_MEETING_INVITE +
            ") !=0 THEN " + UIProvider.MessageFlags.CALENDAR_INVITE +
            " ELSE 0 END";

    /**
     * Mapping of UIProvider columns to EmailProvider columns for a detailed message view in
     * UnifiedEmail
     */
    private static ProjectionMap getMessageViewMap() {
        if (sMessageViewMap == null) {
            sMessageViewMap = ProjectionMap.builder()
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
                .add(UIProvider.MessageColumns.DATE_RECEIVED_MS,
                        EmailContent.MessageColumns.TIMESTAMP)
                .add(UIProvider.MessageColumns.BODY_HTML, Body.HTML_CONTENT)
                .add(UIProvider.MessageColumns.BODY_TEXT, Body.TEXT_CONTENT)
                .add(UIProvider.MessageColumns.REF_MESSAGE_ID, "0")
                .add(UIProvider.MessageColumns.DRAFT_TYPE, NOT_A_DRAFT_STRING)
                .add(UIProvider.MessageColumns.APPEND_REF_MESSAGE_CONTENT, "0")
                .add(UIProvider.MessageColumns.HAS_ATTACHMENTS,
                        EmailContent.MessageColumns.FLAG_ATTACHMENT)
                .add(UIProvider.MessageColumns.ATTACHMENT_LIST_URI,
                        uriWithFQId("uiattachments", Message.TABLE_NAME))
                .add(UIProvider.MessageColumns.MESSAGE_FLAGS, MESSAGE_FLAGS)
                .add(UIProvider.MessageColumns.DRAFT_TYPE, MESSAGE_DRAFT_TYPE)
                .add(UIProvider.MessageColumns.MESSAGE_ACCOUNT_URI,
                        uriWithColumn("account", MessageColumns.ACCOUNT_KEY))
                .add(UIProvider.MessageColumns.STARRED, EmailContent.MessageColumns.FLAG_FAVORITE)
                .add(UIProvider.MessageColumns.READ, EmailContent.MessageColumns.FLAG_READ)
                .add(UIProvider.MessageColumns.SEEN, EmailContent.MessageColumns.FLAG_SEEN)
                .add(UIProvider.MessageColumns.SPAM_WARNING_STRING, null)
                .add(UIProvider.MessageColumns.SPAM_WARNING_LEVEL,
                        Integer.toString(UIProvider.SpamWarningLevel.NO_WARNING))
                .add(UIProvider.MessageColumns.SPAM_WARNING_LINK_TYPE,
                        Integer.toString(UIProvider.SpamWarningLinkType.NO_LINK))
                .add(UIProvider.MessageColumns.VIA_DOMAIN, null)
                .build();
        }
        return sMessageViewMap;
    }
    private static ProjectionMap sMessageViewMap;

    /**
     * Generate UIProvider folder capabilities from mailbox flags
     */
    private static final String FOLDER_CAPABILITIES =
        "CASE WHEN (" + MailboxColumns.FLAGS + "&" + Mailbox.FLAG_ACCEPTS_MOVED_MAIL +
            ") !=0 THEN " + UIProvider.FolderCapabilities.CAN_ACCEPT_MOVED_MESSAGES +
            " ELSE 0 END";

    /**
     * Convert EmailProvider type to UIProvider type
     */
    private static final String FOLDER_TYPE = "CASE " + MailboxColumns.TYPE
            + " WHEN " + Mailbox.TYPE_INBOX   + " THEN " + UIProvider.FolderType.INBOX
            + " WHEN " + Mailbox.TYPE_DRAFTS  + " THEN " + UIProvider.FolderType.DRAFT
            + " WHEN " + Mailbox.TYPE_OUTBOX  + " THEN " + UIProvider.FolderType.OUTBOX
            + " WHEN " + Mailbox.TYPE_SENT    + " THEN " + UIProvider.FolderType.SENT
            + " WHEN " + Mailbox.TYPE_TRASH   + " THEN " + UIProvider.FolderType.TRASH
            + " WHEN " + Mailbox.TYPE_JUNK    + " THEN " + UIProvider.FolderType.SPAM
            + " WHEN " + Mailbox.TYPE_STARRED + " THEN " + UIProvider.FolderType.STARRED
            + " ELSE " + UIProvider.FolderType.DEFAULT + " END";

    private static final String FOLDER_ICON = "CASE " + MailboxColumns.TYPE
            + " WHEN " + Mailbox.TYPE_INBOX   + " THEN " + R.drawable.ic_folder_inbox_holo_light
            + " WHEN " + Mailbox.TYPE_DRAFTS  + " THEN " + R.drawable.ic_folder_drafts_holo_light
            + " WHEN " + Mailbox.TYPE_OUTBOX  + " THEN " + R.drawable.ic_folder_outbox_holo_light
            + " WHEN " + Mailbox.TYPE_SENT    + " THEN " + R.drawable.ic_folder_sent_holo_light
            + " WHEN " + Mailbox.TYPE_TRASH   + " THEN " + R.drawable.ic_menu_trash_holo_light
            + " WHEN " + Mailbox.TYPE_STARRED + " THEN " + R.drawable.ic_menu_star_holo_light
            + " ELSE -1 END";

    private static ProjectionMap getFolderListMap() {
        if (sFolderListMap == null) {
            sFolderListMap = ProjectionMap.builder()
                .add(BaseColumns._ID, MailboxColumns.ID)
                .add(UIProvider.FolderColumns.PERSISTENT_ID, MailboxColumns.SERVER_ID)
                .add(UIProvider.FolderColumns.URI, uriWithId("uifolder"))
                .add(UIProvider.FolderColumns.NAME, "displayName")
                .add(UIProvider.FolderColumns.HAS_CHILDREN,
                        MailboxColumns.FLAGS + "&" + Mailbox.FLAG_HAS_CHILDREN)
                .add(UIProvider.FolderColumns.CAPABILITIES, FOLDER_CAPABILITIES)
                .add(UIProvider.FolderColumns.SYNC_WINDOW, "3")
                .add(UIProvider.FolderColumns.CONVERSATION_LIST_URI, uriWithId("uimessages"))
                .add(UIProvider.FolderColumns.CHILD_FOLDERS_LIST_URI, uriWithId("uisubfolders"))
                .add(UIProvider.FolderColumns.UNREAD_COUNT, MailboxColumns.UNREAD_COUNT)
                .add(UIProvider.FolderColumns.TOTAL_COUNT, MailboxColumns.TOTAL_COUNT)
                .add(UIProvider.FolderColumns.REFRESH_URI, uriWithId(QUERY_UIREFRESH))
                .add(UIProvider.FolderColumns.SYNC_STATUS, MailboxColumns.UI_SYNC_STATUS)
                .add(UIProvider.FolderColumns.LAST_SYNC_RESULT, MailboxColumns.UI_LAST_SYNC_RESULT)
                .add(UIProvider.FolderColumns.TYPE, FOLDER_TYPE)
                .add(UIProvider.FolderColumns.ICON_RES_ID, FOLDER_ICON)
                .add(UIProvider.FolderColumns.HIERARCHICAL_DESC, MailboxColumns.HIERARCHICAL_NAME)
                .build();
        }
        return sFolderListMap;
    }
    private static ProjectionMap sFolderListMap;

    /**
     * Constructs the map of default entries for accounts. These values can be overridden in
     * {@link #genQueryAccount(String[], String)}.
     */
    private static ProjectionMap getAccountListMap(Context context) {
        if (sAccountListMap == null) {
            final ProjectionMap.Builder builder = ProjectionMap.builder()
                    .add(BaseColumns._ID, AccountColumns.ID)
                    .add(UIProvider.AccountColumns.FOLDER_LIST_URI, uriWithId("uifolders"))
                    .add(UIProvider.AccountColumns.FULL_FOLDER_LIST_URI, uriWithId("uiallfolders"))
                    .add(UIProvider.AccountColumns.NAME, AccountColumns.DISPLAY_NAME)
                    .add(UIProvider.AccountColumns.UNDO_URI,
                            ("'content://" + EmailContent.AUTHORITY + "/uiundo'"))
                    .add(UIProvider.AccountColumns.URI, uriWithId("uiaccount"))
                    .add(UIProvider.AccountColumns.SEARCH_URI, uriWithId("uisearch"))
                            // TODO: Is provider version used?
                    .add(UIProvider.AccountColumns.PROVIDER_VERSION, "1")
                    .add(UIProvider.AccountColumns.SYNC_STATUS, "0")
                    .add(UIProvider.AccountColumns.RECENT_FOLDER_LIST_URI,
                            uriWithId("uirecentfolders"))
                    .add(UIProvider.AccountColumns.DEFAULT_RECENT_FOLDER_LIST_URI,
                            uriWithId("uidefaultrecentfolders"))
                    .add(UIProvider.AccountColumns.SettingsColumns.SIGNATURE,
                            AccountColumns.SIGNATURE)
                    .add(UIProvider.AccountColumns.SettingsColumns.SNAP_HEADERS,
                            Integer.toString(UIProvider.SnapHeaderValue.ALWAYS))
                    .add(UIProvider.AccountColumns.SettingsColumns.REPLY_BEHAVIOR,
                            Integer.toString(UIProvider.DefaultReplyBehavior.REPLY))
                    .add(UIProvider.AccountColumns.SettingsColumns.CONFIRM_ARCHIVE, "0")
                    .add(UIProvider.AccountColumns.SettingsColumns.CONVERSATION_VIEW_MODE,
                            Integer.toString(UIProvider.ConversationViewMode.UNDEFINED))
                    .add(UIProvider.AccountColumns.SettingsColumns.VEILED_ADDRESS_PATTERN, null);

            final String feedbackUri = context.getString(R.string.email_feedback_uri);
            if (!TextUtils.isEmpty(feedbackUri)) {
                builder.add(UIProvider.AccountColumns.SEND_FEEDBACK_INTENT_URI, feedbackUri);
            }

            sAccountListMap = builder.build();
        }
        return sAccountListMap;
    }
    private static ProjectionMap sAccountListMap;

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
     * Mapping of UIProvider columns to EmailProvider columns for a message's attachments
     */
    private static ProjectionMap getAttachmentMap() {
        if (sAttachmentMap == null) {
            sAttachmentMap = ProjectionMap.builder()
                .add(UIProvider.AttachmentColumns.NAME, AttachmentColumns.FILENAME)
                .add(UIProvider.AttachmentColumns.SIZE, AttachmentColumns.SIZE)
                .add(UIProvider.AttachmentColumns.URI, uriWithId("uiattachment"))
                .add(UIProvider.AttachmentColumns.CONTENT_TYPE, AttachmentColumns.MIME_TYPE)
                .add(UIProvider.AttachmentColumns.STATE, AttachmentColumns.UI_STATE)
                .add(UIProvider.AttachmentColumns.DESTINATION, AttachmentColumns.UI_DESTINATION)
                .add(UIProvider.AttachmentColumns.DOWNLOADED_SIZE,
                        AttachmentColumns.UI_DOWNLOADED_SIZE)
                .add(UIProvider.AttachmentColumns.CONTENT_URI, AttachmentColumns.CONTENT_URI)
                .build();
        }
        return sAttachmentMap;
    }
    private static ProjectionMap sAttachmentMap;

    /**
     * Generate the SELECT clause using a specified mapping and the original UI projection
     * @param map the ProjectionMap to use for this projection
     * @param projection the projection as sent by UnifiedEmail
     * @return a StringBuilder containing the SELECT expression for a SQLite query
     */
    private static StringBuilder genSelect(ProjectionMap map, String[] projection) {
        return genSelect(map, projection, EMPTY_CONTENT_VALUES);
    }

    private static StringBuilder genSelect(ProjectionMap map, String[] projection,
            ContentValues values) {
        StringBuilder sb = new StringBuilder("SELECT ");
        boolean first = true;
        for (String column: projection) {
            if (first) {
                first = false;
            } else {
                sb.append(',');
            }
            String val = null;
            // First look at values; this is an override of default behavior
            if (values.containsKey(column)) {
                String value = values.getAsString(column);
                if (value == null) {
                    val = "NULL AS " + column;
                } else if (value.startsWith("@")) {
                    val = value.substring(1) + " AS " + column;
                } else {
                    val = "'" + value + "' AS " + column;
                }
            } else {
                // Now, get the standard value for the column from our projection map
                val = map.get(column);
                // If we don't have the column, return "NULL AS <column>", and warn
                if (val == null) {
                    val = "NULL AS " + column;
                }
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
        return uriWithColumn(type, EmailContent.RECORD_ID);
    }

    /**
     * Convenience method to create a Uri string given the "type" of query; we append the type
     * of the query and the passed in column name
     *
     * @param type the "type" of the query, as defined by our UriMatcher definitions
     * @param columnName the column in the table being queried
     * @return a Uri string
     */
    private static String uriWithColumn(String type, String columnName) {
        return "'content://" + EmailContent.AUTHORITY + "/" + type + "/' || " + columnName;
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

    // Regex that matches start of img tag. '<(?i)img\s+'.
    private static final Pattern IMG_TAG_START_REGEX = Pattern.compile("<(?i)img\\s+");

    /**
     * Class that holds the sqlite query and the attachment (JSON) value (which might be null)
     */
    private static class MessageQuery {
        final String query;
        final String attachmentJson;

        MessageQuery(String _query, String _attachmentJson) {
            query = _query;
            attachmentJson = _attachmentJson;
        }
    }

    /**
     * Generate the "view message" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private MessageQuery genQueryViewMessage(String[] uiProjection, String id) {
        Context context = getContext();
        long messageId = Long.parseLong(id);
        Message msg = Message.restoreMessageWithId(context, messageId);
        ContentValues values = new ContentValues();
        String attachmentJson = null;
        if (msg != null) {
            Body body = Body.restoreBodyWithMessageId(context, messageId);
            if (body != null) {
                if (body.mHtmlContent != null) {
                    if (IMG_TAG_START_REGEX.matcher(body.mHtmlContent).find()) {
                        values.put(UIProvider.MessageColumns.EMBEDS_EXTERNAL_RESOURCES, 1);
                    }
                }
            }
            Address[] fromList = Address.unpack(msg.mFrom);
            int autoShowImages = 0;
            Preferences prefs = Preferences.getPreferences(context);
            for (Address sender : fromList) {
                String email = sender.getAddress();
                if (prefs.shouldShowImagesFor(email)) {
                    autoShowImages = 1;
                    break;
                }
            }
            values.put(UIProvider.MessageColumns.ALWAYS_SHOW_IMAGES, autoShowImages);
            // Add attachments...
            Attachment[] atts = Attachment.restoreAttachmentsWithMessageId(context, messageId);
            if (atts.length > 0) {
                ArrayList<com.android.mail.providers.Attachment> uiAtts =
                        new ArrayList<com.android.mail.providers.Attachment>();
                for (Attachment att : atts) {
                    if (att.mContentId != null && att.getContentUri() != null) {
                        continue;
                    }
                    com.android.mail.providers.Attachment uiAtt =
                            new com.android.mail.providers.Attachment();
                    uiAtt.setName(att.mFileName);
                    uiAtt.setContentType(att.mMimeType);
                    uiAtt.size = (int) att.mSize;
                    uiAtt.uri = uiUri("uiattachment", att.mId);
                    uiAtts.add(uiAtt);
                }
                values.put(UIProvider.MessageColumns.ATTACHMENTS, "@?"); // @ for literal
                attachmentJson = com.android.mail.providers.Attachment.toJSONArray(uiAtts);
            }
            if (msg.mDraftInfo != 0) {
                values.put(UIProvider.MessageColumns.APPEND_REF_MESSAGE_CONTENT,
                        (msg.mDraftInfo & Message.DRAFT_INFO_APPEND_REF_MESSAGE) != 0 ? 1 : 0);
                values.put(UIProvider.MessageColumns.QUOTE_START_POS,
                        msg.mDraftInfo & Message.DRAFT_INFO_QUOTE_POS_MASK);
            }
            if ((msg.mFlags & Message.FLAG_INCOMING_MEETING_INVITE) != 0) {
                values.put(UIProvider.MessageColumns.EVENT_INTENT_URI,
                        "content://ui.email2.android.com/event/" + msg.mId);
            }
        }
        StringBuilder sb = genSelect(getMessageViewMap(), uiProjection, values);
        sb.append(" FROM " + Message.TABLE_NAME + " LEFT JOIN " + Body.TABLE_NAME + " ON " +
                Body.MESSAGE_KEY + "=" + Message.TABLE_NAME + "." + Message.RECORD_ID + " WHERE " +
                Message.TABLE_NAME + "." + Message.RECORD_ID + "=?");
        String sql = sb.toString();
        return new MessageQuery(sql, attachmentJson);
    }

    /**
     * Generate the "message list" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @param unseenOnly <code>true</code> to only return unseen messages
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private static String genQueryMailboxMessages(String[] uiProjection, final boolean unseenOnly) {
        StringBuilder sb = genSelect(getMessageListMap(), uiProjection);
        sb.append(" FROM " + Message.TABLE_NAME + " WHERE " +
                Message.FLAG_LOADED + "=" + Message.FLAG_LOADED_COMPLETE + " AND " +
                Message.MAILBOX_KEY + "=? ");
        if (unseenOnly) {
            sb.append("AND ").append(MessageColumns.FLAG_SEEN).append(" = 0 ");
        }
        sb.append("ORDER BY " + MessageColumns.TIMESTAMP + " DESC");
        return sb.toString();
    }

    /**
     * Generate various virtual mailbox SQLite queries, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @param mailboxId the id of the virtual mailbox
     * @param unseenOnly <code>true</code> to only return unseen messages
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private static Cursor getVirtualMailboxMessagesCursor(SQLiteDatabase db, String[] uiProjection,
            long mailboxId, final boolean unseenOnly) {
        ContentValues values = new ContentValues();
        values.put(UIProvider.ConversationColumns.COLOR, CONVERSATION_COLOR);
        final int virtualMailboxId = getVirtualMailboxType(mailboxId);
        final String[] selectionArgs;
        StringBuilder sb = genSelect(getMessageListMap(), uiProjection, values);
        sb.append(" FROM " + Message.TABLE_NAME + " WHERE " +
                Message.FLAG_LOADED + "=" + Message.FLAG_LOADED_COMPLETE + " AND ");
        if (isCombinedMailbox(mailboxId)) {
            if (unseenOnly) {
                sb.append(MessageColumns.FLAG_SEEN).append("=0 AND ");
            }
            selectionArgs = null;
        } else {
            if (virtualMailboxId == Mailbox.TYPE_INBOX) {
                throw new IllegalArgumentException("No virtual mailbox for: " + mailboxId);
            }
            sb.append(MessageColumns.ACCOUNT_KEY).append("=? AND ");
            selectionArgs = new String[]{getVirtualMailboxAccountIdString(mailboxId)};
        }
        switch (getVirtualMailboxType(mailboxId)) {
            case Mailbox.TYPE_INBOX:
                sb.append(MessageColumns.MAILBOX_KEY + " IN (SELECT " + MailboxColumns.ID +
                        " FROM " + Mailbox.TABLE_NAME + " WHERE " + MailboxColumns.TYPE +
                        "=" + Mailbox.TYPE_INBOX + ")");
                break;
            case Mailbox.TYPE_STARRED:
                sb.append(MessageColumns.FLAG_FAVORITE + "=1");
                break;
            case Mailbox.TYPE_ALL_UNREAD:
                sb.append(MessageColumns.FLAG_READ + "=0 AND " + MessageColumns.MAILBOX_KEY +
                        " NOT IN (SELECT " + MailboxColumns.ID + " FROM " + Mailbox.TABLE_NAME +
                        " WHERE " + MailboxColumns.TYPE + "=" + Mailbox.TYPE_TRASH + ")");
                break;
            default:
                throw new IllegalArgumentException("No virtual mailbox for: " + mailboxId);
        }
        sb.append(" ORDER BY " + MessageColumns.TIMESTAMP + " DESC");
        return db.rawQuery(sb.toString(), selectionArgs);
    }

    /**
     * Generate the "message list" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private static String genQueryConversation(String[] uiProjection) {
        StringBuilder sb = genSelect(getMessageListMap(), uiProjection);
        sb.append(" FROM " + Message.TABLE_NAME + " WHERE " + Message.RECORD_ID + "=?");
        return sb.toString();
    }

    /**
     * Generate the "top level folder list" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private static String genQueryAccountMailboxes(String[] uiProjection) {
        StringBuilder sb = genSelect(getFolderListMap(), uiProjection);
        sb.append(" FROM " + Mailbox.TABLE_NAME + " WHERE " + MailboxColumns.ACCOUNT_KEY +
                "=? AND " + MailboxColumns.TYPE + " < " + Mailbox.TYPE_NOT_EMAIL +
                " AND " + MailboxColumns.PARENT_KEY + " < 0 ORDER BY ");
        sb.append(MAILBOX_ORDER_BY);
        return sb.toString();
    }

    /**
     * Generate the "all folders" SQLite query, given a projection from UnifiedEmail.  The list is
     * sorted by the name as it appears in a hierarchical listing
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private static String genQueryAccountAllMailboxes(String[] uiProjection) {
        StringBuilder sb = genSelect(getFolderListMap(), uiProjection);
        // Use a derived column to choose either hierarchicalName or displayName
        sb.append(", case when " + MailboxColumns.HIERARCHICAL_NAME + " is null then " +
                MailboxColumns.DISPLAY_NAME + " else " + MailboxColumns.HIERARCHICAL_NAME +
                " end as h_name");
        // Order by the derived column
        sb.append(" FROM " + Mailbox.TABLE_NAME + " WHERE " + MailboxColumns.ACCOUNT_KEY +
                "=? AND " + MailboxColumns.TYPE + " < " + Mailbox.TYPE_NOT_EMAIL +
                " ORDER BY h_name");
        return sb.toString();
    }

    /**
     * Generate the "recent folder list" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private static String genQueryRecentMailboxes(String[] uiProjection) {
        StringBuilder sb = genSelect(getFolderListMap(), uiProjection);
        sb.append(" FROM " + Mailbox.TABLE_NAME + " WHERE " + MailboxColumns.ACCOUNT_KEY +
                "=? AND " + MailboxColumns.TYPE + " < " + Mailbox.TYPE_NOT_EMAIL +
                " AND " + MailboxColumns.PARENT_KEY + " < 0 AND " +
                MailboxColumns.LAST_TOUCHED_TIME + " > 0 ORDER BY " +
                MailboxColumns.LAST_TOUCHED_TIME + " DESC");
        return sb.toString();
    }

    private static int getFolderCapabilities(EmailServiceInfo info, int flags, int type,
            long mailboxId) {
        // All folders support delete
        int caps = UIProvider.FolderCapabilities.DELETE;
        if (info != null && info.offerLookback) {
            // Protocols supporting lookback support settings
            caps |= UIProvider.FolderCapabilities.SUPPORTS_SETTINGS;
        }
        if ((flags & Mailbox.FLAG_ACCEPTS_MOVED_MAIL) != 0) {
            // If the mailbox can accept moved mail, report that as well
            caps |= UIProvider.FolderCapabilities.CAN_ACCEPT_MOVED_MESSAGES;
            caps |= UIProvider.FolderCapabilities.ALLOWS_REMOVE_CONVERSATION;
        }

        // For trash, we don't allow undo
        if (type == Mailbox.TYPE_TRASH) {
            caps =  UIProvider.FolderCapabilities.CAN_ACCEPT_MOVED_MESSAGES |
                    UIProvider.FolderCapabilities.CAN_HOLD_MAIL |
                    UIProvider.FolderCapabilities.DELETE |
                    UIProvider.FolderCapabilities.DELETE_ACTION_FINAL;
        }
        if (isVirtualMailbox(mailboxId)) {
            caps |= UIProvider.FolderCapabilities.IS_VIRTUAL;
        }
        return caps;
    }

    /**
     * Generate a "single mailbox" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private String genQueryMailbox(String[] uiProjection, String id) {
        long mailboxId = Long.parseLong(id);
        ContentValues values = new ContentValues();
        if (mSearchParams != null && mailboxId == mSearchParams.mSearchMailboxId) {
            // This is the current search mailbox; use the total count
            values = new ContentValues();
            values.put(UIProvider.FolderColumns.TOTAL_COUNT, mSearchParams.mTotalCount);
            // "load more" is valid for search results
            values.put(UIProvider.FolderColumns.LOAD_MORE_URI,
                    uiUriString("uiloadmore", mailboxId));
            values.put(UIProvider.FolderColumns.CAPABILITIES, UIProvider.FolderCapabilities.DELETE);
        } else {
            Context context = getContext();
            Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
            // Make sure we can't get NPE if mailbox has disappeared (the result will end up moot)
            if (mailbox != null) {
                String protocol = Account.getProtocol(context, mailbox.mAccountKey);
                EmailServiceInfo info = EmailServiceUtils.getServiceInfo(context, protocol);
                // All folders support delete
                if (info != null && info.offerLoadMore) {
                    // "load more" is valid for protocols not supporting "lookback"
                    values.put(UIProvider.FolderColumns.LOAD_MORE_URI,
                            uiUriString("uiloadmore", mailboxId));
                }
                values.put(UIProvider.FolderColumns.CAPABILITIES,
                        getFolderCapabilities(info, mailbox.mFlags, mailbox.mType, mailboxId));
                // The persistent id is used to form a filename, so we must ensure that it doesn't
                // include illegal characters (such as '/'). Only perform the encoding if this
                // query wants the persistent id.
                boolean shouldEncodePersistentId = false;
                if (uiProjection == null) {
                    shouldEncodePersistentId = true;
                } else {
                    for (final String column : uiProjection) {
                        if (TextUtils.equals(column, UIProvider.FolderColumns.PERSISTENT_ID)) {
                            shouldEncodePersistentId = true;
                            break;
                        }
                    }
                }
                if (shouldEncodePersistentId) {
                    values.put(UIProvider.FolderColumns.PERSISTENT_ID,
                            Base64.encodeToString(mailbox.mServerId.getBytes(),
                                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
                }
             }
        }
        StringBuilder sb = genSelect(getFolderListMap(), uiProjection, values);
        sb.append(" FROM " + Mailbox.TABLE_NAME + " WHERE " + MailboxColumns.ID + "=?");
        return sb.toString();
    }

    public static final String LEGACY_AUTHORITY = "ui.email.android.com";
    private static final Uri BASE_EXTERNAL_URI = Uri.parse("content://" + LEGACY_AUTHORITY);

    private static final Uri BASE_EXTERAL_URI2 = Uri.parse("content://ui.email2.android.com");

    private static String getExternalUriString(String segment, String account) {
        return BASE_EXTERNAL_URI.buildUpon().appendPath(segment)
                .appendQueryParameter("account", account).build().toString();
    }

    private static String getExternalUriStringEmail2(String segment, String account) {
        return BASE_EXTERAL_URI2.buildUpon().appendPath(segment)
                .appendQueryParameter("account", account).build().toString();
    }

    private static String getBits(int bitField) {
        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < 32; i++, bitField >>= 1) {
            if ((bitField & 1) != 0) {
                sb.append("" + i + " ");
            }
        }
        return sb.toString();
    }

    private int getCapabilities(Context context, long accountId) {
        final EmailServiceProxy service = EmailServiceUtils.getServiceForAccount(context,
                mServiceCallback, accountId);
        int capabilities = 0;
        Account acct = null;
        try {
            service.setTimeout(10);
            acct = Account.restoreAccountWithId(context, accountId);
            if (acct == null) {
                Log.d(TAG, "getCapabilities() for " + accountId + ": returning 0x0 (no account)");
                return 0;
            }
            capabilities = service.getCapabilities(acct);
            // STOPSHIP
            Log.d(TAG, "getCapabilities() for " + acct.mDisplayName + ": 0x" +
                    Integer.toHexString(capabilities) + getBits(capabilities));
       } catch (RemoteException e) {
            // Nothing to do
           Log.w(TAG, "getCapabilities() for " + acct.mDisplayName + ": RemoteException");
        }

        // If the configuration states that feedback is supported, add that capability
        final Resources res = context.getResources();
        if (res.getBoolean(R.bool.feedback_supported)) {
            capabilities |= UIProvider.AccountCapabilities.SEND_FEEDBACK;
        }
        return capabilities;
    }

    /**
     * Generate a "single account" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private String genQueryAccount(String[] uiProjection, String id) {
        final ContentValues values = new ContentValues();
        final long accountId = Long.parseLong(id);
        final Context context = getContext();

        EmailServiceInfo info = null;

        // TODO: If uiProjection is null, this will NPE. We should do everything here if it's null.
        final Set<String> projectionColumns = ImmutableSet.copyOf(uiProjection);

        if (projectionColumns.contains(UIProvider.AccountColumns.CAPABILITIES)) {
            // Get account capabilities from the service
            values.put(UIProvider.AccountColumns.CAPABILITIES, getCapabilities(context, accountId));
        }
        if (projectionColumns.contains(UIProvider.AccountColumns.SETTINGS_INTENT_URI)) {
            values.put(UIProvider.AccountColumns.SETTINGS_INTENT_URI,
                    getExternalUriString("settings", id));
        }
        if (projectionColumns.contains(UIProvider.AccountColumns.COMPOSE_URI)) {
            values.put(UIProvider.AccountColumns.COMPOSE_URI,
                    getExternalUriStringEmail2("compose", id));
        }
        if (projectionColumns.contains(UIProvider.AccountColumns.MIME_TYPE)) {
            values.put(UIProvider.AccountColumns.MIME_TYPE, EMAIL_APP_MIME_TYPE);
        }
        if (projectionColumns.contains(UIProvider.AccountColumns.COLOR)) {
            values.put(UIProvider.AccountColumns.COLOR, ACCOUNT_COLOR);
        }

        final Preferences prefs = Preferences.getPreferences(getContext());
        final MailPrefs mailPrefs = MailPrefs.get(getContext());
        if (projectionColumns.contains(UIProvider.AccountColumns.SettingsColumns.CONFIRM_DELETE)) {
            values.put(UIProvider.AccountColumns.SettingsColumns.CONFIRM_DELETE,
                    prefs.getConfirmDelete() ? "1" : "0");
        }
        if (projectionColumns.contains(UIProvider.AccountColumns.SettingsColumns.CONFIRM_SEND)) {
            values.put(UIProvider.AccountColumns.SettingsColumns.CONFIRM_SEND,
                    prefs.getConfirmSend() ? "1" : "0");
        }
        if (projectionColumns.contains(UIProvider.AccountColumns.SettingsColumns.SWIPE)) {
            values.put(UIProvider.AccountColumns.SettingsColumns.SWIPE,
                    mailPrefs.getConversationListSwipeActionInteger(false));
        }
        if (projectionColumns.contains(
                UIProvider.AccountColumns.SettingsColumns.CONV_LIST_ICON)) {
            String convListIcon = prefs.getConversationListIcon();
            values.put(UIProvider.AccountColumns.SettingsColumns.CONV_LIST_ICON,
                    convListIconToUiValue(convListIcon));
        }
        if (projectionColumns.contains(UIProvider.AccountColumns.SettingsColumns.AUTO_ADVANCE)) {
            int autoAdvance = prefs.getAutoAdvanceDirection();
            values.put(UIProvider.AccountColumns.SettingsColumns.AUTO_ADVANCE,
                    autoAdvanceToUiValue(autoAdvance));
        }
        if (projectionColumns.contains(
                UIProvider.AccountColumns.SettingsColumns.MESSAGE_TEXT_SIZE)) {
            int textZoom = prefs.getTextZoom();
            values.put(UIProvider.AccountColumns.SettingsColumns.MESSAGE_TEXT_SIZE,
                    textZoomToUiValue(textZoom));
        }
        // Set default inbox, if we've got an inbox; otherwise, say initial sync needed
        long mailboxId = Mailbox.findMailboxOfType(context, accountId, Mailbox.TYPE_INBOX);
        if (projectionColumns.contains(UIProvider.AccountColumns.SettingsColumns.DEFAULT_INBOX) &&
                mailboxId != Mailbox.NO_MAILBOX) {
            values.put(UIProvider.AccountColumns.SettingsColumns.DEFAULT_INBOX,
                    uiUriString("uifolder", mailboxId));
        }
        if (projectionColumns.contains(
                UIProvider.AccountColumns.SettingsColumns.DEFAULT_INBOX_NAME) &&
                mailboxId != Mailbox.NO_MAILBOX) {
            values.put(UIProvider.AccountColumns.SettingsColumns.DEFAULT_INBOX_NAME,
                    Mailbox.getDisplayName(context, mailboxId));
        }
        if (projectionColumns.contains(UIProvider.AccountColumns.SYNC_STATUS)) {
            if (mailboxId != Mailbox.NO_MAILBOX) {
                values.put(UIProvider.AccountColumns.SYNC_STATUS, UIProvider.SyncStatus.NO_SYNC);
            } else {
                values.put(UIProvider.AccountColumns.SYNC_STATUS,
                        UIProvider.SyncStatus.INITIAL_SYNC_NEEDED);
            }
        }
        if (projectionColumns.contains(
                UIProvider.AccountColumns.SettingsColumns.PRIORITY_ARROWS_ENABLED)) {
            // Email doesn't support priority inbox, so always state priority arrows disabled.
            values.put(UIProvider.AccountColumns.SettingsColumns.PRIORITY_ARROWS_ENABLED, "0");
        }
        if (projectionColumns.contains(
                UIProvider.AccountColumns.SettingsColumns.SETUP_INTENT_URI)) {
            // Set the setup intent if needed
            // TODO We should clarify/document the trash/setup relationship
            long trashId = Mailbox.findMailboxOfType(context, accountId, Mailbox.TYPE_TRASH);
            if (trashId == Mailbox.NO_MAILBOX) {
                info = EmailServiceUtils.getServiceInfoForAccount(context, accountId);
                if (info != null && info.requiresSetup) {
                    values.put(UIProvider.AccountColumns.SettingsColumns.SETUP_INTENT_URI,
                            getExternalUriString("setup", id));
                }
            }
        }
        if (projectionColumns.contains(UIProvider.AccountColumns.TYPE)) {
            final String type;
            if (info == null) {
                info = EmailServiceUtils.getServiceInfoForAccount(context, accountId);
            }
            if (info != null) {
                type = info.accountType;
            } else {
                type = "unknown";
            }

            values.put(UIProvider.AccountColumns.TYPE, type);
        }

        final StringBuilder sb = genSelect(getAccountListMap(getContext()), uiProjection, values);
        sb.append(" FROM " + Account.TABLE_NAME + " WHERE " + AccountColumns.ID + "=?");
        return sb.toString();
    }

    private static int autoAdvanceToUiValue(int autoAdvance) {
        switch(autoAdvance) {
            case Preferences.AUTO_ADVANCE_OLDER:
                return UIProvider.AutoAdvance.OLDER;
            case Preferences.AUTO_ADVANCE_NEWER:
                return UIProvider.AutoAdvance.NEWER;
            case Preferences.AUTO_ADVANCE_MESSAGE_LIST:
            default:
                return UIProvider.AutoAdvance.LIST;
        }
    }

    private static int textZoomToUiValue(int textZoom) {
        switch(textZoom) {
            case Preferences.TEXT_ZOOM_HUGE:
                return UIProvider.MessageTextSize.HUGE;
            case Preferences.TEXT_ZOOM_LARGE:
                return UIProvider.MessageTextSize.LARGE;
            case Preferences.TEXT_ZOOM_NORMAL:
                return UIProvider.MessageTextSize.NORMAL;
            case Preferences.TEXT_ZOOM_SMALL:
                return UIProvider.MessageTextSize.SMALL;
            case Preferences.TEXT_ZOOM_TINY:
                return UIProvider.MessageTextSize.TINY;
            default:
                return UIProvider.MessageTextSize.NORMAL;
        }
    }

    private static int convListIconToUiValue(String convListIcon) {
        if (Preferences.CONV_LIST_ICON_SENDER_IMAGE.equals(convListIcon)) {
            return UIProvider.ConversationListIcon.SENDER_IMAGE;
        } else if (Preferences.CONV_LIST_ICON_NONE.equals(convListIcon)) {
            return UIProvider.ConversationListIcon.NONE;
        } else {
            return UIProvider.ConversationListIcon.DEFAULT;
        }
    }

    /**
     * Generate a Uri string for a combined mailbox uri
     * @param type the uri command type (e.g. "uimessages")
     * @param id the id of the item (e.g. an account, mailbox, or message id)
     * @return a Uri string
     */
    private static String combinedUriString(String type, String id) {
        return "content://" + EmailContent.AUTHORITY + "/" + type + "/" + id;
    }

    public static final long COMBINED_ACCOUNT_ID = 0x10000000;

    /**
     * Generate an id for a combined mailbox of a given type
     * @param type the mailbox type for the combined mailbox
     * @return the id, as a String
     */
    private static String combinedMailboxId(int type) {
        return Long.toString(Account.ACCOUNT_ID_COMBINED_VIEW + type);
    }

    private static String getVirtualMailboxIdString(long accountId, int type) {
        return Long.toString(getVirtualMailboxId(accountId, type));
    }

    public static long getVirtualMailboxId(long accountId, int type) {
        return (accountId << 32) + type;
    }

    private static boolean isVirtualMailbox(long mailboxId) {
        return mailboxId >= 0x100000000L;
    }

    private static boolean isCombinedMailbox(long mailboxId) {
        return (mailboxId >> 32) == COMBINED_ACCOUNT_ID;
    }

    private static long getVirtualMailboxAccountId(long mailboxId) {
        return mailboxId >> 32;
    }

    private static String getVirtualMailboxAccountIdString(long mailboxId) {
        return Long.toString(mailboxId >> 32);
    }

    private static int getVirtualMailboxType(long mailboxId) {
        return (int)(mailboxId & 0xF);
    }

    private void addCombinedAccountRow(MatrixCursor mc) {
        final long id = Account.getDefaultAccountId(getContext());
        if (id == Account.NO_ACCOUNT) return;

        // Build a map of the requested columns to the appropriate positions
        final ImmutableMap.Builder<String, Integer> builder =
                new ImmutableMap.Builder<String, Integer>();
        final String[] columnNames = mc.getColumnNames();
        for (int i = 0; i < columnNames.length; i++) {
            builder.put(columnNames[i], i);
        }
        final Map<String, Integer> colPosMap = builder.build();

        final Object[] values = new Object[columnNames.length];
        if (colPosMap.containsKey(BaseColumns._ID)) {
            values[colPosMap.get(BaseColumns._ID)] = 0;
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.CAPABILITIES)) {
            values[colPosMap.get(UIProvider.AccountColumns.CAPABILITIES)] =
                    AccountCapabilities.UNDO | AccountCapabilities.SENDING_UNAVAILABLE;
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.FOLDER_LIST_URI)) {
            values[colPosMap.get(UIProvider.AccountColumns.FOLDER_LIST_URI)] =
                    combinedUriString("uifolders", COMBINED_ACCOUNT_ID_STRING);
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.NAME)) {
            values[colPosMap.get(UIProvider.AccountColumns.NAME)] = getContext().getString(
                R.string.mailbox_list_account_selector_combined_view);
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.TYPE)) {
            values[colPosMap.get(UIProvider.AccountColumns.TYPE)] = "unknown";
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.UNDO_URI)) {
            values[colPosMap.get(UIProvider.AccountColumns.UNDO_URI)] =
                    "'content://" + EmailContent.AUTHORITY + "/uiundo'";
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.URI)) {
            values[colPosMap.get(UIProvider.AccountColumns.URI)] =
                    combinedUriString("uiaccount", COMBINED_ACCOUNT_ID_STRING);
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.MIME_TYPE)) {
            values[colPosMap.get(UIProvider.AccountColumns.MIME_TYPE)] =
                    EMAIL_APP_MIME_TYPE;
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.SETTINGS_INTENT_URI)) {
            values[colPosMap.get(UIProvider.AccountColumns.SETTINGS_INTENT_URI)] =
                    getExternalUriString("settings", COMBINED_ACCOUNT_ID_STRING);
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.COMPOSE_URI)) {
            values[colPosMap.get(UIProvider.AccountColumns.COMPOSE_URI)] =
                    getExternalUriStringEmail2("compose", Long.toString(id));
        }

        // TODO: Get these from default account?
        Preferences prefs = Preferences.getPreferences(getContext());
        if (colPosMap.containsKey(UIProvider.AccountColumns.SettingsColumns.AUTO_ADVANCE)) {
            values[colPosMap.get(UIProvider.AccountColumns.SettingsColumns.AUTO_ADVANCE)] =
                    Integer.toString(UIProvider.AutoAdvance.NEWER);
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.SettingsColumns.MESSAGE_TEXT_SIZE)) {
            values[colPosMap.get(UIProvider.AccountColumns.SettingsColumns.MESSAGE_TEXT_SIZE)] =
                    Integer.toString(UIProvider.MessageTextSize.NORMAL);
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.SettingsColumns.SNAP_HEADERS)) {
            values[colPosMap.get(UIProvider.AccountColumns.SettingsColumns.SNAP_HEADERS)] =
                    Integer.toString(UIProvider.SnapHeaderValue.ALWAYS);
        }
        //.add(UIProvider.SettingsColumns.SIGNATURE, AccountColumns.SIGNATURE)
        if (colPosMap.containsKey(UIProvider.AccountColumns.SettingsColumns.REPLY_BEHAVIOR)) {
            values[colPosMap.get(UIProvider.AccountColumns.SettingsColumns.REPLY_BEHAVIOR)] =
                    Integer.toString(UIProvider.DefaultReplyBehavior.REPLY);
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.SettingsColumns.CONV_LIST_ICON)) {
            values[colPosMap.get(UIProvider.AccountColumns.SettingsColumns.CONV_LIST_ICON)] =
                    convListIconToUiValue(prefs.getConversationListIcon());
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.SettingsColumns.CONFIRM_DELETE)) {
            values[colPosMap.get(UIProvider.AccountColumns.SettingsColumns.CONFIRM_DELETE)] =
                    prefs.getConfirmDelete() ? 1 : 0;
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.SettingsColumns.CONFIRM_ARCHIVE)) {
            values[colPosMap.get(
                    UIProvider.AccountColumns.SettingsColumns.CONFIRM_ARCHIVE)] = 0;
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.SettingsColumns.CONFIRM_SEND)) {
            values[colPosMap.get(UIProvider.AccountColumns.SettingsColumns.CONFIRM_SEND)] =
                    prefs.getConfirmSend() ? 1 : 0;
        }
        if (colPosMap.containsKey(UIProvider.AccountColumns.SettingsColumns.DEFAULT_INBOX)) {
            values[colPosMap.get(UIProvider.AccountColumns.SettingsColumns.DEFAULT_INBOX)] =
                    combinedUriString("uifolder", combinedMailboxId(Mailbox.TYPE_INBOX));
        }

        mc.addRow(values);
    }

    private Cursor getVirtualMailboxCursor(long mailboxId) {
        MatrixCursor mc = new MatrixCursorWithCachedColumns(UIProvider.FOLDERS_PROJECTION, 1);
        mc.addRow(getVirtualMailboxRow(getVirtualMailboxAccountId(mailboxId),
                getVirtualMailboxType(mailboxId)));
        return mc;
    }

    private Object[] getVirtualMailboxRow(long accountId, int mailboxType) {
        String idString = getVirtualMailboxIdString(accountId, mailboxType);
        Object[] values = new Object[UIProvider.FOLDERS_PROJECTION.length];
        values[UIProvider.FOLDER_ID_COLUMN] = 0;
        values[UIProvider.FOLDER_URI_COLUMN] = combinedUriString("uifolder", idString);
        values[UIProvider.FOLDER_NAME_COLUMN] =
                Mailbox.getSystemMailboxName(getContext(), mailboxType);
        values[UIProvider.FOLDER_HAS_CHILDREN_COLUMN] = 0;
        values[UIProvider.FOLDER_CAPABILITIES_COLUMN] = UIProvider.FolderCapabilities.IS_VIRTUAL;
        values[UIProvider.FOLDER_CONVERSATION_LIST_URI_COLUMN] = combinedUriString("uimessages",
                idString);
        values[UIProvider.FOLDER_ID_COLUMN] = 0;
        return values;
    }

    private Cursor uiAccounts(String[] uiProjection) {
        final Context context = getContext();
        final SQLiteDatabase db = getDatabase(context);
        final Cursor accountIdCursor =
                db.rawQuery("select _id from " + Account.TABLE_NAME, new String[0]);
        final MatrixCursor mc;
        try {
            int numAccounts = accountIdCursor.getCount();
            boolean combinedAccount = false;
            if (numAccounts > 1) {
                combinedAccount = true;
                numAccounts++;
            }
            final Bundle extras = new Bundle();
            // Email always returns the accurate number of accounts
            extras.putInt(AccountCursorExtraKeys.ACCOUNTS_LOADED, 1);
            mc = new MatrixCursorWithExtra(uiProjection, accountIdCursor.getCount(), extras);
            final Object[] values = new Object[uiProjection.length];
            while (accountIdCursor.moveToNext()) {
                final String id = accountIdCursor.getString(0);
                final Cursor accountCursor =
                        db.rawQuery(genQueryAccount(uiProjection, id), new String[] {id});
                try {
                    if (accountCursor.moveToNext()) {
                        for (int i = 0; i < uiProjection.length; i++) {
                            values[i] = accountCursor.getString(i);
                        }
                        mc.addRow(values);
                    }
                } finally {
                    accountCursor.close();
                }
            }
            if (combinedAccount) {
                addCombinedAccountRow(mc);
            }
        } finally {
            accountIdCursor.close();
        }
        mc.setNotificationUri(context.getContentResolver(), UIPROVIDER_ALL_ACCOUNTS_NOTIFIER);

        return mc;
    }

    /**
     * Generate the "attachment list" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @param contentTypeQueryParameters list of mimeTypes, used as a filter for the attachments
     * or null if there are no query parameters
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private static String genQueryAttachments(String[] uiProjection,
            List<String> contentTypeQueryParameters) {
        StringBuilder sb = genSelect(getAttachmentMap(), uiProjection);
        sb.append(" FROM " + Attachment.TABLE_NAME + " WHERE " + AttachmentColumns.MESSAGE_KEY +
                " =? ");

        // Filter for certain content types.
        // The filter works by adding LIKE operators for each
        // content type you wish to request. Content types
        // are filtered by performing a case-insensitive "starts with"
        // filter. IE, "image/" would return "image/png" as well as "image/jpeg".
        if (contentTypeQueryParameters != null && !contentTypeQueryParameters.isEmpty()) {
            final int size = contentTypeQueryParameters.size();
            sb.append("AND (");
            for (int i = 0; i < size; i++) {
                final String contentType = contentTypeQueryParameters.get(i);
                sb.append(AttachmentColumns.MIME_TYPE + " LIKE '" + contentType + "%'");

                if (i != size - 1) {
                    sb.append(" OR ");
                }
            }
            sb.append(")");
        }
        return sb.toString();
    }

    /**
     * Generate the "single attachment" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private String genQueryAttachment(String[] uiProjection, String idString) {
        Long id = Long.parseLong(idString);
        Attachment att = Attachment.restoreAttachmentWithId(getContext(), id);
        ContentValues values = new ContentValues();
        values.put(AttachmentColumns.CONTENT_URI,
                AttachmentUtilities.getAttachmentUri(att.mAccountKey, id).toString());
        StringBuilder sb = genSelect(getAttachmentMap(), uiProjection, values);
        sb.append(" FROM " + Attachment.TABLE_NAME + " WHERE " + AttachmentColumns.ID + " =? ");
        return sb.toString();
    }

    /**
     * Generate the "subfolder list" SQLite query, given a projection from UnifiedEmail
     *
     * @param uiProjection as passed from UnifiedEmail
     * @return the SQLite query to be executed on the EmailProvider database
     */
    private static String genQuerySubfolders(String[] uiProjection) {
        StringBuilder sb = genSelect(getFolderListMap(), uiProjection);
        sb.append(" FROM " + Mailbox.TABLE_NAME + " WHERE " + MailboxColumns.PARENT_KEY +
                " =? ORDER BY ");
        sb.append(MAILBOX_ORDER_BY);
        return sb.toString();
    }

    private static final String COMBINED_ACCOUNT_ID_STRING = Long.toString(COMBINED_ACCOUNT_ID);

    /**
     * Returns a cursor over all the folders for a specific URI which corresponds to a single
     * account.
     * @param uri
     * @param uiProjection
     * @return
     */
    private Cursor uiFolders(Uri uri, String[] uiProjection) {
        Context context = getContext();
        SQLiteDatabase db = getDatabase(context);
        String id = uri.getPathSegments().get(1);
        if (id.equals(COMBINED_ACCOUNT_ID_STRING)) {
            MatrixCursor mc = new MatrixCursorWithCachedColumns(UIProvider.FOLDERS_PROJECTION, 3);
            int count;
            Object[] row;
            count = EmailContent.count(context, Message.CONTENT_URI,
                    MessageColumns.MAILBOX_KEY + " IN (SELECT " + MailboxColumns.ID +
                   " FROM " + Mailbox.TABLE_NAME + " WHERE " + MailboxColumns.TYPE +
                   "=" + Mailbox.TYPE_INBOX + ") AND " + MessageColumns.FLAG_READ + "=0", null);
            row = getVirtualMailboxRow(COMBINED_ACCOUNT_ID, Mailbox.TYPE_INBOX);
            row[UIProvider.FOLDER_UNREAD_COUNT_COLUMN] = count;
            row[UIProvider.FOLDER_ICON_RES_ID_COLUMN] = R.drawable.ic_folder_inbox_holo_light;
            mc.addRow(row);
            count = EmailContent.count(context, Message.CONTENT_URI,
                    MessageColumns.FLAG_FAVORITE + "=1", null);
            row = getVirtualMailboxRow(COMBINED_ACCOUNT_ID, Mailbox.TYPE_STARRED);
            row[UIProvider.FOLDER_UNREAD_COUNT_COLUMN] = count;
            row[UIProvider.FOLDER_ICON_RES_ID_COLUMN] = R.drawable.ic_menu_star_holo_light;
            mc.addRow(row);
            row = getVirtualMailboxRow(COMBINED_ACCOUNT_ID, Mailbox.TYPE_ALL_UNREAD);
            count = EmailContent.count(context, Message.CONTENT_URI,
                    MessageColumns.FLAG_READ + "=0 AND " + MessageColumns.MAILBOX_KEY +
                    " NOT IN (SELECT " + MailboxColumns.ID + " FROM " + Mailbox.TABLE_NAME +
                    " WHERE " + MailboxColumns.TYPE + "=" + Mailbox.TYPE_TRASH + ")", null);
            row[UIProvider.FOLDER_UNREAD_COUNT_COLUMN] = count;
            // TODO: Hijacking the mark unread icon for now.
            row[UIProvider.FOLDER_ICON_RES_ID_COLUMN] = R.drawable.ic_menu_mark_unread_holo_light;
            mc.addRow(row);
            return mc;
        } else {
            Cursor c = db.rawQuery(genQueryAccountMailboxes(uiProjection), new String[] {id});
            c = getFolderListCursor(db, c, uiProjection);
            int numStarred = EmailContent.count(context, Message.CONTENT_URI,
                    MessageColumns.ACCOUNT_KEY + "=? AND " + MessageColumns.FLAG_FAVORITE + "=1",
                    new String[] {id});
            // Add starred virtual folder to the cursor
            // Show number of messages as unread count (for backward compatibility)
            MatrixCursor mc = new MatrixCursorWithCachedColumns(uiProjection, 2);
            final long acctId = Long.parseLong(id);
            Object[] row = getVirtualMailboxRow(acctId, Mailbox.TYPE_STARRED);
            row[UIProvider.FOLDER_UNREAD_COUNT_COLUMN] = numStarred;
            row[UIProvider.FOLDER_ICON_RES_ID_COLUMN] = R.drawable.ic_menu_star_holo_light;
            mc.addRow(row);
            row = getVirtualMailboxRow(acctId, Mailbox.TYPE_ALL_UNREAD);
            int numUnread  = EmailContent.count(context, Message.CONTENT_URI,
                    MessageColumns.ACCOUNT_KEY + "=" + id + " AND " +
                    MessageColumns.FLAG_READ + "=0 AND " + MessageColumns.MAILBOX_KEY +
                    " NOT IN (SELECT " + MailboxColumns.ID + " FROM " + Mailbox.TABLE_NAME +
                    " WHERE " + MailboxColumns.TYPE + "=" + Mailbox.TYPE_TRASH + ")", null);
            row[UIProvider.FOLDER_UNREAD_COUNT_COLUMN] = numUnread;
            // TODO: Hijacking the mark unread icon for now.
            row[UIProvider.FOLDER_ICON_RES_ID_COLUMN] = R.drawable.ic_menu_mark_unread_holo_light;
            mc.addRow(row);
            Cursor[] cursors = new Cursor[] {mc, c};
            return new MergeCursor(cursors);
        }
    }

    /**
     * Returns an array of the default recent folders for a given URI which is unique for an
     * account. Some accounts might not have default recent folders, in which case an empty array
     * is returned.
     * @param id
     * @return
     */
    private Uri[] defaultRecentFolders(final String id) {
        final SQLiteDatabase db = getDatabase(getContext());
        if (id.equals(COMBINED_ACCOUNT_ID_STRING)) {
            // We don't have default recents for the combined view.
            return new Uri[0];
        }
        // We search for the types we want, and find corresponding IDs.
        final String[] idAndType = { BaseColumns._ID, UIProvider.FolderColumns.TYPE };

        // Sent, Drafts, and Starred are the default recents.
        final StringBuilder sb = genSelect(getFolderListMap(), idAndType);
        sb.append(" FROM " + Mailbox.TABLE_NAME
                + " WHERE " + MailboxColumns.ACCOUNT_KEY + " = " + id
                + " AND "
                + MailboxColumns.TYPE + " IN (" + Mailbox.TYPE_SENT +
                    ", " + Mailbox.TYPE_DRAFTS +
                    ", " + Mailbox.TYPE_STARRED
                + ")");
        LogUtils.d(TAG, "defaultRecentFolders: Query is %s", sb);
        final Cursor c = db.rawQuery(sb.toString(), null);
        if (c == null || c.getCount() <= 0 || !c.moveToFirst()) {
            return new Uri[0];
        }
        // Read all the IDs of the mailboxes, and turn them into URIs.
        final Uri[] recentFolders = new Uri[c.getCount()];
        int i = 0;
        do {
            final long folderId = c.getLong(0);
            recentFolders[i] = uiUri("uifolder", folderId);
            LogUtils.d(TAG, "Default recent folder: %d, with uri %s", folderId, recentFolders[i]);
            ++i;
        } while (c.moveToNext());
        return recentFolders;
    }

    /**
     * Wrapper that handles the visibility feature (i.e. the conversation list is visible, so
     * any pending notifications for the corresponding mailbox should be canceled). We also handle
     * getExtras() to provide a snapshot of the mailbox's status
     */
    static class VisibilityCursor extends CursorWrapper {
        private final long mMailboxId;
        private final Context mContext;
        private final Bundle mExtras = new Bundle();

        public VisibilityCursor(Context context, Cursor cursor, long mailboxId) {
            super(cursor);
            mMailboxId = mailboxId;
            mContext = context;
            Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
            if (mailbox != null) {
                mExtras.putInt(UIProvider.CursorExtraKeys.EXTRA_STATUS, mailbox.mUiSyncStatus);
                if (mailbox.mUiLastSyncResult != UIProvider.LastSyncResult.SUCCESS) {
                    mExtras.putInt(UIProvider.CursorExtraKeys.EXTRA_ERROR,
                            mailbox.mUiLastSyncResult);
                }
                mExtras.putInt(UIProvider.CursorExtraKeys.EXTRA_TOTAL_COUNT, mailbox.mTotalCount);
            }
        }

        @Override
        public Bundle getExtras() {
            return mExtras;
        }

        /**
         * When showing a folder, if it's been at least this long since the last sync,
         * force a folder refresh.
         */
        private static final long AUTO_REFRESH_INTERVAL_MS = 5 * DateUtils.MINUTE_IN_MILLIS;

        @Override
        public Bundle respond(Bundle params) {
            final String setVisibilityKey =
                    UIProvider.ConversationCursorCommand.COMMAND_KEY_SET_VISIBILITY;
            if (params.containsKey(setVisibilityKey)) {
                final boolean visible = params.getBoolean(setVisibilityKey);
                if (visible) {
                    NotificationController.getInstance(mContext).cancelNewMessageNotification(
                            mMailboxId);
                    if (params.containsKey(
                            UIProvider.ConversationCursorCommand.COMMAND_KEY_ENTERED_FOLDER)) {
                        Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, mMailboxId);
                        if (mailbox != null) {
                            final ContentResolver resolver = mContext.getContentResolver();
                            // Mark all messages as seen
                            // TODO: should this happen even if the mailbox couldn't be restored?
                            final ContentValues contentValues = new ContentValues(1);
                            contentValues.put(MessageColumns.FLAG_SEEN, true);
                            final Uri uri = EmailContent.Message.CONTENT_URI;
                            resolver.update(uri, contentValues, MessageColumns.MAILBOX_KEY + " = ?",
                                    new String[] {String.valueOf(mailbox.mId)});
                            // If it's been long enough, force sync this mailbox.
                            final long timeSinceLastSync =
                                    System.currentTimeMillis() - mailbox.mSyncTime;
                            if (timeSinceLastSync > AUTO_REFRESH_INTERVAL_MS) {
                                final Uri refreshUri = Uri.parse(EmailContent.CONTENT_URI + "/" +
                                        QUERY_UIREFRESH + "/" + mailbox.mId);
                                resolver.query(refreshUri, null, null, null, null);
                            }
                        }
                    }
                }
            }
            // Return success
            Bundle response = new Bundle();
            response.putString(setVisibilityKey,
                    UIProvider.ConversationCursorCommand.COMMAND_RESPONSE_OK);
            return response;
        }
    }

    static class AttachmentsCursor extends CursorWrapper {
        private final int mContentUriIndex;
        private final int mUriIndex;
        private final Context mContext;

        public AttachmentsCursor(Context context, Cursor cursor) {
            super(cursor);
            mContentUriIndex = cursor.getColumnIndex(UIProvider.AttachmentColumns.CONTENT_URI);
            mUriIndex = cursor.getColumnIndex(UIProvider.AttachmentColumns.URI);
            mContext = context;
        }

        @Override
        public String getString(int column) {
            if (column == mContentUriIndex) {
                final Uri uri = Uri.parse(getString(mUriIndex));
                final long id = Long.parseLong(uri.getLastPathSegment());
                final Attachment att = Attachment.restoreAttachmentWithId(mContext, id);
                if (att == null) return "";

                final String contentUri;
                // Until the package installer can handle opening apks from a content:// uri, for
                // any apk that was successfully saved in external storage, return the
                // content uri from the attachment
                if (att.mUiDestination == UIProvider.AttachmentDestination.EXTERNAL &&
                        att.mUiState == UIProvider.AttachmentState.SAVED &&
                        TextUtils.equals(att.mMimeType, MimeType.ANDROID_ARCHIVE)) {
                    contentUri = att.getContentUri();
                } else {
                    contentUri =
                            AttachmentUtilities.getAttachmentUri(att.mAccountKey, id).toString();
                }
                return contentUri;
            } else {
                return super.getString(column);
            }
        }
    }

    /**
     * For debugging purposes; shouldn't be used in production code
     */
    static class CloseDetectingCursor extends CursorWrapper {

        public CloseDetectingCursor(Cursor cursor) {
            super(cursor);
        }

        @Override
        public void close() {
            super.close();
            Log.d(TAG, "Closing cursor", new Error());
        }
    }

    /**
     * We need to do individual queries for the mailboxes in order to get correct
     * folder capabilities.
     */
    private Cursor getFolderListCursor(SQLiteDatabase db, Cursor c, String[] uiProjection) {
        final MatrixCursor mc = new MatrixCursorWithCachedColumns(uiProjection);
        Object[] values = new Object[uiProjection.length];
        String[] args = new String[1];
        try {
            // Loop through mailboxes, building matrix cursor
            while (c.moveToNext()) {
                String id = c.getString(0);
                args[0] = id;
                Cursor mailboxCursor = db.rawQuery(genQueryMailbox(uiProjection, id), args);
                if (mailboxCursor.moveToNext()) {
                    for (int i = 0; i < uiProjection.length; i++) {
                        values[i] = mailboxCursor.getString(i);
                    }
                    mc.addRow(values);
                }
            }
        } finally {
            c.close();
        }
       return mc;
    }

    /**
     * Handle UnifiedEmail queries here (dispatched from query())
     *
     * @param match the UriMatcher match for the original uri passed in from UnifiedEmail
     * @param uri the original uri passed in from UnifiedEmail
     * @param uiProjection the projection passed in from UnifiedEmail
     * @param unseenOnly <code>true</code> to only return unseen messages (where supported)
     * @return the result Cursor
     */
    private Cursor uiQuery(int match, Uri uri, String[] uiProjection, final boolean unseenOnly) {
        Context context = getContext();
        ContentResolver resolver = context.getContentResolver();
        SQLiteDatabase db = getDatabase(context);
        // Should we ever return null, or throw an exception??
        Cursor c = null;
        String id = uri.getPathSegments().get(1);
        Uri notifyUri = null;
        switch(match) {
            case UI_ALL_FOLDERS:
                c = db.rawQuery(genQueryAccountAllMailboxes(uiProjection), new String[] {id});
                c = getFolderListCursor(db, c, uiProjection);
                break;
            case UI_RECENT_FOLDERS:
                c = db.rawQuery(genQueryRecentMailboxes(uiProjection), new String[] {id});
                notifyUri = UIPROVIDER_RECENT_FOLDERS_NOTIFIER.buildUpon().appendPath(id).build();
                break;
            case UI_SUBFOLDERS:
                c = db.rawQuery(genQuerySubfolders(uiProjection), new String[] {id});
                c = getFolderListCursor(db, c, uiProjection);
                break;
            case UI_MESSAGES:
                long mailboxId = Long.parseLong(id);
                if (isVirtualMailbox(mailboxId)) {
                    c = getVirtualMailboxMessagesCursor(db, uiProjection, mailboxId, unseenOnly);
                } else {
                    c = db.rawQuery(
                            genQueryMailboxMessages(uiProjection, unseenOnly), new String[] {id});
                }
                notifyUri = UIPROVIDER_CONVERSATION_NOTIFIER.buildUpon().appendPath(id).build();
                c = new VisibilityCursor(context, c, mailboxId);
                break;
            case UI_MESSAGE:
                MessageQuery qq = genQueryViewMessage(uiProjection, id);
                String sql = qq.query;
                String attJson = qq.attachmentJson;
                // With attachments, we have another argument to bind
                if (attJson != null) {
                    c = db.rawQuery(sql, new String[] {attJson, id});
                } else {
                    c = db.rawQuery(sql, new String[] {id});
                }
                break;
            case UI_ATTACHMENTS:
                final List<String> contentTypeQueryParameters =
                        uri.getQueryParameters(PhotoContract.ContentTypeParameters.CONTENT_TYPE);
                c = db.rawQuery(genQueryAttachments(uiProjection, contentTypeQueryParameters),
                        new String[] {id});
                c = new AttachmentsCursor(context, c);
                notifyUri = UIPROVIDER_ATTACHMENTS_NOTIFIER.buildUpon().appendPath(id).build();
                break;
            case UI_ATTACHMENT:
                c = db.rawQuery(genQueryAttachment(uiProjection, id), new String[] {id});
                notifyUri = UIPROVIDER_ATTACHMENT_NOTIFIER.buildUpon().appendPath(id).build();
                break;
            case UI_FOLDER:
                mailboxId = Long.parseLong(id);
                if (isVirtualMailbox(mailboxId)) {
                    c = getVirtualMailboxCursor(mailboxId);
                } else {
                    c = db.rawQuery(genQueryMailbox(uiProjection, id), new String[] {id});
                    notifyUri = UIPROVIDER_FOLDER_NOTIFIER.buildUpon().appendPath(id).build();
                }
                break;
            case UI_ACCOUNT:
                if (id.equals(COMBINED_ACCOUNT_ID_STRING)) {
                    MatrixCursor mc = new MatrixCursorWithCachedColumns(uiProjection, 1);
                    addCombinedAccountRow(mc);
                    c = mc;
                } else {
                    c = db.rawQuery(genQueryAccount(uiProjection, id), new String[] {id});
                }
                notifyUri = UIPROVIDER_ACCOUNT_NOTIFIER.buildUpon().appendPath(id).build();
                break;
            case UI_CONVERSATION:
                c = db.rawQuery(genQueryConversation(uiProjection), new String[] {id});
                break;
        }
        if (notifyUri != null) {
            c.setNotificationUri(resolver, notifyUri);
        }
        return c;
    }

    /**
     * Convert a UIProvider attachment to an EmailProvider attachment (for sending); we only need
     * a few of the fields
     * @param uiAtt the UIProvider attachment to convert
     * @param cachedFile the path to the cached file to
     * @return the EmailProvider attachment
     */
    // TODO(pwestbro): once the Attachment contains the cached uri, the second parameter can be
    // removed
    private static Attachment convertUiAttachmentToAttachment(
            com.android.mail.providers.Attachment uiAtt, String cachedFile) {
        final Attachment att = new Attachment();
        att.setContentUri(uiAtt.contentUri.toString());

        if (!TextUtils.isEmpty(cachedFile)) {
            // Generate the content provider uri for this cached file
            final Uri.Builder cachedFileBuilder = Uri.parse(
                    "content://" + EmailContent.AUTHORITY + "/attachment/cachedFile").buildUpon();
            cachedFileBuilder.appendQueryParameter(Attachment.CACHED_FILE_QUERY_PARAM, cachedFile);
            att.setCachedFileUri(cachedFileBuilder.build().toString());
        }

        att.mFileName = uiAtt.getName();
        att.mMimeType = uiAtt.getContentType();
        att.mSize = uiAtt.size;
        return att;
    }

    /**
     * Create a mailbox given the account and mailboxType.
     */
    private Mailbox createMailbox(long accountId, int mailboxType) {
        Context context = getContext();
        Mailbox box = Mailbox.newSystemMailbox(context, accountId, mailboxType);
        // Make sure drafts and save will show up in recents...
        // If these already exist (from old Email app), they will have touch times
        switch (mailboxType) {
            case Mailbox.TYPE_DRAFTS:
                box.mLastTouchedTime = Mailbox.DRAFTS_DEFAULT_TOUCH_TIME;
                break;
            case Mailbox.TYPE_SENT:
                box.mLastTouchedTime = Mailbox.SENT_DEFAULT_TOUCH_TIME;
                break;
        }
        box.save(context);
        return box;
    }

    /**
     * Given an account name and a mailbox type, return that mailbox, creating it if necessary
     * @param accountId the account id to use
     * @param mailboxType the type of mailbox we're trying to find
     * @return the mailbox of the given type for the account in the uri, or null if not found
     */
    private Mailbox getMailboxByAccountIdAndType(final long accountId, final int mailboxType) {
        Mailbox mailbox = Mailbox.restoreMailboxOfType(getContext(), accountId, mailboxType);
        if (mailbox == null) {
            mailbox = createMailbox(accountId, mailboxType);
        }
        return mailbox;
    }

    /**
     * Given a mailbox and the content values for a message, create/save the message in the mailbox
     * @param mailbox the mailbox to use
     * @param extras the bundle containing the message fields
     * @return the uri of the newly created message
     * TODO(yph): The following fields are available in extras but unused, verify whether they
     *     should be respected:
     *     - UIProvider.MessageColumns.SNIPPET
     *     - UIProvider.MessageColumns.REPLY_TO
     *     - UIProvider.MessageColumns.FROM
     *     - UIProvider.MessageColumns.CUSTOM_FROM_ADDRESS
     */
    private Uri uiSaveMessage(Message msg, Mailbox mailbox, Bundle extras) {
        final Context context = getContext();
        // Fill in the message
        final Account account = Account.restoreAccountWithId(context, mailbox.mAccountKey);
        if (account == null) return null;
        msg.mFrom = account.mEmailAddress;
        msg.mTimeStamp = System.currentTimeMillis();
        msg.mTo = extras.getString(UIProvider.MessageColumns.TO);
        msg.mCc = extras.getString(UIProvider.MessageColumns.CC);
        msg.mBcc = extras.getString(UIProvider.MessageColumns.BCC);
        msg.mSubject = extras.getString(UIProvider.MessageColumns.SUBJECT);
        msg.mText = extras.getString(UIProvider.MessageColumns.BODY_TEXT);
        msg.mHtml = extras.getString(UIProvider.MessageColumns.BODY_HTML);
        msg.mMailboxKey = mailbox.mId;
        msg.mAccountKey = mailbox.mAccountKey;
        msg.mDisplayName = msg.mTo;
        msg.mFlagLoaded = Message.FLAG_LOADED_COMPLETE;
        msg.mFlagRead = true;
        msg.mFlagSeen = true;
        final Integer quoteStartPos = extras.getInt(UIProvider.MessageColumns.QUOTE_START_POS);
        msg.mQuotedTextStartPos = quoteStartPos == null ? 0 : quoteStartPos;
        int flags = 0;
        final int draftType = extras.getInt(UIProvider.MessageColumns.DRAFT_TYPE);
        switch(draftType) {
            case DraftType.FORWARD:
                flags |= Message.FLAG_TYPE_FORWARD;
                break;
            case DraftType.REPLY_ALL:
                flags |= Message.FLAG_TYPE_REPLY_ALL;
                //$FALL-THROUGH$
            case DraftType.REPLY:
                flags |= Message.FLAG_TYPE_REPLY;
                break;
            case DraftType.COMPOSE:
                flags |= Message.FLAG_TYPE_ORIGINAL;
                break;
        }
        int draftInfo = 0;
        if (extras.containsKey(UIProvider.MessageColumns.QUOTE_START_POS)) {
            draftInfo = extras.getInt(UIProvider.MessageColumns.QUOTE_START_POS);
            if (extras.getInt(UIProvider.MessageColumns.APPEND_REF_MESSAGE_CONTENT) != 0) {
                draftInfo |= Message.DRAFT_INFO_APPEND_REF_MESSAGE;
            }
        }
        if (!extras.containsKey(UIProvider.MessageColumns.APPEND_REF_MESSAGE_CONTENT)) {
            flags |= Message.FLAG_NOT_INCLUDE_QUOTED_TEXT;
        }
        msg.mDraftInfo = draftInfo;
        msg.mFlags = flags;

        final String ref = extras.getString(UIProvider.MessageColumns.REF_MESSAGE_ID);
        if (ref != null && msg.mQuotedTextStartPos >= 0) {
            String refId = Uri.parse(ref).getLastPathSegment();
            try {
                long sourceKey = Long.parseLong(refId);
                msg.mSourceKey = sourceKey;
            } catch (NumberFormatException e) {
                // This will be zero; the default
            }
        }

        // Get attachments from the ContentValues
        final List<com.android.mail.providers.Attachment> uiAtts =
                com.android.mail.providers.Attachment.fromJSONArray(
                        extras.getString(UIProvider.MessageColumns.ATTACHMENTS));
        final ArrayList<Attachment> atts = new ArrayList<Attachment>();
        boolean hasUnloadedAttachments = false;
        Bundle attachmentFds =
                extras.getParcelable(UIProvider.SendOrSaveMethodParamKeys.OPENED_FD_MAP);
        for (com.android.mail.providers.Attachment uiAtt: uiAtts) {
            final Uri attUri = uiAtt.uri;
            if (attUri != null && attUri.getAuthority().equals(EmailContent.AUTHORITY)) {
                // If it's one of ours, retrieve the attachment and add it to the list
                final long attId = Long.parseLong(attUri.getLastPathSegment());
                final Attachment att = Attachment.restoreAttachmentWithId(context, attId);
                if (att != null) {
                    // We must clone the attachment into a new one for this message; easiest to
                    // use a parcel here
                    final Parcel p = Parcel.obtain();
                    att.writeToParcel(p, 0);
                    p.setDataPosition(0);
                    final Attachment attClone = new Attachment(p);
                    p.recycle();
                    // Clear the messageKey (this is going to be a new attachment)
                    attClone.mMessageKey = 0;
                    // If we're sending this, it's not loaded, and we're not smart forwarding
                    // add the download flag, so that ADS will start up
                    if (mailbox.mType == Mailbox.TYPE_OUTBOX && att.getContentUri() == null &&
                            ((account.mFlags & Account.FLAGS_SUPPORTS_SMART_FORWARD) == 0)) {
                        attClone.mFlags |= Attachment.FLAG_DOWNLOAD_FORWARD;
                        hasUnloadedAttachments = true;
                    }
                    atts.add(attClone);
                }
            } else {
                // Cache the attachment.  This will allow us to send it, if the permissions are
                // revoked
                final String cachedFileUri =
                        AttachmentUtils.cacheAttachmentUri(context, uiAtt, attachmentFds);

                // Convert external attachment to one of ours and add to the list
                atts.add(convertUiAttachmentToAttachment(uiAtt, cachedFileUri));
            }
        }
        if (!atts.isEmpty()) {
            msg.mAttachments = atts;
            msg.mFlagAttachment = true;
            if (hasUnloadedAttachments) {
                Utility.showToast(context, R.string.message_view_attachment_background_load);
            }
        }
        // Save it or update it...
        if (!msg.isSaved()) {
            msg.save(context);
        } else {
            // This is tricky due to how messages/attachments are saved; rather than putz with
            // what's changed, we'll delete/re-add them
            final ArrayList<ContentProviderOperation> ops =
                    new ArrayList<ContentProviderOperation>();
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
        if (mailbox.mType == Mailbox.TYPE_OUTBOX) {
            startSync(mailbox, 0);
            final long originalMsgId = msg.mSourceKey;
            if (originalMsgId != 0) {
                final Message originalMsg = Message.restoreMessageWithId(context, originalMsgId);
                // If the original message exists, set its forwarded/replied to flags
                if (originalMsg != null) {
                    final ContentValues cv = new ContentValues();
                    flags = originalMsg.mFlags;
                    switch(draftType) {
                        case DraftType.FORWARD:
                            flags |= Message.FLAG_FORWARDED;
                            break;
                        case DraftType.REPLY_ALL:
                        case DraftType.REPLY:
                            flags |= Message.FLAG_REPLIED_TO;
                            break;
                    }
                    cv.put(Message.FLAGS, flags);
                    context.getContentResolver().update(ContentUris.withAppendedId(
                            Message.CONTENT_URI, originalMsgId), cv, null, null);
                }
            }
        }
        return uiUri("uimessage", msg.mId);
    }

    private Uri uiSaveDraftMessage(final long accountId, final Bundle extras) {
        final Mailbox mailbox =
                getMailboxByAccountIdAndType(accountId, Mailbox.TYPE_DRAFTS);
        if (mailbox == null) return null;
        final Message msg;
        if (extras.containsKey(BaseColumns._ID)) {
            final long messageId = extras.getLong(BaseColumns._ID);
            msg = Message.restoreMessageWithId(getContext(), messageId);
        } else {
            msg = new Message();
        }
        return uiSaveMessage(msg, mailbox, extras);
    }

    private Uri uiSendDraftMessage(final long accountId, final Bundle extras) {
        final Context context = getContext();
        final Message msg;
        if (extras.containsKey(BaseColumns._ID)) {
            final long messageId = extras.getLong(BaseColumns._ID);
            msg = Message.restoreMessageWithId(getContext(), messageId);
        } else {
            msg = new Message();
        }

        if (msg == null) return null;
        final Mailbox mailbox = getMailboxByAccountIdAndType(accountId, Mailbox.TYPE_OUTBOX);
        if (mailbox == null) return null;
        // Make sure the sent mailbox exists, since it will be necessary soon.
        // TODO(yph): move system mailbox creation to somewhere sane.
        final Mailbox sentMailbox = getMailboxByAccountIdAndType(accountId, Mailbox.TYPE_SENT);
        if (sentMailbox == null) return null;
        final Uri messageUri = uiSaveMessage(msg, mailbox, extras);
        // Kick observers
        context.getContentResolver().notifyChange(Mailbox.CONTENT_URI, null);
        return messageUri;
    }

    private static void putIntegerLongOrBoolean(ContentValues values, String columnName,
            Object value) {
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

    /**
     * Update the timestamps for the folders specified and notifies on the recent folder URI.
     * @param folders
     * @return number of folders updated
     */
    private static int updateTimestamp(final Context context, String id, Uri[] folders){
        int updated = 0;
        final long now = System.currentTimeMillis();
        final ContentResolver resolver = context.getContentResolver();
        final ContentValues touchValues = new ContentValues();
        for (int i=0, size=folders.length; i < size; ++i) {
            touchValues.put(MailboxColumns.LAST_TOUCHED_TIME, now);
            LogUtils.d(TAG, "updateStamp: %s updated", folders[i]);
            updated += resolver.update(folders[i], touchValues, null, null);
        }
        final Uri toNotify =
                UIPROVIDER_RECENT_FOLDERS_NOTIFIER.buildUpon().appendPath(id).build();
        LogUtils.d(TAG, "updateTimestamp: Notifying on %s", toNotify);
        resolver.notifyChange(toNotify, null);
        return updated;
    }

    /**
     * Updates the recent folders. The values to be updated are specified as ContentValues pairs
     * of (Folder URI, access timestamp). Returns nonzero if successful, always.
     * @param uri
     * @param values
     * @return nonzero value always.
     */
    private int uiUpdateRecentFolders(Uri uri, ContentValues values) {
        final int numFolders = values.size();
        final String id = uri.getPathSegments().get(1);
        final Uri[] folders = new Uri[numFolders];
        final Context context = getContext();
        final NotificationController controller = NotificationController.getInstance(context);
        int i = 0;
        for (final String uriString: values.keySet()) {
            folders[i] = Uri.parse(uriString);
            try {
                final String mailboxIdString = folders[i].getLastPathSegment();
                final long mailboxId = Long.parseLong(mailboxIdString);
                controller.cancelNewMessageNotification(mailboxId);
            } catch (NumberFormatException e) {
                // Keep on going...
            }
        }
        return updateTimestamp(context, id, folders);
    }

    /**
     * Populates the recent folders according to the design.
     * @param uri
     * @return the number of recent folders were populated.
     */
    private int uiPopulateRecentFolders(Uri uri) {
        final Context context = getContext();
        final String id = uri.getLastPathSegment();
        final Uri[] recentFolders = defaultRecentFolders(id);
        final int numFolders = recentFolders.length;
        if (numFolders <= 0) {
            return 0;
        }
        final int rowsUpdated = updateTimestamp(context, id, recentFolders);
        LogUtils.d(TAG, "uiPopulateRecentFolders: %d folders changed", rowsUpdated);
        return rowsUpdated;
    }

    private int uiUpdateAttachment(Uri uri, ContentValues uiValues) {
        Integer stateValue = uiValues.getAsInteger(UIProvider.AttachmentColumns.STATE);
        if (stateValue != null) {
            // This is a command from UIProvider
            long attachmentId = Long.parseLong(uri.getLastPathSegment());
            Context context = getContext();
            Attachment attachment =
                    Attachment.restoreAttachmentWithId(context, attachmentId);
            if (attachment == null) {
                // Went away; ah, well...
                return 0;
            }
            ContentValues values = new ContentValues();
            switch (stateValue.intValue()) {
                case UIProvider.AttachmentState.NOT_SAVED:
                    // Set state, try to cancel request
                    values.put(AttachmentColumns.UI_STATE, stateValue);
                    values.put(AttachmentColumns.FLAGS,
                            attachment.mFlags &= ~Attachment.FLAG_DOWNLOAD_USER_REQUEST);
                    attachment.update(context, values);
                    return 1;
                case UIProvider.AttachmentState.DOWNLOADING:
                    // Set state and destination; request download
                    values.put(AttachmentColumns.UI_STATE, stateValue);
                    Integer destinationValue =
                        uiValues.getAsInteger(UIProvider.AttachmentColumns.DESTINATION);
                    values.put(AttachmentColumns.UI_DESTINATION,
                            destinationValue == null ? 0 : destinationValue);
                    values.put(AttachmentColumns.FLAGS,
                            attachment.mFlags | Attachment.FLAG_DOWNLOAD_USER_REQUEST);
                    attachment.update(context, values);
                    return 1;
                case UIProvider.AttachmentState.SAVED:
                    // If this is an inline attachment, notify message has changed
                    if (!TextUtils.isEmpty(attachment.mContentId)) {
                        notifyUI(UIPROVIDER_MESSAGE_NOTIFIER, attachment.mMessageKey);
                    }
                    return 1;
            }
        }
        return 0;
    }

    private int uiUpdateFolder(final Context context, Uri uri, ContentValues uiValues) {
        // We need to mark seen separately
        if (uiValues.containsKey(UIProvider.ConversationColumns.SEEN)) {
            final int seenValue = uiValues.getAsInteger(UIProvider.ConversationColumns.SEEN);

            if (seenValue == 1) {
                final String mailboxId = uri.getLastPathSegment();
                final int rows = markAllSeen(context, mailboxId);

                if (uiValues.size() == 1) {
                    // Nothing else to do, so return this value
                    return rows;
                }
            }
        }

        final Uri ourUri = convertToEmailProviderUri(uri, Mailbox.CONTENT_URI, true);
        if (ourUri == null) return 0;
        ContentValues ourValues = new ContentValues();
        // This should only be called via update to "recent folders"
        for (String columnName: uiValues.keySet()) {
            if (columnName.equals(MailboxColumns.LAST_TOUCHED_TIME)) {
                ourValues.put(MailboxColumns.LAST_TOUCHED_TIME, uiValues.getAsLong(columnName));
            }
        }
        return update(ourUri, ourValues, null, null);
    }

    private int markAllSeen(final Context context, final String mailboxId) {
        final SQLiteDatabase db = getDatabase(context);
        final String table = Message.TABLE_NAME;
        final ContentValues values = new ContentValues(1);
        values.put(MessageColumns.FLAG_SEEN, 1);
        final String whereClause = MessageColumns.MAILBOX_KEY + " = ?";
        final String[] whereArgs = new String[] {mailboxId};

        return db.update(table, values, whereClause, whereArgs);
    }

    private ContentValues convertUiMessageValues(Message message, ContentValues values) {
        final ContentValues ourValues = new ContentValues();
        for (String columnName : values.keySet()) {
            final Object val = values.get(columnName);
            if (columnName.equals(UIProvider.ConversationColumns.STARRED)) {
                putIntegerLongOrBoolean(ourValues, MessageColumns.FLAG_FAVORITE, val);
            } else if (columnName.equals(UIProvider.ConversationColumns.READ)) {
                putIntegerLongOrBoolean(ourValues, MessageColumns.FLAG_READ, val);
            } else if (columnName.equals(UIProvider.ConversationColumns.SEEN)) {
                putIntegerLongOrBoolean(ourValues, MessageColumns.FLAG_SEEN, val);
            } else if (columnName.equals(MessageColumns.MAILBOX_KEY)) {
                putIntegerLongOrBoolean(ourValues, MessageColumns.MAILBOX_KEY, val);
            } else if (columnName.equals(UIProvider.ConversationOperations.FOLDERS_UPDATED)) {
                // Skip this column, as the folders will also be specified  the RAW_FOLDERS column
            } else if (columnName.equals(UIProvider.ConversationColumns.RAW_FOLDERS)) {
                // Convert from folder list uri to mailbox key
                final FolderList flist = FolderList.fromBlob(values.getAsByteArray(columnName));
                if (flist.folders.size() != 1) {
                    LogUtils.e(TAG,
                            "Incorrect number of folders for this message: Message is %s",
                            message.mId);
                } else {
                    final Folder f = flist.folders.get(0);
                    final Uri uri = f.uri;
                    final Long mailboxId = Long.parseLong(uri.getLastPathSegment());
                    putIntegerLongOrBoolean(ourValues, MessageColumns.MAILBOX_KEY, mailboxId);
                }
            } else if (columnName.equals(UIProvider.MessageColumns.ALWAYS_SHOW_IMAGES)) {
                Address[] fromList = Address.unpack(message.mFrom);
                Preferences prefs = Preferences.getPreferences(getContext());
                for (Address sender : fromList) {
                    String email = sender.getAddress();
                    prefs.setSenderAsTrusted(email);
                }
            } else if (columnName.equals(UIProvider.ConversationColumns.VIEWED) ||
                    columnName.equals(UIProvider.ConversationOperations.Parameters.SUPPRESS_UNDO)) {
                // Ignore for now
            } else {
                throw new IllegalArgumentException("Can't update " + columnName + " in message");
            }
        }
        return ourValues;
    }

    private static Uri convertToEmailProviderUri(Uri uri, Uri newBaseUri, boolean asProvider) {
        final String idString = uri.getLastPathSegment();
        try {
            final long id = Long.parseLong(idString);
            Uri ourUri = ContentUris.withAppendedId(newBaseUri, id);
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

    // TODO: This should depend on flags on the mailbox...
    private static boolean uploadsToServer(Context context, Mailbox m) {
        if (m.mType == Mailbox.TYPE_DRAFTS || m.mType == Mailbox.TYPE_OUTBOX ||
                m.mType == Mailbox.TYPE_SEARCH) {
            return false;
        }
        String protocol = Account.getProtocol(context, m.mAccountKey);
        EmailServiceInfo info = EmailServiceUtils.getServiceInfo(context, protocol);
        return (info != null && info.syncChanges);
    }

    private int uiUpdateMessage(Uri uri, ContentValues values) {
        return uiUpdateMessage(uri, values, false);
    }

    private int uiUpdateMessage(Uri uri, ContentValues values, boolean forceSync) {
        Context context = getContext();
        Message msg = getMessageFromLastSegment(uri);
        if (msg == null) return 0;
        Mailbox mailbox = Mailbox.restoreMailboxWithId(context, msg.mMailboxKey);
        if (mailbox == null) return 0;
        Uri ourBaseUri =
                (forceSync || uploadsToServer(context, mailbox)) ? Message.SYNCED_CONTENT_URI :
                    Message.CONTENT_URI;
        Uri ourUri = convertToEmailProviderUri(uri, ourBaseUri, true);
        if (ourUri == null) return 0;

        // Special case - meeting response
        if (values.containsKey(UIProvider.MessageOperations.RESPOND_COLUMN)) {
            EmailServiceProxy service = EmailServiceUtils.getServiceForAccount(context,
                    mServiceCallback, mailbox.mAccountKey);
            try {
                service.sendMeetingResponse(msg.mId,
                        values.getAsInteger(UIProvider.MessageOperations.RESPOND_COLUMN));
                // Delete the message immediately
                uiDeleteMessage(uri);
                Utility.showToast(context, R.string.confirm_response);
                // Notify box has changed so the deletion is reflected in the UI
                notifyUIConversationMailbox(mailbox.mId);
            } catch (RemoteException e) {
            }
            return 1;
        }

        ContentValues undoValues = new ContentValues();
        ContentValues ourValues = convertUiMessageValues(msg, values);
        for (String columnName: ourValues.keySet()) {
            if (columnName.equals(MessageColumns.MAILBOX_KEY)) {
                undoValues.put(MessageColumns.MAILBOX_KEY, msg.mMailboxKey);
            } else if (columnName.equals(MessageColumns.FLAG_READ)) {
                undoValues.put(MessageColumns.FLAG_READ, msg.mFlagRead);
            } else if (columnName.equals(MessageColumns.FLAG_SEEN)) {
                undoValues.put(MessageColumns.FLAG_SEEN, msg.mFlagSeen);
            } else if (columnName.equals(MessageColumns.FLAG_FAVORITE)) {
                undoValues.put(MessageColumns.FLAG_FAVORITE, msg.mFlagFavorite);
            }
        }
        if (undoValues == null || undoValues.size() == 0) {
            return -1;
        }
        final Boolean suppressUndo =
                values.getAsBoolean(UIProvider.ConversationOperations.Parameters.SUPPRESS_UNDO);
        if (suppressUndo == null || !suppressUndo.booleanValue()) {
            final ContentProviderOperation op =
                    ContentProviderOperation.newUpdate(convertToEmailProviderUri(
                            uri, ourBaseUri, false))
                            .withValues(undoValues)
                            .build();
            addToSequence(uri, op);
        }
        return update(ourUri, ourValues, null, null);
    }

    public static final String PICKER_UI_ACCOUNT = "picker_ui_account";
    public static final String PICKER_MAILBOX_TYPE = "picker_mailbox_type";
    public static final String PICKER_MESSAGE_ID = "picker_message_id";
    public static final String PICKER_HEADER_ID = "picker_header_id";

    private int uiDeleteMessage(Uri uri) {
        final Context context = getContext();
        Message msg = getMessageFromLastSegment(uri);
        if (msg == null) return 0;
        Mailbox mailbox = Mailbox.restoreMailboxWithId(context, msg.mMailboxKey);
        if (mailbox == null) return 0;
        if (mailbox.mType == Mailbox.TYPE_TRASH || mailbox.mType == Mailbox.TYPE_DRAFTS) {
            // We actually delete these, including attachments
            AttachmentUtilities.deleteAllAttachmentFiles(context, msg.mAccountKey, msg.mId);
            notifyUIFolder(mailbox.mId, mailbox.mAccountKey);
            return context.getContentResolver().delete(
                    ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, msg.mId), null, null);
        }
        Mailbox trashMailbox =
                Mailbox.restoreMailboxOfType(context, msg.mAccountKey, Mailbox.TYPE_TRASH);
        if (trashMailbox == null) {
            return 0;
        }
        ContentValues values = new ContentValues();
        values.put(MessageColumns.MAILBOX_KEY, trashMailbox.mId);
        notifyUIFolder(mailbox.mId, mailbox.mAccountKey);
        return uiUpdateMessage(uri, values, true);
    }

    private int pickFolder(Uri uri, int type, int headerId) {
        Context context = getContext();
        Long acctId = Long.parseLong(uri.getLastPathSegment());
        // For push imap, for example, we want the user to select the trash mailbox
        Cursor ac = query(uiUri("uiaccount", acctId), UIProvider.ACCOUNTS_PROJECTION,
                null, null, null);
        try {
            if (ac.moveToFirst()) {
                final com.android.mail.providers.Account uiAccount =
                        new com.android.mail.providers.Account(ac);
                Intent intent = new Intent(context, FolderPickerActivity.class);
                intent.putExtra(PICKER_UI_ACCOUNT, uiAccount);
                intent.putExtra(PICKER_MAILBOX_TYPE, type);
                intent.putExtra(PICKER_HEADER_ID, headerId);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return 1;
            }
            return 0;
        } finally {
            ac.close();
        }
    }

    private int pickTrashFolder(Uri uri) {
        return pickFolder(uri, Mailbox.TYPE_TRASH, R.string.trash_folder_selection_title);
    }

    private int pickSentFolder(Uri uri) {
        return pickFolder(uri, Mailbox.TYPE_SENT, R.string.sent_folder_selection_title);
    }

    private Cursor uiUndo(String[] projection) {
        // First see if we have any operations saved
        // TODO: Make sure seq matches
        if (!mLastSequenceOps.isEmpty()) {
            try {
                // TODO Always use this projection?  Or what's passed in?
                // Not sure if UI wants it, but I'm making a cursor of convo uri's
                MatrixCursor c = new MatrixCursorWithCachedColumns(
                        new String[] {UIProvider.ConversationColumns.URI},
                        mLastSequenceOps.size());
                for (ContentProviderOperation op: mLastSequenceOps) {
                    c.addRow(new String[] {op.getUri().toString()});
                }
                // Just apply the batch and we're done!
                applyBatch(mLastSequenceOps);
                // But clear the operations
                mLastSequenceOps.clear();
                return c;
            } catch (OperationApplicationException e) {
            }
        }
        return new MatrixCursorWithCachedColumns(projection, 0);
    }

    private void notifyUIConversation(Uri uri) {
        String id = uri.getLastPathSegment();
        Message msg = Message.restoreMessageWithId(getContext(), Long.parseLong(id));
        if (msg != null) {
            notifyUIConversationMailbox(msg.mMailboxKey);
        }
    }

    /**
     * Notify about the Mailbox id passed in
     * @param id the Mailbox id to be notified
     */
    private void notifyUIConversationMailbox(long id) {
        notifyUI(UIPROVIDER_CONVERSATION_NOTIFIER, Long.toString(id));
        Mailbox mailbox = Mailbox.restoreMailboxWithId(getContext(), id);
        if (mailbox == null) {
            Log.w(TAG, "No mailbox for notification: " + id);
            return;
        }
        // Notify combined inbox...
        if (mailbox.mType == Mailbox.TYPE_INBOX) {
            notifyUI(UIPROVIDER_CONVERSATION_NOTIFIER,
                    EmailProvider.combinedMailboxId(Mailbox.TYPE_INBOX));
        }
        notifyWidgets(id);
    }

    /**
     * Notify about the Account id passed in
     * @param id the Account id to be notified
     */
    private void notifyUIAccount(long id) {
        // Notify on the specific account
        notifyUI(UIPROVIDER_ACCOUNT_NOTIFIER, Long.toString(id));

        // Notify on the all accounts list
        notifyUI(UIPROVIDER_ALL_ACCOUNTS_NOTIFIER, null);
    }

    /**
     * Notify about a folder update. Because folder changes can affect the conversation cursor's
     * extras, the conversation must also be notified here.
     * @param folderId the folder id to be notified
     * @param accountId the account id to be notified (for folder list notification); if null, then
     * lookup the accountId from the folder.
     */
    private void notifyUIFolder(String folderId, String accountId) {
        notifyUI(UIPROVIDER_CONVERSATION_NOTIFIER, folderId);
        notifyUI(UIPROVIDER_FOLDER_NOTIFIER, folderId);
        if (accountId == null) {
            try {
                final Mailbox mailbox = Mailbox.restoreMailboxWithId(getContext(),
                        Long.parseLong(folderId));
                if (mailbox != null) {
                    accountId = Long.toString(mailbox.mAccountKey);
                }
            } catch (NumberFormatException e) {
                // Bad folderId, so we can't lookup account.
            }
        }
        if (accountId != null) {
            notifyUI(UIPROVIDER_FOLDERLIST_NOTIFIER, accountId);
        }
    }

    private void notifyUIFolder(long folderId, long accountId) {
        notifyUIFolder(Long.toString(folderId), Long.toString(accountId));
    }

    private void notifyUI(Uri uri, String id) {
        final Uri notifyUri = (id != null) ? uri.buildUpon().appendPath(id).build() : uri;
        getContext().getContentResolver().notifyChange(notifyUri, null);
    }

    private void notifyUI(Uri uri, long id) {
        notifyUI(uri, Long.toString(id));
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

    private Mailbox getMailbox(final Uri uri) {
        final long id = Long.parseLong(uri.getLastPathSegment());
        return Mailbox.restoreMailboxWithId(getContext(), id);
    }

    /**
     * Create an android.accounts.Account object for this account.
     * @param accountId id of account to load.
     * @return an android.accounts.Account for this account, or null if we can't load it.
     */
    private android.accounts.Account getAccountManagerAccount(final long accountId) {
        final Context context = getContext();
        final Account account = Account.restoreAccountWithId(context, accountId);
        if (account == null) return null;
        EmailServiceInfo info =
                EmailServiceUtils.getServiceInfo(context, account.getProtocol(context));
        return new android.accounts.Account(account.mEmailAddress, info.accountType);
    }

    /**
     * The old sync code used to add periodic syncs for a mailbox when it was manually synced.
     * This function removes all such non-default periodic syncs.
     * @param account The account for which to remove unnecessary periodic syncs.
     */
    private void removeExtraPeriodicSyncs(final android.accounts.Account account) {
        final List<PeriodicSync> syncs =
                ContentResolver.getPeriodicSyncs(account, EmailContent.AUTHORITY);
        for (PeriodicSync sync : syncs) {
            if (!sync.extras.isEmpty()) {
                ContentResolver.removePeriodicSync(account, EmailContent.AUTHORITY, sync.extras);
            }
        }
    }

    /**
     * Update an account's periodic sync if the sync interval has changed.
     * @param accountId id for the account to update.
     * @param values the ContentValues for this update to the account.
     */
    private void updateAccountSyncInterval(final long accountId, final ContentValues values) {
        final Integer syncInterval = values.getAsInteger(AccountColumns.SYNC_INTERVAL);
        if (syncInterval == null) {
            // No change to the sync interval.
            return;
        }
        final android.accounts.Account account = getAccountManagerAccount(accountId);
        if (account == null) {
            // Unable to load the account, or unknown protocol.
            return;
        }

        Log.d(TAG, "Setting sync interval for account " + accountId + " to " + syncInterval +
                " minutes");

        // TODO: Ideally we don't need to do this every time we change the sync interval.
        // Either do this on upgrade, or perhaps on startup.
        removeExtraPeriodicSyncs(account);

        final Bundle extras = new Bundle();
        if (syncInterval > 0) {
            ContentResolver.addPeriodicSync(account, EmailContent.AUTHORITY, extras,
                    syncInterval * 60);
        } else {
            ContentResolver.removePeriodicSync(account, EmailContent.AUTHORITY, extras);
        }
    }

    private void startSync(final Mailbox mailbox, final int deltaMessageCount) {
        android.accounts.Account account = getAccountManagerAccount(mailbox.mAccountKey);
        Bundle extras = new Bundle(7);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        extras.putLong(Mailbox.SYNC_EXTRA_MAILBOX_ID, mailbox.mId);
        if (deltaMessageCount != 0) {
            extras.putInt(Mailbox.SYNC_EXTRA_DELTA_MESSAGE_COUNT, deltaMessageCount);
        }
        extras.putString(EmailServiceStatus.SYNC_EXTRAS_CALLBACK_URI,
                EmailContent.CONTENT_URI.toString());
        extras.putString(EmailServiceStatus.SYNC_EXTRAS_CALLBACK_METHOD,
                SYNC_STATUS_CALLBACK_METHOD);
        ContentResolver.requestSync(account, EmailContent.AUTHORITY, extras);
    }

    private Cursor uiFolderRefresh(final Mailbox mailbox, final int deltaMessageCount) {
        if (mailbox != null) {
            startSync(mailbox, deltaMessageCount);
        }
        return null;
    }

    //Number of additional messages to load when a user selects "Load more..." in POP/IMAP boxes
    public static final int VISIBLE_LIMIT_INCREMENT = 10;
    //Number of additional messages to load when a user selects "Load more..." in a search
    public static final int SEARCH_MORE_INCREMENT = 10;

    private Cursor uiFolderLoadMore(final Mailbox mailbox) {
        if (mailbox == null) return null;
        if (mailbox.mType == Mailbox.TYPE_SEARCH) {
            // Ask for 10 more messages
            mSearchParams.mOffset += SEARCH_MORE_INCREMENT;
            runSearchQuery(getContext(), mailbox.mAccountKey, mailbox.mId);
        } else {
            uiFolderRefresh(mailbox, VISIBLE_LIMIT_INCREMENT);
        }
        return null;
    }

    private static final String SEARCH_MAILBOX_SERVER_ID = "__search_mailbox__";
    private SearchParams mSearchParams;

    /**
     * Returns the search mailbox for the specified account, creating one if necessary
     * @return the search mailbox for the passed in account
     */
    private Mailbox getSearchMailbox(long accountId) {
        Context context = getContext();
        Mailbox m = Mailbox.restoreMailboxOfType(context, accountId, Mailbox.TYPE_SEARCH);
        if (m == null) {
            m = new Mailbox();
            m.mAccountKey = accountId;
            m.mServerId = SEARCH_MAILBOX_SERVER_ID;
            m.mFlagVisible = false;
            m.mDisplayName = SEARCH_MAILBOX_SERVER_ID;
            m.mSyncInterval = Mailbox.CHECK_INTERVAL_NEVER;
            m.mType = Mailbox.TYPE_SEARCH;
            m.mFlags = Mailbox.FLAG_HOLDS_MAIL;
            m.mParentKey = Mailbox.NO_MAILBOX;
            m.save(context);
        }
        return m;
    }

    private void runSearchQuery(final Context context, final long accountId,
            final long searchMailboxId) {
        // Start the search running in the background
        new Thread(new Runnable() {
            @Override
            public void run() {
                 try {
                    EmailServiceProxy service = EmailServiceUtils.getServiceForAccount(context,
                            mServiceCallback, accountId);
                    if (service != null) {
                        try {
                            // Save away the total count
                            mSearchParams.mTotalCount = service.searchMessages(accountId,
                                    mSearchParams, searchMailboxId);
                            //Log.d(TAG, "TotalCount to UI: " + mSearchParams.mTotalCount);
                            notifyUIFolder(searchMailboxId, accountId);
                        } catch (RemoteException e) {
                            Log.e("searchMessages", "RemoteException", e);
                        }
                    }
                } finally {
                }
            }}).start();

    }

    // TODO: Handle searching for more...
    private Cursor uiSearch(Uri uri, String[] projection) {
        final long accountId = Long.parseLong(uri.getLastPathSegment());

        // TODO: Check the actual mailbox
        Mailbox inbox = Mailbox.restoreMailboxOfType(getContext(), accountId, Mailbox.TYPE_INBOX);
        if (inbox == null) {
            Log.w(Logging.LOG_TAG, "In uiSearch, inbox doesn't exist for account " + accountId);
            return null;
        }

        String filter = uri.getQueryParameter(UIProvider.SearchQueryParameters.QUERY);
        if (filter == null) {
            throw new IllegalArgumentException("No query parameter in search query");
        }

        // Find/create our search mailbox
        Mailbox searchMailbox = getSearchMailbox(accountId);
        final long searchMailboxId = searchMailbox.mId;

        mSearchParams = new SearchParams(inbox.mId, filter, searchMailboxId);

        final Context context = getContext();
        if (mSearchParams.mOffset == 0) {
            // Delete existing contents of search mailbox
            ContentResolver resolver = context.getContentResolver();
            resolver.delete(Message.CONTENT_URI, Message.MAILBOX_KEY + "=" + searchMailboxId,
                    null);
            ContentValues cv = new ContentValues();
            // For now, use the actual query as the name of the mailbox
            cv.put(Mailbox.DISPLAY_NAME, mSearchParams.mFilter);
            resolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, searchMailboxId),
                    cv, null, null);
        }

        // Start the search running in the background
        runSearchQuery(context, accountId, searchMailboxId);

        // This will look just like a "normal" folder
        return uiQuery(UI_FOLDER, ContentUris.withAppendedId(Mailbox.CONTENT_URI,
                searchMailbox.mId), projection, false);
    }

    private static final String MAILBOXES_FOR_ACCOUNT_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?";
    private static final String MAILBOXES_FOR_ACCOUNT_EXCEPT_ACCOUNT_MAILBOX_SELECTION =
        MAILBOXES_FOR_ACCOUNT_SELECTION + " AND " + MailboxColumns.TYPE + "!=" +
        Mailbox.TYPE_EAS_ACCOUNT_MAILBOX;
    private static final String MESSAGES_FOR_ACCOUNT_SELECTION = MessageColumns.ACCOUNT_KEY + "=?";

    /**
     * Delete an account and clean it up
     */
    private int uiDeleteAccount(Uri uri) {
        Context context = getContext();
        long accountId = Long.parseLong(uri.getLastPathSegment());
        try {
            // Get the account URI.
            final Account account = Account.restoreAccountWithId(context, accountId);
            if (account == null) {
                return 0; // Already deleted?
            }

            deleteAccountData(context, accountId);

            // Now delete the account itself
            uri = ContentUris.withAppendedId(Account.CONTENT_URI, accountId);
            context.getContentResolver().delete(uri, null, null);

            // Clean up
            AccountBackupRestore.backup(context);
            SecurityPolicy.getInstance(context).reducePolicies();
            MailActivityEmail.setServicesEnabledSync(context);
            return 1;
        } catch (Exception e) {
            Log.w(Logging.LOG_TAG, "Exception while deleting account", e);
        }
        return 0;
    }

    private int uiDeleteAccountData(Uri uri) {
        Context context = getContext();
        long accountId = Long.parseLong(uri.getLastPathSegment());
        // Get the account URI.
        final Account account = Account.restoreAccountWithId(context, accountId);
        if (account == null) {
            return 0; // Already deleted?
        }
        deleteAccountData(context, accountId);
        return 1;
    }

    private static void deleteAccountData(Context context, long accountId) {
        // Delete synced attachments
        AttachmentUtilities.deleteAllAccountAttachmentFiles(context, accountId);

        // Delete synced email, leaving only an empty inbox.  We do this in two phases:
        // 1. Delete all non-inbox mailboxes (which will delete all of their messages)
        // 2. Delete all remaining messages (which will be the inbox messages)
        ContentResolver resolver = context.getContentResolver();
        String[] accountIdArgs = new String[] { Long.toString(accountId) };
        resolver.delete(Mailbox.CONTENT_URI,
                MAILBOXES_FOR_ACCOUNT_EXCEPT_ACCOUNT_MAILBOX_SELECTION,
                accountIdArgs);
        resolver.delete(Message.CONTENT_URI, MESSAGES_FOR_ACCOUNT_SELECTION, accountIdArgs);

        // Delete sync keys on remaining items
        ContentValues cv = new ContentValues();
        cv.putNull(Account.SYNC_KEY);
        resolver.update(Account.CONTENT_URI, cv, Account.ID_SELECTION, accountIdArgs);
        cv.clear();
        cv.putNull(Mailbox.SYNC_KEY);
        resolver.update(Mailbox.CONTENT_URI, cv,
                MAILBOXES_FOR_ACCOUNT_SELECTION, accountIdArgs);

        // Delete PIM data (contacts, calendar), stop syncs, etc. if applicable
        IEmailService service = EmailServiceUtils.getServiceForAccount(context, null, accountId);
        if (service != null) {
            try {
                service.deleteAccountPIMData(accountId);
            } catch (RemoteException e) {
                // Can't do anything about this
            }
        }
    }

    private int[] mSavedWidgetIds = new int[0];
    private final ArrayList<Long> mWidgetNotifyMailboxes = new ArrayList<Long>();
    private AppWidgetManager mAppWidgetManager;
    private ComponentName mEmailComponent;

    private void notifyWidgets(long mailboxId) {
        Context context = getContext();
        // Lazily initialize these
        if (mAppWidgetManager == null) {
            mAppWidgetManager = AppWidgetManager.getInstance(context);
            mEmailComponent = new ComponentName(context, WidgetProvider.PROVIDER_NAME);
        }

        // See if we have to populate our array of mailboxes used in widgets
        int[] widgetIds = mAppWidgetManager.getAppWidgetIds(mEmailComponent);
        if (!Arrays.equals(widgetIds, mSavedWidgetIds)) {
            mSavedWidgetIds = widgetIds;
            String[][] widgetInfos = BaseWidgetProvider.getWidgetInfo(context, widgetIds);
            // widgetInfo now has pairs of account uri/folder uri
            mWidgetNotifyMailboxes.clear();
            for (String[] widgetInfo: widgetInfos) {
                try {
                    if (widgetInfo == null) continue;
                    long id = Long.parseLong(Uri.parse(widgetInfo[1]).getLastPathSegment());
                    if (!isCombinedMailbox(id)) {
                        // For a regular mailbox, just add it to the list
                        if (!mWidgetNotifyMailboxes.contains(id)) {
                            mWidgetNotifyMailboxes.add(id);
                        }
                    } else {
                        switch (getVirtualMailboxType(id)) {
                            // We only handle the combined inbox in widgets
                            case Mailbox.TYPE_INBOX:
                                Cursor c = query(Mailbox.CONTENT_URI, Mailbox.ID_PROJECTION,
                                        MailboxColumns.TYPE + "=?",
                                        new String[] {Integer.toString(Mailbox.TYPE_INBOX)}, null);
                                try {
                                    while (c.moveToNext()) {
                                        mWidgetNotifyMailboxes.add(
                                                c.getLong(Mailbox.ID_PROJECTION_COLUMN));
                                    }
                                } finally {
                                    c.close();
                                }
                                break;
                        }
                    }
                } catch (NumberFormatException e) {
                    // Move along
                }
            }
        }

        // If our mailbox needs to be notified, do so...
        if (mWidgetNotifyMailboxes.contains(mailboxId)) {
            Intent intent = new Intent(Utils.ACTION_NOTIFY_DATASET_CHANGED);
            intent.putExtra(Utils.EXTRA_FOLDER_URI, uiUri("uifolder", mailboxId));
            intent.setType(EMAIL_APP_MIME_TYPE);
            context.sendBroadcast(intent);
         }
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        Context context = getContext();
        writer.println("Installed services:");
        for (EmailServiceInfo info: EmailServiceUtils.getServiceInfoList(context)) {
            writer.println("  " + info);
        }
        writer.println();
        writer.println("Accounts: ");
        Cursor cursor = query(Account.CONTENT_URI, Account.CONTENT_PROJECTION, null, null, null);
        if (cursor.getCount() == 0) {
            writer.println("  None");
        }
        try {
            while (cursor.moveToNext()) {
                Account account = new Account();
                account.restore(cursor);
                writer.println("  Account " + account.mDisplayName);
                HostAuth hostAuth =
                        HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv);
                if (hostAuth != null) {
                    writer.println("    Protocol = " + hostAuth.mProtocol +
                            (TextUtils.isEmpty(account.mProtocolVersion) ? "" : " version " +
                                    account.mProtocolVersion));
                }
            }
        } finally {
            cursor.close();
        }
    }
}
