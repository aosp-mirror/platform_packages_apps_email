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
import android.app.FragmentManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.security.InvalidParameterException;

/**
 * A class manages what are showing on {@link MessageListXL} (i.e. account id, mailbox id, and
 * message id), and show/hide fragments accordingly.
 *
 * TODO Highlight selected message on message list
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
    private static final String BUNDLE_KEY_MESSAGE_LIST_STATE
            = "MessageListXl.state.message_list_state";

    private boolean mIsActivityResumed;

    /** Current account id. (-1 = not selected) */
    private long mAccountId = -1;

    /** Current mailbox id. (-1 = not selected) */
    private long mMailboxId = -1;

    /** Current message id. (-1 = not selected) */
    private long mMessageId = -1;

    private ThreePaneLayout mThreePane;

    private MailboxListFragment mMailboxListFragment;
    private MessageListFragment mMessageListFragment;
    private MessageViewFragment mMessageViewFragment;
    private MessageCommandButtonView mMessageCommandButtons;

    private MailboxFinder mMailboxFinder;

    /** Save state for the "message list -> message view -[back press]-> message list" transition */
    private MessageListFragment.State mMessageListFragmentState;

    private MessageOrderManager mOrderManager;
    private final MessageOrderManagerCallback mMessageOrderManagerCallback =
        new MessageOrderManagerCallback();

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
        selectMailbox(mailboxId, -1);
    }

    @Override
    public void onMailboxNotFound(long accountId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXLFragmentManager#onMailboxNotFound()");
        }
        // Shouldn't happen
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
            mMessageId = -1;
            mMessageListFragment.setSelectedMessage(-1);
            mMessageViewFragment.clearContent();
        }
        mMessageListFragment.setVisibility((visiblePanes & ThreePaneLayout.PANE_MIDDLE) != 0);
    }

    // MailboxListFragment$Callback
    @Override
    public void onMailboxSelected(long accountId, long mailboxId) {
        selectMailbox(mailboxId, -1);
    }

    @Override
    public void onAccountSelected(long accountId) {
        selectAccount(accountId, -1, -1);
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
            selectMessage(messageId);
        }
    }

    @Override
    public void onMailboxNotFound() {
        // TODO: What to do??
    }

    @Override
    public void onEnterSelectionMode(boolean enter) {
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
     * the constructor.)
     */
    public void onActivityViewReady() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXLFragmentManager.onActivityViewReady");
        }
        mThreePane = (ThreePaneLayout) mActivity.findViewById(R.id.three_pane);
        mThreePane.setCallback(this);

        FragmentManager fm = mActivity.getFragmentManager();
        mMailboxListFragment = (MailboxListFragment) fm.findFragmentById(
                mThreePane.getLeftPaneId());
        mMessageListFragment = (MessageListFragment) fm.findFragmentById(
                mThreePane.getMiddlePaneId());
        mMessageViewFragment = (MessageViewFragment) fm.findFragmentById(
                mThreePane.getRightPaneId());
        mMessageCommandButtons = mThreePane.getMessageCommandButtons();
        mMessageCommandButtons.setCallback(new CommandButtonCallback());

        mMailboxListFragment.setCallback(this);
        mMessageListFragment.setCallback(this);
        mMessageViewFragment.setCallback(this);
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
     * it'll return -1.
     *
     * @see #getUIAccountId()
     */
    public long getActualAccountId() {
        return mAccountId == Account.ACCOUNT_ID_COMBINED_VIEW ? -1 : mAccountId;
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
        return getUIAccountId() != -1;
    }

    public boolean isMailboxSelected() {
        return getMailboxId() != -1;
    }

    public boolean isMessageSelected() {
        return getMessageId() != -1;
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

        if (mIsActivityResumed) {
            return;
        }
        mIsActivityResumed = true;
    }

    /**
     * Called from {@link MessageListXL#onPause}.
     */
    public void onPause() {
        if (!mIsActivityResumed) {
            return;
        }
        mIsActivityResumed = false;
        mMessageListFragmentState = mMessageListFragment.getState();
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
        closeMailboxFinder();
    }

    public void onSaveInstanceState(Bundle outState) {
        outState.putLong(BUNDLE_KEY_ACCOUNT_ID, mAccountId);
        outState.putLong(BUNDLE_KEY_MAILBOX_ID, mMailboxId);
        outState.putLong(BUNDLE_KEY_MESSAGE_ID, mMessageId);
        outState.putParcelable(BUNDLE_KEY_MESSAGE_LIST_STATE, mMessageListFragmentState);
    }

    public void loadState(Bundle savedInstanceState) {
        long accountId = savedInstanceState.getLong(BUNDLE_KEY_ACCOUNT_ID, -1);
        long mailboxId = savedInstanceState.getLong(BUNDLE_KEY_MAILBOX_ID, -1);
        long messageId = savedInstanceState.getLong(BUNDLE_KEY_MESSAGE_ID, -1);
        mMessageListFragmentState = savedInstanceState.getParcelable(BUNDLE_KEY_MESSAGE_LIST_STATE);
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessageListXLFragmentManager: Restoring "
                    + accountId + "," + mailboxId + "," + messageId);
        }
        if (accountId == -1) {
            return;
        }
        // selectAccount() calls selectMailbox/Message() if necessary.
        selectAccount(accountId, mailboxId, messageId);
    }

    /**
     * Call it to select an account.
     *
     * @param accountId account ID.  Must not be -1.
     * @param mailboxId mailbox ID.  Pass -1 to open account's inbox.
     * @param messageId message ID.  Pass -1 to not open a message.
     */
    public void selectAccount(long accountId, long mailboxId, long messageId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "selectAccount mAccountId=" + accountId);
        }
        if (accountId == -1) {
            throw new InvalidParameterException();
        }
        if (mAccountId == accountId) {
            return;
        }

        // Update members.
        mAccountId = accountId;
        mMailboxId = -1;
        mMessageId = -1;

        // In case of "message list -> message view -> change account", we don't have to keep it.
        mMessageListFragmentState = null;

        // Open mailbox list, clear message list / message view
        mMailboxListFragment.openMailboxes(mAccountId);
        mMessageListFragment.clearContent();
        mThreePane.showLeftPane(); // Show mailbox list

        if ((accountId == Account.ACCOUNT_ID_COMBINED_VIEW) && (mailboxId == -1)) {
            // When opening the Combined view, the right pane will be "combined inbox".
            selectMailbox(Mailbox.QUERY_ALL_INBOXES, -1);
        } else if (mailboxId == -1) {
            // Try to find the inbox for the account
            closeMailboxFinder();
            mMailboxFinder = new MailboxFinder(mActivity, mAccountId, Mailbox.TYPE_INBOX, this);
            mMailboxFinder.startLookup();
        } else {
            selectMailbox(mailboxId, messageId);
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
     * Select a mailbox and potentially a message within that mailbox.
     * We assume the mailbox selected here belongs to the account selected with
     * {@link #selectAccount}.
     *
     * @param mailboxId ID of mailbox
     * @param messageId message ID.  Pass -1 to not open a message.
     */
    private void selectMailbox(long mailboxId, long messageId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "selectMailbox mMailboxId=" + mailboxId);
        }
        if (mailboxId == -1) {
            throw new InvalidParameterException();
        }
        if (mMailboxId == mailboxId) {
            return;
        }

        // Update members
        mMailboxId = mailboxId;
        mMessageId = -1;

        // Open mailbox
        if (mMessageListFragmentState != null) {
            mMessageListFragmentState.restore(mMessageListFragment);
            mMessageListFragmentState = null;
        }
        mMessageListFragment.openMailbox(mMailboxId);
        mMailboxListFragment.setSelectedMailbox(mMailboxId);
        mActivity.onMailboxChanged(mAccountId, mMailboxId);

        // If a message ID was specified, show it; otherwise show the mailbox list
        if (messageId == -1) {
            mThreePane.showLeftPane();
        } else {
            selectMessage(messageId);
        }
    }

    /**
     * Select a message to view.
     * We assume the message selected here belongs to the mailbox selected with
     * {@link #selectMailbox}.
     */
    private void selectMessage(long messageId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "selectMessage messageId=" + messageId);
        }
        if (messageId == -1) {
            throw new InvalidParameterException();
        }
        if (mMessageId == messageId) {
            return;
        }

        mMessageListFragmentState = mMessageListFragment.getState();

        // Update member
        mMessageId = messageId;

        // Open message
        mMessageListFragment.setSelectedMessage(mMessageId);
        mMessageViewFragment.openMessage(mMessageId);
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
            MessageListXLFragmentManager.this.onMoveToNewer();
        }

        @Override
        public void onMoveToOlder() {
            MessageListXLFragmentManager.this.onMoveToOlder();
        }
    }

    private void onCurrentMessageGone() {
        switch (Preferences.getPreferences(mActivity).getAutoAdvanceDirection()) {
            case Preferences.AUTO_ADVANCE_NEWER:
                if (onMoveToNewer()) return;
                if (onMoveToOlder()) return;
                break;
            case Preferences.AUTO_ADVANCE_OLDER:
                if (onMoveToOlder()) return;
                if (onMoveToNewer()) return;
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

    private boolean onMoveToOlder() {
        if (isMessageSelected() && (mOrderManager != null)
                && mOrderManager.moveToOlder()) {
            selectMessage(mOrderManager.getCurrentMessageId());
            return true;
        }
        return false;
    }

    private boolean onMoveToNewer() {
        if (isMessageSelected() && (mOrderManager != null)
                && mOrderManager.moveToNewer()) {
            selectMessage(mOrderManager.getCurrentMessageId());
            return true;
        }
        return false;
    }
}
