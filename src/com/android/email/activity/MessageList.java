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
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.app.ListActivity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
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
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

public class MessageList extends ListActivity implements OnItemClickListener, OnClickListener {

    // Magic mailbox ID's
    // NOTE:  This is a quick solution for merged mailboxes.  I would rather implement this
    // with a more generic way of packaging and sharing queries between activities
    public static final long QUERY_ALL_INBOXES = -2;
    public static final long QUERY_ALL_UNREAD = -3;
    public static final long QUERY_ALL_FAVORITES = -4;
    public static final long QUERY_ALL_DRAFTS = -5;
    public static final long QUERY_ALL_OUTBOX = -6;

    // Intent extras (internal to this activity)
    private static final String EXTRA_ACCOUNT_ID = "com.android.email.activity._ACCOUNT_ID";
    private static final String EXTRA_MAILBOX_TYPE = "com.android.email.activity.MAILBOX_TYPE";
    private static final String EXTRA_MAILBOX_ID = "com.android.email.activity.MAILBOX_ID";
    private static final String EXTRA_ACCOUNT_NAME = "com.android.email.activity.ACCOUNT_NAME";
    private static final String EXTRA_MAILBOX_NAME = "com.android.email.activity.MAILBOX_NAME";
    
    // UI support
    private ListView mListView;
    private View mMultiSelectPanel;
    private View mReadUnreadButton;
    private View mFavoriteButton;
    private View mDeleteButton;
    private MessageListAdapter mListAdapter;
    private MessageListHandler mHandler = new MessageListHandler();
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
    private long mMailboxId;
    private LoadMessagesTask mLoadMessagesTask;
    private FindMailboxTask mFindMailboxTask;

    /**
     * Reduced mailbox projection used to hunt for inboxes
     * TODO: remove this and implement a custom URI
     */
    public final static int MAILBOX_FIND_INBOX_COLUMN_ID = 0;

    public final static String[] MAILBOX_FIND_INBOX_PROJECTION = new String[] {
        EmailContent.RECORD_ID, MailboxColumns.TYPE, MailboxColumns.FLAG_VISIBLE
    };

    /**
     * Open a specific mailbox.
     * 
     * TODO This should just shortcut to a more generic version that can accept a list of
     * accounts/mailboxes (e.g. merged inboxes).
     * 
     * @param context
     * @param id mailbox key
     * @param accountName the account we're viewing (for title formatting - not for lookup)
     * @param mailboxName the mailbox we're viewing (for title formatting - not for lookup)
     */
    public static void actionHandleAccount(Context context, long id, 
            String accountName, String mailboxName) {
        Intent intent = new Intent(context, MessageList.class);
        intent.putExtra(EXTRA_MAILBOX_ID, id);
        intent.putExtra(EXTRA_ACCOUNT_NAME, accountName);
        intent.putExtra(EXTRA_MAILBOX_NAME, mailboxName);
        context.startActivity(intent);
    }

