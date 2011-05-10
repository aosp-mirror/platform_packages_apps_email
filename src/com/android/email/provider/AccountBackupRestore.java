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

package com.android.email.provider;

import com.android.email.Email;
import com.android.email.Preferences;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;

import android.content.ContentResolver;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

/**
 * Helper class to facilitate EmailProvider's account backup/restore facility.
 *
 * Account backup/restore was implemented entirely for the purpose of recovering from database
 * corruption errors that were/are sporadic and of undetermined cause (though the prevailing wisdom
 * is that this is due to some kind of memory issue).  Rather than have the offending database get
 * deleted by SQLiteDatabase and forcing the user to recreate his accounts from scratch, it was
 * decided to backup accounts when created/modified and then restore them if 1) there are no
 * accounts in the database and 2) there are backup accounts.  This, at least, would cause user's
 * email data for IMAP/EAS to be re-synced and prevent the worst outcomes from occurring.
 *
 * To accomplish backup/restore, we use the facility now built in to EmailProvider to store a
 * backup version of the Account and HostAuth tables in a second database (EmailProviderBackup.db)
 *
 * TODO: We might look into having our own DatabaseErrorHandler that tries to be clever about
 * determining whether or not a "corrupt" database is truly corrupt; the problem here is that it
 * has proven impossible to reproduce the bug, and therefore any "solution" of this kind of utterly
 * impossible to test in the wild.
 */
public class AccountBackupRestore {
    // We only need to do this once, so prevent extra work by remembering this...
    private static boolean sBackupsChecked = false;

    /**
     * Backup user Account and HostAuth data into our backup database
     */
    public static void backup(Context context) {
        ContentResolver resolver = context.getContentResolver();
        int numBackedUp = resolver.update(EmailProvider.ACCOUNT_BACKUP_URI, null, null, null);
        if (numBackedUp < 0) {
            Log.e(Logging.LOG_TAG, "Account backup failed!");
        } else if (Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "Backed up " + numBackedUp + " accounts...");
        }
    }

    /**
     * Restore user Account and HostAuth data from our backup database
     */
    public static void restoreIfNeeded(Context context) {
        if (sBackupsChecked) return;

        // Check for legacy backup
        String legacyBackup = Preferences.getLegacyBackupPreference(context);
        // If there's a legacy backup, create a new-style backup and delete the legacy backup
        // In the 1:1000000000 chance that the user gets an app update just as his database becomes
        // corrupt, oh well...
        if (!TextUtils.isEmpty(legacyBackup)) {
            backup(context);
            Preferences.clearLegacyBackupPreference(context);
            Log.w(Logging.LOG_TAG, "Created new EmailProvider backup database");
        }

        // If we have accounts, we're done
        if (EmailContent.count(context, Account.CONTENT_URI) > 0) return;
        ContentResolver resolver = context.getContentResolver();
        int numRecovered = resolver.update(EmailProvider.ACCOUNT_RESTORE_URI, null, null, null);
        if (numRecovered > 0) {
            Log.e(Logging.LOG_TAG, "Recovered " + numRecovered + " accounts!");
        } else if (numRecovered < 0) {
            Log.e(Logging.LOG_TAG, "Account recovery failed?");
        } else if (Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "No accounts to restore...");
        }
        sBackupsChecked = true;
    }
}
