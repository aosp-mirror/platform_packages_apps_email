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

package com.android.email.service;

import com.android.exchange.Eas;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.Constants;
import android.accounts.NetworkErrorException;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PermissionInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;

/**
 * A very basic authenticator service for EAS.  At the moment, it has no UI hooks.  When called
 * with addAccount, it simply adds the account to AccountManager directly with a username and
 * password.  We will need to implement confirmPassword, confirmCredentials, and updateCredentials.
 */
public class EasAuthenticatorService extends Service {

    class EasAuthenticator extends AbstractAccountAuthenticator {
        public EasAuthenticator(Context context) {
            super(context);
        }

        @Override
        public Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
                String authTokenType, String[] requiredFeatures, Bundle options)
                throws NetworkErrorException {
            // The Bundle we are passed has username and password set
            AccountManager.get(EasAuthenticatorService.this).blockingAddAccountExplicitly(
                    new Account(options.getString("username"), Eas.ACCOUNT_MANAGER_TYPE),
                    options.getString("password"), null);
            Bundle b = new Bundle();
            b.putString(Constants.ACCOUNT_NAME_KEY, options.getString("username"));
            b.putString(Constants.ACCOUNT_TYPE_KEY, Eas.ACCOUNT_MANAGER_TYPE);
            return b;
        }

        @Override
        public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public boolean confirmPassword(AccountAuthenticatorResponse response, Account account,
                String password) throws NetworkErrorException {
            // TODO Auto-generated method stub
            return false;
        }

        @Override
        public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
            return null;
        }

        @Override
        public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account,
                String authTokenType, Bundle loginOptions) throws NetworkErrorException {
            return null;
        }

        @Override
        public String getAuthTokenLabel(String authTokenType) {
            // null means we don't have compartmentalized authtoken types 
            return null;
        }

        @Override
        public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account,
                String[] features) throws NetworkErrorException {
            return null;
        }

        @Override
        public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account,
                String authTokenType, Bundle loginOptions) {
            // TODO Auto-generated method stub
            return null;
        }

    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO Replace this with an appropriate constant in AccountManager, when it's created
        String authenticatorIntent = "android.accounts.AccountAuthenticator";
        
        if (authenticatorIntent.equals(intent.getAction())) {
            return new EasAuthenticator(this).getIAccountAuthenticator().asBinder();
        } else {
            return null;
        }
    }
}
