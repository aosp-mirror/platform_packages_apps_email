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
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.service.MailService;

import android.app.ListActivity;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
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
import android.widget.Button;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class MessageList extends ListActivity implements OnItemClickListener, OnClickListener {
    // Intent extras (internal to this activity)
    private static final String EXTRA_ACCOUNT_ID = "com.android.email.activity._ACCOUNT_ID";
    private static final String EXTRA_MAILBOX_TYPE = "com.android.email.activity.MAILBOX_TYPE";
    private static final String EXTRA_MAILBOX_ID = "com.android.email.activity.MAILBOX_ID";
    private static final String STATE_SELECTED_ITEM_TOP =
        "com.android.email.activity.MessageList.selectedItemTop";
    private static final String STATE_SELECTED_POSITION =
        "com.android.email.activity.MessageList.selectedPosition";

    // UI support
    private ListView mListView;
    private View mMultiSelectPanel;
    private Button mReadUnreadButton;
    private Button mFavoriteButton;
    private Button mDeleteButton;
    private View mListFooterView;
    private TextView mListFooterText;
    private View mListFooterProgress;
    private TextView mErrorBanner;

    private static final int LIST_FOOTER_MODE_NONE = 0;
    private static final int LIST_FOOTER_MODE_REFRESH = 1;
    private static final int LIST_FOOTER_MODE_MORE = 2;
    private static final int LIST_FOOTER_MODE_SEND = 3;
    private int mListFooterMode;

    private MessageListAdapter mListAdapter;
    private MessageListHandler mHandler = new MessageListHandler();
    private Controller mController = Controller.getInstance(getApplication());
    private ControllerResults mControllerCallback = new ControllerResults();
    private TextView mLeftTitle;
    private TextView mRightTitle;
    private ProgressBar mProgressIcon;

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
    private ContentResolver mResolver;
    private long mMailboxId;
    private LoadMessagesTask mLoadMessagesTask;
    private FindMailboxTask mFindMailboxTask;
    private SetTitleTask mSetTitleTask;
    private SetFooterTask mSetFooterTask;

    public final static String[] MAILBOX_FIND_INBOX_PROJECTION = new String[] {
        EmailContent.RECORD_ID, MailboxColumns.TYPE, MailboxColumns.FLAG_VISIBLE
    };

    private static final int MAILBOX_NAME_COLUMN_ID = 0;
    private static final int MAILBOX_NAME_COLUMN_ACCOUNT_KEY = 1;
    private static final int MAILBOX_NAME_COLUMN_TYPE = 2;
    private static final String[] MAILBOX_NAME_PROJECTION = new String[] {
            MailboxColumns.DISPLAY_NAME, MailboxColumns.ACCOUNT_KEY,
            MailboxColumns.TYPE};

    private static final int ACCOUNT_DISPLAY_NAME_COLUMN_ID = 0;
    private static final String[] ACCOUNT_NAME_PROJECTION = new String[] {
            AccountColumns.DISPLAY_NAME };

    private static final String ID_SELECTION = EmailContent.RECORD_ID + "=?";

    private Boolean mPushModeMailbox = null;
    private int mSavedItemTop = 0;
    private int mSavedItemPosition = -1;
    private boolean mCanAutoRefresh = false;

    /**
     * Open a specific mailbox.
     *
     * TODO This should just shortcut to a more generic version that can accept a list of
     * accounts/mailboxes (e.g. merged inboxes).
     *
     * @param context
     * @param id mailbox key
     */
    public static void actionHandleMailbox(Context context, long id) {
        Intent intent = new Intent(context, MessageList.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_MAILBOX_ID, id);
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
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(EXTRA_MAILBOX_TYPE, mailboxType);
        context.startActivity(intent);
    }

    /**
     * Return an intent to open a specific mailbox by account & type.  It will also clear
     * notifications.
     *
     * @param context The caller's context (for generating an intent)
     * @param accountId The account to open, or -1
     * @param mailboxId the ID of the mailbox to open, or -1
     * @param mailboxType the type of mailbox to open (e.g. @see Mailbox.TYPE_INBOX) or -1
     */
    public static Intent actionHandleAccountIntent(Context context, long accountId,
            long mailboxId, int mailboxType) {
        Intent intent = new Intent(context, MessageList.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        intent.putExtra(EXTRA_MAILBOX_TYPE, mailboxType);
        return intent;
    }

    /**
     * Used for generating lightweight (Uri-only) intents.
     *
     * @param context Calling context for building the intent
     * @param accountId The account of interest
     * @param mailboxType The folder name to open (typically Mailbox.TYPE_INBOX)
     * @return an Intent which can be used to view that account
     */
    public static Intent actionHandleAccountUriIntent(Context context, long accountId,
            int mailboxType) {
        Intent i = actionHandleAccountIntent(context, accountId, -1, mailboxType);
        i.removeExtra(EXTRA_ACCOUNT_ID);
        Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, accountId);
        i.setData(uri);
        return i;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        requestWindowFeature(Window.FEATURE_CUSTOM_TITLE);
        setContentView(R.layout.message_list);
        getWindow().setFeatureInt(Window.FEATURE_CUSTOM_TITLE,
                R.layout.list_title);

        mCanAutoRefresh = true;
        mListView = getListView();
        mMultiSelectPanel = findViewById(R.id.footer_organize);
        mReadUnreadButton = (Button) findViewById(R.id.btn_read_unread);
        mFavoriteButton = (Button) findViewById(R.id.btn_multi_favorite);
        mDeleteButton = (Button) findViewById(R.id.btn_multi_delete);
        mLeftTitle = (TextView) findViewById(R.id.title_left_text);
        mRightTitle = (TextView) findViewById(R.id.title_right_text);
        mProgressIcon = (ProgressBar) findViewById(R.id.title_progress_icon);
        mErrorBanner = (TextView) findViewById(R.id.connection_error_text);

        mReadUnreadButton.setOnClickListener(this);
        mFavoriteButton.setOnClickListener(this);
        mDeleteButton.setOnClickListener(this);

        mListView.setOnItemClickListener(this);
        mListView.setItemsCanFocus(false);
        registerForContextMenu(mListView);

        mListAdapter = new MessageListAdapter(this);
        setListAdapter(mListAdapter);

        mResolver = getContentResolver();

        // TODO extend this to properly deal with multiple mailboxes, cursor, etc.

        // Select 'by id' or 'by type' or 'by uri' mode and launch appropriate queries

        mMailboxId = getIntent().getLongExtra(EXTRA_MAILBOX_ID, -1);
        if (mMailboxId != -1) {
            // Specific mailbox ID was provided - go directly to it
            mSetTitleTask = new SetTitleTask(mMailboxId);
            mSetTitleTask.execute();
            mLoadMessagesTask = new LoadMessagesTask(mMailboxId, -1);
            mLoadMessagesTask.execute();
            addFooterView(mMailboxId, -1, -1);
        } else {
            long accountId = -1;
            int mailboxType = getIntent().getIntExtra(EXTRA_MAILBOX_TYPE, Mailbox.TYPE_INBOX);
            Uri uri = getIntent().getData();
            if (uri != null
                    && "content".equals(uri.getScheme())
                    && EmailContent.AUTHORITY.equals(uri.getAuthority())) {
                // A content URI was provided - try to look up the account
                String accountIdString = uri.getPathSegments().get(1);
                if (accountIdString != null) {
                    accountId = Long.parseLong(accountIdString);
                }
                mFindMailboxTask = new FindMailboxTask(accountId, mailboxType, false);
                mFindMailboxTask.execute();
            } else {
                // Go by account id + type
                accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, -1);
                mFindMailboxTask = new FindMailboxTask(accountId, mailboxType, true);
                mFindMailboxTask.execute();
            }
            addFooterView(-1, accountId, mailboxType);
        }
        // TODO set title to "account > mailbox (#unread)"
    }

    @Override
    public void onPause() {
        super.onPause();
        mController.removeResultCallback(mControllerCallback);
    }

    @Override
    public void onResume() {
        super.onResume();
        mController.addResultCallback(mControllerCallback);

        // clear notifications here
        NotificationManager notificationManager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(MailService.NEW_MESSAGE_NOTIFICATION_ID);
        restoreListPosition();
        autoRefreshStaleMailbox();
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
        if (mSetTitleTask != null &&
                mSetTitleTask.getStatus() != SetTitleTask.Status.FINISHED) {
            mSetTitleTask.cancel(true);
            mSetTitleTask = null;
        }
        if (mSetFooterTask != null &&
                mSetFooterTask.getStatus() != SetTitleTask.Status.FINISHED) {
            mSetFooterTask.cancel(true);
            mSetFooterTask = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveListPosition();
        outState.putInt(STATE_SELECTED_POSITION, mSavedItemPosition);
        outState.putInt(STATE_SELECTED_ITEM_TOP, mSavedItemTop);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mSavedItemTop = savedInstanceState.getInt(STATE_SELECTED_ITEM_TOP, 0);
        mSavedItemPosition = savedInstanceState.getInt(STATE_SELECTED_POSITION, -1);
    }

    private void saveListPosition() {
        mSavedItemPosition = getListView().getSelectedItemPosition();
        if (mSavedItemPosition >= 0) {
            mSavedItemTop = getListView().getSelectedView().getTop();
        } else {
            mSavedItemPosition = getListView().getFirstVisiblePosition();
            if (mSavedItemPosition >= 0) {
                mSavedItemTop = 0;
                View topChild = getListView().getChildAt(0);
                if (topChild != null) {
                    mSavedItemTop = topChild.getTop();
                }
            }
        }
    }

    private void restoreListPosition() {
        if (mSavedItemPosition >= 0 && mSavedItemPosition < getListView().getCount()) {
            getListView().setSelectionFromTop(mSavedItemPosition, mSavedItemTop);
            mSavedItemPosition = -1;
            mSavedItemTop = 0;
        }
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (view != mListFooterView) {
            MessageListItem itemView = (MessageListItem) view;
            onOpenMessage(id, itemView.mMailboxId);
        } else {
            doFooterClick();
        }
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
        if (mMailboxId < 0) {
            getMenuInflater().inflate(R.menu.message_list_option_smart_folder, menu);
        } else {
            getMenuInflater().inflate(R.menu.message_list_option, menu);
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean showDeselect = mListAdapter.getSelectedSet().size() > 0;
        menu.setGroupVisible(R.id.deselect_all_group, showDeselect);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                onRefresh();
                return true;
            case R.id.folders:
                onFolders();
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
            case R.id.deselect_all:
                onDeselectAll();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        // There is no context menu for the list footer
        if (info.targetView == mListFooterView) {
            return;
        }
        MessageListItem itemView = (MessageListItem) info.targetView;

        Cursor c = (Cursor) mListView.getItemAtPosition(info.position);
        String messageName = c.getString(MessageListAdapter.COLUMN_SUBJECT);

        menu.setHeaderTitle(messageName);

        // TODO: There is probably a special context menu for the trash
        Mailbox mailbox = Mailbox.restoreMailboxWithId(this, itemView.mMailboxId);

        switch (mailbox.mType) {
            case EmailContent.Mailbox.TYPE_DRAFTS:
                getMenuInflater().inflate(R.menu.message_list_context_drafts, menu);
                break;
            case EmailContent.Mailbox.TYPE_OUTBOX:
                getMenuInflater().inflate(R.menu.message_list_context_outbox, menu);
                break;
            case EmailContent.Mailbox.TYPE_TRASH:
                getMenuInflater().inflate(R.menu.message_list_context_trash, menu);
                break;
            default:
                getMenuInflater().inflate(R.menu.message_list_context, menu);
                // The default menu contains "mark as read".  If the message is read, change
                // the menu text to "mark as unread."
                if (itemView.mRead) {
                    menu.findItem(R.id.mark_as_read).setTitle(R.string.mark_as_unread_action);
                }
                break;
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
                onReply(itemView.mMessageId);
                break;
            case R.id.reply_all:
                onReplyAll(itemView.mMessageId);
                break;
            case R.id.forward:
                onForward(itemView.mMessageId);
                break;
            case R.id.mark_as_read:
                onSetMessageRead(info.id, !itemView.mRead);
                break;
        }
        return super.onContextItemSelected(item);
    }

    private void onRefresh() {
        // TODO: Should not be reading from DB in UI thread - need a cleaner way to get accountId
        if (mMailboxId >= 0) {
            Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mMailboxId);
            if (mailbox != null) {
                mController.updateMailbox(mailbox.mAccountKey, mMailboxId, mControllerCallback);
            }
        }
    }

    private void onFolders() {
        if (mMailboxId >= 0) {
            // TODO smaller projection
            Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mMailboxId);
            MailboxList.actionHandleAccount(this, mailbox.mAccountKey);
            finish();
        }
    }

    private void onAccounts() {
        AccountFolderList.actionShowAccounts(this);
        finish();
    }

    private long lookupAccountIdFromMailboxId(long mailboxId) {
        // TODO: Select correct account to send from when there are multiple mailboxes
        // TODO: Should not be reading from DB in UI thread
        if (mailboxId < 0) {
            return -1; // no info, default account
        }
        EmailContent.Mailbox mailbox =
            EmailContent.Mailbox.restoreMailboxWithId(this, mailboxId);
        return mailbox.mAccountKey;
    }

    private void onCompose() {
        MessageCompose.actionCompose(this, lookupAccountIdFromMailboxId(mMailboxId));
    }

    private void onEditAccount() {
        AccountSettings.actionSettings(this, lookupAccountIdFromMailboxId(mMailboxId));
    }

    private void onDeselectAll() {
        mListAdapter.getSelectedSet().clear();
        mListView.invalidateViews();
        showMultiPanel(false);
    }

    private void onOpenMessage(long messageId, long mailboxId) {
        // TODO: Should not be reading from DB in UI thread
        EmailContent.Mailbox mailbox = EmailContent.Mailbox.restoreMailboxWithId(this, mailboxId);

        if (mailbox.mType == EmailContent.Mailbox.TYPE_DRAFTS) {
            MessageCompose.actionEditDraft(this, messageId);
        } else {
            // WARNING: here we pass mMailboxId, which can be the negative id of a compound
            // mailbox, instead of the mailboxId of the particular message that is opened
            MessageView.actionView(this, messageId, mMailboxId);
        }
    }

    private void onReply(long messageId) {
        MessageCompose.actionReply(this, messageId, false);
    }

    private void onReplyAll(long messageId) {
        MessageCompose.actionReply(this, messageId, true);
    }

    private void onForward(long messageId) {
        MessageCompose.actionForward(this, messageId);
    }

    private void onLoadMoreMessages() {
        if (mMailboxId >= 0) {
            mController.loadMoreMessages(mMailboxId, mControllerCallback);
        }
    }

    private void onSendPendingMessages() {
        if (mMailboxId == Mailbox.QUERY_ALL_OUTBOX) {
            // For the combined Outbox, we loop through all accounts and send the messages
            Cursor c = mResolver.query(Account.CONTENT_URI, Account.ID_PROJECTION,
                    null, null, null);
            try {
                while (c.moveToNext()) {
                    long accountId = c.getLong(Account.ID_PROJECTION_COLUMN);
                    mController.sendPendingMessages(accountId, mControllerCallback);
                }
            } finally {
                c.close();
            }
        } else {
            long accountId = lookupAccountIdFromMailboxId(mMailboxId);
            mController.sendPendingMessages(accountId, mControllerCallback);
        }
    }

    private void onDelete(long messageId, long accountId) {
        mController.deleteMessage(messageId, accountId);
        Toast.makeText(this, R.string.message_deleted_toast, Toast.LENGTH_SHORT).show();
    }

    private void onSetMessageRead(long messageId, boolean newRead) {
        mController.setMessageRead(messageId, newRead);
    }

    private void onSetMessageFavorite(long messageId, boolean newFavorite) {
        mController.setMessageFavorite(messageId, newFavorite);
    }

    /**
     * Toggles a set read/unread states.  Note, the default behavior is "mark unread", so the
     * sense of the helper methods is "true=unread".
     *
     * @param selectedSet The current list of selected items
     */
    private void onMultiToggleRead(Set<Long> selectedSet) {
        toggleMultiple(selectedSet, new MultiToggleHelper() {

            public boolean getField(long messageId, Cursor c) {
                return c.getInt(MessageListAdapter.COLUMN_READ) == 0;
            }

            public boolean setField(long messageId, Cursor c, boolean newValue) {
                boolean oldValue = getField(messageId, c);
                if (oldValue != newValue) {
                    onSetMessageRead(messageId, !newValue);
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
        toggleMultiple(selectedSet, new MultiToggleHelper() {

            public boolean getField(long messageId, Cursor c) {
                return c.getInt(MessageListAdapter.COLUMN_FAVORITE) != 0;
            }

            public boolean setField(long messageId, Cursor c, boolean newValue) {
                boolean oldValue = getField(messageId, c);
                if (oldValue != newValue) {
                    onSetMessageFavorite(messageId, newValue);
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
            mController.deleteMessage(id, -1);
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
     * Test selected messages for showing appropriate labels
     * @param selectedSet
     * @param column_id
     * @param defaultflag
     * @return true when the specified flagged message is selected
     */
    private boolean testMultiple(Set<Long> selectedSet, int column_id, boolean defaultflag) {
        Cursor c = mListAdapter.getCursor();
        if (c == null || c.isClosed()) {
            return false;
        }
        c.moveToPosition(-1);
        while (c.moveToNext()) {
            long id = c.getInt(MessageListAdapter.COLUMN_ID);
            if (selectedSet.contains(Long.valueOf(id))) {
                if (c.getInt(column_id) == (defaultflag? 1 : 0)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Implements a timed refresh of "stale" mailboxes.  This should only happen when
     * multiple conditions are true, including:
     *   Only when the user explicitly opens the mailbox (not onResume, for example)
     *   Only for real, non-push mailboxes
     *   Only when the mailbox is "stale" (currently set to 5 minutes since last refresh)
     */
    private void autoRefreshStaleMailbox() {
        if (!mCanAutoRefresh
                || (mListAdapter.getCursor() == null) // Check if messages info is loaded
                || (mPushModeMailbox != null && mPushModeMailbox) // Check the push mode
                || (mMailboxId < 0)) { // Check if this mailbox is synthetic/combined
            return;
        }
        mCanAutoRefresh = false;
        if (!Email.mailboxRequiresRefresh(mMailboxId)) {
            return;
        }
        onRefresh();
    }

    private void updateFooterButtonNames () {
        // Show "unread_action" when one or more read messages are selected.
        if (testMultiple(mListAdapter.getSelectedSet(), MessageListAdapter.COLUMN_READ, true)) {
            mReadUnreadButton.setText(R.string.unread_action);
        } else {
            mReadUnreadButton.setText(R.string.read_action);
        }
        // Show "set_star_action" when one or more un-starred messages are selected.
        if (testMultiple(mListAdapter.getSelectedSet(),
                MessageListAdapter.COLUMN_FAVORITE, false)) {
            mFavoriteButton.setText(R.string.set_star_action);
        } else {
            mFavoriteButton.setText(R.string.remove_star_action);
        }
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
        if (show) {
            updateFooterButtonNames();
        }
    }

    /**
     * Add the fixed footer view if appropriate (not always - not all accounts & mailboxes).
     *
     * Here are some rules (finish this list):
     *
     * Any merged, synced box (except send):  refresh
     * Any push-mode account:  refresh
     * Any non-push-mode account:  load more
     * Any outbox (send again):
     *
     * @param mailboxId the ID of the mailbox
     */
    private void addFooterView(long mailboxId, long accountId, int mailboxType) {
        // first, look for shortcuts that don't need us to spin up a DB access task
        if (mailboxId == Mailbox.QUERY_ALL_INBOXES
                || mailboxId == Mailbox.QUERY_ALL_UNREAD
                || mailboxId == Mailbox.QUERY_ALL_FAVORITES) {
            finishFooterView(LIST_FOOTER_MODE_REFRESH);
            return;
        }
        if (mailboxId == Mailbox.QUERY_ALL_DRAFTS || mailboxType == Mailbox.TYPE_DRAFTS) {
            finishFooterView(LIST_FOOTER_MODE_NONE);
            return;
        }
        if (mailboxId == Mailbox.QUERY_ALL_OUTBOX || mailboxType == Mailbox.TYPE_OUTBOX) {
            finishFooterView(LIST_FOOTER_MODE_SEND);
            return;
        }

        // We don't know enough to select the footer command type (yet), so we'll
        // launch an async task to do the remaining lookups and decide what to do
        mSetFooterTask = new SetFooterTask();
        mSetFooterTask.execute(mailboxId, accountId);
    }

    private final static String[] MAILBOX_ACCOUNT_AND_TYPE_PROJECTION =
        new String[] { MailboxColumns.ACCOUNT_KEY, MailboxColumns.TYPE };

    private class SetFooterTask extends AsyncTask<Long, Void, Integer> {
        /**
         * There are two operational modes here, requiring different lookup.
         * mailboxIs != -1:  A specific mailbox - check its type, then look up its account
         * accountId != -1:  A specific account - look up the account
         */
        @Override
        protected Integer doInBackground(Long... params) {
            long mailboxId = params[0];
            long accountId = params[1];
            int mailboxType = -1;
            if (mailboxId != -1) {
                try {
                    Uri uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId);
                    Cursor c = mResolver.query(uri, MAILBOX_ACCOUNT_AND_TYPE_PROJECTION,
                            null, null, null);
                    if (c.moveToFirst()) {
                        try {
                            accountId = c.getLong(0);
                            mailboxType = c.getInt(1);
                        } finally {
                            c.close();
                        }
                    }
                } catch (IllegalArgumentException iae) {
                    // can't do any more here
                    return LIST_FOOTER_MODE_NONE;
                }
            }
            switch (mailboxType) {
                case Mailbox.TYPE_OUTBOX:
                    return LIST_FOOTER_MODE_SEND;
                case Mailbox.TYPE_DRAFTS:
                    return LIST_FOOTER_MODE_NONE;
            }
            if (accountId != -1) {
                // This is inefficient but the best fix is not here but in isMessagingController
                Account account = Account.restoreAccountWithId(MessageList.this, accountId);
                if (account != null) {
                    mPushModeMailbox = account.mSyncInterval == Account.CHECK_INTERVAL_PUSH;
                    if (MessageList.this.mController.isMessagingController(account)) {
                        return LIST_FOOTER_MODE_MORE;       // IMAP or POP
                    } else {
                        return LIST_FOOTER_MODE_NONE;    // EAS
                    }
                }
            }
            return LIST_FOOTER_MODE_NONE;
        }

        @Override
        protected void onPostExecute(Integer listFooterMode) {
            if (listFooterMode == null) {
                return;
            }
            finishFooterView(listFooterMode);
        }
    }

    /**
     * Add the fixed footer view as specified, and set up the test as well.
     *
     * @param listFooterMode the footer mode we've determined should be used for this list
     */
    private void finishFooterView(int listFooterMode) {
        mListFooterMode = listFooterMode;
        if (mListFooterMode != LIST_FOOTER_MODE_NONE) {
            mListFooterView = ((LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE))
                    .inflate(R.layout.message_list_item_footer, mListView, false);
            mList.addFooterView(mListFooterView);
            setListAdapter(mListAdapter);

            mListFooterProgress = mListFooterView.findViewById(R.id.progress);
            mListFooterText = (TextView) mListFooterView.findViewById(R.id.main_text);
            setListFooterText(false);
        }
    }

    /**
     * Set the list footer text based on mode and "active" status
     */
    private void setListFooterText(boolean active) {
        if (mListFooterMode != LIST_FOOTER_MODE_NONE) {
            int footerTextId = 0;
            switch (mListFooterMode) {
                case LIST_FOOTER_MODE_REFRESH:
                    footerTextId = active ? R.string.status_loading_more
                                          : R.string.refresh_action;
                    break;
                case LIST_FOOTER_MODE_MORE:
                    footerTextId = active ? R.string.status_loading_more
                                          : R.string.message_list_load_more_messages_action;
                    break;
                case LIST_FOOTER_MODE_SEND:
                    footerTextId = active ? R.string.status_sending_messages
                                          : R.string.message_list_send_pending_messages_action;
                    break;
            }
            mListFooterText.setText(footerTextId);
        }
    }

    /**
     * Handle a click in the list footer, which changes meaning depending on what we're looking at.
     */
    private void doFooterClick() {
        switch (mListFooterMode) {
            case LIST_FOOTER_MODE_NONE:         // should never happen
                break;
            case LIST_FOOTER_MODE_REFRESH:
                onRefresh();
                break;
            case LIST_FOOTER_MODE_MORE:
                onLoadMoreMessages();
                break;
            case LIST_FOOTER_MODE_SEND:
                onSendPendingMessages();
                break;
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
            if (mailboxId == Mailbox.NO_MAILBOX && mOkToRecurse) {
                // Not found - launch network lookup
                mControllerCallback.mWaitForMailboxType = mMailboxType;
                mController.updateMailboxList(mAccountId, mControllerCallback);
            }
            return mailboxId;
        }

        @Override
        protected void onPostExecute(Long mailboxId) {
            if (mailboxId == null) {
                return;
            }
            if (mailboxId != Mailbox.NO_MAILBOX) {
                mMailboxId = mailboxId;
                mSetTitleTask = new SetTitleTask(mMailboxId);
                mSetTitleTask.execute();
                mLoadMessagesTask = new LoadMessagesTask(mMailboxId, mAccountId);
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
        private long mAccountKey;

        /**
         * Special constructor to cache some local info
         */
        public LoadMessagesTask(long mailboxKey, long accountKey) {
            mMailboxKey = mailboxKey;
            mAccountKey = accountKey;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            String selection =
                Utility.buildMailboxIdSelection(MessageList.this.mResolver, mMailboxKey);
            Cursor c = MessageList.this.managedQuery(
                    EmailContent.Message.CONTENT_URI,
                    MessageList.this.mListAdapter.PROJECTION,
                    selection, null,
                    EmailContent.MessageColumns.TIMESTAMP + " DESC");
            return c;
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if (cursor == null || cursor.isClosed()) {
                return;
            }
            MessageList.this.mListAdapter.changeCursor(cursor);
            // changeCursor occurs the jumping of position in ListView, so it's need to restore
            // the position;
            restoreListPosition();
            autoRefreshStaleMailbox();
            // Reset the "new messages" count in the service, since we're seeing them now
            if (mMailboxKey == Mailbox.QUERY_ALL_INBOXES) {
                MailService.resetNewMessageCount(MessageList.this, -1);
            } else if (mMailboxKey >= 0 && mAccountKey != -1) {
                MailService.resetNewMessageCount(MessageList.this, mAccountKey);
            }
        }
    }

    private class SetTitleTask extends AsyncTask<Void, Void, String[]> {

        private long mMailboxKey;

        public SetTitleTask(long mailboxKey) {
            mMailboxKey = mailboxKey;
        }

        @Override
        protected String[] doInBackground(Void... params) {
            // Check special Mailboxes
            if (mMailboxKey == Mailbox.QUERY_ALL_INBOXES) {
                return new String[] {null,
                        getString(R.string.account_folder_list_summary_inbox)};
            } else if (mMailboxKey == Mailbox.QUERY_ALL_FAVORITES) {
                return new String[] {null,
                        getString(R.string.account_folder_list_summary_starred)};
            } else if (mMailboxKey == Mailbox.QUERY_ALL_DRAFTS) {
                return new String[] {null,
                        getString(R.string.account_folder_list_summary_drafts)};
            } else if (mMailboxKey == Mailbox.QUERY_ALL_OUTBOX) {
                return new String[] {null,
                        getString(R.string.account_folder_list_summary_outbox)};
            }
            String accountName = null;
            String mailboxName = null;
            String accountKey = null;
            Cursor c = MessageList.this.mResolver.query(Mailbox.CONTENT_URI,
                    MAILBOX_NAME_PROJECTION, ID_SELECTION,
                    new String[] { Long.toString(mMailboxKey) }, null);
            try {
                if (c.moveToFirst()) {
                    mailboxName = Utility.FolderProperties.getInstance(MessageList.this)
                            .getDisplayName(c.getInt(MAILBOX_NAME_COLUMN_TYPE));
                    if (mailboxName == null) {
                        mailboxName = c.getString(MAILBOX_NAME_COLUMN_ID);
                    }
                    accountKey = c.getString(MAILBOX_NAME_COLUMN_ACCOUNT_KEY);
                }
            } finally {
                c.close();
            }
            if (accountKey != null) {
                c = MessageList.this.mResolver.query(Account.CONTENT_URI,
                        ACCOUNT_NAME_PROJECTION, ID_SELECTION, new String[] { accountKey },
                        null);
                try {
                    if (c.moveToFirst()) {
                        accountName = c.getString(ACCOUNT_DISPLAY_NAME_COLUMN_ID);
                    }
                } finally {
                    c.close();
                }
            }
            return new String[] {accountName, mailboxName};
        }

        @Override
        protected void onPostExecute(String[] names) {
            if (names == null) {
                return;
            }
            if (names[0] != null) {
                mRightTitle.setText(names[0]);
            }
            if (names[1] != null) {
                mLeftTitle.setText(names[1]);
            }
        }
    }

    /**
     * Handler for UI-thread operations (when called from callbacks or any other threads)
     */
    class MessageListHandler extends Handler {
        private static final int MSG_PROGRESS = 1;
        private static final int MSG_LOOKUP_MAILBOX_TYPE = 2;
        private static final int MSG_ERROR_BANNER = 3;
        private static final int MSG_REQUERY_LIST = 4;

        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_PROGRESS:
                    boolean visible = (msg.arg1 != 0);
                    if (visible) {
                        mProgressIcon.setVisibility(View.VISIBLE);
                    } else {
                        mProgressIcon.setVisibility(View.GONE);
                    }
                    if (mListFooterProgress != null) {
                        mListFooterProgress.setVisibility(visible ? View.VISIBLE : View.GONE);
                    }
                    setListFooterText(visible);
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
                case MSG_ERROR_BANNER:
                    String message = (String) msg.obj;
                    boolean isVisible = mErrorBanner.getVisibility() == View.VISIBLE;
                    if (message != null) {
                        mErrorBanner.setText(message);
                        if (!isVisible) {
                            mErrorBanner.setVisibility(View.VISIBLE);
                            mErrorBanner.startAnimation(
                                    AnimationUtils.loadAnimation(
                                            MessageList.this, R.anim.header_appear));
                        }
                    } else {
                        if (isVisible) {
                            mErrorBanner.setVisibility(View.GONE);
                            mErrorBanner.startAnimation(
                                    AnimationUtils.loadAnimation(
                                            MessageList.this, R.anim.header_disappear));
                        }
                    }
                    break;
                case MSG_REQUERY_LIST:
                    mListAdapter.doRequery();
                    if (mMultiSelectPanel.getVisibility() == View.VISIBLE) {
                        updateFooterButtonNames();
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

        /**
         * Called from any thread to signal that the list adapter should requery and update.
         */
        public void requeryList() {
            sendEmptyMessage(MSG_REQUERY_LIST);
        }
    }

    /**
     * Callback for async Controller results.
     */
    private class ControllerResults implements Controller.Result {

        // This is used to alter the connection banner operation for sending messages
        MessagingException mSendMessageException;

        // These are preset for use by updateMailboxListCallback
        int mWaitForMailboxType = -1;

        // TODO check accountKey and only react to relevant notifications
        public void updateMailboxListCallback(MessagingException result, long accountKey,
                int progress) {
            // no updateBanner here, we are only listing a single mailbox
            updateProgress(result, progress);
            if (progress == 100) {
                mHandler.lookupMailboxType(accountKey, mWaitForMailboxType);
            }
        }

        // TODO check accountKey and only react to relevant notifications
        public void updateMailboxCallback(MessagingException result, long accountKey,
                long mailboxKey, int progress, int numNewMessages) {
            updateBanner(result, progress, mailboxKey);
            if (result != null || progress == 100) {
                Email.updateMailboxRefreshTime(mMailboxId);
            }
            updateProgress(result, progress);
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

        /**
         * We alter the updateBanner hysteresis here to capture any failures and handle
         * them just once at the end.  This callback is overly overloaded:
         *  result == null, messageId == -1, progress == 0:     start batch send
         *  result == null, messageId == xx, progress == 0:     start sending one message
         *  result == xxxx, messageId == xx, progress == 0;     failed sending one message
         *  result == null, messageId == -1, progres == 100;    finish sending batch
         */
        public void sendMailCallback(MessagingException result, long accountId, long messageId,
                int progress) {
            if (mListFooterMode == LIST_FOOTER_MODE_SEND) {
                // reset captured error when we start sending one or more messages
                if (messageId == -1 && result == null && progress == 0) {
                    mSendMessageException = null;
                }
                // capture first exception that comes along
                if (result != null && mSendMessageException == null) {
                    mSendMessageException = result;
                }
                // if we're completing the sequence, change the banner state
                if (messageId == -1 && progress == 100) {
                    updateBanner(mSendMessageException, progress, mMailboxId);
                }
                // always update the spinner, which has less state to worry about
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
        private void updateBanner(MessagingException result, int progress, long mailboxKey) {
            if (mailboxKey != mMailboxId) {
                return;
            }
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

        private ColorStateList mTextColorPrimary;
        private ColorStateList mTextColorSecondary;

        // Timer to control the refresh rate of the list
        private final RefreshTimer mRefreshTimer = new RefreshTimer();
        // Last time we allowed a refresh of the list
        private long mLastRefreshTime = 0;
        // How long we want to wait for refreshes (a good starting guess)
        // I suspect this could be lowered down to even 1000 or so, but this seems ok for now
        private static final long REFRESH_INTERVAL_MS = 2500;
        
        private java.text.DateFormat mDateFormat;
        private java.text.DateFormat mDayFormat;
        private java.text.DateFormat mTimeFormat;

        private HashSet<Long> mChecked = new HashSet<Long>();

        public MessageListAdapter(Context context) {
            super(context, null, true);
            mContext = context;
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            Resources resources = context.getResources();
            mAttachmentIcon = resources.getDrawable(R.drawable.ic_mms_attachment_small);
            mFavoriteIconOn = resources.getDrawable(R.drawable.btn_star_big_buttonless_dark_on);
            mFavoriteIconOff = resources.getDrawable(R.drawable.btn_star_big_buttonless_dark_off);
            mSelectedIconOn = resources.getDrawable(R.drawable.btn_check_buttonless_dark_on);
            mSelectedIconOff = resources.getDrawable(R.drawable.btn_check_buttonless_dark_off);

            Theme theme = context.getTheme();
            TypedArray array;
            array = theme.obtainStyledAttributes(new int[] { android.R.attr.textColorPrimary });
            mTextColorPrimary = resources.getColorStateList(array.getResourceId(0, 0));
            array = theme.obtainStyledAttributes(new int[] { android.R.attr.textColorSecondary });
            mTextColorSecondary = resources.getColorStateList(array.getResourceId(0, 0));

            mDateFormat = android.text.format.DateFormat.getDateFormat(context);    // short date
            mDayFormat = android.text.format.DateFormat.getDateFormat(context);     // TODO: day
            mTimeFormat = android.text.format.DateFormat.getTimeFormat(context);    // 12/24 time
        }

        /**
         * We override onContentChange to throttle the refresh, which can happen way too often
         * on syncing a large list (up to many times per second).  This will prevent ANR's during
         * initial sync and potentially at other times as well.
         */
        @Override
        protected synchronized void onContentChanged() {
            if (mCursor != null && !mCursor.isClosed()) {
                long sinceRefresh = SystemClock.elapsedRealtime() - mLastRefreshTime;
                mRefreshTimer.schedule(REFRESH_INTERVAL_MS - sinceRefresh);
            }
        }

        /**
         * Called in UI thread only, from Handler, to complete the requery that we
         * intercepted in onContentChanged().
         */
        public void doRequery() {
            if (mCursor != null && !mCursor.isClosed()) {
                mDataValid = mCursor.requery();
                notifyDataSetChanged();
            }
        }

        class RefreshTimer extends Timer {
            private TimerTask timerTask = null;

            protected void clear() {
                timerTask = null;
            }

            protected synchronized void schedule(long delay) {
                if (timerTask != null) return;
                if (delay < 0) {
                    refreshList();
                } else {
                    timerTask = new RefreshTimerTask();
                    schedule(timerTask, delay);
                }
            }
        }

        class RefreshTimerTask extends TimerTask {
            @Override
            public void run() {
                refreshList();
            }
        }

        /**
         * Do the work of requerying the list and notifying the UI of changed data
         * Make sure we call notifyDataSetChanged on the UI thread.
         */
        private synchronized void refreshList() {
            if (Email.LOGD) {
                Log.d("messageList", "refresh: "
                        + (SystemClock.elapsedRealtime() - mLastRefreshTime) + "ms");
            }
            mHandler.requeryList();
            mLastRefreshTime = SystemClock.elapsedRealtime();
            mRefreshTimer.clear();
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

            TextView fromView = (TextView) view.findViewById(R.id.from);
            String text = cursor.getString(COLUMN_DISPLAY_NAME);
            fromView.setText(text);

            TextView subjectView = (TextView) view.findViewById(R.id.subject);
            text = cursor.getString(COLUMN_SUBJECT);
            subjectView.setText(text);

            boolean hasAttachments = cursor.getInt(COLUMN_ATTACHMENTS) != 0;
            subjectView.setCompoundDrawablesWithIntrinsicBounds(null, null,
                    hasAttachments ? mAttachmentIcon : null, null);

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

            if (itemView.mRead) {
                subjectView.setTypeface(Typeface.DEFAULT);
                fromView.setTypeface(Typeface.DEFAULT);
                fromView.setTextColor(mTextColorSecondary);
                view.setBackgroundDrawable(context.getResources().getDrawable(
                        R.drawable.message_list_item_background_read));
            } else {
                subjectView.setTypeface(Typeface.DEFAULT_BOLD);
                fromView.setTypeface(Typeface.DEFAULT_BOLD);
                fromView.setTextColor(mTextColorPrimary);
                view.setBackgroundDrawable(context.getResources().getDrawable(
                        R.drawable.message_list_item_background_unread));
            }

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
            onSetMessageFavorite(itemView.mMessageId, newFavorite);
        }
    }
}
