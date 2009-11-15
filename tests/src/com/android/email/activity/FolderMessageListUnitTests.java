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

package com.android.email.activity;

import com.android.email.provider.EmailContent;

import android.content.ContentUris;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * This is a series of unit tests for the FolderMessageList class.
 * 
 * This is just unit tests of simple statics - the activity is not instantiated
 */
@SmallTest
public class FolderMessageListUnitTests extends AndroidTestCase {

    private long mAccountId;
    private EmailContent.Account mAccount;
    
    /**
     * Delete any dummy accounts we set up for this test
     */
    @Override
    protected void tearDown() throws Exception {
        if (mAccount != null) {
            Uri uri = ContentUris.withAppendedId(
                    EmailContent.Account.CONTENT_URI, mAccountId);
            getContext().getContentResolver().delete(uri, null, null);
        }
        
        // must call last because it scrubs member variables
        super.tearDown();
    }
    
//    /**
//     * Test of actionHandleAccount() variants.  Make sure they generate correct intents and 
//     * then call startActivity() with them.
//     */
//    public void testActionHandleAccount() {
//        // Create a dummy account
//        createTestAccount();
//        
//        // Create a mock context to catch the startActivity calls
//        MyContext mockContext = new MyContext(getContext());
//        
//        // First, try with no initial folder
//        FolderMessageList.actionHandleAccount(mockContext, mAccountId);
//        Intent i = mockContext.startActivityIntent;
//        assertNotNull(i);
//        checkIntent(i, null, mAccount, null);
//        
//        // Next try with initial folder specified
//        FolderMessageList.actionHandleAccount(mockContext, mAccountId, "test-folder-name");
//        i = mockContext.startActivityIntent;
//        assertNotNull(i);
//        checkIntent(i, null, mAccount, "test-folder-name");
//    }

    /**
     * Test of actionHandleAccountIntent().  Make sure it generates correct intents.
     */
//    public void testActionHandleAccountIntent() {
//        // Create a dummy account
//        createTestAccount();
//        
//        // First try with no initial folder
//        Intent result = FolderMessageList.actionHandleAccountIntent(
//                getContext(), mAccountId, null);
//        checkIntent(result, null, mAccount, null);
//        
//        // now try with a specified initial folder
//        result = FolderMessageList.actionHandleAccountIntent(
//                getContext(), mAccountId, "test-folder-name");
//        checkIntent(result, null, mAccount, "test-folder-name");
//    }

    /**
     * Test of actionHandleAccountUriIntent().  Make sure it generates correct intents.
     */
//    public void testActionHandleAccountUriIntent() {
//        // Create a dummy account
//        createTestAccount();
//        Uri uri = ContentUris.withAppendedId(EmailContent.Account.CONTENT_URI, mAccountId);
//        
//        // First try with no initial folder
//        Intent result = FolderMessageList.actionHandleAccountUriIntent(
//                getContext(), mAccountId, null);
//        checkIntent(result, uri, null, null);
//        
//        // now try with a specified initial folder
//        result = FolderMessageList.actionHandleAccountUriIntent(
//                getContext(), mAccountId, "test-folder-name");
//        checkIntent(result, uri, null, "test-folder-name");
//    }

    /**
     * Check the values in a generated intent
     */
    private void checkIntent(Intent i, 
            Uri expectData, EmailContent.Account expectAccount, String expectFolder) {
        
        Uri resultUri = i.getData();
        assertEquals(expectData, resultUri);
        
        long resultAccountId = i.getLongExtra("account", -1);
        long expectAccountId = (expectAccount != null) ? expectAccount.mId : -1;
        assertEquals(expectAccountId, resultAccountId);
        
        String resultFolder = i.getStringExtra("initialFolder");
        assertEquals(expectFolder, resultFolder);
    }

    /**
     * Create a dummy account with minimal fields
     */
    private void createTestAccount() {
        mAccount = new EmailContent.Account();
        mAccount.save(getContext());
        
        mAccountId = mAccount.mId;
    }
    
    /**
     * Mock Context so we can catch the startActivity call in actionHandleAccount()
     */
    private static class MyContext extends ContextWrapper {

        Intent startActivityIntent = null;
        
        public MyContext(Context base) {
            super(base);
        }
        
        @Override
        public void startActivity(Intent i) {
            startActivityIntent = i;
        }
        
        
    }
    
}
