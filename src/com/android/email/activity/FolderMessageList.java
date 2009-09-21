/*
 * Copyright (C) 2008 The Android Open Source Project
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
import com.android.email.MessagingController;
import com.android.email.MessagingListener;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.mail.Address;
import com.android.email.mail.AuthenticationFailedException;
import com.android.email.mail.CertificateValidationException;
import com.android.email.mail.Flag;
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Message.RecipientType;
import com.android.email.mail.store.LocalStore;
import com.android.email.mail.store.LocalStore.LocalMessage;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;

import android.app.ExpandableListActivity;
import android.app.NotificationManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.util.Config;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.BaseExpandableListAdapter;
import android.widget.CursorTreeAdapter;
import android.widget.ExpandableListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ExpandableListView.ExpandableListContextMenuInfo;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;

/**
 * FolderMessageList is the primary user interface for the program. This Activity shows
 * a two level list of the Account's folders and each folder's messages. From this
 * Activity the user can perform all standard message operations.
 *
 *
 * TODO (old)
 * some things that are slowing us down:
 * Need a way to remove state such as progress bar and per folder progress on
 * resume if the command has completed.
 *
 * TODO (old)
 * Break out seperate functions for:
 *  refresh local folders
 *  refresh remote folders
 *  refresh open folder local messages
 *  refresh open folder remote messages
 *
 * And don't refresh remote folders ever unless the user runs a refresh. Maybe not even then.
 *
 * TODO (new - 2009 rebuild)
 * From old listFolders() handler - this now should happen with the groups (folder) cursor
 * reports a data change:
 *   Now we need to refresh any folders that are currently expanded. We do this
 *   in case the status or amount of messages has changed.
 */
public class FolderMessageList extends ExpandableListActivity {

    private static final boolean DBG_ADD_CONTENT = false;     // DO NOT CHECK IN AS 'TRUE'
    private static final boolean DBG_LOG_CURSORS = false;     // DO NOT CHECK IN AS 'TRUE'

    private static final String EXTRA_ACCOUNT_ID = "account";
    private static final String EXTRA_CLEAR_NOTIFICATION = "clearNotification";
    private static final String EXTRA_INITIAL_FOLDER = "initialFolder";

    private static final String STATE_KEY_LIST =
            "com.android.email.activity.folderlist_expandableListState";
    private static final String STATE_KEY_EXPANDED_GROUP =
            "com.android.email.activity.folderlist_expandedGroup";
    private static final String STATE_KEY_EXPANDED_GROUP_SELECTION =
            "com.android.email.activity.folderlist_expandedGroupSelection";
    private static final String STATE_KEY_REFRESH_REMOTE =
            "com.android.email.activity.refresh_remote";

    private static final int UPDATE_FOLDER_ON_EXPAND_INTERVAL_MS = (1000 * 60 * 3);

    private static final int[] colorChipResIds = new int[] {
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

    private static final Flag[] DELETED_FLAG = new Flag[] { Flag.DELETED };

    private ExpandableListView mListView;
    private int colorChipResId;

    private LoadMailBoxesTask mLoadMailboxesTask;
    private LoadMessagesTask mLoadMessagesTask;
    private NewMessagingListener mMessagingListener;
    private NewFolderMessageListAdapter mNewAdapter;
    private ControllerResults mControllerCallback;

    private FolderMessageListAdapter mAdapter;
    private LayoutInflater mInflater;
    private long mAccountId;
    private EmailContent.Account mAccount;
    private boolean mSyncWindowUser;

    /**
     * Stores the name of the folder that we want to open as soon as possible after load. It is
     * set to null once the folder has been opened once.
     */
    private String mInitialFolder;

    private java.text.DateFormat mDateFormat;
    private java.text.DateFormat mTimeFormat;

    private int mExpandedGroup = -1;
    private boolean mRestoringState;

    private boolean mRefreshRemote;

    /**
     * These arrays support NewFolderMessageListAdapter
     */
    private final static int[] sGroupToIds = new int[] {
            R.id.folder_name,
            R.id.folder_status,
            R.id.new_message_count
    };
    private final static int[] sChildToIds = new int[] {
            R.id.chip,
            R.id.from,
            R.id.date,
            R.id.subject
    };

    private FolderMessageListHandler mHandler = new FolderMessageListHandler();

    class FolderMessageListHandler extends Handler {
        private static final int MSG_PROGRESS = 2;
        private static final int MSG_DATA_CHANGED = 3;
        private static final int MSG_FOLDER_LOADING = 7;
        private static final int MSG_REMOVE_MESSAGE = 11;
        private static final int MSG_SYNC_MESSAGES = 13;
        private static final int MSG_FOLDER_STATUS = 17;
        private static final int MSG_NEW_MESSAGE = 19;
        private static final int MSG_REMOVE_MESSAGE_UID = 20;
        private static final int MSG_MESSAGE_UID_CHANGED = 21;
        private static final int MSG_LIST_FOLDERS_FINISHED = 22;

        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_PROGRESS:
                    setProgressBarIndeterminateVisibility(msg.arg1 != 0);
                    break;
                case MSG_DATA_CHANGED:
                    mAdapter.notifyDataSetChanged();
                    break;
                /*
                 * The following functions modify the state of the adapter's underlying list and
                 * must be run here, in the main thread, so that notifyDataSetChanged is run
                 * before any further requests are made to the adapter.
                 */
                case MSG_FOLDER_LOADING: {
                    FolderInfoHolder folder = mAdapter.getFolder((String) msg.obj);
                    if (folder != null) {
                        folder.loading = msg.arg1 != 0;
                        mAdapter.notifyDataSetChanged();
                    }
                    break;
                }
                case MSG_REMOVE_MESSAGE: {
                    FolderInfoHolder folder = (FolderInfoHolder) ((Object[]) msg.obj)[0];
                    MessageInfoHolder message = (MessageInfoHolder) ((Object[]) msg.obj)[1];
                    folder.messages.remove(message);
                    mAdapter.notifyDataSetChanged();
                    break;
                }
                case MSG_SYNC_MESSAGES: {
                    String folderName = (String) ((Object[]) msg.obj)[0];
                    Message[] messages = (Message[]) ((Object[]) msg.obj)[1];
                    FolderInfoHolder folder = mAdapter.getFolder(folderName);
                    if (folder == null) {
                        return;
                    }
                    folder.messages.clear();
                    for (Message message : messages) {
                        mAdapter.addOrUpdateMessage(folder, message, false, false);
                    }
                    Collections.sort(folder.messages);
                    mAdapter.notifyDataSetChanged();
                    break;
                }
                case MSG_FOLDER_STATUS: {
                    String folderName = (String) ((Object[]) msg.obj)[0];
                    String status = (String) ((Object[]) msg.obj)[1];
                    int forceRefresh = msg.arg1;
                    FolderInfoHolder folder = mAdapter.getFolder(folderName);
                    if (folder != null) {
                        folder.status = status;
                        if (forceRefresh != 0) {
                            /*
                             * Reset the last checked time to 0 so that the next expand will
                             * attempt to refresh this folder.
                             */
                            folder.lastChecked = 0;
                        }
                        mAdapter.notifyDataSetChanged();
                    }
                    break;
                }
                case MSG_NEW_MESSAGE: {
                    String folder = (String) ((Object[]) msg.obj)[0];
                    Message message = (Message) ((Object[]) msg.obj)[1];
                    mAdapter.addOrUpdateMessage(folder, message);
                    break;
                }
                case MSG_REMOVE_MESSAGE_UID: {
                    String folder = (String) ((Object[]) msg.obj)[0];
                    String messageUid = (String) ((Object[]) msg.obj)[1];
                    mAdapter.removeMessage(folder, messageUid);
                    break;
                }
                case MSG_MESSAGE_UID_CHANGED: {
                    String folder = (String) ((Object[]) msg.obj)[0];
                    String oldUid = (String) ((Object[]) msg.obj)[1];
                    String newUid = (String) ((Object[]) msg.obj)[2];
                    FolderInfoHolder holder = mAdapter.getFolder(folder);
                    if (folder != null) {
                        for (MessageInfoHolder message : holder.messages) {
                            if (message.uid.equals(oldUid)) {
                                message.uid = newUid;
                                message.message.setUid(newUid);
                            }
                        }
                    }
                    break;
                }
                case MSG_LIST_FOLDERS_FINISHED: {
                    doRefreshOpenMailbox();
                    break;
                }

                default:
                    super.handleMessage(msg);
            }
        }

