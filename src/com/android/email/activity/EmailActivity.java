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

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.email.Controller;
import com.android.email.ControllerResultUiThreadWrapper;
import com.android.email.Email;
import com.android.email.MessageListContext;
import com.android.email.MessagingExceptionStrings;
import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.IntentUtilities;
import com.google.common.base.Preconditions;

import java.util.ArrayList;

/**
 * The main Email activity, which is used on both the tablet and the phone.
 *
 * Because this activity is device agnostic, so most of the UI aren't owned by this, but by
 * the UIController.
 */
public class EmailActivity extends Activity implements View.OnClickListener, FragmentInstallable {
    public static final String EXTRA_ACCOUNT_ID = "ACCOUNT_ID";
    public static final String EXTRA_MAILBOX_ID = "MAILBOX_ID";
    public static final String EXTRA_MESSAGE_ID = "MESSAGE_ID";
    public static final String EXTRA_QUERY_STRING = "QUERY_STRING";

    /** Loader IDs starting with this is safe to use from UIControllers. */
    static final int UI_CONTROLLER_LOADER_ID_BASE = 100;

    /** Loader IDs starting with this is safe to use from ActionBarController. */
    static final int ACTION_BAR_CONTROLLER_LOADER_ID_BASE = 200;

    private static float sLastFontScale = -1;

    private Controller mController;
    private Controller.Result mControllerResult;

    private UIControllerBase mUIController;

    private final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    /** Banner to display errors */
    private BannerController mErrorBanner;
    /** Id of the account that had a messaging exception most recently. */
    private long mLastErrorAccountId;

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
        Preconditions.checkArgument(Account.isNormalAccount(accountId),
                "Can only search in normal accounts");

        // Note that a search doesn't use a restart intent, as we want another instance of
        // the activity to sit on the stack for search.
        Intent i = new Intent(fromActivity, EmailActivity.class);
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
        if (UiUtilities.useTwoPane(this)) {
            if (getIntent().getAction() != null
                    && Intent.ACTION_SEARCH.equals(getIntent().getAction())
                    && !UiUtilities.showTwoPaneSearchResults(this)) {
                mUIController = new UIControllerSearchTwoPane(this);
            } else {
                mUIController = new UIControllerTwoPane(this);
            }
        } else {
            mUIController = new UIControllerOnePane(this);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Logging.LOG_TAG, this + " onCreate");

        float fontScale = getResources().getConfiguration().fontScale;
        if (sLastFontScale != -1 && sLastFontScale != fontScale) {
            // If the font scale has been initialized, and has been detected to be different than
            // the last time the Activity ran, it means the user changed the font while no
            // Email Activity was running - we still need to purge static information though.
            onFontScaleChangeDetected();
        }
        sLastFontScale = fontScale;

        // UIController is used in onPrepareOptionsMenu(), which can be called from within
        // super.onCreate(), so we need to initialize it here.
        initUIController();

        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(mUIController.getLayoutId());

        mUIController.onActivityViewReady();

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
            final Intent intent = getIntent();
            final MessageListContext viewContext = MessageListContext.forIntent(this, intent);
            if (viewContext == null) {
                // This might happen if accounts were deleted on another thread, and there aren't
                // any remaining
                Welcome.actionStart(this);
                finish();
                return;
            } else {
                final long messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, Message.NO_MESSAGE);
                mUIController.open(viewContext, messageId);
            }
        }
        mUIController.onActivityCreated();
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

    @Override
    @SuppressWarnings("deprecation")
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mUIController.onOptionsItemSelected(item)) {
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
                Account account = Account.restoreAccountWithId(EmailActivity.this, accountId);
                if (account == null) return;
                String message =
                    MessagingExceptionStrings.getErrorString(EmailActivity.this, result);
                if (!TextUtils.isEmpty(account.mDisplayName)) {
                    // TODO Use properly designed layout. Don't just concatenate strings;
                    // which is generally poor for I18N.
                    message = message + "   (" + account.mDisplayName + ")";
                }
                if (mErrorBanner.show(message)) {
                    mLastErrorAccountId = accountId;
                }
             }
        }
    }

    /**
     * Handle a change to the system font size. This invalidates some static caches we have.
     */
    private void onFontScaleChangeDetected() {
        MessageListItem.resetDrawingCaches();
    }
}
