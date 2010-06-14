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
import com.android.email.activity.setup.AccountSettings;
import com.android.email.mail.AuthenticationFailedException;
import com.android.email.mail.CertificateValidationException;
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
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Animation.AnimationListener;
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
    private FindMailboxTask mFindMailboxTask;
    private SetTitleTask mSetTitleTask;

    private static final int MAILBOX_NAME_COLUMN_ID = 0;
    private static final int MAILBOX_NAME_COLUMN_ACCOUNT_KEY = 1;
    private static final int MAILBOX_NAME_COLUMN_TYPE = 2;
    private static final String[] MAILBOX_NAME_PROJECTION = new String[] {
            MailboxColumns.DISPLAY_NAME, MailboxColumns.ACCOUNT_KEY,
            MailboxColumns.TYPE};

    private static final int ACCOUNT_DISPLAY_NAME_COLUMN_ID = 0;
    private static final String[] ACCOUNT_NAME_PROJECTION = new String[] {
            AccountColumns.DISPLAY_NAME };

    private static final int ACCOUNT_INFO_COLUMN_FLAGS = 0;
    private static final String[] ACCOUNT_INFO_PROJECTION = new String[] {
            AccountColumns.FLAGS };

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
        mListFragment = (MessageListFragment) findFragmentById(R.id.list);
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
            mListFragment.openMailbox(-1, mailboxId);
        } else {
            int mailboxType = intent.getIntExtra(EXTRA_MAILBOX_TYPE, Mailbox.TYPE_INBOX);
            Uri uri = intent.getData();
            // TODO Possible ANR.  getAccountIdFromShortcutSafeUri accesses DB.
            long accountId = (uri == null) ? -1
                    : Account.getAccountIdFromShortcutSafeUri(this, uri);

            // TODO Both branches have almost identical code.  Unify them.
            // (And why not "okay to recurse" in the first case?)
            if (accountId != -1) {
                // A content URI was provided - try to look up the account
                mFindMailboxTask = new FindMailboxTask(accountId, mailboxType, false);
                mFindMailboxTask.execute();
            } else {
                // Go by account id + type
                accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1);
                mFindMailboxTask = new FindMailboxTask(accountId, mailboxType, true);
                mFindMailboxTask.execute();
            }
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
        NotificationManager notificationManager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(MailService.NOTIFICATION_ID_NEW_MESSAGES);

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

        Utility.cancelTaskInterrupt(mFindMailboxTask);
        mFindMailboxTask = null;
        Utility.cancelTaskInterrupt(mSetTitleTask);
        mSetTitleTask = null;
    }

    /**
     * Called when the list fragment can't find mailbox/account.
     */
    public void onMailboxNotFound() {
        finish();
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
        mListFragment.updateListPosition();
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

    // TODO Move these two method to the fragment.
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        mListFragment.createContextMenu(menu, v, menuInfo);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        return mListFragment.onContextItemSelected(item);
    }

    private void onFolders() {
        long mailboxId = mListFragment.getMailboxId();
        if (mailboxId >= 0) {
            // TODO smaller projection
            Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mailboxId);
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
        // TOOD it actually doesn't work for -1.
        AccountSettings.actionSettings(this, mListFragment.getAccountId());
    }

    // TODO move this to controller.  It doesn't really belong here.
    public void onSendPendingMessages() {
        long mailboxId = mListFragment.getMailboxId();
        if (mailboxId == Mailbox.QUERY_ALL_OUTBOX) {
            // For the combined Outbox, we loop through all accounts and send the messages
            Cursor c = mResolver.query(Account.CONTENT_URI, Account.ID_PROJECTION,
                    null, null, null);
            try {
                while (c.moveToNext()) {
                    long accountId = c.getLong(Account.ID_PROJECTION_COLUMN);
                    mController.sendPendingMessages(accountId);
                }
            } finally {
                c.close();
            }
        } else {
            mController.sendPendingMessages(mListFragment.getAccountId());
        }
    }

    /**
     * Show multi-selection panel, if one or more messages are selected.   Button labels will be
     * updated too.
     */
    public void onSelectionChanged() {
        showMultiPanel(mListFragment.getSelectedCount() > 0);
    }

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
     * Async task for finding a single mailbox by type (possibly even going to the network).
     *
     * This is much too complex, as implemented.  It uses this AsyncTask to check for a mailbox,
     * then (if not found) a Controller call to refresh mailboxes from the server, and a handler
     * to relaunch this task (a 2nd time) to read the results of the network refresh.  The core
     * problem is that we have two different non-UI-thread jobs (reading DB and reading network)
     * and two different paradigms for dealing with them.  Some unification would be needed here
     * to make this cleaner.
     *
     * TODO: If this problem spreads to other operations, find a cleaner way to handle it.
     */
    private class FindMailboxTask extends AsyncTask<Void, Void, Long> {

        private final long mAccountId;
        private final int mMailboxType;
        private final boolean mOkToRecurse;

        private static final int ACTION_DEFAULT = 0;
        private static final int SHOW_WELCOME_ACTIVITY = 1;
        private static final int SHOW_SECURITY_ACTIVITY = 2;
        private static final int START_NETWORK_LOOK_UP = 3;
        private int mAction = ACTION_DEFAULT;

        /**
         * Special constructor to cache some local info
         */
        public FindMailboxTask(long accountId, int mailboxType, boolean okToRecurse) {
            mAccountId = accountId;
            mMailboxType = mailboxType;
            mOkToRecurse = okToRecurse;
        }

        @Override
        protected Long doInBackground(Void... params) {
            // Quick check that account is not in security hold
            if (mAccountId != -1 && isSecurityHold(mAccountId)) {
                mAction = SHOW_SECURITY_ACTIVITY;
                return Mailbox.NO_MAILBOX;
            }
            // See if we can find the requested mailbox in the DB.
            long mailboxId = Mailbox.findMailboxOfType(MessageList.this, mAccountId, mMailboxType);
            if (mailboxId == Mailbox.NO_MAILBOX) {
                // Mailbox not found.  Does the account really exists?
                final boolean accountExists = Account.isValidId(MessageList.this, mAccountId);
                if (accountExists && mOkToRecurse) {
                    // launch network lookup
                    mAction = START_NETWORK_LOOK_UP;
                } else {
                    // We don't want to do the network lookup, or the account doesn't exist in the
                    // first place.
                    mAction = SHOW_WELCOME_ACTIVITY;
                }
            }
            return mailboxId;
        }

        @Override
        protected void onPostExecute(Long mailboxId) {
            switch (mAction) {
                case SHOW_SECURITY_ACTIVITY:
                    // launch the security setup activity
                    Intent i = AccountSecurity.actionUpdateSecurityIntent(
                            MessageList.this, mAccountId);
                    MessageList.this.startActivityForResult(i, REQUEST_SECURITY);
                    return;
                case SHOW_WELCOME_ACTIVITY:
                    // Let the Welcome activity show the default screen.
                    Welcome.actionStart(MessageList.this);
                    finish();
                    return;
                case START_NETWORK_LOOK_UP:
                    mControllerCallback.getWrappee().presetMailboxListCallback(
                            mMailboxType, mAccountId);

                    // TODO updateMailboxList accessed DB, so we shouldn't call on the UI thread,
                    // but we should fix the Controller side.  (Other Controller methods too access
                    // DB but are called on the UI thread.)
                    mController.updateMailboxList(mAccountId);
                    return;
                default:
                    // At this point, mailboxId != NO_MAILBOX
                    mSetTitleTask = new SetTitleTask(mailboxId);
                    mSetTitleTask.execute();
                    mListFragment.openMailbox(mAccountId, mailboxId);
                    return;
            }
        }
    }

    /**
     * Check a single account for security hold status.  Do not call from UI thread.
     */
    private boolean isSecurityHold(long accountId) {
        Cursor c = MessageList.this.getContentResolver().query(
                ContentUris.withAppendedId(Account.CONTENT_URI, accountId),
                ACCOUNT_INFO_PROJECTION, null, null, null);
        try {
            if (c.moveToFirst()) {
                int flags = c.getInt(ACCOUNT_INFO_COLUMN_FLAGS);
                if ((flags & Account.FLAGS_SECURITY_HOLD) != 0) {
                    return true;
                }
            }
        } finally {
            c.close();
        }
        return false;
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
        mListFragment.showProgressIcon(show);
    }

    private void lookupMailboxType(long accountId, int mailboxType) {
        // kill running async task, if any
        Utility.cancelTaskInterrupt(mFindMailboxTask);
        // start new one.  do not recurse back to controller.
        mFindMailboxTask = new FindMailboxTask(accountId, mailboxType, false);
        mFindMailboxTask.execute();
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
        MessagingException mSendMessageException;

        // These values are set by FindMailboxTask.
        private int mWaitForMailboxType = -1;
        private long mWaitForMailboxAccount = -1;

        public void presetMailboxListCallback(int mailboxType, long accountId) {
            mWaitForMailboxType = mailboxType;
            mWaitForMailboxAccount = accountId;
        }

        @Override
        public void updateMailboxListCallback(MessagingException result,
                long accountKey, int progress) {
            // updateMailboxList is never the end goal in MessageList, so we don't show
            // these errors.  There are a couple of corner cases that we miss reporting, but
            // this is better than reporting a number of non-problem intermediate states.
            // updateBanner(result, progress, mMailboxId);

            updateProgress(result, progress);
            if (progress == 100 && accountKey == mWaitForMailboxAccount) {
                mWaitForMailboxAccount = -1;
                lookupMailboxType(accountKey, mWaitForMailboxType);
            }
        }

        // TODO check accountKey and only react to relevant notifications
        @Override
        public void updateMailboxCallback(MessagingException result, long accountKey,
                long mailboxKey, int progress, int numNewMessages) {
            updateBanner(result, progress, mailboxKey);
            if (result != null || progress == 100) {
                Email.updateMailboxRefreshTime(mailboxKey);
            }
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
                int id = R.string.status_network_error;
                if (result instanceof AuthenticationFailedException) {
                    id = R.string.account_setup_failed_dlg_auth_message;
                } else if (result instanceof CertificateValidationException) {
                    id = R.string.account_setup_failed_dlg_certificate_message;
                } else {
                    switch (result.getExceptionType()) {
                        case MessagingException.IOERROR:
                            id = R.string.account_setup_failed_ioerror;
                            break;
                        case MessagingException.TLS_REQUIRED:
                            id = R.string.account_setup_failed_tls_required;
                            break;
                        case MessagingException.AUTH_REQUIRED:
                            id = R.string.account_setup_failed_auth_required;
                            break;
                        case MessagingException.GENERAL_SECURITY:
                            id = R.string.account_setup_failed_security;
                            break;
                        // TODO Generate a unique string for this case, which is the case
                        // where the security policy needs to be updated.
                        case MessagingException.SECURITY_POLICIES_REQUIRED:
                            id = R.string.account_setup_failed_security;
                            break;
                    }
                }
                showErrorBanner(getString(id));
            } else if (progress > 0) {
                showErrorBanner(null);
            }
        }
    }
}
