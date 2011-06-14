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

import com.android.email.Controller;
import com.android.email.ControllerResultUiThreadWrapper;
import com.android.email.Email;
import com.android.email.MessagingExceptionStrings;
import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.utility.EmailAsyncTask;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Fragment;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * The main Email activity, which is used on both the tablet and the phone.
 *
 * Because this activity is device agnostic, so most of the UI aren't owned by this, but by
 * the UIController.
 */
public class EmailActivity extends Activity implements View.OnClickListener, FragmentInstallable {
    private static final String EXTRA_ACCOUNT_ID = "ACCOUNT_ID";
    private static final String EXTRA_MAILBOX_ID = "MAILBOX_ID";
    private static final String EXTRA_MESSAGE_ID = "MESSAGE_ID";
    private static final String EXTRA_QUERY_STRING = "QUERY_STRING";

    /** Loader IDs starting with this is safe to use from UIControllers. */
    static final int UI_CONTROLLER_LOADER_ID_BASE = 100;

    /** Loader IDs starting with this is safe to use from ActionBarController. */
    static final int ACTION_BAR_CONTROLLER_LOADER_ID_BASE = 200;

    private static final int MAILBOX_SYNC_FREQUENCY_DIALOG = 1;
    private static final int MAILBOX_SYNC_LOOKBACK_DIALOG = 2;

    private Context mContext;
    private Controller mController;
    private Controller.Result mControllerResult;

    private UIControllerBase mUIController;

    private final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    /** Banner to display errors */
    private BannerController mErrorBanner;
    /** Id of the account that had a messaging exception most recently. */
    private long mLastErrorAccountId;

    // STOPSHIP Temporary mailbox settings UI
    private int mDialogSelection = -1;

    /**
     * Create an intent to launch and open account's inbox.
     *
     * @param accountId If -1, default account will be used.
     */
    public static Intent createOpenAccountIntent(Activity fromActivity, long accountId) {
        Intent i = IntentUtilities.createRestartAppIntent(fromActivity, EmailActivity.class);
        if (accountId != -1) {
            i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        }
        return i;
    }

    /**
     * Create an intent to launch and open a mailbox.
     *
     * @param accountId must not be -1.
     * @param mailboxId must not be -1.  Magic mailboxes IDs (such as
     * {@link Mailbox#QUERY_ALL_INBOXES}) don't work.
     */
    public static Intent createOpenMailboxIntent(Activity fromActivity, long accountId,
            long mailboxId) {
        if (accountId == -1 || mailboxId == -1) {
            throw new IllegalArgumentException();
        }
        Intent i = IntentUtilities.createRestartAppIntent(fromActivity, EmailActivity.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        return i;
    }

    /**
     * Create an intent to launch and open a message.
     *
     * @param accountId must not be -1.
     * @param mailboxId must not be -1.  Magic mailboxes IDs (such as
     * {@link Mailbox#QUERY_ALL_INBOXES}) don't work.
     * @param messageId must not be -1.
     */
    public static Intent createOpenMessageIntent(Activity fromActivity, long accountId,
            long mailboxId, long messageId) {
        if (accountId == -1 || mailboxId == -1 || messageId == -1) {
            throw new IllegalArgumentException();
        }
        Intent i = IntentUtilities.createRestartAppIntent(fromActivity, EmailActivity.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        i.putExtra(EXTRA_MESSAGE_ID, messageId);
        return i;
    }

    /**
     * Create an intent to launch search activity.
     *
     * @param accountId ID of the account for the mailbox.  Must not be {@link Account#NO_ACCOUNT}.
     * @param mailboxId ID of the mailbox to search, or {@link Mailbox#NO_MAILBOX} to perform
     *     global search.
     * @param query query string.
     */
    public static Intent createSearchIntent(Activity fromActivity, long accountId,
            long mailboxId, String query) {
        // STOPSHIP temporary search UI
        if (accountId == Account.NO_ACCOUNT) {
            throw new IllegalArgumentException();
        }
        Intent i = IntentUtilities.createRestartAppIntent(fromActivity, EmailActivity.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        i.putExtra(EXTRA_QUERY_STRING, query);
        i.setAction(Intent.ACTION_SEARCH);
        return i;
    }

    /**
     * Initialize {@link #mUIController}.
     */
    private void initUIController() {
        mUIController = UiUtilities.useTwoPane(this)
                ? new UIControllerTwoPane(this) : new UIControllerOnePane(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, this + " onCreate");

        // UIController is used in onPrepareOptionsMenu(), which can be called from within
        // super.onCreate(), so we need to initialize it here.
        initUIController();

        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(mUIController.getLayoutId());

        mUIController.onActivityViewReady();

        mContext = getApplicationContext();
        mController = Controller.getInstance(this);
        mControllerResult = new ControllerResultUiThreadWrapper<ControllerResult>(new Handler(),
                new ControllerResult());
        mController.addResultCallback(mControllerResult);

        // Set up views
        // TODO Probably better to extract mErrorMessageView related code into a separate class,
        // so that it'll be easy to reuse for the phone activities.
        TextView errorMessage = (TextView) findViewById(R.id.error_message);
        errorMessage.setOnClickListener(this);
        int errorBannerHeight = getResources().getDimensionPixelSize(R.dimen.error_message_height);
        mErrorBanner = new BannerController(this, errorMessage, errorBannerHeight);

        if (savedInstanceState != null) {
            mUIController.onRestoreInstanceState(savedInstanceState);
        } else {
            // This needs to be done after installRestoredFragments.
            // See UIControllerTwoPane.preFragmentTransactionCheck()
            initFromIntent();
        }
        mUIController.onActivityCreated();
    }

    private void initFromIntent() {
        final Intent i = getIntent();
        final long accountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        final long mailboxId = i.getLongExtra(EXTRA_MAILBOX_ID, -1);
        final long messageId = i.getLongExtra(EXTRA_MESSAGE_ID, -1);
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, String.format("initFromIntent: %d %d", accountId, mailboxId));
        }

        if (accountId != -1) {
            mUIController.open(accountId, mailboxId, messageId);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        mUIController.onSaveInstanceState(outState);
    }

    // FragmentInstallable
    @Override
    public void onInstallFragment(Fragment fragment) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onInstallFragment fragment=" + fragment);
        }
        mUIController.onInstallFragment(fragment);
    }

