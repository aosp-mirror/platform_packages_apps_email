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
import android.widget.Button;
import android.widget.EditText;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Tests of the basic UI logic in the Account Setup Outgoing (SMTP) screen.
 */
@MediumTest
public class AccountSetupOutgoingTests extends 
        ActivityInstrumentationTestCase2<AccountSetupOutgoing> {

    private AccountSetupOutgoing mActivity;
    private EditText mServerView;
    private EditText mPasswordView;
    private Button mNextButton;
    
    public AccountSetupOutgoingTests() {
        super(AccountSetupOutgoing.class);
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
        Intent i = getTestIntent("smtp://user:password@server.com:999");
        setActivityIntent(i);
    }
    
    /**
     * Test processing with a complete, good URI -> good fields
     */
    public void testGoodUri() {
        getActivityAndFields();
        assertTrue(mNextButton.isEnabled());
    }
    
    /**
     * No user is not OK - not enabled
     */
    public void testBadUriNoUser() {
        Intent i = getTestIntent("smtp://:password@server.com:999");
        setActivityIntent(i);
        getActivityAndFields();
        assertFalse(mNextButton.isEnabled());
    }
    
    /**
     * No password is not OK - not enabled
     */
    public void testBadUriNoPassword() {
        Intent i = getTestIntent("smtp://user@server.com:999");
        setActivityIntent(i);
        getActivityAndFields();
        assertFalse(mNextButton.isEnabled());
    }
    
    /**
     * No port is OK - still enabled
     */
    public void testGoodUriNoPort() {
        Intent i = getTestIntent("smtp://user:password@server.com");
        setActivityIntent(i);
        getActivityAndFields();
        assertTrue(mNextButton.isEnabled());
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
     * Test to confirm that passwords with leading or trailing spaces are accepted verbatim.
     */
    @UiThreadTest
    public void testPasswordNoTrim() throws URISyntaxException {
        getActivityAndFields();

        // Clear the password - should disable
        checkPassword(null, false);

        // Various combinations of spaces should be OK
        checkPassword(" leading", true);
        checkPassword("trailing ", true);
        checkPassword("em bedded", true);
        checkPassword(" ", true);
    }

    /**
     * Check password field for a given password.  Should be called in UI thread.  Confirms that
     * the password has not been trimmed.
     *
     * @param password the password to test with
     * @param expectNext true if expected that this password will enable the "next" button
     */
    private void checkPassword(String password, boolean expectNext) throws URISyntaxException {
        mPasswordView.setText(password);
        if (expectNext) {
            assertTrue(mNextButton.isEnabled());
            URI uri = mActivity.getUri();
            String actualUserInfo = uri.getUserInfo();
            String actualPassword = actualUserInfo.split(":", 2)[1];
            assertEquals(password, actualPassword);
        } else {
            assertFalse(mNextButton.isEnabled());
        }
    }

    /**
     * TODO:  A series of tests to explore the logic around security models & ports
     */
    
    /**
     * Get the activity (which causes it to be started, using our intent) and get the UI fields
     */
    private void getActivityAndFields() {
        mActivity = getActivity();
        mServerView = (EditText) mActivity.findViewById(R.id.account_server);
        mPasswordView = (EditText) mActivity.findViewById(R.id.account_password);
        mNextButton = (Button) mActivity.findViewById(R.id.next);
    }
    
    /**
     * Create an intent with the Account in it
     */
    private Intent getTestIntent(String senderUriString) {
        EmailContent.Account account = new EmailContent.Account();
        account.setSenderUri(this.getInstrumentation().getTargetContext(), senderUriString);
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.putExtra("account", account);     // AccountSetupNames.EXTRA_ACCOUNT == "account"
        return i;
    }

}
