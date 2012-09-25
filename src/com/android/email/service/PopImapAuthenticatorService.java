/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.email.activity.setup.AccountSetupBasics;
import com.android.emailcommon.AccountManagerTypes;
import com.android.emailcommon.CalendarProviderStub;
import com.android.emailcommon.provider.EmailContent;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.NetworkErrorException;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.ContactsContract;

/**
 * A very basic authenticator service for POP/IMAP.  At the moment, it has no UI hooks.  When called
 * with addAccount, it simply adds the account to AccountManager directly with a username and
 * password.
 */
public class PopImapAuthenticatorService extends Service {
    public static final String OPTIONS_USERNAME = "username";
    public static final String OPTIONS_PASSWORD = "password";
    public static final String OPTIONS_CONTACTS_SYNC_ENABLED = "contacts";
    public static final String OPTIONS_CALENDAR_SYNC_ENABLED = "calendar";
    public static final String OPTIONS_EMAIL_SYNC_ENABLED = "email";

    class PopImapAuthenticator extends AbstractAccountAuthenticator {

        public PopImapAuthenticator(Context context) {
            super(context);
        }

        @Override
        public Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
                String authTokenType, String[] requiredFeatures, Bundle options)
                throws NetworkErrorException {
            // There are two cases here:
            // 1) We are called with a username/password; this comes from the traditional email
            //    app UI; we simply create the account and return the proper bundle
            if (options != null && options.containsKey(OPTIONS_PASSWORD)
                    && options.containsKey(OPTIONS_USERNAME)) {
                final Account account = new Account(options.getString(OPTIONS_USERNAME),
                        AccountManagerTypes.TYPE_POP_IMAP);
                AccountManager.get(PopImapAuthenticatorService.this).addAccountExplicitly(
                            account, options.getString(OPTIONS_PASSWORD), null);

                // Set up email syncing
                boolean syncEmail = false;
                if (options.containsKey(OPTIONS_EMAIL_SYNC_ENABLED) &&
                        options.getBoolean(OPTIONS_EMAIL_SYNC_ENABLED)) {
                    syncEmail = true;
                }
                ContentResolver.setIsSyncable(account, EmailContent.AUTHORITY, 1);
                ContentResolver.setSyncAutomatically(account, EmailContent.AUTHORITY, syncEmail);
                ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 0);
                ContentResolver.setIsSyncable(account, CalendarProviderStub.AUTHORITY, 0);

                Bundle b = new Bundle();
                b.putString(AccountManager.KEY_ACCOUNT_NAME, options.getString(OPTIONS_USERNAME));
                b.putString(AccountManager.KEY_ACCOUNT_TYPE, AccountManagerTypes.TYPE_POP_IMAP);
                return b;
            // 2) The other case is that we're creating a new account from an Account manager
            //    activity.  In this case, we add an intent that will be used to gather the
            //    account information...
            } else {
                Bundle b = new Bundle();
                Intent intent =
                    AccountSetupBasics.actionSetupPopImapIntent(PopImapAuthenticatorService.this);
                intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
                b.putParcelable(AccountManager.KEY_INTENT, intent);
                return b;
            }
        }

        @Override
        public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account,
                Bundle options) {
            return null;
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
            return null;
        }

    }

    @Override
    public IBinder onBind(Intent intent) {
        if (AccountManager.ACTION_AUTHENTICATOR_INTENT.equals(intent.getAction())) {
            return new PopImapAuthenticator(this).getIBinder();
        } else {
            return null;
        }
    }
}
