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

import com.android.email.Clock;
import com.android.email.Controller;
import com.android.email.ControllerResultUiThreadWrapper;
import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.email.Utility;
import com.android.email.activity.setup.AccountSecurity;
import com.android.email.activity.setup.AccountSettingsXL;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;

import android.app.ActionBar;
import android.app.Activity;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

import java.security.InvalidParameterException;

/**
 * The main (two-pane) activity for XL devices.
 *
 * TODO Refine "move to".
 */
public class MessageListXL extends Activity implements
        MessageListXLFragmentManager.TargetActivity, MoveMessageToDialog.Callback,
        View.OnClickListener {
    private static final String EXTRA_ACCOUNT_ID = "ACCOUNT_ID";
    private static final String EXTRA_MAILBOX_ID = "MAILBOX_ID";
    private static final String EXTRA_MESSAGE_ID = "MESSAGE_ID";
    private static final int LOADER_ID_ACCOUNT_LIST = 0;
    /* package */ static final int MAILBOX_REFRESH_MIN_INTERVAL = 30 * 1000; // in milliseconds
    /* package */ static final int INBOX_AUTO_REFRESH_MIN_INTERVAL = 10 * 1000; // in milliseconds

    private Context mContext;
    private Controller mController;
    private RefreshManager mRefreshManager;
    private final RefreshListener mMailRefreshManagerListener
            = new RefreshListener();
    private Controller.Result mControllerResult;

    private AccountSelectorAdapter mAccountsSelectorAdapter;
    private final ActionBarNavigationCallback mActionBarNavigationCallback
            = new ActionBarNavigationCallback();

    private MessageOrderManager mOrderManager;

    private final MessageListXLFragmentManager mFragmentManager
            = new MessageListXLFragmentManager(this);

    private final MessageOrderManagerCallback mMessageOrderManagerCallback
            = new MessageOrderManagerCallback();

    private RefreshTask mRefreshTask;

    private BannerController mBannerController;
    private TextView mErrorMessageView;
    /**
     * Id of the account that had a messaging exception most recently.
     */
    private long mLastErrorAccountId;

    /**
     * Launch and open account's inbox.
     *
     * @param accountId If -1, default account will be used.
     */
    public static void actionOpenAccount(Activity fromActivity, long accountId) {
        Intent i = new Intent(fromActivity, MessageListXL.class);
        if (accountId != -1) {
            i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        }
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        fromActivity.startActivity(i);
    }

    /**
     * Launch and open a mailbox.
     *
     * @param accountId must not be -1.
     * @param mailboxId must not be -1.  Magic mailboxes IDs (such as
     * {@link Mailbox#QUERY_ALL_INBOXES}) don't work.
     */
    public static void actionOpenMailbox(Activity fromActivity, long accountId, long mailboxId) {
        Intent i = new Intent(fromActivity, MessageListXL.class);
        if (accountId == -1 || mailboxId == -1) {
            throw new InvalidParameterException();
        }
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        fromActivity.startActivity(i);
    }

    /**
     * Launch and open a message.
     *
     * @param accountId must not be -1.
     * @param mailboxId must not be -1.  Magic mailboxes IDs (such as
     * {@link Mailbox#QUERY_ALL_INBOXES}) don't work.
     * @param messageId must not be -1.
     */
    public static void actionOpenMessage(Activity fromActivity, long accountId, long mailboxId,
            long messageId) {
        Intent i = new Intent(fromActivity, MessageListXL.class);
        if (accountId == -1 || mailboxId == -1 || messageId == -1) {
            throw new InvalidParameterException();
        }
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        i.putExtra(EXTRA_MESSAGE_ID, messageId);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        fromActivity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onCreate");
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.message_list_xl);

        ActionBar ab = getActionBar();
        ab.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE | ActionBar.DISPLAY_SHOW_HOME);

        mFragmentManager.onActivityViewReady();

        mContext = getApplicationContext();
        mController = Controller.getInstance(this);
        mRefreshManager = RefreshManager.getInstance(this);
        mRefreshManager.registerListener(mMailRefreshManagerListener);

        mFragmentManager.setMailboxListFragmentCallback(new MailboxListFragmentCallback());
        mFragmentManager.setMessageListFragmentCallback(new MessageListFragmentCallback());
        mFragmentManager.setMessageViewFragmentCallback(new MessageViewFragmentCallback());

        mAccountsSelectorAdapter = new AccountSelectorAdapter(this, null);

        if (savedInstanceState != null) {
            mFragmentManager.loadState(savedInstanceState);
        } else {
            initFromIntent();
        }
        loadAccounts();

        // Set up views
        // TODO Probably better to extract mErrorMessageView related code into a separate class,
        // so that it'll be easy to reuse for the phone activities.
        mErrorMessageView = (TextView) findViewById(R.id.error_message);
        mErrorMessageView.setOnClickListener(this);
        mBannerController = new BannerController(this, mErrorMessageView,
                getResources().getDimensionPixelSize(R.dimen.error_message_height));

        // Halt the progress indicator (we'll display it later when needed)
        setProgressBarIndeterminate(true);
        setProgressBarIndeterminateVisibility(false);

        mControllerResult = new ControllerResultUiThreadWrapper<ControllerResult>(new Handler(),
                new ControllerResult());
        mController.addResultCallback(mControllerResult);
    }

    private void initFromIntent() {
        final Intent i = getIntent();
        final long accountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        final long mailboxId = i.getLongExtra(EXTRA_MAILBOX_ID, -1);
        final long messageId = i.getLongExtra(EXTRA_MESSAGE_ID, -1);
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, String.format("initFromIntent: %d %d", accountId, mailboxId));
        }

        if (accountId != -1) {
            mFragmentManager.selectAccount(accountId, mailboxId, messageId, true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXL onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        mFragmentManager.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onStart");
        super.onStart();

        mFragmentManager.onStart();
        if (mFragmentManager.isMessageSelected()) {
            updateMessageOrderManager();
        }
    }

    @Override
    protected void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onResume");
        super.onResume();
        mFragmentManager.onResume();

        // On MessageList.onResume, we go back to Welcome if an account has been added/removed.
        // We don't need to do that here, because when the activity resumes, the account list loader
        // will load the latest list.
        // And if all the accounts have been removed, the loader will detect it and do
        // appropriate things.
    }

    @Override
    protected void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onPause");
        super.onPause();
        mFragmentManager.onPause();
    }

    @Override
    protected void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onStop");
        super.onStop();

        mFragmentManager.onStop();
        stopMessageOrderManager();
    }

    @Override
    protected void onDestroy() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onDestroy");
        mController.removeResultCallback(mControllerResult);
        Utility.cancelTaskInterrupt(mRefreshTask);
        mRefreshManager.unregisterListener(mMailRefreshManagerListener);
        mFragmentManager.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXL onBackPressed");
        }
        onBackPressed(true);
    }

    /**
     * Performs the back action.
     *
     * @param isSystemBackKey set true if the system back key is pressed, rather than the home
     * icon on action bar.
     */
    private boolean onBackPressed(boolean isSystemBackKey) {
        if (mFragmentManager.onBackPressed(isSystemBackKey)) {
            return true;
        }
        if (isSystemBackKey) {
            // Perform the default behavior.
            super.onBackPressed();
            return true;
        }
        return false;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.error_message:
                dismissErrorMessage();
                break;
        }
    }

    private void onCurrentMessageGone() {
        switch (Preferences.getPreferences(this).getAutoAdvanceDirection()) {
            case Preferences.AUTO_ADVANCE_NEWER:
                if (moveToNewer()) return;
                if (moveToOlder()) return;
                break;
            case Preferences.AUTO_ADVANCE_OLDER:
                if (moveToOlder()) return;
                if (moveToNewer()) return;
                break;
        }
        // Last message in the box or AUTO_ADVANCE_MESSAGE_LIST.  Go back to message list.
        mFragmentManager.goBackToMailbox();
    }

    private void onMoveMessage() {
        long messageId = mFragmentManager.getMessageId();
        MoveMessageToDialog dialog = MoveMessageToDialog.newInstance(this, new long[] {messageId},
                null);
        dialog.show(getFragmentManager(), "dialog");
    }

    @Override
    public void onMoveToMailboxSelected(long newMailboxId, long[] messageIds) {
        ActivityHelper.moveMessages(this, newMailboxId, messageIds);
        onCurrentMessageGone();
    }

    /**
     * Start {@link MessageOrderManager} if not started, and sync it to the current message.
     */
    private void updateMessageOrderManager() {
        if (!mFragmentManager.isMailboxSelected()) {
            return;
        }
        final long mailboxId = mFragmentManager.getMailboxId();
        if (mOrderManager == null || mOrderManager.getMailboxId() != mailboxId) {
            stopMessageOrderManager();
            mOrderManager = new MessageOrderManager(this, mailboxId, mMessageOrderManagerCallback);
        }
        if (mFragmentManager.isMessageSelected()) {
            mOrderManager.moveTo(mFragmentManager.getMessageId());
        }
    }

    private class MessageOrderManagerCallback implements MessageOrderManager.Callback {
        @Override
        public void onMessagesChanged() {
            updateNavigationArrows();
        }

        @Override
        public void onMessageNotFound() {
            // Current message gone.
            mFragmentManager.goBackToMailbox();
        }
    }

    /**
     * Stop {@link MessageOrderManager}.
     */
    private void stopMessageOrderManager() {
        if (mOrderManager != null) {
            mOrderManager.close();
            mOrderManager = null;
        }
    }

    /**
     * Called when the default account is not found, i.e. there's no account set up.
     */
    private void onNoAccountFound() {
        // Open Welcome, which in turn shows the adding a new account screen.
        Welcome.actionStart(this);
        finish();
        return;
    }

    /**
     * Disable/enable the move-to-newer/older buttons.
     */
    private void updateNavigationArrows() {
        if (mOrderManager == null) {
            // shouldn't happen, but just in case
            mFragmentManager.updateMessageCommandButtons(false, false, 0, 0);
        } else {
            mFragmentManager.updateMessageCommandButtons(
                    mOrderManager.canMoveToNewer(), mOrderManager.canMoveToOlder(),
                    mOrderManager.getCurrentPosition(), mOrderManager.getTotalMessageCount());
        }
    }

    private boolean moveToOlder() {
        if (mFragmentManager.isMessageSelected() && (mOrderManager != null)
                && mOrderManager.moveToOlder()) {
            mFragmentManager.selectMessage(mOrderManager.getCurrentMessageId());
            return true;
        }
        return false;
    }

    private boolean moveToNewer() {
        if (mFragmentManager.isMessageSelected() && (mOrderManager != null)
                && mOrderManager.moveToNewer()) {
            mFragmentManager.selectMessage(mOrderManager.getCurrentMessageId());
            return true;
        }
        return false;
    }

    private class MailboxListFragmentCallback implements MailboxListFragment.Callback {
        @Override
        public void onMailboxSelected(long accountId, long mailboxId) {
            mFragmentManager.selectMailbox(mailboxId, -1, true);
        }

        @Override
        public void onAccountSelected(long accountId) {
            mFragmentManager.selectAccount(accountId, -1, -1, true);
            loadAccounts(); // This will update the account spinner, and select the account.
        }

        @Override
        public void onCurrentMailboxUpdated(long mailboxId, String mailboxName, int unreadCount) {
            mFragmentManager.setCurrentMailboxName(mailboxName, unreadCount);
        }
    }

    private class MessageListFragmentCallback implements MessageListFragment.Callback {
        @Override
        public void onMessageOpen(long messageId, long messageMailboxId, long listMailboxId,
                int type) {
            if (type == MessageListFragment.Callback.TYPE_DRAFT) {
                MessageCompose.actionEditDraft(MessageListXL.this, messageId);
            } else {
                mFragmentManager.selectMessage(messageId);
            }
        }

        @Override
        public void onMailboxNotFound() {
            // TODO: What to do??
        }

        @Override
        public void onEnterSelectionMode(boolean enter) {
        }
    }

    private class MessageViewFragmentCallback implements MessageViewFragment.Callback {
        @Override
        public void onMessageViewShown(int mailboxType) {
            updateMessageOrderManager();
            updateNavigationArrows();
        }

        @Override
        public void onMessageViewGone() {
            stopMessageOrderManager();
        }

        @Override
        public boolean onUrlInMessageClicked(String url) {
            return ActivityHelper.openUrlInMessage(MessageListXL.this, url,
                    mFragmentManager.getActualAccountId());
        }

        @Override
        public void onMessageSetUnread() {
            mFragmentManager.goBackToMailbox();
        }

        @Override
        public void onMessageNotExists() {
            mFragmentManager.goBackToMailbox();
        }

        @Override
        public void onLoadMessageStarted() {
            // TODO Any nice UI for this?
        }

        @Override
        public void onLoadMessageFinished() {
            // TODO Any nice UI for this?
        }

        @Override
        public void onLoadMessageError(String errorMessage) {
        }

        @Override
        public void onRespondedToInvite(int response) {
            onCurrentMessageGone();
        }

        @Override
        public void onCalendarLinkClicked(long epochEventStartTime) {
            ActivityHelper.openCalendar(MessageListXL.this, epochEventStartTime);
        }

        @Override
        public void onBeforeMessageDelete() {
            onCurrentMessageGone();
        }

        @Override
        public void onMoveMessage() {
            MessageListXL.this.onMoveMessage();
        }

        @Override
        public void onForward() {
            MessageCompose.actionForward(MessageListXL.this, mFragmentManager.getMessageId());
        }

        @Override
        public void onReply() {
            MessageCompose.actionReply(MessageListXL.this, mFragmentManager.getMessageId(), false);
        }

        @Override
        public void onReplyAll() {
            MessageCompose.actionReply(MessageListXL.this, mFragmentManager.getMessageId(), true);
        }
    }

    @Override
    public void onAccountSecurityHold(long accountId) {
        startActivity(AccountSecurity.actionUpdateSecurityIntent(this, accountId));
    }

    @Override
    public void onAccountChanged(long accountId) {
        invalidateOptionsMenu(); // Update the refresh button
    }

    @Override
    public void onMailboxChanged(long accountId, long newMailboxId) {
        updateProgressIcon();
    }

    @Override
    public void onMoveToNewer() {
        moveToNewer();
    }

    @Override
    public void onMoveToOlder() {
        moveToOlder();
    }

    /**
     * Call this when getting a connection error.
     */
    private void showErrorMessage(String message, long accountId) {
        if (mBannerController.show(message)) {
            mLastErrorAccountId = accountId;
        }
    }

    /**
     * Call this when the connection for an account is considered working.
     */
    private void clearErrorMessage(long accountId) {
        if (mLastErrorAccountId == accountId) {
            dismissErrorMessage();
        }
    }

    /**
     * Force dismiss the error banner.
     */
    private void dismissErrorMessage() {
        mBannerController.dismiss();
    }

    /**
     * Load account list for the action bar.
     *
     * If there's only one account configured, show the account name in the action bar.
     * If more than one account are configured, show a spinner in the action bar, and select the
     * current account.
     */
    private void loadAccounts() {
        getLoaderManager().initLoader(LOADER_ID_ACCOUNT_LIST, null, new LoaderCallbacks<Cursor>() {
            @Override
            public Loader<Cursor> onCreateLoader(int id, Bundle args) {
                return AccountSelectorAdapter.createLoader(mContext);
            }

            @Override
            public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
                updateAccountList(data);
            }

            @Override
            public void onLoaderReset(Loader<Cursor> loader) {
                mAccountsSelectorAdapter.swapCursor(null);
            }
        });
    }

    private void updateAccountList(Cursor accountsCursor) {
        final int count = accountsCursor.getCount();
        if (count == 0) {
            onNoAccountFound();
            return;
        }

        // If ony one acount, don't show dropdown.
        final ActionBar ab = getActionBar();
        if (count == 1) {
            accountsCursor.moveToFirst();

            // Show the account name as the title.
            ab.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE, ActionBar.DISPLAY_SHOW_TITLE);
            ab.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            ab.setTitle(AccountSelectorAdapter.getAccountDisplayName(accountsCursor));
            return;
        }

        // Find the currently selected account, and select it.
        int defaultSelection = 0;
        if (mFragmentManager.isAccountSelected()) {
            accountsCursor.moveToPosition(-1);
            int i = 0;
            while (accountsCursor.moveToNext()) {
                final long accountId = AccountSelectorAdapter.getAccountId(accountsCursor);
                if (accountId == mFragmentManager.getUIAccountId()) {
                    defaultSelection = i;
                    break;
                }
                i++;
            }
        }

        // Update the dropdown list.
        mAccountsSelectorAdapter.swapCursor(accountsCursor);

        // Don't show the title.
        ab.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_TITLE);
        ab.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
        ab.setListNavigationCallbacks(mAccountsSelectorAdapter, mActionBarNavigationCallback);
        ab.setSelectedNavigationItem(defaultSelection);
    }

    private class ActionBarNavigationCallback implements ActionBar.OnNavigationListener {
        @Override
        public boolean onNavigationItemSelected(int itemPosition, long accountId) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "Account selected: accountId=" + accountId);
            }
            mFragmentManager.selectAccount(accountId, -1, -1, true);
            return true;
        }
    }

    private class RefreshListener
            implements RefreshManager.Listener {
        @Override
        public void onMessagingError(final long accountId, long mailboxId, final String message) {
            updateProgressIcon();
        }

        @Override
        public void onRefreshStatusChanged(long accountId, long mailboxId) {
            updateProgressIcon();
        }
    }

    /**
     * If we're refreshing the current mailbox, animate the "mailbox refreshing" progress icon.
     */
    private void updateProgressIcon() {
        setProgressBarIndeterminateVisibility(isProgressActive());
    }

    private boolean isProgressActive() {
        final long mailboxId = mFragmentManager.getMailboxId();
        return (mailboxId >= 0) && mRefreshManager.isMessageListRefreshing(mailboxId);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.message_list_xl_option, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.refresh);
        if (item != null) {
            item.setVisible(shouldShowRefreshButton());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private boolean shouldShowRefreshButton() {
        return -1 != mFragmentManager.getActualAccountId();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                // Comes from the action bar when the app icon on the left is pressed.
                // It works like a back press, but it won't close the activity.
                return onBackPressed(false);
            case R.id.compose:
                return onCompose();
            case R.id.refresh:
                onRefresh();
                return true;
            case R.id.account_settings:
                return onAccountSettings();
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean onCompose() {
        if (!mFragmentManager.isAccountSelected()) {
            return false; // this shouldn't really happen
        }
        MessageCompose.actionCompose(this, mFragmentManager.getActualAccountId());
        return true;
    }

    private boolean onAccountSettings() {
        AccountSettingsXL.actionSettings(this, mFragmentManager.getActualAccountId());
        return true;
    }

    private void onRefresh() {
        // Cancel previously running instance if any.
        Utility.cancelTaskInterrupt(mRefreshTask);
        mRefreshTask = new RefreshTask(this, mFragmentManager.getActualAccountId(),
                mFragmentManager.getMailboxId());
        mRefreshTask.execute();
    }

    /**
     * Class to handle refresh.
     *
     * When the user press "refresh",
     * <ul>
     *   <li>Refresh the current mailbox, if it's refreshable.  (e.g. don't refresh combined inbox,
     *       drafts, etc.
     *   <li>Refresh the mailbox list, if it hasn't been refreshed in the last
     *       {@link #MAILBOX_REFRESH_MIN_INTERVAL}.
     *   <li>Refresh inbox, if it's not the current mailbox and it hasn't been refreshed in the last
     *       {@link #INBOX_AUTO_REFRESH_MIN_INTERVAL}.
     * </ul>
     */
    /* package */ static class RefreshTask extends AsyncTask<Void, Void, Boolean> {
        private final Clock mClock;
        private final Context mContext;
        private final long mAccountId;
        private final long mMailboxId;
        private final RefreshManager mRefreshManager;
        /* package */ long mInboxId;

        public RefreshTask(Context context, long accountId, long mailboxId) {
            this(context, accountId, mailboxId, Clock.INSTANCE,
                    RefreshManager.getInstance(context));
        }

        /* package */ RefreshTask(Context context, long accountId, long mailboxId, Clock clock,
                RefreshManager refreshManager) {
            mClock = clock;
            mContext = context;
            mRefreshManager = refreshManager;
            mAccountId = accountId;
            mMailboxId = mailboxId;
        }

        /**
         * Do DB access on a worker thread.
         */
        @Override
        protected Boolean doInBackground(Void... params) {
            mInboxId = Account.getInboxId(mContext, mAccountId);
            return Mailbox.isRefreshable(mContext, mMailboxId);
        }

        /**
         * Do the actual refresh.
         */
        @Override
        protected void onPostExecute(Boolean isCurrentMailboxRefreshable) {
            if (isCancelled() || isCurrentMailboxRefreshable == null) {
                return;
            }
            if (isCurrentMailboxRefreshable) {
                mRefreshManager.refreshMessageList(mAccountId, mMailboxId, false);
            }
            // Refresh mailbox list
            if (mAccountId != -1) {
                if (shouldRefreshMailboxList()) {
                    mRefreshManager.refreshMailboxList(mAccountId);
                }
            }
            // Refresh inbox
            if (shouldAutoRefreshInbox()) {
                mRefreshManager.refreshMessageList(mAccountId, mInboxId, false);
            }
        }

        /**
         * @return true if the mailbox list of the current account hasn't been refreshed
         * in the last {@link #MAILBOX_REFRESH_MIN_INTERVAL}.
         */
        /* package */ boolean shouldRefreshMailboxList() {
            if (mRefreshManager.isMailboxListRefreshing(mAccountId)) {
                return false;
            }
            final long nextRefreshTime = mRefreshManager.getLastMailboxListRefreshTime(mAccountId)
                    + MAILBOX_REFRESH_MIN_INTERVAL;
            if (nextRefreshTime > mClock.getTime()) {
                return false;
            }
            return true;
        }

        /**
         * @return true if the inbox of the current account hasn't been refreshed
         * in the last {@link #INBOX_AUTO_REFRESH_MIN_INTERVAL}.
         */
        /* package */ boolean shouldAutoRefreshInbox() {
            if (mInboxId == mMailboxId) {
                return false; // Current ID == inbox.  No need to auto-refresh.
            }
            if (mRefreshManager.isMessageListRefreshing(mInboxId)) {
                return false;
            }
            final long nextRefreshTime = mRefreshManager.getLastMessageListRefreshTime(mInboxId)
                    + INBOX_AUTO_REFRESH_MIN_INTERVAL;
            if (nextRefreshTime > mClock.getTime()) {
                return false;
            }
            return true;
        }
    }

    /**
     * A {@link Controller.Result} to detect connection status.
     */
    private class ControllerResult extends Controller.Result {
        @Override
        public void sendMailCallback(
                MessagingException result, long accountId, long messageId, int progress) {
            handleError(result, accountId, progress);
        }

        @Override
        public void serviceCheckMailCallback(
                MessagingException result, long accountId, long mailboxId, int progress, long tag) {
            handleError(result, accountId, progress);
        }

        @Override
        public void updateMailboxCallback(MessagingException result, long accountId, long mailboxId,
                int progress, int numNewMessages) {
            handleError(result, accountId, progress);
        }

        @Override
        public void updateMailboxListCallback(
                MessagingException result, long accountId, int progress) {
            handleError(result, accountId, progress);
        }

        @Override
        public void loadAttachmentCallback(MessagingException result, long accountId,
                long messageId, long attachmentId, int progress) {
            handleError(result, accountId, progress);
        }

        @Override
        public void loadMessageForViewCallback(MessagingException result, long accountId,
                long messageId, int progress) {
            handleError(result, accountId, progress);
        }

        private void handleError(MessagingException result, long accountId, int progress) {
            if (accountId == -1) {
                return;
            }
            if (result == null) {
                if (progress > 0) {
                    // Connection now working.
                    clearErrorMessage(accountId);
                }
            } else {
                // Connection error.
                showErrorMessage(result.getUiErrorMessage(MessageListXL.this), accountId);
            }
        }
    }
}
