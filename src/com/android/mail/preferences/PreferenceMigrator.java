/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.mail.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;

import com.android.email.Preferences;
import com.android.emailcommon.provider.EmailContent;
import com.android.mail.providers.Account;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Migrates Email settings to UnifiedEmail
 */
public class PreferenceMigrator extends BasePreferenceMigrator {
    private static final String LOG_TAG = LogTag.getLogTag();

    @Override
    protected void migrate(final Context context, final int oldVersion, final int newVersion) {
        final List<Account> accounts = new ArrayList<Account>();

        final Cursor accountCursor = context.getContentResolver().query(Uri.parse(
                EmailContent.CONTENT_URI + "/uiaccts"),
                UIProvider.ACCOUNTS_PROJECTION_NO_CAPABILITIES, null, null, null);
        try {
            while (accountCursor.moveToNext()) {
                accounts.add(new Account(accountCursor));
            }
        } finally {
            accountCursor.close();
        }

        migrate(context, oldVersion, newVersion, accounts);
    }

    public static final String REPLY_ALL = "reply_all";

    private static final String PREFERENCE_NOTIFY = "account_notify";
    private static final String PREFERENCE_VIBRATE = "account_settings_vibrate";
    private static final String PREFERENCE_VIBRATE_OLD = "account_settings_vibrate_when";
    private static final String PREFERENCE_RINGTONE = "account_ringtone";

    protected static void migrate(final Context context, final int oldVersion, final int newVersion,
            final List<Account> accounts) {
        if (oldVersion < 1) {
            // Move global settings
            final Preferences preferences = Preferences.getPreferences(context);
            final MailPrefs mailPrefs = MailPrefs.get(context);

            @SuppressWarnings("deprecation")
            final boolean hasSwipeDelete = preferences.hasSwipeDelete();
            if (hasSwipeDelete) {
                @SuppressWarnings("deprecation")
                final boolean swipeDelete = preferences.getSwipeDelete();
                mailPrefs.setConversationListSwipeEnabled(swipeDelete);
            }

            // Move folder notification settings
            for (final Account account : accounts) {
                // The only setting in AccountPreferences so far is a global notification toggle,
                // but we only allow Inbox notifications, so it will remain unused
                final Cursor folderCursor =
                        context.getContentResolver().query(account.settings.defaultInbox,
                                UIProvider.FOLDERS_PROJECTION, null, null, null);

                if (folderCursor == null) {
                    LogUtils.e(LOG_TAG, "Null folder cursor for mailbox %s",
                            account.settings.defaultInbox);
                    continue;
                }

                Folder folder = null;
                try {
                    if (folderCursor.moveToFirst()) {
                        folder = new Folder(folderCursor);
                    }
                } finally {
                    folderCursor.close();
                }

                final FolderPreferences folderPreferences =
                        new FolderPreferences(context, account.name, folder, true /* inbox */);

                final SharedPreferences sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(context);

                if (sharedPreferences.contains(PREFERENCE_NOTIFY)) {
                    final boolean notify = sharedPreferences.getBoolean(PREFERENCE_NOTIFY, true);
                    folderPreferences.setNotificationsEnabled(notify);
                }

                if (sharedPreferences.contains(PREFERENCE_RINGTONE)) {
                    final String ringtoneUri =
                            sharedPreferences.getString(PREFERENCE_RINGTONE, null);
                    folderPreferences.setNotificationRingtoneUri(ringtoneUri);
                }

                if (sharedPreferences.contains(PREFERENCE_VIBRATE)) {
                    final boolean vibrate = sharedPreferences.getBoolean(PREFERENCE_VIBRATE, false);
                    folderPreferences.setNotificationVibrateEnabled(vibrate);
                } else if (sharedPreferences.contains(PREFERENCE_VIBRATE_OLD)) {
                    final boolean vibrate = "always".equals(
                            sharedPreferences.getString(PREFERENCE_VIBRATE_OLD, ""));
                    folderPreferences.setNotificationVibrateEnabled(vibrate);
                }
            }
        }
    }
}
