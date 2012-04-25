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

import com.android.emailcommon.mail.MockFolder;
import com.android.emailcommon.provider.Account;

import android.content.ContentUris;
import android.net.Uri;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * This is a series of unit tests for the MessagingController class.
 * 
 * Technically these are functional because they use the underlying provider framework.
 */
@SmallTest
public class MessagingControllerUnitTests extends AndroidTestCase {

    private long mAccountId;
    private Account mAccount;
    
    /**
     * Delete any dummy accounts we set up for this test
     */
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        
        if (mAccount != null) {
            Uri uri = ContentUris.withAppendedId(
                    Account.CONTENT_URI, mAccountId);
            getContext().getContentResolver().delete(uri, null, null);
        }
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
        
        @Override
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
        mAccount = new Account();
        mAccount.save(getContext());
        
        mAccountId = mAccount.mId;
    }
    
}
