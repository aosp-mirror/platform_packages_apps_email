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

import com.android.email.AccountBackupRestore;
import com.android.email.NotificationController;
import com.android.email.ResourceHelper;
import com.android.emailcommon.service.IAccountService;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

public class AccountService extends Service {

    private Context mContext;

    private final IAccountService.Stub mBinder = new IAccountService.Stub() {

        @Override
        public void notifyLoginFailed(long accountId) throws RemoteException {
            NotificationController.getInstance(mContext).showLoginFailedNotification(accountId);
        }

        @Override
        public void notifyLoginSucceeded(long accountId) throws RemoteException {
            NotificationController.getInstance(mContext).cancelLoginFailedNotification(accountId);
        }

        @Override
        public void notifyNewMessages(long accountId) throws RemoteException {
            MailService.actionNotifyNewMessages(mContext, accountId);
        }

        @Override
        public void restoreAccountsIfNeeded() throws RemoteException {
            AccountBackupRestore.restoreAccountsIfNeeded(mContext);
        }

        @Override
        public void accountDeleted() throws RemoteException {
            MailService.accountDeleted(mContext);
        }

        @Override
        public int getAccountColor(long accountId) throws RemoteException {
            return ResourceHelper.getInstance(mContext).getAccountColor(accountId);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        if (mContext == null) {
            mContext = this;
        }
        return mBinder;
    }
}