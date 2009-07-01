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
import com.android.email.Utility;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.MessageColumns;

import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.AdapterView.OnItemClickListener;

import java.util.Date;
import java.util.HashSet;

public class MessageList extends ListActivity implements OnItemClickListener, OnClickListener {
    
    private static final String EXTRA_MAILBOX_ID = "com.android.email.activity.MAILBOX_ID";
    private static final String EXTRA_ACCOUNT_NAME = "com.android.email.activity.ACCOUNT_NAME";
    private static final String EXTRA_MAILBOX_NAME = "com.android.email.activity.MAILBOX_NAME";
    
    // UI support
    private ListView mListView;
    private MessageListAdapter mListAdapter;
    private MessageListHandler mHandler = new MessageListHandler();
    private ControllerResults mControllerCallback = new ControllerResults();
    
    // DB access
    private long mMailboxId;
    private LoadMessagesTask mLoadMessagesTask;

    /**
     * Open a specific mailbox.
     * 
     * TODO This should just shortcut to a more generic version that can accept a list of
     * accounts/mailboxes (e.g. merged inboxes).
     * 
     * @param context
     * @param id mailbox key
     * @param accountName the account we're viewing
     * @param mailboxName the mailbox we're viewing
     */
    public static void actionHandleAccount(Context context, long id, 
            String accountName, String mailboxName) {
        Intent intent = new Intent(context, MessageList.class);
        intent.putExtra(EXTRA_MAILBOX_ID, id);
        intent.putExtra(EXTRA_ACCOUNT_NAME, accountName);
        intent.putExtra(EXTRA_MAILBOX_NAME, mailboxName);
        context.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

        setContentView(R.layout.message_list);
        mListView = getListView();
        mListView.setOnItemClickListener(this);
        mListView.setItemsCanFocus(false);
        registerForContextMenu(mListView);
        
        mListAdapter = new MessageListAdapter(this);
        setListAdapter(mListAdapter);
        mListView.setAdapter(mAdapter);

        // TODO set title to "account > mailbox (#unread)"
        
        // TODO extend this to properly deal with multiple mailboxes, cursor, etc.
        mMailboxId = getIntent().getLongExtra(EXTRA_MAILBOX_ID, -1);
        
        mLoadMessagesTask = (LoadMessagesTask) new LoadMessagesTask(mMailboxId).execute();
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

        if (mLoadMessagesTask != null &&
                mLoadMessagesTask.getStatus() != LoadMessagesTask.Status.FINISHED) {
            mLoadMessagesTask.cancel(true);
            mLoadMessagesTask = null;
        }
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        // TODO these can be lighter-weight lookups
        EmailContent.Message message = EmailContent.Message.restoreMessageWithId(this, id);
        EmailContent.Mailbox mailbox =
            EmailContent.Mailbox.restoreMailboxWithId(this, message.mMailboxKey);
        
        if (mailbox.mType == EmailContent.Mailbox.TYPE_DRAFTS) {
            // TODO need id-based API for MessageCompose
            // MessageCompose.actionEditDraft(this, id);
        }
        else {
            MessageView.actionView(this, id);
        }
    }

    public void onClick(View v) {
        // TODO Auto-generated method stub
        
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.message_list_option, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                onRefresh();
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

    private void onRefresh() {
        // TODO: This needs to loop through all open mailboxes (there might be more than one)
        EmailContent.Mailbox mailbox =
            EmailContent.Mailbox.restoreMailboxWithId(this, mMailboxId);
        EmailContent.Account account =
            EmailContent.Account.restoreAccountWithId(this, mailbox.mAccountKey);
        mHandler.progress(true);
        Controller.getInstance(getApplication()).updateMailbox(
                account, mailbox, mControllerCallback);
    }
    
    private void onAccounts() {
        Accounts.actionShowAccounts(this);
        finish();
    }
    
    private void onCompose() {
        // TODO: Select correct account to send from when there are multiple mailboxes
        EmailContent.Mailbox mailbox =
            EmailContent.Mailbox.restoreMailboxWithId(this, mMailboxId);
        MessageCompose.actionCompose(this, mailbox.mAccountKey);
    }
    
    private void onEditAccount() {
        // TODO: Select correct account to edit when there are multiple mailboxes
        EmailContent.Mailbox mailbox =
            EmailContent.Mailbox.restoreMailboxWithId(this, mMailboxId);
        AccountSettings.actionSettings(this, mailbox.mAccountKey);
    }

    /**
     * Async task for loading a single folder out of the UI thread
     * 
     * TODO: Extend API to support compound select (e.g. merged inbox list)
     */
    private class LoadMessagesTask extends AsyncTask<Void, Void, Cursor> {

        private long mMailboxKey;

        /**
         * Special constructor to cache some local info
         */
        public LoadMessagesTask(long mailboxKey) {
            mMailboxKey = mailboxKey;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            return MessageList.this.managedQuery(
                    EmailContent.Message.CONTENT_URI,
                    MessageListAdapter.PROJECTION,
                    EmailContent.MessageColumns.MAILBOX_KEY + "=?",
                    new String[] {
                            String.valueOf(mMailboxKey)
                            },
                    EmailContent.MessageColumns.TIMESTAMP + " DESC");
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            MessageList.this.mListAdapter.changeCursor(cursor);
            
            // TODO: remove this hack and only update at the right time
            if (cursor != null && cursor.getCount() == 0) {
                onRefresh();
            }
        }
    }
    
    /**
     * Handler for UI-thread operations (when called from callbacks or any other threads)
     */
    class MessageListHandler extends Handler {
        private static final int MSG_PROGRESS = 1;

        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_PROGRESS:
                    setProgressBarIndeterminateVisibility(msg.arg1 != 0);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }

        public void progress(boolean progress) {
            android.os.Message msg =android.os.Message.obtain();
            msg.what = MSG_PROGRESS;
            msg.arg1 = progress ? 1 : 0;
            sendMessage(msg);
        }
    }
    