        public void synchronizeMessages(String folder, Message[] messages) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_SYNC_MESSAGES;
            msg.obj = new Object[] { folder, messages };
            sendMessage(msg);
        }

        public void removeMessage(FolderInfoHolder folder, MessageInfoHolder message) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_REMOVE_MESSAGE;
            msg.obj = new Object[] { folder, message };
            sendMessage(msg);
        }

        public void folderLoading(String folder, boolean loading) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_FOLDER_LOADING;
            msg.arg1 = loading ? 1 : 0;
            msg.obj = folder;
            sendMessage(msg);
        }

        public void progress(boolean progress) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_PROGRESS;
            msg.arg1 = progress ? 1 : 0;
            sendMessage(msg);
        }

        public void dataChanged() {
            sendEmptyMessage(MSG_DATA_CHANGED);
        }

        public void folderStatus(String folder, String status, boolean forceRefresh) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_FOLDER_STATUS;
            msg.obj = new String[] { folder, status };
            msg.arg1 = forceRefresh ? 1 : 0;
            sendMessage(msg);
        }

        public void newMessage(String folder, Message message) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_NEW_MESSAGE;
            msg.obj = new Object[] { folder, message };
            sendMessage(msg);
        }

        public void removeMessageByUid(String folder, String messageUid) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_REMOVE_MESSAGE_UID;
            msg.obj = new Object[] { folder, messageUid };
            sendMessage(msg);
        }

        public void messageUidChanged(String folder, String oldUid, String newUid) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_MESSAGE_UID_CHANGED;
            msg.obj = new Object[] { folder, oldUid, newUid };
            sendMessage(msg);
        }

        public void listFoldersFinished() {
            sendEmptyMessage(MSG_LIST_FOLDERS_FINISHED);
        }
    }

    /**
     * This class is responsible for reloading the list of local messages for a given folder,
     * notifying the adapter that the message have been loaded and queueing up a remote
     * update of the folder.
     */
    static class FolderUpdateWorker implements Runnable {
        String mFolder;
        boolean mSynchronizeRemote;
        MessagingListener mListener;
        EmailContent.Account mAccount;
        MessagingController mController;

        /**
         * Create a worker for the given folder and specifying whether the
         * worker should synchronize the remote folder or just the local one.
         * @param folder
         * @param synchronizeRemote
         */
        public FolderUpdateWorker(String folder, boolean synchronizeRemote,
                MessagingListener listener, EmailContent.Account account,
                MessagingController controller) {
            mFolder = folder;
            mSynchronizeRemote = synchronizeRemote;
            mListener = listener;
            mAccount = account;
            mController = controller;
        }

        public void run() {
            // Lower our priority
//            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            // Synchronously load the list of local messages
//            mController.listLocalMessages(
//                    mAccount,
//                    mFolder,
//                    mListener);
//            if (mSynchronizeRemote) {
//                // Tell the MessagingController to run a remote update of this folder
//                // at it's leisure
//                mController.synchronizeMailbox(
//                        mAccount,
//                        mFolder,
//                        mListener);
//            }
        }
    }

    /**
     * Open a specific account.
     * @param context
     * @param id
     */
    @Deprecated
    public static void actionHandleAccount(Context context, long id) {
        actionHandleAccount(context, id, null);
    }

    /**
     * Open a specific account.
     * @param context
     * @param id
     * @param initialFolder The folder to open, or null for none
     */
    @Deprecated
    public static void actionHandleAccount(Context context, long id, String initialFolder) {
        Intent intent = new Intent(context, FolderMessageList.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, id);
        if (initialFolder != null) {
            intent.putExtra(EXTRA_INITIAL_FOLDER, initialFolder);
        }
        context.startActivity(intent);
    }

    @Deprecated
    public static Intent actionHandleAccountIntent(Context context, long id, String initialFolder) {
        Intent intent = new Intent(context, FolderMessageList.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, id);
        intent.putExtra(EXTRA_CLEAR_NOTIFICATION, true);
        if (initialFolder != null) {
            intent.putExtra(EXTRA_INITIAL_FOLDER, initialFolder);
        }
        return intent;
    }

    /**
     * This should be used for generating lightweight (Uri-only) intents.  It probably makes sense
     * to move entirely to this, and stop passing entire account structs through Intents.
     *
     * @param context Calling context for building the intent
     * @param account The account of interest
     * @param initialFolder If non-null, can set the folder name to open (typically Email.INBOX)
     * @return an Intent which can be used to view that account
     */
    @Deprecated
    public static Intent actionHandleAccountUriIntent(Context context, long id,
            String initialFolder) {
        Intent i = actionHandleAccountIntent(context, id, initialFolder);
        i.removeExtra(EXTRA_ACCOUNT_ID);
        Uri uri = ContentUris.withAppendedId(EmailContent.Account.CONTENT_URI, id);
        i.setData(uri);
        return i;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

        mDateFormat = android.text.format.DateFormat.getDateFormat(this);   // short format
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(this);   // 12/24 date format

        mListView = getExpandableListView();
        mListView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_INSET);
        mListView.setLongClickable(true);
        registerForContextMenu(mListView);

        /*
         * We manually save and restore the list's state because our adapter is slow.
         */
        mListView.setSaveEnabled(false);

        getExpandableListView().setGroupIndicator(
                getResources().getDrawable(R.drawable.expander_ic_folder));

        mInflater = getLayoutInflater();

        Intent intent = getIntent();
        mAccountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        mAccount = EmailContent.Account.restoreAccountWithId(this, mAccountId);
        if (mAccount == null) {
            Uri uri = intent.getData();
            if (uri != null) {
                // TODO - DO NOT CHECK IN - add Account.restoreFromUri()

                // mAccount = Preferences.getPreferences(this).getAccountByContentUri(uri);
            }
        }
        // If no useable account was specified, just go to the accounts list screen instead
        if (mAccount == null) {
            AccountFolderList.actionShowAccounts(this);
            finish();
            return;
        }

        // Take the initial folder into account only if we are *not* restoring the activity already
        if (savedInstanceState == null) {
            mInitialFolder = intent.getStringExtra(EXTRA_INITIAL_FOLDER);
        }

        /*
         * Since the color chip is always the same color for a given account we just cache the id
         * of the chip right here.
         */
        colorChipResId = colorChipResIds[(int)mAccountId % colorChipResIds.length];

        /**
         * "User" means the user adjusts the sync window in the UI (load more messages).
         * Non-user means that it's set to a fixed window e.g. 3 days
         */
        mSyncWindowUser = mAccount.getSyncLookback() == EmailContent.Account.SYNC_WINDOW_USER;

        /*
        mAdapter = new FolderMessageListAdapter();

        final Object previousData = getLastNonConfigurationInstance();
        if (previousData != null) {
            //noinspection unchecked
            mAdapter.mFolders = (ArrayList<FolderInfoHolder>) previousData;
        }

        setListAdapter(mAdapter);

        if (savedInstanceState != null) {
            mRestoringState = true;
            onRestoreListState(savedInstanceState);
            mRestoringState = false;
            mRefreshRemote |= savedInstanceState.getBoolean(STATE_KEY_REFRESH_REMOTE);
        }
        */

        mControllerCallback = new ControllerResults();
        mMessagingListener = new NewMessagingListener();

        mLoadMailboxesTask = (LoadMailBoxesTask) new LoadMailBoxesTask(savedInstanceState);
        mLoadMailboxesTask.execute();

        setTitle(mAccount.getDisplayName());
    }

    private void onRestoreListState(Bundle savedInstanceState) {
        final int expandedGroup = savedInstanceState.getInt(STATE_KEY_EXPANDED_GROUP, -1);
        if (expandedGroup >= 0  && mAdapter.getGroupCount() > expandedGroup) {
            mListView.expandGroup(expandedGroup);
            long selectedChild = savedInstanceState.getLong(STATE_KEY_EXPANDED_GROUP_SELECTION, -1);
            if (selectedChild != ExpandableListView.PACKED_POSITION_VALUE_NULL) {
                mListView.setSelection(mListView.getFlatListPosition(selectedChild));
            }
        }
        mListView.onRestoreInstanceState(savedInstanceState.getParcelable(STATE_KEY_LIST));
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return mAdapter != null ? mAdapter.mFolders : null;
    }

    @Override
    public void onPause() {
        super.onPause();
        MessagingController.getInstance(getApplication()).removeListener(mMessagingListener);
        Controller.getInstance(getApplication()).removeResultCallback(mControllerCallback);
    }

    /**
     * On resume we refresh the folder list (in the background) and we refresh the messages
     * for any folder that is currently open. This guarantees that things like unread message
     * count and read status are updated.
     */
    @Override
    public void onResume() {
        super.onResume();

        NotificationManager notifMgr = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        notifMgr.cancel(1);

        Controller.getInstance(getApplication()).addResultCallback(mControllerCallback);
        MessagingController.getInstance(getApplication()).addListener(mMessagingListener);
        mAccount.refresh(this);
        onRefresh(false);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(STATE_KEY_LIST, mListView.onSaveInstanceState());
        outState.putInt(STATE_KEY_EXPANDED_GROUP, mExpandedGroup);
        outState.putLong(STATE_KEY_EXPANDED_GROUP_SELECTION, mListView.getSelectedPosition());
        outState.putBoolean(STATE_KEY_REFRESH_REMOTE, mRefreshRemote);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mLoadMailboxesTask != null &&
                mLoadMailboxesTask.getStatus() != AsyncTask.Status.FINISHED) {
            mLoadMailboxesTask.cancel(true);
            mLoadMailboxesTask = null;
        }
        if (mLoadMessagesTask != null &&
                mLoadMessagesTask.getStatus() != AsyncTask.Status.FINISHED) {
            mLoadMessagesTask.cancel(true);
            mLoadMessagesTask = null;
        }
    }

    @Override
    public void onGroupCollapse(int groupPosition) {
        super.onGroupCollapse(groupPosition);
        mExpandedGroup = -1;
    }

    @Override
    public void onGroupExpand(int groupPosition) {
        super.onGroupExpand(groupPosition);

        // This is a huge, temporary hack, since we're not really using the child cursor
        // any more (we're jumping to MessageList).  This is allowed here because this class
        // is slated for retirement anyway.
        Cursor groupCursor = mNewAdapter.getGroup(groupPosition);
        long mailboxKey = groupCursor.getLong(EmailContent.Mailbox.CONTENT_ID_COLUMN);
        MessageList.actionHandleMailbox(this, mailboxKey);
        
//        // We enforce viewing one folder at a time, so close the previously-opened folder
//        if (mExpandedGroup != -1) {
//            mListView.collapseGroup(mExpandedGroup);
//        }
//        mExpandedGroup = groupPosition;
//
//        if (!mRestoringState) {
//            /*
//             * Scroll the selected item to the top of the screen.
//             */
//            int position = mListView.getFlatListPosition(
//                    ExpandableListView.getPackedPositionForGroup(groupPosition));
//            mListView.setSelectionFromTop(position, 0);
//        }
        
//        // We're going to build a new cursor every time here.  TODO can we cache it?
//        // Kill any previous unfinished task
//        if (mLoadMessagesTask != null &&
//                mLoadMessagesTask.getStatus() != AsyncTask.Status.FINISHED) {
//            mLoadMessagesTask.cancel(true);
//            mLoadMessagesTask = null;
//        }
//
//        // Now start a new task to create a non-empty cursor
//        Cursor groupCursor = mNewAdapter.getCursor();
//        long mailboxKey = groupCursor.getLong(EmailContent.Mailbox.CONTENT_ID_COLUMN);
//        mLoadMessagesTask = new LoadMessagesTask(groupPosition, mailboxKey);
//        mLoadMessagesTask.execute();


        // TODO replace the worker with an equivalent that syncs remote messages into folder
//        final FolderInfoHolder folder = (FolderInfoHolder) mAdapter.getGroup(groupPosition);
//        /*
//         * We'll only do a hard refresh of a particular folder every 3 minutes or if the user
//         * specifically asks for a refresh.
//         */
//        if (System.currentTimeMillis() - folder.lastChecked
//                > UPDATE_FOLDER_ON_EXPAND_INTERVAL_MS) {
//            folder.lastChecked = System.currentTimeMillis();
//            // TODO: If the previous thread is already running, we should cancel it
//            new Thread(new FolderUpdateWorker(folder.name, true, null, mAccount,
//                    MessagingController.getInstance(getApplication())))
//                    .start();
//        }
    }

    @Override
    public boolean onChildClick(ExpandableListView parent, View v, int groupPosition,
            int childPosition, long id) {

        // TODO completely rewrite this.  For now, quick access to new onOpenMessage call.
        onOpenMessage(groupPosition, childPosition);
        return true;    // "handled"

/*
        FolderInfoHolder folder = (FolderInfoHolder) mAdapter.getGroup(groupPosition);
        if (folder.outbox) {
            if (childPosition == folder.messages.size() && !folder.loading) {
                mHandler.folderLoading(mAccount.getOutboxFolderName(this), true);
                mHandler.progress(true);
                MessagingController.getInstance(getApplication()).sendPendingMessages(
                        mAccount,
                        null);
            }
            return false;
        }
        if (childPosition == folder.messages.size() && !folder.loading) {
            if (folder.status == null && mSyncWindowUser) {
                MessagingController.getInstance(getApplication()).loadMoreMessages(
                        mAccount,
                        folder.name,
                        mMessagingListener);
                return false;
            }
            else {
                MessagingController.getInstance(getApplication()).synchronizeMailbox(
                        mAccount,
                        folder.name,
                        mMessagingListener);
                return false;
            }
        }
        else if (childPosition >= folder.messages.size()) {
            return false;
        }
        MessageInfoHolder message =
            (MessageInfoHolder) mAdapter.getChild(groupPosition, childPosition);

        onOpenMessage(folder, message);
        return true;
*/
    }

    private void onRefresh(final boolean forceRemote) {
        if (forceRemote) {
            mRefreshRemote = true;
        }

        if (mRefreshRemote) {
            mHandler.progress(true);
            Controller.getInstance(getApplication()).
                    updateMailboxList(mAccount.mId, mControllerCallback);
        }
    }

    /**
     * This is typically called after refreshing the mailbox list, but it might be called at
     * other times as well.
     */
    private void doRefreshOpenMailbox() {
        if (this.mExpandedGroup != -1) {
            Cursor mailboxCursor = mNewAdapter.getGroup(mExpandedGroup);
//            EmailContent.Mailbox mailbox =
//                EmailContent.getContent(mailboxCursor, EmailContent.Mailbox.class);
//
//            if (mailbox != null) {
//                mHandler.progress(true);
//                Controller.getInstance(getApplication()).
//                        updateMailbox(mAccount.mId, mailbox, mControllerCallback);
//            }
        }
     }

    /**
     * Callback for async Controller results.  This is all a placeholder until we figure out the
     * final way to do this.
     */
    private class ControllerResults implements Controller.Result {
        public void updateMailboxListCallback(MessagingException result, long accountKey,
                int progress) {
            mHandler.progress(false);
            mHandler.listFoldersFinished();
        }

        public void updateMailboxCallback(MessagingException result, long accountKey,
                long mailboxKey, int progress, int numNewMessages) {
            mHandler.progress(false);
            if (result != null || progress == 100) {
                Email.updateMailboxRefreshTime(mailboxKey);
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
        }
    }

    @Deprecated
    private void onOpenMessage(FolderInfoHolder folder, MessageInfoHolder message) {
        /*
         * We set read=true here for UI performance reasons. The actual value will get picked up
         * on the refresh when the Activity is resumed but that may take a second or so and we
         * don't want this to show and then go away.
         * I've gone back and forth on this, and this gives a better UI experience, so I am
         * putting it back in.
         */
        if (!message.read) {
            message.read = true;
            mHandler.dataChanged();
        }

        if (folder.drafts) {
            // actionEditDraft() now takes messageId only
            // MessageCompose.actionEditDraft(this, mAccountId, message.message);
        }
        else {
            ArrayList<String> folderUids = new ArrayList<String>();
            for (MessageInfoHolder holder : folder.messages) {
                folderUids.add(holder.uid);
            }
            // deprecated
            // MessageView.actionView(this, mAccountId, folder.name, message.uid, folderUids);
        }
    }

    /**
     * TODO we can probably do the cursor work and message ID lookup in the caller, especially
     * when we implement other handlers like onReply, onDelete, etc.
     *
     * @param groupPosition
     * @param childPosition
     */
    private void onOpenMessage(int groupPosition, int childPosition) {
        Cursor mailboxCursor = mNewAdapter.getGroup(groupPosition);
        Cursor messageCursor = mNewAdapter.getChild(groupPosition, childPosition);

        int mailboxTypeColumn = mailboxCursor.getColumnIndex(EmailContent.MailboxColumns.TYPE);
        int mailboxType = mailboxCursor.getInt(mailboxTypeColumn);
        if (mailboxType == EmailContent.Mailbox.TYPE_DRAFTS) {
            // TODO - compose from draft
        } else {
            // TODO - save enough data to recreate the cursor, to enable prev/next in MessageView
            int messageIdColumn = messageCursor.getColumnIndex(EmailContent.RECORD_ID);
            long messageId = messageCursor.getLong(messageIdColumn);
            MessageView.actionView(this, messageId, -1);
        }

    }

    private void onEditAccount() {
        // We request a remote refresh *after* the account settings because user changes may
        // change the results we get from the server.  This will be picked up in onResume().
        mRefreshRemote = true;
        AccountSettings.actionSettings(this, mAccountId);
    }

    private void onAccounts() {
        AccountFolderList.actionShowAccounts(this);
        finish();
    }

    private void onCompose() {
        MessageCompose.actionCompose(this, mAccountId);
    }

    private void onDelete(MessageInfoHolder holder) {
//        MessagingController.getInstance(getApplication()).deleteMessage(
//                mAccount,
//                holder.message.getFolder().getName(),
//                holder.message,
//                null);
        mAdapter.removeMessage(holder.message.getFolder().getName(), holder.uid);
        Toast.makeText(this, R.string.message_deleted_toast, Toast.LENGTH_SHORT).show();
    }

    private void onReply(MessageInfoHolder holder) {
        // now uses messageId
        // MessageCompose.actionReply(this, mAccountId, holder.message, false);
    }

    private void onReplyAll(MessageInfoHolder holder) {
        // now uses messageId
        // MessageCompose.actionReply(this, mAccountId, holder.message, true);
    }

    private void onForward(MessageInfoHolder holder) {
        // now uses messageId
        // MessageCompose.actionForward(this, mAccountId, holder.message);
    }

    private void onToggleRead(MessageInfoHolder holder) {
//        MessagingController.getInstance(getApplication()).markMessageRead(
//                mAccount,
//                holder.message.getFolder().getName(),
//                holder.uid,
//                !holder.read);
//        holder.read = !holder.read;
//        onRefresh(false);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                onRefresh(true);
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
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.folder_message_list_option, menu);
        return true;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        ExpandableListContextMenuInfo info =
                (ExpandableListContextMenuInfo) item.getMenuInfo();
        int groupPosition =
                ExpandableListView.getPackedPositionGroup(info.packedPosition);
        int childPosition =
            ExpandableListView.getPackedPositionChild(info.packedPosition);
        if (childPosition < mAdapter.getChildrenCount(groupPosition)) {
            switch (item.getItemId()) {
                case R.id.open:
                    onOpenMessage(groupPosition, childPosition);
                    break;
            }

            // TODO: completely rewrite this.  For now, just don't crash.
/*
            FolderInfoHolder folder = (FolderInfoHolder) mAdapter.getGroup(groupPosition);
            MessageInfoHolder holder =
                (MessageInfoHolder) mAdapter.getChild(groupPosition, childPosition);
            switch (item.getItemId()) {
                case R.id.open:
                    onOpenMessage(folder, holder);
                    break;
                case R.id.delete:
                    onDelete(holder);
                    break;
                case R.id.reply:
                    onReply(holder);
                    break;
                case R.id.reply_all:
                    onReplyAll(holder);
                    break;
                case R.id.forward:
                    onForward(holder);
                    break;
                case R.id.mark_as_read:
                    onToggleRead(holder);
                    break;
            }
*/
        }
        return super.onContextItemSelected(item);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        ExpandableListContextMenuInfo info = (ExpandableListContextMenuInfo) menuInfo;
        if (ExpandableListView.getPackedPositionType(info.packedPosition) ==
                ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
            long packedPosition = info.packedPosition;
            int groupPosition = ExpandableListView.getPackedPositionGroup(packedPosition);
            int childPosition = ExpandableListView.getPackedPositionChild(packedPosition);

            // TODO: completely rewrite this.  For now, just don't crash.
/*
            FolderInfoHolder folder = (FolderInfoHolder) mAdapter.getGroup(groupPosition);
            if (folder.outbox) {
                return;
            }
            if (childPosition < folder.messages.size()) {
                getMenuInflater().inflate(R.menu.folder_message_list_context, menu);
                MessageInfoHolder message =
                        (MessageInfoHolder) mAdapter.getChild(groupPosition, childPosition);
                if (message.read) {
                    menu.findItem(R.id.mark_as_read).setTitle(R.string.mark_as_unread_action);
                }
            }
*/
        }
    }

    /**
     * Async task to handle the cursor query out of the UI thread
     */
    private class LoadMailBoxesTask extends AsyncTask<Void, Void, Cursor> {

        // We park the activity's saved instance state here, to apply
        // after the cursor is loaded and the adapter is ready for UI
        Bundle mSavedInstanceState;

        public LoadMailBoxesTask(Bundle savedInstanceState) {
            super();
            mSavedInstanceState = savedInstanceState;
        }

        // Note that we order by the mailbox columns type column, which should be ordered
        // to match desired UI presentation (e.g. INBOX at the top)
        @Override
        protected Cursor doInBackground(Void... params) {
            // Only show boxes with mail, and sort secondarily by name
            return FolderMessageList.this.managedQuery(
                    Mailbox.CONTENT_URI,
                    Mailbox.CONTENT_PROJECTION,
                    MailboxColumns.ACCOUNT_KEY + "=? and "
                        + MailboxColumns.TYPE + '<' + Mailbox.TYPE_NOT_EMAIL,
                    new String[] { String.valueOf(FolderMessageList.this.mAccountId) },
                    MailboxColumns.TYPE + ',' + MailboxColumns.DISPLAY_NAME);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if (DBG_LOG_CURSORS) {
                Log.d(Email.LOG_TAG, "LoadMailBoxesTask.onPostExecute: cursor length " +
                        String.valueOf(cursor.getCount()));
            }
            FolderMessageList.this.mNewAdapter =
                new NewFolderMessageListAdapter(cursor, FolderMessageList.this,
                        mDateFormat, mTimeFormat);
            mListView.setAdapter(FolderMessageList.this.mNewAdapter);

            // After setting up the adapter & data, restore its state (if applicable)
            if (mSavedInstanceState != null) {
                mRestoringState = true;
                onRestoreListState(mSavedInstanceState);
                mRestoringState = false;
                mRefreshRemote |= mSavedInstanceState.getBoolean(STATE_KEY_REFRESH_REMOTE);
            }

            // If there are no folders at all, this is probably a new account - reload from server
            if (cursor.getCount() == 0) {
                FolderMessageList.this.onRefresh(true);
            }

            /**
             * For debugging purposes, add a single mailbox when there are none
             */
            if (DBG_ADD_CONTENT) {
                if (cursor.getCount() < 4) {
                    EmailContent.Mailbox box = new EmailContent.Mailbox();

                    box.mDisplayName = "dummy mailbox";
                    // box.mServerId;
                    // box.mParentServerId;
                    box.mAccountKey = FolderMessageList.this.mAccountId;
                    box.mType = EmailContent.Mailbox.TYPE_INBOX;
                    // box.mDelimiter;
                    // box.mSyncKey;
                    // box.mSyncLookback;
                    // box.mSyncFrequency;
                    // box.mSyncTime;
                    box.mUnreadCount = 100;
                    box.mFlagVisible = true;
                    // box.mFlags;
                    // box.mVisibleLimit;
                    box.save(FolderMessageList.this);
                    long mailbox1Id = box.mId;

                    box = new EmailContent.Mailbox();

                    box.mDisplayName = "dummy mailbox 2";
                    box.mAccountKey = FolderMessageList.this.mAccountId;
                    box.mType = EmailContent.Mailbox.TYPE_MAIL;
                    box.mUnreadCount = 75;
                    box.mFlagVisible = true;
                    box.save(FolderMessageList.this);
                    long mailbox2Id = box.mId;

                    box = new EmailContent.Mailbox();

                    box.mDisplayName = "dummy mailbox 3";
                    box.mAccountKey = FolderMessageList.this.mAccountId;
                    box.mType = EmailContent.Mailbox.TYPE_MAIL;
                    box.mUnreadCount = 75;
                    box.mFlagVisible = true;
                    box.save(FolderMessageList.this);
                    long mailbox3Id = box.mId;

                    box = new EmailContent.Mailbox();

                    box.mDisplayName = "dummy mailbox diff acct";
                    box.mAccountKey = FolderMessageList.this.mAccountId + 1;
                    box.mType = EmailContent.Mailbox.TYPE_INBOX;
                    box.mUnreadCount = 50;
                    box.mFlagVisible = true;
                    box.save(FolderMessageList.this);

                    // finally toss in a few messages

                    EmailContent.Message msg = new EmailContent.Message();
                    msg.mDisplayName = "sender1@google.com";
                    msg.mTimeStamp = 1010101090;    // invent a better timestamp
                    msg.mSubject = "message 1";
                    // msg.mPreview;
                    // msg.mFlagRead = false;
                    // msg.mFlagLoaded = 0;
                    // msg.mFlagFavorite = false;
                    // msg.mFlagAttachment = false;
                    // msg.mFlags = 0;
//                    msg.mText = "This is the body text of the 1st email.";
//                    msg.mTextInfo = "X;X;8;" + msg.mText.length()*2;
                    // msg.mHtmlInfo;
                    // msg.mServerId;
                    // msg.mServerIntId;
                    // msg.mClientId;
                    // msg.mMessageId;
                    // msg.mThreadId;
                    // msg.mBodyKey;
                    msg.mMailboxKey = mailbox1Id;
                    msg.mAccountKey = FolderMessageList.this.mAccountId;
                    // msg.mReferenceKey;
                    // msg.mSender;
                    msg.mFrom = "sender1@google.com";
                    // msg.mTo;
                    // msg.mCc;
                    // msg.mBcc;
                    // msg.mReplyTo;
                    msg.save(FolderMessageList.this);

                    msg = new EmailContent.Message();
                    msg.mDisplayName = "sender2@google.com";
                    msg.mTimeStamp = 1010101091;    // invent a better timestamp
                    msg.mSubject = "message 2";
                    msg.mMailboxKey = mailbox1Id;
                    msg.mAccountKey = FolderMessageList.this.mAccountId;
                    msg.mFrom = "sender2@google.com";
//                    msg.mText = "This is the body text of this email.";
//                    msg.mTextInfo = "X;X;8;" + msg.mText.length()*2;
                    msg.save(FolderMessageList.this);

                    msg = new EmailContent.Message();
                    msg.mDisplayName = "sender3@google.com";
                    msg.mTimeStamp = 1010101092;    // invent a better timestamp
                    msg.mSubject = "message 3";
                    msg.mMailboxKey = mailbox1Id;
                    msg.mAccountKey = FolderMessageList.this.mAccountId;
                    msg.mFrom = "sender3@google.com";
//                    msg.mText = "This is the body text of the 3rd email.";
//                    msg.mTextInfo = "X;X;8;" + msg.mText.length()*2;
                    msg.save(FolderMessageList.this);

                    msg = new EmailContent.Message();
                    msg.mDisplayName = "sender4@google.com";
                    msg.mTimeStamp = 1010101093;    // invent a better timestamp
                    msg.mSubject = "message 4";
                    msg.mMailboxKey = mailbox2Id;
                    msg.mAccountKey = FolderMessageList.this.mAccountId;
                    msg.mFrom = "sender4@google.com";
//                    msg.mText = "This is the body text of the 4th email.";
//                    msg.mTextInfo = "X;X;8;" + msg.mText.length()*2;
                    msg.save(FolderMessageList.this);
                }
            }
        }
    }

    /**
     * Async task for loading a single folder out of the UI thread
     */
    private class LoadMessagesTask extends AsyncTask<Void, Void, Cursor> {

        private int mGroupNumber;
        private long mMailboxKey;

        /**
         * Special constructor to cache some local info
         */
        public LoadMessagesTask(int groupNumber, long mailboxKey) {
            mGroupNumber = groupNumber;
            mMailboxKey = mailboxKey;
        }

        // TODO should use a lighter-weight projection of messages because we're only
        // displaying summaries, not the whole message.
        @Override
        protected Cursor doInBackground(Void... params) {
            return FolderMessageList.this.managedQuery(
                    EmailContent.Message.CONTENT_URI,
                    EmailContent.Message.CONTENT_PROJECTION,
                    EmailContent.MessageColumns.ACCOUNT_KEY + "=?" +
                        " AND " + EmailContent.MessageColumns.MAILBOX_KEY + "=?",
                    new String[] {
                            String.valueOf(FolderMessageList.this.mAccountId),
                            String.valueOf(mMailboxKey)
                            },
                    EmailContent.MessageColumns.TIMESTAMP + " DESC");
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if (DBG_LOG_CURSORS) {
                Log.d(Email.LOG_TAG, "LoadMessagesTask.onPostExecute: cursor length " +
                        String.valueOf(cursor.getCount()));
            }
            // Confirm that we're still stuffing the correct group #
            if (mGroupNumber == FolderMessageList.this.mExpandedGroup) {
                FolderMessageList.this.mNewAdapter.setChildrenCursor(mGroupNumber, cursor);

                // TODO implement 3-minute refresh rule, instead of "every time"
                doRefreshOpenMailbox();
            }
        }
    }

    /**
     * This class implements the tree for displaying folders and messages based on cursors.
     *
     * TODO: There is a bug # 1913302 describing a bug in ResourceCursorTreeAdapter, mixing up
     * recycled views when you have "last child" or "expanded/contracted group" layouts.  Because of
     * this, we're going to simplify (for now) and simply display the message list, without the
     * added "last" view of the loading status.  This is all placeholder code until we move folder
     * view into the Account screen.
     */
    private static class NewFolderMessageListAdapter extends CursorTreeAdapter {

        Context mContext;
        private LayoutInflater mInflater;

        int mFolderNameColumn;
        int mFolderUnreadCountColumn;

        boolean haveChildColumns;
        int mChildReadFlagColumn;
        int mChildDisplayNameColumn;
        int mChildDateColumn;
        int mChildSubjectColumn;

        private java.text.DateFormat mDateFormat;
        private java.text.DateFormat mTimeFormat;

        public NewFolderMessageListAdapter(Cursor cursor, Context context, DateFormat dateFormat,
                DateFormat timeFormat) {
            super(cursor, context);
            mContext = context;
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mDateFormat = dateFormat;
            mTimeFormat = timeFormat;

            mFolderNameColumn =
                cursor.getColumnIndexOrThrow(EmailContent.MailboxColumns.DISPLAY_NAME);
            mFolderUnreadCountColumn =
                cursor.getColumnIndexOrThrow(EmailContent.MailboxColumns.UNREAD_COUNT);
        }

        /**
         * We return null here (no immediate cursor availability) and use an AsyncTask to get
         * the cursor in certain onclick situations
         */
        @Override
        protected Cursor getChildrenCursor(Cursor groupCursor) {
            return null;
        }
        
        /**
         * Overriding this allows the child cursors to be requeried on dataset changes
         */
        @Override
        public void notifyDataSetChanged() {
            notifyDataSetChanged(false);
        }

        @Override
        protected void bindChildView(View view, Context context, Cursor cursor, boolean isLastChild)
                {
            if (!haveChildColumns) {
                mChildReadFlagColumn =
                    cursor.getColumnIndexOrThrow(EmailContent.MessageColumns.FLAG_READ);
                mChildDisplayNameColumn =
                    cursor.getColumnIndexOrThrow(EmailContent.MessageColumns.DISPLAY_NAME);
                mChildDateColumn =
                    cursor.getColumnIndexOrThrow(EmailContent.MessageColumns.TIMESTAMP);
                mChildSubjectColumn =
                    cursor.getColumnIndexOrThrow(EmailContent.MessageColumns.SUBJECT);
                haveChildColumns = true;
            }
            for (int i = 0; i < sChildToIds.length; i++) {
                View v = view.findViewById(sChildToIds[i]);
                String text = null;
                switch(v.getId()) {
                    case R.id.chip:
                        // TODO - change chip color based on acct#
                        boolean readFlag = cursor.getInt(mChildReadFlagColumn) != 0;
                        v.getBackground().setAlpha(readFlag ? 0 : 255);
                        break;
                    case R.id.from:
                        text = cursor.getString(mChildDisplayNameColumn);
                        break;
                    case R.id.date:
                        long timestamp = cursor.getLong(mChildDateColumn);
                        Date date = new Date(timestamp);
                        if (Utility.isDateToday(date)) {
                            text = mTimeFormat.format(date);
                        } else {
                            text = mDateFormat.format(date);
                        }
                        break;
                    case R.id.subject:
                        // TODO maybe start using snippet instead of subject
                        text = cursor.getString(mChildSubjectColumn);
                        break;
                }
                if (text != null) ((TextView)v).setText(text);
            }
        }

        @Override
        protected void bindGroupView(View view, Context context, Cursor cursor, boolean isExpanded)
                {
            for (int i = 0; i < sGroupToIds.length; i++) {
                View v = view.findViewById(sGroupToIds[i]);
                String text = null;
                switch(v.getId()) {
                    case R.id.folder_name:
                        text = cursor.getString(mFolderNameColumn);
                        break;
                    case R.id.folder_status:
                        // TODO - display status, if any
                        v.setVisibility(View.GONE);
                        break;
                    case R.id.new_message_count:
                        text = cursor.getString(mFolderUnreadCountColumn);
                        break;
                }
                if (text != null) ((TextView)v).setText(text);
            }
        }

        @Override
        protected View newChildView(Context context, Cursor cursor, boolean isLastChild,
                ViewGroup parent) {
            return mInflater.inflate(R.layout.folder_message_list_child, parent, false);
        }

        @Override
        protected View newGroupView(Context context, Cursor cursor, boolean isExpanded,
                ViewGroup parent) {
            return mInflater.inflate(R.layout.folder_message_list_group, parent, false);
        }

    }

    /**
     * This class replaces the old MessagingListener, and most of it is not implemented because
     * we're switching over to cursors for most notifications.
     */
    private class NewMessagingListener extends MessagingListener {
    }

    class FolderMessageListAdapter extends BaseExpandableListAdapter {
        private ArrayList<FolderInfoHolder> mFolders = new ArrayList<FolderInfoHolder>();

        private MessagingListener mListener = new MessagingListener() {
//            @Override
//            public void listFoldersStarted(EmailContent.Account account) {
//                if (!account.equals(mAccount)) {
//                    return;
//                }
//                mHandler.progress(true);
//            }

//            @Override
//            public void listFoldersFailed(EmailContent.Account account, String message) {
//                if (!account.equals(mAccount)) {
//                    return;
//                }
//                mHandler.progress(false);
//                if (Email.LOGD) {
//                    Log.v(Email.LOG_TAG, "listFoldersFailed " + message);
//                }
//            }

//            @Override
//            public void listFoldersFinished(EmailContent.Account account) {
//                if (!account.equals(mAccount)) {
//                    return;
//                }
//                mHandler.progress(false);
//                mHandler.listFoldersFinished();
//            }

/*
            @Override
            public void listLocalMessagesStarted(EmailContent.Account account, String folder) {
                if (!account.equals(mAccount)) {
                    return;
                }
                mHandler.progress(true);
                mHandler.folderLoading(folder, true);
            }

            @Override
            public void listLocalMessagesFailed(EmailContent.Account account, String folder,
                    String message) {
                if (!account.equals(mAccount)) {
                    return;
                }
                mHandler.progress(false);
                mHandler.folderLoading(folder, false);
            }

            @Override
            public void listLocalMessagesFinished(EmailContent.Account account, String folder) {
                if (!account.equals(mAccount)) {
                    return;
                }
                mHandler.progress(false);
                mHandler.folderLoading(folder, false);
            }

            @Override
            public void listLocalMessages(EmailContent.Account account, String folder,
                    Message[] messages) {
                if (!account.equals(mAccount)) {
                    return;
                }
                mHandler.synchronizeMessages(folder, messages);
            }
*/
//            @Override
//            public void synchronizeMailboxStarted(EmailContent.Account account,
//                    EmailContent.Mailbox folder) {
//                if (!account.equals(mAccount)) {
//                    return;
//                }
//                mHandler.progress(true);
////                mHandler.folderLoading(folder, true);
////                mHandler.folderStatus(folder, null, false);
//            }

//            @Override
//            public void synchronizeMailboxFinished(EmailContent.Account account,
//                    EmailContent.Mailbox folder, int totalMessagesInMailbox, int numNewMessages) {
//                if (!account.equals(mAccount)) {
//                    return;
//                }
//                mHandler.progress(false);
////                mHandler.folderLoading(folder, false);
////                mHandler.folderStatus(folder, null, false);
//                onRefresh(false);
//            }

//            @Override
//            public void synchronizeMailboxFailed(EmailContent.Account account,
//                    EmailContent.Mailbox folder, Exception e) {
//                if (!account.equals(mAccount)) {
//                    return;
//                }
//                mHandler.progress(false);
////                mHandler.folderLoading(folder, false);
//                // Use exception details to select a decent string
//                // TODO combine with very similar code in AccountSettingsCheckSetup
//                int id = R.string.status_network_error;
//                if (e instanceof AuthenticationFailedException) {
//                    id = R.string.account_setup_failed_dlg_auth_message;
//                } else if (e instanceof CertificateValidationException) {
//                    id = R.string.account_setup_failed_dlg_certificate_message;
//                } else if (e instanceof MessagingException) {
//                    MessagingException me = (MessagingException) e;
//                    switch (me.getExceptionType()) {
//                        case MessagingException.IOERROR:
//                            id = R.string.account_setup_failed_ioerror;
//                            break;
//                        case MessagingException.TLS_REQUIRED:
//                            id = R.string.account_setup_failed_tls_required;
//                            break;
//                        case MessagingException.AUTH_REQUIRED:
//                            id = R.string.account_setup_failed_auth_required;
//                            break;
//                        case MessagingException.GENERAL_SECURITY:
//                            id = R.string.account_setup_failed_security;
//                            break;
//                    }
//                }
////                mHandler.folderStatus(folder, getString(id), true);
//            }

//            @Override
//            public void emptyTrashCompleted(EmailContent.Account account) {
//                if (!account.equals(mAccount)) {
//                    return;
//                }
//                onRefresh(false);
//            }

//            @Override
//            public void sendPendingMessagesCompleted(EmailContent.Account account) {
//                if (!account.equals(mAccount)) {
//                    return;
//                }
//                mHandler.folderLoading(account.getOutboxFolderName(FolderMessageList.this), false);
//                mHandler.progress(false);
//                onRefresh(false);
//            }
//
//            @Override
//            public void sendPendingMessagesFailed(EmailContent.Account account, Exception reason) {
//                if (!account.equals(mAccount)) {
//                    return;
//                }
//                String outboxName = account.getOutboxFolderName(FolderMessageList.this);
//                mHandler.folderLoading(outboxName, false);
//                mHandler.progress(false);
//                mHandler.folderStatus(outboxName, reason.getMessage(), false);
//                onRefresh(false);
//            }
//
//            @Override
//            public void sendPendingMessageFailed(EmailContent.Account account, Message message,
//                    Exception reason) {
//                if (!account.equals(mAccount)) {
//                    return;
//                }
//                // TODO May we move failed message to draft folder?
//                //mHandler.removeMessageByUid(folder, message.getUid());
//                //mHandler.folderStatus(account.getOutboxFolderName(), reason.getMessage(), false);
//            }

//            @Override
//            public void messageUidChanged(EmailContent.Account account, String folder,
//                    String oldUid, String newUid) {
//                if (!mAccount.equals(account)) {
//                    return;
//                }
//                mHandler.messageUidChanged(folder, oldUid, newUid);
//            }
        };

        private Drawable mAttachmentIcon;

        FolderMessageListAdapter() {
            mAttachmentIcon = getResources().getDrawable(R.drawable.ic_mms_attachment_small);
        }

        /*
         * Set special folder boolean indicator and display name in FolderInfoHolder
         */
        private void setSpecialFolderInfo(FolderInfoHolder holder) {
            String folderName = holder.name;
            holder.special = true;
//            if (folderName.equalsIgnoreCase(Email.INBOX)) {
//                holder.inbox = true;
//                holder.displayName = getString(R.string.special_mailbox_name_inbox);
//            } else if (folderName.equals(mAccount.getDraftsFolderName(FolderMessageList.this))) {
//                holder.drafts = true;
//                holder.displayName = getString(R.string.special_mailbox_display_name_drafts);
//            } else if (folderName.equals(mAccount.getOutboxFolderName(FolderMessageList.this))) {
//                holder.outbox = true;
//                holder.displayName = getString(R.string.special_mailbox_display_name_outbox);
//            } else if (folderName.equals(mAccount.getSentFolderName(FolderMessageList.this))) {
//                holder.sent = true;
//                holder.displayName = getString(R.string.special_mailbox_display_name_sent);
//            } else if (folderName.equals(mAccount.getTrashFolderName(FolderMessageList.this))) {
//                holder.trash = true;
//                holder.displayName = getString(R.string.special_mailbox_display_name_trash);
//            } else {
//                holder.special = false;
//                holder.displayName = folderName;
//            }
        }

        /**
         * This code is invoked (in the UI thread) when listFoldersFinished() happens.  It
         * implements the UI policy of opening the requested folder (if any).
         */
        public void doListFoldersFinished() {
            if (mInitialFolder != null) {
                int groupPosition = getFolderPosition(mInitialFolder);
                mInitialFolder = null;
                if (groupPosition != -1) {
                    mListView.expandGroup(groupPosition);
                }
            }
        }

        public void removeMessage(String folder, String messageUid) {
            FolderInfoHolder f = getFolder(folder);
            if (f == null) {
                return;
            }
            MessageInfoHolder m = getMessage(f, messageUid);
            if (m == null) {
                return;
            }
            mHandler.removeMessage(f, m);
        }

        public void addOrUpdateMessage(String folder, Message message) {
            addOrUpdateMessage(folder, message, true, true);
        }

        private void addOrUpdateMessage(FolderInfoHolder folder, Message message,
                boolean sort, boolean notify) {
            MessageInfoHolder m = getMessage(folder, message.getUid());
            if (m == null) {
                m = new MessageInfoHolder(message, folder, mDateFormat, mTimeFormat);
                folder.messages.add(m);
            }
            else {
                m.populate(message, folder);
            }
            if (sort) {
                Collections.sort(folder.messages);
            }
            if (notify) {
                mHandler.dataChanged();
            }
        }

        private void addOrUpdateMessage(String folder, Message message,
                boolean sort, boolean notify) {
            FolderInfoHolder f = getFolder(folder);
            if (f == null) {
                return;
            }
            addOrUpdateMessage(f, message, sort, notify);
        }

        public MessageInfoHolder getMessage(FolderInfoHolder folder, String messageUid) {
            for (MessageInfoHolder message : folder.messages) {
                if (message.uid.equals(messageUid)) {
                    return message;
                }
            }
            return null;
        }

        public int getGroupCount() {
          return mFolders.size();
        }

        public long getGroupId(int groupPosition) {
            return groupPosition;
        }

        public Object getGroup(int groupPosition) {
            return mFolders.get(groupPosition);
        }

        public FolderInfoHolder getFolder(String folder) {
            FolderInfoHolder folderHolder = null;
            for (int i = 0, count = getGroupCount(); i < count; i++) {
                FolderInfoHolder holder = (FolderInfoHolder) getGroup(i);
                if (holder.name.equals(folder)) {
                    folderHolder = holder;
                }
            }
            return folderHolder;
        }

        /**
         * Gets the group position of the given folder or returns -1 if the folder is not
         * found.
         * @param folder
         * @return
         */
        public int getFolderPosition(String folder) {
            for (int i = 0, count = getGroupCount(); i < count; i++) {
                FolderInfoHolder holder = (FolderInfoHolder) getGroup(i);
                if (holder.name.equals(folder)) {
                    return i;
                }
            }
            return -1;
        }

        public View getGroupView(int groupPosition, boolean isExpanded, View convertView,
                ViewGroup parent) {
            FolderInfoHolder folder = (FolderInfoHolder) getGroup(groupPosition);
            View view;
            if (convertView != null) {
                view = convertView;
            } else {
                view = mInflater.inflate(R.layout.folder_message_list_group, parent, false);
            }
            FolderViewHolder holder = (FolderViewHolder) view.getTag();
            if (holder == null) {
                holder = new FolderViewHolder();
                holder.folderName = (TextView) view.findViewById(R.id.folder_name);
                holder.newMessageCount = (TextView) view.findViewById(R.id.new_message_count);
                holder.folderStatus = (TextView) view.findViewById(R.id.folder_status);
                view.setTag(holder);
            }
            holder.folderName.setText(folder.displayName);

            if (folder.status == null) {
                holder.folderStatus.setVisibility(View.GONE);
            }
            else {
                holder.folderStatus.setText(folder.status);
                holder.folderStatus.setVisibility(View.VISIBLE);
            }

            if ((folder.inbox || !folder.special) && folder.unreadMessageCount != 0) {
                holder.newMessageCount.setText(Integer.toString(folder.unreadMessageCount));
                holder.newMessageCount.setVisibility(View.VISIBLE);
            }
            else if ((folder.outbox || folder.drafts) && folder.messageCount > 0) {
                holder.newMessageCount.setText(Integer.toString(folder.messageCount));
                holder.newMessageCount.setVisibility(View.VISIBLE);
            }
            else {
                holder.newMessageCount.setVisibility(View.GONE);
            }
            return view;
        }

        public int getChildrenCount(int groupPosition) {
            FolderInfoHolder folder = (FolderInfoHolder) getGroup(groupPosition);
            return folder.messages.size() + 1;
        }

        public long getChildId(int groupPosition, int childPosition) {
            FolderInfoHolder folder = (FolderInfoHolder) getGroup(groupPosition);
            if (childPosition < folder.messages.size()) {
                MessageInfoHolder holder = folder.messages.get(childPosition);
                return ((LocalStore.LocalMessage) holder.message).getId();
            } else {
                return -1;
            }
        }

        public Object getChild(int groupPosition, int childPosition) {
            FolderInfoHolder folder = (FolderInfoHolder) getGroup(groupPosition);
            return folder.messages.get(childPosition);
        }

        public View getChildView(int groupPosition, int childPosition, boolean isLastChild,
                View convertView, ViewGroup parent) {
            FolderInfoHolder folder = (FolderInfoHolder) getGroup(groupPosition);
            if (isLastChild) {
                View view;
                if ((convertView != null)
                        && (convertView.getId()
                                == R.layout.folder_message_list_child_footer)) {
                    view = convertView;
                }
                else {
                    view = mInflater.inflate(R.layout.folder_message_list_child_footer,
                            parent, false);
                    view.setId(R.layout.folder_message_list_child_footer);
                }
                FooterViewHolder holder = (FooterViewHolder) view.getTag();
                if (holder == null) {
                    holder = new FooterViewHolder();
                    holder.progress = (ProgressBar) view.findViewById(R.id.progress);
                    holder.main = (TextView) view.findViewById(R.id.main_text);
                    view.setTag(holder);
                }
                if (folder.loading) {
                    if (folder.outbox) {
                        holder.main.setText(R.string.status_sending_messages);
                    } else {
                        holder.main.setText(R.string.status_loading_more);
                    }
                    holder.progress.setVisibility(View.VISIBLE);
                }
                else {
                    if (folder.status == null) {
                        if (folder.outbox) {
                            holder.main.setText(R.string.message_list_send_pending_messages_action);
                        } else {
                            if (mSyncWindowUser) {
                                holder.main.setText(
                                    R.string.message_list_load_more_messages_action);
                            } else {
                                holder.main.setText(R.string.refresh_action);
                            }
                        }
                    }
                    else {
                        if (folder.outbox) {
                            holder.main.setText(R.string.status_sending_messages_failed);
                        } else {
                            holder.main.setText(R.string.status_loading_more_failed);
                        }
                    }
                    holder.progress.setVisibility(View.GONE);
                }
                return view;
            }
            else {
                MessageInfoHolder message =
                    (MessageInfoHolder) getChild(groupPosition, childPosition);
                View view;
                if ((convertView != null)
                        && (convertView.getId() != R.layout.folder_message_list_child_footer)) {
                    view = convertView;
                } else {
                    view = mInflater.inflate(R.layout.folder_message_list_child, parent, false);
                }
                MessageViewHolder holder = (MessageViewHolder) view.getTag();
                if (holder == null) {
                    holder = new MessageViewHolder();
                    holder.subject = (TextView) view.findViewById(R.id.subject);
                    holder.from = (TextView) view.findViewById(R.id.from);
                    holder.date = (TextView) view.findViewById(R.id.date);
                    holder.chip = view.findViewById(R.id.chip);
                    /*
                     * TODO
                     * The line below and the commented lines a bit further down are work
                     * in progress for outbox status. They should not be removed.
                     */
//                    holder.status = (TextView) view.findViewById(R.id.status);

                    /*
                     * This will need to move to below if we ever convert this whole thing
                     * to a combined inbox.
                     */
                    holder.chip.setBackgroundResource(colorChipResId);

                    view.setTag(holder);
                }
                holder.chip.getBackground().setAlpha(message.read ? 0 : 255);
                holder.subject.setText(message.subject);
                holder.subject.setTypeface(null, message.read ? Typeface.NORMAL : Typeface.BOLD);
                holder.from.setText(message.sender);
                holder.from.setTypeface(null, message.read ? Typeface.NORMAL : Typeface.BOLD);
                holder.date.setText(message.date);
                holder.from.setCompoundDrawablesWithIntrinsicBounds(null, null,
                        message.hasAttachments ? mAttachmentIcon : null, null);
//                if (folder.outbox) {
//                    holder.status.setText("Sending");
//                }
//                else {
//                    holder.status.setText("");
//                }
                return view;
            }
        }

        public boolean hasStableIds() {
            return true;
        }

        public boolean isChildSelectable(int groupPosition, int childPosition) {
            return childPosition < getChildrenCount(groupPosition);
        }

    }

    /**
     * Holder for a single folder's information.
     */
    static class FolderInfoHolder implements Comparable<FolderInfoHolder> {
        public String name;
        public String displayName;
        public ArrayList<MessageInfoHolder> messages;
        public long lastChecked;
        public int messageCount;
        public int unreadMessageCount;
        public boolean loading;
        public String status;
        public boolean lastCheckFailed;

        /**
         * Special folder indicator.
         */
        public boolean special;  // One of the following five folders.
        public boolean inbox;
        public boolean drafts;
        public boolean outbox;
        public boolean sent;
        public boolean trash;

        public int compareTo(FolderInfoHolder o) {
            String s1 = this.name;
            String s2 = o.name;
            if ("Inbox".equalsIgnoreCase(s1)) {
                return -1;
            } else if ("Inbox".equalsIgnoreCase(s2)) {
                return 1;
            } else
                return s1.toUpperCase().compareTo(s2.toUpperCase());
        }
    }

    /**
     * Holder for a single message's information.
     */
    static class MessageInfoHolder implements Comparable<MessageInfoHolder> {
        public String subject;
        public String date;
        public Date compareDate;
        public String sender;
        public boolean hasAttachments;
        public String uid;
        public boolean read;
        public Message message;

        private java.text.DateFormat mDateFormat;
        private java.text.DateFormat mTimeFormat;

        public MessageInfoHolder(Message m, FolderInfoHolder folder, DateFormat formatForDate,
                DateFormat formatForTime) {
            mDateFormat = formatForDate;
            mTimeFormat = formatForTime;
            populate(m, folder);
        }

        public void populate(Message m, FolderInfoHolder folder) {
            try {
                LocalMessage message = (LocalMessage) m;
                Date date = message.getSentDate();
                this.compareDate = date;
                if (Utility.isDateToday(date)) {
                    this.date = mTimeFormat.format(date);
                }
                else {
                    this.date = mDateFormat.format(date);
                }
                this.hasAttachments = message.getAttachmentCount() > 0;
                this.read = message.isSet(Flag.SEEN);
                if (folder.outbox || folder.drafts || folder.sent) {
                    this.sender = Address.toFriendly(
                            message.getRecipients(RecipientType.TO));
                }
                else {
                    this.sender = Address.toFriendly(message.getFrom());
                }
                this.subject = message.getSubject();
                this.uid = message.getUid();
                this.message = m;
            }
            catch (MessagingException me) {
                if (Email.LOGD) {
                    Log.v(Email.LOG_TAG, "Unable to load message info", me);
                }
            }
        }

        public int compareTo(MessageInfoHolder o) {
            return this.compareDate.compareTo(o.compareDate) * -1;
        }
    }

    static class FolderViewHolder {
        public TextView folderName;
        public TextView folderStatus;
        public TextView newMessageCount;
    }

    static class MessageViewHolder {
        public TextView subject;
        public TextView preview;
        public TextView from;
        public TextView date;
        public View chip;
    }

    static class FooterViewHolder {
        public ProgressBar progress;
        public TextView main;
    }


}
