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


package com.android.emailcommon.provider;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.SparseBooleanArray;

import com.android.emailcommon.Logging;
import com.android.emailcommon.R;
import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogUtils;

import java.util.ArrayList;

public class Mailbox extends EmailContent implements EmailContent.MailboxColumns, Parcelable {
    /**
     * Sync extras key when syncing one or more mailboxes to specify how many
     * mailboxes are included in the extra.
     */
    public static final String SYNC_EXTRA_MAILBOX_COUNT = "__mailboxCount__";
    /**
     * Sync extras key pattern when syncing one or more mailboxes to specify
     * which mailbox to sync. Is intentionally private, we have helper functions
     * to set up an appropriate bundle, or read its contents.
     */
    private static final String SYNC_EXTRA_MAILBOX_ID_PATTERN = "__mailboxId%d__";
    /**
     * Sync extra key indicating that we are doing a sync of the folder structure for an account.
     */
    public static final String SYNC_EXTRA_ACCOUNT_ONLY = "__account_only__";
    /**
     * Sync extra key indicating that we are only starting a ping.
     */
    public static final String SYNC_EXTRA_PUSH_ONLY = "__push_only__";

    /**
     * Sync extras key to specify that only a specific mailbox type should be synced.
     */
    public static final String SYNC_EXTRA_MAILBOX_TYPE = "__mailboxType__";
    /**
     * Sync extras key when syncing a mailbox to specify how many additional messages to sync.
     */
    public static final String SYNC_EXTRA_DELTA_MESSAGE_COUNT = "__deltaMessageCount__";

    public static final String SYNC_EXTRA_NOOP = "__noop__";

    public static final String TABLE_NAME = "Mailbox";


    public static Uri CONTENT_URI;
    public static Uri MESSAGE_COUNT_URI;

    public static void initMailbox() {
        CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/mailbox");
        MESSAGE_COUNT_URI = Uri.parse(EmailContent.CONTENT_URI + "/mailboxCount");
    }

    private static String formatMailboxIdExtra(final int index) {
        return String.format(SYNC_EXTRA_MAILBOX_ID_PATTERN, index);
    }

    public static Bundle createSyncBundle(final ArrayList<Long> mailboxIds) {
        final Bundle bundle = new Bundle(mailboxIds.size() + 1);
        bundle.putInt(SYNC_EXTRA_MAILBOX_COUNT, mailboxIds.size());
        for (int i = 0; i < mailboxIds.size(); i++) {
            bundle.putLong(formatMailboxIdExtra(i), mailboxIds.get(i));
        }
        return bundle;
    }

    public static Bundle createSyncBundle(final long[] mailboxIds) {
        final Bundle bundle = new Bundle(mailboxIds.length + 1);
        bundle.putInt(SYNC_EXTRA_MAILBOX_COUNT, mailboxIds.length);
        for (int i = 0; i < mailboxIds.length; i++) {
            bundle.putLong(formatMailboxIdExtra(i), mailboxIds[i]);
        }
        return bundle;
    }

    public static Bundle createSyncBundle(final long mailboxId) {
        final Bundle bundle = new Bundle(2);
        bundle.putInt(SYNC_EXTRA_MAILBOX_COUNT, 1);
        bundle.putLong(formatMailboxIdExtra(0), mailboxId);
        return bundle;
    }

    public static long[] getMailboxIdsFromBundle(Bundle bundle) {
        final int count = bundle.getInt(SYNC_EXTRA_MAILBOX_COUNT, 0);
        if (count > 0) {
            if (bundle.getBoolean(SYNC_EXTRA_PUSH_ONLY, false)) {
                LogUtils.w(Logging.LOG_TAG, "Mailboxes specified in a push only sync");
            }
            if (bundle.getBoolean(SYNC_EXTRA_ACCOUNT_ONLY, false)) {
                LogUtils.w(Logging.LOG_TAG, "Mailboxes specified in an account only sync");
            }
            final long [] result = new long[count];
            for (int i = 0; i < count; i++) {
                result[i] = bundle.getLong(formatMailboxIdExtra(i), 0);
            }

            return result;
        } else {
            return null;
        }
    }

    public static boolean isAccountOnlyExtras(Bundle bundle) {
        final boolean result = bundle.getBoolean(SYNC_EXTRA_ACCOUNT_ONLY, false);
        if (result) {
            final int count = bundle.getInt(SYNC_EXTRA_MAILBOX_COUNT, 0);
            if (count != 0) {
                LogUtils.w(Logging.LOG_TAG, "Mailboxes specified in an account only sync");
            }
        }
        return result;
    }

    public static boolean isPushOnlyExtras(Bundle bundle) {
        final boolean result = bundle.getBoolean(SYNC_EXTRA_PUSH_ONLY, false);
        if (result) {
            final int count = bundle.getInt(SYNC_EXTRA_MAILBOX_COUNT, 0);
            if (count != 0) {
                LogUtils.w(Logging.LOG_TAG, "Mailboxes specified in a push only sync");
            }
        }
        return result;
    }