    // FragmentInstallable
    @Override
    public void onUninstallFragment(Fragment fragment) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onUninstallFragment fragment=" + fragment);
        }
        mUIController.onUninstallFragment(fragment);
    }

    @Override
    protected void onStart() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, this + " onStart");
        super.onStart();
        mUIController.onActivityStart();

        // STOPSHIP Temporary search UI
        Intent intent = getIntent();
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            final long accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, Account.NO_ACCOUNT);
            final long mailboxId = intent.getLongExtra(EXTRA_MAILBOX_ID, Mailbox.NO_MAILBOX);
            final String queryString = intent.getStringExtra(EXTRA_QUERY_STRING);
            Log.d(Logging.LOG_TAG, "Search: " + queryString);
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

            startActivity(createOpenMessageIntent(EmailActivity.this,
                    accountId, searchMailbox.mId, msg.mId));
            EmailAsyncTask.runAsyncParallel(new Runnable() {
                @Override
                public void run() {
                    SearchParams searchSpec = new SearchParams(SearchParams.ALL_MAILBOXES,
                            queryString);
                    controller.searchMessages(accountId, searchSpec, searchMailbox.mId);
                }});
            return;
        }
    }

    @Override
    protected void onResume() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, this + " onResume");
        super.onResume();
        mUIController.onActivityResume();
        /**
         * In {@link MessageList#onResume()}, we go back to {@link Welcome} if an account
         * has been added/removed. We don't need to do that here, because we fetch the most
         * up-to-date account list. Additionally, we detect and do the right thing if all
         * of the accounts have been removed.
         */
    }

    @Override
    protected void onPause() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, this + " onPause");
        super.onPause();
        mUIController.onActivityPause();
    }

    @Override
    protected void onStop() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, this + " onStop");
        super.onStop();
        mUIController.onActivityStop();
    }

    @Override
    protected void onDestroy() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, this + " onDestroy");
        mController.removeResultCallback(mControllerResult);
        mTaskTracker.cancellAllInterrupt();
        mUIController.onActivityDestroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onBackPressed");
        }
        if (!mUIController.onBackPressed(true)) {
            // Not handled by UIController -- perform the default. i.e. close the app.
            super.onBackPressed();
        }
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
     * Force dismiss the error banner.
     */
    private void dismissErrorMessage() {
        mErrorBanner.dismiss();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return mUIController.onCreateOptionsMenu(getMenuInflater(), menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // STOPSHIP Temporary sync options UI
        boolean isEas = false;

        long accountId = mUIController.getActualAccountId();
        if (accountId > 0) {
            // Move database operations out of the UI thread
            isEas = "eas".equals(Account.getProtocol(mContext, accountId));
        }

        // Should use an isSyncable call to prevent drafts/outbox from allowing this
        menu.findItem(R.id.sync_lookback).setVisible(isEas);
        menu.findItem(R.id.sync_frequency).setVisible(isEas);

        return mUIController.onPrepareOptionsMenu(getMenuInflater(), menu);
    }

    /**
     * Called when the search key is pressd.
     *
     * Use the below command to emulate the key press on devices without the search key.
     * adb shell input keyevent 84
     */
    @Override
    public boolean onSearchRequested() {
        if (Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onSearchRequested");
        }
        mUIController.onSearchRequested();
        return true; // Event handled.
    }

    // STOPSHIP Set column from user options
    private void setMailboxColumn(long mailboxId, String column, String value) {
        if (mailboxId > 0) {
            ContentValues cv = new ContentValues();
            cv.put(column, value);
            getContentResolver().update(
                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                    cv, null, null);
            mUIController.onRefresh();
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
    @Deprecated
    protected Dialog onCreateDialog(int id, Bundle args) {
        final long mailboxId = mUIController.getMailboxSettingsMailboxId();
        if (mailboxId < 0) {
            return null;
        }
        final Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mailboxId);
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
                            setMailboxColumn(mailboxId, MailboxColumns.SYNC_INTERVAL,
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
                            setMailboxColumn(mailboxId, MailboxColumns.SYNC_LOOKBACK,
                                    windowValues[mDialogSelection]);
                        }})
                    .setNegativeButton(R.string.cancel_action, mCancelListener)
                   .create();
        }
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mUIController.onOptionsItemSelected(item)) {
            return true;
        }
        switch (item.getItemId()) {
            // STOPSHIP Temporary mailbox settings UI
            case R.id.sync_lookback:
                showDialog(MAILBOX_SYNC_LOOKBACK_DIALOG);
                return true;
            // STOPSHIP Temporary mailbox settings UI
            case R.id.sync_frequency:
                showDialog(MAILBOX_SYNC_FREQUENCY_DIALOG);
                return true;
        }
        return super.onOptionsItemSelected(item);
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
                int progress, int numNewMessages, ArrayList<Long> addedMessages) {
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
                            Account.restoreAccountWithId(EmailActivity.this, accountId);
                        return (account == null) ? null : account.mDisplayName;
                    }

                    @Override
                    protected void onPostExecute(String accountName) {
                        String message =
                            MessagingExceptionStrings.getErrorString(EmailActivity.this, result);
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
