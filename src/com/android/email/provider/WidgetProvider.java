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

package com.android.email.provider;

import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViewsService;

import com.android.email.Email;
import com.android.email.R;
import com.android.email.widget.EmailWidget;
import com.android.email.widget.WidgetManager;
import com.android.emailcommon.Logging;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class WidgetProvider extends AppWidgetProvider {
    @Override
    public void onEnabled(final Context context) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(EmailWidget.TAG, "onEnabled");
        }
        super.onEnabled(context);
    }

    @Override
    public void onDisabled(Context context) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(EmailWidget.TAG, "onDisabled");
        }
        context.stopService(new Intent(context, WidgetService.class));
        super.onDisabled(context);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(EmailWidget.TAG, "onUpdate");
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds);
        WidgetManager.getInstance().updateWidgets(context, appWidgetIds);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(EmailWidget.TAG, "onDeleted");
        }
        WidgetManager.getInstance().deleteWidgets(context, appWidgetIds);
        super.onDeleted(context, appWidgetIds);
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(EmailWidget.TAG, "onReceive");
        }
        super.onReceive(context, intent);

        if (EmailProvider.ACTION_NOTIFY_MESSAGE_LIST_DATASET_CHANGED.equals(intent.getAction())) {
            // Retrieve the list of current widgets.
            final AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            final ComponentName component = new ComponentName(context, WidgetProvider.class);
            final int[] widgetIds = appWidgetManager.getAppWidgetIds(component);

            // Ideally, this would only call notify AppWidgetViewDataChanged for the widgets, where
            // the account had the change, but the current intent doesn't include this information.

            // Calling notifyAppWidgetViewDataChanged will cause onDataSetChanged() to be called
            // on the RemoteViewsService.RemoteViewsFactory, starting the service if necessary.
            appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.message_list);
        }
    }

    /**
     * We use the WidgetService for two purposes:
     *  1) To provide a widget factory for RemoteViews, and
     *  2) Catch our command Uri's (i.e. take actions on user clicks) and let EmailWidget
     *     handle them.
     */
    public static class WidgetService extends RemoteViewsService {
        @Override
        public RemoteViewsFactory onGetViewFactory(Intent intent) {
            // Which widget do we want (nice alliteration, huh?)
            int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            if (widgetId == -1) return null;
            // Find the existing widget or create it
            return WidgetManager.getInstance().getOrCreateWidget(this, widgetId);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            return Service.START_NOT_STICKY;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
            WidgetManager.getInstance().dump(fd, writer, args);
        }
    }
 }
