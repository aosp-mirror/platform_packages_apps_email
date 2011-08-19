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

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.utility.Utility;

public class Mailbox extends EmailContent implements SyncColumns, MailboxColumns, Parcelable {
    public static final String TABLE_NAME = "Mailbox";
    @SuppressWarnings("hiding")
    public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/mailbox");
    public static final Uri ADD_TO_FIELD_URI =
        Uri.parse(EmailContent.CONTENT_URI + "/mailboxIdAddToField");
    public static final Uri FROM_ACCOUNT_AND_TYPE_URI =
        Uri.parse(EmailContent.CONTENT_URI + "/mailboxIdFromAccountAndType");

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
    public int mVisibleLimit;
    public String mSyncStatus;
    public long mLastSeenMessageKey;
    public long mLastTouchedTime;

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
    public static final int CONTENT_VISIBLE_LIMIT_COLUMN = 13;
    public static final int CONTENT_SYNC_STATUS_COLUMN = 14;
    public static final int CONTENT_PARENT_KEY_COLUMN = 15;
    public static final int CONTENT_LAST_SEEN_MESSAGE_KEY_COLUMN = 16;
    public static final int CONTENT_LAST_TOUCHED_TIME_COLUMN = 17;

    /**
     * <em>NOTE</em>: If fields are added or removed, the method {@link #getHashes()}
     * MUST be updated.
     */
    public static final String[] CONTENT_PROJECTION = new String[] {
        RECORD_ID, MailboxColumns.DISPLAY_NAME, MailboxColumns.SERVER_ID,
        MailboxColumns.PARENT_SERVER_ID, MailboxColumns.ACCOUNT_KEY, MailboxColumns.TYPE,
        MailboxColumns.DELIMITER, MailboxColumns.SYNC_KEY, MailboxColumns.SYNC_LOOKBACK,
        MailboxColumns.SYNC_INTERVAL, MailboxColumns.SYNC_TIME,
        MailboxColumns.FLAG_VISIBLE, MailboxColumns.FLAGS, MailboxColumns.VISIBLE_LIMIT,
        MailboxColumns.SYNC_STATUS, MailboxColumns.PARENT_KEY,
        MailboxColumns.LAST_SEEN_MESSAGE_KEY, MailboxColumns.LAST_TOUCHED_TIME,
    };

    private static final String ACCOUNT_AND_MAILBOX_TYPE_SELECTION =
            MailboxColumns.ACCOUNT_KEY + " =? AND " +
            MailboxColumns.TYPE + " =?";
    private static final String MAILBOX_TYPE_SELECTION =
            MailboxColumns.TYPE + " =?";
    /** Selection by server pathname for a given account */
    public static final String PATH_AND_ACCOUNT_SELECTION =
        MailboxColumns.SERVER_ID + "=? and " + MailboxColumns.ACCOUNT_KEY + "=?";

    private static final String[] MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION = new String [] {
            "sum(" + MailboxColumns.UNREAD_COUNT + ")"
            };
    private static final int UNREAD_COUNT_COUNT_COLUMN = 0;
    private static final String[] MAILBOX_SUM_OF_MESSAGE_COUNT_PROJECTION = new String [] {
            "sum(" + MailboxColumns.MESSAGE_COUNT + ")"
            };
    private static final int MESSAGE_COUNT_COUNT_COLUMN = 0;

    private static final String[] MAILBOX_TYPE_PROJECTION = new String [] {
            MailboxColumns.TYPE
            };
    private static final int MAILBOX_TYPE_TYPE_COLUMN = 0;

    private static final String[] MAILBOX_DISPLAY_NAME_PROJECTION = new String [] {
            MailboxColumns.DISPLAY_NAME
            };
    private static final int MAILBOX_DISPLAY_NAME_COLUMN = 0;

    public static final long NO_MAILBOX = -1;

    // Sentinel values for the mSyncInterval field of both Mailbox records
    public static final int CHECK_INTERVAL_NEVER = -1;
    public static final int CHECK_INTERVAL_PUSH = -2;
    // The following two sentinel values are used by EAS
    // Ping indicates that the EAS mailbox is synced based on a "ping" from the server
    public static final int CHECK_INTERVAL_PING = -3;
    // Push-Hold indicates an EAS push or ping Mailbox shouldn't sync just yet
    public static final int CHECK_INTERVAL_PUSH_HOLD = -4;

    // Sentinel for PARENT_KEY.  Use NO_MAILBOX for toplevel mailboxes (i.e. no parents).
    public static final long PARENT_KEY_UNINITIALIZED = 0L;

    private static final String WHERE_TYPE_AND_ACCOUNT_KEY =
        MailboxColumns.TYPE + "=? and " + MailboxColumns.ACCOUNT_KEY + "=?";

