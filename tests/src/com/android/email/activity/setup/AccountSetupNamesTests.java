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

// TODO Test crashes.  Fix it.

/*
The problem is that this test creates a partial account which will be
used by the activity, but the account is picked up by MailService too
(which is probably not intentional), which crashes because the account
is not properly constructed.  (empty address)
 */

//
//package com.android.email.activity.setup;
//
//import com.android.email.R;
//import com.android.email.provider.EmailContent;
//
//import android.content.ContentUris;
//import android.content.Context;
//import android.content.Intent;
//import android.net.Uri;
//import android.test.ActivityInstrumentationTestCase2;
//import android.test.suitebuilder.annotation.MediumTest;
//import android.widget.Button;
//
///**
// * Tests of basic UI logic in the AccountSetupNamesTest screen.
// * You can run this entire test case with:
// *   runtest -c com.android.email.activity.setup.AccountSetupNamesTests email
// */
//@MediumTest
//public class AccountSetupNamesTests extends ActivityInstrumentationTestCase2<AccountSetupNames> {
//    private long mAccountId;
//    private EmailContent.Account mAccount;
//
//    private Context mContext;
//    private AccountSetupNames mActivity;
//    private Button mDoneButton;
//
//    public AccountSetupNamesTests() {
//        super(AccountSetupNames.class);
//    }
//
//    /**
//     * Common setup code for all tests.
//     */
//    @Override
//    protected void setUp() throws Exception {
//        super.setUp();
//
//        mContext = this.getInstrumentation().getTargetContext();
//    }
//
//    /**
//     * Delete any dummy accounts we set up for this test
//     */
//    @Override
//    protected void tearDown() throws Exception {
//        if (mAccount != null) {
//            Uri uri = ContentUris.withAppendedId(
//                    EmailContent.Account.CONTENT_URI, mAccountId);
//            mContext.getContentResolver().delete(uri, null, null);
//        }
//
//        // must call last because it scrubs member variables
//        super.tearDown();
//    }
//
//    /**
//     * Test a "good" account name (enables the button)
//     */
//    public void testGoodAccountName() {
//        Intent i = getTestIntent("imap", "GoodName");
//        this.setActivityIntent(i);
//
//        getActivityAndFields();
//
//        assertTrue(mDoneButton.isEnabled());
//    }
//
//    /**
//     * Test a "bad" account name (disables the button)
//     */
//    public void testBadAccountName() {
//        Intent i = getTestIntent("imap", "");
//        this.setActivityIntent(i);
//
//        getActivityAndFields();
//
//        assertFalse(mDoneButton.isEnabled());
//    }
//
//    /**
//     * Test a "bad" account name (disables the button)
//     */
//    public void testEasAccountName() {
//        Intent i = getTestIntent("eas", "");
//        this.setActivityIntent(i);
//
//        getActivityAndFields();
//
//        assertTrue(mDoneButton.isEnabled());
//    }
//
//    /**
//     * Get the activity (which causes it to be started, using our intent) and get the UI fields
//     */
//    private void getActivityAndFields() {
//        mActivity = getActivity();
//        mDoneButton = (Button) mActivity.findViewById(R.id.done);
//    }
//
//    /**
//     * Create an intent with the Account in it, using protocol as the protocol and name as the
//     * user's sender name
//     */
//    private Intent getTestIntent(String protocol, String name) {
//        mAccount = new EmailContent.Account();
//        mAccount.setSenderName(name);
//        HostAuth hostAuth = new HostAuth();
//        hostAuth.mProtocol = protocol;
//        mAccount.mHostAuthRecv = hostAuth;
//        mAccount.save(mContext);
//        mAccountId = mAccount.mId;
//        SetupData.init(SetupData.FLOW_MODE_NORMAL, mAccount);
//        return new Intent(Intent.ACTION_MAIN);
//    }
//}
