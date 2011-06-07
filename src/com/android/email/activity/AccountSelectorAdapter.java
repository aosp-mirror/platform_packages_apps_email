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

import com.google.common.annotations.VisibleForTesting;

import com.android.email.R;
import com.android.email.data.ClosingMatrixCursor;
import com.android.email.data.ThrottlingCursorLoader;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;

import java.util.ArrayList;

import android.content.ContentUris;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.TextView;

/**
 * Account selector spinner.
 *
 * TODO Test it!
 */
public class AccountSelectorAdapter extends CursorAdapter {
    /** meta data column for an account's unread count */
    private static final String UNREAD_COUNT = "unreadCount";
    /** meta data column for the row type; used for display purposes */
    private static final String ROW_TYPE = "rowType";
    /** meta data position of the currently selected account in the drop-down list */
    private static final String ACCOUNT_POSITION = "accountPosition";
    private static final int ROW_TYPE_HEADER = AdapterView.ITEM_VIEW_TYPE_HEADER_OR_FOOTER;
    @SuppressWarnings("unused")
    private static final int ROW_TYPE_MAILBOX = 0;
    private static final int ROW_TYPE_ACCOUNT = 1;
    private static final int ITEM_VIEW_TYPE_ACCOUNT = 0;
    static final int UNKNOWN_POSITION = -1;
    /** Projection for account database query */
    private static final String[] ACCOUNT_PROJECTION = new String[] {
        Account.ID,
        Account.DISPLAY_NAME,
        Account.EMAIL_ADDRESS,
    };
    /**
     * Projection used for the selector display; we add meta data that doesn't exist in the
     * account database, so, this should be a super-set of {@link #ACCOUNT_PROJECTION}.
     */
    private static final String[] ADAPTER_PROJECTION = new String[] {
        ROW_TYPE,
        Account.ID,
        Account.DISPLAY_NAME,
        Account.EMAIL_ADDRESS,
        UNREAD_COUNT,
        ACCOUNT_POSITION,
    };

    /** Mailbox types for default "recent mailbox" entries if none exist */
    private static final int[] DEFAULT_RECENT_TYPES = new int[] {
        Mailbox.TYPE_DRAFTS,
        Mailbox.TYPE_SENT,
    };

    /** Sort order.  Show the default account first. */
    private static final String ORDER_BY =
            EmailContent.Account.IS_DEFAULT + " desc, " + EmailContent.Account.RECORD_ID;

    private final LayoutInflater mInflater;
    @SuppressWarnings("hiding")
    private final Context mContext;

    /**
     * Returns a loader that can populate the account spinner.
     * @param context a context
     * @param accountId the ID of the currently viewed account
     */
    public static Loader<Cursor> createLoader(Context context, long accountId) {
        return new AccountsLoader(context, accountId);
    }

