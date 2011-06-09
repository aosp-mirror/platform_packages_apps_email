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
import com.android.email.activity.MailboxFinder.Callback;
import com.android.email.activity.setup.AccountSecurity;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.util.Log;

import java.util.Set;


/**
 * UI Controller for non x-large devices.  Supports a single-pane layout.
 *
 * One one-pane, only at most one fragment can be installed at a time.
 *
 * Note due to the asynchronous nature of the fragment transaction, there is a window when
 * there is no installed or visible fragments.
 *
 * Major TODOs
 * - TODO Newer/Older for message view with swipe!
 * - TODO Implement callbacks
 */
class UIControllerOnePane extends UIControllerBase {
    private static final String BUNDLE_KEY_PREVIOUS_FRAGMENT
            = "UIControllerOnePane.PREVIOUS_FRAGMENT";

    // Our custom poor-man's back stack which has only one entry at maximum.
    private Fragment mPreviousFragment;

    // MailboxListFragment.Callback
    @Override
    public void onAccountSelected(long accountId) {
        switchAccount(accountId);
    }

    // MailboxListFragment.Callback
    @Override
    public void onCurrentMailboxUpdated(long mailboxId, String mailboxName, int unreadCount) {
    }

    // MailboxListFragment.Callback
    @Override
    public void onMailboxSelected(long accountId, long mailboxId, boolean nestedNavigation) {
        if (nestedNavigation) {
            return; // Nothing to do on 1-pane.
        }
        openMailbox(accountId, mailboxId);
    }

    // MailboxListFragment.Callback
    @Override
    public void onParentMailboxChanged() {
        refreshActionBar();
    }

    // MessageListFragment.Callback
    @Override
    public void onAdvancingOpAccepted(Set<Long> affectedMessages) {
        // Nothing to do on 1 pane.
    }

    // MessageListFragment.Callback
    @Override
    public void onEnterSelectionMode(boolean enter) {
        // TODO Auto-generated method stub
    }

    // MessageListFragment.Callback
    @Override
    public void onListLoaded() {
        // TODO Auto-generated method stub
    }

    // MessageListFragment.Callback
    @Override
    public void onMailboxNotFound() {
        open(getUIAccountId(), Mailbox.NO_MAILBOX, Message.NO_MESSAGE);
    }

    // MessageListFragment.Callback
    @Override
    public void onMessageOpen(
            long messageId, long messageMailboxId, long listMailboxId, int type) {
        if (type == MessageListFragment.Callback.TYPE_DRAFT) {
            MessageCompose.actionEditDraft(mActivity, messageId);
        } else {
            open(getUIAccountId(), getMailboxId(), messageId);
        }
    }

    // MessageListFragment.Callback
    @Override
    public boolean onDragStarted() {
        // No drag&drop on 1-pane
        return false;
    }

    // MessageListFragment.Callback
    @Override
    public void onDragEnded() {
        // No drag&drop on 1-pane
    }

    // MessageViewFragment.Callback
    @Override
    public void onForward() {
        MessageCompose.actionForward(mActivity, getMessageId());
    }

    // MessageViewFragment.Callback
    @Override
    public void onReply() {
        MessageCompose.actionReply(mActivity, getMessageId(), false);
    }

    // MessageViewFragment.Callback
    @Override
    public void onReplyAll() {
        MessageCompose.actionReply(mActivity, getMessageId(), true);
    }

    // MessageViewFragment.Callback
    @Override
    public void onCalendarLinkClicked(long epochEventStartTime) {
        ActivityHelper.openCalendar(mActivity, epochEventStartTime);
    }

    // MessageViewFragment.Callback
    @Override
    public boolean onUrlInMessageClicked(String url) {
        return ActivityHelper.openUrlInMessage(mActivity, url, getActualAccountId());
    }

    // MessageViewFragment.Callback
    @Override
    public void onBeforeMessageGone() {
        // TODO Auto-generated method stub
    }

    // MessageViewFragment.Callback
    @Override
    public void onMessageSetUnread() {
        // TODO Auto-generated method stub
    }

