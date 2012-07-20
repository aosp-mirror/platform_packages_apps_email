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

import android.appwidget.AppWidgetManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.provider.BaseColumns;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.AccountColumns;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.widget.BaseWidgetProvider;
import com.android.mail.widget.WidgetService;

public class WidgetProvider extends BaseWidgetProvider {
    private static final String LEGACY_PREFS_NAME = "com.android.email.widget.WidgetManager";
    private static final String LEGACY_ACCOUNT_ID_PREFIX = "accountId_";
    private static final String LEGACY_MAILBOX_ID_PREFIX = "mailboxId_";

    private static final String LOG_TAG = LogTag.getLogTag();

    // This projection is needed, as if we were to request the capabilities of the account,
    // that provider attempts to bind to the email service to get this information.  It is not
    // valid to bind to a service in a broadcast receiver, as the bind just blocks, for the amount
    // of time specified in the timeout.
    // Instead, this projection doesn't include the capabilities column.  The cursor wrapper then
    // makes sure that the Account objects can find all of the columns it expects.
    private static final String[] WIDGET_ACCOUNTS_PROJECTION = {
        BaseColumns._ID,
        AccountColumns.NAME,
        AccountColumns.PROVIDER_VERSION,
        AccountColumns.URI,
        AccountColumns.FOLDER_LIST_URI,
        AccountColumns.FULL_FOLDER_LIST_URI,
        AccountColumns.SEARCH_URI,
        AccountColumns.ACCOUNT_FROM_ADDRESSES,
        AccountColumns.SAVE_DRAFT_URI,
        AccountColumns.SEND_MAIL_URI,
        AccountColumns.EXPUNGE_MESSAGE_URI,
        AccountColumns.UNDO_URI,
        AccountColumns.SETTINGS_INTENT_URI,
        AccountColumns.SYNC_STATUS,
        AccountColumns.HELP_INTENT_URI,
        AccountColumns.SEND_FEEDBACK_INTENT_URI,
        AccountColumns.COMPOSE_URI,
        AccountColumns.MIME_TYPE,
        AccountColumns.RECENT_FOLDER_LIST_URI,
        AccountColumns.COLOR,
        AccountColumns.DEFAULT_RECENT_FOLDER_LIST_URI,
        AccountColumns.MANUAL_SYNC_URI,
        AccountColumns.SettingsColumns.SIGNATURE,
        AccountColumns.SettingsColumns.AUTO_ADVANCE,
        AccountColumns.SettingsColumns.MESSAGE_TEXT_SIZE,
        AccountColumns.SettingsColumns.SNAP_HEADERS,
        AccountColumns.SettingsColumns.REPLY_BEHAVIOR,
        AccountColumns.SettingsColumns.HIDE_CHECKBOXES,
        AccountColumns.SettingsColumns.CONFIRM_DELETE,
        AccountColumns.SettingsColumns.CONFIRM_ARCHIVE,
        AccountColumns.SettingsColumns.CONFIRM_SEND,
        AccountColumns.SettingsColumns.DEFAULT_INBOX,
        AccountColumns.SettingsColumns.DEFAULT_INBOX_NAME,
        AccountColumns.SettingsColumns.FORCE_REPLY_FROM_DEFAULT,
        AccountColumns.SettingsColumns.MAX_ATTACHMENT_SIZE
    };


    @Override
    public void onReceive(Context context, Intent intent) {
        // We want to migrate any legacy Email widget information to the new format
        migrateAllLegacyWidgetInformation(context);

        super.onReceive(context, intent);
    }