    public String mDisplayName;
    public String mServerId;
    public String mParentServerId;
    public long mParentKey;
    public long mAccountKey;
    public int mType;
    public int mDelimiter;
    public String mSyncKey;
    public int mSyncLookback;
    public int mSyncInterval;
    public long mSyncTime;
    public boolean mFlagVisible = true;
    public int mFlags;
    public String mSyncStatus;
    public long mLastTouchedTime;
    public int mUiSyncStatus;
    public int mUiLastSyncResult;
    public int mTotalCount;
    public String mHierarchicalName;
    public long mLastFullSyncTime;

    public static final int CONTENT_ID_COLUMN = 0;
    public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
    public static final int CONTENT_SERVER_ID_COLUMN = 2;
    public static final int CONTENT_PARENT_SERVER_ID_COLUMN = 3;
    public static final int CONTENT_ACCOUNT_KEY_COLUMN = 4;
    public static final int CONTENT_TYPE_COLUMN = 5;
    public static final int CONTENT_DELIMITER_COLUMN = 6;
    public static final int CONTENT_SYNC_KEY_COLUMN = 7;
    public static final int CONTENT_SYNC_LOOKBACK_COLUMN = 8;
    public static final int CONTENT_SYNC_INTERVAL_COLUMN = 9;
    public static final int CONTENT_SYNC_TIME_COLUMN = 10;
    public static final int CONTENT_FLAG_VISIBLE_COLUMN = 11;
    public static final int CONTENT_FLAGS_COLUMN = 12;
    public static final int CONTENT_SYNC_STATUS_COLUMN = 13;
    public static final int CONTENT_PARENT_KEY_COLUMN = 14;
    public static final int CONTENT_LAST_TOUCHED_TIME_COLUMN = 15;
    public static final int CONTENT_UI_SYNC_STATUS_COLUMN = 16;
    public static final int CONTENT_UI_LAST_SYNC_RESULT_COLUMN = 17;
    public static final int CONTENT_TOTAL_COUNT_COLUMN = 18;
    public static final int CONTENT_HIERARCHICAL_NAME_COLUMN = 19;
    public static final int CONTENT_LAST_FULL_SYNC_COLUMN = 20;

    /**
     * <em>NOTE</em>: If fields are added or removed, the method {@link #getHashes()}
     * MUST be updated.
     */
    public static final String[] CONTENT_PROJECTION = new String[] {
            MailboxColumns._ID,
            MailboxColumns.DISPLAY_NAME,
            MailboxColumns.SERVER_ID,
            MailboxColumns.PARENT_SERVER_ID,
            MailboxColumns.ACCOUNT_KEY,
            MailboxColumns.TYPE,
            MailboxColumns.DELIMITER,
            MailboxColumns.SYNC_KEY,
            MailboxColumns.SYNC_LOOKBACK,
            MailboxColumns.SYNC_INTERVAL,
            MailboxColumns.SYNC_TIME,
            MailboxColumns.FLAG_VISIBLE,
            MailboxColumns.FLAGS,
            MailboxColumns.SYNC_STATUS,
            MailboxColumns.PARENT_KEY,
            MailboxColumns.LAST_TOUCHED_TIME,
            MailboxColumns.UI_SYNC_STATUS,
            MailboxColumns.UI_LAST_SYNC_RESULT,
            MailboxColumns.TOTAL_COUNT,
            MailboxColumns.HIERARCHICAL_NAME,
            MailboxColumns.LAST_FULL_SYNC_TIME
    };

    /** Selection by server pathname for a given account */
    public static final String PATH_AND_ACCOUNT_SELECTION =
        MailboxColumns.SERVER_ID + "=? and " + MailboxColumns.ACCOUNT_KEY + "=?";

    private static final String[] MAILBOX_TYPE_PROJECTION = new String [] {
            MailboxColumns.TYPE
    };
    private static final int MAILBOX_TYPE_TYPE_COLUMN = 0;

    private static final String[] MAILBOX_DISPLAY_NAME_PROJECTION = new String [] {
            MailboxColumns.DISPLAY_NAME
    };
    private static final int MAILBOX_DISPLAY_NAME_COLUMN = 0;

    /**
     * Projection to use when reading {@link MailboxColumns#ACCOUNT_KEY} for a mailbox.
     */
    private static final String[] ACCOUNT_KEY_PROJECTION = { MailboxColumns.ACCOUNT_KEY };
    private static final int ACCOUNT_KEY_PROJECTION_ACCOUNT_KEY_COLUMN = 0;

    /**
     * Projection for querying data needed during a sync.
     */
    public interface ProjectionSyncData {
        public static final int COLUMN_SERVER_ID = 0;
        public static final int COLUMN_SYNC_KEY = 1;

        public static final String[] PROJECTION = {
                MailboxColumns.SERVER_ID,
                MailboxColumns.SYNC_KEY
        };
    }

    public static final long NO_MAILBOX = -1;

    // Sentinel values for the mSyncInterval field of both Mailbox records
    @Deprecated
    public static final int CHECK_INTERVAL_NEVER = -1;
    @Deprecated
    public static final int CHECK_INTERVAL_PUSH = -2;
    // The following two sentinel values are used by EAS
    // Ping indicates that the EAS mailbox is synced based on a "ping" from the server
    @Deprecated
    public static final int CHECK_INTERVAL_PING = -3;
    // Push-Hold indicates an EAS push or ping Mailbox shouldn't sync just yet
    @Deprecated
    public static final int CHECK_INTERVAL_PUSH_HOLD = -4;

