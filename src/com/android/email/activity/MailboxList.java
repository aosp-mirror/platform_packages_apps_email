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
import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.mail.AuthenticationFailedException;
import com.android.email.mail.CertificateValidationException;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.app.ListActivity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.AdapterView.OnItemClickListener;

public class MailboxList extends ListActivity implements OnItemClickListener, OnClickListener {

    // Intent extras (internal to this activity)
    private static final String EXTRA_ACCOUNT_ID = "com.android.email.activity._ACCOUNT_ID";

    private static final String MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?"
        + " AND " + MailboxColumns.TYPE + "<" + Mailbox.TYPE_NOT_EMAIL
        + " AND " + MailboxColumns.FLAG_VISIBLE + "=1";
    private static final String MESSAGE_MAILBOX_ID_SELECTION =
        MessageColumns.MAILBOX_KEY + "=?";

    // UI support
    private ListView mListView;
    private ProgressBar mProgressIcon;
    private TextView mErrorBanner;

    private MailboxListAdapter mListAdapter;
    private MailboxListHandler mHandler;
    private ControllerResults mControllerCallback;

    // DB access
    private long mAccountId;
    private LoadMailboxesTask mLoadMailboxesTask;
    private AsyncTask<Void, Void, Object[]> mLoadAccountNameTask;
    private MessageCountTask mMessageCountTask;

    private long mDraftMailboxKey = -1;
    private long mTrashMailboxKey = -1;
    private int mUnreadCountDraft = 0;
    private int mUnreadCountTrash = 0;

    /**
     * Open a specific account.
     * 
     * @param context
     * @param accountId the account to view
     */
    public static void actionHandleAccount(Context context, long accountId) {
        Intent intent = new Intent(context, MailboxList.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        context.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.mailbox_list);

        mHandler = new MailboxListHandler();
        mControllerCallback = new ControllerResults();
        mListView = getListView();
        mProgressIcon = (ProgressBar) findViewById(R.id.title_progress_icon);
        mErrorBanner = (TextView) findViewById(R.id.connection_error_text);

        mListView.setOnItemClickListener(this);
        mListView.setItemsCanFocus(false);
        registerForContextMenu(mListView);

        mListAdapter = new MailboxListAdapter(this);
        setListAdapter(mListAdapter);

        ((Button) findViewById(R.id.account_title_button)).setOnClickListener(this);

        mAccountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, -1);
        if (mAccountId != -1) {
            mLoadMailboxesTask = new LoadMailboxesTask(mAccountId);
            mLoadMailboxesTask.execute();
        } else {
            finish();
        }

        ((TextView)findViewById(R.id.title_left_text)).setText(R.string.mailbox_list_title);

        // Go to the database for the account name
        mLoadAccountNameTask = new AsyncTask<Void, Void, Object[]>() {
            @Override
            protected Object[] doInBackground(Void... params) {
                String accountName = null;
                Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, mAccountId);
                Cursor c = MailboxList.this.getContentResolver().query(
                        uri, new String[] { AccountColumns.DISPLAY_NAME }, null, null, null);
                try {
                    if (c.moveToFirst()) {
                        accountName = c.getString(0);
                    }
                } finally {
                    c.close();
                }
                int nAccounts = EmailContent.count(MailboxList.this, Account.CONTENT_URI, null, null);
                return new Object[] {accountName, nAccounts};
            }
 
            @Override
            protected void onPostExecute(Object[] result) {
                if (result == null) {
                    return;
                }
                final String accountName = (String) result[0];
                // accountName is null if account name can't be retrieved or query exception
                if (accountName == null) {
                    // something is wrong with this account
                    finish();
                }

                final int nAccounts = (Integer) result[1];
                setTitleAccountName(accountName, nAccounts > 1);
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

        // Exit immediately if the accounts list has changed (e.g. externally deleted)
        if (Email.getNotifyUiAccountsChanged()) {
            Welcome.actionStart(this);
            finish();
            return;
        }

        updateMessageCount();

        // TODO: may need to clear notifications here
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        Utility.cancelTaskInterrupt(mLoadMailboxesTask);
        mLoadMailboxesTask = null;
        Utility.cancelTaskInterrupt(mLoadAccountNameTask);
        mLoadAccountNameTask = null;
        Utility.cancelTaskInterrupt(mMessageCountTask);
        mMessageCountTask = null;

        mListAdapter.changeCursor(null);
    }

