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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;

import com.android.email.R;
import com.android.email.activity.ShortcutPickerFragment.AccountShortcutPickerFragment;
import com.android.email.activity.ShortcutPickerFragment.MailboxShortcutPickerFragment;
import com.android.email.activity.ShortcutPickerFragment.PickerCallback;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;

/**
 * This class implements a launcher shortcut for directly accessing a single account.
 */
public class ShortcutPicker extends Activity implements OnClickListener, PickerCallback {
    /**
     * If true, creates pre-honeycomb style shortcuts. This allows developers to test launching
     * the app from old style shortcuts (which point sat MessageList rather than Welcome) without
     * actually carrying over shortcuts from previous versions.
     */
    private final static boolean TEST_CREATE_OLD_STYLE_SHORTCUT = false; // DO NOT SUBMIT WITH TRUE

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // TODO Relax this test slightly in order to re-use this activity for widget creation
        if (!Intent.ACTION_CREATE_SHORTCUT.equals(getIntent().getAction())) {
            // finish() immediately if we aren't supposed to be here
            finish();
            return;
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
            // Shortcuts for combined accounts can only be for inboxes.
            return MailboxShortcutPickerFragment.FILTER_INBOX_ONLY;
        }

        return MailboxShortcutPickerFragment.FILTER_ALLOW_ALL;
    }

    @Override
    public void onSelected(Account account, long mailboxId) {
        String shortcutName;
        if (Account.isNormalAccount(account.mId) &&
                (Mailbox.getMailboxType(this, mailboxId) != Mailbox.TYPE_INBOX)) {
            shortcutName = Mailbox.getDisplayName(this, mailboxId);
        } else {
            shortcutName = account.getDisplayName();
        }
        setupShortcut(account, mailboxId, shortcutName);
        finish();
    }

    @Override
    public void onMissingData(boolean missingAccount, boolean missingMailbox) {
        // TODO what's the proper handling if the mailbox list is '0'? display toast?
        finish();
    }

    /**
     * This function creates a shortcut and returns it to the caller.  There are actually two
     * intents that you will send back.
     *
     * The first intent serves as a container for the shortcut and is returned to the launcher by
     * setResult().  This intent must contain three fields:
     *
     * <ul>
     * <li>{@link android.content.Intent#EXTRA_SHORTCUT_INTENT} The shortcut intent.</li>
     * <li>{@link android.content.Intent#EXTRA_SHORTCUT_NAME} The text that will be displayed with
     * the shortcut.</li>
     * <li>{@link android.content.Intent#EXTRA_SHORTCUT_ICON} The shortcut's icon, if provided as a
     * bitmap, <i>or</i> {@link android.content.Intent#EXTRA_SHORTCUT_ICON_RESOURCE} if provided as
     * a drawable resource.</li>
     * </ul>
     *
     * If you use a simple drawable resource, note that you must wrapper it using
     * {@link android.content.Intent.ShortcutIconResource}, as shown below.  This is required so
     * that the launcher can access resources that are stored in your application's .apk file.  If
     * you return a bitmap, such as a thumbnail, you can simply put the bitmap into the extras
     * bundle using {@link android.content.Intent#EXTRA_SHORTCUT_ICON}.
     *
     * The shortcut intent can be any intent that you wish the launcher to send, when the user
     * clicks on the shortcut.  Typically this will be {@link android.content.Intent#ACTION_VIEW}
     * with an appropriate Uri for your content, but any Intent will work here as long as it
     * triggers the desired action within your Activity.
     */
    private void setupShortcut(Account account, long mailboxId, String shortcutName) {
        Activity myActivity = this;
        // First, set up the shortcut intent.
        final Intent shortcutIntent;
        if (TEST_CREATE_OLD_STYLE_SHORTCUT) {
            shortcutIntent = MessageList.createFroyoIntent(myActivity, account);
            Log.d(Logging.LOG_TAG, "Created old style intent: " + shortcutIntent);
        } else {
            // TODO if we add meta-mailboxes/accounts to the database, remove this special case
            if (account.mId == Account.ACCOUNT_ID_COMBINED_VIEW) {
                shortcutIntent = Welcome.createOpenMessageIntent(
                        myActivity, account.mId, mailboxId, Message.NO_MESSAGE);
            } else {
                String uuid = account.mCompatibilityUuid;
                shortcutIntent = Welcome.createAccountShortcutIntent(myActivity, uuid, mailboxId);
            }
        }

        // Then, set up the container intent (the response to the caller)
        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, shortcutName);
        Parcelable iconResource
                = Intent.ShortcutIconResource.fromContext(myActivity, R.mipmap.ic_launcher_email);
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconResource);

        // Now, return the result to the launcher
        myActivity.setResult(Activity.RESULT_OK, intent);
    }
}
