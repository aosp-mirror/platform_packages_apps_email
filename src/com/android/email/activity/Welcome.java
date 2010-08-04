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

import com.android.email.AccountBackupRestore;
import com.android.email.Email;
import com.android.email.ExchangeUtils;
import com.android.email.Utility;
import com.android.email.activity.setup.AccountSetupBasics;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.service.MailService;

import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;

/**
 * The Welcome activity initializes the application and decides what Activity
 * the user should start with.
 * If no accounts are configured the user is taken to the AccountSetupBasics Activity where they
 * can configure an account.
 * If a single account is configured the user is taken directly to the MessageList for
 * the INBOX of that account.
 * If more than one account is configured the user is taken to the AccountFolderList Activity so
 * they can select an account.
 */
public class Welcome extends Activity {

    private AccountsUpdatedListener mAccountsUpdatedListener;
    private Handler mHandler = new Handler();

    /**
     * Launch this activity.  Note:  It's assumed that this activity is only called as a means to
     * 'reset' the UI state; Because of this, it is always launched with FLAG_ACTIVITY_CLEAR_TOP,
     * which will drop any other activities on the stack (e.g. AccountFolderList or MessageList).
     */
    public static void actionStart(Activity fromActivity) {
        Intent i = new Intent(fromActivity, Welcome.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        fromActivity.startActivity(i);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // Reset the "accounts changed" notification, now that we're here
        Email.setNotifyUiAccountsChanged(false);

        // Quickly check for bulk upgrades (from older app versions) and switch to the
        // upgrade activity if necessary
        if (UpgradeAccounts.doBulkUpgradeIfNecessary(this)) {
            finish();
            return;
        }

        // Restore accounts, if it has not happened already
        // NOTE:  This is blocking, which it should not be (in the UI thread)
        // We're going to live with this for the short term and replace with something
        // smarter.  Long-term fix:  Move this, and most of the code below, to an AsyncTask
        // and do the DB work in a thread.  Then post handler to finish() as appropriate.
        AccountBackupRestore.restoreAccountsIfNeeded(this);

        // Because the app could be reloaded (for debugging, etc.), we need to make sure that
        // SyncManager gets a chance to start.  There is no harm to starting it if it has already
        // been started
        // TODO More completely separate SyncManager from Email app
        ExchangeUtils.startExchangeService(this);

        // TODO Move this listener code to a more central location
        // Set up our observer for AccountManager
        mAccountsUpdatedListener = new AccountsUpdatedListener();
        AccountManager.get(getApplication()).addOnAccountsUpdatedListener(
                mAccountsUpdatedListener, mHandler, true);
        // Run reconciliation to make sure we're up-to-date on account status
        mAccountsUpdatedListener.onAccountsUpdated(null);

        new MainActivityLauncher(this).execute();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mAccountsUpdatedListener != null) {
            AccountManager.get(this).removeOnAccountsUpdatedListener(mAccountsUpdatedListener);
        }
    }

    /**
     * Reconcile accounts when accounts are added/removed from AccountManager
     */
    public class AccountsUpdatedListener implements OnAccountsUpdateListener {
        public void onAccountsUpdated(android.accounts.Account[] accounts) {
            Utility.runAsync(new Runnable() {
                public void run() {
                    MailService.reconcilePopImapAccounts(Welcome.this);
                }
            });
        }
    }

    /**
     * Open the Activity appropriate to the current configuration.
     *
     * - If there's 0 accounts, open AccountSetupBasics.
     * - If it has XL screen, open MessageListXL.
     * - If there's 1 account, open MessageList.
     * - Otherwise open AccountFolderList.
     */
    private static class MainActivityLauncher extends AsyncTask<Void, Void, Void> {
        private final Activity mFromActivity;

        public MainActivityLauncher(Activity fromActivity) {
            mFromActivity = fromActivity;
        }

        @Override
        protected Void doInBackground(Void... params) {
            final int numAccount =
                    EmailContent.count(mFromActivity, EmailContent.Account.CONTENT_URI);
            if (numAccount == 0) {
                AccountSetupBasics.actionNewAccount(mFromActivity);
            } else {
                final int screenLayout = mFromActivity.getResources().getConfiguration()
                        .screenLayout;
                if ((screenLayout & Configuration.SCREENLAYOUT_SIZE_XLARGE) != 0) {
                    MessageListXL.actionStart(mFromActivity);
                } else {
                    if (numAccount == 1) {
                        long accountId = EmailContent.Account.getDefaultAccountId(mFromActivity);
                        MessageList.actionHandleAccount(mFromActivity, accountId,
                                Mailbox.TYPE_INBOX);
                    } else {
                        AccountFolderList.actionShowAccounts(mFromActivity);
                    }
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mFromActivity.finish();
        }
    }
}
