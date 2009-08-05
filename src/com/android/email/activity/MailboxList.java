/*
 * Copyright (C) 2009 The Android Open Source Project
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
import com.android.email.R;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;

import android.app.ListActivity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.AdapterView.OnItemClickListener;

public class MailboxList extends ListActivity implements OnItemClickListener, OnClickListener {

    // Intent extras (internal to this activity)
    private static final String EXTRA_ACCOUNT_ID = "com.android.email.activity._ACCOUNT_ID";

    private static final String MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?"
        + " AND " + MailboxColumns.TYPE + "<" + Mailbox.TYPE_NOT_EMAIL
        + " AND " + MailboxColumns.FLAG_VISIBLE + "=1";
    // UI support
    private ListView mListView;
    private TextView mAccountNameView;
    private TextView mAccountStatusView;
    private View mRefreshButton;
    private View mProgress;

    private MailboxListAdapter mListAdapter;
    private MailboxListHandler mHandler = new MailboxListHandler();
    private ControllerResults mControllerCallback = new ControllerResults();

    private static final int[] mColorChipResIds = new int[] {
        R.drawable.appointment_indicator_leftside_1,
        R.drawable.appointment_indicator_leftside_2,
        R.drawable.appointment_indicator_leftside_3,
        R.drawable.appointment_indicator_leftside_4,
        R.drawable.appointment_indicator_leftside_5,
        R.drawable.appointment_indicator_leftside_6,
        R.drawable.appointment_indicator_leftside_7,
        R.drawable.appointment_indicator_leftside_8,
        R.drawable.appointment_indicator_leftside_9,
        R.drawable.appointment_indicator_leftside_10,
        R.drawable.appointment_indicator_leftside_11,
        R.drawable.appointment_indicator_leftside_12,
        R.drawable.appointment_indicator_leftside_13,
        R.drawable.appointment_indicator_leftside_14,
        R.drawable.appointment_indicator_leftside_15,
        R.drawable.appointment_indicator_leftside_16,
        R.drawable.appointment_indicator_leftside_17,
        R.drawable.appointment_indicator_leftside_18,
        R.drawable.appointment_indicator_leftside_19,
        R.drawable.appointment_indicator_leftside_20,
        R.drawable.appointment_indicator_leftside_21,
    };

    // DB access
    private long mAccountId;
    private LoadMailboxesTask mLoadMailboxesTask;

    /**
     * Open a specific account.
     * 
     * @param context
     * @param accountId the account to view
     */
    public static void actionHandleAccount(Context context, long accountId) {
        Intent intent = new Intent(context, MailboxList.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        context.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        setContentView(R.layout.mailbox_list);
        mListView = getListView();
        findViewById(R.id.button_compose).setOnClickListener(this);
        mRefreshButton = findViewById(R.id.button_refresh);
        mRefreshButton.setOnClickListener(this);
        mAccountNameView = (TextView) findViewById(R.id.account_name);
        mAccountStatusView = (TextView) findViewById(R.id.account_status);
        mProgress = findViewById(R.id.progress);

        mListView.setOnItemClickListener(this);
        mListView.setItemsCanFocus(false);
        registerForContextMenu(mListView);

        mListAdapter = new MailboxListAdapter(this);
        setListAdapter(mListAdapter);

        mAccountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, -1);
        if (mAccountId != -1) {
            mLoadMailboxesTask = new LoadMailboxesTask(mAccountId);
            mLoadMailboxesTask.execute();
        } else {
            finish();
        }

        // setup fat fitle - color chip, name, status, refresh/progress
        int chipResId = mColorChipResIds[(int)mAccountId % mColorChipResIds.length];
        findViewById(R.id.chip).setBackgroundResource(chipResId);
        mAccountStatusView.setVisibility(View.GONE);
        mProgress.setVisibility(View.GONE);

        // Go to the database for the account name
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                String result = null;
                Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, mAccountId);
                Cursor c = MailboxList.this.getContentResolver().query(
                        uri, new String[] { AccountColumns.DISPLAY_NAME }, null, null, null);
                try {
                    if (c.moveToFirst()) {
                        result = c.getString(0);
                    }
                } finally {
                    c.close();
                }
                return result;
            }
 
            @Override
            protected void onPostExecute(String result) {
                if (result == null) {
                    // something is wrong with this account
                    finish();
                }
                mAccountNameView.setText(result);
            }

        }.execute();
    }

    @Override
    public void onPause() {
        super.onPause();
        Controller.getInstance(getApplication()).removeResultCallback(mControllerCallback);
    }

    @Override
    public void onResume() {
        super.onResume();
        Controller.getInstance(getApplication()).addResultCallback(mControllerCallback);

        // TODO: may need to clear notifications here
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mLoadMailboxesTask != null &&
                mLoadMailboxesTask.getStatus() != LoadMailboxesTask.Status.FINISHED) {
            mLoadMailboxesTask.cancel(true);
            mLoadMailboxesTask = null;
        }
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        onOpenMailbox(id);
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.button_compose:
                onCompose();
                break;
            case R.id.button_refresh:
                onRefresh(-1);
                break;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.mailbox_list_option, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                onRefresh(-1);
                return true;
            case R.id.accounts:
                onAccounts();
                return true;
            case R.id.compose:
                onCompose();
                return true;
            case R.id.account_settings:
                onEditAccount();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        getMenuInflater().inflate(R.menu.mailbox_list_context, menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info =
            (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();

        switch (item.getItemId()) {
            case R.id.refresh:
                onRefresh(info.id);
                break;
        }
        return super.onContextItemSelected(item);
    }

    /**
     * Refresh the mailbox list, or a single mailbox
     * @param mailboxId -1 for all
     */
    private void onRefresh(long mailboxId) {
        Controller controller = Controller.getInstance(getApplication());
        mHandler.progress(true);
        if (mailboxId >= 0) {
            Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mailboxId);
            controller.updateMailbox(mAccountId, mailbox, mControllerCallback);
        } else {
            controller.updateMailboxList(mAccountId, mControllerCallback);
        }
    }

    private void onAccounts() {
        AccountFolderList.actionShowAccounts(this);
        finish();
    }

    private void onEditAccount() {
        AccountSettings.actionSettings(this, mAccountId);
    }

    private void onOpenMailbox(long mailboxId) {
        MessageList.actionHandleAccount(this, mailboxId, null, null);
    }

    private void onCompose() {
        MessageCompose.actionCompose(this, mAccountId);
    }

    /**
     * Async task for loading the mailboxes for a given account
     */
    private class LoadMailboxesTask extends AsyncTask<Void, Void, Cursor> {

        private long mAccountKey;

        /**
         * Special constructor to cache some local info
         */
        public LoadMailboxesTask(long accountId) {
            mAccountKey = accountId;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            return MailboxList.this.managedQuery(
                    EmailContent.Mailbox.CONTENT_URI,
                    MailboxList.this.mListAdapter.PROJECTION,
                    MAILBOX_SELECTION,
                    new String[] { String.valueOf(mAccountKey) },
                    MailboxColumns.TYPE);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            MailboxList.this.mListAdapter.changeCursor(cursor);
        }
    }

    /**
     * Handler for UI-thread operations (when called from callbacks or any other threads)
     */
    class MailboxListHandler extends Handler {
        private static final int MSG_PROGRESS = 1;

        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_PROGRESS:
                    boolean showProgress = (msg.arg1 != 0);
                    if (showProgress) {
                        mRefreshButton.setVisibility(View.GONE);
                        mProgress.setVisibility(View.VISIBLE);
                    } else {
                        mRefreshButton.setVisibility(View.VISIBLE);
                        mProgress.setVisibility(View.GONE);
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }

        /**
         * Call from any thread to start/stop progress indicator(s)
         * @param progress true to start, false to stop
         */
        public void progress(boolean progress) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_PROGRESS;
            msg.arg1 = progress ? 1 : 0;
            sendMessage(msg);
        }
    }

    /**
     * Callback for async Controller results.
     */
    private class ControllerResults implements Controller.Result {

        // TODO report errors into UI
        public void updateMailboxListCallback(MessagingException result, long accountKey,
                int progress) {
            if (accountKey == mAccountId) {
                if (progress == 0) {
                    mHandler.progress(true);
                }
                else if (result != null || progress == 100) {
                    mHandler.progress(false);
                }
            }
        }

        // TODO report errors into UI
        public void updateMailboxCallback(MessagingException result, long accountKey,
                long mailboxKey, int progress, int totalMessagesInMailbox, int numNewMessages) {
            if (accountKey == mAccountId) {
                if (progress == 0) {
                    mHandler.progress(true);
                }
                else if (result != null || progress == 100) {
                    mHandler.progress(false);
                }
            }
        }

        public void loadAttachmentCallback(MessagingException result, long messageId,
                long attachmentId, int progress) {
        }
    }

    /**
     * The adapter for displaying mailboxes.
     */
    /* package */ class MailboxListAdapter extends CursorAdapter {

        public final String[] PROJECTION = new String[] { MailboxColumns.ID,
                MailboxColumns.DISPLAY_NAME, MailboxColumns.UNREAD_COUNT, MailboxColumns.TYPE };
        private final int COLUMN_DISPLAY_NAME = 1;
        private final int COLUMN_UNREAD_COUNT = 2;

        Context mContext;
        private LayoutInflater mInflater;

        public MailboxListAdapter(Context context) {
            super(context, null);
            mContext = context;
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            // TODO translation by mailbox type
            String text = cursor.getString(COLUMN_DISPLAY_NAME);
            if (text != null) {
                TextView nameView = (TextView) view.findViewById(R.id.mailbox_name);
                nameView.setText(text);
            }

            // TODO get/track live folder status
            text = null;
            TextView statusView = (TextView) view.findViewById(R.id.mailbox_status);
            if (text != null) {
                statusView.setText(text);
                statusView.setVisibility(View.VISIBLE);
            } else {
                statusView.setVisibility(View.GONE);
            }

            // TODO do we use a different count for special mailboxes (total count vs. unread)
            int count = -1;
            text = cursor.getString(COLUMN_UNREAD_COUNT);
            if (text != null) {
                count = Integer.valueOf(text);
            }
            TextView countView = (TextView) view.findViewById(R.id.new_message_count);
            // If the unread count is zero, not to show countView.
            if (count > 0) {
                countView.setVisibility(View.VISIBLE);
                countView.setText(text);
            } else {
                countView.setVisibility(View.GONE);
            }

        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return mInflater.inflate(R.layout.mailbox_list_item, parent, false);
        }
    }
}
