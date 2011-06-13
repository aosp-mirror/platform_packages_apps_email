/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.email.widget;

import com.android.email.Email;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class that maintains references to all widgets.
 */
public class WidgetManager {
    private static final String PREFS_NAME = "com.android.email.widget.WidgetManager";
    private static final String ACCOUNT_ID_PREFIX = "accountId_";
    private static final String MAILBOX_ID_PREFIX = "mailboxId_";

    private final static WidgetManager sInstance = new WidgetManager();

    // Widget ID -> Widget
    private final static Map<Integer, EmailWidget> mWidgets =
            new ConcurrentHashMap<Integer, EmailWidget>();

    private WidgetManager() {
    }

    public static WidgetManager getInstance() {
        return sInstance;
    }

    public synchronized void createWidgets(Context context, int[] widgetIds) {
        for (int widgetId : widgetIds) {
            getOrCreateWidget(context, widgetId);
        }
    }

    public synchronized void deleteWidgets(Context context, int[] widgetIds) {
        for (int widgetId : widgetIds) {
            // Find the widget in the map
            final EmailWidget widget = WidgetManager.getInstance().get(widgetId);
            if (widget != null) {
                // Stop loading and remove the widget from the map
                widget.onDeleted();
            }
            remove(context, widgetId);
        }
    }

    public synchronized EmailWidget getOrCreateWidget(Context context, int widgetId) {
        EmailWidget widget = WidgetManager.getInstance().get(widgetId);
        if (widget == null) {
            if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(EmailWidget.TAG, "Create email widget; ID: " + widgetId);
            }
            widget = new EmailWidget(context, widgetId);
            WidgetManager.getInstance().put(widgetId, widget);
            widget.start();
        }
        return widget;
    }

    private EmailWidget get(int widgetId) {
        return mWidgets.get(widgetId);
    }

    private void put(int widgetId, EmailWidget widget) {
        mWidgets.put(widgetId, widget);
    }

    private void remove(Context context, int widgetId) {
        mWidgets.remove(widgetId);
        WidgetManager.removeWidgetPrefs(context, widgetId);
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        int n = 0;
        for (EmailWidget widget : mWidgets.values()) {
            writer.println("Widget #" + (++n));
            writer.println("    " + widget.toString());
        }
    }

    /** Saves shared preferences for the given widget */
    static void saveWidgetPrefs(Context context, int appWidgetId, long accountId, long mailboxId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        prefs.edit()
            .putLong(ACCOUNT_ID_PREFIX + appWidgetId, accountId)
            .putLong(MAILBOX_ID_PREFIX + appWidgetId, mailboxId)
            .commit();    // preferences must be committed before we return
    }

    /** Removes shared preferences for the given widget */
    static void removeWidgetPrefs(Context context, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.endsWith("_" + appWidgetId)) {
                editor.remove(key);
            }
        }
        editor.apply();   // just want to clean up; don't care when preferences are actually removed
    }

    /**
     * Returns the saved account ID for the given widget. Otherwise, {@link Account#NO_ACCOUNT} if
     * the account ID was not previously saved.
     */
    static long loadAccountIdPref(Context context, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        long accountId = prefs.getLong(ACCOUNT_ID_PREFIX + appWidgetId, Account.NO_ACCOUNT);
        return accountId;
    }

    /**
     * Returns the saved mailbox ID for the given widget. Otherwise, {@link Mailbox#NO_MAILBOX} if
     * the mailbox ID was not previously saved.
     */
    static long loadMailboxIdPref(Context context, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        long mailboxId = prefs.getLong(MAILBOX_ID_PREFIX + appWidgetId, Mailbox.NO_MAILBOX);
        return mailboxId;
    }
}
