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
import android.os.Bundle;
import android.os.IBinder;

import com.android.email.provider.AccountReconciler;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;

/**
 * Legacy service, now used mainly for account reconciliation
 * TODO: Eliminate this service, since it doesn't actually do anything.
 */
public class MailService extends Service {

    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId) {
        super.onStartCommand(intent, flags, startId);
        AccountReconciler.reconcileAccounts(this);
        // Make sure our services are running, if necessary
        MailActivityEmail.setServicesEnabledAsync(this);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
