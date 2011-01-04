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
import com.android.email.R;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;

import android.app.ActionBar;
import android.app.FragmentManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import java.security.InvalidParameterException;

/**
 * A class manages what are showing on {@link MessageListXL} (i.e. account id, mailbox id, and
 * message id), and show/hide fragments accordingly.
 *
 * TODO Highlight selected message on message list
 *
 * TODO: Test it.  It's testable if we implement MockFragmentTransaction, which may be too early
 * to do so at this point.  (API may not be stable enough yet.)
 */
class MessageListXLFragmentManager {
    private static final String BUNDLE_KEY_ACCOUNT_ID = "MessageListXl.state.account_id";
    private static final String BUNDLE_KEY_MAILBOX_ID = "MessageListXl.state.mailbox_id";
    private static final String BUNDLE_KEY_MESSAGE_ID = "MessageListXl.state.message_id";
    private static final String BUNDLE_KEY_MESSAGE_LIST_STATE
            = "MessageListXl.state.message_list_state";

    private final Context mContext;

    private boolean mIsActivityResumed;

    /** Current account id. (-1 = not selected) */
    private long mAccountId = -1;

    /** Current mailbox id. (-1 = not selected) */
    private long mMailboxId = -1;

    /** Current message id. (-1 = not selected) */
    private long mMessageId = -1;

    private ActionBar mActionBar;
    private ThreePaneLayout mThreePane;
    private View mActionBarMailboxNameView;
    private TextView mActionBarMailboxName;
    private TextView mActionBarUnreadCount;

    private MailboxListFragment mMailboxListFragment;
    private MessageListFragment mMessageListFragment;
    private MessageViewFragment mMessageViewFragment;

    private MailboxFinder mMailboxFinder;
    private final MailboxFinderCallback mMailboxFinderCallback = new MailboxFinderCallback();
    private final ThreePaneLayoutCallback mThreePaneLayoutCallback = new ThreePaneLayoutCallback();

    /**
     * Save state for the "message list -> message view -[back press]-> message list" transition.
     */
    private MessageListFragment.State mMessageListFragmentState;

    /**
     * The interface that {@link MessageListXL} implements.  We don't call its methods directly,
     * in the hope that it'll make writing tests easier, and make it clear which methods are needed
     * for MessageListXLFragmentManager.
     */
    public interface TargetActivity {
        public ActionBar getActionBar();
        public FragmentManager getFragmentManager();

        /**
         * Called when the selected account is on security-hold.
         */
        public void onAccountSecurityHold(long accountId);

        /**
         * Called when the current account has changed.
         */
        public void onAccountChanged(long accountId);

        /**
         * Called when the current mailbox has changed.
         */
        public void onMailboxChanged(long accountId, long newMailboxId);

        public View findViewById(int id);
    }

    private final TargetActivity mTargetActivity;

    public MessageListXLFragmentManager(MessageListXL activity) {
        mContext = activity;
        mTargetActivity = activity;
    }

