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
import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.service.MailService;

import android.app.Activity;
import android.app.ListFragment;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.security.InvalidParameterException;
import java.util.HashSet;
import java.util.Set;

public class MessageListFragment extends ListFragment implements OnItemClickListener,
        OnItemLongClickListener, MessagesAdapter.Callback {
    private static final String STATE_SELECTED_ITEM_TOP =
            "com.android.email.activity.MessageList.selectedItemTop";
    private static final String STATE_SELECTED_POSITION =
            "com.android.email.activity.MessageList.selectedPosition";
    private static final String STATE_CHECKED_ITEMS =
            "com.android.email.activity.MessageList.checkedItems";

    // UI Support
    private Activity mActivity;
    private Callback mCallback = EmptyCallback.INSTANCE;
    private View mListFooterView;
    private TextView mListFooterText;
    private View mListFooterProgress;

    private static final int LIST_FOOTER_MODE_NONE = 0;
    private static final int LIST_FOOTER_MODE_REFRESH = 1;
    private static final int LIST_FOOTER_MODE_MORE = 2;
    private static final int LIST_FOOTER_MODE_SEND = 3;
    private int mListFooterMode;

    private MessagesAdapter mListAdapter;

    // DB access
    private ContentResolver mResolver;
    private long mAccountId = -1;
    private long mMailboxId = -1;
    private LoadMessagesTask mLoadMessagesTask;
    private SetFooterTask mSetFooterTask;

    /* package */ static final String[] MESSAGE_PROJECTION = new String[] {
        EmailContent.RECORD_ID, MessageColumns.MAILBOX_KEY, MessageColumns.ACCOUNT_KEY,
        MessageColumns.DISPLAY_NAME, MessageColumns.SUBJECT, MessageColumns.TIMESTAMP,
        MessageColumns.FLAG_READ, MessageColumns.FLAG_FAVORITE, MessageColumns.FLAG_ATTACHMENT,
        MessageColumns.FLAGS,
    };

    // Controller access
    private Controller mController;

    // Misc members
    private Boolean mPushModeMailbox = null;
    private int mSavedItemTop = 0;
    private int mSavedItemPosition = -1;
    private int mFirstSelectedItemTop = 0;
    private int mFirstSelectedItemPosition = -1;
    private int mFirstSelectedItemHeight = -1;
    private boolean mCanAutoRefresh;

    private boolean mStarted;

    /**
     * Callback interface that owning activities must implement
     */
    public interface Callback {
        /**
         * Called when selected messages have been changed.
         */
        public void onSelectionChanged();

        /**
         * Called when the specified mailbox does not exist.
         */
        public void onMailboxNotFound();

        /**
         * Called when the user wants to open a message.
         * Note {@code mailboxId} is of the actual mailbox of the message, which is different from
         * {@link MessageListFragment#getMailboxId} if it's magic mailboxes.
         */
        public void onMessageOpen(final long messageId, final long mailboxId);
    }

    private static final class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();

        public void onMailboxNotFound() {
        }
        public void onSelectionChanged() {
        }
        public void onMessageOpen(long messageId, long mailboxId) {
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onCreate");
        }
        super.onCreate(savedInstanceState);
        mActivity = getActivity();
        mResolver = mActivity.getContentResolver();
        mController = Controller.getInstance(mActivity);
        mCanAutoRefresh = true;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);

        ListView listView = getListView();
        listView.setOnItemClickListener(this);
        listView.setOnItemLongClickListener(this);
        listView.setItemsCanFocus(false);

        mListAdapter = new MessagesAdapter(mActivity, new Handler(), this);

        mListFooterView = getActivity().getLayoutInflater().inflate(
                R.layout.message_list_item_footer, listView, false);

        // TODO extend this to properly deal with multiple mailboxes, cursor, etc.

        if (savedInstanceState != null) {
            // Fragment doesn't have this method.  Call it manually.
            loadState(savedInstanceState);
        }
    }

    @Override
    public void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onStart");
        }
        super.onStart();
        mStarted = true;
        if (mMailboxId != -1) {
            startLoading();
        }
    }

    @Override
    public void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onStop");
        }
        mStarted = false;
        super.onStop();
    }

    @Override
    public void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onResume");
        }
        super.onResume();
        restoreListPosition();
        autoRefreshStaleMailbox();
    }

    @Override
    public void onDestroy() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onDestroy");
        }
        Utility.cancelTaskInterrupt(mLoadMessagesTask);
        mLoadMessagesTask = null;
        Utility.cancelTaskInterrupt(mSetFooterTask);
        mSetFooterTask = null;

        if (mListAdapter != null) {
            mListAdapter.changeCursor(null);
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        saveListPosition();
        outState.putInt(STATE_SELECTED_POSITION, mSavedItemPosition);
        outState.putInt(STATE_SELECTED_ITEM_TOP, mSavedItemTop);
        Set<Long> checkedset = mListAdapter.getSelectedSet();
        long[] checkedarray = new long[checkedset.size()];
        int i = 0;
        for (Long l : checkedset) {
            checkedarray[i] = l;
            i++;
        }
        outState.putLongArray(STATE_CHECKED_ITEMS, checkedarray);
    }

    // Unit tests use it
    /* package */ void loadState(Bundle savedInstanceState) {
        mSavedItemTop = savedInstanceState.getInt(STATE_SELECTED_ITEM_TOP, 0);
        mSavedItemPosition = savedInstanceState.getInt(STATE_SELECTED_POSITION, -1);
        Set<Long> checkedset = mListAdapter.getSelectedSet();
        for (long l: savedInstanceState.getLongArray(STATE_CHECKED_ITEMS)) {
            checkedset.add(l);
        }
    }

    public void setCallback(Callback callback) {
        mCallback = (callback != null) ? callback : EmptyCallback.INSTANCE;
    }

    /**
     * Called by an Activity to open an mailbox.
     *
     * @param accountId account id of the mailbox, if already known.  Pass -1 if unknown or
     *     {@code mailboxId} is of a special mailbox.  If -1 is passed, this fragment will find it
     *     using {@code mailboxId}, which the activity can get later with {@link #getAccountId()}.
     *     Passing -1 is always safe, but we can skip a database lookup if specified.
     *
     * @param mailboxId the ID of a mailbox, or one of "special" mailbox IDs like
     *     {@link Mailbox#QUERY_ALL_INBOXES}.  -1 is not allowed.
     */
    public void openMailbox(long accountId, long mailboxId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment openMailbox");
        }
        if (mailboxId == -1) {
            throw new InvalidParameterException();
        }
        if ((mAccountId == accountId) && (mMailboxId == mailboxId)) {
            return;
        }

        mAccountId = accountId;
        mMailboxId = mailboxId;

        if (mStarted) {
            startLoading();
        }
    }

    private void startLoading() {
        // Clear the list.  (ListFragment will show the "Loading" animation)
        getListView().removeFooterView(mListFooterView);
        setListAdapter(null);
        setListShown(false);

        // Start loading...
        Utility.cancelTaskInterrupt(mLoadMessagesTask);
        mLoadMessagesTask = new LoadMessagesTask(mMailboxId, mAccountId);
        mLoadMessagesTask.execute();
    }

    /* package */ MessagesAdapter getAdapterForTest() {
        return mListAdapter;
    }

    /**
     * @return the account id or -1 if it's unknown yet.  It's also -1 if it's a magic mailbox.
     */
    public long getAccountId() {
        return mAccountId;
    }

    /**
     * @return the mailbox id, which is the value set to {@link #openMailbox(long, long)}.
     * (Meaning it will never return -1, but may return special values,
     * eg {@link Mailbox#QUERY_ALL_INBOXES}).
     */
    public long getMailboxId() {
        return mMailboxId;
    }

    /**
     * @return true if the mailbox is a "special" box.  (e.g. combined inbox, all starred, etc.)
     */
    public boolean isMagicMailbox() {
        return mMailboxId < 0;
    }

    /**
     * @return if it's an outbox.
     */
    public boolean isOutbox() {
        return mListFooterMode == LIST_FOOTER_MODE_SEND;
    }

    /**
     * @return the number of messages that are currently selecteed.
     */
    public int getSelectedCount() {
        return mListAdapter.getSelectedSet().size();
    }

    /**
     * @return true if the list is in the "selection" mode.
     */
    private boolean isInSelectionMode() {
        return getSelectedCount() > 0;
    }

    /**
     * Save the focused list item.
     *
     * TODO It's not really working.  Fix it.
     */
    private void saveListPosition() {
        mSavedItemPosition = getListView().getSelectedItemPosition();
        if (mSavedItemPosition >= 0 && getListView().isSelected()) {
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

    /**
     * Restore the focused list item.
     *
     * TODO It's not really working.  Fix it.
     */
    private void restoreListPosition() {
        if (mSavedItemPosition >= 0 && mSavedItemPosition < getListView().getCount()) {
            getListView().setSelectionFromTop(mSavedItemPosition, mSavedItemTop);
            mSavedItemPosition = -1;
            mSavedItemTop = 0;
        }
    }

    /**
     * Called when a message is clicked.
     */
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (view != mListFooterView) {
            MessageListItem itemView = (MessageListItem) view;
            if (isInSelectionMode()) {
                toggleSelection(itemView);
            } else {
                mCallback.onMessageOpen(id, itemView.mMailboxId);
            }
        } else {
            doFooterClick();
        }
    }

    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (view != mListFooterView) {
            if (isInSelectionMode()) {
                // Already in selection mode.  Ignore.
            } else {
                toggleSelection((MessageListItem) view);
                return true;
            }
        }
        return false;
    }

    private void toggleSelection(MessageListItem itemView) {
        mListAdapter.updateSelected(itemView, !mListAdapter.isSelected(itemView));
    }

    public void onMultiToggleRead() {
        onMultiToggleRead(mListAdapter.getSelectedSet());
    }

    public void onMultiToggleFavorite() {
        onMultiToggleFavorite(mListAdapter.getSelectedSet());
    }

    public void onMultiDelete() {
        onMultiDelete(mListAdapter.getSelectedSet());
    }

    /**
     * Refresh the list.  NOOP for special mailboxes (e.g. combined inbox).
     */
    public void onRefresh() {
        if (!isMagicMailbox()) {
            // Note we can use mAccountId here because it's not a magic mailbox, which doesn't have
            // a specific account id.
            mController.updateMailbox(mAccountId, mMailboxId);
        }
    }

    public void onDeselectAll() {
        mListAdapter.getSelectedSet().clear();
        getListView().invalidateViews();
        mCallback.onSelectionChanged();
    }

    /**
     * Load more messages.  NOOP for special mailboxes (e.g. combined inbox).
     */
    private void onLoadMoreMessages() {
        if (!isMagicMailbox()) {
            mController.loadMoreMessages(mMailboxId);
        }
    }

    public void onSendPendingMessages() {
        if (getMailboxId() == Mailbox.QUERY_ALL_OUTBOX) {
            mController.sendPendingMessagesForAllAccounts(mActivity);
        } else if (!isMagicMailbox()) { // Magic boxes don't have a specific account id.
            mController.sendPendingMessages(getAccountId());
        }
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
                return c.getInt(MessagesAdapter.COLUMN_READ) == 0;
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
                return c.getInt(MessagesAdapter.COLUMN_FAVORITE) != 0;
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
        Toast.makeText(mActivity, mActivity.getResources().getQuantityString(
                R.plurals.message_deleted_toast, cloneSet.size()), Toast.LENGTH_SHORT).show();
        selectedSet.clear();
        mCallback.onSelectionChanged();
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
            long id = c.getInt(MessagesAdapter.COLUMN_ID);
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
                long id = c.getInt(MessagesAdapter.COLUMN_ID);
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
            long id = c.getInt(MessagesAdapter.COLUMN_ID);
            if (selectedSet.contains(Long.valueOf(id))) {
                if (c.getInt(column_id) == (defaultflag? 1 : 0)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return true if one or more non-starred messages are selected.
     */
    public boolean doesSelectionContainNonStarredMessage() {
        return testMultiple(mListAdapter.getSelectedSet(), MessagesAdapter.COLUMN_FAVORITE,
                false);
    }

    /**
     * @return true if one or more read messages are selected.
     */
    public boolean doesSelectionContainReadMessage() {
        return testMultiple(mListAdapter.getSelectedSet(), MessagesAdapter.COLUMN_READ, true);
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
                || isMagicMailbox()) { // Check if this mailbox is synthetic/combined
            return;
        }
        mCanAutoRefresh = false;
        if (!Email.mailboxRequiresRefresh(mMailboxId)) {
            return;
        }
        onRefresh();
    }

    public void updateListPosition() { // TODO give it a better name
        int listViewHeight = getListView().getHeight();
        if (mListAdapter.getSelectedSet().size() == 1 && mFirstSelectedItemPosition >= 0
                && mFirstSelectedItemPosition < getListView().getCount()
                && listViewHeight < mFirstSelectedItemTop) {
            getListView().setSelectionFromTop(mFirstSelectedItemPosition,
                    listViewHeight - mFirstSelectedItemHeight);
        }
    }

    /**
     * Show/hide the progress icon on the list footer.  It's called by the host activity.
     * TODO: It might be cleaner if the fragment listen to the controller events and show it by
     *     itself, rather than letting the activity controll this.
     */
    public void showProgressIcon(boolean show) {
        if (mListFooterProgress != null) {
            mListFooterProgress.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        setListFooterText(show);
    }

    // Adapter callbacks
    public void onAdapterFavoriteChanged(MessageListItem itemView, boolean newFavorite) {
        onSetMessageFavorite(itemView.mMessageId, newFavorite);
    }

    public void onAdapterRequery() {
        // This updates the "multi-selection" button labels.
        mCallback.onSelectionChanged();
    }

    public void onAdapterSelectedChanged(MessageListItem itemView, boolean newSelected,
            int mSelectedCount) {
        if (mSelectedCount == 1 && newSelected) {
            mFirstSelectedItemPosition = getListView().getPositionForView(itemView);
            mFirstSelectedItemTop = itemView.getBottom();
            mFirstSelectedItemHeight = itemView.getHeight();
        } else {
            mFirstSelectedItemPosition = -1;
        }
        mCallback.onSelectionChanged();
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
     * @param accountId the ID of the account
     */
    private void addFooterView(long mailboxId, long accountId) {
        // first, look for shortcuts that don't need us to spin up a DB access task
        if (mailboxId == Mailbox.QUERY_ALL_INBOXES
                || mailboxId == Mailbox.QUERY_ALL_UNREAD
                || mailboxId == Mailbox.QUERY_ALL_FAVORITES) {
            finishFooterView(LIST_FOOTER_MODE_REFRESH);
            return;
        }
        if (mailboxId == Mailbox.QUERY_ALL_DRAFTS) {
            finishFooterView(LIST_FOOTER_MODE_NONE);
            return;
        }
        if (mailboxId == Mailbox.QUERY_ALL_OUTBOX) {
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
                Account account = Account.restoreAccountWithId(mActivity, accountId);
                if (account != null) {
                    // TODO move this to more appropriate place
                    // (don't change member fields on a worker thread.)
                    mPushModeMailbox = account.mSyncInterval == Account.CHECK_INTERVAL_PUSH;
                    if (mController.isMessagingController(account)) {
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
            if (isCancelled()) {
                return;
            }
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
            getListView().addFooterView(mListFooterView);
            getListView().setAdapter(mListAdapter);

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
     * Async task for loading a single folder out of the UI thread
     *
     * The code here (for merged boxes) is a placeholder/hack and should be replaced.  Some
     * specific notes:
     * TODO:  Move the double query into a specialized URI that returns all inbox messages
     * and do the dirty work in raw SQL in the provider.
     * TODO:  Generalize the query generation so we can reuse it in MessageView (for next/prev)
     */
    private class LoadMessagesTask extends AsyncTask<Void, Void, Cursor> {

        private final long mMailboxKey;
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
            // First, determine account id, if unknown
            if (mAccountKey == -1) { // TODO Use constant instead of -1
                if (isMagicMailbox()) {
                    // Magic mailbox.  No accountid.
                } else {
                    EmailContent.Mailbox mailbox =
                            EmailContent.Mailbox.restoreMailboxWithId(mActivity, mMailboxKey);
                    if (mailbox != null) {
                        mAccountKey = mailbox.mAccountKey;
                    } else {
                        // Mailbox not found.
                        // TODO We used to close the activity in this case, but what to do now??
                        return null;
                    }
                }
            }

            // Load messages
            String selection =
                Utility.buildMailboxIdSelection(mResolver, mMailboxKey);
            Cursor c = mActivity.getContentResolver().query(
                    EmailContent.Message.CONTENT_URI, MESSAGE_PROJECTION,
                    selection, null, EmailContent.MessageColumns.TIMESTAMP + " DESC");
            return c;
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if (isCancelled()) {
                return;
            }
            if (cursor == null || cursor.isClosed()) {
                mCallback.onMailboxNotFound();
                return;
            }
            MessageListFragment.this.mAccountId = mAccountKey;

            mListAdapter.changeCursor(cursor);
            setListAdapter(mListAdapter);

            addFooterView(mMailboxKey, mAccountKey);

            // changeCursor occurs the jumping of position in ListView, so it's need to restore
            // the position;
            restoreListPosition();
            autoRefreshStaleMailbox();
            // Reset the "new messages" count in the service, since we're seeing them now
            if (mMailboxKey == Mailbox.QUERY_ALL_INBOXES) {
                MailService.resetNewMessageCount(mActivity, -1);
            } else if (mMailboxKey >= 0 && mAccountKey != -1) {
                MailService.resetNewMessageCount(mActivity, mAccountKey);
            }
        }
    }
}
