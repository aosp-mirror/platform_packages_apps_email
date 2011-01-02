/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.email;

import com.android.email.mail.Store;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.Arrays;
import java.util.UUID;

/**
 * Account stores all of the settings for a single account defined by the user. It is able to save
 * and delete itself given a Preferences to work with. Each account is defined by a UUID. 
 */
public class Account {
    public static final int DELETE_POLICY_NEVER = 0;
    public static final int DELETE_POLICY_7DAYS = 1;
    public static final int DELETE_POLICY_ON_DELETE = 2;
    
    public static final int CHECK_INTERVAL_NEVER = -1;
    public static final int CHECK_INTERVAL_PUSH = -2;
    
    public static final int SYNC_WINDOW_USER = -1;
    public static final int SYNC_WINDOW_1_DAY = 1;
    public static final int SYNC_WINDOW_3_DAYS = 2;
    public static final int SYNC_WINDOW_1_WEEK = 3;
    public static final int SYNC_WINDOW_2_WEEKS = 4;
    public static final int SYNC_WINDOW_1_MONTH = 5;
    public static final int SYNC_WINDOW_ALL = 6;

    // These flags will never be seen in a "real" (legacy) account
    public static final int BACKUP_FLAGS_IS_BACKUP = 1;
    public static final int BACKUP_FLAGS_SYNC_CONTACTS = 2;
    public static final int BACKUP_FLAGS_IS_DEFAULT = 4;
    public static final int BACKUP_FLAGS_SYNC_CALENDAR = 8;

    // transient values - do not serialize
    private transient Preferences mPreferences;

    // serialized values
    String mUuid;
    String mStoreUri;
    String mLocalStoreUri;
    String mSenderUri;
    String mDescription;
    String mName;
    String mEmail;
    int mAccountColor;
    int mAutomaticCheckIntervalMinutes;
    long mLastAutomaticCheckTime;
    boolean mNotifyNewMail;
    String mDraftsFolderName;
    String mSentFolderName;
    String mTrashFolderName;
    String mOutboxFolderName;
    int mAccountNumber;
    boolean mVibrate;           // true: Always vibrate. false: Only when mVibrateWhenSilent.
    boolean mVibrateWhenSilent; // true: Vibrate even if !mVibrate. False: Require mVibrate.
    String mRingtoneUri;
    int mSyncWindow;
    int mBackupFlags;           // for account backups only
    String mProtocolVersion;    // for account backups only
    int mSecurityFlags;         // for account backups only
    String mSignature;          // for account backups only

    /**
     * <pre>
     * 0 Never 
     * 1 After 7 days 
     * 2 When I delete from inbox
     * </pre>
     */
    int mDeletePolicy;

    /**
     * All new fields should have named keys
     */
    private final String KEY_STORE_URI = ".storeUri";
    private final String KEY_LOCALSTORE_URI = ".localStoreUri";
    private final String KEY_SENDER_URI = ".senderUri";
    private final String KEY_OLD_TRANSPORT_URI = ".transportUri";
    private final String KEY_DESCRIPTION = ".description";
    private final String KEY_NAME = ".name";
    private final String KEY_EMAIL = ".email";
    private final String KEY_ACCOUNT_COLOR = ".accountColor";
    private final String KEY_AUTO_CHECK_INTERVAL_MINS = ".automaticCheckIntervalMinutes";
    private final String KEY_LAST_AUTO_CHECK_TIME = ".lastAutomaticCheckTime";
    private final String KEY_NOTIFY_NEW_MAIL = ".notifyNewMail";
    private final String KEY_DELETE_POLICY = ".deletePolicy";
    private final String KEY_DRAFTS_FOLDER_NAME = ".draftsFolderName";
    private final String KEY_SENT_FOLDER_NAME = ".sentFolderName";
    private final String KEY_TRASH_FOLDER_NAME = ".trashFolderName";
    private final String KEY_OUTBOX_FOLDER_NAME = ".outboxFolderName";
    private final String KEY_ACCOUNT_NUMBER = ".accountNumber";
    private final String KEY_VIBRATE = ".vibrate";
    private final String KEY_VIBRATE_WHEN_SILENT = ".vibrateWhenSilent";
    private final String KEY_RINGTONE = ".ringtone";
    private final String KEY_SYNC_WINDOW = ".syncWindow";
    private final String KEY_BACKUP_FLAGS = ".backupFlags";
    private final String KEY_PROTOCOL_VERSION = ".protocolVersion";
    private final String KEY_SECURITY_FLAGS = ".securityFlags";
    private final String KEY_SIGNATURE = ".signature";

