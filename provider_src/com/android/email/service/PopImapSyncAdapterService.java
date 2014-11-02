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

import com.android.email.R;
import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;

import java.util.ArrayList;

public class PopImapSyncAdapterService extends Service {
    private static final String TAG = "PopImapSyncService";
    private SyncAdapterImpl mSyncAdapter = null;

    public PopImapSyncAdapterService() {
        super();
    }

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        public SyncAdapterImpl(Context context) {
            super(context, true /* autoInitialize */);
        }

        @Override
        public void onPerformSync(android.accounts.Account account, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {
            PopImapSyncAdapterService.performSync(getContext(), account, extras, provider,
                    syncResult);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mSyncAdapter = new SyncAdapterImpl(getApplicationContext());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mSyncAdapter.getSyncAdapterBinder();
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

    private static void sync(final Context context, final long mailboxId,
            final Bundle extras, final SyncResult syncResult, final boolean uiRefresh,
            final int deltaMessageCount) {
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
            resolver.delete(Message.UPDATED_CONTENT_URI, MessageColumns.MAILBOX_KEY + "=?",
                    new String[] {Long.toString(mailbox.mId)});
            return;
        }
        LogUtils.d(TAG, "About to sync mailbox: " + mailbox.mDisplayName);

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
                } else {
                    EmailServiceStatus.syncMailboxStatus(resolver, extras, mailboxId,
                            EmailServiceStatus.IN_PROGRESS, 0, UIProvider.LastSyncResult.SUCCESS);
                    final int status;
                    if (protocol.equals(legacyImapProtocol)) {
                        status = ImapService.synchronizeMailboxSynchronous(context, account,
                                mailbox, deltaMessageCount != 0, uiRefresh);
                    } else {
                        status = Pop3Service.synchronizeMailboxSynchronous(context, account,
                                mailbox, deltaMessageCount);
                    }
                    EmailServiceStatus.syncMailboxStatus(resolver, extras, mailboxId, status, 0,
                            UIProvider.LastSyncResult.SUCCESS);
                }
            } catch (MessagingException e) {
                final int type = e.getExceptionType();
                // type must be translated into the domain of values used by EmailServiceStatus
                switch(type) {
                    case MessagingException.IOERROR:
                        EmailServiceStatus.syncMailboxStatus(resolver, extras, mailboxId, type, 0,
                                UIProvider.LastSyncResult.CONNECTION_ERROR);
                        syncResult.stats.numIoExceptions++;
                        break;
                    case MessagingException.AUTHENTICATION_FAILED:
                        EmailServiceStatus.syncMailboxStatus(resolver, extras, mailboxId, type, 0,
                                UIProvider.LastSyncResult.AUTH_ERROR);
                        syncResult.stats.numAuthExceptions++;
                        break;
                    case MessagingException.SERVER_ERROR:
                        EmailServiceStatus.syncMailboxStatus(resolver, extras, mailboxId, type, 0,
                                UIProvider.LastSyncResult.SERVER_ERROR);
                        break;

                    default:
                        EmailServiceStatus.syncMailboxStatus(resolver, extras, mailboxId, type, 0,
                                UIProvider.LastSyncResult.INTERNAL_ERROR);
                }
            }
        } finally {
            // Always clear our sync state and update sync time.
            values.put(Mailbox.UI_SYNC_STATUS, EmailContent.SYNC_STATUS_NONE);
            values.put(Mailbox.SYNC_TIME, System.currentTimeMillis());
            resolver.update(mailboxUri, values, null, null);
        }
    }

    /**
     * Partial integration with system SyncManager; we initiate manual syncs upon request
     */
    private static void performSync(Context context, android.accounts.Account account,
            Bundle extras, ContentProviderClient provider, SyncResult syncResult) {
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
                    LogUtils.d(TAG, "Upload sync request for " + acct.mDisplayName);
                    // See if any boxes have mail...
                    ArrayList<Long> mailboxesToUpdate;
                    Cursor updatesCursor = provider.query(Message.UPDATED_CONTENT_URI,
                            new String[] {MessageColumns.MAILBOX_KEY},
                            MessageColumns.ACCOUNT_KEY + "=?",
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
                        sync(context, mailboxId, extras, syncResult, false, 0);
                    }
                } else {
                    LogUtils.d(TAG, "Sync request for " + acct.mDisplayName);
                    LogUtils.d(TAG, extras.toString());

                    // We update our folder structure on every sync.
                    final EmailServiceProxy service =
                            EmailServiceUtils.getServiceForAccount(context, acct.mId);
                    service.updateFolderList(acct.mId);

                    // Get the id for the mailbox we want to sync.
                    long [] mailboxIds = Mailbox.getMailboxIdsFromBundle(extras);
                    if (mailboxIds == null || mailboxIds.length == 0) {
                        // No mailbox specified, just sync the inbox.
                        // TODO: IMAP may eventually want to allow multiple auto-sync mailboxes.
                        final long inboxId = Mailbox.findMailboxOfType(context, acct.mId,
                                Mailbox.TYPE_INBOX);
                        if (inboxId != Mailbox.NO_MAILBOX) {
                            mailboxIds = new long[1];
                            mailboxIds[0] = inboxId;
                        }
                    }

                    if (mailboxIds != null) {
                        boolean uiRefresh =
                            extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false);
                        int deltaMessageCount =
                                extras.getInt(Mailbox.SYNC_EXTRA_DELTA_MESSAGE_COUNT, 0);
                        for (long mailboxId : mailboxIds) {
                            sync(context, mailboxId, extras, syncResult, uiRefresh,
                                    deltaMessageCount);
                        }
                    }
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
