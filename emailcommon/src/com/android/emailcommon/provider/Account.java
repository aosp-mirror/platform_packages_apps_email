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
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogUtils;
import com.google.common.annotations.VisibleForTesting;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

public final class Account extends EmailContent implements Parcelable {
    public static final String TABLE_NAME = "Account";

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

    /**
     * Whether or not the user has asked for notifications of new mail in this account
     *
     * @deprecated Used only for migration
     */
    @Deprecated
    public final static int FLAGS_NOTIFY_NEW_MAIL = 1<<0;
    /**
     * Whether or not the user has asked for vibration notifications with all new mail
     *
     * @deprecated Used only for migration
     */
    @Deprecated
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
    // Whether or not the initial folder list has been loaded
    public static final int FLAGS_INITIAL_FOLDER_LIST_LOADED = 1<<13;

    // Deletion policy (see FLAGS_DELETE_POLICY_MASK, above)
    public static final int DELETE_POLICY_NEVER = 0;
    public static final int DELETE_POLICY_7DAYS = 1<<0;        // not supported
    public static final int DELETE_POLICY_ON_DELETE = 1<<1;

    // Sentinel values for the mSyncInterval field of both Account records
    public static final int CHECK_INTERVAL_NEVER = -1;
    public static final int CHECK_INTERVAL_PUSH = -2;

    public static Uri CONTENT_URI;
    public static Uri RESET_NEW_MESSAGE_COUNT_URI;
    public static Uri NOTIFIER_URI;

    public static void initAccount() {
        CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/account");
        RESET_NEW_MESSAGE_COUNT_URI = Uri.parse(EmailContent.CONTENT_URI + "/resetNewMessageCount");
        NOTIFIER_URI = Uri.parse(EmailContent.CONTENT_NOTIFIER_URI + "/account");
    }

    public String mDisplayName;
    public String mEmailAddress;
    public String mSyncKey;
    public int mSyncLookback;
    public int mSyncInterval;
    public long mHostAuthKeyRecv;
    public long mHostAuthKeySend;
    public int mFlags;
    public String mSenderName;
    /** @deprecated Used only for migration */
    @Deprecated
    private String mRingtoneUri;
    public String mProtocolVersion;
    public String mSecuritySyncKey;
    public String mSignature;
    public long mPolicyKey;
    public long mPingDuration;

    @VisibleForTesting
    static final String JSON_TAG_HOST_AUTH_RECV = "hostAuthRecv";
    @VisibleForTesting
    static final String JSON_TAG_HOST_AUTH_SEND = "hostAuthSend";

    // Convenience for creating/working with an account
    public transient HostAuth mHostAuthRecv;
    public transient HostAuth mHostAuthSend;
    public transient Policy mPolicy;

    // Marks this account as being a temporary entry, so we know to use it directly and not go
    // through the database or any caches
    private transient boolean mTemporary;

    public static final int CONTENT_ID_COLUMN = 0;
    public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
    public static final int CONTENT_EMAIL_ADDRESS_COLUMN = 2;
    public static final int CONTENT_SYNC_KEY_COLUMN = 3;
    public static final int CONTENT_SYNC_LOOKBACK_COLUMN = 4;
    public static final int CONTENT_SYNC_INTERVAL_COLUMN = 5;
    public static final int CONTENT_HOST_AUTH_KEY_RECV_COLUMN = 6;
    public static final int CONTENT_HOST_AUTH_KEY_SEND_COLUMN = 7;
    public static final int CONTENT_FLAGS_COLUMN = 8;
    public static final int CONTENT_SENDER_NAME_COLUMN = 9;
    public static final int CONTENT_RINGTONE_URI_COLUMN = 10;
    public static final int CONTENT_PROTOCOL_VERSION_COLUMN = 11;
    public static final int CONTENT_SECURITY_SYNC_KEY_COLUMN = 12;
    public static final int CONTENT_SIGNATURE_COLUMN = 13;
    public static final int CONTENT_POLICY_KEY_COLUMN = 14;
    public static final int CONTENT_PING_DURATION_COLUMN = 15;
    public static final int CONTENT_MAX_ATTACHMENT_SIZE_COLUMN = 16;