    /**
     * Must be called just after the activity sets up the content view.
     *
     * (Due to the complexity regarding class/activity initialization order, we can't do this in
     * the constructor.)
     */
    public void onActivityViewReady() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXLFragmentManager.onActivityViewReady");
        }
        mThreePane = (ThreePaneLayout) mTargetActivity.findViewById(R.id.three_pane);
        mThreePane.setCallback(mThreePaneLayoutCallback);

        FragmentManager fm = mTargetActivity.getFragmentManager();
        mMailboxListFragment = (MailboxListFragment) fm.findFragmentById(
                mThreePane.getLeftPaneId());
        mMessageListFragment = (MessageListFragment) fm.findFragmentById(
                mThreePane.getMiddlePaneId());
        mMessageViewFragment = (MessageViewFragment) fm.findFragmentById(
                mThreePane.getRightPaneId());

        mActionBar = mTargetActivity.getActionBar();

        // Set a view for the current mailbox to the action bar.
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        mActionBarMailboxNameView = inflater.inflate(R.layout.action_bar_current_mailbox, null);
        mActionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM, ActionBar.DISPLAY_SHOW_CUSTOM);
        mActionBar.setCustomView(mActionBarMailboxNameView);

        mActionBarMailboxName =
                (TextView) mActionBarMailboxNameView.findViewById(R.id.mailbox_name);
        mActionBarUnreadCount =
                (TextView) mActionBarMailboxNameView.findViewById(R.id.unread_count);
    }

    /** Set callback for fragment. */
    public void setMailboxListFragmentCallback(
            MailboxListFragment.Callback mailboxListFragmentCallback) {
        mMailboxListFragment.setCallback(mailboxListFragmentCallback);
    }

    /** Set callback for fragment. */
    public void setMessageListFragmentCallback(
            MessageListFragment.Callback messageListFragmentCallback) {
        mMessageListFragment.setCallback(messageListFragmentCallback);
    }

    /** Set callback for fragment. */
    public void setMessageViewFragmentCallback(
            MessageViewFragment.Callback messageViewFragmentCallback) {
        mMessageViewFragment.setCallback(messageViewFragmentCallback);
    }

    public long getAccountId() {
        return mAccountId;
    }

    public long getMailboxId() {
        return mMailboxId;
    }

    public long getMessageId() {
        return mMessageId;
    }

    public boolean isAccountSelected() {
        return getAccountId() != -1;
    }

    public boolean isMailboxSelected() {
        return getMailboxId() != -1;
    }

    public boolean isMessageSelected() {
        return getMessageId() != -1;
    }

    public MailboxListFragment getMailboxListFragment() {
        return mMailboxListFragment;
    }

    public MessageListFragment getMessageListFragment() {
        return mMessageListFragment;
    }

    public MessageViewFragment getMessageViewFragment() {
        return mMessageViewFragment;
    }

    /**
     * Called from {@link MessageListXL#onStart}.
     */
    public void onStart() {
        // Nothing to do
    }

    /**
     * Called from {@link MessageListXL#onResume}.
     */
    public void onResume() {
        if (mIsActivityResumed) {
            return;
        }
        mIsActivityResumed = true;

        updateActionBar();
    }

    /**
     * Called from {@link MessageListXL#onPause}.
     */
    public void onPause() {
        if (!mIsActivityResumed) {
            return;
        }
        mIsActivityResumed = false;
        saveMessageListFragmentState();
    }

    /**
     * Called from {@link MessageListXL#onStop}.
     */
    public void onStop() {
        // Nothing to do
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
            Log.d(Email.LOG_TAG, "MessageListXLFragmentManager: Restoring "
                    + accountId + "," + mailboxId + "," + messageId);
        }
        if (accountId == -1) {
            return;
        }
        // selectAccount() calls selectMailbox/Message() if necessary.
        selectAccount(accountId, mailboxId, messageId, false);
    }

    private void saveMessageListFragmentState() {
        if (mMessageListFragment != null) {
            mMessageListFragmentState = mMessageListFragment.getState();
        }
    }

    private void restoreMesasgeListState() {
        if ((mMessageListFragment != null) && (mMessageListFragmentState != null)) {
            mMessageListFragmentState.restore(mMessageListFragment);
            mMessageListFragmentState = null;
        }
    }

    private void updateActionBar() {
        // If the left pane (mailbox list pane) is hidden, the back action on action bar will be
        // enabled, and we also show the current mailbox name.
        final int visiblePanes = mThreePane.getVisiblePanes();
        final boolean leftPaneHidden = ((visiblePanes & ThreePaneLayout.PANE_LEFT) == 0);
        mActionBar.setDisplayOptions(leftPaneHidden ? ActionBar.DISPLAY_HOME_AS_UP : 0,
                ActionBar.DISPLAY_HOME_AS_UP);
        mActionBarMailboxNameView.setVisibility(leftPaneHidden ? View.VISIBLE : View.GONE);
    }

    public void setCurrentMailboxName(String mailboxName, int unreadCount) {
        mActionBarMailboxName.setText(mailboxName);
        if (unreadCount == 0) {
            // No unread messages, or it's the mailbox doesn't have the idea of "unread".
            // (e.g. outbox)
            mActionBarUnreadCount.setText("");
        } else {
            mActionBarUnreadCount.setText(Integer.toString(unreadCount));
        }
    }

    /**
     * Call it to select an account.
     *
     * @param accountId account ID.  Must not be -1.
     * @param mailboxId mailbox ID.  Pass -1 to open account's inbox.
     * @param messageId message ID.  Pass -1 to not open a message.
     * @param byExplicitUserAction set true if the user is explicitly opening the mailbox,
     *     in which case we perform "auto-refresh".
     */
    public void selectAccount(long accountId, long mailboxId, long messageId,
            boolean byExplicitUserAction) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "selectAccount mAccountId=" + accountId);
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
            selectMailbox(Mailbox.QUERY_ALL_INBOXES, -1, false);
        } else if (mailboxId == -1) {
            startInboxLookup();
        } else {
            selectMailbox(mailboxId, messageId, byExplicitUserAction);
        }
        mTargetActivity.onAccountChanged(mAccountId);
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
     * If the current view is MessageView, go back to MessageList.
     */
    public void goBackToMailbox() {
        if (isMessageSelected()) {
            mThreePane.showLeftPane(); // Show mailbox list
        }
    }

    /**
     * Call it to select a mailbox.
     *
     * We assume the mailbox selected here belongs to the account selected with
     * {@link #selectAccount}.
     *
     * @param mailboxId ID of mailbox
     * @param messageId message ID.  Pass -1 to not open a message.
     * @param byExplicitUserAction set true if the user is explicitly opening the mailbox,
     *     in which case we perform "auto-refresh".
     */
    public void selectMailbox(long mailboxId, long messageId, boolean byExplicitUserAction) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "selectMailbox mMailboxId=" + mailboxId);
        }
        if (mailboxId == -1) {
            throw new InvalidParameterException();
        }

        if (mMailboxId == mailboxId) {
            return;
        }

        // Update members.
        mMailboxId = mailboxId;
        mMessageId = -1;

        // Open mailbox
        if (byExplicitUserAction) {
            mMessageListFragment.doAutoRefresh();
        }
        mMessageListFragment.openMailbox(mMailboxId);
        restoreMesasgeListState();

        mMailboxListFragment.setSelectedMailbox(mMailboxId);
        mTargetActivity.onMailboxChanged(mAccountId, mMailboxId);
        if (messageId == -1) {
            mThreePane.showLeftPane(); // Show mailbox list
        } else {
            selectMessage(messageId);
        }
    }

    /**
     * Call it to select a mailbox.
     *
     * We assume the message passed here belongs to the account/mailbox selected with
     * {@link #selectAccount} and {@link #selectMailbox}.
     */
    public void selectMessage(long messageId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "selectMessage messageId=" + messageId);
        }
        if (messageId == -1) {
            throw new InvalidParameterException();
        }
        if (mMessageId == messageId) {
            return;
        }

        saveMessageListFragmentState();

        // Update member.
        mMessageId = messageId;

        // Open message
        mMessageListFragment.setSelectedMessage(mMessageId);
        mMessageViewFragment.openMessage(mMessageId);
        mThreePane.showRightPane(); // Show message view
    }

    /**
     * Unselect the currently viewed message, if any, and release the resoruce grabbed by the
     * message view.
     *
     * This must be called when the three pane reports that the message view pane gets hidden.
     */
    private void onMessageViewClosed() {
        mMessageId = -1;
        mMessageListFragment.setSelectedMessage(-1);
        mMessageViewFragment.clearContent();
    }

    private void startInboxLookup() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "startLookForInbox account=" + mAccountId);
        }
        closeMailboxFinder();
        mMailboxFinder = new MailboxFinder(mContext, mAccountId, Mailbox.TYPE_INBOX,
                mMailboxFinderCallback);
        mMailboxFinder.startLookup();
    }

    private void closeMailboxFinder() {
        if (mMailboxFinder != null) {
            mMailboxFinder.cancel();
            mMailboxFinder = null;
        }
    }

    private class MailboxFinderCallback implements MailboxFinder.Callback {
        @Override
        public void onAccountNotFound() {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "MailboxFinderCallback.onAccountNotFound");
            }
            // Shouldn't happen
        }

        @Override
        public void onAccountSecurityHold(long accountId) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "MailboxFinderCallback.onAccountSecurityHold");
            }
            mTargetActivity.onAccountSecurityHold(accountId);
        }

        @Override
        public void onMailboxFound(long accountId, long mailboxId) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "  Found inbox");
            }
            selectMailbox(mailboxId, -1, true);
        }

        @Override
        public void onMailboxNotFound(long accountId) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "MailboxFinderCallback.onMailboxNotFound");
            }
            // Shouldn't happen
        }
    }

    private class ThreePaneLayoutCallback implements ThreePaneLayout.Callback {
        @Override
        public void onVisiblePanesChanged(int previousVisiblePanes) {
            updateActionBar();
            final int visiblePanes = mThreePane.getVisiblePanes();
            if (((visiblePanes & ThreePaneLayout.PANE_RIGHT) == 0) &&
                    ((previousVisiblePanes & ThreePaneLayout.PANE_RIGHT) != 0)) {
                // Message view just got hidden
                onMessageViewClosed();
            }
            mMessageListFragment.setVisibility((visiblePanes & ThreePaneLayout.PANE_MIDDLE) != 0);
        }
    }
}
