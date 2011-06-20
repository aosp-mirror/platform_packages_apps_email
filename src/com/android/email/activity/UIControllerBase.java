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

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.android.email.Email;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.email.activity.setup.AccountSettings;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;

import java.util.LinkedList;
import java.util.List;

/**
 * Base class for the UI controller.
 */
abstract class UIControllerBase implements MailboxListFragment.Callback,
        MessageListFragment.Callback, MessageViewFragment.Callback  {
    static final boolean DEBUG_FRAGMENTS = false; // DO NOT SUBMIT WITH TRUE

    protected static final String BUNDLE_KEY_RESUME_INBOX_LOOKUP
            = "UIController.state.resumeInboxLookup";
    protected static final String BUNDLE_KEY_INBOX_LOOKUP_ACCOUNT_ID
            = "UIController.state.inboxLookupAccountId";

    /** The owner activity */
    final EmailActivity mActivity;
    final FragmentManager mFragmentManager;

    private final ActionBarController mActionBarController;

    final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    final RefreshManager mRefreshManager;

    /** {@code true} if the activity is resumed. */
    private boolean mResumed;

    /**
     * Use to find Inbox.  This should only run while the activity is resumed, because otherwise
     * we may not be able to perform fragment transactions when we get a callback.
     * See also {@link #mResumeInboxLookup}.
     */
    private MailboxFinder mInboxFinder;

    /**
     * Account ID passed to {@link #startInboxLookup(long)}.  We save it for resuming it in
     * {@link #onActivityResume()}.
     */
    private long mInboxLookupAccountId;

    /**
     * We (re)start inbox lookup in {@link #onActivityResume} if it's set.
     * Set in {@link #onActivityPause()} if it's still running, or {@link #startInboxLookup} is
     * called before the activity is resumed.
     */
    private boolean mResumeInboxLookup;

    /**
     * Fragments that are installed.
     *
     * A fragment is installed in {@link Fragment#onActivityCreated} and uninstalled in
     * {@link Fragment#onDestroyView}, using {@link FragmentInstallable} callbacks.
     *
     * This means fragments in the back stack are *not* installed.
     *
     * We set callbacks to fragments only when they are installed.
     *
     * @see FragmentInstallable
     */
    private MailboxListFragment mMailboxListFragment;
    private MessageListFragment mMessageListFragment;
    private MessageViewFragment mMessageViewFragment;

    /**
     * To avoid double-deleting a fragment (which will cause a runtime exception),
     * we put a fragment in this list when we {@link FragmentTransaction#remove(Fragment)} it,
     * and remove from the list when we actually uninstall it.
     */
    private final List<Fragment> mRemovedFragments = new LinkedList<Fragment>();

    private final RefreshManager.Listener mRefreshListener
            = new RefreshManager.Listener() {
        @Override
        public void onMessagingError(final long accountId, long mailboxId, final String message) {
            refreshActionBar();
        }

        @Override
        public void onRefreshStatusChanged(long accountId, long mailboxId) {
            refreshActionBar();
        }
    };

    public UIControllerBase(EmailActivity activity) {
        mActivity = activity;
        mFragmentManager = activity.getFragmentManager();
        mRefreshManager = RefreshManager.getInstance(mActivity);
        mActionBarController = createActionBarController(activity);
        if (DEBUG_FRAGMENTS) {
            FragmentManager.enableDebugLogging(true);
        }
    }

    /**
     * Called by the base class to let a subclass create an {@link ActionBarController}.
     */
    protected abstract ActionBarController createActionBarController(Activity activity);

    /** @return the layout ID for the activity. */
    public abstract int getLayoutId();

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
        mActionBarController.onActivityCreated();
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
        mResumed = true;
        if (mResumeInboxLookup) {
            startInboxLookup(mInboxLookupAccountId);
            mResumeInboxLookup = false;
        }
        refreshActionBar();
    }

    /**
     * Handles the {@link android.app.Activity#onPause} callback.
     */
    public void onActivityPause() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityPause");
        }
        mResumeInboxLookup = (mInboxFinder != null);
        stopInboxLookup();
    }

    /**
     * Handles the {@link android.app.Activity#onStop} callback.
     */
    public void onActivityStop() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityStop");
        }
        mResumed = false;
    }

    /**
     * Handles the {@link android.app.Activity#onDestroy} callback.
     */
    public void onActivityDestroy() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityDestroy");
        }
        mActionBarController.onActivityDestroy();
        mRefreshManager.unregisterListener(mRefreshListener);
        mTaskTracker.cancellAllInterrupt();
    }

    /**
     * Handles the {@link android.app.Activity#onSaveInstanceState} callback.
     */
    public void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onSaveInstanceState");
        }
        outState.putBoolean(BUNDLE_KEY_RESUME_INBOX_LOOKUP, mResumeInboxLookup);
        outState.putLong(BUNDLE_KEY_INBOX_LOOKUP_ACCOUNT_ID, mInboxLookupAccountId);
        mActionBarController.onSaveInstanceState(outState);
    }

    /**
     * Handles the {@link android.app.Activity#onRestoreInstanceState} callback.
     */
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " restoreInstanceState");
        }
        mResumeInboxLookup = savedInstanceState.getBoolean(BUNDLE_KEY_RESUME_INBOX_LOOKUP);
        mInboxLookupAccountId = savedInstanceState.getLong(BUNDLE_KEY_INBOX_LOOKUP_ACCOUNT_ID);
        mActionBarController.onRestoreInstanceState(savedInstanceState);
    }

    /**
     * Install a fragment.  Must be caleld from the host activity's
     * {@link FragmentInstallable#onInstallFragment}.
     */
    public final void onInstallFragment(Fragment fragment) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onInstallFragment  fragment=" + fragment);
        }
        if (fragment instanceof MailboxListFragment) {
            installMailboxListFragment((MailboxListFragment) fragment);
        } else if (fragment instanceof MessageListFragment) {
            installMessageListFragment((MessageListFragment) fragment);
        } else if (fragment instanceof MessageViewFragment) {
            installMessageViewFragment((MessageViewFragment) fragment);
        } else {
            throw new IllegalArgumentException("Tried to install unknown fragment");
        }
    }

    /** Install fragment */
    protected void installMailboxListFragment(MailboxListFragment fragment) {
        mMailboxListFragment = fragment;
        mMailboxListFragment.setCallback(this);
        refreshActionBar();
    }

    /** Install fragment */
    protected void installMessageListFragment(MessageListFragment fragment) {
        mMessageListFragment = fragment;
        mMessageListFragment.setCallback(this);
        refreshActionBar();
    }

    /** Install fragment */
    protected void installMessageViewFragment(MessageViewFragment fragment) {
        mMessageViewFragment = fragment;
        mMessageViewFragment.setCallback(this);
        refreshActionBar();
    }

    /**
     * Uninstall a fragment.  Must be caleld from the host activity's
     * {@link FragmentInstallable#onUninstallFragment}.
     */
    public final void onUninstallFragment(Fragment fragment) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onUninstallFragment  fragment=" + fragment);
        }
        mRemovedFragments.remove(fragment);
        if (fragment == mMailboxListFragment) {
            uninstallMailboxListFragment();
        } else if (fragment == mMessageListFragment) {
            uninstallMessageListFragment();
        } else if (fragment == mMessageViewFragment) {
            uninstallMessageViewFragment();
        } else {
            throw new IllegalArgumentException("Tried to uninstall unknown fragment");
        }
    }

    /** Uninstall {@link MailboxListFragment} */
    protected void uninstallMailboxListFragment() {
        mMailboxListFragment.setCallback(null);
        mMailboxListFragment = null;
    }

    /** Uninstall {@link MessageListFragment} */
    protected void uninstallMessageListFragment() {
        mMessageListFragment.setCallback(null);
        mMessageListFragment = null;
    }

    /** Uninstall {@link MessageViewFragment} */
    protected void uninstallMessageViewFragment() {
        mMessageViewFragment.setCallback(null);
        mMessageViewFragment = null;
    }

    /**
     * If a {@link Fragment} is not already in {@link #mRemovedFragments},
     * {@link FragmentTransaction#remove} it and add to the list.
     *
     * Do nothing if {@code fragment} is null.
     */
    protected final void removeFragment(FragmentTransaction ft, Fragment fragment) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " removeFragment fragment=" + fragment);
        }
        if (fragment == null) {
            return;
        }
        if (!mRemovedFragments.contains(fragment)) {
            ft.remove(fragment);
            addFragmentToRemovalList(fragment);
        }
    }

    /**
     * Remove a {@link Fragment} from {@link #mRemovedFragments}.  No-op if {@code fragment} is
     * null.
     *
     * {@link #removeMailboxListFragment}, {@link #removeMessageListFragment} and
     * {@link #removeMessageViewFragment} all call this, so subclasses don't have to do this when
     * using them.
     *
     * However, unfortunately, subclasses have to call this manually when popping from the
     * back stack to avoid double-delete.
     */
    protected void addFragmentToRemovalList(Fragment fragment) {
        if (fragment != null) {
            mRemovedFragments.add(fragment);
        }
    }

    /**
     * Remove the fragment if it's installed.
     */
    protected FragmentTransaction removeMailboxListFragment(FragmentTransaction ft) {
        removeFragment(ft, mMailboxListFragment);
        return ft;
    }

    /**
     * Remove the fragment if it's installed.
     */
    protected FragmentTransaction removeMessageListFragment(FragmentTransaction ft) {
        removeFragment(ft, mMessageListFragment);
        return ft;
    }

    /**
     * Remove the fragment if it's installed.
     */
    protected FragmentTransaction removeMessageViewFragment(FragmentTransaction ft) {
        removeFragment(ft, mMessageViewFragment);
        return ft;
    }

    /** @return true if a {@link MailboxListFragment} is installed. */
    protected final boolean isMailboxListInstalled() {
        return mMailboxListFragment != null;
    }

    /** @return true if a {@link MessageListFragment} is installed. */
    protected final boolean isMessageListInstalled() {
        return mMessageListFragment != null;
    }

    /** @return true if a {@link MessageViewFragment} is installed. */
    protected final boolean isMessageViewInstalled() {
        return mMessageViewFragment != null;
    }

    /** @return the installed {@link MailboxListFragment} or null. */
    protected final MailboxListFragment getMailboxListFragment() {
        return mMailboxListFragment;
    }

    /** @return the installed {@link MessageListFragment} or null. */
    protected final MessageListFragment getMessageListFragment() {
        return mMessageListFragment;
    }

    /** @return the installed {@link MessageViewFragment} or null. */
    protected final MessageViewFragment getMessageViewFragment() {
        return mMessageViewFragment;
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
     * Performs the back action.
     *
     * NOTE The method in the base class has precedence.  Subclasses overriding this method MUST
     * call super's method first.
     *
     * @param isSystemBackKey <code>true</code> if the system back key was pressed.
     * <code>false</code> if it's caused by the "home" icon click on the action bar.
     */
    public boolean onBackPressed(boolean isSystemBackKey) {
        if (mActionBarController.onBackPressed(isSystemBackKey)) {
            return true;
        }
        return false;
    }

    /**
     * Must be called from {@link Activity#onSearchRequested()}.
     */
    public void onSearchRequested() {
        mActionBarController.enterSearchMode(null);
    }

    /**
     * Callback called when the inbox lookup (started by {@link #startInboxLookup}) is finished.
     */
    protected abstract MailboxFinder.Callback getInboxLookupCallback();

    private final MailboxFinder.Callback mMailboxFinderCallback = new MailboxFinder.Callback() {
        private void cleanUp() {
            mInboxFinder = null;
        }

        @Override
        public void onAccountNotFound() {
            getInboxLookupCallback().onAccountNotFound();
            cleanUp();
        }

        @Override
        public void onAccountSecurityHold(long accountId) {
            getInboxLookupCallback().onAccountSecurityHold(accountId);
            cleanUp();
        }

        @Override
        public void onMailboxFound(long accountId, long mailboxId) {
            getInboxLookupCallback().onMailboxFound(accountId, mailboxId);
            cleanUp();
        }

        @Override
        public void onMailboxNotFound(long accountId) {
            getInboxLookupCallback().onMailboxNotFound(accountId);
            cleanUp();
        }
    };

    /**
     * Start inbox lookup.
     */
    protected void startInboxLookup(long accountId) {
        if (mInboxFinder != null) {
            return; // already running
        }
        mInboxLookupAccountId = accountId;
        if (!mResumed) {
            mResumeInboxLookup = true; // Don't start yet.
            return;
        }
        mInboxFinder = new MailboxFinder(mActivity, accountId, Mailbox.TYPE_INBOX,
                mMailboxFinderCallback);
        mInboxFinder.startLookup();
    }

    /**
     * Stop inbox lookup.
     */
    protected void stopInboxLookup() {
        if (mInboxFinder == null) {
            return; // not running
        }
        mInboxFinder.cancel();
        mInboxFinder = null;
    }

    /** @return true if the search menu option should be enabled. */
    protected boolean canSearch() {
        return false;
    }

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

        // STOPSHIP Temporary search options code
        // Only show search/sync options for EAS 12.0 and later
        boolean canSearch = false;
        if (canSearch()) {
            long accountId = getActualAccountId();
            if (accountId > 0) {
                // Move database operations out of the UI thread
                if ("eas".equals(Account.getProtocol(mActivity, accountId))) {
                    Account account = Account.restoreAccountWithId(mActivity, accountId);
                    if (account != null) {
                        // We should set a flag in the account indicating ability to handle search
                        String protocolVersion = account.mProtocolVersion;
                        if (Double.parseDouble(protocolVersion) >= 12.0) {
                            canSearch = true;
                        }
                    }
                }
            }
        }
        // Should use an isSearchable call to prevent search on inappropriate accounts/boxes
        // STOPSHIP Figure out where the "canSearch" test belongs
        menu.findItem(R.id.search).setVisible(true); //canSearch);

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
            case R.id.search:
                onSearchRequested();
                return true;
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
     * @return the ID of the message in focus and visible, if any. Returns
     *     {@link Message#NO_MESSAGE} if no message is opened.
     */
    protected long getMessageId() {
        return isMessageViewInstalled()
                ? getMessageViewFragment().getMessageId()
                : Message.NO_MESSAGE;
    }


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
     * Refresh the action bar and menu items, including the "refreshing" icon.
     */
    protected void refreshActionBar() {
        if (mActionBarController != null) {
            mActionBarController.refresh();
        }
        mActivity.invalidateOptionsMenu();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName(); // Shown on logcat
    }
}
