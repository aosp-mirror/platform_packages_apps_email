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

import com.android.email.Clock;
import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.google.common.annotations.VisibleForTesting;

import java.util.Set;

/**
 * UI Controller for x-large devices.  Supports a multi-pane layout.
 *
 * Note: Always use {@link #commitFragmentTransaction} to operate fragment transactions,
 * so that we can easily switch between synchronous and asynchronous transactions.
 */
class UIControllerTwoPane extends UIControllerBase implements
        ThreePaneLayout.Callback,
        MailboxListFragment.Callback,
        MessageListFragment.Callback,
        MessageViewFragment.Callback {
    @VisibleForTesting
    static final int MAILBOX_REFRESH_MIN_INTERVAL = 30 * 1000; // in milliseconds

    @VisibleForTesting
    static final int INBOX_AUTO_REFRESH_MIN_INTERVAL = 10 * 1000; // in milliseconds

    // Other UI elements
    private ThreePaneLayout mThreePane;

    private MessageCommandButtonView mMessageCommandButtons;

    private MessageOrderManager mOrderManager;
    private final MessageOrderManagerCallback mMessageOrderManagerCallback =
        new MessageOrderManagerCallback();

    /**
     * The mailbox name selected on the mailbox list.
     * Passed via {@link #onCurrentMailboxUpdated}.
     */
    private String mCurrentMailboxName;

    /**
     * The unread count for the mailbox selected on the mailbox list.
     * Passed via {@link #onCurrentMailboxUpdated}.
     *
     * 0 if the mailbox doesn't have the concept of "unread".  e.g. Drafts.
     */
    private int mCurrentMailboxUnreadCount;

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
        refreshActionBar();

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
    }

    // MailboxListFragment$Callback
    @Override
    public void onMailboxSelected(long accountId, long mailboxId, boolean nestedNavigation) {
        if ((accountId == Account.NO_ACCOUNT) || (mailboxId == Mailbox.NO_MAILBOX)) {
            throw new IllegalArgumentException();
        }
        if (getMessageListMailboxId() != mailboxId) {
            updateMessageList(accountId, mailboxId, true);
        }
    }

    // MailboxListFragment$Callback
    @Override
    public void onAccountSelected(long accountId) {
        switchAccount(accountId);
    }

    // MailboxListFragment$Callback
    @Override
    public void onCurrentMailboxUpdated(long mailboxId, String mailboxName, int unreadCount) {
        mCurrentMailboxName = mailboxName;
        mCurrentMailboxUnreadCount = unreadCount;
        refreshActionBar();
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
                updateMessageView(messageId);
                mThreePane.showRightPane();
            }
        }
    }

    // MessageListFragment$Callback
    @Override
    public void onMailboxNotFound() {
        Log.e(Logging.LOG_TAG, "unable to find mailbox");
    }

    // MessageListFragment$Callback
    @Override
    public void onEnterSelectionMode(boolean enter) {
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

        int autoAdvanceDir = Preferences.getPreferences(mActivity).getAutoAdvanceDirection();
        if ((autoAdvanceDir == Preferences.AUTO_ADVANCE_MESSAGE_LIST) || (mOrderManager == null)) {
            if (affectedMessages.contains(getMessageId())) {
                goBackToMailbox();
            }
            return;
        }

        // Navigate to the first unselected item in the appropriate direction.
        switch (autoAdvanceDir) {
            case Preferences.AUTO_ADVANCE_NEWER:
                while (affectedMessages.contains(mOrderManager.getCurrentMessageId())) {
                    if (!mOrderManager.moveToNewer()) {
                        goBackToMailbox();
                        return;
                    }
                }
                updateMessageView(mOrderManager.getCurrentMessageId());
                break;

            case Preferences.AUTO_ADVANCE_OLDER:
                while (affectedMessages.contains(mOrderManager.getCurrentMessageId())) {
                    if (!mOrderManager.moveToOlder()) {
                        goBackToMailbox();
                        return;
                    }
                }
                updateMessageView(mOrderManager.getCurrentMessageId());
                break;
        }
    }

    // MessageListFragment$Callback
    @Override
    public void onListLoaded() {
    }

    // MessageListFragment$Callback
    @Override
    public boolean onDragStarted() {
        Log.w(Logging.LOG_TAG, "Drag started");

        if ((mThreePane.getVisiblePanes() & ThreePaneLayout.PANE_LEFT) == 0) {
            // Mailbox list hidden.  D&D not allowed.
            return false;
        }

        // STOPSHIP Save the current mailbox list

        return true;
    }

    // MessageListFragment$Callback
    @Override
    public void onDragEnded() {
        Log.w(Logging.LOG_TAG, "Drag ended");

        // STOPSHIP Restore the saved mailbox list
    }

    // MessageViewFragment$Callback
    @Override
    public void onMessageShown() {
        updateMessageOrderManager();
        updateNavigationArrows();
    }

    // MessageViewFragment$Callback
    @Override
    public boolean onUrlInMessageClicked(String url) {
        return ActivityHelper.openUrlInMessage(mActivity, url, getActualAccountId());
    }

    // MessageViewFragment$Callback
    @Override
    public void onMessageSetUnread() {
        goBackToMailbox();
    }

    // MessageViewFragment$Callback
    @Override
    public void onMessageNotExists() {
        goBackToMailbox();
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
    public void onRespondedToInvite(int response) {
        onCurrentMessageGone();
    }

    // MessageViewFragment$Callback
    @Override
    public void onCalendarLinkClicked(long epochEventStartTime) {
        ActivityHelper.openCalendar(mActivity, epochEventStartTime);
    }

    // MessageViewFragment$Callback
    @Override
    public void onBeforeMessageGone() {
        onCurrentMessageGone();
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

    /**
     * Returns the id of the parent mailbox used for the mailbox list fragment.
     *
     * IMPORTANT: Do not confuse {@link #getMailboxListMailboxId()} with
     *     {@link #getMessageListMailboxId()}
     */
    private long getMailboxListMailboxId() {
        return isMailboxListInstalled() ? getMailboxListFragment().getSelectedMailboxId()
                : Mailbox.NO_MAILBOX;
    }

    /**
     * Returns the id of the mailbox used for the message list fragment.
     *
     * IMPORTANT: Do not confuse {@link #getMailboxListMailboxId()} with
     *     {@link #getMessageListMailboxId()}
     */
    private long getMessageListMailboxId() {
        return isMessageListInstalled() ? getMessageListFragment().getMailboxId()
                : Mailbox.NO_MAILBOX;
    }

    /*
     * STOPSHIP Remove this -- see the base class method.
     */
    @Override
    public long getMailboxSettingsMailboxId() {
        return getMessageListMailboxId();
    }

    private long getMessageId() {
        return isMessageViewInstalled() ? getMessageViewFragment().getMessageId()
                : Message.NO_MESSAGE;
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
        // - Don't show for combined inboxes, but
        // - Show even for non-refreshable mailboxes, in which case we refresh the mailbox list
        return getActualAccountId() != Account.NO_ACCOUNT;
    }

    /**
     * Called by the host activity at the end of {@link Activity#onCreate}.
     */
    @Override
    public void onActivityCreated() {
        super.onActivityCreated();
    }

    /** {@inheritDoc} */
    @Override
    public void onActivityStart() {
        super.onActivityStart();
        if (isMessageViewInstalled()) {
            updateMessageOrderManager();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onActivityResume() {
        super.onActivityResume();
        refreshActionBar();
    }

    /** {@inheritDoc} */
    @Override
    public void onActivityPause() {
        super.onActivityPause();
    }

    /** {@inheritDoc} */
    @Override
    public void onActivityStop() {
        stopMessageOrderManager();
        super.onActivityStop();
    }

    /** {@inheritDoc} */
    @Override
    public void onActivityDestroy() {
        super.onActivityDestroy();
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
    }

    @Override
    protected void installMessageViewFragment(MessageViewFragment fragment) {
        super.installMessageViewFragment(fragment);

        if (isMessageListInstalled()) {
            getMessageListFragment().setSelectedMessage(fragment.getMessageId());
        }
    }

    @Override
    protected void uninstallMessageViewFragment() {
        // Don't need it when there's no message view.
        stopMessageOrderManager();
        super.uninstallMessageViewFragment();
    }

    /**
     * Commit a {@link FragmentTransaction}.
     */
    private void commitFragmentTransaction(FragmentTransaction ft) {
        if (DEBUG_FRAGMENTS) {
            Log.d(Logging.LOG_TAG, this + " commitFragmentTransaction: " + ft);
        }
        if (!ft.isEmpty()) {
            ft.commit();
            mFragmentManager.executePendingTransactions();
        }
    }

    @Override
    public void open(long accountId, long mailboxId, long messageId) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " open accountId=" + accountId
                    + " mailboxId=" + mailboxId + " messageId=" + messageId);
        }
        final FragmentTransaction ft = mFragmentManager.beginTransaction();
        if (accountId == Account.NO_ACCOUNT) {
            throw new IllegalArgumentException();
        } else if (messageId == Message.NO_MESSAGE) {
            updateMailboxList(ft, accountId, mailboxId, true);
            updateMessageList(ft, accountId, mailboxId, true);

            // TODO: This is a total hack. Do something smarter to see if this should open a
            // search view.
            if (mailboxId == Controller.getInstance(mActivity).getSearchMailbox(accountId).mId) {
                mThreePane.showRightPane();
            } else {
                mThreePane.showLeftPane();
            }
        } else {
            if (mailboxId == Mailbox.NO_MAILBOX) {
                Log.e(Logging.LOG_TAG, this + " unspecified mailbox ");
                return;
            }
            updateMailboxList(ft, accountId, mailboxId, true);
            updateMessageList(ft, accountId, mailboxId, true);
            updateMessageView(ft, messageId);

            mThreePane.showRightPane();
        }
        commitFragmentTransaction(ft);
    }

    /**
     * Loads the given account and optionally selects the given mailbox and message. If the
     * specified account is already selected, no actions will be performed unless
     * <code>forceReload</code> is <code>true</code>.
     *
     * @param ft {@link FragmentTransaction} to use.
     * @param accountId ID of the account to load. Must never be {@link Account#NO_ACCOUNT}.
     * @param mailboxId ID of the mailbox to use as the "selected".  Pass
     *     {@link Mailbox#NO_MAILBOX} to show the root mailboxes.
     * @param clearDependentPane if true, the message list and the message view will be cleared
     */
    private void updateMailboxList(FragmentTransaction ft,
            long accountId, long mailboxId, boolean clearDependentPane) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " updateMailboxList accountId=" + accountId
                    + " mailboxId=" + mailboxId);
        }
        if (accountId == Account.NO_ACCOUNT) {
            throw new IllegalArgumentException();
        }

        if ((getUIAccountId() != accountId) || (getMailboxListMailboxId() != mailboxId)) {
            removeMailboxListFragment(ft);
            ft.add(mThreePane.getLeftPaneId(),
                    MailboxListFragment.newInstance(accountId, mailboxId, true));
        }
        if (clearDependentPane) {
            removeMessageListFragment(ft);
            removeMessageViewFragment(ft);
        }
    }

    /**
     * Shortcut to call {@link #updateMailboxList(FragmentTransaction, long, long, boolean)} and
     * commit.
     */
    @SuppressWarnings("unused")
    private void updateMailboxList(long accountId, long mailboxId, boolean clearDependentPane) {
        FragmentTransaction ft = mFragmentManager.beginTransaction();
        updateMailboxList(ft, accountId, mailboxId, clearDependentPane);
        commitFragmentTransaction(ft);
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
     * @param accountId ID of the owner account for the mailbox.  Must never be
     *     {@link Account#NO_ACCOUNT}.
     * @param mailboxId ID of the mailbox to load. Must never be {@link Mailbox#NO_MAILBOX}.
     * @param clearDependentPane if true, the message view will be cleared
     */
    private void updateMessageList(FragmentTransaction ft,
            long accountId, long mailboxId, boolean clearDependentPane) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " updateMessageList mMailboxId=" + mailboxId);
        }
        if (mailboxId == Mailbox.NO_MAILBOX) {
            throw new IllegalArgumentException();
        }

        if (mailboxId != getMessageListMailboxId()) {
            removeMessageListFragment(ft);
            ft.add(mThreePane.getMiddlePaneId(), MessageListFragment.newInstance(
                    accountId, mailboxId));
        }
        if (clearDependentPane) {
            removeMessageViewFragment(ft);
        }
    }

    /**
     * Shortcut to call {@link #updateMessageList(FragmentTransaction, long, long, boolean)} and
     * commit.
     */
    private void updateMessageList(long accountId, long mailboxId, boolean clearDependentPane) {
        FragmentTransaction ft = mFragmentManager.beginTransaction();
        updateMessageList(ft, accountId, mailboxId, clearDependentPane);
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

        ft.add(mThreePane.getRightPaneId(), MessageViewFragment.newInstance(
                getUIAccountId(), getMessageListMailboxId(), messageId));
    }

    /**
     * Shortcut to call {@link #updateMessageView(FragmentTransaction, long)} and commit.
     */
    private void updateMessageView(long messageId) {
        FragmentTransaction ft = mActivity.getFragmentManager().beginTransaction();
        updateMessageView(ft, messageId);
        commitFragmentTransaction(ft);
    }

    /**
     * Remove the message view if shown.
     */
    private void unselectMessage() {
        commitFragmentTransaction(removeMessageViewFragment(
                mActivity.getFragmentManager().beginTransaction()));
        if (isMessageListInstalled()) {
            getMessageListFragment().setSelectedMessage(Message.NO_MESSAGE);
        }
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

    private void onCurrentMessageGone() {
        switch (Preferences.getPreferences(mActivity).getAutoAdvanceDirection()) {
            case Preferences.AUTO_ADVANCE_NEWER:
                if (moveToNewer()) return;
                break;
            case Preferences.AUTO_ADVANCE_OLDER:
                if (moveToOlder()) return;
                break;
        }
        // Last message in the box or AUTO_ADVANCE_MESSAGE_LIST.  Go back to message list.
        goBackToMailbox();
    }

    /**
     * Potentially create a new {@link MessageOrderManager}; if it's not already started or if
     * the account has changed, and sync it to the current message.
     */
    private void updateMessageOrderManager() {
        if (!isMessageViewInstalled()) {
            return;
        }
        final long mailboxId = getMessageListMailboxId();
        if (mOrderManager == null || mOrderManager.getMailboxId() != mailboxId) {
            stopMessageOrderManager();
            mOrderManager =
                new MessageOrderManager(mActivity, mailboxId, mMessageOrderManagerCallback);
        }
        mOrderManager.moveTo(getMessageId());
    }

    private class MessageOrderManagerCallback implements MessageOrderManager.Callback {
        @Override
        public void onMessagesChanged() {
            updateNavigationArrows();
        }

        @Override
        public void onMessageNotFound() {
            // Current message gone.
            goBackToMailbox();
        }
    }

    /**
     * Stop {@link MessageOrderManager}.
     */
    private void stopMessageOrderManager() {
        if (mOrderManager != null) {
            mOrderManager.close();
            mOrderManager = null;
        }
    }

    /**
     * Disable/enable the move-to-newer/older buttons.
     */
    private void updateNavigationArrows() {
        if (mOrderManager == null) {
            // shouldn't happen, but just in case
            mMessageCommandButtons.enableNavigationButtons(false, false, 0, 0);
        } else {
            mMessageCommandButtons.enableNavigationButtons(
                    mOrderManager.canMoveToNewer(), mOrderManager.canMoveToOlder(),
                    mOrderManager.getCurrentPosition(), mOrderManager.getTotalMessageCount());
        }
    }

    private boolean moveToOlder() {
        if ((mOrderManager != null) && mOrderManager.moveToOlder()) {
            updateMessageView(mOrderManager.getCurrentMessageId());
            return true;
        }
        return false;
    }

    private boolean moveToNewer() {
        if ((mOrderManager != null) && mOrderManager.moveToNewer()) {
            updateMessageView(mOrderManager.getCurrentMessageId());
            return true;
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean onBackPressed(boolean isSystemBackKey) {
        // Super's method has precedence.  Must call it first.
        if (super.onBackPressed(isSystemBackKey)) {
            return true;
        }
        if (mThreePane.onBackPressed(isSystemBackKey)) {
            return true;
        }
        if (isMailboxListInstalled() && getMailboxListFragment().navigateUp()) {
            return true;
        }
        return false;
    }

    @Override protected boolean canSearch() {
        // Search is always enabled on two-pane. (if the account supports it)
        return true;
    }

    /**
     * Handles the "refresh" option item.  Opens the settings activity.
     * TODO used by experimental code in the activity -- otherwise can be private.
     */
    @Override
    public void onRefresh() {
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
        protected void onPostExecute(Boolean isCurrentMailboxRefreshable) {
            if (isCancelled() || isCurrentMailboxRefreshable == null) {
                return;
            }
            if (isCurrentMailboxRefreshable) {
                mRefreshManager.refreshMessageList(mAccountId, mMailboxId, false);
            }
            // Refresh mailbox list
            if (mAccountId != Account.NO_ACCOUNT) {
                if (shouldRefreshMailboxList()) {
                    mRefreshManager.refreshMailboxList(mAccountId);
                }
            }
            // Refresh inbox
            if (shouldAutoRefreshInbox()) {
                mRefreshManager.refreshMessageList(mAccountId, mInboxId, false);
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
        public String getCurrentMailboxName() {
            return mCurrentMailboxName;
        }

        @Override
        public int getCurrentMailboxUnreadCount() {
            return mCurrentMailboxUnreadCount;
        }

        @Override
        public long getUIAccountId() {
            return UIControllerTwoPane.this.getUIAccountId();
        }

        @Override
        public boolean isAccountSelected() {
            return UIControllerTwoPane.this.isAccountSelected();
        }

        @Override
        public void onAccountSelected(long accountId) {
            switchAccount(accountId);
        }

        @Override
        public void onMailboxSelected(long mailboxId) {
            openMailbox(getUIAccountId(), mailboxId);
        }

        @Override
        public void onNoAccountsFound() {
            Welcome.actionStart(mActivity);
            mActivity.finish();
        }

        @Override
        public boolean shouldShowMailboxName() {
            // Show when the left pane is hidden.
            return (mThreePane.getVisiblePanes() & ThreePaneLayout.PANE_LEFT) == 0;
        }

        @Override
        public boolean shouldShowUp() {
            final int visiblePanes = mThreePane.getVisiblePanes();
            final boolean leftPaneHidden = ((visiblePanes & ThreePaneLayout.PANE_LEFT) == 0);
            return leftPaneHidden
                    || (isMailboxListInstalled() && !getMailboxListFragment().isRoot());
        }

        @Override
        public void onSearchSubmit(final String queryTerm) {
            final long accountId = getUIAccountId();
            if (!Account.isNormalAccount(accountId)) {
                return; // Invalid account to search from.
            }

            // TODO: do a global search for EAS inbox.
            final long mailboxId = getMessageListMailboxId();

            if (Email.DEBUG) {
                Log.d(Logging.LOG_TAG, "Submitting search: " + queryTerm);
            }

            mActivity.startActivity(EmailActivity.createSearchIntent(
                    mActivity, accountId, mailboxId, queryTerm));
        }

        @Override
        public void onSearchExit() {
            // STOPSHIP If the activity is a "search" instance, finish() it.
        }
    }
}
