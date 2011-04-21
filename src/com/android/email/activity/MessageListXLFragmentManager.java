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

import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Mailbox;

import android.app.ActionBar;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Set;

/**
 * A class manages what are showing on {@link MessageListXL} (i.e. account id, mailbox id, and
 * message id), and show/hide fragments accordingly.
 *
 * Note: Always use {@link #commitFragmentTransaction} to commit fragment transactions.  Currently
 * we use synchronous transactions only, but we may want to switch back to asynchronous later.
 *
 * TODO: Test it.  It's testable if we implement MockFragmentTransaction, which may be too early
 * to do so at this point.  (API may not be stable enough yet.)
 *
 * TODO Refine "move to".
 */
class MessageListXLFragmentManager implements
        MoveMessageToDialog.Callback,
        MailboxFinder.Callback,
        ThreePaneLayout.Callback,
        MailboxListFragment.Callback,
        MessageListFragment.Callback,
        MessageViewFragment.Callback {
    private static final String BUNDLE_KEY_ACCOUNT_ID = "MessageListXl.state.account_id";
    private static final String BUNDLE_KEY_MAILBOX_ID = "MessageListXl.state.mailbox_id";
    private static final String BUNDLE_KEY_MESSAGE_ID = "MessageListXl.state.message_id";

    /** No account selected */
    static final long NO_ACCOUNT = -1;
    /** No mailbox selected */
    static final long NO_MAILBOX = -1;
    /** No message selected */
    static final long NO_MESSAGE = -1;
    /** Current account id */
    private long mAccountId = NO_ACCOUNT;

    /** Current mailbox id */
    private long mMailboxId = NO_MAILBOX;

    /** Current message id */
    private long mMessageId = NO_MESSAGE;

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

    /**
     * List of fragments that are restored by the framework while the activity is being re-created
     * for configuration changes (e.g. screen rotation).  We'll install them later when the activity
     * is created.
     */
    private final ArrayList<Fragment> mRestoredFragments = new ArrayList<Fragment>();

    private boolean mActivityCreated = false;

    /**
     * The interface that {@link MessageListXL} implements.  We don't call its methods directly,
     * in the hope that it'll make writing tests easier, and make it clear which methods are needed
     * for MessageListXLFragmentManager.
     * TODO Consider getting rid of this. The fragment manager needs an {@link Activity}, so,
     * merely passing around an interface is not sufficient.
     */
    public interface TargetActivity {
        /** Implemented by {@link Activity}, so, signature must match */
        public ActionBar getActionBar();
        public FragmentManager getFragmentManager();
        public View findViewById(int id);

        /** Called when the selected account is on security-hold. */
        public void onAccountSecurityHold(long accountId);
        /** Called when the current account has changed. */
        public void onAccountChanged(long accountId);
        /** Called when the current mailbox has changed. */
        public void onMailboxChanged(long accountId, long newMailboxId);
        /** Called when the current mailbox name / unread count has changed. */
        public void onMailboxNameChanged(String mailboxName, int unreadCount);
        /** Called when the visible panes have changed. */
        public void onVisiblePanesChanged(int visiblePanes);
    }

    private final MessageListXL mActivity;

    public MessageListXLFragmentManager(MessageListXL activity) {
        mActivity = activity;
    }

    // MailboxFinder$Callback
    @Override
    public void onAccountNotFound() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXLFragmentManager#onAccountNotFound()");
        }
        // Shouldn't happen
    }

    @Override
    public void onAccountSecurityHold(long accountId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXLFragmentManager#onAccountSecurityHold()");
        }
        mActivity.onAccountSecurityHold(accountId);
    }

    @Override
    public void onMailboxFound(long accountId, long mailboxId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXLFragmentManager#onMailboxFound()");
        }
        updateMessageList(mailboxId, true, true);
    }

    @Override
    public void onMailboxNotFound(long accountId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXLFragmentManager#onMailboxNotFound()");
        }
        // TODO: handle more gracefully.
        Log.e(Logging.LOG_TAG, "unable to find mailbox for account " + accountId);
    }

    @Override
    public void onMailboxNotFound() {
        // TODO: handle more gracefully.
        Log.e(Logging.LOG_TAG, "unable to find mailbox");
    }

    // MoveMessageToDialog$Callback
    @Override
    public void onMoveToMailboxSelected(long newMailboxId, long[] messageIds) {
        ActivityHelper.moveMessages(mActivity, newMailboxId, messageIds);
        onCurrentMessageGone();
    }

    // ThreePaneLayoutCallback
    @Override
    public void onVisiblePanesChanged(int previousVisiblePanes) {
        final int visiblePanes = mThreePane.getVisiblePanes();
        mActivity.onVisiblePanesChanged(visiblePanes);
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
        if (mMessageListFragment != null) {
            mMessageListFragment.setVisibility((visiblePanes & ThreePaneLayout.PANE_MIDDLE) != 0);
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
            updateMailboxList(accountId, mailboxId, true, true);
            updateMessageList(mailboxId, true, true);
        } else {
            updateMessageList(mailboxId, true, true);
        }
    }

    @Override
    public void onAccountSelected(long accountId) {
        openAccount(accountId);
    }

    @Override
    public void onCurrentMailboxUpdated(long mailboxId, String mailboxName, int unreadCount) {
        mActivity.onMailboxNameChanged(mailboxName, unreadCount);
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
    public void onBeforeMessageDelete() {
        onCurrentMessageGone();
    }

    @Override
    public void onMoveMessage() {
        long messageId = getMessageId();
        MoveMessageToDialog dialog = MoveMessageToDialog.newInstance(new long[] {messageId}, null);
        dialog.show(mActivity.getFragmentManager(), "dialog");
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
    public void onActivityViewReady() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXLFragmentManager onActivityViewReady");
        }
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
    public long getUIAccountId() {
        return mAccountId;
    }

    /**
     * @return the currently selected account ID.  If the current view is the combined view,
     * it'll return {@link #NO_ACCOUNT}.
     *
     * @see #getUIAccountId()
     */
    public long getActualAccountId() {
        return mAccountId == Account.ACCOUNT_ID_COMBINED_VIEW ? NO_ACCOUNT : mAccountId;
    }

    public long getMailboxId() {
        return mMailboxId;
    }

    public long getMessageId() {
        return mMessageId;
    }

    /**
     * @return true if an account is selected, or the current view is the combined view.
     */
    public boolean isAccountSelected() {
        return getUIAccountId() != NO_ACCOUNT;
    }

    public boolean isMailboxSelected() {
        return getMailboxId() != NO_MAILBOX;
    }

    public boolean isMessageSelected() {
        return getMessageId() != NO_MESSAGE;
    }

    /**
     * Called at the end of {@link MessageListXL#onCreate}.
     */
    public void onActivityCreated() {
        mActivityCreated = true;

        // Install all the fragments restored by the framework.
        for (Fragment fragment : mRestoredFragments) {
            installFragment(fragment);
        }
        mRestoredFragments.clear();
    }

    /**
     * Called by {@link MessageListXL} when a {@link Fragment} is attached.
     *
     * If the activity has already been created, we initialize the fragment here.  Otherwise we
     * keep the fragment in {@link #mRestoredFragments} and initialize it after the activity's
     * onCreate.
     */
    public void onAttachFragment(Fragment fragment) {
        if (!mActivityCreated) {
            // Fragment being restored by the framework during the activity recreation.
            mRestoredFragments.add(fragment);
            return;
        }
        installFragment(fragment);
    }

    /**
     * Called from {@link MessageListXL#onStart}.
     */
    public void onStart() {
        if (isMessageSelected()) {
            updateMessageOrderManager();
        }
    }

    /**
     * Called from {@link MessageListXL#onResume}.
     */
    public void onResume() {
        int visiblePanes = mThreePane.getVisiblePanes();
        mActivity.onVisiblePanesChanged(visiblePanes);
    }

    /**
     * Called from {@link MessageListXL#onPause}.
     */
    public void onPause() {
    }

    /**
     * Called from {@link MessageListXL#onStop}.
     */
    public void onStop() {
        stopMessageOrderManager();
    }

    /**
     * Called from {@link MessageListXL#onDestroy}.
     */
    public void onDestroy() {
        mActivityCreated = false;
        closeMailboxFinder();
    }

    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXLFragmentManager onSaveInstanceState");
        }
        outState.putLong(BUNDLE_KEY_ACCOUNT_ID, mAccountId);
        outState.putLong(BUNDLE_KEY_MAILBOX_ID, mMailboxId);
        outState.putLong(BUNDLE_KEY_MESSAGE_ID, mMessageId);
    }

    public void restoreInstanceState(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXLFragmentManager restoreInstanceState");
        }
        mAccountId = savedInstanceState.getLong(BUNDLE_KEY_ACCOUNT_ID, NO_ACCOUNT);
        mMailboxId = savedInstanceState.getLong(BUNDLE_KEY_MAILBOX_ID, NO_MAILBOX);
        mMessageId = savedInstanceState.getLong(BUNDLE_KEY_MESSAGE_ID, NO_MESSAGE);

        // STOPSHIP If MailboxFinder is still running, it needs restarting after loadState().
        // This probably means we need to start MailboxFinder if mMailboxId == -1.
    }

    private void installFragment(Fragment fragment) {
        if (fragment instanceof MailboxListFragment) {
            mMailboxListFragment = (MailboxListFragment) fragment;
            mMailboxListFragment.setCallback(this);
        } else if (fragment instanceof MessageListFragment) {
            mMessageListFragment = (MessageListFragment) fragment;
            mMessageListFragment.setCallback(this);
        } else if (fragment instanceof MessageViewFragment) {
            mMessageViewFragment = (MessageViewFragment) fragment;
            mMessageViewFragment.setCallback(this);
        } else {
            // Ignore -- uninteresting fragments such as dialogs.
        }
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

    private void commitFragmentTransaction(FragmentTransaction ft) {
        ft.commit();
        mActivity.getFragmentManager().executePendingTransactions();
    }

    /**
     * Show the default view for the account.
     *
     * On two-pane, it's the account's root mailboxes on the left pane with Inbox on the right pane.
     *
     * @param accountId ID of the account to load.  Can be {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
     *     Must never be {@link #NO_ACCOUNT}.
     */
    public void openAccount(long accountId) {
        open(accountId, NO_MAILBOX, NO_MESSAGE);
    }

    /**
     * Loads the given account and optionally selects the given mailbox and message.  Used to open
     * a particular view at a request from outside of the activity, such as the widget.
     *
     * @param accountId ID of the account to load.  Can be {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
     *     Must never be {@link #NO_ACCOUNT}.
     * @param mailboxId ID of the mailbox to load. If {@link #NO_MAILBOX}, load the account's inbox.
     * @param messageId ID of the message to load. If {@link #NO_MESSAGE}, do not open a message.
     */
    public void open(long accountId, long mailboxId, long messageId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "open accountId=" + accountId + " mailboxId=" + mailboxId
                    + " messageId=" + messageId);
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
            updateMailboxList(accountId, NO_MAILBOX, true, true);
            updateMessageList(mailboxId, true, true);
        } else {
            // STOPSHIP Use the appropriate parent mailbox ID
            updateMailboxList(accountId, NO_MAILBOX, false, true);
            updateMessageList(mailboxId, false, true);
            updateMessageView(messageId);
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
            Log.d(Logging.LOG_TAG, "updateMailboxList accountId=" + accountId + " parentMailboxId="
                    + parentMailboxId);
        }
        if (accountId == NO_ACCOUNT) {
            throw new InvalidParameterException();
        }

        // TODO Check if the current fragment has been initialized with the same parameters, and
        // then return.

        mAccountId = accountId;

        // Open mailbox list, remove message list / message view
        final FragmentManager fm = mActivity.getFragmentManager();
        final FragmentTransaction ft = fm.beginTransaction();
        uninstallMailboxListFragment(ft);
        if (clearDependentPane) {
            mMailboxId = NO_MAILBOX;
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
        mActivity.onAccountChanged(mAccountId);
    }

    /**
     * Handles the back event.
     *
     * @param isSystemBackKey See {@link ThreePaneLayout#onBackPressed}
     * @return true if the event is handled.
     */
    public boolean onBackPressed(boolean isSystemBackKey) {
        return mThreePane.onBackPressed(isSystemBackKey);
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
            Log.d(Logging.LOG_TAG, "updateMessageList mMailboxId=" + mailboxId);
        }
        if (mailboxId == 0 || mailboxId == -1) {
            throw new InvalidParameterException();
        }

        // TODO Check if the current fragment has been initialized with the same parameters, and
        // then return.

        mMailboxId = mailboxId;

        final FragmentManager fm = mActivity.getFragmentManager();
        final FragmentTransaction ft = fm.beginTransaction();
        uninstallMessageListFragment(ft);
        if (clearDependentPane) {
            uninstallMessageViewFragment(ft);
            mMessageId = NO_MESSAGE;
        }
        ft.add(mThreePane.getMiddlePaneId(), MessageListFragment.newInstance(mailboxId));
        commitFragmentTransaction(ft);

        mMailboxListFragment.setSelectedMailbox(mailboxId);
        mActivity.onMailboxChanged(mAccountId, mailboxId);

        if (changeVisiblePane) {
            mThreePane.showLeftPane();
        }
    }

    /**
     * Show a message on the message view.
     *
     * @param messageId ID of the mailbox to load. Must never be {@link #NO_MESSAGE}.
     */
    private void updateMessageView(long messageId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "updateMessageView messageId=" + messageId);
        }
        if (messageId == NO_MESSAGE) {
            throw new InvalidParameterException();
        }

        // TODO Check if the current fragment has been initialized with the same parameters, and
        // then return.

        // Update member
        mMessageId = messageId;

        // Open message
        mMessageListFragment.setSelectedMessage(mMessageId);

        final FragmentManager fm = mActivity.getFragmentManager();
        final FragmentTransaction ft = fm.beginTransaction();
        uninstallMessageViewFragment(ft);
        ft.add(mThreePane.getRightPaneId(), MessageViewFragment.newInstance(messageId));
        commitFragmentTransaction(ft);

        mThreePane.showRightPane(); // Show message view
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
        final long mailboxId = getMailboxId();
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
}
