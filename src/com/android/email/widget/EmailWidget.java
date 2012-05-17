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

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.Loader.OnLoadCompleteListener;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.Uri.Builder;
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

import com.android.email.Email;
import com.android.email.R;
import com.android.email.ResourceHelper;
import com.android.email.activity.MessageCompose;
import com.android.email.activity.UiUtilities;
import com.android.email.activity.Welcome;
import com.android.email.provider.WidgetProvider.WidgetService;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;

import java.util.List;

/**
 * The email widget.
 * <p><em>NOTE</em>: All methods must be called on the UI thread so synchronization is NOT required
 * in this class)
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
    private static final String COMMAND_NAME_VIEW_MESSAGE = "view_message";
    private static final Uri COMMAND_URI_VIEW_MESSAGE =
            COMMAND_URI.buildUpon().appendPath(COMMAND_NAME_VIEW_MESSAGE).build();

    // TODO Can this be moved to the loader and made a database 'LIMIT'?
    private static final int MAX_MESSAGE_LIST_COUNT = 25;

    private static String sSubjectSnippetDivider;
    private static int sSenderFontSize;
    private static int sSubjectFontSize;
    private static int sDateFontSize;
    private static int sDefaultTextColor;
    private static int sLightTextColor;
    private static Object sWidgetLock = new Object();

    private final Context mContext;
    private final AppWidgetManager mWidgetManager;

    // The widget identifier
    private final int mWidgetId;

    // The widget's loader (derived from ThrottlingCursorLoader)
    private final EmailWidgetLoader mLoader;
    private final ResourceHelper mResourceHelper;

    /** The account ID of this widget. May be {@link Account#ACCOUNT_ID_COMBINED_VIEW}. */
    private long mAccountId = Account.NO_ACCOUNT;
    /** The display name of this account */
    private String mAccountName;
    /** The display name of this mailbox */
    private String mMailboxName;

    /**
     * The cursor for the messages, with some extra info such as the number of accounts.
     *
     * Note this cursor can be closed any time by the loader.  Always use {@link #isCursorValid()}
     * before touching its contents.
     */
    private EmailWidgetLoader.WidgetCursor mCursor;

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
        }
        mResourceHelper = ResourceHelper.getInstance(mContext);
    }

    /**
     * Start loading the data.  At this point nothing on the widget changes -- the current view
     * will remain valid until the loader loads the latest data.
     */
    public void start() {
        long accountId = WidgetManager.loadAccountIdPref(mContext, mWidgetId);
        long mailboxId = WidgetManager.loadMailboxIdPref(mContext, mWidgetId);
        // Legacy support; if preferences haven't been saved for this widget, load something
        if (accountId == Account.NO_ACCOUNT) {
            accountId = Account.ACCOUNT_ID_COMBINED_VIEW;
            mailboxId = Mailbox.QUERY_ALL_INBOXES;
        }
        mAccountId = accountId;
        mLoader.load(mAccountId, mailboxId);
    }

    /**
     * Resets the data in the widget and forces a reload.
     */
    public void reset() {
        mLoader.reset();
        start();
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
        synchronized (sWidgetLock) {
            mCursor = (EmailWidgetLoader.WidgetCursor) cursor;
            mAccountName = mCursor.getAccountName();
            mMailboxName = mCursor.getMailboxName();
        }
        updateHeader();
        mWidgetManager.notifyAppWidgetViewDataChanged(mWidgetId, R.id.message_list);
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // just in case intent comes without it
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, (int) mAccountId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
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
            }
        } catch (NumberFormatException e) {
            // Shouldn't happen as we construct all of the Uri's
            return false;
        }
        return true;
    }

    private static void openMessage(final Context context, final long mailboxId,
            final long messageId) {
        EmailAsyncTask.runAsyncParallel(new Runnable() {
            @Override
            public void run() {
                Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
                if (mailbox == null) return;
                context.startActivity(Welcome.createOpenMessageIntent(context, mailbox.mAccountKey,
                        mailboxId, messageId));
            }
        });
    }

    private void setTextViewTextAndDesc(RemoteViews views, final int id, String text) {
        views.setTextViewText(id, text);
        views.setContentDescription(id, text);
    }

    private void setupTitleAndCount(RemoteViews views) {
        // Set up the title (view type + count of messages)
        setTextViewTextAndDesc(views, R.id.widget_title, mMailboxName);
        views.setViewVisibility(R.id.widget_tap, View.VISIBLE);
        setTextViewTextAndDesc(views, R.id.widget_tap, mAccountName);
        String count = "";
        synchronized (sWidgetLock) {
            if (isCursorValid()) {
                count = UiUtilities
                        .getMessageCountForUi(mContext, mCursor.getMessageCount(), false);
            }
        }
        setTextViewTextAndDesc(views, R.id.widget_count, count);
    }

    /**
     * Update the "header" of the widget (i.e. everything that doesn't include the scrolling
     * message list)
     */
    private void updateHeader() {
        if (Email.DEBUG) {
            Log.d(TAG, "#updateHeader(); widgetId: " + mWidgetId);
        }

        // Get the widget layout
        RemoteViews views =
                new RemoteViews(mContext.getPackageName(), R.layout.widget);

        // Set up the list with an adapter
        Intent intent = new Intent(mContext, WidgetService.class);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mWidgetId);
        intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.message_list, intent);

        setupTitleAndCount(views);

        if (isCursorValid()) {
            // Show compose icon & message list
            if (mAccountId == Account.ACCOUNT_ID_COMBINED_VIEW
                || Account.restoreAccountWithId(mContext, mAccountId) == null) {
                // Don't allow compose for "combined" view
                views.setViewVisibility(R.id.widget_compose, View.INVISIBLE);
            } else {
                views.setViewVisibility(R.id.widget_compose, View.VISIBLE);
            }
            views.setViewVisibility(R.id.message_list, View.VISIBLE);
            views.setViewVisibility(R.id.tap_to_configure, View.GONE);
            // Create click intent for "compose email" target
            intent = MessageCompose.getMessageComposeIntent(mContext, mAccountId);
            intent.putExtra(MessageCompose.EXTRA_FROM_WIDGET, true);
            setActivityIntent(views, R.id.widget_compose, intent);
            // Create click intent for logo to open inbox
            intent = Welcome.createOpenAccountInboxIntent(mContext, mAccountId);
            setActivityIntent(views, R.id.widget_header, intent);
        } else {
            // TODO This really should never happen ... probably can remove the else block
            // Hide compose icon & show "touch to configure" text
            views.setViewVisibility(R.id.widget_compose, View.INVISIBLE);
            views.setViewVisibility(R.id.message_list, View.GONE);
            views.setViewVisibility(R.id.tap_to_configure, View.VISIBLE);
            // Create click intent for "touch to configure" target
            intent = Welcome.createOpenAccountInboxIntent(mContext, -1);
            setActivityIntent(views, R.id.tap_to_configure, intent);
        }

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
    private CharSequence getStyledSubjectSnippet(String subject, String snippet, boolean read) {
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
        synchronized (sWidgetLock) {
            // Use the cursor to set up the widget
            if (!isCursorValid() || !mCursor.moveToPosition(position)) {
                return getLoadingView();
            }
            RemoteViews views = new RemoteViews(mContext.getPackageName(),
                    R.layout.widget_list_item);
            boolean isUnread = mCursor.getInt(EmailWidgetLoader.WIDGET_COLUMN_FLAG_READ) != 1;
            int drawableId = R.drawable.conversation_read_selector;
            if (isUnread) {
                drawableId = R.drawable.conversation_unread_selector;
            }
            views.setInt(R.id.widget_message, "setBackgroundResource", drawableId);

            // Add style to sender
            String rawSender = mCursor.isNull(EmailWidgetLoader.WIDGET_COLUMN_DISPLAY_NAME) ?
                    "" : mCursor.getString(EmailWidgetLoader.WIDGET_COLUMN_DISPLAY_NAME);
            SpannableStringBuilder from = new SpannableStringBuilder(rawSender);
            from.setSpan(isUnread ? new StyleSpan(Typeface.BOLD) : new StyleSpan(Typeface.NORMAL),
                    0, from.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            CharSequence styledFrom = addStyle(from, sSenderFontSize, sDefaultTextColor);
            views.setTextViewText(R.id.widget_from, styledFrom);
            views.setContentDescription(R.id.widget_from, rawSender);
            long timestamp = mCursor.getLong(EmailWidgetLoader.WIDGET_COLUMN_TIMESTAMP);
            // Get a nicely formatted date string (relative to today)
            String date = DateUtils.getRelativeTimeSpanString(mContext, timestamp).toString();
            // Add style to date
            CharSequence styledDate = addStyle(date, sDateFontSize, sDefaultTextColor);
            views.setTextViewText(R.id.widget_date, styledDate);
            views.setContentDescription(R.id.widget_date, date);

            // Add style to subject/snippet
            String subject = mCursor.getString(EmailWidgetLoader.WIDGET_COLUMN_SUBJECT);
            String snippet = mCursor.getString(EmailWidgetLoader.WIDGET_COLUMN_SNIPPET);
            CharSequence subjectAndSnippet = getStyledSubjectSnippet(subject, snippet, !isUnread);
            views.setTextViewText(R.id.widget_subject, subjectAndSnippet);
            views.setContentDescription(R.id.widget_subject, subject);

            int messageFlags = mCursor.getInt(EmailWidgetLoader.WIDGET_COLUMN_FLAGS);
            boolean hasInvite = (messageFlags & Message.FLAG_INCOMING_MEETING_INVITE) != 0;
            views.setViewVisibility(R.id.widget_invite, hasInvite ? View.VISIBLE : View.GONE);

            boolean hasAttachment = mCursor
                    .getInt(EmailWidgetLoader.WIDGET_COLUMN_FLAG_ATTACHMENT) != 0;
            views.setViewVisibility(R.id.widget_attachment, hasAttachment ? View.VISIBLE
                    : View.GONE);

            if (mAccountId != Account.ACCOUNT_ID_COMBINED_VIEW) {
                views.setViewVisibility(R.id.color_chip, View.INVISIBLE);
            } else {
                long accountId = mCursor.getLong(EmailWidgetLoader.WIDGET_COLUMN_ACCOUNT_KEY);
                int colorId = mResourceHelper.getAccountColorId(accountId);
                if (colorId != ResourceHelper.UNDEFINED_RESOURCE_ID) {
                    // Color defined by resource ID, so, use it
                    views.setViewVisibility(R.id.color_chip, View.VISIBLE);
                    views.setImageViewResource(R.id.color_chip, colorId);
                } else {
                    // Color not defined by resource ID, nothing we can do, so,
                    // hide the chip
                    views.setViewVisibility(R.id.color_chip, View.INVISIBLE);
                }
            }

            // Set button intents for view, reply, and delete
            String messageId = mCursor.getString(EmailWidgetLoader.WIDGET_COLUMN_ID);
            String mailboxId = mCursor.getString(EmailWidgetLoader.WIDGET_COLUMN_MAILBOX_KEY);
            setFillInIntent(views, R.id.widget_message, COMMAND_URI_VIEW_MESSAGE, messageId,
                    mailboxId);

            return views;
        }
    }

    @Override
    public int getCount() {
        if (!isCursorValid())
            return 0;
        synchronized (sWidgetLock) {
            return Math.min(mCursor.getCount(), MAX_MESSAGE_LIST_COUNT);
        }
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
        // Note: we are not doing anything special in onDataSetChanged().  Since this service has
        // a reference to a loader that will keep itself updated, if the service is running, it
        // shouldn't be necessary to for the query to be run again.  If the service hadn't been
        // running, the act of starting the service will also start the loader.
    }

    public void onDeleted() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(TAG, "#onDeleted(); widgetId: " + mWidgetId);
        }

        if (mLoader != null) {
            mLoader.reset();
        }
    }

    @Override
    public void onDestroy() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(TAG, "#onDestroy(); widgetId: " + mWidgetId);
        }

        if (mLoader != null) {
            mLoader.reset();
        }
    }

    @Override
    public void onCreate() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(TAG, "#onCreate(); widgetId: " + mWidgetId);
        }
    }

    @Override
    public String toString() {
        return "View=" + mAccountName;
    }
}
