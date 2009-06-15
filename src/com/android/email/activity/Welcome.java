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

package com.android.email.activity;

import com.android.email.Email;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent;
//import com.android.exchange.SyncManager;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;

/**
 * The Welcome activity initializes the application and decides what Activity
 * the user should start with.
 * If no accounts are configured the user is taken to the Accounts Activity where they
 * can configure an account.
 * If a single account is configured the user is taken directly to the FolderMessageList for
 * the INBOX of that account.
 * If more than one account is configured the user is taken to the Accounts Activity so they
 * can select an account.
 */
public class Welcome extends Activity {
    private static final boolean DEBUG_ADD_TEST_ACCOUNTS = false;        // DO NOT CHECK IN "TRUE"
    
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        if (DEBUG_ADD_TEST_ACCOUNTS) {
            testAccounts();
        }
        
        // TODO Automatically start Exchange service, until we can base this on the existence of
        // at least one Exchange account
        //startService(new Intent(this, SyncManager.class));
        
        // Find out how many accounts we have, and if there's just one, go directly to it
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    EmailContent.Account.CONTENT_URI, 
                    EmailContent.Account.ID_PROJECTION,
                    null, null, null);
            if (c.getCount() == 1) {
                c.moveToFirst();
                long id = c.getLong(EmailContent.Account.CONTENT_ID_COLUMN);
                FolderMessageList.actionHandleAccount(this, id, Email.INBOX);
                finish();
                return;
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }

        // Otherwise  (n=0 or n>1) go to the account info screen
        Accounts.actionShowAccounts(this);
        finish();
    }

    private void testAccounts() {
        EmailContent.Account acct = EmailContent.Account.getDefaultAccount(this);
        Log.i("EmailApp", "Default (none) = " + ((acct == null) ? "none" : acct.mDisplayName));

        EmailContent.HostAuth ha = new EmailContent.HostAuth();
        ha.mAddress = "imap.everyone.net";
        ha.mLogin = "foo@nextobject.com";
        ha.mPassword = "flatearth";
        ha.mProtocol = "imap";

        EmailContent.HostAuth sha = new EmailContent.HostAuth();
        sha.mAddress = "smtp.everyone.net";
        sha.mLogin = "foo@nextobject.com";
        sha.mPassword = "flatearth";
        sha.mProtocol = "smtp";

        EmailContent.Account acct1 = new EmailContent.Account();
        acct1.mHostAuthRecv = ha;
        acct1.mHostAuthSend = sha;
        acct1.mDisplayName = "Nextobject";
        acct1.mEmailAddress = "foo@nextobject.com";
        acct1.mIsDefault = true;

        acct1.saveOrUpdate(this);

        ha = new EmailContent.HostAuth();
        ha.mAddress = "imap.gmail.com";
        ha.mLogin = "mblank@google.com";
        ha.mPassword = "flatearth";
        ha.mProtocol = "imap";

        sha = new EmailContent.HostAuth();
        sha.mAddress = "smtp.gmail.com";
        sha.mLogin = "mblank@google.com";
        sha.mPassword = "flatearth";
        sha.mProtocol = "smtp";

        EmailContent.Account acct2 = new EmailContent.Account();
        acct2.mHostAuthRecv = ha;
        acct2.mHostAuthSend = sha;
        acct2.mDisplayName = "Google";
        acct2.mEmailAddress = "mblank@google.com";
        acct2.mIsDefault = true;                    // this should supercede the previous one

        acct2.saveOrUpdate(this);

        // TODO this should move to unit tests of the new Account code
        acct = EmailContent.Account.getDefaultAccount(this);
        Log.i("EmailApp", "Default (Google) = " + (acct == null ? "none" : acct.mDisplayName));
        
        acct1.setDefaultAccount(true);
        acct1.saveOrUpdate(this);
        acct = EmailContent.Account.getDefaultAccount(this);
        Log.i("EmailApp", "Default (Nextobject) = " + (acct == null ? "none" : acct.mDisplayName));
    }
}
