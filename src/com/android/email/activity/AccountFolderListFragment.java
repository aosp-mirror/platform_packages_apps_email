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

import com.android.email.Controller;
import com.android.email.ControllerResultUiThreadWrapper;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.app.Activity;
import android.app.ListFragment;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.AdapterView.OnItemClickListener;

public class AccountFolderListFragment extends ListFragment
        implements OnItemClickListener, AccountsAdapter.Callback  {

    // UI Support
    private Activity mActivity;
    private ListView mListView;
    private Callback mCallback;

    // Tasks and Data
    private AccountsAdapter mListAdapter;
    private LoadAccountsTask mLoadAccountsTask;
    private Controller.Result mControllerCallback;

    private static final String FAVORITE_COUNT_SELECTION =
        MessageColumns.FLAG_FAVORITE + "= 1";
    private static final String MAILBOX_TYPE_SELECTION =
        MailboxColumns.TYPE + " =?";
    private static final String MAILBOX_ID_SELECTION =
        MessageColumns.MAILBOX_KEY + " =?";
    private static final String[] MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION = new String [] {
        "sum(" + MailboxColumns.UNREAD_COUNT + ")"
    };

    /**
     * Callback interface that owning activities must implement
     */
    public interface Callback {
        /** Called when the user clicks on a specific account */
        public void onOpenAccount(long accountId);
        /** Called when the user clicks on a specific (currently, only magic mailbox) */
        public void onOpenMailbox(long mailboxId);
        /** Called when the user clicks to open the mailbox list for a specific account */
        public void onOpenMailboxes(long accountId);
        /** Begin composing a message in a specific account, or -1 for the default account */
        public void onCompose(long accountId);
        /** Begin refreshing a specific account, or -1 for all accounts */
        public void onRefresh(long accountId);
        /** Begin edit settings for a specific account */
        public void onEditAccount(long accountId);
        /** Delete a specific account */
        public void onDeleteAccount(long accountId);
    }

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /**
     * Called to have the fragment instantiate its user interface view.
     *
     * TODO:  When supported by ListFragment, it should be possible to remove this
     * and accept the default behavior which would be to create a single ListView.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View fragmentView =
                inflater.inflate(R.layout.account_folder_list_fragment, container, false);
        return fragmentView;
    }

    /**
     * Called by activity during onCreate() to bind additional information
     * @param callback if non-null, UI clicks (e.g. refresh or open) will be delivered here
     */
    public void bindActivityInfo(Callback callback) {
        mCallback = callback;
    }

    /**
     * Called when the fragment is instantiated, but not yet displayed.
     */
    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mActivity = getActivity();

        mListView = getListView();
        mListView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_INSET);
        mListView.setOnItemClickListener(this);
        mListView.setLongClickable(true);
        registerForContextMenu(mListView);

        mControllerCallback = new ControllerResultUiThreadWrapper<ControllerResults>(
                new Handler(), new ControllerResults());
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        super.onStart();
    }

    /**
     * Called when the fragment is visible to the user and actively running.
     */
    @Override
    public void onResume() {
        super.onResume();
        Controller.getInstance(mActivity).addResultCallback(mControllerCallback);
        updateAccounts();
    }

    /**
     * Called when the fragment is no longer displayed
     */
    @Override
    public void onPause() {
        super.onPause();
        Controller.getInstance(mActivity).removeResultCallback(mControllerCallback);
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        super.onStop();
        Utility.cancelTaskInterrupt(mLoadAccountsTask);
        mLoadAccountsTask = null;
    }

    /**
     * Called when the fragment is no longer in use.
     */
   @Override
   public void onDestroy() {
        super.onDestroy();
        if (mListAdapter != null) {
            mListAdapter.changeCursor(null);
        }
   }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo info) {
        super.onCreateContextMenu(menu, v, info);
        AdapterView.AdapterContextMenuInfo menuInfo = (AdapterView.AdapterContextMenuInfo) info;
        if (mListAdapter.isMailbox(menuInfo.position)) {
            Cursor c = (Cursor) mListView.getItemAtPosition(menuInfo.position);
            String displayName = c.getString(Account.CONTENT_DISPLAY_NAME_COLUMN);
            menu.setHeaderTitle(displayName);
            mActivity.getMenuInflater()
                    .inflate(R.menu.account_folder_list_smart_folder_context, menu);
        } else if (mListAdapter.isAccount(menuInfo.position)) {
            Cursor c = (Cursor) mListView.getItemAtPosition(menuInfo.position);
            String accountName = c.getString(Account.CONTENT_DISPLAY_NAME_COLUMN);
            menu.setHeaderTitle(accountName);
            mActivity.getMenuInflater().inflate(R.menu.account_folder_list_context, menu);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo menuInfo =
            (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();

        // Drop the event if there's nowhere to send it (it's probably late-arriving)
        if (mCallback == null) {
            return false;
        }

        if (mListAdapter.isMailbox(menuInfo.position)) {
            Cursor c = (Cursor) mListView.getItemAtPosition(menuInfo.position);
            long id = c.getLong(AccountsAdapter.MAILBOX_COLUMN_ID);
            switch (item.getItemId()) {
                case R.id.open_folder:
                    mCallback.onOpenMailbox(id);
                    break;
                case R.id.check_mail:
                    mCallback.onRefresh(-1);
                    break;
            }
            return false;
        } else if (mListAdapter.isAccount(menuInfo.position)) {
            Cursor c = (Cursor) mListView.getItemAtPosition(menuInfo.position);
            long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
            switch (item.getItemId()) {
                case R.id.open_folder:
                    mCallback.onOpenAccount(accountId);
                    break;
                case R.id.compose:
                    mCallback.onCompose(accountId);
                    break;
                case R.id.refresh_account:
                    mCallback.onRefresh(accountId);
                    break;
                case R.id.edit_account:
                    mCallback.onEditAccount(accountId);
                    break;
                case R.id.delete_account:
                    mCallback.onDeleteAccount(accountId);
                    break;
            }
            return true;
        }
        return false;
    }

    /* Implements OnItemClickListener.onItemClick */
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        // Drop the event if there's nowhere to send it (it's probably late-arriving)
        if (mCallback == null) {
            return;
        }

        if (mListAdapter.isMailbox(position)) {
            mCallback.onOpenMailbox(id);
        } else if (mListAdapter.isAccount(position)) {
            mCallback.onOpenAccount(id);
        }
    }

    /* Implements AccountsAdapter.Controller */
    public void onClickAccountFolders(long accountId) {
        if (mCallback != null) {
            mCallback.onOpenMailboxes(accountId);
        }
    }

    /**
     * Trigger accounts list reload
     */
    private void updateAccounts() {
        Utility.cancelTaskInterrupt(mLoadAccountsTask);
        mLoadAccountsTask = (LoadAccountsTask) new LoadAccountsTask().execute();
    }

    /**
     * Called by container to mark an account as "being deleted" (we quickly hide it)
     */
    public void hideDeletingAccount(long accountId) {
        mListAdapter.addOnDeletingAccount(accountId);
    }

    private static int getUnreadCountByMailboxType(Context context, int type) {
        int count = 0;
        Cursor c = context.getContentResolver().query(Mailbox.CONTENT_URI,
                MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION,
                MAILBOX_TYPE_SELECTION,
                new String[] { String.valueOf(type) }, null);

        try {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } finally {
            c.close();
        }
        return count;
    }

    private static int getCountByMailboxType(Context context, int type) {
        int count = 0;
        Cursor c = context.getContentResolver().query(Mailbox.CONTENT_URI,
                EmailContent.ID_PROJECTION, MAILBOX_TYPE_SELECTION,
                new String[] { String.valueOf(type) }, null);

        try {
            c.moveToPosition(-1);
            while (c.moveToNext()) {
                count += EmailContent.count(context, Message.CONTENT_URI,
                        MAILBOX_ID_SELECTION,
                        new String[] {
                            String.valueOf(c.getLong(EmailContent.ID_PROJECTION_COLUMN)) });
            }
        } finally {
            c.close();
        }
        return count;
    }

    /**
     * Build the group and child cursors that support the summary views (aka "at a glance").
     *
     * This is a placeholder implementation with significant problems that need to be addressed:
     *
     * TODO: We should only show summary mailboxes if they are non-empty.  So there needs to be
     * a more dynamic child-cursor here, probably listening for update notifications on a number
     * of other internally-held queries such as count-of-inbox, count-of-unread, etc.
     *
     * TODO: This simple list is incomplete.  For example, we probably want drafts, outbox, and
     * (maybe) sent (again, these would be displayed only when non-empty).
     *
     * TODO: We need a way to count total unread in all inboxes (probably with some provider help)
     *
     * TODO: We need a way to count total # messages in all other summary boxes (probably with
     * some provider help).
     *
     * TODO use narrower account projection (see LoadAccountsTask)
     */
    private MatrixCursor getSummaryChildCursor() {
        MatrixCursor childCursor = new MatrixCursor(AccountsAdapter.MAILBOX_PROJECTION);
        int count;
        RowBuilder row;
        // TYPE_INBOX
        count = getUnreadCountByMailboxType(mActivity, Mailbox.TYPE_INBOX);
        row = childCursor.newRow();
        row.add(Long.valueOf(Mailbox.QUERY_ALL_INBOXES));           // MAILBOX_COLUMN_ID = 0;
                                                                    // MAILBOX_DISPLAY_NAME
        row.add(mActivity.getString(R.string.account_folder_list_summary_inbox));
        row.add(null);                                              // MAILBOX_ACCOUNT_KEY = 2;
        row.add(Integer.valueOf(Mailbox.TYPE_INBOX));               // MAILBOX_TYPE = 3;
        row.add(Integer.valueOf(count));                            // MAILBOX_UNREAD_COUNT = 4;
        // TYPE_MAIL (FAVORITES)
        count = EmailContent.count(mActivity, Message.CONTENT_URI, FAVORITE_COUNT_SELECTION, null);
        if (count > 0) {
            row = childCursor.newRow();
            row.add(Long.valueOf(Mailbox.QUERY_ALL_FAVORITES));     // MAILBOX_COLUMN_ID = 0;
                                                                    // MAILBOX_DISPLAY_NAME
            row.add(mActivity.getString(R.string.account_folder_list_summary_starred));
            row.add(null);                                          // MAILBOX_ACCOUNT_KEY = 2;
            row.add(Integer.valueOf(Mailbox.TYPE_MAIL));            // MAILBOX_TYPE = 3;
            row.add(Integer.valueOf(count));                        // MAILBOX_UNREAD_COUNT = 4;
        }
        // TYPE_DRAFTS
        count = getCountByMailboxType(mActivity, Mailbox.TYPE_DRAFTS);
        if (count > 0) {
            row = childCursor.newRow();
            row.add(Long.valueOf(Mailbox.QUERY_ALL_DRAFTS));        // MAILBOX_COLUMN_ID = 0;
                                                                    // MAILBOX_DISPLAY_NAME
            row.add(mActivity.getString(R.string.account_folder_list_summary_drafts));
            row.add(null);                                          // MAILBOX_ACCOUNT_KEY = 2;
            row.add(Integer.valueOf(Mailbox.TYPE_DRAFTS));          // MAILBOX_TYPE = 3;
            row.add(Integer.valueOf(count));                        // MAILBOX_UNREAD_COUNT = 4;
        }
        // TYPE_OUTBOX
        count = getCountByMailboxType(mActivity, Mailbox.TYPE_OUTBOX);
        if (count > 0) {
            row = childCursor.newRow();
            row.add(Long.valueOf(Mailbox.QUERY_ALL_OUTBOX));        // MAILBOX_COLUMN_ID = 0;
                                                                    // MAILBOX_DISPLAY_NAME
            row.add(mActivity.getString(R.string.account_folder_list_summary_outbox));
            row.add(null);                                          // MAILBOX_ACCOUNT_KEY = 2;
            row.add(Integer.valueOf(Mailbox.TYPE_OUTBOX));          // MAILBOX_TYPE = 3;
            row.add(Integer.valueOf(count));                        // MAILBOX_UNREAD_COUNT = 4;
        }
        return childCursor;
    }

    /**
     * Async task to handle the accounts query outside of the UI thread
     */
    private class LoadAccountsTask extends AsyncTask<Void, Void, Object[]> {
        @Override
        protected Object[] doInBackground(Void... params) {
            // Create the summaries cursor
            Cursor c1 = getSummaryChildCursor();

            // TODO use a custom projection and don't have to sample all of these columns
            Cursor c2 = mActivity.getContentResolver().query(
                    EmailContent.Account.CONTENT_URI,
                    EmailContent.Account.CONTENT_PROJECTION, null, null, null);
            Long defaultAccount = Account.getDefaultAccountId(mActivity);
            return new Object[] { c1, c2 , defaultAccount};
        }

        @Override
        protected void onPostExecute(Object[] params) {
            if (isCancelled() || params == null || ((Cursor)params[1]).isClosed()) {
                return;
            }
            // Before writing a new list adapter into the listview, we need to
            // shut down the old one (if any).
            ListAdapter oldAdapter = mListView.getAdapter();
            if (oldAdapter != null && oldAdapter instanceof CursorAdapter) {
                ((CursorAdapter)oldAdapter).changeCursor(null);
            }
            // Now create a new list adapter and install it
            mListAdapter = AccountsAdapter.getInstance((Cursor)params[0], (Cursor)params[1],
                    mActivity, (Long)params[2], AccountFolderListFragment.this);
            mListView.setAdapter(mListAdapter);
        }
    }

    /**
     * Controller results listener.  We wrap it with {@link ControllerResultUiThreadWrapper},
     * so all methods are called on the UI thread.
     */
    private class ControllerResults extends Controller.Result {
        @Override
        public void updateMailboxCallback(MessagingException result, long accountKey,
                long mailboxKey, int progress, int numNewMessages) {
            if (progress == 100) {
                updateAccounts();
            }
        }

        @Override
        public void sendMailCallback(MessagingException result, long accountId, long messageId,
                int progress) {
            if (progress == 100) {
                updateAccounts();
            }
        }

        @Override
        public void deleteAccountCallback(long accountId) {
            updateAccounts();
        }
    }

}