    /**
     * Open a specific mailbox by account & type
     * 
     * @param context The caller's context (for generating an intent)
     * @param accountId The account to open
     * @param mailboxType the type of mailbox to open (e.g. @see EmailContent.Mailbox.TYPE_INBOX)
     */
    public static void actionHandleAccount(Context context, long accountId, int mailboxType) {
        Intent intent = new Intent(context, MessageList.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(EXTRA_MAILBOX_TYPE, mailboxType);
        context.startActivity(intent);
    }

    /**
     * Return an intent to open a specific mailbox by account & type.  It will also clear
     * notifications.
     * 
     * @param context The caller's context (for generating an intent)
     * @param accountId The account to open
     * @param mailboxType the type of mailbox to open (e.g. @see EmailContent.Mailbox.TYPE_INBOX)
     */
    public static Intent actionHandleAccountIntent(Context context, long accountId,
            int mailboxType) {
        Intent intent = new Intent(context, MessageList.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(EXTRA_MAILBOX_TYPE, mailboxType);
        return intent;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

        setContentView(R.layout.message_list);
        mListView = getListView();
        mMultiSelectPanel = findViewById(R.id.footer_organize);
        mReadUnreadButton = findViewById(R.id.btn_read_unread);
        mFavoriteButton = findViewById(R.id.btn_multi_favorite);
        mDeleteButton = findViewById(R.id.btn_multi_delete);

        mReadUnreadButton.setOnClickListener(this);
        mFavoriteButton.setOnClickListener(this);
        mDeleteButton.setOnClickListener(this);

        mListView.setOnItemClickListener(this);
        mListView.setItemsCanFocus(false);
        registerForContextMenu(mListView);

        mListAdapter = new MessageListAdapter(this);
        setListAdapter(mListAdapter);

        // TODO extend this to properly deal with multiple mailboxes, cursor, etc.

        // Select 'by id' or 'by type' mode and launch appropriate queries

        mMailboxId = getIntent().getLongExtra(EXTRA_MAILBOX_ID, -1);
        if (mMailboxId != -1) {
            mLoadMessagesTask = new LoadMessagesTask(mMailboxId);
            mLoadMessagesTask.execute();
        } else {
            long accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, -1);
            int mailboxType = getIntent().getIntExtra(EXTRA_MAILBOX_TYPE, -1);
            mFindMailboxTask = new FindMailboxTask(accountId, mailboxType, true);
            mFindMailboxTask.execute();
        }

        // TODO set title to "account > mailbox (#unread)"
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
        if (mFindMailboxTask != null &&
                mFindMailboxTask.getStatus() != FindMailboxTask.Status.FINISHED) {
            mFindMailboxTask.cancel(true);
            mFindMailboxTask = null;
        }
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        MessageListItem itemView = (MessageListItem) view;
        onOpenMessage(id, itemView.mMailboxId);
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_read_unread:
                onMultiToggleRead(mListAdapter.getSelectedSet());
                break;
            case R.id.btn_multi_favorite:
                onMultiToggleFavorite(mListAdapter.getSelectedSet());
                break;
            case R.id.btn_multi_delete:
                onMultiDelete(mListAdapter.getSelectedSet());
                break;
        }
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

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        MessageListItem itemView = (MessageListItem) info.targetView;

        // TODO: There is no context menu for the outbox
        // TODO: There is probably a special context menu for the trash

        getMenuInflater().inflate(R.menu.message_list_context, menu);

        // The default menu contains "mark as read".  If the message is read, change
        // the menu text to "mark as unread."
        if (itemView.mRead) {
            menu.findItem(R.id.mark_as_read).setTitle(R.string.mark_as_unread_action);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info =
            (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        MessageListItem itemView = (MessageListItem) info.targetView;

        switch (item.getItemId()) {
            case R.id.open:
                onOpenMessage(info.id, itemView.mMailboxId);
                break;
            case R.id.delete:
                onDelete(info.id, itemView.mAccountId);
                break;
            case R.id.reply:
                //onReply(holder);
                break;
            case R.id.reply_all:
                //onReplyAll(holder);
                break;
            case R.id.forward:
                //onForward(holder);
                break;
            case R.id.mark_as_read:
                onToggleRead(info.id, itemView.mRead);
                break;
        }
        return super.onContextItemSelected(item);
    }

    private void onRefresh() {
        // TODO: This needs to loop through all open mailboxes (there might be more than one)
        // TODO: Should not be reading from DB in UI thread
        if (mMailboxId >= 0) {
            EmailContent.Mailbox mailbox =
                    EmailContent.Mailbox.restoreMailboxWithId(this, mMailboxId);
            EmailContent.Account account =
                    EmailContent.Account.restoreAccountWithId(this, mailbox.mAccountKey);
            mHandler.progress(true);
            Controller.getInstance(getApplication()).updateMailbox(
                    account, mailbox, mControllerCallback);
        }
    }

    private void onAccounts() {
        AccountFolderList.actionShowAccounts(this);
        finish();
    }

    private void onCompose() {
        // TODO: Select correct account to send from when there are multiple mailboxes
        // TODO: Should not be reading from DB in UI thread
        if (mMailboxId >= 0) {
            EmailContent.Mailbox mailbox =
                    EmailContent.Mailbox.restoreMailboxWithId(this, mMailboxId);
            MessageCompose.actionCompose(this, mailbox.mAccountKey);
        }
    }

    private void onEditAccount() {
        // TODO: Select correct account to edit when there are multiple mailboxes
        // TODO: Should not be reading from DB in UI thread
        if (mMailboxId >= 0) {
            EmailContent.Mailbox mailbox =
                    EmailContent.Mailbox.restoreMailboxWithId(this, mMailboxId);
            AccountSettings.actionSettings(this, mailbox.mAccountKey);
        }
    }

    public void onOpenMessage(long messageId, long mailboxId) {
        // TODO: Should not be reading from DB in UI thread
        EmailContent.Mailbox mailbox = EmailContent.Mailbox.restoreMailboxWithId(this, mailboxId);

        if (mailbox.mType == EmailContent.Mailbox.TYPE_DRAFTS) {
            // TODO need id-based API for MessageCompose
            // MessageCompose.actionEditDraft(this, messageId);
        } else {
            MessageView.actionView(this, messageId);
        }
    }

    private void onDelete(long messageId, long accountId) {
        Controller.getInstance(getApplication()).deleteMessage(messageId, accountId);
        Toast.makeText(this, R.string.message_deleted_toast, Toast.LENGTH_SHORT).show();
    }

    private void onToggleRead(long messageId, boolean oldRead) {
        boolean isRead = ! oldRead;

        // TODO this should be a call to the controller, since it may possibly kick off
        // more than just a DB update.  Also, the DB update shouldn't be in the UI thread
        // as it is here.  Also, it needs to update the read/unread count in the mailbox?
        ContentValues cv = new ContentValues();
        cv.put(EmailContent.MessageColumns.FLAG_READ, isRead);
        Uri uri = ContentUris.withAppendedId(
                EmailContent.Message.SYNCED_CONTENT_URI, messageId);
        getContentResolver().update(uri, cv, null, null);
    }

    /**
     * Toggles a set read/unread states.  Note, the default behavior is "mark unread", so the
     * sense of the helper methods is "true=unread".
     * 
     * @param selectedSet The current list of selected items
     */
    private void onMultiToggleRead(Set<Long> selectedSet) {
        int numChanged = toggleMultiple(selectedSet, new MultiToggleHelper() {

            public boolean getField(long messageId, Cursor c) {
                return c.getInt(MessageListAdapter.COLUMN_READ) == 0;
            }

            public boolean setField(long messageId, Cursor c, boolean newValue) {
                boolean oldValue = getField(messageId, c);
                if (oldValue != newValue) {
                    onToggleRead(messageId, !oldValue);
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * Toggles a set of favorites (stars)
     * 
     * @param selectedSet The current list of selected items
     */
    private void onMultiToggleFavorite(Set<Long> selectedSet) {
        int numChanged = toggleMultiple(selectedSet, new MultiToggleHelper() {

            public boolean getField(long messageId, Cursor c) {
                return c.getInt(MessageListAdapter.COLUMN_FAVORITE) != 0;
            }

            public boolean setField(long messageId, Cursor c, boolean newValue) {
                boolean oldValue = getField(messageId, c);
                if (oldValue != newValue) {
                    // Update provider
                    // TODO this should probably be a call to the controller, since it may possibly
                    // kick off more than just a DB update.
                    ContentValues cv = new ContentValues();
                    cv.put(EmailContent.MessageColumns.FLAG_FAVORITE, newValue);
                    Uri uri = ContentUris.withAppendedId(
                            EmailContent.Message.SYNCED_CONTENT_URI, messageId);
                    MessageList.this.getContentResolver().update(uri, cv, null, null);

                    return true;
                }
                return false;
            }
        });
    }

    private void onMultiDelete(Set<Long> selectedSet) {
        // Clone the set, because deleting is going to thrash things
        HashSet<Long> cloneSet = new HashSet<Long>(selectedSet);
        for (Long id : cloneSet) {
            Controller.getInstance(getApplication()).deleteMessage(id, -1);
        }
        // TODO: count messages and show "n messages deleted"
        Toast.makeText(this, R.string.message_deleted_toast, Toast.LENGTH_SHORT).show();
        selectedSet.clear();
        showMultiPanel(false);
    }

    private interface MultiToggleHelper {
        /**
         * Return true if the field of interest is "set".  If one or more are false, then our
         * bulk action will be to "set".  If all are set, our bulk action will be to "clear".
         * @param messageId the message id of the current message
         * @param c the cursor, positioned to the item of interest
         * @return true if the field at this row is "set"
         */
        public boolean getField(long messageId, Cursor c);

        /**
         * Set or clear the field of interest.  Return true if a change was made.
         * @param messageId the message id of the current message
         * @param c the cursor, positioned to the item of interest
         * @param newValue the new value to be set at this row
         * @return true if a change was actually made
         */
        public boolean setField(long messageId, Cursor c, boolean newValue);
    }

    /**
     * Toggle multiple fields in a message, using the following logic:  If one or more fields
     * are "clear", then "set" them.  If all fields are "set", then "clear" them all.
     * 
     * @param selectedSet the set of messages that are selected
     * @param helper functions to implement the specific getter & setter
     * @return the number of messages that were updated
     */
    private int toggleMultiple(Set<Long> selectedSet, MultiToggleHelper helper) {
        Cursor c = mListAdapter.getCursor();
        boolean anyWereFound = false;
        boolean allWereSet = true;

        c.moveToPosition(-1);
        while (c.moveToNext()) {
            long id = c.getInt(MessageListAdapter.COLUMN_ID);
            if (selectedSet.contains(Long.valueOf(id))) {
                anyWereFound = true;
                if (!helper.getField(id, c)) {
                    allWereSet = false;
                    break;
                }
            }
        }

        int numChanged = 0;

        if (anyWereFound) {
            boolean newValue = !allWereSet;
            c.moveToPosition(-1);
            while (c.moveToNext()) {
                long id = c.getInt(MessageListAdapter.COLUMN_ID);
                if (selectedSet.contains(Long.valueOf(id))) {
                    if (helper.setField(id, c, newValue)) {
                        ++numChanged;
                    }
                }
            }
        }

        return numChanged;
    }

    /**
     * Show or hide the panel of multi-select options
     */
    private void showMultiPanel(boolean show) {
        if (show && mMultiSelectPanel.getVisibility() != View.VISIBLE) {
            mMultiSelectPanel.setVisibility(View.VISIBLE);
            mMultiSelectPanel.startAnimation(
                    AnimationUtils.loadAnimation(this, R.anim.footer_appear));

        } else if (!show && mMultiSelectPanel.getVisibility() != View.GONE) {
            mMultiSelectPanel.setVisibility(View.GONE);
            mMultiSelectPanel.startAnimation(
                        AnimationUtils.loadAnimation(this, R.anim.footer_disappear));
        }
    }

    /**
     * Async task for finding a single mailbox by type (possibly even going to the network).
     * 
     * This is much too complex, as implemented.  It uses this AsyncTask to check for a mailbox,
     * then (if not found) a Controller call to refresh mailboxes from the server, and a handler
     * to relaunch this task (a 2nd time) to read the results of the network refresh.  The core
     * problem is that we have two different non-UI-thread jobs (reading DB and reading network)
     * and two different paradigms for dealing with them.  Some unification would be needed here
     * to make this cleaner.
     * 
     * TODO: If this problem spreads to other operations, find a cleaner way to handle it.
     */
    private class FindMailboxTask extends AsyncTask<Void, Void, Long> {

        private long mAccountId;
        private int mMailboxType;
        private boolean mOkToRecurse;

        /**
         * Special constructor to cache some local info
         */
        public FindMailboxTask(long accountId, int mailboxType, boolean okToRecurse) {
            mAccountId = accountId;
            mMailboxType = mailboxType;
            mOkToRecurse = okToRecurse;
        }

        @Override
        protected Long doInBackground(Void... params) {
            // See if we can find the requested mailbox in the DB.
            long mailboxId = Mailbox.findMailboxOfType(MessageList.this, mAccountId, mMailboxType);
            if (mailboxId == -1 && mOkToRecurse) {
                // Not found - launch network lookup
                EmailContent.Account account =
                    EmailContent.Account.restoreAccountWithId(MessageList.this, mAccountId);
                mControllerCallback.mWaitForMailboxType = mMailboxType;
                mHandler.progress(true);
                Controller.getInstance(getApplication()).updateMailboxList(
                        account, mControllerCallback);
            }
            return mailboxId;
        }

        @Override
        protected void onPostExecute(Long mailboxId) {
            if (mailboxId != -1) {
                mMailboxId = mailboxId;
                mLoadMessagesTask = new LoadMessagesTask(mMailboxId);
                mLoadMessagesTask.execute();
            }
        }
    }

    /**
     * Async task for loading a single folder out of the UI thread
     * 
     * The code here (for merged boxes) is a placeholder/hack and should be replaced.  Some
     * specific notes:
     * TODO:  Move the double query into a specialized URI that returns all inbox messages
     * and do the dirty work in raw SQL in the provider.
     * TODO:  Generalize the query generation so we can reuse it in MessageView (for next/prev)
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
            // Setup default selection & args, then add to it as necessary
            StringBuilder selection = new StringBuilder(
                    Message.FLAG_LOADED + "!=" + Message.NOT_LOADED + " AND ");
            String[] selArgs = null;

            if (mMailboxKey == QUERY_ALL_INBOXES || mMailboxKey == QUERY_ALL_DRAFTS ||
                    mMailboxKey == QUERY_ALL_OUTBOX) {
                // query for all mailboxes of type INBOX, DRAFTS, or OUTBOX
                int type;
                if (mMailboxKey == QUERY_ALL_INBOXES) {
                    type = Mailbox.TYPE_INBOX;
                } else if (mMailboxKey == QUERY_ALL_DRAFTS) {
                    type = Mailbox.TYPE_DRAFTS;
                } else {
                    type = Mailbox.TYPE_OUTBOX;
                }
                StringBuilder inboxes = new StringBuilder();
                Cursor c = MessageList.this.getContentResolver().query(
                        Mailbox.CONTENT_URI,
                        MAILBOX_FIND_INBOX_PROJECTION,
                        MailboxColumns.TYPE + "=? AND " + MailboxColumns.FLAG_VISIBLE + "=1",
                        new String[] { Integer.toString(type) }, null);
                // build a long WHERE list
                // TODO do this directly in the provider
                while (c.moveToNext()) {
                    if (inboxes.length() != 0) {
                        inboxes.append(" OR ");
                    }
                    inboxes.append(MessageColumns.MAILBOX_KEY + "=");
                    inboxes.append(c.getLong(MAILBOX_FIND_INBOX_COLUMN_ID));
                }
                c.close();
                // This is a hack - if there were no matching mailboxes, the empty selection string
                // would match *all* messages.  Instead, force a "non-matching" selection, which
                // generates an empty Message cursor.
                // TODO: handle this properly when we move the compound lookup into the provider
                if (inboxes.length() == 0) {
                    inboxes.append(Message.RECORD_ID + "=-1");
                }
                // make that the selection
                selection.append(inboxes);
            } else  if (mMailboxKey == QUERY_ALL_UNREAD) {
                selection.append(Message.FLAG_READ + "=0");
            } else if (mMailboxKey == QUERY_ALL_FAVORITES) {
                selection.append(Message.FLAG_FAVORITE + "=1");
            } else {
                selection.append(MessageColumns.MAILBOX_KEY + "=?");
                selArgs = new String[] { String.valueOf(mMailboxKey) };
            }
            return MessageList.this.managedQuery(
                    EmailContent.Message.CONTENT_URI,
                    MessageList.this.mListAdapter.PROJECTION,
                    selection.toString(), selArgs,
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
        private static final int MSG_LOOKUP_MAILBOX_TYPE = 2;

        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_PROGRESS:
                    setProgressBarIndeterminateVisibility(msg.arg1 != 0);
                    break;
                case MSG_LOOKUP_MAILBOX_TYPE:
                    // kill running async task, if any
                    if (mFindMailboxTask != null &&
                            mFindMailboxTask.getStatus() != FindMailboxTask.Status.FINISHED) {
                        mFindMailboxTask.cancel(true);
                        mFindMailboxTask = null;
                    }
                    // start new one.  do not recurse back to controller.
                    long accountId = ((Long)msg.obj).longValue();
                    int mailboxType = msg.arg1;
                    mFindMailboxTask = new FindMailboxTask(accountId, mailboxType, false);
                    mFindMailboxTask.execute();
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
         * Called from any thread to look for a mailbox of a specific type.  This is designed
         * to be called from the Controller's MailboxList callback;  It instructs the async task
         * not to recurse, in case the mailbox is not found after this.
         * 
         * See FindMailboxTask for more notes on this handler.
         */
        public void lookupMailboxType(long accountId, int mailboxType) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_LOOKUP_MAILBOX_TYPE;
            msg.arg1 = mailboxType;
            msg.obj = Long.valueOf(accountId);
            sendMessage(msg);
        }
    }

    /**
     * Callback for async Controller results.  This is all a placeholder until we figure out the
     * final way to do this.
     */
    private class ControllerResults implements Controller.Result {

        // These are preset for use by updateMailboxListCallback
        int mWaitForMailboxType = -1;

        public void updateMailboxListCallback(MessagingException result, long accountKey) {
            if (mWaitForMailboxType != -1) {
                mHandler.progress(false);
                if (result == null) {
                    mHandler.lookupMailboxType(accountKey, mWaitForMailboxType);
                }
            }
        }

        public void updateMailboxCallback(MessagingException result, long accountKey,
                long mailboxKey, int totalMessagesInMailbox, int numNewMessages) {
            mHandler.progress(false);
        }
    }

    /**
     * This class implements the adapter for displaying messages based on cursors.
     */
    /* package */ class MessageListAdapter extends CursorAdapter {
        
        public static final int COLUMN_ID = 0;
        public static final int COLUMN_MAILBOX_KEY = 1;
        public static final int COLUMN_ACCOUNT_KEY = 2;
        public static final int COLUMN_DISPLAY_NAME = 3;
        public static final int COLUMN_SUBJECT = 4;
        public static final int COLUMN_DATE = 5;
        public static final int COLUMN_READ = 6;
        public static final int COLUMN_FAVORITE = 7;
        public static final int COLUMN_ATTACHMENTS = 8;

        public final String[] PROJECTION = new String[] {
            EmailContent.RECORD_ID, MessageColumns.MAILBOX_KEY, MessageColumns.ACCOUNT_KEY,
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

        public Set<Long> getSelectedSet() {
            return mChecked;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            // Reset the view (in case it was recycled) and prepare for binding
            MessageListItem itemView = (MessageListItem) view;
            itemView.bindViewInit(this, true);

            // Load the public fields in the view (for later use)
            itemView.mMessageId = cursor.getLong(COLUMN_ID);
            itemView.mMailboxId = cursor.getLong(COLUMN_MAILBOX_KEY);
            itemView.mAccountId = cursor.getLong(COLUMN_ACCOUNT_KEY);
            itemView.mRead = cursor.getInt(COLUMN_READ) != 0;
            itemView.mFavorite = cursor.getInt(COLUMN_FAVORITE) != 0;
            itemView.mSelected = mChecked.contains(Long.valueOf(itemView.mMessageId));

            // Load the UI
            View chipView = view.findViewById(R.id.chip);
            int chipResId = mColorChipResIds[(int)itemView.mAccountId % mColorChipResIds.length];
            chipView.setBackgroundResource(chipResId);
            // TODO always display chip.  Use other indications (e.g. boldface) for read/unread
            chipView.getBackground().setAlpha(itemView.mRead ? 100 : 255);

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
            selectedView.setImageDrawable(itemView.mSelected ? mSelectedIconOn : mSelectedIconOff);

            ImageView favoriteView = (ImageView) view.findViewById(R.id.favorite);
            favoriteView.setImageDrawable(itemView.mFavorite ? mFavoriteIconOn : mFavoriteIconOff);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return mInflater.inflate(R.layout.message_list_item, parent, false);
        }

        /**
         * This is used as a callback from the list items, to set the selected state
         *
         * @param itemView the item being changed
         * @param newSelected the new value of the selected flag (checkbox state)
         */
        public void updateSelected(MessageListItem itemView, boolean newSelected) {
            ImageView selectedView = (ImageView) itemView.findViewById(R.id.selected);
            selectedView.setImageDrawable(newSelected ? mSelectedIconOn : mSelectedIconOff);

            // Set checkbox state in list, and show/hide panel if necessary
            Long id = Long.valueOf(itemView.mMessageId);
            if (newSelected) {
                mChecked.add(id);
            } else {
                mChecked.remove(id);
            }

            MessageList.this.showMultiPanel(mChecked.size() > 0);
        }

        /**
         * This is used as a callback from the list items, to set the favorite state
         *
         * @param itemView the item being changed
         * @param newFavorite the new value of the favorite flag (star state)
         */
        public void updateFavorite(MessageListItem itemView, boolean newFavorite) {
            ImageView favoriteView = (ImageView) itemView.findViewById(R.id.favorite);
            favoriteView.setImageDrawable(newFavorite ? mFavoriteIconOn : mFavoriteIconOff);

            // Update provider
            // TODO this should probably be a call to the controller, since it may possibly kick off
            // more than just a DB update.
            ContentValues cv = new ContentValues();
            cv.put(EmailContent.MessageColumns.FLAG_FAVORITE, newFavorite);
            Uri uri = ContentUris.withAppendedId(
                    EmailContent.Message.SYNCED_CONTENT_URI, itemView.mMessageId);
            mContext.getContentResolver().update(uri, cv, null, null);
        }
    }
}
