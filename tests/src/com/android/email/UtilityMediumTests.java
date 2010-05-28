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

import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.email.provider.EmailContent.Account;

import android.content.Context;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * This is a series of medium tests for the Utility class.  These tests must be locally
 * complete - no server(s) required.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.UtilityMediumTests email
 */
@MediumTest
public class UtilityMediumTests extends ProviderTestCase2<EmailProvider> {

    EmailProvider mProvider;
    Context mMockContext;

    public UtilityMediumTests() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
    }

    public void testFindExistingAccount() {
        // Create two accounts
        Account account1 = ProviderTestUtils.setupAccount("account1", false, mMockContext);
        account1.mHostAuthRecv = ProviderTestUtils.setupHostAuth("ha1", -1, false, mMockContext);
        account1.mHostAuthSend = ProviderTestUtils.setupHostAuth("ha1", -1, false, mMockContext);
        account1.save(mMockContext);
        Account account2 = ProviderTestUtils.setupAccount("account2", false, mMockContext);
        account2.mHostAuthRecv = ProviderTestUtils.setupHostAuth("ha2", -1, false, mMockContext);
        account2.mHostAuthSend = ProviderTestUtils.setupHostAuth("ha2", -1, false, mMockContext);
        account2.save(mMockContext);
        // Make sure we can find them
        Account acct = Utility.findExistingAccount(mMockContext, -1, "address-ha1", "login-ha1");
        assertNotNull(acct);
        assertEquals("account1", acct.mDisplayName);
        acct = Utility.findExistingAccount(mMockContext, -1, "address-ha2", "login-ha2");
        assertNotNull(acct);
        assertEquals("account2", acct.mDisplayName);
        // We shouldn't find account
        acct = Utility.findExistingAccount(mMockContext, -1, "address-ha3", "login-ha3");
        assertNull(acct);
        // Try to find account1, excluding account1
        acct = Utility.findExistingAccount(mMockContext, account1.mId, "address-ha1", "login-ha1");
        assertNull(acct);
    }
}
