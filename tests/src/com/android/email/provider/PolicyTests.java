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

import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.Policy;

import android.content.Context;
import android.os.Parcel;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * This is a series of unit tests for the Policy class
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.provider.PolicyTests email
 */

@MediumTest
public class PolicyTests extends ProviderTestCase2<EmailProvider> {

    private Context mMockContext;

    public PolicyTests() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        // Invalidate all caches, since we reset the database for each test
        ContentCache.invalidateAllCachesForTest();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    public void testGetAccountIdWithPolicyKey() {
        String securitySyncKey = "key";
        // Setup two accounts with policies
        Account account1 = ProviderTestUtils.setupAccount("acct1", true, mMockContext);
        Policy policy1 = new Policy();
        policy1.setAccountPolicy(mMockContext, account1, securitySyncKey);
        Account account2 = ProviderTestUtils.setupAccount("acct2", true, mMockContext);
        Policy policy2 = new Policy();
        policy2.setAccountPolicy(mMockContext, account2, securitySyncKey);
        // Get the accounts back from the database
        account1.refresh(mMockContext);
        account2.refresh(mMockContext);
        // Both should have valid policies
        assertTrue(account1.mPolicyKey > 0);
        // And they should be findable via getAccountIdWithPolicyKey
        assertTrue(account2.mPolicyKey > 0);
        assertEquals(account1.mId, Policy.getAccountIdWithPolicyKey(mMockContext,
                account1.mPolicyKey));
        assertEquals(account2.mId, Policy.getAccountIdWithPolicyKey(mMockContext,
                account2.mPolicyKey));
    }

    public void testSetAndClearAccountPolicy() {
        String securitySyncKey = "key";
        Account account = ProviderTestUtils.setupAccount("acct", true, mMockContext);
        // Nothing up my sleeve
        assertEquals(0, account.mPolicyKey);
        assertEquals(0, EmailContent.count(mMockContext, Policy.CONTENT_URI));
        Policy policy = new Policy();
        policy.setAccountPolicy(mMockContext, account, securitySyncKey);
        account.refresh(mMockContext);
        // We should have a policyKey now
        assertTrue(account.mPolicyKey > 0);
        Policy dbPolicy = Policy.restorePolicyWithId(mMockContext, account.mPolicyKey);
        // The policy should exist in the database
        assertNotNull(dbPolicy);
        // And it should be the same as the original
        assertEquals(policy, dbPolicy);
        // The account should have the security sync key set
        assertEquals(securitySyncKey, account.mSecuritySyncKey);
        Policy.clearAccountPolicy(mMockContext, account);
        account.refresh(mMockContext);
        // Make sure policyKey is cleared and policy is deleted
        assertEquals(0, account.mPolicyKey);
        assertEquals(0, EmailContent.count(mMockContext, Policy.CONTENT_URI));
        account.refresh(mMockContext);
        // The account's security sync key should also be null
        assertNull(account.mSecuritySyncKey);
    }

    public void testParcel() {
        Policy policy = new Policy();
        policy.mPasswordMode = Policy.PASSWORD_MODE_STRONG;
        policy.mPasswordMinLength = 6;
        policy.mPasswordComplexChars = 5;
        policy.mPasswordExpirationDays = 4;
        policy.mPasswordHistory = 3;
        policy.mPasswordMaxFails = 8;
        policy.mMaxScreenLockTime = 600;
        policy.mRequireRemoteWipe = true;
        policy.mRequireEncryption = true;
        policy.mRequireEncryptionExternal = true;
        policy.mRequireManualSyncWhenRoaming = true;
        policy.mDontAllowCamera = false;
        policy.mDontAllowAttachments = true;
        policy.mDontAllowHtml = false;
        policy.mMaxAttachmentSize = 22222;
        policy.mMaxTextTruncationSize = 33333;
        policy.mMaxHtmlTruncationSize = 44444;
        policy.mMaxEmailLookback = 5;
        policy.mMaxCalendarLookback = 6;
        policy.mPasswordRecoveryEnabled = true;
        Parcel parcel = Parcel.obtain();
        policy.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        Policy readPolicy = Policy.CREATOR.createFromParcel(parcel);
        assertEquals(policy, readPolicy);
    }
}
