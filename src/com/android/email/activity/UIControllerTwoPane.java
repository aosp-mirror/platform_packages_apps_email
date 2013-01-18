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
import android.app.FragmentTransaction;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;

import com.android.email.Clock;
import com.android.email.Email;
import com.android.email.MessageListContext;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;

import java.util.Set;

/**
 * UI Controller for x-large devices.  Supports a multi-pane layout.
 *
 * Note: Always use {@link #commitFragmentTransaction} to operate fragment transactions,
 * so that we can easily switch between synchronous and asynchronous transactions.
 */
class UIControllerTwoPane extends UIControllerBase implements ThreePaneLayout.Callback {
    @VisibleForTesting
    static final int MAILBOX_REFRESH_MIN_INTERVAL = 30 * 1000; // in milliseconds

    @VisibleForTesting
    static final int INBOX_AUTO_REFRESH_MIN_INTERVAL = 10 * 1000; // in milliseconds

    // Other UI elements
    protected ThreePaneLayout mThreePane;

    private MessageCommandButtonView mMessageCommandButtons;

    private MessageCommandButtonView mInMessageCommandButtons;

    public UIControllerTwoPane(EmailActivity activity) {
        super(activity);
    }

    @Override
    public int getLayoutId() {
        return R.layout.email_activity_two_pane;
    }

    // ThreePaneLayoutCallback
    @Override
    public void onVisiblePanesChanged(int previousVisiblePanes) {
        // If the right pane is gone, remove the message view.
        final int visiblePanes = mThreePane.getVisiblePanes();

        if (((visiblePanes & ThreePaneLayout.PANE_RIGHT) == 0) &&
                ((previousVisiblePanes & ThreePaneLayout.PANE_RIGHT) != 0)) {
            // Message view just got hidden
            unselectMessage();
        }
        // Disable CAB when the message list is not visible.
        if (isMessageListInstalled()) {
            getMessageListFragment().onHidden((visiblePanes & ThreePaneLayout.PANE_MIDDLE) == 0);
        }
        refreshActionBar();
    }

    // MailboxListFragment$Callback
    @Override
    public void onMailboxSelected(long accountId, long mailboxId, boolean nestedNavigation) {
        setListContext(MessageListContext.forMailbox(accountId, mailboxId));
        if (getMessageListMailboxId() != mListContext.getMailboxId()) {
            updateMessageList(true);
        }
    }

    /**
     * Handles the {@link android.app.Activity#onCreateOptionsMenu} callback.
     */
    public boolean onCreateOptionsMenu(MenuInflater inflater, Menu menu) {
        int state = mThreePane.getPaneState();
        boolean handled = false;
        int menuId = -1;
        switch (state) {
            case ThreePaneLayout.STATE_LEFT_VISIBLE:
                MessageListFragment fragment = getMessageListFragment();
                MessageListContext context = fragment == null ? null : fragment.getListContext();
                if (context != null && context.isSearch()) {
                    menuId = R.menu.message_search_list_fragment_option;
                } else {
                    menuId = R.menu.message_list_fragment_option;
                }
                handled=  true;
                break;
            case ThreePaneLayout.STATE_MIDDLE_EXPANDED:
            case ThreePaneLayout.STATE_RIGHT_VISIBLE:
                menuId = R.menu.message_view_fragment_option;
                handled=  true;
                break;
        }
        if (menuId != -1) {
            inflater.inflate(menuId, menu);
        }
        return handled;
    }

    // MailboxListFragment$Callback
    @Override
    public void onAccountSelected(long accountId) {
        // It's from combined view, so "forceShowInbox" doesn't really matter.
        // (We're always switching accounts.)
        switchAccount(accountId, true);
    }

    // MailboxListFragment$Callback
    @Override
    public void onParentMailboxChanged() {
        refreshActionBar();
    }

