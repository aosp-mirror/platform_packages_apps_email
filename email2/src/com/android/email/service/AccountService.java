/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;

import com.android.email.Email;
import com.android.email.NotificationController;
import com.android.email.ResourceHelper;
import com.android.email.VendorPolicyLoader;
import com.android.email.provider.AccountReconciler;
import com.android.emailcommon.Configuration;
import com.android.emailcommon.Device;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.service.IAccountService;
import com.android.emailcommon.utility.EmailAsyncTask;

import java.io.IOException;
import java.util.ArrayList;

public class AccountService extends Service {

    // Save context
    private Context mContext;

    private final IAccountService.Stub mBinder = new IAccountService.Stub() {

        @Override
        public void notifyLoginFailed(long accountId) {
            NotificationController.getInstance(mContext).showLoginFailedNotification(accountId);
        }

        @Override
        public void notifyLoginSucceeded(long accountId) {
            NotificationController.getInstance(mContext).cancelLoginFailedNotification(accountId);
        }

        private ArrayList<Account> getAccountList(String forProtocol) {
            ArrayList<Account> providerAccounts = new ArrayList<Account>();
            Cursor c = mContext.getContentResolver().query(Account.CONTENT_URI,
                    Account.ID_PROJECTION, null, null, null);
            try {
                while (c.moveToNext()) {
                    long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
                    String protocol = Account.getProtocol(mContext, accountId);
                    if ((protocol != null) && forProtocol.equals(protocol)) {
                        Account account = Account.restoreAccountWithId(mContext, accountId);
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

        @Override
        public void reconcileAccounts(String protocol, String accountManagerType) {
            ArrayList<Account> providerList = getAccountList(protocol);
            android.accounts.Account[] accountMgrList =
                AccountManager.get(mContext).getAccountsByType(accountManagerType);
            AccountReconciler.reconcileAccounts(mContext, providerList, accountMgrList, mContext);
        }

        @Override
        public int getAccountColor(long accountId) {
            return ResourceHelper.getInstance(mContext).getAccountColor(accountId);
        }

        @Override
        public Bundle getConfigurationData(String accountType) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(Configuration.EXCHANGE_CONFIGURATION_USE_ALTERNATE_STRINGS,
                    VendorPolicyLoader.getInstance(mContext).useAlternateExchangeStrings());
            return bundle;
        }

        @Override
        public String getDeviceId() {
            try {
                EmailAsyncTask.runAsyncSerial(new Runnable() {
                    @Override
                    public void run() {
                        // Make sure the service is properly running (re: lifecycle)
                        EmailServiceUtils.startExchangeService(mContext);
                        // Send current logging flags
                        Email.updateLoggingFlags(mContext);
                    }});
                return Device.getDeviceId(mContext);
            } catch (IOException e) {
                return null;
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        if (mContext == null) {
            mContext = this;
        }
        // Make sure we have a valid deviceId (just retrieves a static String except first time)
        try {
            Device.getDeviceId(this);
        } catch (IOException e) {
        }
        return mBinder;
    }
}