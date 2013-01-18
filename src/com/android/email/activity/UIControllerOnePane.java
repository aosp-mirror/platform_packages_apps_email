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
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

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
        // It's from combined view, so "forceShowInbox" doesn't really matter.
        // (We're always switching accounts.)
        switchAccount(accountId, true);
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

    private boolean isInboxShown() {
        if (!isMessageListInstalled()) {
            return false;
        }
        return getMessageListFragment().isInboxList();
    }

    // This is all temporary as we'll have a different action bar controller for 1-pane.
    private class ActionBarControllerCallback implements ActionBarController.Callback {
        @Override
        public int getTitleMode() {
            if (isMailboxListInstalled()) {
                return TITLE_MODE_ACCOUNT_WITH_ALL_FOLDERS_LABEL;
            }
            if (isMessageViewInstalled()) {
                return TITLE_MODE_MESSAGE_SUBJECT;
            }
            return TITLE_MODE_ACCOUNT_WITH_MAILBOX;
        }

        public String getMessageSubject() {
            if (isMessageViewInstalled() && getMessageViewFragment().isMessageOpen()) {
                return getMessageViewFragment().getMessage().mSubject;
            } else {
                return null;
            }
        }

        @Override
        public boolean shouldShowUp() {
            return isMessageViewInstalled()
                    || (isMessageListInstalled() && !isInboxShown())
                    || isMailboxListInstalled();
        }

        @Override
        public long getUIAccountId() {
            return UIControllerOnePane.this.getUIAccountId();
        }

        @Override
        public long getMailboxId() {
            return UIControllerOnePane.this.getMailboxId();
        }

        @Override
        public void onMailboxSelected(long accountId, long mailboxId) {
            if (mailboxId == Mailbox.NO_MAILBOX) {
                showAllMailboxes();
            } else {
                openMailbox(accountId, mailboxId);
            }
        }

        @Override
        public boolean isAccountSelected() {
            return UIControllerOnePane.this.isAccountSelected();
        }

        @Override
        public void onAccountSelected(long accountId) {
            switchAccount(accountId, true); // Always go to inbox
        }

        @Override
        public void onNoAccountsFound() {
            Welcome.actionStart(mActivity);
            mActivity.finish();
        }

        @Override
        public String getSearchHint() {
            if (!isMessageListInstalled()) {
                return null;
            }
            return UIControllerOnePane.this.getSearchHint();
        }

        @Override
        public void onSearchStarted() {
            if (!isMessageListInstalled()) {
                return;
            }
            UIControllerOnePane.this.onSearchStarted();
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

        @Override
        public void onUpPressed() {
            onBackPressed(false);
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
    public long getUIAccountId() {
        if (mListContext != null) {
            return mListContext.mAccountId;
        }
        if (isMailboxListInstalled()) {
            return getMailboxListFragment().getAccountId();
        }
        return Account.NO_ACCOUNT;
    }

    private long getMailboxId() {
        if (mListContext != null) {
            return mListContext.getMailboxId();
        }
        return Mailbox.NO_MAILBOX;
    }

    @Override
    public boolean onBackPressed(boolean isSystemBackKey) {
        if (Email.DEBUG) {
            // This is VERY important -- no check for DEBUG_LIFECYCLE
            Log.d(Logging.LOG_TAG, this + " onBackPressed: " + isSystemBackKey);
        }
        // The action bar controller has precedence.  Must call it first.
        if (mActionBarController.onBackPressed(isSystemBackKey)) {
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
        if (isMessageViewInstalled()) {
            if (DEBUG_FRAGMENTS) {
                Log.d(Logging.LOG_TAG, this + " Back: Message view -> Message List");
            }
            // If the message view is shown, show the "parent" message list.
            // This happens when we get a deep link to a message.  (e.g. from a widget)
            openMailbox(mListContext.mAccountId, mListContext.getMailboxId());
            return true;
        } else if (isMailboxListInstalled()) {
            // If the mailbox list is shown, always go back to the inbox.
            switchAccount(getMailboxListFragment().getAccountId(), true /* force show inbox */);
            return true;
        } else if (isMessageListInstalled() && !isInboxShown()) {
            // Non-inbox list. Go to inbox.
            switchAccount(mListContext.mAccountId, true /* force show inbox */);
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

        if (messageId != Message.NO_MESSAGE) {
            openMessage(messageId);
        } else {
            showFragment(MessageListFragment.newInstance(listContext));
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
     * Show the mailbox list.
     *
     * This is the only way to open the mailbox list on 1-pane.
     * {@link #open(MessageListContext, long)} will only open either the message list or the
     * message view.
     */
    private void openMailboxList(long accountId) {
        setListContext(null);
        showFragment(MailboxListFragment.newInstance(accountId, Mailbox.NO_MAILBOX, false));
    }

    private void openMessage(long messageId) {
        showFragment(MessageViewFragment.newInstance(messageId));
    }

    /**
     * Push the installed fragment into our custom back stack (or optionally
     * {@link FragmentTransaction#remove} it) and {@link FragmentTransaction#add} {@code fragment}.
     *
     * @param fragment {@link Fragment} to be added.
     *
     *  TODO Delay-call the whole method and use the synchronous transaction.
     */
    private void showFragment(Fragment fragment) {
        final FragmentTransaction ft = mFragmentManager.beginTransaction();
        final Fragment installed = getInstalledFragment();
        if ((installed instanceof MessageViewFragment)
                && (fragment instanceof MessageViewFragment)) {
            // Newer/older navigation, auto-advance, etc.
            // In this case we want to keep the backstack untouched, so that after back navigation
            // we can restore the message list, including scroll position and batch selection.
        } else {
            if (DEBUG_FRAGMENTS) {
                Log.i(Logging.LOG_TAG, this + " backstack: [push] " + getInstalledFragment()
                        + " -> " + fragment);
            }
            if (mPreviousFragment != null) {
                if (DEBUG_FRAGMENTS) {
                    Log.d(Logging.LOG_TAG, this + " showFragment: destroying previous fragment "
                            + mPreviousFragment);
                }
                removeFragment(ft, mPreviousFragment);
                mPreviousFragment = null;
            }
            // Remove the current fragment or push it into the backstack.
            if (installed != null) {
                if (installed instanceof MessageViewFragment) {
                    // Message view should never be pushed to the backstack.
                    if (DEBUG_FRAGMENTS) {
                        Log.d(Logging.LOG_TAG, this + " showFragment: removing " + installed);
                    }
                    ft.remove(installed);
                } else {
                    // Other fragments should be pushed.
                    mPreviousFragment = installed;
                    if (DEBUG_FRAGMENTS) {
                        Log.d(Logging.LOG_TAG, this + " showFragment: detaching "
                                + mPreviousFragment);
                    }
                    ft.detach(mPreviousFragment);
                }
            }
        }
        // Show the new one
        if (DEBUG_FRAGMENTS) {
            Log.d(Logging.LOG_TAG, this + " showFragment: replacing with " + fragment);
        }
        ft.replace(R.id.fragment_placeholder, fragment);
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
        if (mPreviousFragment instanceof MessageViewFragment) {
            throw new IllegalStateException("Message view should never be in backstack");
        }
        final Fragment installed = getInstalledFragment();
        if (installed == null) {
            // If no fragment is installed right now, do nothing.
            return false;
        }

        // Okay now we have 2 fragments; the one in the back stack and the one that's currently
        // installed.
        if (isInboxShown()) {
            // Inbox is the top level list - never go back from here.
            return false;
        }

        // Disallow the MailboxList--> non-inbox MessageList transition as the Mailbox list
        // is always considered "higher" than a non-inbox MessageList
        if ((mPreviousFragment instanceof MessageListFragment)
                && (!((MessageListFragment) mPreviousFragment).isInboxList())
                && (installed  instanceof MailboxListFragment)) {
            return false;
        }
        return true;
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

        // Restore listContext.
        if (mPreviousFragment instanceof MailboxListFragment) {
            setListContext(null);
        } else if (mPreviousFragment instanceof MessageListFragment) {
            setListContext(((MessageListFragment) mPreviousFragment).getListContext());
        } else {
            throw new IllegalStateException("Message view should never be in backstack");
        }

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

        openMailboxList(getUIAccountId());
    }

    @Override
    protected void installMailboxListFragment(MailboxListFragment fragment) {
        stopMessageOrderManager();
        super.installMailboxListFragment(fragment);
    }

    @Override
    protected void installMessageListFragment(MessageListFragment fragment) {
        stopMessageOrderManager();
        super.installMessageListFragment(fragment);
    }

    @Override
    protected long getMailboxSettingsMailboxId() {
        return isMessageListInstalled()
                ? getMessageListFragment().getMailboxId()
                : Mailbox.NO_MAILBOX;
    }

    /**
     * Handles the {@link android.app.Activity#onCreateOptionsMenu} callback.
     */
    public boolean onCreateOptionsMenu(MenuInflater inflater, Menu menu) {
        if (isMessageListInstalled()) {
            inflater.inflate(R.menu.message_list_fragment_option, menu);
            return true;
        }
        if (isMessageViewInstalled()) {
            inflater.inflate(R.menu.message_view_fragment_option, menu);
            return true;
        }
        return false;
    }

    @Override
    public boolean onPrepareOptionsMenu(MenuInflater inflater, Menu menu) {
        // First, let the base class do what it has to do.
        super.onPrepareOptionsMenu(inflater, menu);

        final boolean messageViewVisible = isMessageViewInstalled();
        if (messageViewVisible) {
            final MessageOrderManager om = getMessageOrderManager();
            menu.findItem(R.id.newer).setVisible(true);
            menu.findItem(R.id.older).setVisible(true);
            // orderManager shouldn't be null when the message view is installed, but just in case..
            menu.findItem(R.id.newer).setEnabled((om != null) && om.canMoveToNewer());
            menu.findItem(R.id.older).setEnabled((om != null) && om.canMoveToOlder());
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.newer:
                moveToNewer();
                return true;
            case R.id.older:
                moveToOlder();
                return true;
            case R.id.show_all_mailboxes:
                showAllMailboxes();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected boolean isRefreshEnabled() {
        // Refreshable only when an actual account is selected, and message view isn't shown.
        // (i.e. only available on the mailbox list or the message view, but not on the combined
        // one)
        if (!isActualAccountSelected() || isMessageViewInstalled()) {
            return false;
        }
        return isMailboxListInstalled() || (mListContext.getMailboxId() > 0);
    }

    @Override
    protected void onRefresh() {
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

    @Override protected void navigateToMessage(long messageId) {
        openMessage(messageId);
    }

    @Override protected void updateNavigationArrows() {
        refreshActionBar();
    }
}
