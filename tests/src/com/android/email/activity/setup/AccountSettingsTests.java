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

import com.android.email.Account;
import com.android.email.mail.Store;

import android.content.Intent;
import android.preference.ListPreference;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Tests of basic UI logic in the AccountSettings screen.
 */
@MediumTest
public class AccountSettingsTests extends ActivityInstrumentationTestCase2<AccountSettings> {

    private AccountSettings mActivity;
    private ListPreference mCheckFrequency;
    
    private static final String PREFERENCE_FREQUENCY = "account_check_frequency";
    
    public AccountSettingsTests() {
        super("com.android.email", AccountSettings.class);
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
        Account account = new Account(this.getInstrumentation().getTargetContext());
        account.setName(name);
        account.setStoreUri(storeUri);
        account.setSenderUri(senderUri);
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.putExtra("account", account);     // AccountSetupNames.EXTRA_ACCOUNT == "account"
        return i;
    }
    
}
