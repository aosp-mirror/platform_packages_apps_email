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
import com.android.email.activity.setup.AccountSettingsUtils;
import com.android.email.activity.setup.AccountSettingsUtils.Provider;
import com.android.email.mail.Folder;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;
import com.android.email.mail.store.LocalStore;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.HostAuth;

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

import java.net.URI;
import java.net.URISyntaxException;

/**
 * This activity will be used whenever we have a large/slow bulk upgrade operation.
 *
 * Note: It's preferable to check for "accounts needing upgrade" before launching this
 * activity, so as to not waste time before every launch.
 *
 * Note:  This activity is set (in the manifest) to disregard configuration changes (e.g. rotation).
 * This allows it to continue through without restarting.
 * Do not attempt to define orientation-specific resources, they won't be loaded.
 *
 * TODO: More work on actual conversions
 * TODO: Confirm from donut sources the right way to ID the drafts, outbox, sent folders in IMAP
 * TODO: Smarter cleanup of SSL/TLS situation, since certificates may be bad (see design spec)
 * TODO: Trigger refresh after upgrade
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
                    mLegacyAccounts[msg.arg1].progress += msg.arg2;
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
            incProgress(accountNum, 1);
        }

        public void incProgress(int accountNum, int incrementBy) {
            if (incrementBy == 0) return;
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_INC_PROGRESS;
            msg.arg1 = accountNum;
            msg.arg2 = incrementBy;
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
            
            // Step 2:  Scrub accounts, deleting anything we're not keeping to reclaim disk space
            for (int i = 0; i < mAccountInfo.length; i++) {
                if (mAccountInfo[i].error == null) {
                    scrubAccount(mContext, mAccountInfo[i].account, i, handler);
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
     * Clean out an account.
     *
     * For IMAP:  Anything we can reload from server, we delete.  This reduces the risk of running
     * out of disk space by copying everything.
     * For POP: Delete the trash folder (which we won't bring forward).
     */
    /* package */ static void scrubAccount(Context context, Account account, int accountNum,
            UIHandler handler) {
        String storeUri = account.getStoreUri();
        boolean isImap = storeUri.startsWith(Store.STORE_SCHEME_IMAP);

        try {
            Store store = LocalStore.newInstance(account.getLocalStoreUri(), context, null);
            Folder[] folders = store.getPersonalNamespaces();
            for (Folder folder : folders) {
                folder.open(Folder.OpenMode.READ_ONLY, null);
                String folderName = folder.getName();
                if ("drafts".equalsIgnoreCase(folderName)) {
                    // do not delete drafts
                } else if ("outbox".equalsIgnoreCase(folderName)) {
                    // do not delete outbox
                } else if ("sent".equalsIgnoreCase(folderName)) {
                    // do not delete sent
                } else if (isImap || "trash".equalsIgnoreCase(folderName)) {
                    Log.d(Email.LOG_TAG, "Scrub " + account.getDescription() + "." + folderName);
                    // for all other folders, delete the folder (and its messages & attachments)
                    int messageCount = folder.getMessageCount();
                    folder.delete(true);
                    if (handler != null) {
                        handler.incProgress(accountNum, 1 + messageCount);
                    }
                }
            }
            int pruned = ((LocalStore)store).pruneCachedAttachments();
            if (handler != null) {
                handler.incProgress(accountNum, pruned);
            }
        } catch (MessagingException e) {
            Log.d(Email.LOG_TAG, "Exception while cleaning IMAP account " + e);
        }
    }
    
    /**
     * Copy an account.
     */
    /* package */ static void copyAccount(Context context, Account account, int accountNum,
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
        cleanupConnections(context, newAccount, account);
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
    /* package */ static void deleteAccountStore(Context context, Account account,
            UIHandler handler) {
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

    /**
     * Cleanup SSL, TLS, etc for each converted account.
     */
    /* package */ static void cleanupConnections(Context context, EmailContent.Account newAccount,
            Account account) {
        // 1. Look up provider for this email address
        String email = newAccount.mEmailAddress;
        int atSignPos = email.lastIndexOf('@');
        String domain = email.substring(atSignPos + 1);
        Provider p = AccountSettingsUtils.findProviderForDomain(context, domain);

        // 2. If provider found, just use its settings (overriding what user had)
        // This is drastic but most reliable.  Note:  This also benefits from newer provider
        // data that might be found in a vendor policy module.
        if (p != null) {
            // Incoming
            try {
                URI incomingUriTemplate = p.incomingUriTemplate;
                String incomingUsername = newAccount.mHostAuthRecv.mLogin;
                String incomingPassword = newAccount.mHostAuthRecv.mPassword;
                URI incomingUri = new URI(incomingUriTemplate.getScheme(), incomingUsername + ":"
                        + incomingPassword, incomingUriTemplate.getHost(),
                        incomingUriTemplate.getPort(), incomingUriTemplate.getPath(), null, null);
                newAccount.mHostAuthRecv.setStoreUri(incomingUri.toString());
            } catch (URISyntaxException e) {
                // Ignore - just use the data we copied across (for better or worse)
            }
            // Outgoing
            try {
                URI outgoingUriTemplate = p.outgoingUriTemplate;
                String outgoingUsername = newAccount.mHostAuthSend.mLogin;
                String outgoingPassword = newAccount.mHostAuthSend.mPassword;
                URI outgoingUri = new URI(outgoingUriTemplate.getScheme(), outgoingUsername + ":"
                        + outgoingPassword, outgoingUriTemplate.getHost(),
                        outgoingUriTemplate.getPort(), outgoingUriTemplate.getPath(), null, null);
                newAccount.mHostAuthSend.setStoreUri(outgoingUri.toString());
            } catch (URISyntaxException e) {
                // Ignore - just use the data we copied across (for better or worse)
            }
            Log.d(Email.LOG_TAG, "Rewriting connection details for " + account.getDescription());
            return;
        }

        // 3. Otherwise, use simple heuristics to adjust connection and attempt to keep it
        //    reliable.  NOTE:  These are the "legacy" ssl/tls encodings, not the ones in
        //    the current provider.
        newAccount.mHostAuthRecv.mFlags |= HostAuth.FLAG_TRUST_ALL_CERTIFICATES;
        String receiveUri = account.getStoreUri();
        if (receiveUri.contains("+ssl+")) {
            // non-optional SSL - keep as is, with trust all
        } else if (receiveUri.contains("+ssl")) {
            // optional SSL - TBD
        } else if (receiveUri.contains("+tls+")) {
            // non-optional TLS - keep as is, with trust all
        } else if (receiveUri.contains("+tls")) {
            // optional TLS - TBD
        }
        newAccount.mHostAuthSend.mFlags |= HostAuth.FLAG_TRUST_ALL_CERTIFICATES;
        String sendUri = account.getSenderUri();
        if (sendUri.contains("+ssl+")) {
            // non-optional SSL - keep as is, with trust all
        } else if (sendUri.contains("+ssl")) {
            // optional SSL - TBD
        } else if (sendUri.contains("+tls+")) {
            // non-optional TLS - keep as is, with trust all
        } else if (sendUri.contains("+tls")) {
            // optional TLS - TBD
        }
    }
}
