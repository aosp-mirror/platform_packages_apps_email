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
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

import com.android.email.R;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;

import java.net.URISyntaxException;

/**
 * Tests of basic UI logic in the AccountSetupOptions screen.
 * You can run this entire test case with:
 *   runtest -c com.android.email.activity.setup.AccountSetupOptionsTests email
 */
@Suppress
@MediumTest
public class AccountSetupOptionsTests
        extends ActivityInstrumentationTestCase2<AccountSetupFinal> {

    private AccountSetupFinal mActivity;
    private Spinner mCheckFrequencyView;
    private CheckBox mBackgroundAttachmentsView;

    public AccountSetupOptionsTests() {
        super(AccountSetupFinal.class);
    }

    /**
     * Test that POP accounts aren't displayed with a push option
     */
    public void testPushOptionPOP()
            throws URISyntaxException {
        Intent i = getTestIntent("Name", "pop3://user:password@server.com");
        this.setActivityIntent(i);

        getActivityAndFields();

        boolean hasPush = frequencySpinnerHasValue(Account.CHECK_INTERVAL_PUSH);
        assertFalse(hasPush);
    }

    /**
     * Test that IMAP accounts aren't displayed with a push option
     */
    public void testPushOptionIMAP()
            throws URISyntaxException {
        Intent i = getTestIntent("Name", "imap://user:password@server.com");
        this.setActivityIntent(i);

        getActivityAndFields();

        boolean hasPush = frequencySpinnerHasValue(Account.CHECK_INTERVAL_PUSH);
        assertFalse(hasPush);
    }

    /**
     * Test that EAS accounts are displayed with a push option
     */
    public void testPushOptionEAS()
            throws URISyntaxException {
        Intent i = getTestIntent("Name", "eas://user:password@server.com");
        this.setActivityIntent(i);

        getActivityAndFields();

        boolean hasPush = frequencySpinnerHasValue(Account.CHECK_INTERVAL_PUSH);
        assertTrue(hasPush);
    }

    /**
     * Test that POP3 accounts don't have a "background attachments" checkbox
     */
    public void testBackgroundAttachmentsPop()
            throws URISyntaxException {
        checkBackgroundAttachments("pop3://user:password@server.com", false);
    }

    /**
     * Test that IMAP accounts have a "background attachments" checkbox
     */
    public void testBackgroundAttachmentsImap()
            throws URISyntaxException {
        checkBackgroundAttachments("imap://user:password@server.com", true);
    }

    /**
     * Test that EAS accounts have a "background attachments" checkbox
     */
    public void testBackgroundAttachmentsEas()
            throws URISyntaxException {
        checkBackgroundAttachments("eas://user:password@server.com", true);
    }

    /**
     * Common code to check that the "background attachments" checkbox is shown/hidden properly
     */
    private void checkBackgroundAttachments(String storeUri, boolean expectVisible)
            throws URISyntaxException {
        Intent i = getTestIntent("Name", storeUri);
        this.setActivityIntent(i);
        getActivityAndFields();

        boolean isNull = mBackgroundAttachmentsView == null;
        boolean isVisible = !isNull && (mBackgroundAttachmentsView.getVisibility() == View.VISIBLE);

        if (!expectVisible) {
            assertTrue(!isVisible);
        } else {
            assertTrue(!isNull);
            assertTrue(isVisible);
        }
    }

    /**
     * Get the activity (which causes it to be started, using our intent) and get the UI fields
     */
    private void getActivityAndFields() {
        mActivity = getActivity();
        mCheckFrequencyView = (Spinner) mActivity.findViewById(R.id.account_check_frequency);
        mBackgroundAttachmentsView = (CheckBox) mActivity.findViewById(
                R.id.account_background_attachments);
    }

    /**
     * Test the frequency values list for a particular value
     */
    private boolean frequencySpinnerHasValue(int value) {
        SpinnerAdapter sa = mCheckFrequencyView.getAdapter();

        for (int i = 0; i < sa.getCount(); ++i) {
            SpinnerOption so = (SpinnerOption) sa.getItem(i);
            if (so != null && ((Integer)so.value) == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create an intent with the Account in it
     */
    private Intent getTestIntent(String name, String storeUri)
            throws URISyntaxException {
        final Account account = new Account();
        account.setSenderName(name);
        final Context context = getInstrumentation().getTargetContext();
        final HostAuth auth = account.getOrCreateHostAuthRecv(context);
        auth.setHostAuthFromString(storeUri);
        final SetupDataFragment setupDataFragment =
                new SetupDataFragment();
        setupDataFragment.setFlowMode(SetupDataFragment.FLOW_MODE_NORMAL);
        setupDataFragment.setAccount(account);
        final Intent i = new Intent(AccountSetupFinal.ACTION_JUMP_TO_OPTIONS);
        i.putExtra(SetupDataFragment.EXTRA_SETUP_DATA, setupDataFragment);
        return i;
    }

}
