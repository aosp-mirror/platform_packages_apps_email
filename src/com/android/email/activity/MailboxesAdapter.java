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

package com.android.email.activity;

import com.android.email.R;
import com.android.email.Utility;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;

import android.content.Context;
import android.content.CursorLoader;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The adapter for displaying mailboxes.
 *
 * TODO Add "combined inbox/star/etc.".
 * TODO Throttle auto-requery.
 * TODO Unit test, when UI is settled.
 */
/* package */ class MailboxesAdapter extends CursorAdapter {
    private static final String[] PROJECTION = new String[] { MailboxColumns.ID,
            MailboxColumns.DISPLAY_NAME, MailboxColumns.UNREAD_COUNT, MailboxColumns.TYPE,
            MailboxColumns.MESSAGE_COUNT};
    private static final int COLUMN_ID = 0;
    private static final int COLUMN_DISPLAY_NAME = 1;
    private static final int COLUMN_UNREAD_COUNT = 2;
    private static final int COLUMN_TYPE = 3;
    private static final int COLUMN_MESSAGE_COUNT = 4;

    private static final String MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?" +
            " AND " + MailboxColumns.TYPE + "<" + Mailbox.TYPE_NOT_EMAIL +
            " AND " + MailboxColumns.FLAG_VISIBLE + "=1";

    private final LayoutInflater mInflater;

    public MailboxesAdapter(Context context) {
        super(context, null, 0 /* no auto-requery */);
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        final int type = cursor.getInt(COLUMN_TYPE);

        // Set mailbox name
        final TextView nameView = (TextView) view.findViewById(R.id.mailbox_name);
        String mailboxName = Utility.FolderProperties.getInstance(context)
                .getDisplayName(type);
        if (mailboxName == null) {
            mailboxName = cursor.getString(COLUMN_DISPLAY_NAME);
        }
        if (mailboxName != null) {
            nameView.setText(mailboxName);
        }

        // Set count
        final int count = cursor.getInt((type == Mailbox.TYPE_DRAFTS || type == Mailbox.TYPE_TRASH)
                ? COLUMN_MESSAGE_COUNT : COLUMN_UNREAD_COUNT);
        final TextView unreadCountView = (TextView) view.findViewById(R.id.new_message_count);
        final TextView allCountView = (TextView) view.findViewById(R.id.all_message_count);

        // If the unread count is zero, not to show countView.
        if (count > 0) {
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            switch (type) {
                case Mailbox.TYPE_DRAFTS:
                case Mailbox.TYPE_OUTBOX:
                case Mailbox.TYPE_SENT:
                case Mailbox.TYPE_TRASH:
                    unreadCountView.setVisibility(View.GONE);
                    allCountView.setVisibility(View.VISIBLE);
                    allCountView.setText(Integer.toString(count));
                    break;
                default:
                    allCountView.setVisibility(View.GONE);
                    unreadCountView.setVisibility(View.VISIBLE);
                    unreadCountView.setText(Integer.toString(count));
                    break;
            }
        } else {
            nameView.setTypeface(Typeface.DEFAULT);
            allCountView.setVisibility(View.GONE);
            unreadCountView.setVisibility(View.GONE);
        }

        // Set folder icon
        ((ImageView) view.findViewById(R.id.folder_icon))
                .setImageDrawable(Utility.FolderProperties.getInstance(context)
                .getIconIds(type));
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(R.layout.mailbox_list_item, parent, false);
    }

    /**
     * @return mailboxes Loader for an account.
     */
    public static Loader<Cursor> createLoader(Context context, long accountId) {
        return new CursorLoader(context,
                EmailContent.Mailbox.CONTENT_URI,
                MailboxesAdapter.PROJECTION,
                MAILBOX_SELECTION,
                new String[] { String.valueOf(accountId) },
                MailboxColumns.TYPE + "," + MailboxColumns.DISPLAY_NAME);
    }
}