    public Account(Context context) {
        // TODO Change local store path to something readable / recognizable
        mUuid = UUID.randomUUID().toString();
        mLocalStoreUri = "local://localhost/" + context.getDatabasePath(mUuid + ".db");
        mAutomaticCheckIntervalMinutes = -1;
        mAccountNumber = -1;
        mNotifyNewMail = true;
        mVibrate = false;
        mVibrateWhenSilent = false;
        mRingtoneUri = "content://settings/system/notification_sound";
        mSyncWindow = SYNC_WINDOW_USER;       // IMAP & POP3
        mBackupFlags = 0;
        mProtocolVersion = null;
        mSecurityFlags = 0;
        mSignature = null;
    }

    Account(Preferences preferences, String uuid) {
        this.mUuid = uuid;
        refresh(preferences);
    }
    
    /**
     * Refresh the account from the stored settings.
     */
    public void refresh(Preferences preferences) {
        mPreferences = preferences;

        mStoreUri = Utility.base64Decode(preferences.mSharedPreferences.getString(mUuid
                + KEY_STORE_URI, null));
        mLocalStoreUri = preferences.mSharedPreferences.getString(mUuid + KEY_LOCALSTORE_URI, null);
        
        String senderText = preferences.mSharedPreferences.getString(mUuid + KEY_SENDER_URI, null);
        if (senderText == null) {
            // Preference ".senderUri" was called ".transportUri" in earlier versions, so we'll
            // do a simple upgrade here when necessary.
            senderText = preferences.mSharedPreferences.getString(mUuid + KEY_OLD_TRANSPORT_URI, null);
        }
        mSenderUri = Utility.base64Decode(senderText);
        
        mDescription = preferences.mSharedPreferences.getString(mUuid + KEY_DESCRIPTION, null);
        mName = preferences.mSharedPreferences.getString(mUuid + KEY_NAME, mName);
        mEmail = preferences.mSharedPreferences.getString(mUuid + KEY_EMAIL, mEmail);
        
        mAccountColor = preferences.mSharedPreferences.getInt(mUuid + KEY_ACCOUNT_COLOR, 0xffffffff);
        
        mAutomaticCheckIntervalMinutes = preferences.mSharedPreferences.getInt(mUuid
                + KEY_AUTO_CHECK_INTERVAL_MINS, -1);
        mLastAutomaticCheckTime = preferences.mSharedPreferences.getLong(mUuid
                + KEY_LAST_AUTO_CHECK_TIME, 0);
        mNotifyNewMail = preferences.mSharedPreferences.getBoolean(mUuid + KEY_NOTIFY_NEW_MAIL, 
                false);
        
        // delete policy was incorrectly set on earlier versions, so we'll upgrade it here.
        // rule:  if IMAP account and policy = 0 ("never"), change policy to 2 ("on delete")
        mDeletePolicy = preferences.mSharedPreferences.getInt(mUuid + KEY_DELETE_POLICY, 0);
        if (mDeletePolicy == DELETE_POLICY_NEVER && 
                mStoreUri != null && mStoreUri.toString().startsWith(Store.STORE_SCHEME_IMAP)) {
            mDeletePolicy = DELETE_POLICY_ON_DELETE;
        }
        
        mDraftsFolderName = preferences.mSharedPreferences.getString(mUuid  + KEY_DRAFTS_FOLDER_NAME, 
                "Drafts");
        mSentFolderName = preferences.mSharedPreferences.getString(mUuid  + KEY_SENT_FOLDER_NAME, 
                "Sent");
        mTrashFolderName = preferences.mSharedPreferences.getString(mUuid  + KEY_TRASH_FOLDER_NAME, 
                "Trash");
        mOutboxFolderName = preferences.mSharedPreferences.getString(mUuid  + KEY_OUTBOX_FOLDER_NAME, 
                "Outbox");
        mAccountNumber = preferences.mSharedPreferences.getInt(mUuid + KEY_ACCOUNT_NUMBER, 0);
        mVibrate = preferences.mSharedPreferences.getBoolean(mUuid + KEY_VIBRATE, false);
        mVibrateWhenSilent = preferences.mSharedPreferences.getBoolean(mUuid +
                KEY_VIBRATE_WHEN_SILENT, false);
        mRingtoneUri = preferences.mSharedPreferences.getString(mUuid  + KEY_RINGTONE, 
                "content://settings/system/notification_sound");
        
        mSyncWindow = preferences.mSharedPreferences.getInt(mUuid + KEY_SYNC_WINDOW, 
                SYNC_WINDOW_USER);

        mBackupFlags = preferences.mSharedPreferences.getInt(mUuid + KEY_BACKUP_FLAGS, 0);
        mProtocolVersion = preferences.mSharedPreferences.getString(mUuid + KEY_PROTOCOL_VERSION,
                null);
        mSecurityFlags = preferences.mSharedPreferences.getInt(mUuid + KEY_SECURITY_FLAGS, 0);
        mSignature = preferences.mSharedPreferences.getString(mUuid + KEY_SIGNATURE, null);
    }

