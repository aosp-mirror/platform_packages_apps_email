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
import com.android.email.RefreshManager;
import com.android.email.Utility;
import com.android.email.Utility.ListStateSaver;
import com.android.email.data.MailboxAccountLoader;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.service.MailService;

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Context;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextPaint;
import android.util.Log;
import android.view.ActionMode;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.DragThumbnailBuilder;
import android.view.View.OnDragListener;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;

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
 *
 * TODO Finalize batch move UI.  Probably the "move" button should be disabled or hidden when
 * the selection contains non-movable messages.  But then how does the user know why they can't be
 * moved?
 */
public class MessageListFragment extends ListFragment
        implements OnItemClickListener, OnItemLongClickListener, MessagesAdapter.Callback,
        MoveMessageToDialog.Callback, OnDragListener {
    private static final String BUNDLE_LIST_STATE = "MessageListFragment.state.listState";
    private static final String BUNDLE_KEY_SELECTED_MESSAGE_ID
            = "messageListFragment.state.listState.selected_message_id";

    private static final int LOADER_ID_MAILBOX_LOADER = 1;
    private static final int LOADER_ID_MESSAGES_LOADER = 2;

    // UI Support
    private Activity mActivity;
    private Callback mCallback = EmptyCallback.INSTANCE;

    private ListView mListView;
    private View mListFooterView;
    private TextView mListFooterText;
    private View mListFooterProgress;
    private View mListPanel;
    private View mNoMessagesPanel;

    private static final int LIST_FOOTER_MODE_NONE = 0;
    private static final int LIST_FOOTER_MODE_MORE = 1;
    private int mListFooterMode;

    private MessagesAdapter mListAdapter;

    private long mMailboxId = -1;
    private long mLastLoadedMailboxId = -1;
    private long mSelectedMessageId = -1;

    private Account mAccount;
    private Mailbox mMailbox;
    private boolean mIsEasAccount;
    private boolean mIsRefreshable;
    private int mCountTotalAccounts;

    // Controller access
    private Controller mController;
    private RefreshManager mRefreshManager;
    private RefreshListener mRefreshListener = new RefreshListener();

    // Misc members
    private boolean mDoAutoRefresh;

    private boolean mOpenRequested;

    /**
     * Visibility.  On XL, message list is normally visible, except when message view is shown
     * in full-screen on portrait.
     *
     * When not visible, the contextual action bar will be gone.
     */
    private boolean mIsVisible = true;

    /** true between {@link #onResume} and {@link #onPause}. */
    private boolean mResumed;

    /**
     * {@link ActionMode} shown when 1 or more message is selected.
     */
    private ActionMode mSelectionMode;
    private SelectionModeCallback mLastSelectionModeCallback;

    /** Whether "Send all messages" should be shown. */
    private boolean mShowSendCommand;

    private Utility.ListStateSaver mSavedListState;

    private MessageOpenTask mMessageOpenTask;

    /**
     * Callback interface that owning activities must implement
     */
    public interface Callback {
        public static final int TYPE_REGULAR = 0;
        public static final int TYPE_DRAFT = 1;
        public static final int TYPE_TRASH = 2;

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

        /**
         * Called when entering/leaving selection mode.
         * @param enter true if entering, false if leaving
         */
        public void onEnterSelectionMode(boolean enter);
    }

    private static final class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();

        @Override
        public void onMailboxNotFound() {
        }
        @Override
        public void onMessageOpen(
                long messageId, long messageMailboxId, long listMailboxId, int type) {
        }
        @Override
        public void onEnterSelectionMode(boolean enter) {
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onCreate");
        }
        super.onCreate(savedInstanceState);
        mActivity = getActivity();
        setHasOptionsMenu(true);
        mController = Controller.getInstance(mActivity);
        mRefreshManager = RefreshManager.getInstance(mActivity);
        mRefreshManager.registerListener(mRefreshListener);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Use a custom layout, which includes the original layout with "send messages" panel.
        View root = inflater.inflate(R.layout.message_list_fragment,null);
        mListPanel = root.findViewById(R.id.list_panel);
        mNoMessagesPanel = root.findViewById(R.id.no_messages_panel);
        return root;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);

        mListView = getListView();
        mListView.setOnItemClickListener(this);
        mListView.setOnItemLongClickListener(this);
        mListView.setItemsCanFocus(false);
        mListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        mListAdapter = new MessagesAdapter(mActivity, this);

        mListFooterView = getActivity().getLayoutInflater().inflate(
                R.layout.message_list_item_footer, mListView, false);

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

        // If we're recovering from the stopped state, we don't have to reload.
        // (when mOpenRequested = false)
        if (mMailboxId != -1 && mOpenRequested) {
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
        mSavedListState = new Utility.ListStateSaver(getListView());
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
        Utility.cancelTaskInterrupt(mMessageOpenTask);
        mMessageOpenTask = null;
        mRefreshManager.unregisterListener(mRefreshListener);
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        mListAdapter.onSaveInstanceState(outState);
        outState.putParcelable(BUNDLE_LIST_STATE, new Utility.ListStateSaver(getListView()));
        outState.putLong(BUNDLE_KEY_SELECTED_MESSAGE_ID, mSelectedMessageId);
    }

    // Unit tests use it
    /* package */void loadState(Bundle savedInstanceState) {
        mListAdapter.loadState(savedInstanceState);
        mSavedListState = savedInstanceState.getParcelable(BUNDLE_LIST_STATE);
        mSelectedMessageId = savedInstanceState.getLong(BUNDLE_KEY_SELECTED_MESSAGE_ID);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.message_list_fragment_option, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.send).setVisible(mShowSendCommand);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.send:
                onSendPendingMessages();
                return true;

        }
        return false;
    }

    public void setCallback(Callback callback) {
        mCallback = (callback != null) ? callback : EmptyCallback.INSTANCE;
    }

    public void setVisibility(boolean isVisible) {
        if (isVisible == mIsVisible) {
            return;
        }
        mIsVisible = isVisible;
        updateSelectionMode();
    }

    /**
     * Clear all the content, stop the loaders, etc -- should be called when the fragment is hidden.
     */
    public void clearContent() {
        mMailboxId = -1;
        stopLoaders();
        onDeselectAll();
        if (mListAdapter != null) {
            mListAdapter.changeCursor(null);
        }
        setListShownNoAnimation(false);
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

        mOpenRequested = true;
        mMailboxId = mailboxId;

        onDeselectAll();
        if (mResumed) {
            startLoading();
        }
    }

    public void setSelectedMessage(long messageId) {
        mSelectedMessageId = messageId;
        if (mResumed) {
            highlightSelectedMessage(true);
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
     * @return the mailbox id, which is the value set to {@link #openMailbox}.
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
     * @return the number of messages that are currently selecteed.
     */
    public int getSelectedCount() {
        return mListAdapter.getSelectedSet().size();
    }

    /**
     * @return true if the list is in the "selection" mode.
     */
    public boolean isInSelectionMode() {
        return mSelectionMode != null;
    }

    /**
     * Called when a message is clicked.
     */
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (view != mListFooterView) {
            MessageListItem itemView = (MessageListItem) view;
            onMessageOpen(itemView.mMailboxId, id);
        } else {
            doFooterClick();
        }
    }

    // This is tentative drag & drop UI
    // STOPSHIP this entire class needs to be rewritten based on the actual UI design
    private static class ThumbnailBuilder extends DragThumbnailBuilder {
        private static Drawable sBackground;
        private static TextPaint sPaint;

        // TODO Get actual dimention from UI
        private static final int mWidth = 250;
        private final int mHeight;
        private String mDragDesc;
        private float mDragDescX;
        private float mDragDescY;

        public ThumbnailBuilder(View view, int count) {
            super(view);
            Resources resources = view.getResources();
            // TODO Get actual dimension from UI
            mHeight = view.getHeight();
            mDragDesc = resources.getQuantityString(R.plurals.move_messages, count, count);
            mDragDescX = 60;
            // Use height of this font??
            mDragDescY = view.getHeight() / 2;
            if (sBackground == null) {
                sBackground = resources.getDrawable(R.drawable.drag_background_holo);
                sBackground.setBounds(0, 0, mWidth, view.getHeight());
                sPaint = new TextPaint();
                sPaint.setTypeface(Typeface.DEFAULT_BOLD);
                sPaint.setTextSize(18);
            }
        }

        @Override
        public void onProvideThumbnailMetrics(Point thumbnailSize, Point thumbnailTouchPoint) {
            thumbnailSize.set(mWidth, mHeight);
            thumbnailTouchPoint.set(20, mHeight / 2);
        }

        @Override
        public void onDrawThumbnail(Canvas canvas) {
            super.onDrawThumbnail(canvas);
            sBackground.draw(canvas);
            canvas.drawText(mDragDesc, mDragDescX, mDragDescY, sPaint);
        }
    }

    public boolean onDrag(View view, DragEvent event) {
        switch(event.getAction()) {
            case DragEvent.ACTION_DRAG_ENDED:
                if (event.getResult()) {
                    onDeselectAll(); // Clear the selection
                }
                break;
        }
        return false;
    }

    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (view != mListFooterView) {
            // We can't move from combined accounts view
            // We also need to check the actual mailbox to see if we can move items from it
            if (mAccount == null || mMailbox == null) {
                return false;
            } else if (mMailboxId > 0 && !Mailbox.canMoveFrom(mActivity, mMailboxId)) {
                return false;
            }
            MessageListItem listItem = (MessageListItem)view;
            if (!mListAdapter.isSelected(listItem)) {
                toggleSelection(listItem);
            }
            // Create ClipData with the Uri of the message we're long clicking
            ClipData data = ClipData.newUri(mActivity.getContentResolver(),
                    MessageListItem.MESSAGE_LIST_ITEMS_CLIP_LABEL, null,
                    Message.CONTENT_URI.buildUpon()
                    .appendPath(Long.toString(listItem.mMessageId))
                    .appendQueryParameter(
                            EmailProvider.MESSAGE_URI_PARAMETER_MAILBOX_ID,
                            Long.toString(mMailboxId))
                            .build());
            Set<Long> selectedMessageIds = mListAdapter.getSelectedSet();
            int size = selectedMessageIds.size();
            // Add additional Uri's for any other selected messages
            for (Long messageId: selectedMessageIds) {
                if (messageId.longValue() != listItem.mMessageId) {
                    data.addItem(new ClipData.Item(
                            ContentUris.withAppendedId(Message.CONTENT_URI, messageId)));
                }
            }
            // Start dragging now
            listItem.setOnDragListener(this);
            listItem.startDrag(data, new ThumbnailBuilder(listItem, size), false, null);
            return true;
        }
        return false;
    }

    private void toggleSelection(MessageListItem itemView) {
        mListAdapter.toggleSelected(itemView);
    }

    /**
     * Called when a message on the list is selected
     *
     * @param messageMailboxId the actual mailbox ID of the message.  Note it's different from
     * {@link #mMailboxId} in combined mailboxes.  ({@link #mMailboxId} can take values such as
     * {@link Mailbox#QUERY_ALL_INBOXES})
     * @param messageId ID of the msesage to open.
     */
    private void onMessageOpen(final long messageMailboxId, final long messageId) {
        Utility.cancelTaskInterrupt(mMessageOpenTask);
        mMessageOpenTask = new MessageOpenTask(messageMailboxId, messageId);
        mMessageOpenTask.execute();
    }

    /**
     * Task to look up the mailbox type for a message, and kicks the callback.
     */
    private class MessageOpenTask extends AsyncTask<Void, Void, Integer> {
        private final long mMessageMailboxId;
        private final long mMessageId;

        public MessageOpenTask(long messageMailboxId, long messageId) {
            mMessageMailboxId = messageMailboxId;
            mMessageId = messageId;
        }

        @Override
        protected Integer doInBackground(Void... params) {
            // Restore the mailbox type.  Note we can't use mMailbox.mType here, because
            // we don't have mMailbox for combined mailbox.
            // ("All Starred" can contain any kind of messages.)
            switch (Mailbox.getMailboxType(mActivity, mMessageMailboxId)) {
                case EmailContent.Mailbox.TYPE_DRAFTS:
                    return Callback.TYPE_DRAFT;
                case EmailContent.Mailbox.TYPE_TRASH:
                    return Callback.TYPE_TRASH;
                default:
                    return Callback.TYPE_REGULAR;
            }
        }

        @Override
        protected void onPostExecute(Integer type) {
            if (isCancelled() || type == null) {
                return;
            }
            mCallback.onMessageOpen(mMessageId, mMessageMailboxId, getMailboxId(), type);
        }
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

    public void onMultiMove() {
        long[] messageIds = Utility.toPrimitiveLongArray(mListAdapter.getSelectedSet());
        MoveMessageToDialog dialog = MoveMessageToDialog.newInstance(getActivity(), messageIds,
                this);
        dialog.show(getFragmentManager(), "dialog");
    }

    @Override
    public void onMoveToMailboxSelected(long newMailboxId, long[] messageIds) {
        ActivityHelper.moveMessages(getActivity(), newMailboxId, messageIds);

        // Move is async, so we can't refresh now.  Instead, just clear the selection.
        onDeselectAll();
    }

    /**
     * Refresh the list.  NOOP for special mailboxes (e.g. combined inbox).
     *
     * Note: Manual refresh is enabled even for push accounts.
     */
    public void onRefresh() {
        if (!mIsRefreshable) {
            return;
        }
        long accountId = getAccountId();
        if (accountId != -1) {
            mRefreshManager.refreshMessageList(accountId, mMailboxId);
        }
    }

    public void onDeselectAll() {
        if ((mListAdapter == null) || (mListAdapter.getSelectedSet().size() == 0)) {
            return;
        }
        mListAdapter.getSelectedSet().clear();
        getListView().invalidateViews();
        if (isInSelectionMode()) {
            finishSelectionMode();
        }
    }

    /**
     * Load more messages.  NOOP for special mailboxes (e.g. combined inbox).
     */
    private void onLoadMoreMessages() {
        long accountId = getAccountId();
        if (accountId != -1) {
            mRefreshManager.loadMoreMessages(accountId, mMailboxId);
        }
    }

    /**
     * @return if it's an outbox or "all outboxes".
     *
     * TODO make it private.  It's only used by MessageList, but the callsite is obsolete.
     */
    public boolean isOutbox() {
        return (getMailboxId() == Mailbox.QUERY_ALL_OUTBOX)
            || ((mMailbox != null) && (mMailbox.mType == Mailbox.TYPE_OUTBOX));
    }

    public void onSendPendingMessages() {
        RefreshManager rm = RefreshManager.getInstance(mActivity);
        if (getMailboxId() == Mailbox.QUERY_ALL_OUTBOX) {
            rm.sendPendingMessagesForAllAccounts();
        } else if (mMailbox != null) { // Magic boxes don't have a specific account id.
            rm.sendPendingMessages(mMailbox.mAccountKey);
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
        // Message deletion is async... Can't refresh the list immediately.
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

        refreshList();

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
     *   Only refreshable mailboxes.
     *   Only when the user explicitly opens the mailbox (not onResume, for example)
     *   Only when the mailbox is "stale" (currently set to 5 minutes since last refresh)
     * Note we do this even if it's a push account; even on Exchange only inbox can be pushed.
     */
    private void autoRefreshStaleMailbox() {
        if (!mDoAutoRefresh // Not explicitly open
                || !mIsRefreshable // Not refreshable (special box such as drafts, or magic boxes)
                ) {
            return;
        }
        mDoAutoRefresh = false;
        if (!mRefreshManager.isMailboxStale(mMailboxId)) {
            return;
        }
        onRefresh();
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
        updateSelectionMode();
    }

    private void determineFooterMode() {
        mListFooterMode = LIST_FOOTER_MODE_NONE;
        if ((mMailbox == null) || (mMailbox.mType == Mailbox.TYPE_OUTBOX)
                || (mMailbox.mType == Mailbox.TYPE_DRAFTS)) {
            return; // No footer
        }
        if (!mIsEasAccount) {
            // IMAP, POP has "load more"
            mListFooterMode = LIST_FOOTER_MODE_MORE;
        }
    }

    private void addFooterView() {
        ListView lv = getListView();
        if (mListFooterView != null) {
            lv.removeFooterView(mListFooterView);
        }
        determineFooterMode();
        if (mListFooterMode != LIST_FOOTER_MODE_NONE) {

            lv.addFooterView(mListFooterView);
            lv.setAdapter(mListAdapter);

            mListFooterProgress = mListFooterView.findViewById(R.id.progress);
            mListFooterText = (TextView) mListFooterView.findViewById(R.id.main_text);

            updateListFooter();
        }
    }

    /**
     * Set the list footer text based on mode and the current "network active" status
     */
    private void updateListFooter() {
        if (mListFooterMode != LIST_FOOTER_MODE_NONE) {
            int footerTextId = 0;
            switch (mListFooterMode) {
                case LIST_FOOTER_MODE_MORE:
                    boolean active = mRefreshManager.isMessageListRefreshing(mMailboxId);
                    footerTextId = active ? R.string.status_loading_messages
                            : R.string.message_list_load_more_messages_action;
                    mListFooterProgress.setVisibility(active ? View.VISIBLE : View.GONE);
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

    private void showSendCommand(boolean show) {
        mShowSendCommand = show;
        mActivity.invalidateOptionsMenu();
    }

    private void showSendCommandIfNecessary() {
        showSendCommand(isOutbox() && (mListAdapter != null) && (mListAdapter.getCount() > 0));
    }

    private void showNoMessageText(boolean visible) {
        mNoMessagesPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        mListPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private void showNoMessageTextIfNecessary() {
        boolean noItem = (mListFooterMode == LIST_FOOTER_MODE_NONE)
                && (mListView.getCount() == 0);
        showNoMessageText(noItem);
    }

    private void startLoading() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListFragment startLoading");
        }
        mOpenRequested = false;

        // Clear the list. (ListFragment will show the "Loading" animation)
        showNoMessageText(false);
        setListShown(false);
        showSendCommand(false);

        // Start loading...
        final LoaderManager lm = getLoaderManager();

        // If we're loading a different mailbox, discard the previous result.
        // It also causes not to preserve the list position.
        boolean mailboxChanging = false;
        if ((mLastLoadedMailboxId != -1) && (mLastLoadedMailboxId != mMailboxId)) {
            mailboxChanging = true;
            stopLoaders();
        }
        lm.initLoader(LOADER_ID_MAILBOX_LOADER, null,
                new MailboxAccountLoaderCallback(mailboxChanging));
    }

    private void stopLoaders() {
        final LoaderManager lm = getLoaderManager();
        lm.stopLoader(LOADER_ID_MAILBOX_LOADER);
        lm.stopLoader(LOADER_ID_MESSAGES_LOADER);
    }

    /**
     * Loader callbacks for {@link MailboxAccountLoader}.
     */
    private class MailboxAccountLoaderCallback implements LoaderManager.LoaderCallbacks<
            MailboxAccountLoader.Result> {
        private boolean mMailboxChanging;

        public MailboxAccountLoaderCallback(boolean mailboxChanging) {
            mMailboxChanging = mailboxChanging;
        }

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
            if (!result.mIsFound) {
                mCallback.onMailboxNotFound();
                return;
            }

            mLastLoadedMailboxId = mMailboxId;
            mAccount = result.mAccount;
            mMailbox = result.mMailbox;
            mIsEasAccount = result.mIsEasAccount;
            mIsRefreshable = result.mIsRefreshable;
            mCountTotalAccounts = result.mCountTotalAccounts;
            getLoaderManager().initLoader(LOADER_ID_MESSAGES_LOADER, null,
                    new MessagesLoaderCallback(mMailboxChanging));

            // Clear this for next reload triggered by content changed events.
            mMailboxChanging = false;
        }
    }

    /**
     * Reload the data and refresh the list view.
     */
    private void refreshList() {
        getLoaderManager().restartLoader(LOADER_ID_MESSAGES_LOADER, null,
                new MessagesLoaderCallback(false));
    }

    /**
     * Loader callbacks for message list.
     */
    private class MessagesLoaderCallback implements LoaderManager.LoaderCallbacks<Cursor> {
        private boolean mMailboxChanging;

        public MessagesLoaderCallback(boolean mailboxChanging) {
            mMailboxChanging = mailboxChanging;
        }

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG,
                        "MessageListFragment onCreateLoader(messages) mailboxId=" + mMailboxId);
            }
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
            final Utility.ListStateSaver lss;
            if (mMailboxChanging) {
                lss = null; // Don't preserve list state
            } else if (mSavedListState != null) {
                lss = mSavedListState;
                mSavedListState = null;
            } else {
                lss = new Utility.ListStateSaver(lv);
            }

            // Update the list
            mListAdapter.changeCursor(cursor);
            // Show chips if combined view.
            mListAdapter.setShowColorChips(mMailboxId < 0 && mCountTotalAccounts > 1);
            setListAdapter(mListAdapter);
            setListShown(true);

            // Various post processing...
            autoRefreshStaleMailbox();
            addFooterView();
            updateSelectionMode();
            showSendCommandIfNecessary();
            showNoMessageTextIfNecessary();

            // We want to make selection visible only when the loader was explicitly started.
            // i.e. Refresh caused by content changed events shouldn't scroll the list.
            highlightSelectedMessage(mMailboxChanging);

            // Restore the state -- this step has to be the last, because Some of the
            // "post processing" seems to reset the scroll position.
            if (lss != null) {
                lss.restore(lv);
            }

            resetNewMessageCount(mActivity, mMailboxId, getAccountId());

            // Clear this for next reload triggered by content changed events.
            mMailboxChanging = false;
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

    /**
     * Show/hide the "selection" action mode, according to the number of selected messages and
     * the visibility of the fragment.
     * Also update the content (title and menus) if necessary.
     */
    public void updateSelectionMode() {
        final int numSelected = getSelectedCount();
        if ((numSelected == 0) || !mIsVisible) {
            finishSelectionMode();
            return;
        }
        if (isInSelectionMode()) {
            updateSelectionModeView();
        } else {
            mLastSelectionModeCallback = new SelectionModeCallback();
            getActivity().startActionMode(mLastSelectionModeCallback);
        }
    }


    /**
     * Finish the "selection" action mode.
     *
     * Note this method finishes the contextual mode, but does *not* clear the selection.
     * If you want to do so use {@link #onDeselectAll()} instead.
     */
    private void finishSelectionMode() {
        if (isInSelectionMode()) {
            mLastSelectionModeCallback.mClosedByUser = false;
            mSelectionMode.finish();
        }
    }

    /** Update the "selection" action mode bar */
    private void updateSelectionModeView() {
        mSelectionMode.invalidate();
    }

    private class SelectionModeCallback implements ActionMode.Callback {
        private MenuItem mMarkRead;
        private MenuItem mMarkUnread;
        private MenuItem mAddStar;
        private MenuItem mRemoveStar;

        /* package */ boolean mClosedByUser = true;

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mSelectionMode = mode;

            MenuInflater inflater = getActivity().getMenuInflater();
            inflater.inflate(R.menu.message_list_selection_mode, menu);
            mMarkRead = menu.findItem(R.id.mark_read);
            mMarkUnread = menu.findItem(R.id.mark_unread);
            mAddStar = menu.findItem(R.id.add_star);
            mRemoveStar = menu.findItem(R.id.remove_star);

            mCallback.onEnterSelectionMode(true);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            int num = getSelectedCount();
            // Set title -- "# selected"
            mSelectionMode.setTitle(getActivity().getResources().getQuantityString(
                    R.plurals.message_view_selected_message_count, num, num));

            // Show appropriate menu items.
            boolean nonStarExists = doesSelectionContainNonStarredMessage();
            boolean readExists = doesSelectionContainReadMessage();
            mMarkRead.setVisible(!readExists);
            mMarkUnread.setVisible(readExists);
            mAddStar.setVisible(nonStarExists);
            mRemoveStar.setVisible(!nonStarExists);
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.mark_read:
                case R.id.mark_unread:
                    onMultiToggleRead();
                    break;
                case R.id.add_star:
                case R.id.remove_star:
                    onMultiToggleFavorite();
                    break;
                case R.id.delete:
                    onMultiDelete();
                    break;
                case R.id.move:
                    onMultiMove();
                    break;
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mCallback.onEnterSelectionMode(false);

            // Clear this before onDeselectAll() to prevent onDeselectAll() from trying to close the
            // contextual mode again.
            mSelectionMode = null;
            if (mClosedByUser) {
                // Clear selection, only when the contextual mode is explicitly closed by the user.
                //
                // We close the contextual mode when the fragment becomes temporary invisible
                // (i.e. mIsVisible == false) too, in which case we want to keep the selection.
                onDeselectAll();
            }
        }
    }

    private class RefreshListener implements RefreshManager.Listener {
        @Override
        public void onMessagingError(long accountId, long mailboxId, String message) {
        }

        @Override
        public void onRefreshStatusChanged(long accountId, long mailboxId) {
            updateListFooter();
        }
    }

    /**
     * Object that holds the current state (right now it's only the ListView state) of the fragment.
     *
     * Used by {@link MessageListXLFragmentManager} to preserve scroll position through fragment
     * transitions.
     */
    public static class State implements Parcelable {
        private final ListStateSaver mListState;

        private State(Parcel p) {
            mListState = p.readParcelable(getClass().getClassLoader());
        }

        private State(MessageListFragment messageListFragment) {
            mListState = new Utility.ListStateSaver(messageListFragment.getListView());
        }

        public void restore(MessageListFragment messageListFragment) {
            messageListFragment.mSavedListState = mListState;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeParcelable(mListState, flags);
        }

        public static final Parcelable.Creator<State> CREATOR
                = new Parcelable.Creator<State>() {
                    public State createFromParcel(Parcel in) {
                        return new State(in);
                    }

                    public State[] newArray(int size) {
                        return new State[size];
                    }
                };
    }

    public State getState() {
        return new State(this);
    }

    /**
     * Highlight the selected message.
     */
    private void highlightSelectedMessage(boolean ensureSelectionVisible) {
        if (mSelectedMessageId == -1) {
            // No mailbox selected
            mListView.clearChoices();
            return;
        }

        final int count = mListView.getCount();
        for (int i = 0; i < count; i++) {
            if (mListView.getItemIdAtPosition(i) != mSelectedMessageId) {
                continue;
            }
            mListView.setItemChecked(i, true);
            if (ensureSelectionVisible) {
                Utility.listViewSmoothScrollToPosition(getActivity(), mListView, i);
            }
            break;
        }
    }
}
