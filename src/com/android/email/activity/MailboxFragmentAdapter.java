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

package com.android.email.activity;

import android.content.ContentUris;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.database.MergeCursor;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.email.Email;
import com.android.email.FolderProperties;
import com.android.email.R;
import com.android.email.ResourceHelper;
import com.android.email.data.ClosingMatrixCursor;
import com.android.email.data.ThrottlingCursorLoader;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;

/**
 * Mailbox cursor adapter for the mailbox list fragment.
 *
 * A mailbox cursor may contain one of several different types of data. Currently, this
 * adapter supports the following views:
 * 1. The standard inbox, mailbox view
 * 2. The combined mailbox view
 * 3. Nested folder navigation
 *
 * TODO At a minimum, we should break out the loaders. They have no relation to the view code
 * and only serve to confuse the user.
 * TODO Determine if we actually need a separate adapter / view / loader for nested folder
 * navigation. It's a little convoluted at the moment, but, still manageable.
 */
class MailboxFragmentAdapter extends CursorAdapter {
    /**
     * Callback interface used to report clicks other than the basic list item click or long press.
     */
    interface Callback {
        /** Callback for setting background of mailbox list items during a drag */
        public void onBind(MailboxListItem listItem);
    }

    /** Do-nothing callback to avoid null tests for <code>mCallback</code>. */
    private static final class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onBind(MailboxListItem listItem) { }
    }

    /*
     * The type of the row to present to the user. There are 4 defined rows that each
     * have a slightly different look. These are typically used in the constant column
     * {@link #ROW_TYPE} specified in {@link #PROJECTION} and {@link #SUBMAILBOX_PROJECTION}.
     */
    /** Both regular and combined mailboxes */
    private static final int ROW_TYPE_MAILBOX = 0;
    /** Account "mailboxes" in the combined view */
    private static final int ROW_TYPE_ACCOUNT = 1;
    // The following types are used when drilling into a mailbox
    /** The current mailbox */
    private static final int ROW_TYPE_CURMAILBOX = 2;
    /** Sub mailboxes */
    private static final int ROW_TYPE_SUBMAILBOX = 3;
    /** Header */
    private static final int ROW_TYPE_HEADER = 4;

    /** The type of data contained in the cursor row. */
    private static final String ROW_TYPE = "rowType";
    /** The original ID of the cursor row. May be negative. */
    private static final String ORIGINAL_ID = "orgMailboxId";
    /**
     * Projection for a typical mailbox or account row.
     * <p><em>NOTE</em> This projection contains two ID columns. The first, named "_id", is used
     * by the framework ListView implementation. Since ListView does not handle negative IDs in
     * this column, we define a "mailbox_id" column that contains the real mailbox ID; which
     * may be negative for special mailboxes.
     */
    private static final String[] PROJECTION = new String[] { MailboxColumns.ID,
            MailboxColumns.ID + " AS " + ORIGINAL_ID,
            MailboxColumns.DISPLAY_NAME, MailboxColumns.TYPE, MailboxColumns.UNREAD_COUNT,
            MailboxColumns.MESSAGE_COUNT, ROW_TYPE_MAILBOX + " AS " + ROW_TYPE,
            MailboxColumns.FLAGS, MailboxColumns.ACCOUNT_KEY };
    /**
     * Projection used to retrieve immediate children for a mailbox. The columns need to
     * be identical to those in {@link #PROJECTION}. We are only changing the constant
     * column {@link #ROW_TYPE}.
     */
    private static final String[] SUBMAILBOX_PROJECTION = new String[] { MailboxColumns.ID,
        MailboxColumns.ID + " AS " + ORIGINAL_ID,
        MailboxColumns.DISPLAY_NAME, MailboxColumns.TYPE, MailboxColumns.UNREAD_COUNT,
        MailboxColumns.MESSAGE_COUNT, ROW_TYPE_SUBMAILBOX + " AS " + ROW_TYPE,
        MailboxColumns.FLAGS, MailboxColumns.ACCOUNT_KEY };
    private static final String[] CURMAILBOX_PROJECTION = new String[] { MailboxColumns.ID,
        MailboxColumns.ID + " AS " + ORIGINAL_ID,
        MailboxColumns.DISPLAY_NAME, MailboxColumns.TYPE, MailboxColumns.UNREAD_COUNT,
        MailboxColumns.MESSAGE_COUNT, ROW_TYPE_CURMAILBOX + " AS " + ROW_TYPE,
        MailboxColumns.FLAGS, MailboxColumns.ACCOUNT_KEY };
    /** Project to use for matrix cursors; rows MUST be identical to {@link #PROJECTION} */
    private static final String[] MATRIX_PROJECTION = new String[] {
        MailboxColumns.ID, ORIGINAL_ID, MailboxColumns.DISPLAY_NAME, MailboxColumns.TYPE,
        MailboxColumns.UNREAD_COUNT, MailboxColumns.MESSAGE_COUNT, ROW_TYPE, MailboxColumns.FLAGS,
        MailboxColumns.ACCOUNT_KEY };

    /** All mailboxes for the account */
    private static final String ALL_MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?" +
            " AND " + Mailbox.USER_VISIBLE_MAILBOX_SELECTION;
    /** All system mailboxes for an account */
    private static final String SYSTEM_MAILBOX_SELECTION = ALL_MAILBOX_SELECTION
            + " AND " + MailboxColumns.TYPE + "!=" + Mailbox.TYPE_MAIL;
    /** All mailboxes with the given parent */
    private static final String USER_MAILBOX_SELECTION_WITH_PARENT = ALL_MAILBOX_SELECTION
            + " AND " + MailboxColumns.PARENT_KEY + "=?"
            + " AND " + MailboxColumns.TYPE + "=" + Mailbox.TYPE_MAIL;
    /** Selection for a specific mailbox */
    private static final String MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?"
            + " AND " + MailboxColumns.ID + "=?";

    private static final String MAILBOX_ORDER_BY = "CASE " + MailboxColumns.TYPE
            + " WHEN " + Mailbox.TYPE_INBOX   + " THEN 0"
            + " WHEN " + Mailbox.TYPE_DRAFTS  + " THEN 1"
            + " WHEN " + Mailbox.TYPE_OUTBOX  + " THEN 2"
            + " WHEN " + Mailbox.TYPE_SENT    + " THEN 3"
            + " WHEN " + Mailbox.TYPE_TRASH   + " THEN 4"
            + " WHEN " + Mailbox.TYPE_JUNK    + " THEN 5"
            // Other mailboxes (i.e. of Mailbox.TYPE_MAIL) are shown in alphabetical order.
            + " ELSE 10 END"
            + " ," + MailboxColumns.DISPLAY_NAME;

    /** View is of a "normal" row */
    private static final int ITEM_VIEW_TYPE_NORMAL = 0;
    /** View is of a separator row */
    private static final int ITEM_VIEW_TYPE_HEADER = AdapterView.ITEM_VIEW_TYPE_HEADER_OR_FOOTER;

    private static boolean sEnableUpdate = true;
    private final LayoutInflater mInflater;
    private final ResourceHelper mResourceHelper;
    private final Callback mCallback;

    public MailboxFragmentAdapter(Context context, Callback callback) {
        super(context, null, 0 /* flags; no content observer */);
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
        mResourceHelper = ResourceHelper.getInstance(context);
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return isHeader(position) ? ITEM_VIEW_TYPE_HEADER : ITEM_VIEW_TYPE_NORMAL;
    }

    @Override
    public boolean isEnabled(int position) {
        return !isHeader(position);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        if (view instanceof MailboxListItem) {
            bindListItem(view, context, cursor);
        } else {
            bindListHeader(view, context, cursor);
        }
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        if (cursor.getInt(cursor.getColumnIndex(ROW_TYPE)) == ROW_TYPE_HEADER) {
            return mInflater.inflate(R.layout.mailbox_list_header, parent, false);
        }
        return mInflater.inflate(R.layout.mailbox_list_item, parent, false);
    }

    private boolean isHeader(int position) {
        Cursor c = getCursor();
        if ((c == null) || c.isClosed()) {
            return false;
        }
        c.moveToPosition(position);
        int rowType = c.getInt(c.getColumnIndex(ROW_TYPE));
        return rowType == ROW_TYPE_HEADER;
    }

    /** Returns {@code true} if the specified row is of an account in the combined view. */
    boolean isAccountRow(int position) {
        return isAccountRow((Cursor) getItem(position));
    }

    /**
     * Returns {@code true} if the specified row is a mailbox.
     * ({@link #ROW_TYPE_MAILBOX}, {@link #ROW_TYPE_CURMAILBOX} and {@link #ROW_TYPE_SUBMAILBOX})
     */
    boolean isMailboxRow(int position) {
        return isMailboxRow((Cursor) getItem(position));
    }

    /** Returns {@code true} if the current row is of an account in the combined view. */
    private static boolean isAccountRow(Cursor cursor) {
        return getRowType(cursor) == ROW_TYPE_ACCOUNT;
    }

    /** Returns {@code true} if the current row is a header */
    private static boolean isHeaderRow(Cursor cursor) {
        return getRowType(cursor) == ROW_TYPE_HEADER;
    }

    /**
     * Returns {@code true} if the current row is a mailbox.
     * ({@link #ROW_TYPE_MAILBOX}, {@link #ROW_TYPE_CURMAILBOX} and {@link #ROW_TYPE_SUBMAILBOX})
     */
    private static boolean isMailboxRow(Cursor cursor) {
        return !(isAccountRow(cursor) || isHeaderRow(cursor));
    }

    /**
     * Returns the ID of the given row. It may be a mailbox or account ID depending upon the
     * result of {@link #isAccountRow}.
     */
    long getId(int position) {
        Cursor c = (Cursor) getItem(position);
        return getId(c);
    }

    /**
     * Returns the account ID of the mailbox owner for the given row. If the given row is a
     * combined mailbox, {@link Account#ACCOUNT_ID_COMBINED_VIEW} is returned. If the given
     * row is an account, returns the account's ID [the same as {@link #ORIGINAL_ID}].
     */
    long getAccountId(int position) {
        Cursor c = (Cursor) getItem(position);
        return getAccountId(c);
    }

    /**
     * Turn on and off list updates; during a drag operation, we do NOT want to the list of
     * mailboxes to update, as this would be visually jarring
     * @param state whether or not the MailboxList can be updated
     */
    static void enableUpdates(boolean state) {
        sEnableUpdate = state;
    }

    private static String getDisplayName(Context context, Cursor cursor) {
        final String name = cursor.getString(cursor.getColumnIndex(MailboxColumns.DISPLAY_NAME));
        if (isHeaderRow(cursor) || isAccountRow(cursor)) {
            // Always use actual name
            return name;
        } else {
            // Use this method for two purposes:
            // - Set combined mailbox names
            // - Rewrite special mailbox names (e.g. trash)
            FolderProperties fp = FolderProperties.getInstance(context);
            return fp.getDisplayName(getType(cursor), getId(cursor), name);
        }
    }

    static long getId(Cursor cursor) {
        return cursor.getLong(cursor.getColumnIndex(ORIGINAL_ID));
    }

    static int getType(Cursor cursor) {
        return cursor.getInt(cursor.getColumnIndex(MailboxColumns.TYPE));
    }

    static int getMessageCount(Cursor cursor) {
        return cursor.getInt(cursor.getColumnIndex(MailboxColumns.MESSAGE_COUNT));
    }

    static int getUnreadCount(Cursor cursor) {
        return cursor.getInt(cursor.getColumnIndex(MailboxColumns.UNREAD_COUNT));
    }

    static long getAccountId(Cursor cursor) {
        return cursor.getLong(cursor.getColumnIndex(MailboxColumns.ACCOUNT_KEY));
    }

    private static int getRowType(Cursor cursor) {
        return cursor.getInt(cursor.getColumnIndex(ROW_TYPE));
    }

    private static int getFlags(Cursor cursor) {
        return cursor.getInt(cursor.getColumnIndex(MailboxColumns.FLAGS));
    }

    /**
     * {@link Cursor} with extra information which is returned by the loader created by
     * {@link MailboxFragmentAdapter#createMailboxesLoader}.
     */
    static class CursorWithExtras extends CursorWrapper {
        /**
         * The number of mailboxes in the cursor if the cursor contains top-level mailboxes.
         * Otherwise, the number of *child* mailboxes.
         */
        public final int mChildCount;

        CursorWithExtras(Cursor cursor, int childCount) {
            super(cursor);
            mChildCount = childCount;
        }
    }

    private void bindListHeader(View view, Context context, Cursor cursor) {
        final TextView nameView = (TextView) view.findViewById(R.id.display_name);
        nameView.setText(getDisplayName(context, cursor));
    }

    private void bindListItem(View view, Context context, Cursor cursor) {
        final boolean isAccount = isAccountRow(cursor);
        final int type = getType(cursor);
        final long id = getId(cursor);
        final long accountId = getAccountId(cursor);
        final int flags = getFlags(cursor);
        final int rowType = getRowType(cursor);
        final boolean hasVisibleChildren = (flags & Mailbox.FLAG_HAS_CHILDREN) != 0
                && (flags & Mailbox.FLAG_CHILDREN_VISIBLE) != 0;

        MailboxListItem listItem = (MailboxListItem)view;
        listItem.mMailboxId = isAccountRow(cursor) ? Mailbox.NO_MAILBOX : id;
        listItem.mMailboxType = type;
        listItem.mAccountId = accountId;
        listItem.mIsValidDropTarget = (id >= 0)
                && !Utility.arrayContains(Mailbox.INVALID_DROP_TARGETS, type)
                && (flags & Mailbox.FLAG_ACCEPTS_MOVED_MAIL) != 0;
        listItem.mIsNavigable = hasVisibleChildren;

        listItem.mAdapter = this;
        // Set the background depending on whether we're in drag mode, the mailbox is a valid
        // target, etc.
        mCallback.onBind(listItem);

        // Set mailbox name
        final TextView nameView = (TextView) view.findViewById(R.id.mailbox_name);
        nameView.setText(getDisplayName(context, cursor));
        // Set count
        final int count;
        if (isAccountRow(cursor)) {
            count = getUnreadCount(cursor);
        } else {
            FolderProperties fp = FolderProperties.getInstance(context);
            count = fp.getMessageCount(type, getUnreadCount(cursor), getMessageCount(cursor));
        }
        final TextView countView = (TextView) view.findViewById(R.id.message_count);

        // Set folder icon
        final ImageView folderIcon = (ImageView) view.findViewById(R.id.folder_icon);
        folderIcon.setImageDrawable(
                FolderProperties.getInstance(context).getIcon(type, id, flags));

        final ImageView mailboxExpandedIcon =
                (ImageView) view.findViewById(R.id.folder_expanded_icon);
        switch (rowType) {
            case ROW_TYPE_SUBMAILBOX:
                if (hasVisibleChildren) {
                    mailboxExpandedIcon.setVisibility(View.VISIBLE);
                    mailboxExpandedIcon.setImageResource(
                            R.drawable.ic_mailbox_collapsed_holo_light);
                } else {
                    mailboxExpandedIcon.setVisibility(View.INVISIBLE);
                    mailboxExpandedIcon.setImageDrawable(null);
                }
                folderIcon.setVisibility(View.INVISIBLE);
                break;
            case ROW_TYPE_CURMAILBOX:
                mailboxExpandedIcon.setVisibility(View.GONE);
                mailboxExpandedIcon.setImageDrawable(null);
                folderIcon.setVisibility(View.GONE);
                break;
            case ROW_TYPE_MAILBOX:
            default: // Includes ROW_TYPE_ACCOUNT
                if (hasVisibleChildren) {
                    mailboxExpandedIcon.setVisibility(View.VISIBLE);
                    mailboxExpandedIcon.setImageResource(
                            R.drawable.ic_mailbox_collapsed_holo_light);
                } else {
                    mailboxExpandedIcon.setVisibility(View.GONE);
                    mailboxExpandedIcon.setImageDrawable(null);
                }
                folderIcon.setVisibility(View.VISIBLE);
                break;
        }

        // If the unread count is zero, not to show countView.
        if (count > 0) {
            countView.setVisibility(View.VISIBLE);
            countView.setText(Integer.toString(count));
        } else {
            countView.setVisibility(View.GONE);
        }

        final View chipView = view.findViewById(R.id.color_chip);
        if (isAccount) {
            chipView.setVisibility(View.VISIBLE);
            chipView.setBackgroundColor(mResourceHelper.getAccountColor(id));
        } else {
            chipView.setVisibility(View.GONE);
        }
    }

    /**
     * Returns a cursor loader for the mailboxes of the given account.  If <code>parentKey</code>
     * refers to a valid mailbox ID [e.g. non-zero], restrict the loader to only those mailboxes
     * contained by this parent mailbox.
     *
     * Note the returned loader always returns a {@link CursorWithExtras}.
     */
    static Loader<Cursor> createMailboxesLoader(Context context, long accountId,
            long parentMailboxId) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxFragmentAdapter#CursorWithExtras accountId=" + accountId
                    + " parentMailboxId=" + parentMailboxId);
        }
        if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
            throw new IllegalArgumentException();
        }
        return new MailboxFragmentLoader(context, accountId, parentMailboxId);
    }

    /**
     * Returns a cursor loader for the combined view.
     */
    static Loader<Cursor> createCombinedViewLoader(Context context) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxFragmentAdapter#createCombinedViewLoader");
        }
        return new CombinedMailboxLoader(context);
    }

    /**
     * Adds a new row into the given cursor.
     */
    private static void addMailboxRow(MatrixCursor cursor, long mailboxId, String displayName,
            int mailboxType, int unreadCount, int messageCount, int rowType, int flags,
            long accountId) {
        long listId = mailboxId;
        if (mailboxId < 0) {
            listId = Long.MAX_VALUE + mailboxId; // IDs for the list view must be positive
        }
        RowBuilder row = cursor.newRow();
        row.add(listId);
        row.add(mailboxId);
        row.add(displayName);
        row.add(mailboxType);
        row.add(unreadCount);
        row.add(messageCount);
        row.add(rowType);
        row.add(flags);
        row.add(accountId);
    }

    private static void addCombinedMailboxRow(Context context, MatrixCursor cursor, long id,
            int mailboxType, boolean showAlways) {
        if (id >= 0) {
            throw new IllegalArgumentException(); // Must be QUERY_ALL_*, which are all negative
        }
        int count = FolderProperties.getMessageCountForCombinedMailbox(context, id);
        if (showAlways || (count > 0)) {
            addMailboxRow(
                    cursor, id, "", mailboxType, count, count, ROW_TYPE_MAILBOX, Mailbox.FLAG_NONE,
                    Account.ACCOUNT_ID_COMBINED_VIEW);
        }
    }

    /**
     * Loads mailboxes that are the children of a given mailbox ID.
     *
     * The returned {@link Cursor} is always a {@link CursorWithExtras}.
     */
    private static class MailboxFragmentLoader extends ThrottlingCursorLoader {
        private final Context mContext;
        private final long mAccountId;
        private final long mParentKey;

        MailboxFragmentLoader(Context context, long accountId, long parentKey) {
            super(context, Mailbox.CONTENT_URI,
                    (parentKey != Mailbox.NO_MAILBOX)
                            ? SUBMAILBOX_PROJECTION
                            : PROJECTION,
                    USER_MAILBOX_SELECTION_WITH_PARENT,
                    new String[] { Long.toString(accountId), Long.toString(parentKey) },
                    MAILBOX_ORDER_BY);
            mContext = context;
            mAccountId = accountId;
            mParentKey = parentKey;
        }

        @Override
        public void onContentChanged() {
            if (sEnableUpdate) {
                super.onContentChanged();
            }
        }

        @Override
        public Cursor loadInBackground() {
            boolean parentRemoved = false;

            final Cursor userMailboxCursor = super.loadInBackground();
            final Cursor returnCursor;

            final int childCount = userMailboxCursor.getCount();

            if (mParentKey != Mailbox.NO_MAILBOX) {
                // If we're not showing the top level mailboxes, add the "parent" mailbox.
                final Cursor parentCursor = getContext().getContentResolver().query(
                        Mailbox.CONTENT_URI, CURMAILBOX_PROJECTION, MAILBOX_SELECTION,
                        new String[] { Long.toString(mAccountId), Long.toString(mParentKey) },
                        null);
                returnCursor = new MergeCursor(new Cursor[] { parentCursor, userMailboxCursor });
            } else {
                // TODO Add per-account starred mailbox support
                final MatrixCursor starredCursor = new MatrixCursor(MATRIX_PROJECTION);
                final Cursor systemMailboxCursor = mContext.getContentResolver().query(
                        Mailbox.CONTENT_URI, PROJECTION, SYSTEM_MAILBOX_SELECTION,
                        new String[] { Long.toString(mAccountId) }, MAILBOX_ORDER_BY);
                final MatrixCursor recentCursor = new MatrixCursor(MATRIX_PROJECTION);
                final MatrixCursor headerCursor = new MatrixCursor(MATRIX_PROJECTION);
                if (childCount > 0) {
                    final String name = mContext.getString(R.string.mailbox_list_user_mailboxes);
                    addMailboxRow(headerCursor, 0L, name, 0, 0, 0, ROW_TYPE_HEADER, 0, 0L);
                }
                ArrayList<Long> recentList = null;
                boolean useTwoPane = UiUtilities.useTwoPane(mContext);
                if (useTwoPane) {
                    recentList = RecentMailboxManager.getInstance(mContext)
                            .getMostRecent(mAccountId, true);
                }
                if (recentList != null && recentList.size() > 0) {
                    final String name = mContext.getString(R.string.mailbox_list_recent_mailboxes);
                    addMailboxRow(recentCursor, 0L, name, 0, 0, 0, ROW_TYPE_HEADER, 0, 0L);
                    for (long mailboxId : recentList) {
                        final Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, mailboxId);
                        if (mailbox == null) continue;
                        final int messageCount = Utility.getFirstRowInt(mContext,
                            ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                            new String[] { MailboxColumns.MESSAGE_COUNT }, null, null, null, 0);
                        final int unreadCount = Utility.getFirstRowInt(mContext,
                            ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                            new String[] { MailboxColumns.UNREAD_COUNT }, null, null, null, 0);
                        addMailboxRow(recentCursor, mailboxId, mailbox.mDisplayName, mailbox.mType,
                            unreadCount, messageCount, ROW_TYPE_MAILBOX, mailbox.mFlags,
                            mailbox.mAccountKey);
                    }
                }
                int accountStarredCount = Message.getFavoriteMessageCount(mContext, mAccountId);
                if (accountStarredCount > 0) {
                    // Only add "Starred", if there is at least one starred message
                    addCombinedMailboxRow(mContext, starredCursor, Mailbox.QUERY_ALL_FAVORITES,
                            Mailbox.TYPE_MAIL, true);
                }
                returnCursor = new MergeCursor(new Cursor[] {
                        starredCursor, systemMailboxCursor, recentCursor, headerCursor,
                        userMailboxCursor, });
            }
            return new CursorWithExtras(returnCursor, childCount);
        }
    }

    /**
     * Loader for mailboxes in "Combined view".
     */
    @VisibleForTesting
    static class CombinedMailboxLoader extends ThrottlingCursorLoader {
        private static final String[] ACCOUNT_PROJECTION = new String[] {
            EmailContent.RECORD_ID, AccountColumns.DISPLAY_NAME,
        };
        private static final int COLUMN_ACCOUND_ID = 0;
        private static final int COLUMN_ACCOUNT_DISPLAY_NAME = 1;

        private final Context mContext;

        private CombinedMailboxLoader(Context context) {
            super(context, Account.CONTENT_URI, ACCOUNT_PROJECTION, null, null, null);
            mContext = context;
        }

        @Override
        public Cursor loadInBackground() {
            final Cursor accounts = super.loadInBackground();

            // Build combined mailbox rows.
            final MatrixCursor returnCursor = buildCombinedMailboxes(mContext, accounts);

            // Add account rows.
            accounts.moveToPosition(-1);
            while (accounts.moveToNext()) {
                final long accountId = accounts.getLong(COLUMN_ACCOUND_ID);
                final String accountName = accounts.getString(COLUMN_ACCOUNT_DISPLAY_NAME);
                final int unreadCount = Mailbox.getUnreadCountByAccountAndMailboxType(
                        mContext, accountId, Mailbox.TYPE_INBOX);
                addMailboxRow(returnCursor, accountId, accountName, Mailbox.TYPE_NONE,
                        unreadCount, unreadCount, ROW_TYPE_ACCOUNT, Mailbox.FLAG_NONE,
                        accountId);
            }
            return returnCursor;
        }

        @VisibleForTesting
        static MatrixCursor buildCombinedMailboxes(Context c, Cursor innerCursor) {
            MatrixCursor cursor = new ClosingMatrixCursor(MATRIX_PROJECTION, innerCursor);
            // Combined inbox -- show unread count
            addCombinedMailboxRow(c, cursor, Mailbox.QUERY_ALL_INBOXES, Mailbox.TYPE_INBOX, true);

            // Favorite (starred) -- show # of favorites
            addCombinedMailboxRow(c, cursor, Mailbox.QUERY_ALL_FAVORITES, Mailbox.TYPE_MAIL, false);

            // Drafts -- show # of drafts
            addCombinedMailboxRow(c, cursor, Mailbox.QUERY_ALL_DRAFTS, Mailbox.TYPE_DRAFTS, false);

            // Outbox -- # of outstanding messages
            addCombinedMailboxRow(c, cursor, Mailbox.QUERY_ALL_OUTBOX, Mailbox.TYPE_OUTBOX, false);

            return cursor;
        }
    }
}
