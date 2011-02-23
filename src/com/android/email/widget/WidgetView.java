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

package com.android.email.widget;

import com.android.email.R;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.utility.Utility;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * Represents the "view" of the widget.
 *
 * It's a {@link ViewType} + mutable fields.  (e.g. account id/name)
 */
/* package */ class WidgetView {
    private static final String SORT_ID_ASCENDING = AccountColumns.ID + " ASC";
    private static final String[] ID_NAME_PROJECTION = {Account.RECORD_ID, Account.DISPLAY_NAME};
    private static final int ID_NAME_COLUMN_ID = 0;
    private static final int ID_NAME_COLUMN_NAME = 1;

    private static enum ViewType {
        TYPE_ALL_UNREAD(false, Message.UNREAD_SELECTION, R.string.widget_unread),
        TYPE_ALL_STARRED(false, Message.ALL_FAVORITE_SELECTION, R.string.widget_starred),
        TYPE_ALL_INBOX(false, Message.INBOX_SELECTION, R.string.widget_all_mail),
        TYPE_ACCOUNT_INBOX(true, Message.PER_ACCOUNT_INBOX_SELECTION, 0) {
            @Override public String getTitle(Context context, String accountName) {
                return accountName;
            }

            @Override public String[] getSelectionArgs(long accountId) {
                return new String[]{Long.toString(accountId)};
            }
        };

        private final boolean mIsPerAccount;
        private final String mSelection;
        private final int mTitleResource;

        ViewType(boolean isPerAccount, String selection, int titleResource) {
            mIsPerAccount = isPerAccount;
            mSelection = selection;
            mTitleResource = titleResource;
        }

        public String getTitle(Context context, String accountName) {
            return context.getString(mTitleResource);
        }

        public String getSelection() {
            return mSelection;
        }

        public String[] getSelectionArgs(long accountId) {
            return null;
        }
    }

    /* package */ static final WidgetView ALL_UNREAD = new WidgetView(ViewType.TYPE_ALL_UNREAD);
    /* package */ static final WidgetView ALL_STARRED = new WidgetView(ViewType.TYPE_ALL_STARRED);
    /* package */ static final WidgetView ALL_INBOX = new WidgetView(ViewType.TYPE_ALL_INBOX);

    /**
     * The initial view will be the *next* of ALL_STARRED -- see {@link #getNext}.
     */
    public static final WidgetView UNINITIALIZED_VIEW = ALL_STARRED;

    private final ViewType mViewType;
    /** Account ID -- set only when isPerAccount */
    private final long mAccountId;
    /** Account name -- set only when isPerAccount */
    private final String mAccountName;

    private WidgetView(ViewType viewType) {
        this(viewType, 0, null);
    }

    private WidgetView(ViewType viewType, long accountId, String accountName) {
        mViewType = viewType;
        mAccountId = accountId;
        mAccountName = accountName;
    }

    public boolean isPerAccount() {
        return mViewType.mIsPerAccount;
    }

    public String getTitle(Context context) {
        return mViewType.getTitle(context, mAccountName);
    }

    public String getSelection(Context context) {
        return mViewType.getSelection();
    }

    public String[] getSelectionArgs() {
        return mViewType.getSelectionArgs(mAccountId);
    }

    /**
     * Switch to the "next" view.
     *
     * Views rotate in this order:
     * - {@link #ALL_STARRED}
     * - {@link #ALL_INBOX} -- this will be skipped if # of accounts <= 1
     * - Inbox for account 1
     * - Inbox for account 2
     * -  :
     * - {@link #ALL_UNREAD}
     * - Go back to {@link #ALL_STARRED}.
     *
     * Note the initial view is always the next of {@link #ALL_STARRED}.
     */
    public WidgetView getNext(Context context) {
        if (mViewType == ViewType.TYPE_ALL_UNREAD) {
            return ALL_STARRED;
        }
        if (mViewType == ViewType.TYPE_ALL_STARRED) {
            // If we're in starred and there is more than one account, go to "all mail"
            // Otherwise, fall through to the accounts themselves
            if (EmailContent.count(context, Account.CONTENT_URI) > 1) {
                return ALL_INBOX;
            }
        }
        final long nextAccountIdStart;
        if (mViewType == ViewType.TYPE_ALL_INBOX) {
            nextAccountIdStart = -1;
        } else { // TYPE_ACCOUNT_INBOX
            nextAccountIdStart = mAccountId + 1;
        }
        Cursor c = context.getContentResolver().query(Account.CONTENT_URI, ID_NAME_PROJECTION,
                "_id>=?", new String[] {Long.toString(nextAccountIdStart)}, SORT_ID_ASCENDING);

        final long nextAccountId;
        final String nextAccountName;
        try {
            if (c.moveToFirst()) {
                return new WidgetView(ViewType.TYPE_ACCOUNT_INBOX, c.getLong(ID_NAME_COLUMN_ID),
                        c.getString(ID_NAME_COLUMN_NAME));
            } else {
                return ALL_UNREAD;
            }
        } finally {
            c.close();
        }
    }

    /**
     * Returns whether the current view is valid. The following rules determine if a view is
     * considered valid:
     * 1. {@link ViewType#TYPE_ALL_STARRED} and {@link ViewType#TYPE_ALL_UNREAD} are always
     *    valid.
     * 2. If the view is {@link ViewType#TYPE_ALL_INBOX}, returns <code>true</code> if more than
     *    one account is defined. Otherwise, returns <code>false</code>.
     * 3. If the view is {@link ViewType#TYPE_ACCOUNT_INBOX}, returns <code>true</code> if the
     *    account is defined. Otherwise, returns <code>false</code>.
     */
    public boolean isValid(Context context) {
        switch(mViewType) {
            case TYPE_ALL_INBOX:
                // "all inbox" is valid only if there is more than one account
                return (EmailContent.count(context, Account.CONTENT_URI) > 1);
            case TYPE_ACCOUNT_INBOX:
                // Ensure current account still exists
                Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, mAccountId);
                return Utility.getFirstRowLong(context, uri,
                        EmailContent.ID_PROJECTION, null, null, null,
                        EmailContent.ID_PROJECTION_COLUMN, null) != null;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WidgetView:type=");
        sb.append(mViewType);
        sb.append("  account=");
        sb.append(mAccountId);

        return sb.toString();
    }
}
