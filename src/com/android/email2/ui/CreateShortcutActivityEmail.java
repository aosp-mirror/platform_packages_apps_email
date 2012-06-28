/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
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

package com.android.email2.ui;

import com.android.mail.providers.Account;
import com.android.mail.ui.FolderSelectionActivity;
import com.android.mail.ui.MailboxSelectionActivity;
import com.android.mail.utils.AccountUtils;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class CreateShortcutActivityEmail extends Activity {

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        Account[] cachedAccounts = AccountUtils.getSyncingAccounts(this);
        Intent intent = getIntent();
        if (cachedAccounts != null && cachedAccounts.length == 1) {
            intent.setClass(this, FolderSelectionActivity.class);
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_FORWARD_RESULT);
            intent.setAction(Intent.ACTION_CREATE_SHORTCUT);
            intent.putExtra(FolderSelectionActivity.EXTRA_ACCOUNT_SHORTCUT,
                    cachedAccounts[0]);
        } else {
            intent.setClass(this, MailboxSelectionActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        }
        startActivity(intent);
        finish();
    }
}
