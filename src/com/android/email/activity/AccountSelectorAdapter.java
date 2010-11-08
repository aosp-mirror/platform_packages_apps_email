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
import com.android.email.data.ThrottlingCursorLoader;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
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
    private static final String[] PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        EmailContent.Account.DISPLAY_NAME
    };

    private static final int ID_COLUMN = 0;
    private static final int DISPLAY_NAME_COLUMN = 1;

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
        View view = mInflater.inflate(android.R.layout.simple_spinner_dropdown_item, null);
        TextView textView = (TextView) view.findViewById(android.R.id.text1);
        textView.setText(getAccountDisplayName(position));
        return view;
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        TextView textView = (TextView) view.findViewById(android.R.id.text1);
        textView.setText(getAccountDisplayName(cursor));
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(android.R.layout.simple_spinner_item, null);
    }

    /** @return Account id extracted from a Cursor. */
    public static long getAccountId(Cursor c) {
        return c.getLong(ID_COLUMN);
    }

    private String getAccountDisplayName(int position) {
        final Cursor c = getCursor();
        return c.moveToPosition(position) ? getAccountDisplayName(c) : null;
    }

    /** @return Account name extracted from a Cursor. */
    public static String getAccountDisplayName(Cursor cursor) {
        return cursor.getString(DISPLAY_NAME_COLUMN);
    }

    /**
     * Load the account list.  Also add the "Combined view" row if there's more than one account.
     */
    private static class AccountsLoader extends ThrottlingCursorLoader {
        private final Context mContext;

        public AccountsLoader(Context context) {
            super(context, EmailContent.Account.CONTENT_URI, PROJECTION, null, null, ORDER_BY);
            mContext = context;
        }

        @Override
        public Cursor loadInBackground() {
            final Cursor accountsCursor = super.loadInBackground();
            if (accountsCursor.getCount() <= 1) {
                return accountsCursor;
            }
            // If more than 1 account, add "Combined view".
            final MatrixCursor combinedViewRow = new MatrixCursor(PROJECTION);
            RowBuilder rb = combinedViewRow.newRow();

            // Add ID and display name
            rb.add(Account.ACCOUNT_ID_COMBINED_VIEW);
            rb.add(mContext.getResources().getString(
                    R.string.mailbox_list_account_selector_combined_view));
            return new MergeCursor(new Cursor[] {accountsCursor, combinedViewRow});
        }
    }
}
