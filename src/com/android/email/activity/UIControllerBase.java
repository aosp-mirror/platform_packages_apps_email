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

package com.android.email.activity;

import com.android.email.Email;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.email.activity.setup.AccountSettings;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import java.util.ArrayList;

/**
 * Base class for the UI controller.
 *
 * Note: Always use {@link #commitFragmentTransaction} and {@link #popBackStack} to operate fragment
 * transactions.
 * (Currently we use synchronous transactions only, but we may want to switch back to asynchronous
 * later.)
 */
abstract class UIControllerBase {
    protected static final String BUNDLE_KEY_ACCOUNT_ID = "UIController.state.account_id";
    protected static final String BUNDLE_KEY_MAILBOX_ID = "UIController.state.mailbox_id";
    protected static final String BUNDLE_KEY_MESSAGE_ID = "UIController.state.message_id";

    /** The owner activity */
    final EmailActivity mActivity;

    final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    final RefreshManager mRefreshManager;

    /**
     * List of fragments that are restored by the framework while the activity is being re-created
     * for configuration changes (e.g. screen rotation).  We'll install them later when the activity
     * is created in {@link #installRestoredFragments()}.
     */
    private final ArrayList<Fragment> mRestoredFragments = new ArrayList<Fragment>();

    /**
     * Whether fragment installation should be hold.
     * We hold installing fragments until {@link #installRestoredFragments()} is called.
     */
    private boolean mHoldFragmentInstallation = true;

    private final RefreshManager.Listener mRefreshListener
            = new RefreshManager.Listener() {
        @Override
        public void onMessagingError(final long accountId, long mailboxId, final String message) {
            updateRefreshProgress();
        }

        @Override
        public void onRefreshStatusChanged(long accountId, long mailboxId) {
            updateRefreshProgress();
        }
    };

    public UIControllerBase(EmailActivity activity) {
        mActivity = activity;
        mRefreshManager = RefreshManager.getInstance(mActivity);
    }

    /** @return the layout ID for the activity. */
    public abstract int getLayoutId();

    /**
     * @return true if the UI controller currently can install fragments.
     */
    protected final boolean isFragmentInstallable() {
        return !mHoldFragmentInstallation;
    }

