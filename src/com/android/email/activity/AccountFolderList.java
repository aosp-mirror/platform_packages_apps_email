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
import com.android.email.activity.setup.AccountSettings;
import com.android.email.activity.setup.AccountSetupBasics;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.service.MailService;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Toast;

public class AccountFolderList extends Activity implements AccountFolderListFragment.Callback {
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

    // UI Support
    private boolean mProgressRunning;
    private AccountFolderListFragment mListFragment;

    private Controller.Result mControllerCallback;

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

        // STOPSHIP make progress work properly - temporarily missing from ActionBar
        // requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS); // this disables ActionBar
        setContentView(R.layout.account_folder_list);

        mControllerCallback = new ControllerResultUiThreadWrapper<ControllerResults>(
                new Handler(), new ControllerResults());
        mListFragment =
                (AccountFolderListFragment) findFragmentById(R.id.account_folder_list_fragment);
        mListFragment.bindActivityInfo(this);

        if (icicle != null && icicle.containsKey(ICICLE_SELECTED_ACCOUNT)) {
            mSelectedContextAccount = (Account) icicle.getParcelable(ICICLE_SELECTED_ACCOUNT);
        }

        mProgressRunning = false;
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void onAddNewAccount() {
        AccountSetupBasics.actionNewAccount(this);
    }

    /* Implements AccountFolderListFragment.Callback */
    public void onEditAccount(long accountId) {
        AccountSettings.actionSettings(this, accountId);
    }

    /* Implements AccountFolderListFragment.Callback */
    public void onRefresh(long accountId) {
        if (accountId == -1) {
            // TODO implement a suitable "Refresh all accounts" / "check mail" comment in Controller
            // TODO this is temp
            Toast.makeText(this, getString(R.string.account_folder_list_refresh_toast),
                    Toast.LENGTH_LONG).show();
        } else {
            showProgressIcon(true);
            Controller.getInstance(getApplication()).updateMailboxList(accountId);
            // TODO update the inbox too
        }
    }

    /* Implements AccountFolderListFragment.Callback */
    public void onCompose(long accountId) {
        if (accountId == -1) {
            accountId = Account.getDefaultAccountId(this);
        }
        if (accountId != -1) {
            MessageCompose.actionCompose(this, accountId);
        } else {
            onAddNewAccount();
        }
    }

    /* Implements AccountFolderListFragment.Callback */
    public void onDeleteAccount(long accountId) {
        mSelectedContextAccount = Account.restoreAccountWithId(this, accountId);
        showDialog(DIALOG_REMOVE_ACCOUNT);
    }

    /* Implements AccountFolderListFragment.Callback */
    public void onOpenAccount(long accountId) {
        MessageList.actionHandleAccount(this, accountId, Mailbox.TYPE_INBOX);
    }

    /* Implements AccountFolderListFragment.Callback */
    public void onOpenMailbox(long mailboxId) {
        MessageList.actionHandleMailbox(this, mailboxId);
    }

    /* Implements AccountFolderListFragment.Callback */
    public void onOpenMailboxes(long accountId) {
        MailboxList.actionHandleAccount(this, accountId);
    }

    @Override
    public Dialog onCreateDialog(int id, Bundle args) {
        switch (id) {
            case DIALOG_REMOVE_ACCOUNT:
                return createRemoveAccountDialog();
        }
        return super.onCreateDialog(id, args);
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
                    mListFragment.hideDeletingAccount(mSelectedContextAccount.mId);

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
    public void onPrepareDialog(int id, Dialog dialog, Bundle args) {
        switch (id) {
            case DIALOG_REMOVE_ACCOUNT:
                AlertDialog alert = (AlertDialog) dialog;
                alert.setMessage(getString(R.string.account_delete_dlg_instructions_fmt,
                        mSelectedContextAccount.getDisplayName()));
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

    // STOPSHIP - this is a placeholder if/until there's support for progress in actionbar
    // Remove it, or replace with a better icon
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        MenuItem item = menu.findItem(R.id.check_mail);
        if (mProgressRunning) {
            item.setIcon(android.R.drawable.progress_indeterminate_horizontal);
        } else {
            item.setIcon(R.drawable.ic_menu_refresh);
        }
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.account_folder_list_option, menu);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getKeyCode() == SECRET_KEY_CODES[mSecretKeyCodeIndex]) {
            mSecretKeyCodeIndex++;
            if (mSecretKeyCodeIndex == SECRET_KEY_CODES.length) {
                mSecretKeyCodeIndex = 0;
                Debug.actionShow(this);
            }
        } else {
            mSecretKeyCodeIndex = 0;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showProgressIcon(boolean show) {
        // STOPSHIP:  This doesn't work, pending fix is bug b/2802962
        //setProgressBarIndeterminateVisibility(show);
        // STOPSHIP:  This is a hack used to replace the refresh icon with a spinner
        mProgressRunning = show;
        invalidateOptionsMenu();
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
        }

        private void updateProgress(MessagingException result, int progress) {
            showProgressIcon(result == null && progress < 100);
        }
    }
}
