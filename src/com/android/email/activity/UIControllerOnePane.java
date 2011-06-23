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
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.util.Log;

import com.android.email.Email;
import com.android.email.MessageListContext;
import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;

import java.util.Set;


/**
 * UI Controller for non x-large devices.  Supports a single-pane layout.
 *
 * One one-pane, only at most one fragment can be installed at a time.
 *
 * Note: Always use {@link #commitFragmentTransaction} to operate fragment transactions,
 * so that we can easily switch between synchronous and asynchronous transactions.
 *
 * Major TODOs
 * - TODO Newer/Older for message view
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
        switchAccount(getUIAccountId());
    }

    // MessageListFragment.Callback
    @Override
    public void onMessageOpen(
            long messageId, long messageMailboxId, long listMailboxId, int type) {
        if (type == MessageListFragment.Callback.TYPE_DRAFT) {
            MessageCompose.actionEditDraft(mActivity, messageId);
        } else {
            open(mListContext, messageId);
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
            return isMessageViewInstalled()
                     || (isMailboxListInstalled() && !getMailboxListFragment().isRoot());
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

        @Override
        public void onSearchSubmit(String queryTerm) {
            if (!isMessageListInstalled()) {
                return;
            }
            UIControllerOnePane.this.onSearchSubmit(queryTerm);
        }

        @Override
        public void onSearchExit() {
            UIControllerOnePane.this.onSearchExit();
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
            mFragmentManager.putFragment(outState,
                    BUNDLE_KEY_PREVIOUS_FRAGMENT, mPreviousFragment);
        }
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mPreviousFragment = mFragmentManager.getFragment(savedInstanceState,
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
    public long getUIAccountId() {
        // Get it from the visible fragment.
        if (isMailboxListInstalled()) {
            return getMailboxListFragment().getAccountId();
        }
        if (isMessageListInstalled()) {
            return getMessageListFragment().getAccountId();
        }
        if (isMessageViewInstalled()) {
            return getMessageViewFragment().getOpenerAccountId();
        }
        return Account.NO_ACCOUNT;
    }

    private long getMailboxId() {
        // Get it from the visible fragment.
        if (isMessageListInstalled()) {
            return getMessageListFragment().getMailboxId();
        }
        if (isMessageViewInstalled()) {
            return getMessageViewFragment().getOpenerMailboxId();
        }
        return Mailbox.NO_MAILBOX;
    }

    @Override
    public boolean onBackPressed(boolean isSystemBackKey) {
        if (Email.DEBUG) {
            // This is VERY important -- no check for DEBUG_LIFECYCLE
            Log.d(Logging.LOG_TAG, this + " onBackPressed: " + isSystemBackKey);
        }
        // Super's method has precedence.  Must call it first.
        if (super.onBackPressed(isSystemBackKey)) {
            return true;
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
    public void openInternal(final MessageListContext listContext, final long messageId) {
        if (Email.DEBUG) {
            // This is VERY important -- don't check for DEBUG_LIFECYCLE
            Log.i(Logging.LOG_TAG, this + " open " + listContext + " messageId=" + messageId);
        }

        final boolean accountChanging = (getUIAccountId() != listContext.mAccountId);
        if (messageId != Message.NO_MESSAGE) {
            showMessageView(messageId, accountChanging);
        } else {
            showMessageList(listContext.mAccountId, listContext.getMailboxId(), accountChanging);
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

    private void showMessageView(long messageId, boolean clearBackStack) {
        long accountId = mListContext.mAccountId;
        long mailboxId = mListContext.getMailboxId();
        showFragment(MessageViewFragment.newInstance(accountId, mailboxId, messageId),
                clearBackStack);
    }

    /**
     * Use this instead of {@link FragmentTransaction#commit}.  We may switch to the asynchronous
     * transaction some day.
     */
    private void commitFragmentTransaction(FragmentTransaction ft) {
        if (!ft.isEmpty()) {
            ft.commit();
            mFragmentManager.executePendingTransactions();
        }
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
        final FragmentTransaction ft = mFragmentManager.beginTransaction();
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
        final FragmentTransaction ft = mFragmentManager.beginTransaction();
        final Fragment installed = getInstalledFragment();
        if (DEBUG_FRAGMENTS) {
            Log.i(Logging.LOG_TAG, this + " backstack: [pop] " + installed + " -> "
                    + mPreviousFragment);
        }
        removeFragment(ft, installed);
        ft.attach(mPreviousFragment);
        ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_CLOSE);
        mPreviousFragment = null;
        commitFragmentTransaction(ft);
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
        return isMessageListInstalled()
                ? getMessageListFragment().getMailboxId()
                : Mailbox.NO_MAILBOX;
    }

    @Override
    protected boolean canSearch() {
        return isMessageListInstalled();
    }

    @Override
    protected boolean isRefreshEnabled() {
        // Refreshable only when an actual account is selected, and message view isn't shown.
        // (i.e. only available on the mailbox list or the message view, but not on the combined
        // one)
        return isActualAccountSelected() && !isMessageViewInstalled();
    }

    @Override
    public void onRefresh() {
        if (!isRefreshEnabled()) {
            return;
        }
        if (isMessageListInstalled()) {
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
        if (isMessageListInstalled()) {
            return mRefreshManager.isMessageListRefreshing(getMailboxId());
        } else {
            return mRefreshManager.isMailboxListRefreshing(getActualAccountId());
        }
    }
}
