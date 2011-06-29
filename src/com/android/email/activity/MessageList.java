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
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * A dummy activity to support old-style (pre-honeycomb) account shortcuts.
 */
public class MessageList extends Activity {
    @VisibleForTesting
    static final String EXTRA_ACCOUNT_ID = "com.android.email.activity._ACCOUNT_ID";

    private final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Activity me = this;
        new EmailAsyncTask<Void, Void, Long>(mTaskTracker) {
            @Override
            protected Long doInBackground(Void... params) {
                return getAccountFromIntent(me, getIntent());
            }

            @Override
            protected void onSuccess(Long accountId) {
                if ((accountId == null) || (accountId == Account.NO_ACCOUNT)) {
                    // Account deleted?
                    Utility.showToast(me, R.string.toast_account_not_found);
                    Welcome.actionStart(me);
                } else {
                    Welcome.actionOpenAccountInbox(me, accountId);
                }
                finish();
            }
        }.executeParallel();
    }

    @Override
    protected void onDestroy() {
        mTaskTracker.cancellAllInterrupt();
        super.onDestroy();
    }

    @VisibleForTesting
    static long getAccountFromIntent(Context context, Intent i) {
        final Uri uri = i.getData();
        if (uri == null) {
            return Account.NO_ACCOUNT;
        }
        return Account.getAccountIdFromShortcutSafeUri(context, uri);
    }

    /**
     * Create a froyo/gingerbread style account shortcut intent.  Used by unit tests and
     * test code in {@link ShortcutPicker}.
     */
    @VisibleForTesting
    static Intent createFroyoIntent(Context context, Account account) {
        final Intent intent = new Intent(context, MessageList.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_ACCOUNT_ID, account.mId);
        intent.setData(account.getShortcutSafeUri());

        return intent;
    }
}
