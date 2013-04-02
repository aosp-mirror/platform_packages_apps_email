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

import android.accounts.OperationCanceledException;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.android.email.R;
import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceProxy;

import java.util.ArrayList;

public class PopImapSyncAdapterService extends Service {
    private static final String TAG = "PopImapSyncService";
    private static SyncAdapterImpl sSyncAdapter = null;
    private static final Object sSyncAdapterLock = new Object();

    public PopImapSyncAdapterService() {
        super();
    }

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        private Context mContext;

        public SyncAdapterImpl(Context context) {
            super(context, true /* autoInitialize */);
            mContext = context;
        }

        @Override
        public void onPerformSync(android.accounts.Account account, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {
            try {
                PopImapSyncAdapterService.performSync(mContext, account, extras,
                        authority, provider, syncResult);
            } catch (OperationCanceledException e) {
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (sSyncAdapterLock) {
            if (sSyncAdapter == null) {
                sSyncAdapter = new SyncAdapterImpl(getApplicationContext());
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return sSyncAdapter.getSyncAdapterBinder();
    }

    /**
     * @return whether or not this mailbox retrieves its data from the server (as opposed to just
     *     a local mailbox that is never synced).
     */
    private static boolean loadsFromServer(Context context, Mailbox m, String protocol) {
        String legacyImapProtocol = context.getString(R.string.protocol_legacy_imap);
        String pop3Protocol = context.getString(R.string.protocol_pop3);
        if (legacyImapProtocol.equals(protocol)) {
            // TODO: actually use a sync flag when creating the mailboxes. Right now we use an
            // approximation for IMAP.
            return m.mType != Mailbox.TYPE_DRAFTS
                    && m.mType != Mailbox.TYPE_OUTBOX
                    && m.mType != Mailbox.TYPE_SEARCH;

        } else if (pop3Protocol.equals(protocol)) {
            return Mailbox.TYPE_INBOX == m.mType;
        }

        return false;
    }

    private static void sync(Context context, long mailboxId, SyncResult syncResult,
            boolean uiRefresh) {
        TempDirectory.setTempDirectory(context);
        Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
        if (mailbox == null) return;
        Account account = Account.restoreAccountWithId(context, mailbox.mAccountKey);
        if (account == null) return;
        ContentResolver resolver = context.getContentResolver();
        String protocol = account.getProtocol(context);
        if ((mailbox.mType != Mailbox.TYPE_OUTBOX) &&
                !loadsFromServer(context, mailbox, protocol)) {
            // This is an update to a message in a non-syncing mailbox; delete this from the
            // updates table and return
            resolver.delete(Message.UPDATED_CONTENT_URI, Message.MAILBOX_KEY + "=?",
                    new String[] {Long.toString(mailbox.mId)});
            return;
        }
        Log.d(TAG, "Mailbox: " + mailbox.mDisplayName);

        Uri mailboxUri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId);
        ContentValues values = new ContentValues();
        // Set mailbox sync state
        values.put(Mailbox.UI_SYNC_STATUS,
                uiRefresh ? EmailContent.SYNC_STATUS_USER : EmailContent.SYNC_STATUS_BACKGROUND);
        resolver.update(mailboxUri, values, null, null);
        try {
            try {
                String legacyImapProtocol = context.getString(R.string.protocol_legacy_imap);
                if (mailbox.mType == Mailbox.TYPE_OUTBOX) {
                    EmailServiceStub.sendMailImpl(context, account.mId);
                } else if (protocol.equals(legacyImapProtocol)) {
                    ImapService.synchronizeMailboxSynchronous(context, account, mailbox);
                } else {
                    Pop3Service.synchronizeMailboxSynchronous(context, account, mailbox);
                }
            } catch (MessagingException e) {
                int cause = e.getExceptionType();
                switch(cause) {
                    case MessagingException.IOERROR:
                        syncResult.stats.numIoExceptions++;
                        break;
                    case MessagingException.AUTHENTICATION_FAILED:
                        syncResult.stats.numAuthExceptions++;
                        break;
                }
            }
        } finally {
            // Always clear our sync state
            values.put(Mailbox.UI_SYNC_STATUS, EmailContent.SYNC_STATUS_NONE);
            resolver.update(mailboxUri, values, null, null);
        }
    }

    /**
     * Partial integration with system SyncManager; we initiate manual syncs upon request
     */
    private static void performSync(Context context, android.accounts.Account account,
            Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult)
                    throws OperationCanceledException {
        // Find an EmailProvider account with the Account's email address
        Cursor c = null;
        try {
            c = provider.query(com.android.emailcommon.provider.Account.CONTENT_URI,
                    Account.CONTENT_PROJECTION, AccountColumns.EMAIL_ADDRESS + "=?",
                    new String[] {account.name}, null);
            if (c != null && c.moveToNext()) {
                Account acct = new Account();
                acct.restore(c);
                if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD)) {
                    Log.d(TAG, "Upload sync request for " + acct.mDisplayName);
                    // See if any boxes have mail...
                    ArrayList<Long> mailboxesToUpdate;
                    Cursor updatesCursor = provider.query(Message.UPDATED_CONTENT_URI,
                            new String[] {Message.MAILBOX_KEY},
                            Message.ACCOUNT_KEY + "=?",
                            new String[] {Long.toString(acct.mId)},
                            null);
                    try {
                        if ((updatesCursor == null) || (updatesCursor.getCount() == 0)) return;
                        mailboxesToUpdate = new ArrayList<Long>();
                        while (updatesCursor.moveToNext()) {
                            Long mailboxId = updatesCursor.getLong(0);
                            if (!mailboxesToUpdate.contains(mailboxId)) {
                                mailboxesToUpdate.add(mailboxId);
                            }
                        }
                    } finally {
                        if (updatesCursor != null) {
                            updatesCursor.close();
                        }
                    }
                    for (long mailboxId: mailboxesToUpdate) {
                        sync(context, mailboxId, syncResult, false);
                    }
                } else {
                    Log.d(TAG, "Sync request for " + acct.mDisplayName);
                    Log.d(TAG, extras.toString());
                    long mailboxId = extras.getLong(EmailServiceStub.SYNC_EXTRA_MAILBOX_ID,
                            Mailbox.NO_MAILBOX);
                    boolean isInbox = false;
                    if (mailboxId == Mailbox.NO_MAILBOX) {
                        mailboxId = Mailbox.findMailboxOfType(context, acct.mId,
                                Mailbox.TYPE_INBOX);
                        if (mailboxId == Mailbox.NO_MAILBOX) {
                            // Update folders?
                            EmailServiceProxy service =
                                EmailServiceUtils.getServiceForAccount(context, null, acct.mId);
                            service.updateFolderList(acct.mId);
                        }
                        isInbox = true;
                    }
                    if (mailboxId == Mailbox.NO_MAILBOX) return;
                    boolean uiRefresh =
                            extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false);
                    sync(context, mailboxId, syncResult, uiRefresh);

                    // Outbox is a special case here
                    Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
                    if (mailbox.mType == Mailbox.TYPE_OUTBOX) {
                        return;
                    }

                    // Convert from minutes to seconds
                    int syncFrequency = acct.mSyncInterval * 60;
                    // Values < 0 are for "never" or "push"; 0 is undefined
                    if (syncFrequency <= 0) return;
                    Bundle ex = new Bundle();
                    if (!isInbox) {
                        ex.putLong(EmailServiceStub.SYNC_EXTRA_MAILBOX_ID, mailboxId);
                    }
                    Log.d(TAG, "Setting periodic sync for " + acct.mDisplayName + ": " +
                            syncFrequency + " seconds");
                    ContentResolver.addPeriodicSync(account, authority, ex, syncFrequency);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }
}