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

package com.android.email;

import android.content.SharedPreferences;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * This is a series of unit tests for the Account class.
 * 
 * Technically these are functional because they use the underlying preferences framework.
 */
@MediumTest
public class AccountUnitTests extends AndroidTestCase {

    private Preferences mPreferences;
    
    private String mUuid;
    private Account mAccount;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        mPreferences = Preferences.getPreferences(getContext());
    }

    /**
     * Delete any dummy accounts we set up for this test
     */
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        
        if (mAccount != null && mPreferences != null) {
            mAccount.delete(mPreferences);
        }
    }
    
    /**
     * Test the update path from .transportUri to .senderUri
     */
    public void testTransportToSenderUpdate() {
        
        final String TEST_VALUE = "This Is The Sender Uri";
        
        // Create a dummy account
        createTestAccount();
        
        // Tweak it to look like an old account (with ".transportUri")
        SharedPreferences.Editor editor = mPreferences.mSharedPreferences.edit();
        editor.remove(mUuid + ".senderUri");
        editor.putString(mUuid + ".transportUri", Utility.base64Encode(TEST_VALUE));
        editor.commit();
        
        // Read it, see if we get back the string as a sender string
        mAccount.refresh(mPreferences);
        assertEquals(TEST_VALUE, mAccount.getSenderUri());
        
        // Update it - this will automatically convert it to the newer name
        mAccount.save(mPreferences);
        
        // Confirm that the field was replaced with the new form
        String newString = mPreferences.mSharedPreferences.getString(mUuid + ".senderUri", null);
        assertEquals(TEST_VALUE, Utility.base64Decode(newString));
        String oldString = mPreferences.mSharedPreferences.getString(mUuid + ".transportUri", null);
        assertNull(oldString);

    }
    
    /**
     * Test the update path for old IMAP accounts that didn't have DELETE_POLICY_ON_DELETE
     * properly preset.
     */
    public void testImapDeletePolicyUpdate() {

        final String STORE_URI_IMAP = "imap://user:pass@imap-server.com";
        final String STORE_URI_POP3 = "pop3://user:pass@pop3-server.com";
        
        // Test 1:  try it with a POP3 account - no update should occur
        
        // create a dummy account
        createTestAccount();
        
        // set up a minimal POP3 account with default value
        SharedPreferences.Editor editor = mPreferences.mSharedPreferences.edit();
        editor.putString(mUuid + ".storeUri", Utility.base64Encode(STORE_URI_POP3));
        editor.putInt(mUuid + ".deletePolicy", Account.DELETE_POLICY_NEVER);
        editor.commit();
        
        // read it in and confirm that we get the default value
        mAccount.refresh(mPreferences);
        assertEquals(Account.DELETE_POLICY_NEVER, mAccount.getDeletePolicy());
        
        // flush it and confirm that we don't change the database
        mAccount.save(mPreferences);
        int storedPolicy = mPreferences.mSharedPreferences.getInt(mUuid + ".deletePolicy", -1);
        assertEquals(Account.DELETE_POLICY_NEVER, storedPolicy);

        // Test 2:  try it with an IMAP account - this time we should see an auto-update

        // create a dummy account
        mAccount.delete(mPreferences);
        createTestAccount();
        
        // tweak it to have the wrong settings - this is what IMAP accounts look like
        // with manual setup, in earlier versions
        editor = mPreferences.mSharedPreferences.edit();
        editor.putString(mUuid + ".storeUri", Utility.base64Encode(STORE_URI_IMAP));
        editor.putInt(mUuid + ".deletePolicy", Account.DELETE_POLICY_NEVER);
        editor.commit();
        
        // Now read it in and confirm that we get the properly updated value
        mAccount.refresh(mPreferences);
        assertEquals(Account.DELETE_POLICY_ON_DELETE, mAccount.getDeletePolicy());
        
        // Now flush it and confirm that we fixed the database
        mAccount.save(mPreferences);
        storedPolicy = mPreferences.mSharedPreferences.getInt(mUuid + ".deletePolicy", -1);
        assertEquals(Account.DELETE_POLICY_ON_DELETE, storedPolicy);
    }

    /**
     * Test new flags field (added only for backups - not used by real/legacy accounts)
     */
    public void testFlagsField() {
        createTestAccount();
        assertEquals(0, mAccount.mBackupFlags);
        mAccount.save(mPreferences);
        mAccount.mBackupFlags = -1;
        mAccount.refresh(mPreferences);
        assertEquals(0, mAccount.mBackupFlags);

        mAccount.mBackupFlags = Account.BACKUP_FLAGS_IS_BACKUP;
        mAccount.save(mPreferences);
        mAccount.mBackupFlags = -1;
        mAccount.refresh(mPreferences);
        assertEquals(Account.BACKUP_FLAGS_IS_BACKUP, mAccount.mBackupFlags);

        mAccount.mBackupFlags = Account.BACKUP_FLAGS_SYNC_CONTACTS;
        mAccount.save(mPreferences);
        mAccount.mBackupFlags = -1;
        mAccount.refresh(mPreferences);
        assertEquals(Account.BACKUP_FLAGS_SYNC_CONTACTS, mAccount.mBackupFlags);

        mAccount.mBackupFlags = Account.BACKUP_FLAGS_IS_DEFAULT;
        mAccount.save(mPreferences);
        mAccount.mBackupFlags = -1;
        mAccount.refresh(mPreferences);
        assertEquals(Account.BACKUP_FLAGS_IS_DEFAULT, mAccount.mBackupFlags);
    }

    /**
     * Create a dummy account with minimal fields
     */
    private void createTestAccount() {
        mAccount = new Account(getContext());
        mAccount.save(mPreferences);
        
        mUuid = mAccount.getUuid();
    }
    
}
