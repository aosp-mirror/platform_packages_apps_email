/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.utility.Utility;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Account extends EmailContent implements AccountColumns, Parcelable {
    public static final String TABLE_NAME = "Account";
    @SuppressWarnings("hiding")
    public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/account");
    public static final Uri ADD_TO_FIELD_URI =
        Uri.parse(EmailContent.CONTENT_URI + "/accountIdAddToField");
    public static final Uri RESET_NEW_MESSAGE_COUNT_URI =
        Uri.parse(EmailContent.CONTENT_URI + "/resetNewMessageCount");
    public static final Uri NOTIFIER_URI =
        Uri.parse(EmailContent.CONTENT_NOTIFIER_URI + "/account");
    public static final Uri DEFAULT_ACCOUNT_ID_URI =
        Uri.parse(EmailContent.CONTENT_URI + "/account/default");

    // Define all pseudo account IDs here to avoid conflict with one another.
    /**
     * Pseudo account ID to represent a "combined account" that includes messages and mailboxes
     * from all defined accounts.
     *
     * <em>IMPORTANT</em>: This must never be stored to the database.
     */
    public static final long ACCOUNT_ID_COMBINED_VIEW = 0x1000000000000000L;
    /**
     * Pseudo account ID to represent "no account". This may be used any time the account ID
     * may not be known or when we want to specifically select "no" account.
     *
     * <em>IMPORTANT</em>: This must never be stored to the database.
     */
    public static final long NO_ACCOUNT = -1L;

    // Whether or not the user has asked for notifications of new mail in this account
    public final static int FLAGS_NOTIFY_NEW_MAIL = 1<<0;
    // Whether or not the user has asked for vibration notifications with all new mail
    public final static int FLAGS_VIBRATE = 1<<1;
    // Bit mask for the account's deletion policy (see DELETE_POLICY_x below)
    public static final int FLAGS_DELETE_POLICY_MASK = 1<<2 | 1<<3;
    public static final int FLAGS_DELETE_POLICY_SHIFT = 2;
    // Whether the account is in the process of being created; any account reconciliation code
    // MUST ignore accounts with this bit set; in addition, ContentObservers for this data
    // SHOULD consider the state of this flag during operation
    public static final int FLAGS_INCOMPLETE = 1<<4;
    // Security hold is used when the device is not in compliance with security policies
    // required by the server; in this state, the user MUST be alerted to the need to update
    // security settings.  Sync adapters SHOULD NOT attempt to sync when this flag is set.
    public static final int FLAGS_SECURITY_HOLD = 1<<5;
    // Whether the account supports "smart forward" (i.e. the server appends the original
    // message along with any attachments to the outgoing message)
    public static final int FLAGS_SUPPORTS_SMART_FORWARD = 1<<7;
    // Whether the account should try to cache attachments in the background
    public static final int FLAGS_BACKGROUND_ATTACHMENTS = 1<<8;
    // Available to sync adapter
    public static final int FLAGS_SYNC_ADAPTER = 1<<9;
    // Sync disabled is a status commanded by the server; the sync adapter SHOULD NOT try to
    // sync mailboxes in this account automatically.  A manual sync request to sync a mailbox
    // with sync disabled SHOULD try to sync and report any failure result via the UI.
    public static final int FLAGS_SYNC_DISABLED = 1<<10;
    // Whether or not server-side search is supported by this account
    public static final int FLAGS_SUPPORTS_SEARCH = 1<<11;
    // Whether or not server-side search supports global search (i.e. all mailboxes); only valid
    // if FLAGS_SUPPORTS_SEARCH is true
    public static final int FLAGS_SUPPORTS_GLOBAL_SEARCH = 1<<12;

    // Deletion policy (see FLAGS_DELETE_POLICY_MASK, above)
    public static final int DELETE_POLICY_NEVER = 0;
    public static final int DELETE_POLICY_7DAYS = 1<<0;        // not supported
    public static final int DELETE_POLICY_ON_DELETE = 1<<1;

    // Sentinel values for the mSyncInterval field of both Account records
    public static final int CHECK_INTERVAL_NEVER = -1;
    public static final int CHECK_INTERVAL_PUSH = -2;

    public String mDisplayName;
    public String mEmailAddress;
    public String mSyncKey;
    public int mSyncLookback;
    public int mSyncInterval;
    public long mHostAuthKeyRecv;
    public long mHostAuthKeySend;
    public int mFlags;
    public boolean mIsDefault;          // note: callers should use getDefaultAccountId()
    public String mCompatibilityUuid;
    public String mSenderName;
    public String mRingtoneUri;
    public String mProtocolVersion;
    public int mNewMessageCount;
    public String mSecuritySyncKey;
    public String mSignature;
    public long mPolicyKey;

    // For compatibility with Email1
    public long mNotifiedMessageId;
    public int mNotifiedMessageCount;

    // Convenience for creating/working with an account
    public transient HostAuth mHostAuthRecv;
    public transient HostAuth mHostAuthSend;
    public transient Policy mPolicy;
    // Might hold the corresponding AccountManager account structure
    public transient android.accounts.Account mAmAccount;

    public static final int CONTENT_ID_COLUMN = 0;
    public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
    public static final int CONTENT_EMAIL_ADDRESS_COLUMN = 2;
    public static final int CONTENT_SYNC_KEY_COLUMN = 3;
    public static final int CONTENT_SYNC_LOOKBACK_COLUMN = 4;
    public static final int CONTENT_SYNC_INTERVAL_COLUMN = 5;
    public static final int CONTENT_HOST_AUTH_KEY_RECV_COLUMN = 6;
    public static final int CONTENT_HOST_AUTH_KEY_SEND_COLUMN = 7;
    public static final int CONTENT_FLAGS_COLUMN = 8;
    public static final int CONTENT_IS_DEFAULT_COLUMN = 9;
    public static final int CONTENT_COMPATIBILITY_UUID_COLUMN = 10;
    public static final int CONTENT_SENDER_NAME_COLUMN = 11;
    public static final int CONTENT_RINGTONE_URI_COLUMN = 12;
    public static final int CONTENT_PROTOCOL_VERSION_COLUMN = 13;
    public static final int CONTENT_NEW_MESSAGE_COUNT_COLUMN = 14;
    public static final int CONTENT_SECURITY_SYNC_KEY_COLUMN = 15;
    public static final int CONTENT_SIGNATURE_COLUMN = 16;
    public static final int CONTENT_POLICY_KEY = 17;
    public static final int CONTENT_NOTIFIED_MESSAGE_ID_COLUMN = 18;
    public static final int CONTENT_NOTIFIED_MESSAGE_COUNT_COLUMN = 19;

    public static final String[] CONTENT_PROJECTION = new String[] {
        RECORD_ID, AccountColumns.DISPLAY_NAME,
        AccountColumns.EMAIL_ADDRESS, AccountColumns.SYNC_KEY, AccountColumns.SYNC_LOOKBACK,
        AccountColumns.SYNC_INTERVAL, AccountColumns.HOST_AUTH_KEY_RECV,
        AccountColumns.HOST_AUTH_KEY_SEND, AccountColumns.FLAGS, AccountColumns.IS_DEFAULT,
        AccountColumns.COMPATIBILITY_UUID, AccountColumns.SENDER_NAME,
        AccountColumns.RINGTONE_URI, AccountColumns.PROTOCOL_VERSION,
        AccountColumns.NEW_MESSAGE_COUNT, AccountColumns.SECURITY_SYNC_KEY,
        AccountColumns.SIGNATURE, AccountColumns.POLICY_KEY,
        AccountColumns.NOTIFIED_MESSAGE_ID, AccountColumns.NOTIFIED_MESSAGE_COUNT
    };

    public static final int CONTENT_MAILBOX_TYPE_COLUMN = 1;

    /**
     * This projection is for listing account id's only
     */
    public static final String[] ID_TYPE_PROJECTION = new String[] {
        RECORD_ID, MailboxColumns.TYPE
    };

    public static final int ACCOUNT_FLAGS_COLUMN_ID = 0;
    public static final int ACCOUNT_FLAGS_COLUMN_FLAGS = 1;
    public static final String[] ACCOUNT_FLAGS_PROJECTION = new String[] {
            AccountColumns.ID, AccountColumns.FLAGS};

    public static final String MAILBOX_SELECTION =
        MessageColumns.MAILBOX_KEY + " =?";

    public static final String UNREAD_COUNT_SELECTION =
        MessageColumns.MAILBOX_KEY + " =? and " + MessageColumns.FLAG_READ + "= 0";

    private static final String UUID_SELECTION = AccountColumns.COMPATIBILITY_UUID + " =?";

    public static final String SECURITY_NONZERO_SELECTION =
        Account.POLICY_KEY + " IS NOT NULL AND " + Account.POLICY_KEY + "!=0";

    private static final String FIND_INBOX_SELECTION =
            MailboxColumns.TYPE + " = " + Mailbox.TYPE_INBOX +
            " AND " + MailboxColumns.ACCOUNT_KEY + " =?";

    /**
     * This projection is for searching for the default account
     */
    private static final String[] DEFAULT_ID_PROJECTION = new String[] {
        RECORD_ID, IS_DEFAULT
    };

    /**
     * no public constructor since this is a utility class
     */
    public Account() {
        mBaseUri = CONTENT_URI;

        // other defaults (policy)
        mRingtoneUri = "content://settings/system/notification_sound";
        mSyncInterval = -1;
        mSyncLookback = -1;
        mFlags = FLAGS_NOTIFY_NEW_MAIL;
        mCompatibilityUuid = UUID.randomUUID().toString();
    }

    public static Account restoreAccountWithId(Context context, long id) {
        return EmailContent.restoreContentWithId(context, Account.class,
                Account.CONTENT_URI, Account.CONTENT_PROJECTION, id);
    }

    /**
     * Returns {@code true} if the given account ID is a "normal" account. Normal accounts
     * always have an ID greater than {@code 0} and not equal to any pseudo account IDs
     * (such as {@link #ACCOUNT_ID_COMBINED_VIEW})
     */
    public static boolean isNormalAccount(long accountId) {
        return (accountId > 0L) && (accountId != ACCOUNT_ID_COMBINED_VIEW);
    }

    /**
     * Refresh an account that has already been loaded.  This is slightly less expensive
     * that generating a brand-new account object.
     */
    public void refresh(Context context) {
        Cursor c = context.getContentResolver().query(getUri(), Account.CONTENT_PROJECTION,
                null, null, null);
        try {
            c.moveToFirst();
            restore(c);
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    @Override
    public void restore(Cursor cursor) {
        mId = cursor.getLong(CONTENT_ID_COLUMN);
        mBaseUri = CONTENT_URI;
        mDisplayName = cursor.getString(CONTENT_DISPLAY_NAME_COLUMN);
        mEmailAddress = cursor.getString(CONTENT_EMAIL_ADDRESS_COLUMN);
        mSyncKey = cursor.getString(CONTENT_SYNC_KEY_COLUMN);
        mSyncLookback = cursor.getInt(CONTENT_SYNC_LOOKBACK_COLUMN);
        mSyncInterval = cursor.getInt(CONTENT_SYNC_INTERVAL_COLUMN);
        mHostAuthKeyRecv = cursor.getLong(CONTENT_HOST_AUTH_KEY_RECV_COLUMN);
        mHostAuthKeySend = cursor.getLong(CONTENT_HOST_AUTH_KEY_SEND_COLUMN);
        mFlags = cursor.getInt(CONTENT_FLAGS_COLUMN);
        mIsDefault = cursor.getInt(CONTENT_IS_DEFAULT_COLUMN) == 1;
        mCompatibilityUuid = cursor.getString(CONTENT_COMPATIBILITY_UUID_COLUMN);
        mSenderName = cursor.getString(CONTENT_SENDER_NAME_COLUMN);
        mRingtoneUri = cursor.getString(CONTENT_RINGTONE_URI_COLUMN);
        mProtocolVersion = cursor.getString(CONTENT_PROTOCOL_VERSION_COLUMN);
        mNewMessageCount = cursor.getInt(CONTENT_NEW_MESSAGE_COUNT_COLUMN);
        mSecuritySyncKey = cursor.getString(CONTENT_SECURITY_SYNC_KEY_COLUMN);
        mSignature = cursor.getString(CONTENT_SIGNATURE_COLUMN);
        mPolicyKey = cursor.getLong(CONTENT_POLICY_KEY);
        mNotifiedMessageId = cursor.getLong(CONTENT_NOTIFIED_MESSAGE_ID_COLUMN);
        mNotifiedMessageCount = cursor.getInt(CONTENT_NOTIFIED_MESSAGE_COUNT_COLUMN);
    }

    private long getId(Uri u) {
        return Long.parseLong(u.getPathSegments().get(1));
    }

    /**
     * @return the user-visible name for the account
     */
    public String getDisplayName() {
        return mDisplayName;
    }

    /**
     * Set the description.  Be sure to call save() to commit to database.
     * @param description the new description
     */
    public void setDisplayName(String description) {
        mDisplayName = description;
    }

    /**
     * @return the email address for this account
     */
    public String getEmailAddress() {
        return mEmailAddress;
    }

    /**
     * Set the Email address for this account.  Be sure to call save() to commit to database.
     * @param emailAddress the new email address for this account
     */
    public void setEmailAddress(String emailAddress) {
        mEmailAddress = emailAddress;
    }

    /**
     * @return the sender's name for this account
     */
    public String getSenderName() {
        return mSenderName;
    }

    /**
     * Set the sender's name.  Be sure to call save() to commit to database.
     * @param name the new sender name
     */
    public void setSenderName(String name) {
        mSenderName = name;
    }

    public String getSignature() {
        return mSignature;
    }

    public void setSignature(String signature) {
        mSignature = signature;
    }

    /**
     * @return the minutes per check (for polling)
     * TODO define sentinel values for "never", "push", etc.  See Account.java
     */
    public int getSyncInterval() {
        return mSyncInterval;
    }

    /**
     * Set the minutes per check (for polling).  Be sure to call save() to commit to database.
     * TODO define sentinel values for "never", "push", etc.  See Account.java
     * @param minutes the number of minutes between polling checks
     */
    public void setSyncInterval(int minutes) {
        mSyncInterval = minutes;
    }

    /**
     * @return One of the {@code Account.SYNC_WINDOW_*} constants that represents the sync
     *     lookback window.
     * TODO define sentinel values for "all", "1 month", etc.  See Account.java
     */
    public int getSyncLookback() {
        return mSyncLookback;
    }

    /**
     * Set the sync lookback window.  Be sure to call save() to commit to database.
     * TODO define sentinel values for "all", "1 month", etc.  See Account.java
     * @param value One of the {@link com.android.emailcommon.service.SyncWindow} constants
     */
    public void setSyncLookback(int value) {
        mSyncLookback = value;
    }

    /**
     * @return the flags for this account
     * @see #FLAGS_NOTIFY_NEW_MAIL
     * @see #FLAGS_VIBRATE
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Set the flags for this account
     * @see #FLAGS_NOTIFY_NEW_MAIL
     * @see #FLAGS_VIBRATE
     * @param newFlags the new value for the flags
     */
    public void setFlags(int newFlags) {
        mFlags = newFlags;
    }

    /**
     * @return the ringtone Uri for this account
     */
    public String getRingtone() {
        return mRingtoneUri;
    }

    /**
     * Set the ringtone Uri for this account
     * @param newUri the new URI string for the ringtone for this account
     */
    public void setRingtone(String newUri) {
        mRingtoneUri = newUri;
    }

    /**
     * Set the "delete policy" as a simple 0,1,2 value set.
     * @param newPolicy the new delete policy
     */
    public void setDeletePolicy(int newPolicy) {
        mFlags &= ~FLAGS_DELETE_POLICY_MASK;
        mFlags |= (newPolicy << FLAGS_DELETE_POLICY_SHIFT) & FLAGS_DELETE_POLICY_MASK;
    }

    /**
     * Return the "delete policy" as a simple 0,1,2 value set.
     * @return the current delete policy
     */
    public int getDeletePolicy() {
        return (mFlags & FLAGS_DELETE_POLICY_MASK) >> FLAGS_DELETE_POLICY_SHIFT;
    }

    /**
     * Return the Uuid associated with this account.  This is primarily for compatibility
     * with accounts set up by previous versions, because there are externals references
     * to the Uuid (e.g. desktop shortcuts).
     */
    public String getUuid() {
        return mCompatibilityUuid;
    }

    public HostAuth getOrCreateHostAuthSend(Context context) {
        if (mHostAuthSend == null) {
            if (mHostAuthKeySend != 0) {
                mHostAuthSend = HostAuth.restoreHostAuthWithId(context, mHostAuthKeySend);
            } else {
                mHostAuthSend = new HostAuth();
            }
        }
        return mHostAuthSend;
    }

    public HostAuth getOrCreateHostAuthRecv(Context context) {
        if (mHostAuthRecv == null) {
            if (mHostAuthKeyRecv != 0) {
                mHostAuthRecv = HostAuth.restoreHostAuthWithId(context, mHostAuthKeyRecv);
            } else {
                mHostAuthRecv = new HostAuth();
            }
        }
        return mHostAuthRecv;
    }

    /**
     * For compatibility while converting to provider model, generate a "local store URI"
     *
     * @return a string in the form of a Uri, as used by the other parts of the email app
     */
    public String getLocalStoreUri(Context context) {
        return "local://localhost/" + context.getDatabasePath(getUuid() + ".db");
    }

    /**
     * @return true if the instance is of an EAS account.
     *
     * NOTE This method accesses the DB if {@link #mHostAuthRecv} hasn't been restored yet.
     * Use caution when you use this on the main thread.
     */
    public boolean isEasAccount(Context context) {
        return "eas".equals(getProtocol(context));
    }

    public boolean supportsMoveMessages(Context context) {
        String protocol = getProtocol(context);
        return "eas".equals(protocol) || "imap".equals(protocol);
    }

    /**
     * @return true if the account supports "search".
     */
    public static boolean supportsServerSearch(Context context, long accountId) {
        Account account = Account.restoreAccountWithId(context, accountId);
        if (account == null) return false;
        return (account.mFlags & Account.FLAGS_SUPPORTS_SEARCH) != 0;
    }

    /**
     * Set the account to be the default account.  If this is set to "true", when the account
     * is saved, all other accounts will have the same value set to "false".
     * @param newDefaultState the new default state - if true, others will be cleared.
     */
    public void setDefaultAccount(boolean newDefaultState) {
        mIsDefault = newDefaultState;
    }

    /**
     * @return {@link Uri} to this {@link Account} in the
     * {@code content://com.android.email.provider/account/UUID} format, which is safe to use
     * for desktop shortcuts.
     *
     * <p>We don't want to store _id in shortcuts, because
     * {@link com.android.email.provider.AccountBackupRestore} won't preserve it.
     */
    public Uri getShortcutSafeUri() {
        return getShortcutSafeUriFromUuid(mCompatibilityUuid);
    }

    /**
     * @return {@link Uri} to an {@link Account} with a {@code uuid}.
     */
    public static Uri getShortcutSafeUriFromUuid(String uuid) {
        return CONTENT_URI.buildUpon().appendEncodedPath(uuid).build();
    }

    /**
     * Parse {@link Uri} in the {@code content://com.android.email.provider/account/ID} format
     * where ID = account id (used on Eclair, Android 2.0-2.1) or UUID, and return _id of
     * the {@link Account} associated with it.
     *
     * @param context context to access DB
     * @param uri URI of interest
     * @return _id of the {@link Account} associated with ID, or -1 if none found.
     */
    public static long getAccountIdFromShortcutSafeUri(Context context, Uri uri) {
        // Make sure the URI is in the correct format.
        if (!"content".equals(uri.getScheme())
                || !AUTHORITY.equals(uri.getAuthority())) {
            return -1;
        }

        final List<String> ps = uri.getPathSegments();
        if (ps.size() != 2 || !"account".equals(ps.get(0))) {
            return -1;
        }

        // Now get the ID part.
        final String id = ps.get(1);

        // First, see if ID can be parsed as long.  (Eclair-style)
        // (UUIDs have '-' in them, so they are always non-parsable.)
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ok) {
            // OK, it's not a long.  Continue...
        }

        // Now id is a UUId.
        return getAccountIdFromUuid(context, id);
    }

    /**
     * @return ID of the account with the given UUID.
     */
    public static long getAccountIdFromUuid(Context context, String uuid) {
        return Utility.getFirstRowLong(context,
                CONTENT_URI, ID_PROJECTION,
                UUID_SELECTION, new String[] {uuid}, null, 0, -1L);
    }

    /**
     * Return the id of the default account.  If one hasn't been explicitly specified, return
     * the first one in the database (the logic is provided within EmailProvider)
     * @param context the caller's context
     * @return the id of the default account, or Account.NO_ACCOUNT if there are no accounts
     */
    static public long getDefaultAccountId(Context context) {
        Cursor c = context.getContentResolver().query(
                Account.DEFAULT_ACCOUNT_ID_URI, Account.ID_PROJECTION, null, null, null);
        try {
            if (c != null && c.moveToFirst()) {
                return c.getLong(Account.ID_PROJECTION_COLUMN);
            }
        } finally {
            c.close();
        }
        return Account.NO_ACCOUNT;
    }

    /**
     * Given an account id, return the account's protocol
     * @param context the caller's context
     * @param accountId the id of the account to be examined
     * @return the account's protocol (or null if the Account or HostAuth do not exist)
     */
    public static String getProtocol(Context context, long accountId) {
        Account account = Account.restoreAccountWithId(context, accountId);
        if (account != null) {
            return account.getProtocol(context);
         }
        return null;
    }

    /**
     * Return the account's protocol
     * @param context the caller's context
     * @return the account's protocol (or null if the HostAuth doesn't not exist)
     */
    public String getProtocol(Context context) {
        HostAuth hostAuth = HostAuth.restoreHostAuthWithId(context, mHostAuthKeyRecv);
        if (hostAuth != null) {
            return hostAuth.mProtocol;
        }
        return null;
    }

    /**
     * Return the account ID for a message with a given id
     *
     * @param context the caller's context
     * @param messageId the id of the message
     * @return the account ID, or -1 if the account doesn't exist
     */
    public static long getAccountIdForMessageId(Context context, long messageId) {
        return Message.getKeyColumnLong(context, messageId, MessageColumns.ACCOUNT_KEY);
    }

    /**
     * Return the account for a message with a given id
     * @param context the caller's context
     * @param messageId the id of the message
     * @return the account, or null if the account doesn't exist
     */
    public static Account getAccountForMessageId(Context context, long messageId) {
        long accountId = getAccountIdForMessageId(context, messageId);
        if (accountId != -1) {
            return Account.restoreAccountWithId(context, accountId);
        }
        return null;
    }

    /**
     * @return true if an {@code accountId} is assigned to any existing account.
     */
    public static boolean isValidId(Context context, long accountId) {
        return null != Utility.getFirstRowLong(context, CONTENT_URI, ID_PROJECTION,
                ID_SELECTION, new String[] {Long.toString(accountId)}, null,
                ID_PROJECTION_COLUMN);
    }

    /**
     * Check a single account for security hold status.
     */
    public static boolean isSecurityHold(Context context, long accountId) {
        return (Utility.getFirstRowLong(context,
                ContentUris.withAppendedId(Account.CONTENT_URI, accountId),
                ACCOUNT_FLAGS_PROJECTION, null, null, null, ACCOUNT_FLAGS_COLUMN_FLAGS, 0L)
                & Account.FLAGS_SECURITY_HOLD) != 0;
    }

    /**
     * @return id of the "inbox" mailbox, or -1 if not found.
     */
    public static long getInboxId(Context context, long accountId) {
        return Utility.getFirstRowLong(context, Mailbox.CONTENT_URI, ID_PROJECTION,
                FIND_INBOX_SELECTION, new String[] {Long.toString(accountId)}, null,
                ID_PROJECTION_COLUMN, -1L);
    }

    /**
     * Clear all account hold flags that are set.
     *
     * (This will trigger watchers, and in particular will cause EAS to try and resync the
     * account(s).)
     */
    public static void clearSecurityHoldOnAllAccounts(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Cursor c = resolver.query(Account.CONTENT_URI, ACCOUNT_FLAGS_PROJECTION,
                SECURITY_NONZERO_SELECTION, null, null);
        try {
            while (c.moveToNext()) {
                int flags = c.getInt(ACCOUNT_FLAGS_COLUMN_FLAGS);

                if (0 != (flags & FLAGS_SECURITY_HOLD)) {
                    ContentValues cv = new ContentValues();
                    cv.put(AccountColumns.FLAGS, flags & ~FLAGS_SECURITY_HOLD);
                    long accountId = c.getLong(ACCOUNT_FLAGS_COLUMN_ID);
                    Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, accountId);
                    resolver.update(uri, cv, null, null);
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * Given an account id, determine whether the account is currently prohibited from automatic
     * sync, due to roaming while the account's policy disables this
     * @param context the caller's context
     * @param accountId the account id
     * @return true if the account can't automatically sync due to roaming; false otherwise
     */
    public static boolean isAutomaticSyncDisabledByRoaming(Context context, long accountId) {
        Account account = Account.restoreAccountWithId(context, accountId);
        // Account being deleted; just return
        if (account == null) return false;
        long policyKey = account.mPolicyKey;
        // If no security policy, we're good
        if (policyKey <= 0) return false;

        ConnectivityManager cm =
            (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        // If we're not on mobile, we're good
        if (info == null || (info.getType() != ConnectivityManager.TYPE_MOBILE)) return false;
        // If we're not roaming, we're good
        if (!info.isRoaming()) return false;
        Policy policy = Policy.restorePolicyWithId(context, policyKey);
        // Account being deleted; just return
        if (policy == null) return false;
        return policy.mRequireManualSyncWhenRoaming;
    }

    /**
     * Override update to enforce a single default account, and do it atomically
     */
    @Override
    public int update(Context context, ContentValues cv) {
        if (cv.containsKey(AccountColumns.IS_DEFAULT) &&
                cv.getAsBoolean(AccountColumns.IS_DEFAULT)) {
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            ContentValues cv1 = new ContentValues();
            cv1.put(AccountColumns.IS_DEFAULT, false);
            // Clear the default flag in all accounts
            ops.add(ContentProviderOperation.newUpdate(CONTENT_URI).withValues(cv1).build());
            // Update this account
            ops.add(ContentProviderOperation
                    .newUpdate(ContentUris.withAppendedId(CONTENT_URI, mId))
                    .withValues(cv).build());
            try {
                context.getContentResolver().applyBatch(AUTHORITY, ops);
                return 1;
            } catch (RemoteException e) {
                // There is nothing to be done here; fail by returning 0
            } catch (OperationApplicationException e) {
                // There is nothing to be done here; fail by returning 0
            }
            return 0;
        }
        return super.update(context, cv);
    }

    /*
     * Override this so that we can store the HostAuth's first and link them to the Account
     * (non-Javadoc)
     * @see com.android.email.provider.EmailContent#save(android.content.Context)
     */
    @Override
    public Uri save(Context context) {
        if (isSaved()) {
            throw new UnsupportedOperationException();
        }
        // This logic is in place so I can (a) short circuit the expensive stuff when
        // possible, and (b) override (and throw) if anyone tries to call save() or update()
        // directly for Account, which are unsupported.
        if (mHostAuthRecv == null && mHostAuthSend == null && mIsDefault == false &&
                mPolicy != null) {
            return super.save(context);
        }

        int index = 0;
        int recvIndex = -1;
        int sendIndex = -1;

        // Create operations for saving the send and recv hostAuths
        // Also, remember which operation in the array they represent
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
        if (mHostAuthRecv != null) {
            recvIndex = index++;
            ops.add(ContentProviderOperation.newInsert(mHostAuthRecv.mBaseUri)
                    .withValues(mHostAuthRecv.toContentValues())
                    .build());
        }
        if (mHostAuthSend != null) {
            sendIndex = index++;
            ops.add(ContentProviderOperation.newInsert(mHostAuthSend.mBaseUri)
                    .withValues(mHostAuthSend.toContentValues())
                    .build());
        }

        // Create operations for making this the only default account
        // Note, these are always updates because they change existing accounts
        if (mIsDefault) {
            index++;
            ContentValues cv1 = new ContentValues();
            cv1.put(AccountColumns.IS_DEFAULT, 0);
            ops.add(ContentProviderOperation.newUpdate(CONTENT_URI).withValues(cv1).build());
        }

        // Now do the Account
        ContentValues cv = null;
        if (recvIndex >= 0 || sendIndex >= 0) {
            cv = new ContentValues();
            if (recvIndex >= 0) {
                cv.put(Account.HOST_AUTH_KEY_RECV, recvIndex);
            }
            if (sendIndex >= 0) {
                cv.put(Account.HOST_AUTH_KEY_SEND, sendIndex);
            }
        }

        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(mBaseUri);
        b.withValues(toContentValues());
        if (cv != null) {
            b.withValueBackReferences(cv);
        }
        ops.add(b.build());

        try {
            ContentProviderResult[] results =
                context.getContentResolver().applyBatch(AUTHORITY, ops);
            // If saving, set the mId's of the various saved objects
            if (recvIndex >= 0) {
                long newId = getId(results[recvIndex].uri);
                mHostAuthKeyRecv = newId;
                mHostAuthRecv.mId = newId;
            }
            if (sendIndex >= 0) {
                long newId = getId(results[sendIndex].uri);
                mHostAuthKeySend = newId;
                mHostAuthSend.mId = newId;
            }
            Uri u = results[index].uri;
            mId = getId(u);
            return u;
        } catch (RemoteException e) {
            // There is nothing to be done here; fail by returning null
        } catch (OperationApplicationException e) {
            // There is nothing to be done here; fail by returning null
        }
        return null;
    }

    @Override
    public ContentValues toContentValues() {
        ContentValues values = new ContentValues();
        values.put(AccountColumns.DISPLAY_NAME, mDisplayName);
        values.put(AccountColumns.EMAIL_ADDRESS, mEmailAddress);
        values.put(AccountColumns.SYNC_KEY, mSyncKey);
        values.put(AccountColumns.SYNC_LOOKBACK, mSyncLookback);
        values.put(AccountColumns.SYNC_INTERVAL, mSyncInterval);
        values.put(AccountColumns.HOST_AUTH_KEY_RECV, mHostAuthKeyRecv);
        values.put(AccountColumns.HOST_AUTH_KEY_SEND, mHostAuthKeySend);
        values.put(AccountColumns.FLAGS, mFlags);
        values.put(AccountColumns.IS_DEFAULT, mIsDefault);
        values.put(AccountColumns.COMPATIBILITY_UUID, mCompatibilityUuid);
        values.put(AccountColumns.SENDER_NAME, mSenderName);
        values.put(AccountColumns.RINGTONE_URI, mRingtoneUri);
        values.put(AccountColumns.PROTOCOL_VERSION, mProtocolVersion);
        values.put(AccountColumns.NEW_MESSAGE_COUNT, mNewMessageCount);
        values.put(AccountColumns.SECURITY_SYNC_KEY, mSecuritySyncKey);
        values.put(AccountColumns.SIGNATURE, mSignature);
        values.put(AccountColumns.POLICY_KEY, mPolicyKey);
        values.put(AccountColumns.NOTIFIED_MESSAGE_ID, mNotifiedMessageId);
        values.put(AccountColumns.NOTIFIED_MESSAGE_COUNT, mNotifiedMessageCount);
        return values;
    }

    /**
     * Supports Parcelable
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Supports Parcelable
     */
    public static final Parcelable.Creator<Account> CREATOR
            = new Parcelable.Creator<Account>() {
        @Override
        public Account createFromParcel(Parcel in) {
            return new Account(in);
        }

        @Override
        public Account[] newArray(int size) {
            return new Account[size];
        }
    };

    /**
     * Supports Parcelable
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // mBaseUri is not parceled
        dest.writeLong(mId);
        dest.writeString(mDisplayName);
        dest.writeString(mEmailAddress);
        dest.writeString(mSyncKey);
        dest.writeInt(mSyncLookback);
        dest.writeInt(mSyncInterval);
        dest.writeLong(mHostAuthKeyRecv);
        dest.writeLong(mHostAuthKeySend);
        dest.writeInt(mFlags);
        dest.writeByte(mIsDefault ? (byte)1 : (byte)0);
        dest.writeString(mCompatibilityUuid);
        dest.writeString(mSenderName);
        dest.writeString(mRingtoneUri);
        dest.writeString(mProtocolVersion);
        dest.writeInt(mNewMessageCount);
        dest.writeString(mSecuritySyncKey);
        dest.writeString(mSignature);
        dest.writeLong(mPolicyKey);

        if (mHostAuthRecv != null) {
            dest.writeByte((byte)1);
            mHostAuthRecv.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte)0);
        }

        if (mHostAuthSend != null) {
            dest.writeByte((byte)1);
            mHostAuthSend.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte)0);
        }
    }

    /**
     * Supports Parcelable
     */
    public Account(Parcel in) {
        mBaseUri = Account.CONTENT_URI;
        mId = in.readLong();
        mDisplayName = in.readString();
        mEmailAddress = in.readString();
        mSyncKey = in.readString();
        mSyncLookback = in.readInt();
        mSyncInterval = in.readInt();
        mHostAuthKeyRecv = in.readLong();
        mHostAuthKeySend = in.readLong();
        mFlags = in.readInt();
        mIsDefault = in.readByte() == 1;
        mCompatibilityUuid = in.readString();
        mSenderName = in.readString();
        mRingtoneUri = in.readString();
        mProtocolVersion = in.readString();
        mNewMessageCount = in.readInt();
        mSecuritySyncKey = in.readString();
        mSignature = in.readString();
        mPolicyKey = in.readLong();

        mHostAuthRecv = null;
        if (in.readByte() == 1) {
            mHostAuthRecv = new HostAuth(in);
        }

        mHostAuthSend = null;
        if (in.readByte() == 1) {
            mHostAuthSend = new HostAuth(in);
        }
    }

    /**
     * For debugger support only - DO NOT use for code.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder('[');
        if (mHostAuthRecv != null && mHostAuthRecv.mProtocol != null) {
            sb.append(mHostAuthRecv.mProtocol);
            sb.append(':');
        }
        if (mDisplayName != null)   sb.append(mDisplayName);
        sb.append(':');
        if (mEmailAddress != null)  sb.append(mEmailAddress);
        sb.append(':');
        if (mSenderName != null)    sb.append(mSenderName);
        sb.append(']');
        return sb.toString();
    }
}