    // MessageViewFragment.Callback
    @Override
    public void onRespondedToInvite(int response) {
        // TODO Auto-generated method stub
    }

    // MessageViewFragment.Callback
    @Override
    public void onLoadMessageError(String errorMessage) {
        // TODO Auto-generated method stub
    }

    // MessageViewFragment.Callback
    @Override
    public void onLoadMessageFinished() {
        // TODO Auto-generated method stub
    }

    // MessageViewFragment.Callback
    @Override
    public void onLoadMessageStarted() {
        // TODO Auto-generated method stub
    }

    // MessageViewFragment.Callback
    @Override
    public void onMessageNotExists() {
        // TODO Auto-generated method stub
    }

    // MessageViewFragment.Callback
    @Override
    public void onMessageShown() {
        // TODO Auto-generated method stub
    }

    // This is all temporary as we'll have a different action bar controller for 1-pane.
    private class ActionBarControllerCallback implements ActionBarController.Callback {
        @Override
        public boolean shouldShowMailboxName() {
            return false; // no mailbox name/unread count.
        }

        @Override
        public String getCurrentMailboxName() {
            return null; // no mailbox name/unread count.
        }

        @Override
        public int getCurrentMailboxUnreadCount() {
            return 0; // no mailbox name/unread count.
        }

        @Override
        public boolean shouldShowUp() {
            return isMessageViewVisible()
                     || (isMailboxListVisible() && !getMailboxListFragment().isRoot());
        }

        @Override
        public long getUIAccountId() {
            return UIControllerOnePane.this.getUIAccountId();
        }

        @Override
        public void onMailboxSelected(long mailboxId) {
            if (mailboxId == Mailbox.NO_MAILBOX) {
                showAllMailboxes();
            } else {
                openMailbox(getUIAccountId(), mailboxId);
            }
        }

        @Override
        public boolean isAccountSelected() {
            return UIControllerOnePane.this.isAccountSelected();
        }

        @Override
        public void onAccountSelected(long accountId) {
            switchAccount(accountId);
        }

        @Override
        public void onNoAccountsFound() {
            Welcome.actionStart(mActivity);
            mActivity.finish();
        }
    }

    public UIControllerOnePane(EmailActivity activity) {
        super(activity);
    }

