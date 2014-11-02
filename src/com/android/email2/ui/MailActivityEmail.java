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

package com.android.email2.ui;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.android.email.Preferences;
import com.android.email.provider.EmailProvider;
import com.android.email.service.AttachmentService;
import com.android.email.service.EmailServiceUtils;
import com.android.emailcommon.Logging;
import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.IntentUtilities;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;

public class MailActivityEmail extends com.android.mail.ui.MailActivity {

    public static final String LOG_TAG = LogTag.getLogTag();

    private static final int MATCH_LEGACY_SHORTCUT_INTENT = 1;
    /**
     * A matcher for data URI's that specify conversation list info.
     */
    private static final UriMatcher sUrlMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sUrlMatcher.addURI(
                EmailProvider.LEGACY_AUTHORITY, "view/mailbox", MATCH_LEGACY_SHORTCUT_INTENT);
    }


    @Override
    public void onCreate(Bundle bundle) {
        final Intent intent = getIntent();
        final Uri data = intent != null ? intent.getData() : null;
        if (data != null) {
            final int match = sUrlMatcher.match(data);
            switch (match) {
                case MATCH_LEGACY_SHORTCUT_INTENT: {
                    final long mailboxId = IntentUtilities.getMailboxIdFromIntent(intent);
                    final Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mailboxId);
                    if (mailbox == null) {
                        LogUtils.e(LOG_TAG, "unable to restore mailbox");
                        break;
                    }

                    final Intent viewIntent = getViewIntent(mailbox.mAccountKey, mailboxId);
                    if (viewIntent != null) {
                        setIntent(viewIntent);
                    }
                    break;
                }
            }
        }

        super.onCreate(bundle);
        TempDirectory.setTempDirectory(this);

        // Make sure all required services are running when the app is started (can prevent
        // issues after an adb sync/install)
        EmailProvider.setServicesEnabledAsync(this);
    }

    /**
     * Internal, utility method for logging.
     * The calls to log() must be guarded with "if (Email.LOGD)" for performance reasons.
     */
    public static void log(String message) {
        LogUtils.d(Logging.LOG_TAG, message);
    }

    private Intent getViewIntent(long accountId, long mailboxId) {
        final ContentResolver contentResolver = getContentResolver();

        final Cursor accountCursor = contentResolver.query(
                EmailProvider.uiUri("uiaccount", accountId),
                UIProvider.ACCOUNTS_PROJECTION_NO_CAPABILITIES,
                null, null, null);

        if (accountCursor == null) {
            LogUtils.e(LOG_TAG, "Null account cursor for mAccountId %d", accountId);
            return null;
        }

        com.android.mail.providers.Account account = null;
        try {
            if (accountCursor.moveToFirst()) {
                account = com.android.mail.providers.Account.builder().buildFrom(accountCursor);
            }
        } finally {
            accountCursor.close();
        }


        final Cursor folderCursor = contentResolver.query(
                EmailProvider.uiUri("uifolder", mailboxId),
                UIProvider.FOLDERS_PROJECTION, null, null, null);

        if (folderCursor == null) {
            LogUtils.e(LOG_TAG, "Null folder cursor for account %d, mailbox %d",
                    accountId, mailboxId);
            return null;
        }

        Folder folder = null;
        try {
            if (folderCursor.moveToFirst()) {
                folder = new Folder(folderCursor);
            } else {
                LogUtils.e(LOG_TAG, "Empty folder cursor for account %d, mailbox %d",
                        accountId, mailboxId);
                return null;
            }
        } finally {
            folderCursor.close();
        }

        return Utils.createViewFolderIntent(this, folder.folderUri.fullUri, account);
    }
}