    // Sentinel for PARENT_KEY.  Use NO_MAILBOX for toplevel mailboxes (i.e. no parents).
    public static final long PARENT_KEY_UNINITIALIZED = 0L;

    private static final String WHERE_TYPE_AND_ACCOUNT_KEY =
        MailboxColumns.TYPE + "=? and " + MailboxColumns.ACCOUNT_KEY + "=?";

    /**
     * Selection for mailboxes that should receive push for an account. A mailbox should receive
     * push if it has a valid, non-initial sync key and is opted in for sync.
     */
    private static final String PUSH_MAILBOXES_FOR_ACCOUNT_SELECTION =
            MailboxColumns.SYNC_KEY + " is not null and " + MailboxColumns.SYNC_KEY + "!='' and " +
                    MailboxColumns.SYNC_KEY + "!='0' and " + MailboxColumns.SYNC_INTERVAL +
                    "=1 and " + MailboxColumns.ACCOUNT_KEY + "=?";

    /** Selection for mailboxes that say they want to sync, plus outbox, for an account. */
    private static final String OUTBOX_PLUS_SYNCING_AND_ACCOUNT_SELECTION = "("
            + MailboxColumns.TYPE + "=" + Mailbox.TYPE_OUTBOX + " or "
            + MailboxColumns.SYNC_INTERVAL + "=1) and " + MailboxColumns.ACCOUNT_KEY + "=?";

    /** Selection for mailboxes that are configured for sync of a certain type for an account. */
    private static final String SYNCING_AND_TYPE_FOR_ACCOUNT_SELECTION =
            MailboxColumns.SYNC_INTERVAL + "=1 and " + MailboxColumns.TYPE + "=? and " +
                    MailboxColumns.ACCOUNT_KEY + "=?";

    // Types of mailboxes.  The list is ordered to match a typical UI presentation, e.g.
    // placing the inbox at the top.
    // Arrays of "special_mailbox_display_names" and "special_mailbox_icons" are depends on
    // types Id of mailboxes.
    /** No type specified */
    public static final int TYPE_NONE = -1;
    /** The "main" mailbox for the account, almost always referred to as "Inbox" */
    public static final int TYPE_INBOX = 0;
    // Types of mailboxes
    /** Generic mailbox that holds mail */
    public static final int TYPE_MAIL = 1;
    /** Parent-only mailbox; does not hold any mail */
    public static final int TYPE_PARENT = 2;
    /** Drafts mailbox */
    public static final int TYPE_DRAFTS = 3;
    /** Local mailbox associated with the account's outgoing mail */
    public static final int TYPE_OUTBOX = 4;
    /** Sent mail; mail that was sent from the account */
    public static final int TYPE_SENT = 5;
    /** Deleted mail */
    public static final int TYPE_TRASH = 6;
    /** Junk mail */
    public static final int TYPE_JUNK = 7;
    /** Search results */
    public static final int TYPE_SEARCH = 8;
    /** Starred (virtual) */
    public static final int TYPE_STARRED = 9;
    /** All unread mail (virtual) */
    public static final int TYPE_UNREAD = 10;

    // Types after this are used for non-mail mailboxes (as in EAS)
    public static final int TYPE_NOT_EMAIL = 0x40;
    public static final int TYPE_CALENDAR = 0x41;
    public static final int TYPE_CONTACTS = 0x42;
    public static final int TYPE_TASKS = 0x43;
    @Deprecated
    public static final int TYPE_EAS_ACCOUNT_MAILBOX = 0x44;
    public static final int TYPE_UNKNOWN = 0x45;

    /**
     * Specifies which mailbox types may be synced from server, and what the default sync interval
     * value should be.
     * If a mailbox type is in this array, then it can be synced.
     * If the mailbox type is mapped to true in this array, then new mailboxes of that type should
     * be set to automatically sync (either with the periodic poll, or with push, as determined
     * by the account's sync settings).
     * See {@link #isSyncableType} and {@link #getDefaultSyncStateForType} for how to access this
     * data.
     */
    private static final SparseBooleanArray SYNCABLE_TYPES;
    static {
        SYNCABLE_TYPES = new SparseBooleanArray(7);
        SYNCABLE_TYPES.put(TYPE_INBOX, true);
        SYNCABLE_TYPES.put(TYPE_MAIL, false);
        // TODO: b/11158759
        // For now, drafts folders are not syncable.
        //SYNCABLE_TYPES.put(TYPE_DRAFTS, true);
        SYNCABLE_TYPES.put(TYPE_SENT, true);
        SYNCABLE_TYPES.put(TYPE_TRASH, false);
        SYNCABLE_TYPES.put(TYPE_CALENDAR, true);
        SYNCABLE_TYPES.put(TYPE_CONTACTS, true);
    }

    public static final int TYPE_NOT_SYNCABLE = 0x100;
    // A mailbox that holds Messages that are attachments
    public static final int TYPE_ATTACHMENT = 0x101;

