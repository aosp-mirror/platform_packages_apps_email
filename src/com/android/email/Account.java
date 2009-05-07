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
import android.os.Debug;
import android.util.Log;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Account stores all of the settings for a single account defined by the user. It is able to save
 * and delete itself given a Preferences to work with. Each account is defined by a UUID. 
 */
public class Account implements Serializable {
    private static final boolean DEBUG_CHECK_BAD_DATA = true;         // DO NOT SHIP WITH "TRUE"
    private static final boolean DEBUG_STOP_ON_BAD_DATA = false;      // DO NOT SHIP WITH "TRUE"
    
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

    /** 
     * This should never be used for persistance, only for marshalling.
     * TODO: Remove serializable (VERY SLOW) and replace with Parcelable
     */
    private static final long serialVersionUID = 1L;
    
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
    int mAutomaticCheckIntervalMinutes;
    long mLastAutomaticCheckTime;
    boolean mNotifyNewMail;
    String mDraftsFolderName;
    String mSentFolderName;
    String mTrashFolderName;
    String mOutboxFolderName;
    int mAccountNumber;
    boolean mVibrate;
    String mRingtoneUri;
    int mSyncWindow;

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
    private final String KEY_SYNC_WINDOW = ".syncWindow";

    public Account(Context context) {
        // TODO Change local store path to something readable / recognizable
        mUuid = UUID.randomUUID().toString();
        mLocalStoreUri = "local://localhost/" + context.getDatabasePath(mUuid + ".db");
        debugCheckAllUriFields("constructor 1");
        mAutomaticCheckIntervalMinutes = -1;
        mAccountNumber = -1;
        mNotifyNewMail = true;
        mVibrate = false;
        mRingtoneUri = "content://settings/system/notification_sound";
        mSyncWindow = SYNC_WINDOW_USER;       // IMAP & POP3
        debugCheckAllUriFields("constructor 2");
    }

    Account(Preferences preferences, String uuid) {
        debugCheckAllUriFields("constructor 2-1");
        this.mUuid = uuid;
        refresh(preferences);
        debugCheckAllUriFields("constructor 2-2");
    }
    
    /**
     * Refresh the account from the stored settings.
     */
    public void refresh(Preferences preferences) {
        mPreferences = preferences;
        debugCheckAllUriFields("refresh 1");
        
        /**
         * Note:  Until we have resolved the potential for synchronization failures in
         * SharedPreferences, we're going to do a global lock around the read and write
         * functions.
         */
        synchronized (Account.class) {

            String storeText = preferences.mSharedPreferences.getString(mUuid + ".storeUri", null);
            debugCheckBase64("refresh 1", storeText);
            mStoreUri = Utility.base64Decode(storeText);
            debugCheckAllUriFields("refresh 2");
            
            mLocalStoreUri = preferences.mSharedPreferences.getString(mUuid + ".localStoreUri", null);
            debugCheckAllUriFields("refresh 3");

            String senderText = preferences.mSharedPreferences.getString(mUuid + ".senderUri", null);
            debugCheckBase64("refresh 2", senderText);
            if (senderText == null) {
                // Preference ".senderUri" was called ".transportUri" in earlier versions, so we'll
                // do a simple upgrade here when necessary.
                senderText = preferences.mSharedPreferences.getString(mUuid + ".transportUri", null);
                debugCheckBase64("refresh 3", senderText);
            }
            mSenderUri = Utility.base64Decode(senderText);
            debugCheckAllUriFields("refresh 4");

            mDescription = preferences.mSharedPreferences.getString(mUuid + ".description", null);
            mName = preferences.mSharedPreferences.getString(mUuid + ".name", mName);
            mEmail = preferences.mSharedPreferences.getString(mUuid + ".email", mEmail);
            mAutomaticCheckIntervalMinutes = preferences.mSharedPreferences.getInt(mUuid
                    + ".automaticCheckIntervalMinutes", -1);
            mLastAutomaticCheckTime = preferences.mSharedPreferences.getLong(mUuid
                    + ".lastAutomaticCheckTime", 0);
            mNotifyNewMail = preferences.mSharedPreferences.getBoolean(mUuid + ".notifyNewMail", 
                    false);

            // delete policy was incorrectly set on earlier versions, so we'll upgrade it here.
            // rule:  if IMAP account and policy = 0 ("never"), change policy to 2 ("on delete")
            mDeletePolicy = preferences.mSharedPreferences.getInt(mUuid + ".deletePolicy", 0);
            if (mDeletePolicy == DELETE_POLICY_NEVER && 
                    mStoreUri != null && mStoreUri.toString().startsWith(Store.STORE_SCHEME_IMAP)) {
                mDeletePolicy = DELETE_POLICY_ON_DELETE;
            }

            mDraftsFolderName = preferences.mSharedPreferences.getString(mUuid  + ".draftsFolderName", 
            "Drafts");
            mSentFolderName = preferences.mSharedPreferences.getString(mUuid  + ".sentFolderName", 
            "Sent");
            mTrashFolderName = preferences.mSharedPreferences.getString(mUuid  + ".trashFolderName", 
            "Trash");
            mOutboxFolderName = preferences.mSharedPreferences.getString(mUuid  + ".outboxFolderName", 
            "Outbox");
            mAccountNumber = preferences.mSharedPreferences.getInt(mUuid + ".accountNumber", 0);
            mVibrate = preferences.mSharedPreferences.getBoolean(mUuid + ".vibrate", false);
            mRingtoneUri = preferences.mSharedPreferences.getString(mUuid  + ".ringtone", 
            "content://settings/system/notification_sound");

            mSyncWindow = preferences.mSharedPreferences.getInt(mUuid + KEY_SYNC_WINDOW, 
                    SYNC_WINDOW_USER);
        }
        debugCheckAllUriFields("refresh 5");
    }

