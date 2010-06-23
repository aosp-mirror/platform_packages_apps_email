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
import com.android.email.provider.EmailContent.Message;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemClock;
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
import java.util.Timer;
import java.util.TimerTask;


/**
 * This class implements the adapter for displaying messages based on cursors.
 */
/* package */ class MessagesAdapter extends CursorAdapter {

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

    private static final int ITEM_BACKGROUND_SELECTED = 0xFFB0FFB0; // TODO color not finalized

    private final LayoutInflater mInflater;
    private final Drawable mAttachmentIcon;
    private final Drawable mInvitationIcon;
    private final Drawable mFavoriteIconOn;
    private final Drawable mFavoriteIconOff;

    private final ColorStateList mTextColorPrimary;
    private final ColorStateList mTextColorSecondary;

    // Timer to control the refresh rate of the list
    private final RefreshTimer mRefreshTimer = new RefreshTimer();
    // Last time we allowed a refresh of the list
    private long mLastRefreshTime = 0;
    // How long we want to wait for refreshes (a good starting guess)
    // I suspect this could be lowered down to even 1000 or so, but this seems ok for now
    private static final long REFRESH_INTERVAL_MS = 2500;

    private final java.text.DateFormat mDateFormat;
    private final java.text.DateFormat mTimeFormat;

    private final HashSet<Long> mSelected = new HashSet<Long>();

    /**
     * Callback from MessageListAdapter.  All methods are called on the UI thread.
     */
    public interface Callback {
        /** Called when the adapter refreshes */
        void onAdapterRequery();
        /** Called when the use starts/unstars a message */
        void onAdapterFavoriteChanged(MessageListItem itemView, boolean newFavorite);
        /** Called when the user selects/unselects a message */
        void onAdapterSelectedChanged(MessageListItem itemView, boolean newSelected,
                int mSelectedCount);
    }

    private final Callback mCallback;

    /**
     * Used to call callbacks in the UI thread.
     */
    private final Handler mHandler;

    public MessagesAdapter(Context context, Handler handler, Callback callback) {
        super(context.getApplicationContext(), null, true);
        mHandler = handler;
        mCallback = callback;
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        Resources resources = context.getResources();
        mAttachmentIcon = resources.getDrawable(R.drawable.ic_mms_attachment_small);
        mInvitationIcon = resources.getDrawable(R.drawable.ic_calendar_event_small);
        mFavoriteIconOn = resources.getDrawable(R.drawable.btn_star_big_buttonless_dark_on);
        mFavoriteIconOff = resources.getDrawable(R.drawable.btn_star_big_buttonless_dark_off);

        Theme theme = context.getTheme();
        TypedArray array;
        array = theme.obtainStyledAttributes(new int[] { android.R.attr.textColorPrimary });
        mTextColorPrimary = resources.getColorStateList(array.getResourceId(0, 0));
        array = theme.obtainStyledAttributes(new int[] { android.R.attr.textColorSecondary });
        mTextColorSecondary = resources.getColorStateList(array.getResourceId(0, 0));

        mDateFormat = android.text.format.DateFormat.getDateFormat(context);    // short date
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(context);    // 12/24 time
    }

    /**
     * We override onContentChange to throttle the refresh, which can happen way too often
     * on syncing a large list (up to many times per second).  This will prevent ANR's during
     * initial sync and potentially at other times as well.
     */
    @Override
    protected synchronized void onContentChanged() {
        final Cursor cursor = getCursor();
        if (cursor != null && !cursor.isClosed()) {
            long sinceRefresh = SystemClock.elapsedRealtime() - mLastRefreshTime;
            mRefreshTimer.schedule(REFRESH_INTERVAL_MS - sinceRefresh);
        }
    }

    /**
     * Called in UI thread only to complete the requery that we
     * intercepted in onContentChanged().
     */
    private void doRequery() {
        super.onContentChanged();
    }

    private class RefreshTimer extends Timer {
        private TimerTask timerTask = null;

        protected void clear() {
            timerTask = null;
        }

        protected synchronized void schedule(long delay) {
            if (timerTask != null) return;
            if (delay < 0) {
                refreshList();
            } else {
                timerTask = new RefreshTimerTask();
                schedule(timerTask, delay);
            }
        }
    }

    private class RefreshTimerTask extends TimerTask {
        @Override
        public void run() {
            refreshList();
        }
    }

    /**
     * Do the work of requerying the list and notifying the UI of changed data
     * Make sure we call notifyDataSetChanged on the UI thread.
     */
    private synchronized void refreshList() {
        if (Email.LOGD) {
            Log.d("messageList", "refresh: "
                    + (SystemClock.elapsedRealtime() - mLastRefreshTime) + "ms");
        }
        mHandler.post(new Runnable() {
            public void run() {
                doRequery();
                if (mCallback != null) {
                    mCallback.onAdapterRequery();
                }
            }
        });
        mLastRefreshTime = SystemClock.elapsedRealtime();
        mRefreshTimer.clear();
    }

    public Set<Long> getSelectedSet() {
        return mSelected;
    }

    public boolean isSelected(MessageListItem itemView) {
        return mSelected.contains(itemView.mMessageId);
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

        ImageView favoriteView = (ImageView) view.findViewById(R.id.favorite);
        favoriteView.setImageDrawable(itemView.mFavorite ? mFavoriteIconOn : mFavoriteIconOff);
        updateBackgroundColor(itemView);
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(R.layout.message_list_item, parent, false);
    }

    /**
     * This is used as a callback from the list items, to set the selected state
     *
     * <p>Must be called on the UI thread.
     *
     * @param itemView the item being changed
     * @param newSelected the new value of the selected flag (checkbox state)
     */
    public void updateSelected(MessageListItem itemView, boolean newSelected) {
        // Set checkbox state in list, and show/hide panel if necessary
        Long id = Long.valueOf(itemView.mMessageId);
        if (newSelected) {
            mSelected.add(id);
        } else {
            mSelected.remove(id);
        }
        updateBackgroundColor(itemView);
        if (mCallback != null) {
            mCallback.onAdapterSelectedChanged(itemView, newSelected, mSelected.size());
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
}
