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

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Rect;
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

import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.email.provider.EmailProvider;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;

import java.util.Timer;

/**
 * This fragment presents a list of mailboxes for a given account or the combined mailboxes.
 *
 * This fragment has several parameters that determine the current view.
 *
 * <pre>
 * Parameters:
 * - Account ID.
 *   - Set via {@link #newInstance}.
 *   - Can be obtained with {@link #getAccountId()}.
 *   - Will not change throughout fragment lifecycle.
 *   - Either an actual account ID, or {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
 *
 * - "Highlight enabled?" flag
 *   - Set via {@link #newInstance}.
 *   - Can be obtained with {@link #getEnableHighlight()}.
 *   - Will not change throughout fragment lifecycle.
 *   - If {@code true}, we highlight the "selected" mailbox (used only on 2-pane).
 *   - Note even if it's {@code true}, there may be no highlighted mailbox.
 *     (This usually happens on 2-pane before the UI controller finds the Inbox to highlight.)
 *
 * - "Parent" mailbox ID
 *   - Stored in {@link #mParentMailboxId}
 *   - Changes as the user navigates through nested mailboxes.
 *   - Initialized using the {@code mailboxId} parameter for {@link #newInstance}
 *     in {@link #setInitialParentAndHighlight()}.
 *
 * - "Highlighted" mailbox
 *   - Only used when highlighting is enabled.  (Otherwise always {@link Mailbox#NO_MAILBOX}.)
 *     i.e. used only on two-pane.
 *   - Stored in {@link #mHighlightedMailboxId}
 *   - Initialized using the {@code mailboxId} parameter for {@link #newInstance}
 *     in {@link #setInitialParentAndHighlight()}.
 *
 *   - Can be changed any time, using {@link #setHighlightedMailbox(long)}.
 *
 *   - If set, it's considered "selected", and we highlight the list item.
 *
 *   - (It should always be the ID of the list item selected in the list view, but we store it in
 *     a member for efficiency.)
 *
 *   - Sometimes, we need to set the highlighted mailbox while we're still loading data.
 *     In this case, we can't update {@link #mHighlightedMailboxId} right away, but need to do so
 *     in when the next data set arrives, in
 *     {@link MailboxListFragment.MailboxListLoaderCallbacks#onLoadFinished}.  For this, we use
 *     we store the mailbox ID in {@link #mNextHighlightedMailboxId} and update
 *     {@link #mHighlightedMailboxId} in onLoadFinished.
 *
 *
 * The "selected" is defined using the "parent" and "highlighted" mailboxes.
 * - "Selected" mailbox  (also sometimes called "current".)
 *   - This is what the user thinks it's now selected.
 *
 *   - Can be obtained with {@link #getSelectedMailboxId()}
 *   - If the "highlighted" mailbox exists, it's the "selected."  Otherwise, the "parent"
 *     is considered "selected."
 *   - This is what is passed to {@link Callback#onMailboxSelected}.
 * </pre>
 *
 *
 * This fragment shows the content in one of the three following views, depending on the
 * parameters above.
 *
 * <pre>
 * 1. Combined view
 *   - Used if the account ID == {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
 *   - Parent mailbox is always {@link Mailbox#NO_MAILBOX}.
 *   - List contains:
 *     - combined mailboxes
 *     - all accounts
 *
 * 2. Root view for an account
 *   - Used if the account ID != {@link Account#ACCOUNT_ID_COMBINED_VIEW} and
 *     Parent mailbox == {@link Mailbox#NO_MAILBOX}
 *   - List contains
 *     - all the top level mailboxes for the selected account.
 *
 * 3. Root view for a mailbox.  (nested view)
 *   - Used if the account ID != {@link Account#ACCOUNT_ID_COMBINED_VIEW} and
 *     Parent mailbox != {@link Mailbox#NO_MAILBOX}
 *   - List contains:
 *     - parent mailbox (determined by "parent" mailbox ID)
 *     - all child mailboxes of the parent mailbox.
 * </pre>
 *
 *
 * Note that when a fragment is put in the back stack, it'll lose the content view but the fragment
 * itself is not destroyed.  If you call {@link #getListView()} in this state it'll throw
 * an {@link IllegalStateException}.  So,
 * - If code is supposed to be executed only when the fragment has the content view, use
 *   {@link #getListView()} directly to make sure it doesn't accidentally get executed when there's
 *   no views.
 * - Otherwise, make sure to check if the fragment has views with {@link #isViewCreated()}
 *   before touching any views.
 */
