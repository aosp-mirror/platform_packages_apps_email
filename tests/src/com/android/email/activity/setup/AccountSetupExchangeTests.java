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

package com.android.email.activity.setup;

import com.android.email.R;
import com.android.email.provider.EmailContent;

import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.widget.Button;
import android.widget.EditText;

/**
 * Tests of the basic UI logic in the Account Setup Incoming (IMAP / POP3) screen.
 */
@MediumTest
public class AccountSetupExchangeTests extends 
        ActivityInstrumentationTestCase2<AccountSetupExchange> {

    // borrowed from AccountSetupExchange
    private static final String EXTRA_ACCOUNT = "account";

    private AccountSetupExchange mActivity;
    private EditText mServerView;
    private Button mNextButton;
    
    public AccountSetupExchangeTests() {
        super("com.android.email", AccountSetupExchange.class);
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
        Intent i = getTestIntent("eas://user:password@server.com");
        setActivityIntent(i);
    }
    
    /**
     * Test processing with a complete, good URI -> good fields
     */
    public void testGoodUri() {
        Intent i = getTestIntent("eas://user:password@server.com");
        setActivityIntent(i);
        getActivityAndFields();
        assertTrue(mNextButton.isEnabled());
    }
    
    // TODO Add tests for valid usernames in eas
    // They would be <name> or <name>\<domain> or <name>/<domain> or a valid email address

    /**
     * No user is not OK - not enabled
     */
    public void testBadUriNoUser() {
        Intent i = getTestIntent("eas://:password@server.com");
        setActivityIntent(i);
        getActivityAndFields();
        assertFalse(mNextButton.isEnabled());
    }
    
    /**
     * No password is not OK - not enabled
     */
    public void testBadUriNoPassword() {
        Intent i = getTestIntent("eas://user@server.com");
        setActivityIntent(i);
        getActivityAndFields();
        assertFalse(mNextButton.isEnabled());
    }
    
    /**
     * Test for non-standard but OK server names
     */
    @UiThreadTest
    public void testGoodServerVariants() {
        getActivityAndFields();
        assertTrue(mNextButton.isEnabled());
        
        mServerView.setText("  server.com  ");
        assertTrue(mNextButton.isEnabled());
    }
        
    /**
     * Test for non-empty but non-OK server names
     */
    @UiThreadTest
    public void testBadServerVariants() {
        getActivityAndFields();
        assertTrue(mNextButton.isEnabled());
        
        mServerView.setText("  ");
        assertFalse(mNextButton.isEnabled());
        
        mServerView.setText("serv$er.com");
        assertFalse(mNextButton.isEnabled());
    }
        
    /**
     * Get the activity (which causes it to be started, using our intent) and get the UI fields
     */
    private void getActivityAndFields() {
        mActivity = getActivity();
        mServerView = (EditText) mActivity.findViewById(R.id.account_server);
        mNextButton = (Button) mActivity.findViewById(R.id.next);
    }
    
    /**
     * Create an intent with the Account in it
     */
    private Intent getTestIntent(String storeUriString) {
        EmailContent.Account account = new EmailContent.Account();
        account.setStoreUri(getInstrumentation().getTargetContext(), storeUriString);
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.putExtra(EXTRA_ACCOUNT, account);
        return i;
    }

}
