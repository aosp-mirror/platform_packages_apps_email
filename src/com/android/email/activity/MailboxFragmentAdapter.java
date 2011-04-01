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
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.utility.Utility;

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.database.MergeCursor;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Cursor adapter for a fragment mailbox list.
 */
/*package*/ class MailboxFragmentAdapter extends MailboxesAdapter {
    public MailboxFragmentAdapter(Context context, Callback callback) {
        super(context, callback);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        final boolean isAccount = isAccountRow(cursor);
        final int type = cursor.getInt(COLUMN_TYPE);
        final long id = cursor.getLong(COLUMN_ID);

        MailboxListItem listItem = (MailboxListItem)view;
        listItem.mMailboxType = type;
        listItem.mMailboxId = id;
        listItem.mAdapter = this;

        // Set the background depending on whether we're in drag mode, the mailbox is a valid
        // target, etc.
        mCallback.onSetDropTargetBackground(listItem);

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

        // If the unread count is zero, not to show countView.
        if (count > 0) {
            countView.setVisibility(View.VISIBLE);
            countView.setText(Integer.toString(count));
        } else {
            countView.setVisibility(View.GONE);
        }

        // Set folder icon
        ((ImageView) view.findViewById(R.id.folder_icon)).setImageDrawable(
                FolderProperties.getInstance(context).getIcon(type, id));

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
        return mInflater.inflate(R.layout.mailbox_list_item, parent, false);
    }

    /**
     * @return mailboxes Loader for an account.
     */
    public static Loader<Cursor> createLoader(Context context, long accountId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxFragmentAdapter#createLoader accountId=" + accountId);
        }
        if (accountId != Account.ACCOUNT_ID_COMBINED_VIEW) {
            return new MailboxFragmentLoader(context, accountId);
        } else {
            return new CombinedMailboxLoader(context);
        }
    }

    /**
     * Adds a new row into the specified cursor.
     */
    private static void addSummaryMailboxRow(MatrixCursor cursor, long id, int type, int count,
            boolean showAlways) {
        if (id >= 0) {
            throw new IllegalArgumentException(); // Must be QUERY_ALL_*, which are all negative.
        }
        if (showAlways || (count > 0)) {
            RowBuilder row = cursor.newRow();
            row.add(Long.MAX_VALUE + id); // Map QUERY_ALL_* constants to positive ints.
            row.add(id); // The real mailbox ID.
            row.add(""); // Display name.  We get it from FolderProperties.
            row.add(type);
            row.add(count);
            row.add(count);
            row.add(ROW_TYPE_MAILBOX);
        }
    }

    /**
     * Loader for mailboxes of an account.
     */
    private static class MailboxFragmentLoader extends ThrottlingCursorLoader {
        private final Context mContext;
        private final long mAccountId;

        public MailboxFragmentLoader(Context context, long accountId) {
            super(context, EmailContent.Mailbox.CONTENT_URI,
                    MailboxesAdapter.PROJECTION, MAILBOX_SELECTION,
                    new String[] { String.valueOf(accountId) }, MAILBOX_ORDER_BY);
            mContext = context;
            mAccountId = accountId;
        }

        @Override
        public void onContentChanged() {
            if (sEnableUpdate) {
                super.onContentChanged();
            }
        }

        @Override
        public Cursor loadInBackground() {
            final Cursor mailboxesCursor = super.loadInBackground();

            // Add "Starred", only if the account has at least one starred message.
            // TODO It's currently "combined starred", but the plan is to make it per-account
            // starred.
            final int accountStarredCount = Message.getFavoriteMessageCount(mContext, mAccountId);
            if (accountStarredCount == 0) {
                return Utility.CloseTraceCursorWrapper.get(mailboxesCursor); // no starred message
            }

            final MatrixCursor starredCursor = new MatrixCursor(getProjection());

            final int totalStarredCount = Message.getFavoriteMessageCount(mContext);
            addSummaryMailboxRow(starredCursor, Mailbox.QUERY_ALL_FAVORITES, Mailbox.TYPE_MAIL,
                    totalStarredCount, true);

            return Utility.CloseTraceCursorWrapper.get(
                    new MergeCursor(new Cursor[] {starredCursor, mailboxesCursor}));
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
            final MatrixCursor combinedWithAccounts = getCursor(mContext, accounts);

            accounts.moveToPosition(-1);
            while (accounts.moveToNext()) {
                RowBuilder row =  combinedWithAccounts.newRow();
                final long accountId = accounts.getLong(COLUMN_ACCOUND_ID);
                row.add(accountId);
                row.add(accountId);
                row.add(accounts.getString(COLUMN_ACCOUNT_DISPLAY_NAME));
                row.add(-1); // No mailbox type.  Shouldn't really be used.
                final int unreadCount = Mailbox.getUnreadCountByAccountAndMailboxType(
                        mContext, accountId, Mailbox.TYPE_INBOX);
                row.add(unreadCount);
                row.add(unreadCount);
                row.add(ROW_TYPE_ACCOUNT);
            }
            return Utility.CloseTraceCursorWrapper.get(combinedWithAccounts);
        }

        /*package*/ static MatrixCursor getCursor(Context context,
                Cursor innerCursor) {
            MatrixCursor cursor = new ClosingMatrixCursor(PROJECTION, innerCursor);
            // Combined inbox -- show unread count
            addSummaryMailboxRow(cursor, Mailbox.QUERY_ALL_INBOXES, Mailbox.TYPE_INBOX,
                    Mailbox.getUnreadCountByMailboxType(context, Mailbox.TYPE_INBOX), true);

            // Favorite (starred) -- show # of favorites
            addSummaryMailboxRow(cursor, Mailbox.QUERY_ALL_FAVORITES, Mailbox.TYPE_MAIL,
                    Message.getFavoriteMessageCount(context), false);

            // Drafts -- show # of drafts
            addSummaryMailboxRow(cursor, Mailbox.QUERY_ALL_DRAFTS, Mailbox.TYPE_DRAFTS,
                    Mailbox.getMessageCountByMailboxType(context, Mailbox.TYPE_DRAFTS), false);

            // Outbox -- # of outstanding messages
            addSummaryMailboxRow(cursor, Mailbox.QUERY_ALL_OUTBOX, Mailbox.TYPE_OUTBOX,
                    Mailbox.getMessageCountByMailboxType(context, Mailbox.TYPE_OUTBOX), false);

            return cursor;
        }
    }
}
