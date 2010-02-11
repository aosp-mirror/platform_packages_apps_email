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
import com.android.email.LegacyConversions;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.mail.Folder;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;
import com.android.email.mail.store.LocalStore;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.AccountColumns;

import android.app.Activity;
import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * This activity will be used whenever we have a large/slow bulk upgrade operation.
 *
 * Note: It's preferable to check for "accounts needing upgrade" before launching this
 * activity, so as to not waste time before every launch.
 *
 * TODO: Disable orientation changes, to keep the activity from restarting on rotation.  This is
 *       set in the manifest but for some reason it's not working.
 * TODO: More work on actual conversions
 */
public class UpgradeAccounts extends ListActivity implements OnClickListener {

    private AccountInfo[] mLegacyAccounts;
    private UIHandler mHandler = new UIHandler();
    private AccountsAdapter mAdapter;
    private ListView mListView;
    private Button mProceedButton;
    private ConversionTask mConversionTask;
    
    /** This projection is for looking up accounts by their legacy UUID */
    private static final String WHERE_ACCOUNT_UUID_IS = AccountColumns.COMPATIBILITY_UUID + "=?";

    public static void actionStart(Activity fromActivity) {
        Intent i = new Intent(fromActivity, UpgradeAccounts.class);
        fromActivity.startActivity(i);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Preferences p = Preferences.getPreferences(this);
        loadAccountInfoArray(p.getAccounts());

        Log.d(Email.LOG_TAG, "*** Preparing to upgrade " +
                Integer.toString(mLegacyAccounts.length) + " accounts");

        setContentView(R.layout.upgrade_accounts);
        mListView = getListView();
        mProceedButton = (Button) findViewById(R.id.action_button);
        mProceedButton.setEnabled(false);
        mProceedButton.setOnClickListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateList();
        
        // Start the big conversion engine
        mConversionTask = new ConversionTask(mLegacyAccounts);
        mConversionTask.execute();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mConversionTask != null &&
                mConversionTask.getStatus() != ConversionTask.Status.FINISHED) {
            mConversionTask.cancel(true);
            mConversionTask = null;
        }
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.action_button:
                onClickOk();
                break;
        }
    }

    private void onClickOk() {
        Welcome.actionStart(UpgradeAccounts.this);
        finish();
    }

    private void updateList() {
        mAdapter = new AccountsAdapter();
        getListView().setAdapter(mAdapter);
    }

    private static class AccountInfo {
        Account account;
        int maxProgress;
        int progress;
        String error;
    }

    private void loadAccountInfoArray(Account[] legacyAccounts) {
        mLegacyAccounts = new AccountInfo[legacyAccounts.length];
        for (int i = 0; i < legacyAccounts.length; i++) {
            AccountInfo ai = new AccountInfo();
            ai.account = legacyAccounts[i];
            ai.maxProgress = 0;
            ai.progress = 0;
            ai.error = null;
            mLegacyAccounts[i] = ai;
        }
    }

    private static class ViewHolder {
        TextView displayName;
        ProgressBar progress;
        TextView errorReport;
    }

    class AccountsAdapter extends BaseAdapter {
        final LayoutInflater mInflater;
        
        AccountsAdapter() {
            mInflater = (LayoutInflater)getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
        
        public int getCount() {
            return mLegacyAccounts.length;
        }

        public Object getItem(int position) {
            return mLegacyAccounts[position];
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v;
            if (convertView == null) {
                v = newView(parent);
            } else {
                v = convertView;
            }
            bindView(v, position);
            return v;
        }
        
        public View newView(ViewGroup parent) {
            View v = mInflater.inflate(R.layout.upgrade_accounts_item, parent, false);
            ViewHolder h = new ViewHolder();
            h.displayName = (TextView) v.findViewById(R.id.name);
            h.progress = (ProgressBar) v.findViewById(R.id.progress);
            h.errorReport = (TextView) v.findViewById(R.id.error);
            v.setTag(h);
            return v;
        }
        
        public void bindView(View view, int position) {
            ViewHolder vh = (ViewHolder) view.getTag();
            AccountInfo ai = mLegacyAccounts[position];
            vh.displayName.setText(ai.account.getDescription());
            if (ai.error == null) {
                vh.errorReport.setVisibility(View.GONE);
                vh.progress.setVisibility(View.VISIBLE);
                vh.progress.setMax(ai.maxProgress);
                vh.progress.setProgress(ai.progress);
            } else {
                vh.progress.setVisibility(View.GONE);
                vh.errorReport.setVisibility(View.VISIBLE);
                vh.errorReport.setText(ai.error);
            }
        }
    }

    /**
     * Handler for updating UI from async workers
     *
     * TODO: I don't know the right paradigm for updating a progress bar in a ListView.  I'd
     * like to be able to say, "update it if it's visible, skip it if it's not visible."
     */
    class UIHandler extends Handler {
        private static final int MSG_SET_MAX = 1;
        private static final int MSG_SET_PROGRESS = 2;
        private static final int MSG_INC_PROGRESS = 3;
        private static final int MSG_ERROR = 4;

        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_SET_MAX:
                    mLegacyAccounts[msg.arg1].maxProgress = msg.arg2;
                    mListView.invalidateViews();        // find a less annoying way to do that
                    break;
                case MSG_SET_PROGRESS:
                    mLegacyAccounts[msg.arg1].progress = msg.arg2;
                    mListView.invalidateViews();        // find a less annoying way to do that
                    break;
                case MSG_INC_PROGRESS:
                    mLegacyAccounts[msg.arg1].progress++;
                    mListView.invalidateViews();        // find a less annoying way to do that
                    break;
                case MSG_ERROR:
                    mLegacyAccounts[msg.arg1].error = (String) msg.obj;
                    mListView.invalidateViews();        // find a less annoying way to do that
                    mProceedButton.setEnabled(true);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }

        public void setMaxProgress(int accountNum, int max) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_SET_MAX;
            msg.arg1 = accountNum;
            msg.arg2 = max;
            sendMessage(msg);
        }
            
        public void setProgress(int accountNum, int progress) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_SET_PROGRESS;
            msg.arg1 = accountNum;
            msg.arg2 = progress;
            sendMessage(msg);
        }

        public void incProgress(int accountNum) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_INC_PROGRESS;
            msg.arg1 = accountNum;
            sendMessage(msg);
        }

        // Note: also enables the "OK" button, so we pause when complete
        public void error(String error) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_ERROR;
            msg.obj = error;
            sendMessage(msg);
        }
    }

    /**
     * Everything above was UI plumbing.  This is the meat of this class - a conversion
     * engine to rebuild accounts from the "LocalStore" (pre Android 2.0) format to the
     * "Provider" (2.0 and beyond) format.
     */
    private class ConversionTask extends AsyncTask<Void, Void, Void> {
        UpgradeAccounts.AccountInfo[] mAccountInfo;
        final Context mContext;
        final Preferences mPreferences;
        
        public ConversionTask(UpgradeAccounts.AccountInfo[] accountInfo) {
            // TODO: should I copy this?
            mAccountInfo = accountInfo;
            mContext = UpgradeAccounts.this;
            mPreferences = Preferences.getPreferences(mContext);
        }

        @Override
        protected Void doInBackground(Void... params) {
            UIHandler handler = UpgradeAccounts.this.mHandler;
            // Step 1:  Analyze accounts and generate progress max values
            for (int i = 0; i < mAccountInfo.length; i++) {
                int estimate = UpgradeAccounts.estimateWork(mContext, mAccountInfo[i].account);
                UpgradeAccounts.this.mHandler.setMaxProgress(i, estimate);
            }
            
            // Step 2:  Clean out IMAP accounts
            for (int i = 0; i < mAccountInfo.length; i++) {
                if (mAccountInfo[i].error == null) {
                    cleanImapAccount(mContext, mAccountInfo[i].account, i, handler);
                }
            }

            // Step 3:  Copy accounts (and delete old accounts)
            for (int i = 0; i < mAccountInfo.length; i++) {
                if (mAccountInfo[i].error == null) {
                    copyAccount(mContext, mAccountInfo[i].account, i, handler);
                }
                deleteAccountStore(mContext, mAccountInfo[i].account, handler);
                mAccountInfo[i].account.delete(mPreferences);
                
                // reset the progress indicator to mark account "complete" (in case est was wrong)
                UpgradeAccounts.this.mHandler.setMaxProgress(i, 100);
                UpgradeAccounts.this.mHandler.setProgress(i, 100);
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (!isCancelled()) {
                // if there were no errors, we never enabled the OK button, but
                // we'll just proceed through anyway and return to the Welcome activity
                if (!mProceedButton.isEnabled()) {
                    onClickOk();
                }
            }
        }
    }

    /**
     * Estimate the work required to convert an account.
     * 1 (account) + # folders + # messages + # attachments
     */
    /* package */ static int estimateWork(Context context, Account account) {
        int estimate = 1;         // account
        try {
            Store store = LocalStore.newInstance(account.getLocalStoreUri(), context, null);
            Folder[] folders = store.getPersonalNamespaces();
            estimate += folders.length;
            for (int i = 0; i < folders.length; i++) {
                Folder folder = folders[i];
                folder.open(Folder.OpenMode.READ_ONLY, null);
                estimate += folder.getMessageCount();
            }
            estimate += ((LocalStore)store).getStoredAttachmentCount();
        
        } catch (MessagingException e) {
            Log.d(Email.LOG_TAG, "Exception while estimating account size " + e);
        }
        return estimate;
    }

    /**
     * Clean out an IMAP account.  Anything we can reload from server, we delete.  This seems
     * drastic, but it greatly reduces the risk of running out of disk space by copying everything.
     */
    /* package */ void cleanImapAccount(Context context, Account account, int accountNum,
            UIHandler handler) {
        String storeUri = account.getStoreUri();
        if (!storeUri.startsWith(Store.STORE_SCHEME_IMAP)) {
            return;
        }
        if (handler != null) {
            handler.incProgress(accountNum);
        }
        
    }
    
    /**
     * Copy an account.
     */
    /* package */ void copyAccount(Context context, Account account, int accountNum,
            UIHandler handler) {
        // If already exists- just skip it
        int existCount = EmailContent.count(context, EmailContent.Account.CONTENT_URI,
                WHERE_ACCOUNT_UUID_IS, new String[] { account.getUuid() });
        if (existCount > 0) {
            Log.d(Email.LOG_TAG, "No conversion, account exists: " + account.getDescription());
            if (handler != null) {
                handler.error(context.getString(R.string.upgrade_accounts_error));
            }
            return;
        }
        // Create the new account and write it
        EmailContent.Account newAccount = LegacyConversions.makeAccount(context, account);
        newAccount.save(context);
        if (handler != null) {
            handler.incProgress(accountNum);
        }
        
        // TODO folders
        // TODO messages
        // TODO attachments
    }

    /**
     * Delete an account
     */
    /* package */ void deleteAccountStore(Context context, Account account, UIHandler handler) {
        try {
            Store store = LocalStore.newInstance(account.getLocalStoreUri(), context, null);
            store.delete();
        } catch (MessagingException e) {
            Log.d(Email.LOG_TAG, "Exception while deleting account " + e);
            if (handler != null) {
                handler.error(context.getString(R.string.upgrade_accounts_error));
            }
        }
    }

}