    public AccountSelectorAdapter(Context context) {
        super(context, null, 0 /* no auto-requery */);
        mContext = context;
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    /**
     * Invoked when the action bar needs the view of the text in the bar itself. The default
     * is to show just the display name of the cursor at the given position.
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (!isAccountItem(position)) {
            // asked to show a recent mailbox; instead, show the account associated w/ the mailbox
            int newPosition = getAccountPosition(position);
            if (newPosition != UNKNOWN_POSITION) {
                position = newPosition;
            }
        }
        return super.getView(position, convertView, parent);
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        Cursor c = getCursor();
        c.moveToPosition(position);

        View view;
        if (c.getInt(c.getColumnIndex(ROW_TYPE)) == ROW_TYPE_HEADER) {
            view = mInflater.inflate(R.layout.account_selector_dropdown_header, parent, false);
            final TextView displayNameView = (TextView) view.findViewById(R.id.display_name);
            final String displayName = getAccountDisplayName(c);
            displayNameView.setText(displayName);
        } else {
            view = mInflater.inflate(R.layout.account_selector_dropdown, parent, false);
            final TextView displayNameView = (TextView) view.findViewById(R.id.display_name);
            final TextView emailAddressView = (TextView) view.findViewById(R.id.email_address);
            final TextView unreadCountView = (TextView) view.findViewById(R.id.unread_count);

            final String displayName = getAccountDisplayName(position);
            final String emailAddress = getAccountEmailAddress(position);

            displayNameView.setText(displayName);

            // Show the email address only when it's different from the display name.
            if (displayName.equals(emailAddress)) {
                emailAddressView.setVisibility(View.GONE);
            } else {
                emailAddressView.setVisibility(View.VISIBLE);
                emailAddressView.setText(emailAddress);
            }

            unreadCountView.setText(UiUtilities.getMessageCountForUi(mContext,
                    getAccountUnreadCount(position), false));
        }
        return view;
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        TextView textView = (TextView) view.findViewById(R.id.display_name);
        textView.setText(getAccountDisplayName(cursor));
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(R.layout.account_selector, parent, false);
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        Cursor c = getCursor();
        c.moveToPosition(position);
        return c.getLong(c.getColumnIndex(ROW_TYPE)) == ROW_TYPE_HEADER
                ? AdapterView.ITEM_VIEW_TYPE_HEADER_OR_FOOTER
                : ITEM_VIEW_TYPE_ACCOUNT;
    }

    @Override
    public boolean isEnabled(int position) {
        return (getItemViewType(position) != AdapterView.ITEM_VIEW_TYPE_HEADER_OR_FOOTER);
    }

    public boolean isAccountItem(int position) {
        Cursor c = getCursor();
        c.moveToPosition(position);
        return (c.getLong(c.getColumnIndex(ROW_TYPE)) == ROW_TYPE_ACCOUNT);
    }

    private String getAccountDisplayName(int position) {
        final Cursor c = getCursor();
        return c.moveToPosition(position) ? getAccountDisplayName(c) : null;
    }

    private String getAccountEmailAddress(int position) {
        final Cursor c = getCursor();
        return c.moveToPosition(position) ? getAccountEmailAddress(c) : null;
    }

    private int getAccountUnreadCount(int position) {
        final Cursor c = getCursor();
        return c.moveToPosition(position) ? getAccountUnreadCount(c) : 0;
    }

    private int getAccountPosition(int position) {
        final Cursor c = getCursor();
        return c.moveToPosition(position) ? getAccountPosition(c) : UNKNOWN_POSITION;
    }

    /** Returns the account ID extracted from the given cursor. */
    private static long getAccountId(Cursor c) {
        return c.getLong(c.getColumnIndex(Account.ID));
    }

    /** Returns the account name extracted from the given cursor. */
    static String getAccountDisplayName(Cursor cursor) {
        return cursor.getString(cursor.getColumnIndex(Account.DISPLAY_NAME));
    }

    /** Returns the email address extracted from the given cursor. */
    private static String getAccountEmailAddress(Cursor cursor) {
        return cursor.getString(cursor.getColumnIndex(Account.EMAIL_ADDRESS));
    }

    /** Returns the unread count extracted from the given cursor. */
    private static int getAccountUnreadCount(Cursor cursor) {
        return cursor.getInt(cursor.getColumnIndex(UNREAD_COUNT));
    }

    /** Returns the account position extracted from the given cursor. */
    private static int getAccountPosition(Cursor cursor) {
        return cursor.getInt(cursor.getColumnIndex(ACCOUNT_POSITION));
    }

    /**
     * Load the account list.  The resulting cursor contains
     * - Account info
     * - # of unread messages in inbox
     * - The "Combined view" row if there's more than one account.
     */
    @VisibleForTesting
    static class AccountsLoader extends ThrottlingCursorLoader {
        private final Context mContext;
        private final long mAccountId;
        public AccountsLoader(Context context, long accountId) {
            // Super class loads a regular account cursor, but we replace it in loadInBackground().
            super(context, EmailContent.Account.CONTENT_URI, ACCOUNT_PROJECTION, null, null,
                    ORDER_BY);
            mContext = context;
            mAccountId = accountId;
        }

        @Override
        public Cursor loadInBackground() {
            final Cursor accountsCursor = super.loadInBackground();
            // Use ClosingMatrixCursor so that accountsCursor gets closed too when it's closed.
            final CursorWithExtras resultCursor
                    = new CursorWithExtras(ADAPTER_PROJECTION, accountsCursor);
            final int accountPosition = addAccountsToCursor(resultCursor, accountsCursor);
            addRecentsToCursor(resultCursor, accountPosition);
            return Utility.CloseTraceCursorWrapper.get(resultCursor);
        }

        /** Adds the account list [with extra meta data] to the given matrix cursor */
        private int addAccountsToCursor(MatrixCursor matrixCursor, Cursor accountCursor) {
            int accountPosition = UNKNOWN_POSITION;
            accountCursor.moveToPosition(-1);
            // Add a header for the accounts
            String header =
                    mContext.getString(R.string.mailbox_list_account_selector_account_header);
            addRow(matrixCursor, ROW_TYPE_HEADER, 0L, header, null, 0, UNKNOWN_POSITION);
            int totalUnread = 0;
            int currentPosition = 1;
            while (accountCursor.moveToNext()) {
                // Add account, with its unread count.
                final long accountId = accountCursor.getLong(0);
                final int unread = Mailbox.getUnreadCountByAccountAndMailboxType(
                        mContext, accountId, Mailbox.TYPE_INBOX);
                final String name = getAccountDisplayName(accountCursor);
                final String emailAddress = getAccountEmailAddress(accountCursor);
                addRow(matrixCursor, ROW_TYPE_ACCOUNT, accountId, name, emailAddress, unread,
                    UNKNOWN_POSITION);
                totalUnread += unread;
                if (accountId == mAccountId) {
                    accountPosition = currentPosition;
                }
                currentPosition++;
            }
            // Add "combined view" if more than one account exists
            final int countAccounts = accountCursor.getCount();
            if (countAccounts > 1) {
                final String name = mContext.getResources().getString(
                        R.string.mailbox_list_account_selector_combined_view);
                final String accountCount = mContext.getResources().getQuantityString(
                        R.plurals.number_of_accounts, countAccounts, countAccounts);
                addRow(matrixCursor, ROW_TYPE_ACCOUNT, Account.ACCOUNT_ID_COMBINED_VIEW,
                        name, accountCount, totalUnread,UNKNOWN_POSITION);
            }
            return accountPosition;
        }

        /**
         * Adds the recent mailbox list to the given cursor.
         * @param matrixCursor the cursor to add the list to
         * @param accountPosition the cursor position of the currently selected account
         */
        private void addRecentsToCursor(CursorWithExtras matrixCursor, int accountPosition) {
            if (mAccountId <= 0L || mAccountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
                // Currently selected account isn't usable for our purposes
                return;
            }
            String emailAddress = null;
            if (accountPosition != UNKNOWN_POSITION) {
                matrixCursor.moveToPosition(accountPosition);
                emailAddress =
                        matrixCursor.getString(matrixCursor.getColumnIndex(Account.EMAIL_ADDRESS));
            }
            boolean useTwoPane = UiUtilities.useTwoPane(mContext);
            // Filter system mailboxes if we're using a two-pane view
            RecentMailboxManager mailboxManager = RecentMailboxManager.getInstance(mContext);
            ArrayList<Long> recentMailboxes = mailboxManager.getMostRecent(mAccountId, useTwoPane);
            if (!useTwoPane && recentMailboxes.size() == 0) {
                for (int type : DEFAULT_RECENT_TYPES) {
                    Mailbox mailbox = Mailbox.restoreMailboxOfType(mContext, mAccountId, type);
                    if (mailbox != null) {
                        recentMailboxes.add(mailbox.mId);
                    }
                }
            }
            matrixCursor.mRecentCount = recentMailboxes.size();
            if (recentMailboxes.size() > 0) {
                String mailboxHeader = mContext.getString(
                        R.string.mailbox_list_account_selector_mailbox_header_fmt, emailAddress);
                addRow(matrixCursor, ROW_TYPE_HEADER, 0L, mailboxHeader, null, 0, UNKNOWN_POSITION);
                for (long mailboxId : recentMailboxes) {
                    final int unread = Utility.getFirstRowInt(mContext,
                            ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                            new String[] { MailboxColumns.UNREAD_COUNT }, null, null, null, 0);
                    final Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, mailboxId);
                    addRow(matrixCursor, ROW_TYPE_MAILBOX, mailboxId, mailbox.mDisplayName, null,
                            unread, accountPosition);
                }
            }
            if (!useTwoPane) {
                String name = mContext.getString(
                        R.string.mailbox_list_account_selector_show_all_folders);
                addRow(matrixCursor, ROW_TYPE_MAILBOX, Mailbox.NO_MAILBOX, name, null, 0,
                        UNKNOWN_POSITION);
            }
        }

        /** Adds a row to the given cursor */
        private void addRow(MatrixCursor cursor, int rowType, long id, String name,
                String emailAddress, int unreadCount, int listPosition) {
            cursor.newRow()
                .add(rowType)
                .add(id)
                .add(name)
                .add(emailAddress)
                .add(unreadCount)
                .add(listPosition);
        }
    }

    /** Cursor with some extra meta data. */
    static class CursorWithExtras extends ClosingMatrixCursor {
        /** Number of account elements */
        final int mAccountCount;
        /** Number of recent mailbox elements */
        int mRecentCount;

        private CursorWithExtras(String[] columnNames, Cursor innerCursor) {
            super(columnNames, innerCursor);
            mAccountCount = (innerCursor == null) ? 0 : innerCursor.getCount();
        }

        /**
         * Returns the cursor position of the item with the given ID. Or {@link #UNKNOWN_POSITION}
         * if the given ID does not exist.
         */
        int getPosition(long id) {
            moveToPosition(-1);
            while(moveToNext()) {
                if (id == getAccountId(this)) {
                    return getPosition();
                }
            }
            return UNKNOWN_POSITION;
        }
    }
}
