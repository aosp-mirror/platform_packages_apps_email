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

import android.app.ActionBar;
import android.app.Activity;
import android.app.Fragment;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
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
    private static final int LOADER_ID_ACCOUNT_LIST = 0;

    private Context mContext;

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

    public static void actionStart(Activity fromActivity) {
        fromActivity.startActivity(new Intent(fromActivity, MessageListXL.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.message_list_xl);

        final boolean isRestoring = (savedInstanceState != null);

        mContext = getApplicationContext();

        mFragmentManager.setMailboxListFragmentCallback(new MailboxListFragmentCallback());
        mFragmentManager.setMessageListFragmentCallback(new MessageListFragmentCallback());
        mFragmentManager.setMessageViewFragmentCallback(new MessageViewFragmentCallback());

        mMessageViewButtonPanel = findViewById(R.id.message_view_buttons);
        mMoveToNewerButton = findViewById(R.id.moveToNewer);
        mMoveToOlderButton = findViewById(R.id.moveToOlder);
        mMoveToNewerButton.setOnClickListener(this);
        mMoveToOlderButton.setOnClickListener(this);

        mAccountsSelectorAdapter = new AccountSelectorAdapter(mContext, null);

        if (isRestoring) {
            mFragmentManager.loadState(savedInstanceState);
        }
        loadAccounts();
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
            mFragmentManager.selectMailbox(mFragmentManager.getMailboxId());
        } else {
            // Perform the default behavior == close the activity.
            super.onBackPressed();
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
        }
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
            // TODO Current message gone
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
        public void onRefresh(long accountId, long mailboxId) {
            // Will be removed.
        }

        // TODO Rename to onSelectMailbox
        @Override
        public void onMailboxSelected(long accountId, long mailboxId) {
            mFragmentManager.selectMailbox(mailboxId);
        }
    }

    private class MessageListFragmentCallback implements MessageListFragment.Callback {
        @Override
        public void onSelectionChanged() {
            // TODO Context mode
        }

        @Override
        // TODO Rename to onSelectMessage
        public void onMessageOpen(long messageId, long mailboxId) { // RENAME: OpenMessage ?
            // TODO Deal with drafts.  (Open MessageCompose instead.)
            mFragmentManager.selectMessage(messageId);
        }

        @Override
        public void onMailboxNotFound() { // RENAME: NotExists? (see MessageViewFragment)
            // TODO: What to do??
        }
    }

    private class MessageViewFragmentCallback implements MessageViewFragment.Callback {
        @Override
        public boolean onUrlInMessageClicked(String url) {
            return false;
        }

        @Override
        public void onRespondedToInvite(int response) {
        }

        @Override
        public void onMessageSetUnread() {
        }

        @Override
        public void onMessageNotExists() {
        }

        @Override
        public void onLoadMessageStarted() {
        }

        @Override
        public void onLoadMessageFinished() {
        }

        @Override
        public void onLoadMessageError() {
        }

        @Override
        public void onFetchAttachmentStarted(String attachmentName) {
        }

        @Override
        public void onFetchAttachmentFinished() {
        }

        @Override
        public void onFetchAttachmentError() {
        }

        @Override
        public void onCalendarLinkClicked(long epochEventStartTime) {
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

    /**
     * STOPSHIP: Remove this.
     * Rotate screen when the R key is pressed.  Workaround for auto-orientation not working.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_R) {
            setRequestedOrientation(
                    (getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                    ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