    /**
     * Must be called just after the activity sets up the content view.  Used to initialize views.
     *
     * (Due to the complexity regarding class/activity initialization order, we can't do this in
     * the constructor.)
     */
    public void onActivityViewReady() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityViewReady");
        }
    }

    /**
     * Called at the end of {@link EmailActivity#onCreate}.
     */
    public void onActivityCreated() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityCreated");
        }
        mRefreshManager.registerListener(mRefreshListener);
    }

    /**
     * Handles the {@link android.app.Activity#onStart} callback.
     */
    public void onActivityStart() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityStart");
        }
    }

    /**
     * Handles the {@link android.app.Activity#onResume} callback.
     */
    public void onActivityResume() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityResume");
        }
    }

    /**
     * Handles the {@link android.app.Activity#onPause} callback.
     */
    public void onActivityPause() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityPause");
        }
    }

    /**
     * Handles the {@link android.app.Activity#onStop} callback.
     */
    public void onActivityStop() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityStop");
        }
    }

    /**
     * Handles the {@link android.app.Activity#onDestroy} callback.
     */
    public void onActivityDestroy() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityDestroy");
        }
        mHoldFragmentInstallation = true; // No more fragment installation.
        mRefreshManager.unregisterListener(mRefreshListener);
        mTaskTracker.cancellAllInterrupt();
    }

    /**
     * Install all the fragments kept in {@link #mRestoredFragments}.
     *
     * Must be called at the end of {@link EmailActivity#onCreate}.
     */
    public final void installRestoredFragments() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " installRestoredFragments");
        }

        mHoldFragmentInstallation = false;

        // Install all the fragments restored by the framework.
        for (Fragment fragment : mRestoredFragments) {
            installFragment(fragment);
        }
        mRestoredFragments.clear();
    }

    /**
     * Handles the {@link android.app.Activity#onSaveInstanceState} callback.
     */
    public void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onSaveInstanceState");
        }
    }

    /**
     * Handles the {@link android.app.Activity#onRestoreInstanceState} callback.
     */
    public void restoreInstanceState(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " restoreInstanceState");
        }
    }

    /**
     * Handles the {@link android.app.Activity#onAttachFragment} callback.
     *
     * If the activity has already been created, we initialize the fragment here.  Otherwise we
     * keep the fragment in {@link #mRestoredFragments} and initialize it after the activity's
     * onCreate.
     */
    public final void onAttachFragment(Fragment fragment) {
        if (mHoldFragmentInstallation) {
            // Fragment being restored by the framework during the activity recreation.
            mRestoredFragments.add(fragment);
            return;
        }
        installFragment(fragment);
    }

    private void installFragment(Fragment fragment) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " installFragment  fragment=" + fragment);
        }
        if (fragment instanceof MailboxListFragment) {
            installMailboxListFragment((MailboxListFragment) fragment);
        } else if (fragment instanceof MessageListFragment) {
            installMessageListFragment((MessageListFragment) fragment);
        } else if (fragment instanceof MessageViewFragment) {
            installMessageViewFragment((MessageViewFragment) fragment);
        } else {
            // Ignore -- uninteresting fragments such as dialogs.
        }
    }

    protected abstract void installMailboxListFragment(MailboxListFragment fragment);

    protected abstract void installMessageListFragment(MessageListFragment fragment);

    protected abstract void installMessageViewFragment(MessageViewFragment fragment);

    // not used
    protected final void popBackStack(FragmentManager fm, String name, int flags) {
        fm.popBackStackImmediate(name, flags);
    }

    protected final void commitFragmentTransaction(FragmentTransaction ft) {
        ft.commit();
        mActivity.getFragmentManager().executePendingTransactions();
    }

    /**
     * @return the currently selected account ID, *or* {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
     *
     * @see #getActualAccountId()
     */
    public abstract long getUIAccountId();

    /**
     * @return true if an account is selected, or the current view is the combined view.
     */
    public final boolean isAccountSelected() {
        return getUIAccountId() != Account.NO_ACCOUNT;
    }

    /**
     * @return if an actual account is selected.  (i.e. {@link Account#ACCOUNT_ID_COMBINED_VIEW}
     * is not considered "actual".s)
     */
    public final boolean isActualAccountSelected() {
        return isAccountSelected() && (getUIAccountId() != Account.ACCOUNT_ID_COMBINED_VIEW);
    }

    /**
     * @return the currently selected account ID.  If the current view is the combined view,
     * it'll return {@link Account#NO_ACCOUNT}.
     *
     * @see #getUIAccountId()
     */
    public final long getActualAccountId() {
        return isActualAccountSelected() ? getUIAccountId() : Account.NO_ACCOUNT;
    }

    /**
     * Show the default view for the given account.
     *
     * No-op if the given account is already selected.
     *
     * @param accountId ID of the account to load.  Can be {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
     *     Must never be {@link Account#NO_ACCOUNT}.
     */
    public final void switchAccount(long accountId) {
        if (accountId == getUIAccountId()) {
            // Do nothing if the account is already selected.  Not even going back to the inbox.
            return;
        }
        openAccount(accountId);
    }

    /**
     * Shortcut for {@link #open} with {@link Mailbox#NO_MAILBOX} and {@link Message#NO_MESSAGE}.
     */
    protected final void openAccount(long accountId) {
        open(accountId, Mailbox.NO_MAILBOX, Message.NO_MESSAGE);
    }

    /**
     * Shortcut for {@link #open} with {@link Message#NO_MESSAGE}.
     */
    protected final void openMailbox(long accountId, long mailboxId) {
        open(accountId, mailboxId, Message.NO_MESSAGE);
    }

    /**
     * Loads the given account and optionally selects the given mailbox and message.  Used to open
     * a particular view at a request from outside of the activity, such as the widget.
     *
     * @param accountId ID of the account to load.  Can be {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
     *     Must never be {@link Account#NO_ACCOUNT}.
     * @param mailboxId ID of the mailbox to load. If {@link Mailbox#NO_MAILBOX},
     *     load the account's inbox.
     * @param messageId ID of the message to load. If {@link Message#NO_MESSAGE},
     *     do not open a message.
     */
    public abstract void open(long accountId, long mailboxId, long messageId);

    /**
     * Navigates to the parent mailbox list of the given mailbox.
     */
    protected final void navigateToParentMailboxList(final long currentMailboxId) {
        final long accountId = getUIAccountId();
        final Context context = mActivity.getApplicationContext(); // for DB access only.

        // Get the upper level mailbox ID, and navigate to it.

        // Unfortunately if the screen rotates while the task is running, we just cancel the task
        // so navigation request will be gone.  But we'll live with it as it's not too critical.
        new EmailAsyncTask<Void, Void, Long>(mTaskTracker) {
            @Override protected Long doInBackground(Void... params) {
                final Mailbox mailbox = Mailbox.restoreMailboxWithId(context, currentMailboxId);
                if (mailbox == null) {
                    return null;
                }
                return mailbox.mParentKey;
            }

            @Override protected void onPostExecute(Long mailboxId) {
                if (mailboxId == null) {
                    // Mailbox removed, just show the root for the account.
                    mailboxId = Mailbox.NO_MAILBOX;
                }
                openMailbox(accountId, mailboxId);
            }
        }.cancelPreviousAndExecuteSerial();
    }

    /**
     * Performs the back action.
     *
     * @param isSystemBackKey <code>true</code> if the system back key was pressed.
     * <code>false</code> if it's caused by the "home" icon click on the action bar.
     */
    public abstract boolean onBackPressed(boolean isSystemBackKey);

    /**
     * Handles the {@link android.app.Activity#onCreateOptionsMenu} callback.
     */
    public boolean onCreateOptionsMenu(MenuInflater inflater, Menu menu) {
        inflater.inflate(R.menu.email_activity_options, menu);
        return true;
    }

    /**
     * Handles the {@link android.app.Activity#onPrepareOptionsMenu} callback.
     */
    public boolean onPrepareOptionsMenu(MenuInflater inflater, Menu menu) {

        // Update the refresh button.
        MenuItem item = menu.findItem(R.id.refresh);
        if (isRefreshEnabled()) {
            item.setVisible(true);
            if (isRefreshInProgress()) {
                item.setActionView(R.layout.action_bar_indeterminate_progress);
            } else {
                item.setActionView(null);
            }
        } else {
            item.setVisible(false);
        }
        return true;
    }

    /**
     * Handles the {@link android.app.Activity#onOptionsItemSelected} callback.
     *
     * @return true if the option item is handled.
     */
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
        return false;
    }

    /**
     * Opens the message compose activity.
     */
    private boolean onCompose() {
        if (!isAccountSelected()) {
            return false; // this shouldn't really happen
        }
        MessageCompose.actionCompose(mActivity, getActualAccountId());
        return true;
    }

    /**
     * Handles the "Settings" option item.  Opens the settings activity.
     */
    private boolean onAccountSettings() {
        AccountSettings.actionSettings(mActivity, getActualAccountId());
        return true;
    }

    /**
     * STOPSHIP For experimental UI.  Remove this.
     *
     * @return mailbox ID which we search for messages.
     */
    public abstract long getSearchMailboxId();

    /**
     * STOPSHIP For experimental UI.  Remove this.
     *
     * @return mailbox ID for "mailbox settings" option.
     */
    public abstract long getMailboxSettingsMailboxId();

    /**
     * STOPSHIP For experimental UI.  Make it abstract protected.
     *
     * Performs "refesh".
     */
    public abstract void onRefresh();

    /**
     * @return true if refresh is in progress for the current mailbox.
     */
    protected abstract boolean isRefreshInProgress();

    /**
     * @return true if the UI should enable the "refresh" command.
     */
    protected abstract boolean isRefreshEnabled();


    /**
     * Start/stop the "refresh" animation on the action bar according to the current refresh state.
     *
     * (We start the animation if {@link #isRefreshInProgress} returns true,
     * and stop otherwise.)
     */
    protected void updateRefreshProgress() {
        mActivity.invalidateOptionsMenu();
    }
}