    // MessageListFragment$Callback
    @Override
    public void onMessageOpen(long messageId, long messageMailboxId, long listMailboxId,
            int type) {
        if (type == MessageListFragment.Callback.TYPE_DRAFT) {
            MessageCompose.actionEditDraft(mActivity, messageId);
        } else {
            if (getMessageId() != messageId) {
                navigateToMessage(messageId);
                mThreePane.showRightPane();
            }
        }
    }

    // MessageListFragment$Callback
    /**
     * Apply the auto-advance policy upon initation of a batch command that could potentially
     * affect the currently selected conversation.
     */
    @Override
    public void onAdvancingOpAccepted(Set<Long> affectedMessages) {
        if (!isMessageViewInstalled()) {
            // Do nothing if message view is not visible.
            return;
        }

        final MessageOrderManager orderManager = getMessageOrderManager();
        int autoAdvanceDir = Preferences.getPreferences(mActivity).getAutoAdvanceDirection();
        if ((autoAdvanceDir == Preferences.AUTO_ADVANCE_MESSAGE_LIST) || (orderManager == null)) {
            if (affectedMessages.contains(getMessageId())) {
                goBackToMailbox();
            }
            return;
        }

        // Navigate to the first unselected item in the appropriate direction.
        switch (autoAdvanceDir) {
            case Preferences.AUTO_ADVANCE_NEWER:
                while (affectedMessages.contains(orderManager.getCurrentMessageId())) {
                    if (!orderManager.moveToNewer()) {
                        goBackToMailbox();
                        return;
                    }
                }
                navigateToMessage(orderManager.getCurrentMessageId());
                break;

            case Preferences.AUTO_ADVANCE_OLDER:
                while (affectedMessages.contains(orderManager.getCurrentMessageId())) {
                    if (!orderManager.moveToOlder()) {
                        goBackToMailbox();
                        return;
                    }
                }
                navigateToMessage(orderManager.getCurrentMessageId());
                break;
        }
    }

    // MessageListFragment$Callback
    @Override
    public boolean onDragStarted() {
        if (Email.DEBUG) {
            Log.i(Logging.LOG_TAG, "Drag started");
        }

        if (((mListContext != null) && mListContext.isSearch())
                || !mThreePane.isLeftPaneVisible()) {
            // D&D not allowed.
            return false;
        }

        return true;
    }

    // MessageListFragment$Callback
    @Override
    public void onDragEnded() {
        if (Email.DEBUG) {
            Log.i(Logging.LOG_TAG, "Drag ended");
        }
    }


    // MessageViewFragment$Callback
    @Override
    public boolean onUrlInMessageClicked(String url) {
        return ActivityHelper.openUrlInMessage(mActivity, url, getActualAccountId());
    }

    // MessageViewFragment$Callback
    @Override
    public void onLoadMessageStarted() {
    }

    // MessageViewFragment$Callback
    @Override
    public void onLoadMessageFinished() {
    }

    // MessageViewFragment$Callback
    @Override
    public void onLoadMessageError(String errorMessage) {
    }

    // MessageViewFragment$Callback
    @Override
    public void onCalendarLinkClicked(long epochEventStartTime) {
        ActivityHelper.openCalendar(mActivity, epochEventStartTime);
    }

    // MessageViewFragment$Callback
    @Override
    public void onForward() {
        MessageCompose.actionForward(mActivity, getMessageId());
    }

    // MessageViewFragment$Callback
    @Override
    public void onReply() {
        MessageCompose.actionReply(mActivity, getMessageId(), false);
    }

    // MessageViewFragment$Callback
    @Override
    public void onReplyAll() {
        MessageCompose.actionReply(mActivity, getMessageId(), true);
    }

    /**
     * Must be called just after the activity sets up the content view.
     */
    @Override
    public void onActivityViewReady() {
        super.onActivityViewReady();

        // Set up content
        mThreePane = (ThreePaneLayout) mActivity.findViewById(R.id.three_pane);
        mThreePane.setCallback(this);

        mMessageCommandButtons = mThreePane.getMessageCommandButtons();
        mMessageCommandButtons.setCallback(new CommandButtonCallback());
        mInMessageCommandButtons = mThreePane.getInMessageCommandButtons();
        mInMessageCommandButtons.setCallback(new CommandButtonCallback());
    }

