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

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager.LoaderCallbacks;
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

/**
 * Fragment containing a list of accounts to show during shortcut creation.
 */
public class AccountShortcutPickerFragment extends ListFragment
        implements OnItemClickListener, LoaderCallbacks<Cursor> {
    /**
     * If true, creates pre-honeycomb style shortcuts. This allows developers to test launching
     * the app from old style shortcuts (which point sat MessageList rather than Welcome) without
     * actually carrying over shortcuts from previous versions.
     */
    private final static boolean TEST_CREATE_OLD_STYLE_SHORTCUT = false; // DO NOT SUBMIT WITH TRUE

    private final static String[] FROM_COLUMNS = new String[] {
            AccountColumns.DISPLAY_NAME,
            AccountColumns.EMAIL_ADDRESS,
    };
    private final static int[] TO_IDS = new int[] {
            R.id.description,
            R.id.email,
    };
    private SimpleCursorAdapter mAdapter;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        ListView listView = getListView();
        listView.setOnItemClickListener(this);
        listView.setItemsCanFocus(false);

        String[] fromColumns;
        fromColumns = FROM_COLUMNS;
        mAdapter = new SimpleCursorAdapter(getActivity(),
            R.layout.account_shortcut_picker_item, null, fromColumns, TO_IDS, 0);
        setListAdapter(mAdapter);

        getLoaderManager().initLoader(0, null, this);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Cursor cursor = (Cursor)parent.getItemAtPosition(position);
        Account account = new Account();
        account.restore(cursor);
        setupShortcut(account);
        getActivity().finish();
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return new CursorLoader(
            getActivity(), Account.CONTENT_URI, Account.CONTENT_PROJECTION, null, null, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        // No accounts; close the dialog
        if (data.getCount() == 0) {
            getActivity().finish();
            return;
        }
        mAdapter.swapCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
    }

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
    private void setupShortcut(Account account) {
        Activity myActivity = getActivity();
        // First, set up the shortcut intent.
        final Intent shortcutIntent;
        if (TEST_CREATE_OLD_STYLE_SHORTCUT) {
            shortcutIntent = MessageList.createFroyoIntent(myActivity, account);
            Log.d(Logging.LOG_TAG, "Created old style intent: " + shortcutIntent);
        } else {
            shortcutIntent = Welcome.createAccountShortcutIntent(myActivity, account);
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
}
