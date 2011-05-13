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

import com.android.email.Clock;
import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.email.activity.setup.AccountSecurity;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.security.InvalidParameterException;
import java.util.Set;
import java.util.Stack;

/**
 * UI Controller for x-large devices.  Supports a multi-pane layout.
 */
class UIControllerTwoPane extends UIControllerBase implements
        MailboxFinder.Callback,
        ThreePaneLayout.Callback,
        MailboxListFragment.Callback,
        MessageListFragment.Callback,
        MessageViewFragment.Callback {
    private static final String BUNDLE_KEY_MAILBOX_STACK
            = "UIControllerTwoPane.state.mailbox_stack";

    /* package */ static final int MAILBOX_REFRESH_MIN_INTERVAL = 30 * 1000; // in milliseconds
    /* package */ static final int INBOX_AUTO_REFRESH_MIN_INTERVAL = 10 * 1000; // in milliseconds

    /** Current account id */
    private long mAccountId = NO_ACCOUNT;

    // TODO Remove this instance variable and replace it with a call to mMessageListFragment to
    // retrieve it's mailbox ID. There's no reason we should be duplicating data
    /**
     * The id of the currently viewed mailbox in the mailbox list fragment.
     * IMPORTANT: Do not confuse this with the value returned by {@link #getMessageListMailboxId()}
     * which is the mailbox id associated with the message list fragment. The two may be different.
     */
    private long mMailboxListMailboxId = NO_MAILBOX;

    /** Current message id */
    private long mMessageId = NO_MESSAGE;

    private ActionBarController mActionBarController;
    private final ActionBarControllerCallback mActionBarControllerCallback =
            new ActionBarControllerCallback();

    // Other UI elements
    private ThreePaneLayout mThreePane;

    /**
     * Fragments that are installed.
     *
     * A fragment is installed when:
     * - it is attached to the activity
     * - the parent activity is created
     * - and it is not scheduled to be removed.
     *
     * We set callbacks to fragments only when they are installed.
     */
    private MailboxListFragment mMailboxListFragment;
    private MessageListFragment mMessageListFragment;
    private MessageViewFragment mMessageViewFragment;

    private MessageCommandButtonView mMessageCommandButtons;

    private MailboxFinder mMailboxFinder;

    private MessageOrderManager mOrderManager;
    private final MessageOrderManagerCallback mMessageOrderManagerCallback =
        new MessageOrderManagerCallback();
    /** Mailbox IDs that the user has navigated away from; used to provide "back" functionality */
    private final Stack<Long> mMailboxStack = new Stack<Long>();

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

    // MailboxFinder$Callback
    @Override
    public void onAccountNotFound() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onAccountNotFound()");
        }
        // Shouldn't happen
    }

    @Override
    public void onAccountSecurityHold(long accountId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onAccountSecurityHold()");
        }
        mActivity.startActivity(AccountSecurity.actionUpdateSecurityIntent(mActivity, accountId,
                true));
    }

    @Override
    public void onMailboxFound(long accountId, long mailboxId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onMailboxFound()");
        }
        updateMessageList(mailboxId, true, true);
    }

    @Override
    public void onMailboxNotFound(long accountId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onMailboxNotFound()");
        }
        // TODO: handle more gracefully.
        Log.e(Logging.LOG_TAG, "unable to find mailbox for account " + accountId);
    }

    @Override
    public void onMailboxNotFound() {
        // TODO: handle more gracefully.
        Log.e(Logging.LOG_TAG, "unable to find mailbox");
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
            mMessageId = NO_MESSAGE;
            if (mMessageListFragment != null) {
                mMessageListFragment.setSelectedMessage(NO_MESSAGE);
            }
            uninstallMessageViewFragment(mActivity.getFragmentManager().beginTransaction())
                    .commit();
        }
        // Disable CAB when the message list is not visible.
        if (mMessageListFragment != null) {
            mMessageListFragment.onHidden((visiblePanes & ThreePaneLayout.PANE_MIDDLE) == 0);
        }
    }

    private void refreshActionBar() {
        if (mActionBarController != null) {
            mActionBarController.refresh();
        }
    }

    // MailboxListFragment$Callback
    @Override
    public void onMailboxSelected(long accountId, long mailboxId, boolean navigate,
            boolean dragDrop) {
        if (dragDrop) {
            // We don't want to change the message list for D&D.

            // STOPSHIP fixit: the new mailbox list created here doesn't know D&D is in progress.

            updateMailboxList(accountId, mailboxId, true,
                    false /* don't clear message list and message view */);
        } else if (mailboxId == NO_MAILBOX) {
            // reload the top-level message list.  Always implies navigate.
            openAccount(accountId);
        } else if (navigate) {
            if (mMailboxStack.isEmpty() || mailboxId != mMailboxListMailboxId) {
                // Don't navigate to the same mailbox id twice in a row
                mMailboxStack.push(mMailboxListMailboxId);
                openMailbox(accountId, mailboxId);
            }
        } else {
            updateMessageList(mailboxId, true, true);
        }
    }

    @Override
    public void onAccountSelected(long accountId) {
        // TODO openAccount should do the check eventually, but it's necessary for now.
        if (accountId != getUIAccountId()) {
            openAccount(accountId);
        }
    }

    @Override
    public void onCurrentMailboxUpdated(long mailboxId, String mailboxName, int unreadCount) {
        mCurrentMailboxName = mailboxName;
        mCurrentMailboxUnreadCount = unreadCount;
        refreshActionBar();
    }

    // MessageListFragment$Callback
    @Override
    public void onMessageOpen(long messageId, long messageMailboxId, long listMailboxId,
            int type) {
        if (type == MessageListFragment.Callback.TYPE_DRAFT) {
            MessageCompose.actionEditDraft(mActivity, messageId);
        } else {
            updateMessageView(messageId);
        }
    }

    @Override
    public void onEnterSelectionMode(boolean enter) {
    }

    /**
     * Apply the auto-advance policy upon initation of a batch command that could potentially
     * affect the currently selected conversation.
     */
    @Override
    public void onAdvancingOpAccepted(Set<Long> affectedMessages) {
        if (!isMessageSelected()) {
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

    @Override
    public void onListLoaded() {
    }

    // MessageViewFragment$Callback
    @Override
    public void onMessageViewShown(int mailboxType) {
        updateMessageOrderManager();
        updateNavigationArrows();
    }

    @Override
    public void onMessageViewGone() {
        stopMessageOrderManager();
    }

    @Override
    public boolean onUrlInMessageClicked(String url) {
        return ActivityHelper.openUrlInMessage(mActivity, url, getActualAccountId());
    }

    @Override
    public void onMessageSetUnread() {
        goBackToMailbox();
    }

    @Override
    public void onMessageNotExists() {
        goBackToMailbox();
    }

    @Override
    public void onLoadMessageStarted() {
        // TODO Any nice UI for this?
    }

    @Override
    public void onLoadMessageFinished() {
        // TODO Any nice UI for this?
    }

    @Override
    public void onLoadMessageError(String errorMessage) {
    }

    @Override
    public void onRespondedToInvite(int response) {
        onCurrentMessageGone();
    }

    @Override
    public void onCalendarLinkClicked(long epochEventStartTime) {
        ActivityHelper.openCalendar(mActivity, epochEventStartTime);
    }

    @Override
    public void onBeforeMessageGone() {
        onCurrentMessageGone();
    }

    @Override
    public void onForward() {
        MessageCompose.actionForward(mActivity, getMessageId());
    }

    @Override
    public void onReply() {
        MessageCompose.actionReply(mActivity, getMessageId(), false);
    }

    @Override
    public void onReplyAll() {
        MessageCompose.actionReply(mActivity, getMessageId(), true);
    }

    /**
     * Must be called just after the activity sets up the content view.
     *
     * (Due to the complexity regarding class/activity initialization order, we can't do this in
     * the constructor.)  TODO this should no longer be true when we merge activities.
     */
    @Override
    public void onActivityViewReady() {
        super.onActivityViewReady();
        mActionBarController = new ActionBarController(mActivity, mActivity.getLoaderManager(),
                mActivity.getActionBar(), mActionBarControllerCallback);

        // Set up content
        mThreePane = (ThreePaneLayout) mActivity.findViewById(R.id.three_pane);
        mThreePane.setCallback(this);

        mMessageCommandButtons = mThreePane.getMessageCommandButtons();
        mMessageCommandButtons.setCallback(new CommandButtonCallback());
    }

    /**
     * @return the currently selected account ID, *or* {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
     *
     * @see #getActualAccountId()
     */
    @Override
    public long getUIAccountId() {
        return mAccountId;
    }

    /**
     * Returns the id of the mailbox used for the message list fragment.
     * IMPORTANT: Do not confuse this with {@link #mMailboxListMailboxId} which is the id used
     * for the mailbox list. The two may be different.
     */
    private long getMessageListMailboxId() {
        return (mMessageListFragment == null)
                ? Mailbox.NO_MAILBOX
                : mMessageListFragment.getMailboxId();
    }

    /*
     * STOPSHIP Remove this -- see the base class method.
     */
    @Override
    public long getMailboxSettingsMailboxId() {
        return getMessageListMailboxId();
    }

    /*
     * STOPSHIP Remove this -- see the base class method.
     */
    @Override
    public long getSearchMailboxId() {
        return getMessageListMailboxId();
    }

    private long getMessageId() {
        return mMessageId;
    }

    private boolean isMailboxSelected() {
        return getMessageListMailboxId() != NO_MAILBOX;
    }

    private boolean isMessageSelected() {
        return getMessageId() != NO_MESSAGE;
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
        return -1 != getActualAccountId();
    }

    /**
     * Called by the host activity at the end of {@link Activity#onCreate}.
     */
    @Override
    public void onActivityCreated() {
        super.onActivityCreated();
        mActionBarController.onActivityCreated();
    }

    /** {@inheritDoc} */
    @Override
    public void onActivityStart() {
        super.onActivityStart();
        if (isMessageSelected()) {
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
        closeMailboxFinder();
        super.onActivityDestroy();
    }

    /** {@inheritDoc} */
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(BUNDLE_KEY_ACCOUNT_ID, mAccountId);
        outState.putLong(BUNDLE_KEY_MAILBOX_ID, mMailboxListMailboxId);
        outState.putLong(BUNDLE_KEY_MESSAGE_ID, mMessageId);
        if (!mMailboxStack.isEmpty()) {
            // Save the mailbox stack
            long[] mailboxIds = Utility.toPrimitiveLongArray(mMailboxStack);
            outState.putLongArray(BUNDLE_KEY_MAILBOX_STACK, mailboxIds);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void restoreInstanceState(Bundle savedInstanceState) {
        super.restoreInstanceState(savedInstanceState);
        mAccountId = savedInstanceState.getLong(BUNDLE_KEY_ACCOUNT_ID, NO_ACCOUNT);
        mMailboxListMailboxId = savedInstanceState.getLong(BUNDLE_KEY_MAILBOX_ID, NO_MAILBOX);
        mMessageId = savedInstanceState.getLong(BUNDLE_KEY_MESSAGE_ID, NO_MESSAGE);
        long[] mailboxIds = savedInstanceState.getLongArray(BUNDLE_KEY_MAILBOX_STACK);
        if (mailboxIds != null) {
            // Restore the mailbox stack; ugly hack to get around 'Long' versus 'long'
            mMailboxStack.clear();
            for (long id : mailboxIds) {
                mMailboxStack.push(id);
            }
        }

        // STOPSHIP If MailboxFinder is still running, it needs restarting after loadState().
        // This probably means we need to start MailboxFinder if mMailboxId == -1.
    }

    @Override
    protected void installMailboxListFragment(MailboxListFragment fragment) {
        mMailboxListFragment = fragment;
        mMailboxListFragment.setCallback(this);
    }

    @Override
    protected void installMessageListFragment(MessageListFragment fragment) {
        mMessageListFragment = fragment;
        mMessageListFragment.setCallback(this);
    }

    @Override
    protected void installMessageViewFragment(MessageViewFragment fragment) {
        mMessageViewFragment = fragment;
        mMessageViewFragment.setCallback(this);
    }

    private FragmentTransaction uninstallMailboxListFragment(FragmentTransaction ft) {
        if (mMailboxListFragment != null) {
            ft.remove(mMailboxListFragment);
            mMailboxListFragment.setCallback(null);
            mMailboxListFragment = null;
        }
        return ft;
    }

    private FragmentTransaction uninstallMessageListFragment(FragmentTransaction ft) {
        if (mMessageListFragment != null) {
            ft.remove(mMessageListFragment);
            mMessageListFragment.setCallback(null);
            mMessageListFragment = null;
        }
        return ft;
    }

    private FragmentTransaction uninstallMessageViewFragment(FragmentTransaction ft) {
        if (mMessageViewFragment != null) {
            ft.remove(mMessageViewFragment);
            mMessageViewFragment.setCallback(null);
            mMessageViewFragment = null;
        }
        return ft;
    }

    /**
     * {@inheritDoc}
     *
     * On two-pane, it's the account's root mailboxes on the left pane with Inbox on the right pane.
     */
    @Override
    public void openAccount(long accountId) {
        mMailboxStack.clear();
        open(accountId, NO_MAILBOX, NO_MESSAGE);
        refreshActionBar();
    }

    /**
     * Opens the given mailbox. on two-pane, this will update both the mailbox list and the
     * message list.
     *
     * NOTE: It's assumed that the mailbox is associated with the specified account. If the
     * mailbox is not associated with the account, the behaviour is undefined.
     */
    private void openMailbox(long accountId, long mailboxId) {
        updateMailboxList(accountId, mailboxId, true, true);
        updateMessageList(mailboxId, true, true);
        refreshActionBar();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void open(long accountId, long mailboxId, long messageId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " open accountId=" + accountId
                    + " mailboxId=" + mailboxId + " messageId=" + messageId);
        }
        if (accountId == NO_ACCOUNT) {
            throw new IllegalArgumentException();
        } else if (mailboxId == NO_MAILBOX) {
            updateMailboxList(accountId, NO_MAILBOX, true, true);

            // Show the appropriate message list
            if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
                // When opening the Combined view, the right pane will be "combined inbox".
                updateMessageList(Mailbox.QUERY_ALL_INBOXES, true, true);
            } else {
                // Try to find the inbox for the account
                closeMailboxFinder();
                mMailboxFinder = new MailboxFinder(mActivity, mAccountId, Mailbox.TYPE_INBOX, this);
                mMailboxFinder.startLookup();
            }
        } else if (messageId == NO_MESSAGE) {
            // STOPSHIP Use the appropriate parent mailbox ID
            updateMailboxList(accountId, mailboxId, true, true);
            updateMessageList(mailboxId, true, true);
        } else {
            // STOPSHIP Use the appropriate parent mailbox ID
            updateMailboxList(accountId, mailboxId, false, true);
            updateMessageList(mailboxId, false, true);
            updateMessageView(messageId);
        }
    }

    /**
     * Pre-fragment transaction check.
     *
     * @throw IllegalStateException if updateXxx methods can't be called in the current state.
     */
    private void preFragmentTransactionCheck() {
        if (!isFragmentInstallable()) {
            // Code assumes mMailboxListFragment/etc are set right within the
            // commitFragmentTransaction() call (because we use synchronous transaction),
            // so updateXxx() can't be called if fragments are not installable yet.
            throw new IllegalStateException();
        }
    }

    /**
     * Loads the given account and optionally selects the given mailbox and message. If the
     * specified account is already selected, no actions will be performed unless
     * <code>forceReload</code> is <code>true</code>.
     *
     * @param accountId ID of the account to load. Must never be {@link #NO_ACCOUNT}.
     * @param parentMailboxId ID of the mailbox to use as the parent mailbox.  Pass
     *     {@link #NO_MAILBOX} to show the root mailboxes.
     * @param changeVisiblePane if true, the message view will be hidden.
     * @param clearDependentPane if true, the message list and the message view will be cleared
     */

    // TODO The name "updateMailboxList" is misleading, as it also updates members such as
    // mAccountId.  We need better structure but let's do that after refactoring
    // MailboxListFragment.onMailboxSelected, and removed the UI callbacks such as
    // TargetActivity.onAccountChanged.

    private void updateMailboxList(long accountId, long parentMailboxId,
            boolean changeVisiblePane, boolean clearDependentPane) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " updateMailboxList accountId=" + accountId
                    + " parentMailboxId=" + parentMailboxId);
        }
        preFragmentTransactionCheck();
        if (accountId == NO_ACCOUNT) {
            throw new InvalidParameterException();
        }

        // TODO Check if the current fragment has been initialized with the same parameters, and
        // then return.

        mAccountId = accountId;
        mMailboxListMailboxId = parentMailboxId;

        // Open mailbox list, remove message list / message view
        final FragmentManager fm = mActivity.getFragmentManager();
        final FragmentTransaction ft = fm.beginTransaction();
        uninstallMailboxListFragment(ft);
        if (clearDependentPane) {
            mMessageId = NO_MESSAGE;
            uninstallMessageListFragment(ft);
            uninstallMessageViewFragment(ft);
        }
        ft.add(mThreePane.getLeftPaneId(),
                MailboxListFragment.newInstance(getUIAccountId(), parentMailboxId));
        commitFragmentTransaction(ft);

        if (changeVisiblePane) {
            mThreePane.showLeftPane();
        }
        updateRefreshProgress();
    }

    /**
     * Go back to a mailbox list view. If a message view is currently active, it will
     * be hidden.
     */
    private void goBackToMailbox() {
        if (isMessageSelected()) {
            mThreePane.showLeftPane(); // Show mailbox list
        }
    }

    /**
     * Selects the specified mailbox and optionally loads a message within it. If a message is
     * not loaded, a list of the messages contained within the mailbox is shown. Otherwise the
     * given message is shown. If <code>navigateToMailbox<code> is <code>true</code>, the
     * mailbox is navigated to and any contained mailboxes are shown.
     *
     * @param mailboxId ID of the mailbox to load. Must never be <code>0</code> or <code>-1</code>.
     * @param changeVisiblePane if true, the message view will be hidden.
     * @param clearDependentPane if true, the message view will be cleared
     */
    private void updateMessageList(long mailboxId, boolean changeVisiblePane,
            boolean clearDependentPane) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " updateMessageList mMailboxId=" + mailboxId);
        }
        preFragmentTransactionCheck();
        if (mailboxId == 0 || mailboxId == -1) {
            throw new InvalidParameterException();
        }

        // TODO Check if the current fragment has been initialized with the same parameters, and
        // then return.

        final FragmentManager fm = mActivity.getFragmentManager();
        final FragmentTransaction ft = fm.beginTransaction();
        uninstallMessageListFragment(ft);
        if (clearDependentPane) {
            uninstallMessageViewFragment(ft);
            mMessageId = NO_MESSAGE;
        }
        ft.add(mThreePane.getMiddlePaneId(), MessageListFragment.newInstance(mailboxId));
        commitFragmentTransaction(ft);

        if (changeVisiblePane) {
            mThreePane.showLeftPane();
        }

        // TODO We shouldn't select the mailbox when we're updating the message list. These two
        // functions should be done separately. Find a better location for this call to be done.
        mMailboxListFragment.setSelectedMailbox(mailboxId);
        updateRefreshProgress();
    }

    /**
     * Show a message on the message view.
     *
     * @param messageId ID of the mailbox to load. Must never be {@link #NO_MESSAGE}.
     */
    private void updateMessageView(long messageId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " updateMessageView messageId=" + messageId);
        }
        preFragmentTransactionCheck();
        if (messageId == NO_MESSAGE) {
            throw new InvalidParameterException();
        }

        // TODO Check if the current fragment has been initialized with the same parameters, and
        // then return.

        // Update member
        mMessageId = messageId;

        // Open message
        final FragmentManager fm = mActivity.getFragmentManager();
        final FragmentTransaction ft = fm.beginTransaction();
        uninstallMessageViewFragment(ft);
        ft.add(mThreePane.getRightPaneId(), MessageViewFragment.newInstance(messageId));
        commitFragmentTransaction(ft);

        mThreePane.showRightPane(); // Show message view

        mMessageListFragment.setSelectedMessage(mMessageId);
    }

    private void closeMailboxFinder() {
        if (mMailboxFinder != null) {
            mMailboxFinder.cancel();
            mMailboxFinder = null;
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
        if (!isMailboxSelected()) {
            return;
        }
        final long mailboxId = getMessageListMailboxId();
        if (mOrderManager == null || mOrderManager.getMailboxId() != mailboxId) {
            stopMessageOrderManager();
            mOrderManager =
                new MessageOrderManager(mActivity, mailboxId, mMessageOrderManagerCallback);
        }
        if (isMessageSelected()) {
            mOrderManager.moveTo(getMessageId());
        }
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
        if (mThreePane.onBackPressed(isSystemBackKey)) {
            return true;
        } else if (!mMailboxStack.isEmpty()) {
            long mailboxId = mMailboxStack.pop();
            if (mailboxId == NO_MAILBOX) {
                // No mailbox; reload the top-level message list
                openAccount(mAccountId);
            } else {
                openMailbox(mAccountId, mailboxId);
            }
            return true;
        }
        return false;
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
    /* package */ static class RefreshTask extends EmailAsyncTask<Void, Void, Boolean> {
        private final Clock mClock;
        private final Context mContext;
        private final long mAccountId;
        private final long mMailboxId;
        private final RefreshManager mRefreshManager;
        /* package */ long mInboxId;

        public RefreshTask(EmailAsyncTask.Tracker tracker, Context context, long accountId,
                long mailboxId) {
            this(tracker, context, accountId, mailboxId, Clock.INSTANCE,
                    RefreshManager.getInstance(context));
        }

        /* package */ RefreshTask(EmailAsyncTask.Tracker tracker, Context context, long accountId,
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
            if (mAccountId != -1) {
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
        /* package */ boolean shouldRefreshMailboxList() {
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
        /* package */ boolean shouldAutoRefreshInbox() {
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
            openAccount(accountId);
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
            return leftPaneHidden || !mMailboxStack.isEmpty();
        }
    }
}
