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

import com.android.email.Account;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.mail.Store;

import android.content.Context;
import android.content.Intent;
import android.test.ActivityUnitTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.View;

import java.util.HashSet;

/**
 * This is a series of unit tests for the AccountSetupAccountType class.
 */
@SmallTest
public class AccountSetupAccountTypeUnitTests 
        extends ActivityUnitTestCase<AccountSetupAccountType> {

    Context mContext;
    private Preferences mPreferences;
    
    private HashSet<Account> mAccounts = new HashSet<Account>();
    
    public AccountSetupAccountTypeUnitTests() {
        super(AccountSetupAccountType.class);
      }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        mContext = this.getInstrumentation().getTargetContext();
        mPreferences = Preferences.getPreferences(mContext);
    }

    /**
     * Delete any dummy accounts we set up for this test
     */
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        
        if (mPreferences != null) {
            for (Account account : mAccounts) {
                account.delete(mPreferences);
            }
        }
    }
    
    /**
     * Test store type limit enforcement
     */
    public void testStoreTypeLimits() {
        Account acct1 = createTestAccount("scheme1");
        Account acct2 = createTestAccount("scheme1");
        Account acct3 = createTestAccount("scheme2");
        
        AccountSetupAccountType activity = startActivity(getTestIntent(acct1), null, null);

        // Test with no limit
        Store.StoreInfo info = new Store.StoreInfo();
        info.mAccountInstanceLimit = -1;
        info.mScheme = "scheme1";
        assertTrue("no limit", activity.checkAccountInstanceLimit(info));
        
        // Test with limit, but not reached
        info.mAccountInstanceLimit = 3;
        assertTrue("limit, but not reached", activity.checkAccountInstanceLimit(info));
        
        // Test with limit, reached
        info.mAccountInstanceLimit = 2;
        assertFalse("limit, reached", activity.checkAccountInstanceLimit(info));
    }

    /**
     * Confirm that EAS is not presented (not supported in this release)
     */
    public void testEasOffered() {
        Account acct1 = createTestAccount("scheme1");
        AccountSetupAccountType activity = startActivity(getTestIntent(acct1), null, null);
        View exchangeButton = activity.findViewById(R.id.exchange);
        assertEquals(View.GONE, exchangeButton.getVisibility());
    }

    /**
     * Create a dummy account with minimal fields
     */
    private Account createTestAccount(String scheme) {
        Account account = new Account(mContext);
        account.setStoreUri(scheme + "://user:pass@server.com:999");
        account.save(mPreferences);
        mAccounts.add(account);
        return account;
    }
    
    /**
     * Create an intent with the Account in it
     */
    private Intent getTestIntent(Account account) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.putExtra("account", account);     // AccountSetupNames.EXTRA_ACCOUNT == "account"
        return i;
    }

}
