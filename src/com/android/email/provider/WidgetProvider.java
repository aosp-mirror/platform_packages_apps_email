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
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.activity.MessageCompose;
import com.android.email.activity.Welcome;
import com.android.email.data.ThrottlingCursorLoader;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.Uri.Builder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WidgetProvider extends AppWidgetProvider {
    private static final String TAG = "WidgetProvider";

    /**
     * When handling clicks in a widget ListView, a single PendingIntent template is provided to
     * RemoteViews, and the individual "on click" actions are distinguished via a "fillInIntent"
     * on each list element; when a click is received, this "fillInIntent" is merged with the
     * PendingIntent using Intent.fillIn().  Since this mechanism does NOT preserve the Extras
     * Bundle, we instead encode information about the action (e.g. view, reply, etc.) and its
     * arguments (e.g. messageId, mailboxId, etc.) in an Uri which is added to the Intent via
     * Intent.setDataAndType()
     *
     * The mime type MUST be set in the Intent, even though we do not use it; therefore, it's value
     * is entirely arbitrary.
     *
     * Our "command" Uri is NOT used by the system in any manner, and is therefore constrained only
     * in the requirement that it be syntactically valid.
     *
     * We use the following convention for our commands:
     *     widget://command/<command>/<arg1>[/<arg2>]
     */
    private static final String WIDGET_DATA_MIME_TYPE = "com.android.email/widget_data";
    private static final Uri COMMAND_URI = Uri.parse("widget://command");

    // Command names and Uri's built upon COMMAND_URI
    private static final String COMMAND_NAME_SWITCH_LIST_VIEW = "switch_list_view";
    private static final Uri COMMAND_URI_SWITCH_LIST_VIEW =
        COMMAND_URI.buildUpon().appendPath(COMMAND_NAME_SWITCH_LIST_VIEW).build();
    private static final String COMMAND_NAME_VIEW_MESSAGE = "view_message";
    private static final Uri COMMAND_URI_VIEW_MESSAGE =
        COMMAND_URI.buildUpon().appendPath(COMMAND_NAME_VIEW_MESSAGE).build();

    private static final int TOTAL_COUNT_UNKNOWN = -1;
    private static final int MAX_MESSAGE_LIST_COUNT = 25;

    private static final String[] NO_ARGUMENTS = new String[0];
    private static final String SORT_TIMESTAMP_DESCENDING = MessageColumns.TIMESTAMP + " DESC";
    private static final String SORT_ID_ASCENDING = AccountColumns.ID + " ASC";
    private static final String[] ID_NAME_PROJECTION = {Account.RECORD_ID, Account.DISPLAY_NAME};
    private static final int ID_NAME_COLUMN_ID = 0;
    private static final int ID_NAME_COLUMN_NAME = 1;


    // Map holding our instantiated widgets, accessed by widget id
    private final static Map<Integer, EmailWidget> sWidgetMap
            = new ConcurrentHashMap<Integer, EmailWidget>();
    private static AppWidgetManager sWidgetManager;
    private static Context sContext;
    private static ContentResolver sResolver;

    private static int sSenderFontSize;
    private static int sSubjectFontSize;
    private static int sDateFontSize;
    private static int sDefaultTextColor;
    private static int sLightTextColor;
    private static String sSubjectSnippetDivider;
    private static String sConfigureText;

    /**
     * Types of views that we're prepared to show in the widget - all mail, unread mail, and starred
     * mail; we rotate between them.  Each ViewType is composed of a selection string and a title.
     */
    public enum ViewType {
        ALL_MAIL(null, NO_ARGUMENTS, R.string.widget_all_mail),
        UNREAD(MessageColumns.FLAG_READ + "=0", NO_ARGUMENTS, R.string.widget_unread),
        STARRED(Message.ALL_FAVORITE_SELECTION, NO_ARGUMENTS, R.string.widget_starred),
        ACCOUNT(MessageColumns.ACCOUNT_KEY + "=?", new String[1], 0);

        private final String selection;
        private final String[] selectionArgs;
        private final int titleResource;
        private String title;

        ViewType(String _selection, String[] _selectionArgs, int _titleResource) {
            selection = _selection;
            selectionArgs = _selectionArgs;
            titleResource = _titleResource;
        }

        public String getTitle(Context context) {
            if (title == null && titleResource != 0) {
                title = context.getString(titleResource);
            }
            return title;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ViewType:selection=");
            sb.append("\"");
            sb.append(selection);
            sb.append("\"");
            if (selectionArgs != null && selectionArgs.length > 0) {
                sb.append(",args=(");
                for (String arg : selectionArgs) {
                    sb.append(arg);
                    sb.append(", ");
                }
                sb.append(")");
            }

            return sb.toString();
        }
    }

    static class EmailWidget implements RemoteViewsService.RemoteViewsFactory {
        // The widget identifier
        private final int mWidgetId;

        // The cursor underlying the message list for this widget; this must only be modified while
        // holding mCursorLock
        private volatile Cursor mCursor;
        // A lock on our cursor, which is used in the UI thread while inflating views, and by
        // our Loader in the background
        private final Object mCursorLock = new Object();
        // Number of records in the cursor
        private int mCursorCount = TOTAL_COUNT_UNKNOWN;
        // The widget's loader (derived from ThrottlingCursorLoader)
        private WidgetLoader mLoader;

        // The current view type (all mail, unread, or starred for now)
        /*package*/ ViewType mViewType = ViewType.STARRED;

        // The projection to be used by the WidgetLoader
        public static final String[] WIDGET_PROJECTION = new String[] {
            EmailContent.RECORD_ID, MessageColumns.DISPLAY_NAME, MessageColumns.TIMESTAMP,
            MessageColumns.SUBJECT, MessageColumns.FLAG_READ, MessageColumns.FLAG_FAVORITE,
            MessageColumns.FLAG_ATTACHMENT, MessageColumns.MAILBOX_KEY, MessageColumns.SNIPPET,
            MessageColumns.ACCOUNT_KEY
            };
        public static final int WIDGET_COLUMN_ID = 0;
        public static final int WIDGET_COLUMN_DISPLAY_NAME = 1;
        public static final int WIDGET_COLUMN_TIMESTAMP = 2;
        public static final int WIDGET_COLUMN_SUBJECT = 3;
        public static final int WIDGET_COLUMN_FLAG_READ = 4;
        public static final int WIDGET_COLUMN_FLAG_FAVORITE = 5;
        public static final int WIDGET_COLUMN_FLAG_ATTACHMENT = 6;
        public static final int WIDGET_COLUMN_MAILBOX_KEY = 7;
        public static final int WIDGET_COLUMN_SNIPPET = 8;
        public static final int WIDGET_COLUMN_ACCOUNT_KEY = 9;

        public EmailWidget(int _widgetId) {
            super();
            if (Email.DEBUG) {
                Log.d(TAG, "Creating EmailWidget with id = " + _widgetId);
            }
            mWidgetId = _widgetId;
            mLoader = new WidgetLoader();
            if (sSubjectSnippetDivider == null) {
                // Initialize string, color, dimension resources
                Resources res = sContext.getResources();
                sSubjectSnippetDivider =
                    res.getString(R.string.message_list_subject_snippet_divider);
                sSenderFontSize = res.getDimensionPixelSize(R.dimen.widget_senders_font_size);
                sSubjectFontSize = res.getDimensionPixelSize(R.dimen.widget_subject_font_size);
                sDateFontSize = res.getDimensionPixelSize(R.dimen.widget_date_font_size);
                sDefaultTextColor = res.getColor(R.color.widget_default_text_color);
                sDefaultTextColor = res.getColor(R.color.widget_default_text_color);
                sLightTextColor = res.getColor(R.color.widget_light_text_color);
                sConfigureText =  res.getString(R.string.widget_other_views);
            }
        }

        /**
         * The ThrottlingCursorLoader does all of the heavy lifting in managing the data loading
         * task; all we need is to register a listener so that we're notified when the load is
         * complete.
         */
        final class WidgetLoader extends ThrottlingCursorLoader {
            protected WidgetLoader() {
                super(sContext, Message.CONTENT_URI, WIDGET_PROJECTION, mViewType.selection,
                        mViewType.selectionArgs, SORT_TIMESTAMP_DESCENDING);
                registerListener(0, new OnLoadCompleteListener<Cursor>() {
                    @Override
                    public void onLoadComplete(Loader<Cursor> loader, Cursor cursor) {
                        synchronized (mCursorLock) {
                            // Save away the cursor
                            mCursor = cursor;
                            // Reset the notification Uri to our Message table notifier URI
                            mCursor.setNotificationUri(sResolver, Message.NOTIFIER_URI);
                            // Save away the count (for display)
                            mCursorCount = mCursor.getCount();
                            if (Email.DEBUG) {
                                Log.d(TAG, "onLoadComplete, count = " + cursor.getCount());
                            }
                        }
                        RemoteViews views =
                            new RemoteViews(sContext.getPackageName(), R.layout.widget);
                        setupTitleAndCount(views);
                        sWidgetManager.partiallyUpdateAppWidget(mWidgetId, views);
                        sWidgetManager.notifyAppWidgetViewDataChanged(mWidgetId, R.id.message_list);
                    }
                });
            }

            /**
             * Stop any pending load, reset selection parameters, and start loading
             * Must be called from the UI thread
             * @param viewType the current ViewType
             */
            private void load(ViewType viewType) {
                reset();
                setSelection(viewType.selection);
                setSelectionArgs(viewType.selectionArgs);
                startLoading();
            }
        }

        /**
         * Initialize to first appropriate view (depending on the number of accounts)
         */
        private void init() {
            new WidgetViewSwitcher(this).execute();
        }

        /**
         * Reset cursor and cursor count, notify widget that list data is invalid, and start loading
         * with our current ViewType
         */
        private void loadView() {
            synchronized(mCursorLock) {
                mCursorCount = TOTAL_COUNT_UNKNOWN;
                mCursor = null;
                sWidgetManager.notifyAppWidgetViewDataChanged(mWidgetId, R.id.message_list);
                mLoader.load(mViewType);
            }
        }

        /**
         * Switch to the next widget view (all -> account1 -> ... -> account n -> unread -> starred)
         * This must be called on a background thread
         */
        public synchronized void switchToNextView() {
            switch(mViewType) {
                // If we're in starred and there is more than one account, go to "all mail"
                // Otherwise, fall through to the accounts themselves
                case STARRED:
                    if (EmailContent.count(sContext, Account.CONTENT_URI) > 1) {
                        mViewType = ViewType.ALL_MAIL;
                        break;
                    }
                    //$FALL-THROUGH$
                case ALL_MAIL:
                    ViewType.ACCOUNT.selectionArgs[0] = "0";
                    //$FALL-THROUGH$
                case ACCOUNT:
                    // Find the next account (or, if none, default to UNREAD)
                    String idString = ViewType.ACCOUNT.selectionArgs[0];
                    Cursor c = sResolver.query(Account.CONTENT_URI, ID_NAME_PROJECTION, "_id>?",
                            new String[] {idString}, SORT_ID_ASCENDING);
                    try {
                        if (c.moveToFirst()) {
                            mViewType = ViewType.ACCOUNT;
                            mViewType.selectionArgs[0] = c.getString(ID_NAME_COLUMN_ID);
                            mViewType.title = c.getString(ID_NAME_COLUMN_NAME);
                        } else {
                            mViewType = ViewType.UNREAD;
                        }
                    } finally {
                        c.close();
                    }
                    break;
                case UNREAD:
                    mViewType = ViewType.STARRED;
                    break;
            }
        }

        /**
         * Convenience method for creating an onClickPendingIntent that executes a command via
         * our command Uri.  Used for the "next view" command; appends the widget id to the command
         * Uri.
         *
         * @param views The RemoteViews we're inflating
         * @param buttonId the id of the button view
         * @param data the command Uri
         */
        private void setCommandIntent(RemoteViews views, int buttonId, Uri data) {
            Intent intent = new Intent(sContext, WidgetService.class);
            intent.setDataAndType(ContentUris.withAppendedId(data, mWidgetId),
                    WIDGET_DATA_MIME_TYPE);
            PendingIntent pendingIntent = PendingIntent.getService(sContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            views.setOnClickPendingIntent(buttonId, pendingIntent);
        }

        /**
         * Convenience method for creating an onClickPendingIntent that launches another activity
         * directly.  Used for the "Compose" button
         *
         * @param views The RemoteViews we're inflating
         * @param buttonId the id of the button view
         * @param activityClass the class of the activity to be launched
         */
        private void setActivityIntent(RemoteViews views, int buttonId,
                Class<? extends Activity> activityClass) {
            Intent intent = new Intent(sContext, activityClass);
            PendingIntent pendingIntent = PendingIntent.getActivity(sContext, 0, intent, 0);
            views.setOnClickPendingIntent(buttonId, pendingIntent);
        }

        /**
         * Convenience method for constructing a fillInIntent for a given list view element.
         * Appends the command and any arguments to a base Uri.
         *
         * @param views the RemoteViews we are inflating
         * @param viewId the id of the view
         * @param baseUri the base uri for the command
         * @param args any arguments to the command
         */
        private void setFillInIntent(RemoteViews views, int viewId, Uri baseUri, String ... args) {
            Intent intent = new Intent();
            Builder builder = baseUri.buildUpon();
            for (String arg: args) {
                builder.appendPath(arg);
            }
            intent.setDataAndType(builder.build(), WIDGET_DATA_MIME_TYPE);
            views.setOnClickFillInIntent(viewId, intent);
        }

        private void setupTitleAndCount(RemoteViews views) {
            // Set up the title (view type + count of messages)
            views.setTextViewText(R.id.widget_title, mViewType.getTitle(sContext));
            views.setTextViewText(R.id.widget_tap, sConfigureText);
            String count = "";
            if (mCursorCount != TOTAL_COUNT_UNKNOWN) {
                count = Integer.toString(mCursor.getCount());
            }
            views.setTextViewText(R.id.widget_count, count);
        }
        /**
         * Update the "header" of the widget (i.e. everything that doesn't include the scrolling
         * message list)
         */
        private void updateHeader() {
            if (Email.DEBUG) {
                Log.d(TAG, "updateWidget " + mWidgetId);
            }

            // Get the widget layout
            RemoteViews views = new RemoteViews(sContext.getPackageName(), R.layout.widget);

            // Set up the list with an adapter
            Intent intent = new Intent(sContext, WidgetService.class);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mWidgetId);
            intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)));
            views.setRemoteAdapter(mWidgetId, R.id.message_list, intent);

            setupTitleAndCount(views);

             // Set up "new" button (compose new message) and "next view" button
            setActivityIntent(views, R.id.widget_compose, MessageCompose.class);
            setCommandIntent(views, R.id.widget_logo, COMMAND_URI_SWITCH_LIST_VIEW);

            // Use a bare intent for our template; we need to fill everything in
            intent = new Intent(sContext, WidgetService.class);
            PendingIntent pendingIntent =
                PendingIntent.getService(sContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
            views.setPendingIntentTemplate(R.id.message_list, pendingIntent);

            // And finally update the widget
            sWidgetManager.updateAppWidget(mWidgetId, views);
        }

        /**
         * Add size and color styling to text
         *
         * @param text the text to style
         * @param size the font size for this text
         * @param color the color for this text
         * @return a CharSequence quitable for use in RemoteViews.setTextViewText()
         */
        private CharSequence addStyle(CharSequence text, int size, int color) {
            SpannableStringBuilder builder = new SpannableStringBuilder(text);
            builder.setSpan(
                    new AbsoluteSizeSpan(size), 0, text.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (color != 0) {
                builder.setSpan(new ForegroundColorSpan(color), 0, text.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return builder;
        }

        /**
         * Create styled text for our combination subject and snippet
         *
         * @param subject the message's subject (or null)
         * @param snippet the message's snippet (or null)
         * @param read whether or not the message is read
         * @return a CharSequence suitable for use in RemoteViews.setTextViewText()
         */
        private CharSequence getStyledSubjectSnippet (String subject, String snippet,
                boolean read) {
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            boolean hasSubject = false;
            if (!TextUtils.isEmpty(subject)) {
                SpannableString ss = new SpannableString(subject);
                ss.setSpan(new StyleSpan(read ? Typeface.NORMAL : Typeface.BOLD), 0, ss.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new ForegroundColorSpan(sDefaultTextColor), 0, ss.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.append(ss);
                hasSubject = true;
            }
            if (!TextUtils.isEmpty(snippet)) {
                if (hasSubject) {
                    ssb.append(sSubjectSnippetDivider);
                }
                SpannableString ss = new SpannableString(snippet);
                ss.setSpan(new ForegroundColorSpan(sLightTextColor), 0, snippet.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.append(ss);
            }
            return addStyle(ssb, sSubjectFontSize, 0);
        }

        /* (non-Javadoc)
         * @see android.widget.RemoteViewsService.RemoteViewsFactory#getViewAt(int)
         */
        public RemoteViews getViewAt(int position) {
            // Use the cursor to set up the widget
            synchronized (mCursorLock) {
                if (mCursor == null || mCursor.isClosed() || !mCursor.moveToPosition(position)) {
                    return getLoadingView();
                }
                RemoteViews views =
                    new RemoteViews(sContext.getPackageName(), R.layout.widget_list_item);
                boolean isUnread = mCursor.getInt(WIDGET_COLUMN_FLAG_READ) != 1;

                // Add style to sender
                SpannableStringBuilder from =
                    new SpannableStringBuilder(mCursor.getString(WIDGET_COLUMN_DISPLAY_NAME));
                from.setSpan(
                        isUnread ? new StyleSpan(Typeface.BOLD) : new StyleSpan(Typeface.NORMAL), 0,
                        from.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                CharSequence styledFrom = addStyle(from, sSenderFontSize, sDefaultTextColor);
                views.setTextViewText(R.id.widget_from, styledFrom);

                long timestamp = mCursor.getLong(WIDGET_COLUMN_TIMESTAMP);
                // Get a nicely formatted date string (relative to today)
                String date = DateUtils.getRelativeTimeSpanString(sContext, timestamp).toString();
                // Add style to date
                CharSequence styledDate = addStyle(date, sDateFontSize, sDefaultTextColor);
                views.setTextViewText(R.id.widget_date, styledDate);

                // Add style to subject/snippet
                String subject = mCursor.getString(WIDGET_COLUMN_SUBJECT);
                String snippet = mCursor.getString(WIDGET_COLUMN_SNIPPET);
                CharSequence subjectAndSnippet =
                    getStyledSubjectSnippet(subject, snippet, !isUnread);
                views.setTextViewText(R.id.widget_subject, subjectAndSnippet);

                if (mCursor.getInt(WIDGET_COLUMN_FLAG_ATTACHMENT) != 0) {
                    views.setViewVisibility(R.id.widget_attachment, View.VISIBLE);
                } else {
                    views.setViewVisibility(R.id.widget_attachment, View.GONE);
                }

                // Set button intents for view, reply, and delete
                String messageId = mCursor.getString(WIDGET_COLUMN_ID);
                String mailboxId = mCursor.getString(WIDGET_COLUMN_MAILBOX_KEY);
                setFillInIntent(views, R.id.widget_message, COMMAND_URI_VIEW_MESSAGE, messageId,
                        mailboxId);

                return views;
            }
        }

        @Override
        public int getCount() {
            if (mCursor == null) return 0;
            return Math.min(mCursor.getCount(), MAX_MESSAGE_LIST_COUNT);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public RemoteViews getLoadingView() {
            RemoteViews view = new RemoteViews(sContext.getPackageName(), R.layout.widget_loading);
            view.setTextViewText(R.id.loading_text, sContext.getString(R.string.widget_loading));
            return view;
        }

        @Override
        public int getViewTypeCount() {
            // Regular list view and the "loading" view
            return 2;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public void onDataSetChanged() {
        }

        private void onDeleted() {
            if (mLoader != null) {
                mLoader.stopLoading();
            }
            sWidgetMap.remove(mWidgetId);
        }

        @Override
        public void onDestroy() {
            if (mLoader != null) {
                mLoader.stopLoading();
            }
            sWidgetMap.remove(mWidgetId);
        }

        @Override
        public void onCreate() {
        }
    }

    private static synchronized void update(Context context, int[] appWidgetIds) {
        for (int widgetId: appWidgetIds) {
            getOrCreateWidget(context, widgetId).updateHeader();
        }
    }

    /**
     * Force a context for widgets (used by unit tests)
     * @param context the Context to set
     */
    /*package*/ static void setContextForTest(Context context) {
        sContext = context;
        sResolver = context.getContentResolver();
        sWidgetManager = AppWidgetManager.getInstance(context);
    }

    /*package*/ static EmailWidget getOrCreateWidget(Context context, int widgetId) {
        // Lazily initialize these
        if (sContext == null) {
            sContext = context.getApplicationContext();
            sWidgetManager = AppWidgetManager.getInstance(context);
            sResolver = context.getContentResolver();
        }
        EmailWidget widget = sWidgetMap.get(widgetId);
        if (widget == null) {
            if (Email.DEBUG) {
                Log.d(TAG, "Creating EmailWidget for id #" + widgetId);
            }
            widget = new EmailWidget(widgetId);
            widget.init();
            sWidgetMap.put(widgetId, widget);
        }
        return widget;
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        if (Email.DEBUG) {
            Log.d(TAG, "onDisabled");
        }
        context.stopService(new Intent(context, WidgetService.class));
    }

    @Override
    public void onEnabled(final Context context) {
        super.onEnabled(context);
        if (Email.DEBUG) {
            Log.d(TAG, "onEnabled");
        }
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                final int[] appWidgetIds = extras.getIntArray(AppWidgetManager.EXTRA_APPWIDGET_IDS);
                if (appWidgetIds != null && appWidgetIds.length > 0) {
                    update(context, appWidgetIds);
                }
            }
        } else if (AppWidgetManager.ACTION_APPWIDGET_DELETED.equals(action)) {
            Bundle extras = intent.getExtras();
            if (extras != null && extras.containsKey(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
                final int widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID);
                // Find the widget in the map
                EmailWidget widget = sWidgetMap.get(widgetId);
                if (widget != null) {
                    // Stop loading and remove the widget from the map
                    widget.onDeleted();
                }
            }
        }
    }

    /**
     * Utility class to handle switching widget views; in the background, we access the database
     * to determine account status, etc.  In the foreground, we start up the Loader with new
     * parameters
     */
    /*package*/ static class WidgetViewSwitcher extends AsyncTask<Void, Void, Void> {
        private final EmailWidget mWidget;
        private boolean mLoadAfterSwitch = true;

        public WidgetViewSwitcher(EmailWidget widget) {
            mWidget = widget;
        }

        /*package*/ void disableLoadAfterSwitchForTest() {
            mLoadAfterSwitch = false;
        }

        @Override
        protected Void doInBackground(Void... params) {
            mWidget.switchToNextView();
            return null;
        }

        @Override
        protected void onPostExecute(Void param) {
            if (isCancelled()) {
                return;
            }
            if (mLoadAfterSwitch) {
                mWidget.loadView();
            }
        }
    }

    /**
     * We use the WidgetService for two purposes:
     *  1) To provide a widget factory for RemoteViews, and
     *  2) To process our command Uri's (i.e. take actions on user clicks)
     */
    public static class WidgetService extends RemoteViewsService {
        @Override
        public RemoteViewsFactory onGetViewFactory(Intent intent) {
            // Which widget do we want (nice alliteration, huh?)
            int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            if (widgetId == -1) return null;
            // Find the existing widget or create it
            return getOrCreateWidget(this, widgetId);
        }

        @Override
        public void startActivity(Intent intent) {
            // Since we're not calling startActivity from an Activity, we need the new task flag
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            super.startActivity(intent);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Uri data = intent.getData();
            if (data == null) return Service.START_NOT_STICKY;
            List<String> pathSegments = data.getPathSegments();
            // Our path segments are <command>, <arg1> [, <arg2>]
            // First, a quick check of Uri validity
            if (pathSegments.size() < 2) {
                throw new IllegalArgumentException();
            }
            String command = pathSegments.get(0);
            // Ignore unknown action names
            try {
                final long arg1 = Long.parseLong(pathSegments.get(1));
                if (COMMAND_NAME_VIEW_MESSAGE.equals(command)) {
                    // "view", <message id>, <mailbox id>
                    final long mailboxId = Long.parseLong(pathSegments.get(2));
                    final long messageId = arg1;
                    Utility.runAsync(new Runnable() {
                        @Override
                        public void run() {
                            openMessage(mailboxId, messageId);
                        }
                    });
                } else if (COMMAND_NAME_SWITCH_LIST_VIEW.equals(command)) {
                    // "next_view", <widget id>
                    EmailWidget widget = sWidgetMap.get((int)arg1);
                    if (widget != null) {
                        WidgetViewSwitcher switcher = new WidgetViewSwitcher(widget);
                        switcher.execute();
                    }
                }
            } catch (NumberFormatException e) {
                // Shouldn't happen as we construct all of the Uri's
            }
            return Service.START_NOT_STICKY;
        }

        private void openMessage(long mailboxId, long messageId) {
            Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mailboxId);
            if (mailbox == null) return;
            startActivity(Welcome.createOpenMessageIntent(this, mailbox.mAccountKey, mailboxId,
                    messageId));
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
            int n = 0;
            for (EmailWidget widget : sWidgetMap.values()) {
                writer.println("Widget #" + (++n));
                writer.println("    ViewType=" + widget.mViewType);
            }
        }
    }
}