    public static final Integer[] INVALID_DROP_TARGETS = new Integer[] {Mailbox.TYPE_DRAFTS,
        Mailbox.TYPE_OUTBOX, Mailbox.TYPE_SENT};

    public static final String USER_VISIBLE_MAILBOX_SELECTION =
        MailboxColumns.TYPE + "<" + Mailbox.TYPE_NOT_EMAIL +
        " AND " + MailboxColumns.FLAG_VISIBLE + "=1";

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

    // Types after this are used for non-mail mailboxes (as in EAS)
    public static final int TYPE_NOT_EMAIL = 0x40;
    public static final int TYPE_CALENDAR = 0x41;
    public static final int TYPE_CONTACTS = 0x42;
    public static final int TYPE_TASKS = 0x43;
    public static final int TYPE_EAS_ACCOUNT_MAILBOX = 0x44;
    public static final int TYPE_UNKNOWN = 0x45;

    public static final int TYPE_NOT_SYNCABLE = 0x100;
    // A mailbox that holds Messages that are attachments
    public static final int TYPE_ATTACHMENT = 0x101;

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

     /**
     * Restore a Mailbox from the database, given its unique id
     * @param context
     * @param id
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
    public static Mailbox newSystemMailbox(long accountId, int mailboxType, String name) {
        if (mailboxType == Mailbox.TYPE_MAIL) {
            throw new IllegalArgumentException("Cannot specify TYPE_MAIL for a system mailbox");
        }
        Mailbox box = new Mailbox();
        box.mAccountKey = accountId;
        box.mType = mailboxType;
        box.mSyncInterval = Account.CHECK_INTERVAL_NEVER;
        box.mFlagVisible = true;
        box.mServerId = box.mDisplayName = name;
        box.mParentKey = Mailbox.NO_MAILBOX;
        box.mFlags = Mailbox.FLAG_HOLDS_MAIL;
        return box;
    }

    /**
     * Returns a Mailbox from the database, given its pathname and account id. All mailbox
     * paths for a particular account must be unique. Paths are stored in the column
     * {@link MailboxColumns#SERVER_ID} for want of yet another column in the table.
     * @param context
     * @param accountId the ID of the account
     * @param path the fully qualified, remote pathname
     */
    public static Mailbox restoreMailboxForPath(Context context, long accountId, String path) {
        Cursor c = context.getContentResolver().query(
                Mailbox.CONTENT_URI,
                Mailbox.CONTENT_PROJECTION,
                Mailbox.PATH_AND_ACCOUNT_SELECTION,
                new String[] { path, Long.toString(accountId) },
                null);
        if (c == null) throw new ProviderUnavailableException();
        try {
            Mailbox mailbox = null;
            if (c.moveToFirst()) {
                mailbox = getContent(c, Mailbox.class);
                if (c.moveToNext()) {
                    Log.w(Logging.LOG_TAG, "Multiple mailboxes named \"" + path + "\"");
                }
            } else {
                Log.i(Logging.LOG_TAG, "Could not find mailbox at \"" + path + "\"");
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
        mVisibleLimit = cursor.getInt(CONTENT_VISIBLE_LIMIT_COLUMN);
        mSyncStatus = cursor.getString(CONTENT_SYNC_STATUS_COLUMN);
        mLastSeenMessageKey = cursor.getLong(CONTENT_LAST_SEEN_MESSAGE_KEY_COLUMN);
        mLastTouchedTime = cursor.getLong(CONTENT_LAST_TOUCHED_TIME_COLUMN);
    }

    @Override
    public ContentValues toContentValues() {
        ContentValues values = new ContentValues();
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
        values.put(MailboxColumns.VISIBLE_LIMIT, mVisibleLimit);
        values.put(MailboxColumns.SYNC_STATUS, mSyncStatus);
        values.put(MailboxColumns.LAST_SEEN_MESSAGE_KEY, mLastSeenMessageKey);
        values.put(MailboxColumns.LAST_TOUCHED_TIME, mLastTouchedTime);
        return values;
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
        // First use special URI
        Uri uri = FROM_ACCOUNT_AND_TYPE_URI.buildUpon().appendPath(Long.toString(accountId))
            .appendPath(Integer.toString(type)).build();
        Cursor c = context.getContentResolver().query(uri, ID_PROJECTION, null, null, null);
        if (c != null) {
            try {
                c.moveToFirst();
                Long mailboxId = c.getLong(ID_PROJECTION_COLUMN);
                if (mailboxId != null
                        && mailboxId != 0L
                        && mailboxId != NO_MAILBOX) {
                    return mailboxId;
                }
            } finally {
                c.close();
            }
        }
        // Fallback to querying the database directly.
        String[] bindArguments = new String[] {Long.toString(type), Long.toString(accountId)};
        return Utility.getFirstRowLong(context, Mailbox.CONTENT_URI,
                ID_PROJECTION, WHERE_TYPE_AND_ACCOUNT_KEY, bindArguments, null,
                ID_PROJECTION_COLUMN, NO_MAILBOX);
    }

    /**
     * Convenience method that returns the mailbox found using the method above
     */
    public static Mailbox restoreMailboxOfType(Context context, long accountId, int type) {
        long mailboxId = findMailboxOfType(context, accountId, type);
        if (mailboxId != Mailbox.NO_MAILBOX) {
            return Mailbox.restoreMailboxWithId(context, mailboxId);
        }
        return null;
    }

    public static int getUnreadCountByAccountAndMailboxType(Context context, long accountId,
            int type) {
        return Utility.getFirstRowInt(context, Mailbox.CONTENT_URI,
                MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION,
                ACCOUNT_AND_MAILBOX_TYPE_SELECTION,
                new String[] { String.valueOf(accountId), String.valueOf(type) },
                null, UNREAD_COUNT_COUNT_COLUMN, 0);
    }

    public static int getUnreadCountByMailboxType(Context context, int type) {
        return Utility.getFirstRowInt(context, Mailbox.CONTENT_URI,
                MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION,
                MAILBOX_TYPE_SELECTION,
                new String[] { String.valueOf(type) }, null, UNREAD_COUNT_COUNT_COLUMN, 0);
    }

    public static int getMessageCountByMailboxType(Context context, int type) {
        return Utility.getFirstRowInt(context, Mailbox.CONTENT_URI,
                MAILBOX_SUM_OF_MESSAGE_COUNT_PROJECTION,
                MAILBOX_TYPE_SELECTION,
                new String[] { String.valueOf(type) }, null, MESSAGE_COUNT_COUNT_COLUMN, 0);
    }

    /**
     * Return the mailbox for a message with a given id
     * @param context the caller's context
     * @param messageId the id of the message
     * @return the mailbox, or null if the mailbox doesn't exist
     */
    public static Mailbox getMailboxForMessageId(Context context, long messageId) {
        long mailboxId = Message.getKeyColumnLong(context, messageId,
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
        Uri url = ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId);
        return Utility.getFirstRowInt(context, url, MAILBOX_TYPE_PROJECTION,
                null, null, null, MAILBOX_TYPE_TYPE_COLUMN, -1);
    }

    /**
     * @return mailbox display name, or null if mailbox not found.
     */
    public static String getDisplayName(Context context, long mailboxId) {
        Uri url = ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId);
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
     * @return whether or not this mailbox retrieves its data from the server (as opposed to just
     *     a local mailbox that is never synced).
     */
    public boolean loadsFromServer(String protocol) {
        if (HostAuth.SCHEME_EAS.equals(protocol)) {
            return mType != Mailbox.TYPE_DRAFTS
                    && mType != Mailbox.TYPE_OUTBOX
                    && mType != Mailbox.TYPE_SEARCH
                    && mType < Mailbox.TYPE_NOT_SYNCABLE;

        } else if (HostAuth.SCHEME_IMAP.equals(protocol)) {
            // TODO: actually use a sync flag when creating the mailboxes. Right now we use an
            // approximation for IMAP.
            return mType != Mailbox.TYPE_DRAFTS
                    && mType != Mailbox.TYPE_OUTBOX
                    && mType != Mailbox.TYPE_SEARCH;

        } else if (HostAuth.SCHEME_POP3.equals(protocol)) {
            return TYPE_INBOX == mType;
        }

        return false;
    }

    /**
     * @return true if messages in a mailbox of a type can be replied/forwarded.
     */
    public static boolean isMailboxTypeReplyAndForwardable(int type) {
        return (type != TYPE_TRASH) && (type != TYPE_DRAFTS);
    }

    /**
     * Returns a set of hashes that can identify this mailbox. These can be used to
     * determine if any of the fields have been modified.
     */
    public Object[] getHashes() {
        Object[] hash = new Object[CONTENT_PROJECTION.length];

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
        hash[CONTENT_VISIBLE_LIMIT_COLUMN]
                = mVisibleLimit;
        hash[CONTENT_SYNC_STATUS_COLUMN]
                = mSyncStatus;
        hash[CONTENT_PARENT_KEY_COLUMN]
                = mParentKey;
        hash[CONTENT_LAST_SEEN_MESSAGE_KEY_COLUMN]
                = mLastSeenMessageKey;
        hash[CONTENT_LAST_TOUCHED_TIME_COLUMN]
                = mLastTouchedTime;
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
        dest.writeInt(mVisibleLimit);
        dest.writeString(mSyncStatus);
        dest.writeLong(mLastSeenMessageKey);
        dest.writeLong(mLastTouchedTime);
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
        mVisibleLimit = in.readInt();
        mSyncStatus = in.readString();
        mLastSeenMessageKey = in.readLong();
        mLastTouchedTime = in.readLong();
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
}
