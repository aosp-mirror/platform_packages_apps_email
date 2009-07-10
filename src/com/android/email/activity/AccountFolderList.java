/*
 * Copyright (C) 2009 The Android Open Source Project
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

import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.activity.setup.AccountSetupBasics;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ExpandableListActivity;
import android.app.NotificationManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.CursorTreeAdapter;
import android.widget.ExpandableListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ExpandableListView.ExpandableListContextMenuInfo;

public class AccountFolderList extends ExpandableListActivity implements OnClickListener {
    private static final int DIALOG_REMOVE_ACCOUNT = 1;
    /**
     * Key codes used to open a debug settings screen.
     */
    private static int[] secretKeyCodes = {
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_U,
            KeyEvent.KEYCODE_G
    };

    private int mSecretKeyCodeIndex = 0;

    private static final String ICICLE_SELECTED_ACCOUNT = "com.android.email.selectedAccount";
    private EmailContent.Account mSelectedContextAccount;

    ExpandableListView mListView;
    AccountsAdapter mListAdapter;

    LoadAccountsTask mLoadAccountsTask;
    LoadMailboxesTask mLoadMailboxesTask;

    private MessageListHandler mHandler = new MessageListHandler();
    private ControllerResults mControllerCallback = new ControllerResults();

    /**
     * Start the Accounts list activity.  Uses the CLEAR_TOP flag which means that other stacked
     * activities may be killed in order to get back to Accounts.
     */
    public static void actionShowAccounts(Context context) {
        Intent i = new Intent(context, AccountFolderList.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(i);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.account_folder_list);
        mListView = getExpandableListView();
        mListView.setItemsCanFocus(false);
        mListView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_INSET);
        mListView.setLongClickable(true);
        registerForContextMenu(mListView);

        findViewById(R.id.add_new_account).setOnClickListener(this);

        if (icicle != null && icicle.containsKey(ICICLE_SELECTED_ACCOUNT)) {
            mSelectedContextAccount = (Account) icicle.getParcelable(ICICLE_SELECTED_ACCOUNT);
        }

        mLoadAccountsTask = (LoadAccountsTask) new LoadAccountsTask().execute();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mSelectedContextAccount != null) {
            outState.putParcelable(ICICLE_SELECTED_ACCOUNT, mSelectedContextAccount);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Controller.getInstance(getApplication()).removeResultCallback(mControllerCallback);
    }

    @Override
    public void onResume() {
        super.onResume();

        NotificationManager notifMgr = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        notifMgr.cancel(1);

        Controller.getInstance(getApplication()).addResultCallback(mControllerCallback);

        // TODO: What updates do we need to auto-trigger, now that we have mailboxes in view?
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mLoadMailboxesTask != null &&
                mLoadMailboxesTask.getStatus() != LoadMailboxesTask.Status.FINISHED) {
            mLoadMailboxesTask.cancel(true);
            mLoadMailboxesTask = null;
        }

        if (mLoadAccountsTask != null &&
                mLoadAccountsTask.getStatus() != LoadAccountsTask.Status.FINISHED) {
            mLoadAccountsTask.cancel(true);
            mLoadAccountsTask = null;
        }
    }

    @Override
    public void onGroupExpand(int groupPosition) {
        super.onGroupExpand(groupPosition);

        // If we don't have a cursor yet, create one
        Cursor childCursor = mListAdapter.getChild(groupPosition, 0);
        if (childCursor == null) {
            // Kill any previous unfinished task
            if (mLoadMailboxesTask != null &&
                    mLoadMailboxesTask.getStatus() != LoadMailboxesTask.Status.FINISHED) {
                mLoadMailboxesTask.cancel(true);
                mLoadMailboxesTask = null;
            }

            // Now start a new task to create a non-empty cursor
            Cursor groupCursor = mListAdapter.getCursor();
            long accountKey = groupCursor.getLong(EmailContent.Account.CONTENT_ID_COLUMN);
            mLoadMailboxesTask = new LoadMailboxesTask(accountKey, groupPosition);
            mLoadMailboxesTask.execute();
        }
    }

    @Override
    public boolean onChildClick(ExpandableListView parent, View v, int groupPosition,
            int childPosition, long id) {
        onOpenFolder(groupPosition, childPosition);
        return true;    // "handled"
    }

    /**
     * Async task to handle the accounts query outside of the UI thread
     */
    private class LoadAccountsTask extends AsyncTask<Void, Void, Cursor> {

        @Override
        protected Cursor doInBackground(Void... params) {
            // TODO use a custom projection and don't have to sample all of these columns
            return AccountFolderList.this.managedQuery(
                    EmailContent.Account.CONTENT_URI,
                    EmailContent.Account.CONTENT_PROJECTION,
                    null, null, null);
        }

        @Override
        protected void onPostExecute(Cursor theCursor) {
            mListAdapter = new AccountsAdapter(theCursor, AccountFolderList.this);
            mListView.setAdapter(mListAdapter);

            // This is deferred until after the first fetch, so it won't flicker
            // while we're waiting to find out if we have any accounts
            mListView.setEmptyView(findViewById(R.id.empty));
        }
    }

    /**
     * Async task to handle the mailboxes query outside of the UI thread
     */
    private class LoadMailboxesTask extends AsyncTask<Void, Void, Cursor> {

        private long mAccountId;
        private int mGroupNumber;

        public LoadMailboxesTask(long accountId, int groupNumber) {
            mAccountId = accountId;
            mGroupNumber = groupNumber;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            // TODO use a custom projection and don't have to sample all of these columns
            return AccountFolderList.this.managedQuery(
                    EmailContent.Mailbox.CONTENT_URI,
                    EmailContent.Mailbox.CONTENT_PROJECTION,
                    EmailContent.MailboxColumns.ACCOUNT_KEY + "=?",
                    new String[] { String.valueOf(mAccountId) },
                    EmailContent.MailboxColumns.TYPE);
        }

        @Override
        protected void onPostExecute(Cursor theCursor) {
            // TODO: There is a race condition here - what if the result came back after
            // the positions shifted?  We need to use something other than "groupNumber"
            // to set the correct adapter & cursor.
            AccountFolderList.this.mListAdapter.setChildrenCursor(mGroupNumber, theCursor);

            // If there are zero folders, this is probably a brand-new account - schedule a
            // top-level refresh
            if (theCursor.getCount() == 0) {
                onRefresh(mAccountId);
            }
        }
    }

    private void onAddNewAccount() {
        AccountSetupBasics.actionNewAccount(this);
    }

    private void onEditAccount(long accountId) {
        AccountSettings.actionSettings(this, accountId);
    }

    /**
     * Refresh one or all accounts
     * @param accountId A specific id to refresh folders only, or -1 to refresh everything
     */
    private void onRefresh(long accountId) {
        if (accountId == -1) {
            // TODO implement a suitable "Refresh all accounts" / "check mail" comment in Controller
            // TODO this is temp
            Toast.makeText(this,
                    "Please longpress an account to refresh it", Toast.LENGTH_LONG).show();
        } else {
            EmailContent.Account account =
                    EmailContent.Account.restoreAccountWithId(this, accountId);
            Controller.getInstance(getApplication()).updateMailboxList(
                    account, mControllerCallback);
        }
    }

    private void onCompose() {
        EmailContent.Account defaultAccount = EmailContent.Account.getDefaultAccount(this);
        if (defaultAccount != null) {
            MessageCompose.actionCompose(this, defaultAccount.mId);
        } else {
            onAddNewAccount();
        }
    }

    public void onClick(View view) {
        if (view.getId() == R.id.add_new_account) {
            onAddNewAccount();
        }
    }

    private void onDeleteAccount(long accountId) {
        mSelectedContextAccount = Account.restoreAccountWithId(this, accountId);
        showDialog(DIALOG_REMOVE_ACCOUNT);
    }

    @Override
    public Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_REMOVE_ACCOUNT:
                return createRemoveAccountDialog();
        }
        return super.onCreateDialog(id);
    }

    /**
     * Open a folder.  This may be a "real" folder or composite, depending on which group.
     * @param groupPosition The group # (account)
     * @param childPosition The child # (folder)
     */
    private void onOpenFolder(int groupPosition, int childPosition) {
        Cursor childCursor = mListAdapter.getChild(groupPosition, childPosition);
        long mailboxKey = childCursor.getLong(EmailContent.Mailbox.CONTENT_ID_COLUMN);
        MessageList.actionHandleAccount(this, mailboxKey, null, null);
    }

    private Dialog createRemoveAccountDialog() {
        return new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(R.string.account_delete_dlg_title)
            .setMessage(getString(R.string.account_delete_dlg_instructions_fmt,
                    mSelectedContextAccount.getDescription()))
            .setPositiveButton(R.string.okay_action, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    dismissDialog(DIALOG_REMOVE_ACCOUNT);
                    try {
                        // Delete Remote store at first.
                        Store.getInstance(
                                mSelectedContextAccount.getStoreUri(AccountFolderList.this),
                                getApplication(), null).delete();
                        // Remove the Store instance from cache.
                        Store.removeInstance(mSelectedContextAccount.getStoreUri(
                                AccountFolderList.this));
                        Uri uri = ContentUris.withAppendedId(
                                EmailContent.Account.CONTENT_URI, mSelectedContextAccount.mId);
                        AccountFolderList.this.getContentResolver().delete(uri, null, null);
                    } catch (Exception e) {
                            // Ignore
                    }
                    Email.setServicesEnabled(AccountFolderList.this);
                }
            })
            .setNegativeButton(R.string.cancel_action, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    dismissDialog(DIALOG_REMOVE_ACCOUNT);
                }
            })
            .create();
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        ExpandableListContextMenuInfo menuInfo = (ExpandableListContextMenuInfo)item.getMenuInfo();
        int type = ExpandableListView.getPackedPositionType(menuInfo.packedPosition);

        if (type == ExpandableListView.PACKED_POSITION_TYPE_GROUP) {
            int group = ExpandableListView.getPackedPositionGroup(menuInfo.packedPosition);
            Cursor c = (Cursor) mListView.getItemAtPosition(group);
            long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
            switch (item.getItemId()) {
                case R.id.delete_account:
                    onDeleteAccount(accountId);
                    break;
                case R.id.edit_account:
                    onEditAccount(accountId);
                    break;
                case R.id.refresh_account:
                    onRefresh(accountId);
                    break;
            }
            return true;
        } else {
            // TODO child context menus (per mailbox)
            return false;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.add_new_account:
                onAddNewAccount();
                break;
            case R.id.check_mail:
                onRefresh(-1);
                break;
            case R.id.compose:
                onCompose();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.account_folder_list_option, menu);
        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo info) {
        super.onCreateContextMenu(menu, v, info);
        ExpandableListContextMenuInfo menuInfo = (ExpandableListContextMenuInfo) info;
        int type = ExpandableListView.getPackedPositionType(menuInfo.packedPosition);
        if (type == ExpandableListView.PACKED_POSITION_TYPE_GROUP) {
            menu.setHeaderTitle(R.string.accounts_context_menu_title);
            getMenuInflater().inflate(R.menu.account_folder_list_context, menu);
        } else {
            // TODO child context menus (per mailbox)
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getKeyCode() == secretKeyCodes[mSecretKeyCodeIndex]) {
            mSecretKeyCodeIndex++;
            if (mSecretKeyCodeIndex == secretKeyCodes.length) {
                mSecretKeyCodeIndex = 0;
                startActivity(new Intent(this, Debug.class));
            }
        } else {
            mSecretKeyCodeIndex = 0;
        }
        return super.onKeyDown(keyCode, event);
    }
    
    /**
     * Handler for UI-thread operations (when called from callbacks or any other threads)
     */
    class MessageListHandler extends Handler {
        private static final int MSG_PROGRESS = 1;

        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_PROGRESS:
                    setProgressBarIndeterminateVisibility(msg.arg1 != 0);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }

        /**
         * Call from any thread to start/stop progress indicator(s)
         * @param progress true to start, false to stop
         */
        public void progress(boolean progress) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_PROGRESS;
            msg.arg1 = progress ? 1 : 0;
            sendMessage(msg);
        }
    }

    /**
     * Callback for async Controller results.  This is all a placeholder until we figure out the
     * final way to do this.
     */
    private class ControllerResults implements Controller.Result {
        public void updateMailboxListCallback(MessagingException result, long accountKey) {
            mHandler.progress(false);
        }

        public void updateMailboxCallback(MessagingException result, long accountKey,
                long mailboxKey, int totalMessagesInMailbox, int numNewMessages) {
            mHandler.progress(false);
        }
    }

    private static class AccountsAdapter extends CursorTreeAdapter {

        Context mContext;
        private LayoutInflater mInflater;

        public AccountsAdapter(Cursor c, Context context) {
            super(c, context, true);
            mContext = context;
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        protected void bindChildView(View view, Context context, Cursor cursor, boolean isLastChild)
                {
            String text = cursor.getString(EmailContent.Mailbox.CONTENT_DISPLAY_NAME_COLUMN);
            if (text != null) {
                TextView nameView = (TextView) view.findViewById(R.id.folder_name);
                nameView.setText(text);
            }

            // TODO get/trach live folder status
            text = null;
            TextView statusView = (TextView) view.findViewById(R.id.folder_status);
            if (text != null) {
                statusView.setText(text);
                statusView.setVisibility(View.VISIBLE);
            } else {
                statusView.setVisibility(View.GONE);
            }

            text = cursor.getString(EmailContent.Mailbox.CONTENT_UNREAD_COUNT_COLUMN);
            if (text != null) {
                TextView countView = (TextView) view.findViewById(R.id.new_message_count);
                countView.setText(text);
            }
        }

        @Override
        protected void bindGroupView(View view, Context context, Cursor cursor, boolean isExpanded)
                {
            String text = cursor.getString(EmailContent.Account.CONTENT_DISPLAY_NAME_COLUMN);
            if (text != null) {
                TextView descriptionView = (TextView) view.findViewById(R.id.description);
                descriptionView.setText(text);
            }

            text = cursor.getString(EmailContent.Account.CONTENT_EMAIL_ADDRESS_COLUMN);
            if (text != null) {
                TextView emailView = (TextView) view.findViewById(R.id.email);
                emailView.setText(text);
            }

            // TODO get unread count from Account
            int unreadMessageCount = 0;
            TextView countView = (TextView) view.findViewById(R.id.new_message_count);
            if (unreadMessageCount > 0) {
                countView.setText(String.valueOf(unreadMessageCount));
                countView.setVisibility(View.VISIBLE);
            } else {
                countView.setVisibility(View.GONE);
            }
        }

        /**
         * We return null here (no immediate cursor availability) and use an AsyncTask to get
         * the cursor in certain onclick situations
         */
        @Override
        protected Cursor getChildrenCursor(Cursor groupCursor) {
            return null;
        }

        /**
         * Overriding this allows the child cursors to be requeried on dataset changes
         */
        @Override
        public void notifyDataSetChanged() {
            notifyDataSetChanged(false);
        }

        @Override
        protected View newChildView(Context context, Cursor cursor, boolean isLastChild,
                ViewGroup parent) {
            return mInflater.inflate(R.layout.account_folder_list_child, parent, false);
        }

        @Override
        protected View newGroupView(Context context, Cursor cursor, boolean isExpanded,
                ViewGroup parent) {
            return mInflater.inflate(R.layout.account_folder_list_group, parent, false);
        }
    }
}


