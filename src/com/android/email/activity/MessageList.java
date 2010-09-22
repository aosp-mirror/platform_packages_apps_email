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
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.activity.setup.AccountSecurity;
import com.android.email.activity.setup.AccountSettingsXL;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.service.MailService;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

public class MessageList extends Activity implements OnClickListener,
        AnimationListener, MessageListFragment.Callback {
    // Intent extras (internal to this activity)
    private static final String EXTRA_ACCOUNT_ID = "com.android.email.activity._ACCOUNT_ID";
    private static final String EXTRA_MAILBOX_TYPE = "com.android.email.activity.MAILBOX_TYPE";
    private static final String EXTRA_MAILBOX_ID = "com.android.email.activity.MAILBOX_ID";

    private static final int REQUEST_SECURITY = 0;

    // UI support
    private MessageListFragment mListFragment;
    private View mMultiSelectPanel;
    private Button mReadUnreadButton;
    private Button mFavoriteButton;
    private Button mDeleteButton;
    private TextView mErrorBanner;

    private final Controller mController = Controller.getInstance(getApplication());
    private ControllerResultUiThreadWrapper<ControllerResults> mControllerCallback;

    private TextView mLeftTitle;
    private ProgressBar mProgressIcon;

    // DB access
    private ContentResolver mResolver;
    private SetTitleTask mSetTitleTask;

    private MailboxFinder mMailboxFinder;
    private MailboxFinderCallback mMailboxFinderCallback = new MailboxFinderCallback();

    private static final int MAILBOX_NAME_COLUMN_ID = 0;
    private static final int MAILBOX_NAME_COLUMN_ACCOUNT_KEY = 1;
    private static final int MAILBOX_NAME_COLUMN_TYPE = 2;
    private static final String[] MAILBOX_NAME_PROJECTION = new String[] {
            MailboxColumns.DISPLAY_NAME, MailboxColumns.ACCOUNT_KEY,
            MailboxColumns.TYPE};

    private static final int ACCOUNT_DISPLAY_NAME_COLUMN_ID = 0;
    private static final String[] ACCOUNT_NAME_PROJECTION = new String[] {
            AccountColumns.DISPLAY_NAME };

    private static final String ID_SELECTION = EmailContent.RECORD_ID + "=?";

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
        setContentView(R.layout.message_list);

        mControllerCallback = new ControllerResultUiThreadWrapper<ControllerResults>(
                new Handler(), new ControllerResults());
        mListFragment = (MessageListFragment) findFragmentById(R.id.message_list_fragment);
        mMultiSelectPanel = findViewById(R.id.footer_organize);
        mReadUnreadButton = (Button) findViewById(R.id.btn_read_unread);
        mFavoriteButton = (Button) findViewById(R.id.btn_multi_favorite);
        mDeleteButton = (Button) findViewById(R.id.btn_multi_delete);
        mLeftTitle = (TextView) findViewById(R.id.title_left_text);
        mProgressIcon = (ProgressBar) findViewById(R.id.title_progress_icon);
        mErrorBanner = (TextView) findViewById(R.id.connection_error_text);

        mReadUnreadButton.setOnClickListener(this);
        mFavoriteButton.setOnClickListener(this);
        mDeleteButton.setOnClickListener(this);
        ((Button) findViewById(R.id.account_title_button)).setOnClickListener(this);

        mListFragment.setCallback(this);

        mResolver = getContentResolver();

        // Show the appropriate account/mailbox specified by an {@link Intent}.
        selectAccountAndMailbox(getIntent());
    }

    /**
     * Show the appropriate account/mailbox specified by an {@link Intent}.
     */
    private void selectAccountAndMailbox(Intent intent) {
        long mailboxId = intent.getLongExtra(EXTRA_MAILBOX_ID, -1);
        if (mailboxId != -1) {
            // Specific mailbox ID was provided - go directly to it
            mSetTitleTask = new SetTitleTask(mailboxId);
            mSetTitleTask.execute();
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
        // TODO set title to "account > mailbox (#unread)"
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

        // clear notifications here
        MailService.cancelNewMessageNotification(this);

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
        Utility.cancelTaskInterrupt(mSetTitleTask);
        mSetTitleTask = null;
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

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_read_unread:
                mListFragment.onMultiToggleRead();
                break;
            case R.id.btn_multi_favorite:
                mListFragment.onMultiToggleFavorite();
                break;
            case R.id.btn_multi_delete:
                mListFragment.onMultiDelete();
                break;
            case R.id.account_title_button:
                onAccounts();
                break;
        }
    }

    public void onAnimationEnd(Animation animation) {
        // TODO: If the button panel hides the only selected item, scroll the list to make it
        // visible again.
    }

    public void onAnimationRepeat(Animation animation) {
    }

    public void onAnimationStart(Animation animation) {
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // Re-create menu every time.  (We may not know the mailbox id yet)
        menu.clear();
        if (mListFragment.isMagicMailbox()) {
            getMenuInflater().inflate(R.menu.message_list_option_smart_folder, menu);
        } else {
            getMenuInflater().inflate(R.menu.message_list_option, menu);
        }
        boolean showDeselect = mListFragment.getSelectedCount() > 0;
        menu.setGroupVisible(R.id.deselect_all_group, showDeselect);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                mListFragment.onRefresh();
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
            case R.id.deselect_all:
                mListFragment.onDeselectAll();
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
     * Show multi-selection panel, if one or more messages are selected.   Button labels will be
     * updated too.
     *
     * @deprecated not used any longer.  remove them.
     */
    public void onSelectionChanged() {
        showMultiPanel(mListFragment.getSelectedCount() > 0);
    }

    /**
     * @deprecated not used any longer.  remove them.  (with associated resources, strings,
     * members, etc)
     */
    private void updateFooterButtonNames () {
        // Show "unread_action" when one or more read messages are selected.
        if (mListFragment.doesSelectionContainReadMessage()) {
            mReadUnreadButton.setText(R.string.unread_action);
        } else {
            mReadUnreadButton.setText(R.string.read_action);
        }
        // Show "set_star_action" when one or more un-starred messages are selected.
        if (mListFragment.doesSelectionContainNonStarredMessage()) {
            mFavoriteButton.setText(R.string.set_star_action);
        } else {
            mFavoriteButton.setText(R.string.remove_star_action);
        }
    }

    /**
     * Show or hide the panel of multi-select options
     *
     * @deprecated not used any longer.  remove them.
     */
    private void showMultiPanel(boolean show) {
        if (show && mMultiSelectPanel.getVisibility() != View.VISIBLE) {
            mMultiSelectPanel.setVisibility(View.VISIBLE);
            Animation animation = AnimationUtils.loadAnimation(this, R.anim.footer_appear);
            animation.setAnimationListener(this);
            mMultiSelectPanel.startAnimation(animation);
        } else if (!show && mMultiSelectPanel.getVisibility() != View.GONE) {
            mMultiSelectPanel.setVisibility(View.GONE);
            mMultiSelectPanel.startAnimation(
                        AnimationUtils.loadAnimation(this, R.anim.footer_disappear));
        }
        if (show) {
            updateFooterButtonNames();
        }
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

    private class SetTitleTask extends AsyncTask<Void, Void, Object[]> {

        private long mMailboxKey;

        public SetTitleTask(long mailboxKey) {
            mMailboxKey = mailboxKey;
        }

        @Override
        protected Object[] doInBackground(Void... params) {
            // Check special Mailboxes
            int resIdSpecialMailbox = 0;
            if (mMailboxKey == Mailbox.QUERY_ALL_INBOXES) {
                resIdSpecialMailbox = R.string.account_folder_list_summary_inbox;
            } else if (mMailboxKey == Mailbox.QUERY_ALL_FAVORITES) {
                resIdSpecialMailbox = R.string.account_folder_list_summary_starred;
            } else if (mMailboxKey == Mailbox.QUERY_ALL_DRAFTS) {
                resIdSpecialMailbox = R.string.account_folder_list_summary_drafts;
            } else if (mMailboxKey == Mailbox.QUERY_ALL_OUTBOX) {
                resIdSpecialMailbox = R.string.account_folder_list_summary_outbox;
            }
            if (resIdSpecialMailbox != 0) {
                return new Object[] {null, getString(resIdSpecialMailbox), 0};
            }

            String accountName = null;
            String mailboxName = null;
            String accountKey = null;
            Cursor c = MessageList.this.mResolver.query(Mailbox.CONTENT_URI,
                    MAILBOX_NAME_PROJECTION, ID_SELECTION,
                    new String[] { Long.toString(mMailboxKey) }, null);
            try {
                if (c.moveToFirst()) {
                    mailboxName = Utility.FolderProperties.getInstance(MessageList.this)
                            .getDisplayName(c.getInt(MAILBOX_NAME_COLUMN_TYPE));
                    if (mailboxName == null) {
                        mailboxName = c.getString(MAILBOX_NAME_COLUMN_ID);
                    }
                    accountKey = c.getString(MAILBOX_NAME_COLUMN_ACCOUNT_KEY);
                }
            } finally {
                c.close();
            }
            if (accountKey != null) {
                c = MessageList.this.mResolver.query(Account.CONTENT_URI,
                        ACCOUNT_NAME_PROJECTION, ID_SELECTION, new String[] { accountKey },
                        null);
                try {
                    if (c.moveToFirst()) {
                        accountName = c.getString(ACCOUNT_DISPLAY_NAME_COLUMN_ID);
                    }
                } finally {
                    c.close();
                }
            }
            int nAccounts = EmailContent.count(MessageList.this, Account.CONTENT_URI, null, null);
            return new Object[] {accountName, mailboxName, nAccounts};
        }

        @Override
        protected void onPostExecute(Object[] result) {
            if (result == null) {
                return;
            }

            final int nAccounts = (Integer) result[2];
            if (result[0] != null) {
                setTitleAccountName((String) result[0], nAccounts > 1);
            }

            if (result[1] != null) {
                mLeftTitle.setText((String) result[1]);
            }
        }
    }

    private void setTitleAccountName(String accountName, boolean showAccountsButton) {
        TextView accountsButton = (TextView) findViewById(R.id.account_title_button);
        TextView textPlain = (TextView) findViewById(R.id.title_right_text);
        if (showAccountsButton) {
            accountsButton.setVisibility(View.VISIBLE);
            textPlain.setVisibility(View.GONE);
            accountsButton.setText(accountName);
        } else {
            accountsButton.setVisibility(View.GONE);
            textPlain.setVisibility(View.VISIBLE);
            textPlain.setText(accountName);
        }
    }

    private void showProgressIcon(boolean show) {
        int visibility = show ? View.VISIBLE : View.GONE;
        mProgressIcon.setVisibility(visibility);
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
                showErrorBanner(result.getUiErrorMessage(MessageList.this));
            } else if (progress > 0) {
                showErrorBanner(null);
            }
        }
    }

    private class MailboxFinderCallback implements MailboxFinder.Callback {
        @Override
        public void onMailboxFound(long accountId, long mailboxId) {
            mSetTitleTask = new SetTitleTask(mailboxId);
            mSetTitleTask.execute();
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
                    MessageList.this, accountId);
            MessageList.this.startActivityForResult(i, REQUEST_SECURITY);
        }
    }
}
