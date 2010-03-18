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

import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;

import android.content.Context;
import android.database.Cursor;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * This is a series of unit tests for backup/restore of the Account class.
 *
 * Technically these are functional because they use the underlying preferences framework.
 *
 * NOTE:  These tests are destructive of any "legacy" accounts that might be lying around.
 */
@MediumTest
public class AccountBackupRestoreTests extends ProviderTestCase2<EmailProvider> {

    private Preferences mPreferences;
    private Context mMockContext;

    public AccountBackupRestoreTests() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mMockContext = getMockContext();
        // Note: preferences are not supported by this mock context, so we must
        // explicitly use (and clean out) the real ones for now.
        mPreferences = Preferences.getPreferences(mContext);
    }

    /**
     * Delete any dummy accounts we set up for this test
     */
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        deleteLegacyAccounts();
    }

    /**
     * Delete *all* legacy accounts
     */
    private void deleteLegacyAccounts() {
        Account[] oldAccounts = mPreferences.getAccounts();
        for (Account oldAccount : oldAccounts) {
            oldAccount.delete(mPreferences);
        }
    }

    /**
     * Test backup with no accounts
     */
    public void testNoAccountBackup() {
        // create some "old" backups or legacy accounts
        Account backupAccount = new Account(mMockContext);
        backupAccount.save(mPreferences);
        // confirm they are there
        Account[] oldBackups = mPreferences.getAccounts();
        assertTrue(oldBackups.length >= 1);
        // make sure there are no accounts in the provider
        int numAccounts = EmailContent.count(mMockContext, EmailContent.Account.CONTENT_URI,
                null, null);
        assertEquals(0, numAccounts);
        // run backups
        AccountBackupRestore.doBackupAccounts(mMockContext, mPreferences);
        // confirm there are no backups made
        Account[] backups = mPreferences.getAccounts();
        assertEquals(0, backups.length);
    }

    /**
     * Test backup with accounts
     */
    public void testBackup() {
        // Clear the decks
        deleteLegacyAccounts();

        // Create real accounts in need of backup
        EmailContent.Account liveAccount1 =
            ProviderTestUtils.setupAccount("testBackup1", false, mMockContext);
        liveAccount1.mHostAuthRecv =
            ProviderTestUtils.setupHostAuth("legacy-recv", 0, false, mMockContext);
        liveAccount1.mHostAuthSend =
            ProviderTestUtils.setupHostAuth("legacy-send", 0, false, mMockContext);
        liveAccount1.setDefaultAccount(true);
        liveAccount1.save(mMockContext);
        EmailContent.Account liveAccount2 =
            ProviderTestUtils.setupAccount("testBackup2", false, mMockContext);
        liveAccount2.mHostAuthRecv =
            ProviderTestUtils.setupHostAuth("legacy-recv", 0, false, mMockContext);
        liveAccount2.mHostAuthSend =
            ProviderTestUtils.setupHostAuth("legacy-send", 0, false, mMockContext);
        liveAccount2.setDefaultAccount(false);
        liveAccount2.save(mMockContext);

        // run backups
        AccountBackupRestore.doBackupAccounts(mMockContext, mPreferences);

        // Confirm we have two backups now
        // Deep inspection is not performed here - see LegacyConversionsTests
        // We just check for basic identity & flags
        Account[] backups = mPreferences.getAccounts();
        assertEquals(2, backups.length);
        for (Account backup : backups) {
            if ("testBackup1".equals(backup.getDescription())) {
                assertTrue(0 != (backup.mBackupFlags & Account.BACKUP_FLAGS_IS_DEFAULT));
            } else if ("testBackup2".equals(backup.getDescription())) {
                assertFalse(0 != (backup.mBackupFlags & Account.BACKUP_FLAGS_IS_DEFAULT));
            } else {
                fail("unexpected backup name=" + backup.getDescription());
            }
        }
        Account backup1 = backups[0];
        assertTrue(0 != (backup1.mBackupFlags & Account.BACKUP_FLAGS_IS_BACKUP));
        assertEquals(liveAccount1.getDisplayName(), backup1.getDescription());
    }

    /**
     * TODO: Test backup EAS accounts, with and without contacts sync
     *
     * Blocker:  We need to inject the dependency on ContentResolver.getSyncAutomatically()
     * so we can make our fake accounts appear to be syncable or non-syncable
     */

    /**
     * Test no-restore with accounts found
     */
    public void testNoAccountRestore1() {
        // make sure there are no real backups
        deleteLegacyAccounts();

        // make sure there are test backups available
        Account backupAccount1 = setupLegacyBackupAccount("backup1");
        backupAccount1.save(mPreferences);
        Account backupAccount2 = setupLegacyBackupAccount("backup2");
        backupAccount2.save(mPreferences);

        // make sure there are accounts
        EmailContent.Account existing =
            ProviderTestUtils.setupAccount("existing", true, mMockContext);

        // run the restore
        boolean anyRestored = AccountBackupRestore.doRestoreAccounts(mMockContext, mPreferences);
        assertFalse(anyRestored);

        // make sure accounts still there
        int numAccounts = EmailContent.count(mMockContext, EmailContent.Account.CONTENT_URI,
                null, null);
        assertEquals(1, numAccounts);
    }

    /**
     * Test no-restore with no accounts & no backups
     */
    public void testNoAccountRestore2() {
        // make sure there are no real backups
        deleteLegacyAccounts();

        // make sure there are no accounts
        int numAccounts = EmailContent.count(mMockContext, EmailContent.Account.CONTENT_URI,
                null, null);
        assertEquals(0, numAccounts);

        // run the restore
        boolean anyRestored = AccountBackupRestore.doRestoreAccounts(mMockContext, mPreferences);
        assertFalse(anyRestored);

        // make sure accounts still there
        numAccounts = EmailContent.count(mMockContext, EmailContent.Account.CONTENT_URI,
                null, null);
        assertEquals(0, numAccounts);
    }

    /**
     * Test restore with 2 accounts.
     * Repeats test to verify restore of default account
     */
    public void testAccountRestore() {
        // make sure there are no real backups
        deleteLegacyAccounts();

        // create test backups
        Account backupAccount1 = setupLegacyBackupAccount("backup1");
        backupAccount1.mBackupFlags |= Account.BACKUP_FLAGS_IS_DEFAULT;
        backupAccount1.save(mPreferences);
        Account backupAccount2 = setupLegacyBackupAccount("backup2");
        backupAccount2.save(mPreferences);

        // run the restore
        boolean anyRestored = AccountBackupRestore.doRestoreAccounts(mMockContext, mPreferences);
        assertTrue(anyRestored);

        // Check the restored accounts
        // Deep inspection is not performed here - see LegacyConversionsTests for that
        // We just check for basic identity & flags
        Cursor c = mMockContext.getContentResolver().query(EmailContent.Account.CONTENT_URI,
                EmailContent.Account.CONTENT_PROJECTION, null, null, null);
        try {
            assertEquals(2, c.getCount());
            while (c.moveToNext()) {
                EmailContent.Account restored =
                    EmailContent.getContent(c, EmailContent.Account.class);
                if ("backup1".equals(restored.getDisplayName())) {
                    assertTrue(restored.mIsDefault);
                } else if ("backup2".equals(restored.getDisplayName())) {
                    assertFalse(restored.mIsDefault);
                } else {
                    fail("Unexpected restore account name=" + restored.getDisplayName());
                }
                checkRestoredTransientValues(restored);
            }
        } finally {
            c.close();
        }

        // clear out the backups & accounts and try again
        deleteLegacyAccounts();
        mMockContext.getContentResolver().delete(EmailContent.Account.CONTENT_URI, null, null);

        Account backupAccount3 = setupLegacyBackupAccount("backup3");
        backupAccount3.save(mPreferences);
        Account backupAccount4 = setupLegacyBackupAccount("backup4");
        backupAccount4.mBackupFlags |= Account.BACKUP_FLAGS_IS_DEFAULT;
        backupAccount4.save(mPreferences);

        // run the restore
        AccountBackupRestore.doRestoreAccounts(mMockContext, mPreferences);

        // Check the restored accounts
        // Deep inspection is not performed here - see LegacyConversionsTests for that
        // We just check for basic identity & flags
        c = mMockContext.getContentResolver().query(EmailContent.Account.CONTENT_URI,
                EmailContent.Account.CONTENT_PROJECTION, null, null, null);
        try {
            assertEquals(2, c.getCount());
            while (c.moveToNext()) {
                EmailContent.Account restored =
                    EmailContent.getContent(c, EmailContent.Account.class);
                if ("backup3".equals(restored.getDisplayName())) {
                    assertFalse(restored.mIsDefault);
                } else if ("backup4".equals(restored.getDisplayName())) {
                    assertTrue(restored.mIsDefault);
                } else {
                    fail("Unexpected restore account name=" + restored.getDisplayName());
                }
                checkRestoredTransientValues(restored);
            }
        } finally {
            c.close();
        }
    }

    /**
     * Check a given restored account to make sure that transient (non-backed-up) values
     * are initialized to reasonable values.
     */
    private void checkRestoredTransientValues(EmailContent.Account restored) {
        // sync key == null
        assertNull(restored.mSyncKey);
        // hostauth id's are no longer zero or -1
        assertTrue(restored.mHostAuthKeyRecv > 0);
        assertTrue(restored.mHostAuthKeySend > 0);
        // protocol version == null or non-empty string
        assertTrue(restored.mProtocolVersion == null || restored.mProtocolVersion.length() > 0);
    }

    /**
     * TODO: Test restore EAS accounts, with and without contacts sync
     *
     * Blocker:  We need to inject the dependency on account manager to catch the calls to it
     */

    /**
     * Setup a legacy backup account with many fields prefilled.
     */
    private Account setupLegacyBackupAccount(String name) {
        Account backup = new Account(mMockContext);

        // fill in useful fields
        backup.mUuid = "test-uid-" + name;
        backup.mStoreUri = "store://test/" + name;
        backup.mLocalStoreUri = "local://localhost/" + name;
        backup.mSenderUri = "sender://test/" + name;
        backup.mDescription = name;
        backup.mName = "name " + name;
        backup.mEmail = "email " + name;
        backup.mAutomaticCheckIntervalMinutes = 100;
        backup.mLastAutomaticCheckTime = 200;
        backup.mNotifyNewMail = true;
        backup.mDraftsFolderName = "drafts " + name;
        backup.mSentFolderName = "sent " + name;
        backup.mTrashFolderName = "trash " + name;
        backup.mOutboxFolderName = "outbox " + name;
        backup.mAccountNumber = 300;
        backup.mVibrate = true;
        backup.mVibrateWhenSilent = false;
        backup.mRingtoneUri = "ringtone://test/" + name;
        backup.mSyncWindow = 400;
        backup.mBackupFlags = Account.BACKUP_FLAGS_IS_BACKUP;
        backup.mProtocolVersion = "proto version" + name;
        backup.mDeletePolicy = Account.DELETE_POLICY_NEVER;
        backup.mSecurityFlags = 500;
        return backup;
    }
}
