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

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;

import com.android.email.provider.ContentCache;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.service.LegacyPolicySet;

/**
 * This is a series of unit tests for backup/restore of the SecurityPolicy class.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.SecurityPolicyTests email
 */

// TODO: after b/12085240 gets fixed, we need to see if this test can be enabled
@Suppress
@MediumTest
public class SecurityPolicyTests extends ProviderTestCase2<EmailProvider> {
    private Context mMockContext;
    private SecurityPolicy mSecurityPolicy;

    public SecurityPolicyTests() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    private static final Policy EMPTY_POLICY = new Policy();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMockContext = new MockContext2(getMockContext(), mContext);
        // Invalidate all caches, since we reset the database for each test
        ContentCache.invalidateAllCaches();
    }

    /**
     * Delete any dummy accounts we set up for this test
     */
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Private context wrapper used to add back getPackageName() for these tests.
     *
     * This class also implements {@link Context} method(s) that are called during tests.
     */
    private static class MockContext2 extends ContextWrapper {

        private final Context mRealContext;

        public MockContext2(Context mockContext, Context realContext) {
            super(mockContext);
            mRealContext = realContext;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public String getPackageName() {
            return mRealContext.getPackageName();
        }

        @Override
        public Object getSystemService(String name) {
            return mRealContext.getSystemService(name);
        }
    }

    /**
     * Create a Policy using the arguments formerly used to create a PolicySet; this minimizes the
     * changes needed for re-using the PolicySet unit test logic
     */
    private Policy setupPolicy(int minPasswordLength, int passwordMode, int maxPasswordFails,
            int maxScreenLockTime, boolean requireRemoteWipe, int passwordExpirationDays,
            int passwordHistory, int passwordComplexChars, boolean requireEncryption,
            boolean dontAllowCamera)
            throws IllegalArgumentException {
        Policy policy = new Policy();
        policy.mPasswordMinLength = minPasswordLength;
        policy.mPasswordMode = passwordMode;
        policy.mPasswordMaxFails = maxPasswordFails;
        policy.mMaxScreenLockTime = maxScreenLockTime;
        policy.mRequireRemoteWipe = requireRemoteWipe;
        policy.mPasswordExpirationDays = passwordExpirationDays;
        policy.mPasswordHistory = passwordHistory;
        policy.mPasswordComplexChars = passwordComplexChars;
        policy.mRequireEncryption = requireEncryption;
        policy.mDontAllowCamera = dontAllowCamera;
        return policy;
    }

    /**
     * Test business logic of aggregating accounts with policies
     */
    public void testAggregator() {
        mSecurityPolicy = SecurityPolicy.getInstance(mMockContext);

        // with no accounts, should return empty set
        assertEquals(EMPTY_POLICY, mSecurityPolicy.computeAggregatePolicy());

        // with accounts having no security, empty set
        ProviderTestUtils.setupAccount("no-sec-1", true, mMockContext);
        ProviderTestUtils.setupAccount("no-sec-2", true, mMockContext);
        assertEquals(EMPTY_POLICY, mSecurityPolicy.computeAggregatePolicy());

        // with a single account in security mode, should return same security as in account
        // first test with partially-populated policies
        Account a3 = ProviderTestUtils.setupAccount("sec-3", true, mMockContext);
        Policy p3ain = setupPolicy(10, Policy.PASSWORD_MODE_SIMPLE, 0, 0, false, 0, 0, 0,
                false, false);
        SecurityPolicy.setAccountPolicy(mMockContext, a3, p3ain, null);
        Policy p3aout = mSecurityPolicy.computeAggregatePolicy();
        assertNotNull(p3aout);
        assertEquals(p3ain, p3aout);

        // Repeat that test with fully-populated policies
        Policy p3bin = setupPolicy(10, Policy.PASSWORD_MODE_SIMPLE, 15, 16, false, 6, 2, 3,
                false, false);
        SecurityPolicy.setAccountPolicy(mMockContext, a3, p3bin, null);
        Policy p3bout = mSecurityPolicy.computeAggregatePolicy();
        assertNotNull(p3bout);
        assertEquals(p3bin, p3bout);

        // add another account which mixes it up (some fields will change, others will not)
        // pw length and pw mode - max logic - will change because larger #s here
        // fail count and lock timer - min logic - will *not* change because larger #s here
        // wipe required - OR logic - will *not* change here because false
        // expiration - will not change because 0 (unspecified)
        // max complex chars - max logic - will change
        // encryption required - OR logic - will *not* change here because false
        // don't allow camera - OR logic - will change here because it's true
        Policy p4in = setupPolicy(20, Policy.PASSWORD_MODE_STRONG, 25, 26, false, 0, 5, 7,
                false, true);
        Account a4 = ProviderTestUtils.setupAccount("sec-4", true, mMockContext);
        SecurityPolicy.setAccountPolicy(mMockContext, a4, p4in, null);
        Policy p4out = mSecurityPolicy.computeAggregatePolicy();
        assertNotNull(p4out);
        assertEquals(20, p4out.mPasswordMinLength);
        assertEquals(Policy.PASSWORD_MODE_STRONG, p4out.mPasswordMode);
        assertEquals(15, p4out.mPasswordMaxFails);
        assertEquals(16, p4out.mMaxScreenLockTime);
        assertEquals(6, p4out.mPasswordExpirationDays);
        assertEquals(5, p4out.mPasswordHistory);
        assertEquals(7, p4out.mPasswordComplexChars);
        assertFalse(p4out.mRequireRemoteWipe);
        assertFalse(p4out.mRequireEncryption);
        assertFalse(p4out.mRequireEncryptionExternal);
        assertTrue(p4out.mDontAllowCamera);

        // add another account which mixes it up (the remaining fields will change)
        // pw length and pw mode - max logic - will *not* change because smaller #s here
        // fail count and lock timer - min logic - will change because smaller #s here
        // wipe required - OR logic - will change here because true
        // expiration time - min logic - will change because lower here
        // history & complex chars - will not change because 0 (unspecified)
        // encryption required - OR logic - will change here because true
        // don't allow camera - OR logic - will *not* change here because it's already true
        Policy p5in = setupPolicy(4, Policy.PASSWORD_MODE_SIMPLE, 5, 6, true, 1, 0, 0,
                true, false);
        Account a5 = ProviderTestUtils.setupAccount("sec-5", true, mMockContext);
        SecurityPolicy.setAccountPolicy(mMockContext, a5, p5in, null);
        Policy p5out = mSecurityPolicy.computeAggregatePolicy();
        assertNotNull(p5out);
        assertEquals(20, p5out.mPasswordMinLength);
        assertEquals(Policy.PASSWORD_MODE_STRONG, p5out.mPasswordMode);
        assertEquals(5, p5out.mPasswordMaxFails);
        assertEquals(6, p5out.mMaxScreenLockTime);
        assertEquals(1, p5out.mPasswordExpirationDays);
        assertEquals(5, p5out.mPasswordHistory);
        assertEquals(7, p5out.mPasswordComplexChars);
        assertTrue(p5out.mRequireRemoteWipe);
        assertFalse(p5out.mRequireEncryptionExternal);
        assertTrue(p5out.mDontAllowCamera);
    }

    private long assertAccountPolicyConsistent(long accountId, long oldKey) {
        Account account = Account.restoreAccountWithId(mMockContext, accountId);
        long policyKey = account.mPolicyKey;

        assertTrue(policyKey > 0);

        // Found a policy. Ensure it matches.
        Policy policy = Policy.restorePolicyWithId(mMockContext, policyKey);
        assertNotNull(policy);
        assertEquals(account.mPolicyKey, policy.mId);
        assertEquals(
                accountId,
                Policy.getAccountIdWithPolicyKey(mMockContext, policy.mId));

        // Assert the old one isn't there.
        if (oldKey > 0) {
            assertNull("old policy not cleaned up",
                    Policy.restorePolicyWithId(mMockContext, oldKey));
        }

        return policyKey;
    }

    @SmallTest
    public void testSettingAccountPolicy() {
        Account account = ProviderTestUtils.setupAccount("testaccount", true, mMockContext);
        long accountId = account.mId;
        Policy initial = setupPolicy(10, Policy.PASSWORD_MODE_SIMPLE, 0, 0, false, 0, 0, 0,
                false, false);
        SecurityPolicy.setAccountPolicy(mMockContext, account, initial, null);

        long oldKey = assertAccountPolicyConsistent(account.mId, 0);

        Policy updated = setupPolicy(10, Policy.PASSWORD_MODE_SIMPLE, 0, 0, false, 0, 0, 0,
                false, false);
        SecurityPolicy.setAccountPolicy(mMockContext, account, updated, null);
        oldKey = assertAccountPolicyConsistent(account.mId, oldKey);

        // Remove the policy
        SecurityPolicy.clearAccountPolicy(
                mMockContext, Account.restoreAccountWithId(mMockContext, accountId));
        assertNull("old policy not cleaned up",
                Policy.restorePolicyWithId(mMockContext, oldKey));
    }

    /**
     * Test equality.  Note, the tests for inequality are poor, as each field should
     * be tested individually.
     */
    @SmallTest
    public void testEquals() {
        Policy p1 =
            setupPolicy(1, Policy.PASSWORD_MODE_STRONG, 3, 4, true, 7, 8, 9, false, false);
        Policy p2 =
            setupPolicy(1, Policy.PASSWORD_MODE_STRONG, 3, 4, true, 7, 8, 9, false, false);
        Policy p3 =
            setupPolicy(2, Policy.PASSWORD_MODE_SIMPLE, 5, 6, true, 7, 8, 9, false, false);
        Policy p4 =
            setupPolicy(1, Policy.PASSWORD_MODE_STRONG, 3, 4, true, 7, 8, 9, false, true);
        assertTrue(p1.equals(p2));
        assertFalse(p2.equals(p3));
        assertFalse(p1.equals(p4));
    }

    /**
     * Test the API to set/clear policy hold flags in an account
     */
    public void testSetClearHoldFlag() {
        Account a2 = ProviderTestUtils.setupAccount("holdflag-2", false, mMockContext);
        a2.mFlags = Account.FLAGS_SYNC_DISABLED | Account.FLAGS_SECURITY_HOLD;
        a2.save(mMockContext);

        // confirm set until cleared
        Account a2a = Account.restoreAccountWithId(mMockContext, a2.mId);
        assertEquals(Account.FLAGS_SYNC_DISABLED | Account.FLAGS_SECURITY_HOLD, a2a.mFlags);

        // set account hold flag off
        SecurityPolicy.setAccountHoldFlag(mMockContext, a2, false);
        assertEquals(Account.FLAGS_SYNC_DISABLED, a2.mFlags);

        // confirm account hold flag set
        Account a2b = Account.restoreAccountWithId(mMockContext, a2.mId);
        assertEquals(Account.FLAGS_SYNC_DISABLED, a2b.mFlags);
    }

    /**
     * Test the response to disabling DeviceAdmin status
     */
    public void testDisableAdmin() {
        Account a1 = ProviderTestUtils.setupAccount("disable-1", true, mMockContext);
        Policy p1 = setupPolicy(10, Policy.PASSWORD_MODE_SIMPLE, 0, 0, false, 0, 0, 0,
                false, false);
        SecurityPolicy.setAccountPolicy(mMockContext, a1, p1, "security-sync-key-1");

        Account a2 = ProviderTestUtils.setupAccount("disable-2", true, mMockContext);
        Policy p2 = setupPolicy(20, Policy.PASSWORD_MODE_STRONG, 25, 26, false, 0, 0, 0,
                false, false);
        SecurityPolicy.setAccountPolicy(mMockContext, a2, p2, "security-sync-key-2");

        Account a3 = ProviderTestUtils.setupAccount("disable-3", true, mMockContext);
        SecurityPolicy.clearAccountPolicy(mMockContext, a3);

        mSecurityPolicy = SecurityPolicy.getInstance(mMockContext);

        // Confirm that "enabling" device admin does not change security status (policy & sync key)
        Policy before = mSecurityPolicy.getAggregatePolicy();
        mSecurityPolicy.onAdminEnabled(true);        // "enabled" should not change anything
        Policy after1 = mSecurityPolicy.getAggregatePolicy();
        assertEquals(before, after1);
        Account a1a = Account.restoreAccountWithId(mMockContext, a1.mId);
        assertNotNull(a1a.mSecuritySyncKey);
        assertTrue(a1a.mPolicyKey > 0);
        Account a2a = Account.restoreAccountWithId(mMockContext, a2.mId);
        assertNotNull(a2a.mSecuritySyncKey);
        assertTrue(a2a.mPolicyKey > 0);
        Account a3a = Account.restoreAccountWithId(mMockContext, a3.mId);
        assertNull(a3a.mSecuritySyncKey);
        assertTrue(a3a.mPolicyKey == 0);

        mSecurityPolicy.deleteSecuredAccounts(mMockContext);
        Policy after2 = mSecurityPolicy.getAggregatePolicy();
        assertEquals(EMPTY_POLICY, after2);
        Account a1b = Account.restoreAccountWithId(mMockContext, a1.mId);
        assertNull(a1b);
        Account a2b = Account.restoreAccountWithId(mMockContext, a2.mId);
        assertNull(a2b);
        Account a3b = Account.restoreAccountWithId(mMockContext, a3.mId);
        assertNull(a3b.mSecuritySyncKey);
    }

    /**
     * Test the scanner that finds expiring accounts
     */
    public void testFindExpiringAccount() {
        ProviderTestUtils.setupAccount("expiring-1", true, mMockContext);

        // With no expiring accounts, this should return null.
        long nextExpiringAccountId = SecurityPolicy.findShortestExpiration(mMockContext);
        assertEquals(-1, nextExpiringAccountId);

        // Add a single expiring account
        Account a2 =
            ProviderTestUtils.setupAccount("expiring-2", true, mMockContext);
        Policy p2 = setupPolicy(20, Policy.PASSWORD_MODE_STRONG, 25, 26, false, 30, 0, 0,
                false, true);
        SecurityPolicy.setAccountPolicy(mMockContext, a2, p2, null);

        // The expiring account should be returned
        nextExpiringAccountId = SecurityPolicy.findShortestExpiration(mMockContext);
        assertEquals(a2.mId, nextExpiringAccountId);

        // Add an account with a longer expiration
        Account a3 = ProviderTestUtils.setupAccount("expiring-3", true, mMockContext);
        Policy p3 = setupPolicy(20, Policy.PASSWORD_MODE_STRONG, 25, 26, false, 60, 0, 0,
                false, true);
        SecurityPolicy.setAccountPolicy(mMockContext, a3, p3, null);

        // The original expiring account (a2) should be returned
        nextExpiringAccountId = SecurityPolicy.findShortestExpiration(mMockContext);
        assertEquals(a2.mId, nextExpiringAccountId);

        // Add an account with a shorter expiration
        Account a4 = ProviderTestUtils.setupAccount("expiring-4", true, mMockContext);
        Policy p4 = setupPolicy(20, Policy.PASSWORD_MODE_STRONG, 25, 26, false, 15, 0, 0,
                false, true);
        SecurityPolicy.setAccountPolicy(mMockContext, a4, p4, null);

        // The new expiring account (a4) should be returned
        nextExpiringAccountId = SecurityPolicy.findShortestExpiration(mMockContext);
        assertEquals(a4.mId, nextExpiringAccountId);
    }

    /**
     * Test the scanner that wipes expiring accounts
     */
    public void testWipeExpiringAccounts() {
        mSecurityPolicy = SecurityPolicy.getInstance(mMockContext);

        // Two accounts - a1 is normal, a2 has security (but no expiration)
        Account a1 = ProviderTestUtils.setupAccount("expired-1", true, mMockContext);
        Account a2 = ProviderTestUtils.setupAccount("expired-2", true, mMockContext);
        Policy p2 = setupPolicy(20, Policy.PASSWORD_MODE_STRONG, 25, 26, false, 0, 0, 0,
                false, true);
        SecurityPolicy.setAccountPolicy(mMockContext, a2, p2, null);

        // Add a mailbox & messages to each account
        long account1Id = a1.mId;
        long account2Id = a2.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false, true, mMockContext);
        ProviderTestUtils.setupMessage("message2", account1Id, box1Id, false, true, mMockContext);
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", account2Id, true, mMockContext);
        long box2Id = box2.mId;
        ProviderTestUtils.setupMessage("message3", account2Id, box2Id, false, true, mMockContext);
        ProviderTestUtils.setupMessage("message4", account2Id, box2Id, false, true, mMockContext);

        // Run the expiration code - should do nothing
        boolean wiped = SecurityPolicy.wipeExpiredAccounts(mMockContext);
        assertFalse(wiped);
        // check mailboxes & messages not wiped
        assertEquals(2, EmailContent.count(mMockContext, Account.CONTENT_URI));
        assertEquals(2, EmailContent.count(mMockContext, Mailbox.CONTENT_URI));
        assertEquals(4, EmailContent.count(mMockContext, Message.CONTENT_URI));

        // Add 3rd account that really expires
        Account a3 = ProviderTestUtils.setupAccount("expired-3", true, mMockContext);
        Policy p3 = setupPolicy(20, Policy.PASSWORD_MODE_STRONG, 25, 26, false, 30, 0, 0,
                false, true);
        SecurityPolicy.setAccountPolicy(mMockContext, a3, p3, null);

        // Add mailbox & messages to 3rd account
        long account3Id = a3.mId;
        Mailbox box3 = ProviderTestUtils.setupMailbox("box3", account3Id, true, mMockContext);
        long box3Id = box3.mId;
        ProviderTestUtils.setupMessage("message5", account3Id, box3Id, false, true, mMockContext);
        ProviderTestUtils.setupMessage("message6", account3Id, box3Id, false, true, mMockContext);

        // check new counts
        assertEquals(3, EmailContent.count(mMockContext, Account.CONTENT_URI));
        assertEquals(3, EmailContent.count(mMockContext, Mailbox.CONTENT_URI));
        assertEquals(6, EmailContent.count(mMockContext, Message.CONTENT_URI));

        // Run the expiration code - wipe acct #3
        wiped = SecurityPolicy.wipeExpiredAccounts(mMockContext);
        assertTrue(wiped);
        // check new counts - account survives but data is wiped
        assertEquals(3, EmailContent.count(mMockContext, Account.CONTENT_URI));
        assertEquals(2, EmailContent.count(mMockContext, Mailbox.CONTENT_URI));
        assertEquals(4, EmailContent.count(mMockContext, Message.CONTENT_URI));

        // Check security hold states - only #3 should be in hold
        Account account = Account.restoreAccountWithId(mMockContext, account1Id);
        assertEquals(0, account.mFlags & Account.FLAGS_SECURITY_HOLD);
        account = Account.restoreAccountWithId(mMockContext, account2Id);
        assertEquals(0, account.mFlags & Account.FLAGS_SECURITY_HOLD);
        account = Account.restoreAccountWithId(mMockContext, account3Id);
        assertEquals(Account.FLAGS_SECURITY_HOLD, account.mFlags & Account.FLAGS_SECURITY_HOLD);
    }

    /**
     * Test the code that converts from exchange-style quality to DPM/Lockscreen style quality.
     */
    public void testGetDPManagerPasswordQuality() {
        // Policy.PASSWORD_MODE_NONE -> DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED
        Policy p1 = setupPolicy(0, Policy.PASSWORD_MODE_NONE,
                0, 0, false, 0, 0, 0, false, false);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED,
                p1.getDPManagerPasswordQuality());

        // PASSWORD_MODE_SIMPLE -> PASSWORD_QUALITY_NUMERIC
        Policy p2 = setupPolicy(4, Policy.PASSWORD_MODE_SIMPLE,
                0, 0, false, 0, 0, 0, false, false);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC,
                p2.getDPManagerPasswordQuality());

        // PASSWORD_MODE_STRONG -> PASSWORD_QUALITY_ALPHANUMERIC
        Policy p3 = setupPolicy(4, Policy.PASSWORD_MODE_STRONG,
                0, 0, false, 0, 0, 0, false, false);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC,
                p3.getDPManagerPasswordQuality());

        // PASSWORD_MODE_STRONG + complex chars -> PASSWORD_QUALITY_COMPLEX
        Policy p4 = setupPolicy(4, Policy.PASSWORD_MODE_STRONG,
                0, 0, false, 0, 0 , 2, false, false);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_COMPLEX,
                p4.getDPManagerPasswordQuality());
    }

    private boolean policySetEqualsPolicy(PolicySet ps, Policy policy) {
        if ((ps.mPasswordMode >> LegacyPolicySet.PASSWORD_MODE_SHIFT) != policy.mPasswordMode) {
            return false;
        }
        if (ps.mMinPasswordLength != policy.mPasswordMinLength) return false;
        if (ps.mPasswordComplexChars != policy.mPasswordComplexChars) return false;
        if (ps.mPasswordHistory != policy.mPasswordHistory) return false;
        if (ps.mPasswordExpirationDays != policy.mPasswordExpirationDays) return false;
        if (ps.mMaxPasswordFails != policy.mPasswordMaxFails) return false;
        if (ps.mMaxScreenLockTime != policy.mMaxScreenLockTime) return false;
        if (ps.mRequireRemoteWipe != policy.mRequireRemoteWipe) return false;
        if (ps.mRequireEncryption != policy.mRequireEncryption) return false;
        if (ps.mRequireEncryptionExternal != policy.mRequireEncryptionExternal) return false;
        return true;
    }

    public void testPolicyFlagsToPolicy() {
        // Policy flags; the three sets included here correspond to policies for three test
        // accounts that, between them, use all of the possible policies
        long flags = 67096612L;
        PolicySet ps = new PolicySet(flags);
        Policy policy = LegacyPolicySet.flagsToPolicy(flags);
        assertTrue(policySetEqualsPolicy(ps, policy));
        flags = 52776591691846L;
        ps = new PolicySet(flags);
        policy = LegacyPolicySet.flagsToPolicy(flags);
        assertTrue(policySetEqualsPolicy(ps, policy));
        flags = 1689605957029924L;
        ps = new PolicySet(flags);
        policy = LegacyPolicySet.flagsToPolicy(flags);
        assertTrue(policySetEqualsPolicy(ps, policy));
    }

    /**
     * The old PolicySet class fields and constructor; we use this to test conversion to the
     * new Policy table scheme
     */
    private static class PolicySet {
        private final int mMinPasswordLength;
        private final int mPasswordMode;
        private final int mMaxPasswordFails;
        private final int mMaxScreenLockTime;
        private final boolean mRequireRemoteWipe;
        private final int mPasswordExpirationDays;
        private final int mPasswordHistory;
        private final int mPasswordComplexChars;
        private final boolean mRequireEncryption;
        private final boolean mRequireEncryptionExternal;

        /**
         * Create from values encoded in an account flags int
         */
        private PolicySet(long flags) {
            mMinPasswordLength = (int) ((flags & LegacyPolicySet.PASSWORD_LENGTH_MASK)
                    >> LegacyPolicySet.PASSWORD_LENGTH_SHIFT);
            mPasswordMode =
                (int) (flags & LegacyPolicySet.PASSWORD_MODE_MASK);
            mMaxPasswordFails = (int) ((flags & LegacyPolicySet.PASSWORD_MAX_FAILS_MASK)
                    >> LegacyPolicySet.PASSWORD_MAX_FAILS_SHIFT);
            mMaxScreenLockTime = (int) ((flags & LegacyPolicySet.SCREEN_LOCK_TIME_MASK)
                    >> LegacyPolicySet.SCREEN_LOCK_TIME_SHIFT);
            mRequireRemoteWipe = 0 != (flags & LegacyPolicySet.REQUIRE_REMOTE_WIPE);
            mPasswordExpirationDays = (int) ((flags & LegacyPolicySet.PASSWORD_EXPIRATION_MASK)
                    >> LegacyPolicySet.PASSWORD_EXPIRATION_SHIFT);
            mPasswordHistory = (int) ((flags & LegacyPolicySet.PASSWORD_HISTORY_MASK)
                    >> LegacyPolicySet.PASSWORD_HISTORY_SHIFT);
            mPasswordComplexChars = (int) ((flags & LegacyPolicySet.PASSWORD_COMPLEX_CHARS_MASK)
                    >> LegacyPolicySet.PASSWORD_COMPLEX_CHARS_SHIFT);
            mRequireEncryption = 0 != (flags & LegacyPolicySet.REQUIRE_ENCRYPTION);
            mRequireEncryptionExternal = 0 != (flags & LegacyPolicySet.REQUIRE_ENCRYPTION_EXTERNAL);
        }
    }
}
