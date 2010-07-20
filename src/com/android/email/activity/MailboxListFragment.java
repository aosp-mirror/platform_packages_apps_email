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
import com.android.email.Utility;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.app.Activity;
import android.app.ListFragment;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

/**
 * This fragment presents a list of mailboxes for a given account.  The "API" includes the
 * following elements which must be provided by the host Activity.
 *
 *  - call bindActivityInfo() to provide the account ID and set callbacks
 *  - provide callbacks for onOpen and onRefresh
 *  - pass-through implementations of onCreateContextMenu() and onContextItemSelected() (temporary)
 */
public class MailboxListFragment extends ListFragment implements OnItemClickListener {

    private static final String MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?" +
            " AND " + MailboxColumns.TYPE + "<" + Mailbox.TYPE_NOT_EMAIL +
            " AND " + MailboxColumns.FLAG_VISIBLE + "=1";
    private static final String MESSAGE_MAILBOX_ID_SELECTION = MessageColumns.MAILBOX_KEY + "=?";

    // Account & mailboxes access
    private long mAccountId;
    private LoadMailboxesTask mLoadMailboxesTask;
    private MessageCountTask mMessageCountTask;
    private long mDraftMailboxKey = -1;
    private long mTrashMailboxKey = -1;

    // UI Support
    private Activity mActivity;
    private MailboxesAdapter mListAdapter;
    private Callback mCallback;

    /**
     * Callback interface that owning activities must implement
     */
    public interface Callback {
        public void onRefresh(long accountId, long mailboxId);
        public void onOpen(long accountId, long mailboxId);
    }

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mActivity = getActivity();

        ListView listView = getListView();
        listView.setOnItemClickListener(this);
        listView.setItemsCanFocus(false);
        registerForContextMenu(listView);

        mListAdapter = new MailboxesAdapter(mActivity);
    }

    /**
     * Called by activity during onCreate() to bind additional information
     * @param accountId the account we're looking at
     * @param callback if non-null, UI clicks (e.g. refresh or open) will be delivered here
     */
    public void bindActivityInfo(long accountId, Callback callback) {
        mAccountId = accountId;
        mCallback = callback;
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        super.onStart();
        mLoadMailboxesTask = new LoadMailboxesTask(mAccountId);
        mLoadMailboxesTask.execute();
    }

    /**
     * Called when the fragment is visible to the user and actively running.
     */
    @Override
    public void onResume() {
        super.onResume();
        updateMessageCount();
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        super.onStop();
        Utility.cancelTaskInterrupt(mLoadMailboxesTask);
        mLoadMailboxesTask = null;
        Utility.cancelTaskInterrupt(mMessageCountTask);
        mMessageCountTask = null;
    }

    /**
     * Called when the fragment is no longer in use.
     */
   @Override
   public void onDestroy() {
        super.onDestroy();

        mListAdapter.changeCursor(null);
   }

    /**
     * This is called via the activity
     * TODO This will be removed when possible
     */
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo info) {
        AdapterView.AdapterContextMenuInfo menuInfo = (AdapterView.AdapterContextMenuInfo) info;
        Cursor c = (Cursor) getListView().getItemAtPosition(menuInfo.position);
        String folderName = Utility.FolderProperties.getInstance(mActivity)
                .getDisplayName(Integer.valueOf(c.getString(mListAdapter.COLUMN_TYPE)));
        if (folderName == null) {
            folderName = c.getString(mListAdapter.COLUMN_DISPLAY_NAME);
        }

        menu.setHeaderTitle(folderName);
        mActivity.getMenuInflater().inflate(R.menu.mailbox_list_context, menu);
    }

    /**
     * This is called via the activity
     * TODO This will be removed when possible
     */
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info =
            (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();

        switch (item.getItemId()) {
            case R.id.refresh:
                if (mCallback != null) {
                    mCallback.onRefresh(mAccountId, info.id);
                }
                return true;
            case R.id.open:
                if (mCallback != null) {
                    mCallback.onOpen(mAccountId, info.id);
                }
                return true;
        }
        return false;
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (mCallback != null) {
            mCallback.onOpen(mAccountId, id);
        }
    }

    /**
     * Async task for loading the mailboxes for a given account
     */
    private class LoadMailboxesTask extends AsyncTask<Void, Void, Object[]> {

        private long mAccountKey;

        /**
         * Special constructor to cache some local info
         */
        public LoadMailboxesTask(long accountId) {
            mAccountKey = accountId;
        }

        @Override
        protected Object[] doInBackground(Void... params) {
            long draftMailboxKey = -1;
            long trashMailboxKey = -1;
            Cursor c = mActivity.managedQuery(
                    EmailContent.Mailbox.CONTENT_URI,
                    mListAdapter.PROJECTION,
                    MAILBOX_SELECTION,
                    new String[] { String.valueOf(mAccountKey) },
                    MailboxColumns.TYPE + "," + MailboxColumns.DISPLAY_NAME);
            c.moveToPosition(-1);
            while (c.moveToNext()) {
                long mailboxId = c.getInt(mListAdapter.COLUMN_ID);
                switch (c.getInt(mListAdapter.COLUMN_TYPE)) {
                case Mailbox.TYPE_DRAFTS:
                    draftMailboxKey = mailboxId;
                    break;
                case Mailbox.TYPE_TRASH:
                    trashMailboxKey = mailboxId;
                    break;
                }
            }
            Object[] result = new Object[3];
            result[0] = c;
            result[1] = draftMailboxKey;
            result[2] = trashMailboxKey;
            return result;
        }

        @Override
        protected void onPostExecute(Object[] results) {
            if (results == null) return;
            Cursor cursor = (Cursor) results[0];
            mDraftMailboxKey = (Long) results[1];
            mTrashMailboxKey = (Long) results[2];

            if (cursor.isClosed()) return;
            mListAdapter.changeCursor(cursor);
            setListAdapter(mListAdapter);
            updateMessageCount();
        }
    }

    private class MessageCountTask extends AsyncTask<Void, Void, int[]> {

        @Override
        protected int[] doInBackground(Void... params) {
            int[] counts = new int[2];
            if (mDraftMailboxKey != -1) {
                counts[0] = EmailContent.count(mActivity, Message.CONTENT_URI,
                        MESSAGE_MAILBOX_ID_SELECTION,
                        new String[] { String.valueOf(mDraftMailboxKey)});
            } else {
                counts[0] = 0;
            }
            if (mTrashMailboxKey != -1) {
                counts[1] = EmailContent.count(mActivity, Message.CONTENT_URI,
                        MESSAGE_MAILBOX_ID_SELECTION,
                        new String[] { String.valueOf(mTrashMailboxKey)});
            } else {
                counts[1] = 0;
            }
            return counts;
        }

        @Override
        protected void onPostExecute(int[] counts) {
            if (counts == null) {
                return;
            }
            int countDraft = counts[0];
            int countTrash = counts[1];
            mListAdapter.setMessageCounts(countDraft, countTrash);
        }
    }

    private void updateMessageCount() {
        if (mAccountId == -1 || mListAdapter.getCursor() == null) {
            return;
        }
        if (mMessageCountTask != null
                && mMessageCountTask.getStatus() != MessageCountTask.Status.FINISHED) {
            mMessageCountTask.cancel(true);
        }
        mMessageCountTask = (MessageCountTask) new MessageCountTask().execute();
    }
}
