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

package com.android.emailcommon.utility;

import com.android.email.Email;
import com.android.emailcommon.provider.EmailContent.Account;

import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.List;

public class AccountReconciler {
    /**
     * Compare our account list (obtained from EmailProvider) with the account list owned by
     * AccountManager.  If there are any orphans (an account in one list without a corresponding
     * account in the other list), delete the orphan, as these must remain in sync.
     *
     * Note that the duplication of account information is caused by the Email application's
     * incomplete integration with AccountManager.
     *
     * This function may not be called from the main/UI thread, because it makes blocking calls
     * into the account manager.
     *
     * @param context The context in which to operate
     * @param emailProviderAccounts the exchange provider accounts to work from
     * @param accountManagerAccounts The account manager accounts to work from
     * @param resolver the content resolver for making provider updates (injected for testability)
     */
    public static boolean reconcileAccounts(Context context,
            List<Account> emailProviderAccounts, android.accounts.Account[] accountManagerAccounts,
            ContentResolver resolver) {
        // First, look through our EmailProvider accounts to make sure there's a corresponding
        // AccountManager account
        boolean accountsDeleted = false;
        for (Account providerAccount: emailProviderAccounts) {
            String providerAccountName = providerAccount.mEmailAddress;
            boolean found = false;
            for (android.accounts.Account accountManagerAccount: accountManagerAccounts) {
                if (accountManagerAccount.name.equalsIgnoreCase(providerAccountName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                if ((providerAccount.mFlags & Account.FLAGS_INCOMPLETE) != 0) {
                    if (Email.DEBUG) {
                        Log.d(Email.LOG_TAG,
                        "Account reconciler noticed incomplete account; ignoring");
                    }
                    continue;
                }
                // This account has been deleted in the AccountManager!
                Log.d(Email.LOG_TAG, "Account deleted in AccountManager; deleting from provider: " +
                        providerAccountName);
                // TODO This will orphan downloaded attachments; need to handle this
                resolver.delete(ContentUris.withAppendedId(Account.CONTENT_URI,
                        providerAccount.mId), null, null);
                accountsDeleted = true;
            }
        }
        // Now, look through AccountManager accounts to make sure we have a corresponding cached EAS
        // account from EmailProvider
        for (android.accounts.Account accountManagerAccount: accountManagerAccounts) {
            String accountManagerAccountName = accountManagerAccount.name;
            boolean found = false;
            for (Account cachedEasAccount: emailProviderAccounts) {
                if (cachedEasAccount.mEmailAddress.equalsIgnoreCase(accountManagerAccountName)) {
                    found = true;
                }
            }
            if (!found) {
                // This account has been deleted from the EmailProvider database
                Log.d(Email.LOG_TAG,
                        "Account deleted from provider; deleting from AccountManager: " +
                        accountManagerAccountName);
                // Delete the account
                AccountManagerFuture<Boolean> blockingResult = AccountManager.get(context)
                    .removeAccount(accountManagerAccount, null, null);
                try {
                    // Note: All of the potential errors from removeAccount() are simply logged
                    // here, as there is nothing to actually do about them.
                    blockingResult.getResult();
                } catch (OperationCanceledException e) {
                    Log.w(Email.LOG_TAG, e.toString());
                } catch (AuthenticatorException e) {
                    Log.w(Email.LOG_TAG, e.toString());
                } catch (IOException e) {
                    Log.w(Email.LOG_TAG, e.toString());
                }
                accountsDeleted = true;
            }
        }
        return accountsDeleted;
    }
}