    /**
     * For each of the following folder types, we expect there to be exactly one folder of that
     * type per account.
     * Each sync adapter must do the following:
     * 1) On initial sync: For each type that was not found from the server, create a local folder.
     * 2) On folder delete: If it's of a required type, convert it to local rather than delete.
     * 3) On folder add: If it's of a required type, convert the local folder to server.
     * 4) When adding a duplicate (either initial sync or folder add): Error.
     */
    public static final int[] REQUIRED_FOLDER_TYPES =
            { TYPE_INBOX, TYPE_DRAFTS, TYPE_OUTBOX, TYPE_SENT, TYPE_TRASH };

    // Default "touch" time for system mailboxes
    public static final int DRAFTS_DEFAULT_TOUCH_TIME = 2;
    public static final int SENT_DEFAULT_TOUCH_TIME = 1;

    // Bit field flags; each is defined below
    // Warning: Do not read these flags until POP/IMAP/EAS all populate them
    /** No flags set */
    public static final int FLAG_NONE = 0;
    /** Has children in the mailbox hierarchy */
    public static final int FLAG_HAS_CHILDREN = 1<<0;
    /** Children are visible in the UI */
    public static final int FLAG_CHILDREN_VISIBLE = 1<<1;
    /** cannot receive "pushed" mail */
    public static final int FLAG_CANT_PUSH = 1<<2;
    /** can hold emails (i.e. some parent mailboxes cannot themselves contain mail) */
    public static final int FLAG_HOLDS_MAIL = 1<<3;
    /** can be used as a target for moving messages within the account */
    public static final int FLAG_ACCEPTS_MOVED_MAIL = 1<<4;
    /** can be used as a target for appending messages */
    public static final int FLAG_ACCEPTS_APPENDED_MAIL = 1<<5;
    /** has user settings (sync lookback, etc.) */
    public static final int FLAG_SUPPORTS_SETTINGS = 1<<6;

    // Magic mailbox ID's
    // NOTE:  This is a quick solution for merged mailboxes.  I would rather implement this
    // with a more generic way of packaging and sharing queries between activities
    public static final long QUERY_ALL_INBOXES = -2;
    public static final long QUERY_ALL_UNREAD = -3;
    public static final long QUERY_ALL_FAVORITES = -4;
    public static final long QUERY_ALL_DRAFTS = -5;
    public static final long QUERY_ALL_OUTBOX = -6;

    public Mailbox() {
        mBaseUri = CONTENT_URI;
    }

