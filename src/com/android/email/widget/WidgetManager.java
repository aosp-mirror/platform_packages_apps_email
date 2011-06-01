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

import android.content.Context;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class that maintains references to all widgets.
 */
public class WidgetManager {
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
            remove(widgetId);
        }
    }

    public synchronized EmailWidget getOrCreateWidget(Context context, int widgetId) {
        EmailWidget widget = WidgetManager.getInstance().get(widgetId);
        if (widget == null) {
            if (Email.DEBUG) {
                Log.d(EmailWidget.TAG, "Creating EmailWidget for id #" + widgetId);
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

    private void remove(int widgetId) {
        mWidgets.remove(widgetId);
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        int n = 0;
        for (EmailWidget widget : mWidgets.values()) {
            writer.println("Widget #" + (++n));
            writer.println("    " + widget.toString());
        }
    }
}
