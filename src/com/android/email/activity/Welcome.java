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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

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

        Account[] accounts = Preferences.getPreferences(this).getAccounts();
        if (accounts.length == 1) {
            FolderMessageList.actionHandleAccount(this, accounts[0], Email.INBOX);
        } else {
            Accounts.actionShowAccounts(this);
        }
        
        finish();
    }
}
