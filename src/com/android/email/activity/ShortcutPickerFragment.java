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
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.AdapterView.OnItemClickListener;

// TODO restructure this into three classes -- a base class w/ two sub-classes. Intead of using
// selectingMailbox(), we'd just define the proper methods in the sub-class.

/**
 * Fragment containing a list of accounts to show during shortcut creation.
 */
public abstract class ShortcutPickerFragment extends ListFragment
        implements OnItemClickListener, LoaderCallbacks<Cursor> {
    /**
     * If true, creates pre-honeycomb style shortcuts. This allows developers to test launching
     * the app from old style shortcuts (which point sat MessageList rather than Welcome) without
     * actually carrying over shortcuts from previous versions.
     */
    private final static boolean TEST_CREATE_OLD_STYLE_SHORTCUT = false; // DO NOT SUBMIT WITH TRUE
    private final static int LOADER_ID = 0;
    private final static int[] TO_VIEWS = new int[] {
        android.R.id.text1,
    };

    /** Cursor adapter that provides either the account or mailbox list */
    private SimpleCursorAdapter mAdapter;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        final String[] fromColumns = getFromColumns();
        mAdapter = new SimpleCursorAdapter(activity,
            android.R.layout.simple_expandable_list_item_1, null, fromColumns, TO_VIEWS, 0);
        setListAdapter(mAdapter);

        getLoaderManager().initLoader(LOADER_ID, null, this);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        ListView listView = getListView();
        listView.setOnItemClickListener(this);
        listView.setItemsCanFocus(false);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        // No accounts; close the dialog
        if (data.getCount() == 0) {
            // TODO what's the proper handling if the mailbox list is '0'? display toast?
            getActivity().finish();
            return;
        }
        // TODO special handle case where there is one account or one mailbox; the user shouldn't
        //      be forced to select anything in either of those cases.
        mAdapter.swapCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
    }

    /** Returns the cursor columns to map into list */
    abstract String[] getFromColumns();

    /**
     * This function creates a shortcut and returns it to the caller.  There are actually two
     * intents that you will send back.
     *
     * The first intent serves as a container for the shortcut and is returned to the launcher by
     * setResult().  This intent must contain three fields:
     *
     * <ul>
     * <li>{@link android.content.Intent#EXTRA_SHORTCUT_INTENT} The shortcut intent.</li>
     * <li>{@link android.content.Intent#EXTRA_SHORTCUT_NAME} The text that will be displayed with
     * the shortcut.</li>
     * <li>{@link android.content.Intent#EXTRA_SHORTCUT_ICON} The shortcut's icon, if provided as a
     * bitmap, <i>or</i> {@link android.content.Intent#EXTRA_SHORTCUT_ICON_RESOURCE} if provided as
     * a drawable resource.</li>
     * </ul>
     *
     * If you use a simple drawable resource, note that you must wrapper it using
     * {@link android.content.Intent.ShortcutIconResource}, as shown below.  This is required so
     * that the launcher can access resources that are stored in your application's .apk file.  If
     * you return a bitmap, such as a thumbnail, you can simply put the bitmap into the extras
     * bundle using {@link android.content.Intent#EXTRA_SHORTCUT_ICON}.
     *
     * The shortcut intent can be any intent that you wish the launcher to send, when the user
     * clicks on the shortcut.  Typically this will be {@link android.content.Intent#ACTION_VIEW}
     * with an appropriate Uri for your content, but any Intent will work here as long as it
     * triggers the desired action within your Activity.
     */
    void setupShortcut(Account account, long mailboxId) {
        Activity myActivity = getActivity();
        // First, set up the shortcut intent.
        final Intent shortcutIntent;
        if (TEST_CREATE_OLD_STYLE_SHORTCUT) {
            shortcutIntent = MessageList.createFroyoIntent(myActivity, account);
            Log.d(Logging.LOG_TAG, "Created old style intent: " + shortcutIntent);
        } else {
            String uuid = account.mCompatibilityUuid;
            shortcutIntent = Welcome.createAccountShortcutIntent(myActivity, uuid, mailboxId);
        }

        // Then, set up the container intent (the response to the caller)
        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, account.getDisplayName());
        Parcelable iconResource
                = Intent.ShortcutIconResource.fromContext(myActivity, R.mipmap.ic_launcher_email);
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconResource);

        // Now, return the result to the launcher

        myActivity.setResult(Activity.RESULT_OK, intent);
    }

    /** Account picker */
    public static class AccountShortcutPickerFragment extends ShortcutPickerFragment {
        private final static String[] ACCOUNT_FROM_COLUMNS = new String[] {
            AccountColumns.DISPLAY_NAME,
        };

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            getActivity().setTitle(R.string.account_shortcut_picker_title);
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Cursor cursor = (Cursor) parent.getItemAtPosition(position);
            Account account = new Account();
            account.restore(cursor);
            ShortcutPickerFragment fragment = new MailboxShortcutPickerFragment();
            final Bundle args = new Bundle();
            args.putParcelable(MailboxShortcutPickerFragment.ARG_ACCOUNT, account);
            fragment.setArguments(args);
            getFragmentManager()
                .beginTransaction()
                    .replace(R.id.shortcut_list, fragment)
                    .addToBackStack(null)
                    .commit();
        }

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            Context context = getActivity();
            // TODO Add ability to insert special account "all accounts"
            return new CursorLoader(
                context, Account.CONTENT_URI, Account.CONTENT_PROJECTION, null, null, null);
        }

        @Override
        String[] getFromColumns() {
            return ACCOUNT_FROM_COLUMNS;
        }
    }

    /** Mailbox picker */
    public static class MailboxShortcutPickerFragment extends ShortcutPickerFragment {
        static final String ARG_ACCOUNT = "MailboxShortcutPickerFragment.account";
        private final static String[] MAILBOX_FROM_COLUMNS = new String[] {
            MailboxColumns.DISPLAY_NAME,
        };
        /** Loader projection used for IMAP & POP3 accounts */
        private final static String[] IMAP_PROJECTION = new String [] {
            MailboxColumns.ID, MailboxColumns.SERVER_ID + " as " + MailboxColumns.DISPLAY_NAME
        };
        /** Loader projection used for EAS accounts */
        private final static String[] EAS_PROJECTION = new String [] {
            MailboxColumns.ID, MailboxColumns.DISPLAY_NAME
        };
        // TODO This is identical to MailboxesAdapter#ALL_MAILBOX_SELECTION; any way we can create a
        // common selection? Move this to the Mailbox class?
        private final static String ALL_MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?" +
                " AND " + Mailbox.USER_VISIBLE_MAILBOX_SELECTION;

        /** The currently selected account */
        private Account mAccount;

        @Override
        public void onAttach(Activity activity) {
            // Need to setup the account first thing
            mAccount = getArguments().getParcelable(ARG_ACCOUNT);
            super.onAttach(activity);
        }

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            getActivity().setTitle(R.string.mailbox_shortcut_picker_title);
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Cursor cursor = (Cursor) parent.getItemAtPosition(position);
            long mailboxId = cursor.getLong(Mailbox.CONTENT_ID_COLUMN);
            setupShortcut(mAccount, mailboxId);
            getActivity().finish();
        }

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            Context context = getActivity();
            // TODO Add ability to insert special mailboxes like "starred", etc...
            // TODO Create a fully-qualified path name for Exchange accounts [code should also work
            //      for MoveMessageToDialog.java]
            HostAuth recvAuth = mAccount.getOrCreateHostAuthRecv(context);
            final String[] projection;
            final String orderBy;
            if (recvAuth.isEasConnection()) {
                projection = EAS_PROJECTION;
                orderBy = MailboxColumns.DISPLAY_NAME;
            } else {
                projection = IMAP_PROJECTION;
                orderBy = MailboxColumns.SERVER_ID;
            }
            return new CursorLoader(
                context, Mailbox.CONTENT_URI, projection, ALL_MAILBOX_SELECTION,
                new String[] { Long.toString(mAccount.mId) }, orderBy);
        }

        @Override
        String[] getFromColumns() {
            return MAILBOX_FROM_COLUMNS;
        }
    }
}