    @Override
    protected ActionBarController createActionBarController(Activity activity) {
        return new ActionBarController(activity, activity.getLoaderManager(),
                activity.getActionBar(), new ActionBarControllerCallback());
    }

    /**
     * @return the currently selected account ID, *or* {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
     *
     * @see #getActualAccountId()
     */
    @Override
    public long getUIAccountId() {
        return isMailboxListInstalled() ? getMailboxListFragment().getAccountId()
                :Account.NO_ACCOUNT;
    }

    @Override
    public long getMailboxSettingsMailboxId() {
        return getMessageListMailboxId();
    }

    /**
     * @return true if refresh is in progress for the current mailbox.
     */
    @Override
    protected boolean isRefreshInProgress() {
        long messageListMailboxId = getMessageListMailboxId();
        return (messageListMailboxId >= 0)
                && mRefreshManager.isMessageListRefreshing(messageListMailboxId);
    }

    /**
     * @return true if the UI should enable the "refresh" command.
     */
    @Override
    protected boolean isRefreshEnabled() {
        return getActualAccountId() != Account.NO_ACCOUNT
                && (mListContext.getMailboxId() > 0);
    }


    /** {@inheritDoc} */
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    /** {@inheritDoc} */
    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
    }

    @Override
    protected void installMessageListFragment(MessageListFragment fragment) {
        super.installMessageListFragment(fragment);

        if (isMailboxListInstalled()) {
            getMailboxListFragment().setHighlightedMailbox(fragment.getMailboxId());
        }
        getMessageListFragment().setLayout(mThreePane);
        mThreePane.setIsSearch(getMessageListFragment().getListContext().isSearch());
    }

    @Override
    protected void installMessageViewFragment(MessageViewFragment fragment) {
        super.installMessageViewFragment(fragment);

        if (isMessageListInstalled()) {
            getMessageListFragment().setSelectedMessage(fragment.getMessageId());
        }
    }

    @Override
    public void openInternal(final MessageListContext listContext, final long messageId) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " open " + listContext);
        }

        final FragmentTransaction ft = mFragmentManager.beginTransaction();
        updateMailboxList(ft, true);
        updateMessageList(ft, true);

        if (messageId != Message.NO_MESSAGE) {
            updateMessageView(ft, messageId);
            mThreePane.showRightPane();
        } else if (mListContext.isSearch() && UiUtilities.showTwoPaneSearchResults(mActivity)) {
            mThreePane.showRightPane();
        } else {
            mThreePane.showLeftPane();
        }
        commitFragmentTransaction(ft);
    }

    /**
     * Loads the given account and optionally selects the given mailbox and message. If the
     * specified account is already selected, no actions will be performed unless
     * <code>forceReload</code> is <code>true</code>.
     *
     * @param ft {@link FragmentTransaction} to use.
     * @param clearDependentPane if true, the message list and the message view will be cleared
     */
    private void updateMailboxList(FragmentTransaction ft, boolean clearDependentPane) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " updateMailboxList " + mListContext);
        }

        long accountId = mListContext.mAccountId;
        long mailboxId = mListContext.getMailboxId();
        if ((getUIAccountId() != accountId) || (getMailboxListMailboxId() != mailboxId)) {
            removeMailboxListFragment(ft);
            boolean enableHighlight = !mListContext.isSearch();
            ft.add(mThreePane.getLeftPaneId(),
                    MailboxListFragment.newInstance(accountId, mailboxId, enableHighlight));
        }
        if (clearDependentPane) {
            removeMessageListFragment(ft);
            removeMessageViewFragment(ft);
        }
    }

    /**
     * Go back to a mailbox list view. If a message view is currently active, it will
     * be hidden.
     */
    private void goBackToMailbox() {
        if (isMessageViewInstalled()) {
            mThreePane.showLeftPane(); // Show mailbox list
        }
    }

    /**
     * Show the message list fragment for the given mailbox.
     *
     * @param ft {@link FragmentTransaction} to use.
     */
    private void updateMessageList(FragmentTransaction ft, boolean clearDependentPane) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " updateMessageList " + mListContext);
        }

        if (mListContext.getMailboxId() != getMessageListMailboxId()) {
            removeMessageListFragment(ft);
            ft.add(mThreePane.getMiddlePaneId(), MessageListFragment.newInstance(mListContext));
        }
        if (clearDependentPane) {
            removeMessageViewFragment(ft);
        }
    }

    /**
     * Shortcut to call {@link #updateMessageList(FragmentTransaction, boolean)} and
     * commit.
     */
    private void updateMessageList(boolean clearDependentPane) {
        FragmentTransaction ft = mFragmentManager.beginTransaction();
        updateMessageList(ft, clearDependentPane);
        commitFragmentTransaction(ft);
    }

    /**
     * Show a message on the message view.
     *
     * @param ft {@link FragmentTransaction} to use.
     * @param messageId ID of the mailbox to load. Must never be {@link Message#NO_MESSAGE}.
     */
    private void updateMessageView(FragmentTransaction ft, long messageId) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " updateMessageView messageId=" + messageId);
        }
        if (messageId == Message.NO_MESSAGE) {
            throw new IllegalArgumentException();
        }

        if (messageId == getMessageId()) {
            return; // nothing to do.
        }

        removeMessageViewFragment(ft);

        ft.add(mThreePane.getRightPaneId(), MessageViewFragment.newInstance(messageId));
    }

    /**
     * Shortcut to call {@link #updateMessageView(FragmentTransaction, long)} and commit.
     */
    @Override protected void navigateToMessage(long messageId) {
        FragmentTransaction ft = mFragmentManager.beginTransaction();
        updateMessageView(ft, messageId);
        commitFragmentTransaction(ft);
    }

    /**
     * Remove the message view if shown.
     */
    private void unselectMessage() {
        commitFragmentTransaction(removeMessageViewFragment(mFragmentManager.beginTransaction()));
        if (isMessageListInstalled()) {
            getMessageListFragment().setSelectedMessage(Message.NO_MESSAGE);
        }
        stopMessageOrderManager();
    }

    private class CommandButtonCallback implements MessageCommandButtonView.Callback {
        @Override
        public void onMoveToNewer() {
            moveToNewer();
        }

        @Override
        public void onMoveToOlder() {
            moveToOlder();
        }
    }

    /**
     * Disable/enable the move-to-newer/older buttons.
     */
    @Override protected void updateNavigationArrows() {
        final MessageOrderManager orderManager = getMessageOrderManager();
        if (orderManager == null) {
            // shouldn't happen, but just in case
            mMessageCommandButtons.enableNavigationButtons(false, false, 0, 0);
            mInMessageCommandButtons.enableNavigationButtons(false, false, 0, 0);
        } else {
            mMessageCommandButtons.enableNavigationButtons(
                    orderManager.canMoveToNewer(), orderManager.canMoveToOlder(),
                    orderManager.getCurrentPosition(), orderManager.getTotalMessageCount());
            mInMessageCommandButtons.enableNavigationButtons(
                    orderManager.canMoveToNewer(), orderManager.canMoveToOlder(),
                    orderManager.getCurrentPosition(), orderManager.getTotalMessageCount());
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean onBackPressed(boolean isSystemBackKey) {
        if (!mThreePane.isPaneCollapsible()) {
            if (mActionBarController.onBackPressed(isSystemBackKey)) {
                return true;
            }

            if (mThreePane.showLeftPane()) {
                return true;
            }
        }

        if (isMailboxListInstalled() && getMailboxListFragment().navigateUp()) {
            return true;
        }
        return false;
    }

    @Override
    protected void onRefresh() {
        // Cancel previously running instance if any.
        new RefreshTask(mTaskTracker, mActivity, getActualAccountId(),
                getMessageListMailboxId()).cancelPreviousAndExecuteParallel();
    }

    /**
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
    @VisibleForTesting
    static class RefreshTask extends EmailAsyncTask<Void, Void, Boolean> {
        private final Clock mClock;
        private final Context mContext;
        private final long mAccountId;
        private final long mMailboxId;
        private final RefreshManager mRefreshManager;
        @VisibleForTesting
        long mInboxId;

        public RefreshTask(EmailAsyncTask.Tracker tracker, Context context, long accountId,
                long mailboxId) {
            this(tracker, context, accountId, mailboxId, Clock.INSTANCE,
                    RefreshManager.getInstance(context));
        }

        @VisibleForTesting
        RefreshTask(EmailAsyncTask.Tracker tracker, Context context, long accountId,
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
        protected void onSuccess(Boolean isCurrentMailboxRefreshable) {
            if (isCurrentMailboxRefreshable == null) {
                return;
            }
            if (isCurrentMailboxRefreshable) {
                mRefreshManager.refreshMessageList(mAccountId, mMailboxId, true);
            }
            // Refresh mailbox list
            if (mAccountId != Account.NO_ACCOUNT) {
                if (shouldRefreshMailboxList()) {
                    mRefreshManager.refreshMailboxList(mAccountId);
                }
            }
            // Refresh inbox
            if (shouldAutoRefreshInbox()) {
                mRefreshManager.refreshMessageList(mAccountId, mInboxId, true);
            }
        }

        /**
         * @return true if the mailbox list of the current account hasn't been refreshed
         * in the last {@link #MAILBOX_REFRESH_MIN_INTERVAL}.
         */
        @VisibleForTesting
        boolean shouldRefreshMailboxList() {
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
        @VisibleForTesting
        boolean shouldAutoRefreshInbox() {
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

    private class ActionBarControllerCallback implements ActionBarController.Callback {

        @Override
        public long getUIAccountId() {
            return UIControllerTwoPane.this.getUIAccountId();
        }

        @Override
        public long getMailboxId() {
            return getMessageListMailboxId();
        }

        @Override
        public boolean isAccountSelected() {
            return UIControllerTwoPane.this.isAccountSelected();
        }

        @Override
        public void onAccountSelected(long accountId) {
            switchAccount(accountId, false);
        }

        @Override
        public void onMailboxSelected(long accountId, long mailboxId) {
            openMailbox(accountId, mailboxId);
        }

        @Override
        public void onNoAccountsFound() {
            Welcome.actionStart(mActivity);
            mActivity.finish();
        }

        @Override
        public int getTitleMode() {
            if (mThreePane.isLeftPaneVisible()) {
                // Mailbox list visible
                return TITLE_MODE_ACCOUNT_NAME_ONLY;
            } else if (mThreePane.isRightPaneVisible()
                    && !mThreePane.isMiddlePaneVisible()) {
                return TITLE_MODE_MESSAGE_SUBJECT;
            } else {
                // Mailbox list hidden
                return TITLE_MODE_ACCOUNT_WITH_MAILBOX;
            }
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
            final int visiblePanes = mThreePane.getVisiblePanes();
            final boolean leftPaneHidden = ((visiblePanes & ThreePaneLayout.PANE_LEFT) == 0);
            return leftPaneHidden
                    || (isMailboxListInstalled() && getMailboxListFragment().canNavigateUp());
        }

        @Override
        public String getSearchHint() {
            return UIControllerTwoPane.this.getSearchHint();
        }

        @Override
        public void onSearchStarted() {
            UIControllerTwoPane.this.onSearchStarted();
        }

        @Override
        public void onSearchSubmit(final String queryTerm) {
            UIControllerTwoPane.this.onSearchSubmit(queryTerm);
        }

        @Override
        public void onSearchExit() {
            UIControllerTwoPane.this.onSearchExit();
        }

        @Override
        public void onUpPressed() {
            onBackPressed(false);
        }
    }
}
