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

package com.android.email.activity.setup;

import com.android.email.R;
import com.android.email.provider.EmailContent;

import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.widget.EditText;

/**
 * Tests of the basic UI logic in the Account Setup Incoming (IMAP / POP3) screen.
 * You can run this entire test case with:
 *   runtest -c com.android.email.activity.setup.AccountSetupIncomingTests email
 */
@MediumTest
public class AccountSetupIncomingTests extends 
        ActivityInstrumentationTestCase2<AccountSetupIncoming> {
    
    private AccountSetupIncoming mActivity;
    private EditText mServerView;
    
    public AccountSetupIncomingTests() {
        super(AccountSetupIncoming.class);
    }

    /**
     * Common setup code for all tests.  Sets up a default launch intent, which some tests
     * will use (others will override).
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // This sets up a default URI which can be used by any of the test methods below.
        // Individual test methods can replace this with a custom URI if they wish
        // (except those that run on the UI thread - for them, it's too late to change it.)
        Intent i = getTestIntent("imap://user:password@server.com:999");
        setActivityIntent(i);
    }
    
    /**
     * Test processing with a complete, good URI -> good fields
     */
    public void testGoodUri() {
        Intent i = getTestIntent("imap://user:password@server.com:999");
        setActivityIntent(i);
        getActivityAndFields();
        assertTrue(mActivity.mNextButtonEnabled);
    }
    
    /**
     * No user is not OK - not enabled
     */
    public void testBadUriNoUser() {
        Intent i = getTestIntent("imap://:password@server.com:999");
        setActivityIntent(i);
        getActivityAndFields();
        assertFalse(mActivity.mNextButtonEnabled);
    }
    
    /**
     * No password is not OK - not enabled
     */
    public void testBadUriNoPassword() {
        Intent i = getTestIntent("imap://user@server.com:999");
        setActivityIntent(i);
        getActivityAndFields();
        assertFalse(mActivity.mNextButtonEnabled);
    }
    
    /**
     * No port is OK - still enabled
     */
    public void testGoodUriNoPort() {
        Intent i = getTestIntent("imap://user:password@server.com");
        setActivityIntent(i);
        getActivityAndFields();
        assertTrue(mActivity.mNextButtonEnabled);
    }
        
    /**
     * Test for non-standard but OK server names
     */
    @UiThreadTest
    public void testGoodServerVariants() {
        getActivityAndFields();
        assertTrue(mActivity.mNextButtonEnabled);
        
        mServerView.setText("  server.com  ");
        assertTrue(mActivity.mNextButtonEnabled);
    }
        
    /**
     * Test for non-empty but non-OK server names
     */
    @UiThreadTest
    public void testBadServerVariants() {
        getActivityAndFields();
        assertTrue(mActivity.mNextButtonEnabled);
        
        mServerView.setText("  ");
        assertFalse(mActivity.mNextButtonEnabled);
        
        mServerView.setText("serv$er.com");
        assertFalse(mActivity.mNextButtonEnabled);
    }
    
    /**
     * TODO:  A series of tests to explore the logic around security models & ports
     * TODO:  A series of tests exploring differences between IMAP and POP3
     */
        
    /**
     * Get the activity (which causes it to be started, using our intent) and get the UI fields
     */
    private void getActivityAndFields() {
        mActivity = getActivity();
        mServerView = (EditText) mActivity.findViewById(R.id.account_server);
    }
    
    /**
     * Create an intent with the Account in it
     */
    private Intent getTestIntent(String storeUriString) {
        EmailContent.Account account = new EmailContent.Account();
        account.setStoreUri(getInstrumentation().getTargetContext(), storeUriString);
        SetupData.init(SetupData.FLOW_MODE_NORMAL, account);
        return new Intent(Intent.ACTION_MAIN);
    }

}
