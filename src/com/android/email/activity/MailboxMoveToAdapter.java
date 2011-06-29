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

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.TextView;

import com.android.email.Email;
import com.android.email.FolderProperties;
import com.android.email.data.ThrottlingCursorLoader;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;

/**
 * Cursor adapter for the "move to mailbox" dialog.
 * TODO We've detached this class from {@link MailboxFragmentAdapter} and {@link MailboxFragmentAdapter}.
 * Depending upon the UX for the dialog and nested folders, we may want to bring these three
 * adapter classes back into alignment.
 */
class MailboxMoveToAdapter extends CursorAdapter {
    /** Selection for all mailboxes in an account */
    private static final String ALL_MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?"
        + " AND " + Mailbox.USER_VISIBLE_MAILBOX_SELECTION;
    /** Selection for valid target mailboxes */
    private static final String TARGET_MAILBOX_SELECTION =
        MailboxColumns.TYPE + " NOT IN (" + Mailbox.TYPE_DRAFTS + ","
        + Mailbox.TYPE_OUTBOX + "," + Mailbox.TYPE_SENT + "," + Mailbox.TYPE_TRASH + ") AND ("
        + MailboxColumns.FLAGS + " & " + Mailbox.FLAG_ACCEPTS_MOVED_MAIL + " != 0)";
    /** Selection to exclude a mailbox ID */
    private static final String EXCLUDE_MAILBOX_SELECTION =
        MailboxColumns.ID + "!=?";
    /** The main selection to populate the "move to" dialog */
    private static final String MOVE_TO_SELECTION =
        ALL_MAILBOX_SELECTION
        + " AND " + TARGET_MAILBOX_SELECTION
        + " AND " + EXCLUDE_MAILBOX_SELECTION;
    /** Projection that uses the server id column as the mailbox name */
    private static final String[] MOVE_TO_PROJECTION_SERVER_ID = new String[] {
        MailboxColumns.ID,
        MailboxColumns.ID + " AS org_mailbox_id",
        MailboxColumns.SERVER_ID,
        MailboxColumns.TYPE,
    };
    /** Projection that uses the display name column as the mailbox name */
    private static final String[] MOVE_TO_PROJECTION_DISPLAY_NAME = new String[] {
        MailboxColumns.ID,
        MailboxColumns.ID + " AS org_mailbox_id",
        MailboxColumns.DISPLAY_NAME,
        MailboxColumns.TYPE,
    };
    /** Sort order for special mailboxes */
    private static final String MOVE_TO_ORDER_BY_STATIC =
        "CASE " + MailboxColumns.TYPE
        + " WHEN " + Mailbox.TYPE_INBOX   + " THEN 0"
        + " WHEN " + Mailbox.TYPE_JUNK    + " THEN 1"
        + " ELSE 10 END";
    /** Server id sort order */
    private static final String MOVE_TO_ORDER_BY_SERVER_ID =
        MOVE_TO_ORDER_BY_STATIC
        // All other mailboxes are shown in alphabetical order.
        + ", " + MailboxColumns.SERVER_ID;
    /** Display name sort order */
    private static final String MOVE_TO_ORDER_BY_DISPLAY_NAME =
        MOVE_TO_ORDER_BY_STATIC
        // All other mailboxes are shown in alphabetical order.
        + ", " + MailboxColumns.DISPLAY_NAME;

    // Column 0 is only for ListView; we don't use it in our code.
    private static final int COLUMN_ID = 1;
    private static final int COLUMN_MAILBOX_NAME = 2;
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

    static Loader<Cursor> createLoader(Context context, long accountId, long mailboxId) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxDialogAdapter#createLoader accountId=" + accountId
                    + ", mailboxId=" + mailboxId);
        }
        return new MailboxMoveToLoader(context, accountId, mailboxId);
    }

    /**
     * Returns the mailbox name to display in the dialog list. If the mailbox is of
     * certain, well known, types, use a predefined name. Otherwise, use the server
     * provided name.
     */
    private static String getDisplayText(Context context, Cursor cursor) {
        final int type = cursor.getInt(COLUMN_TYPE);
        final long mailboxId = cursor.getLong(COLUMN_ID);
        return FolderProperties.getInstance(context).getDisplayName(type, mailboxId,
                cursor.getString(COLUMN_MAILBOX_NAME));
    }

    /** Loader for the "move to mailbox" dialog. */
    private static class MailboxMoveToLoader extends ThrottlingCursorLoader {
        private final long mAccountId;
        public MailboxMoveToLoader(Context context, long accountId, long mailboxId) {
            super(context, Mailbox.CONTENT_URI,
                    null, MOVE_TO_SELECTION,
                    new String[] { Long.toString(accountId), Long.toString(mailboxId) }, null);
            mAccountId = accountId;
        }

        @Override
        public Cursor loadInBackground() {
            // TODO Create a common way to store the fully qualified path name for all account types
            final String protocol = Account.getProtocol(getContext(), mAccountId);
            if (HostAuth.SCHEME_EAS.equals(protocol)) {
                // For EAS accounts; use the display name
                setProjection(MOVE_TO_PROJECTION_DISPLAY_NAME);
                setSortOrder(MOVE_TO_ORDER_BY_DISPLAY_NAME);
            } else {
                // For all other accounts; use the server id
                setProjection(MOVE_TO_PROJECTION_SERVER_ID);
                setSortOrder(MOVE_TO_ORDER_BY_SERVER_ID);
            }
            final Cursor mailboxesCursor = super.loadInBackground();
            return Utility.CloseTraceCursorWrapper.get(mailboxesCursor);
        }
    }
}
