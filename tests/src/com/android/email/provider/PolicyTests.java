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

import android.content.Context;
import android.os.Parcel;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;

import com.android.email.SecurityPolicy;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.AttachmentColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;

import java.util.ArrayList;

/**
 * This is a series of unit tests for the Policy class
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.provider.PolicyTests email
 */
@Suppress
@MediumTest
public class PolicyTests extends ProviderTestCase2<EmailProvider> {

    private static final String CANT_DOWNLOAD_SELECTION = "(" + AttachmentColumns.FLAGS + "&" +
        Attachment.FLAG_POLICY_DISALLOWS_DOWNLOAD + ")!=0";

    private Context mMockContext;

    public PolicyTests() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        // Invalidate all caches, since we reset the database for each test
        ContentCache.invalidateAllCaches();
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
        SecurityPolicy.setAccountPolicy(mMockContext, account1, policy1, securitySyncKey);
        Account account2 = ProviderTestUtils.setupAccount("acct2", true, mMockContext);
        Policy policy2 = new Policy();
        SecurityPolicy.setAccountPolicy(mMockContext, account2, policy2, securitySyncKey);
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
        SecurityPolicy.setAccountPolicy(mMockContext, account, policy, securitySyncKey);
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
        SecurityPolicy.clearAccountPolicy(mMockContext, account);
        account.refresh(mMockContext);
        // Make sure policyKey is cleared and policy is deleted
        assertEquals(0, account.mPolicyKey);
        assertEquals(0, EmailContent.count(mMockContext, Policy.CONTENT_URI));
        account.refresh(mMockContext);
        // The account's security sync key should also be null
        assertNull(account.mSecuritySyncKey);
    }

    private Attachment setupSimpleAttachment(String name, long size, Account acct) {
        Attachment att = ProviderTestUtils.setupAttachment(-1, name, size, false, mMockContext);
        att.mAccountKey = acct.mId;
        return att;
    }
    public void testSetAttachmentFlagsForNewPolicy() {
        Account acct = ProviderTestUtils.setupAccount("acct1", true, mMockContext);
        Policy policy1 = new Policy();
        policy1.mDontAllowAttachments = true;
        SecurityPolicy.setAccountPolicy(mMockContext, acct, policy1, null);
        Mailbox box = ProviderTestUtils.setupMailbox("box1", acct.mId, true, mMockContext);
        Message msg1 = ProviderTestUtils.setupMessage("message1", acct.mId, box.mId, false, false,
                mMockContext);
        ArrayList<Attachment> atts = new ArrayList<Attachment>();
        Attachment att1 = setupSimpleAttachment("fileName1", 10001L, acct);
        atts.add(att1);
        Attachment att2 = setupSimpleAttachment("fileName2", 20001L, acct);
        atts.add(att2);
        msg1.mAttachments = atts;
        msg1.save(mMockContext);
        Message msg2 = ProviderTestUtils.setupMessage("message2", acct.mId, box.mId, false, false,
                mMockContext);
        atts.clear();
        Attachment att3 = setupSimpleAttachment("fileName3", 70001L, acct);
        atts.add(att3);
        Attachment att4 = setupSimpleAttachment("fileName4", 5001L, acct);
        atts.add(att4);
        msg2.mAttachments = atts;
        msg2.save(mMockContext);
        // Make sure we've got our 4 attachments
        assertEquals(4, EmailContent.count(mMockContext, Attachment.CONTENT_URI));
        // All should be downloadable
        assertEquals(0, EmailContent.count(mMockContext, Attachment.CONTENT_URI,
                CANT_DOWNLOAD_SELECTION, null));
        // Enforce our no-attachments policy
        Policy.setAttachmentFlagsForNewPolicy(mMockContext, acct, policy1);
        // None should be downloadable
        assertEquals(4, EmailContent.count(mMockContext, Attachment.CONTENT_URI,
                CANT_DOWNLOAD_SELECTION, null));

        Policy policy2 = new Policy();
        policy2.mMaxAttachmentSize = 20000;
        // Switch to new policy that sets a limit, but otherwise allows attachments
        Policy.setAttachmentFlagsForNewPolicy(mMockContext, acct, policy2);
        // Two shouldn't be downloadable
        assertEquals(2, EmailContent.count(mMockContext, Attachment.CONTENT_URI,
                CANT_DOWNLOAD_SELECTION, null));
        // Make sure they're the right ones (att2 and att3)
        att2 = Attachment.restoreAttachmentWithId(mMockContext, att2.mId);
        assertTrue((att2.mFlags & Attachment.FLAG_POLICY_DISALLOWS_DOWNLOAD) != 0);
        att3 = Attachment.restoreAttachmentWithId(mMockContext, att3.mId);
        assertTrue((att3.mFlags & Attachment.FLAG_POLICY_DISALLOWS_DOWNLOAD) != 0);

        Policy policy3 = new Policy();
        policy3.mMaxAttachmentSize = 5001;
        // Switch to new policy that sets a lower limit
        Policy.setAttachmentFlagsForNewPolicy(mMockContext, acct, policy3);
        // Three shouldn't be downloadable
        assertEquals(3, EmailContent.count(mMockContext, Attachment.CONTENT_URI,
                CANT_DOWNLOAD_SELECTION, null));
        // Make sure the right one is downloadable
        att4 = Attachment.restoreAttachmentWithId(mMockContext, att4.mId);
        assertTrue((att4.mFlags & Attachment.FLAG_POLICY_DISALLOWS_DOWNLOAD) == 0);

        Policy policy4 = new Policy();
        // Switch to new policy that is without restrictions
        Policy.setAttachmentFlagsForNewPolicy(mMockContext, acct, policy4);
        // Nothing should be blocked now
        assertEquals(0, EmailContent.count(mMockContext, Attachment.CONTENT_URI,
                CANT_DOWNLOAD_SELECTION, null));
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
