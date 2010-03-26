/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.app.Activity;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;

/**
 * An activity to handle old-style (Android <= 1.6) desktop shortcut.
 */
public class FolderMessageList extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                openInbox();
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                finish();
            }
        }.execute();
    }

    private void openInbox() {
        // If it's the first time, we need to upgrade all accounts.
        if (UpgradeAccounts.doBulkUpgradeIfNecessary(this)) {
            return;
        }

        Uri uri = getIntent().getData();

        // Verify the format.
        if (uri == null || !"content".equals(uri.getScheme())
                || !"accounts".equals(uri.getAuthority())) {
            return;
        }
        String uuid = uri.getPath();
        if (uuid.length() > 0) {
            uuid = uuid.substring(1); // remove the beginning '/'.
        }
        if (TextUtils.isEmpty(uuid)) {
            return;
        }
        MessageList.actionOpenAccountInboxUuid(this, uuid);
    }
}
