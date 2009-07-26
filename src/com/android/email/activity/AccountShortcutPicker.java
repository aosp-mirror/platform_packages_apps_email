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

import com.android.email.Account;
import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;
import com.android.email.mail.store.LocalStore;
import com.android.email.mail.store.LocalStore.LocalFolder;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
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
    
    Account[] mAccounts;
    
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        // finish() immediately if we aren't supposed to be here
        if (!Intent.ACTION_CREATE_SHORTCUT.equals(getIntent().getAction())) {
            finish();
            return;
        }

        // finish() immediately if no accounts are configured
        mAccounts = Preferences.getPreferences(this).getAccounts();
        if (mAccounts.length == 0) {
            finish();
            return;
        }
        
        setContentView(R.layout.accounts);
        ListView listView = getListView();
        listView.setOnItemClickListener(this);
        listView.setItemsCanFocus(false);
        listView.setEmptyView(findViewById(R.id.empty));
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        getListView().setAdapter(new AccountsAdapter(mAccounts));
    }

    private void onOpenAccount(Account account) {
        // generate & return intents
        setupShortcut(account);
        finish();
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Account account = (Account)parent.getItemAtPosition(position);
        onOpenAccount(account);
    }

    class AccountsAdapter extends ArrayAdapter<Account> {
        public AccountsAdapter(Account[] accounts) {
            super(AccountShortcutPicker.this, 0, accounts);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Account account = getItem(position);
            View view;
            if (convertView != null) {
                view = convertView;
            }
            else {
                view = getLayoutInflater().inflate(R.layout.accounts_item, parent, false);
            }
            AccountViewHolder holder = (AccountViewHolder) view.getTag();
            if (holder == null) {
                holder = new AccountViewHolder();
                holder.description = (TextView) view.findViewById(R.id.description);
                holder.email = (TextView) view.findViewById(R.id.email);
                holder.newMessageCount = (TextView) view.findViewById(R.id.new_message_count);
                view.setTag(holder);
            }
            holder.description.setText(account.getDescription());
            holder.email.setText(account.getEmail());
            if (account.getEmail().equals(account.getDescription())) {
                holder.email.setVisibility(View.GONE);
            }
            int unreadMessageCount = 0;
            try {
                LocalStore localStore = (LocalStore) Store.getInstance(
                        account.getLocalStoreUri(), getApplication(), null);
                LocalFolder localFolder = (LocalFolder) localStore.getFolder(Email.INBOX);
                if (localFolder.exists()) {
                    unreadMessageCount = localFolder.getUnreadMessageCount();
                }
            }
            catch (MessagingException me) {
                /*
                 * This is not expected to fail under normal circumstances.
                 */
                throw new RuntimeException("Unable to get unread count from local store.", me);
            }
            holder.newMessageCount.setText(Integer.toString(unreadMessageCount));
            holder.newMessageCount.setVisibility(unreadMessageCount > 0 ? View.VISIBLE : View.GONE);
            return view;
        }

        class AccountViewHolder {
            public TextView description;
            public TextView email;
            public TextView newMessageCount;
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

        Intent shortcutIntent = FolderMessageList.actionHandleAccountUriIntent(this, 
                account, Email.INBOX);

        // Then, set up the container intent (the response to the caller)

        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, account.getDescription());
        Parcelable iconResource = Intent.ShortcutIconResource.fromContext(this, R.drawable.icon);
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconResource);

        // Now, return the result to the launcher

        setResult(RESULT_OK, intent);
    }


}


