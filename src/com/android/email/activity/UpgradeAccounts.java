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
import com.android.email.Utility;
import com.android.email.activity.setup.AccountSettingsUtils;
import com.android.email.activity.setup.AccountSettingsUtils.Provider;
import com.android.email.mail.FetchProfile;
import com.android.email.mail.Flag;
import com.android.email.mail.Folder;
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Part;
import com.android.email.mail.Store;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.mail.store.LocalStore;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.provider.EmailContent.Mailbox;

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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * This activity will be used whenever we have a large/slow bulk upgrade operation.
 *
 * The general strategy is to iterate through the legacy accounts, convert them one-by-one, and
 * then delete them.  The loop is very conservative;  If there is any problem, the bias will be
 * to abandon the conversion and let the account be deleted.  We never want to get stuck here, and
 * we never want to run more than once (on a device being upgraded from 1.6).  After this code
 * runs, there should be zero legacy accounts.
 *
 * Note: It's preferable to check for "accounts needing upgrade" before launching this
 * activity, so as to not waste time before every launch.
 *
 * Note:  This activity is set (in the manifest) to disregard configuration changes (e.g. rotation).
 * This allows it to continue through without restarting.
 * Do not attempt to define orientation-specific resources, they won't be loaded.
 *
 * TODO: Read pending events and convert them to things like updates or deletes in the DB
 * TODO: Smarter cleanup of SSL/TLS situation, since certificates may be bad (see design spec)
 */
public class UpgradeAccounts extends ListActivity implements OnClickListener {

    /** DO NOT CHECK IN AS 'TRUE' - DEVELOPMENT ONLY */
    private static final boolean DEBUG_FORCE_UPGRADES = false;

    private AccountInfo[] mLegacyAccounts;
    private UIHandler mHandler = new UIHandler();
    private AccountsAdapter mAdapter;
    private ListView mListView;
    private Button mProceedButton;
    private ConversionTask mConversionTask;

    // These are used to hold off restart of this activity while worker is still busy
    private static final Object sConversionInProgress = new Object();
    private static boolean sConversionHasRun = false;
    
    /** This projection is for looking up accounts by their legacy UUID */
    private static final String WHERE_ACCOUNT_UUID_IS = AccountColumns.COMPATIBILITY_UUID + "=?";

    public static void actionStart(Context context) {
        Intent i = new Intent(context, UpgradeAccounts.class);
        context.startActivity(i);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Preferences p = Preferences.getPreferences(this);
        Account[] legacyAccounts = p.getAccounts();
        if (legacyAccounts.length == 0) {
            finish();
            return;
        }
        loadAccountInfoArray(legacyAccounts);

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

        Utility.cancelTask(mConversionTask, false); // false = Don't interrupt running task
        mConversionTask = null;
    }

