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
import com.android.email.ControllerResultUiThreadWrapper;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.activity.setup.AccountSetupBasics;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.service.MailService;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;

public class AccountFolderList extends ListActivity
        implements OnItemClickListener, AccountsAdapter.Callback {
    private static final int DIALOG_REMOVE_ACCOUNT = 1;
    /**
     * Key codes used to open a debug settings screen.
     */
    private static final int[] SECRET_KEY_CODES = {
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_U,
            KeyEvent.KEYCODE_G
    };
    private int mSecretKeyCodeIndex = 0;

    private static final String ICICLE_SELECTED_ACCOUNT = "com.android.email.selectedAccount";
    private EmailContent.Account mSelectedContextAccount;

    private ListView mListView;
    private ProgressBar mProgressIcon;

    private AccountsAdapter mListAdapter;

    private LoadAccountsTask mLoadAccountsTask;
    private Controller.Result mControllerCallback;

    private static final String FAVORITE_COUNT_SELECTION =
        MessageColumns.FLAG_FAVORITE + "= 1";

    private static final String MAILBOX_TYPE_SELECTION =
        MailboxColumns.TYPE + " =?";

    private static final String MAILBOX_ID_SELECTION =
        MessageColumns.MAILBOX_KEY + " =?";

    private static final String[] MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION = new String [] {
        "sum(" + MailboxColumns.UNREAD_COUNT + ")"
    };

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

        requestWindowFeature(Window.FEATURE_CUSTOM_TITLE);
        setContentView(R.layout.account_folder_list);
        getWindow().setFeatureInt(Window.FEATURE_CUSTOM_TITLE,
                R.layout.list_title);

        mControllerCallback = new ControllerResultUiThreadWrapper<ControllerResults>(
                new Handler(), new ControllerResults());
        mProgressIcon = (ProgressBar) findViewById(R.id.title_progress_icon);

        mListView = getListView();
        mListView.setItemsCanFocus(false);
        mListView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_INSET);
        mListView.setOnItemClickListener(this);
        mListView.setLongClickable(true);
        registerForContextMenu(mListView);

        if (icicle != null && icicle.containsKey(ICICLE_SELECTED_ACCOUNT)) {
            mSelectedContextAccount = (Account) icicle.getParcelable(ICICLE_SELECTED_ACCOUNT);
        }

        ((TextView) findViewById(R.id.title_left_text)).setText(R.string.app_name);
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

        // Exit immediately if the accounts list has changed (e.g. externally deleted)
        if (Email.getNotifyUiAccountsChanged()) {
            Welcome.actionStart(this);
            finish();
            return;
        }

        updateAccounts();
        // TODO: What updates do we need to auto-trigger, now that we have mailboxes in view?
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Utility.cancelTaskInterrupt(mLoadAccountsTask);
        mLoadAccountsTask = null;

        if (mListAdapter != null) {
            mListAdapter.changeCursor(null);
        }
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (mListAdapter.isMailbox(position)) {
            MessageList.actionHandleMailbox(this, id);
        } else if (mListAdapter.isAccount(position)) {
            MessageList.actionHandleAccount(this, id, Mailbox.TYPE_INBOX);
        }
    }

    /**
     * Implements AccountsAdapter.Controller
     */
    public void onClickAccountFolders(long accountId) {
        MailboxList.actionHandleAccount(this, accountId);
    }

    private static int getUnreadCountByMailboxType(Context context, int type) {
        int count = 0;
        Cursor c = context.getContentResolver().query(Mailbox.CONTENT_URI,
                MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION,
                MAILBOX_TYPE_SELECTION,
                new String[] { String.valueOf(type) }, null);

        try {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } finally {
            c.close();
        }
        return count;
    }

    private static int getCountByMailboxType(Context context, int type) {
        int count = 0;
        Cursor c = context.getContentResolver().query(Mailbox.CONTENT_URI,
                EmailContent.ID_PROJECTION, MAILBOX_TYPE_SELECTION,
                new String[] { String.valueOf(type) }, null);

        try {
            c.moveToPosition(-1);
            while (c.moveToNext()) {
                count += EmailContent.count(context, Message.CONTENT_URI,
                        MAILBOX_ID_SELECTION,
                        new String[] {
                            String.valueOf(c.getLong(EmailContent.ID_PROJECTION_COLUMN)) });
            }
        } finally {
            c.close();
        }
        return count;
    }

    /**
     * Build the group and child cursors that support the summary views (aka "at a glance").
     *
     * This is a placeholder implementation with significant problems that need to be addressed:
     *
     * TODO: We should only show summary mailboxes if they are non-empty.  So there needs to be
     * a more dynamic child-cursor here, probably listening for update notifications on a number
     * of other internally-held queries such as count-of-inbox, count-of-unread, etc.
     *
     * TODO: This simple list is incomplete.  For example, we probably want drafts, outbox, and
     * (maybe) sent (again, these would be displayed only when non-empty).
     *
     * TODO: We need a way to count total unread in all inboxes (probably with some provider help)
     *
     * TODO: We need a way to count total # messages in all other summary boxes (probably with
     * some provider help).
     *
     * TODO use narrower account projection (see LoadAccountsTask)
     */
    private MatrixCursor getSummaryChildCursor() {
        MatrixCursor childCursor = new MatrixCursor(AccountsAdapter.MAILBOX_PROJECTION);
        int count;
        RowBuilder row;
        // TYPE_INBOX
        count = getUnreadCountByMailboxType(this, Mailbox.TYPE_INBOX);
        row = childCursor.newRow();
        row.add(Long.valueOf(Mailbox.QUERY_ALL_INBOXES));   // MAILBOX_COLUMN_ID = 0;
        row.add(getString(R.string.account_folder_list_summary_inbox)); // MAILBOX_DISPLAY_NAME
        row.add(null);                                          // MAILBOX_ACCOUNT_KEY = 2;
        row.add(Integer.valueOf(Mailbox.TYPE_INBOX));           // MAILBOX_TYPE = 3;
        row.add(Integer.valueOf(count));                        // MAILBOX_UNREAD_COUNT = 4;
        // TYPE_MAIL (FAVORITES)
        count = EmailContent.count(this, Message.CONTENT_URI, FAVORITE_COUNT_SELECTION, null);
        if (count > 0) {
            row = childCursor.newRow();
            row.add(Long.valueOf(Mailbox.QUERY_ALL_FAVORITES)); // MAILBOX_COLUMN_ID = 0;
            // MAILBOX_DISPLAY_NAME
            row.add(getString(R.string.account_folder_list_summary_starred));
            row.add(null);                                          // MAILBOX_ACCOUNT_KEY = 2;
            row.add(Integer.valueOf(Mailbox.TYPE_MAIL));            // MAILBOX_TYPE = 3;
            row.add(Integer.valueOf(count));                        // MAILBOX_UNREAD_COUNT = 4;
        }
        // TYPE_DRAFTS
        count = getCountByMailboxType(this, Mailbox.TYPE_DRAFTS);
        if (count > 0) {
            row = childCursor.newRow();
            row.add(Long.valueOf(Mailbox.QUERY_ALL_DRAFTS));    // MAILBOX_COLUMN_ID = 0;
            row.add(getString(R.string.account_folder_list_summary_drafts));// MAILBOX_DISPLAY_NAME
            row.add(null);                                          // MAILBOX_ACCOUNT_KEY = 2;
            row.add(Integer.valueOf(Mailbox.TYPE_DRAFTS));          // MAILBOX_TYPE = 3;
            row.add(Integer.valueOf(count));                        // MAILBOX_UNREAD_COUNT = 4;
        }
        // TYPE_OUTBOX
        count = getCountByMailboxType(this, Mailbox.TYPE_OUTBOX);
        if (count > 0) {
            row = childCursor.newRow();
            row.add(Long.valueOf(Mailbox.QUERY_ALL_OUTBOX));    // MAILBOX_COLUMN_ID = 0;
            row.add(getString(R.string.account_folder_list_summary_outbox));// MAILBOX_DISPLAY_NAME
            row.add(null);                                          // MAILBOX_ACCOUNT_KEY = 2;
            row.add(Integer.valueOf(Mailbox.TYPE_OUTBOX));          // MAILBOX_TYPE = 3;
            row.add(Integer.valueOf(count));                        // MAILBOX_UNREAD_COUNT = 4;
        }
        return childCursor;
    }

    /**
     * Async task to handle the accounts query outside of the UI thread
     */
    private class LoadAccountsTask extends AsyncTask<Void, Void, Object[]> {
        @Override
        protected Object[] doInBackground(Void... params) {
            // Create the summaries cursor
            Cursor c1 = getSummaryChildCursor();

            // TODO use a custom projection and don't have to sample all of these columns
            Cursor c2 = getContentResolver().query(
                    EmailContent.Account.CONTENT_URI,
                    EmailContent.Account.CONTENT_PROJECTION, null, null, null);
            Long defaultAccount = Account.getDefaultAccountId(AccountFolderList.this);
            return new Object[] { c1, c2 , defaultAccount};
        }

        @Override
        protected void onPostExecute(Object[] params) {
            if (isCancelled() || params == null || ((Cursor)params[1]).isClosed()) {
                return;
            }
            // Before writing a new list adapter into the listview, we need to
            // shut down the old one (if any).
            ListAdapter oldAdapter = mListView.getAdapter();
            if (oldAdapter != null && oldAdapter instanceof CursorAdapter) {
                ((CursorAdapter)oldAdapter).changeCursor(null);
            }
            // Now create a new list adapter and install it
            mListAdapter = AccountsAdapter.getInstance((Cursor)params[0], (Cursor)params[1],
                    AccountFolderList.this, (Long)params[2], AccountFolderList.this);
            mListView.setAdapter(mListAdapter);
        }
    }

    private void updateAccounts() {
        Utility.cancelTaskInterrupt(mLoadAccountsTask);
        mLoadAccountsTask = (LoadAccountsTask) new LoadAccountsTask().execute();
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
            Toast.makeText(this, getString(R.string.account_folder_list_refresh_toast),
                    Toast.LENGTH_LONG).show();
        } else {
            showProgressIcon(true);
            Controller.getInstance(getApplication()).updateMailboxList(accountId);
        }
    }

    private void onCompose(long accountId) {
        if (accountId == -1) {
            accountId = Account.getDefaultAccountId(this);
        }
        if (accountId != -1) {
            MessageCompose.actionCompose(this, accountId);
        } else {
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

    private Dialog createRemoveAccountDialog() {
        return new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(R.string.account_delete_dlg_title)
            .setMessage(getString(R.string.account_delete_dlg_instructions_fmt,
                    mSelectedContextAccount.getDisplayName()))
            .setPositiveButton(R.string.okay_action, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    dismissDialog(DIALOG_REMOVE_ACCOUNT);
                    // Clear notifications, which may become stale here
                    NotificationManager notificationManager = (NotificationManager)
                            getSystemService(Context.NOTIFICATION_SERVICE);
                    notificationManager.cancel(MailService.NOTIFICATION_ID_NEW_MESSAGES);
                    int numAccounts = EmailContent.count(AccountFolderList.this,
                            Account.CONTENT_URI, null, null);
                    mListAdapter.addOnDeletingAccount(mSelectedContextAccount.mId);

                    Controller.getInstance(AccountFolderList.this).deleteAccount(
                            mSelectedContextAccount.mId);
                    if (numAccounts == 1) {
                        AccountSetupBasics.actionNewAccount(AccountFolderList.this);
                        finish();
                    }
                }
            })
            .setNegativeButton(R.string.cancel_action, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    dismissDialog(DIALOG_REMOVE_ACCOUNT);
                }
            })
            .create();
    }

    /**
     * Update a cached dialog with current values (e.g. account name)
     */
    @Override
    public void onPrepareDialog(int id, Dialog dialog) {
        switch (id) {
            case DIALOG_REMOVE_ACCOUNT:
                AlertDialog alert = (AlertDialog) dialog;
                alert.setMessage(getString(R.string.account_delete_dlg_instructions_fmt,
                        mSelectedContextAccount.getDisplayName()));
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo menuInfo =
            (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();

        if (mListAdapter.isMailbox(menuInfo.position)) {
            Cursor c = (Cursor) mListView.getItemAtPosition(menuInfo.position);
            long id = c.getLong(AccountsAdapter.MAILBOX_COLUMN_ID);
            switch (item.getItemId()) {
                case R.id.open_folder:
                    MessageList.actionHandleMailbox(this, id);
                    break;
                case R.id.check_mail:
                    onRefresh(-1);
                    break;
            }
            return false;
        } else if (mListAdapter.isAccount(menuInfo.position)) {
            Cursor c = (Cursor) mListView.getItemAtPosition(menuInfo.position);
            long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
            switch (item.getItemId()) {
                case R.id.open_folder:
                    MailboxList.actionHandleAccount(this, accountId);
                    break;
                case R.id.compose:
                    onCompose(accountId);
                    break;
                case R.id.refresh_account:
                    onRefresh(accountId);
                    break;
                case R.id.edit_account:
                    onEditAccount(accountId);
                    break;
                case R.id.delete_account:
                    onDeleteAccount(accountId);
                    break;
            }
            return true;
        }
        return false;
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
                onCompose(-1);
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
        AdapterView.AdapterContextMenuInfo menuInfo = (AdapterView.AdapterContextMenuInfo) info;
        if (mListAdapter.isMailbox(menuInfo.position)) {
            Cursor c = (Cursor) mListView.getItemAtPosition(menuInfo.position);
            String displayName = c.getString(Account.CONTENT_DISPLAY_NAME_COLUMN);
            menu.setHeaderTitle(displayName);
            getMenuInflater().inflate(R.menu.account_folder_list_smart_folder_context, menu);
        } else if (mListAdapter.isAccount(menuInfo.position)) {
            Cursor c = (Cursor) mListView.getItemAtPosition(menuInfo.position);
            String accountName = c.getString(Account.CONTENT_DISPLAY_NAME_COLUMN);
            menu.setHeaderTitle(accountName);
            getMenuInflater().inflate(R.menu.account_folder_list_context, menu);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getKeyCode() == SECRET_KEY_CODES[mSecretKeyCodeIndex]) {
            mSecretKeyCodeIndex++;
            if (mSecretKeyCodeIndex == SECRET_KEY_CODES.length) {
                mSecretKeyCodeIndex = 0;
                startActivity(new Intent(this, Debug.class));
            }
        } else {
            mSecretKeyCodeIndex = 0;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showProgressIcon(boolean show) {
        mProgressIcon.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * Controller results listener.  We wrap it with {@link ControllerResultUiThreadWrapper},
     * so all methods are called on the UI thread.
     */
    private class ControllerResults extends Controller.Result {
        @Override
        public void updateMailboxListCallback(MessagingException result, long accountKey,
                int progress) {
            updateProgress(result, progress);
        }

        @Override
        public void updateMailboxCallback(MessagingException result, long accountKey,
                long mailboxKey, int progress, int numNewMessages) {
            if (result != null || progress == 100) {
                Email.updateMailboxRefreshTime(mailboxKey);
            }
            if (progress == 100) {
                updateAccounts();
            }
            updateProgress(result, progress);
        }

        @Override
        public void serviceCheckMailCallback(MessagingException result, long accountId,
                long mailboxId, int progress, long tag) {
            updateProgress(result, progress);
        }

        @Override
        public void sendMailCallback(MessagingException result, long accountId, long messageId,
                int progress) {
            if (progress == 100) {
                updateAccounts();
            }
        }

        private void updateProgress(MessagingException result, int progress) {
            showProgressIcon(result == null && progress < 100);
        }

        @Override
        public void deleteAccountCallback(long accountId) {
            updateAccounts();
        }
    }
}
