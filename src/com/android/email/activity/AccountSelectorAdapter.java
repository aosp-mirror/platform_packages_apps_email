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

import com.android.email.data.ThrottlingCursorLoader;
import com.android.email.provider.EmailContent;

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
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
    private static final int AUTO_REQUERY_TIMEOUT = 5 * 1000; // in ms

    private static final String[] PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        EmailContent.Account.DISPLAY_NAME,
        EmailContent.Account.EMAIL_ADDRESS
    };

    private static final int ID_COLUMN = 0;
    private static final int DISPLAY_NAME_COLUMN = 1;
    private static final int EMAIL_ADDRESS_COLUMN = 2;

    /** Sort order.  Show the default account first. */
    private static final String ORDER_BY =
            EmailContent.Account.IS_DEFAULT + " desc, " + EmailContent.Account.RECORD_ID;

    public static Loader<Cursor> createLoader(Context context) {
        return new ThrottlingCursorLoader(context, EmailContent.Account.CONTENT_URI, PROJECTION,
                null, null, ORDER_BY, AUTO_REQUERY_TIMEOUT);
    }

    public AccountSelectorAdapter(Context context, Cursor c) {
        super(context, c, 0 /* no auto-requery */);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        TextView v = (TextView) view;
        v.setText(cursor.getString(EMAIL_ADDRESS_COLUMN));
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return new TextView(context);
    }

    /** @return Account id extracted from a Cursor. */
    public static long getAccountId(Cursor c) {
        return c.getLong(ID_COLUMN);
    }
}
