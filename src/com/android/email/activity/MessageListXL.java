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
import com.android.email.MessagingExceptionStrings;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.email.activity.setup.AccountSettingsXL;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Fragment;
import android.app.LoaderManager.LoaderCallbacks;
import android.app.SearchManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import java.security.InvalidParameterException;

/**
 * The main Email activity, which is used on both the tablet and the phone.
 *
 * Because this activity is device agnostic, so most of the UI aren't owned by this, but by
 * the UIController.
 *
 * TODO: Account spinner should also be moved out of this class.  (to the UIController or to a
 * sepate class.)
 */
public class MessageListXL extends Activity implements View.OnClickListener {
    private static final String EXTRA_ACCOUNT_ID = "ACCOUNT_ID";
    private static final String EXTRA_MAILBOX_ID = "MAILBOX_ID";
    private static final String EXTRA_MESSAGE_ID = "MESSAGE_ID";
    private static final int LOADER_ID_ACCOUNT_LIST = 0;
    /* package */ static final int MAILBOX_REFRESH_MIN_INTERVAL = 30 * 1000; // in milliseconds
    /* package */ static final int INBOX_AUTO_REFRESH_MIN_INTERVAL = 10 * 1000; // in milliseconds

    private static final int MAILBOX_SYNC_FREQUENCY_DIALOG = 1;
    private static final int MAILBOX_SYNC_LOOKBACK_DIALOG = 2;

    private Context mContext;
    private Controller mController;
    private RefreshManager mRefreshManager;
    private final RefreshListener mRefreshListener = new RefreshListener();
    private Controller.Result mControllerResult;

    private AccountSelectorAdapter mAccountsSelectorAdapter;
    private final ActionBarNavigationCallback mActionBarNavigationCallback =
        new ActionBarNavigationCallback();

    private final UIControllerTwoPane mUIController = new UIControllerTwoPane(this);

    private final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    /** Banner to display errors */
    private BannerController mErrorBanner;
    /** Id of the account that had a messaging exception most recently. */
    private long mLastErrorAccountId;

    // STOPSHIP Temporary mailbox settings UI
    private int mDialogSelection = -1;

    /**
     * Launch and open account's inbox.
     *
     * @param accountId If -1, default account will be used.
     */
    public static void actionOpenAccount(Activity fromActivity, long accountId) {
        Intent i = IntentUtilities.createRestartAppIntent(fromActivity, MessageListXL.class);
        if (accountId != -1) {
            i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        }
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
        if (accountId == -1 || mailboxId == -1) {
            throw new InvalidParameterException();
        }
        Intent i = IntentUtilities.createRestartAppIntent(fromActivity, MessageListXL.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
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
        if (accountId == -1 || mailboxId == -1 || messageId == -1) {
            throw new InvalidParameterException();
        }
        Intent i = IntentUtilities.createRestartAppIntent(fromActivity, MessageListXL.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        i.putExtra(EXTRA_MESSAGE_ID, messageId);
        fromActivity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, "MessageListXL onCreate");
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.message_list_xl);

        mUIController.onActivityViewReady();

        mContext = getApplicationContext();
        mController = Controller.getInstance(this);
        mControllerResult = new ControllerResultUiThreadWrapper<ControllerResult>(new Handler(),
                new ControllerResult());
        mController.addResultCallback(mControllerResult);
        mRefreshManager = RefreshManager.getInstance(this);
        mRefreshManager.registerListener(mRefreshListener);

        mAccountsSelectorAdapter = new AccountSelectorAdapter(this, null);

        loadAccounts();

        // Set up views
        // TODO Probably better to extract mErrorMessageView related code into a separate class,
        // so that it'll be easy to reuse for the phone activities.
        TextView errorMessage = (TextView) findViewById(R.id.error_message);
        errorMessage.setOnClickListener(this);
        int errorBannerHeight = getResources().getDimensionPixelSize(R.dimen.error_message_height);
        mErrorBanner = new BannerController(this, errorMessage, errorBannerHeight);

        // Install restored fragments.
        mUIController.installRestoredFragments();

        if (savedInstanceState != null) {
            mUIController.restoreInstanceState(savedInstanceState);
        } else {
            // This needs to be done after installRestoredFragments.
            // See UIControllerTwoPane.preFragmentTransactionCheck()
            initFromIntent();
        }
    }

