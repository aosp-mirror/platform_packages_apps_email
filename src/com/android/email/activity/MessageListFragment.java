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
import com.android.email.data.MailboxAccountLoader;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.service.MailService;

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
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

// TODO Better handling of restoring list position/adapter check status
/**
 * Message list.
 *
 * <p>This fragment uses two different loaders to load data.
 * <ul>
 *   <li>One to load {@link Account} and {@link Mailbox}, with {@link MailboxAccountLoader}.
 *   <li>The other to actually load messages.
 * </ul>
 * We run them sequentially.  i.e. First starts {@link MailboxAccountLoader}, and when it finishes
 * starts the other.
 */
public class MessageListFragment extends ListFragment
        implements OnItemClickListener, OnItemLongClickListener, MessagesAdapter.Callback {

    private static final int LOADER_ID_MAILBOX_LOADER = 1;
    private static final int LOADER_ID_MESSAGES_LOADER = 2;

    // UI Support
    private Activity mActivity;
    private Callback mCallback = EmptyCallback.INSTANCE;

    private View mListFooterView;
    private TextView mListFooterText;
    private View mListFooterProgress;

    private static final int LIST_FOOTER_MODE_NONE = 0;
    private static final int LIST_FOOTER_MODE_MORE = 2;
    private int mListFooterMode;

    private MessagesAdapter mListAdapter;

    private long mMailboxId = -1;
    private long mLastLoadedMailboxId = -1;
    private Account mAccount;
    private Mailbox mMailbox;

    // Controller access
    private Controller mController;

    // Misc members
    private boolean mDoAutoRefresh;

    /** true between {@link #onResume} and {@link #onPause}. */
    private boolean mResumed;

    /**
     * Callback interface that owning activities must implement
     */
    public interface Callback {
        public static final int TYPE_REGULAR = 0;
        public static final int TYPE_DRAFT = 1;
        public static final int TYPE_TRASH = 2;

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
         *
         * @param messageId the message ID of the message
         * @param messageMailboxId the mailbox ID of the message.
         *     This will never take values like {@link Mailbox#QUERY_ALL_INBOXES}.
         * @param listMailboxId the mailbox ID of the listbox shown on this fragment.
         *     This can be that of a magic mailbox, e.g.  {@link Mailbox#QUERY_ALL_INBOXES}.
         * @param type {@link #TYPE_REGULAR}, {@link #TYPE_DRAFT} or {@link #TYPE_TRASH}.
         */
        public void onMessageOpen(long messageId, long messageMailboxId, long listMailboxId,
                int type);
    }

    private static final class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();

        @Override
        public void onMailboxNotFound() {
        }
        @Override
        public void onSelectionChanged() {
        }
        @Override
        public void onMessageOpen(
                long messageId, long messageMailboxId, long listMailboxId, int type) {
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onCreate");
        }
        super.onCreate(savedInstanceState);
        mActivity = getActivity();
        mController = Controller.getInstance(mActivity);
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
    }

    @Override
    public void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onResume");
        }
        super.onResume();
        mResumed = true;
        if (mMailboxId != -1) {
            startLoading();
        }
    }

    @Override
    public void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onPause");
        }
        mResumed = false;
        super.onStop();
    }

    @Override
    public void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onStop");
        }
        super.onStop();
    }

    @Override
    public void onDestroy() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onDestroy");
        }

        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        mListAdapter.onSaveInstanceState(outState);
    }

    // Unit tests use it
    /* package */void loadState(Bundle savedInstanceState) {
        mListAdapter.loadState(savedInstanceState);
    }

    public void setCallback(Callback callback) {
        mCallback = (callback != null) ? callback : EmptyCallback.INSTANCE;
    }

    /**
     * Called by an Activity to open an mailbox.
     *
     * @param mailboxId the ID of a mailbox, or one of "special" mailbox IDs like
     *     {@link Mailbox#QUERY_ALL_INBOXES}.  -1 is not allowed.
     */
    public void openMailbox(long mailboxId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment openMailbox");
        }
        if (mailboxId == -1) {
            throw new InvalidParameterException();
        }
        if (mMailboxId == mailboxId) {
            return;
        }

        mMailboxId = mailboxId;

        if (mResumed) {
            startLoading();
        }
    }

    /* package */MessagesAdapter getAdapterForTest() {
        return mListAdapter;
    }

    /**
     * @return the account id or -1 if it's unknown yet.  It's also -1 if it's a magic mailbox.
     */
    public long getAccountId() {
        return (mMailbox == null) ? -1 : mMailbox.mAccountKey;
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
     * @return true if it's an outbox. false otherwise, or the mailbox type is
     *         unknown yet.
     * @deprecated It's used by MessageList to see if we should show a progress
     *             for sending messages. The logic here means we can't catch
     *             callbacks while the mailbox type isn't figured out yet. That
     *             show/hide progress logic isn't working in the way it should
     *             in the first place, so fix it and remove this method.
     */
    public boolean isOutbox() {
        return mMailbox == null ? false : (mMailbox.mType == Mailbox.TYPE_OUTBOX);
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
     * Called when a message is clicked.
     */
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (view != mListFooterView) {
            MessageListItem itemView = (MessageListItem) view;
            if (isInSelectionMode()) {
                toggleSelection(itemView);
            } else {
                onMessageOpen(itemView.mMailboxId, id);
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

    private void onMessageOpen(final long mailboxId, final long messageId) {
        final int type;
        if (mMailbox == null) { // Magic mailbox
            if (mMailboxId == Mailbox.QUERY_ALL_DRAFTS) {
                type = Callback.TYPE_DRAFT;
            } else {
                type = Callback.TYPE_REGULAR;
            }
        } else {
            switch (mMailbox.mType) {
                case EmailContent.Mailbox.TYPE_DRAFTS:
                    type = Callback.TYPE_DRAFT;
                    break;
                case EmailContent.Mailbox.TYPE_TRASH:
                    type = Callback.TYPE_TRASH;
                    break;
                default:
                    type = Callback.TYPE_REGULAR;
                    break;
            }
        }
        mCallback.onMessageOpen(messageId, mailboxId, getMailboxId(), type);
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
        final long accountId = getAccountId();
        if (accountId != -1) {
            mController.updateMailbox(accountId, mMailboxId);
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
                if (c.getInt(column_id) == (defaultflag ? 1 : 0)) {
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
     * Called by activity to indicate that the user explicitly opened the
     * mailbox and it needs auto-refresh when it's first shown. TODO:
     * {@link MessageList} needs to call this as well.
     *
     * TODO It's a bit ugly. We can remove this if this fragment "remembers" the current mailbox ID
     * through configuration changes.
     */
    public void doAutoRefresh() {
        mDoAutoRefresh = true;
    }

    /**
     * Implements a timed refresh of "stale" mailboxes.  This should only happen when
     * multiple conditions are true, including:
     *   Only when the user explicitly opens the mailbox (not onResume, for example)
     *   Only for real, non-push mailboxes
     *   Only when the mailbox is "stale" (currently set to 5 minutes since last refresh)
     */
    private void autoRefreshStaleMailbox() {
        if (!mDoAutoRefresh // Not explicitly open
                || (mMailbox == null) // Magic inbox
                || (mAccount.mSyncInterval == Account.CHECK_INTERVAL_PUSH) // Not push
        ) {
            return;
        }
        mDoAutoRefresh = false;
        if (!Email.mailboxRequiresRefresh(mMailboxId)) {
            return;
        }
        onRefresh();
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
        updateListFooterText(show);
    }

    /** Implements {@link MessagesAdapter.Callback} */
    @Override
    public void onAdapterFavoriteChanged(MessageListItem itemView, boolean newFavorite) {
        onSetMessageFavorite(itemView.mMessageId, newFavorite);
    }

    /** Implements {@link MessagesAdapter.Callback} */
    @Override
    public void onAdapterSelectedChanged(
            MessageListItem itemView, boolean newSelected, int mSelectedCount) {
        mCallback.onSelectionChanged();
    }

    private void determineFooterMode() {
        mListFooterMode = LIST_FOOTER_MODE_NONE;
        if (mAccount != null && !mAccount.isEasAccount()) {
            // IMAP, POP has "load more"
            mListFooterMode = LIST_FOOTER_MODE_MORE;
        }
    }

    private void addFooterView() {
        determineFooterMode();
        if (mListFooterMode != LIST_FOOTER_MODE_NONE) {
            ListView lv = getListView();
            if (mListFooterView != null) {
                lv.removeFooterView(mListFooterView);
            }

            lv.addFooterView(mListFooterView);
            lv.setAdapter(mListAdapter);

            mListFooterProgress = mListFooterView.findViewById(R.id.progress);
            mListFooterText = (TextView) mListFooterView.findViewById(R.id.main_text);

            // TODO We don't know if it's really "inactive". Someone has to
            // remember all sync status.
            updateListFooterText(false);
        }
    }

    /**
     * Set the list footer text based on mode and "network active" status
     */
    private void updateListFooterText(boolean networkActive) {
        if (mListFooterMode != LIST_FOOTER_MODE_NONE) {
            int footerTextId = 0;
            switch (mListFooterMode) {
                case LIST_FOOTER_MODE_MORE:
                    footerTextId = networkActive ? R.string.status_loading_messages
                            : R.string.message_list_load_more_messages_action;
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
            case LIST_FOOTER_MODE_NONE: // should never happen
                break;
            case LIST_FOOTER_MODE_MORE:
                onLoadMoreMessages();
                break;
        }
    }

    private void startLoading() {
        // Clear the list. (ListFragment will show the "Loading" animation)
        setListAdapter(null);
        setListShown(false);

        // Start loading...
        final LoaderManager lm = getLoaderManager();

        // If we're loading a different mailbox, discard the previous result.
        if ((mLastLoadedMailboxId != -1) && (mLastLoadedMailboxId != mMailboxId)) {
            lm.stopLoader(LOADER_ID_MAILBOX_LOADER);
            lm.stopLoader(LOADER_ID_MESSAGES_LOADER);
        }
        lm.initLoader(LOADER_ID_MAILBOX_LOADER, null, new MailboxAccountLoaderCallback());
    }

    /**
     * Loader callbacks for {@link MailboxAccountLoader}.
     */
    private class MailboxAccountLoaderCallback implements LoaderManager.LoaderCallbacks<
            MailboxAccountLoader.Result> {
        @Override
        public Loader<MailboxAccountLoader.Result> onCreateLoader(int id, Bundle args) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG,
                        "MessageListFragment onCreateLoader(mailbox) mailboxId=" + mMailboxId);
            }
            return new MailboxAccountLoader(getActivity().getApplicationContext(), mMailboxId);
        }

        @Override
        public void onLoadFinished(Loader<MailboxAccountLoader.Result> loader,
                MailboxAccountLoader.Result result) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "MessageListFragment onLoadFinished(mailbox) mailboxId="
                        + mMailboxId);
            }
            if (!isMagicMailbox() && !result.isFound()) {
                mCallback.onMailboxNotFound();
                return;
            }

            mLastLoadedMailboxId = mMailboxId;
            mAccount = result.mAccount;
            mMailbox = result.mMailbox;
            getLoaderManager().initLoader(LOADER_ID_MESSAGES_LOADER, null,
                    new MessagesLoaderCallback());
        }
    }

    /**
     * Loader callbacks for message list.
     */
    private class MessagesLoaderCallback implements LoaderManager.LoaderCallbacks<Cursor> {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG,
                        "MessageListFragment onCreateLoader(messages) mailboxId=" + mMailboxId);
            }

            // Reset new message count.
            // TODO Do it in onLoadFinished(). Unfortunately
            // resetNewMessageCount() ends up a
            // db operation, which causes a onContentChanged notification, which
            // makes cursor
            // loaders to requery. Until we fix ContentProvider (don't notify
            // unrelated cursors)
            // we need to do it here.
            resetNewMessageCount(mActivity, mMailboxId, getAccountId());
            return MessagesAdapter.createLoader(getActivity(), mMailboxId);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG,
                        "MessageListFragment onLoadFinished(messages) mailboxId=" + mMailboxId);
            }

            // Save list view state (primarily scroll position)
            final ListView lv = getListView();
            final Utility.ListStateSaver lss = new Utility.ListStateSaver(lv);

            // Update the list
            mListAdapter.changeCursor(cursor);
            setListAdapter(mListAdapter);
            setListShown(true);

            // Restore the state
            lss.restore(lv);

            // Various post processing...
            // (resetNewMessageCount should be here. See above.)
            autoRefreshStaleMailbox();
            addFooterView();
        }
    }

    /**
     * Reset the "new message" count.
     * <ul>
     * <li>If {@code mailboxId} is {@link Mailbox#QUERY_ALL_INBOXES}, reset the
     * counts of all accounts.
     * <li>If {@code mailboxId} is not of a magic inbox (i.e. >= 0) and {@code
     * accountId} is valid, reset the count of the specified account.
     * </ul>
     */
    /* protected */static void resetNewMessageCount(
            Context context, long mailboxId, long accountId) {
        if (mailboxId == Mailbox.QUERY_ALL_INBOXES) {
            MailService.resetNewMessageCount(context, -1);
        } else if (mailboxId >= 0 && accountId != -1) {
            MailService.resetNewMessageCount(context, accountId);
        }
    }
}
