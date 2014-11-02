/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.email.provider;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.widget.BaseWidgetProvider;
import com.android.mail.widget.WidgetService;

public class WidgetProvider extends BaseWidgetProvider {
    private static final String LEGACY_PREFS_NAME = "com.android.email.widget.WidgetManager";
    private static final String LEGACY_ACCOUNT_ID_PREFIX = "accountId_";
    private static final String LEGACY_MAILBOX_ID_PREFIX = "mailboxId_";

    private static final String LOG_TAG = LogTag.getLogTag();

    /**
     * Remove preferences when deleting widget
     */
    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);

        // Remove any legacy Email widget information
        final SharedPreferences prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, 0);
        final SharedPreferences.Editor editor = prefs.edit();
        for (int widgetId : appWidgetIds) {
            // Remove the account in the preference
            editor.remove(LEGACY_ACCOUNT_ID_PREFIX + widgetId);
            editor.remove(LEGACY_MAILBOX_ID_PREFIX + widgetId);
        }
        editor.apply();
    }

    @Override
    protected com.android.mail.providers.Account getAccountObject(
            Context context, String accountUri) {
        final ContentResolver resolver = context.getContentResolver();
        final Cursor accountCursor = resolver.query(Uri.parse(accountUri),
                UIProvider.ACCOUNTS_PROJECTION_NO_CAPABILITIES, null, null, null);

        return getPopulatedAccountObject(accountCursor);
    }


    @Override
    protected boolean isAccountValid(Context context, com.android.mail.providers.Account account) {
        if (account != null) {
            final ContentResolver resolver = context.getContentResolver();
            final Cursor accountCursor = resolver.query(account.uri,
                    UIProvider.ACCOUNTS_PROJECTION_NO_CAPABILITIES, null, null, null);
            if (accountCursor != null) {
                try {
                    return accountCursor.getCount() > 0;
                } finally {
                    accountCursor.close();
                }
            }
        }
        return false;
    }

    @Override
    protected void migrateLegacyWidgetInformation(Context context, int widgetId) {
        final SharedPreferences prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, 0);
        final SharedPreferences.Editor editor = prefs.edit();

        long accountId = loadAccountIdPref(context, widgetId);
        long mailboxId = loadMailboxIdPref(context, widgetId);
        // Legacy support; if preferences haven't been saved for this widget, load something
        if (accountId == Account.NO_ACCOUNT || mailboxId == Mailbox.NO_MAILBOX) {
            LogUtils.d(LOG_TAG, "Couldn't load account or mailbox.  accountId: %d" +
                    " mailboxId: %d widgetId %d", accountId, mailboxId, widgetId);
            return;
        }

        accountId = migrateLegacyWidgetAccountId(accountId);
        mailboxId = migrateLegacyWidgetMailboxId(mailboxId, accountId);

        // Get Account and folder objects for the account id and mailbox id
        final com.android.mail.providers.Account uiAccount = getAccount(context, accountId);
        final Folder uiFolder = EmailProvider.getFolder(context, mailboxId);

        if (uiAccount != null && uiFolder != null) {
            WidgetService.saveWidgetInformation(context, widgetId, uiAccount,
                    uiFolder.folderUri.fullUri.toString());

            updateWidgetInternal(context, widgetId, uiAccount, uiFolder.type, uiFolder.capabilities,
                    uiFolder.folderUri.fullUri, uiFolder.conversationListUri, uiFolder.name);

            // Now remove the old legacy preference value
            editor.remove(LEGACY_ACCOUNT_ID_PREFIX + widgetId);
            editor.remove(LEGACY_MAILBOX_ID_PREFIX + widgetId);
        }
        editor.apply();
    }

    private static long migrateLegacyWidgetAccountId(long accountId) {
        if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
            return EmailProvider.COMBINED_ACCOUNT_ID;
        }
        return accountId;
    }

    /**
     * @param accountId The migrated accountId
     * @return
     */
    private static long migrateLegacyWidgetMailboxId(long mailboxId, long accountId) {
        if (mailboxId == Mailbox.QUERY_ALL_INBOXES) {
            return EmailProvider.getVirtualMailboxId(accountId, Mailbox.TYPE_INBOX);
        } else if (mailboxId == Mailbox.QUERY_ALL_UNREAD) {
            return EmailProvider.getVirtualMailboxId(accountId, Mailbox.TYPE_UNREAD);
        }
        return mailboxId;
    }

    private static com.android.mail.providers.Account getAccount(Context context, long accountId) {
        final ContentResolver resolver = context.getContentResolver();
        final Cursor ac = resolver.query(EmailProvider.uiUri("uiaccount", accountId),
                UIProvider.ACCOUNTS_PROJECTION_NO_CAPABILITIES, null, null, null);

        com.android.mail.providers.Account uiAccount = getPopulatedAccountObject(ac);

        return uiAccount;
    }

    private static com.android.mail.providers.Account getPopulatedAccountObject(
            final Cursor accountCursor) {
        if (accountCursor == null) {
            LogUtils.e(LOG_TAG, "Null account cursor");
            return null;
        }

        com.android.mail.providers.Account uiAccount = null;
        try {
            if (accountCursor.moveToFirst()) {
                 uiAccount = com.android.mail.providers.Account.builder().buildFrom(accountCursor);
            }
        } finally {
            accountCursor.close();
        }
        return uiAccount;
    }

    /**
     * Returns the saved account ID for the given widget. Otherwise,
     * {@link com.android.emailcommon.provider.Account#NO_ACCOUNT} if
     * the account ID was not previously saved.
     */
    static long loadAccountIdPref(Context context, int appWidgetId) {
        final SharedPreferences prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, 0);
        long accountId = prefs.getLong(LEGACY_ACCOUNT_ID_PREFIX + appWidgetId, Account.NO_ACCOUNT);
        return accountId;
    }

    /**
     * Returns the saved mailbox ID for the given widget. Otherwise,
     * {@link com.android.emailcommon.provider.Mailbox#NO_MAILBOX} if
     * the mailbox ID was not previously saved.
     */
    static long loadMailboxIdPref(Context context, int appWidgetId) {
        final SharedPreferences prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, 0);
        long mailboxId = prefs.getLong(LEGACY_MAILBOX_ID_PREFIX + appWidgetId, Mailbox.NO_MAILBOX);
        return mailboxId;
    }
}
