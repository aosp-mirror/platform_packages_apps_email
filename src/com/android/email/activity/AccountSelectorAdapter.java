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

import com.android.email.R;
import com.android.email.data.ClosingMatrixCursor;
import com.android.email.data.ThrottlingCursorLoader;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.utility.Utility;

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.TextView;

/**
 * Adapter for the account selector on {@link MessageListXL}.
 *
 * TODO Test it!
 * TODO Use layout?  Or use the standard resources that ActionBarDemo uses?
 * TODO Revisit the sort order when we get more detailed UI spec.  (current sort order makes things
 * simpler for now.)  Maybe we can just use SimpleCursorAdapter.
 */
public class AccountSelectorAdapter extends CursorAdapter {
    /** Projection used to query from Account */
    private static final String[] ACCOUNT_PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        EmailContent.Account.DISPLAY_NAME,
        EmailContent.Account.EMAIL_ADDRESS,
    };

    /**
     * Projection for the resulting MatrixCursor -- must be {@link #ACCOUNT_PROJECTION}
     * with "UNREAD_COUNT".
     */
    private static final String[] RESULT_PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        EmailContent.Account.DISPLAY_NAME,
        EmailContent.Account.EMAIL_ADDRESS,
        "UNREAD_COUNT"
    };

    private static final int ID_COLUMN = 0;
    private static final int DISPLAY_NAME_COLUMN = 1;
    private static final int EMAIL_ADDRESS_COLUMN = 2;
    private static final int UNREAD_COUNT_COLUMN = 3;

    /** Sort order.  Show the default account first. */
    private static final String ORDER_BY =
            EmailContent.Account.IS_DEFAULT + " desc, " + EmailContent.Account.RECORD_ID;

    private final LayoutInflater mInflater;

    public static Loader<Cursor> createLoader(Context context) {
        return new AccountsLoader(context);
    }

    public AccountSelectorAdapter(Context context, Cursor c) {
        super(context, c, 0 /* no auto-requery */);
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        final View view = mInflater.inflate(R.layout.account_selector_dropdown, parent, false);

        final TextView displayNameView = (TextView) view.findViewById(R.id.display_name);
        final TextView emailAddressView = (TextView) view.findViewById(R.id.email_address);
        final TextView unreadCountView = (TextView) view.findViewById(R.id.unread_count);

        final String displayName = getAccountDisplayName(position);
        final String emailAddress = getAccountEmailAddress(position);

        displayNameView.setText(displayName);

        // Show the email address only when it's different from the display name.
        // If same, show " " instead of "", so that the text view won't get completely
        // collapsed. (TextView's height will be 0px if it's "match_content" and the
        // content is "".)
        emailAddressView.setText(emailAddress.equals(displayName) ? " " : emailAddress);
        unreadCountView.setText(Integer.toString(getAccountUnreadCount(position)));
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

    /** @return Account id extracted from a Cursor. */
    public static long getAccountId(Cursor c) {
        return c.getLong(ID_COLUMN);
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

    /** @return Account name extracted from a Cursor. */
    public static String getAccountDisplayName(Cursor cursor) {
        return cursor.getString(DISPLAY_NAME_COLUMN);
    }

    /** @return Email address extracted from a Cursor. */
    public static String getAccountEmailAddress(Cursor cursor) {
        return cursor.getString(EMAIL_ADDRESS_COLUMN);
    }

    /** @return Unread count extracted from a Cursor. */
    public static int getAccountUnreadCount(Cursor cursor) {
        return cursor.getInt(UNREAD_COUNT_COLUMN);
    }

    /**
     * Load the account list.  The resulting cursor contains
     * - Account info
     * - # of unread messages in inbox
     * - The "Combined view" row if there's more than one account.
     */
    /* package */ static class AccountsLoader extends ThrottlingCursorLoader {
        private final Context mContext;

        public AccountsLoader(Context context) {
            // Super class loads a regular account cursor, but we replace it in loadInBackground().
            super(context, EmailContent.Account.CONTENT_URI, ACCOUNT_PROJECTION, null, null,
                    ORDER_BY);
            mContext = context;
        }

        @Override
        public Cursor loadInBackground() {
            // Fetch account list
            final Cursor accountsCursor = super.loadInBackground();

            // Cursor that's actually returned.
            // Use ClosingMatrixCursor so that accountsCursor gets closed too when it's closed.
            final MatrixCursor resultCursor = new ClosingMatrixCursor(RESULT_PROJECTION,
                    accountsCursor);
            accountsCursor.moveToPosition(-1);

            // Build the cursor...
            int totalUnread = 0;
            while (accountsCursor.moveToNext()) {
                // Add account, with its unread count.
                final long accountId = accountsCursor.getLong(0);
                final int unread = Mailbox.getUnreadCountByAccountAndMailboxType(
                        mContext, accountId, Mailbox.TYPE_INBOX);

                RowBuilder rb = resultCursor.newRow();
                rb.add(accountId);
                rb.add(getAccountDisplayName(accountsCursor));
                rb.add(getAccountEmailAddress(accountsCursor));
                rb.add(unread);
                totalUnread += unread;
            }
            // Add "combined view"
            final int countAccounts = resultCursor.getCount();
            if (countAccounts > 1) {
                RowBuilder rb = resultCursor.newRow();

                // Add ID, display name, # of accounts, total unread count.
                rb.add(Account.ACCOUNT_ID_COMBINED_VIEW);
                rb.add(mContext.getResources().getString(
                        R.string.mailbox_list_account_selector_combined_view));
                rb.add(mContext.getResources().getQuantityString(R.plurals.number_of_accounts,
                        countAccounts, countAccounts));
                rb.add(totalUnread);
            }
            return Utility.CloseTraceCursorWrapper.get(resultCursor);
        }
    }
}
