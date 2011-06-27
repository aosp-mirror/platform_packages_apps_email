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

import com.android.email.FolderProperties;
import com.android.email.R;
import com.android.email.data.ClosingMatrixCursor;
import com.android.email.data.ThrottlingCursorLoader;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;

import java.util.ArrayList;

import android.content.ContentResolver;
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
    /** meta data column for an message count (unread or total, depending on row) */
    private static final String MESSAGE_COUNT = "unreadCount";

    /** meta data column for the row type; used for display purposes */
    private static final String ROW_TYPE = "rowType";

    /** meta data position of the currently selected account in the drop-down list */
    private static final String ACCOUNT_POSITION = "accountPosition";

    /** "account id" virtual column name for the matrix cursor */
    private static final String ACCOUNT_ID = "accountId";

    private static final int ROW_TYPE_HEADER = AdapterView.ITEM_VIEW_TYPE_HEADER_OR_FOOTER;
    @SuppressWarnings("unused")
    private static final int ROW_TYPE_MAILBOX = 0;
    private static final int ROW_TYPE_ACCOUNT = 1;
    private static final int ITEM_VIEW_TYPE_ACCOUNT = 0;
    static final int UNKNOWN_POSITION = -1;
    /** Projection for account database query */
    private static final String[] ACCOUNT_PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        Account.DISPLAY_NAME,
        Account.EMAIL_ADDRESS,
    };
    /**
     * Projection used for the selector display; we add meta data that doesn't exist in the
     * account database, so, this should be a super-set of {@link #ACCOUNT_PROJECTION}.
     */
    private static final String[] ADAPTER_PROJECTION = new String[] {
        ROW_TYPE,
        EmailContent.RECORD_ID,
        Account.DISPLAY_NAME,
        Account.EMAIL_ADDRESS,
        MESSAGE_COUNT,
        ACCOUNT_POSITION,
        ACCOUNT_ID,
    };

    /** Sort order.  Show the default account first. */
    private static final String ORDER_BY =
            Account.IS_DEFAULT + " desc, " + Account.RECORD_ID;

    private final LayoutInflater mInflater;
    @SuppressWarnings("hiding")
    private final Context mContext;

    /**
     * Returns a loader that can populate the account spinner.
     * @param context a context
     * @param accountId the ID of the currently viewed account
     */
    public static Loader<Cursor> createLoader(Context context, long accountId, long mailboxId) {
        return new AccountsLoader(context, accountId, mailboxId, UiUtilities.useTwoPane(context));
    }

    public AccountSelectorAdapter(Context context) {
        super(context, null, 0 /* no auto-requery */);
        mContext = context;
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    /**
     * {@inheritDoc}
     *
     * The account selector view can contain one of four types of row data:
     * <ol>
     * <li>headers</li>
     * <li>accounts</li>
     * <li>recent mailboxes</li>
     * <li>"show all folders"</li>
     * </ol>
     * Headers are handled separately as they have a unique layout and cannot be interacted with.
     * Accounts, recent mailboxes and "show all folders" all have the same interaction model and
     * share a very similar layout. The single difference is that both accounts and recent
     * mailboxes display an unread count; whereas "show all folders" does not. To determine
     * if a particular row is "show all folders" verify that a) it's not an account row and
     * b) it's ID is {@link Mailbox#NO_MAILBOX}.
     *
     * TODO Use recycled views.  ({@link #getViewTypeCount} and {@link #getItemViewType})
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Cursor c = getCursor();
        c.moveToPosition(position);
        View view;
        if (c.getInt(c.getColumnIndex(ROW_TYPE)) == ROW_TYPE_HEADER) {
            view = mInflater.inflate(R.layout.action_bar_spinner_dropdown_header, parent, false);
            final TextView displayNameView = (TextView) view.findViewById(R.id.display_name);
            final String displayName = getDisplayName(c);
            displayNameView.setText(displayName);
        } else {
            view = mInflater.inflate(R.layout.action_bar_spinner_dropdown, parent, false);
            final TextView displayNameView = (TextView) view.findViewById(R.id.display_name);
            final TextView emailAddressView = (TextView) view.findViewById(R.id.email_address);
            final TextView unreadCountView = (TextView) view.findViewById(R.id.unread_count);

            final String displayName = getDisplayName(c);
            final String emailAddress = getAccountEmailAddress(c);

            displayNameView.setText(displayName);

            // Show the email address only when it's different from the display name.
            if (displayName.equals(emailAddress)) {
                emailAddressView.setVisibility(View.GONE);
            } else {
                emailAddressView.setVisibility(View.VISIBLE);
                emailAddressView.setText(emailAddress);
            }

            boolean isAccount = isAccountItem(c);
            long id = getId(c);
            if (isAccount || id != Mailbox.NO_MAILBOX) {
                unreadCountView.setVisibility(View.VISIBLE);
                unreadCountView.setText(UiUtilities.getMessageCountForUi(mContext,
                        getAccountUnreadCount(c), true));
            } else {
                unreadCountView.setVisibility(View.INVISIBLE);
            }
        }
        return view;
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return null; // we don't reuse views.  This method never gets called.
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        // we don't reuse views.  This method never gets called.
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
        return isAccountItem(c);
    }

    public boolean isAccountItem(Cursor c) {
        return (c.getLong(c.getColumnIndex(ROW_TYPE)) == ROW_TYPE_ACCOUNT);
    }

    public boolean isMailboxItem(int position) {
        Cursor c = getCursor();
        c.moveToPosition(position);
        return (c.getLong(c.getColumnIndex(ROW_TYPE)) == ROW_TYPE_MAILBOX);
    }

    private int getAccountUnreadCount(Cursor c) {
        return getMessageCount(c);
    }

    /**
     * Returns the account/mailbox ID extracted from the given cursor.
     */
    private static long getId(Cursor c) {
        return c.getLong(c.getColumnIndex(EmailContent.RECORD_ID));
    }

    /**
     * @return ID of the account / mailbox for a row
     */
    public long getId(int position) {
        final Cursor c = getCursor();
        return c.moveToPosition(position) ? getId(c) : Account.NO_ACCOUNT;
    }

    /**
     * @return ID of the account for a row
     */
    public long getAccountId(int position) {
        final Cursor c = getCursor();
        return c.moveToPosition(position)
                ? c.getLong(c.getColumnIndex(ACCOUNT_ID))
                : Account.NO_ACCOUNT;
    }

    /** Returns the account name extracted from the given cursor. */
    static String getDisplayName(Cursor cursor) {
        return cursor.getString(cursor.getColumnIndex(Account.DISPLAY_NAME));
    }

    /** Returns the email address extracted from the given cursor. */
    private static String getAccountEmailAddress(Cursor cursor) {
        return cursor.getString(cursor.getColumnIndex(Account.EMAIL_ADDRESS));
    }

    /**
     * Returns the message count (unread or total, depending on row) extracted from the given
     * cursor.
     */
    private static int getMessageCount(Cursor cursor) {
        return cursor.getInt(cursor.getColumnIndex(MESSAGE_COUNT));
    }

    private static String sCombinedViewDisplayName;
    private static String getCombinedViewDisplayName(Context c) {
        if (sCombinedViewDisplayName == null) {
            sCombinedViewDisplayName = c.getResources().getString(
                    R.string.mailbox_list_account_selector_combined_view);
        }
        return sCombinedViewDisplayName;
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
        private final long mMailboxId;
        private final boolean mUseTwoPane; // Injectable for test
        private final FolderProperties mFolderProperties;

        @VisibleForTesting
        AccountsLoader(Context context, long accountId, long mailboxId, boolean useTwoPane) {
            // Super class loads a regular account cursor, but we replace it in loadInBackground().
            super(context, Account.CONTENT_URI, ACCOUNT_PROJECTION, null, null,
                    ORDER_BY);
            mContext = context;
            mAccountId = accountId;
            mMailboxId = mailboxId;
            mFolderProperties = FolderProperties.getInstance(mContext);
            mUseTwoPane = useTwoPane;
        }

        @Override
        public Cursor loadInBackground() {
            final Cursor accountsCursor = super.loadInBackground();
            // Use ClosingMatrixCursor so that accountsCursor gets closed too when it's closed.
            final CursorWithExtras resultCursor
                    = new CursorWithExtras(ADAPTER_PROJECTION, accountsCursor);
            final int accountPosition = addAccountsToCursor(resultCursor, accountsCursor);
            addRecentsToCursor(resultCursor, accountPosition);

            resultCursor.setAccountMailboxInfo(getContext(), mAccountId, mMailboxId);
            return resultCursor;
        }

        /** Adds the account list [with extra meta data] to the given matrix cursor */
        private int addAccountsToCursor(CursorWithExtras matrixCursor, Cursor accountCursor) {
            int accountPosition = UNKNOWN_POSITION;
            accountCursor.moveToPosition(-1);

            // Add a header for the accounts
            addHeaderRow(matrixCursor, mContext.getString(
                    R.string.mailbox_list_account_selector_account_header));

            matrixCursor.mAccountCount = accountCursor.getCount();
            int totalUnread = 0;
            int currentPosition = 1;
            while (accountCursor.moveToNext()) {
                // Add account, with its unread count.
                final long accountId = accountCursor.getLong(0);
                final int unread = Mailbox.getUnreadCountByAccountAndMailboxType(
                        mContext, accountId, Mailbox.TYPE_INBOX);
                final String name = getDisplayName(accountCursor);
                final String emailAddress = getAccountEmailAddress(accountCursor);
                addRow(matrixCursor, ROW_TYPE_ACCOUNT, accountId, name, emailAddress, unread,
                    UNKNOWN_POSITION, accountId);
                totalUnread += unread;
                if (accountId == mAccountId) {
                    accountPosition = currentPosition;
                }
                currentPosition++;
            }
            // Add "combined view" if more than one account exists
            final int countAccounts = accountCursor.getCount();
            if (countAccounts > 1) {
                final String accountCount = mContext.getResources().getQuantityString(
                        R.plurals.number_of_accounts, countAccounts, countAccounts);
                addRow(matrixCursor, ROW_TYPE_ACCOUNT, Account.ACCOUNT_ID_COMBINED_VIEW,
                        getCombinedViewDisplayName(mContext),
                        accountCount, totalUnread, UNKNOWN_POSITION,
                        Account.ACCOUNT_ID_COMBINED_VIEW);

                // Increment the account count for the combined account.
                matrixCursor.mAccountCount++;
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
            RecentMailboxManager mailboxManager = RecentMailboxManager.getInstance(mContext);
            ArrayList<Long> recentMailboxes = null;
            if (!mUseTwoPane) {
                // Do not display recent mailboxes in the account spinner for the two pane view
                recentMailboxes = mailboxManager.getMostRecent(mAccountId, mUseTwoPane);
            }
            int recentCount = (recentMailboxes == null) ? 0 : recentMailboxes.size();
            matrixCursor.mRecentCount = recentCount;

            if (!mUseTwoPane) {
                // "Recent mailboxes" header
                addHeaderRow(matrixCursor, mContext.getString(
                        R.string.mailbox_list_account_selector_mailbox_header_fmt, emailAddress));
            }

            if (recentCount > 0) {
                for (long mailboxId : recentMailboxes) {
                    addMailboxRow(matrixCursor, accountPosition, mailboxId);
                }
            }

            if (!mUseTwoPane) {
                // TODO We need this for combined view too.
                String name = mContext.getString(
                        R.string.mailbox_list_account_selector_show_all_folders);
                addRow(matrixCursor, ROW_TYPE_MAILBOX, Mailbox.NO_MAILBOX, name, null, 0,
                        accountPosition, mAccountId);
            }
        }


        private static final String[] RECENT_MAILBOX_INFO_PROJECTION = new String[] {
            MailboxColumns.ID, MailboxColumns.DISPLAY_NAME, MailboxColumns.TYPE,
            MailboxColumns.UNREAD_COUNT, MailboxColumns.MESSAGE_COUNT
        };

        private void addMailboxRow(MatrixCursor matrixCursor, int accountPosition, long mailboxId) {
            Cursor c = mContext.getContentResolver().query(
                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                    RECENT_MAILBOX_INFO_PROJECTION, null, null, null);
            if (!c.moveToFirst()) {
                return;
            }
            addRow(matrixCursor, ROW_TYPE_MAILBOX, mailboxId,
                    mFolderProperties.getDisplayName(c), null,
                    mFolderProperties.getMessageCount(c), accountPosition, mAccountId);
        }

        private void addHeaderRow(MatrixCursor cursor, String name) {
            addRow(cursor, ROW_TYPE_HEADER, 0L, name, null, 0, UNKNOWN_POSITION,
                    Account.NO_ACCOUNT);
        }

        /** Adds a row to the given cursor */
        private void addRow(MatrixCursor cursor, int rowType, long id, String name,
                String emailAddress, int messageCount, int listPosition, long accountId) {
            cursor.newRow()
                .add(rowType)
                .add(id)
                .add(name)
                .add(emailAddress)
                .add(messageCount)
                .add(listPosition)
                .add(accountId);
        }
    }

    /** Cursor with some extra meta data. */
    static class CursorWithExtras extends ClosingMatrixCursor {

        /** Number of account elements, including the combined account row. */
        private int mAccountCount;
        /** Number of recent mailbox elements */
        private int mRecentCount;

        private boolean mAccountExists;

        /**
         * Account ID that's loaded.
         */
        private long mAccountId;
        private String mAccountDisplayName;

        /**
         * Mailbox ID that's loaded.
         */
        private long mMailboxId;
        private String mMailboxDisplayName;
        private int mMailboxMessageCount;

        private CursorWithExtras(String[] columnNames, Cursor innerCursor) {
            super(columnNames, innerCursor);
        }

        private static final String[] ACCOUNT_INFO_PROJECTION = new String[] {
            AccountColumns.DISPLAY_NAME,
        };
        private static final String[] MAILBOX_INFO_PROJECTION = new String[] {
            MailboxColumns.ID, MailboxColumns.DISPLAY_NAME, MailboxColumns.TYPE,
            MailboxColumns.UNREAD_COUNT, MailboxColumns.MESSAGE_COUNT
        };

        /**
         * Set the current account/mailbox info.
         */
        private void setAccountMailboxInfo(Context context, long accountId, long mailboxId) {
            mAccountId = accountId;
            mMailboxId = mailboxId;

            // Get account info
            if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
                // We need to treat ACCOUNT_ID_COMBINED_VIEW specially...
                mAccountExists = true;
                mAccountDisplayName = getCombinedViewDisplayName(context);
                mMailboxDisplayName = FolderProperties.getInstance(context)
                        .getCombinedMailboxName(mMailboxId);

                // TODO Would be nicer to show message count for combined mailboxes too..
                mMailboxMessageCount = 0;
                return;
            }

            mAccountDisplayName = Utility.getFirstRowString(context,
                    ContentUris.withAppendedId(Account.CONTENT_URI, accountId),
                    ACCOUNT_INFO_PROJECTION, null, null, null, 0, null);
            if (mAccountDisplayName == null) {
                // Account gone!
                mAccountExists = false;
                return;
            }
            mAccountExists = true;

            // If mailbox not specified, done.
            if (mMailboxId == Mailbox.NO_MAILBOX) {
                return;
            }

            // Get mailbox info
            final ContentResolver r = context.getContentResolver();
            final Cursor mailboxCursor = r.query(
                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                    MAILBOX_INFO_PROJECTION, null, null, null);
            try {
                if (mailboxCursor.moveToFirst()) {
                    final FolderProperties fp = FolderProperties.getInstance(context);
                    mMailboxDisplayName = fp.getDisplayName(mailboxCursor);
                    mMailboxMessageCount = fp.getMessageCount(mailboxCursor);
                }
            } finally {
                mailboxCursor.close();
            }
        }

        /**
         * Returns the cursor position of the item with the given ID. Or {@link #UNKNOWN_POSITION}
         * if the given ID does not exist.
         */
        int getPosition(long id) {
            moveToPosition(-1);
            while(moveToNext()) {
                if (id == getId(this)) {
                    return getPosition();
                }
            }
            return UNKNOWN_POSITION;
        }

        public int getAccountCount() {
            return mAccountCount;
        }

        public int getRecentMailboxCount() {
            return mRecentCount;
        }

        public long getAccountId() {
            return mAccountId;
        }

        public String getAccountDisplayName() {
            return mAccountDisplayName;
        }

        public long getMailboxId() {
            return mMailboxId;
        }

        public String getMailboxDisplayName() {
            return mMailboxDisplayName;
        }

        public int getMailboxMessageCount() {
            return mMailboxMessageCount;
        }

        /**
         * @return {@code true} if the specified accuont exists.
         */
        public boolean accountExists() {
            return mAccountExists;
        }
    }
}
