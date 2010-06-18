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

import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;

import android.content.Context;
import android.database.Cursor;
import android.database.MergeCursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Adapter that presents a combined list of smart mailboxes (e.g. combined inbox, all drafts, etc),
 * a non-selectable separator, and the list of accounts.
 */
public class AccountsAdapter extends CursorAdapter {

    /**
     * Reduced mailbox projection used by AccountsAdapter
     */
    public final static String[] MAILBOX_PROJECTION = new String[] {
        EmailContent.RECORD_ID, MailboxColumns.DISPLAY_NAME,
        MailboxColumns.ACCOUNT_KEY, MailboxColumns.TYPE,
        MailboxColumns.UNREAD_COUNT,
        MailboxColumns.FLAG_VISIBLE, MailboxColumns.FLAGS
    };
    public final static int MAILBOX_COLUMN_ID = 0;
    public final static int MAILBOX_DISPLAY_NAME = 1;
    public final static int MAILBOX_ACCOUNT_KEY = 2;
    public final static int MAILBOX_TYPE = 3;
    public final static int MAILBOX_UNREAD_COUNT = 4;
    public final static int MAILBOX_FLAG_VISIBLE = 5;
    public final static int MAILBOX_FLAGS = 6;

    private static final String[] MAILBOX_UNREAD_COUNT_PROJECTION = new String [] {
        MailboxColumns.UNREAD_COUNT
    };
    private static final int MAILBOX_UNREAD_COUNT_COLUMN_UNREAD_COUNT = 0;

    private static final String MAILBOX_INBOX_SELECTION =
        MailboxColumns.ACCOUNT_KEY + " =?" + " AND " + MailboxColumns.TYPE +" = "
        + Mailbox.TYPE_INBOX;

    private final LayoutInflater mInflater;
    private final int mMailboxesCount;
    private final int mSeparatorPosition;
    private final long mDefaultAccountId;
    private final ArrayList<Long> mOnDeletingAccounts = new ArrayList<Long>();
    private Callback mCallback;

    public static AccountsAdapter getInstance(Cursor mailboxesCursor, Cursor accountsCursor,
            Context context, long defaultAccountId, Callback callback) {
        Cursor[] cursors = new Cursor[] { mailboxesCursor, accountsCursor };
        Cursor mc = new MergeCursor(cursors);
        return new AccountsAdapter(mc, context, mailboxesCursor.getCount(), defaultAccountId,
                callback);
    }

    private AccountsAdapter(Cursor c, Context context, int mailboxesCount, long defaultAccountId,
            Callback callback) {
        super(context, c, true);
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mMailboxesCount = mailboxesCount;
        mSeparatorPosition = mailboxesCount;
        mDefaultAccountId = defaultAccountId;
        mCallback = callback;
    }

    /**
     * When changeCursor(null) is called, drop reference(s) to make sure we don't leak the activity
     */
    @Override
    public void changeCursor(Cursor cursor) {
        super.changeCursor(cursor);
        if (cursor == null) {
            mCallback = null;
        }
    }

    /**
     * Callback interface used to report clicks other than the basic list item click or longpress.
     */
    public interface Callback {
        /**
         * Callback for clicks on the "folder" icon (to open MailboxList)
         */
        public void onClickAccountFolders(long accountId);
    }

    public boolean isMailbox(int position) {
        return position < mMailboxesCount;
    }

    public boolean isAccount(int position) {
        return position > mMailboxesCount;
    }

    public void addOnDeletingAccount(long accountId) {
        mOnDeletingAccounts.add(accountId);
    }

    public boolean isOnDeletingAccountView(long accountId) {
        return mOnDeletingAccounts.contains(accountId);
    }

