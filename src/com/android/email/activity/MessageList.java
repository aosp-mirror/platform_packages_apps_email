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
import com.android.email.ControllerResultUiThreadWrapper;
import com.android.email.Email;
import com.android.email.MessagingExceptionStrings;
import com.android.email.R;
import com.android.email.activity.setup.AccountSecurity;
import com.android.email.activity.setup.AccountSettingsXL;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Mailbox;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
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

    private final Controller mController = Controller.getInstance(getApplication());
    private ControllerResultUiThreadWrapper<ControllerResults> mControllerCallback;

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
     * @param id mailbox key
     */
    public static void actionHandleMailbox(Context context, long id) {
        context.startActivity(createIntent(context, -1, id, -1));
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
     * Open the inbox of the account with a UUID.  It's used to handle old style
     * (Android <= 1.6) desktop shortcut intents.
     */
    public static void actionOpenAccountInboxUuid(Context context, String accountUuid) {
        Intent i = createIntent(context, -1, -1, Mailbox.TYPE_INBOX);
        i.setData(Account.getShortcutSafeUriFromUuid(accountUuid));
        context.startActivity(i);
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

    /**
     * Create and return an intent for a desktop shortcut for an account.
     *
     * @param context Calling context for building the intent
     * @param account The account of interest
     * @param mailboxType The folder name to open (typically Mailbox.TYPE_INBOX)
     * @return an Intent which can be used to view that account
     */
    public static Intent createAccountIntentForShortcut(Context context, Account account,
            int mailboxType) {
        Intent i = createIntent(context, -1, -1, mailboxType);
        i.setData(account.getShortcutSafeUri());
        return i;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.message_list);

        mControllerCallback = new ControllerResultUiThreadWrapper<ControllerResults>(
                new Handler(), new ControllerResults());
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
    public void onPause() {
        super.onPause();
        mController.removeResultCallback(mControllerCallback);
    }

    @Override
    public void onResume() {
        super.onResume();
        mController.addResultCallback(mControllerCallback);

        // Exit immediately if the accounts list has changed (e.g. externally deleted)
        if (Email.getNotifyUiAccountsChanged()) {
            Welcome.actionStart(this);
            finish();
            return;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mMailboxFinder != null) {
            mMailboxFinder.cancel();
            mMailboxFinder = null;
        }
    }


    private void launchWelcomeAndFinish() {
        Welcome.actionStart(this);
        finish();
    }

    /**
     * Called when the list fragment can't find mailbox/account.
     */
    public void onMailboxNotFound() {
        finish();
    }

    @Override
    public void onMessageOpen(long messageId, long messageMailboxId, long listMailboxId, int type) {
        if (type == MessageListFragment.Callback.TYPE_DRAFT) {
            MessageCompose.actionEditDraft(this, messageId);
        } else {
            // WARNING: here we pass "listMailboxId", which can be the negative id of
            // a compound mailbox, instead of the mailboxId of the particular message that
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
        // TODO Disable "refresh" for combined mailboxes
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
        if (!mListFragment.isMagicMailbox()) { // Magic boxes don't have "folders" option.
            // TODO smaller projection
            Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mListFragment.getMailboxId());
            if (mailbox != null) {
                MailboxList.actionHandleAccount(this, mailbox.mAccountKey);
                finish();
            }
        }
    }

    private void onAccounts() {
        AccountFolderList.actionShowAccounts(this);
        finish();
    }

    private void onCompose() {
        MessageCompose.actionCompose(this, mListFragment.getAccountId());
    }

    private void onEditAccount() {
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
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void showProgressIcon(boolean show) {
        // TODO Show "refreshing" icon somewhere. (It's on the action bar on xlarge.)
    }

    private void showErrorBanner(String message) {
        boolean isVisible = mErrorBanner.getVisibility() == View.VISIBLE;
        if (message != null) {
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

    /**
     * TODO This should probably be removed -- use RefreshManager instead to update the progress
     * icon and the error banner.
     *
     * Controller results listener.  We wrap it with {@link ControllerResultUiThreadWrapper},
     * so all methods are called on the UI thread.
     */
    private class ControllerResults extends Controller.Result {

        // This is used to alter the connection banner operation for sending messages
        private MessagingException mSendMessageException;

        // TODO check accountKey and only react to relevant notifications
        @Override
        public void updateMailboxCallback(MessagingException result, long accountKey,
                long mailboxKey, int progress, int numNewMessages) {
            updateBanner(result, progress, mailboxKey);
            updateProgress(result, progress);
        }

        /**
         * We alter the updateBanner hysteresis here to capture any failures and handle
         * them just once at the end.  This callback is overly overloaded:
         *  result == null, messageId == -1, progress == 0:     start batch send
         *  result == null, messageId == xx, progress == 0:     start sending one message
         *  result == xxxx, messageId == xx, progress == 0;     failed sending one message
         *  result == null, messageId == -1, progres == 100;    finish sending batch
         */
        @Override
        public void sendMailCallback(MessagingException result, long accountId, long messageId,
                int progress) {
            if (mListFragment.isOutbox()) {
                // reset captured error when we start sending one or more messages
                if (messageId == -1 && result == null && progress == 0) {
                    mSendMessageException = null;
                }
                // capture first exception that comes along
                if (result != null && mSendMessageException == null) {
                    mSendMessageException = result;
                }
                // if we're completing the sequence, change the banner state
                if (messageId == -1 && progress == 100) {
                    updateBanner(mSendMessageException, progress, mListFragment.getMailboxId());
                }
                // always update the spinner, which has less state to worry about
                updateProgress(result, progress);
            }
        }

        private void updateProgress(MessagingException result, int progress) {
            showProgressIcon(result == null && progress < 100);
        }

        /**
         * Show or hide the connection error banner, and convert the various MessagingException
         * variants into localizable text.  There is hysteresis in the show/hide logic:  Once shown,
         * the banner will remain visible until some progress is made on the connection.  The
         * goal is to keep it from flickering during retries in a bad connection state.
         *
         * @param result
         * @param progress
         */
        private void updateBanner(MessagingException result, int progress, long mailboxKey) {
            if (mailboxKey != mListFragment.getMailboxId()) {
                return;
            }
            if (result != null) {
                showErrorBanner(
                        MessagingExceptionStrings.getErrorString(MessageList.this, result));
            } else if (progress > 0) {
                showErrorBanner(null);
            }
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
            MessageList.this.startActivityForResult(i, REQUEST_SECURITY);
        }
    }
}
