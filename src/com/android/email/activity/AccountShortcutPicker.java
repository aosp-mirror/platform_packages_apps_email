/*
 * Copyright (C) 2008 The Android Open Source Project
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
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;

import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.AdapterView.OnItemClickListener;

/**
 *
 * This class implements a launcher shortcut for directly accessing a single account.
 *
 * This is simply a lightweight version of Accounts, and should almost certainly be merged with it
 * (or, one could be a base class of the other).
 */
public class AccountShortcutPicker extends ListActivity implements OnItemClickListener {

    /**
     * Support for list adapter
     */
    private final static String[] FROM_COLUMNS = new String[] {
            EmailContent.AccountColumns.DISPLAY_NAME,
            EmailContent.AccountColumns.EMAIL_ADDRESS,
            EmailContent.RECORD_ID
    };
    private final static int[] TO_IDS = new int[] {
            R.id.description,
            R.id.email,
            R.id.new_message_count
    };

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // finish() immediately if we aren't supposed to be here
        if (!Intent.ACTION_CREATE_SHORTCUT.equals(getIntent().getAction())) {
            finish();
            return;
        }

        // finish() immediately if no accounts are configured
        // TODO: lightweight projection with only those columns needed for this display
        // TODO: query outside of UI thread
        Cursor c = this.managedQuery(
                EmailContent.Account.CONTENT_URI,
                EmailContent.Account.CONTENT_PROJECTION,
                null, null, null);
        if (c.getCount() == 0) {
            finish();
            return;
        }

        setContentView(R.layout.accounts);
        ListView listView = getListView();
        listView.setOnItemClickListener(this);
        listView.setItemsCanFocus(false);
        listView.setEmptyView(findViewById(R.id.empty));

        AccountsAdapter a = new AccountsAdapter(this,
                R.layout.accounts_item, c, FROM_COLUMNS, TO_IDS);
        listView.setAdapter(a);
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Cursor cursor = (Cursor)parent.getItemAtPosition(position);
        Account account = new Account().restore(cursor);
        setupShortcut(account);
        finish();
    }

    private static class AccountsAdapter extends SimpleCursorAdapter {

        public AccountsAdapter(Context context, int layout, Cursor c, String[] from, int[] to) {
            super(context, layout, c, from, to);
            setViewBinder(new MyViewBinder());
        }

        /**
         * This is only used for the unread messages count.  Most of the views are handled
         * normally by SimpleCursorAdapter.
         */
        private static class MyViewBinder implements SimpleCursorAdapter.ViewBinder {

            public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
                if (view.getId() == R.id.new_message_count) {

                    int unreadMessageCount = 0;     // TODO get unread count from Account
                    if (unreadMessageCount <= 0) {
                        view.setVisibility(View.GONE);
                    } else {
                        ((TextView)view).setText(String.valueOf(unreadMessageCount));
                    }
                    return true;
                }

                return false;
            }
        }
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
        // First, set up the shortcut intent.

        Intent shortcutIntent = MessageList.createAccountIntentForShortcut(
                this, account, Mailbox.TYPE_INBOX);

        // Then, set up the container intent (the response to the caller)

        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, account.getDisplayName());
        Parcelable iconResource = Intent.ShortcutIconResource.fromContext(this, R.drawable.icon);
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconResource);

        // Now, return the result to the launcher

        setResult(RESULT_OK, intent);
    }


}