    public static final String[] CONTENT_PROJECTION = {
        AttachmentColumns._ID, AccountColumns.DISPLAY_NAME,
        AccountColumns.EMAIL_ADDRESS, AccountColumns.SYNC_KEY, AccountColumns.SYNC_LOOKBACK,
        AccountColumns.SYNC_INTERVAL, AccountColumns.HOST_AUTH_KEY_RECV,
        AccountColumns.HOST_AUTH_KEY_SEND, AccountColumns.FLAGS,
        AccountColumns.SENDER_NAME,
        AccountColumns.RINGTONE_URI, AccountColumns.PROTOCOL_VERSION,
        AccountColumns.SECURITY_SYNC_KEY,
        AccountColumns.SIGNATURE, AccountColumns.POLICY_KEY, AccountColumns.PING_DURATION,
        AccountColumns.MAX_ATTACHMENT_SIZE
    };

    public static final int ACCOUNT_FLAGS_COLUMN_ID = 0;
    public static final int ACCOUNT_FLAGS_COLUMN_FLAGS = 1;
    public static final String[] ACCOUNT_FLAGS_PROJECTION = {
            AccountColumns._ID, AccountColumns.FLAGS};

    public static final String SECURITY_NONZERO_SELECTION =
        AccountColumns.POLICY_KEY + " IS NOT NULL AND " + AccountColumns.POLICY_KEY + "!=0";

    private static final String FIND_INBOX_SELECTION =
            MailboxColumns.TYPE + " = " + Mailbox.TYPE_INBOX +
            " AND " + MailboxColumns.ACCOUNT_KEY + " =?";

    public Account() {
        mBaseUri = CONTENT_URI;

        // other defaults (policy)
        mRingtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString();
        mSyncInterval = -1;
        mSyncLookback = -1;
        mFlags = 0;
    }

    public static Account restoreAccountWithId(Context context, long id) {
        return restoreAccountWithId(context, id, null);
    }

    public static Account restoreAccountWithId(Context context, long id, ContentObserver observer) {
        return EmailContent.restoreContentWithId(context, Account.class,
                Account.CONTENT_URI, Account.CONTENT_PROJECTION, id, observer);
    }

    public static Account restoreAccountWithAddress(Context context, String emailAddress) {
        return restoreAccountWithAddress(context, emailAddress, null);
    }

    public static Account restoreAccountWithAddress(Context context, String emailAddress,
            ContentObserver observer) {
        final Cursor c = context.getContentResolver().query(CONTENT_URI,
                new String[] {AccountColumns._ID},
                AccountColumns.EMAIL_ADDRESS + "=?", new String[] {emailAddress},
                null);
        if (c == null || !c.moveToFirst()) {
            return null;
        }
        final long id = c.getLong(c.getColumnIndex(AccountColumns._ID));
        return restoreAccountWithId(context, id, observer);
    }

    @Override
    protected Uri getContentNotificationUri() {
        return Account.CONTENT_URI;
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
        mSenderName = cursor.getString(CONTENT_SENDER_NAME_COLUMN);
        mRingtoneUri = cursor.getString(CONTENT_RINGTONE_URI_COLUMN);
        mProtocolVersion = cursor.getString(CONTENT_PROTOCOL_VERSION_COLUMN);
        mSecuritySyncKey = cursor.getString(CONTENT_SECURITY_SYNC_KEY_COLUMN);
        mSignature = cursor.getString(CONTENT_SIGNATURE_COLUMN);
        mPolicyKey = cursor.getLong(CONTENT_POLICY_KEY_COLUMN);
        mPingDuration = cursor.getLong(CONTENT_PING_DURATION_COLUMN);
    }

    public boolean isTemporary() {
        return mTemporary;
    }

    public void setTemporary(boolean temporary) {
        mTemporary = temporary;
    }

    private static long getId(Uri u) {
        return Long.parseLong(u.getPathSegments().get(1));
    }

    public long getId() {
        return mId;
    }

    /**
     * Returns the user-visible name for the account, eg. "My work address"
     * or "foo@exemple.com".
     * @return the user-visible name for the account.
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

    @VisibleForTesting
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
     * @return the current ping duration.
     */
    public long getPingDuration() {
        return mPingDuration;
    }

    /**
     * Set the ping duration.  Be sure to call save() to commit to database.
     */
    public void setPingDuration(long value) {
        mPingDuration = value;
    }

    /**
     * @return the flags for this account
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Set the flags for this account
     * @param newFlags the new value for the flags
     */
    public void setFlags(int newFlags) {
        mFlags = newFlags;
    }

