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

import com.android.email.mail.Store;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.ListPreference;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Tests of basic UI logic in the AccountSettings screen.
 */
@MediumTest
public class AccountSettingsTests extends ActivityInstrumentationTestCase2<AccountSettings> {

    // Borrowed from AccountSettings
    private static final String EXTRA_ACCOUNT_ID = "account_id";

    private long mAccountId;
    private Account mAccount;

    private Context mContext;
    private AccountSettings mActivity;
    private ListPreference mCheckFrequency;

    private static final String PREFERENCE_FREQUENCY = "account_check_frequency";

    public AccountSettingsTests() {
        super(AccountSettings.class);
    }

    /**
     * Common setup code for all tests.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mContext = this.getInstrumentation().getTargetContext();
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
    public void testPushOptionPOP() {
        Intent i = getTestIntent("Name", "pop3://user:password@server.com",
                "smtp://user:password@server.com");
        this.setActivityIntent(i);

        getActivityAndFields();

        boolean hasPush = frequencySpinnerHasValue(Account.CHECK_INTERVAL_PUSH);
        assertFalse(hasPush);
    }

    /**
     * Test that IMAP accounts aren't displayed with a push option
     */
    public void testPushOptionIMAP() {
        Intent i = getTestIntent("Name", "imap://user:password@server.com",
                "smtp://user:password@server.com");
        this.setActivityIntent(i);

        getActivityAndFields();

        boolean hasPush = frequencySpinnerHasValue(Account.CHECK_INTERVAL_PUSH);
        assertFalse(hasPush);
    }

    /**
     * Test that EAS accounts are displayed with a push option
     */
    public void testPushOptionEAS() {
        // This test should only be run if EAS is supported
        if (Store.StoreInfo.getStoreInfo("eas", this.getInstrumentation().getTargetContext())
                == null) {
            return;
        }

        Intent i = getTestIntent("Name", "eas://user:password@server.com",
                "eas://user:password@server.com");
        this.setActivityIntent(i);

        getActivityAndFields();

        boolean hasPush = frequencySpinnerHasValue(Account.CHECK_INTERVAL_PUSH);
        assertTrue(hasPush);
    }

    /**
     * Get the activity (which causes it to be started, using our intent) and get the UI fields
     */
    private void getActivityAndFields() {
        mActivity = getActivity();
        mCheckFrequency = (ListPreference) mActivity.findPreference(PREFERENCE_FREQUENCY);
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
    private Intent getTestIntent(String name, String storeUri, String senderUri) {
        mAccount = new Account();
        mAccount.setSenderName(name);
        // For EAS, at least, email address is required
        mAccount.mEmailAddress = "user@server.com";
        mAccount.setStoreUri(mContext, storeUri);
        mAccount.setSenderUri(mContext, senderUri);
        mAccount.save(mContext);
        mAccountId = mAccount.mId;

        Intent i = new Intent(Intent.ACTION_MAIN);
        i.putExtra(EXTRA_ACCOUNT_ID, mAccountId);
        return i;
    }

}