public class MailboxListFragment extends ListFragment implements OnItemClickListener,
        OnDragListener {
    private static final String TAG = "MailboxListFragment";

    private static final String BUNDLE_KEY_PARENT_MAILBOX_ID
            = "MailboxListFragment.state.parent_mailbox_id";
    private static final String BUNDLE_KEY_HIGHLIGHTED_MAILBOX_ID
            = "MailboxListFragment.state.selected_mailbox_id";
    private static final String BUNDLE_LIST_STATE = "MailboxListFragment.state.listState";
    private static final boolean DEBUG_DRAG_DROP = false; // MUST NOT SUBMIT SET TO TRUE

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
    private static final String ARG_ENABLE_HIGHLIGHT = "enablehighlight";
    private static final String ARG_INITIAL_CURRENT_MAILBOX_ID = "initialParentMailboxId";

    private final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    /** Rectangle used for hit testing children */
    private static final Rect sTouchFrame = new Rect();

    private RefreshManager mRefreshManager;

    // UI Support
    private Activity mActivity;
    private MailboxFragmentAdapter mListAdapter;
    private Callback mCallback = EmptyCallback.INSTANCE;

    // See the class javadoc
    private long mParentMailboxId;
    private long mHighlightedMailboxId;

    /**
     * Becomes {@code true} once we determine which mailbox to use as the parent.
     */
    private boolean mParentDetermined;

    /**
     * ID of the mailbox that should be highlighted when the next cursor is loaded.
     */
    private long mNextHighlightedMailboxId = Mailbox.NO_MAILBOX;

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
    /** {@code true} if we are currently scrolling under the drag item */
    private boolean mTargetScrolling;

    private Parcelable mSavedListState;

    private final MailboxFragmentAdapter.Callback mMailboxesAdapterCallback =
            new MailboxFragmentAdapter.Callback() {
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
         * Called when any mailbox (even a combined mailbox) is selected.
         *
         * @param accountId
         *          The ID of the owner account of the selected mailbox.
         *          Or {@link Account#ACCOUNT_ID_COMBINED_VIEW} if it's a combined mailbox.
         * @param mailboxId
         *          The ID of the selected mailbox. This may be real mailbox ID [e.g. a number > 0],
         *          or a combined mailbox ID [e.g. {@link Mailbox#QUERY_ALL_INBOXES}].
         * @param nestedNavigation {@code true} if the event is caused by nested mailbox navigation,
         *          that is, going up or drilling-in to a child mailbox.
         */
        public void onMailboxSelected(long accountId, long mailboxId, boolean nestedNavigation);

        /** Called when an account is selected on the combined view. */
        public void onAccountSelected(long accountId);

        /**
         * Called when the parent mailbox is changing.
         */
        public void onParentMailboxChanged();
    }

    private static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onMailboxSelected(long accountId, long mailboxId,
                boolean nestedNavigation) { }
        @Override public void onAccountSelected(long accountId) { }
        @Override
        public void onParentMailboxChanged() { }
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
     *
     * @param accountId The ID of the account we want to view
     * @param initialCurrentMailboxId ID of the mailbox of interest.
     *        Pass {@link Mailbox#NO_MAILBOX} to show top-level mailboxes.
     * @param enableHighlight {@code true} if highlighting is enabled on the current screen
     *        configuration.  (We don't highlight mailboxes on one-pane.)
     */
    public static MailboxListFragment newInstance(long accountId, long initialCurrentMailboxId,
            boolean enableHighlight) {
        final MailboxListFragment instance = new MailboxListFragment();
        final Bundle args = new Bundle();
        args.putLong(ARG_ACCOUNT_ID, accountId);
        args.putLong(ARG_INITIAL_CURRENT_MAILBOX_ID, initialCurrentMailboxId);
        args.putBoolean(ARG_ENABLE_HIGHLIGHT, enableHighlight);
        instance.setArguments(args);
        return instance;
    }

    /**
     * The account ID the mailbox is associated with. Do not use directly; instead, use
     * {@link #getAccountId()}.
     * <p><em>NOTE:</em> Although we cannot force these to be immutable using Java language
     * constructs, this <em>must</em> be considered immutable.
     */
    private Long mImmutableAccountId;

    /**
     * {@code initialCurrentMailboxId} passed to {@link #newInstance}.
     * Do not use directly; instead, use {@link #getInitialCurrentMailboxId()}.
     * <p><em>NOTE:</em> Although we cannot force these to be immutable using Java language
     * constructs, this <em>must</em> be considered immutable.
     */
    private long mImmutableInitialCurrentMailboxId;

    /**
     * {@code enableHighlight} passed to {@link #newInstance}.
     * Do not use directly; instead, use {@link #getEnableHighlight()}.
     * <p><em>NOTE:</em> Although we cannot force these to be immutable using Java language
     * constructs, this <em>must</em> be considered immutable.
     */
    private boolean mImmutableEnableHighlight;

    private void initializeArgCache() {
        if (mImmutableAccountId != null) return;
        mImmutableAccountId = getArguments().getLong(ARG_ACCOUNT_ID);
        mImmutableInitialCurrentMailboxId = getArguments().getLong(ARG_INITIAL_CURRENT_MAILBOX_ID);
        mImmutableEnableHighlight = getArguments().getBoolean(ARG_ENABLE_HIGHLIGHT);
    }

    /**
     * @return {@code accountId} passed to {@link #newInstance}.  Safe to call even before onCreate.
     */
    public long getAccountId() {
        initializeArgCache();
        return mImmutableAccountId;
    }

    /**
     * @return {@code initialCurrentMailboxId} passed to {@link #newInstance}.
     * Safe to call even before onCreate.
     */
    public long getInitialCurrentMailboxId() {
        initializeArgCache();
        return mImmutableInitialCurrentMailboxId;
    }

    /**
     * @return {@code enableHighlight} passed to {@link #newInstance}.
     * Safe to call even before onCreate.
     */
    public boolean getEnableHighlight() {
        initializeArgCache();
        return mImmutableEnableHighlight;
    }

    @Override
    public void onAttach(Activity activity) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onAttach");
        }
        super.onAttach(activity);
    }

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onCreate");
        }
        super.onCreate(savedInstanceState);

        mActivity = getActivity();
        mRefreshManager = RefreshManager.getInstance(mActivity);
        mListAdapter = new MailboxFragmentAdapter(mActivity, mMailboxesAdapterCallback);
        setListAdapter(mListAdapter); // It's safe to do even before the list view is created.

        if (savedInstanceState == null) {
            setInitialParentAndHighlight();
        } else {
            restoreInstanceState(savedInstanceState);
        }
    }

    /**
     * Set {@link #mParentMailboxId} and {@link #mHighlightedMailboxId} from the fragment arguments.
     */
    private void setInitialParentAndHighlight() {
        final long initialMailboxId = getInitialCurrentMailboxId();
        if (getAccountId() == Account.ACCOUNT_ID_COMBINED_VIEW) {
            // For the combined view, always show the top-level, but highlight the "current".
            mParentMailboxId = Mailbox.NO_MAILBOX;
        } else {
            // Inbox needs special care.
            // Note we can't get the mailbox type on the UI thread but this method *can* be used...
            final long inboxId = Mailbox.findMailboxOfType(getActivity(), getAccountId(),
                    Mailbox.TYPE_INBOX);
            if (initialMailboxId == inboxId) {
                // If Inbox is set as the initial current, we show the top level mailboxes
                // with inbox highlighted.
                mParentMailboxId = Mailbox.NO_MAILBOX;
            } else {
                // Otherwise, try using the "current" as the "parent" (and also highlight it).
                // If it has no children, we go up in onLoadFinished().
                mParentMailboxId = initialMailboxId;
            }
        }
        // Highlight the mailbox of interest
        if (getEnableHighlight()) {
            mHighlightedMailboxId = initialMailboxId;
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onCreateView");
        }
        return inflater.inflate(R.layout.mailbox_list_fragment, container, false);
    }

    /**
     * @return true if the content view is created and not destroyed yet. (i.e. between
     * {@link #onCreateView} and {@link #onDestroyView}.
     */
    private boolean isViewCreated() {
        return getView() != null;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);

        // Note we can't do this in onCreateView.
        // getListView() is only usable after onCreateView().
        final ListView lv = getListView();
        lv.setOnItemClickListener(this);
        lv.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        lv.setOnDragListener(this);

        startLoading(mParentMailboxId, mHighlightedMailboxId);

        UiUtilities.installFragment(this);
    }

    public void setCallback(Callback callback) {
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onStart");
        }
        super.onStart();
    }

    /**
     * Called when the fragment is visible to the user and actively running.
     */
    @Override
    public void onResume() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onResume");
        }
        super.onResume();

        // Fetch the latest mailbox list from the server here if stale so that the user always
        // sees the (reasonably) up-to-date mailbox list, without pressing "refresh".
        final long accountId = getAccountId();
        if (mRefreshManager.isMailboxListStale(accountId)) {
            mRefreshManager.refreshMailboxList(accountId);
        }
    }

    @Override
    public void onPause() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onPause");
        }
        mSavedListState = getListView().onSaveInstanceState();
        super.onPause();
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onStop");
        }
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onDestroyView");
        }
        UiUtilities.uninstallFragment(this);
        super.onDestroyView();
    }

    /**
     * Called when the fragment is no longer in use.
     */
    @Override
    public void onDestroy() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onDestroy");
        }
        mTaskTracker.cancellAllInterrupt();
        super.onDestroy();
    }

    @Override
    public void onDetach() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onDetach");
        }
        super.onDetach();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        outState.putLong(BUNDLE_KEY_PARENT_MAILBOX_ID, mParentMailboxId);
        outState.putLong(BUNDLE_KEY_HIGHLIGHTED_MAILBOX_ID, mHighlightedMailboxId);
        if (isViewCreated()) {
            outState.putParcelable(BUNDLE_LIST_STATE, getListView().onSaveInstanceState());
        }
    }

    private void restoreInstanceState(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " restoreInstanceState");
        }
        mParentMailboxId = savedInstanceState.getLong(BUNDLE_KEY_PARENT_MAILBOX_ID);
        mNextHighlightedMailboxId = savedInstanceState.getLong(BUNDLE_KEY_HIGHLIGHTED_MAILBOX_ID);
        mSavedListState = savedInstanceState.getParcelable(BUNDLE_LIST_STATE);
    }

    /**
     * @return "Selected" mailbox ID.
     */
    public long getSelectedMailboxId() {
        return (mHighlightedMailboxId != Mailbox.NO_MAILBOX) ? mHighlightedMailboxId
                : mParentMailboxId;
    }

    /**
     * @return {@code true} if top-level mailboxes are shown.  {@code false} otherwise.
     */
    private boolean isRoot() {
        return mParentMailboxId == Mailbox.NO_MAILBOX;
    }

    /**
     * Navigate one level up in the mailbox hierarchy. Does nothing if at the root account view.
     */
    public boolean navigateUp() {
        if (isRoot()) {
            return false;
        }
        FindParentMailboxTask.ResultCallback callback = new FindParentMailboxTask.ResultCallback() {
            @Override public void onResult(long nextParentMailboxId,
                    long nextHighlightedMailboxId, long nextSelectedMailboxId) {

                startLoading(nextParentMailboxId, nextHighlightedMailboxId);
            }
        };
        new FindParentMailboxTask(
                getActivity().getApplicationContext(), mTaskTracker, getAccountId(),
                getEnableHighlight(), mParentMailboxId, mHighlightedMailboxId, callback
                ).cancelPreviousAndExecuteParallel((Void[]) null);
        return true;
    }

    /**
     * @return {@code true} if the fragment is showing nested mailboxes and we can go one level up.
     *         {@code false} otherwise, meaning we're showing the top level mailboxes *OR*
     *         we're still loading initial data and we can't determine if we're going to show
     *         top-level or not.
     */
    public boolean canNavigateUp() {
        if (!mParentDetermined) {
            return false; // We can't determine yet...
        }
        return !isRoot();
    }

    /**
     * A task to determine what parent mailbox ID/highlighted mailbox ID to use for the "UP"
     * navigation, given the current parent mailbox ID, the highlighted mailbox ID, and {@link
     * #mEnableHighlight}.
     */
    @VisibleForTesting
    static class FindParentMailboxTask extends EmailAsyncTask<Void, Void, Long[]> {
        public interface ResultCallback {
            /**
             * Callback to get the result.
             *
             * @param nextParentMailboxId ID of the mailbox to use
             * @param nextHighlightedMailboxId ID of the mailbox to highlight
             * @param nextSelectedMailboxId ID of the mailbox to notify with
             *        {@link Callback#onMailboxSelected}.
             */
            public void onResult(long nextParentMailboxId, long nextHighlightedMailboxId,
                    long nextSelectedMailboxId);
        }

        private final Context mContext;
        private final long mAccountId;
        private final boolean mEnableHighlight;
        private final long mParentMailboxId;
        private final long mHighlightedMailboxId;
        private final ResultCallback mCallback;

        public FindParentMailboxTask(Context context, EmailAsyncTask.Tracker taskTracker,
                long accountId, boolean enableHighlight, long parentMailboxId,
                long highlightedMailboxId, ResultCallback callback) {
            super(taskTracker);
            mContext = context;
            mAccountId = accountId;
            mEnableHighlight = enableHighlight;
            mParentMailboxId = parentMailboxId;
            mHighlightedMailboxId = highlightedMailboxId;
            mCallback = callback;
        }

        @Override
        protected Long[] doInBackground(Void... params) {
            Mailbox parentMailbox = Mailbox.restoreMailboxWithId(mContext, mParentMailboxId);
            final long nextParentId = (parentMailbox == null) ? Mailbox.NO_MAILBOX
                    : parentMailbox.mParentKey;
            final long nextHighlightedId;
            final long nextSelectedId;
            if (mEnableHighlight) {
                // If the "parent" is highlighted before the transition, it should still be
                // highlighted after the upper level view.
                if (mParentMailboxId == mHighlightedMailboxId) {
                    nextHighlightedId = mParentMailboxId;
                } else {
                    // Otherwise, the next parent will be highlighted, unless we're going up to
                    // the root, in which case Inbox should be highlighted.
                    if (nextParentId == Mailbox.NO_MAILBOX) {
                        nextHighlightedId = Mailbox.findMailboxOfType(mContext, mAccountId,
                                Mailbox.TYPE_INBOX);
                    } else {
                        nextHighlightedId = nextParentId;
                    }
                }

                // Highlighted one will be "selected".
                nextSelectedId = nextHighlightedId;

            } else { // !mEnableHighlight
                nextHighlightedId = Mailbox.NO_MAILBOX;

                // Parent will be selected.
                nextSelectedId = nextParentId;
            }
            return new Long[]{nextParentId, nextHighlightedId, nextSelectedId};
        }

        @Override
        protected void onSuccess(Long[] result) {
            mCallback.onResult(result[0], result[1], result[2]);
        }
    }

    /**
     * Starts the loader.
     *
     * @param parentMailboxId Mailbox ID to be used as the "parent" mailbox
     * @param highlightedMailboxId Mailbox ID that should be highlighted when the data is loaded.
     */
    private void startLoading(long parentMailboxId, long highlightedMailboxId) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " startLoading  parent=" + parentMailboxId
                    + " highlighted=" + highlightedMailboxId);
        }
        final LoaderManager lm = getLoaderManager();
        boolean parentMailboxChanging = false;

        // Parent mailbox changing -- destroy the current loader to force reload.
        if (mParentMailboxId != parentMailboxId) {
            lm.destroyLoader(MAILBOX_LOADER_ID);
            setListShown(false);
            parentMailboxChanging = true;
        }
        mParentMailboxId = parentMailboxId;
        if (getEnableHighlight()) {
            mNextHighlightedMailboxId = highlightedMailboxId;
        }

        lm.initLoader(MAILBOX_LOADER_ID, null, new MailboxListLoaderCallbacks());

        if (parentMailboxChanging) {
            mCallback.onParentMailboxChanged();
        }
    }

    /**
     * Highlight the given mailbox.
     *
     * If data is already loaded, it just sets {@link #mHighlightedMailboxId} and highlight the
     * corresponding list item.  (And if the corresponding list item is not found,
     * {@link #mHighlightedMailboxId} is set to {@link Mailbox#NO_MAILBOX})
     *
     * If we're still loading data, it sets {@link #mNextHighlightedMailboxId} instead, and then
     * it'll be set to {@link #mHighlightedMailboxId} in
     * {@link MailboxListLoaderCallbacks#onLoadFinished}.
     *
     * @param mailboxId The ID of the mailbox to highlight.
     */
    public void setHighlightedMailbox(long mailboxId) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " setHighlightedMailbox  mailbox=" + mailboxId);
        }
        if (!getEnableHighlight()) {
            return;
        }
        if (mHighlightedMailboxId == mailboxId) {
            return; // already highlighted.
        }
        if (mListAdapter.getCursor() == null) {
            // List not loaded yet.  Just remember the ID here and let onLoadFinished() update
            // mHighlightedMailboxId.
            mNextHighlightedMailboxId = mailboxId;
            return;
        }
        mHighlightedMailboxId = mailboxId;
        updateHighlightedMailbox(true);
    }

    // TODO This class probably should be made static. There are many calls into the enclosing
    // class and we need to be cautious about what we call while in these callbacks
    private class MailboxListLoaderCallbacks implements LoaderCallbacks<Cursor> {
        private boolean mIsFirstLoad;

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Logging.LOG_TAG, MailboxListFragment.this + " onCreateLoader");
            }
            mIsFirstLoad = true;
            if (getAccountId() == Account.ACCOUNT_ID_COMBINED_VIEW) {
                return MailboxFragmentAdapter.createCombinedViewLoader(getActivity());
            } else {
                return MailboxFragmentAdapter.createMailboxesLoader(getActivity(), getAccountId(),
                        mParentMailboxId);
            }
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
            if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Logging.LOG_TAG, MailboxListFragment.this + " onLoadFinished  count="
                        + cursor.getCount());
            }
            // Note in onLoadFinished we can assume the view is created.
            // The loader manager doesn't deliver results when a fragment is stopped.

            // If we're showing a nested mailboxes, and the current parent mailbox has no children,
            // go up.
            if (getAccountId() != Account.ACCOUNT_ID_COMBINED_VIEW) {
                MailboxFragmentAdapter.CursorWithExtras c =
                        (MailboxFragmentAdapter.CursorWithExtras) cursor;
                if ((c.mChildCount == 0) && !isRoot()) {
                    // Always swap out the cursor so we don't hold a reference to a stale one.
                    mListAdapter.swapCursor(cursor);
                    navigateUp();
                    return;
                }
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
                // There's no row -- call setListShown(false) to make ListFragment show progress
                // icon.
                mListAdapter.swapCursor(null);
                setListShown(false);

            } else {
                mParentDetermined = true; // Okay now we're sure which mailbox is the parent.

                mListAdapter.swapCursor(cursor);
                setListShown(true);

                // Restore the list state, so scroll position is restored - this has to happen
                // prior to setting the checked/highlighted mailbox below.
                lv.onRestoreInstanceState(listState);

                // Update the highlighted mailbox
                if (mNextHighlightedMailboxId != Mailbox.NO_MAILBOX) {
                    setHighlightedMailbox(mNextHighlightedMailboxId);
                    mNextHighlightedMailboxId = Mailbox.NO_MAILBOX;
                }

                // We want to make visible the selection only for the first load.
                // Re-load caused by content changed events shouldn't scroll the list.
                if (!updateHighlightedMailbox(mIsFirstLoad)) {
                    // This may happen if the mailbox to be selected is not actually in the list
                    // that was loaded. Let the user just pick one manually if needed.
                    return;
                }
            }

            // List has been reloaded; clear any drop target information
            mDropTargetId = NO_DROP_TARGET;
            mDropTargetView = null;

            mIsFirstLoad = false;
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Logging.LOG_TAG, MailboxListFragment.this + " onLoaderReset");
            }
            mListAdapter.swapCursor(null);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * @param doNotUse <em>IMPORTANT</em>: Do not use this parameter. The ID in the list widget
     * must be a positive value. However, we rely on negative IDs for special mailboxes. Instead,
     * we use the ID returned by {@link MailboxFragmentAdapter#getId(int)}.
     */
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long doNotUse) {
        final long id = mListAdapter.getId(position);
        if (mListAdapter.isAccountRow(position)) {
            mCallback.onAccountSelected(id);
        } else if (mListAdapter.isMailboxRow(position)) {
            // Save account-id.  (Need to do this before startLoading() below, which will destroy
            // the current loader and make the mListAdapter lose the cursor.
            // Note, don't just use getAccountId().  A mailbox may tied to a different account ID
            // from getAccountId().  (Currently "Starred" does so.)
            final long accountId = mListAdapter.getAccountId(position);
            boolean nestedNavigation = false;
            if (((MailboxListItem) view).isNavigable() && (id != mParentMailboxId)) {
                // Drill-in.  Selected one will be the next parent, and it'll also be highlighted.
                startLoading(id, id);
                nestedNavigation = true;
            }
            mCallback.onMailboxSelected(accountId, id, nestedNavigation);
        }
    }

    /**
     * Really highlight the mailbox for {@link #mHighlightedMailboxId} on the list view.
     *
     * Note if a list item for {@link #mHighlightedMailboxId} is not found,
     * {@link #mHighlightedMailboxId} will be set to {@link Mailbox#NO_MAILBOX}.
     *
     * @return false when the highlighted mailbox seems to be gone; i.e. if
     *         {@link #mHighlightedMailboxId} is set but not found in the list.
     */
    private boolean updateHighlightedMailbox(boolean ensureSelectionVisible) {
        if (!getEnableHighlight() || !isViewCreated()) {
            return true; // Nothing to highlight
        }
        final ListView lv = getListView();
        boolean found = false;
        if (mHighlightedMailboxId == Mailbox.NO_MAILBOX) {
            // No mailbox selected
            lv.clearChoices();
            found = true;
        } else {
            // TODO Don't mix list view & list adapter indices. This is a recipe for disaster.
            final int count = lv.getCount();
            for (int i = 0; i < count; i++) {
                if (mListAdapter.getId(i) != mHighlightedMailboxId) {
                    continue;
                }
                found = true;
                lv.setItemChecked(i, true);
                if (ensureSelectionVisible) {
                    Utility.listViewSmoothScrollToPosition(getActivity(), lv, i);
                }
                break;
            }
        }
        if (!found) {
            mHighlightedMailboxId = Mailbox.NO_MAILBOX;
        }
        return found;
    }

    // Drag & Drop handling

    /**
     * Update all of the list's child views with the proper target background (for now, orange if
     * a valid target, except red if the trash; standard background otherwise)
     */
    private void updateChildViews() {
        final ListView lv = getListView();
        int itemCount = lv.getChildCount();
        // Lazily initialize the height of our list items
        if (itemCount > 0 && mDragItemHeight < 0) {
            mDragItemHeight = lv.getChildAt(0).getHeight();
        }
        for (int i = 0; i < itemCount; i++) {
            final View child = lv.getChildAt(i);
            if (!(child instanceof MailboxListItem)) {
                continue;
            }
            MailboxListItem item = (MailboxListItem) child;
            item.setDropTargetBackground(mDragInProgress, mDragItemMailboxId);
        }
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
        stopScrolling();
    }

    /**
     * Called while dragging;  highlight possible drop targets, and auto scroll the list.
     */
    private void onDragLocation(DragEvent event) {
        final ListView lv = getListView();
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
        final int viewIndex = pointToIndex(lv, rawTouchX, rawTouchY);
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
            final View childView = lv.getChildAt(viewIndex);
            final MailboxListItem newTarget;
            if (childView == null) {
                // In any event, we're no longer dragging in the list view if newTarget is null
                if (DEBUG_DRAG_DROP) {
                    Log.d(TAG, "=== Drag off the list");
                }
                newTarget = null;
                final int childCount = lv.getChildCount();
                if (viewIndex >= childCount) {
                    // Touching beyond the end of the list; may happen for small lists
                    onDragExited();
                    return;
                } else {
                    // We should never get here
                    Log.w(TAG, "null view; idx: " + viewIndex + ", cnt: " + childCount);
                }
            } else if (!(childView instanceof MailboxListItem)) {
                // We're over a header suchas "Recent folders".  We shouldn't finish DnD, but
                // drop should be disabled.
                newTarget = null;
                targetId = NO_DROP_TARGET;
            } else {
                newTarget = (MailboxListItem) childView;
                if (newTarget.mMailboxType == Mailbox.TYPE_TRASH) {
                    if (DEBUG_DRAG_DROP) {
                        Log.d(TAG, "=== Trash mailbox; id: " + newTarget.mMailboxId);
                    }
                    newTarget.setDropTrashBackground();
                } else if (newTarget.isDropTarget(mDragItemMailboxId)) {
                    if (DEBUG_DRAG_DROP) {
                        Log.d(TAG, "=== Target mailbox; id: " + newTarget.mMailboxId);
                    }
                    newTarget.setDropActiveBackground();
                } else {
                    if (DEBUG_DRAG_DROP) {
                        Log.d(TAG, "=== Non-droppable mailbox; id: " + newTarget.mMailboxId);
                    }
                    newTarget.setDropTargetBackground(true, mDragItemMailboxId);
                    targetId = NO_DROP_TARGET;
                }
            }
            // Save away our current position and view
            mDropTargetId = targetId;
            mDropTargetView = newTarget;
        }

        // This is a quick-and-dirty implementation of drag-under-scroll; something like this
        // should eventually find its way into the framework
        int scrollDiff = rawTouchY - (lv.getHeight() - SCROLL_ZONE_SIZE);
        boolean scrollDown = (scrollDiff > 0);
        boolean scrollUp = (SCROLL_ZONE_SIZE > rawTouchY);
        if (!mTargetScrolling && scrollDown) {
            int itemsToScroll = lv.getCount() - lv.getLastVisiblePosition();
            int pixelsToScroll = (itemsToScroll + 1) * mDragItemHeight;
            lv.smoothScrollBy(pixelsToScroll, pixelsToScroll * SCROLL_SPEED);
            if (DEBUG_DRAG_DROP) {
                Log.d(TAG, "=== Start scrolling list down");
            }
            mTargetScrolling = true;
        } else if (!mTargetScrolling && scrollUp) {
            int pixelsToScroll = (lv.getFirstVisiblePosition() + 1) * mDragItemHeight;
            lv.smoothScrollBy(-pixelsToScroll, pixelsToScroll * SCROLL_SPEED);
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
        final ListView lv = getListView();
        if (mTargetScrolling) {
            mTargetScrolling = false;
            if (DEBUG_DRAG_DROP) {
                Log.d(TAG, "=== Stop scrolling list");
            }
            // Stop the scrolling
            lv.smoothScrollBy(0, 0);
        }
    }

    private void onDragEnded() {
        if (mDragInProgress) {
            mDragInProgress = false;
            // Reenable updates to the view and redraw (in case it changed)
            MailboxFragmentAdapter.enableUpdates(true);
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
                MailboxFragmentAdapter.enableUpdates(false);
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
        // Call either deleteMessage or moveMessage, depending on the target
        if (mDropTargetView.mMailboxType == Mailbox.TYPE_TRASH) {
            controller.deleteMessages(messageIds);
        } else {
            controller.moveMessages(messageIds, mDropTargetView.mMailboxId);
        }
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