    public String getUuid() {
        return mUuid;
    }

    public String getStoreUri() {
        debugCheckAllUriFields("getStoreUri");
        return mStoreUri;
    }

    public void setStoreUri(String storeUri) {
        debugCheckAllUriFields("setStoreUri 1");
        this.mStoreUri = storeUri;
        debugCheckAllUriFields("setStoreUri 2");
    }

    public String getSenderUri() {
        debugCheckAllUriFields("getSenderUri");
        return mSenderUri;
    }

    public void setSenderUri(String senderUri) {
        debugCheckAllUriFields("setSenderUri 1");
        this.mSenderUri = senderUri;
        debugCheckAllUriFields("setSenderUri 2");
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

    public boolean isVibrate() {
        return mVibrate;
    }

    public void setVibrate(boolean vibrate) {
        mVibrate = vibrate;
    }

    public String getRingtone() {
        return mRingtoneUri;
    }

    public void setRingtone(String ringtoneUri) {
        mRingtoneUri = ringtoneUri;
    }

    public void delete(Preferences preferences) {
        /**
         * Note:  Until we have resolved the potential for synchronization failures in
         * SharedPreferences, we're going to do a global lock around the read and write
         * functions.
         */
        synchronized (Account.class) {
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

            editor.remove(mUuid + ".storeUri");
            editor.remove(mUuid + ".localStoreUri");
            editor.remove(mUuid + ".senderUri");
            editor.remove(mUuid + ".description");
            editor.remove(mUuid + ".name");
            editor.remove(mUuid + ".email");
            editor.remove(mUuid + ".automaticCheckIntervalMinutes");
            editor.remove(mUuid + ".lastAutomaticCheckTime");
            editor.remove(mUuid + ".notifyNewMail");
            editor.remove(mUuid + ".deletePolicy");
            editor.remove(mUuid + ".draftsFolderName");
            editor.remove(mUuid + ".sentFolderName");
            editor.remove(mUuid + ".trashFolderName");
            editor.remove(mUuid + ".outboxFolderName");
            editor.remove(mUuid + ".accountNumber");
            editor.remove(mUuid + ".vibrate");
            editor.remove(mUuid + ".ringtone");
            editor.remove(mUuid + KEY_SYNC_WINDOW);

            // also delete any deprecated fields
            editor.remove(mUuid + ".transportUri");

            editor.commit();
        }
    }

    public void save(Preferences preferences) {
        mPreferences = preferences;
        debugCheckAllUriFields("save 1");
        
        /**
         * Note:  Until we have resolved the potential for synchronization failures in
         * SharedPreferences, we're going to do a global lock around the read and write
         * functions.
         */
        synchronized (Account.class) {
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

            debugCheckAllUriFields("save 2");
            SharedPreferences.Editor editor = preferences.mSharedPreferences.edit();

            String storeText = Utility.base64Encode(mStoreUri);
            debugCheckBase64("save 1", storeText);
            editor.putString(mUuid + ".storeUri", storeText);
            editor.putString(mUuid + ".localStoreUri", mLocalStoreUri);
            String senderText = Utility.base64Encode(mSenderUri);
            debugCheckBase64("save 2", senderText);
            editor.putString(mUuid + ".senderUri", senderText);
            editor.putString(mUuid + ".description", mDescription);
            editor.putString(mUuid + ".name", mName);
            editor.putString(mUuid + ".email", mEmail);
            editor.putInt(mUuid + ".automaticCheckIntervalMinutes", mAutomaticCheckIntervalMinutes);
            editor.putLong(mUuid + ".lastAutomaticCheckTime", mLastAutomaticCheckTime);
            editor.putBoolean(mUuid + ".notifyNewMail", mNotifyNewMail);
            editor.putInt(mUuid + ".deletePolicy", mDeletePolicy);
            editor.putString(mUuid + ".draftsFolderName", mDraftsFolderName);
            editor.putString(mUuid + ".sentFolderName", mSentFolderName);
            editor.putString(mUuid + ".trashFolderName", mTrashFolderName);
            editor.putString(mUuid + ".outboxFolderName", mOutboxFolderName);
            editor.putInt(mUuid + ".accountNumber", mAccountNumber);
            editor.putBoolean(mUuid + ".vibrate", mVibrate);
            editor.putString(mUuid + ".ringtone", mRingtoneUri);
            editor.putInt(mUuid + KEY_SYNC_WINDOW, mSyncWindow);

            // The following fields are *not* written because they need to be more fine-grained
            // and not risk rewriting with old data.
            // editor.putString(mUuid + PREF_TAG_STORE_PERSISTENT, mStorePersistent);

            // also delete any deprecated fields
            editor.remove(mUuid + ".transportUri");

            editor.commit();
            debugCheckAllUriFields("save 3");
        }
        debugCheckAllUriFields("save 4");
    }

    @Override
    public String toString() {
        return mDescription;
    }

    public Uri getContentUri() {
        return Uri.parse("content://accounts/" + getUuid());
    }

    public String getLocalStoreUri() {
        debugCheckAllUriFields("getLocalStoreUri");
        return mLocalStoreUri;
    }

    public void setLocalStoreUri(String localStoreUri) {
        debugCheckAllUriFields("setLocalStoreUri 1");
        this.mLocalStoreUri = localStoreUri;
        debugCheckAllUriFields("setLocalStoreUri 2");
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
    
    /**
     * Check fields after deserialization
     * TODO this is bug-finding code and should not be enabled for shipping builds
     */
    private void readObject(java.io.ObjectInputStream in) 
            throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        // do my tests
        if (DEBUG_CHECK_BAD_DATA) {
            debugCheckAllUriFields("deserialize");
        }
    }

    /**
     * Check Uri fields for possible corruption
     */
    private void debugCheckAllUriFields(String when) {
        if (DEBUG_CHECK_BAD_DATA) {
            debugCheckUriField(when, "localstore", this.mLocalStoreUri);
            debugCheckUriField(when, "store", this.mStoreUri);
            debugCheckUriField(when, "sender", this.mSenderUri);
        }
    }
    
    /**
     * Check a single Uri field for possible corruption
     */
    private void debugCheckUriField(String when, String what, String uri) {
        if (!DEBUG_CHECK_BAD_DATA || uri == null || "".equals(uri)) {
            return;
        }
        
        try {
            new URI(uri);
        } catch (URISyntaxException use) {
            String detail = "Corrupted account " + what + " during " + when + ": " + uri;
            Log.d(Email.LOG_TAG, detail + " " + use.toString());
            if (DEBUG_STOP_ON_BAD_DATA && Debug.isDebuggerConnected()) {
                throw new Error(detail, use);
            }
        }
    }
    
    /**
     * Check a single base64 string for possible corruption
     */
    private void debugCheckBase64(String when, String base64) {
        if (!DEBUG_CHECK_BAD_DATA || base64 == null || "".equals(base64)) {
            return;
        }
        
        // first test:  simply looking for legal chars (any ordering)
        for (byte b : base64.getBytes()) {
            if (b >= 'A' && b <= 'Z') continue;
            if (b >= 'a' && b <= 'z') continue;
            if (b >= '0' && b <= '9') continue;
            if (b == '+' || b == '/') continue;
            if (b == '=') continue;
            
            String detail = "Corrupted base64 string during " + when + ": " + base64;
            Log.d(Email.LOG_TAG, detail);
            if (DEBUG_STOP_ON_BAD_DATA && Debug.isDebuggerConnected()) {
                throw new Error(detail);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Account) {
            return ((Account)o).mUuid.equals(mUuid);
        }
        return super.equals(o);
    }
}
