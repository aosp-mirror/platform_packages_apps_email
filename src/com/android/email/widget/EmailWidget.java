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
import com.android.email.R;
import com.android.email.ResourceHelper;
import com.android.email.UiUtilities;
import com.android.email.activity.MessageCompose;
import com.android.email.activity.Welcome;
import com.android.email.provider.WidgetProvider.WidgetService;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.utility.Utility;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.Loader.OnLoadCompleteListener;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.Uri.Builder;
import android.os.AsyncTask;
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

import java.util.List;

/**
 * The email widget.
 *
 * Threading notes:
 * - All methods must be called on the UI thread, except for {@link WidgetUpdater#doInBackground}.
 * - {@link WidgetUpdater#doInBackground} must not read/write any members of {@link EmailWidget}.
 * - (So no synchronizations are required in this class)
 */
public class EmailWidget implements RemoteViewsService.RemoteViewsFactory,
        OnLoadCompleteListener<Cursor> {
    public static final String TAG = "EmailWidget";

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

    private static final int MAX_MESSAGE_LIST_COUNT = 25;

    private static String sSubjectSnippetDivider;
    private static String sConfigureText;
    private static int sSenderFontSize;
    private static int sSubjectFontSize;
    private static int sDateFontSize;
    private static int sDefaultTextColor;
    private static int sLightTextColor;

    private final Context mContext;
    private final AppWidgetManager mWidgetManager;

    // The widget identifier
    private final int mWidgetId;

    // The widget's loader (derived from ThrottlingCursorLoader)
    private final EmailWidgetLoader mLoader;
    private final ResourceHelper mResourceHelper;

    /**
     * The cursor for the messages, with some extra info such as the number of accounts.
     *
     * Note this cursor can be closed any time by the loader.  Always use {@link #isCursorValid()}
     * before touching its contents.
     */
    private EmailWidgetLoader.CursorWithCounts mCursor;

    /** The current view type */
    /* package */ WidgetView mWidgetView = WidgetView.UNINITIALIZED_VIEW;

    public EmailWidget(Context context, int _widgetId) {
        super();
        if (Email.DEBUG) {
            Log.d(TAG, "Creating EmailWidget with id = " + _widgetId);
        }
        mContext = context.getApplicationContext();
        mWidgetManager = AppWidgetManager.getInstance(mContext);

        mWidgetId = _widgetId;
        mLoader = new EmailWidgetLoader(mContext);
        mLoader.registerListener(0, this);
        if (sSubjectSnippetDivider == null) {
            // Initialize string, color, dimension resources
            Resources res = mContext.getResources();
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
        mResourceHelper = ResourceHelper.getInstance(mContext);
    }

    public void start() {
        // The default view is UNINITIALIZED_VIEW, and we switch to the next one, which should
        // be the initial view.  (the first view shown to the user.)
        switchView();
    }

    private boolean isCursorValid() {
        return mCursor != null && !mCursor.isClosed();
    }

    /**
     * Called when the loader finished loading data.  Update the widget.
     */
    @Override
    public void onLoadComplete(Loader<Cursor> loader, Cursor cursor) {
        // Save away the cursor
        mCursor = (EmailWidgetLoader.CursorWithCounts) cursor;
        mWidgetView = mLoader.getLoadingWidgetView();

        RemoteViews views = new RemoteViews(mContext.getPackageName(), R.layout.widget);
        updateHeader();
        setupTitleAndCount(views);
        mWidgetManager.partiallyUpdateAppWidget(mWidgetId, views);
        mWidgetManager.notifyAppWidgetViewDataChanged(mWidgetId, R.id.message_list);
    }

    /**
     * Start loading the data.  At this point nothing on the widget changes -- the current view
     * will remain valid until the loader loads the latest data.
     */
    private void loadView(WidgetView view) {
        mLoader.load(view);
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
        Intent intent = new Intent(mContext, WidgetService.class);
        intent.setDataAndType(ContentUris.withAppendedId(data, mWidgetId), WIDGET_DATA_MIME_TYPE);
        PendingIntent pendingIntent = PendingIntent.getService(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(buttonId, pendingIntent);
    }

    /**
     * Convenience method for creating an onClickPendingIntent that launches another activity
     * directly.
     *
     * @param views The RemoteViews we're inflating
     * @param buttonId the id of the button view
     * @param intent The intent to be used when launching the activity
     */
    private void setActivityIntent(RemoteViews views, int buttonId, Intent intent) {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, intent, 0);
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

    /**
     * Called back by {@link com.android.email.provider.WidgetProvider.WidgetService} to
     * handle intents created by remote views.
     */
    public static boolean processIntent(Context context, Intent intent) {
        final Uri data = intent.getData();
        if (data == null) {
            return false;
        }
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
            if (EmailWidget.COMMAND_NAME_VIEW_MESSAGE.equals(command)) {
                // "view", <message id>, <mailbox id>
                openMessage(context, Long.parseLong(pathSegments.get(2)), arg1);
            } else if (EmailWidget.COMMAND_NAME_SWITCH_LIST_VIEW.equals(command)) {
                // "next_view", <widget id>
                EmailWidget widget = WidgetManager.getInstance().get((int)arg1);
                if (widget != null) {
                    widget.switchView();
                }
            }
        } catch (NumberFormatException e) {
            // Shouldn't happen as we construct all of the Uri's
            return false;
        }
        return true;
    }

    private static void openMessage(final Context context, final long mailboxId,
            final long messageId) {
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
                if (mailbox == null) return;
                context.startActivity(Welcome.createOpenMessageIntent(context, mailbox.mAccountKey,
                        mailboxId, messageId));
            }
        });
    }

    private void setupTitleAndCount(RemoteViews views) {
        // Set up the title (view type + count of messages)
        views.setTextViewText(R.id.widget_title, mWidgetView.getTitle(mContext));
        views.setTextViewText(R.id.widget_tap, sConfigureText);
        String count = "";
        if (isCursorValid()) {
            count = UiUtilities.getMessageCountForUi(mContext, mCursor.getMessageCount(), false);
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
        RemoteViews views =
                new RemoteViews(mContext.getPackageName(), R.layout.widget);

        // Set up the list with an adapter
        Intent intent = new Intent(mContext, WidgetService.class);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mWidgetId);
        intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(mWidgetId, R.id.message_list, intent);

        setupTitleAndCount(views);

        if (!isCursorValid() || mCursor.getAccountCount() == 0) {
            // Hide compose icon & show "touch to configure" text
            views.setViewVisibility(R.id.widget_compose, View.INVISIBLE);
            views.setViewVisibility(R.id.message_list, View.GONE);
            views.setViewVisibility(R.id.tap_to_configure, View.VISIBLE);
            // Create click intent for "touch to configure" target
            intent = Welcome.createOpenAccountInboxIntent(mContext, -1);
            setActivityIntent(views, R.id.tap_to_configure, intent);
        } else {
            // Show compose icon & message list
            views.setViewVisibility(R.id.widget_compose, View.VISIBLE);
            views.setViewVisibility(R.id.message_list, View.VISIBLE);
            views.setViewVisibility(R.id.tap_to_configure, View.GONE);
            // Create click intent for "compose email" target
            intent = MessageCompose.getMessageComposeIntent(mContext, -1);
            setActivityIntent(views, R.id.widget_compose, intent);
        }
        // Create click intent for "view rotation" target
        setCommandIntent(views, R.id.widget_logo, COMMAND_URI_SWITCH_LIST_VIEW);

        // Use a bare intent for our template; we need to fill everything in
        intent = new Intent(mContext, WidgetService.class);
        PendingIntent pendingIntent = PendingIntent.getService(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        views.setPendingIntentTemplate(R.id.message_list, pendingIntent);

        // And finally update the widget
        mWidgetManager.updateAppWidget(mWidgetId, views);
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

    @Override
    public RemoteViews getViewAt(int position) {
        // Use the cursor to set up the widget
        if (!isCursorValid() || !mCursor.moveToPosition(position)) {
            return getLoadingView();
        }
        RemoteViews views =
            new RemoteViews(mContext.getPackageName(), R.layout.widget_list_item);
        boolean isUnread = mCursor.getInt(EmailWidgetLoader.WIDGET_COLUMN_FLAG_READ) != 1;
        int drawableId = R.drawable.widget_read_conversation_selector;
        if (isUnread) {
            drawableId = R.drawable.widget_unread_conversation_selector;
        }
        views.setInt(R.id.widget_message, "setBackgroundResource", drawableId);

        // Add style to sender
        SpannableStringBuilder from =
            new SpannableStringBuilder(mCursor.getString(
                    EmailWidgetLoader.WIDGET_COLUMN_DISPLAY_NAME));
        from.setSpan(
                isUnread ? new StyleSpan(Typeface.BOLD) : new StyleSpan(Typeface.NORMAL), 0,
                from.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        CharSequence styledFrom = addStyle(from, sSenderFontSize, sDefaultTextColor);
        views.setTextViewText(R.id.widget_from, styledFrom);

        long timestamp = mCursor.getLong(EmailWidgetLoader.WIDGET_COLUMN_TIMESTAMP);
        // Get a nicely formatted date string (relative to today)
        String date = DateUtils.getRelativeTimeSpanString(mContext, timestamp).toString();
        // Add style to date
        CharSequence styledDate = addStyle(date, sDateFontSize, sDefaultTextColor);
        views.setTextViewText(R.id.widget_date, styledDate);

        // Add style to subject/snippet
        String subject = mCursor.getString(EmailWidgetLoader.WIDGET_COLUMN_SUBJECT);
        String snippet = mCursor.getString(EmailWidgetLoader.WIDGET_COLUMN_SNIPPET);
        CharSequence subjectAndSnippet =
            getStyledSubjectSnippet(subject, snippet, !isUnread);
        views.setTextViewText(R.id.widget_subject, subjectAndSnippet);

        int messageFlags = mCursor.getInt(EmailWidgetLoader.WIDGET_COLUMN_FLAGS);
        boolean hasInvite = (messageFlags & Message.FLAG_INCOMING_MEETING_INVITE) != 0;
        views.setViewVisibility(R.id.widget_invite, hasInvite ? View.VISIBLE : View.GONE);

        boolean hasAttachment =
                mCursor.getInt(EmailWidgetLoader.WIDGET_COLUMN_FLAG_ATTACHMENT) != 0;
        views.setViewVisibility(R.id.widget_attachment,
                hasAttachment ? View.VISIBLE : View.GONE);

        if (mCursor.getAccountCount() <= 1 || mWidgetView.isPerAccount()) {
            views.setViewVisibility(R.id.color_chip, View.INVISIBLE);
        } else {
            long accountId = mCursor.getLong(EmailWidgetLoader.WIDGET_COLUMN_ACCOUNT_KEY);
            int colorId = mResourceHelper.getAccountColorId(accountId);
            if (colorId != ResourceHelper.UNDEFINED_RESOURCE_ID) {
                // Color defined by resource ID, so, use it
                views.setViewVisibility(R.id.color_chip, View.VISIBLE);
                views.setImageViewResource(R.id.color_chip, colorId);
            } else {
                // Color not defined by resource ID, nothing we can do, so, hide the chip
                views.setViewVisibility(R.id.color_chip, View.INVISIBLE);
            }
        }

        // Set button intents for view, reply, and delete
        String messageId = mCursor.getString(EmailWidgetLoader.WIDGET_COLUMN_ID);
        String mailboxId = mCursor.getString(EmailWidgetLoader.WIDGET_COLUMN_MAILBOX_KEY);
        setFillInIntent(views, R.id.widget_message, COMMAND_URI_VIEW_MESSAGE,
                messageId, mailboxId);

        return views;
    }

    @Override
    public int getCount() {
        if (!isCursorValid()) return 0;
        return Math.min(mCursor.getCount(), MAX_MESSAGE_LIST_COUNT);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public RemoteViews getLoadingView() {
        RemoteViews view = new RemoteViews(mContext.getPackageName(), R.layout.widget_loading);
        view.setTextViewText(R.id.loading_text, mContext.getString(R.string.widget_loading));
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

    public void onDeleted() {
        if (mLoader != null) {
            mLoader.reset();
        }
        WidgetManager.getInstance().remove(mWidgetId);
    }

    @Override
    public void onDestroy() {
        if (mLoader != null) {
            mLoader.reset();
        }
        WidgetManager.getInstance().remove(mWidgetId);
    }

    @Override
    public void onCreate() {
    }

    /**
     * Update the widget.  If the current view is invalid, switch to the next view, then update.
     */
    /* package */ void validateAndUpdate() {
        new WidgetUpdater(false).execute();
    }

    /**
     * Switch to the next view.
     */
    /* package */ void switchView() {
        new WidgetUpdater(true).execute();
    }

    /**
     * Update the widget.  If {@code switchToNextView} is set true, or the current view is invalid,
     * switch to the next view.
     */
    private class WidgetUpdater extends AsyncTask<Void, Void, WidgetView> {
        private final WidgetView mCurrentView;
        private final boolean mSwitchToNextView;

        public WidgetUpdater(boolean switchToNextView) {
            mCurrentView = mWidgetView;
            mSwitchToNextView = switchToNextView;
        }

        @Override
        protected WidgetView doInBackground(Void... params) {
            if (mSwitchToNextView || !mCurrentView.isValid(mContext)) {
                return mCurrentView.getNext(mContext);
            } else {
                return mCurrentView; // Reload the same view.
            }
        }

        @Override
        protected void onPostExecute(WidgetView nextView) {
            if (nextView != null) {
                loadView(nextView);
            }
        }
    }
}