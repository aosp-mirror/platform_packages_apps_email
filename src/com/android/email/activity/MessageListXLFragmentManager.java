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
import com.android.email.provider.EmailContent.Mailbox;

import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
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

    private ThreePaneLayout mThreePane;

    private MailboxListFragment mMailboxListFragment;
    private MessageListFragment mMessageListFragment;
    private MessageViewFragment mMessageViewFragment;

    private MailboxFinder mMailboxFinder;
    private final MailboxFinderCallback mMailboxFinderCallback = new MailboxFinderCallback();

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
        public FragmentManager getFragmentManager();

        /**
         * Called when the selected account is on security-hold.
         */
        public void onAccountSecurityHold(long accountId);

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

        FragmentManager fm = mTargetActivity.getFragmentManager();
        mMailboxListFragment = (MailboxListFragment) fm.findFragmentById(
                mThreePane.getLeftPaneId());
        mMessageListFragment = (MessageListFragment) fm.findFragmentById(
                mThreePane.getMiddlePaneId());
        mMessageViewFragment = (MessageViewFragment) fm.findFragmentById(
                mThreePane.getRightPaneId());
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
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXLFragmentManager: Restoring "
                    + accountId + "," + mailboxId + "," + messageId);
        }
        if (accountId == -1) {
            return;
        }
        // selectAccount() calls selectMailbox() if necessary
        selectAccount(accountId, mailboxId, false);
        if (messageId == -1) {
            return;
        }
        selectMessage(messageId);
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

    /**
     * Call it to select an account.
     *
     * @param accountId account ID.  Must not be -1.
     * @param mailboxId mailbox ID.  Pass -1 to open account's inbox.
     * @param byExplicitUserAction set true if the user is explicitly opening the mailbox,
     *     in which case we perform "auto-refresh".
     */
    public void selectAccount(long accountId, long mailboxId, boolean byExplicitUserAction) {
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
        hideMessageView();

        if (mailboxId == -1) {
            startInboxLookup();
        } else {
            selectMailbox(mailboxId, byExplicitUserAction);
        }
    }

    /**
     * If the current view is MessageView, go back to MessageList.
     */
    public void goBackToMailbox() {
        if (isMessageSelected()) {
            hideMessageView();
            selectMailbox(getMailboxId(), false);
        }
    }

    /**
     * Call it to select a mailbox.
     *
     * We assume the mailbox selected here belongs to the account selected with
     * {@link #selectAccount}.
     *
     * @param mailboxId ID of mailbox
     * @param byExplicitUserAction set true if the user is explicitly opening the mailbox,
     *     in which case we perform "auto-refresh".
     */
    public void selectMailbox(long mailboxId, boolean byExplicitUserAction) {
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
        hideMessageView();
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
        hideMessageBoxList();
    }

    private void hideMessageBoxList() {
        mThreePane.showRightPane(true);
    }

    private void hideMessageView() {
        mMessageId = -1;
        mThreePane.showRightPane(false);
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
            selectMailbox(mailboxId, true);
        }

        @Override
        public void onMailboxNotFound(long accountId) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "MailboxFinderCallback.onMailboxNotFound");
            }
            // Shouldn't happen
        }
    }
}
