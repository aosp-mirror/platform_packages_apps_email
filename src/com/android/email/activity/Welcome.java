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

import com.android.email.Account;
import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.provider.EmailStore;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * The Welcome activity initializes the application and decides what Activity
 * the user should start with.
 * If no accounts are configured the user is taken to the Accounts Activity where they
 * can configure an account.
 * If a single account is configured the user is taken directly to the FolderMessageList for
 * the INBOX of that account.
 * If more than one account is configuref the user is takaen to the Accounts Activity so they
 * can select an account.
 */
public class Welcome extends Activity {
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        if (false) {
            testAccounts();
        }

        Account[] accounts = Preferences.getPreferences(this).getAccounts();
        if (accounts.length == 1) {
            FolderMessageList.actionHandleAccount(this, accounts[0], Email.INBOX);
        } else {
            Accounts.actionShowAccounts(this);
        }

        finish();
    }

    private void testAccounts() {
        EmailStore.Account acct = EmailStore.Account.getDefaultAccount(this);
        Log.i("EmailApp", "Default (none) = " + ((acct == null) ? "none" : acct.mDisplayName));

        EmailStore.HostAuth ha = new EmailStore.HostAuth();
        ha.mAddress = "imap.everyone.net";
        ha.mLogin = "foo@nextobject.com";
        ha.mPassword = "flatearth";
        ha.mProtocol = "imap";

        EmailStore.HostAuth sha = new EmailStore.HostAuth();
        sha.mAddress = "smtp.everyone.net";
        sha.mLogin = "foo@nextobject.com";
        sha.mPassword = "flatearth";
        sha.mProtocol = "smtp";

        EmailStore.Account acct1 = new EmailStore.Account();
        acct1.mHostAuthRecv = ha;
        acct1.mHostAuthSend = sha;
        acct1.mDisplayName = "Nextobject";
        acct1.mEmailAddress = "foo@nextobject.com";

        acct1.save(this);

        ha = new EmailStore.HostAuth();
        ha.mAddress = "imap.gmail.com";
        ha.mLogin = "mblank@google.com";
        ha.mPassword = "flatearth";
        ha.mProtocol = "imap";

        sha = new EmailStore.HostAuth();
        sha.mAddress = "smtp.gmail.com";
        sha.mLogin = "mblank@google.com";
        sha.mPassword = "flatearth";
        sha.mProtocol = "smtp";

        EmailStore.Account acct2 = new EmailStore.Account();
        acct2.mHostAuthRecv = ha;
        acct2.mHostAuthSend = sha;
        acct2.mDisplayName = "Google";
        acct2.mEmailAddress = "mblank@google.com";

        acct2.save(this);

        // Should be null
        acct = EmailStore.Account.getDefaultAccount(this);
        Log.i("EmailApp", "Default (Nextobject) = " + acct == null ? "none" : acct.mDisplayName);
        EmailStore.Account.setDefaultAccount(this, acct2.mId);
        acct = EmailStore.Account.getDefaultAccount(this);
        Log.i("EmailApp", "Default (Google) = " + acct == null ? "none" : acct.mDisplayName);
        EmailStore.Account.setDefaultAccount(this, acct1.mId);
        acct = EmailStore.Account.getDefaultAccount(this);
        Log.i("EmailApp", "Default (Nextobject) = " + acct == null ? "none" : acct.mDisplayName);
    }
}
