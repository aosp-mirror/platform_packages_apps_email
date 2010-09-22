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

import com.android.email.SecurityPolicy.PolicySet;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * This is a series of unit tests for backup/restore of the SecurityPolicy class.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.SecurityPolicyTests email
*/
@MediumTest
public class SecurityPolicyTests extends ProviderTestCase2<EmailProvider> {

    private Context mMockContext;

    private static final PolicySet EMPTY_POLICY_SET =
        new PolicySet(0, PolicySet.PASSWORD_MODE_NONE, 0, 0, false);

    public SecurityPolicyTests() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mMockContext = new MockContext2(getMockContext(), this.mContext);
    }

    /**
     * Delete any dummy accounts we set up for this test
     */
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Private context wrapper used to add back getPackageName() for these tests
     */
    private static class MockContext2 extends ContextWrapper {

        private final Context mRealContext;

        public MockContext2(Context mockContext, Context realContext) {
            super(mockContext);
            mRealContext = realContext;
        }

        @Override
        public String getPackageName() {
            return mRealContext.getPackageName();
        }
    }

    /**
     * Retrieve the security policy object, and inject the mock context so it works as expected
     */
    private SecurityPolicy getSecurityPolicy() {
        SecurityPolicy sp = SecurityPolicy.getInstance(mMockContext);
        sp.setContext(mMockContext);
        return sp;
    }

    public void testPolicySetConstructor() {
        // We know that EMPTY_POLICY_SET doesn't generate an Exception or we wouldn't be here
        // Try some illegal parameters
        try {
            new PolicySet(100, PolicySet.PASSWORD_MODE_SIMPLE, 0, 0, false);
            fail("Too-long password allowed");
        } catch (IllegalArgumentException e) {
        }
        try {
            new PolicySet(0, PolicySet.PASSWORD_MODE_STRONG + 1, 0, 0, false);
            fail("Illegal password mode allowed");
        } catch (IllegalArgumentException e) {
        }
        PolicySet ps = new PolicySet(0, PolicySet.PASSWORD_MODE_SIMPLE, 0,
                PolicySet.SCREEN_LOCK_TIME_MAX + 1, false);
        assertEquals(PolicySet.SCREEN_LOCK_TIME_MAX, ps.getMaxScreenLockTimeForTest());
        ps = new PolicySet(0, PolicySet.PASSWORD_MODE_SIMPLE,
                PolicySet.PASSWORD_MAX_FAILS_MAX + 1, 0, false);
        assertEquals(PolicySet.PASSWORD_MAX_FAILS_MAX, ps.getMaxPasswordFailsForTest());
        // All password related fields should be zero when password mode is NONE
        // Illegal values for these fields should be ignored
        ps = new PolicySet(999/*length*/, PolicySet.PASSWORD_MODE_NONE,
                999/*fails*/, 9999/*screenlock*/, false);
        assertEquals(0, ps.mMinPasswordLength);
        assertEquals(0, ps.mMaxScreenLockTime);
        assertEquals(0, ps.mMaxPasswordFails);
    }

    /**
     * Test business logic of aggregating accounts with policies
     */
    public void testAggregator() {
        SecurityPolicy sp = getSecurityPolicy();

        // with no accounts, should return empty set
        assertTrue(EMPTY_POLICY_SET.equals(sp.computeAggregatePolicy()));

        // with accounts having no security, empty set
        Account a1 = ProviderTestUtils.setupAccount("no-sec-1", false, mMockContext);
        a1.mSecurityFlags = 0;
        a1.save(mMockContext);
        Account a2 = ProviderTestUtils.setupAccount("no-sec-2", false, mMockContext);
        a2.mSecurityFlags = 0;
        a2.save(mMockContext);
        assertTrue(EMPTY_POLICY_SET.equals(sp.computeAggregatePolicy()));

        // with a single account in security mode, should return same security as in account
        // first test with partially-populated policies
        Account a3 = ProviderTestUtils.setupAccount("sec-3", false, mMockContext);
        PolicySet p3ain = new PolicySet(10, PolicySet.PASSWORD_MODE_SIMPLE, 0, 0, false);
        p3ain.writeAccount(a3, null, true, mMockContext);
        PolicySet p3aout = sp.computeAggregatePolicy();
        assertNotNull(p3aout);
        assertEquals(p3ain, p3aout);

        // Repeat that test with fully-populated policies
        PolicySet p3bin = new PolicySet(10, PolicySet.PASSWORD_MODE_SIMPLE, 15, 16, false);
        p3bin.writeAccount(a3, null, true, mMockContext);
        PolicySet p3bout = sp.computeAggregatePolicy();
        assertNotNull(p3bout);
        assertEquals(p3bin, p3bout);

        // add another account which mixes it up (some fields will change, others will not)
        // pw length and pw mode - max logic - will change because larger #s here
        // fail count and lock timer - min logic - will *not* change because larger #s here
        // wipe required - OR logic - will *not* change here because false
        PolicySet p4in = new PolicySet(20, PolicySet.PASSWORD_MODE_STRONG, 25, 26, false);
        Account a4 = ProviderTestUtils.setupAccount("sec-4", false, mMockContext);
        p4in.writeAccount(a4, null, true, mMockContext);
        PolicySet p4out = sp.computeAggregatePolicy();
        assertNotNull(p4out);
        assertEquals(20, p4out.mMinPasswordLength);
        assertEquals(PolicySet.PASSWORD_MODE_STRONG, p4out.mPasswordMode);
        assertEquals(15, p4out.mMaxPasswordFails);
        assertEquals(16, p4out.mMaxScreenLockTime);
        assertFalse(p4out.mRequireRemoteWipe);

        // add another account which mixes it up (the remaining fields will change)
        // pw length and pw mode - max logic - will *not* change because smaller #s here
        // fail count and lock timer - min logic - will change because smaller #s here
        // wipe required - OR logic - will change here because true
        PolicySet p5in = new PolicySet(4, PolicySet.PASSWORD_MODE_SIMPLE, 5, 6, true);
        Account a5 = ProviderTestUtils.setupAccount("sec-5", false, mMockContext);
        p5in.writeAccount(a5, null, true, mMockContext);
        PolicySet p5out = sp.computeAggregatePolicy();
        assertNotNull(p5out);
        assertEquals(20, p5out.mMinPasswordLength);
        assertEquals(PolicySet.PASSWORD_MODE_STRONG, p5out.mPasswordMode);
        assertEquals(5, p5out.mMaxPasswordFails);
        assertEquals(6, p5out.mMaxScreenLockTime);
        assertTrue(p5out.mRequireRemoteWipe);
    }

    /**
     * Make sure aggregator (and any other direct DB accessors) handle the case of upgraded
     * accounts properly (where the security flags will be NULL instead of zero).
     */
    public void testNullFlags() {
        SecurityPolicy sp = getSecurityPolicy();

        Account a1 = ProviderTestUtils.setupAccount("null-sec-1", true, mMockContext);
        ContentValues cv = new ContentValues();
        cv.putNull(AccountColumns.SECURITY_FLAGS);
        Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, a1.mId);
        mMockContext.getContentResolver().update(uri, cv, null, null);

        Account a2 = ProviderTestUtils.setupAccount("no-sec-2", false, mMockContext);
        a2.mSecurityFlags = 0;
        a2.save(mMockContext);
        assertTrue(EMPTY_POLICY_SET.equals(sp.computeAggregatePolicy()));
    }

    /**
     * Make sure the fields are encoded properly for their max ranges.  This is looking
     * for any encoding mask/shift errors, which would cause bits to overflow into other fields.
     */
    @SmallTest
    public void testFieldIsolation() {
        PolicySet p = new PolicySet(PolicySet.PASSWORD_LENGTH_MAX, PolicySet.PASSWORD_MODE_SIMPLE,
                0, 0, false);
        assertEquals(PolicySet.PASSWORD_LENGTH_MAX, p.mMinPasswordLength);
        assertEquals(0, p.mMaxPasswordFails);
        assertEquals(0, p.mMaxScreenLockTime);
        assertFalse(p.mRequireRemoteWipe);

        p = new PolicySet(0, PolicySet.PASSWORD_MODE_STRONG, 0, 0, false);
        assertEquals(0, p.mMinPasswordLength);
        assertEquals(PolicySet.PASSWORD_MODE_STRONG, p.mPasswordMode);
        assertEquals(0, p.mMinPasswordLength);
        assertEquals(0, p.mMaxPasswordFails);
        assertEquals(0, p.mMaxScreenLockTime);
        assertFalse(p.mRequireRemoteWipe);

        p = new PolicySet(0, PolicySet.PASSWORD_MODE_SIMPLE, PolicySet.PASSWORD_MAX_FAILS_MAX, 0,
                false);
        assertEquals(0, p.mMinPasswordLength);
        assertEquals(PolicySet.PASSWORD_MAX_FAILS_MAX, p.mMaxPasswordFails);
        assertEquals(0, p.mMaxScreenLockTime);
        assertFalse(p.mRequireRemoteWipe);

        p = new PolicySet(0, PolicySet.PASSWORD_MODE_SIMPLE, 0, PolicySet.SCREEN_LOCK_TIME_MAX,
                false);
        assertEquals(0, p.mMinPasswordLength);
        assertEquals(0, p.mMaxPasswordFails);
        assertEquals(PolicySet.SCREEN_LOCK_TIME_MAX, p.mMaxScreenLockTime);
        assertFalse(p.mRequireRemoteWipe);

        p = new PolicySet(0, PolicySet.PASSWORD_MODE_NONE, 0, 0, true);
        assertEquals(0, p.mMinPasswordLength);
        assertEquals(0, p.mMaxPasswordFails);
        assertEquals(0, p.mMaxScreenLockTime);
        assertTrue(p.mRequireRemoteWipe);
    }

    /**
     * Test encoding into an Account and out again
     */
    @SmallTest
    public void testAccountEncoding() {
        PolicySet p1 = new PolicySet(1, PolicySet.PASSWORD_MODE_STRONG, 3, 4, true);
        Account a = new Account();
        final String SYNC_KEY = "test_sync_key";
        p1.writeAccount(a, SYNC_KEY, false, null);
        PolicySet p2 = new PolicySet(a);
        assertEquals(p1, p2);
    }

    /**
     * Test equality & hash.  Note, the tests for inequality are poor, as each field should
     * be tested individually.
     */
    @SmallTest
    public void testEqualsAndHash() {
        PolicySet p1 = new PolicySet(1, PolicySet.PASSWORD_MODE_STRONG, 3, 4, true);
        PolicySet p2 = new PolicySet(1, PolicySet.PASSWORD_MODE_STRONG, 3, 4, true);
        PolicySet p3 = new PolicySet(2, PolicySet.PASSWORD_MODE_SIMPLE, 5, 6, true);
        assertTrue(p1.equals(p2));
        assertFalse(p2.equals(p3));
        assertTrue(p1.hashCode() == p2.hashCode());
        assertFalse(p2.hashCode() == p3.hashCode());
    }

    /**
     * Test the API to set/clear policy hold flags in an account
     */
    public void testSetClearHoldFlag() {
        SecurityPolicy sp = getSecurityPolicy();

        Account a1 = ProviderTestUtils.setupAccount("holdflag-1", false, mMockContext);
        a1.mFlags = Account.FLAGS_NOTIFY_NEW_MAIL;
        a1.save(mMockContext);
        Account a2 = ProviderTestUtils.setupAccount("holdflag-2", false, mMockContext);
        a2.mFlags = Account.FLAGS_VIBRATE_ALWAYS | Account.FLAGS_SECURITY_HOLD;
        a2.save(mMockContext);

        // confirm clear until set
        Account a1a = Account.restoreAccountWithId(mMockContext, a1.mId);
        assertEquals(Account.FLAGS_NOTIFY_NEW_MAIL, a1a.mFlags);
        sp.setAccountHoldFlag(a1, true);
        assertEquals(Account.FLAGS_NOTIFY_NEW_MAIL | Account.FLAGS_SECURITY_HOLD, a1.mFlags);
        Account a1b = Account.restoreAccountWithId(mMockContext, a1.mId);
        assertEquals(Account.FLAGS_NOTIFY_NEW_MAIL | Account.FLAGS_SECURITY_HOLD, a1b.mFlags);

        // confirm set until cleared
        Account a2a = Account.restoreAccountWithId(mMockContext, a2.mId);
        assertEquals(Account.FLAGS_VIBRATE_ALWAYS | Account.FLAGS_SECURITY_HOLD, a2a.mFlags);
        sp.setAccountHoldFlag(a2, false);
        assertEquals(Account.FLAGS_VIBRATE_ALWAYS, a2.mFlags);
        Account a2b = Account.restoreAccountWithId(mMockContext, a2.mId);
        assertEquals(Account.FLAGS_VIBRATE_ALWAYS, a2b.mFlags);
    }

    /**
     * Test the API to clear all policy hold flags in all accounts)
     */
    public void testClearHoldFlags() {
        SecurityPolicy sp = getSecurityPolicy();

        Account a1 = ProviderTestUtils.setupAccount("holdflag-1", false, mMockContext);
        a1.mFlags = Account.FLAGS_NOTIFY_NEW_MAIL;
        a1.save(mMockContext);
        Account a2 = ProviderTestUtils.setupAccount("holdflag-2", false, mMockContext);
        a2.mFlags = Account.FLAGS_VIBRATE_ALWAYS | Account.FLAGS_SECURITY_HOLD;
        a2.save(mMockContext);

        // bulk clear
        sp.clearAccountHoldFlags();

        // confirm new values as expected - no hold flags; other flags unmolested
        Account a1a = Account.restoreAccountWithId(mMockContext, a1.mId);
        assertEquals(Account.FLAGS_NOTIFY_NEW_MAIL, a1a.mFlags);
        Account a2a = Account.restoreAccountWithId(mMockContext, a2.mId);
        assertEquals(Account.FLAGS_VIBRATE_ALWAYS, a2a.mFlags);
    }

    /**
     * Test the response to disabling DeviceAdmin status
     */
    public void testDisableAdmin() {
        Account a1 = ProviderTestUtils.setupAccount("disable-1", false, mMockContext);
        PolicySet p1 = new PolicySet(10, PolicySet.PASSWORD_MODE_SIMPLE, 0, 0, false);
        p1.writeAccount(a1, "sync-key-1", true, mMockContext);

        Account a2 = ProviderTestUtils.setupAccount("disable-2", false, mMockContext);
        PolicySet p2 = new PolicySet(20, PolicySet.PASSWORD_MODE_STRONG, 25, 26, false);
        p2.writeAccount(a2, "sync-key-2", true, mMockContext);

        Account a3 = ProviderTestUtils.setupAccount("disable-3", false, mMockContext);
        a3.mSecurityFlags = 0;
        a3.mSecuritySyncKey = null;
        a3.save(mMockContext);

        SecurityPolicy sp = getSecurityPolicy();

        // Confirm that "enabling" device admin does not change security status (flags & sync key)
        PolicySet before = sp.getAggregatePolicy();
        sp.onAdminEnabled(true);        // "enabled" should not change anything
        PolicySet after1 = sp.getAggregatePolicy();
        assertEquals(before, after1);
        Account a1a = Account.restoreAccountWithId(mMockContext, a1.mId);
        assertNotNull(a1a.mSecuritySyncKey);
        Account a2a = Account.restoreAccountWithId(mMockContext, a2.mId);
        assertNotNull(a2a.mSecuritySyncKey);
        Account a3a = Account.restoreAccountWithId(mMockContext, a3.mId);
        assertNull(a3a.mSecuritySyncKey);

        // Revoke device admin status.  In the accounts we set up, security values should be reset
        sp.onAdminEnabled(false);        // "disabled" should clear policies
        PolicySet after2 = sp.getAggregatePolicy();
        assertEquals(SecurityPolicy.NO_POLICY_SET, after2);
        Account a1b = Account.restoreAccountWithId(mMockContext, a1.mId);
        assertNull(a1b.mSecuritySyncKey);
        Account a2b = Account.restoreAccountWithId(mMockContext, a2.mId);
        assertNull(a2b.mSecuritySyncKey);
        Account a3b = Account.restoreAccountWithId(mMockContext, a3.mId);
        assertNull(a3b.mSecuritySyncKey);
    }
}