    public String getUuid() {
        return mUuid;
    }

    public String getStoreUri() {
        return mStoreUri;
    }

    public void setStoreUri(String storeUri) {
        this.mStoreUri = storeUri;
    }

    public String getSenderUri() {
        return mSenderUri;
    }

    public void setSenderUri(String senderUri) {
        this.mSenderUri = senderUri;
    }

    public String getDescription() {
        return mDescription;
    }

    public void setDescription(String description) {
        this.mDescription = description;
    }

    public String getName() {
        return mName;
    }

    public void setName(String name) {
        this.mName = name;
    }

    public String getEmail() {
        return mEmail;
    }

    public void setEmail(String email) {
        this.mEmail = email;
    }

    public int getAccountColor () {
    	return mAccountColor;
    }

    public void setAccountColor (int color) {
    	mAccountColor = color;
    }
    
    public boolean isVibrate() {
        return mVibrate;
    }

    public void setVibrate(boolean vibrate) {
        mVibrate = vibrate;
    }

    public boolean isVibrateWhenSilent() {
        return mVibrateWhenSilent;
    }

    public void setVibrateWhenSilent(boolean vibrateWhenSilent) {
        mVibrateWhenSilent = vibrateWhenSilent;
    }

    public String getRingtone() {
        return mRingtoneUri;
    }

    public void setRingtone(String ringtoneUri) {
        mRingtoneUri = ringtoneUri;
    }

