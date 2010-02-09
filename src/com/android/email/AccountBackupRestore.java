/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.email.mail.store.ExchangeStore;
import com.android.email.provider.EmailContent;

import android.accounts.AccountManagerFuture;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.Calendar;
import android.provider.ContactsContract;
import android.util.Log;

/**
 * Utility functions to support backup and restore of accounts.
 *
 * In the short term, this is used to work around local database failures.  In the long term,
 * this will also support server-side backups, providing support for automatic account restoration
 * when switching or replacing phones.
 */
public class AccountBackupRestore {

    /**
     * Backup accounts.  Can be called from UI thread (does work in a new thread)
     */
    public static void backupAccounts(final Context context) {
        if (Email.DEBUG) {
            Log.v(Email.LOG_TAG, "backupAccounts");
        }
        // Because we typically call this from the UI, let's do the work in a thread
        new Thread() {
            @Override
            public void run() {
                doBackupAccounts(context, Preferences.getPreferences(context));
            }
        }.start();
    }

    /**
     * Restore accounts if needed.  This is blocking, and should only be called in specific
     * startup/entry points.
     */
    public static void restoreAccountsIfNeeded(final Context context) {
        // Don't log here;  This is called often.
        boolean restored = doRestoreAccounts(context, Preferences.getPreferences(context));
        if (restored) {
            // after restoring accounts, register services appropriately
            Log.w(Email.LOG_TAG, "Register services after restoring accounts");
            // update security profile 
            SecurityPolicy.getInstance(context).updatePolicies(-1);
            // enable/disable other email services as necessary
            Email.setServicesEnabled(context);
            ExchangeUtils.startExchangeService(context);
        }
    }