    /**
     * Callback for async Controller results.  This is all a placeholder until we figure out the
     * final way to do this.
     */
    private class ControllerResults implements Controller.Result {
        public void updateMailboxListCallback(MessagingException result, long accountKey) {
        }

        public void updateMailboxCallback(MessagingException result, long accountKey,
                long mailboxKey, int totalMessagesInMailbox, int numNewMessages) {
            mHandler.progress(false);
        }
    }

    /**
     * This class implements the adapter for displaying messages based on cursors.
     */
    private static class MessageListAdapter extends CursorAdapter {
        
        public static final int COLUMN_ID = 0;
        public static final int COLUMN_MAILBOX_KEY = 1;
        public static final int COLUMN_DISPLAY_NAME = 2;
        public static final int COLUMN_SUBJECT = 3;
        public static final int COLUMN_DATE = 4;
        public static final int COLUMN_READ = 5;
        public static final int COLUMN_FAVORITE = 6;
        public static final int COLUMN_ATTACHMENTS = 7;

        public static final String[] PROJECTION = new String[] {
            EmailContent.RECORD_ID, MessageColumns.MAILBOX_KEY,
            MessageColumns.DISPLAY_NAME, MessageColumns.SUBJECT, MessageColumns.TIMESTAMP,
            MessageColumns.FLAG_READ, MessageColumns.FLAG_FAVORITE, MessageColumns.FLAG_ATTACHMENT,
        };

        Context mContext;
        private LayoutInflater mInflater;
        private Drawable mAttachmentIcon;
        private Drawable mFavoriteIconOn;
        private Drawable mFavoriteIconOff;
        private Drawable mSelectedIconOn;
        private Drawable mSelectedIconOff;

        private java.text.DateFormat mDateFormat;
        private java.text.DateFormat mDayFormat;
        private java.text.DateFormat mTimeFormat;
        
        private HashSet<Long> mChecked = new HashSet<Long>();

        public MessageListAdapter(Context context) {
            super(context, null);
            mContext = context;
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            Resources resources = context.getResources();
            mAttachmentIcon = resources.getDrawable(R.drawable.ic_mms_attachment_small);
            mFavoriteIconOn = resources.getDrawable(android.R.drawable.star_on);
            mFavoriteIconOff = resources.getDrawable(android.R.drawable.star_off);
            mSelectedIconOn = resources.getDrawable(R.drawable.btn_check_buttonless_on);
            mSelectedIconOff = resources.getDrawable(R.drawable.btn_check_buttonless_off);
            
            mDateFormat = android.text.format.DateFormat.getDateFormat(context);    // short date
            mDayFormat = android.text.format.DateFormat.getDateFormat(context);     // TODO: day
            mTimeFormat = android.text.format.DateFormat.getTimeFormat(context);    // 12/24 time
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            View clipView = view.findViewById(R.id.chip);
            boolean readFlag = cursor.getInt(COLUMN_READ) != 0;
            clipView.getBackground().setAlpha(readFlag ? 0 : 255);
            
            TextView fromView = (TextView) view.findViewById(R.id.from);
            String text = cursor.getString(COLUMN_DISPLAY_NAME);
            if (text != null) fromView.setText(text);
            
            boolean hasAttachments = cursor.getInt(COLUMN_ATTACHMENTS) != 0;
            fromView.setCompoundDrawablesWithIntrinsicBounds(null, null,
                    hasAttachments ? mAttachmentIcon : null, null);
            
            TextView subjectView = (TextView) view.findViewById(R.id.subject);
            text = cursor.getString(COLUMN_SUBJECT);
            if (text != null) subjectView.setText(text);
            
            // TODO ui spec suggests "time", "day", "date" - implement "day"
            TextView dateView = (TextView) view.findViewById(R.id.date);
            long timestamp = cursor.getLong(COLUMN_DATE);
            Date date = new Date(timestamp);
            if (Utility.isDateToday(date)) {
                text = mTimeFormat.format(date);
            } else {
                text = mDateFormat.format(date);
            }
            dateView.setText(text);
            
            ImageView selectedView = (ImageView) view.findViewById(R.id.selected);
            boolean selected = mChecked.contains(Long.valueOf(cursor.getLong(COLUMN_ID)));
            selectedView.setImageDrawable(selected ? mSelectedIconOn : mSelectedIconOff);

            ImageView favoriteView = (ImageView) view.findViewById(R.id.favorite);
            boolean favorite = cursor.getInt(COLUMN_FAVORITE) != 0;
            favoriteView.setImageDrawable(favorite ? mFavoriteIconOn : mFavoriteIconOff);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            // TODO:  This should be a custom view so we can deal with touch events
            // in the checkbox & star.
            return mInflater.inflate(R.layout.message_list_item, parent, false);
        }
    }


}
