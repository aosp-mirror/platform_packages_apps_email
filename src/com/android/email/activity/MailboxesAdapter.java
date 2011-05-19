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
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.google.common.annotations.VisibleForTesting;

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
    /*package*/ static final int COUNT_TYPE_UNREAD = 0;
    /*package*/ static final int COUNT_TYPE_TOTAL = 1;
    /*package*/ static final int COUNT_TYPE_NO_COUNT = 2;

    /**
     * Callback interface used to report clicks other than the basic list item click or long press.
     */
    public interface Callback {
        /** Callback for setting background of mailbox list items during a drag */
        public void onBind(MailboxListItem listItem);
    }

    /**
     * The type of the row to present to the user. There are 4 defined rows that each
     * have a slightly different look. These are typically used in the constant column
     * <code>row_type</code> specified in {@link #PROJECTION} and {@link #SUBMAILBOX_PROJECTION}.
     */
    /** Both regular and combined mailboxes */
    static final int ROW_TYPE_MAILBOX = 0;
    /** Account "mailboxes" in the combined view */
    static final int ROW_TYPE_ACCOUNT = 1;
    // STOPSHIP Need to determine if these types are sufficient for nested folders
    // The following types are used when drilling into a mailbox
    /** The current mailbox */
    static final int ROW_TYPE_CURMAILBOX = 2;
    /** Sub mailboxes */
    static final int ROW_TYPE_SUBMAILBOX = 3;

    /**
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
            MailboxColumns.MESSAGE_COUNT, ROW_TYPE_MAILBOX + " AS row_type",
            MailboxColumns.FLAGS, MailboxColumns.ACCOUNT_KEY };
    // STOPSHIP May need to adjust sub-folder projection depending upon final UX
    /**
     * Projection used to retrieve immediate children for a mailbox. The columns need to
     * be identical to those in {@link #PROJECTION}. We are only changing the constant
     * column <code>row_type</code>.
     */
    /*package*/ static final String[] SUBMAILBOX_PROJECTION = new String[] { MailboxColumns.ID,
        MailboxColumns.ID + " AS org_mailbox_id",
        MailboxColumns.DISPLAY_NAME, MailboxColumns.TYPE, MailboxColumns.UNREAD_COUNT,
        MailboxColumns.MESSAGE_COUNT, ROW_TYPE_SUBMAILBOX + " AS row_type",
        MailboxColumns.FLAGS, MailboxColumns.ACCOUNT_KEY };
    /*package*/ static final String[] CURMAILBOX_PROJECTION = new String[] { MailboxColumns.ID,
        MailboxColumns.ID + " AS org_mailbox_id",
        MailboxColumns.DISPLAY_NAME, MailboxColumns.TYPE, MailboxColumns.UNREAD_COUNT,
        MailboxColumns.MESSAGE_COUNT, ROW_TYPE_CURMAILBOX + " AS row_type",
        MailboxColumns.FLAGS, MailboxColumns.ACCOUNT_KEY };

    // Column 0 is only for ListView; we don't use it in our code.
    /**
     * ID for the current row.  Normally it's the ID for the current mailbox, but if it's an account
     * row on the combined view, it's the ID for the account.
     */
    /*package*/ static final int COLUMN_ID = 1;
    /*package*/ static final int COLUMN_DISPLAY_NAME = 2;
    /*package*/ static final int COLUMN_TYPE = 3;
    /*package*/ static final int COLUMN_UNREAD_COUNT = 4;
    /*package*/ static final int COLUMN_MESSAGE_COUNT = 5;
    /*package*/ static final int COLUMN_ROW_TYPE = 6;
    /*package*/ static final int COLUMN_FLAGS = 7;
    /**
     * ID for the owner account of the mailbox.  If it's a combined mailbox, it's
     * {@link Account#ACCOUNT_ID_COMBINED_VIEW}.  If it's an account row on the combined view,
     * it's the ID for the account.
     */
    /*package*/ static final int COLUMN_ACCOUNT_ID = 8;

    /** All mailboxes for the account */
    /*package*/ static final String ALL_MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?" +
            " AND " + Mailbox.USER_VISIBLE_MAILBOX_SELECTION;
    /** All mailboxes with the given parent */
    /*package*/ static final String MAILBOX_SELECTION_WITH_PARENT = ALL_MAILBOX_SELECTION +
            " AND " + MailboxColumns.PARENT_KEY + "=?";

    /*package*/ static final String MAILBOX_ORDER_BY = "CASE " + MailboxColumns.TYPE +
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

    /*package*/ static boolean sEnableUpdate = true;
    /*package*/ final LayoutInflater mInflater;
    /*package*/ final ResourceHelper mResourceHelper;
    /*package*/ final Callback mCallback;

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
    /*package*/ static boolean isAccountRow(Cursor c) {
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
    /*package*/ static int getCountTypeForMailboxType(Cursor c) {
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
     * Returns the ID of the mailbox (or account, if {@link #isAccountRow} is {@code true})
     * of the given row.
     */
    public long getId(int position) {
        Cursor c = (Cursor) getItem(position);
        return c.getLong(COLUMN_ID);
    }

    /** @see #COLUMN_ACCOUNT_ID */
    public long getAccountId(int position) {
        Cursor c = (Cursor) getItem(position);
        return c.getLong(COLUMN_ACCOUNT_ID);
    }

    /**
     * Turn on and off list updates; during a drag operation, we do NOT want to the list of
     * mailboxes to update, as this would be visually jarring
     * @param state whether or not the MailboxList can be updated
     */
    public static void enableUpdates(boolean state) {
        sEnableUpdate = state;
    }

    /*package*/ static String getDisplayName(Context context, Cursor cursor) {
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

    /*package*/ static long getIdForTest(Cursor cursor) {
        return cursor.getLong(COLUMN_ID);
    }

    /*package*/ static int getTypeForTest(Cursor cursor) {
        return cursor.getInt(COLUMN_TYPE);
    }

    /*package*/ static int getMessageCountForTest(Cursor cursor) {
        return cursor.getInt(COLUMN_MESSAGE_COUNT);
    }

    /*package*/ static int getUnreadCountForTest(Cursor cursor) {
        return cursor.getInt(COLUMN_UNREAD_COUNT);
    }

    @VisibleForTesting
    static long getAccountId(Cursor cursor) {
        return cursor.getLong(COLUMN_ACCOUNT_ID);
    }
}