    /**
     * This is an entry point called by the list item for clicks in the folder "button"
     *
     * @param itemView the item in which the click occurred
     */
    public void onClickFolder(AccountFolderListItem itemView) {
        if (mCallback != null) {
            mCallback.onClickAccountFolders(itemView.mAccountId);
        }
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        if (cursor.getPosition() < mMailboxesCount) {
            bindMailboxItem(view, context, cursor, false);
        } else {
            bindAccountItem(view, context, cursor, false);
        }
    }

    private void bindMailboxItem(View view, Context context, Cursor cursor, boolean isLastChild)
            {
        // Reset the view (in case it was recycled) and prepare for binding
        AccountFolderListItem itemView = (AccountFolderListItem) view;
        itemView.bindViewInit(this, false);

        // Invisible (not "gone") to maintain spacing
        view.findViewById(R.id.chip).setVisibility(View.INVISIBLE);

        String text = cursor.getString(MAILBOX_DISPLAY_NAME);
        if (text != null) {
            TextView nameView = (TextView) view.findViewById(R.id.name);
            nameView.setText(text);
        }

        // TODO get/track live folder status
        text = null;
        TextView statusView = (TextView) view.findViewById(R.id.status);
        if (text != null) {
            statusView.setText(text);
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
        }

        int count = -1;
        text = cursor.getString(MAILBOX_UNREAD_COUNT);
        if (text != null) {
            count = Integer.valueOf(text);
        }
        TextView unreadCountView = (TextView) view.findViewById(R.id.new_message_count);
        TextView allCountView = (TextView) view.findViewById(R.id.all_message_count);
        int id = cursor.getInt(MAILBOX_COLUMN_ID);
        // If the unread count is zero, not to show countView.
        if (count > 0) {
            if (id == Mailbox.QUERY_ALL_FAVORITES
                    || id == Mailbox.QUERY_ALL_DRAFTS
                    || id == Mailbox.QUERY_ALL_OUTBOX) {
                unreadCountView.setVisibility(View.GONE);
                allCountView.setVisibility(View.VISIBLE);
                allCountView.setText(text);
            } else {
                allCountView.setVisibility(View.GONE);
                unreadCountView.setVisibility(View.VISIBLE);
                unreadCountView.setText(text);
            }
        } else {
            allCountView.setVisibility(View.GONE);
            unreadCountView.setVisibility(View.GONE);
        }

        view.findViewById(R.id.folder_button).setVisibility(View.GONE);
        view.findViewById(R.id.folder_separator).setVisibility(View.GONE);
        view.findViewById(R.id.default_sender).setVisibility(View.GONE);
        view.findViewById(R.id.folder_icon).setVisibility(View.VISIBLE);
        ((ImageView)view.findViewById(R.id.folder_icon)).setImageDrawable(
                Utility.FolderProperties.getInstance(context).getSummaryMailboxIconIds(id));
    }

