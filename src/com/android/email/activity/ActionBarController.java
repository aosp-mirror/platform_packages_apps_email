/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.ActionBar;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.SearchView;
import android.widget.TextView;

import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;

/**
 * Manages the account name and the custom view part on the action bar.
 *
 * TODO Show current mailbox name/unread count on the account spinner
 *      -- and remove mMailboxNameContainer.
 *
 * TODO Stop using the action bar spinner and create our own spinner as a custom view.
 *      (so we'll be able to just hide it, etc.)
 *
 * TODO Update search hint somehow
 */
public class ActionBarController {
    private static final String BUNDLE_KEY_MODE = "ActionBarController.BUNDLE_KEY_MODE";

    /**
     * Constants for {@link #mSearchMode}.
     *
     * In {@link #MODE_NORMAL} mode, we don't show the search box.
     * In {@link #MODE_SEARCH} mode, we do show the search box.
     * The action bar doesn't really care if the activity is showing search results.
     * If the activity is showing search results, and the {@link Callback#onSearchExit} is called,
     * the activity probably wants to close itself, but this class doesn't make the desision.
     */
    private static final int MODE_NORMAL = 0;
    private static final int MODE_SEARCH = 1;

    private static final int LOADER_ID_ACCOUNT_LIST
            = EmailActivity.ACTION_BAR_CONTROLLER_LOADER_ID_BASE + 0;

    private final Context mContext;
    private final LoaderManager mLoaderManager;
    private final ActionBar mActionBar;

    private final View mActionBarCustomView;
    private final View mMailboxNameContainer;
    private final TextView mMailboxNameView;
    private final TextView mUnreadCountView;
    private final View mSearchContainer;
    private final SearchView mSearchView;

    private final ActionBarNavigationCallback mActionBarNavigationCallback =
        new ActionBarNavigationCallback();

    private final AccountSelectorAdapter mAccountsSelectorAdapter;
    private AccountSelectorAdapter.CursorWithExtras mAccountCursor;
    /** The current account ID; used to determine if the account has changed. */
    private long mLastAccountIdForDirtyCheck = Account.NO_ACCOUNT;

    /** Either {@link #MODE_NORMAL} or {@link #MODE_SEARCH}. */
    private int mSearchMode = MODE_NORMAL;

    public final Callback mCallback;

    public interface SearchContext {
        public long getTargetMailboxId();
    }

    public interface Callback {
        /** @return true if an account is selected. */
        public boolean isAccountSelected();

        /**
         * @return currently selected account ID, {@link Account#ACCOUNT_ID_COMBINED_VIEW},
         * or -1 if no account is selected.
         */
        public long getUIAccountId();

        /** @return true if the current mailbox name should be shown.  */
        public boolean shouldShowMailboxName();

        /** @return current mailbox name */
        public String getCurrentMailboxName();
        /**
         * @return unread count for the current mailbox.  (0 if the mailbox doesn't have the concept
         *     of "unread"; e.g. Drafts)
         */
        public int getCurrentMailboxUnreadCount();

        /** @return the "UP" arrow should be shown. */
        public boolean shouldShowUp();

        /**
         * Called when an account is selected on the account spinner.
         * @param accountId ID of the selected account, or {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
         */
        public void onAccountSelected(long accountId);

        /**
         * Invoked when a recent mailbox is selected on the account spinner.
         * @param mailboxId The ID of the selected mailbox, or {@link Mailbox#NO_MAILBOX} if the
         *          special option "show all mailboxes" was selected.
         */
        public void onMailboxSelected(long mailboxId);

        /** Called when no accounts are found in the database. */
        public void onNoAccountsFound();

        /**
         * Called when a search is submitted.
         *
         * @param queryTerm query string
         */
        public void onSearchSubmit(String queryTerm);

        /**
         * Called when the search box is closed.
         */
        public void onSearchExit();
    }

    public ActionBarController(Context context, LoaderManager loaderManager,
            ActionBar actionBar, Callback callback) {
        mContext = context;
        mLoaderManager = loaderManager;
        mActionBar = actionBar;
        mCallback = callback;
        mAccountsSelectorAdapter = new AccountSelectorAdapter(mContext);

        mActionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE
                | ActionBar.DISPLAY_SHOW_HOME
                | ActionBar.DISPLAY_SHOW_CUSTOM);

