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
import com.android.email.mail.Store;
import com.android.emailcommon.provider.EmailContent;

import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

/**
 * Tests of basic UI logic in the AccountSetupOptions screen.
 * You can run this entire test case with:
 *   runtest -c com.android.email.activity.setup.AccountSetupOptionsTests email
 */
@MediumTest
public class AccountSetupOptionsTests 
        extends ActivityInstrumentationTestCase2<AccountSetupOptions> {

    private AccountSetupOptions mActivity;
    private Spinner mCheckFrequencyView;
    private CheckBox mBackgroundAttachmentsView;
    
    public AccountSetupOptionsTests() {
        super(AccountSetupOptions.class);
    }

    /**
     * Test that POP accounts aren't displayed with a push option
     */
    public void testPushOptionPOP() {
        Intent i = getTestIntent("Name", "pop3://user:password@server.com");
        this.setActivityIntent(i);
        
        getActivityAndFields();
        
        boolean hasPush = frequencySpinnerHasValue(EmailContent.Account.CHECK_INTERVAL_PUSH);
        assertFalse(hasPush);
    }
        
    /**
     * Test that IMAP accounts aren't displayed with a push option
     */
    public void testPushOptionIMAP() {
        Intent i = getTestIntent("Name", "imap://user:password@server.com");
        this.setActivityIntent(i);
        
        getActivityAndFields();
        
        boolean hasPush = frequencySpinnerHasValue(EmailContent.Account.CHECK_INTERVAL_PUSH);
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
            
        Intent i = getTestIntent("Name", "eas://user:password@server.com");
        this.setActivityIntent(i);
        
        getActivityAndFields();
        
        boolean hasPush = frequencySpinnerHasValue(EmailContent.Account.CHECK_INTERVAL_PUSH);
        assertTrue(hasPush);
    }

    /**
     * Test that POP3 accounts don't have a "background attachments" checkbox
     */
    public void testBackgroundAttachmentsPop() {
        checkBackgroundAttachments("pop3://user:password@server.com", false);
    }

    /**
     * Test that IMAP accounts have a "background attachments" checkbox
     */
    public void testBackgroundAttachmentsImap() {
        checkBackgroundAttachments("imap://user:password@server.com", true);
    }

    /**
     * Test that EAS accounts have a "background attachments" checkbox
     */
    public void testBackgroundAttachmentsEas() {
        // This test should only be run if EAS is supported
        if (Store.StoreInfo.getStoreInfo("eas", this.getInstrumentation().getTargetContext()) 
                == null) {
            return;
        }
        checkBackgroundAttachments("eas://user:password@server.com", true);
    }

    /**
     * Common code to check that the "background attachments" checkbox is shown/hidden properly   
     */
    private void checkBackgroundAttachments(String storeUri, boolean expectVisible) {
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
            if (so != null && ((Integer)so.value).intValue() == value) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Create an intent with the Account in it
     */
    private Intent getTestIntent(String name, String storeUri) {
        EmailContent.Account account = new EmailContent.Account();
        account.setSenderName(name);
        account.setStoreUri(getInstrumentation().getTargetContext(), storeUri);
        SetupData.init(SetupData.FLOW_MODE_NORMAL, account);
        return new Intent(Intent.ACTION_MAIN);
    }
    
}
