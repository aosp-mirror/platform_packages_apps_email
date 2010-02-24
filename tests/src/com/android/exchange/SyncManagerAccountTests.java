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

import com.android.email.AccountTestCase;
import com.android.email.Email;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.exchange.SyncManager.SyncError;

import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;

import java.util.HashMap;

/**
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.SyncManagerAccountTests email
 */
public class SyncManagerAccountTests extends AccountTestCase {

    EmailProvider mProvider;
    Context mMockContext;

    public SyncManagerAccountTests() {
        super();
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        // Delete any test accounts we might have created earlier
        deleteTemporaryAccountManagerAccounts();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        // Delete any test accounts we might have created earlier
        deleteTemporaryAccountManagerAccounts();
    }

    /**
     * Confirm that the test below is functional (and non-destructive) when there are
     * prexisting (non-test) accounts in the account manager.
     */
    public void testTestReconcileAccounts() {
        Account firstAccount = null;
        final String TEST_USER_ACCOUNT = "__user_account_test_1";
        Context context = getContext();
        try {
            // Note:  Unlike calls to setupProviderAndAccountManagerAccount(), we are creating
            // *real* accounts here (not in the mock provider)
            createAccountManagerAccount(TEST_USER_ACCOUNT + TEST_ACCOUNT_SUFFIX);
            firstAccount = ProviderTestUtils.setupAccount(TEST_USER_ACCOUNT, true, context);
            // Now run the test with the "user" accounts in place
            testReconcileAccounts();
        } finally {
            if (firstAccount != null) {
                boolean firstAccountFound = false;
                // delete the provider account
                context.getContentResolver().delete(firstAccount.getUri(), null, null);
                // delete the account manager account
                android.accounts.Account[] accountManagerAccounts = AccountManager.get(context)
                        .getAccountsByType(Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                for (android.accounts.Account accountManagerAccount: accountManagerAccounts) {
                    if ((TEST_USER_ACCOUNT + TEST_ACCOUNT_SUFFIX)
                            .equals(accountManagerAccount.name)) {
                        deleteAccountManagerAccount(accountManagerAccount);
                        firstAccountFound = true;
                    }
                }
                assertTrue(firstAccountFound);
            }
        }
    }

    /**
     * Note, there is some inherent risk in this test, as it creates *real* accounts in the
     * system (it cannot use the mock context with the Account Manager).
     */
    public void testReconcileAccounts() {
        // Note that we can't use mMockContext for AccountManager interactions, as it isn't a fully
        // functional Context.
        Context context = getContext();

        // Capture the baseline (account manager accounts) so we can measure the changes
        // we're making, irrespective of the number of actual accounts, and not destroy them
        android.accounts.Account[] baselineAccounts =
            AccountManager.get(context).getAccountsByType(Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);

        // Set up three accounts, both in AccountManager and in EmailProvider
        Account firstAccount = setupProviderAndAccountManagerAccount(getTestAccountName("1"));
        setupProviderAndAccountManagerAccount(getTestAccountName("2"));
        setupProviderAndAccountManagerAccount(getTestAccountName("3"));

        // Check that they're set up properly
        assertEquals(3, EmailContent.count(mMockContext, Account.CONTENT_URI, null, null));
        android.accounts.Account[] accountManagerAccounts =
                getAccountManagerAccounts(baselineAccounts);
        assertEquals(3, accountManagerAccounts.length);

        // Delete account "2" from AccountManager
        android.accounts.Account removedAccount =
            makeAccountManagerAccount(getTestAccountEmailAddress("2"));
        deleteAccountManagerAccount(removedAccount);

        // Confirm it's deleted
        accountManagerAccounts = getAccountManagerAccounts(baselineAccounts);
        assertEquals(2, accountManagerAccounts.length);

        // Run the reconciler
        ContentResolver resolver = mMockContext.getContentResolver();
        SyncManager.reconcileAccountsWithAccountManager(context,
                makeSyncManagerAccountList(), accountManagerAccounts, true, resolver);

        // There should now be only two EmailProvider accounts
        assertEquals(2, EmailContent.count(mMockContext, Account.CONTENT_URI, null, null));

        // Ok, now we've got two of each; let's delete a provider account
        resolver.delete(ContentUris.withAppendedId(Account.CONTENT_URI, firstAccount.mId),
                null, null);
        // ...and then there was one
        assertEquals(1, EmailContent.count(mMockContext, Account.CONTENT_URI, null, null));

        // Run the reconciler
        SyncManager.reconcileAccountsWithAccountManager(context,
                makeSyncManagerAccountList(), accountManagerAccounts, true, resolver);

        // There should now be only one AccountManager account
        accountManagerAccounts = getAccountManagerAccounts(baselineAccounts);
        assertEquals(1, accountManagerAccounts.length);
        // ... and it should be account "3"
        assertEquals(getTestAccountEmailAddress("3"), accountManagerAccounts[0].name);
    }

    public void testReleaseSyncHolds() {
        Context context = mMockContext;
        SyncManager syncManager = new SyncManager();
        SyncError securityErrorAccount1 =
            syncManager.new SyncError(AbstractSyncService.EXIT_SECURITY_FAILURE, false);
        SyncError ioError =
            syncManager.new SyncError(AbstractSyncService.EXIT_IO_ERROR, false);
        SyncError securityErrorAccount2 =
            syncManager.new SyncError(AbstractSyncService.EXIT_SECURITY_FAILURE, false);
        // Create account and two mailboxes
        Account acct1 = ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", acct1.mId, true, context);
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", acct1.mId, true, context);
        Account acct2 = ProviderTestUtils.setupAccount("acct2", true, context);
        Mailbox box3 = ProviderTestUtils.setupMailbox("box3", acct2.mId, true, context);
        Mailbox box4 = ProviderTestUtils.setupMailbox("box4", acct2.mId, true, context);

        HashMap<Long, SyncError> errorMap = syncManager.mSyncErrorMap;
        // Add errors into the map
        errorMap.put(box1.mId, securityErrorAccount1);
        errorMap.put(box2.mId, ioError);
        errorMap.put(box3.mId, securityErrorAccount2);
        errorMap.put(box4.mId, securityErrorAccount2);
        // We should have 4
        assertEquals(4, errorMap.keySet().size());
        // Release the holds on acct2 (there are two of them)
        syncManager.releaseSyncHolds(context, AbstractSyncService.EXIT_SECURITY_FAILURE, acct2);
        // There should be two left
        assertEquals(2, errorMap.keySet().size());
        // And these are the two...
        assertNotNull(errorMap.get(box2.mId));
        assertNotNull(errorMap.get(box1.mId));

        // Put the two back
        errorMap.put(box3.mId, securityErrorAccount2);
        errorMap.put(box4.mId, securityErrorAccount2);
        // We should have 4 again
        assertEquals(4, errorMap.keySet().size());
        // Release all of the security holds
        syncManager.releaseSyncHolds(context, AbstractSyncService.EXIT_SECURITY_FAILURE, null);
        // There should be one left
        assertEquals(1, errorMap.keySet().size());
        // And this is the one
        assertNotNull(errorMap.get(box2.mId));

        // Release the i/o holds on account 2 (there aren't any)
        syncManager.releaseSyncHolds(context, AbstractSyncService.EXIT_IO_ERROR, acct2);
        // There should still be one left
        assertEquals(1, errorMap.keySet().size());

        // Release the i/o holds on account 1 (there's one)
        syncManager.releaseSyncHolds(context, AbstractSyncService.EXIT_IO_ERROR, acct1);
        // There should still be one left
        assertEquals(0, errorMap.keySet().size());
    }

}
