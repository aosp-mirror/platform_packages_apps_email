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

package com.android.email.activity.setup;

import com.android.email.AccountBackupRestore;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.AccountColumns;

import android.content.ContentValues;
import android.content.Context;

public class AccountSettingsUtils {

    /**
     * Commits the UI-related settings of an account to the provider.  This is static so that it
     * can be used by the various account activities.  If the account has never been saved, this
     * method saves it; otherwise, it just saves the settings.
     * @param context the context of the caller
     * @param account the account whose settings will be committed
     */
    public static void commitSettings(Context context, EmailContent.Account account) {
        if (!account.isSaved()) {
            account.save(context);
        } else {
            ContentValues cv = new ContentValues();
            cv.put(AccountColumns.IS_DEFAULT, account.mIsDefault);
            cv.put(AccountColumns.DISPLAY_NAME, account.getDisplayName());
            cv.put(AccountColumns.SENDER_NAME, account.getSenderName());
            cv.put(AccountColumns.SYNC_INTERVAL, account.mSyncInterval);
            cv.put(AccountColumns.RINGTONE_URI, account.mRingtoneUri);
            cv.put(AccountColumns.FLAGS, account.mFlags);
            cv.put(AccountColumns.SYNC_LOOKBACK, account.mSyncLookback);
            account.update(context, cv);
        }
        // Update the backup (side copy) of the accounts
        AccountBackupRestore.backupAccounts(context);
    }
}
