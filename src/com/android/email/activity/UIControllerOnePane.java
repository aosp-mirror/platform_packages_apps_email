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
import com.android.email.activity.MailboxFinder.Callback;
import com.android.email.activity.setup.AccountSecurity;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import java.util.Set;


/**
 * UI Controller for non x-large devices.  Supports a single-pane layout.
 *
 * One one-pane, multiple fragments can be installed at the same time, but only one of them
 * can be "visible" at a time.  Others are in the back stack.  Use {@link #isMailboxListVisible()},
 * {@link #isMessageListVisible()} and {@link #isMessageViewVisible()} to determine which is
 * visible.
 *
 * Note due to the asynchronous nature of the fragment transaction, there is a window when
 * there is no installed or visible fragments.
 *
 * TODO Use the back stack for the message list -> message view navigation, so that the list
 * position/selection will be restored on back.
 *
 * Major TODOs
 * - TODO Newer/Older for message view with swipe!
 * - TODO Implement callbacks
 */
class UIControllerOnePane extends UIControllerBase {
    // TODO Newer/Older buttons not needed.  Remove this.
    private MessageCommandButtonView mMessageCommandButtons;

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
        open(getUIAccountId(), Mailbox.NO_MAILBOX, Message.NO_MESSAGE);
    }

    // MessageListFragment.Callback
    @Override
    public void onMessageOpen(
            long messageId, long messageMailboxId, long listMailboxId, int type) {
        if (type == MessageListFragment.Callback.TYPE_DRAFT) {
            MessageCompose.actionEditDraft(mActivity, messageId);
        } else {
            open(getUIAccountId(), getMailboxId(), messageId);
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
            return isMessageViewVisible()
                     || (isMailboxListVisible() && !getMailboxListFragment().isRoot());
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
    }

    @Override
    public void restoreInstanceState(Bundle savedInstanceState) {
        super.restoreInstanceState(savedInstanceState);
    }

    @Override
    public int getLayoutId() {
        return R.layout.email_activity_one_pane;
    }

    @Override
    public void onActivityViewReady() {
        super.onActivityViewReady();

        mMessageCommandButtons = UiUtilities.getView(mActivity, R.id.message_command_buttons);
        mMessageCommandButtons.setCallback(new CommandButtonCallback());
    }

    @Override
    public void onActivityCreated() {
        super.onActivityCreated();
    }

    @Override
    public void onActivityResume() {
        super.onActivityResume();
        refreshActionBar();
    }

    /** @return true if a {@link MailboxListFragment} is installed and visible. */
    private final boolean isMailboxListVisible() {
        return isMailboxListInstalled();
    }

    /** @return true if a {@link MessageListFragment} is installed and visible. */
    private final boolean isMessageListVisible() {
        return isMessageListInstalled();
    }

    /** @return true if a {@link MessageViewFragment} is installed and visible. */
    private final boolean isMessageViewVisible() {
        return isMessageViewInstalled();
    }

    @Override
    public long getUIAccountId() {
        // Get it from the visible fragment.
        if (isMailboxListVisible()) {
            return getMailboxListFragment().getAccountId();
        }
        if (isMessageListVisible()) {
            return getMessageListFragment().getAccountId();
        }
        if (isMessageViewVisible()) {
            return getMessageViewFragment().getOpenerAccountId();
        }
        return Account.NO_ACCOUNT;
    }

    private long getMailboxId() {
        // Get it from the visible fragment.
        if (isMessageListVisible()) {
            return getMessageListFragment().getMailboxId();
        }
        if (isMessageViewVisible()) {
            return getMessageViewFragment().getOpenerMailboxId();
        }
        return Mailbox.NO_MAILBOX;
    }

    private long getMessageId() {
        // Get it from the visible fragment.
        if (isMessageViewVisible()) {
            return getMessageViewFragment().getMessageId();
        }
        return Message.NO_MESSAGE;
    }

    private final MailboxFinder.Callback mInboxLookupCallback = new MailboxFinder.Callback() {
        @Override
        public void onMailboxFound(long accountId, long mailboxId) {
            // Inbox found.
            openMailbox(accountId, mailboxId);
        }

        @Override
        public void onAccountNotFound() {
            // Account removed?
            Welcome.actionStart(mActivity);
        }

        @Override
        public void onMailboxNotFound(long accountId) {
            // Inbox not found??
            Welcome.actionStart(mActivity);
        }

        @Override
        public void onAccountSecurityHold(long accountId) {
            mActivity.startActivity(AccountSecurity.actionUpdateSecurityIntent(mActivity, accountId,
                    true));
        }
    };

    @Override
    protected Callback getInboxLookupCallback() {
        return mInboxLookupCallback;
    }

    @Override
    public boolean onBackPressed(boolean isSystemBackKey) {
        if (isMessageViewVisible()) {
            openMailbox(getMessageViewFragment().getOpenerAccountId(),
                    getMessageViewFragment().getOpenerMailboxId());
            return true;
        } else if (isMailboxListVisible() && getMailboxListFragment().navigateUp()) {
            return true;
        }
        return false;
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

        if ((getUIAccountId() == accountId) && (getMailboxId() == mailboxId)
                && (getMessageId() == messageId)) {
            return;
        }

        if (messageId != Message.NO_MESSAGE) {
            showMessageView(accountId, mailboxId, messageId);
        } else if (mailboxId != Mailbox.NO_MAILBOX) {
            showMessageList(accountId, mailboxId);
        } else {
            // Mailbox not specified.  Open Inbox or Combined Inbox.
            if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
                showMessageList(accountId, Mailbox.QUERY_ALL_INBOXES);
            } else {
                startInboxLookup(accountId);
            }
        }
    }

    private void uninstallAllFragments(FragmentTransaction ft) {
        if (isMailboxListInstalled()) {
            uninstallMailboxListFragment(ft);
        }
        if (isMessageListInstalled()) {
            uninstallMessageListFragment(ft);
        }
        if (isMessageViewInstalled()) {
            uninstallMessageViewFragment(ft);
        }
    }

    private void showMailboxList(long accountId, long mailboxId) {
        showFragment(MailboxListFragment.newInstance(accountId, mailboxId, false));
    }

    private void showMessageList(long accountId, long mailboxId) {
        showFragment(MessageListFragment.newInstance(accountId, mailboxId));
    }

    private void showMessageView(long accountId, long mailboxId, long messageId) {
        showFragment(MessageViewFragment.newInstance(accountId, mailboxId, messageId));
    }

    private void showFragment(Fragment fragment) {
        final FragmentTransaction ft = mActivity.getFragmentManager().beginTransaction();
        uninstallAllFragments(ft);
        ft.add(R.id.fragment_placeholder, fragment);
        commitFragmentTransaction(ft);
    }

    private void showAllMailboxes() {
        if (!isAccountSelected()) {
            return; // Can happen because of asyncronous fragment transactions.
        }
        // Don't use open(account, NO_MAILBOX, NO_MESSAGE).  This is used to open the default
        // view, which is Inbox on the message list.
        showMailboxList(getUIAccountId(), Mailbox.NO_MAILBOX);
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

    @Override
    public boolean onPrepareOptionsMenu(MenuInflater inflater, Menu menu) {
        // STOPSHIP For temporary menu item which should be visible only on 1-pane.
        menu.findItem(R.id.show_all_folders).setVisible(true);
        return super.onPrepareOptionsMenu(inflater, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.show_all_folders: // STOPSHIP For temporary menu item
                showAllMailboxes();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
