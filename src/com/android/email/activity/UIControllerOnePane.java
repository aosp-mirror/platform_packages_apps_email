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
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;

import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.util.Log;

import java.util.Set;


/**
 * UI Controller for non x-large devices.  Supports a single-pane layout.
 *
 * STOPSHIP Everything in this class is 100% temporary at this point
 * - Navigation model is different from what it should be (whatever it'll be).
 *   e.g. when the app is launched, we should show Inbox, not mailbox list.
 *
 * - It uses the two-pane action bar only so that we can change accounts
 *
 * Major TODOs
 * - TODO Proper Navigation model, including retaining fragments to keep state such as the scroll
 *        position and batch selection.
 * - TODO Nested folders
 * - TODO Newer/Older for message view with swipe!
 * - TODO Implement callbacks
 */
class UIControllerOnePane extends UIControllerBase {
    private ActionBarController mActionBarController;

    /**
     * Current account/mailbox/message IDs.
     * Don't use them directly; use the accessors instead, as we might want to get them from the
     * topmost fragment in the future.
     */
    private long mCurrentAccountId = Account.NO_ACCOUNT;
    private long mCurrentMailboxId = Mailbox.NO_MAILBOX;
    private long mCurrentMessageId = Message.NO_MESSAGE;

    private MessageCommandButtonView mMessageCommandButtons;

    private final MailboxListFragment.Callback mMailboxListFragmentCallback =
            new MailboxListFragment.Callback() {
        @Override
        public void onAccountSelected(long accountId) {
            switchAccount(accountId);
        }

        @Override
        public void onCurrentMailboxUpdated(long mailboxId, String mailboxName, int unreadCount) {
        }

        @Override
        public void onMailboxSelected(long accountId, long mailboxId, boolean navigate) {
            openMailbox(accountId, mailboxId);
        }

        @Override
        public void onMailboxSelectedForDnD(long mailboxId) {
            // No drag&drop on 1-pane
        }
    };

    private final MessageListFragment.Callback mMessageListFragmentCallback =
            new MessageListFragment.Callback() {
        @Override
        public void onAdvancingOpAccepted(Set<Long> affectedMessages) {
            // Nothing to do on 1 pane.
        }

        @Override
        public void onEnterSelectionMode(boolean enter) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onListLoaded() {
            // TODO Auto-generated method stub

        }

        @Override
        public void onMailboxNotFound() {
            open(getUIAccountId(), Mailbox.NO_MAILBOX, Message.NO_MESSAGE);
        }

        @Override
        public void onMessageOpen(
                long messageId, long messageMailboxId, long listMailboxId, int type) {
            if (type == MessageListFragment.Callback.TYPE_DRAFT) {
                MessageCompose.actionEditDraft(mActivity, messageId);
            } else {
                open(getUIAccountId(), getMailboxId(), messageId);
            }
        }

        @Override
        public boolean onDragStarted() {
            // No drag&drop on 1-pane
            return false;
        }

        @Override
        public void onDragEnded() {
            // No drag&drop on 1-pane
        }
    };

    private final MessageViewFragment.Callback mMessageViewFragmentCallback =
            new MessageViewFragment.Callback() {
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

        @Override
        public void onCalendarLinkClicked(long epochEventStartTime) {
            ActivityHelper.openCalendar(mActivity, epochEventStartTime);
        }

        @Override
        public boolean onUrlInMessageClicked(String url) {
            return ActivityHelper.openUrlInMessage(mActivity, url, getActualAccountId());
        }

        @Override
        public void onBeforeMessageGone() {
            // TODO Auto-generated method stub

        }

        @Override
        public void onMessageSetUnread() {
            // TODO Auto-generated method stub

        }

        @Override
        public void onRespondedToInvite(int response) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onLoadMessageError(String errorMessage) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onLoadMessageFinished() {
            // TODO Auto-generated method stub

        }

        @Override
        public void onLoadMessageStarted() {
            // TODO Auto-generated method stub

        }

        @Override
        public void onMessageNotExists() {
            // TODO Auto-generated method stub

        }

        @Override
        public void onMessageViewDestroyed() {
            // TODO Auto-generated method stub

        }

        @Override
        public void onMessageShown() {
            // TODO Auto-generated method stub

        }
    };

    // This is all temporary as we'll have a different action bar controller for 1-pane.
    private final ActionBarController.Callback mActionBarControllerCallback
            = new ActionBarController.Callback() {
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
            // Always show the UP arrow.
            return true;
        }

        @Override
        public long getUIAccountId() {
            return UIControllerOnePane.this.getUIAccountId();
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
    };

    public UIControllerOnePane(EmailActivity activity) {
        super(activity);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(BUNDLE_KEY_ACCOUNT_ID, mCurrentAccountId);
        outState.putLong(BUNDLE_KEY_MAILBOX_ID, mCurrentMailboxId);
        outState.putLong(BUNDLE_KEY_MESSAGE_ID, mCurrentMessageId);
    }