    /**
     * @return the ringtone Uri for this account
     * @deprecated Used only for migration
     */
    @Deprecated
    public String getRingtone() {
        return mRingtoneUri;
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
     * Return the id of the default account. If one hasn't been explicitly specified, return the
     * first one in the database. If no account exists, returns {@link #NO_ACCOUNT}.
     *
     * @param context the caller's context
     * @param lastUsedAccountId the last used account id, which is the basis of the default account
     */
    public static long getDefaultAccountId(final Context context, final long lastUsedAccountId) {
        final Cursor cursor = context.getContentResolver().query(
                CONTENT_URI, ID_PROJECTION, null, null, null);

        long firstAccount = NO_ACCOUNT;

        try {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    final long accountId = cursor.getLong(Account.ID_PROJECTION_COLUMN);

                    if (accountId == lastUsedAccountId) {
                        return accountId;
                    }

                    if (firstAccount == NO_ACCOUNT) {
                        firstAccount = accountId;
                    }
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return firstAccount;
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
        HostAuth hostAuth = getOrCreateHostAuthRecv(context);
        if (hostAuth != null) {
            return hostAuth.mProtocol;
        }
        return null;
    }

    /**
     * Return a corresponding account manager object using the passed in type
     *
     * @param type We can't look up the account type from here, so pass it in
     * @return system account object
     */
    public android.accounts.Account getAccountManagerAccount(String type) {
        return new android.accounts.Account(mEmailAddress, type);
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
        if (mHostAuthRecv == null && mHostAuthSend == null && mPolicy != null) {
            return super.save(context);
        }

        int index = 0;
        int recvIndex = -1;
        int recvCredentialsIndex = -1;
        int sendIndex = -1;
        int sendCredentialsIndex = -1;

        // Create operations for saving the send and recv hostAuths, and their credentials.
        // Also, remember which operation in the array they represent
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
        if (mHostAuthRecv != null) {
            if (mHostAuthRecv.mCredential != null) {
                recvCredentialsIndex = index++;
                ops.add(ContentProviderOperation.newInsert(mHostAuthRecv.mCredential.mBaseUri)
                        .withValues(mHostAuthRecv.mCredential.toContentValues())
                    .build());
            }
            recvIndex = index++;
            final ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(
                    mHostAuthRecv.mBaseUri);
            b.withValues(mHostAuthRecv.toContentValues());
            if (recvCredentialsIndex >= 0) {
                final ContentValues cv = new ContentValues();
                cv.put(HostAuthColumns.CREDENTIAL_KEY, recvCredentialsIndex);
                b.withValueBackReferences(cv);
            }
            ops.add(b.build());
        }
        if (mHostAuthSend != null) {
            if (mHostAuthSend.mCredential != null) {
                if (mHostAuthRecv.mCredential != null &&
                        mHostAuthRecv.mCredential.equals(mHostAuthSend.mCredential)) {
                    // These two credentials are identical, use the same row.
                    sendCredentialsIndex = recvCredentialsIndex;
                } else {
                    sendCredentialsIndex = index++;
                    ops.add(ContentProviderOperation.newInsert(mHostAuthSend.mCredential.mBaseUri)
                            .withValues(mHostAuthSend.mCredential.toContentValues())
                            .build());
                }
            }
            sendIndex = index++;
            final ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(
                    mHostAuthSend.mBaseUri);
            b.withValues(mHostAuthSend.toContentValues());
            if (sendCredentialsIndex >= 0) {
                final ContentValues cv = new ContentValues();
                cv.put(HostAuthColumns.CREDENTIAL_KEY, sendCredentialsIndex);
                b.withValueBackReferences(cv);
            }
            ops.add(b.build());
        }

        // Now do the Account
        ContentValues cv = null;
        if (recvIndex >= 0 || sendIndex >= 0) {
            cv = new ContentValues();
            if (recvIndex >= 0) {
                cv.put(AccountColumns.HOST_AUTH_KEY_RECV, recvIndex);
            }
            if (sendIndex >= 0) {
                cv.put(AccountColumns.HOST_AUTH_KEY_SEND, sendIndex);
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
                context.getContentResolver().applyBatch(EmailContent.AUTHORITY, ops);
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
        values.put(AccountColumns.SENDER_NAME, mSenderName);
        values.put(AccountColumns.RINGTONE_URI, mRingtoneUri);
        values.put(AccountColumns.PROTOCOL_VERSION, mProtocolVersion);
        values.put(AccountColumns.SECURITY_SYNC_KEY, mSecuritySyncKey);
        values.put(AccountColumns.SIGNATURE, mSignature);
        values.put(AccountColumns.POLICY_KEY, mPolicyKey);
        values.put(AccountColumns.PING_DURATION, mPingDuration);
        return values;
    }

    public String toJsonString(final Context context) {
        ensureLoaded(context);
        final JSONObject json = toJson();
        if (json != null) {
            return json.toString();
        }
        return null;
    }

    protected JSONObject toJson() {
        try {
            final JSONObject json = new JSONObject();
            json.putOpt(AccountColumns.DISPLAY_NAME, mDisplayName);
            json.put(AccountColumns.EMAIL_ADDRESS, mEmailAddress);
            json.put(AccountColumns.SYNC_LOOKBACK, mSyncLookback);
            json.put(AccountColumns.SYNC_INTERVAL, mSyncInterval);
            final JSONObject recvJson = mHostAuthRecv.toJson();
            json.put(JSON_TAG_HOST_AUTH_RECV, recvJson);
            if (mHostAuthSend != null) {
                final JSONObject sendJson = mHostAuthSend.toJson();
                json.put(JSON_TAG_HOST_AUTH_SEND, sendJson);
            }
            json.put(AccountColumns.FLAGS, mFlags);
            json.putOpt(AccountColumns.SENDER_NAME, mSenderName);
            json.putOpt(AccountColumns.PROTOCOL_VERSION, mProtocolVersion);
            json.putOpt(AccountColumns.SIGNATURE, mSignature);
            json.put(AccountColumns.PING_DURATION, mPingDuration);
            return json;
        } catch (final JSONException e) {
            LogUtils.d(LogUtils.TAG, e, "Exception while serializing Account");
        }
        return null;
    }

    public static Account fromJsonString(final String jsonString) {
        try {
            final JSONObject json = new JSONObject(jsonString);
            return fromJson(json);
        } catch (final JSONException e) {
            LogUtils.d(LogUtils.TAG, e, "Could not parse json for account");
        }
        return null;
    }

    protected static Account fromJson(final JSONObject json) {
        try {
            final Account a = new Account();
            a.mDisplayName = json.optString(AccountColumns.DISPLAY_NAME);
            a.mEmailAddress = json.getString(AccountColumns.EMAIL_ADDRESS);
            // SYNC_KEY is not stored
            a.mSyncLookback = json.getInt(AccountColumns.SYNC_LOOKBACK);
            a.mSyncInterval = json.getInt(AccountColumns.SYNC_INTERVAL);
            final JSONObject recvJson = json.getJSONObject(JSON_TAG_HOST_AUTH_RECV);
            a.mHostAuthRecv = HostAuth.fromJson(recvJson);
            final JSONObject sendJson = json.optJSONObject(JSON_TAG_HOST_AUTH_SEND);
            if (sendJson != null) {
                a.mHostAuthSend = HostAuth.fromJson(sendJson);
            }
            a.mFlags = json.getInt(AccountColumns.FLAGS);
            a.mSenderName = json.optString(AccountColumns.SENDER_NAME);
            a.mProtocolVersion = json.optString(AccountColumns.PROTOCOL_VERSION);
            // SECURITY_SYNC_KEY is not stored
            a.mSignature = json.optString(AccountColumns.SIGNATURE);
            // POLICY_KEY is not stored
            a.mPingDuration = json.optInt(AccountColumns.PING_DURATION, 0);
            return a;
        } catch (final JSONException e) {
            LogUtils.d(LogUtils.TAG, e, "Exception while deserializing Account");
        }
        return null;
    }

    /**
     * Ensure that all optionally-loaded fields are populated from the provider.
     * @param context for provider loads
     */
    public void ensureLoaded(final Context context) {
        if (mHostAuthKeyRecv == 0 && mHostAuthRecv == null) {
            throw new IllegalStateException("Trying to load incomplete Account object");
        }
        getOrCreateHostAuthRecv(context).ensureLoaded(context);

        if (mHostAuthKeySend != 0) {
            getOrCreateHostAuthSend(context);
            if (mHostAuthSend != null) {
                mHostAuthSend.ensureLoaded(context);
            }
        }
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
        dest.writeString("" /* mCompatibilityUuid */);
        dest.writeString(mSenderName);
        dest.writeString(mRingtoneUri);
        dest.writeString(mProtocolVersion);
        dest.writeInt(0 /* mNewMessageCount */);
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
        /* mCompatibilityUuid = */ in.readString();
        mSenderName = in.readString();
        mRingtoneUri = in.readString();
        mProtocolVersion = in.readString();
        /* mNewMessageCount = */ in.readInt();
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
        StringBuilder sb = new StringBuilder("[");
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
