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

import java.io.Serializable;
import java.util.Arrays;
import java.util.UUID;

/**
 * Account stores all of the settings for a single account defined by the user. It is able to save
 * and delete itself given a Preferences to work with. Each account is defined by a UUID. 
 */
public class Account implements Serializable {
    public static final int DELETE_POLICY_NEVER = 0;
    public static final int DELETE_POLICY_7DAYS = 1;
    public static final int DELETE_POLICY_ON_DELETE = 2;
    
    private static final long serialVersionUID = 2975156672298625121L;

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

    /**
     * <pre>
     * 0 Never 
     * 1 After 7 days 
     * 2 When I delete from inbox
     * </pre>
     */
    int mDeletePolicy;

    public Account(Context context) {
        // TODO Change local store path to something readable / recognizable
        mUuid = UUID.randomUUID().toString();
        mLocalStoreUri = "local://localhost/" + context.getDatabasePath(mUuid + ".db");
        mAutomaticCheckIntervalMinutes = -1;
        mAccountNumber = -1;
        mNotifyNewMail = true;
        mVibrate = false;
        mRingtoneUri = "content://settings/system/notification_sound";
    }

    Account(Preferences preferences, String uuid) {
        this.mUuid = uuid;
        refresh(preferences);
    }
    
    /**
     * Refresh the account from the stored settings.
     */
    public void refresh(Preferences preferences) {
        mStoreUri = Utility.base64Decode(preferences.mSharedPreferences.getString(mUuid
                + ".storeUri", null));
        mLocalStoreUri = preferences.mSharedPreferences.getString(mUuid + ".localStoreUri", null);
        
        String senderText = preferences.mSharedPreferences.getString(mUuid + ".senderUri", null);
        if (senderText == null) {
            // Preference ".senderUri" was called ".transportUri" in earlier versions, so we'll
            // do a simple upgrade here when necessary.
            senderText = preferences.mSharedPreferences.getString(mUuid + ".transportUri", null);
        }
        mSenderUri = Utility.base64Decode(senderText);
        
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
        
        // also delete any deprecated fields
        editor.remove(mUuid + ".transportUri");
        
        editor.commit();
    }

    public void save(Preferences preferences) {
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

        editor.putString(mUuid + ".storeUri", Utility.base64Encode(mStoreUri));
        editor.putString(mUuid + ".localStoreUri", mLocalStoreUri);
        editor.putString(mUuid + ".senderUri", Utility.base64Encode(mSenderUri));
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
        
        // also delete any deprecated fields
        editor.remove(mUuid + ".transportUri");

        editor.commit();
    }

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

    @Override
    public boolean equals(Object o) {
        if (o instanceof Account) {
            return ((Account)o).mUuid.equals(mUuid);
        }
        return super.equals(o);
    }
}
