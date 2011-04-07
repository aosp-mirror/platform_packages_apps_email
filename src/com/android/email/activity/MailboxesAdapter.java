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

import com.android.email.FolderProperties;
import com.android.email.ResourceHelper;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;

import android.content.Context;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;

/**
 * Super class adapter for displaying a mailbox list.
 * NOTE: Do not use {@link #getItemId(int)}; it's only for ListView. Instead, use {@link #getId}.
 *
 * TODO Show "Starred" per account
 * TODO Unit test, when UI is settled.
 */
/*package*/ abstract class MailboxesAdapter extends CursorAdapter {
    /**
     * Return value from {@link #getCountType}.
     */
    public static final int COUNT_TYPE_UNREAD = 0;
    public static final int COUNT_TYPE_TOTAL = 1;
    public static final int COUNT_TYPE_NO_COUNT = 2;

    /**
     * Callback interface used to report clicks other than the basic list item click or long press.
     */
    public interface Callback {
        /** Callback for setting background of mailbox list items during a drag */
        public void onBind(MailboxListItem listItem);
    }

    /**
     * Row type, used in the "row_type" in {@link #PROJECTION}.
     * {@link #ROW_TYPE_MAILBOX} for regular mailboxes and combined mailboxes.
     * {@link #ROW_TYPE_ACCOUNT} for account row in the combined view.
     */
    static final int ROW_TYPE_MAILBOX = 0;
    static final int ROW_TYPE_ACCOUNT = 1;

    /*
     * Note here we have two ID columns.  The first one is for ListView, which doesn't like ID
     * values to be negative.  The second one is the actual mailbox ID, which we use in the rest
     * of code.
     * ListView uses row IDs for some operations, including onSave/RestoreInstanceState,
     * and if we use negative IDs they don't work as expected.
     * Because ListView finds the ID column by name ("_id"), we rename the second column
     * so that ListView gets the correct column.
     */
    /*package*/ static final String[] PROJECTION = new String[] { MailboxColumns.ID,
            MailboxColumns.ID + " AS org_mailbox_id",
            MailboxColumns.DISPLAY_NAME, MailboxColumns.TYPE, MailboxColumns.UNREAD_COUNT,
            MailboxColumns.MESSAGE_COUNT, ROW_TYPE_MAILBOX + " AS row_type" };

    // Column 0 is only for ListView; we don't use it in our code.
    static final int COLUMN_ID = 1;
    static final int COLUMN_DISPLAY_NAME = 2;
    static final int COLUMN_TYPE = 3;
    static final int COLUMN_UNREAD_COUNT = 4;
    static final int COLUMN_MESSAGE_COUNT = 5;
    static final int COLUMN_ROW_TYPE = 6;

    /** All mailboxes for the account */
    static final String MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?" +
            " AND " + Mailbox.USER_VISIBLE_MAILBOX_SELECTION;
    // STOPSHIP This can be removed when legacy protocols support folders
    /** All top-level mailboxes */
    static final String MAILBOX_SELECTION_NO_PARENT = MAILBOX_SELECTION +
            " AND " + MailboxColumns.PARENT_KEY + "<=0";
    /** All mailboxes with the given parent */
    static final String MAILBOX_SELECTION_WITH_PARENT = MAILBOX_SELECTION +
            " AND " + MailboxColumns.PARENT_KEY + "=?";

    static final String MAILBOX_ORDER_BY = "CASE " + MailboxColumns.TYPE +
            " WHEN " + Mailbox.TYPE_INBOX   + " THEN 0" +
            " WHEN " + Mailbox.TYPE_DRAFTS  + " THEN 1" +
            " WHEN " + Mailbox.TYPE_OUTBOX  + " THEN 2" +
            " WHEN " + Mailbox.TYPE_SENT    + " THEN 3" +
            " WHEN " + Mailbox.TYPE_TRASH   + " THEN 4" +
            " WHEN " + Mailbox.TYPE_JUNK    + " THEN 5" +
            // Other mailboxes (i.e. of Mailbox.TYPE_MAIL) are shown in alphabetical order.
            " ELSE 10 END" +
            " ," + MailboxColumns.DISPLAY_NAME;

    /** Do-nothing callback to avoid null tests for <code>mCallback</code>. */
    private static final class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override
        public void onBind(MailboxListItem listItem) {
        }
    }

    static boolean sEnableUpdate = true;
    final LayoutInflater mInflater;
    final ResourceHelper mResourceHelper;
    final Callback mCallback;

    /*package*/ MailboxesAdapter(Context context, Callback callback) {
        super(context, null, 0 /* flags; no content observer */);
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
        mResourceHelper = ResourceHelper.getInstance(context);
    }

    @Override
    public abstract void bindView(View view, Context context, Cursor cursor);

    @Override
    public abstract View newView(Context context, Cursor cursor, ViewGroup parent);

    /**
     * @return true if the current row is of an account in the combined view.
     */
    static boolean isAccountRow(Cursor c) {
        return c.getInt(COLUMN_ROW_TYPE) == ROW_TYPE_ACCOUNT;
    }

    /**
     * @return true if the specified row is of an account in the combined view.
     */
    public boolean isAccountRow(int position) {
        return isAccountRow((Cursor) getItem(position));
    }

    /**
     * @return which type of count should be used for the current row.
     * Possible return values are COUNT_TYPE_*.
     */
    static int getCountTypeForMailboxType(Cursor c) {
        if (isAccountRow(c)) {
            return COUNT_TYPE_UNREAD; // Use the unread count for account rows.
        }
        switch (c.getInt(COLUMN_TYPE)) {
            case Mailbox.TYPE_DRAFTS:
            case Mailbox.TYPE_OUTBOX:
                return COUNT_TYPE_TOTAL;
            case Mailbox.TYPE_SENT:
            case Mailbox.TYPE_TRASH:
                return COUNT_TYPE_NO_COUNT;
        }
        return COUNT_TYPE_UNREAD;
    }

    /**
     * @return count type for the specified row.  See {@link #getCountTypeForMailboxType}
     */
    private int getCountType(int position) {
        if (isAccountRow(position)) {
            return COUNT_TYPE_UNREAD;
        }
        return getCountTypeForMailboxType((Cursor) getItem(position));
    }

    /**
     * @return count type for the specified row.
     */
    public int getUnreadCount(int position) {
        if (getCountType(position) != COUNT_TYPE_UNREAD) {
            return 0; // Don't have a unread count.
        }
        Cursor c = (Cursor) getItem(position);
        return c.getInt(COLUMN_UNREAD_COUNT);
    }

    /**
     * @return display name for the specified row.
     */
    public String getDisplayName(Context context, int position) {
        Cursor c = (Cursor) getItem(position);
        return getDisplayName(context, c);
    }

    /**
     * @return ID of the mailbox (or account, if {@link #isAccountRow} == true) of the specified
     * row.
     */
    public long getId(int position) {
        Cursor c = (Cursor) getItem(position);
        return c.getLong(COLUMN_ID);
    }

    /**
     * Turn on and off list updates; during a drag operation, we do NOT want to the list of
     * mailboxes to update, as this would be visually jarring
     * @param state whether or not the MailboxList can be updated
     */
    public static void enableUpdates(boolean state) {
        sEnableUpdate = state;
    }

    static String getDisplayName(Context context, Cursor cursor) {
        String name = null;
        if (cursor.getInt(COLUMN_ROW_TYPE) == ROW_TYPE_MAILBOX) {
            // If it's a mailbox (as opposed to account row in combined view), and of certain types,
            // we use the predefined names.
            final int type = cursor.getInt(COLUMN_TYPE);
            final long mailboxId = cursor.getLong(COLUMN_ID);
            name = FolderProperties.getInstance(context).getDisplayName(type, mailboxId);
        }
        if (name == null) {
            name = cursor.getString(COLUMN_DISPLAY_NAME);
        }
        return name;
    }

    /* package */ static long getIdForTest(Cursor cursor) {
        return cursor.getLong(COLUMN_ID);
    }

    /* package */ static int getTypeForTest(Cursor cursor) {
        return cursor.getInt(COLUMN_TYPE);
    }

    /* package */ static int getMessageCountForTest(Cursor cursor) {
        return cursor.getInt(COLUMN_MESSAGE_COUNT);
    }

    /* package */ static int getUnreadCountForTest(Cursor cursor) {
        return cursor.getInt(COLUMN_UNREAD_COUNT);
    }
}