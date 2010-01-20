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

import com.android.email.AccountBackupRestore;
import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.activity.setup.AccountSetupBasics;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.NotificationManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.MatrixCursor.RowBuilder;
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
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;

public class AccountFolderList extends ListActivity
        implements OnItemClickListener, OnClickListener {
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

    private ListView mListView;
    private ProgressBar mProgressIcon;

    private AccountsAdapter mListAdapter;

    private LoadAccountsTask mLoadAccountsTask;

    private MessageListHandler mHandler = new MessageListHandler();
    private ControllerResults mControllerCallback = new ControllerResults();

    /**
     * Reduced mailbox projection used by AccountsAdapter
     */
    public final static int MAILBOX_COLUMN_ID = 0;
    public final static int MAILBOX_DISPLAY_NAME = 1;
    public final static int MAILBOX_ACCOUNT_KEY = 2;
    public final static int MAILBOX_TYPE = 3;
    public final static int MAILBOX_UNREAD_COUNT = 4;
    public final static int MAILBOX_FLAG_VISIBLE = 5;
    public final static int MAILBOX_FLAGS = 6;

    public final static String[] MAILBOX_PROJECTION = new String[] {
        EmailContent.RECORD_ID, MailboxColumns.DISPLAY_NAME,
        MailboxColumns.ACCOUNT_KEY, MailboxColumns.TYPE,
        MailboxColumns.UNREAD_COUNT,
        MailboxColumns.FLAG_VISIBLE, MailboxColumns.FLAGS
    };

    private static final String FAVORITE_COUNT_SELECTION =
        MessageColumns.FLAG_FAVORITE + "= 1";

    private static final String MAILBOX_TYPE_SELECTION =
        MailboxColumns.TYPE + " =?";

    private static final String MAILBOX_ID_SELECTION =
        MessageColumns.MAILBOX_KEY + " =?";

    private static final String[] MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION = new String [] {
        "sum(" + MailboxColumns.UNREAD_COUNT + ")"
    };

    private static final String MAILBOX_INBOX_SELECTION =
        MailboxColumns.ACCOUNT_KEY + " =?" + " AND " + MailboxColumns.TYPE +" = "
        + Mailbox.TYPE_INBOX;

    private static final int MAILBOX_UNREAD_COUNT_COLUMN_UNREAD_COUNT = 0;
    private static final String[] MAILBOX_UNREAD_COUNT_PROJECTION = new String [] {
        MailboxColumns.UNREAD_COUNT
    };

    private static final int[] mColorChipResIds = new int[] {
        R.drawable.appointment_indicator_leftside_1,
        R.drawable.appointment_indicator_leftside_2,
        R.drawable.appointment_indicator_leftside_3,
        R.drawable.appointment_indicator_leftside_4,
        R.drawable.appointment_indicator_leftside_5,
        R.drawable.appointment_indicator_leftside_6,
        R.drawable.appointment_indicator_leftside_7,
        R.drawable.appointment_indicator_leftside_8,
        R.drawable.appointment_indicator_leftside_9,
        R.drawable.appointment_indicator_leftside_10,
        R.drawable.appointment_indicator_leftside_11,
        R.drawable.appointment_indicator_leftside_12,
        R.drawable.appointment_indicator_leftside_13,
        R.drawable.appointment_indicator_leftside_14,
        R.drawable.appointment_indicator_leftside_15,
        R.drawable.appointment_indicator_leftside_16,
        R.drawable.appointment_indicator_leftside_17,
        R.drawable.appointment_indicator_leftside_18,
        R.drawable.appointment_indicator_leftside_19,
        R.drawable.appointment_indicator_leftside_20,
        R.drawable.appointment_indicator_leftside_21,
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

        updateAccounts();
        // TODO: What updates do we need to auto-trigger, now that we have mailboxes in view?
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mLoadAccountsTask != null &&
                mLoadAccountsTask.getStatus() != LoadAccountsTask.Status.FINISHED) {
            mLoadAccountsTask.cancel(true);
            mLoadAccountsTask = null;
        }
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.button_compose:
                onCompose(-1);
                break;
            case R.id.button_refresh:
                onRefresh(-1);
                break;
        }
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (mListAdapter.isMailbox(position)) {
            MessageList.actionHandleMailbox(this, id);
        } else if (mListAdapter.isAccount(position)) {
            MessageList.actionHandleAccount(this, id, Mailbox.TYPE_INBOX); 
        }
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
        MatrixCursor childCursor = new MatrixCursor(MAILBOX_PROJECTION);
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
            Cursor c2 = AccountFolderList.this.managedQuery(
                    EmailContent.Account.CONTENT_URI,
                    EmailContent.Account.CONTENT_PROJECTION,
                    null, null, null);
            Long defaultAccount = Account.getDefaultAccountId(AccountFolderList.this);
            return new Object[] { c1, c2 , defaultAccount};
        }

        @Override
        protected void onPostExecute(Object[] params) {
            if (params == null || ((Cursor)params[1]).isClosed()) {
                return;
            }
            mListAdapter = AccountsAdapter.getInstance((Cursor)params[0], (Cursor)params[1],
                    AccountFolderList.this, (Long)params[2]);
            mListView.setAdapter(mListAdapter);
        }
    }

    private void updateAccounts() {
        if (mLoadAccountsTask != null &&
                mLoadAccountsTask.getStatus() != LoadAccountsTask.Status.FINISHED) {
            mLoadAccountsTask.cancel(true);
        }
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
            Toast.makeText(this,
                    "Please longpress an account to refresh it", Toast.LENGTH_LONG).show();
        } else {
            mHandler.progress(true);
            Controller.getInstance(getApplication()).updateMailboxList(
                    accountId, mControllerCallback);
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
                        // Update the backup (side copy) of the accounts
                        AccountBackupRestore.backupAccounts(AccountFolderList.this);
                    } catch (Exception e) {
                            // Ignore
                    }
                    Email.setServicesEnabled(AccountFolderList.this);

                    // Jump to account setup if the last/only account was deleted
                    int numAccounts = EmailContent.count(AccountFolderList.this,
                            Account.CONTENT_URI, null, null);
                    if (numAccounts == 0) {
                        AccountSetupBasics.actionNewAccount(AccountFolderList.this);
                        finish();
                    }
                    // Update unread counts and the default sender Id
                    updateAccounts();
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
            long id = c.getLong(MAILBOX_COLUMN_ID);
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
                    boolean showProgress = (msg.arg1 != 0);
                    if (showProgress) {
                        mProgressIcon.setVisibility(View.VISIBLE);
                    } else {
                        mProgressIcon.setVisibility(View.GONE);
                    }
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
     * Callback for async Controller results.
     */
    private class ControllerResults implements Controller.Result {
        public void updateMailboxListCallback(MessagingException result, long accountKey,
                int progress) {
            updateProgress(result, progress);
        }

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

        public void loadMessageForViewCallback(MessagingException result, long messageId,
                int progress) {
        }

        public void loadAttachmentCallback(MessagingException result, long messageId,
                long attachmentId, int progress) {
        }

        public void serviceCheckMailCallback(MessagingException result, long accountId,
                long mailboxId, int progress, long tag) {
            updateProgress(result, progress);
        }

        public void sendMailCallback(MessagingException result, long accountId, long messageId,
                int progress) {
            if (progress == 100) {
                updateAccounts();
            }
        }

        private void updateProgress(MessagingException result, int progress) {
            if (result != null || progress == 100) {
                mHandler.progress(false);
            } else if (progress == 0) {
                mHandler.progress(true);
            }
        }
    }

    /* package */ static class AccountsAdapter extends CursorAdapter {

        Context mContext;
        private LayoutInflater mInflater;
        private int mMailboxesCount;
        private int mSeparatorPosition;
        long mDefaultAccountId;

        public static AccountsAdapter getInstance(Cursor mailboxesCursor, Cursor accountsCursor,
                Context context, long defaultAccountId) {
            Cursor[] cursors = new Cursor[] { mailboxesCursor, accountsCursor };
            Cursor mc = new MergeCursor(cursors);
            return new AccountsAdapter(mc, context, mailboxesCursor.getCount(), defaultAccountId);
        }

        public AccountsAdapter(Cursor c, Context context, int mailboxesCount,
                long defaultAccountId) {
            super(context, c, true);
            mContext = context;
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mMailboxesCount = mailboxesCount;
            mSeparatorPosition = mailboxesCount;
            mDefaultAccountId = defaultAccountId;
        }

        public boolean isMailbox(int position) {
            return position < mMailboxesCount;
        }

        public boolean isAccount(int position) {
            return position >= mMailboxesCount;
        }

        /**
         * This is used as a callback from the list items, for clicks in the folder "button"
         *
         * @param itemView the item in which the click occurred
         */
        public void onClickFolder(AccountFolderListItem itemView) {
            MailboxList.actionHandleAccount(mContext, itemView.mAccountId);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            if (cursor.getPosition() < mMailboxesCount) {
                bindMailboxItem(view, context, cursor, false);
            } else {
                bindAccountItem(view, context, cursor, false);
            }
        }

        private void bindMailboxItem(View view, Context context, Cursor cursor, boolean isLastChild)
                {
            // Reset the view (in case it was recycled) and prepare for binding
            AccountFolderListItem itemView = (AccountFolderListItem) view;
            itemView.bindViewInit(this, false);

            // Invisible (not "gone") to maintain spacing
            view.findViewById(R.id.chip).setVisibility(View.INVISIBLE);

            String text = cursor.getString(MAILBOX_DISPLAY_NAME);
            if (text != null) {
                TextView nameView = (TextView) view.findViewById(R.id.name);
                nameView.setText(text);
            }

            // TODO get/track live folder status
            text = null;
            TextView statusView = (TextView) view.findViewById(R.id.status);
            if (text != null) {
                statusView.setText(text);
                statusView.setVisibility(View.VISIBLE);
            } else {
                statusView.setVisibility(View.GONE);
            }

            int count = -1;
            text = cursor.getString(MAILBOX_UNREAD_COUNT);
            if (text != null) {
                count = Integer.valueOf(text);
            }
            TextView countView = (TextView) view.findViewById(R.id.new_message_count);
            // If the unread count is zero, not to show countView.
            if (count > 0) {
                countView.setVisibility(View.VISIBLE);
                countView.setText(text);
            } else {
                countView.setVisibility(View.GONE);
            }
            int id = cursor.getInt(MAILBOX_COLUMN_ID);
            if (id == Mailbox.QUERY_ALL_FAVORITES
                    || id == Mailbox.QUERY_ALL_DRAFTS
                    || id == Mailbox.QUERY_ALL_OUTBOX) {
                countView.setBackgroundResource(R.drawable.ind_sum);
            } else {
                countView.setBackgroundResource(R.drawable.ind_unread);
            }
            // Padding should be reset after setBackgroundResource
            countView.setPadding(2, 0, 2, 0);

            view.findViewById(R.id.folder_button).setVisibility(View.GONE);
            view.findViewById(R.id.folder_separator).setVisibility(View.GONE);
            view.findViewById(R.id.default_sender).setVisibility(View.GONE);
            view.findViewById(R.id.folder_icon).setVisibility(View.VISIBLE);
            ((ImageView)view.findViewById(R.id.folder_icon)).setImageDrawable(
                    Utility.FolderProperties.getInstance(context).getSummaryMailboxIconIds(id));
        }

        private void bindAccountItem(View view, Context context, Cursor cursor, boolean isExpanded)
                {
            // Reset the view (in case it was recycled) and prepare for binding
            AccountFolderListItem itemView = (AccountFolderListItem) view;
            itemView.bindViewInit(this, true);
            itemView.mAccountId = cursor.getLong(Account.CONTENT_ID_COLUMN);

            long accountId = cursor.getLong(Account.CONTENT_ID_COLUMN);
            View chipView = view.findViewById(R.id.chip);
            int chipResId = mColorChipResIds[(int)accountId % mColorChipResIds.length];
            chipView.setBackgroundResource(chipResId);
            chipView.setVisibility(View.VISIBLE);

            String text = cursor.getString(Account.CONTENT_DISPLAY_NAME_COLUMN);
            if (text != null) {
                TextView descriptionView = (TextView) view.findViewById(R.id.name);
                descriptionView.setText(text);
            }

            text = cursor.getString(Account.CONTENT_EMAIL_ADDRESS_COLUMN);
            if (text != null) {
                TextView emailView = (TextView) view.findViewById(R.id.status);
                emailView.setText(text);
                emailView.setVisibility(View.VISIBLE);
            }

            int unreadMessageCount = 0;
            Cursor c = context.getContentResolver().query(Mailbox.CONTENT_URI,
                    MAILBOX_UNREAD_COUNT_PROJECTION,
                    MAILBOX_INBOX_SELECTION,
                    new String[] { String.valueOf(accountId) }, null);

            try {
                if (c.moveToFirst()) {
                    String count = c.getString(MAILBOX_UNREAD_COUNT_COLUMN_UNREAD_COUNT);
                    if (count != null) {
                        unreadMessageCount = Integer.valueOf(count);
                    }
                }
            } finally {
                c.close();
            }

            TextView countView = (TextView) view.findViewById(R.id.new_message_count);
            if (unreadMessageCount > 0) {
                countView.setText(String.valueOf(unreadMessageCount));
                countView.setVisibility(View.VISIBLE);
            } else {
                countView.setVisibility(View.GONE);
            }
            countView.setBackgroundResource(R.drawable.ind_unread);
            // Padding should be reset after setBackgroundResource
            countView.setPadding(2, 0, 2, 0);

            view.findViewById(R.id.folder_icon).setVisibility(View.GONE);
            view.findViewById(R.id.folder_button).setVisibility(View.VISIBLE);
            view.findViewById(R.id.folder_separator).setVisibility(View.VISIBLE);
            if (accountId == mDefaultAccountId) {
                view.findViewById(R.id.default_sender).setVisibility(View.VISIBLE);
            } else {
                view.findViewById(R.id.default_sender).setVisibility(View.GONE);
            }
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return mInflater.inflate(R.layout.account_folder_list_item, parent, false);
        }

        /*
         * The following series of overrides insert the "Accounts" separator
         */

        /**
         * Prevents the separator view from recycling into the other views
         */
        @Override
        public int getItemViewType(int position) {
            if (position == mSeparatorPosition) {
                return IGNORE_ITEM_VIEW_TYPE;
            }
            return super.getItemViewType(position);
        }

        /**
         * Injects the separator view when required, and fudges the cursor for other views
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (!mDataValid) {
                throw new IllegalStateException("cursor invalid");
            }

            // Handle the separator here - create & bind
            if (position == mSeparatorPosition) {
                TextView view;
                view = (TextView) mInflater.inflate(R.layout.list_separator, parent, false);
                view.setText(R.string.account_folder_list_separator_accounts);
                return view;
            }

            if (!mCursor.moveToPosition(getRealPosition(position))) {
                throw new IllegalStateException("cursor failed move to " + position);
            }

            View v;
            if (convertView == null) {
                v = newView(mContext, mCursor, parent);
            } else {
                v = convertView;
            }
            bindView(v, mContext, mCursor);
            return v;
        }

        /**
         * Forces navigation to skip over the separator
         */
        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        /**
         * Forces navigation to skip over the separator
         */
        @Override
        public boolean isEnabled(int position) {
            return position != mSeparatorPosition;
        }

        /**
         * Adjusts list count to include separator
         */
        @Override
        public int getCount() {
            int count = super.getCount();
            if (mDataValid && (mSeparatorPosition != ListView.INVALID_POSITION)) {
                count += 1;
            }
            return count;
        }

        /**
         * Converts list position to cursor position
         */
        private int getRealPosition(int pos) {
            if (mSeparatorPosition == ListView.INVALID_POSITION) {
                // No separator, identity map
                return pos;
            } else if (pos <= mSeparatorPosition) {
                // Before or at the separator, identity map
                return pos;
            } else {
                // After the separator, remove 1 from the pos to get the real underlying pos
                return pos - 1;
            }
        }

        /**
         * Returns the item using external position numbering (no separator)
         */
        @Override
        public Object getItem(int pos) {
            return super.getItem(getRealPosition(pos));
        }

        /**
         * Returns the item id using external position numbering (no separator)
         */
        @Override
        public long getItemId(int pos) {
            return super.getItemId(getRealPosition(pos));
        }
    }
}


