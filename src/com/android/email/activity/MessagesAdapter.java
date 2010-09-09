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
import com.android.email.data.ThrottlingCursorLoader;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.content.Context;
import android.content.Loader;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;


/**
 * This class implements the adapter for displaying messages based on cursors.
 */
/* package */ class MessagesAdapter extends CursorAdapter {
    private static final String STATE_CHECKED_ITEMS =
            "com.android.email.activity.MessagesAdapter.checkedItems";

    /* package */ static final String[] MESSAGE_PROJECTION = new String[] {
        EmailContent.RECORD_ID, MessageColumns.MAILBOX_KEY, MessageColumns.ACCOUNT_KEY,
        MessageColumns.DISPLAY_NAME, MessageColumns.SUBJECT, MessageColumns.TIMESTAMP,
        MessageColumns.FLAG_READ, MessageColumns.FLAG_FAVORITE, MessageColumns.FLAG_ATTACHMENT,
        MessageColumns.FLAGS, MessageColumns.SNIPPET
    };

    public static final int COLUMN_ID = 0;
    public static final int COLUMN_MAILBOX_KEY = 1;
    public static final int COLUMN_ACCOUNT_KEY = 2;
    public static final int COLUMN_DISPLAY_NAME = 3;
    public static final int COLUMN_SUBJECT = 4;
    public static final int COLUMN_DATE = 5;
    public static final int COLUMN_READ = 6;
    public static final int COLUMN_FAVORITE = 7;
    public static final int COLUMN_ATTACHMENTS = 8;
    public static final int COLUMN_FLAGS = 9;
    public static final int COLUMN_SNIPPET = 10;

    private static final int ITEM_BACKGROUND_SELECTED = 0xFFB0FFB0; // TODO color not finalized

    private final LayoutInflater mInflater;
    private final Drawable mAttachmentIcon;
    private final Drawable mInvitationIcon;
    private final Drawable mFavoriteIconOn;
    private final Drawable mFavoriteIconOff;
    private final Drawable mSelectedIconOn;
    private final Drawable mSelectedIconOff;

    private final ColorStateList mTextColorPrimary;
    private final ColorStateList mTextColorSecondary;

    // How long we want to wait for refreshes (a good starting guess)
    // I suspect this could be lowered down to even 1000 or so, but this seems ok for now
    private static final int REFRESH_INTERVAL_MS = 2500;

    private final java.text.DateFormat mDateFormat;
    private final java.text.DateFormat mTimeFormat;

    /**
     * Set of seleced message IDs.  Note for performac{@link MessageListItem
     */
    private final HashSet<Long> mSelectedSet = new HashSet<Long>();

    /**
     * Callback from MessageListAdapter.  All methods are called on the UI thread.
     */
    public interface Callback {
        /** Called when the use starts/unstars a message */
        void onAdapterFavoriteChanged(MessageListItem itemView, boolean newFavorite);
        /** Called when the user selects/unselects a message */
        void onAdapterSelectedChanged(MessageListItem itemView, boolean newSelected,
                int mSelectedCount);
    }

    private final Callback mCallback;

    public MessagesAdapter(Context context, Callback callback) {
        super(context.getApplicationContext(), null, 0 /* no auto requery */);
        mCallback = callback;
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        Resources resources = context.getResources();
        mAttachmentIcon = resources.getDrawable(R.drawable.ic_mms_attachment_small);
        mInvitationIcon = resources.getDrawable(R.drawable.ic_calendar_event_small);
        mFavoriteIconOn = resources.getDrawable(R.drawable.btn_star_big_buttonless_dark_on);
        mFavoriteIconOff = resources.getDrawable(R.drawable.btn_star_big_buttonless_dark_off);
        mSelectedIconOn = resources.getDrawable(R.drawable.btn_check_buttonless_dark_on);
        mSelectedIconOff = resources.getDrawable(R.drawable.btn_check_buttonless_dark_off);

        Theme theme = context.getTheme();
        TypedArray array;
        array = theme.obtainStyledAttributes(new int[] { android.R.attr.textColorPrimary });
        mTextColorPrimary = resources.getColorStateList(array.getResourceId(0, 0));
        array = theme.obtainStyledAttributes(new int[] { android.R.attr.textColorSecondary });
        mTextColorSecondary = resources.getColorStateList(array.getResourceId(0, 0));

        mDateFormat = android.text.format.DateFormat.getDateFormat(context);    // short date
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(context);    // 12/24 time
    }

    public void onSaveInstanceState(Bundle outState) {
        Set<Long> checkedset = getSelectedSet();
        long[] checkedarray = new long[checkedset.size()];
        int i = 0;
        for (Long l : checkedset) {
            checkedarray[i] = l;
            i++;
        }
        outState.putLongArray(STATE_CHECKED_ITEMS, checkedarray);
    }

    public void loadState(Bundle savedInstanceState) {
        Set<Long> checkedset = getSelectedSet();
        for (long l: savedInstanceState.getLongArray(STATE_CHECKED_ITEMS)) {
            checkedset.add(l);
        }
    }

    public Set<Long> getSelectedSet() {
        return mSelectedSet;
    }

    public boolean isSelected(MessageListItem itemView) {
        return mSelectedSet.contains(itemView.mMessageId);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        // Reset the view (in case it was recycled) and prepare for binding
        MessageListItem itemView = (MessageListItem) view;
        itemView.bindViewInit(this);

        // Load the public fields in the view (for later use)
        itemView.mMessageId = cursor.getLong(COLUMN_ID);
        itemView.mMailboxId = cursor.getLong(COLUMN_MAILBOX_KEY);
        itemView.mAccountId = cursor.getLong(COLUMN_ACCOUNT_KEY);
        itemView.mRead = cursor.getInt(COLUMN_READ) != 0;
        itemView.mFavorite = cursor.getInt(COLUMN_FAVORITE) != 0;

        // Load the UI
        View chipView = view.findViewById(R.id.chip);
        chipView.setBackgroundResource(Email.getAccountColorResourceId(itemView.mAccountId));

        TextView fromView = (TextView) view.findViewById(R.id.from);
        String text = cursor.getString(COLUMN_DISPLAY_NAME);
        fromView.setText(text);

        TextView subjectView = (TextView) view.findViewById(R.id.subject);
        text = cursor.getString(COLUMN_SUBJECT);
        // Add in the snippet if we have one
        // TODO Should this be spanned text?
        // The mocks show, for new messages, only the real subject in bold...
        // Would it be easier to simply use a 2nd TextView? This would also allow ellipsizing an
        // overly-long subject, to let the beautiful snippet shine through.
        String snippet = cursor.getString(COLUMN_SNIPPET);
        if (!TextUtils.isEmpty(snippet)) {
            text = context.getString(R.string.message_list_snippet, text, snippet);
        }
        subjectView.setText(text);

        boolean hasInvitation =
                    (cursor.getInt(COLUMN_FLAGS) & Message.FLAG_INCOMING_MEETING_INVITE) != 0;
        boolean hasAttachments = cursor.getInt(COLUMN_ATTACHMENTS) != 0;
        Drawable icon =
                hasInvitation ? mInvitationIcon
                : hasAttachments ? mAttachmentIcon : null;
        subjectView.setCompoundDrawablesWithIntrinsicBounds(null, null, icon, null);

        // TODO ui spec suggests "time", "day", "date" - implement "day"
        TextView dateView = (TextView) view.findViewById(R.id.date);
        long timestamp = cursor.getLong(COLUMN_DATE);
        Date date = new Date(timestamp);
        if (Utility.isDateToday(date)) {
            text = mTimeFormat.format(date);
        } else {
            text = mDateFormat.format(date);
        }
        dateView.setText(text);

        if (itemView.mRead) {
            subjectView.setTypeface(Typeface.DEFAULT);
            fromView.setTypeface(Typeface.DEFAULT);
            fromView.setTextColor(mTextColorSecondary);
            view.setBackgroundDrawable(context.getResources().getDrawable(
                    R.drawable.message_list_item_background_read));
        } else {
            subjectView.setTypeface(Typeface.DEFAULT_BOLD);
            fromView.setTypeface(Typeface.DEFAULT_BOLD);
            fromView.setTextColor(mTextColorPrimary);
            view.setBackgroundDrawable(context.getResources().getDrawable(
                    R.drawable.message_list_item_background_unread));
        }

        updateCheckBox(itemView);
        ImageView favoriteView = (ImageView) view.findViewById(R.id.favorite);
        favoriteView.setImageDrawable(itemView.mFavorite ? mFavoriteIconOn : mFavoriteIconOff);
        updateBackgroundColor(itemView);
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(R.layout.message_list_item, parent, false);
    }

    private void updateCheckBox(MessageListItem itemView) {
        ImageView selectedView = (ImageView) itemView.findViewById(R.id.selected);
        selectedView.setImageDrawable(isSelected(itemView) ? mSelectedIconOn : mSelectedIconOff);
    }

    public void toggleSelected(MessageListItem itemView) {
        updateSelected(itemView, !isSelected(itemView));
    }

    /**
     * This is used as a callback from the list items, to set the selected state
     *
     * <p>Must be called on the UI thread.
     *
     * @param itemView the item being changed
     * @param newSelected the new value of the selected flag (checkbox state)
     */
    private void updateSelected(MessageListItem itemView, boolean newSelected) {
        if (newSelected) {
            mSelectedSet.add(itemView.mMessageId);
        } else {
            mSelectedSet.remove(itemView.mMessageId);
        }
        updateCheckBox(itemView);
        updateBackgroundColor(itemView);
        if (mCallback != null) {
            mCallback.onAdapterSelectedChanged(itemView, newSelected, mSelectedSet.size());
        }
    }

    /**
     * This is used as a callback from the list items, to set the favorite state
     *
     * <p>Must be called on the UI thread.
     *
     * @param itemView the item being changed
     * @param newFavorite the new value of the favorite flag (star state)
     */
    public void updateFavorite(MessageListItem itemView, boolean newFavorite) {
        ImageView favoriteView = (ImageView) itemView.findViewById(R.id.favorite);
        favoriteView.setImageDrawable(newFavorite ? mFavoriteIconOn : mFavoriteIconOff);
        if (mCallback != null) {
            mCallback.onAdapterFavoriteChanged(itemView, newFavorite);
        }
    }

    /**
     * Update the background color according to the selection state.
     */
    public void updateBackgroundColor(MessageListItem itemView) {
        if (isSelected(itemView)) {
            itemView.setBackgroundColor(ITEM_BACKGROUND_SELECTED);
        } else {
            itemView.setBackgroundDrawable(null); // Change back to default.
        }
    }

    public static Loader<Cursor> createLoader(Context context, long mailboxId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessagesAdapter createLoader mailboxId=" + mailboxId);
        }
        return new MessagesCursor(context, mailboxId);

    }

    private static class MessagesCursor extends ThrottlingCursorLoader {
        private final Context mContext;
        private final long mMailboxId;

        public MessagesCursor(Context context, long mailboxId) {
            // Initialize with no where clause.  We'll set it later.
            super(context, EmailContent.Message.CONTENT_URI,
                    MESSAGE_PROJECTION, null, null,
                    EmailContent.MessageColumns.TIMESTAMP + " DESC", REFRESH_INTERVAL_MS);
            mContext = context;
            mMailboxId = mailboxId;
        }

        @Override
        public Cursor loadInBackground() {
            // Determine the where clause.  (Can't do this on the UI thread.)
            setSelection(Utility.buildMailboxIdSelection(mContext, mMailboxId));

            // Then do a query.
            return super.loadInBackground();
        }
    }
}
