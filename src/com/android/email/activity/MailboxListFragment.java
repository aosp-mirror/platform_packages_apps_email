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
import com.android.email.provider.EmailProvider;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.utility.Utility;

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnDragListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.AdapterView.OnItemClickListener;

import java.security.InvalidParameterException;

/**
 * This fragment presents a list of mailboxes for a given account.  The "API" includes the
 * following elements which must be provided by the host Activity.
 *
 *  - call bindActivityInfo() to provide the account ID and set callbacks
 *  - provide callbacks for onOpen and onRefresh
 *  - pass-through implementations of onCreateContextMenu() and onContextItemSelected() (temporary)
 *
 * TODO Restoring ListView state -- don't do this when changing accounts
 */
public class MailboxListFragment extends ListFragment implements OnItemClickListener,
        OnDragListener {
    private static final String TAG = "MailboxListFragment";
    private static final String BUNDLE_KEY_SELECTED_MAILBOX_ID
            = "MailboxListFragment.state.selected_mailbox_id";
    private static final String BUNDLE_LIST_STATE = "MailboxListFragment.state.listState";
    private static final int LOADER_ID_MAILBOX_LIST = 1;
    private static final boolean DEBUG_DRAG_DROP = false; // MUST NOT SUBMIT SET TO TRUE

    private static final int NO_DROP_TARGET = -1;
    // Total height of the top and bottom scroll zones, in pixels
    private static final int SCROLL_ZONE_SIZE = 64;
    // The amount of time to scroll by one pixel, in ms
    private static final int SCROLL_SPEED = 4;

    private RefreshManager mRefreshManager;

    // UI Support
    private Activity mActivity;
    private MailboxesAdapter mListAdapter;
    private Callback mCallback = EmptyCallback.INSTANCE;

    private ListView mListView;

    private boolean mResumed;

    // Colors used for drop targets
    private static Integer sDropTrashColor;
    private static Drawable sDropActiveDrawable;

    private long mLastLoadedAccountId = -1;
    private long mAccountId = -1;
    private long mSelectedMailboxId = -1;

    private boolean mOpenRequested;

    // True if a drag is currently in progress
    private boolean mDragInProgress = false;
    // The mailbox id of the dragged item's mailbox.  We use it to prevent that box from being a
    // valid drop target
    private long mDragItemMailboxId = -1;
    // The adapter position that the user's finger is hovering over
    private int mDropTargetAdapterPosition = NO_DROP_TARGET;
    // The mailbox list item view that the user's finger is hovering over
    private MailboxListItem mDropTargetView;
    // Lazily instantiated height of a mailbox list item (-1 is a sentinel for 'not initialized')
    private int mDragItemHeight = -1;
    // True if we are currently scrolling under the drag item
    private boolean mTargetScrolling;

    private Utility.ListStateSaver mSavedListState;

    private MailboxesAdapter.Callback mMailboxesAdapterCallback = new MailboxesAdapter.Callback() {
        @Override
        public void onSetDropTargetBackground(MailboxListItem listItem) {
            listItem.setDropTargetBackground(mDragInProgress, mDragItemMailboxId);
        }
    };

    /**
     * Callback interface that owning activities must implement
     */
    public interface Callback {
        /** Called when a mailbox (including combined mailbox) is selected. */
        public void onMailboxSelected(long accountId, long mailboxId);

        /** Called when an account is selected on the combined view. */
        public void onAccountSelected(long accountId);

        /**
         * Called when the list updates to propagate the current mailbox name and the unread count
         * for it.
         *
         * Note the reason why it's separated from onMailboxSelected is because this needs to be
         * reported when the unread count changes without changing the current mailbox.
         */
        public void onCurrentMailboxUpdated(long mailboxId, String mailboxName, int unreadCount);
    }

    private static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onMailboxSelected(long accountId, long mailboxId) { }
        @Override public void onAccountSelected(long accountId) { }
        @Override public void onCurrentMailboxUpdated(long mailboxId, String mailboxName,
                int unreadCount) { }
    }

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        mActivity = getActivity();
        mRefreshManager = RefreshManager.getInstance(mActivity);
        mListAdapter = new MailboxesAdapter(mActivity, MailboxesAdapter.MODE_NORMAL,
                mMailboxesAdapterCallback);
        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState);
        }
        if (sDropTrashColor == null) {
            Resources res = getResources();
            sDropTrashColor = res.getColor(R.color.mailbox_drop_destructive_bg_color);
            sDropActiveDrawable = res.getDrawable(R.drawable.list_activated_holo);
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.mailbox_list_fragment, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);

        mListView = getListView();
        mListView.setOnItemClickListener(this);
        mListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        mListView.setOnDragListener(this);
        registerForContextMenu(mListView);
    }

    public void setCallback(Callback callback) {
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
    }

    private void clearContent() {
        mLastLoadedAccountId = -1;
        mAccountId = -1;
        mSelectedMailboxId = -1;

        mOpenRequested = false;
        mDragInProgress = false;

        stopLoader();
        if (mListAdapter != null) {
            mListAdapter.swapCursor(null);
        }
        setListShownNoAnimation(false);
    }

    /**
     * @param accountId the account we're looking at
     */
    public void openMailboxes(long accountId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment openMailboxes");
        }
        if (accountId == -1) {
            throw new InvalidParameterException();
        }
        if (mAccountId == accountId) {
            return;
        }
        clearContent();
        mOpenRequested = true;
        mAccountId = accountId;
        if (mResumed) {
            startLoading();
        }
    }

    public void setSelectedMailbox(long mailboxId) {
        mSelectedMailboxId = mailboxId;
        if (mResumed) {
            highlightSelectedMailbox(true);
        }
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onStart");
        }
        super.onStart();
    }

    /**
     * Called when the fragment is visible to the user and actively running.
     */
    @Override
    public void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onResume");
        }
        super.onResume();
        mResumed = true;

        // If we're recovering from the stopped state, we don't have to reload.
        // (when mOpenRequested = false)
        if (mAccountId != -1 && mOpenRequested) {
            startLoading();
        }
    }

    @Override
    public void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onPause");
        }
        mResumed = false;
        super.onPause();
        mSavedListState = new Utility.ListStateSaver(getListView());
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onStop");
        }
        super.onStop();
    }

    /**
     * Called when the fragment is no longer in use.
     */
    @Override
    public void onDestroy() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onDestroy");
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        outState.putLong(BUNDLE_KEY_SELECTED_MAILBOX_ID, mSelectedMailboxId);
        outState.putParcelable(BUNDLE_LIST_STATE, new Utility.ListStateSaver(getListView()));
    }

    private void restoreInstanceState(Bundle savedInstanceState) {
        mSelectedMailboxId = savedInstanceState.getLong(BUNDLE_KEY_SELECTED_MAILBOX_ID);
        mSavedListState = savedInstanceState.getParcelable(BUNDLE_LIST_STATE);
    }

    private void startLoading() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment startLoading");
        }
        mOpenRequested = false;
        // Clear the list.  (ListFragment will show the "Loading" animation)
        setListShown(false);

        // If we've already loaded for a different account, discard the previous result and
        // start loading again.
        // We don't want to use restartLoader(), because if the Loader is retained, we *do* want to
        // reuse the previous result.
        // Also, when changing accounts, we don't preserve scroll position.
        boolean accountChanging = false;
        if ((mLastLoadedAccountId != -1) && (mLastLoadedAccountId != mAccountId)) {
            accountChanging = true;
            getLoaderManager().destroyLoader(LOADER_ID_MAILBOX_LIST);

            // Also, when we're changing account, update the mailbox list if stale.
            refreshMailboxListIfStale();
        }
        getLoaderManager().initLoader(LOADER_ID_MAILBOX_LIST, null,
                new MailboxListLoaderCallbacks(accountChanging));
    }

    private void stopLoader() {
        final LoaderManager lm = getLoaderManager();
        lm.destroyLoader(LOADER_ID_MAILBOX_LIST);
    }

    private class MailboxListLoaderCallbacks implements LoaderCallbacks<Cursor> {
        private boolean mAccountChanging;

        public MailboxListLoaderCallbacks(boolean accountChanging) {
            mAccountChanging = accountChanging;
        }

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Logging.LOG_TAG, "MailboxListFragment onCreateLoader");
            }
            return MailboxesAdapter.createLoader(getActivity(), mAccountId,
                    MailboxesAdapter.MODE_NORMAL);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Logging.LOG_TAG, "MailboxListFragment onLoadFinished");
            }
            mLastLoadedAccountId = mAccountId;

            // Save list view state (primarily scroll position)
            final ListView lv = getListView();
            final Utility.ListStateSaver lss;
            if (mAccountChanging) {
                lss = null; // Don't preserve list state
            } else if (mSavedListState != null) {
                lss = mSavedListState;
                mSavedListState = null;
            } else {
                lss = new Utility.ListStateSaver(lv);
            }

            if (cursor.getCount() == 0) {
                // If there's no row, don't set it to the ListView.
                // Instead use setListShown(false) to make ListFragment show progress icon.
                mListAdapter.swapCursor(null);
                setListShown(false);
            } else {
                // Set the adapter.
                mListAdapter.swapCursor(cursor);
                setListAdapter(mListAdapter);
                setListShown(true);

                // We want to make selection visible only when account is changing..
                // i.e. Refresh caused by content changed events shouldn't scroll the list.
                highlightSelectedMailbox(mAccountChanging);
            }

            // Restore the state
            if (lss != null) {
                lss.restore(lv);
            }

            // Clear this for next reload triggered by content changed events.
            mAccountChanging = false;
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            mListAdapter.swapCursor(null);
        }
    }

    public void onItemClick(AdapterView<?> parent, View view, int position,
            long idDontUseIt /* see MailboxesAdapter */ ) {
        final long id = mListAdapter.getId(position);
        if (mListAdapter.isAccountRow(position)) {
            mCallback.onAccountSelected(id);
        } else {
            mCallback.onMailboxSelected(mAccountId, id);
        }
    }

    public void onRefresh() {
        if (mAccountId != -1) {
            mRefreshManager.refreshMailboxList(mAccountId);
        }
    }

    private void refreshMailboxListIfStale() {
        if (mRefreshManager.isMailboxListStale(mAccountId)) {
            mRefreshManager.refreshMailboxList(mAccountId);
        }
    }

    /**
     * Highlight the selected mailbox.
     */
    private void highlightSelectedMailbox(boolean ensureSelectionVisible) {
        String mailboxName = "";
        int unreadCount = 0;
        if (mSelectedMailboxId == -1) {
            // No mailbox selected
            mListView.clearChoices();
        } else {
            final int count = mListView.getCount();
            for (int i = 0; i < count; i++) {
                if (mListAdapter.getId(i) != mSelectedMailboxId) {
                    continue;
                }
                mListView.setItemChecked(i, true);
                if (ensureSelectionVisible) {
                    Utility.listViewSmoothScrollToPosition(getActivity(), mListView, i);
                }
                mailboxName = mListAdapter.getDisplayName(i);
                unreadCount = mListAdapter.getUnreadCount(i);
                break;
            }
        }
        mCallback.onCurrentMailboxUpdated(mSelectedMailboxId, mailboxName, unreadCount);
    }

    // Drag & Drop handling

    /**
     * Update all of the list's child views with the proper target background (for now, orange if
     * a valid target, except red if the trash; standard background otherwise)
     */
    private void updateChildViews() {
        int itemCount = mListView.getChildCount();
        // Lazily initialize the height of our list items
        if (itemCount > 0 && mDragItemHeight < 0) {
            mDragItemHeight = mListView.getChildAt(0).getHeight();
        }
        for (int i = 0; i < itemCount; i++) {
            MailboxListItem item = (MailboxListItem)mListView.getChildAt(i);
            item.setDropTargetBackground(mDragInProgress, mDragItemMailboxId);
        }
    }

    /**
     * Called when our ListView gets a DRAG_EXITED event
     */
    private void onDragExited() {
        // Reset the background of the current target
        if (mDropTargetAdapterPosition != NO_DROP_TARGET) {
            mDropTargetView.setDropTargetBackground(mDragInProgress, mDragItemMailboxId);
            mDropTargetAdapterPosition = NO_DROP_TARGET;
        }
        stopScrolling();
    }

    /**
     * Called while dragging;  highlight possible drop targets, and autoscroll the list.
     */
    private void onDragLocation(DragEvent event) {
        // The drag is somewhere in the ListView
        if (mDragItemHeight <= 0) {
            // This shouldn't be possible, but avoid NPE
            return;
        }
        // Find out which item we're in and highlight as appropriate
        int rawTouchY = (int)event.getY();
        int offset = 0;
        if (mListView.getCount() > 0) {
            offset = mListView.getChildAt(0).getTop();
        }
        int targetScreenPosition = (rawTouchY - offset) / mDragItemHeight;
        int firstVisibleItem = mListView.getFirstVisiblePosition();
        int targetAdapterPosition = firstVisibleItem + targetScreenPosition;
        if (targetAdapterPosition != mDropTargetAdapterPosition) {
            if (DEBUG_DRAG_DROP) {
                Log.d(TAG, "========== DROP TARGET " + mDropTargetAdapterPosition + " -> " +
                        targetAdapterPosition);
            }
            // Unhighlight the current target, if we've got one
            if (mDropTargetAdapterPosition != NO_DROP_TARGET) {
                mDropTargetView.setDropTargetBackground(true, mDragItemMailboxId);
            }
            // Get the new target mailbox view
            MailboxListItem newTarget =
                (MailboxListItem)mListView.getChildAt(targetScreenPosition);
            // This can be null due to a bug in the framework (checking on that)
            // In any event, we're no longer dragging in the list view if newTarget is null
            if (newTarget == null) {
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "========== WTF??? DRAG EXITED");
                }
                onDragExited();
                return;
            } else if (newTarget.mMailboxType == Mailbox.TYPE_TRASH) {
                if (DEBUG_DRAG_DROP) {
                    Log.d("onDragLocation", "=== Mailbox " + newTarget.mMailboxId + " TRASH");
                }
                newTarget.setBackgroundColor(sDropTrashColor);
            } else if (newTarget.isDropTarget(mDragItemMailboxId)) {
                if (DEBUG_DRAG_DROP) {
                    Log.d("onDragLocation", "=== Mailbox " + newTarget.mMailboxId + " TARGET");
                }
                newTarget.setBackgroundDrawable(sDropActiveDrawable);
            } else {
                if (DEBUG_DRAG_DROP) {
                    Log.d("onDragLocation", "=== Mailbox " + newTarget.mMailboxId + " (CALL)");
                }
                targetAdapterPosition = NO_DROP_TARGET;
                newTarget.setDropTargetBackground(true, mDragItemMailboxId);
            }
            // Save away our current position and view
            mDropTargetAdapterPosition = targetAdapterPosition;
            mDropTargetView = newTarget;
        }

        // This is a quick-and-dirty implementation of drag-under-scroll; something like this
        // should eventually find its way into the framework
        int scrollDiff = rawTouchY - (mListView.getHeight() - SCROLL_ZONE_SIZE);
        boolean scrollDown = (scrollDiff > 0);
        boolean scrollUp = (SCROLL_ZONE_SIZE > rawTouchY);
        if (!mTargetScrolling && scrollDown) {
            int itemsToScroll = mListView.getCount() - targetAdapterPosition;
            int pixelsToScroll = (itemsToScroll + 1) * mDragItemHeight;
            mListView.smoothScrollBy(pixelsToScroll, pixelsToScroll * SCROLL_SPEED);
            if (DEBUG_DRAG_DROP) {
                Log.d(TAG, "========== START TARGET SCROLLING DOWN");
            }
            mTargetScrolling = true;
        } else if (!mTargetScrolling && scrollUp) {
            int pixelsToScroll = (firstVisibleItem + 1) * mDragItemHeight;
            mListView.smoothScrollBy(-pixelsToScroll, pixelsToScroll * SCROLL_SPEED);
            if (DEBUG_DRAG_DROP) {
                Log.d(TAG, "========== START TARGET SCROLLING UP");
            }
            mTargetScrolling = true;
        } else if (!scrollUp && !scrollDown) {
            stopScrolling();
        }
    }

    /**
     * Indicate that scrolling has stopped
     */
    private void stopScrolling() {
        if (mTargetScrolling) {
            mTargetScrolling = false;
            if (DEBUG_DRAG_DROP) {
                Log.d(TAG, "========== STOP TARGET SCROLLING");
            }
            // Stop the scrolling
            mListView.smoothScrollBy(0, 0);
        }
    }

    private void onDragEnded() {
        if (mDragInProgress) {
            mDragInProgress = false;
            // Reenable updates to the view and redraw (in case it changed)
            mListAdapter.enableUpdates(true);
            mListAdapter.notifyDataSetChanged();
            // Stop highlighting targets
            updateChildViews();
            // Stop any scrolling that was going on
            stopScrolling();
        }
    }

    private boolean onDragStarted(DragEvent event) {
        // We handle dropping of items with our email mime type
        // If the mime type has a mailbox id appended, that is the mailbox of the item
        // being draged
        ClipDescription description = event.getClipDescription();
        int mimeTypeCount = description.getMimeTypeCount();
        for (int i = 0; i < mimeTypeCount; i++) {
            String mimeType = description.getMimeType(i);
            if (mimeType.startsWith(EmailProvider.EMAIL_MESSAGE_MIME_TYPE)) {
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "========== DRAG STARTED");
                }
                mDragItemMailboxId = -1;
                // See if we find a mailbox id here
                int dash = mimeType.lastIndexOf('-');
                if (dash > 0) {
                    try {
                        mDragItemMailboxId = Long.parseLong(mimeType.substring(dash + 1));
                    } catch (NumberFormatException e) {
                        // Ignore; we just won't know the mailbox
                    }
                }
                mDragInProgress = true;
                // Stop the list from updating
                mListAdapter.enableUpdates(false);
                // Update the backgrounds of our child views to highlight drop targets
                updateChildViews();
                return true;
            }
        }
        return false;
    }

    private boolean onDrop(DragEvent event) {
        stopScrolling();
        // If we're not on a target, we're done
        if (mDropTargetAdapterPosition == NO_DROP_TARGET) return false;
        final Controller controller = Controller.getInstance(mActivity);
        ClipData clipData = event.getClipData();
        int count = clipData.getItemCount();
        if (DEBUG_DRAG_DROP) {
            Log.d(TAG, "Received a drop of " + count + " items.");
        }
        // Extract the messageId's to move from the ClipData (set up in MessageListItem)
        final long[] messageIds = new long[count];
        for (int i = 0; i < count; i++) {
            Uri uri = clipData.getItemAt(i).getUri();
            String msgNum = uri.getPathSegments().get(1);
            long id = Long.parseLong(msgNum);
            messageIds[i] = id;
        }
        // Call either deleteMessage or moveMessage, depending on the target
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                if (mDropTargetView.mMailboxType == Mailbox.TYPE_TRASH) {
                    for (long messageId: messageIds) {
                        // TODO Get this off UI thread (put in clip)
                        Message msg = Message.restoreMessageWithId(mActivity, messageId);
                        if (msg != null) {
                            controller.deleteMessage(messageId, msg.mAccountKey);
                        }
                    }
                } else {
                    controller.moveMessage(messageIds, mDropTargetView.mMailboxId);
                }
            }});
        return true;
    }

    @Override
    public boolean onDrag(View view, DragEvent event) {
        boolean result = false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                result = onDragStarted(event);
                break;
            case DragEvent.ACTION_DRAG_ENTERED:
                // The drag has entered the ListView window
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "========== DRAG ENTERED (target = " + mDropTargetAdapterPosition +
                    ")");
                }
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                // The drag has left the building
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "========== DRAG EXITED (target = " + mDropTargetAdapterPosition +
                            ")");
                }
                onDragExited();
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                // The drag is over
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "========== DRAG ENDED");
                }
                onDragEnded();
                break;
            case DragEvent.ACTION_DRAG_LOCATION:
                // We're moving around within our window; handle scroll, if necessary
                onDragLocation(event);
                break;
            case DragEvent.ACTION_DROP:
                // The drag item was dropped
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "========== DROP");
                }
                result = onDrop(event);
                break;
            default:
                break;
        }
        return result;
    }
}
