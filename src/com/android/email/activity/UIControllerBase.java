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
import com.android.email.RefreshManager;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.utility.EmailAsyncTask;

import android.app.Fragment;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import java.util.ArrayList;

/**
 * Base class for the UI controller.
 *
 * Note: Always use {@link #commitFragmentTransaction} to commit fragment transactions.
 * (Currently we use synchronous transactions only, but we may want to switch back to asynchronous
 * later.)
 */
abstract class UIControllerBase {
    /** No account selected */
    static final long NO_ACCOUNT = -1;
    /** No mailbox selected */
    static final long NO_MAILBOX = -1;
    /** No message selected */
    static final long NO_MESSAGE = -1;

    /** The owner activity */
    final EmailActivity mActivity;

    final RefreshManager mRefreshManager;

    final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

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

    public UIControllerBase(EmailActivity activity) {
        mActivity = activity;
        mRefreshManager = RefreshManager.getInstance(mActivity);
    }

    /** @return the layout ID for the activity. */
    public abstract int getLayoutId();

    /**
     * @return true if the UI controller currently can install fragments.
     */
    boolean isFragmentInstallable() {
        return !mHoldFragmentInstallation;
    }

    /**
     * Must be called just after the activity sets up the content view.  Used to initialize views.
     *
     * (Due to the complexity regarding class/activity initialization order, we can't do this in
     * the constructor.)
     */
    public void onActivityViewReady() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityViewReady");
        }
    }

    /**
     * Called at the end of {@link EmailActivity#onCreate}.
     */
    public void onActivityCreated() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityCreated");
        }
    }

    /**
     * Handles the {@link android.app.Activity#onStart} callback.
     */
    public void onActivityStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityStart");
        }
    }

    /**
     * Handles the {@link android.app.Activity#onResume} callback.
     */
    public void onActivityResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityResume");
        }
    }

    /**
     * Handles the {@link android.app.Activity#onPause} callback.
     */
    public void onActivityPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityPause");
        }
    }

    /**
     * Handles the {@link android.app.Activity#onStop} callback.
     */
    public void onActivityStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityStop");
        }
    }

    /**
     * Handles the {@link android.app.Activity#onDestroy} callback.
     */
    public void onActivityDestroy() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityDestroy");
        }
        mHoldFragmentInstallation = true; // No more fragment installation.
        mTaskTracker.cancellAllInterrupt();
    }

    /**
     * Install all the fragments kept in {@link #mRestoredFragments}.
     *
     * Must be called at the end of {@link EmailActivity#onCreate}.
     */
    public final void installRestoredFragments() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
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
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onSaveInstanceState");
        }
    }

    /**
     * Handles the {@link android.app.Activity#onRestoreInstanceState} callback.
     */
    public void restoreInstanceState(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
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

    void installFragment(Fragment fragment) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " installFragment  fragment=" + fragment);
        }
    }

    void commitFragmentTransaction(FragmentTransaction ft) {
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
     * @return the currently selected account ID.  If the current view is the combined view,
     * it'll return {@link #NO_ACCOUNT}.
     *
     * @see #getUIAccountId()
     */
    public long getActualAccountId() {
        final long uiAccountId = getUIAccountId();
        return uiAccountId == Account.ACCOUNT_ID_COMBINED_VIEW ? NO_ACCOUNT : uiAccountId;
    }

    /**
     * Show the default view for the given account.
     *
     * @param accountId ID of the account to load.  Can be {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
     *     Must never be {@link #NO_ACCOUNT}.
     */
    public abstract void openAccount(long accountId);

    /**
     * Loads the given account and optionally selects the given mailbox and message.  Used to open
     * a particular view at a request from outside of the activity, such as the widget.
     *
     * @param accountId ID of the account to load.  Can be {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
     *     Must never be {@link #NO_ACCOUNT}.
     * @param mailboxId ID of the mailbox to load. If {@link #NO_MAILBOX}, load the account's inbox.
     * @param messageId ID of the message to load. If {@link #NO_MESSAGE}, do not open a message.
     */
    public abstract void open(long accountId, long mailboxId, long messageId);

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
    public abstract boolean onCreateOptionsMenu(MenuInflater inflater, Menu menu);

    /**
     * Handles the {@link android.app.Activity#onPrepareOptionsMenu} callback.
     */
    public abstract boolean onPrepareOptionsMenu(MenuInflater inflater, Menu menu);

    /**
     * Handles the {@link android.app.Activity#onOptionsItemSelected} callback.
     *
     * @return true if the option item is handled.
     */
    public abstract boolean onOptionsItemSelected(MenuItem item);

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
     * STOPSHIP For experimental UI.  Remove this.
     *
     * Performs "refesh".
     */
    public void onRefresh() {
    }
}
