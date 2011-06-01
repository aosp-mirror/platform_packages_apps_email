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

import com.android.email.data.ThrottlingCursorLoader;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.Mailbox;

import android.content.Context;
import android.database.Cursor;
import android.database.CursorWrapper;

/**
 * Loader for {@link EmailWidget}.
 *
 * This loader not only loads the messages, but also:
 * - The number of accounts.
 * - The message count shown in the widget header.
 *   It's currently just the same as the message count, but this will be updated to the unread
 *   counts for inboxes.
 */
class EmailWidgetLoader extends ThrottlingCursorLoader {
    private static final String SORT_TIMESTAMP_DESCENDING = MessageColumns.TIMESTAMP + " DESC";

    // The projection to be used by the WidgetLoader
    private static final String[] WIDGET_PROJECTION = new String[] {
            EmailContent.RECORD_ID, MessageColumns.DISPLAY_NAME, MessageColumns.TIMESTAMP,
            MessageColumns.SUBJECT, MessageColumns.FLAG_READ, MessageColumns.FLAG_FAVORITE,
            MessageColumns.FLAG_ATTACHMENT, MessageColumns.MAILBOX_KEY, MessageColumns.SNIPPET,
            MessageColumns.ACCOUNT_KEY, MessageColumns.FLAGS
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
    public static final int WIDGET_COLUMN_FLAGS = 10;

    private long mAccountId;
    private long mMailboxId;

    /**
     * The actual data returned by this loader.
     */
    static class CursorWithCounts extends CursorWrapper {
        private final int mMessageCount;

        public CursorWithCounts(Cursor cursor, int messageCount) {
            super(cursor);
            mMessageCount = messageCount;
        }

        /**
         * @return The count that should be shown on the widget header.
         *     Note depending on the view, it may be the unread count, which is different from
         *     the record count (i.e. {@link #getCount()}}.
         */
        public int getMessageCount() {
            return mMessageCount;
        }
    }

    private final Context mContext;

    EmailWidgetLoader(Context context) {
        super(context, Message.CONTENT_URI, WIDGET_PROJECTION, null,
                null, SORT_TIMESTAMP_DESCENDING);
        mContext = context;
    }

    @Override
    public Cursor loadInBackground() {
        final Cursor messagesCursor = super.loadInBackground();

        // Reset the notification Uri to our Message table notifier URI
        messagesCursor.setNotificationUri(mContext.getContentResolver(), Message.NOTIFIER_URI);

        final int messageCount;
        if (mMailboxId != Mailbox.QUERY_ALL_FAVORITES) {
            String selection = "(" + getSelection() + " ) AND " + MessageColumns.FLAG_READ + " = 0";
            messageCount = EmailContent.count(mContext, Message.CONTENT_URI, selection,
                    getSelectionArgs());
        } else {
            // Just use the number of all messages shown.
            messageCount = messagesCursor.getCount();
        }

        return new CursorWithCounts(messagesCursor, messageCount);
    }

    /**
     * Stop any pending load, reset selection parameters, and start loading.
     *
     * Must be called from the UI thread
     *
     * @param accountId
     * @param mailboxId
     */
    void load(long accountId, long mailboxId) {
        reset();
        mAccountId = accountId;
        mMailboxId = mailboxId;
        setSelection(Message.PER_ACCOUNT_UNREAD_SELECTION);
        setSelectionArgs(new String[]{ Long.toString(mAccountId) });
        startLoading();
    }
}