    @Override
    public void restoreInstanceState(Bundle savedInstanceState) {
        super.restoreInstanceState(savedInstanceState);
        mCurrentAccountId = savedInstanceState.getLong(BUNDLE_KEY_ACCOUNT_ID, Account.NO_ACCOUNT);
        mCurrentMailboxId = savedInstanceState.getLong(BUNDLE_KEY_MAILBOX_ID, Mailbox.NO_MAILBOX);
        mCurrentMessageId = savedInstanceState.getLong(BUNDLE_KEY_MESSAGE_ID, Message.NO_MESSAGE);
    }

    @Override
    public int getLayoutId() {
        return R.layout.email_activity_one_pane;
    }

    @Override
    public void onActivityViewReady() {
        super.onActivityViewReady();
        mActionBarController = new ActionBarController(mActivity, mActivity.getLoaderManager(),
                mActivity.getActionBar(), mActionBarControllerCallback);

        mMessageCommandButtons = UiUtilities.getView(mActivity, R.id.message_command_buttons);
        mMessageCommandButtons.setCallback(new CommandButtonCallback());
    }

    @Override
    public void onActivityCreated() {
        super.onActivityCreated();
        mActionBarController.onActivityCreated();
    }

    @Override
    public void onActivityResume() {
        super.onActivityResume();
        refreshActionBar();
    }

    @Override
    public long getUIAccountId() {
        return mCurrentAccountId;
    }

    private long getMailboxId() {
        return mCurrentMailboxId;
    }

    private long getMessageId() {
        return mCurrentMessageId;
    }

    private void refreshActionBar() {
        if (mActionBarController != null) {
            mActionBarController.refresh();
        }
        mActivity.invalidateOptionsMenu();
    }

    private boolean isMailboxListVisible() {
        return (getMailboxId() == Mailbox.NO_MAILBOX);
    }

    private boolean isMessageListVisible() {
        return (getMailboxId() != Mailbox.NO_MAILBOX) && (getMessageId() == Message.NO_MESSAGE);
    }

    private boolean isMessageViewVisible() {
        return (getMailboxId() != Mailbox.NO_MAILBOX) && (getMessageId() != Message.NO_MESSAGE);
    }

    @Override
    public boolean onBackPressed(boolean isSystemBackKey) {
        if (isMessageViewVisible()) {
            open(getUIAccountId(), getMailboxId(), Message.NO_MESSAGE);
            return true;
        } else if (isMessageListVisible()) {
            open(getUIAccountId(), Mailbox.NO_MAILBOX, Message.NO_MESSAGE);
            return true;
        } else {
            // STOPSHIP Remove this and return false.  This is so that the app can be closed
            // with the UP press.  (usuful when the device doesn't have a HW back key.)
            mActivity.finish();
            return true;
//          return false;
        }
    }

    @Override
    protected void installMailboxListFragment(MailboxListFragment fragment) {
        fragment.setCallback(mMailboxListFragmentCallback);
    }

    @Override
    protected void installMessageListFragment(MessageListFragment fragment) {
        fragment.setCallback(mMessageListFragmentCallback);
    }

    @Override
    protected void installMessageViewFragment(MessageViewFragment fragment) {
        fragment.setCallback(mMessageViewFragmentCallback);
    }

    @Override
    public void open(final long accountId, final long mailboxId, final long messageId) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " open accountId=" + accountId
                    + " mailboxId=" + mailboxId + " messageId=" + messageId);
        }
        if (accountId == Account.NO_ACCOUNT) {
            throw new IllegalArgumentException();
        }

        // !!! It's all temporary to make 1 pane UI (barely) usable !!!
        //
        // - Nested folders still doesn't work
        // - When opening a child view (e.g. message list -> message view), we should retain
        //   the current fragment so that all the state (selection, scroll position, etc) will be
        //   restored when back.

        if ((getUIAccountId() == accountId) && (getMailboxId() == mailboxId)
                && (getMessageId() == messageId)) {
            return;
        }

        final FragmentManager fm = mActivity.getFragmentManager();
        final FragmentTransaction ft = fm.beginTransaction();

        if (messageId != Message.NO_MESSAGE) {
            ft.replace(R.id.fragment_placeholder, MessageViewFragment.newInstance(messageId));

        } else if (mailboxId != Mailbox.NO_MAILBOX) {
            ft.replace(R.id.fragment_placeholder, MessageListFragment.newInstance(
                    accountId, mailboxId));

        } else {
            ft.replace(R.id.fragment_placeholder,
                    MailboxListFragment.newInstance(accountId, Mailbox.NO_MAILBOX));
        }

        mCurrentAccountId = accountId;
        mCurrentMailboxId = mailboxId;
        mCurrentMessageId = messageId;

        commitFragmentTransaction(ft);

        refreshActionBar();
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

    private class CommandButtonCallback implements MessageCommandButtonView.Callback {
        @Override
        public void onMoveToNewer() {
            // TODO
        }

        @Override
        public void onMoveToOlder() {
            // TODO
        }
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
