/*
 *  Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

package com.android.emailsync;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.ProviderUnavailableException;
import com.android.mail.utils.LogUtils;

import java.util.ArrayList;

/**
 * EmailSyncAlarmReceiver (USAR) is used by the SyncManager to start up-syncs of user-modified data
 * back to the Exchange server.
 *
 * Here's how this works for Email, for example:
 *
 * 1) User modifies or deletes an email from the UI.
 * 2) SyncManager, which has a ContentObserver watching the Message class, is alerted to a change
 * 3) SyncManager sets an alarm (to be received by USAR) for a few seconds in the
 * future (currently 15), the delay preventing excess syncing (think of it as a debounce mechanism).
 * 4) ESAR Receiver's onReceive method is called
 * 5) ESAR goes through all change and deletion records and compiles a list of mailboxes which have
 * changes to be uploaded.
 * 6) ESAR calls SyncManager to start syncs of those mailboxes
 *
 * If EmailProvider isn't available, the upsyncs will happen the next time ExchangeService starts
 *
 */
public class EmailSyncAlarmReceiver extends BroadcastReceiver {
    final String[] MAILBOX_DATA_PROJECTION = {MessageColumns.MAILBOX_KEY};

    @Override
    public void onReceive(final Context context, Intent intent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                handleReceive(context);
            }
        }).start();
    }

    private void handleReceive(Context context) {
        ArrayList<Long> mailboxesToNotify = new ArrayList<Long>();
        ContentResolver cr = context.getContentResolver();

        // Get a selector for EAS accounts (we don't want to sync on changes to POP/IMAP messages)
        String selector = SyncManager.getAccountSelector();

        try {
            // Find all of the deletions
            Cursor c = cr.query(Message.DELETED_CONTENT_URI, MAILBOX_DATA_PROJECTION, selector,
                   null, null);
            if (c == null) throw new ProviderUnavailableException();
            try {
                // Keep track of which mailboxes to notify; we'll only notify each one once
                while (c.moveToNext()) {
                    long mailboxId = c.getLong(0);
                    if (!mailboxesToNotify.contains(mailboxId)) {
                        mailboxesToNotify.add(mailboxId);
                    }
                }
            } finally {
                c.close();
            }

            // Now, find changed messages
            c = cr.query(Message.UPDATED_CONTENT_URI, MAILBOX_DATA_PROJECTION, selector,
                    null, null);
            if (c == null) throw new ProviderUnavailableException();
            try {
                // Keep track of which mailboxes to notify; we'll only notify each one once
                while (c.moveToNext()) {
                    long mailboxId = c.getLong(0);
                    if (!mailboxesToNotify.contains(mailboxId)) {
                        mailboxesToNotify.add(mailboxId);
                    }
                }
            } finally {
                c.close();
            }

            // Request service from the mailbox
            for (Long mailboxId: mailboxesToNotify) {
                SyncManager.serviceRequest(mailboxId, SyncManager.SYNC_UPSYNC);
            }
        } catch (ProviderUnavailableException e) {
            LogUtils.e("EmailSyncAlarmReceiver",
                    "EmailProvider unavailable; aborting alarm receiver");
        }
    }
}
