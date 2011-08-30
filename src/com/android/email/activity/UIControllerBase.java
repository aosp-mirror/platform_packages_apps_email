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
import com.android.email.FolderProperties;
import com.android.email.MessageListContext;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.activity.setup.MailboxSettings;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import java.util.LinkedList;
import java.util.List;

/**
 * Base class for the UI controller.
 */
abstract class UIControllerBase implements MailboxListFragment.Callback,
        MessageListFragment.Callback, MessageViewFragment.Callback  {
    static final boolean DEBUG_FRAGMENTS = false; // DO NOT SUBMIT WITH TRUE

    static final String KEY_LIST_CONTEXT = "UIControllerBase.listContext";

    /** The owner activity */
    final EmailActivity mActivity;
    final FragmentManager mFragmentManager;

    protected final ActionBarController mActionBarController;

    private MessageOrderManager mOrderManager;
    private final MessageOrderManagerCallback mMessageOrderManagerCallback =
            new MessageOrderManagerCallback();

    final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    final RefreshManager mRefreshManager;

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

    /**
     * The NfcHandler implements Near Field Communication sharing features
     * whenever the activity is in the foreground.
     */
    private NfcHandler mNfcHandler;

    /**
     * The active context for the current MessageList.
     * In some UI layouts such as the one-pane view, the message list may not be visible, but is
     * on the backstack. This list context will still be accessible in those cases.
     *
     * Should be set using {@link #setListContext(MessageListContext)}.
     */
    protected MessageListContext mListContext;

    private class RefreshListener implements RefreshManager.Listener {
        private MenuItem mRefreshIcon;

        @Override
        public void onMessagingError(final long accountId, long mailboxId, final String message) {
            updateRefreshIcon();
        }

        @Override
        public void onRefreshStatusChanged(long accountId, long mailboxId) {
            updateRefreshIcon();
        }

        void setRefreshIcon(MenuItem icon) {
            mRefreshIcon = icon;
            updateRefreshIcon();
        }

        private void updateRefreshIcon() {
            if (mRefreshIcon == null) {
                return;
            }

            if (isRefreshInProgress()) {
                mRefreshIcon.setActionView(R.layout.action_bar_indeterminate_progress);
            } else {
                mRefreshIcon.setActionView(null);
            }
        }
    };

    private final RefreshListener mRefreshListener = new RefreshListener();

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
        mNfcHandler = NfcHandler.register(this, mActivity);
    }

    /**
     * Handles the {@link android.app.Activity#onStart} callback.
     */
    public void onActivityStart() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityStart");
        }
        if (isMessageViewInstalled()) {
            updateMessageOrderManager();
        }
    }

    /**
     * Handles the {@link android.app.Activity#onResume} callback.
     */
    public void onActivityResume() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityResume");
        }
        refreshActionBar();
        if (mNfcHandler != null) {
            mNfcHandler.onAccountChanged();  // workaround for email not set on initial load
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
        stopMessageOrderManager();
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
        mActionBarController.onSaveInstanceState(outState);
        outState.putParcelable(KEY_LIST_CONTEXT, mListContext);
    }

    /**
     * Handles the {@link android.app.Activity#onRestoreInstanceState} callback.
     */
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " restoreInstanceState");
        }
        mActionBarController.onRestoreInstanceState(savedInstanceState);
        mListContext = savedInstanceState.getParcelable(KEY_LIST_CONTEXT);
    }

    // MessageViewFragment$Callback
    @Override
    public void onMessageSetUnread() {
        doAutoAdvance();
    }

    // MessageViewFragment$Callback
    @Override
    public void onMessageNotExists() {
        doAutoAdvance();
    }

    // MessageViewFragment$Callback
    @Override
    public void onRespondedToInvite(int response) {
        doAutoAdvance();
    }

    // MessageViewFragment$Callback
    @Override
    public void onBeforeMessageGone() {
        doAutoAdvance();
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

        // TODO: consolidate this refresh with the one that the Fragment itself does. since
        // the fragment calls setHasOptionsMenu(true) - it invalidates when it gets attached.
        // However the timing is slightly different and leads to a delay in update if this isn't
        // here - investigate why. same for the other installs.
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

        updateMessageOrderManager();
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
            // Remove try/catch when b/4981556 is fixed (framework bug)
            try {
                ft.remove(fragment);
            } catch (IllegalStateException ex) {
                Log.e(Logging.LOG_TAG, "Swalling IllegalStateException due to known bug for "
                        + " fragment: " + fragment, ex);
                Log.e(Logging.LOG_TAG, Utility.dumpFragment(fragment));
            }
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
     * Commit a {@link FragmentTransaction}.
     */
    protected void commitFragmentTransaction(FragmentTransaction ft) {
        if (DEBUG_FRAGMENTS) {
            Log.d(Logging.LOG_TAG, this + " commitFragmentTransaction: " + ft);
        }
        if (!ft.isEmpty()) {
            // NB: there should be no cases in which a transaction is committed after
            // onSaveInstanceState. Unfortunately, the "state loss" check also happens when in
            // LoaderCallbacks.onLoadFinished, and we wish to perform transactions there. The check
            // by the framework is conservative and prevents cases where there are transactions
            // affecting Loader lifecycles - but we have no such cases.
            // TODO: use asynchronous callbacks from loaders to avoid this implicit dependency
            ft.commitAllowingStateLoss();
            mFragmentManager.executePendingTransactions();
        }
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
     * @param accountId ID of the account to load.  Can be {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
     *     Must never be {@link Account#NO_ACCOUNT}.
     * @param forceShowInbox If {@code false} and the given account is already selected, do nothing.
     *        If {@code false}, we always change the view even if the account is selected.
     */
    public final void switchAccount(long accountId, boolean forceShowInbox) {

        if (Account.isSecurityHold(mActivity, accountId)) {
            ActivityHelper.showSecurityHoldDialog(mActivity, accountId);
            mActivity.finish();
            return;
        }

        if (accountId == getUIAccountId() && !forceShowInbox) {
            // Do nothing if the account is already selected.  Not even going back to the inbox.
            return;
        }
        if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
            openMailbox(accountId, Mailbox.QUERY_ALL_INBOXES);
        } else {
            long inboxId = Mailbox.findMailboxOfType(mActivity, accountId, Mailbox.TYPE_INBOX);
            if (inboxId == Mailbox.NO_MAILBOX) {
                // The account doesn't have Inbox yet... Redirect to Welcome and let it wait for
                // the initial sync...
                Log.w(Logging.LOG_TAG, "Account " + accountId +" doesn't have Inbox.  Redirecting"
                        + " to Welcome...");
                Welcome.actionOpenAccountInbox(mActivity, accountId);
                mActivity.finish();
            } else {
                openMailbox(accountId, inboxId);
            }
        }
        if (mNfcHandler != null) {
            mNfcHandler.onAccountChanged();
        }
    }

    /**
     * Returns the id of the parent mailbox used for the mailbox list fragment.
     *
     * IMPORTANT: Do not confuse {@link #getMailboxListMailboxId()} with
     *     {@link #getMessageListMailboxId()}
     */
    protected long getMailboxListMailboxId() {
        return isMailboxListInstalled() ? getMailboxListFragment().getSelectedMailboxId()
                : Mailbox.NO_MAILBOX;
    }

    /**
     * Returns the id of the mailbox used for the message list fragment.
     *
     * IMPORTANT: Do not confuse {@link #getMailboxListMailboxId()} with
     *     {@link #getMessageListMailboxId()}
     */
    protected long getMessageListMailboxId() {
        return isMessageListInstalled() ? getMessageListFragment().getMailboxId()
                : Mailbox.NO_MAILBOX;
    }

    /**
     * Shortcut for {@link #open} with {@link Message#NO_MESSAGE}.
     */
    protected final void openMailbox(long accountId, long mailboxId) {
        open(MessageListContext.forMailbox(accountId, mailboxId), Message.NO_MESSAGE);
    }

    /**
     * Opens a given list
     * @param listContext the list context for the message list to open
     * @param messageId if specified and not {@link Message#NO_MESSAGE}, will open the message
     *     in the message list.
     */
    public final void open(final MessageListContext listContext, final long messageId) {
        setListContext(listContext);
        openInternal(listContext, messageId);

        if (listContext.isSearch()) {
            mActionBarController.enterSearchMode(listContext.getSearchParams().mFilter);
        }
    }

    /**
     * Sets the internal value of the list context for the message list.
     */
    protected void setListContext(MessageListContext listContext) {
        if (Objects.equal(listContext, mListContext)) {
            return;
        }

        if (Email.DEBUG && Logging.DEBUG_LIFECYCLE) {
            Log.i(Logging.LOG_TAG, this + " setListContext: " + listContext);
        }
        mListContext = listContext;
    }

    protected abstract void openInternal(
            final MessageListContext listContext, final long messageId);

    /**
     * Performs the back action.
     *
     * @param isSystemBackKey <code>true</code> if the system back key was pressed.
     * <code>false</code> if it's caused by the "home" icon click on the action bar.
     */
    public abstract boolean onBackPressed(boolean isSystemBackKey);

    public void onSearchStarted() {
        // Show/hide the original search icon.
        mActivity.invalidateOptionsMenu();
    }

    /**
     * Must be called from {@link Activity#onSearchRequested()}.
     * This initiates the search entry mode - see {@link #onSearchSubmit} for when the search
     * is actually submitted.
     */
    public void onSearchRequested() {
        long accountId = getActualAccountId();
        boolean accountSearchable = false;
        if (accountId > 0) {
            Account account = Account.restoreAccountWithId(mActivity, accountId);
            if (account != null) {
                String protocol = account.getProtocol(mActivity);
                accountSearchable = (account.mFlags & Account.FLAGS_SUPPORTS_SEARCH) != 0;
            }
        }

        if (!accountSearchable) {
            return;
        }

        if (isMessageListReady()) {
            mActionBarController.enterSearchMode(null);
        }
    }

    /**
     * @return Whether or not a message list is ready and has its initial meta data loaded.
     */
    protected boolean isMessageListReady() {
        return isMessageListInstalled() && getMessageListFragment().hasDataLoaded();
    }

    /**
     * Determines the mailbox to search, if a search was to be initiated now.
     * This will return {@code null} if the UI is not focused on any particular mailbox to search
     * on.
     */
    private Mailbox getSearchableMailbox() {
        if (!isMessageListReady()) {
            return null;
        }
        MessageListFragment messageList = getMessageListFragment();

        // If already in a search, future searches will search the original mailbox.
        return mListContext.isSearch()
                ? messageList.getSearchedMailbox()
                : messageList.getMailbox();
    }

    // TODO: this logic probably needs to be tested in the backends as well, so it may be nice
    // to consolidate this to a centralized place, so that they don't get out of sync.
    /**
     * @return whether or not this account should do a global search instead when a user
     *     initiates a search on the given mailbox.
     */
    private static boolean shouldDoGlobalSearch(Account account, Mailbox mailbox) {
        return ((account.mFlags & Account.FLAGS_SUPPORTS_GLOBAL_SEARCH) != 0)
                && (mailbox.mType == Mailbox.TYPE_INBOX);
    }

    /**
     * Retrieves the hint text to be shown for when a search entry is being made.
     */
    protected String getSearchHint() {
        if (!isMessageListReady()) {
            return "";
        }
        Account account = getMessageListFragment().getAccount();
        Mailbox mailbox = getSearchableMailbox();

        if (mailbox == null) {
            return "";
        }

        if (shouldDoGlobalSearch(account, mailbox)) {
            return mActivity.getString(R.string.search_hint);
        }

        // Regular mailbox, or IMAP - search within that mailbox.
        String mailboxName = FolderProperties.getInstance(mActivity).getDisplayName(mailbox);
        return String.format(
                mActivity.getString(R.string.search_mailbox_hint),
                mailboxName);
    }

    /**
     * Kicks off a search query, if the UI is in a state where a search is possible.
     */
    protected void onSearchSubmit(final String queryTerm) {
        final long accountId = getUIAccountId();
        if (!Account.isNormalAccount(accountId)) {
            return; // Invalid account to search from.
        }

        Mailbox searchableMailbox = getSearchableMailbox();
        if (searchableMailbox == null) {
            return;
        }
        final long mailboxId = searchableMailbox.mId;

        if (Email.DEBUG) {
            Log.d(Logging.LOG_TAG,
                    "Submitting search: [" + queryTerm + "] in mailboxId=" + mailboxId);
        }

        mActivity.startActivity(EmailActivity.createSearchIntent(
                mActivity, accountId, mailboxId, queryTerm));


        // TODO: this causes a slight flicker.
        // A new instance of the activity will sit on top. When the user exits search and
        // returns to this activity, the search box should not be open then.
        mActionBarController.exitSearchMode();
    }

    /**
     * Handles exiting of search entry mode.
     */
    protected void onSearchExit() {
        if ((mListContext != null) && mListContext.isSearch()) {
            mActivity.finish();
        } else {
            // Re show the search icon.
            mActivity.invalidateOptionsMenu();
        }
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
            mRefreshListener.setRefreshIcon(item);
        } else {
            item.setVisible(false);
            mRefreshListener.setRefreshIcon(null);
        }

        // Deal with protocol-specific menu options.
        boolean mailboxHasServerCounterpart = false;
        boolean accountSearchable = false;
        boolean isEas = false;

        if (isMessageListReady()) {
            long accountId = getActualAccountId();
            if (accountId > 0) {
                Account account = Account.restoreAccountWithId(mActivity, accountId);
                if (account != null) {
                    String protocol = account.getProtocol(mActivity);
                    isEas = HostAuth.SCHEME_EAS.equals(protocol);
                    Mailbox mailbox = getMessageListFragment().getMailbox();
                    mailboxHasServerCounterpart = (mailbox != null)
                            && mailbox.loadsFromServer(protocol);
                    accountSearchable = (account.mFlags & Account.FLAGS_SUPPORTS_SEARCH) != 0;
                }
            }
        }

        boolean showSearchIcon = !mActionBarController.isInSearchMode()
                && accountSearchable && mailboxHasServerCounterpart;

        menu.findItem(R.id.search).setVisible(showSearchIcon);
        menu.findItem(R.id.mailbox_settings).setVisible(isEas && mailboxHasServerCounterpart);
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
            case R.id.mailbox_settings:
                final long mailboxId = getMailboxSettingsMailboxId();
                if (mailboxId != Mailbox.NO_MAILBOX) {
                    MailboxSettings.start(mActivity, mailboxId);
                }
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
     * @return mailbox ID for "mailbox settings" option.
     */
    protected abstract long getMailboxSettingsMailboxId();

    /**
     * Performs "refesh".
     */
    protected abstract void onRefresh();

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

    // MessageListFragment.Callback
    @Override
    public void onMailboxNotFound() {
        // Something bad happened - the account or mailbox we were looking for was deleted.
        // Just restart and let the entry flow find a good default view.
        Utility.showToast(mActivity, R.string.toast_mailbox_not_found);
        long accountId = getUIAccountId();
        if (accountId != Account.NO_ACCOUNT) {
            mActivity.startActivity(Welcome.createOpenAccountInboxIntent(mActivity, accountId));
        } else {
            Welcome.actionStart(mActivity);

        }
        mActivity.finish();
    }

    protected final MessageOrderManager getMessageOrderManager() {
        return mOrderManager;
    }

    /** Perform "auto-advance. */
    protected final void doAutoAdvance() {
        switch (Preferences.getPreferences(mActivity).getAutoAdvanceDirection()) {
            case Preferences.AUTO_ADVANCE_NEWER:
                if (moveToNewer()) return;
                break;
            case Preferences.AUTO_ADVANCE_OLDER:
                if (moveToOlder()) return;
                break;
        }
        if (isMessageViewInstalled()) { // We really should have the message view but just in case
            // Go back to mailbox list.
            // Use onBackPressed(), so we'll restore the message view state, such as scroll
            // position.
            // Also make sure to pass false to isSystemBackKey, so on two-pane we don't go back
            // to the collapsed mode.
            onBackPressed(true);
        }
    }

    /**
     * Subclass must implement it to enable/disable the newer/older buttons.
     */
    protected abstract void updateNavigationArrows();

    protected final boolean moveToOlder() {
        if ((mOrderManager != null) && mOrderManager.moveToOlder()) {
            navigateToMessage(mOrderManager.getCurrentMessageId());
            return true;
        }
        return false;
    }

    protected final boolean moveToNewer() {
        if ((mOrderManager != null) && mOrderManager.moveToNewer()) {
            navigateToMessage(mOrderManager.getCurrentMessageId());
            return true;
        }
        return false;
    }

    /**
     * Called when the user taps newer/older.  Subclass must implement it to open the specified
     * message.
     *
     * It's a bit different from just showing the message view fragment; on one-pane we show the
     * message view fragment but don't want to change back state.
     */
    protected abstract void navigateToMessage(long messageId);

    /**
     * Potentially create a new {@link MessageOrderManager}; if it's not already started or if
     * the account has changed, and sync it to the current message.
     */
    private void updateMessageOrderManager() {
        if (!isMessageViewInstalled()) {
            return;
        }
        Preconditions.checkNotNull(mListContext);

        final long mailboxId = mListContext.getMailboxId();
        if (mOrderManager == null || mOrderManager.getMailboxId() != mailboxId) {
            stopMessageOrderManager();
            mOrderManager =
                new MessageOrderManager(mActivity, mailboxId, mMessageOrderManagerCallback);
        }
        mOrderManager.moveTo(getMessageId());
        updateNavigationArrows();
    }

    /**
     * Stop {@link MessageOrderManager}.
     */
    protected final void stopMessageOrderManager() {
        if (mOrderManager != null) {
            mOrderManager.close();
            mOrderManager = null;
        }
    }

    private class MessageOrderManagerCallback implements MessageOrderManager.Callback {
        @Override
        public void onMessagesChanged() {
            updateNavigationArrows();
        }

        @Override
        public void onMessageNotFound() {
            doAutoAdvance();
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName(); // Shown on logcat
    }
}
