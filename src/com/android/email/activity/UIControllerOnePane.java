/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.email.R;

import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;


/**
 * UI Controller for non x-large devices.  Supports a single-pane layout.
 */
class UIControllerOnePane extends UIControllerBase {
    public UIControllerOnePane(EmailActivity activity) {
        super(activity);
    }

    @Override
    public int getLayoutId() {
        return R.layout.email_activity_one_pane;
    }

    @Override
    public long getUIAccountId() {
        return -1;
    }

    @Override
    public boolean onBackPressed(boolean isSystemBackKey) {
        return false;
    }

    @Override
    public boolean onCreateOptionsMenu(MenuInflater inflater, Menu menu) {
        return false;
    }

    @Override
    public boolean onPrepareOptionsMenu(MenuInflater inflater, Menu menu) {
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return false;
    }

    @Override
    public void open(long accountId, long mailboxId, long messageId) {
    }

    @Override
    public void openAccount(long accountId) {
    }

    /*
     * STOPSHIP Remove this -- see the base class method.
     */
    @Override
    public long getMailboxSettingsMailboxId() {
        // Mailbox settigns is still experimental, and doesn't have to work on the phone.
        return -1;
    }

    /*
     * STOPSHIP Remove this -- see the base class method.
     */
    @Override
    public long getSearchMailboxId() {
        // Search is still experimental, and doesn't have to work on the phone.
        return -1;
    }
}