    /**
     * Stopgap measure to prevent monkey or zealous user from exiting while we're still at work.
     */
    @Override
    public void onBackPressed() {
        if (!mProceedButton.isEnabled()) {
            finish();
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
        String errorMessage;    // set/read by handler - UI thread only
        boolean isError;        // set/read by worker thread

        public AccountInfo(Account legacyAccount) {
            account = legacyAccount;
            maxProgress = 0;
            progress = 0;
            errorMessage = null;
            isError = false;
        }
    }

    private void loadAccountInfoArray(Account[] legacyAccounts) {
        mLegacyAccounts = new AccountInfo[legacyAccounts.length];
        for (int i = 0; i < legacyAccounts.length; i++) {
            AccountInfo ai = new AccountInfo(legacyAccounts[i]);
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
            if (ai.errorMessage == null) {
                vh.errorReport.setVisibility(View.GONE);
                vh.progress.setVisibility(View.VISIBLE);
                vh.progress.setMax(ai.maxProgress);
                vh.progress.setProgress(ai.progress);
            } else {
                vh.progress.setVisibility(View.GONE);
                vh.errorReport.setVisibility(View.VISIBLE);
                vh.errorReport.setText(ai.errorMessage);
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
                    mLegacyAccounts[msg.arg1].errorMessage = (String) msg.obj;
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
        public void error(int accountNum, String error) {
            android.os.Message msg = android.os.Message.obtain();
            msg.what = MSG_ERROR;
            msg.arg1 = accountNum;
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
            // Globally synchronize this entire block to prevent it from running in multiple
            // threads.  this is used in case we wind up relaunching during a conversion.
            // If this is anything but the first thread, sConversionHasRun will be set and we'll
            // exit immediately when it's all over.
            synchronized (sConversionInProgress) {
                if (sConversionHasRun) {
                    return null;
                }
                sConversionHasRun = true;

                UIHandler handler = UpgradeAccounts.this.mHandler;
                // Step 1:  Analyze accounts and generate progress max values
                for (int i = 0; i < mAccountInfo.length; i++) {
                    int estimate = UpgradeAccounts.estimateWork(mContext, mAccountInfo[i].account);
                    if (estimate == -1) {
                        mAccountInfo[i].isError = true;
                        mHandler.error(i, mContext.getString(R.string.upgrade_accounts_error));
                    }
                    UpgradeAccounts.this.mHandler.setMaxProgress(i, estimate);
                }

                // Step 2:  Scrub accounts, deleting anything we're not keeping to reclaim storage
                for (int i = 0; i < mAccountInfo.length; i++) {
                    if (!mAccountInfo[i].isError) {
                        boolean ok = scrubAccount(mContext, mAccountInfo[i].account, i, handler);
                        if (!ok) {
                            mAccountInfo[i].isError = true;
                            mHandler.error(i, mContext.getString(R.string.upgrade_accounts_error));
                        }
                    }
                }

                // Step 3:  Copy accounts (and delete old accounts).  POP accounts first.
                // Note:  We don't check error condition here because we still want to
                // delete the remaining parts of all accounts (even if in error condition).
                for (int i = 0; i < mAccountInfo.length; i++) {
                    AccountInfo info = mAccountInfo[i];
                    copyAndDeleteAccount(info, i, handler, Store.STORE_SCHEME_POP3);
                }
                // IMAP accounts next.
                for (int i = 0; i < mAccountInfo.length; i++) {
                    AccountInfo info = mAccountInfo[i];
                    copyAndDeleteAccount(info, i, handler, Store.STORE_SCHEME_IMAP);
                }

                // Step 4:  Enable app-wide features such as composer, and start mail service(s)
                Email.setServicesEnabled(mContext);
            }

            return null;
        }

        /**
         * Copy and delete one account (helper for doInBackground).  Can select accounts by type
         * to force conversion of one or another type only.
         */
        private void copyAndDeleteAccount(AccountInfo info, int i, UIHandler handler, String type) {
            try {
                if (type != null) {
                    String storeUri = info.account.getStoreUri();
                    boolean isType = storeUri.startsWith(type);
                    if (!isType) {
                        return;         // skip this account
                    }
                }
                // Don't try copying if this account is already in error state
                if (!info.isError) {
                    copyAccount(mContext, info.account, i, handler);
                }
            } catch (RuntimeException e) {
                Log.d(Email.LOG_TAG, "Exception while copying account " + e);
                mHandler.error(i, mContext.getString(R.string.upgrade_accounts_error));
                info.isError = true;
            }
            // best effort to delete it (whether copied or not)
            try {
                deleteAccountStore(mContext, info.account, i, handler);
                info.account.delete(mPreferences);
            } catch (RuntimeException e) {
                Log.d(Email.LOG_TAG, "Exception while deleting account " + e);
                // No user notification is required here - we're done
            }
            // jam the progress indicator to mark account "complete" (in case est was wrong)
            handler.setProgress(i, Integer.MAX_VALUE);
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
     * @return conversion operations estimate, or -1 if there's any problem
     */
    /* package */ static int estimateWork(Context context, Account account) {
        int estimate = 1;         // account
        try {
            LocalStore store = LocalStore.newInstance(account.getLocalStoreUri(), context, null);
            Folder[] folders = store.getPersonalNamespaces();
            estimate += folders.length;
            for (int i = 0; i < folders.length; i++) {
                Folder folder = folders[i];
                folder.open(Folder.OpenMode.READ_ONLY, null);
                estimate += folder.getMessageCount();
                folder.close(false);
            }
            estimate += store.getStoredAttachmentCount();
            store.close();
        } catch (MessagingException e) {
            Log.d(Email.LOG_TAG, "Exception while estimating account size " + e);
            return -1;
        } catch (RuntimeException e) {
            Log.d(Email.LOG_TAG, "Exception while estimating account size " + e);
            return -1;
        }
        return estimate;
    }

    /**
     * Clean out an account.
     *
     * For IMAP:  Anything we can reload from server, we delete.  This reduces the risk of running
     * out of disk space by copying everything.
     * For POP: Delete the trash folder (which we won't bring forward).
     * @return true if successful, false if any kind of error
     */
    /* package */ static boolean scrubAccount(Context context, Account account, int accountNum,
            UIHandler handler) {
        try {
            String storeUri = account.getStoreUri();
            boolean isImap = storeUri.startsWith(Store.STORE_SCHEME_IMAP);
            LocalStore store = LocalStore.newInstance(account.getLocalStoreUri(), context, null);
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
                folder.close(false);
            }
            int pruned = store.pruneCachedAttachments();
            if (handler != null) {
                handler.incProgress(accountNum, pruned);
            }
            store.close();
        } catch (MessagingException e) {
            Log.d(Email.LOG_TAG, "Exception while scrubbing account " + e);
            return false;
        } catch (RuntimeException e) {
            Log.d(Email.LOG_TAG, "Exception while scrubbing account " + e);
            return false;
        }
        return true;
    }

    private static class FolderConversion {
        final Folder folder;
        final EmailContent.Mailbox mailbox;

        public FolderConversion(Folder _folder, EmailContent.Mailbox _mailbox) {
            folder = _folder;
            mailbox = _mailbox;
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
                handler.error(accountNum, context.getString(R.string.upgrade_accounts_error));
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
        
        // copy the folders, making a set of them as we go, and recording a few that we
        // need to process first (highest priority for saving the messages)
        HashSet<FolderConversion> conversions = new HashSet<FolderConversion>();
        FolderConversion drafts = null;
        FolderConversion outbox = null;
        FolderConversion sent = null;
        LocalStore store = null;
        try {
            store = LocalStore.newInstance(account.getLocalStoreUri(), context, null);
            Folder[] folders = store.getPersonalNamespaces();
            for (Folder folder : folders) {
                String folderName = null;
                try {
                    folder.open(Folder.OpenMode.READ_ONLY, null);
                    folderName = folder.getName();
                    Log.d(Email.LOG_TAG, "Copy " + account.getDescription() + "." + folderName);
                    EmailContent.Mailbox mailbox =
                        LegacyConversions.makeMailbox(context, newAccount, folder);
                    mailbox.save(context);
                    if (handler != null) {
                        handler.incProgress(accountNum);
                    }
                    folder.close(false);
                    // Now record the conversion, to come back and do the messages
                    FolderConversion conversion = new FolderConversion(folder, mailbox);
                    conversions.add(conversion);
                    switch (mailbox.mType) {
                        case Mailbox.TYPE_DRAFTS:
                            drafts = conversion;
                            break;
                        case Mailbox.TYPE_OUTBOX:
                            outbox = conversion;
                            break;
                        case Mailbox.TYPE_SENT:
                            sent = conversion;
                            break;
                    }
                } catch (MessagingException e) {
                    // We make a best-effort attempt at each folder, so even if this one fails,
                    // we'll try to keep going.
                    Log.d(Email.LOG_TAG, "Exception copying folder " + folderName + ": " + e);
                    if (handler != null) {
                        handler.error(accountNum,
                                context.getString(R.string.upgrade_accounts_error));
                    }
                }
            }

            // copy the messages, starting with the most critical folders, and then doing the rest
            // outbox & drafts are the most important, as they don't exist anywhere else.  we also
            // process local (outgoing) attachments here
            if (outbox != null) {
                copyMessages(context, outbox, true, newAccount, accountNum, handler);
                conversions.remove(outbox);
            }
            if (drafts != null) {
                copyMessages(context, drafts, true, newAccount, accountNum, handler);
                conversions.remove(drafts);
            }
            if (sent != null) {
                copyMessages(context, sent, true, newAccount, accountNum, handler);
                conversions.remove(outbox);
            }
            // Now handle any remaining folders.  For incoming folders we skip attachments, as they
            // can be reloaded from the server.
            for (FolderConversion conversion : conversions) {
                copyMessages(context, conversion, false, newAccount, accountNum, handler);
            }
        } catch (MessagingException e) {
            Log.d(Email.LOG_TAG, "Exception while copying folders " + e);
            // Couldn't copy folders at all
            if (handler != null) {
                handler.error(accountNum, context.getString(R.string.upgrade_accounts_error));
            }
        } finally {
            if (store != null) {
                store.close();
            }
        }
    }

    /**
     * Copy all messages in a given folder
     *
     * @param context a system context
     * @param conversion a folder->mailbox conversion record
     * @param localAttachments true if the attachments refer to local data (to be sent)
     * @param newAccount the id of the newly-created account
     * @param accountNum the UI list # of the account
     * @param handler the handler for updating the UI
     */
    /* package */ static void copyMessages(Context context, FolderConversion conversion,
            boolean localAttachments, EmailContent.Account newAccount, int accountNum,
            UIHandler handler) {
        try {
            boolean makeDeleteSentinels = (conversion.mailbox.mType == Mailbox.TYPE_INBOX) &&
                    (newAccount.getDeletePolicy() == EmailContent.Account.DELETE_POLICY_NEVER);
            conversion.folder.open(Folder.OpenMode.READ_ONLY, null);
            Message[] oldMessages = conversion.folder.getMessages(null);
            for (Message oldMessage : oldMessages) {
                Exception e = null;
                try {
                    // load message data from legacy Store
                    FetchProfile fp = new FetchProfile();
                    fp.add(FetchProfile.Item.ENVELOPE);
                    fp.add(FetchProfile.Item.BODY);
                    conversion.folder.fetch(new Message[] { oldMessage }, fp, null);
                    EmailContent.Message newMessage = new EmailContent.Message();
                    if (makeDeleteSentinels && oldMessage.isSet(Flag.DELETED)) {
                        // Special case for POP3 locally-deleted messages.
                        // Creates a local "deleted message sentinel" which hides the message
                        // Echos provider code in MessagingController.processPendingMoveToTrash()
                        newMessage.mAccountKey = newAccount.mId;
                        newMessage.mMailboxKey = conversion.mailbox.mId;
                        newMessage.mFlagLoaded = EmailContent.Message.FLAG_LOADED_DELETED;
                        newMessage.mFlagRead = true;
                        newMessage.mServerId = oldMessage.getUid();
                        newMessage.save(context);
                    } else {
                        // Main case for converting real messages with bodies & attachments
                        // convert message (headers)
                        LegacyConversions.updateMessageFields(newMessage, oldMessage,
                                newAccount.mId, conversion.mailbox.mId);
                        // convert body (text)
                        EmailContent.Body newBody = new EmailContent.Body();
                        ArrayList<Part> viewables = new ArrayList<Part>();
                        ArrayList<Part> attachments = new ArrayList<Part>();
                        MimeUtility.collectParts(oldMessage, viewables, attachments);
                        LegacyConversions.updateBodyFields(newBody, newMessage, viewables);
                        // commit changes so far so we have real id's
                        newMessage.save(context);
                        newBody.save(context);
                        // convert attachments
                        if (localAttachments) {
                            // These are references to local data, and should create records only
                            // (e.g. the content URI).  No files should be created.
                            LegacyConversions.updateAttachments(context, newMessage, attachments,
                                    true);
                        }
                    }
                    // done
                    if (handler != null) {
                        handler.incProgress(accountNum);
                    }
                } catch (MessagingException me) {
                    e = me;
                } catch (IOException ioe) {
                    e = ioe;
                }
                if (e != null) {
                    Log.d(Email.LOG_TAG, "Exception copying message " + oldMessage.getSubject()
                            + ": "+ e);
                    if (handler != null) {
                        handler.error(accountNum,
                                context.getString(R.string.upgrade_accounts_error));
                    }
                }
            }
        } catch (MessagingException e) {
            // Couldn't copy folder at all
            Log.d(Email.LOG_TAG, "Exception while copying messages in " +
                    conversion.folder.toString() + ": " + e);
            if (handler != null) {
                handler.error(accountNum, context.getString(R.string.upgrade_accounts_error));
            }
        }
    }

    /**
     * Delete an account
     */
    /* package */ static void deleteAccountStore(Context context, Account account, int accountNum,
            UIHandler handler) {
        try {
            Store store = LocalStore.newInstance(account.getLocalStoreUri(), context, null);
            store.delete();
            // delete() closes the store
        } catch (MessagingException e) {
            Log.d(Email.LOG_TAG, "Exception while deleting account " + e);
            if (handler != null) {
                handler.error(accountNum, context.getString(R.string.upgrade_accounts_error));
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

    /**
     * Bulk upgrade old accounts if exist.
     *
     * @return true if bulk upgrade has started.  false otherwise.
     */
    /* package */ static boolean doBulkUpgradeIfNecessary(Context context) {
        if (bulkUpgradesRequired(context, Preferences.getPreferences(context))) {
            UpgradeAccounts.actionStart(context);
            return true;
        }
        return false;
    }

    /**
     * Test for bulk upgrades and return true if necessary
     *
     * @return true if upgrades required (old accounts exist).  false otherwise.
     */
    private static boolean bulkUpgradesRequired(Context context, Preferences preferences) {
        if (DEBUG_FORCE_UPGRADES) {
            // build at least one fake account
            Account fake = new Account(context);
            fake.setDescription("Fake Account");
            fake.setEmail("user@gmail.com");
            fake.setName("First Last");
            fake.setSenderUri("smtp://user:password@smtp.gmail.com");
            fake.setStoreUri("imap://user:password@imap.gmail.com");
            fake.save(preferences);
            return true;
        }

        // 1. Get list of legacy accounts and look for any non-backup entries
        Account[] legacyAccounts = preferences.getAccounts();
        if (legacyAccounts.length == 0) {
            return false;
        }

        // 2. Look at the first legacy account and decide what to do
        // We only need to look at the first:  If it's not a backup account, then it's a true
        // legacy account, and there are one or more accounts needing upgrade.  If it is a backup
        // account, then we know for sure that there are no legacy accounts (backup deletes all
        // old accounts, and indicates that "modern" code has already run on this device.)
        if (0 != (legacyAccounts[0].getBackupFlags() & Account.BACKUP_FLAGS_IS_BACKUP)) {
            return false;
        } else {
            return true;
        }
    }
}
