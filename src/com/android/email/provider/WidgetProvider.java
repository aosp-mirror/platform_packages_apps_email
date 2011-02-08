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

import com.android.email.Email;
import com.android.email.widget.EmailWidget;
import com.android.email.widget.WidgetManager;

import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViewsService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class WidgetProvider extends AppWidgetProvider {
    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        if (Email.DEBUG) {
            Log.d(EmailWidget.TAG, "onDisabled");
        }
        context.stopService(new Intent(context, WidgetService.class));
    }

    @Override
    public void onEnabled(final Context context) {
        super.onEnabled(context);
        if (Email.DEBUG) {
            Log.d(EmailWidget.TAG, "onEnabled");
        }
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();
        Bundle extras = intent.getExtras();
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action) && extras != null) {
            final int[] appWidgetIds = extras.getIntArray(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if (appWidgetIds != null && appWidgetIds.length > 0) {
                WidgetManager.getInstance().getOrCreateWidgets(context, appWidgetIds);
            }
        } else if (AppWidgetManager.ACTION_APPWIDGET_DELETED.equals(action) && extras != null
                && extras.containsKey(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
            final int widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID);
            // Find the widget in the map
            EmailWidget widget = WidgetManager.getInstance().get(widgetId);
            if (widget != null) {
                // Stop loading and remove the widget from the map
                widget.onDeleted();
            }
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
            if (intent.getData() != null) {
                // EmailWidget creates intents, so it knows how to handle them.
                EmailWidget.processIntent(this, intent);
            }
            return Service.START_NOT_STICKY;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
            WidgetManager.getInstance().dump(fd, writer, args);
        }
    }
 }
