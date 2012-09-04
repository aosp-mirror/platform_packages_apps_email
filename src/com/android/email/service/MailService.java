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
import android.accounts.AccountManagerFuture;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.android.email.provider.AccountReconciler;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.email2.ui.MailActivityEmail;
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
        reconcileLocalAccountsSync(this);
        // Make sure our services are running, if necessary
        MailActivityEmail.setServicesEnabledAsync(this);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static ArrayList<Account> getAccountList(Context context, String protocol) {
        ArrayList<Account> providerAccounts = new ArrayList<Account>();
        Cursor c = context.getContentResolver().query(Account.CONTENT_URI, Account.ID_PROJECTION,
                null, null, null);
        try {
            while (c.moveToNext()) {
                long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
                if (protocol.equals(Account.getProtocol(context, accountId))) {
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

    /**
     * Reconcile local (i.e. non-remote) accounts.
     */
    public static void reconcileLocalAccountsSync(Context context) {
        List<EmailServiceInfo> serviceList = EmailServiceUtils.getServiceInfoList(context);
        for (EmailServiceInfo info: serviceList) {
            if (info.klass != null) {
                new AccountReconcilerTask(context, info).runAsync();
            }
        }
    }

    static class AccountReconcilerTask implements Runnable {
        private final Context mContext;
        private final EmailServiceInfo mInfo;

        AccountReconcilerTask(Context context, EmailServiceInfo info) {
            mContext = context;
            mInfo = info;
        }

        public void runAsync() {
            EmailAsyncTask.runAsyncSerial(this);
        }

        @Override
        public void run() {
            Log.d("MailService", "Reconciling accounts of type " + mInfo.accountType +
                    ", protocol " + mInfo.protocol);
            android.accounts.Account[] accountManagerAccounts = AccountManager.get(mContext)
                    .getAccountsByType(mInfo.accountType);
            ArrayList<Account> providerAccounts = getAccountList(mContext, mInfo.protocol);
            reconcileAccountsWithAccountManager(mContext, providerAccounts,
                    accountManagerAccounts, mContext);
        }
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

    public static AccountManagerFuture<Bundle> setupAccountManagerAccount(Context context,
            Account account, boolean email, boolean calendar, boolean contacts,
            AccountManagerCallback<Bundle> callback) {
        Bundle options = new Bundle();
        HostAuth hostAuthRecv = HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv);
        if (hostAuthRecv == null) {
            return null;
        }
        // Set up username/password
        options.putString(EasAuthenticatorService.OPTIONS_USERNAME, account.mEmailAddress);
        options.putString(EasAuthenticatorService.OPTIONS_PASSWORD, hostAuthRecv.mPassword);
        options.putBoolean(EasAuthenticatorService.OPTIONS_CONTACTS_SYNC_ENABLED, contacts);
        options.putBoolean(EasAuthenticatorService.OPTIONS_CALENDAR_SYNC_ENABLED, calendar);
        options.putBoolean(EasAuthenticatorService.OPTIONS_EMAIL_SYNC_ENABLED, email);
        EmailServiceInfo info = EmailServiceUtils.getServiceInfo(context, hostAuthRecv.mProtocol);
        return AccountManager.get(context).addAccount(info.accountType, null, null, options, null,
                callback, null);
    }
}