    public void onClick(View v) {
        switch (v.getId()) {
        case R.id.account_title_button:
            onAccounts();
            break;
        }
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        onOpenMailbox(id);
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
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo info) {
        super.onCreateContextMenu(menu, v, info);
        AdapterView.AdapterContextMenuInfo menuInfo = (AdapterView.AdapterContextMenuInfo) info;
        Cursor c = (Cursor) mListView.getItemAtPosition(menuInfo.position);
        String folderName = Utility.FolderProperties.getInstance(MailboxList.this)
                .getDisplayName(Integer.valueOf(c.getString(mListAdapter.COLUMN_TYPE)));
        if (folderName == null) {
            folderName = c.getString(mListAdapter.COLUMN_DISPLAY_NAME);
        }

        menu.setHeaderTitle(folderName);
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
            case R.id.open:
                onOpenMailbox(info.id);
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
            controller.updateMailbox(mAccountId, mailboxId, mControllerCallback);
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
        MessageList.actionHandleMailbox(this, mailboxId);
    }

    private void onCompose() {
        MessageCompose.actionCompose(this, mAccountId);
    }

    private void setTitleAccountName(String accountName, boolean showAccountsButton) {
        TextView accountsButton = (TextView) findViewById(R.id.account_title_button);
        TextView textPlain = (TextView) findViewById(R.id.title_right_text);
        if (showAccountsButton) {
            accountsButton.setVisibility(View.VISIBLE);
            textPlain.setVisibility(View.GONE);
            accountsButton.setText(accountName);
        } else {
            accountsButton.setVisibility(View.GONE);
            textPlain.setVisibility(View.VISIBLE);
            textPlain.setText(accountName);
        }
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
            Cursor c = MailboxList.this.managedQuery(
                    EmailContent.Mailbox.CONTENT_URI,
                    MailboxList.this.mListAdapter.PROJECTION,
                    MAILBOX_SELECTION,
                    new String[] { String.valueOf(mAccountKey) },
                    MailboxColumns.TYPE + "," + MailboxColumns.DISPLAY_NAME);
            mDraftMailboxKey = -1;
            mTrashMailboxKey = -1;
            c.moveToPosition(-1);
            while (c.moveToNext()) {
                long mailboxId = c.getInt(mListAdapter.COLUMN_ID);
                switch (c.getInt(mListAdapter.COLUMN_TYPE)) {
                case Mailbox.TYPE_DRAFTS:
                    mDraftMailboxKey = mailboxId;
                    break;
                case Mailbox.TYPE_TRASH:
                    mTrashMailboxKey = mailboxId;
                    break;
                }
            }
            if (isCancelled()) {
                c.close();
                c = null;
            }
            return c;
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if (cursor == null || cursor.isClosed()) {
                return;
            }
            MailboxList.this.mListAdapter.changeCursor(cursor);
            updateMessageCount();
        }
    }

    private class MessageCountTask extends AsyncTask<Void, Void, int[]> {

        @Override
        protected int[] doInBackground(Void... params) {
            int[] counts = new int[2];
            if (mDraftMailboxKey != -1) {
                counts[0] = EmailContent.count(MailboxList.this, Message.CONTENT_URI,
                        MESSAGE_MAILBOX_ID_SELECTION,
                        new String[] { String.valueOf(mDraftMailboxKey)});
            } else {
                counts[0] = -1;
            }
            if (mTrashMailboxKey != -1) {
                counts[1] = EmailContent.count(MailboxList.this, Message.CONTENT_URI,
                        MESSAGE_MAILBOX_ID_SELECTION,
                        new String[] { String.valueOf(mTrashMailboxKey)});
            } else {
                counts[1] = -1;
            }
            return counts;
        }

        @Override
        protected void onPostExecute(int[] counts) {
            boolean countChanged = false;
            if (counts == null) {
                return;
            }
            if (counts[0] != -1) {
                if (mUnreadCountDraft != counts[0]) {
                    mUnreadCountDraft = counts[0];
                    countChanged = true;
                }
            } else {
                mUnreadCountDraft = 0;
            }
            if (counts[1] != -1) {
                if (mUnreadCountTrash != counts[1]) {
                    mUnreadCountTrash = counts[1];
                    countChanged = true;
                }
            } else {
                mUnreadCountTrash = 0;
            }
            if (countChanged) {
                mListAdapter.notifyDataSetChanged();
            }
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

    /**
     * Handler for UI-thread operations (when called from callbacks or any other threads)
     */
    class MailboxListHandler extends Handler {
        private static final int MSG_PROGRESS = 1;
        private static final int MSG_ERROR_BANNER = 2;

        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_PROGRESS:
                    boolean showProgress = (msg.arg1 != 0);
                    if (showProgress) {
                        mProgressIcon.setVisibility(View.VISIBLE);
                    } else {
                        mProgressIcon.setVisibility(View.GONE);
                    }
                    break;
                case MSG_ERROR_BANNER:
                    String message = (String) msg.obj;
                    boolean isVisible = mErrorBanner.getVisibility() == View.VISIBLE;
                    if (message != null) {
                        mErrorBanner.setText(message);
                        if (!isVisible) {
                            mErrorBanner.setVisibility(View.VISIBLE);
                            mErrorBanner.startAnimation(
                                    AnimationUtils.loadAnimation(
                                            MailboxList.this, R.anim.header_appear));
                        }
                    } else {
                        if (isVisible) {
                            mErrorBanner.setVisibility(View.GONE);
                            mErrorBanner.startAnimation(
                                    AnimationUtils.loadAnimation(
                                            MailboxList.this, R.anim.header_disappear));
                        }
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

        /**
         * Called from any thread to show or hide the connection error banner.
         * @param message error text or null to hide the box
         */
        public void showErrorBanner(String message) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_ERROR_BANNER;
            msg.obj = message;
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
                updateBanner(result, progress);
                updateProgress(result, progress);
            }
        }

        // TODO report errors into UI
        public void updateMailboxCallback(MessagingException result, long accountKey,
                long mailboxKey, int progress, int numNewMessages) {
            if (result != null || progress == 100) {
                Email.updateMailboxRefreshTime(mailboxKey);
            }
            if (accountKey == mAccountId) {
                updateBanner(result, progress);
                updateProgress(result, progress);
            }
        }

        public void loadMessageForViewCallback(MessagingException result, long messageId,
                int progress) {
        }

        public void loadAttachmentCallback(MessagingException result, long messageId,
                long attachmentId, int progress) {
        }

        public void serviceCheckMailCallback(MessagingException result, long accountId,
                long mailboxId, int progress, long tag) {
        }

        public void sendMailCallback(MessagingException result, long accountId, long messageId,
                int progress) {
            if (accountId == mAccountId) {
                updateBanner(result, progress);
                updateProgress(result, progress);
            }
        }

        private void updateProgress(MessagingException result, int progress) {
            if (result != null || progress == 100) {
                mHandler.progress(false);
            } else if (progress == 0) {
                mHandler.progress(true);
            }
        }

        /**
         * Show or hide the connection error banner, and convert the various MessagingException
         * variants into localizable text.  There is hysteresis in the show/hide logic:  Once shown,
         * the banner will remain visible until some progress is made on the connection.  The
         * goal is to keep it from flickering during retries in a bad connection state.
         *
         * @param result
         * @param progress
         */
        private void updateBanner(MessagingException result, int progress) {
            if (result != null) {
                int id = R.string.status_network_error;
                if (result instanceof AuthenticationFailedException) {
                    id = R.string.account_setup_failed_dlg_auth_message;
                } else if (result instanceof CertificateValidationException) {
                    id = R.string.account_setup_failed_dlg_certificate_message;
                } else {
                    switch (result.getExceptionType()) {
                        case MessagingException.IOERROR:
                            id = R.string.account_setup_failed_ioerror;
                            break;
                        case MessagingException.TLS_REQUIRED:
                            id = R.string.account_setup_failed_tls_required;
                            break;
                        case MessagingException.AUTH_REQUIRED:
                            id = R.string.account_setup_failed_auth_required;
                            break;
                        case MessagingException.GENERAL_SECURITY:
                            id = R.string.account_setup_failed_security;
                            break;
                    }
                }
                mHandler.showErrorBanner(getString(id));
            } else if (progress > 0) {
                mHandler.showErrorBanner(null);
            }
        }
    }

    /**
     * The adapter for displaying mailboxes.
     */
    /* package */ class MailboxListAdapter extends CursorAdapter {

        public final String[] PROJECTION = new String[] { MailboxColumns.ID,
                MailboxColumns.DISPLAY_NAME, MailboxColumns.UNREAD_COUNT, MailboxColumns.TYPE };
        public final int COLUMN_ID = 0;
        public final int COLUMN_DISPLAY_NAME = 1;
        public final int COLUMN_UNREAD_COUNT = 2;
        public final int COLUMN_TYPE = 3;

        Context mContext;
        private LayoutInflater mInflater;

        public MailboxListAdapter(Context context) {
            super(context, null);
            mContext = context;
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            int type = cursor.getInt(COLUMN_TYPE);
            String text = Utility.FolderProperties.getInstance(context)
                    .getDisplayName(type);
            if (text == null) {
                text = cursor.getString(COLUMN_DISPLAY_NAME);
            }
            TextView nameView = (TextView) view.findViewById(R.id.mailbox_name);
            if (text != null) {
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
            View chipView = view.findViewById(R.id.chip);
            chipView.setBackgroundResource(Email.getAccountColorResourceId(mAccountId));
            // TODO do we use a different count for special mailboxes (total count vs. unread)
            int count = -1;
            switch (type) {
                case Mailbox.TYPE_DRAFTS:
                    count = mUnreadCountDraft;
                    text = String.valueOf(count);
                    break;
                case Mailbox.TYPE_TRASH:
                    count = mUnreadCountTrash;
                    text = String.valueOf(count);
                    break;
                default:
                    text = cursor.getString(COLUMN_UNREAD_COUNT);
                    if (text != null) {
                        count = Integer.valueOf(text);
                    }
                    break;
            }
            TextView unreadCountView = (TextView) view.findViewById(R.id.new_message_count);
            TextView allCountView = (TextView) view.findViewById(R.id.all_message_count);
            // If the unread count is zero, not to show countView.
            if (count > 0) {
                nameView.setTypeface(Typeface.DEFAULT_BOLD);
                switch (type) {
                case Mailbox.TYPE_DRAFTS:
                case Mailbox.TYPE_OUTBOX:
                case Mailbox.TYPE_SENT:
                case Mailbox.TYPE_TRASH:
                    unreadCountView.setVisibility(View.GONE);
                    allCountView.setVisibility(View.VISIBLE);
                    allCountView.setText(text);
                    break;
                default:
                    allCountView.setVisibility(View.GONE);
                    unreadCountView.setVisibility(View.VISIBLE);
                    unreadCountView.setText(text);
                    break;
            }
            } else {
                nameView.setTypeface(Typeface.DEFAULT);
                allCountView.setVisibility(View.GONE);
                unreadCountView.setVisibility(View.GONE);
            }

            ImageView folderIcon = (ImageView) view.findViewById(R.id.folder_icon);
            folderIcon.setImageDrawable(Utility.FolderProperties.getInstance(context)
                    .getIconIds(type));
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return mInflater.inflate(R.layout.mailbox_list_item, parent, false);
        }
    }
}