    private void initFromIntent() {
        final Intent i = getIntent();
        final long accountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        final long mailboxId = i.getLongExtra(EXTRA_MAILBOX_ID, -1);
        final long messageId = i.getLongExtra(EXTRA_MESSAGE_ID, -1);
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, String.format("initFromIntent: %d %d", accountId, mailboxId));
        }

        if (accountId != -1) {
            mUIController.open(accountId, mailboxId, messageId);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXL onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        mUIController.onSaveInstanceState(outState);
    }

    @Override
    public void onAttachFragment(Fragment fragment) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXL onAttachFragment fragment=" + fragment);
        }
        super.onAttachFragment(fragment);
        mUIController.onAttachFragment(fragment);
    }

    @Override
    protected void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, "MessageListXL onStart");
        super.onStart();
        mUIController.onStart();

        // STOPSHIP Temporary search UI
        Intent intent = getIntent();
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            // TODO Very temporary (e.g. no database access in UI thread)
            Bundle appData = getIntent().getBundleExtra(SearchManager.APP_DATA);
            if (appData == null) return; // ??
            final long accountId = appData.getLong(EXTRA_ACCOUNT_ID);
            final long mailboxId = appData.getLong(EXTRA_MAILBOX_ID);
            final String queryString = intent.getStringExtra(SearchManager.QUERY);
            Log.d(Logging.LOG_TAG, queryString);
            // Switch to search mailbox
            // TODO How to handle search from within the search mailbox??
            final Controller controller = Controller.getInstance(mContext);
            final Mailbox searchMailbox = controller.getSearchMailbox(accountId);
            if (searchMailbox == null) return;

            // Delete contents, add a placeholder
            ContentResolver resolver = mContext.getContentResolver();
            resolver.delete(Message.CONTENT_URI, Message.MAILBOX_KEY + "=" + searchMailbox.mId,
                    null);
            ContentValues cv = new ContentValues();
            cv.put(Mailbox.DISPLAY_NAME, queryString);
            resolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, searchMailbox.mId), cv,
                    null, null);
            Message msg = new Message();
            msg.mMailboxKey = searchMailbox.mId;
            msg.mAccountKey = accountId;
            msg.mDisplayName = "Searching for " + queryString;
            msg.mTimeStamp = Long.MAX_VALUE; // Sort on top
            msg.save(mContext);

            actionOpenMessage(MessageListXL.this, accountId, searchMailbox.mId, msg.mId);
            Utility.runAsync(new Runnable() {
                @Override
                public void run() {
                    controller.searchMessages(accountId, mailboxId, true, queryString, 10, 0,
                            searchMailbox.mId);
                }});
            return;
        }
    }

    @Override
    protected void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, "MessageListXL onResume");
        super.onResume();
        mUIController.onResume();
        /**
         * In {@link MessageList#onResume()}, we go back to {@link Welcome} if an account
         * has been added/removed. We don't need to do that here, because we fetch the most
         * up-to-date account list. Additionally, we detect and do the right thing if all
         * of the accounts have been removed.
         */
    }

    @Override
    protected void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, "MessageListXL onPause");
        super.onPause();
        mUIController.onPause();
    }

    @Override
    protected void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, "MessageListXL onStop");
        super.onStop();
        mUIController.onStop();
    }

    @Override
    protected void onDestroy() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, "MessageListXL onDestroy");
        mController.removeResultCallback(mControllerResult);
        mTaskTracker.cancellAllInterrupt();
        mRefreshManager.unregisterListener(mRefreshListener);
        mUIController.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXL onBackPressed");
        }
        onBackPressed(true);
    }

    /**
     * Performs the back action.
     *
     * @param isSystemBackKey <code>true</code> if the system back key was pressed. Otherwise,
     * <code>false</code> [e.g. the home icon on action bar were pressed].
     */
    private boolean onBackPressed(boolean isSystemBackKey) {
        if (mUIController.onBackPressed(isSystemBackKey)) {
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

    /**
     * Called by the UIController when the current account has changed.
     */
    public void onAccountChanged(long accountId) {
        updateRefreshProgress();
        loadAccounts(); // This will update the account spinner, and select the account.
    }

    /**
     * Force dismiss the error banner.
     */
    private void dismissErrorMessage() {
        mErrorBanner.dismiss();
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
            // Open Welcome, which in turn shows the adding a new account screen.
            Welcome.actionStart(this);
            finish();
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
        if (mUIController.isAccountSelected()) {
            accountsCursor.moveToPosition(-1);
            int i = 0;
            while (accountsCursor.moveToNext()) {
                final long accountId = AccountSelectorAdapter.getAccountId(accountsCursor);
                if (accountId == mUIController.getUIAccountId()) {
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

    private void selectAccount(long accountId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "Account selected: accountId=" + accountId);
        }
        // TODO UIManager should do the check eventually, but it's necessary for now.
        if (accountId != mUIController.getUIAccountId()) {
            mUIController.openAccount(accountId);
        }
    }

    private class ActionBarNavigationCallback implements ActionBar.OnNavigationListener {
        @Override
        public boolean onNavigationItemSelected(int itemPosition, long accountId) {
            selectAccount(accountId);
            return true;
        }
    }

    private class RefreshListener
            implements RefreshManager.Listener {
        @Override
        public void onMessagingError(final long accountId, long mailboxId, final String message) {
            updateRefreshProgress();
        }

        @Override
        public void onRefreshStatusChanged(long accountId, long mailboxId) {
            updateRefreshProgress();
        }
    }

    /**
     * Start/stop the "refresh" animation on the action bar according to the current refresh state.
     *
     * (We start the animation if {@link UIControllerTwoPane#isRefreshInProgress} returns true,
     * and stop otherwise.)
     */
    public void updateRefreshProgress() {
        invalidateOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.message_list_xl_option, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // STOPSHIP Temporary search/sync options UI
        // Only show search/sync options for EAS
        boolean isEas = false;
        long accountId = mUIController.getActualAccountId();
        if (accountId > 0) {
            if ("eas".equals(Account.getProtocol(mContext, accountId))) {
                isEas = true;
            }
        }
        // Should use an isSearchable call to prevent search on inappropriate accounts/boxes
        menu.findItem(R.id.search).setVisible(isEas);
        // Should use an isSyncable call to prevent drafts/outbox from allowing this
        menu.findItem(R.id.sync_lookback).setVisible(isEas);
        menu.findItem(R.id.sync_frequency).setVisible(isEas);

        ActivityHelper.updateRefreshMenuIcon(menu.findItem(R.id.refresh),
                mUIController.isRefreshEnabled(),
                mUIController.isRefreshInProgress());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onSearchRequested() {
        Bundle bundle = new Bundle();
        bundle.putLong(EXTRA_ACCOUNT_ID, mUIController.getActualAccountId());
        bundle.putLong(EXTRA_MAILBOX_ID, mUIController.getMailboxId());
        startSearch(null, false, bundle, false);
        return true;
    }

    // STOPSHIP Set column from user options
    private void setMailboxColumn(String column, String value) {
        final long mailboxId = mUIController.getMailboxId();
        if (mailboxId > 0) {
            ContentValues cv = new ContentValues();
            cv.put(column, value);
            getContentResolver().update(
                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                    cv, null, null);
            onRefresh();
        }
    }
    // STOPSHIP Temporary mailbox settings UI.  If this ends up being useful, it should
    // be moved to Utility (emailcommon)
    private int findInStringArray(String[] array, String item) {
        int i = 0;
        for (String str: array) {
            if (str.equals(item)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    // STOPSHIP Temporary mailbox settings UI
    private final DialogInterface.OnClickListener mSelectionListener =
        new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                mDialogSelection = which;
            }
    };

    // STOPSHIP Temporary mailbox settings UI
    private final DialogInterface.OnClickListener mCancelListener =
        new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
            }
    };

    // STOPSHIP Temporary mailbox settings UI
    @Override
    protected Dialog onCreateDialog(int id, Bundle args) {
        Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mUIController.getMailboxId());
        if (mailbox == null) return null;
        switch (id) {
            case MAILBOX_SYNC_FREQUENCY_DIALOG:
                String freq = Integer.toString(mailbox.mSyncInterval);
                final String[] freqValues = getResources().getStringArray(
                        R.array.account_settings_check_frequency_values_push);
                int selection = findInStringArray(freqValues, freq);
                // If not found, this is a push mailbox; trust me on this
                if (selection == -1) selection = 0;
                return new AlertDialog.Builder(this)
                    .setIconAttribute(android.R.attr.dialogIcon)
                    .setTitle(R.string.mailbox_options_check_frequency_label)
                    .setSingleChoiceItems(R.array.account_settings_check_frequency_entries_push,
                            selection,
                            mSelectionListener)
                    .setPositiveButton(R.string.okay_action, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            setMailboxColumn(MailboxColumns.SYNC_INTERVAL,
                                    freqValues[mDialogSelection]);
                        }})
                    .setNegativeButton(R.string.cancel_action, mCancelListener)
                   .create();

            case MAILBOX_SYNC_LOOKBACK_DIALOG:
                freq = Integer.toString(mailbox.mSyncLookback);
                final String[] windowValues = getResources().getStringArray(
                        R.array.account_settings_mail_window_values);
                selection = findInStringArray(windowValues, freq);
                return new AlertDialog.Builder(this)
                    .setIconAttribute(android.R.attr.dialogIcon)
                    .setTitle(R.string.mailbox_options_lookback_label)
                    .setSingleChoiceItems(R.array.account_settings_mail_window_entries,
                            selection,
                            mSelectionListener)
                    .setPositiveButton(R.string.okay_action, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            setMailboxColumn(MailboxColumns.SYNC_LOOKBACK,
                                    windowValues[mDialogSelection]);
                        }})
                    .setNegativeButton(R.string.cancel_action, mCancelListener)
                   .create();
        }
        return null;
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
            // STOPSHIP Temporary mailbox settings UI
            case R.id.sync_lookback:
                showDialog(MAILBOX_SYNC_LOOKBACK_DIALOG);
                return true;
            // STOPSHIP Temporary mailbox settings UI
            case R.id.sync_frequency:
                showDialog(MAILBOX_SYNC_FREQUENCY_DIALOG);
                return true;
            case R.id.search:
                onSearchRequested();
                return true;
            case R.id.account_settings:
                return onAccountSettings();
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean onCompose() {
        if (!mUIController.isAccountSelected()) {
            return false; // this shouldn't really happen
        }
        MessageCompose.actionCompose(this, mUIController.getActualAccountId());
        return true;
    }

    private boolean onAccountSettings() {
        AccountSettingsXL.actionSettings(this, mUIController.getActualAccountId());
        return true;
    }

    private void onRefresh() {
        // Cancel previously running instance if any.
        new RefreshTask(mTaskTracker, this, mUIController.getActualAccountId(),
                mUIController.getMailboxId()).cancelPreviousAndExecuteParallel();
    }

    /**
     * TODO Move this to UIController, as requirement for this may change for the phone.
     * (e.g. should we still do the implicit refresh of mailbox list on the phone?)
     *
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
    /* package */ static class RefreshTask extends EmailAsyncTask<Void, Void, Boolean> {
        private final Clock mClock;
        private final Context mContext;
        private final long mAccountId;
        private final long mMailboxId;
        private final RefreshManager mRefreshManager;
        /* package */ long mInboxId;

        public RefreshTask(EmailAsyncTask.Tracker tracker, Context context, long accountId,
                long mailboxId) {
            this(tracker, context, accountId, mailboxId, Clock.INSTANCE,
                    RefreshManager.getInstance(context));
        }

        /* package */ RefreshTask(EmailAsyncTask.Tracker tracker, Context context, long accountId,
                long mailboxId, Clock clock, RefreshManager refreshManager) {
            super(tracker);
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

        private void handleError(final MessagingException result, final long accountId,
                int progress) {
            if (accountId == -1) {
                return;
            }
            if (result == null) {
                if (progress > 0) {
                    // Connection now working; clear the error message banner
                    if (mLastErrorAccountId == accountId) {
                        dismissErrorMessage();
                    }
                }
            } else {
                // Connection error; show the error message banner
                new EmailAsyncTask<Void, Void, String>(mTaskTracker) {
                    @Override
                    protected String doInBackground(Void... params) {
                        Account account =
                            Account.restoreAccountWithId(MessageListXL.this, accountId);
                        return (account == null) ? null : account.mDisplayName;
                    }

                    @Override
                    protected void onPostExecute(String accountName) {
                        String message =
                            MessagingExceptionStrings.getErrorString(MessageListXL.this, result);
                        if (!TextUtils.isEmpty(accountName)) {
                            // TODO Use properly designed layout. Don't just concatenate strings;
                            // which is generally poor for I18N.
                            message = message + "   (" + accountName + ")";
                        }
                        if (mErrorBanner.show(message)) {
                            mLastErrorAccountId = accountId;
                        }
                    }
                }.executeParallel();
            }
        }
    }
}
