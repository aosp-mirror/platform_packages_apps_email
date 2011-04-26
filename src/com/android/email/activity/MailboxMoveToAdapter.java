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
import com.android.email.data.ThrottlingCursorLoader;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.utility.Utility;

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.TextView;

/**
 * Cursor adapter for the "move to mailbox" dialog.
 * TODO We've detached this class from {@link MailboxesAdapter} and {@link MailboxFragmentAdapter}.
 * Depending upon the UX for the dialog and nested folders, we may want to bring these three
 * adapter classes back into alignment.
 */
class MailboxMoveToAdapter extends CursorAdapter {
    private static final String ALL_MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?" +
        " AND " + Mailbox.USER_VISIBLE_MAILBOX_SELECTION;
    private static final String MOVE_TO_TARGET_MAILBOX_SELECTION =
        MailboxColumns.TYPE + " NOT IN (" + Mailbox.TYPE_DRAFTS + "," +
        Mailbox.TYPE_OUTBOX + "," + Mailbox.TYPE_SENT + "," + Mailbox.TYPE_TRASH + ")";
    /** The main selection to populate the "move to" dialog */
    private static final String MOVE_TO_SELECTION =
        ALL_MAILBOX_SELECTION + " AND " + MOVE_TO_TARGET_MAILBOX_SELECTION;
    /** Field projection for the "move to" dialog */
    private static final String[] MOVE_TO_PROJECTION = new String[] { MailboxColumns.ID,
        MailboxColumns.ID + " AS org_mailbox_id",
        MailboxColumns.SERVER_ID,
        MailboxColumns.TYPE,
    };
    private static final String MOVE_TO_ORDER_BY = "CASE " + MailboxColumns.TYPE +
        " WHEN " + Mailbox.TYPE_INBOX   + " THEN 0" +
        " WHEN " + Mailbox.TYPE_JUNK    + " THEN 1" +
        // All other mailboxes are shown in alphabetical order.
        " ELSE 10 END" +
        " ," + MailboxColumns.DISPLAY_NAME;

    // Column 0 is only for ListView; we don't use it in our code.
    private static final int COLUMN_ID = 1;
    private static final int COLUMN_SERVER_ID = 2;
    private static final int COLUMN_TYPE = 3;

    /** Cached layout inflater */
    private final LayoutInflater mInflater;

    public MailboxMoveToAdapter(Context context) {
        super(context, null, 0 /* flags; no content observer */);
        mInflater = LayoutInflater.from(context);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        TextView t = (TextView) view;
        t.setText(getDisplayText(context, cursor));
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(android.R.layout.simple_list_item_1, parent, false);
    }

    static Loader<Cursor> createLoader(Context context, long accountId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxDialogAdapter#createLoader accountId=" + accountId);
        }
        return new MailboxMoveToLoader(context, accountId);
    }

    /**
     * Returns the mailbox name to display in the dialog list. If the mailbox is of
     * certain, well known, types, use a predefined name. Otherwise, use the server
     * provided name.
     */
    private static String getDisplayText(Context context, Cursor cursor) {
        final int type = cursor.getInt(COLUMN_TYPE);
        final long mailboxId = cursor.getLong(COLUMN_ID);
        String name = FolderProperties.getInstance(context).getDisplayName(type, mailboxId);
        if (name == null) {
            name = cursor.getString(COLUMN_SERVER_ID);
        }
        return name;
    }

    /** Loader for the "move to mailbox" dialog. */
    private static class MailboxMoveToLoader extends ThrottlingCursorLoader {
        public MailboxMoveToLoader(Context context, long accountId) {
            super(context, EmailContent.Mailbox.CONTENT_URI,
                    MOVE_TO_PROJECTION, MOVE_TO_SELECTION,
                    new String[] { String.valueOf(accountId) }, MOVE_TO_ORDER_BY);
        }

        @Override
        public Cursor loadInBackground() {
            final Cursor mailboxesCursor = super.loadInBackground();
            return Utility.CloseTraceCursorWrapper.get(mailboxesCursor);
        }
    }
}