        // Prepare the custom view
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        mActionBarCustomView = inflater.inflate(R.layout.action_bar_custom_view, null);
        final ActionBar.LayoutParams customViewLayout = new ActionBar.LayoutParams(
                ActionBar.LayoutParams.MATCH_PARENT,
                ActionBar.LayoutParams.MATCH_PARENT);
        customViewLayout.setMargins(0 , 0, 0, 0);
        mActionBar.setCustomView(mActionBarCustomView, customViewLayout);

        // Mailbox name / unread count
        mMailboxNameContainer = UiUtilities.getView(mActionBarCustomView,
                R.id.current_mailbox_container);
        mMailboxNameView = UiUtilities.getView(mMailboxNameContainer, R.id.mailbox_name);
        mUnreadCountView = UiUtilities.getView(mMailboxNameContainer, R.id.unread_count);

        // Search
        mSearchContainer = UiUtilities.getView(mActionBarCustomView, R.id.search_container);
        mSearchView = UiUtilities.getView(mSearchContainer, R.id.search_view);
        mSearchView.setSubmitButtonEnabled(true);
        mSearchView.setOnQueryTextListener(mOnQueryText);
    }

    /** Must be called from {@link UIControllerBase#onActivityCreated()} */
    public void onActivityCreated() {
        loadAccounts();
        refresh();
    }

    /** Must be called from {@link UIControllerBase#onActivityDestroy()} */
    public void onActivityDestroy() {
    }

    /** Must be called from {@link UIControllerBase#onSaveInstanceState} */
    public void onSaveInstanceState(Bundle outState) {
        outState.putInt(BUNDLE_KEY_MODE, mSearchMode);
    }

    /** Must be called from {@link UIControllerBase#onRestoreInstanceState} */
    public void onRestoreInstanceState(Bundle savedState) {
        int mode = savedState.getInt(BUNDLE_KEY_MODE);
        if (mode == MODE_SEARCH) {
            // No need to re-set the initial query, as the View tree restoration does that
            enterSearchMode(null);
        }
    }

    /**
     * @return true if the search box is shown.
     */
    private boolean isInSearchMode() {
        return mSearchMode == MODE_SEARCH;
    }

    /**
     * Show the search box.
     *
     * @param initialQueryTerm if non-empty, set to the search box.
     */
    public void enterSearchMode(String initialQueryTerm) {
        if (isInSearchMode()) {
            return;
        }
        if (!TextUtils.isEmpty(initialQueryTerm)) {
            mSearchView.setQuery(initialQueryTerm, false);
        } else {
            mSearchView.setQuery("", false);
        }
        mSearchMode = MODE_SEARCH;

        // Need to force it to mode "standard" to hide it.
        mActionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
        mActionBar.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_TITLE);
        mSearchContainer.setVisibility(View.VISIBLE);

        // Focus on the search input box and throw up the IME if specified.
        // TODO: HACK. this is a workaround IME not popping up.
        mSearchView.setIconified(false);

        refresh();
    }

    public void exitSearchMode() {
        if (!isInSearchMode()) {
            return;
        }
        mSearchMode = MODE_NORMAL;
        mSearchContainer.setVisibility(View.GONE);
        mActionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE, ActionBar.DISPLAY_SHOW_TITLE);

        // Force update of account list when we exit search.
        updateAccountList();

        refresh();
        mCallback.onSearchExit();
    }

    /**
     * Performs the back action.
     *
     * @param isSystemBackKey <code>true</code> if the system back key was pressed.
     * <code>false</code> if it's caused by the "home" icon click on the action bar.
     */
    public boolean onBackPressed(boolean isSystemBackKey) {
        if (isInSearchMode()) {
            exitSearchMode();
            return true;
        }
        return false;
    }

    /** Refreshes the action bar display. */
    public void refresh() {
        final boolean showUp = isInSearchMode() || mCallback.shouldShowUp();
        mActionBar.setDisplayOptions(showUp
                ? ActionBar.DISPLAY_HOME_AS_UP : 0, ActionBar.DISPLAY_HOME_AS_UP);

        if (isInSearchMode()) {
            mMailboxNameContainer.setVisibility(View.GONE);
        } else {
            mMailboxNameContainer.setVisibility(mCallback.shouldShowMailboxName()
                    ? View.VISIBLE : View.GONE);
            mMailboxNameView.setText(mCallback.getCurrentMailboxName());
        }

        // Note on action bar, we show only "unread count".  Some mailboxes such as Outbox don't
        // have the idea of "unread count", in which case we just omit the count.
        mUnreadCountView.setText(UiUtilities.getMessageCountForUi(mContext,
                    mCallback.getCurrentMailboxUnreadCount(), true));

        // Update the account list only when the account has changed.
        if (mLastAccountIdForDirtyCheck != mCallback.getUIAccountId()) {
            mLastAccountIdForDirtyCheck = mCallback.getUIAccountId();
            // If the selected account changes, reload the cursor to update the recent mailboxes
            if (mLastAccountIdForDirtyCheck != Account.NO_ACCOUNT) {
                mLoaderManager.destroyLoader(LOADER_ID_ACCOUNT_LIST);
                loadAccounts();
            } else {
                updateAccountList();
            }
        }
    }

    /**
     * Load account cursor, and update the action bar.
     */
    private void loadAccounts() {
        mLoaderManager.initLoader(LOADER_ID_ACCOUNT_LIST, null,
                new LoaderCallbacks<Cursor>() {
            @Override
            public Loader<Cursor> onCreateLoader(int id, Bundle args) {
                return AccountSelectorAdapter.createLoader(mContext, mCallback.getUIAccountId());
            }

            @Override
            public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
                mAccountCursor = (AccountSelectorAdapter.CursorWithExtras) data;
                updateAccountList();
            }

            @Override
            public void onLoaderReset(Loader<Cursor> loader) {
                mAccountCursor = null;
                updateAccountList();
            }
        });
    }

    /**
     * Called when the LOADER_ID_ACCOUNT_LIST loader loads the data.  Update the account spinner
     * on the action bar.
     */
    private void updateAccountList() {
        mAccountsSelectorAdapter.swapCursor(mAccountCursor);

        if (mSearchMode == MODE_SEARCH) {
            // In search mode, so we don't care about the account list - it'll get updated when
            // it goes visible again.
            return;
        }

        final ActionBar ab = mActionBar;
        if (mAccountCursor == null) {
            // Cursor not ready or closed.
            ab.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_TITLE);
            ab.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            return;
        }

        final int count = mAccountCursor.mAccountCount + mAccountCursor.mRecentCount;
        if (count == 0) {
            mCallback.onNoAccountsFound();
            return;
        }

        // If only one account, don't show the drop down.
        int selectedPosition = mAccountCursor.getPosition(mCallback.getUIAccountId());
        if (count == 1) {
            // Show the account name as the title.
            ab.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE, ActionBar.DISPLAY_SHOW_TITLE);
            ab.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            if (selectedPosition >= 0) {
                mAccountCursor.moveToPosition(selectedPosition);
                ab.setTitle(AccountSelectorAdapter.getAccountDisplayName(mAccountCursor));
            }
            return;
        }

        // Update the drop down list.
        if (ab.getNavigationMode() != ActionBar.NAVIGATION_MODE_LIST) {
            ab.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_TITLE);
            ab.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
            ab.setListNavigationCallbacks(mAccountsSelectorAdapter, mActionBarNavigationCallback);
        }
        // Find the currently selected account, and select it.
        if (selectedPosition >= 0) {
            ab.setSelectedNavigationItem(selectedPosition);
        }
    }

    private class ActionBarNavigationCallback implements ActionBar.OnNavigationListener {
        @Override
        public boolean onNavigationItemSelected(int itemPosition, long itemId) {
            if (mAccountsSelectorAdapter.isAccountItem(itemPosition)
                    && itemId != mCallback.getUIAccountId()) {
                mCallback.onAccountSelected(itemId);
            } else if (mAccountsSelectorAdapter.isMailboxItem(itemPosition)) {
                mCallback.onMailboxSelected(itemId);
                // We need to update the selection, otherwise the user is unable to select the
                // recent folder a second time w/o first selecting another item in the spinner
                int selectedPosition = mAccountsSelectorAdapter.getAccountPosition(itemPosition);
                if (selectedPosition != AccountSelectorAdapter.UNKNOWN_POSITION) {
                    mActionBar.setSelectedNavigationItem(selectedPosition);
                }
            } else {
                Log.i(Logging.LOG_TAG,
                        "Invalid type selected in ActionBarController at index " + itemPosition);
            }
            return true;
        }
    }

    private final SearchView.OnQueryTextListener mOnQueryText
            = new SearchView.OnQueryTextListener() {
        @Override
        public boolean onQueryTextChange(String newText) {
            // Event not handled.  Let the search do the default action.
            return false;
        }

        @Override
        public boolean onQueryTextSubmit(String query) {
            mCallback.onSearchSubmit(mSearchView.getQuery().toString());
            return true; // Event handled.
        }
    };

}
