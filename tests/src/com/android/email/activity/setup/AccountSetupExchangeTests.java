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

import android.content.Context;
import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;

import com.android.email.R;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;

import java.net.URISyntaxException;

/**
 * Tests of the basic UI logic in the Account Setup Incoming (IMAP / POP3) screen.
 * You can run this entire test case with:
 *   runtest -c com.android.email.activity.setup.AccountSetupExchangeTests email
 */
@MediumTest
public class AccountSetupExchangeTests extends
        ActivityInstrumentationTestCase2<AccountSetupExchange> {
    //EXCHANGE-REMOVE-SECTION-START
    private AccountSetupExchange mActivity;
    private AccountSetupExchangeFragment mFragment;
    private EditText mServerView;
    private EditText mPasswordView;
    private CheckBox mSslRequiredCheckbox;
    private CheckBox mTrustAllCertificatesCheckbox;
    //EXCHANGE-REMOVE-SECTION-END

    public AccountSetupExchangeTests() {
        super(AccountSetupExchange.class);
    }

    //EXCHANGE-REMOVE-SECTION-START
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
    public void testGoodUri() throws URISyntaxException {
        Intent i = getTestIntent("eas://user:password@server.com");
        setActivityIntent(i);
        getActivityAndFields();
        assertTrue(mActivity.mNextButtonEnabled);
    }

    // TODO Add tests for valid usernames in eas
    // They would be <name> or <name>\<domain> or <name>/<domain> or a valid email address

    /**
     * No user is not OK - not enabled
     */
    public void testBadUriNoUser() throws URISyntaxException {
        Intent i = getTestIntent("eas://:password@server.com");
        setActivityIntent(i);
        getActivityAndFields();
        assertFalse(mActivity.mNextButtonEnabled);
    }

    /**
     * No password is not OK - not enabled
     */
    public void testBadUriNoPassword() throws URISyntaxException {
        Intent i = getTestIntent("eas://user@server.com");
        setActivityIntent(i);
        getActivityAndFields();
        assertFalse(mActivity.mNextButtonEnabled);
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
     * Test to confirm that passwords with leading or trailing spaces are accepted verbatim.
     */
    @UiThreadTest
    public void testPasswordNoTrim() {
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
    private void checkPassword(String password, boolean expectNext) {
        mPasswordView.setText(password);
        if (expectNext) {
            assertTrue(mActivity.mNextButtonEnabled);
        } else {
            assertFalse(mActivity.mNextButtonEnabled);
        }
    }

    /**
     * Test aspects of loadSettings()
     *
     * TODO: More cases
     */
    @UiThreadTest
    public void testLoadSettings() {
        // The default URI has no SSL and no "trust"
        getActivityAndFields();
        assertFalse(mSslRequiredCheckbox.isChecked());
        assertFalse(mTrustAllCertificatesCheckbox.isChecked());
        assertFalse(mTrustAllCertificatesCheckbox.getVisibility() == View.VISIBLE);

        // Setup host auth with variants of SSL enabled and check.  This also enables the
        // "trust certificates" checkbox (not checked, but visible now).
        Account account =
            ProviderTestUtils.setupAccount("account", false, mActivity.getBaseContext());
        account.mHostAuthRecv = ProviderTestUtils.setupHostAuth(
                "eas", "hostauth", false, mActivity.getBaseContext());
        account.mHostAuthRecv.mFlags |= HostAuth.FLAG_SSL;
        account.mHostAuthRecv.mFlags &= ~HostAuth.FLAG_TRUST_ALL;
        mActivity.mFragment.mLoaded = false;
        boolean loadResult = mActivity.mFragment.loadSettings(account);
        assertTrue(loadResult);
        assertTrue(mSslRequiredCheckbox.isChecked());
        assertFalse(mTrustAllCertificatesCheckbox.isChecked());
        assertTrue(mTrustAllCertificatesCheckbox.getVisibility() == View.VISIBLE);

        // Setup host auth with variants of SSL enabled and check.  This also enables the
        // "trust certificates" checkbox (not checked, but visible now).
        account.mHostAuthRecv.mFlags |= HostAuth.FLAG_TRUST_ALL;
        mActivity.mFragment.mLoaded = false;
        loadResult = mActivity.mFragment.loadSettings(account);
        assertTrue(loadResult);
        assertTrue(mSslRequiredCheckbox.isChecked());
        assertTrue(mTrustAllCertificatesCheckbox.isChecked());
        assertTrue(mTrustAllCertificatesCheckbox.getVisibility() == View.VISIBLE);

        // A simple test of an incomplete account, which will fail validation
        account.mHostAuthRecv.mPassword = "";
        mActivity.mFragment.mLoaded = false;
        loadResult = mActivity.mFragment.loadSettings(account);
        assertFalse(loadResult);
    }

    /**
     * TODO: Directly test validateFields() checking boolean result
     */

    /**
     * Get the activity (which causes it to be started, using our intent) and get the UI fields
     */
    private void getActivityAndFields() {
        mActivity = getActivity();
        mFragment = mActivity.mFragment;
        mServerView = (EditText) mActivity.findViewById(R.id.account_server);
        mPasswordView = (EditText) mActivity.findViewById(R.id.account_password);
        mSslRequiredCheckbox = (CheckBox) mActivity.findViewById(R.id.account_ssl);
        mTrustAllCertificatesCheckbox =
            (CheckBox) mActivity.findViewById(R.id.account_trust_certificates);
    }

    /**
     * Create an intent with the Account in it
     */
    private Intent getTestIntent(String storeUriString) throws URISyntaxException {
        Account account = new Account();
        Context context = getInstrumentation().getTargetContext();
        HostAuth auth = account.getOrCreateHostAuthRecv(context);
        HostAuth.setHostAuthFromString(auth, storeUriString);
        Intent i = new Intent(Intent.ACTION_MAIN);
        SetupData.init(SetupData.FLOW_MODE_NORMAL, account);
        SetupData.setAllowAutodiscover(false);
        return i;
    }
    //EXCHANGE-REMOVE-SECTION-END
}