    private void bindAccountItem(View view, Context context, Cursor cursor, boolean isExpanded)
            {
        // Reset the view (in case it was recycled) and prepare for binding
        AccountFolderListItem itemView = (AccountFolderListItem) view;
        itemView.bindViewInit(this, true);
        itemView.mAccountId = cursor.getLong(Account.CONTENT_ID_COLUMN);

        long accountId = cursor.getLong(Account.CONTENT_ID_COLUMN);
        View chipView = view.findViewById(R.id.chip);
        chipView.setBackgroundResource(Email.getAccountColorResourceId(accountId));
        chipView.setVisibility(View.VISIBLE);

        String text = cursor.getString(Account.CONTENT_DISPLAY_NAME_COLUMN);
        if (text != null) {
            TextView descriptionView = (TextView) view.findViewById(R.id.name);
            descriptionView.setText(text);
        }

        text = cursor.getString(Account.CONTENT_EMAIL_ADDRESS_COLUMN);
        if (text != null) {
            TextView emailView = (TextView) view.findViewById(R.id.status);
            emailView.setText(text);
            emailView.setVisibility(View.VISIBLE);
        }

        // TODO: We should not be doing a query inside bindAccountItem
        int unreadMessageCount = 0;
        Cursor c = context.getContentResolver().query(Mailbox.CONTENT_URI,
                MAILBOX_UNREAD_COUNT_PROJECTION,
                MAILBOX_INBOX_SELECTION,
                new String[] { String.valueOf(accountId) }, null);

        try {
            if (c.moveToFirst()) {
                String count = c.getString(MAILBOX_UNREAD_COUNT_COLUMN_UNREAD_COUNT);
                if (count != null) {
                    unreadMessageCount = Integer.valueOf(count);
                }
            }
        } finally {
            c.close();
        }

        view.findViewById(R.id.all_message_count).setVisibility(View.GONE);
        TextView unreadCountView = (TextView) view.findViewById(R.id.new_message_count);
        if (unreadMessageCount > 0) {
            unreadCountView.setText(String.valueOf(unreadMessageCount));
            unreadCountView.setVisibility(View.VISIBLE);
        } else {
            unreadCountView.setVisibility(View.GONE);
        }

        view.findViewById(R.id.folder_icon).setVisibility(View.GONE);
        view.findViewById(R.id.folder_button).setVisibility(View.VISIBLE);
        view.findViewById(R.id.folder_separator).setVisibility(View.VISIBLE);
        if (accountId == mDefaultAccountId) {
            view.findViewById(R.id.default_sender).setVisibility(View.VISIBLE);
        } else {
            view.findViewById(R.id.default_sender).setVisibility(View.GONE);
        }
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(R.layout.account_folder_list_item, parent, false);
    }

    /*
     * The following series of overrides insert the "Accounts" separator
     */

    /**
     * Prevents the separator view from recycling into the other views
     */
    @Override
    public int getItemViewType(int position) {
        if (position == mSeparatorPosition) {
            return IGNORE_ITEM_VIEW_TYPE;
        }
        return super.getItemViewType(position);
    }

    /**
     * Injects the separator view when required, and fudges the cursor for other views
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        // The base class's getView() checks for mDataValid at the beginning, but we don't have
        // to do that, because if the cursor is invalid getCount() returns 0, in which case this
        // method wouldn't get called.

        // Handle the separator here - create & bind
        if (position == mSeparatorPosition) {
            TextView view;
            view = (TextView) mInflater.inflate(R.layout.list_separator, parent, false);
            view.setText(R.string.account_folder_list_separator_accounts);
            return view;
        }
        return super.getView(getRealPosition(position), convertView, parent);
    }

    /**
     * Forces navigation to skip over the separator
     */
    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    /**
     * Forces navigation to skip over the separator
     */
    @Override
    public boolean isEnabled(int position) {
        if (position == mSeparatorPosition) {
            return false;
        } else if (isAccount(position)) {
            Long id = ((MergeCursor)getItem(position)).getLong(Account.CONTENT_ID_COLUMN);
            return !isOnDeletingAccountView(id);
        } else {
            return true;
        }
    }

    /**
     * Adjusts list count to include separator
     */
    @Override
    public int getCount() {
        int count = super.getCount();
        if (count > 0 && (mSeparatorPosition != ListView.INVALID_POSITION)) {
            // Increment for separator, if we have anything to show.
            count += 1;
        }
        return count;
    }

    /**
     * Converts list position to cursor position
     */
    private int getRealPosition(int pos) {
        if (mSeparatorPosition == ListView.INVALID_POSITION) {
            // No separator, identity map
            return pos;
        } else if (pos <= mSeparatorPosition) {
            // Before or at the separator, identity map
            return pos;
        } else {
            // After the separator, remove 1 from the pos to get the real underlying pos
            return pos - 1;
        }
    }

    /**
     * Returns the item using external position numbering (no separator)
     */
    @Override
    public Object getItem(int pos) {
        return super.getItem(getRealPosition(pos));
    }

    /**
     * Returns the item id using external position numbering (no separator)
     */
    @Override
    public long getItemId(int pos) {
        return super.getItemId(getRealPosition(pos));
    }
}

