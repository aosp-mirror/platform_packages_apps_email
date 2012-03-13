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

import android.content.ContentResolver;
import android.content.Context;

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
    /**
     * Backup user Account and HostAuth data into our backup database
     *
     * TODO Make EmailProvider do this automatically.
     */
    public static void backup(Context context) {
        ContentResolver resolver = context.getContentResolver();
        resolver.update(EmailProvider.ACCOUNT_BACKUP_URI, null, null, null);
    }
}
