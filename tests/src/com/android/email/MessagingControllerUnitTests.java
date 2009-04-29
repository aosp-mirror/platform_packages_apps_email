/*
 * Copyright (C) 2009 The Android Open Source Project
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

import com.android.email.mail.Folder;
import com.android.email.mail.MockFolder;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * This is a series of unit tests for the MessagingController class.
 * 
 * Technically these are functional because they use the underlying preferences framework.
 */
@SmallTest
public class MessagingControllerUnitTests extends AndroidTestCase {

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
     * Test the code that copies server-supplied folder names into the account data
     */
    public void testUpdateAccountFolderNames() {
        MessagingController mc = MessagingController.getInstance(getContext());
        // Create a dummy account
        createTestAccount();
        // Refresh it to fill in all fields (many will have default values)
        mAccount.refresh(mPreferences);
        
        // Replace one entry, others are not included
        Folder[] folders1 = new Folder[] {
                new MyMockFolder(Folder.FolderRole.DRAFTS, "DRAFTS_1"),
        };
        mc.updateAccountFolderNames(mAccount, folders1);
        checkServerFolderNames("folders1", mAccount, "DRAFTS_1", "Sent", "Trash", "Outbox");
        
        // test that the data is shared across multiple account instantiations
        Account account2 = new Account(mPreferences, mUuid);
        checkServerFolderNames("folders1-2", account2, "DRAFTS_1", "Sent", "Trash", "Outbox");

        // Replace one entry, others are included but called out as unknown
        Folder[] folders2 = new Folder[] {
                new MyMockFolder(Folder.FolderRole.UNKNOWN, "DRAFTS_2"),
                new MyMockFolder(Folder.FolderRole.SENT, "SENT_2"),
                new MyMockFolder(Folder.FolderRole.UNKNOWN, "TRASH_2"),
                new MyMockFolder(Folder.FolderRole.UNKNOWN, "OUTBOX_2"),
        };
        mc.updateAccountFolderNames(mAccount, folders2);
        checkServerFolderNames("folders2", mAccount, "DRAFTS_1", "SENT_2", "Trash", "Outbox");
        
        // test that the data is shared across multiple account instantiations
        account2 = new Account(mPreferences, mUuid);
        checkServerFolderNames("folders2-2", account2, "DRAFTS_1", "SENT_2", "Trash", "Outbox");
        
        // Replace one entry, check that "other" is ignored, check that Outbox is ignored
        Folder[] folders3 = new Folder[] {
                new MyMockFolder(Folder.FolderRole.OTHER, "OTHER_3a"),
                new MyMockFolder(Folder.FolderRole.TRASH, "TRASH_3"),
                new MyMockFolder(Folder.FolderRole.OTHER, "OTHER_3b"),
                new MyMockFolder(Folder.FolderRole.OUTBOX, "OUTBOX_3"),
        };
        mc.updateAccountFolderNames(mAccount, folders3);
        checkServerFolderNames("folders3", mAccount, "DRAFTS_1", "SENT_2", "TRASH_3", "Outbox");
        
        // test that the data is shared across multiple account instantiations
        account2 = new Account(mPreferences, mUuid);
        checkServerFolderNames("folders3-2", account2, "DRAFTS_1", "SENT_2", "TRASH_3", "Outbox");
    }
    
    /**
     * Quickly check all four folder name slots in mAccount
     */
    private void checkServerFolderNames(String diagnostic, Account account,
            String drafts, String sent, String trash, String outbox) {
        assertEquals(diagnostic, drafts, account.getDraftsFolderName());
        assertEquals(diagnostic, sent, account.getSentFolderName());
        assertEquals(diagnostic, trash, account.getTrashFolderName());
        assertEquals(diagnostic, outbox, account.getOutboxFolderName());
    }
    
    /**
     * MockFolder allows setting and retrieving role & name
     */
    private static class MyMockFolder extends MockFolder {
        private FolderRole mRole;
        private String mName;
        
        public MyMockFolder(FolderRole role, String name) {
            mRole = role;
            mName = name;
        }
        
        public String getName() {
            return mName;
        }
        
        @Override
        public FolderRole getRole() {
            return mRole;
        }
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
