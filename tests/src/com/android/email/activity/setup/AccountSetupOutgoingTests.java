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

import android.content.Context;
import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;

import com.android.email.R;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;

import java.net.URISyntaxException;

/**
 * Tests of the basic UI logic in the Account Setup Outgoing (SMTP) screen.
 * You can run this entire test case with:
 *   runtest -c com.android.email.activity.setup.AccountSetupOutgoingTests email
 */
@MediumTest
public class AccountSetupOutgoingTests extends
        ActivityInstrumentationTestCase2<AccountSetupFinal> {

    private AccountSetupFinal mActivity;
    private EditText mServerView;
    private AuthenticationView mAuthenticationView;

    public AccountSetupOutgoingTests() {
        super(AccountSetupFinal.class);
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

    private boolean isNextButtonEnabled() {
        final View nextButton = mActivity.findViewById(R.id.next);
        return nextButton.isEnabled();
    }

    /**
     * Test processing with a complete, good URI -> good fields
     */
    @UiThreadTest
    public void testGoodUri() {
        getActivityAndFields();
        assertTrue(isNextButtonEnabled());
    }

    /**
     * No user is not OK - not enabled
     */
    public void testBadUriNoUser()
            throws Throwable {
        Intent i = getTestIntent("smtp://:password@server.com:999");
        setActivityIntent(i);
        getActivityAndFields();
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                final CheckBox requireLoginView = (CheckBox)
                        mActivity.findViewById(R.id.account_require_login);
                requireLoginView.setChecked(true);
            }
        });
        assertFalse(isNextButtonEnabled());
    }

    /**
     * No password is not OK - not enabled
     */
    public void testBadUriNoPassword()
            throws URISyntaxException {
        Intent i = getTestIntent("smtp://user@server.com:999");
        setActivityIntent(i);
        getActivityAndFields();
        assertFalse(isNextButtonEnabled());
    }

    /**
     * No port is OK - still enabled
     */
    public void testGoodUriNoPort()
            throws URISyntaxException {
        Intent i = getTestIntent("smtp://user:password@server.com");
        setActivityIntent(i);
        getActivityAndFields();
        assertTrue(isNextButtonEnabled());
    }

    /**
     * Test for non-standard but OK server names
     */
    @UiThreadTest
    public void testGoodServerVariants() {
        getActivityAndFields();
        assertTrue(isNextButtonEnabled());

        mServerView.setText("  server.com  ");
        assertTrue(isNextButtonEnabled());
    }

    /**
     * Test for non-empty but non-OK server names
     */
    @UiThreadTest
    public void testBadServerVariants() {
        getActivityAndFields();
        assertTrue(isNextButtonEnabled());

        mServerView.setText("  ");
        assertFalse(isNextButtonEnabled());

        mServerView.setText("serv$er.com");
        assertFalse(isNextButtonEnabled());
    }

    /**
     * Test to confirm that passwords with leading or trailing spaces are accepted verbatim.
     */
    @UiThreadTest
    public void brokentestPasswordNoTrim() throws URISyntaxException {
        getActivityAndFields();

        // Clear the password - should disable
        checkPassword(null, false);

        // Various combinations of spaces should be OK
        checkPassword(" leading", true);
        checkPassword("trailing ", true);
// TODO: need to fix this part of the test
//        checkPassword("em bedded", true);
//        checkPassword(" ", true);
    }

    /**
     * Check password field for a given password.  Should be called in UI thread.  Confirms that
     * the password has not been trimmed.
     *
     * @param password the password to test with
     * @param expectNext true if expected that this password will enable the "next" button
     */
    private void checkPassword(String password, boolean expectNext) throws URISyntaxException {
        mAuthenticationView.setPassword(password);
        if (expectNext) {
            assertTrue(isNextButtonEnabled());
        } else {
            assertFalse(isNextButtonEnabled());
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
        mAuthenticationView = (AuthenticationView) mActivity.findViewById(R.id.authentication_view);
    }

    /**
     * Create an intent with the Account in it
     */
    private Intent getTestIntent(String senderUriString)
            throws URISyntaxException {
        final Account account = new Account();
        final Context context = getInstrumentation().getTargetContext();
        final HostAuth auth = account.getOrCreateHostAuthSend(context);
        auth.setHostAuthFromString(senderUriString);
        final SetupDataFragment setupDataFragment =
                new SetupDataFragment();
        setupDataFragment.setFlowMode(SetupDataFragment.FLOW_MODE_NORMAL);
        setupDataFragment.setAccount(account);
        final Intent i = new Intent(AccountSetupFinal.ACTION_JUMP_TO_OUTGOING);
        i.putExtra(SetupDataFragment.EXTRA_SETUP_DATA, setupDataFragment);
        return i;
    }

}
