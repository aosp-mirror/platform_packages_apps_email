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

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.HostAuth;

/**
 * This is a series of unit tests for backup/restore of the Account class.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.provider.AccountBackupRestoreTests email
 */
@Suppress
@MediumTest
public class AccountBackupRestoreTests extends ProviderTestCase2<EmailProvider> {

    private Context mMockContext;

    public AccountBackupRestoreTests() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
    }

    /**
     * Delete any dummy accounts we set up for this test
     */
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public static void assertRestoredAccountEqual(Account expect, Account actual) {
        assertEquals(" mDisplayName", expect.mDisplayName, actual.mDisplayName);
        assertEquals(" mEmailAddress", expect.mEmailAddress, actual.mEmailAddress);

        assertEquals(" mSyncLookback", expect.mSyncLookback, actual.mSyncLookback);
        assertEquals(" mSyncInterval", expect.mSyncInterval, actual.mSyncInterval);
        assertEquals(" mFlags", expect.mFlags, actual.mFlags);
        assertEquals(" mSenderName", expect.mSenderName, actual.mSenderName);
        assertEquals(" mProtocolVersion", expect.mProtocolVersion,
                actual.mProtocolVersion);
        assertEquals(" mSignature", expect.mSignature, actual.mSignature);

        // Nulled out by backup
        assertEquals(0, actual.mPolicyKey);
        assertNull(actual.mSyncKey);
        assertNull(actual.mSecuritySyncKey);
    }

    /**
     * Test backup with accounts
     */
    public void testBackupAndRestore() {
        // Create real accounts in need of backup
        Account saved1 =
            ProviderTestUtils.setupAccount("testBackup1", false, mMockContext);
        saved1.mHostAuthRecv =
            ProviderTestUtils.setupHostAuth("legacy-recv", 0, false, mMockContext);
        saved1.mHostAuthSend =
            ProviderTestUtils.setupHostAuth("legacy-send", 0, false, mMockContext);
        saved1.save(mMockContext);
        Account saved2 =
            ProviderTestUtils.setupAccount("testBackup2", false, mMockContext);
        saved2.mHostAuthRecv =
            ProviderTestUtils.setupHostAuth("legacy-recv", 0, false, mMockContext);
        saved2.mHostAuthSend =
            ProviderTestUtils.setupHostAuth("legacy-send", 0, false, mMockContext);
        saved2.save(mMockContext);
        // Make sure they're in the database
        assertEquals(2, EmailContent.count(mMockContext, Account.CONTENT_URI));
        assertEquals(4, EmailContent.count(mMockContext, HostAuth.CONTENT_URI));

        // Backup the accounts
        AccountBackupRestore.backup(mMockContext);

        // Delete the accounts
        ContentResolver cr = mMockContext.getContentResolver();
        cr.delete(Account.CONTENT_URI, null, null);
        cr.delete(HostAuth.CONTENT_URI, null, null);

        // Make sure they're no longer in the database
        assertEquals(0, EmailContent.count(mMockContext, Account.CONTENT_URI));
        assertEquals(0, EmailContent.count(mMockContext, HostAuth.CONTENT_URI));

        // Because we restore accounts at the db open time, we first need to close the db
        // explicitly here.
        // Accounts will be restored next time we touch the db.
        getProvider().shutdown();

        // Make sure there are two accounts and four host auths
        assertEquals(2, EmailContent.count(mMockContext, Account.CONTENT_URI));
        assertEquals(4, EmailContent.count(mMockContext, HostAuth.CONTENT_URI));

        // Get a cursor to our accounts, from earliest to latest (same order as saved1/saved2)
        Cursor c = cr.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION, null, null, "_id ASC");
        assertNotNull(c);
        assertTrue(c.moveToNext());
        // Restore the account
        Account restored = new Account();
        restored.restore(c);
        // And the host auth's
        HostAuth recv = HostAuth.restoreHostAuthWithId(mMockContext, restored.mHostAuthKeyRecv);
        assertNotNull(recv);
        HostAuth send = HostAuth.restoreHostAuthWithId(mMockContext, restored.mHostAuthKeySend);
        assertNotNull(send);
        // The host auth's should be equal (except id)
        ProviderTestUtils.assertHostAuthEqual("backup", saved1.mHostAuthRecv, recv, false);
        ProviderTestUtils.assertHostAuthEqual("backup", saved1.mHostAuthSend, send, false);
        assertRestoredAccountEqual(saved1, restored);

        assertTrue(c.moveToNext());
        // Restore the account
        restored = new Account();
        restored.restore(c);
        // And the host auth's
        recv = HostAuth.restoreHostAuthWithId(mMockContext, restored.mHostAuthKeyRecv);
        assertNotNull(recv);
        send = HostAuth.restoreHostAuthWithId(mMockContext, restored.mHostAuthKeySend);
        assertNotNull(send);
        // The host auth's should be equal (except id)
        ProviderTestUtils.assertHostAuthEqual("backup", saved2.mHostAuthRecv, recv, false);
        ProviderTestUtils.assertHostAuthEqual("backup", saved2.mHostAuthSend, send, false);
        assertRestoredAccountEqual(saved2, restored);
    }
}