    public static String getSystemMailboxName(Context context, int mailboxType) {
        final int resId;
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
            case Mailbox.TYPE_STARRED:
                resId = R.string.mailbox_name_server_starred;
                break;
            case Mailbox.TYPE_UNREAD:
                resId = R.string.mailbox_name_server_all_unread;
                break;
            default:
                throw new IllegalArgumentException("Illegal mailbox type");
        }
        return context.getString(resId);
    }

     /**
     * Restore a Mailbox from the database, given its unique id
     * @param context Makes provider calls
     * @param id Row ID of mailbox to restore
     * @return the instantiated Mailbox
     */
    public static Mailbox restoreMailboxWithId(Context context, long id) {
        return EmailContent.restoreContentWithId(context, Mailbox.class,
                Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION, id);
    }

    /**
     * Builds a new mailbox with "typical" settings for a system mailbox, such as a local "Drafts"
     * mailbox. This is useful for protocols like POP3 or IMAP who don't have certain local
     * system mailboxes synced with the server.
     * Note: the mailbox is not persisted - clients must call {@link #save} themselves.
     */
    public static Mailbox newSystemMailbox(Context context, long accountId, int mailboxType) {
        // Sync interval and flags are different based on mailbox type.
        // TODO: Sync interval doesn't seem to be used anywhere, make it matter or get rid of it.
        final int syncInterval;
        final int flags;
        switch (mailboxType) {
            case TYPE_INBOX:
                flags = Mailbox.FLAG_HOLDS_MAIL | Mailbox.FLAG_ACCEPTS_MOVED_MAIL;
                syncInterval = 0;
                break;
            case TYPE_SENT:
            case TYPE_TRASH:
                flags = Mailbox.FLAG_HOLDS_MAIL;
                syncInterval = 0;
                break;
            case TYPE_DRAFTS:
            case TYPE_OUTBOX:
                flags = Mailbox.FLAG_HOLDS_MAIL;
                syncInterval = Account.CHECK_INTERVAL_NEVER;
                break;
            default:
                throw new IllegalArgumentException("Bad mailbox type for newSystemMailbox: " +
                        mailboxType);
        }

        final Mailbox box = new Mailbox();
        box.mAccountKey = accountId;
        box.mType = mailboxType;
        box.mSyncInterval = syncInterval;
        box.mFlagVisible = true;
        // TODO: Fix how display names work.
        box.mServerId = box.mDisplayName = getSystemMailboxName(context, mailboxType);
        box.mParentKey = Mailbox.NO_MAILBOX;
        box.mFlags = flags;
        return box;
    }

    /**
     * Returns a Mailbox from the database, given its pathname and account id. All mailbox
     * paths for a particular account must be unique. Paths are stored in the column
     * {@link MailboxColumns#SERVER_ID} for want of yet another column in the table.
     * @param context Makes provider calls
     * @param accountId the ID of the account
     * @param path the fully qualified, remote pathname
     */
    public static Mailbox restoreMailboxForPath(Context context, long accountId, String path) {
        final Cursor c = context.getContentResolver().query(
                Mailbox.CONTENT_URI,
                Mailbox.CONTENT_PROJECTION,
                Mailbox.PATH_AND_ACCOUNT_SELECTION,
                new String[] { path, Long.toString(accountId) },
                null);
        if (c == null) throw new ProviderUnavailableException();
        try {
            Mailbox mailbox = null;
            if (c.moveToFirst()) {
                mailbox = getContent(context, c, Mailbox.class);
                if (c.moveToNext()) {
                    LogUtils.w(Logging.LOG_TAG, "Multiple mailboxes named \"%s\"", path);
                }
            } else {
                LogUtils.i(Logging.LOG_TAG, "Could not find mailbox at \"%s\"", path);
            }
            return mailbox;
        } finally {
            c.close();
        }
    }

    /**
     * Returns a {@link Mailbox} for the given path. If the path is not in the database, a new
     * mailbox will be created.
     */
    public static Mailbox getMailboxForPath(Context context, long accountId, String path) {
        Mailbox mailbox = restoreMailboxForPath(context, accountId, path);
        if (mailbox == null) {
            mailbox = new Mailbox();
        }
        return mailbox;
    }

    /**
     * Check if a mailbox type can be synced with the server.
     * @param mailboxType The type to check.
     * @return Whether this type is syncable.
     */
    public static boolean isSyncableType(final int mailboxType) {
        return SYNCABLE_TYPES.indexOfKey(mailboxType) >= 0;
    }

    /**
     * Check if a mailbox type should sync with the server by default.
     * @param mailboxType The type to check.
     * @return Whether this type should default to syncing.
     */
    public static boolean getDefaultSyncStateForType(final int mailboxType) {
        return SYNCABLE_TYPES.get(mailboxType);
    }

    /**
     * Check whether this mailbox is syncable. It has to be both a server synced mailbox, and
     * of a syncable able.
     * @return Whether this mailbox is syncable.
     */
    public boolean isSyncable() {
        return (mTotalCount >= 0) && isSyncableType(mType);
    }

    @Override
    public void restore(Cursor cursor) {
        mBaseUri = CONTENT_URI;
        mId = cursor.getLong(CONTENT_ID_COLUMN);
        mDisplayName = cursor.getString(CONTENT_DISPLAY_NAME_COLUMN);
        mServerId = cursor.getString(CONTENT_SERVER_ID_COLUMN);
        mParentServerId = cursor.getString(CONTENT_PARENT_SERVER_ID_COLUMN);
        mParentKey = cursor.getLong(CONTENT_PARENT_KEY_COLUMN);
        mAccountKey = cursor.getLong(CONTENT_ACCOUNT_KEY_COLUMN);
        mType = cursor.getInt(CONTENT_TYPE_COLUMN);
        mDelimiter = cursor.getInt(CONTENT_DELIMITER_COLUMN);
        mSyncKey = cursor.getString(CONTENT_SYNC_KEY_COLUMN);
        mSyncLookback = cursor.getInt(CONTENT_SYNC_LOOKBACK_COLUMN);
        mSyncInterval = cursor.getInt(CONTENT_SYNC_INTERVAL_COLUMN);
        mSyncTime = cursor.getLong(CONTENT_SYNC_TIME_COLUMN);
        mFlagVisible = cursor.getInt(CONTENT_FLAG_VISIBLE_COLUMN) == 1;
        mFlags = cursor.getInt(CONTENT_FLAGS_COLUMN);
        mSyncStatus = cursor.getString(CONTENT_SYNC_STATUS_COLUMN);
        mLastTouchedTime = cursor.getLong(CONTENT_LAST_TOUCHED_TIME_COLUMN);
        mUiSyncStatus = cursor.getInt(CONTENT_UI_SYNC_STATUS_COLUMN);
        mUiLastSyncResult = cursor.getInt(CONTENT_UI_LAST_SYNC_RESULT_COLUMN);
        mTotalCount = cursor.getInt(CONTENT_TOTAL_COUNT_COLUMN);
        mHierarchicalName = cursor.getString(CONTENT_HIERARCHICAL_NAME_COLUMN);
        mLastFullSyncTime = cursor.getInt(CONTENT_LAST_FULL_SYNC_COLUMN);
    }

    @Override
    public ContentValues toContentValues() {
        final ContentValues values = new ContentValues(20);
        values.put(MailboxColumns.DISPLAY_NAME, mDisplayName);
        values.put(MailboxColumns.SERVER_ID, mServerId);
        values.put(MailboxColumns.PARENT_SERVER_ID, mParentServerId);
        values.put(MailboxColumns.PARENT_KEY, mParentKey);
        values.put(MailboxColumns.ACCOUNT_KEY, mAccountKey);
        values.put(MailboxColumns.TYPE, mType);
        values.put(MailboxColumns.DELIMITER, mDelimiter);
        values.put(MailboxColumns.SYNC_KEY, mSyncKey);
        values.put(MailboxColumns.SYNC_LOOKBACK, mSyncLookback);
        values.put(MailboxColumns.SYNC_INTERVAL, mSyncInterval);
        values.put(MailboxColumns.SYNC_TIME, mSyncTime);
        values.put(MailboxColumns.FLAG_VISIBLE, mFlagVisible);
        values.put(MailboxColumns.FLAGS, mFlags);
        values.put(MailboxColumns.SYNC_STATUS, mSyncStatus);
        values.put(MailboxColumns.LAST_TOUCHED_TIME, mLastTouchedTime);
        values.put(MailboxColumns.UI_SYNC_STATUS, mUiSyncStatus);
        values.put(MailboxColumns.UI_LAST_SYNC_RESULT, mUiLastSyncResult);
        values.put(MailboxColumns.TOTAL_COUNT, mTotalCount);
        values.put(MailboxColumns.HIERARCHICAL_NAME, mHierarchicalName);
        values.put(MailboxColumns.LAST_FULL_SYNC_TIME, mLastFullSyncTime);
        return values;
    }

    /**
     * Store the updated message count in the database.
     * @param c Makes provider calls
     * @param count New count
     */
    public void updateMessageCount(final Context c, final int count) {
        if (count != mTotalCount) {
            final ContentValues values = new ContentValues(1);
            values.put(MailboxColumns.TOTAL_COUNT, count);
            update(c, values);
            mTotalCount = count;
        }
    }

    /**
     * Store the last full sync time in the database.
     * @param c Makes provider calls
     * @param syncTime New syncTime
     */
    public void updateLastFullSyncTime(final Context c, final long syncTime) {
        if (syncTime != mLastFullSyncTime) {
            final ContentValues values = new ContentValues(1);
            values.put(MailboxColumns.LAST_FULL_SYNC_TIME, syncTime);
            update(c, values);
            mLastFullSyncTime = syncTime;
        }
    }

    /**
     * Convenience method to return the id of a given type of Mailbox for a given Account; the
     * common Mailbox types (Inbox, Outbox, Sent, Drafts, Trash, and Search) are all cached by
     * EmailProvider; therefore, we warn if the mailbox is not found in the cache
     *
     * @param context the caller's context, used to get a ContentResolver
     * @param accountId the id of the account to be queried
     * @param type the mailbox type, as defined above
     * @return the id of the mailbox, or -1 if not found
     */
    public static long findMailboxOfType(Context context, long accountId, int type) {
        final String[] bindArguments = new String[] {Long.toString(type), Long.toString(accountId)};
        return Utility.getFirstRowLong(context, Mailbox.CONTENT_URI,
                ID_PROJECTION, WHERE_TYPE_AND_ACCOUNT_KEY, bindArguments, null,
                ID_PROJECTION_COLUMN, NO_MAILBOX);
    }

    /**
     * Convenience method that returns the mailbox found using the method above
     */
    public static Mailbox restoreMailboxOfType(Context context, long accountId, int type) {
        final long mailboxId = findMailboxOfType(context, accountId, type);
        if (mailboxId != Mailbox.NO_MAILBOX) {
            return Mailbox.restoreMailboxWithId(context, mailboxId);
        }
        return null;
    }

    /**
     * Return the mailbox for a message with a given id
     * @param context the caller's context
     * @param messageId the id of the message
     * @return the mailbox, or null if the mailbox doesn't exist
     */
    public static Mailbox getMailboxForMessageId(Context context, long messageId) {
        final long mailboxId = Message.getKeyColumnLong(context, messageId,
                MessageColumns.MAILBOX_KEY);
        if (mailboxId != -1) {
            return Mailbox.restoreMailboxWithId(context, mailboxId);
        }
        return null;
    }

    /**
     * @return mailbox type, or -1 if mailbox not found.
     */
    public static int getMailboxType(Context context, long mailboxId) {
        final Uri url = ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId);
        return Utility.getFirstRowInt(context, url, MAILBOX_TYPE_PROJECTION,
                null, null, null, MAILBOX_TYPE_TYPE_COLUMN, -1);
    }

    /**
     * @return mailbox display name, or null if mailbox not found.
     */
    public static String getDisplayName(Context context, long mailboxId) {
        final Uri url = ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId);
        return Utility.getFirstRowString(context, url, MAILBOX_DISPLAY_NAME_PROJECTION,
                null, null, null, MAILBOX_DISPLAY_NAME_COLUMN);
    }

    /**
     * @param mailboxId ID of a mailbox.  This method accepts magic mailbox IDs, such as
     * {@link #QUERY_ALL_INBOXES}. (They're all non-refreshable.)
     * @return true if a mailbox is refreshable.
     */
    public static boolean isRefreshable(Context context, long mailboxId) {
        if (mailboxId < 0) {
            return false; // magic mailboxes
        }
        switch (getMailboxType(context, mailboxId)) {
            case -1: // not found
            case TYPE_DRAFTS:
            case TYPE_OUTBOX:
                return false;
        }
        return true;
    }

    /**
     * @return whether or not this mailbox supports moving messages out of it
     */
    public boolean canHaveMessagesMoved() {
        switch (mType) {
            case TYPE_INBOX:
            case TYPE_MAIL:
            case TYPE_TRASH:
            case TYPE_JUNK:
                return true;
        }
        return false; // TYPE_DRAFTS, TYPE_OUTBOX, TYPE_SENT, etc
    }

    /**
     * Returns a set of hashes that can identify this mailbox. These can be used to
     * determine if any of the fields have been modified.
     */
    public Object[] getHashes() {
        final Object[] hash = new Object[CONTENT_PROJECTION.length];

        hash[CONTENT_ID_COLUMN]
             = mId;
        hash[CONTENT_DISPLAY_NAME_COLUMN]
                = mDisplayName;
        hash[CONTENT_SERVER_ID_COLUMN]
                = mServerId;
        hash[CONTENT_PARENT_SERVER_ID_COLUMN]
                = mParentServerId;
        hash[CONTENT_ACCOUNT_KEY_COLUMN]
                = mAccountKey;
        hash[CONTENT_TYPE_COLUMN]
                = mType;
        hash[CONTENT_DELIMITER_COLUMN]
                = mDelimiter;
        hash[CONTENT_SYNC_KEY_COLUMN]
                = mSyncKey;
        hash[CONTENT_SYNC_LOOKBACK_COLUMN]
                = mSyncLookback;
        hash[CONTENT_SYNC_INTERVAL_COLUMN]
                = mSyncInterval;
        hash[CONTENT_SYNC_TIME_COLUMN]
                = mSyncTime;
        hash[CONTENT_FLAG_VISIBLE_COLUMN]
                = mFlagVisible;
        hash[CONTENT_FLAGS_COLUMN]
                = mFlags;
        hash[CONTENT_SYNC_STATUS_COLUMN]
                = mSyncStatus;
        hash[CONTENT_PARENT_KEY_COLUMN]
                = mParentKey;
        hash[CONTENT_LAST_TOUCHED_TIME_COLUMN]
                = mLastTouchedTime;
        hash[CONTENT_UI_SYNC_STATUS_COLUMN]
                = mUiSyncStatus;
        hash[CONTENT_UI_LAST_SYNC_RESULT_COLUMN]
                = mUiLastSyncResult;
        hash[CONTENT_TOTAL_COUNT_COLUMN]
                = mTotalCount;
        hash[CONTENT_HIERARCHICAL_NAME_COLUMN]
                = mHierarchicalName;
        return hash;
    }

    // Parcelable
    @Override
    public int describeContents() {
        return 0;
    }

    // Parcelable
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mBaseUri, flags);
        dest.writeLong(mId);
        dest.writeString(mDisplayName);
        dest.writeString(mServerId);
        dest.writeString(mParentServerId);
        dest.writeLong(mParentKey);
        dest.writeLong(mAccountKey);
        dest.writeInt(mType);
        dest.writeInt(mDelimiter);
        dest.writeString(mSyncKey);
        dest.writeInt(mSyncLookback);
        dest.writeInt(mSyncInterval);
        dest.writeLong(mSyncTime);
        dest.writeInt(mFlagVisible ? 1 : 0);
        dest.writeInt(mFlags);
        dest.writeString(mSyncStatus);
        dest.writeLong(mLastTouchedTime);
        dest.writeInt(mUiSyncStatus);
        dest.writeInt(mUiLastSyncResult);
        dest.writeInt(mTotalCount);
        dest.writeString(mHierarchicalName);
        dest.writeLong(mLastFullSyncTime);
    }

    public Mailbox(Parcel in) {
        mBaseUri = in.readParcelable(null);
        mId = in.readLong();
        mDisplayName = in.readString();
        mServerId = in.readString();
        mParentServerId = in.readString();
        mParentKey = in.readLong();
        mAccountKey = in.readLong();
        mType = in.readInt();
        mDelimiter = in.readInt();
        mSyncKey = in.readString();
        mSyncLookback = in.readInt();
        mSyncInterval = in.readInt();
        mSyncTime = in.readLong();
        mFlagVisible = in.readInt() == 1;
        mFlags = in.readInt();
        mSyncStatus = in.readString();
        mLastTouchedTime = in.readLong();
        mUiSyncStatus = in.readInt();
        mUiLastSyncResult = in.readInt();
        mTotalCount = in.readInt();
        mHierarchicalName = in.readString();
        mLastFullSyncTime = in.readLong();
    }

    public static final Parcelable.Creator<Mailbox> CREATOR = new Parcelable.Creator<Mailbox>() {
        @Override
        public Mailbox createFromParcel(Parcel source) {
            return new Mailbox(source);
        }

        @Override
        public Mailbox[] newArray(int size) {
            return new Mailbox[size];
        }
    };

    @Override
    public String toString() {
        return "[Mailbox " + mId + ": " + mDisplayName + "]";
    }

    /**
     * Get the mailboxes that should receive push updates for an account.
     * @param cr The {@link ContentResolver}.
     * @param accountId The id for the account that is pushing.
     * @return A cursor (suitable for use with {@link #restore}) with all mailboxes we should sync.
     */
    public static Cursor getMailboxesForPush(final ContentResolver cr, final long accountId) {
        return cr.query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                PUSH_MAILBOXES_FOR_ACCOUNT_SELECTION, new String[] { Long.toString(accountId) },
                null);
    }

    /**
     * Get the mailbox ids for an account that should sync when we do a full account sync.
     * @param cr The {@link ContentResolver}.
     * @param accountId The id for the account that is pushing.
     * @return A cursor (with one column, containing ids) with all mailbox ids we should sync.
     */
    public static Cursor getMailboxIdsForSync(final ContentResolver cr, final long accountId) {
        // We're sorting by mailbox type. The reason is that the inbox is type 0, other types
        // (e.g. Calendar and Contacts) are all higher numbers. Upon initial sync, we'd like to
        // sync the inbox first to improve perceived performance.
        return cr.query(Mailbox.CONTENT_URI, Mailbox.ID_PROJECTION,
                OUTBOX_PLUS_SYNCING_AND_ACCOUNT_SELECTION,
                new String[] { Long.toString(accountId) }, MailboxColumns.TYPE + " ASC");
    }

    /**
     * Get the mailbox ids for an account that are configured for sync and have a specific type.
     * @param accountId The id for the account that is syncing.
     * @param mailboxType The type of the mailbox we're interested in.
     * @return A cursor (with one column, containing ids) with all mailbox ids that match.
     */
    public static Cursor getMailboxIdsForSyncByType(final ContentResolver cr, final long accountId,
            final int mailboxType) {
        return cr.query(Mailbox.CONTENT_URI, Mailbox.ID_PROJECTION,
                SYNCING_AND_TYPE_FOR_ACCOUNT_SELECTION,
                new String[] { Integer.toString(mailboxType), Long.toString(accountId) }, null);
    }

    /**
     * Get the account id for a mailbox.
     * @param context The {@link Context}.
     * @param mailboxId The id of the mailbox we're interested in, as a {@link String}.
     * @return The account id for the mailbox, or {@link Account#NO_ACCOUNT} if the mailbox doesn't
     *         exist.
     */
    public static long getAccountIdForMailbox(final Context context, final String mailboxId) {
        return Utility.getFirstRowLong(context,
                Mailbox.CONTENT_URI.buildUpon().appendEncodedPath(mailboxId).build(),
                ACCOUNT_KEY_PROJECTION, null, null, null,
                ACCOUNT_KEY_PROJECTION_ACCOUNT_KEY_COLUMN, Account.NO_ACCOUNT);
    }

    /**
     * Gets the correct authority for a mailbox.
     * @param mailboxType The type of the mailbox we're interested in.
     * @return The authority for the mailbox we're interested in.
     */
    public static String getAuthority(final int mailboxType) {
        switch (mailboxType) {
            case Mailbox.TYPE_CALENDAR:
                return CalendarContract.AUTHORITY;
            case Mailbox.TYPE_CONTACTS:
                return ContactsContract.AUTHORITY;
            default:
                return EmailContent.AUTHORITY;
        }
    }

    public static void resyncMailbox(final ContentResolver cr,
            final android.accounts.Account account, final long mailboxId) {
        final Cursor cursor = cr.query(Mailbox.CONTENT_URI,
                new String[]{
                        MailboxColumns.TYPE,
                        MailboxColumns.SERVER_ID,
                },
                MailboxColumns._ID + "=?",
                new String[] {String.valueOf(mailboxId)},
                null);
        if (cursor == null || cursor.getCount() == 0) {
            LogUtils.w(Logging.LOG_TAG, "Mailbox %d not found", mailboxId);
            return;
        }
        try {
            cursor.moveToFirst();
            final int type = cursor.getInt(0);
            if (type >= TYPE_NOT_EMAIL) {
                throw new IllegalArgumentException(
                        String.format("Mailbox %d is not an Email mailbox", mailboxId));
            }
            final String serverId = cursor.getString(1);
            if (TextUtils.isEmpty(serverId)) {
                throw new IllegalArgumentException(
                        String.format("Mailbox %d has no server id", mailboxId));
            }
            final ArrayList<ContentProviderOperation> ops =
                    new ArrayList<ContentProviderOperation>();
            ops.add(ContentProviderOperation.newDelete(Message.CONTENT_URI)
                    .withSelection(Message.MAILBOX_SELECTION,
                            new String[]{String.valueOf(mailboxId)})
                    .build());
            ops.add(ContentProviderOperation.newUpdate(
                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId))
                    .withValue(MailboxColumns.SYNC_KEY, "0").build());

            cr.applyBatch(AUTHORITY, ops);
            final Bundle extras = createSyncBundle(mailboxId);
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true);
            ContentResolver.requestSync(account, AUTHORITY, extras);
            LogUtils.i(Logging.LOG_TAG, "requestSync resyncMailbox %s, %s",
                    account.toString(), extras.toString());
        } catch (RemoteException e) {
            LogUtils.w(Logging.LOG_TAG, e, "Failed to wipe mailbox %d", mailboxId);
        } catch (OperationApplicationException e) {
            LogUtils.w(Logging.LOG_TAG, e, "Failed to wipe mailbox %d", mailboxId);
        } finally {
            cursor.close();
        }
    }
}