    /**
     * Update all widgets in the list
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        migrateLegacyWidgetInformation(context, appWidgetIds);
        super.onUpdate(context, appWidgetManager, appWidgetIds);
    }

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
        final Cursor sparseAccountCursor = resolver.query(Uri.parse(accountUri),
                WIDGET_ACCOUNTS_PROJECTION, null, null, null);

        return getPopulatedAccountObject(sparseAccountCursor);
    }


    @Override
    protected boolean isAccountValid(Context context, com.android.mail.providers.Account account) {
        if (account != null) {
            final ContentResolver resolver = context.getContentResolver();
            final Cursor sparseAccountCursor = resolver.query(account.uri,
                    WIDGET_ACCOUNTS_PROJECTION, null, null, null);
            if (sparseAccountCursor != null) {
                try {
                    return sparseAccountCursor.getCount() > 0;
                } finally {
                    sparseAccountCursor.close();
                }
            }
        }
        return false;
    }



    private void migrateAllLegacyWidgetInformation(Context context) {
        final int[] currentWidgetIds = getCurrentWidgetIds(context);
        migrateLegacyWidgetInformation(context, currentWidgetIds);
    }

    private void migrateLegacyWidgetInformation(Context context, int[] widgetIds) {
        final SharedPreferences prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, 0);
        final SharedPreferences.Editor editor = prefs.edit();

        for (int widgetId : widgetIds) {
            long accountId = loadAccountIdPref(context, widgetId);
            long mailboxId = loadMailboxIdPref(context, widgetId);
            // Legacy support; if preferences haven't been saved for this widget, load something
            if (accountId == Account.NO_ACCOUNT || mailboxId == Mailbox.NO_MAILBOX) {
                LogUtils.d(LOG_TAG, "Couldn't load account or mailbox.  accountId: %d" +
                        " mailboxId: %d widgetId %d", accountId, mailboxId);
                continue;
            }

            // Get Account and folder objects for the account id and mailbox id
            final com.android.mail.providers.Account uiAccount = getAccount(context, accountId);
            final Folder uiFolder = getFolder(context, mailboxId);

            if (uiAccount != null && uiFolder != null) {
                WidgetService.saveWidgetInformation(context, widgetId, uiAccount, uiFolder);

                updateWidgetInternal(context, widgetId, uiAccount, uiFolder);

                // Now remove the old legacy preference value
                editor.remove(LEGACY_ACCOUNT_ID_PREFIX + widgetId);
                editor.remove(LEGACY_MAILBOX_ID_PREFIX + widgetId);
            }
        }
        editor.apply();
    }

    private com.android.mail.providers.Account getAccount(Context context, long accountId) {
        final ContentResolver resolver = context.getContentResolver();
        final Cursor ac = resolver.query(EmailProvider.uiUri("uiaccount", accountId),
                WIDGET_ACCOUNTS_PROJECTION, null, null, null);

        com.android.mail.providers.Account uiAccount = getPopulatedAccountObject(ac);

        return uiAccount;
    }

    private com.android.mail.providers.Account getPopulatedAccountObject(final Cursor ac) {
        if (ac == null) {
            LogUtils.e(LOG_TAG, "Null account cursor");
            return null;
        }

        final Cursor accountCursor = new SparseAccountCursorWrapper(ac);

        com.android.mail.providers.Account uiAccount = null;
        try {
            if (accountCursor.moveToFirst()) {
                 uiAccount = new com.android.mail.providers.Account(accountCursor);
            }
        } finally {
            accountCursor.close();
        }
        return uiAccount;
    }

    private Folder getFolder(Context context, long mailboxId) {
        final ContentResolver resolver = context.getContentResolver();
        final Cursor fc = resolver.query(EmailProvider.uiUri("uifolder", mailboxId),
                UIProvider.FOLDERS_PROJECTION, null, null, null);

        if (fc == null) {
            LogUtils.e(LOG_TAG, "Null folder cursor for mailboxId %d", mailboxId);
            return null;
        }

        Folder uiFolder = null;
        try {
            if (fc.moveToFirst()) {
                 uiFolder = new Folder(fc);
            }
        } finally {
            fc.close();
        }
        return uiFolder;
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

    private class SparseAccountCursorWrapper extends CursorWrapper {
        public SparseAccountCursorWrapper(Cursor cursor) {
            super(cursor);
        }

        @Override
        public int getColumnCount () {
            return UIProvider.ACCOUNTS_PROJECTION.length;
        }

        @Override
        public int getColumnIndex (String columnName) {
            for (int i = 0; i < UIProvider.ACCOUNTS_PROJECTION.length; i++) {
                if (UIProvider.ACCOUNTS_PROJECTION[i].equals(columnName)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public String getColumnName (int columnIndex) {
            return UIProvider.ACCOUNTS_PROJECTION[columnIndex];
        }

        @Override
        public String[] getColumnNames () {
            return UIProvider.ACCOUNTS_PROJECTION;
        }

        @Override
        public int getInt (int columnIndex) {
            if (columnIndex == UIProvider.ACCOUNT_CAPABILITIES_COLUMN) {
                return 0;
            }
            return super.getInt(convertColumnIndex(columnIndex));
        }

        @Override
        public long getLong (int columnIndex) {
            return super.getLong(convertColumnIndex(columnIndex));
        }

        @Override
        public String getString (int columnIndex) {
            return super.getString(convertColumnIndex(columnIndex));
        }

        private int convertColumnIndex(int columnIndex) {
            // Since this sparse cursor doesn't have the capabilities column,
            // we need to adjust all of the column indexes that come after where the
            // capabilities column should be
            if (columnIndex > UIProvider.ACCOUNT_CAPABILITIES_COLUMN) {
                return columnIndex - 1;
            }
            return columnIndex;
        }
    }

}
