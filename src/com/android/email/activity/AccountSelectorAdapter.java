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
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;

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
    private static final int ROW_TYPE_HEADER = AdapterView.ITEM_VIEW_TYPE_HEADER_OR_FOOTER;
    @SuppressWarnings("unused")
    private static final int ROW_TYPE_MAILBOX = 0;
    private static final int ROW_TYPE_ACCOUNT = 1;
    private static final int ITEM_VIEW_TYPE_ACCOUNT = 0;
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
    };

    /** Sort order.  Show the default account first. */
    private static final String ORDER_BY =
            EmailContent.Account.IS_DEFAULT + " desc, " + EmailContent.Account.RECORD_ID;

    private final LayoutInflater mInflater;
    @SuppressWarnings("hiding")
    private final Context mContext;

    public static Loader<Cursor> createLoader(Context context) {
        return new AccountsLoader(context);
    }

    public AccountSelectorAdapter(Context context) {
        super(context, null, 0 /* no auto-requery */);
        mContext = context;
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
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

    /** Returns the account ID extracted from the given cursor. */
    static long getAccountId(Cursor c) {
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

    /**
     * Load the account list.  The resulting cursor contains
     * - Account info
     * - # of unread messages in inbox
     * - The "Combined view" row if there's more than one account.
     */
    @VisibleForTesting
    static class AccountsLoader extends ThrottlingCursorLoader {
        private final Context mContext;

        public AccountsLoader(Context context) {
            // Super class loads a regular account cursor, but we replace it in loadInBackground().
            super(context, EmailContent.Account.CONTENT_URI, ACCOUNT_PROJECTION, null, null,
                    ORDER_BY);
            mContext = context;
        }

        @Override
        public Cursor loadInBackground() {
            final Cursor accountsCursor = super.loadInBackground();
            // Use ClosingMatrixCursor so that accountsCursor gets closed too when it's closed.
            final MatrixCursor resultCursor
                    = new ClosingMatrixCursor(ADAPTER_PROJECTION, accountsCursor);
            addAccountsToCursor(resultCursor, accountsCursor);
            // TODO Add mailbox recent list to the end of the return cursor
            return Utility.CloseTraceCursorWrapper.get(resultCursor);
        }

        /** Adds the account list [with extra meta data] to the given matrix cursor */
        private void addAccountsToCursor(MatrixCursor matrixCursor, Cursor accountCursor) {
            accountCursor.moveToPosition(-1);
            // Add a header for the accounts
            matrixCursor.newRow()
                .add(ROW_TYPE_HEADER)
                .add(0L)
                .add(mContext.getString(R.string.mailbox_list_account_selector_account_header))
                .add(null)
                .add(0L);
            int totalUnread = 0;
            while (accountCursor.moveToNext()) {
                // Add account, with its unread count.
                final long accountId = accountCursor.getLong(0);
                final int unread = Mailbox.getUnreadCountByAccountAndMailboxType(
                        mContext, accountId, Mailbox.TYPE_INBOX);
                matrixCursor.newRow()
                    .add(ROW_TYPE_ACCOUNT)
                    .add(accountId)
                    .add(getAccountDisplayName(accountCursor))
                    .add(getAccountEmailAddress(accountCursor))
                    .add(unread);
                totalUnread += unread;
            }
            // Add "combined view" if more than one account exists
            final int countAccounts = matrixCursor.getCount();
            if (countAccounts > 1) {
                matrixCursor.newRow()
                    .add(ROW_TYPE_ACCOUNT)
                    .add(Account.ACCOUNT_ID_COMBINED_VIEW)
                    .add(mContext.getResources().getString(
                            R.string.mailbox_list_account_selector_combined_view))
                    .add(mContext.getResources().getQuantityString(R.plurals.number_of_accounts,
                            countAccounts, countAccounts))
                    .add(totalUnread);
            }
        }
    }
}
