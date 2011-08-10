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

package com.android.email.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;

import com.android.email.Email;
import com.android.email.R;
import com.android.email.activity.ShortcutPickerFragment.AccountShortcutPickerFragment;
import com.android.email.activity.ShortcutPickerFragment.MailboxShortcutPickerFragment;
import com.android.email.activity.ShortcutPickerFragment.PickerCallback;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.Utility;

/**
 * Activity to configure the Email widget.
 */
public class WidgetConfiguration extends Activity implements OnClickListener, PickerCallback {
    /** ID of the newly created application widget */
    private int mAppWidgetId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        if (Email.DEBUG) {
            Log.i(Logging.LOG_TAG, "WidgetConfiguration initiated");
        }
        if (!AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(getIntent().getAction())) {
            // finish() immediately if we aren't supposed to be here
            finish();
            return;
        }

        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            mAppWidgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        // Set handler for the "cancel" button
        setContentView(R.layout.account_shortcut_picker);
        findViewById(R.id.cancel).setOnClickListener(this);

        if (getFragmentManager().findFragmentById(R.id.shortcut_list) == null) {
            // Load the account picking fragment if we haven't created a fragment yet
            // NOTE: do not add to history as this will be the first fragment in the flow
            AccountShortcutPickerFragment fragment = new AccountShortcutPickerFragment();
            getFragmentManager().beginTransaction().add(R.id.shortcut_list, fragment).commit();
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.cancel:
                finish();
                break;
        }
    }

    @Override
    public Integer buildFilter(Account account) {
        if (!Account.isNormalAccount(account.mId)) {
            return MailboxShortcutPickerFragment.FILTER_INBOX_ONLY
                    | MailboxShortcutPickerFragment.FILTER_ALLOW_UNREAD;
        }

        // We can't synced non-Inbox mailboxes for non-EAS accounts, so they don't sync
        // right now and it doesn't make sense to put them in a widget.
        return HostAuth.SCHEME_EAS.equals(account.getProtocol(this))
                ? MailboxShortcutPickerFragment.FILTER_ALLOW_ALL
                : MailboxShortcutPickerFragment.FILTER_INBOX_ONLY;
    }

    @Override
    public void onSelected(Account account, long mailboxId) {
        setupWidget(account, mailboxId);
        finish();
    }

    @Override
    public void onMissingData(boolean missingAccount, boolean missingMailbox) {
        if (Email.DEBUG) {
            Log.i(Logging.LOG_TAG, "WidgetConfiguration exited abnormally. Probably no accounts.");
        }
        Utility.showToast(this, R.string.widget_no_accounts);
        finish();
    }

    private void setupWidget(Account account, long mailboxId) {
        // save user selected preferences & create initial widget view
        WidgetManager.saveWidgetPrefs(this, mAppWidgetId, account.mId, mailboxId);
        WidgetManager.getInstance().getOrCreateWidget(this, mAppWidgetId).start();

        // Return "OK" result; make sure we pass along the original widget ID
        Intent resultValue = new Intent();
        resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
        setResult(RESULT_OK, resultValue);
    }
}
