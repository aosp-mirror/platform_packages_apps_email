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

import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.database.Cursor;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.Suppress;

import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Base class for unit tests that use {@link android.accounts.Account}.
 */
@Suppress
public abstract class AccountTestCase extends ProviderTestCase2<EmailProvider> {

    protected static final String TEST_ACCOUNT_PREFIX = "__test";
    protected static final String TEST_ACCOUNT_SUFFIX = "@android.com";
    protected static final String TEST_ACCOUNT_TYPE = "com.android.test_exchange";

    public AccountTestCase() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    protected android.accounts.Account[] getExchangeAccounts() {
        return AccountManager.get(getContext()).getAccountsByType(TEST_ACCOUNT_TYPE);
    }

    protected android.accounts.Account makeAccountManagerAccount(String username) {
        return new android.accounts.Account(username, TEST_ACCOUNT_TYPE);
    }

    protected void createAccountManagerAccount(String username) {
        final android.accounts.Account account = makeAccountManagerAccount(username);
        AccountManager.get(getContext()).addAccountExplicitly(account, "password", null);
    }

    protected Account setupProviderAndAccountManagerAccount(String username) {
        // Note that setupAccount creates the email address username@android.com, so that's what
        // we need to use for the account manager
        createAccountManagerAccount(username + TEST_ACCOUNT_SUFFIX);
        return ProviderTestUtils.setupAccount(username, true, getMockContext());
    }

    protected ArrayList<Account> makeExchangeServiceAccountList() {
        ArrayList<Account> accountList = new ArrayList<Account>();
        Cursor c = getMockContext().getContentResolver().query(Account.CONTENT_URI,
                Account.CONTENT_PROJECTION, null, null, null);
        try {
            while (c.moveToNext()) {
                Account account = new Account();
                account.restore(c);
                accountList.add(account);
            }
        } finally {
            c.close();
        }
        return accountList;
    }

    protected void deleteAccountManagerAccount(android.accounts.Account account) {
        AccountManagerFuture<Boolean> future =
            AccountManager.get(getContext()).removeAccount(account, null, null);
        try {
            future.getResult();
        } catch (OperationCanceledException e) {
        } catch (AuthenticatorException e) {
        } catch (IOException e) {
        }
    }

    protected void deleteTemporaryAccountManagerAccounts() {
        for (android.accounts.Account accountManagerAccount: getExchangeAccounts()) {
            if (accountManagerAccount.name.startsWith(TEST_ACCOUNT_PREFIX) &&
                    accountManagerAccount.name.endsWith(TEST_ACCOUNT_SUFFIX)) {
                deleteAccountManagerAccount(accountManagerAccount);
            }
        }
    }

    protected String getTestAccountName(String name) {
        return TEST_ACCOUNT_PREFIX + name;
    }

    protected String getTestAccountEmailAddress(String name) {
        return TEST_ACCOUNT_PREFIX + name + TEST_ACCOUNT_SUFFIX;
    }

    /**
     * Helper to retrieve account manager accounts *and* remove any preexisting accounts
     * from the list, to "hide" them from the reconciler.
     */
    protected android.accounts.Account[] getAccountManagerAccounts(
            android.accounts.Account[] baseline) {
        android.accounts.Account[] rawList = getExchangeAccounts();
        if (baseline.length == 0) {
            return rawList;
        }
        HashSet<android.accounts.Account> set = new HashSet<android.accounts.Account>();
        for (android.accounts.Account addAccount : rawList) {
            set.add(addAccount);
        }
        for (android.accounts.Account removeAccount : baseline) {
            set.remove(removeAccount);
        }
        return set.toArray(new android.accounts.Account[0]);
    }
}
