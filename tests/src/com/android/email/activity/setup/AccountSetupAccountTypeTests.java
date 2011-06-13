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

package com.android.email.activity.setup;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.test.ActivityUnitTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.View;

import com.android.email.R;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;

import java.net.URISyntaxException;
import java.util.HashSet;

/**
 * This is a series of unit tests for the AccountSetupAccountType class.
 * You can run this entire test case with:
 *   runtest -c com.android.email.activity.setup.AccountSetupAccountTypeTests email
 */
@SmallTest
public class AccountSetupAccountTypeTests
        extends ActivityUnitTestCase<AccountSetupAccountType> {

    Context mContext;
    private HashSet<Account> mAccounts = new HashSet<Account>();

    public AccountSetupAccountTypeTests() {
        super(AccountSetupAccountType.class);
      }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mContext = this.getInstrumentation().getTargetContext();
    }

    /**
     * Delete any dummy accounts we set up for this test
     */
    @Override
    protected void tearDown() throws Exception {
        for (Account account : mAccounts) {
            Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, account.mId);
            mContext.getContentResolver().delete(uri, null, null);
        }

        // must call last because it scrubs member variables
        super.tearDown();
    }

    /**
     * Confirm that EAS is presented, when supported.
     */
    public void testEasOffered() throws URISyntaxException {
        createTestAccount("scheme1");
        AccountSetupAccountType activity = startActivity(getTestIntent(), null, null);
        View exchangeButton = activity.findViewById(R.id.exchange);

        int expected = View.GONE; // Default is hidden
        //EXCHANGE-REMOVE-SECTION-START
        expected = View.VISIBLE; // Will be visible if supported.
        //EXCHANGE-REMOVE-SECTION-END

        assertEquals(expected, exchangeButton.getVisibility());
    }

    /**
     * Create a dummy account with minimal fields
     */
    private Account createTestAccount(String scheme) throws URISyntaxException {
        Account account = new Account();
        HostAuth auth = account.getOrCreateHostAuthRecv(mContext);
        HostAuth.setHostAuthFromString(auth, scheme + "://user:pass@server.com:123");
        account.save(mContext);
        mAccounts.add(account);
        SetupData.init(SetupData.FLOW_MODE_NORMAL, account);
        return account;
    }

    /**
     * Create an intent with the Account in it
     */
    private Intent getTestIntent() {
        return new Intent(Intent.ACTION_MAIN);
    }

}
