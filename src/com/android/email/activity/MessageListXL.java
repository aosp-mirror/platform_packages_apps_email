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
import com.android.email.RefreshManager;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.activity.setup.AccountSetupBasics;

import android.app.ActionBar;
import android.app.Activity;
import android.app.Fragment;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

// TODO Where/when/how do we close loaders??  Do we have to?  Getting this error:
// Finalizing a Cursor that has not been deactivated or closed.
// database = /data/data/com.google.android.email/databases/EmailProvider.db,
// table = Account, query = SELECT _id, displayName, emailAddress FROM Account

/**
 * The main (two-pane) activity for XL devices.
 *
 * TODO Refresh account list when adding/removing/changing(e.g. display name) accounts.
 *      -> Need the MessageList.onResume logic.  Figure out a clean way to do that.
 */
public class MessageListXL extends Activity implements View.OnClickListener,
        MessageListXLFragmentManager.TargetActivity {
    private static final String EXTRA_ACCOUNT_ID = "com.android.email.activity._ACCOUNT_ID";
    private static final int LOADER_ID_ACCOUNT_LIST = 0;

    private Context mContext;
    private RefreshManager mRefreshManager;
    private final RefreshListener mMailRefreshManagerListener
            = new RefreshListener();

    private View mMessageViewButtonPanel;
    private View mMoveToNewerButton;
    private View mMoveToOlderButton;

    private AccountSelectorAdapter mAccountsSelectorAdapter;
    private final ActionBarNavigationCallback mActionBarNavigationCallback
            = new ActionBarNavigationCallback();

    private MessageOrderManager mOrderManager;

    private final MessageListXLFragmentManager mFragmentManager
            = new MessageListXLFragmentManager(this);

    private final MessageOrderManagerCallback mMessageOrderManagerCallback
            = new MessageOrderManagerCallback();

    /**
     * Launch and open account's inbox.
     */
    public static void actionStart(Activity fromActivity, long accountId) {
        Intent i = new Intent(fromActivity, MessageListXL.class);
        if (accountId != -1) {
            i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        }
        fromActivity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.message_list_xl);

        final boolean isRestoring = (savedInstanceState != null);

        mContext = getApplicationContext();
        mRefreshManager = RefreshManager.getInstance(this);
        mRefreshManager.registerListener(mMailRefreshManagerListener);

        mFragmentManager.setMailboxListFragmentCallback(new MailboxListFragmentCallback());
        mFragmentManager.setMessageListFragmentCallback(new MessageListFragmentCallback());
        mFragmentManager.setMessageViewFragmentCallback(new MessageViewFragmentCallback());

        mMessageViewButtonPanel = findViewById(R.id.message_view_buttons);
        mMoveToNewerButton = findViewById(R.id.moveToNewer);
        mMoveToOlderButton = findViewById(R.id.moveToOlder);
        mMoveToNewerButton.setOnClickListener(this);
        mMoveToOlderButton.setOnClickListener(this);
        findViewById(R.id.delete).setOnClickListener(this);
        findViewById(R.id.unread).setOnClickListener(this);
        findViewById(R.id.reply).setOnClickListener(this);
        findViewById(R.id.reply_all).setOnClickListener(this);
        findViewById(R.id.forward).setOnClickListener(this);

        mAccountsSelectorAdapter = new AccountSelectorAdapter(mContext, null);

        if (isRestoring) {
            mFragmentManager.loadState(savedInstanceState);
        } else {
            initFromIntent();
        }
        loadAccounts();
    }

    private void initFromIntent() {
        final Intent i = getIntent();
        final long accountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        if (accountId != -1) {
            mFragmentManager.selectAccount(accountId);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXL onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        mFragmentManager.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onStart");
        super.onStart();

        mFragmentManager.onStart();

        if (mFragmentManager.isMessageSelected()) {
            updateMessageOrderManager();
        }
    }

    @Override
    protected void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onResume");
        super.onResume();
        // TODO Add stuff that's done in MessageList.onResume().
    }

    @Override
    protected void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onPause");
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onStop");
        super.onStop();

        mFragmentManager.onStop();
        stopMessageOrderManager();
    }

    @Override
    protected void onDestroy() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onDestroy");
        mRefreshManager.unregisterListener(mMailRefreshManagerListener);
        super.onDestroy();
    }

    @Override
    public void onAttachFragment(Fragment fragment) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXL onAttachFragment " + fragment.getClass());
        }
        super.onAttachFragment(fragment);
        mFragmentManager.onAttachFragment(fragment);
    }

    @Override
    public void onBackPressed() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXL onBackPressed");
        }
        if (mFragmentManager.isMessageSelected()) {
            // Go back to the message list.
            // TODO: This works for now, but it doesn't restore the list view state, e.g. scroll
            // position.
            // TODO: FragmentTransaction *does* support backstack, but the behavior isn't too clear
            // at this point.
            openMailbox();
        } else {
            // Perform the default behavior == close the activity.
            super.onBackPressed();
        }
    }

    /**
     * (Re-)open the current mailbox.  Used to go back to MessageList from MessageView.
     */
    private void openMailbox() {
        if (mFragmentManager.isMailboxSelected()) {
            mFragmentManager.selectMailbox(mFragmentManager.getMailboxId(), false);
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.moveToOlder:
                moveToOlder();
                break;
            case R.id.moveToNewer:
                moveToNewer();
                break;
            case R.id.delete:
                onDeleteMessage();
                break;
            case R.id.unread:
                onSetMessageUnread();
                break;
            case R.id.reply:
                MessageCompose.actionReply(this, mFragmentManager.getMessageId(), false);
                break;
            case R.id.reply_all:
                MessageCompose.actionReply(this, mFragmentManager.getMessageId(), true);
                break;
            case R.id.forward:
                MessageCompose.actionForward(this, mFragmentManager.getMessageId());
                break;
        }
    }

    private void onDeleteMessage() {
        // the delete triggers mCursorObserver in MessageOrderManager.
        // first move to older/newer before the actual delete
        long messageIdToDelete = mFragmentManager.getMessageId();
        if (!moveToOlder()) moveToNewer();
        ActivityHelper.deleteMessage(this, messageIdToDelete);
        // If this was the last message, moveToOlder/Newer didn't move the current position.
        // MessageOrderManager detects the current message is gone, and we go back to the message
        // list in onMessageNotFound().
    }

    private void onSetMessageUnread() {
        MessageViewFragment f = mFragmentManager.getMessageViewFragment();
        f.onMarkMessageAsRead(false);
        openMailbox();
    }

    /**
     * Start {@link MessageOrderManager} if not started, and sync it to the current message.
     */
    private void updateMessageOrderManager() {
        if (!mFragmentManager.isMailboxSelected()) {
            return;
        }
        final long mailboxId = mFragmentManager.getMailboxId();
        if (mOrderManager == null || mOrderManager.getMailboxId() != mailboxId) {
            stopMessageOrderManager();
            mOrderManager = new MessageOrderManager(this, mailboxId, mMessageOrderManagerCallback);
        }
        if (mFragmentManager.isMessageSelected()) {
            mOrderManager.moveTo(mFragmentManager.getMessageId());
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
            openMailbox();
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
     * Called when the default account is not found, i.e. there's no account set up.
     */
    private void onNoAccountFound() {
        // Open Welcome, which in turn shows the adding a new account screen.
        Welcome.actionStart(this);
        finish();
        return;
    }

    /**
     * Disable/enable the previous/next buttons for the message view.
     */
    private void updateNavigationArrows() {
        mMoveToNewerButton.setEnabled((mOrderManager != null) && mOrderManager.canMoveToNewer());
        mMoveToOlderButton.setEnabled((mOrderManager != null) && mOrderManager.canMoveToOlder());
    }

    private boolean moveToOlder() {
        if (mFragmentManager.isMessageSelected() && (mOrderManager != null)
                && mOrderManager.moveToOlder()) {
            mFragmentManager.selectMessage(mOrderManager.getCurrentMessageId());
            return true;
        }
        return false;
    }

    private boolean moveToNewer() {
        if (mFragmentManager.isMessageSelected() && (mOrderManager != null)
                && mOrderManager.moveToNewer()) {
            mFragmentManager.selectMessage(mOrderManager.getCurrentMessageId());
            return true;
        }
        return false;
    }

    private class MailboxListFragmentCallback implements MailboxListFragment.Callback {
        @Override
        public void onMailboxSelected(long accountId, long mailboxId) {
            mFragmentManager.selectMailbox(mailboxId, true);
        }
    }

    private class MessageListFragmentCallback implements MessageListFragment.Callback {
        @Override
        public void onMessageOpen(long messageId, long messageMailboxId, long listMailboxId,
                int type) {
            if (type == MessageListFragment.Callback.TYPE_DRAFT) {
                MessageCompose.actionEditDraft(MessageListXL.this, messageId);
            } else {
                // TODO Disable reply/forward for messages in trash.
                // First, need to figure out what to do with these buttons for MessageViewFragment.
                mFragmentManager.selectMessage(messageId);
            }
        }

        @Override
        public void onMailboxNotFound() {
            // TODO: What to do??
        }
    }

    private class MessageViewFragmentCallback implements MessageViewFragment.Callback {
        @Override
        public boolean onUrlInMessageClicked(String url) {
            return ActivityHelper.openUrlInMessage(MessageListXL.this, url,
                    mFragmentManager.getAccountId());
        }

        @Override
        public void onMessageSetUnread() {
            openMailbox();
        }

        @Override
        public void onMessageNotExists() {
            openMailbox();
        }

        @Override
        public void onLoadMessageStarted() {
            // We show indeterminate progress on one-pane.
            // TODO Any nice UI for this?
        }

        @Override
        public void onLoadMessageFinished() {
            // We hide indeterminate progress on one-pane.
            // TODO Any nice UI for this?
        }

        @Override
        public void onLoadMessageError() {
            // We hide indeterminate progress on one-pane.
            // TODO Any nice UI for this?
        }

        @Override
        public void onFetchAttachmentStarted(String attachmentName) {
            // TODO Will probably be deprecate, then remove.
        }

        @Override
        public void onFetchAttachmentFinished() {
            // TODO Will probably be deprecate, then remove.
        }

        @Override
        public void onFetchAttachmentError() {
            // TODO Will probably be deprecate, then remove.
        }

        @Override
        public void onRespondedToInvite(int response) {
            if (!moveToOlder()) {
                openMailbox(); // if this is the last message, move up to message-list.
            }
        }

        @Override
        public void onCalendarLinkClicked(long epochEventStartTime) {
            ActivityHelper.openCalendar(MessageListXL.this, epochEventStartTime);
        }
    }

    @Override
    public void onMessageViewFragmentShown(long accountId, long mailboxId, long messageId) {
        mMessageViewButtonPanel.setVisibility(View.VISIBLE);

        updateMessageOrderManager();
        updateNavigationArrows();
    }

    @Override
    public void onMessageViewFragmentHidden() {
        mMessageViewButtonPanel.setVisibility(View.GONE);

        stopMessageOrderManager();
    }

    @Override
    public void onAccountSecurityHold() {
        // TODO: implement this
    }

    private void loadAccounts() {
        getLoaderManager().initLoader(LOADER_ID_ACCOUNT_LIST, null, new LoaderCallbacks<Cursor>() {
            @Override
            public Loader<Cursor> onCreateLoader(int id, Bundle args) {
                return AccountSelectorAdapter.createLoader(mContext);
            }

            @Override
            public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
                updateAccountList(data);
            }
        });
    }

    private void updateAccountList(Cursor accountsCursor) {
        if (accountsCursor.getCount() == 0) {
            onNoAccountFound();
            return;
        }

        // Find the currently selected account, and select it.
        int defaultSelection = 0;
        if (mFragmentManager.isAccountSelected()) {
            accountsCursor.moveToPosition(-1);
            int i = 0;
            while (accountsCursor.moveToNext()) {
                final long accountId = AccountSelectorAdapter.getAccountId(accountsCursor);
                if (accountId == mFragmentManager.getAccountId()) {
                    defaultSelection = i;
                    break;
                }
                i++;
            }
        }

        // Update the dropdown list.
        final ActionBar ab = getActionBar();
        mAccountsSelectorAdapter.changeCursor(accountsCursor);
        if (ab.getNavigationMode() != ActionBar.NAVIGATION_MODE_DROPDOWN_LIST) {
            ab.setDropdownNavigationMode(mAccountsSelectorAdapter,
                    mActionBarNavigationCallback, defaultSelection);
        }
    }

    private class ActionBarNavigationCallback implements ActionBar.NavigationCallback {
        @Override
        public boolean onNavigationItemSelected(int itemPosition, long accountId) {
            if (Email.DEBUG) Log.d(Email.LOG_TAG, "Account selected: accountId=" + accountId);
            mFragmentManager.selectAccount(accountId);
            return true;
        }
    }

    private class RefreshListener
            implements RefreshManager.Listener {
        @Override
        public void onMessagingError(long accountId, long mailboxId, String message) {
            Utility.showToast(MessageListXL.this, message); // STOPSHIP temporary UI
            invalidateOptionsMenu();
        }

        @Override
        public void onRefreshStatusChanged(long accountId, long mailboxId) {
            invalidateOptionsMenu();
        }
    }

    private boolean isProgressActive() {
        final long mailboxId = mFragmentManager.getMailboxId();
        return (mailboxId >= 0) && mRefreshManager.isMessageListRefreshing(mailboxId);

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.message_list_xl_option, menu);
        return true;
    }

    // STOPSHIP - this is a placeholder if/until there's support for progress in actionbar
    // Remove it, or replace with a better icon
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.refresh);
        if (isProgressActive()) {
            item.setIcon(android.R.drawable.progress_indeterminate_horizontal);
        } else {
            item.setIcon(R.drawable.ic_menu_refresh);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.compose:
                return onCompose();
            case R.id.refresh:
                onRefresh();
                return true;
            case R.id.account_settings:
                return onAccountSettings();
            case R.id.change_orientation: // STOPSHIP remove this
                Utility.changeOrientation(this);
                return true;
            case R.id.add_new_account: // STOPSHIP remove this
                return onAddNewAccount();
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean onCompose() {
        if (!mFragmentManager.isAccountSelected()) {
            return false; // this shouldn't really happen
        }
        MessageCompose.actionCompose(this, mFragmentManager.getAccountId());
        return true;
    }

    private boolean onAccountSettings() {
        if (!mFragmentManager.isAccountSelected()) {
            return false; // this shouldn't really happen
        }
        AccountSettings.actionSettings(this, mFragmentManager.getAccountId());
        return true;
    }

    private boolean onAddNewAccount() {
        AccountSetupBasics.actionNewAccount(this);
        return true;
    }

    private void onRefresh() {
        // Temporary implementation
        if (mFragmentManager.isMailboxSelected()) {
            long mailboxId = mFragmentManager.getMailboxId();
            // TODO This class here shouldn't really know what can be refreshable.
            // (The test below is only to prevent a crash... It's not enough.  e.g. no refresh
            // for outboxes.)
            if (mailboxId >= 0) {
                mRefreshManager.refreshMessageList(mFragmentManager.getAccountId(), mailboxId);
            }
        }

        // TODO implement this
        // - Refresh mailbox list.  But don't do that always; implement a min interval.
        //
        // - Refresh the selected mailbox, if it's supported.
        //   (regardless if the right-pane is MessageList or MessageView)
        // - If not suppoted (e.g. outbox, draft, or push mailboxes), refresh the inbox of the
        //   current account.

        // To do that, we need a way to tell the type of the currently selected mailbox.
        // We can do this with MessageListFragment, but it's gone it if a message is being viewed.
        // Maybe we should always have a MessageListFragment instance?
        // That way it'll be easier to restore the scroll position.
    }

    /**
     * STOPSHIP: Remove this.
     * Rotate screen when the R key is pressed.  Workaround for auto-orientation not working.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_R) {
            Utility.changeOrientation(this);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
