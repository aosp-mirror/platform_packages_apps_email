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
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.service.MailService;

import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
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
    private static final String EXTRA_ACCOUNT_ID = "com.android.email.activity._ACCOUNT_ID";

    private AccountsUpdatedListener mAccountsUpdatedListener;
    private Handler mHandler = new Handler();

    /**
     * @return true if the two-pane activity should be used on the current configuration.
     */
    public static boolean useTwoPane(Context context) {
        final int screenLayout = context.getResources().getConfiguration().screenLayout;
        return (screenLayout & Configuration.SCREENLAYOUT_SIZE_XLARGE) != 0;
    }

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

    /**
     * Create an Intent to open account's inbox.
     */
    public static Intent createOpenAccountInboxIntent(Activity fromActivity, long accountId) {
        Intent i = new Intent(fromActivity, Welcome.class);
        if (accountId != -1) {
            i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        }
        return i;
    }

    /**
     * Open account's inbox.
     */
    public static void actionOpenAccountInbox(Activity fromActivity, long accountId) {
        fromActivity.startActivity(createOpenAccountInboxIntent(fromActivity, accountId));
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

        final long accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, -1);
        new MainActivityLauncher(this, accountId).execute();
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
     * Open an account with the Activity appropriate to the current configuration.
     * If there's no accounts set up, open the "add account" screen.
     *
     * if {@code account} is -1, open the default account.
     */
    private static class MainActivityLauncher extends AsyncTask<Void, Void, Void> {
        private final Activity mFromActivity;
        private final long mAccountId;

        public MainActivityLauncher(Activity fromActivity, long accountId) {
            mFromActivity = fromActivity;
            mAccountId = accountId;
        }

        @Override
        protected Void doInBackground(Void... params) {
            final int numAccount =
                    EmailContent.count(mFromActivity, EmailContent.Account.CONTENT_URI);
            if (numAccount == 0) {
                AccountSetupBasics.actionNewAccount(mFromActivity);
            } else {
                long accountId = mAccountId;
                if (accountId == -1 || !Account.isValidId(mFromActivity, accountId)) {
                    accountId = EmailContent.Account.getDefaultAccountId(mFromActivity);
                }

                if (useTwoPane(mFromActivity)) {
                    MessageListXL.actionStart(mFromActivity, accountId);
                } else {
                    MessageList.actionHandleAccount(mFromActivity, accountId, Mailbox.TYPE_INBOX);
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
