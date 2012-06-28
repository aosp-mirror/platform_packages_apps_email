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

package com.android.email.service;

import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;

import com.android.email.SingleRunningTask;
import com.android.email.provider.AccountReconciler;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.AccountManagerTypes;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Legacy service, now used mainly for account reconciliation
 */
public class MailService extends Service {

    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId) {
        super.onStartCommand(intent, flags, startId);

        EmailAsyncTask.runAsyncParallel(new Runnable() {
            @Override
            public void run() {
                reconcilePopImapAccountsSync(MailService.this);
            }
        });

        // Make sure our services are running, if necessary
        MailActivityEmail.setServicesEnabledAsync(this);

        // Returning START_NOT_STICKY means that if a mail check is killed (e.g. due to memory
        // pressure, there will be no explicit restart.  This is OK;  Note that we set a watchdog
        // alarm before each mailbox check.  If the mailbox check never completes, the watchdog
        // will fire and get things running again.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static ArrayList<Account> getPopImapAccountList(Context context) {
        ArrayList<Account> providerAccounts = new ArrayList<Account>();
        Cursor c = context.getContentResolver().query(Account.CONTENT_URI, Account.ID_PROJECTION,
                null, null, null);
        try {
            while (c.moveToNext()) {
                long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
                String protocol = Account.getProtocol(context, accountId);
                EmailServiceInfo info = EmailServiceUtils.getServiceInfo(context, protocol);
                if ((info != null) && info.accountType.equals(AccountManagerTypes.TYPE_POP_IMAP)) {
                    Account account = Account.restoreAccountWithId(context, accountId);
                    if (account != null) {
                        providerAccounts.add(account);
                    }
                }
            }
        } finally {
            c.close();
        }
        return providerAccounts;
    }

    private static final SingleRunningTask<Context> sReconcilePopImapAccountsSyncExecutor =
            new SingleRunningTask<Context>("ReconcilePopImapAccountsSync") {
                @Override
                protected void runInternal(Context context) {
                    android.accounts.Account[] accountManagerAccounts = AccountManager.get(context)
                            .getAccountsByType(AccountManagerTypes.TYPE_POP_IMAP);
                    ArrayList<Account> providerAccounts = getPopImapAccountList(context);
                    MailService.reconcileAccountsWithAccountManager(context, providerAccounts,
                            accountManagerAccounts, context);

                }
    };

    /**
     * Reconcile POP/IMAP accounts.
     */
    public static void reconcilePopImapAccountsSync(Context context) {
        sReconcilePopImapAccountsSyncExecutor.run(context);
    }

    /**
     * Determines whether or not POP/IMAP accounts need reconciling or not. This is a safe operation
     * to perform on the UI thread.
     */
    public static boolean hasMismatchInPopImapAccounts(Context context) {
        android.accounts.Account[] accountManagerAccounts = AccountManager.get(context)
                .getAccountsByType(AccountManagerTypes.TYPE_POP_IMAP);
        ArrayList<Account> providerAccounts = getPopImapAccountList(context);
        return AccountReconciler.accountsNeedReconciling(
                context, providerAccounts, accountManagerAccounts);
    }

    /**
     * See Utility.reconcileAccounts for details
     * @param context The context in which to operate
     * @param emailProviderAccounts the exchange provider accounts to work from
     * @param accountManagerAccounts The account manager accounts to work from
     * @param providerContext the provider's context (in unit tests, this may differ from context)
     */
    @VisibleForTesting
    public static void reconcileAccountsWithAccountManager(Context context,
            List<Account> emailProviderAccounts, android.accounts.Account[] accountManagerAccounts,
            Context providerContext) {
        AccountReconciler.reconcileAccounts(context, emailProviderAccounts, accountManagerAccounts,
                providerContext);
    }

    public static void setupAccountManagerAccount(Context context, Account account,
            boolean email, boolean calendar, boolean contacts,
            AccountManagerCallback<Bundle> callback) {
        Bundle options = new Bundle();
        HostAuth hostAuthRecv = HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv);
        if (hostAuthRecv == null) return;
        // Set up username/password
        options.putString(EasAuthenticatorService.OPTIONS_USERNAME, account.mEmailAddress);
        options.putString(EasAuthenticatorService.OPTIONS_PASSWORD, hostAuthRecv.mPassword);
        options.putBoolean(EasAuthenticatorService.OPTIONS_CONTACTS_SYNC_ENABLED, contacts);
        options.putBoolean(EasAuthenticatorService.OPTIONS_CALENDAR_SYNC_ENABLED, calendar);
        options.putBoolean(EasAuthenticatorService.OPTIONS_EMAIL_SYNC_ENABLED, email);
        EmailServiceInfo info = EmailServiceUtils.getServiceInfo(context, hostAuthRecv.mProtocol);
        AccountManager.get(context).addAccount(info.accountType, null, null, options, null,
                callback, null);
    }
}
