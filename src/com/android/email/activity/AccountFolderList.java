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

import com.android.email.Email;
import com.android.email.MessagingController;
import com.android.email.R;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.activity.setup.AccountSetupBasics;
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
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.TextView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;

public class AccountFolderList extends ExpandableListActivity
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
    
    ExpandableListView mListView;
    LoadAccountsTask mAsyncTask;

    /**
     * Support for list adapter
     */
    private final static String[] sFromColumns = new String[] { 
            EmailContent.AccountColumns.DISPLAY_NAME,
            EmailContent.AccountColumns.EMAIL_ADDRESS,
            EmailContent.RECORD_ID
    };
    private final int[] sToIds = new int[] {
            R.id.description,
            R.id.email,
            R.id.new_message_count
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
        setContentView(R.layout.account_folder_list);
        mListView = getExpandableListView();
        mListView.setOnItemClickListener(this);
        mListView.setItemsCanFocus(false);
        mListView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_INSET);
        mListView.setLongClickable(true);
        registerForContextMenu(mListView);

        findViewById(R.id.add_new_account).setOnClickListener(this);

        if (icicle != null && icicle.containsKey(ICICLE_SELECTED_ACCOUNT)) {
            mSelectedContextAccount = (Account) icicle.getParcelable(ICICLE_SELECTED_ACCOUNT);
        }

        mAsyncTask = (LoadAccountsTask) new LoadAccountsTask().execute();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mSelectedContextAccount != null) {
            outState.putParcelable(ICICLE_SELECTED_ACCOUNT, mSelectedContextAccount);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        NotificationManager notifMgr = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        notifMgr.cancel(1);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mAsyncTask != null && mAsyncTask.getStatus() != LoadAccountsTask.Status.FINISHED) {
            mAsyncTask.cancel(true);
            mAsyncTask = null;
        }
    }

    @Override
    public void onGroupExpand(int groupPosition) {
        super.onGroupExpand(groupPosition);

        // This is a temporary hack, until I implement the child cursors
        AccountsAdapter adapter = (AccountsAdapter) mListView.getExpandableListAdapter();
        if (adapter != null) {
            Cursor groupCursor = adapter.getGroup(groupPosition);
            long mailboxKey = groupCursor.getLong(EmailContent.Mailbox.CONTENT_ID_COLUMN);
            FolderMessageList.actionHandleAccount(this, mailboxKey);
        }
    }

    /**
     * Async task to handle the cursor query out of the UI thread
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
            AccountsAdapter adapter = new AccountsAdapter(theCursor, AccountFolderList.this);
            mListView.setAdapter(adapter);

            // This is deferred until after the first fetch, so it won't flicker
            // while we're waiting to find out if we have any accounts
            mListView.setEmptyView(findViewById(R.id.empty));
        }
    }

    private void onAddNewAccount() {
        AccountSetupBasics.actionNewAccount(this);
    }

    private void onEditAccount(long accountId) {
        AccountSettings.actionSettings(this, accountId);
    }

    private void onRefresh() {
        MessagingController.getInstance(getApplication()).checkMail(this, null, null);
    }

    private void onCompose() {
        EmailContent.Account defaultAccount = EmailContent.Account.getDefaultAccount(this);
        if (defaultAccount != null) {
            MessageCompose.actionCompose(this, defaultAccount.mId);
        } else {
            onAddNewAccount();
        }
    }

    private void onOpenAccount(long accountId) {
        FolderMessageList.actionHandleAccount(this, accountId);
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
        AdapterContextMenuInfo menuInfo = (AdapterContextMenuInfo)item.getMenuInfo();
        Cursor c = (Cursor) mListView.getItemAtPosition(menuInfo.position);
        long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
        switch (item.getItemId()) {
            case R.id.delete_account:
                onDeleteAccount(accountId);
                break;
            case R.id.edit_account:
                onEditAccount(accountId);
                break;
            case R.id.open:
                onOpenAccount(accountId);
                break;
        }
        return true;
    }

    public void onItemClick(AdapterView parent, View view, int position, long id) {
        Cursor c = (Cursor) mListView.getItemAtPosition(position);
        long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
        onOpenAccount(accountId);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.add_new_account:
                onAddNewAccount();
                break;
            case R.id.check_mail:
                onRefresh();
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
        getMenuInflater().inflate(R.menu.accounts_option, menu);
        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        menu.setHeaderTitle(R.string.accounts_context_menu_title);
        getMenuInflater().inflate(R.menu.accounts_context, menu);
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
            // TODO Auto-generated method stub
            
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

            TextView countView = (TextView) view.findViewById(R.id.new_message_count);
            int unreadMessageCount = 0;     // TODO get unread count from Account
            if (unreadMessageCount <= 0) {
                countView.setVisibility(View.GONE);
            } else {
                countView.setText(String.valueOf(unreadMessageCount));
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
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        protected View newGroupView(Context context, Cursor cursor, boolean isExpanded,
                ViewGroup parent) {
            return mInflater.inflate(R.layout.account_folder_list_group, parent, false);
        }
    }
}