    public void delete(Preferences preferences) {
        String[] uuids = preferences.mSharedPreferences.getString("accountUuids", "").split(",");
        StringBuffer sb = new StringBuffer();
        for (int i = 0, length = uuids.length; i < length; i++) {
            if (!uuids[i].equals(mUuid)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(uuids[i]);
            }
        }
        String accountUuids = sb.toString();
        SharedPreferences.Editor editor = preferences.mSharedPreferences.edit();
        editor.putString("accountUuids", accountUuids);

        editor.remove(mUuid + KEY_STORE_URI);
        editor.remove(mUuid + KEY_LOCALSTORE_URI);
        editor.remove(mUuid + KEY_SENDER_URI);
        editor.remove(mUuid + KEY_DESCRIPTION);
        editor.remove(mUuid + KEY_NAME);
        editor.remove(mUuid + KEY_EMAIL);
        editor.remove(mUuid + KEY_ACCOUNT_COLOR);
        editor.remove(mUuid + KEY_AUTO_CHECK_INTERVAL_MINS);
        editor.remove(mUuid + KEY_LAST_AUTO_CHECK_TIME);
        editor.remove(mUuid + KEY_NOTIFY_NEW_MAIL);
        editor.remove(mUuid + KEY_DELETE_POLICY);
        editor.remove(mUuid + KEY_DRAFTS_FOLDER_NAME);
        editor.remove(mUuid + KEY_SENT_FOLDER_NAME);
        editor.remove(mUuid + KEY_TRASH_FOLDER_NAME);
        editor.remove(mUuid + KEY_OUTBOX_FOLDER_NAME);
        editor.remove(mUuid + KEY_ACCOUNT_NUMBER);
        editor.remove(mUuid + KEY_VIBRATE);
        editor.remove(mUuid + KEY_VIBRATE_WHEN_SILENT);
        editor.remove(mUuid + KEY_RINGTONE);
        editor.remove(mUuid + KEY_SYNC_WINDOW);
        editor.remove(mUuid + KEY_BACKUP_FLAGS);
        editor.remove(mUuid + KEY_PROTOCOL_VERSION);
        editor.remove(mUuid + KEY_SECURITY_FLAGS);
        editor.remove(mUuid + KEY_SIGNATURE);

        // also delete any deprecated fields
        editor.remove(mUuid + KEY_OLD_TRANSPORT_URI);
        
        editor.commit();
    }

    public void save(Preferences preferences) {
        mPreferences = preferences;
        
        if (!preferences.mSharedPreferences.getString("accountUuids", "").contains(mUuid)) {
            /*
             * When the account is first created we assign it a unique account number. The
             * account number will be unique to that account for the lifetime of the account.
             * So, we get all the existing account numbers, sort them ascending, loop through
             * the list and check if the number is greater than 1 + the previous number. If so
             * we use the previous number + 1 as the account number. This refills gaps.
             * mAccountNumber starts as -1 on a newly created account. It must be -1 for this
             * algorithm to work.
             * 
             * I bet there is a much smarter way to do this. Anyone like to suggest it?
             */
            Account[] accounts = preferences.getAccounts();
            int[] accountNumbers = new int[accounts.length];
            for (int i = 0; i < accounts.length; i++) {
                accountNumbers[i] = accounts[i].getAccountNumber();
            }
            Arrays.sort(accountNumbers);
            for (int accountNumber : accountNumbers) {
                if (accountNumber > mAccountNumber + 1) {
                    break;
                }
                mAccountNumber = accountNumber;
            }
            mAccountNumber++;
            
            String accountUuids = preferences.mSharedPreferences.getString("accountUuids", "");
            accountUuids += (accountUuids.length() != 0 ? "," : "") + mUuid;
            SharedPreferences.Editor editor = preferences.mSharedPreferences.edit();
            editor.putString("accountUuids", accountUuids);
            editor.commit();
        }

        SharedPreferences.Editor editor = preferences.mSharedPreferences.edit();

        editor.putString(mUuid + KEY_STORE_URI, Utility.base64Encode(mStoreUri));
        editor.putString(mUuid + KEY_LOCALSTORE_URI, mLocalStoreUri);
        editor.putString(mUuid + KEY_SENDER_URI, Utility.base64Encode(mSenderUri));
        editor.putString(mUuid + KEY_DESCRIPTION, mDescription);
        editor.putString(mUuid + KEY_NAME, mName);
        editor.putString(mUuid + KEY_EMAIL, mEmail);
        editor.putInt(mUuid + KEY_ACCOUNT_COLOR, mAccountColor);
        editor.putInt(mUuid + KEY_AUTO_CHECK_INTERVAL_MINS, mAutomaticCheckIntervalMinutes);
        editor.putLong(mUuid + KEY_LAST_AUTO_CHECK_TIME, mLastAutomaticCheckTime);
        editor.putBoolean(mUuid + KEY_NOTIFY_NEW_MAIL, mNotifyNewMail);
        editor.putInt(mUuid + KEY_DELETE_POLICY, mDeletePolicy);
        editor.putString(mUuid + KEY_DRAFTS_FOLDER_NAME, mDraftsFolderName);
        editor.putString(mUuid + KEY_SENT_FOLDER_NAME, mSentFolderName);
        editor.putString(mUuid + KEY_TRASH_FOLDER_NAME, mTrashFolderName);
        editor.putString(mUuid + KEY_OUTBOX_FOLDER_NAME, mOutboxFolderName);
        editor.putInt(mUuid + KEY_ACCOUNT_NUMBER, mAccountNumber);
        editor.putBoolean(mUuid + KEY_VIBRATE, mVibrate);
        editor.putBoolean(mUuid + KEY_VIBRATE_WHEN_SILENT, mVibrateWhenSilent);
        editor.putString(mUuid + KEY_RINGTONE, mRingtoneUri);
        editor.putInt(mUuid + KEY_SYNC_WINDOW, mSyncWindow);
        editor.putInt(mUuid + KEY_BACKUP_FLAGS, mBackupFlags);
        editor.putString(mUuid + KEY_PROTOCOL_VERSION, mProtocolVersion);
        editor.putInt(mUuid + KEY_SECURITY_FLAGS, mSecurityFlags);
        editor.putString(mUuid + KEY_SIGNATURE, mSignature);
        
        // The following fields are *not* written because they need to be more fine-grained
        // and not risk rewriting with old data.
        // editor.putString(mUuid + PREF_TAG_STORE_PERSISTENT, mStorePersistent);

        // also delete any deprecated fields
        editor.remove(mUuid + KEY_OLD_TRANSPORT_URI);

        editor.commit();
    }

