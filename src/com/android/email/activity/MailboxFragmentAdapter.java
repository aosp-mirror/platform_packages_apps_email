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

import com.android.email.Email;
import com.android.email.FolderProperties;
import com.android.email.R;
import com.android.email.data.ClosingMatrixCursor;
import com.android.email.data.ThrottlingCursorLoader;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.database.MergeCursor;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

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
/*package*/ class MailboxFragmentAdapter extends MailboxesAdapter {
    private static final String MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?" +
            " AND " + MailboxColumns.ID + "=?";

    /**
     * {@link Cursor} with extra information which is returned by the loader created by
     * {@link MailboxFragmentAdapter#createMailboxesLoader}.
     */
    public static class CursorWithExtras extends CursorWrapper {
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

    public MailboxFragmentAdapter(Context context, Callback callback) {
        super(context, callback);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        if (view instanceof MailboxListItem) {
            bindListItem(view, context, cursor);
        } else {
            bindListHeader(view, context, cursor);
        }
    }
    private void bindListHeader(View view, Context context, Cursor cursor) {
        final TextView nameView = (TextView) view.findViewById(R.id.display_name);
        nameView.setText(getDisplayName(context, cursor));
    }
    private void bindListItem(View view, Context context, Cursor cursor) {
        final boolean isAccount = isAccountRow(cursor);
        final int type = cursor.getInt(COLUMN_TYPE);
        final long id = cursor.getLong(COLUMN_ID);
        final long accountId = cursor.getLong(COLUMN_ACCOUNT_ID);
        final int flags = cursor.getInt(COLUMN_FLAGS);
        final int rowType = cursor.getInt(COLUMN_ROW_TYPE);
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
        switch (getCountTypeForMailboxType(cursor)) {
            case COUNT_TYPE_UNREAD:
                count = cursor.getInt(COLUMN_UNREAD_COUNT);
                break;
            case COUNT_TYPE_TOTAL:
                count = cursor.getInt(COLUMN_MESSAGE_COUNT);
                break;
            default: // no count
                count = 0;
                break;
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

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        if (cursor.getInt(cursor.getColumnIndex(ROW_TYPE)) == ROW_TYPE_HEADER) {
            return mInflater.inflate(R.layout.mailbox_list_header, parent, false);
        }
        return mInflater.inflate(R.layout.mailbox_list_item, parent, false);
    }


    /**
     * Returns a cursor loader for the mailboxes of the given account.  If <code>parentKey</code>
     * refers to a valid mailbox ID [e.g. non-zero], restrict the loader to only those mailboxes
     * contained by this parent mailbox.
     *
     * Note the returned loader always returns a {@link CursorWithExtras}.
     */
    public static Loader<Cursor> createMailboxesLoader(Context context, long accountId,
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
    public static Loader<Cursor> createCombinedViewLoader(Context context) {
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

    private static void addCombinedMailboxRow(MatrixCursor cursor, long id, int mailboxType,
            int count, boolean showAlways) {
        if (id >= 0) {
            throw new IllegalArgumentException(); // Must be QUERY_ALL_*, which are all negative
        }
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
                    MAILBOX_SELECTION_WITH_PARENT,
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

            final Cursor childMailboxCursor = super.loadInBackground();
            final Cursor returnCursor;

            final int childCount = childMailboxCursor.getCount();

            if (mParentKey != Mailbox.NO_MAILBOX) {
                // If we're not showing the top level mailboxes, add the "parent" mailbox.
                final Cursor parentCursor = getContext().getContentResolver().query(
                        Mailbox.CONTENT_URI, CURMAILBOX_PROJECTION, MAILBOX_SELECTION,
                        new String[] { Long.toString(mAccountId), Long.toString(mParentKey) },
                        null);
                returnCursor = new MergeCursor(new Cursor[] { parentCursor, childMailboxCursor });
            } else {
                // TODO Add per-account starred mailbox support
                final MatrixCursor starredCursor = new MatrixCursor(MATRIX_PROJECTION);
                int accountStarredCount = Message.getFavoriteMessageCount(mContext, mAccountId);
                if (accountStarredCount > 0) {
                    // Only add "Starred", if there is at least one starred message
                    final int totalStarredCount = Message.getFavoriteMessageCount(mContext);
                    addCombinedMailboxRow(starredCursor, Mailbox.QUERY_ALL_FAVORITES,
                            Mailbox.TYPE_MAIL, totalStarredCount, true);
                }
                returnCursor = new MergeCursor(new Cursor[] { starredCursor, childMailboxCursor });
            }
            return new CursorWithExtras(Utility.CloseTraceCursorWrapper.get(returnCursor),
                    childCount);
        }
    }

    /**
     * Loader for mailboxes in "Combined view".
     */
    /*package*/ static class CombinedMailboxLoader extends ThrottlingCursorLoader {
        private static final String[] ACCOUNT_PROJECTION = new String[] {
                    EmailContent.RECORD_ID, AccountColumns.DISPLAY_NAME,
                };
        private static final int COLUMN_ACCOUND_ID = 0;
        private static final int COLUMN_ACCOUNT_DISPLAY_NAME = 1;

        private final Context mContext;

        public CombinedMailboxLoader(Context context) {
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
            return Utility.CloseTraceCursorWrapper.get(returnCursor);
        }

        /*package*/ static MatrixCursor buildCombinedMailboxes(Context context,
                Cursor innerCursor) {
            MatrixCursor cursor = new ClosingMatrixCursor(PROJECTION, innerCursor);
            // Combined inbox -- show unread count
            addCombinedMailboxRow(cursor, Mailbox.QUERY_ALL_INBOXES, Mailbox.TYPE_INBOX,
                    Mailbox.getUnreadCountByMailboxType(context, Mailbox.TYPE_INBOX), true);

            // Favorite (starred) -- show # of favorites
            addCombinedMailboxRow(cursor, Mailbox.QUERY_ALL_FAVORITES, Mailbox.TYPE_MAIL,
                    Message.getFavoriteMessageCount(context), false);

            // Drafts -- show # of drafts
            addCombinedMailboxRow(cursor, Mailbox.QUERY_ALL_DRAFTS, Mailbox.TYPE_DRAFTS,
                    Mailbox.getMessageCountByMailboxType(context, Mailbox.TYPE_DRAFTS), false);

            // Outbox -- # of outstanding messages
            addCombinedMailboxRow(cursor, Mailbox.QUERY_ALL_OUTBOX, Mailbox.TYPE_OUTBOX,
                    Mailbox.getMessageCountByMailboxType(context, Mailbox.TYPE_OUTBOX), false);

            return cursor;
        }
    }
}
