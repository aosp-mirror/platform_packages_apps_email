/*
 * Copyright (C) 2009 The Android Open Source Project
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

import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.email.activity.setup.AccountSecurity;
import com.android.email.activity.setup.AccountSettingsXL;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Mailbox;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

public class MessageList extends Activity implements MessageListFragment.Callback {
    // Intent extras (internal to this activity)
    private static final String EXTRA_ACCOUNT_ID = "com.android.email.activity._ACCOUNT_ID";
    private static final String EXTRA_MAILBOX_TYPE = "com.android.email.activity.MAILBOX_TYPE";
    private static final String EXTRA_MAILBOX_ID = "com.android.email.activity.MAILBOX_ID";

    private static final int REQUEST_SECURITY = 0;

    // UI support
    private MessageListFragment mListFragment;
    private TextView mErrorBanner;

    private RefreshManager mRefreshManager;
    private final RefreshListener mRefreshListener = new RefreshListener();

    private MailboxFinder mMailboxFinder;
    private final MailboxFinderCallback mMailboxFinderCallback = new MailboxFinderCallback();

    /* package */ MessageListFragment getListFragmentForTest() {
        return mListFragment;
    }

    /**
     * Open a specific mailbox.
     *
     * TODO This should just shortcut to a more generic version that can accept a list of
     * accounts/mailboxes (e.g. merged inboxes).
     *
     * @param context
     * @param mailboxType mailbox key
     */
    public static void actionHandleMailbox(Context context, long mailboxType) {
        context.startActivity(createIntent(context, -1, mailboxType, -1));
    }

    /**
     * Open a specific mailbox by account & type
     *
     * @param context The caller's context (for generating an intent)
     * @param accountId The account to open
     * @param mailboxType the type of mailbox to open (e.g. @see EmailContent.Mailbox.TYPE_INBOX)
     */
    public static void actionHandleAccount(Context context, long accountId, int mailboxType) {
        context.startActivity(createIntent(context, accountId, -1, mailboxType));
    }

    /**
     * Return an intent to open a specific mailbox by account & type.
     *
     * @param context The caller's context (for generating an intent)
     * @param accountId The account to open, or -1
     * @param mailboxId the ID of the mailbox to open, or -1
     * @param mailboxType the type of mailbox to open (e.g. @see Mailbox.TYPE_INBOX) or -1
     */
    public static Intent createIntent(Context context, long accountId, long mailboxId,
            int mailboxType) {
        Intent intent = new Intent(context, MessageList.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (accountId != -1) intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        if (mailboxId != -1) intent.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        if (mailboxType != -1) intent.putExtra(EXTRA_MAILBOX_TYPE, mailboxType);
        return intent;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.message_list);

        mRefreshManager = RefreshManager.getInstance(this);
        mRefreshManager.registerListener(mRefreshListener);
        mListFragment = (MessageListFragment) getFragmentManager()
                .findFragmentById(R.id.message_list_fragment);
        mErrorBanner = (TextView) findViewById(R.id.connection_error_text);

        mListFragment.setCallback(this);

        // Show the appropriate account/mailbox specified by an {@link Intent}.
        selectAccountAndMailbox(getIntent());
    }

    /**
     * Show the appropriate account/mailbox specified by an {@link Intent}.
     */
    private void selectAccountAndMailbox(Intent intent) {
        long mailboxId = intent.getLongExtra(EXTRA_MAILBOX_ID, -1);
        if (mailboxId != -1) {
            mListFragment.openMailbox(mailboxId);
        } else {
            int mailboxType = intent.getIntExtra(EXTRA_MAILBOX_TYPE, Mailbox.TYPE_INBOX);
            Uri uri = intent.getData();
            // TODO Possible ANR.  getAccountIdFromShortcutSafeUri accesses DB.
            long accountId = (uri == null) ? -1
                    : Account.getAccountIdFromShortcutSafeUri(this, uri);
            if (accountId == -1) {
                accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1);
            }
            if (accountId == -1) {
                launchWelcomeAndFinish();
                return;
            }
            mMailboxFinder = new MailboxFinder(this, accountId, mailboxType,
                    mMailboxFinderCallback);
            mMailboxFinder.startLookup();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Exit immediately if the accounts list has changed (e.g. externally deleted)
        if (Email.getNotifyUiAccountsChanged()) {
            Welcome.actionStart(this);
            finish();
            return;
        }
    }

    @Override
    protected void onDestroy() {
        if (mMailboxFinder != null) {
            mMailboxFinder.cancel();
            mMailboxFinder = null;
        }
        mRefreshManager.unregisterListener(mRefreshListener);
        super.onDestroy();
    }


    private void launchWelcomeAndFinish() {
        Welcome.actionStart(this);
        finish();
    }

    @Override
    public void onListLoaded() {
        // Now we know if the mailbox is refreshable
        updateProgressIcon();
    }

    /**
     * Called when the list fragment can't find mailbox/account.
     */
    @Override
    public void onMailboxNotFound() {
        finish();
    }

    @Override
    public void onMessageOpen(long messageId, long messageMailboxId, long listMailboxId, int type) {
        if (type == MessageListFragment.Callback.TYPE_DRAFT) {
            MessageCompose.actionEditDraft(this, messageId);
        } else {
            // WARNING: here we pass "listMailboxId", which can be the negative id of
            // a combined mailbox, instead of the mailboxId of the particular message that
            // is opened.  This is to support the next/prev buttons on the message view
            // properly even for combined mailboxes.
            MessageView.actionView(this, messageId, listMailboxId);
        }
    }

    @Override
    public void onEnterSelectionMode(boolean enter) {
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.message_list_option, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // this method can be called in the very early stage of the activity lifecycle, where
        // mListFragment isn't ready yet.
        boolean show = (mListFragment != null) && mListFragment.isRefreshable();
        boolean animate = (mListFragment != null) && mRefreshManager.isMessageListRefreshing(
                mListFragment.getMailboxId());
        ActivityHelper.updateRefreshMenuIcon(menu.findItem(R.id.refresh), show, animate);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                mListFragment.onRefresh(true);
                return true;
            case R.id.folders:
                onFolders();
                return true;
            case R.id.accounts:
                onAccounts();
                return true;
            case R.id.compose:
                onCompose();
                return true;
            case R.id.account_settings:
                onEditAccount();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void onFolders() {
        long accountId = mListFragment.getAccountId();
        // accountId will be -1 when a) mailbox is still loading, or for magic mailboxes.
        if (accountId != -1) {
            MailboxList.actionHandleAccount(this, accountId);
            finish();
        }
    }

    private void onAccounts() {
        AccountFolderList.actionShowAccounts(this);
        finish();
    }

    private void onCompose() {
        // Passing account = -1 is okay -- the default account will be used.
        MessageCompose.actionCompose(this, mListFragment.getAccountId());
    }

    private void onEditAccount() {
        // Passing account = -1 is okay
        AccountSettingsXL.actionSettings(this, mListFragment.getAccountId());
    }

    /**
     * Handle the eventual result from the security update activity
     *
     * Note, this is extremely coarse, and it simply returns the user to the Accounts list.
     * Anything more requires refactoring of this Activity.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_SECURITY:
                onAccounts();
        }
    }

    private void updateProgressIcon() {
        invalidateOptionsMenu(); // animate/stop refreshing icon
    }

    private void showErrorBanner(String message) {
        boolean isVisible = mErrorBanner.getVisibility() == View.VISIBLE;
        if (!TextUtils.isEmpty(message)) {
            mErrorBanner.setText(message);
            if (!isVisible) {
                mErrorBanner.setVisibility(View.VISIBLE);
                mErrorBanner.startAnimation(
                        AnimationUtils.loadAnimation(
                                MessageList.this, R.anim.header_appear));
            }
        } else {
            if (isVisible) {
                mErrorBanner.setVisibility(View.GONE);
                mErrorBanner.startAnimation(
                        AnimationUtils.loadAnimation(
                                MessageList.this, R.anim.header_disappear));
            }
        }
    }

    private class RefreshListener implements RefreshManager.Listener {
        @Override
        public void onMessagingError(long accountId, long mailboxId, String message) {
            updateProgressIcon();
            showErrorBanner(message);
        }

        @Override
        public void onRefreshStatusChanged(long accountId, long mailboxId) {
            updateProgressIcon();
            showErrorBanner(null);
        }
    }

    private class MailboxFinderCallback implements MailboxFinder.Callback {
        @Override
        public void onMailboxFound(long accountId, long mailboxId) {
            mListFragment.openMailbox(mailboxId);
        }

        @Override
        public void onAccountNotFound() {
            // Let the Welcome activity show the default screen.
            launchWelcomeAndFinish();
        }

        @Override
        public void onMailboxNotFound(long accountId) {
            // Let the Welcome activity show the default screen.
            launchWelcomeAndFinish();
        }

        @Override
        public void onAccountSecurityHold(long accountId) {
            // launch the security setup activity
            Intent i = AccountSecurity.actionUpdateSecurityIntent(
                    MessageList.this, accountId, true);
            startActivityForResult(i, REQUEST_SECURITY);
        }
    }
}
