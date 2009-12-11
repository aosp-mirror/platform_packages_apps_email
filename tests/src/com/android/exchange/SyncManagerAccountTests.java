/*
 * Copyright (C) 2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

package com.android.exchange;

import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.email.provider.EmailContent.Account;
import com.android.exchange.SyncManager.AccountList;

import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.test.ProviderTestCase2;

import java.io.IOException;

public class SyncManagerAccountTests extends ProviderTestCase2<EmailProvider> {

    private static final String TEST_ACCOUNT_PREFIX = "__test";
    private static final String TEST_ACCOUNT_SUFFIX = "@android.com";

    EmailProvider mProvider;
    Context mMockContext;

    public SyncManagerAccountTests() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        // Delete any test accounts we might have created earlier
        deleteTemporaryAccountManagerAccounts(getContext());
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        // Delete any test accounts we might have created earlier
        deleteTemporaryAccountManagerAccounts(getContext());
    }

    private android.accounts.Account makeAccountManagerAccount(String username) {
        return new android.accounts.Account(username, Eas.ACCOUNT_MANAGER_TYPE);
    }

    private void createAccountManagerAccount(String username) {
        final android.accounts.Account account = makeAccountManagerAccount(username);
        AccountManager.get(getContext()).addAccountExplicitly(account, "password", null);
    }

    private Account setupProviderAndAccountManagerAccount(String username) {
        // Note that setupAccount creates the email address username@android.com, so that's what
        // we need to use for the account manager
        createAccountManagerAccount(username + "@android.com");
        return ProviderTestUtils.setupAccount(username, true, mMockContext);
    }

    private AccountList makeSyncManagerAccountList() {
        AccountList accountList = new AccountList();
        Cursor c = mMockContext.getContentResolver().query(Account.CONTENT_URI,
                Account.CONTENT_PROJECTION, null, null, null);
        try {
            while (c.moveToNext()) {
                accountList.add(new Account().restore(c));
            }
        } finally {
            c.close();
        }
        return accountList;
    }

    private void deleteAccountManagerAccount(Context context, android.accounts.Account account) {
        AccountManagerFuture<Boolean> future =
            AccountManager.get(context).removeAccount(account, null, null);
        try {
            future.getResult();
        } catch (OperationCanceledException e) {
        } catch (AuthenticatorException e) {
        } catch (IOException e) {
        }
    }

    private void deleteTemporaryAccountManagerAccounts(Context context) {
        android.accounts.Account[] accountManagerAccounts =
            AccountManager.get(context).getAccountsByType(Eas.ACCOUNT_MANAGER_TYPE);
        for (android.accounts.Account accountManagerAccount: accountManagerAccounts) {
            if (accountManagerAccount.name.startsWith(TEST_ACCOUNT_PREFIX) &&
                    accountManagerAccount.name.endsWith(TEST_ACCOUNT_SUFFIX)) {
                deleteAccountManagerAccount(context, accountManagerAccount);
            }
        }
    }

    private String getTestAccountName(String name) {
        return TEST_ACCOUNT_PREFIX + name;
    }

    private String getTestAccountEmailAddress(String name) {
        return TEST_ACCOUNT_PREFIX + name + TEST_ACCOUNT_SUFFIX;
    }

    public void testReconcileAccounts() {
        // Note that we can't use mMockContext for AccountManager interactions, as it isn't a fully
        // functional Context.
        Context context = getContext();

        // Set up three accounts, both in AccountManager and in EmailProvider
        Account firstAccount = setupProviderAndAccountManagerAccount(getTestAccountName("1"));
        setupProviderAndAccountManagerAccount(getTestAccountName("2"));
        setupProviderAndAccountManagerAccount(getTestAccountName("3"));

        // Check that they're set up properly
        assertEquals(3, EmailContent.count(mMockContext, Account.CONTENT_URI, null, null));
        android.accounts.Account[] accountManagerAccounts =
            AccountManager.get(context).getAccountsByType(Eas.ACCOUNT_MANAGER_TYPE);
        assertEquals(3, accountManagerAccounts.length);

        // Delete account "2" from AccountManager
        android.accounts.Account removedAccount =
            makeAccountManagerAccount(getTestAccountEmailAddress("2"));
        deleteAccountManagerAccount(context, removedAccount);

        // Confirm it's deleted
        accountManagerAccounts =
            AccountManager.get(context).getAccountsByType(Eas.ACCOUNT_MANAGER_TYPE);
        assertEquals(2, accountManagerAccounts.length);

        // Run the reconciler
        SyncManager syncManager = new SyncManager();
        ContentResolver resolver = mMockContext.getContentResolver();
        syncManager.mResolver = resolver;
        syncManager.reconcileAccountsWithAccountManager(context,
                makeSyncManagerAccountList(), accountManagerAccounts);

        // There should now be only two EmailProvider accounts
        assertEquals(2, EmailContent.count(mMockContext, Account.CONTENT_URI, null, null));

        // Ok, now we've got two of each; let's delete a provider account
        resolver.delete(ContentUris.withAppendedId(Account.CONTENT_URI, firstAccount.mId),
                null, null);
        // ...and then there was one
        assertEquals(1, EmailContent.count(mMockContext, Account.CONTENT_URI, null, null));

        // Run the reconciler
        syncManager.reconcileAccountsWithAccountManager(context,
                makeSyncManagerAccountList(), accountManagerAccounts);

        // There should now be only one AccountManager account
        accountManagerAccounts =
            AccountManager.get(getContext()).getAccountsByType(Eas.ACCOUNT_MANAGER_TYPE);
        assertEquals(1, accountManagerAccounts.length);
        // ... and it should be account "3"
        assertEquals(getTestAccountEmailAddress("3"), accountManagerAccounts[0].name);
     }
}
