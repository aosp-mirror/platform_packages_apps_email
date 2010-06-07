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

import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;

import android.content.Context;
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
 */
/* package */ class MailboxesAdapter extends CursorAdapter {

    public final String[] PROJECTION = new String[] { MailboxColumns.ID,
            MailboxColumns.DISPLAY_NAME, MailboxColumns.UNREAD_COUNT, MailboxColumns.TYPE };
    public final int COLUMN_ID = 0;
    public final int COLUMN_DISPLAY_NAME = 1;
    public final int COLUMN_UNREAD_COUNT = 2;
    public final int COLUMN_TYPE = 3;

    private final LayoutInflater mInflater;
    private long mAccountId;
    private int mUnreadCountDraft;
    private int mUnreadCountTrash;

    public MailboxesAdapter(Context context) {
        super(context, null);
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    /**
     * Set account Id (which may become available after creation)
     * TODO simplify the caller to just provide this at constructor time
     */
    public void setAccountId(long accountId) {
        mAccountId = accountId;
    }

    /**
     * Set special read/unread counts (not taken from the row).
     */
    public void setMessageCounts(int numDrafts, int numTrash) {
        boolean countChanged = (mUnreadCountDraft != numDrafts) || (mUnreadCountTrash != numTrash);
        if (countChanged) {
            mUnreadCountDraft = numDrafts;
            mUnreadCountTrash = numTrash;
            notifyDataSetChanged();
        }
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        int type = cursor.getInt(COLUMN_TYPE);
        String text = Utility.FolderProperties.getInstance(context)
                .getDisplayName(type);
        if (text == null) {
            text = cursor.getString(COLUMN_DISPLAY_NAME);
        }
        TextView nameView = (TextView) view.findViewById(R.id.mailbox_name);
        if (text != null) {
            nameView.setText(text);
        }

        // TODO get/track live folder status
        text = null;
        TextView statusView = (TextView) view.findViewById(R.id.mailbox_status);
        if (text != null) {
            statusView.setText(text);
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
        }
        View chipView = view.findViewById(R.id.chip);
        chipView.setBackgroundResource(Email.getAccountColorResourceId(mAccountId));
        int count = -1;
        switch (type) {
            case Mailbox.TYPE_DRAFTS:
                count = mUnreadCountDraft;
                text = String.valueOf(count);
                break;
            case Mailbox.TYPE_TRASH:
                count = mUnreadCountTrash;
                text = String.valueOf(count);
                break;
            default:
                text = cursor.getString(COLUMN_UNREAD_COUNT);
                if (text != null) {
                    count = Integer.valueOf(text);
                }
                break;
        }
        TextView unreadCountView = (TextView) view.findViewById(R.id.new_message_count);
        TextView allCountView = (TextView) view.findViewById(R.id.all_message_count);
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
                allCountView.setText(text);
                break;
            default:
                allCountView.setVisibility(View.GONE);
                unreadCountView.setVisibility(View.VISIBLE);
                unreadCountView.setText(text);
                break;
        }
        } else {
            nameView.setTypeface(Typeface.DEFAULT);
            allCountView.setVisibility(View.GONE);
            unreadCountView.setVisibility(View.GONE);
        }

        ImageView folderIcon = (ImageView) view.findViewById(R.id.folder_icon);
        folderIcon.setImageDrawable(Utility.FolderProperties.getInstance(context)
                .getIconIds(type));
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(R.layout.mailbox_list_item, parent, false);
    }
}