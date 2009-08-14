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

package com.android.exchange;

import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;

import android.accounts.Account;
import android.accounts.OperationCanceledException;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

public class ContactsSyncAdapterService extends Service {
    private final String TAG = "EAS ContactsSyncAdapterService";
    private final SyncAdapterImpl mSyncAdapter;

    private static final String[] ID_PROJECTION = new String[] {EmailContent.RECORD_ID};
    private static final String ACCOUNT_AND_TYPE_CONTACTS =
        MailboxColumns.ACCOUNT_KEY + "=? AND " + MailboxColumns.TYPE + '=' + Mailbox.TYPE_CONTACTS;

    public ContactsSyncAdapterService() {
        super();
        mSyncAdapter = new SyncAdapterImpl();
    }

    private class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        public SyncAdapterImpl() {
            super(ContactsSyncAdapterService.this);
        }

        @Override
        public void performSync(Account account, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {
            try {
                ContactsSyncAdapterService.this.performSync(account, extras,
                        authority, provider, syncResult);
            } catch (OperationCanceledException e) {
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mSyncAdapter.getISyncAdapter().asBinder();
    }

    /**
     * Partial integration with system SyncManager; we tell our EAS SyncManager to start a contacts
     * sync when we get the signal from the system SyncManager.
     * The missing piece at this point is integration with the push/ping mechanism in EAS; this will
     * be put in place at a later time.
     */
    private void performSync(Account account, Bundle extras, String authority,
            ContentProviderClient provider, SyncResult syncResult)
            throws OperationCanceledException {
        ContentResolver cr = getContentResolver();
        // Find the (EmailProvider) account associated with this email address
        Cursor accountCursor =
            cr.query(com.android.email.provider.EmailContent.Account.CONTENT_URI, ID_PROJECTION,
                AccountColumns.EMAIL_ADDRESS + "=?", new String[] {account.name}, null);
        try {
            if (accountCursor.moveToFirst()) {
                long accountId = accountCursor.getLong(0);
                // Now, find the contacts mailbox associated with the account
                Cursor mailboxCursor = cr.query(Mailbox.CONTENT_URI, ID_PROJECTION,
                        ACCOUNT_AND_TYPE_CONTACTS, new String[] {Long.toString(accountId)}, null);
                try {
                    if (mailboxCursor.moveToFirst()) {
                        Log.i(TAG, "Contact sync requested for " + account.name);
                        // Ask for a sync from our sync manager
                        SyncManager.serviceRequest(mailboxCursor.getLong(0),
                                SyncManager.SYNC_UPSYNC);
                    }
                } finally {
                    mailboxCursor.close();
                }
            }
        } finally {
            accountCursor.close();
        }
    }
}