    /**
     * Non-UI-Thread worker to backup all accounts
     *
     * @param context used to access the provider
     * @param preferences used to access the backups (provided separately for testability)
     */
    /* package */ synchronized static void doBackupAccounts(Context context,
            Preferences preferences) {
        // 1.  Wipe any existing backup accounts
        Account[] oldBackups = preferences.getAccounts();
        for (Account backup : oldBackups) {
            backup.delete(preferences);
        }

        // 2. Identify the default account (if any).  This is required because setting
        // the default account flag is lazy,and sometimes we don't have any flags set.  We'll
        // use this to make it explicit (see loop, below).
        // This is also the quick check for "no accounts" (the only case in which the returned
        // value is -1) and if so, we can exit immediately.
        long defaultAccountId = EmailContent.Account.getDefaultAccountId(context);
        if (defaultAccountId == -1) {
            return;
        }

        // 3. Create new backup(s), if any
        Cursor c = context.getContentResolver().query(EmailContent.Account.CONTENT_URI,
                EmailContent.Account.CONTENT_PROJECTION, null, null, null);
        try {
            while (c.moveToNext()) {
                EmailContent.Account fromAccount =
                        EmailContent.getContent(c, EmailContent.Account.class);
                if (Email.DEBUG) {
                    Log.v(Email.LOG_TAG, "Backing up account:" + fromAccount.getDisplayName());
                }
                Account toAccount = LegacyConversions.makeLegacyAccount(context, fromAccount);

                // Determine if contacts are also synced, and if so, record that
                if (fromAccount.mHostAuthRecv.mProtocol.equals("eas")) {
                    android.accounts.Account acct = new android.accounts.Account(
                            fromAccount.mEmailAddress, Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                    boolean syncContacts = ContentResolver.getSyncAutomatically(acct,
                            ContactsContract.AUTHORITY);
                    if (syncContacts) {
                        toAccount.mBackupFlags |= Account.BACKUP_FLAGS_SYNC_CONTACTS;
                    }
                    boolean syncCalendar = ContentResolver.getSyncAutomatically(acct,
                            Calendar.AUTHORITY);
                    if (syncCalendar) {
                        toAccount.mBackupFlags |= Account.BACKUP_FLAGS_SYNC_CALENDAR;
                    }
                }

                // If this is the default account, mark it as such
                if (fromAccount.mId == defaultAccountId) {
                    toAccount.mBackupFlags |= Account.BACKUP_FLAGS_IS_DEFAULT;
                }

                // Mark this account as a backup of a Provider account, instead of a legacy
                // account to upgrade
                toAccount.mBackupFlags |= Account.BACKUP_FLAGS_IS_BACKUP;

                toAccount.save(preferences);
            }
        } finally {
            c.close();
        }
    }

    /**
     * Restore all accounts.  This is blocking.
     *
     * @param context used to access the provider
     * @param preferences used to access the backups (provided separately for testability)
     * @return true if accounts were restored (meaning services should be restarted, etc.)
     */
    /* package */ synchronized static boolean doRestoreAccounts(Context context,
            Preferences preferences) {
        boolean result = false;

        // 1. Quick check - if we have any accounts, get out
        int numAccounts = EmailContent.count(context, EmailContent.Account.CONTENT_URI, null, null);
        if (numAccounts > 0) {
            return result;
        }
        // 2. Quick check - if no backup accounts, get out
        Account[] backups = preferences.getAccounts();
        if (backups.length == 0) {
            return result;
        }

        Log.w(Email.LOG_TAG, "*** Restoring Email Accounts, found " + backups.length);

        // 3. Possible lost accounts situation - check for any backups, and restore them
        for (Account backupAccount : backups) {
            // don't back up any leftover legacy accounts (these are migrated elsewhere).
            if ((backupAccount.mBackupFlags & Account.BACKUP_FLAGS_IS_BACKUP) == 0) {
                continue;
            }
            // Restore the account
            Log.w(Email.LOG_TAG, "Restoring account:" + backupAccount.getDescription());
            EmailContent.Account toAccount =
                LegacyConversions.makeAccount(context, backupAccount);

            // Mark the default account if this is it
            if (0 != (backupAccount.mBackupFlags & Account.BACKUP_FLAGS_IS_DEFAULT)) {
                toAccount.setDefaultAccount(true);
            }

            // For exchange accounts, handle system account first, then save in provider
            if (toAccount.mHostAuthRecv.mProtocol.equals("eas")) {
                // Recreate entry in Account Manager as well, if needed
                // Set "sync contacts/calendar" mode as well, if needed
                boolean alsoSyncContacts =
                    (backupAccount.mBackupFlags & Account.BACKUP_FLAGS_SYNC_CONTACTS) != 0;
                boolean alsoSyncCalendar =
                    (backupAccount.mBackupFlags & Account.BACKUP_FLAGS_SYNC_CALENDAR) != 0;

                // Use delete-then-add semantic to simplify handling of update-in-place
//                AccountManagerFuture<Boolean> removeResult = ExchangeStore.removeSystemAccount(
//                        context.getApplicationContext(), toAccount, null);
//                try {
//                    // This call blocks until removeSystemAccount completes.  Result is not used.
//                    removeResult.getResult();
//                } catch (AccountsException e) {
//                    Log.d(Email.LOG_TAG, "removeSystemAccount failed: " + e);
//                    // log and discard - we don't care if remove fails, generally
//                } catch (IOException e) {
//                    Log.d(Email.LOG_TAG, "removeSystemAccount failed: " + e);
//                    // log and discard - we don't care if remove fails, generally
//                }

                // NOTE: We must use the Application here, rather than the current context, because
                // all future references to AccountManager will use the context passed in here
                // TODO: Need to implement overwrite semantics for an already-installed account
                AccountManagerFuture<Bundle> addAccountResult =
                     ExchangeStore.addSystemAccount(context.getApplicationContext(), toAccount,
                             alsoSyncContacts, alsoSyncCalendar, null);
//                try {
//                    // This call blocks until addSystemAccount completes.  Result is not used.
//                    addAccountResult.getResult();
                    toAccount.save(context);
//                } catch (OperationCanceledException e) {
//                    Log.d(Email.LOG_TAG, "addAccount was canceled");
//                } catch (IOException e) {
//                    Log.d(Email.LOG_TAG, "addAccount failed: " + e);
//                } catch (AuthenticatorException e) {
//                    Log.d(Email.LOG_TAG, "addAccount failed: " + e);
//                }

            } else {
                // non-eas account - save it immediately
                toAccount.save(context);
            }
            // report that an account was restored
            result = true;
        }
        return result;
    }
}
