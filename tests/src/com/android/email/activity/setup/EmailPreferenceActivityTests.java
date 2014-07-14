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

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.ListPreference;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;

import com.android.emailcommon.provider.Account;

import java.net.URISyntaxException;

/**
 * Tests of basic UI logic in the Account Settings fragment.
 *
 * TODO: This should use a local provider for the test "accounts", and not touch user data
 * TODO: These cannot run in the single-pane mode, and need to be refactored into single-pane
 *       and multi-pane versions.  Until then, they are all disabled.
 *
 * To execute:  runtest -c com.android.email.activity.setup.AccountSettingsTests email
 */
@Suppress
@MediumTest
public class EmailPreferenceActivityTests
        extends ActivityInstrumentationTestCase2<EmailPreferenceActivity> {

    private long mAccountId;
    private Account mAccount;

    private Context mContext;
    private ListPreference mCheckFrequency;

    private static final String PREFERENCE_FREQUENCY = "account_check_frequency";

    public EmailPreferenceActivityTests() {
        super(EmailPreferenceActivity.class);
    }

    /**
     * Common setup code for all tests.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mContext = getInstrumentation().getTargetContext();
    }

    /**
     * Delete any dummy accounts we set up for this test
     */
    @Override
    protected void tearDown() throws Exception {
        if (mAccount != null) {
            Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, mAccountId);
            mContext.getContentResolver().delete(uri, null, null);
        }

        // must call last because it scrubs member variables
        super.tearDown();
    }

    /**
     * Test that POP accounts aren't displayed with a push option
     */
    public void testPushOptionPOP() throws Throwable {
        Intent i = getTestIntent("Name", "pop3://user:password@server.com",
                "smtp://user:password@server.com");
        setActivityIntent(i);

        getActivityAndFields();

        boolean hasPush = frequencySpinnerHasValue(Account.CHECK_INTERVAL_PUSH);
        assertFalse(hasPush);
    }

    /**
     * Test that IMAP accounts aren't displayed with a push option
     */
    public void testPushOptionIMAP() throws Throwable {
        Intent i = getTestIntent("Name", "imap://user:password@server.com",
                "smtp://user:password@server.com");
        setActivityIntent(i);

        getActivityAndFields();

        boolean hasPush = frequencySpinnerHasValue(Account.CHECK_INTERVAL_PUSH);
        assertFalse(hasPush);
    }

    /**
     * Test that EAS accounts are displayed with a push option
     */
    public void testPushOptionEAS() throws Throwable {
        Intent i = getTestIntent("Name", "eas://user:password@server.com",
                "eas://user:password@server.com");
        setActivityIntent(i);

        getActivityAndFields();

        boolean hasPush = frequencySpinnerHasValue(Account.CHECK_INTERVAL_PUSH);
        assertTrue(hasPush);
    }

    /**
     * Get the activity (which causes it to be started, using our intent) and get the UI fields
     */
    private void getActivityAndFields() throws Throwable {
        final EmailPreferenceActivity theActivity = getActivity();

        runTestOnUiThread(new Runnable() {
            public void run() {
                //AccountSettingsFragment f = theActivity.getSettingsFragment();
                //mCheckFrequency =
                //    (ListPreference) f.findPreference(PREFERENCE_FREQUENCY);
            }
        });
    }

    /**
     * Test the frequency values list for a particular value
     */
    private boolean frequencySpinnerHasValue(int value) {
        CharSequence[] values = mCheckFrequency.getEntryValues();
        for (CharSequence listValue : values) {
            if (listValue != null && Integer.parseInt(listValue.toString()) == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create an intent with the Account in it
     */
    private Intent getTestIntent(String name, String storeUri, String senderUri)
            throws URISyntaxException {
        mAccount = new Account();
        mAccount.setSenderName(name);
        // For EAS, at least, email address is required
        mAccount.mEmailAddress = "user@server.com";
        mAccount.getOrCreateHostAuthRecv(mContext).setHostAuthFromString(storeUri);
        mAccount.getOrCreateHostAuthSend(mContext).setHostAuthFromString(senderUri);
        mAccount.save(mContext);
        mAccountId = mAccount.mId;

        // TODO: We don't have an intent that takes an account object
        return null;
    }

}
