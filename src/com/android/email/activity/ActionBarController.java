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

import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent.Account;

import android.app.ActionBar;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

/**
 * Manages the account name and the custom view part on the action bar.
 */
public class ActionBarController {
    private static final int LOADER_ID_ACCOUNT_LIST
            = EmailActivity.ACTION_BAR_CONTROLLER_LOADER_ID_BASE + 0;

    private final Context mContext;
    private final LoaderManager mLoaderManager;
    private final ActionBar mActionBar;

    private final View mActionBarMailboxNameView;
    private final TextView mActionBarMailboxName;
    private final TextView mActionBarUnreadCount;

    private final ActionBarNavigationCallback mActionBarNavigationCallback =
        new ActionBarNavigationCallback();

    private final AccountSelectorAdapter mAccountsSelectorAdapter;
    private Cursor mAccountsSelectorCursor;

    public final Callback mCallback;

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
         *
         * @param accountId ID of the selected account, or
         * {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
         */
        public void onAccountSelected(long accountId);

        /** Called when no accounts are found in the database. */
        public void onNoAccountsFound();
    }

    public ActionBarController(Context context, LoaderManager loaderManager,
            ActionBar actionBar, Callback callback) {
        mContext = context;
        mLoaderManager = loaderManager;
        mActionBar = actionBar;
        mCallback = callback;
        mAccountsSelectorAdapter = new AccountSelectorAdapter(mContext);

        mActionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE | ActionBar.DISPLAY_SHOW_HOME
                | ActionBar.DISPLAY_SHOW_CUSTOM);

        // The custom view for the current mailbox and the unread count.
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        mActionBarMailboxNameView = inflater.inflate(R.layout.action_bar_current_mailbox, null);
        final ActionBar.LayoutParams customViewLayout = new ActionBar.LayoutParams(
                ActionBar.LayoutParams.WRAP_CONTENT,
                ActionBar.LayoutParams.MATCH_PARENT);
        customViewLayout.setMargins(mContext.getResources().getDimensionPixelSize(
                        R.dimen.action_bar_mailbox_name_left_margin) , 0, 0, 0);
        mActionBar.setCustomView(mActionBarMailboxNameView, customViewLayout);

        mActionBarMailboxName = UiUtilities.getView(mActionBarMailboxNameView, R.id.mailbox_name);
        mActionBarUnreadCount = UiUtilities.getView(mActionBarMailboxNameView, R.id.unread_count);
    }

    /**
     * Must be called when the host activity is created.
     */
    public void onActivityCreated() {
        loadAccounts();
        refresh();
    }

    /** Used only in {@link #refresh()} to determine if the account has changed. */
    private long mLastAccountIdForDirtyCheck = -1;

    /**
     * Refresh the content.
     */
    public void refresh() {
        mActionBar.setDisplayOptions(mCallback.shouldShowUp()
                ? ActionBar.DISPLAY_HOME_AS_UP : 0, ActionBar.DISPLAY_HOME_AS_UP);

        mActionBarMailboxNameView.setVisibility(mCallback.shouldShowMailboxName()
                ? View.VISIBLE : View.GONE);

        mActionBarMailboxName.setText(mCallback.getCurrentMailboxName());

        // Note on action bar, we show only "unread count".  Some mailboxes such as Outbox don't
        // have the idea of "unread count", in which case we just omit the count.
        mActionBarUnreadCount.setText(UiUtilities.getMessageCountForUi(mContext,
                mCallback.getCurrentMailboxUnreadCount(), true));

        // Update the account list only when the account has changed.
        if (mLastAccountIdForDirtyCheck != mCallback.getUIAccountId()) {
            mLastAccountIdForDirtyCheck = mCallback.getUIAccountId();
            updateAccountList();
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
                return AccountSelectorAdapter.createLoader(mContext);
            }

            @Override
            public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
                mAccountsSelectorCursor = data;
                updateAccountList();
            }

            @Override
            public void onLoaderReset(Loader<Cursor> loader) {
                mAccountsSelectorCursor = null;
                updateAccountList();
            }
        });
    }

    /**
     * Called when the LOADER_ID_ACCOUNT_LIST loader loads the data.  Update the account spinner
     * on the action bar.
     */
    private void updateAccountList() {
        mAccountsSelectorAdapter.swapCursor(mAccountsSelectorCursor);

        final ActionBar ab = mActionBar;
        if (mAccountsSelectorCursor == null) {
            // Cursor not ready or closed.
            mAccountsSelectorAdapter.swapCursor(null);
            ab.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_TITLE);
            ab.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            return;
        }

        final int count = mAccountsSelectorCursor.getCount();
        if (count == 0) {
            mCallback.onNoAccountsFound();
            return;
        }

        // If only one acount, don't show the dropdown.
        if (count == 1) {
            mAccountsSelectorCursor.moveToFirst();

            // Show the account name as the title.
            ab.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE, ActionBar.DISPLAY_SHOW_TITLE);
            ab.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            ab.setTitle(AccountSelectorAdapter.getAccountDisplayName(mAccountsSelectorCursor));
            return;
        }

        // Find the currently selected account, and select it.
        int defaultSelection = 0;
        if (mCallback.isAccountSelected()) {
            final long accountId = mCallback.getUIAccountId();
            mAccountsSelectorCursor.moveToPosition(-1);
            int i = 0;
            while (mAccountsSelectorCursor.moveToNext()) {
                if (accountId == AccountSelectorAdapter.getAccountId(mAccountsSelectorCursor)) {
                    defaultSelection = i;
                    break;
                }
                i++;
            }
        }

        // Update the dropdown list.
        if (ab.getNavigationMode() != ActionBar.NAVIGATION_MODE_LIST) {
            ab.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_TITLE);
            ab.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
            ab.setListNavigationCallbacks(mAccountsSelectorAdapter, mActionBarNavigationCallback);
        }
        ab.setSelectedNavigationItem(defaultSelection);
    }

    private class ActionBarNavigationCallback implements ActionBar.OnNavigationListener {
        @Override
        public boolean onNavigationItemSelected(int itemPosition, long accountId) {
            if (accountId != mCallback.getUIAccountId()) {
                mCallback.onAccountSelected(accountId);
            }
            return true;
        }
    }
}
