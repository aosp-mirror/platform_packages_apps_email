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
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.TextAppearanceSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;

import com.android.email.R;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.DelayedOperations;
import com.android.emailcommon.utility.Utility;

/**
 * Manages the account name and the custom view part on the action bar.
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

    private static final int DISPLAY_TITLE_MULTIPLE_LINES = 0x20;
    private static final int ACTION_BAR_MASK =
            ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_SHOW_CUSTOM
                    | ActionBar.DISPLAY_SHOW_TITLE | DISPLAY_TITLE_MULTIPLE_LINES;
    private static final int CUSTOM_ACTION_BAR_OPTIONS =
            ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_SHOW_CUSTOM;

    private final Context mContext;
    private final LoaderManager mLoaderManager;
    private final ActionBar mActionBar;
    private final DelayedOperations mDelayedOperations;

    /** "Folders" label shown with account name on 1-pane mailbox list */
    private final String mAllFoldersLabel;

    private final ViewGroup mActionBarCustomView;
    private final ViewGroup mAccountSpinnerContainer;
    private final View mAccountSpinner;
    private final Drawable mAccountSpinnerDefaultBackground;
    private final TextView mAccountSpinnerLine1View;
    private final TextView mAccountSpinnerLine2View;
    private final TextView mAccountSpinnerCountView;

    private View mSearchContainer;
    private SearchView mSearchView;

    private final AccountDropdownPopup mAccountDropdown;

    private final AccountSelectorAdapter mAccountsSelectorAdapter;

    private AccountSelectorAdapter.CursorWithExtras mCursor;

    /** The current account ID; used to determine if the account has changed. */
    private long mLastAccountIdForDirtyCheck = Account.NO_ACCOUNT;

    /** The current mailbox ID; used to determine if the mailbox has changed. */
    private long mLastMailboxIdForDirtyCheck = Mailbox.NO_MAILBOX;

    /** Either {@link #MODE_NORMAL} or {@link #MODE_SEARCH}. */
    private int mSearchMode = MODE_NORMAL;

    /** The current title mode, which should be one of {@code Callback TITLE_MODE_*} */
    private int mTitleMode;

    public final Callback mCallback;

    public interface SearchContext {
        public long getTargetMailboxId();
    }

    private static final int TITLE_MODE_SPINNER_ENABLED = 0x10;

    public interface Callback {
        /** Values for {@link #getTitleMode}.  Show only account name */
        public static final int TITLE_MODE_ACCOUNT_NAME_ONLY = 0 | TITLE_MODE_SPINNER_ENABLED;

        /**
         * Show the current account name with "Folders"
         * The account spinner will be disabled in this mode.
         */
        public static final int TITLE_MODE_ACCOUNT_WITH_ALL_FOLDERS_LABEL = 1;

        /**
         * Show the current account name and the current mailbox name.
         */
        public static final int TITLE_MODE_ACCOUNT_WITH_MAILBOX = 2 | TITLE_MODE_SPINNER_ENABLED;
        /**
         * Show the current message subject.  Actual subject is obtained via
         * {@link #getMessageSubject()}.
         *
         * The account spinner will be disabled in this mode.
         */
        public static final int TITLE_MODE_MESSAGE_SUBJECT = 3;

        /** @return true if an account is selected. */
        public boolean isAccountSelected();

        /**
         * @return currently selected account ID, {@link Account#ACCOUNT_ID_COMBINED_VIEW},
         * or -1 if no account is selected.
         */
        public long getUIAccountId();

        /**
         * @return currently selected mailbox ID, or {@link Mailbox#NO_MAILBOX} if no mailbox is
         * selected.
         */
        public long getMailboxId();

        /**
         * @return constants such as {@link #TITLE_MODE_ACCOUNT_NAME_ONLY}.
         */
        public int getTitleMode();

        /** @see #TITLE_MODE_MESSAGE_SUBJECT */
        public String getMessageSubject();

        /** @return the "UP" arrow should be shown. */
        public boolean shouldShowUp();

        /**
         * Called when an account is selected on the account spinner.
         * @param accountId ID of the selected account, or {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
         */
        public void onAccountSelected(long accountId);

        /**
         * Invoked when a recent mailbox is selected on the account spinner.
         *
         * @param accountId ID of the selected account, or {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
         * @param mailboxId The ID of the selected mailbox, or {@link Mailbox#NO_MAILBOX} if the
         *          special option "show all mailboxes" was selected.
         */
        public void onMailboxSelected(long accountId, long mailboxId);

        /** Called when no accounts are found in the database. */
        public void onNoAccountsFound();

        /**
         * Retrieves the hint text to be shown for when a search entry is being made.
         */
        public String getSearchHint();

        /**
         * Called when the action bar initially shows the search entry field.
         */
        public void onSearchStarted();

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

        public void onUpPressed();
    }

    public ActionBarController(Context context, LoaderManager loaderManager,
            ActionBar actionBar, Callback callback) {
        mContext = context;
        mLoaderManager = loaderManager;
        mActionBar = actionBar;
        mCallback = callback;
        mDelayedOperations = new DelayedOperations(Utility.getMainThreadHandler());
        mAllFoldersLabel = mContext.getResources().getString(
                R.string.action_bar_mailbox_list_title);
        mAccountsSelectorAdapter = new AccountSelectorAdapter(mContext);

        // Configure action bar.
        enterCustomActionBarMode();

        // Prepare the custom view
        mActionBar.setCustomView(R.layout.action_bar_custom_view);
        mActionBarCustomView = (ViewGroup) mActionBar.getCustomView();

        // Account spinner
        mAccountSpinnerContainer =
                UiUtilities.getView(mActionBarCustomView, R.id.account_spinner_container);
        mAccountSpinner = UiUtilities.getView(mActionBarCustomView, R.id.account_spinner);
        mAccountSpinnerDefaultBackground = mAccountSpinner.getBackground();

        mAccountSpinnerLine1View = UiUtilities.getView(mActionBarCustomView, R.id.spinner_line_1);
        mAccountSpinnerLine2View = UiUtilities.getView(mActionBarCustomView, R.id.spinner_line_2);
        mAccountSpinnerCountView = UiUtilities.getView(mActionBarCustomView, R.id.spinner_count);

        // Account dropdown
        mAccountDropdown = new AccountDropdownPopup(mContext);
        mAccountDropdown.setAdapter(mAccountsSelectorAdapter);

        mAccountSpinner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (mAccountsSelectorAdapter.getCount() > 0) {
                    mAccountDropdown.show();
                }
            }
        });
        // this other click listener handles clicks that ought to be aliased to "up"
        // only one or the other listener should be active at any time
        mActionBarCustomView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCallback.onUpPressed();
            }
        });
        // pick a sane default. later enabled in updateTitle().
        mActionBarCustomView.setClickable(false);
    }

    private void initSearchViews() {
        if (mSearchContainer == null) {
            final LayoutInflater inflater = LayoutInflater.from(mContext);
            mSearchContainer = inflater.inflate(R.layout.action_bar_search, null);
            mSearchView = UiUtilities.getView(mSearchContainer, R.id.search_view);
            mSearchView.setSubmitButtonEnabled(false);
            mSearchView.setOnQueryTextListener(mOnQueryText);
            mSearchView.onActionViewExpanded();
            mActionBarCustomView.addView(mSearchContainer);
        }
    }


    /** Must be called from {@link UIControllerBase#onActivityCreated()} */
    public void onActivityCreated() {
        refresh();
    }

    /** Must be called from {@link UIControllerBase#onActivityDestroy()} */
    public void onActivityDestroy() {
        if (mAccountDropdown.isShowing()) {
            mAccountDropdown.dismiss();
        }
    }

    /** Must be called from {@link UIControllerBase#onSaveInstanceState} */
    public void onSaveInstanceState(Bundle outState) {
        mDelayedOperations.removeCallbacks(); // Remove all pending operations
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
    public boolean isInSearchMode() {
        return mSearchMode == MODE_SEARCH;
    }

    /**
     * @return Whether or not the search bar should be shown. This is a function of whether or not a
     *     search is active, and if the current layout supports it.
     */
    private boolean shouldShowSearchBar() {
        return isInSearchMode() && (mTitleMode != Callback.TITLE_MODE_MESSAGE_SUBJECT);
    }

    /**
     * Show the search box.
     *
     * @param initialQueryTerm if non-empty, set to the search box.
     */
    public void enterSearchMode(String initialQueryTerm) {
        initSearchViews();
        if (isInSearchMode()) {
            return;
        }
        if (!TextUtils.isEmpty(initialQueryTerm)) {
            mSearchView.setQuery(initialQueryTerm, false);
        } else {
            mSearchView.setQuery("", false);
        }
        mSearchView.setQueryHint(mCallback.getSearchHint());

        mSearchMode = MODE_SEARCH;

        // Focus on the search input box and throw up the IME if specified.
        // TODO: HACK. this is a workaround IME not popping up.
        mSearchView.setIconified(false);

        refresh();
        mCallback.onSearchStarted();
    }

    public void exitSearchMode() {
        if (!isInSearchMode()) {
            return;
        }
        mSearchMode = MODE_NORMAL;

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
        if (shouldShowSearchBar()) {
            exitSearchMode();
            return true;
        }
        return false;
    }

    /** Refreshes the action bar display. */
    public void refresh() {
        // The actual work is in refreshInernal(), but we don't call it directly here, because:
        // 1. refresh() is called very often.
        // 2. to avoid nested fragment transaction.
        //    refresh is often called during a fragment transaction, but updateTitle() may call
        //    a callback which would initiate another fragment transaction.
        mDelayedOperations.removeCallbacks(mRefreshRunnable);
        mDelayedOperations.post(mRefreshRunnable);
    }

    private final Runnable mRefreshRunnable = new Runnable() {
        @Override public void run() {
            refreshInernal();
        }
    };
    private void refreshInernal() {
        final boolean showUp = isInSearchMode() || mCallback.shouldShowUp();
        mActionBar.setDisplayOptions(showUp
                ? ActionBar.DISPLAY_HOME_AS_UP : 0, ActionBar.DISPLAY_HOME_AS_UP);

        final long accountId = mCallback.getUIAccountId();
        final long mailboxId = mCallback.getMailboxId();
        if ((mLastAccountIdForDirtyCheck != accountId)
                || (mLastMailboxIdForDirtyCheck != mailboxId)) {
            mLastAccountIdForDirtyCheck = accountId;
            mLastMailboxIdForDirtyCheck = mailboxId;

            if (accountId != Account.NO_ACCOUNT) {
                loadAccountMailboxInfo(accountId, mailboxId);
            }
        }

        updateTitle();
    }

    /**
     * Load account/mailbox info, and account/recent mailbox list.
     */
    private void loadAccountMailboxInfo(final long accountId, final long mailboxId) {
        mLoaderManager.restartLoader(LOADER_ID_ACCOUNT_LIST, null,
                new LoaderCallbacks<Cursor>() {
            @Override
            public Loader<Cursor> onCreateLoader(int id, Bundle args) {
                return AccountSelectorAdapter.createLoader(mContext, accountId, mailboxId);
            }

            @Override
            public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
                mCursor = (AccountSelectorAdapter.CursorWithExtras) data;
                updateTitle();
            }

            @Override
            public void onLoaderReset(Loader<Cursor> loader) {
                mCursor = null;
                updateTitle();
            }
        });
    }

    /**
     * Update the "title" part.
     */
    private void updateTitle() {
        mAccountsSelectorAdapter.swapCursor(mCursor);

        enterCustomActionBarMode();
        if (mCursor == null) {
            // Initial load not finished.
            mActionBarCustomView.setVisibility(View.GONE);
            return;
        }
        mActionBarCustomView.setVisibility(View.VISIBLE);

        if (mCursor.getAccountCount() == 0) {
            mCallback.onNoAccountsFound();
            return;
        }

        if ((mCursor.getAccountId() != Account.NO_ACCOUNT) && !mCursor.accountExists()) {
            // Account specified, but does not exist.
            if (isInSearchMode()) {
                exitSearchMode();
            }

            // Switch to the default account.
            mCallback.onAccountSelected(Account.getDefaultAccountId(mContext));
            return;
        }

        mTitleMode = mCallback.getTitleMode();

        if (shouldShowSearchBar()) {
            initSearchViews();
            // In search mode, the search box is a replacement of the account spinner, so ignore
            // the work needed to update that. It will get updated when it goes visible again.
            mAccountSpinnerContainer.setVisibility(View.GONE);
            mSearchContainer.setVisibility(View.VISIBLE);
            return;
        }

        // Account spinner visible.
        mAccountSpinnerContainer.setVisibility(View.VISIBLE);
        UiUtilities.setVisibilitySafe(mSearchContainer, View.GONE);

        if (mTitleMode == Callback.TITLE_MODE_MESSAGE_SUBJECT) {
            // Use two line title action bar mode
            enterMultiLineTitleActionBarMode();
            String subject = mCallback.getMessageSubject();
            if (subject == null) {
                subject = "";
            }
            final SpannableString title = new SpannableString(subject);
            final SpannableStringBuilder builder = new SpannableStringBuilder();
            title.setSpan(new TextAppearanceSpan(mContext, R.style.subject_action_bar_title_text),
                    0, subject.length(), 0);
            builder.append(title);
            mActionBar.setTitle(builder);
            mActionBar.setSubtitle(null);
        } else if (mTitleMode == Callback.TITLE_MODE_ACCOUNT_WITH_ALL_FOLDERS_LABEL) {
            enterSingleLineTitleActionBarMode();
            mActionBar.setTitle(mAllFoldersLabel);
            mActionBar.setSubtitle(mCursor.getAccountDisplayName());
        } else {
            // Get mailbox name
            final String mailboxName;
            if (mTitleMode == Callback.TITLE_MODE_ACCOUNT_WITH_MAILBOX) {
                mailboxName = mCursor.getMailboxDisplayName();
            } else {
                mailboxName = null;
            }

            // Note - setSingleLine is needed as well as setMaxLines since they set different
            // flags on the view.
            mAccountSpinnerLine1View.setSingleLine();
            mAccountSpinnerLine1View.setMaxLines(1);
            if (TextUtils.isEmpty(mailboxName)) {
                mAccountSpinnerLine1View.setText(mCursor.getAccountDisplayName());

                // Change the visibility of line 2, so line 1 will be vertically-centered.
                mAccountSpinnerLine2View.setVisibility(View.GONE);
            } else {
                mAccountSpinnerLine1View.setText(mailboxName);
                mAccountSpinnerLine2View.setVisibility(View.VISIBLE);
                mAccountSpinnerLine2View.setText(mCursor.getAccountDisplayName());
            }

            mAccountSpinnerCountView.setVisibility(View.VISIBLE);
            mAccountSpinnerCountView.setText(UiUtilities.getMessageCountForUi(
                    mContext, mCursor.getMailboxMessageCount(), true));
        }

        boolean spinnerEnabled =
            ((mTitleMode & TITLE_MODE_SPINNER_ENABLED) != 0) && mCursor.shouldEnableSpinner();

        setSpinnerEnabled(spinnerEnabled);
    }

    private void enterCustomActionBarMode() {
        mActionBar.setDisplayOptions(CUSTOM_ACTION_BAR_OPTIONS, ACTION_BAR_MASK);
    }

    private void enterMultiLineTitleActionBarMode() {
        mActionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_SHOW_TITLE
                | DISPLAY_TITLE_MULTIPLE_LINES, ACTION_BAR_MASK);
    }

    private void enterSingleLineTitleActionBarMode() {
        mActionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_SHOW_TITLE,
                ACTION_BAR_MASK);
    }

    private void setSpinnerEnabled(boolean enabled) {
        if (enabled == mAccountSpinner.isEnabled()) {
            return;
        }

        mAccountSpinner.setEnabled(enabled);
        mAccountSpinner.setClickable(enabled);
        mActionBarCustomView.setClickable(!enabled);
        if (enabled) {
            mAccountSpinner.setBackgroundDrawable(mAccountSpinnerDefaultBackground);
        } else {
            mAccountSpinner.setBackgroundDrawable(null);
        }

        // For some reason, changing the background mucks with the padding so we have to manually
        // reset vertical padding here (also specified in XML, but it seems to be ignored for
        // some reason.
        mAccountSpinner.setPadding(
                mAccountSpinner.getPaddingLeft(),
                0,
                mAccountSpinner.getPaddingRight(),
                0);
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

    private void onAccountSpinnerItemClicked(int position) {
        if (mAccountsSelectorAdapter == null) { // just in case...
            return;
        }
        final long accountId = mAccountsSelectorAdapter.getAccountId(position);

        if (mAccountsSelectorAdapter.isAccountItem(position)) {
            mCallback.onAccountSelected(accountId);
        } else if (mAccountsSelectorAdapter.isMailboxItem(position)) {
            mCallback.onMailboxSelected(accountId,
                    mAccountsSelectorAdapter.getId(position));
        }
    }

    // Based on Spinner.DropdownPopup
    private class AccountDropdownPopup extends ListPopupWindow {
        public AccountDropdownPopup(Context context) {
            super(context);
            setAnchorView(mAccountSpinner);
            setModal(true);
            setPromptPosition(POSITION_PROMPT_ABOVE);
            setOnItemClickListener(new OnItemClickListener() {
                public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                    onAccountSpinnerItemClicked(position);
                    dismiss();
                }
            });
        }

        @Override
        public void show() {
            setWidth(mContext.getResources().getDimensionPixelSize(
                    R.dimen.account_dropdown_dropdownwidth));
            setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED);
            super.show();
            // List view is instantiated in super.show(), so we need to do this after...
            getListView().setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        }
    }
}
