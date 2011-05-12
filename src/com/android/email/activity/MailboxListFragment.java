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
import com.android.email.service.MailService;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnDragListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

import java.security.InvalidParameterException;
import java.util.Timer;
import java.util.TimerTask;

/**
 * This fragment presents a list of mailboxes for a given account.
 */
public class MailboxListFragment extends ListFragment implements OnItemClickListener,
        OnDragListener {
    private static final String TAG = "MailboxListFragment";
    private static final String BUNDLE_KEY_SELECTED_MAILBOX_ID
            = "MailboxListFragment.state.selected_mailbox_id";
    private static final String BUNDLE_LIST_STATE = "MailboxListFragment.state.listState";
    private static final boolean DEBUG_DRAG_DROP = false; // MUST NOT SUBMIT SET TO TRUE
    /** While in drag-n-drop, amount of time before it auto expands; in ms */
    private static final long AUTO_EXPAND_DELAY = 750L;

    /** No drop target is available where the user is currently hovering over */
    private static final int NO_DROP_TARGET = -1;
    // Total height of the top and bottom scroll zones, in pixels
    private static final int SCROLL_ZONE_SIZE = 64;
    // The amount of time to scroll by one pixel, in ms
    private static final int SCROLL_SPEED = 4;

    /** Arbitrary number for use with the loader manager */
    private static final int MAILBOX_LOADER_ID = 1;

    /** Argument name(s) */
    private static final String ARG_ACCOUNT_ID = "accountId";
    private static final String ARG_PARENT_MAILBOX_ID = "parentMailboxId";

    /** Timer to auto-expand folder lists during drag-n-drop */
    private static final Timer sDragTimer = new Timer();
    /** Rectangle used for hit testing children */
    private static final Rect sTouchFrame = new Rect();

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

    private long mAccountId = -1;
    private long mParentMailboxId = Mailbox.PARENT_KEY_NONE;
    private long mSelectedMailboxId = -1;

    // True if a drag is currently in progress
    private boolean mDragInProgress;
    /** Mailbox ID of the item being dragged. Used to determine valid drop targets. */
    private long mDragItemMailboxId = -1;
    /** A unique identifier for the drop target. May be {@link #NO_DROP_TARGET}. */
    private int mDropTargetId = NO_DROP_TARGET;
    // The mailbox list item view that the user's finger is hovering over
    private MailboxListItem mDropTargetView;
    // Lazily instantiated height of a mailbox list item (-1 is a sentinel for 'not initialized')
    private int mDragItemHeight = -1;
    /** Task that actually does the work to auto-expand folder lists during drag-n-drop */
    private TimerTask mDragTimerTask;
    // True if we are currently scrolling under the drag item
    private boolean mTargetScrolling;

    private Parcelable mSavedListState;

    private final MailboxesAdapter.Callback mMailboxesAdapterCallback =
            new MailboxesAdapter.Callback() {
        @Override
        public void onBind(MailboxListItem listItem) {
            listItem.setDropTargetBackground(mDragInProgress, mDragItemMailboxId);
        }
    };

    /**
     * Callback interface that owning activities must implement
     */
    public interface Callback {
        /**
         * STOPSHIP split this into separate callbacks.
         * - Drill in to a mailbox and open a mailbox (= show message list) are different operations
         *   on the phone
         * - Regular navigation and navigation for D&D are different; the latter case we probably
         *   want to go back to the original mailbox afterwards.  (Need another callback for this)
         *
         * Called when any mailbox (even a combined mailbox) is selected.
         * @param accountId
         *          The ID of the account for which a mailbox was selected
         * @param mailboxId
         *          The ID of the selected mailbox. This may be real mailbox ID [e.g. a number > 0],
         *          or a combined mailbox ID [e.g. {@link Mailbox#QUERY_ALL_INBOXES}].
         * @param navigate navigate to the mailbox.
         * @param dragDrop true if D&D is in progress.
         */
        public void onMailboxSelected(long accountId, long mailboxId, boolean navigate,
                boolean dragDrop);

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
        @Override public void onMailboxSelected(long accountId, long mailboxId, boolean navigate,
                boolean dragDrop) {
        }
        @Override public void onAccountSelected(long accountId) { }
        @Override public void onCurrentMailboxUpdated(long mailboxId, String mailboxName,
                int unreadCount) { }
    }

    /**
     * Returns the index of the view located at the specified coordinates in the given list.
     * If the coordinates are outside of the list, {@code NO_DROP_TARGET} is returned.
     */
    private static int pointToIndex(ListView list, int x, int y) {
        final int count = list.getChildCount();
        for (int i = count - 1; i >= 0; i--) {
            final View child = list.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                child.getHitRect(sTouchFrame);
                if (sTouchFrame.contains(x, y)) {
                    return i;
                }
            }
        }
        return NO_DROP_TARGET;
    }

    /**
     * Create a new instance with initialization parameters.
     *
     * This fragment should be created only with this method.  (Arguments should always be set.)
     */
    public static MailboxListFragment newInstance(long accountId, long parentMailboxId) {
        final MailboxListFragment instance = new MailboxListFragment();
        final Bundle args = new Bundle();
        args.putLong(ARG_ACCOUNT_ID, accountId);
        args.putLong(ARG_PARENT_MAILBOX_ID, parentMailboxId);
        instance.setArguments(args);
        return instance;
    }

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @SuppressWarnings("unused")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        mActivity = getActivity();
        mRefreshManager = RefreshManager.getInstance(mActivity);
        mListAdapter = new MailboxFragmentAdapter(mActivity, mMailboxesAdapterCallback);
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

    @SuppressWarnings("unused")
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

        final Bundle args = getArguments();
        // STOPSHIP remove the check.  Right now it's needed for the obsolete phone activities.
        if (args != null) {
            openMailboxes(args.getLong(ARG_ACCOUNT_ID), args.getLong(ARG_PARENT_MAILBOX_ID));
        }
        startLoading();
    }

    public void setCallback(Callback callback) {
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
    }

    /**
     * Opens the top-level mailboxes for the given account ID. If the account is currently
     * loaded, the list of top-level mailbox will not be reloaded unless <code>forceReload</code>
     * is <code>true</code>.
     * @param accountId The ID of the account we want to view
     * @param parentMailboxId The ID of the parent mailbox.  Use {@link Mailbox#PARENT_KEY_NONE}
     *     to open the root.
     * Otherwise, only load the list of top-level mailboxes if the account changes.
     */
    // STOPSHIP Make it private once phone activities are gone
    @SuppressWarnings("unused")
    void openMailboxes(long accountId, long parentMailboxId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment openMailboxes");
        }
        if (accountId == -1) {
            throw new InvalidParameterException();
        }

        mAccountId = accountId;
        mParentMailboxId = parentMailboxId;
    }

    /**
     * Returns whether or not the specified mailbox can be navigated to.
     */
    private boolean isNavigable(long mailboxId) {
        final int count = mListView.getCount();
        for (int i = 0; i < count; i++) {
            final MailboxListItem item = (MailboxListItem) mListView.getChildAt(i);
            if (item.mMailboxId != mailboxId) {
                continue;
            }
            return item.isNavigable();
        }
        return false;
    }

    /**
     * Sets the selected mailbox to the given ID. Sub-folders will not be loaded.
     * @param mailboxId The ID of the mailbox to select.
     */
    public void setSelectedMailbox(long mailboxId) {
        mSelectedMailboxId = mailboxId;
        if (mResumed) {
            highlightSelectedMailbox(true);
        }
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @SuppressWarnings("unused")
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
    @SuppressWarnings("unused")
    @Override
    public void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onResume");
        }
        super.onResume();
        mResumed = true;

        // Fetch the latest mailbox list from the server here if stale so that the user always
        // sees the (reasonably) up-to-date mailbox list, without pressing "refresh".
        if (mRefreshManager.isMailboxListStale(mAccountId)) {
            mRefreshManager.refreshMailboxList(mAccountId);
        }
    }

    @SuppressWarnings("unused")
    @Override
    public void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onPause");
        }
        mResumed = false;
        super.onPause();
        mSavedListState = getListView().onSaveInstanceState();
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @SuppressWarnings("unused")
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
    @SuppressWarnings("unused")
    @Override
    public void onDestroy() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onDestroy");
        }
        super.onDestroy();
    }

    @SuppressWarnings("unused")
    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        outState.putLong(BUNDLE_KEY_SELECTED_MAILBOX_ID, mSelectedMailboxId);
        outState.putParcelable(BUNDLE_LIST_STATE, getListView().onSaveInstanceState());
    }

    @SuppressWarnings("unused")
    private void restoreInstanceState(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment restoreInstanceState");
        }
        mSelectedMailboxId = savedInstanceState.getLong(BUNDLE_KEY_SELECTED_MAILBOX_ID);
        mSavedListState = savedInstanceState.getParcelable(BUNDLE_LIST_STATE);
    }

    @SuppressWarnings("unused")
    private void startLoading() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxListFragment startLoading");
        }
        // Clear the list.  (ListFragment will show the "Loading" animation)
        setListShown(false);

        final LoaderManager lm = getLoaderManager();
        lm.initLoader(MAILBOX_LOADER_ID, null, new MailboxListLoaderCallbacks());
    }

    // TODO This class probably should be made static. There are many calls into the enclosing
    // class and we need to be cautious about what we call while in these callbacks
    private class MailboxListLoaderCallbacks implements LoaderCallbacks<Cursor> {
        private boolean mIsFirstLoad;

        @SuppressWarnings("unused")
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Logging.LOG_TAG, "MailboxListFragment onCreateLoader");
            }
            mIsFirstLoad = true;
            return MailboxFragmentAdapter.createLoader(getActivity(), mAccountId, mParentMailboxId);
        }

        @SuppressWarnings("unused")
        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Logging.LOG_TAG, "MailboxListFragment onLoadFinished");
            }
            // Save list view state (primarily scroll position)
            final ListView lv = getListView();
            final Parcelable listState;
            if (mSavedListState != null) {
                listState = mSavedListState;
                mSavedListState = null;
            } else {
                listState = lv.onSaveInstanceState();
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

                // We want to make visible the selection only for the first load.
                // Re-load caused by content changed events shouldn't scroll the list.
                highlightSelectedMailbox(mIsFirstLoad);
            }

            // List has been reloaded; clear any drop target information
            mDropTargetId = NO_DROP_TARGET;
            mDropTargetView = null;

            // Restore the state
            if (listState != null) {
                lv.onRestoreInstanceState(listState);
            }

            mIsFirstLoad = false;
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            mListAdapter.swapCursor(null);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * @param doNotUse <em>IMPORTANT</em>: Do not use this parameter. The ID in the list widget
     * must be a positive value. However, we rely on negative IDs for special mailboxes. Instead,
     * we use the ID returned by {@link MailboxesAdapter#getId(int)}.
     */
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long doNotUse) {
        final long id = mListAdapter.getId(position);
        if (mListAdapter.isAccountRow(position)) {
            mCallback.onAccountSelected(id);
        } else {
            // STOPSHIP On phone, we need a way to open a message list without navigating to the
            // mailbox.
            mCallback.onMailboxSelected(mAccountId, id, isNavigable(id), false);
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
            // TODO Don't mix list view & list adapter indices. This is a recipe for disaster.
            final int count = mListView.getCount();
            for (int i = 0; i < count; i++) {
                if (mListAdapter.getId(i) != mSelectedMailboxId) {
                    continue;
                }
                mListView.setItemChecked(i, true);
                if (ensureSelectionVisible) {
                    Utility.listViewSmoothScrollToPosition(getActivity(), mListView, i);
                }
                mailboxName = mListAdapter.getDisplayName(mActivity, i);
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
     * Starts the timer responsible for auto-selecting mailbox items while in drag-n-drop.
     * If there is already an active task, we first try to cancel it. There are only two
     * reasons why a new timer may not be started. First, if we are unable to cancel a
     * previous timer, we must assume that a new mailbox has already been loaded. Second,
     * if the target item is not permitted to be auto selected.
     * @param newTarget The drag target that needs to be auto selected
     */
    private void startDragTimer(final MailboxListItem newTarget) {
        boolean canceledInTime = mDragTimerTask == null || stopDragTimer();
        if (canceledInTime
                && newTarget != null
                && newTarget.isNavigable()
                && newTarget.isDropTarget(mDragItemMailboxId)) {
            mDragTimerTask = new TimerTask() {
                @Override
                public void run() {
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            stopDragTimer();
                            // STOPSHIP Revisit this -- probably we need a different callback
                            // so that when D&D finishes we can go back to the original mailbox.
                            mCallback.onMailboxSelected(mAccountId, newTarget.mMailboxId, true,
                                    true);
                        }
                    });
                }
            };
            sDragTimer.schedule(mDragTimerTask, AUTO_EXPAND_DELAY);
        }
    }

    /**
     * Stops the timer responsible for auto-selecting mailbox items while in drag-n-drop.
     * If the timer is not active, nothing will happen.
     * @return Whether or not the timer was interrupted. {@link TimerTask#cancel()}.
     */
    private boolean stopDragTimer() {
        boolean timerInterrupted = false;
        synchronized (sDragTimer) {
            if (mDragTimerTask != null) {
                timerInterrupted = mDragTimerTask.cancel();
                mDragTimerTask = null;
            }
        }
        return timerInterrupted;
    }

    /**
     * Called when the user has dragged outside of the mailbox list area.
     */
    private void onDragExited() {
        // Reset the background of the current target
        if (mDropTargetView != null) {
            mDropTargetView.setDropTargetBackground(mDragInProgress, mDragItemMailboxId);
            mDropTargetView = null;
        }
        mDropTargetId = NO_DROP_TARGET;
        stopDragTimer();
        stopScrolling();
    }

    /**
     * Called while dragging;  highlight possible drop targets, and auto scroll the list.
     */
    private void onDragLocation(DragEvent event) {
        // TODO The list may be changing while in drag-n-drop; temporarily suspend drag-n-drop
        // if the list is being updated [i.e. navigated to another mailbox]
        if (mDragItemHeight <= 0) {
            // This shouldn't be possible, but avoid NPE
            Log.w(TAG, "drag item height is not set");
            return;
        }
        // Find out which item we're in and highlight as appropriate
        final int rawTouchX = (int) event.getX();
        final int rawTouchY = (int) event.getY();
        final int viewIndex = pointToIndex(mListView, rawTouchX, rawTouchY);
        int targetId = viewIndex;
        if (targetId != mDropTargetId) {
            if (DEBUG_DRAG_DROP) {
                Log.d(TAG, "=== Target changed; oldId: " + mDropTargetId + ", newId: " + targetId);
            }
            // Remove highlight the current target; if there was one
            if (mDropTargetView != null) {
                mDropTargetView.setDropTargetBackground(true, mDragItemMailboxId);
                mDropTargetView = null;
            }
            // Get the new target mailbox view
            final MailboxListItem newTarget = (MailboxListItem) mListView.getChildAt(viewIndex);
            if (newTarget == null) {
                // In any event, we're no longer dragging in the list view if newTarget is null
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "=== Drag off the list");
                }
                final int childCount = mListView.getChildCount();
                if (viewIndex >= childCount) {
                    // Touching beyond the end of the list; may happen for small lists
                    onDragExited();
                    return;
                } else {
                    // We should never get here
                    Log.w(TAG, "null view; idx: " + viewIndex + ", cnt: " + childCount);
                }
            } else if (newTarget.mMailboxType == Mailbox.TYPE_TRASH) {
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "=== Trash mailbox; id: " + newTarget.mMailboxId);
                }
                newTarget.setBackgroundColor(sDropTrashColor);
            } else if (newTarget.isDropTarget(mDragItemMailboxId)) {
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "=== Target mailbox; id: " + newTarget.mMailboxId);
                }
                newTarget.setBackgroundDrawable(sDropActiveDrawable);
            } else {
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "=== Non-droppable mailbox; id: " + newTarget.mMailboxId);
                }
                newTarget.setDropTargetBackground(true, mDragItemMailboxId);
                targetId = NO_DROP_TARGET;
            }
            // Save away our current position and view
            mDropTargetId = targetId;
            mDropTargetView = newTarget;
            startDragTimer(newTarget);
        }

        // This is a quick-and-dirty implementation of drag-under-scroll; something like this
        // should eventually find its way into the framework
        int scrollDiff = rawTouchY - (mListView.getHeight() - SCROLL_ZONE_SIZE);
        boolean scrollDown = (scrollDiff > 0);
        boolean scrollUp = (SCROLL_ZONE_SIZE > rawTouchY);
        if (!mTargetScrolling && scrollDown) {
            int itemsToScroll = mListView.getCount() - mListView.getLastVisiblePosition();
            int pixelsToScroll = (itemsToScroll + 1) * mDragItemHeight;
            mListView.smoothScrollBy(pixelsToScroll, pixelsToScroll * SCROLL_SPEED);
            if (DEBUG_DRAG_DROP) {
                Log.d(TAG, "=== Start scrolling list down");
            }
            mTargetScrolling = true;
        } else if (!mTargetScrolling && scrollUp) {
            int pixelsToScroll = (mListView.getFirstVisiblePosition() + 1) * mDragItemHeight;
            mListView.smoothScrollBy(-pixelsToScroll, pixelsToScroll * SCROLL_SPEED);
            if (DEBUG_DRAG_DROP) {
                Log.d(TAG, "=== Start scrolling list up");
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
                Log.d(TAG, "=== Stop scrolling list");
            }
            // Stop the scrolling
            mListView.smoothScrollBy(0, 0);
        }
    }

    private void onDragEnded() {
        stopDragTimer();
        if (mDragInProgress) {
            mDragInProgress = false;
            // Reenable updates to the view and redraw (in case it changed)
            MailboxesAdapter.enableUpdates(true);
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
                    Log.d(TAG, "=== Drag started");
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
                MailboxesAdapter.enableUpdates(false);
                // Update the backgrounds of our child views to highlight drop targets
                updateChildViews();
                return true;
            }
        }
        return false;
    }

    /**
     * Perform a "drop" action. If the user is not on top of a valid drop target, no action
     * is performed.
     * @return {@code true} if the drop action was performed. Otherwise {@code false}.
     */
    private boolean onDrop(DragEvent event) {
        stopDragTimer();
        stopScrolling();
        // If we're not on a target, we're done
        if (mDropTargetId == NO_DROP_TARGET) {
            return false;
        }
        final Controller controller = Controller.getInstance(mActivity);
        ClipData clipData = event.getClipData();
        int count = clipData.getItemCount();
        if (DEBUG_DRAG_DROP) {
            Log.d(TAG, "=== Dropping " + count + " items.");
        }
        // Extract the messageId's to move from the ClipData (set up in MessageListItem)
        final long[] messageIds = new long[count];
        for (int i = 0; i < count; i++) {
            Uri uri = clipData.getItemAt(i).getUri();
            String msgNum = uri.getPathSegments().get(1);
            long id = Long.parseLong(msgNum);
            messageIds[i] = id;
        }
        final MailboxListItem targetItem = mDropTargetView;
        // Call either deleteMessage or moveMessage, depending on the target
        EmailAsyncTask.runAsyncSerial(new Runnable() {
            @Override
            public void run() {
                if (targetItem.mMailboxType == Mailbox.TYPE_TRASH) {
                    for (long messageId: messageIds) {
                        // TODO Get this off UI thread (put in clip)
                        Message msg = Message.restoreMessageWithId(mActivity, messageId);
                        if (msg != null) {
                            controller.deleteMessage(messageId, msg.mAccountKey);
                        }
                    }
                } else {
                    controller.moveMessages(messageIds, targetItem.mMailboxId);
                }
            }
        });
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
                    Log.d(TAG, "=== Drag entered; targetId: " + mDropTargetId);
                }
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                // The drag has left the building
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "=== Drag exited; targetId: " + mDropTargetId);
                }
                onDragExited();
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                // The drag is over
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "=== Drag ended");
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
                    Log.d(TAG, "=== Drop");
                }
                result = onDrop(event);
                break;
            default:
                break;
        }
        return result;
    }
}
