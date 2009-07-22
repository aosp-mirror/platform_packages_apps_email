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

import com.android.email.mail.Store;
import com.android.email.provider.EmailContent;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.test.ActivityUnitTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.HashSet;

/**
 * This is a series of unit tests for the AccountSetupAccountType class.
 * 
 * This is just unit tests of simple calls - the activity is not instantiated
 */
@SmallTest
public class AccountSetupAccountTypeUnitTests 
        extends ActivityUnitTestCase<AccountSetupAccountType> {

    // Borrowed from AccountSetupAccountType
    private static final String EXTRA_ACCOUNT = "account";

    Context mContext;
    
    private HashSet<EmailContent.Account> mAccounts = new HashSet<EmailContent.Account>();
    
    public AccountSetupAccountTypeUnitTests() {
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
        for (EmailContent.Account account : mAccounts) {
            Uri uri = ContentUris.withAppendedId(
                    EmailContent.Account.CONTENT_URI, account.mId);
            mContext.getContentResolver().delete(uri, null, null);
        }
        
        // must call last because it scrubs member variables
        super.tearDown();
    }
    
    /**
     * Test store type limit enforcement
     */
    public void testStoreTypeLimits() {
        EmailContent.Account acct1 = createTestAccount("scheme1");
        EmailContent.Account acct2 = createTestAccount("scheme1");
        EmailContent.Account acct3 = createTestAccount("scheme2");
        
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
     * Create a dummy account with minimal fields
     */
    private EmailContent.Account createTestAccount(String scheme) {
        EmailContent.Account account = new EmailContent.Account();
        account.setStoreUri(mContext, scheme + "://user:pass@server.com:123");
        account.save(mContext);
        mAccounts.add(account);
        return account;
    }
    
    /**
     * Create an intent with the Account in it
     */
    private Intent getTestIntent(EmailContent.Account account) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.putExtra(EXTRA_ACCOUNT, account);
        return i;
    }

}
