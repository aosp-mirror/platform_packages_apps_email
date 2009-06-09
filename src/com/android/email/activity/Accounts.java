/*
 * Copyright (C) 2008 The Android Open Source Project
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
import com.android.email.mail.store.LocalStore;
import com.android.email.provider.EmailStore;
import com.android.email.provider.EmailStore.Account;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.NotificationManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;

public class Accounts extends ListActivity implements OnItemClickListener, OnClickListener {
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
    private EmailStore.Account mSelectedContextAccount;
    
    /**
     * Support for list adapter
     */
    private final static String[] sFromColumns = new String[] { 
            EmailStore.AccountColumns.DISPLAY_NAME,
            EmailStore.AccountColumns.EMAIL_ADDRESS,
            EmailStore.RECORD_ID
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
        Intent i = new Intent(context, Accounts.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(i);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.accounts);
        ListView listView = getListView();
        listView.setOnItemClickListener(this);
        listView.setItemsCanFocus(false);
        listView.setEmptyView(findViewById(R.id.empty));
        findViewById(R.id.add_new_account).setOnClickListener(this);
        registerForContextMenu(listView);

        if (icicle != null && icicle.containsKey(ICICLE_SELECTED_ACCOUNT)) {
            mSelectedContextAccount = (Account) icicle.getParcelable(ICICLE_SELECTED_ACCOUNT);
        }
        
        // TODO: lightweight projection with only those columns needed for this display
        // TODO: query outside of UI thread
        Cursor c = this.managedQuery(
                EmailStore.Account.CONTENT_URI, 
                EmailStore.Account.CONTENT_PROJECTION,
                null, null);
        AccountsAdapter a = new AccountsAdapter(this, 
                R.layout.accounts_item, c, sFromColumns, sToIds);
        listView.setAdapter(a);
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
        EmailStore.Account defaultAccount = EmailStore.Account.getDefaultAccount(this);
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
                        LocalStore localStore = (LocalStore) Store.getInstance(
                                mSelectedContextAccount.getLocalStoreUri(Accounts.this),
                                getApplication(), 
                                null);
                        // Delete Remote store at first.
                        Store.getInstance(
                                mSelectedContextAccount.getStoreUri(Accounts.this),
                                getApplication(), 
                                localStore.getPersistentCallbacks()).delete();
                        // Remove the Store instance from cache.
                        Store.removeInstance(mSelectedContextAccount.getStoreUri(Accounts.this));
                        // If no error, then delete LocalStore
                        localStore.delete();
                    } catch (Exception e) {
                            // Ignore
                    }
                    Uri uri = ContentUris.withAppendedId(
                            EmailStore.Account.CONTENT_URI, mSelectedContextAccount.mId);
                    Accounts.this.getContentResolver().delete(uri, null, null);
                    Email.setServicesEnabled(Accounts.this);
                }
            })
            .setNegativeButton(R.string.cancel_action, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    dismissDialog(DIALOG_REMOVE_ACCOUNT);
                }
            })
            .create();
    }

    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo menuInfo = (AdapterContextMenuInfo)item.getMenuInfo();
        Cursor c = (Cursor) getListView().getItemAtPosition(menuInfo.position);
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
        Cursor c = (Cursor) getListView().getItemAtPosition(position);
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
    
    private static class AccountsAdapter extends SimpleCursorAdapter {

        public AccountsAdapter(Context context, int layout, Cursor c, String[] from, int[] to) {
            super(context, layout, c, from, to);
            setViewBinder(new MyViewBinder());
        }
        
        /**
         * This is only used for the unread messages count.  Most of the views are handled
         * normally by SimpleCursorAdapter.
         */
        private static class MyViewBinder implements SimpleCursorAdapter.ViewBinder {

            public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
                if (view.getId() == R.id.new_message_count) {
                    
                    int unreadMessageCount = 0;     // TODO get unread count from Account
                    if (unreadMessageCount <= 0) {
                        view.setVisibility(View.GONE);
                    } else {
                        ((TextView)view).setText(String.valueOf(unreadMessageCount));
                    }
                    return true;
                }
                
                return false;
            }
        }
    }
}