    @Override
    public String toString() {
        return mDescription;
    }

    public Uri getContentUri() {
        return Uri.parse("content://accounts/" + getUuid());
    }

    public String getLocalStoreUri() {
        return mLocalStoreUri;
    }

    public void setLocalStoreUri(String localStoreUri) {
        this.mLocalStoreUri = localStoreUri;
    }

    /**
     * Returns -1 for never.
     */
    public int getAutomaticCheckIntervalMinutes() {
        return mAutomaticCheckIntervalMinutes;
    }

    /**
     * @param automaticCheckIntervalMinutes or -1 for never.
     */
    public void setAutomaticCheckIntervalMinutes(int automaticCheckIntervalMinutes) {
        this.mAutomaticCheckIntervalMinutes = automaticCheckIntervalMinutes;
    }

    public long getLastAutomaticCheckTime() {
        return mLastAutomaticCheckTime;
    }

    public void setLastAutomaticCheckTime(long lastAutomaticCheckTime) {
        this.mLastAutomaticCheckTime = lastAutomaticCheckTime;
    }

    public boolean isNotifyNewMail() {
        return mNotifyNewMail;
    }

    public void setNotifyNewMail(boolean notifyNewMail) {
        this.mNotifyNewMail = notifyNewMail;
    }

    public int getDeletePolicy() {
        return mDeletePolicy;
    }

    public void setDeletePolicy(int deletePolicy) {
        this.mDeletePolicy = deletePolicy;
    }
    
    public String getDraftsFolderName() {
        return mDraftsFolderName;
    }

    public void setDraftsFolderName(String draftsFolderName) {
        mDraftsFolderName = draftsFolderName;
    }

    public String getSentFolderName() {
        return mSentFolderName;
    }

    public void setSentFolderName(String sentFolderName) {
        mSentFolderName = sentFolderName;
    }

    public String getTrashFolderName() {
        return mTrashFolderName;
    }

    public void setTrashFolderName(String trashFolderName) {
        mTrashFolderName = trashFolderName;
    }
    
    public String getOutboxFolderName() {
        return mOutboxFolderName;
    }

    public void setOutboxFolderName(String outboxFolderName) {
        mOutboxFolderName = outboxFolderName;
    }
    
    public int getAccountNumber() {
        return mAccountNumber;
    }

    public int getSyncWindow() {
        return mSyncWindow;
    }
    
    public void setSyncWindow(int window) {
        mSyncWindow = window;
    }

    public int getBackupFlags() {
        return mBackupFlags;
    }

    public void setBackupFlags(int flags) {
        mBackupFlags = flags;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Account) {
            return ((Account)o).mUuid.equals(mUuid);
        }
        return super.equals(o);
    }
}