    @Override
    protected ActionBarController createActionBarController(Activity activity) {

        // For now, we just reuse the same action bar controller used for 2-pane.
        // We may change it later.

        return new ActionBarController(activity, activity.getLoaderManager(),
                activity.getActionBar(), new ActionBarControllerCallback());
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mPreviousFragment != null) {
            mActivity.getFragmentManager().putFragment(outState,
                    BUNDLE_KEY_PREVIOUS_FRAGMENT, mPreviousFragment);
        }
    }

    @Override
    public void restoreInstanceState(Bundle savedInstanceState) {
        super.restoreInstanceState(savedInstanceState);
        mPreviousFragment = mActivity.getFragmentManager().getFragment(savedInstanceState,
                BUNDLE_KEY_PREVIOUS_FRAGMENT);
    }

    @Override
    public int getLayoutId() {
        return R.layout.email_activity_one_pane;
    }

    @Override
    public void onActivityViewReady() {
        super.onActivityViewReady();
    }

    @Override
    public void onActivityCreated() {
        super.onActivityCreated();
    }

    @Override
    public void onActivityResume() {
        super.onActivityResume();
        refreshActionBar();
    }

    /** @return true if a {@link MailboxListFragment} is installed and visible. */
    private final boolean isMailboxListVisible() {
        return isMailboxListInstalled();
    }

    /** @return true if a {@link MessageListFragment} is installed and visible. */
    private final boolean isMessageListVisible() {
        return isMessageListInstalled();
    }

    /** @return true if a {@link MessageViewFragment} is installed and visible. */
    private final boolean isMessageViewVisible() {
        return isMessageViewInstalled();
    }

    @Override
    public long getUIAccountId() {
        // Get it from the visible fragment.
        if (isMailboxListVisible()) {
            return getMailboxListFragment().getAccountId();
        }
        if (isMessageListVisible()) {
            return getMessageListFragment().getAccountId();
        }
        if (isMessageViewVisible()) {
            return getMessageViewFragment().getOpenerAccountId();
        }
        return Account.NO_ACCOUNT;
    }

    private long getMailboxId() {
        // Get it from the visible fragment.
        if (isMessageListVisible()) {
            return getMessageListFragment().getMailboxId();
        }
        if (isMessageViewVisible()) {
            return getMessageViewFragment().getOpenerMailboxId();
        }
        return Mailbox.NO_MAILBOX;
    }

    private long getMessageId() {
        // Get it from the visible fragment.
        if (isMessageViewVisible()) {
            return getMessageViewFragment().getMessageId();
        }
        return Message.NO_MESSAGE;
    }

    private final MailboxFinder.Callback mInboxLookupCallback = new MailboxFinder.Callback() {
        @Override
        public void onMailboxFound(long accountId, long mailboxId) {
            // Inbox found.
            openMailbox(accountId, mailboxId);
        }

        @Override
        public void onAccountNotFound() {
            // Account removed?
            Welcome.actionStart(mActivity);
        }

        @Override
        public void onMailboxNotFound(long accountId) {
            // Inbox not found??
            Welcome.actionStart(mActivity);
        }

        @Override
        public void onAccountSecurityHold(long accountId) {
            mActivity.startActivity(AccountSecurity.actionUpdateSecurityIntent(mActivity, accountId,
                    true));
        }
    };

    @Override
    protected Callback getInboxLookupCallback() {
        return mInboxLookupCallback;
    }

    @Override
    public boolean onBackPressed(boolean isSystemBackKey) {
        if (Email.DEBUG) {
            // This is VERY important -- no check for DEBUG_LIFECYCLE
            Log.d(Logging.LOG_TAG, this + " onBackPressed: " + isSystemBackKey);
        }
        // If the mailbox list is shown and showing a nested mailbox, let it navigate up first.
        if (isMailboxListInstalled() && getMailboxListFragment().navigateUp()) {
            if (DEBUG_FRAGMENTS) {
                Log.d(Logging.LOG_TAG, this + " Back: back handled by mailbox list");
            }
            return true;
        }

        // Custom back stack
        if (shouldPopFromBackStack(isSystemBackKey)) {
            if (DEBUG_FRAGMENTS) {
                Log.d(Logging.LOG_TAG, this + " Back: Popping from back stack");
            }
            popFromBackStack();
            return true;
        }

        // No entry in the back stack.
        // If the message view is shown, show the "parent" message list.
        // This happens when we get a deep link to a message.  (e.g. from a widget)
        if (isMessageViewInstalled()) {
            if (DEBUG_FRAGMENTS) {
                Log.d(Logging.LOG_TAG, this + " Back: Message view -> Message List");
            }
            openMailbox(getMessageViewFragment().getOpenerAccountId(),
                    getMessageViewFragment().getOpenerMailboxId());
            return true;
        }
        return false;
    }

    @Override
    public void open(final long accountId, final long mailboxId, final long messageId) {
        if (Email.DEBUG) {
            // This is VERY important -- no check for DEBUG_LIFECYCLE
            Log.i(Logging.LOG_TAG, this + " open accountId=" + accountId
                    + " mailboxId=" + mailboxId + " messageId=" + messageId);
        }
        if (accountId == Account.NO_ACCOUNT) {
            throw new IllegalArgumentException();
        }

        if ((getUIAccountId() == accountId) && (getMailboxId() == mailboxId)
                && (getMessageId() == messageId)) {
            return;
        }

        final boolean accountChanging = (getUIAccountId() != accountId);
        if (messageId != Message.NO_MESSAGE) {
            showMessageView(accountId, mailboxId, messageId, accountChanging);
        } else if (mailboxId != Mailbox.NO_MAILBOX) {
            showMessageList(accountId, mailboxId, accountChanging);
        } else {
            // Mailbox not specified.  Open Inbox or Combined Inbox.
            if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
                showMessageList(accountId, Mailbox.QUERY_ALL_INBOXES, accountChanging);
            } else {
                startInboxLookup(accountId);
            }
        }
    }

    /**
     * @return currently installed {@link Fragment} (1-pane has only one at most), or null if none
     *         exists.
     */
    private Fragment getInstalledFragment() {
        if (isMailboxListInstalled()) {
            return getMailboxListFragment();
        } else if (isMessageListInstalled()) {
            return getMessageListFragment();
        } else if (isMessageViewInstalled()) {
            return getMessageViewFragment();
        }
        return null;
    }

    /**
     * Remove currently installed {@link Fragment} (1-pane has only one at most), or no-op if none
     *         exists.
     */
    private void removeInstalledFragment(FragmentTransaction ft) {
        removeFragment(ft, getInstalledFragment());
    }

    private void showMailboxList(long accountId, long mailboxId, boolean clearBackStack) {
        showFragment(MailboxListFragment.newInstance(accountId, mailboxId, false), clearBackStack);
    }

    private void showMessageList(long accountId, long mailboxId, boolean clearBackStack) {
        showFragment(MessageListFragment.newInstance(accountId, mailboxId), clearBackStack);
    }

    private void showMessageView(long accountId, long mailboxId, long messageId,
            boolean clearBackStack) {
        showFragment(MessageViewFragment.newInstance(accountId, mailboxId, messageId),
                clearBackStack);
    }

    /**
     * Use this instead of {@link FragmentTransaction#commit}.  We may switch to the synchronous
     * transaction some day.
     */
    private void commitFragmentTransaction(FragmentTransaction ft) {
        ft.commit();
    }

    /**
     * Push the installed fragment into our custom back stack (or optionally
     * {@link FragmentTransaction#remove} it) and {@link FragmentTransaction#add} {@code fragment}.
     *
     * @param fragment {@link Fragment} to be added.
     * @param clearBackStack set {@code true} to remove the currently installed fragment.
     *        {@code false} to push it into the backstack.
     *
     *  TODO Delay-call the whole method and use the synchronous transaction.
     */
    private void showFragment(Fragment fragment, boolean clearBackStack) {
        if (DEBUG_FRAGMENTS) {
            if (clearBackStack) {
                Log.i(Logging.LOG_TAG, this + " backstack: [clear] showing " + fragment);
            } else {
                Log.i(Logging.LOG_TAG, this + " backstack: [push] " + getInstalledFragment()
                        + " -> " + fragment);
            }
        }
        final FragmentTransaction ft = mActivity.getFragmentManager().beginTransaction();
        if (mPreviousFragment != null) {
            if (DEBUG_FRAGMENTS) {
                Log.d(Logging.LOG_TAG, this + " showFragment: destroying previous fragment "
                        + mPreviousFragment);
            }
            removeFragment(ft, mPreviousFragment);
            mPreviousFragment = null;
        }
        // Remove or push the current one
        if (clearBackStack) {
            // Really remove the currently installed one.
            removeInstalledFragment(ft);
        }  else {
            // Instead of removing, detach the current one and push into our back stack.
            mPreviousFragment = getInstalledFragment();
            if (mPreviousFragment != null) {
                if (DEBUG_FRAGMENTS) {
                    Log.d(Logging.LOG_TAG, this + " showFragment: detaching " + mPreviousFragment);
                }
                ft.detach(mPreviousFragment);
            }
        }
        // Add the new one
        if (DEBUG_FRAGMENTS) {
            Log.d(Logging.LOG_TAG, this + " showFragment: adding " + fragment);
        }
        ft.add(R.id.fragment_placeholder, fragment);
        ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
        commitFragmentTransaction(ft);
    }

    /**
     * @param isSystemBackKey <code>true</code> if the system back key was pressed.
     *        <code>false</code> if it's caused by the "home" icon click on the action bar.
     * @return true if we should pop from our custom back stack.
     */
    private boolean shouldPopFromBackStack(boolean isSystemBackKey) {
        if (mPreviousFragment == null) {
            return false; // Nothing in the back stack
        }
        // Never go back to Message View
        if (mPreviousFragment instanceof MessageViewFragment) {
            return false;
        }
        final Fragment installed = getInstalledFragment();
        if (installed == null) {
            // If no fragment is installed right now, do nothing.
            return false;
        }

        // Okay now we have 2 fragments; the one in the back stack and the one that's currently
        // installed.
        if (mPreviousFragment.getClass() == installed.getClass()) {
            // We never want to go back to the same kind of fragment, which happens when the user
            // is on the message list, and selects another mailbox on the action bar.
            return false;
        }

        if (isSystemBackKey) {
            // In other cases, the system back key should always work.
            return true;
        } else {
            // Home icon press -- there are cases where we don't want it to work.

            // Disallow the Message list <-> mailbox list transition
            if ((mPreviousFragment instanceof MailboxListFragment)
                    && (installed  instanceof MessageListFragment)) {
                return false;
            }
            if ((mPreviousFragment instanceof MessageListFragment)
                    && (installed  instanceof MailboxListFragment)) {
                return false;
            }
            return true;
        }
    }

    /**
     * Pop from our custom back stack.
     *
     * TODO Delay-call the whole method and use the synchronous transaction.
     */
    private void popFromBackStack() {
        if (mPreviousFragment == null) {
            return;
        }
        final FragmentTransaction ft = mActivity.getFragmentManager().beginTransaction();
        final Fragment installed = getInstalledFragment();
        if (DEBUG_FRAGMENTS) {
            Log.i(Logging.LOG_TAG, this + " backstack: [pop] " + installed + " -> "
                    + mPreviousFragment);
        }
        removeFragment(ft, installed);
        ft.attach(mPreviousFragment);
        ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_CLOSE);
        commitFragmentTransaction(ft);
        mPreviousFragment = null;
        return;
    }

    private void showAllMailboxes() {
        if (!isAccountSelected()) {
            return; // Can happen because of asynchronous fragment transactions.
        }
        // Don't use open(account, NO_MAILBOX, NO_MESSAGE).  This is used to open the default
        // view, which is Inbox on the message list.  (There's actually no way to open the mainbox
        // list with open(long,long,long))
        showMailboxList(getUIAccountId(), Mailbox.NO_MAILBOX, false);
    }

    /*
     * STOPSHIP Remove this -- see the base class method.
     */
    @Override
    public long getMailboxSettingsMailboxId() {
        // Mailbox settings is still experimental, and doesn't have to work on the phone.
        Utility.showToast(mActivity, "STOPSHIP: Mailbox settings not supported on 1 pane");
        return Mailbox.NO_MAILBOX;
    }

    /*
     * STOPSHIP Remove this -- see the base class method.
     */
    @Override
    public long getSearchMailboxId() {
        // Search is still experimental, and doesn't have to work on the phone.
        Utility.showToast(mActivity, "STOPSHIP: Search not supported on 1 pane");
        return Mailbox.NO_MAILBOX;
    }

    @Override
    protected boolean isRefreshEnabled() {
        // Refreshable only when an actual account is selected, and message view isn't shown.
        // (i.e. only available on the mailbox list or the message view, but not on the combined
        // one)
        return isActualAccountSelected() && !isMessageViewVisible();
    }

    @Override
    public void onRefresh() {
        if (!isRefreshEnabled()) {
            return;
        }
        if (isMessageListVisible()) {
            mRefreshManager.refreshMessageList(getActualAccountId(), getMailboxId(), true);
        } else {
            mRefreshManager.refreshMailboxList(getActualAccountId());
        }
    }

    @Override
    protected boolean isRefreshInProgress() {
        if (!isRefreshEnabled()) {
            return false;
        }
        if (isMessageListVisible()) {
            return mRefreshManager.isMessageListRefreshing(getMailboxId());
        } else {
            return mRefreshManager.isMailboxListRefreshing(getActualAccountId());
        }
    }
}
