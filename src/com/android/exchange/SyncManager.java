/*
 * Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

package com.android.exchange;

import com.android.email.AccountBackupRestore;
import com.android.email.Email;
import com.android.email.SecurityPolicy;
import com.android.email.Utility;
import com.android.email.mail.MessagingException;
import com.android.email.mail.transport.SSLUtils;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.provider.EmailContent.HostAuthColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.SyncColumns;
import com.android.email.service.EmailServiceStatus;
import com.android.email.service.IEmailService;
import com.android.email.service.IEmailServiceCallback;
import com.android.exchange.adapter.CalendarSyncAdapter;
import com.android.exchange.utility.FileLogger;

import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.params.ConnManagerPNames;
import org.apache.http.conn.params.ConnPerRoute;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;

import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OnAccountsUpdateListener;
import android.accounts.OperationCanceledException;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SyncStatusObserver;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.Calendar;
import android.provider.Calendar.Calendars;
import android.provider.Calendar.Events;
import android.provider.ContactsContract;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The SyncManager handles all aspects of starting, maintaining, and stopping the various sync
 * adapters used by Exchange.  However, it is capable of handing any kind of email sync, and it
 * would be appropriate to use for IMAP push, when that functionality is added to the Email
 * application.
 *
 * The Email application communicates with EAS sync adapters via SyncManager's binder interface,
 * which exposes UI-related functionality to the application (see the definitions below)
 *
 * SyncManager uses ContentObservers to detect changes to accounts, mailboxes, and messages in
 * order to maintain proper 2-way syncing of data.  (More documentation to follow)
 *
 */
public class SyncManager extends Service implements Runnable {

    private static final String TAG = "EAS SyncManager";

    // The SyncManager's mailbox "id"
    protected static final int SYNC_MANAGER_ID = -1;
    protected static final int SYNC_MANAGER_SERVICE_ID = 0;

    private static final int SECONDS = 1000;
    private static final int MINUTES = 60*SECONDS;
    private static final int ONE_DAY_MINUTES = 1440;

    private static final int SYNC_MANAGER_HEARTBEAT_TIME = 15*MINUTES;
    private static final int CONNECTIVITY_WAIT_TIME = 10*MINUTES;

    // Sync hold constants for services with transient errors
    private static final int HOLD_DELAY_MAXIMUM = 4*MINUTES;

    // Reason codes when SyncManager.kick is called (mainly for debugging)
    // UI has changed data, requiring an upsync of changes
    public static final int SYNC_UPSYNC = 0;
    // A scheduled sync (when not using push)
    public static final int SYNC_SCHEDULED = 1;
    // Mailbox was marked push
    public static final int SYNC_PUSH = 2;
    // A ping (EAS push signal) was received
    public static final int SYNC_PING = 3;
    // startSync was requested of SyncManager
    public static final int SYNC_SERVICE_START_SYNC = 4;
    // A part request (attachment load, for now) was sent to SyncManager
    public static final int SYNC_SERVICE_PART_REQUEST = 5;
    // Misc.
    public static final int SYNC_KICK = 6;

    private static final String WHERE_PUSH_OR_PING_NOT_ACCOUNT_MAILBOX =
        MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.TYPE + "!=" +
        Mailbox.TYPE_EAS_ACCOUNT_MAILBOX + " and " + MailboxColumns.SYNC_INTERVAL +
        " IN (" + Mailbox.CHECK_INTERVAL_PING + ',' + Mailbox.CHECK_INTERVAL_PUSH + ')';
    protected static final String WHERE_IN_ACCOUNT_AND_PUSHABLE =
        MailboxColumns.ACCOUNT_KEY + "=? and type in (" + Mailbox.TYPE_INBOX + ','
        + Mailbox.TYPE_EAS_ACCOUNT_MAILBOX + ',' + Mailbox.TYPE_CONTACTS + ','
        + Mailbox.TYPE_CALENDAR + ')';
    protected static final String WHERE_IN_ACCOUNT_AND_TYPE_INBOX =
        MailboxColumns.ACCOUNT_KEY + "=? and type = " + Mailbox.TYPE_INBOX ;
    private static final String WHERE_MAILBOX_KEY = Message.MAILBOX_KEY + "=?";
    private static final String WHERE_PROTOCOL_EAS = HostAuthColumns.PROTOCOL + "=\"" +
        AbstractSyncService.EAS_PROTOCOL + "\"";
    private static final String WHERE_NOT_INTERVAL_NEVER_AND_ACCOUNT_KEY_IN =
        "(" + MailboxColumns.TYPE + '=' + Mailbox.TYPE_OUTBOX
        + " or " + MailboxColumns.SYNC_INTERVAL + "!=" + Mailbox.CHECK_INTERVAL_NEVER + ')'
        + " and " + MailboxColumns.ACCOUNT_KEY + " in (";
    private static final String ACCOUNT_KEY_IN = MailboxColumns.ACCOUNT_KEY + " in (";
    private static final String WHERE_CALENDAR_ID = Events.CALENDAR_ID + "=?";

    // Offsets into the syncStatus data for EAS that indicate type, exit status, and change count
    // The format is S<type_char>:<exit_char>:<change_count>
    public static final int STATUS_TYPE_CHAR = 1;
    public static final int STATUS_EXIT_CHAR = 3;
    public static final int STATUS_CHANGE_COUNT_OFFSET = 5;

    // Ready for ping
    public static final int PING_STATUS_OK = 0;
    // Service already running (can't ping)
    public static final int PING_STATUS_RUNNING = 1;
    // Service waiting after I/O error (can't ping)
    public static final int PING_STATUS_WAITING = 2;
    // Service had a fatal error; can't run
    public static final int PING_STATUS_UNABLE = 3;

    private static final int MAX_CLIENT_CONNECTION_MANAGER_SHUTDOWNS = 1;

    // We synchronize on this for all actions affecting the service and error maps
    private static final Object sSyncLock = new Object();
    // All threads can use this lock to wait for connectivity
    public static final Object sConnectivityLock = new Object();
    public static boolean sConnectivityHold = false;

    // Keeps track of running services (by mailbox id)
    private HashMap<Long, AbstractSyncService> mServiceMap =
        new HashMap<Long, AbstractSyncService>();
    // Keeps track of services whose last sync ended with an error (by mailbox id)
    /*package*/ HashMap<Long, SyncError> mSyncErrorMap = new HashMap<Long, SyncError>();
    // Keeps track of which services require a wake lock (by mailbox id)
    private HashMap<Long, Boolean> mWakeLocks = new HashMap<Long, Boolean>();
    // Keeps track of PendingIntents for mailbox alarms (by mailbox id)
    private HashMap<Long, PendingIntent> mPendingIntents = new HashMap<Long, PendingIntent>();
    // The actual WakeLock obtained by SyncManager
    private WakeLock mWakeLock = null;
    // Keep our cached list of active Accounts here
    public final AccountList mAccountList = new AccountList();

    // Observers that we use to look for changed mail-related data
    private Handler mHandler = new Handler();
    private AccountObserver mAccountObserver;
    private MailboxObserver mMailboxObserver;
    private SyncedMessageObserver mSyncedMessageObserver;
    private MessageObserver mMessageObserver;
    private EasSyncStatusObserver mSyncStatusObserver;
    private Object mStatusChangeListener;
    private EasAccountsUpdatedListener mAccountsUpdatedListener;

    // Concurrent because CalendarSyncAdapter can modify the map during a wipe
    private ConcurrentHashMap<Long, CalendarObserver> mCalendarObservers =
        new ConcurrentHashMap<Long, CalendarObserver>();

    private ContentResolver mResolver;

    // The singleton SyncManager object, with its thread and stop flag
    protected static SyncManager INSTANCE;
    private static Thread sServiceThread = null;
    // Cached unique device id
    private static String sDeviceId = null;
    // ConnectionManager that all EAS threads can use
    private static ClientConnectionManager sClientConnectionManager = null;
    // Count of ClientConnectionManager shutdowns
    private static volatile int sClientConnectionManagerShutdownCount = 0;

    private static volatile boolean sStop = false;

    // The reason for SyncManager's next wakeup call
    private String mNextWaitReason;
    // Whether we have an unsatisfied "kick" pending
    private boolean mKicked = false;

    // Receiver of connectivity broadcasts
    private ConnectivityReceiver mConnectivityReceiver = null;
    private ConnectivityReceiver mBackgroundDataSettingReceiver = null;
    private volatile boolean mBackgroundData = true;

    // The callback sent in from the UI using setCallback
    private IEmailServiceCallback mCallback;
    private RemoteCallbackList<IEmailServiceCallback> mCallbackList =
        new RemoteCallbackList<IEmailServiceCallback>();

    /**
     * Proxy that can be used by various sync adapters to tie into SyncManager's callback system.
     * Used this way:  SyncManager.callback().callbackMethod(args...);
     * The proxy wraps checking for existence of a SyncManager instance and an active callback.
     * Failures of these callbacks can be safely ignored.
     */
    static private final IEmailServiceCallback.Stub sCallbackProxy =
        new IEmailServiceCallback.Stub() {

        public void loadAttachmentStatus(long messageId, long attachmentId, int statusCode,
                int progress) throws RemoteException {
            IEmailServiceCallback cb = INSTANCE == null ? null: INSTANCE.mCallback;
            if (cb != null) {
                cb.loadAttachmentStatus(messageId, attachmentId, statusCode, progress);
            }
        }

        public void sendMessageStatus(long messageId, String subject, int statusCode, int progress)
                throws RemoteException {
            IEmailServiceCallback cb = INSTANCE == null ? null: INSTANCE.mCallback;
            if (cb != null) {
                cb.sendMessageStatus(messageId, subject, statusCode, progress);
            }
        }

        public void syncMailboxListStatus(long accountId, int statusCode, int progress)
                throws RemoteException {
            IEmailServiceCallback cb = INSTANCE == null ? null: INSTANCE.mCallback;
            if (cb != null) {
                cb.syncMailboxListStatus(accountId, statusCode, progress);
            }
        }

        public void syncMailboxStatus(long mailboxId, int statusCode, int progress)
                throws RemoteException {
            IEmailServiceCallback cb = INSTANCE == null ? null: INSTANCE.mCallback;
            if (cb != null) {
                cb.syncMailboxStatus(mailboxId, statusCode, progress);
            }
        }
    };

    /**
     * Create our EmailService implementation here.
     */
    private final IEmailService.Stub mBinder = new IEmailService.Stub() {

        public int validate(String protocol, String host, String userName, String password,
                int port, boolean ssl, boolean trustCertificates) throws RemoteException {
            try {
                AbstractSyncService.validate(EasSyncService.class, host, userName, password, port,
                        ssl, trustCertificates, SyncManager.this);
                return MessagingException.NO_ERROR;
            } catch (MessagingException e) {
                return e.getExceptionType();
            }
        }

        public Bundle autoDiscover(String userName, String password) throws RemoteException {
            return new EasSyncService().tryAutodiscover(userName, password);
        }

        public void startSync(long mailboxId) throws RemoteException {
            SyncManager syncManager = INSTANCE;
            if (syncManager == null) return;
            checkSyncManagerServiceRunning();
            Mailbox m = Mailbox.restoreMailboxWithId(syncManager, mailboxId);
            if (m == null) return;
            if (m.mType == Mailbox.TYPE_OUTBOX) {
                // We're using SERVER_ID to indicate an error condition (it has no other use for
                // sent mail)  Upon request to sync the Outbox, we clear this so that all messages
                // are candidates for sending.
                ContentValues cv = new ContentValues();
                cv.put(SyncColumns.SERVER_ID, 0);
                syncManager.getContentResolver().update(Message.CONTENT_URI,
                    cv, WHERE_MAILBOX_KEY, new String[] {Long.toString(mailboxId)});
                // Clear the error state; the Outbox sync will be started from checkMailboxes
                syncManager.mSyncErrorMap.remove(mailboxId);
                kick("start outbox");
                // Outbox can't be synced in EAS
                return;
            } else if (m.mType == Mailbox.TYPE_DRAFTS || m.mType == Mailbox.TYPE_TRASH) {
                // Drafts & Trash can't be synced in EAS
                return;
            }
            startManualSync(mailboxId, SyncManager.SYNC_SERVICE_START_SYNC, null);
        }

        public void stopSync(long mailboxId) throws RemoteException {
            stopManualSync(mailboxId);
        }

        public void loadAttachment(long attachmentId, String destinationFile,
                String contentUriString) throws RemoteException {
            Attachment att = Attachment.restoreAttachmentWithId(SyncManager.this, attachmentId);
            sendMessageRequest(new PartRequest(att, destinationFile, contentUriString));
        }

        public void updateFolderList(long accountId) throws RemoteException {
            reloadFolderList(SyncManager.this, accountId, false);
        }

        public void hostChanged(long accountId) throws RemoteException {
            SyncManager syncManager = INSTANCE;
            if (syncManager == null) return;
            synchronized (sSyncLock) {
                HashMap<Long, SyncError> syncErrorMap = syncManager.mSyncErrorMap;
                ArrayList<Long> deletedMailboxes = new ArrayList<Long>();
                // Go through the various error mailboxes
                for (long mailboxId: syncErrorMap.keySet()) {
                    SyncError error = syncErrorMap.get(mailboxId);
                    // If it's a login failure, look a little harder
                    Mailbox m = Mailbox.restoreMailboxWithId(syncManager, mailboxId);
                    // If it's for the account whose host has changed, clear the error
                    // If the mailbox is no longer around, remove the entry in the map
                    if (m == null) {
                        deletedMailboxes.add(mailboxId);
                    } else if (m.mAccountKey == accountId) {
                        error.fatal = false;
                        error.holdEndTime = 0;
                    }
                }
                for (long mailboxId: deletedMailboxes) {
                    syncErrorMap.remove(mailboxId);
                }
            }
            // Stop any running syncs
            syncManager.stopAccountSyncs(accountId, true);
            // Kick SyncManager
            kick("host changed");
        }

        public void setLogging(int on) throws RemoteException {
            Eas.setUserDebug(on);
        }

        public void sendMeetingResponse(long messageId, int response) throws RemoteException {
            sendMessageRequest(new MeetingResponseRequest(messageId, response));
        }

        public void loadMore(long messageId) throws RemoteException {
        }

        // The following three methods are not implemented in this version
        public boolean createFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        public boolean deleteFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        public boolean renameFolder(long accountId, String oldName, String newName)
                throws RemoteException {
            return false;
        }

        public void setCallback(IEmailServiceCallback cb) throws RemoteException {
            if (mCallback != null) {
                mCallbackList.unregister(mCallback);
            }
            mCallback = cb;
            mCallbackList.register(cb);
        }
    };

    static class AccountList extends ArrayList<Account> {
        private static final long serialVersionUID = 1L;

        public boolean contains(long id) {
            for (Account account : this) {
                if (account.mId == id) {
                    return true;
                }
            }
            return false;
        }

        public Account getById(long id) {
            for (Account account : this) {
                if (account.mId == id) {
                    return account;
                }
            }
            return null;
        }
    }

    class AccountObserver extends ContentObserver {
        String mSyncableEasMailboxSelector = null;
        String mEasAccountSelector = null;

        public AccountObserver(Handler handler) {
            super(handler);
            // At startup, we want to see what EAS accounts exist and cache them
            Context context = getContext();
            synchronized (mAccountList) {
                Cursor c = getContentResolver().query(Account.CONTENT_URI,
                        Account.CONTENT_PROJECTION, null, null, null);
                // Build the account list from the cursor
                try {
                    collectEasAccounts(c, mAccountList);
                } finally {
                    c.close();
                }

                // Create an account mailbox for any account without one
                for (Account account : mAccountList) {
                    int cnt = Mailbox.count(context, Mailbox.CONTENT_URI, "accountKey="
                            + account.mId, null);
                    if (cnt == 0) {
                        addAccountMailbox(account.mId);
                    }
                }
            }
        }

        /**
         * Returns a String suitable for appending to a where clause that selects for all syncable
         * mailboxes in all eas accounts
         * @return a complex selection string that is not to be cached
         */
        public String getSyncableEasMailboxWhere() {
            if (mSyncableEasMailboxSelector == null) {
                StringBuilder sb = new StringBuilder(WHERE_NOT_INTERVAL_NEVER_AND_ACCOUNT_KEY_IN);
                boolean first = true;
                synchronized (mAccountList) {
                    for (Account account : mAccountList) {
                        if (!first) {
                            sb.append(',');
                        } else {
                            first = false;
                        }
                        sb.append(account.mId);
                    }
                }
                sb.append(')');
                mSyncableEasMailboxSelector = sb.toString();
            }
            return mSyncableEasMailboxSelector;
        }

        /**
         * Returns a String suitable for appending to a where clause that selects for all eas
         * accounts.
         * @return a String in the form "accountKey in (a, b, c...)" that is not to be cached
         */
        public String getAccountKeyWhere() {
            if (mEasAccountSelector == null) {
                StringBuilder sb = new StringBuilder(ACCOUNT_KEY_IN);
                boolean first = true;
                synchronized (mAccountList) {
                    for (Account account : mAccountList) {
                        if (!first) {
                            sb.append(',');
                        } else {
                            first = false;
                        }
                        sb.append(account.mId);
                    }
                }
                sb.append(')');
                mEasAccountSelector = sb.toString();
            }
            return mEasAccountSelector;
        }

        private boolean onSecurityHold(Account account) {
            return (account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0;
        }

        private void onAccountChanged() {
            maybeStartSyncManagerThread();
            Context context = getContext();

            // A change to the list requires us to scan for deletions (stop running syncs)
            // At startup, we want to see what accounts exist and cache them
            AccountList currentAccounts = new AccountList();
            Cursor c = getContentResolver().query(Account.CONTENT_URI,
                    Account.CONTENT_PROJECTION, null, null, null);
            try {
                collectEasAccounts(c, currentAccounts);
                synchronized (mAccountList) {
                    for (Account account : mAccountList) {
                        // Ignore accounts not fully created
                        if ((account.mFlags & Account.FLAGS_INCOMPLETE) != 0) {
                            log("Account observer noticed incomplete account; ignoring");
                            continue;
                        } else if (!currentAccounts.contains(account.mId)) {
                            // This is a deletion; shut down any account-related syncs
                            stopAccountSyncs(account.mId, true);
                            // Delete this from AccountManager...
                            android.accounts.Account acct = new android.accounts.Account(
                                    account.mEmailAddress, Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                            AccountManager.get(SyncManager.this).removeAccount(acct, null, null);
                            mSyncableEasMailboxSelector = null;
                            mEasAccountSelector = null;
                        } else {
                            // An account has changed
                            Account updatedAccount = Account.restoreAccountWithId(context,
                                    account.mId);
                            if (updatedAccount == null) continue;
                            if (account.mSyncInterval != updatedAccount.mSyncInterval
                                    || account.mSyncLookback != updatedAccount.mSyncLookback) {
                                // Set the inbox interval to the interval of the Account
                                // This setting should NOT affect other boxes
                                ContentValues cv = new ContentValues();
                                cv.put(MailboxColumns.SYNC_INTERVAL, updatedAccount.mSyncInterval);
                                getContentResolver().update(Mailbox.CONTENT_URI, cv,
                                        WHERE_IN_ACCOUNT_AND_TYPE_INBOX, new String[] {
                                            Long.toString(account.mId)
                                        });
                                // Stop all current syncs; the appropriate ones will restart
                                log("Account " + account.mDisplayName + " changed; stop syncs");
                                stopAccountSyncs(account.mId, true);
                            }

                            // See if this account is no longer on security hold
                            if (onSecurityHold(account) && !onSecurityHold(updatedAccount)) {
                                releaseSyncHolds(SyncManager.this,
                                        AbstractSyncService.EXIT_SECURITY_FAILURE, account);
                            }

                            // Put current values into our cached account
                            account.mSyncInterval = updatedAccount.mSyncInterval;
                            account.mSyncLookback = updatedAccount.mSyncLookback;
                            account.mFlags = updatedAccount.mFlags;
                        }
                    }
                    // Look for new accounts
                    for (Account account : currentAccounts) {
                        if (!mAccountList.contains(account.mId)) {
                            // Don't forget to cache the HostAuth
                            HostAuth ha = HostAuth.restoreHostAuthWithId(getContext(),
                                    account.mHostAuthKeyRecv);
                            if (ha == null) continue;
                            account.mHostAuthRecv = ha;
                            // This is an addition; create our magic hidden mailbox...
                            log("Account observer found new account: " + account.mDisplayName);
                            addAccountMailbox(account.mId);
                            mAccountList.add(account);
                            mSyncableEasMailboxSelector = null;
                            mEasAccountSelector = null;
                        }
                    }
                    // Finally, make sure our account list is up to date
                    mAccountList.clear();
                    mAccountList.addAll(currentAccounts);
                }
            } finally {
                c.close();
            }

            // See if there's anything to do...
            kick("account changed");
        }

        @Override
        public void onChange(boolean selfChange) {
            new Thread(new Runnable() {
               public void run() {
                   onAccountChanged();
                }}, "Account Observer").start();
        }

        private void collectEasAccounts(Cursor c, ArrayList<Account> accounts) {
            Context context = getContext();
            if (context == null) return;
            while (c.moveToNext()) {
                long hostAuthId = c.getLong(Account.CONTENT_HOST_AUTH_KEY_RECV_COLUMN);
                if (hostAuthId > 0) {
                    HostAuth ha = HostAuth.restoreHostAuthWithId(context, hostAuthId);
                    if (ha != null && ha.mProtocol.equals("eas")) {
                        Account account = new Account().restore(c);
                        // Cache the HostAuth
                        account.mHostAuthRecv = ha;
                        accounts.add(account);
                    }
                }
            }
        }

        private void addAccountMailbox(long acctId) {
            Account acct = Account.restoreAccountWithId(getContext(), acctId);
            Mailbox main = new Mailbox();
            main.mDisplayName = Eas.ACCOUNT_MAILBOX_PREFIX;
            main.mServerId = Eas.ACCOUNT_MAILBOX_PREFIX + System.nanoTime();
            main.mAccountKey = acct.mId;
            main.mType = Mailbox.TYPE_EAS_ACCOUNT_MAILBOX;
            main.mSyncInterval = Mailbox.CHECK_INTERVAL_PUSH;
            main.mFlagVisible = false;
            main.save(getContext());
            log("Initializing account: " + acct.mDisplayName);
        }

    }

    /**
     * Register a specific Calendar's data observer; we need to recognize when the SYNC_EVENTS
     * column has changed (when sync has turned off or on)
     * @param account the Account whose Calendar we're observing
     */
    private void registerCalendarObserver(Account account) {
        // Get a new observer
        CalendarObserver observer = new CalendarObserver(mHandler, account);
        if (observer.mCalendarId != 0) {
            // If we find the Calendar (and we'd better) register it and store it in the map
            mCalendarObservers.put(account.mId, observer);
            mResolver.registerContentObserver(
                    ContentUris.withAppendedId(Calendars.CONTENT_URI, observer.mCalendarId), false,
                    observer);
        }
    }

    /**
     * Unregister all CalendarObserver's
     */
    static public void unregisterCalendarObservers() {
        SyncManager syncManager = INSTANCE;
        if (syncManager == null) return;
        ContentResolver resolver = syncManager.mResolver;
        for (CalendarObserver observer: syncManager.mCalendarObservers.values()) {
            resolver.unregisterContentObserver(observer);
        }
        syncManager.mCalendarObservers.clear();
    }

    /**
     * Return the syncable state of an account's calendar, as determined by the sync_events column
     * of our Calendar (from CalendarProvider2)
     * Note that the current state of sync_events is cached in our CalendarObserver
     * @param accountId the id of the account whose calendar we are checking
     * @return whether or not syncing of events is enabled
     */
    private boolean isCalendarEnabled(long accountId) {
        CalendarObserver observer = mCalendarObservers.get(accountId);
        if (observer != null) {
            return (observer.mSyncEvents == 1);
        }
        // If there's no observer, there's no Calendar in CalendarProvider2, so we return true
        // to allow Calendar creation
        return true;
    }

    private class CalendarObserver extends ContentObserver {
        long mAccountId;
        long mCalendarId;
        long mSyncEvents;
        String mAccountName;

        public CalendarObserver(Handler handler, Account account) {
            super(handler);
            mAccountId = account.mId;
            mAccountName = account.mEmailAddress;

            // Find the Calendar for this account
            Cursor c = mResolver.query(Calendars.CONTENT_URI,
                    new String[] {Calendars._ID, Calendars.SYNC_EVENTS},
                    CalendarSyncAdapter.CALENDAR_SELECTION,
                    new String[] {account.mEmailAddress, Email.EXCHANGE_ACCOUNT_MANAGER_TYPE},
                    null);
            if (c != null) {
                // Save its id and its sync events status
                try {
                    if (c.moveToFirst()) {
                        mCalendarId = c.getLong(0);
                        mSyncEvents = c.getLong(1);
                    }
                } finally {
                    c.close();
                }
            }
        }

        @Override
        public synchronized void onChange(boolean selfChange) {
            // See if the user has changed syncing of our calendar
            if (!selfChange) {
                new Thread(new Runnable() {
                    public void run() {
                        Cursor c = mResolver.query(Calendars.CONTENT_URI,
                                new String[] {Calendars.SYNC_EVENTS}, Calendars._ID + "=?",
                                new String[] {Long.toString(mCalendarId)}, null);
                        if (c == null) return;
                        // Get its sync events; if it's changed, we've got work to do
                        try {
                            if (c.moveToFirst()) {
                                long newSyncEvents = c.getLong(0);
                                if (newSyncEvents != mSyncEvents) {
                                    log("_sync_events changed for calendar in " + mAccountName);
                                    Mailbox mailbox = Mailbox.restoreMailboxOfType(INSTANCE,
                                            mAccountId, Mailbox.TYPE_CALENDAR);
                                    // Sanity check for mailbox deletion
                                    if (mailbox == null) return;
                                    if (newSyncEvents == 0) {
                                        // When sync is disabled, we're supposed to delete
                                        // all events in the calendar
                                        log("Deleting events and setting syncKey to 0 for " +
                                                mAccountName);
                                        // First, stop any sync that's ongoing
                                        stopManualSync(mailbox.mId);
                                        // Set the syncKey to 0 (reset)
                                        EasSyncService service =
                                            new EasSyncService(INSTANCE, mailbox);
                                        CalendarSyncAdapter adapter =
                                            new CalendarSyncAdapter(mailbox, service);
                                        try {
                                            adapter.setSyncKey("0", false);
                                        } catch (IOException e) {
                                            // The provider can't be reached; nothing to be done
                                        }
                                        // Reset the sync key locally
                                        ContentValues cv = new ContentValues();
                                        cv.put(Mailbox.SYNC_KEY, "0");
                                        mResolver.update(ContentUris.withAppendedId(
                                                Mailbox.CONTENT_URI, mailbox.mId), cv, null, null);
                                        // Delete all events in this calendar using the sync adapter
                                        // parameter so that the deletion is only local
                                        Uri eventsAsSyncAdapter =
                                            Events.CONTENT_URI.buildUpon()
                                            .appendQueryParameter(
                                                    Calendar.CALLER_IS_SYNCADAPTER, "true")
                                                    .build();
                                        mResolver.delete(eventsAsSyncAdapter, WHERE_CALENDAR_ID,
                                                new String[] {Long.toString(mCalendarId)});
                                    } else {
                                        // If we're in a ping, stop it so that calendar sync can
                                        // start right away
                                        stopPing(mAccountId);
                                        kick("calendar sync changed");
                                    }

                                    // Save away the new value
                                    mSyncEvents = newSyncEvents;
                                }
                            }
                        } finally {
                            c.close();
                        }
                    }}, "Calendar Observer").start();
            }
        }
    }

    private class MailboxObserver extends ContentObserver {
        public MailboxObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            // See if there's anything to do...
            if (!selfChange) {
                kick("mailbox changed");
            }
        }
    }

    private class SyncedMessageObserver extends ContentObserver {
        Intent syncAlarmIntent = new Intent(INSTANCE, EmailSyncAlarmReceiver.class);
        PendingIntent syncAlarmPendingIntent =
            PendingIntent.getBroadcast(INSTANCE, 0, syncAlarmIntent, 0);
        AlarmManager alarmManager = (AlarmManager)INSTANCE.getSystemService(Context.ALARM_SERVICE);

        public SyncedMessageObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            log("SyncedMessage changed: (re)setting alarm for 10s");
            alarmManager.set(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 10*SECONDS, syncAlarmPendingIntent);
        }
    }

    private class MessageObserver extends ContentObserver {

        public MessageObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            // A rather blunt instrument here.  But we don't have information about the URI that
            // triggered this, though it must have been an insert
            if (!selfChange) {
                kick(null);
            }
        }
    }

    static public IEmailServiceCallback callback() {
        return sCallbackProxy;
    }

    static public Account getAccountById(long accountId) {
        SyncManager syncManager = INSTANCE;
        if (syncManager != null) {
            AccountList accountList = syncManager.mAccountList;
            synchronized (accountList) {
                return accountList.getById(accountId);
            }
        }
        return null;
    }

    static public String getEasAccountSelector() {
        SyncManager syncManager = INSTANCE;
        if (syncManager != null && syncManager.mAccountObserver != null) {
            return syncManager.mAccountObserver.getAccountKeyWhere();
        }
        return null;
    }

    public class SyncStatus {
        static public final int NOT_RUNNING = 0;
        static public final int DIED = 1;
        static public final int SYNC = 2;
        static public final int IDLE = 3;
    }

    /*package*/ class SyncError {
        int reason;
        boolean fatal = false;
        long holdDelay = 15*SECONDS;
        long holdEndTime = System.currentTimeMillis() + holdDelay;

        SyncError(int _reason, boolean _fatal) {
            reason = _reason;
            fatal = _fatal;
        }

        /**
         * We double the holdDelay from 15 seconds through 4 mins
         */
        void escalate() {
            if (holdDelay < HOLD_DELAY_MAXIMUM) {
                holdDelay *= 2;
            }
            holdEndTime = System.currentTimeMillis() + holdDelay;
        }
    }

    private void logSyncHolds() {
        if (Eas.USER_LOG && !mSyncErrorMap.isEmpty()) {
            log("Sync holds:");
            long time = System.currentTimeMillis();
            synchronized (sSyncLock) {
                for (long mailboxId : mSyncErrorMap.keySet()) {
                    Mailbox m = Mailbox.restoreMailboxWithId(this, mailboxId);
                    if (m == null) {
                        log("Mailbox " + mailboxId + " no longer exists");
                    } else {
                        SyncError error = mSyncErrorMap.get(mailboxId);
                        log("Mailbox " + m.mDisplayName + ", error = " + error.reason
                                + ", fatal = " + error.fatal);
                        if (error.holdEndTime > 0) {
                            log("Hold ends in " + ((error.holdEndTime - time) / 1000) + "s");
                        }
                    }
                }
            }
        }
    }

    /**
     * Release security holds for the specified account
     * @param account the account whose Mailboxes should be released from security hold
     */
    static public void releaseSecurityHold(Account account) {
        SyncManager syncManager = INSTANCE;
        if (syncManager != null) {
            syncManager.releaseSyncHolds(INSTANCE, AbstractSyncService.EXIT_SECURITY_FAILURE,
                    account);
        }
    }

    /**
     * Release a specific type of hold (the reason) for the specified Account; if the account
     * is null, mailboxes from all accounts with the specified hold will be released
     * @param reason the reason for the SyncError (AbstractSyncService.EXIT_XXX)
     * @param account an Account whose mailboxes should be released (or all if null)
     */
    /*package*/ void releaseSyncHolds(Context context, int reason, Account account) {
        releaseSyncHoldsImpl(context, reason, account);
        kick("security release");
    }

    private void releaseSyncHoldsImpl(Context context, int reason, Account account) {
        synchronized(sSyncLock) {
            ArrayList<Long> releaseList = new ArrayList<Long>();
            for (long mailboxId: mSyncErrorMap.keySet()) {
                if (account != null) {
                    Mailbox m = Mailbox.restoreMailboxWithId(context, mailboxId);
                    if (m == null) {
                        releaseList.add(mailboxId);
                    } else if (m.mAccountKey != account.mId) {
                        continue;
                    }
                }
                SyncError error = mSyncErrorMap.get(mailboxId);
                if (error.reason == reason) {
                    releaseList.add(mailboxId);
                }
            }
            for (long mailboxId: releaseList) {
                mSyncErrorMap.remove(mailboxId);
            }
        }
    }

    public class EasSyncStatusObserver implements SyncStatusObserver {
        public void onStatusChanged(int which) {
            // We ignore the argument (we can only get called in one case - when settings change)
            if (INSTANCE != null) {
                checkPIMSyncSettings();
            }
        }
    }

    /**
     * The reconciler (which is called from this listener) can make blocking calls back into
     * the account manager.  So, in this callback we spin up a worker thread to call the
     * reconciler.
     */
    public class EasAccountsUpdatedListener implements OnAccountsUpdateListener {
        public void onAccountsUpdated(android.accounts.Account[] accounts) {
            SyncManager syncManager = INSTANCE;
            if (syncManager != null) {
                syncManager.runAccountReconciler();
            }
        }
    }

    /**
     * Non-blocking call to run the account reconciler.
     * Launches a worker thread, so it may be called from UI thread.
     */
    private void runAccountReconciler() {
        final SyncManager syncManager = this;
        new Thread() {
            @Override
            public void run() {
                android.accounts.Account[] accountMgrList = AccountManager.get(syncManager)
                        .getAccountsByType(Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                synchronized (mAccountList) {
                    // Make sure we have an up-to-date sAccountList.  If not (for example, if the
                    // service has been destroyed), we would be reconciling against an empty account
                    // list, which would cause the deletion of all of our accounts
                    if (mAccountObserver != null) {
                        mAccountObserver.onAccountChanged();
                        reconcileAccountsWithAccountManager(syncManager, mAccountList,
                                accountMgrList, false, mResolver);
                    }
                }
            }
        }.start();
    }

    public static void log(String str) {
        log(TAG, str);
    }

    public static void log(String tag, String str) {
        if (Eas.USER_LOG) {
            Log.d(tag, str);
            if (Eas.FILE_LOG) {
                FileLogger.log(tag, str);
            }
        }
    }

    public static void alwaysLog(String str) {
        if (!Eas.USER_LOG) {
            Log.d(TAG, str);
        } else {
            log(str);
        }
    }

    /**
     * EAS requires a unique device id, so that sync is possible from a variety of different
     * devices (e.g. the syncKey is specific to a device)  If we're on an emulator or some other
     * device that doesn't provide one, we can create it as droid<n> where <n> is system time.
     * This would work on a real device as well, but it would be better to use the "real" id if
     * it's available
     */
    static public String getDeviceId() throws IOException {
        return getDeviceId(null);
    }

    static public synchronized String getDeviceId(Context context) throws IOException {
        if (sDeviceId == null) {
            sDeviceId = getDeviceIdInternal(context);
        }
        return sDeviceId;
    }

    static private String getDeviceIdInternal(Context context) throws IOException {
        if (INSTANCE == null && context == null) {
            throw new IOException("No context for getDeviceId");
        } else if (context == null) {
            context = INSTANCE;
        }

        try {
            File f = context.getFileStreamPath("deviceName");
            BufferedReader rdr = null;
            String id;
            if (f.exists() && f.canRead()) {
                rdr = new BufferedReader(new FileReader(f), 128);
                id = rdr.readLine();
                rdr.close();
                return id;
            } else if (f.createNewFile()) {
                BufferedWriter w = new BufferedWriter(new FileWriter(f), 128);
                final String consistentDeviceId = Utility.getConsistentDeviceId(context);
                if (consistentDeviceId != null) {
                    // Use different prefix from random IDs.
                    id = "androidc" + consistentDeviceId;
                } else {
                    id = "android" + System.currentTimeMillis();
                }
                w.write(id);
                w.close();
                return id;
            }
        } catch (IOException e) {
        }
        throw new IOException("Can't get device name");
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    static public ConnPerRoute sConnPerRoute = new ConnPerRoute() {
        public int getMaxForRoute(HttpRoute route) {
            return 8;
        }
    };

    static public synchronized ClientConnectionManager getClientConnectionManager() {
        if (sClientConnectionManager == null) {
            // After two tries, kill the process.  Most likely, this will happen in the background
            // The service will restart itself after about 5 seconds
            if (sClientConnectionManagerShutdownCount > MAX_CLIENT_CONNECTION_MANAGER_SHUTDOWNS) {
                alwaysLog("Shutting down process to unblock threads");
                Process.killProcess(Process.myPid());
            }
            // Create a registry for our three schemes; http and https will use built-in factories
            SchemeRegistry registry = new SchemeRegistry();
            registry.register(new Scheme("http",
                    PlainSocketFactory.getSocketFactory(), 80));
            registry.register(new Scheme("https", SSLSocketFactory.getSocketFactory(), 443));

            // Use "insecure" socket factory.
            SSLSocketFactory sf = new SSLSocketFactory(SSLUtils.getSSLSocketFactory(true));
            sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
            // Register the httpts scheme with our factory
            registry.register(new Scheme("httpts", sf, 443));
            // And create a ccm with our registry
            HttpParams params = new BasicHttpParams();
            params.setIntParameter(ConnManagerPNames.MAX_TOTAL_CONNECTIONS, 25);
            params.setParameter(ConnManagerPNames.MAX_CONNECTIONS_PER_ROUTE, sConnPerRoute);
            sClientConnectionManager = new ThreadSafeClientConnManager(params, registry);
        }
        // Null is a valid return result if we get an exception
        return sClientConnectionManager;
    }

    static private synchronized void shutdownConnectionManager() {
        if (sClientConnectionManager != null) {
            alwaysLog("Shutting down ClientConnectionManager");
            sClientConnectionManager.shutdown();
            sClientConnectionManagerShutdownCount++;
            sClientConnectionManager = null;
        }
    }

    public static void stopAccountSyncs(long acctId) {
        SyncManager syncManager = INSTANCE;
        if (syncManager != null) {
            syncManager.stopAccountSyncs(acctId, true);
        }
    }

    private void stopAccountSyncs(long acctId, boolean includeAccountMailbox) {
        synchronized (sSyncLock) {
            List<Long> deletedBoxes = new ArrayList<Long>();
            for (Long mid : mServiceMap.keySet()) {
                Mailbox box = Mailbox.restoreMailboxWithId(this, mid);
                if (box != null) {
                    if (box.mAccountKey == acctId) {
                        if (!includeAccountMailbox &&
                                box.mType == Mailbox.TYPE_EAS_ACCOUNT_MAILBOX) {
                            AbstractSyncService svc = mServiceMap.get(mid);
                            if (svc != null) {
                                svc.stop();
                            }
                            continue;
                        }
                        AbstractSyncService svc = mServiceMap.get(mid);
                        if (svc != null) {
                            svc.stop();
                            Thread t = svc.mThread;
                            if (t != null) {
                                t.interrupt();
                            }
                        }
                        deletedBoxes.add(mid);
                    }
                }
            }
            for (Long mid : deletedBoxes) {
                releaseMailbox(mid);
            }
        }
    }

    static private void reloadFolderListFailed(long accountId) {
        try {
            callback().syncMailboxListStatus(accountId,
                    EmailServiceStatus.ACCOUNT_UNINITIALIZED, 0);
        } catch (RemoteException e1) {
            // Don't care if this fails
        }
    }

    static public void reloadFolderList(Context context, long accountId, boolean force) {
        SyncManager syncManager = INSTANCE;
        if (syncManager == null) return;
        Cursor c = context.getContentResolver().query(Mailbox.CONTENT_URI,
                Mailbox.CONTENT_PROJECTION, MailboxColumns.ACCOUNT_KEY + "=? AND " +
                MailboxColumns.TYPE + "=?",
                new String[] {Long.toString(accountId),
                    Long.toString(Mailbox.TYPE_EAS_ACCOUNT_MAILBOX)}, null);
        try {
            if (c.moveToFirst()) {
                synchronized(sSyncLock) {
                    Mailbox m = new Mailbox().restore(c);
                    Account acct = Account.restoreAccountWithId(context, accountId);
                    if (acct == null) {
                        reloadFolderListFailed(accountId);
                        return;
                    }
                    String syncKey = acct.mSyncKey;
                    // No need to reload the list if we don't have one
                    if (!force && (syncKey == null || syncKey.equals("0"))) {
                        reloadFolderListFailed(accountId);
                        return;
                    }

                    // Change all ping/push boxes to push/hold
                    ContentValues cv = new ContentValues();
                    cv.put(Mailbox.SYNC_INTERVAL, Mailbox.CHECK_INTERVAL_PUSH_HOLD);
                    context.getContentResolver().update(Mailbox.CONTENT_URI, cv,
                            WHERE_PUSH_OR_PING_NOT_ACCOUNT_MAILBOX,
                            new String[] {Long.toString(accountId)});
                    log("Set push/ping boxes to push/hold");

                    long id = m.mId;
                    AbstractSyncService svc = syncManager.mServiceMap.get(id);
                    // Tell the service we're done
                    if (svc != null) {
                        synchronized (svc.getSynchronizer()) {
                            svc.stop();
                        }
                        // Interrupt the thread so that it can stop
                        Thread thread = svc.mThread;
                        thread.setName(thread.getName() + " (Stopped)");
                        thread.interrupt();
                        // Abandon the service
                        syncManager.releaseMailbox(id);
                        // And have it start naturally
                        kick("reload folder list");
                    }
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * Informs SyncManager that an account has a new folder list; as a result, any existing folder
     * might have become invalid.  Therefore, we act as if the account has been deleted, and then
     * we reinitialize it.
     *
     * @param acctId
     */
    static public void stopNonAccountMailboxSyncsForAccount(long acctId) {
        SyncManager syncManager = INSTANCE;
        if (syncManager != null) {
            syncManager.stopAccountSyncs(acctId, false);
            kick("reload folder list");
        }
    }

    private void acquireWakeLock(long id) {
        synchronized (mWakeLocks) {
            Boolean lock = mWakeLocks.get(id);
            if (lock == null) {
                if (mWakeLock == null) {
                    PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MAIL_SERVICE");
                    mWakeLock.acquire();
                    //log("+WAKE LOCK ACQUIRED");
                }
                mWakeLocks.put(id, true);
             }
        }
    }

    private void releaseWakeLock(long id) {
        synchronized (mWakeLocks) {
            Boolean lock = mWakeLocks.get(id);
            if (lock != null) {
                mWakeLocks.remove(id);
                if (mWakeLocks.isEmpty()) {
                    if (mWakeLock != null) {
                        mWakeLock.release();
                    }
                    mWakeLock = null;
                    //log("+WAKE LOCK RELEASED");
                } else {
                }
            }
        }
    }

    static public String alarmOwner(long id) {
        if (id == SYNC_MANAGER_ID) {
            return "SyncManager";
        } else {
            String name = Long.toString(id);
            if (Eas.USER_LOG && INSTANCE != null) {
                Mailbox m = Mailbox.restoreMailboxWithId(INSTANCE, id);
                if (m != null) {
                    name = m.mDisplayName + '(' + m.mAccountKey + ')';
                }
            }
            return "Mailbox " + name;
        }
    }

    private void clearAlarm(long id) {
        synchronized (mPendingIntents) {
            PendingIntent pi = mPendingIntents.get(id);
            if (pi != null) {
                AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
                alarmManager.cancel(pi);
                //log("+Alarm cleared for " + alarmOwner(id));
                mPendingIntents.remove(id);
            }
        }
    }

    private void setAlarm(long id, long millis) {
        synchronized (mPendingIntents) {
            PendingIntent pi = mPendingIntents.get(id);
            if (pi == null) {
                Intent i = new Intent(this, MailboxAlarmReceiver.class);
                i.putExtra("mailbox", id);
                i.setData(Uri.parse("Box" + id));
                pi = PendingIntent.getBroadcast(this, 0, i, 0);
                mPendingIntents.put(id, pi);

                AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
                alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + millis, pi);
                //log("+Alarm set for " + alarmOwner(id) + ", " + millis/1000 + "s");
            }
        }
    }

    private void clearAlarms() {
        AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        synchronized (mPendingIntents) {
            for (PendingIntent pi : mPendingIntents.values()) {
                alarmManager.cancel(pi);
            }
            mPendingIntents.clear();
        }
    }

    static public void runAwake(long id) {
        SyncManager syncManager = INSTANCE;
        if (syncManager != null) {
            syncManager.acquireWakeLock(id);
            syncManager.clearAlarm(id);
        }
    }

    static public void runAsleep(long id, long millis) {
        SyncManager syncManager = INSTANCE;
        if (syncManager != null) {
            syncManager.setAlarm(id, millis);
            syncManager.releaseWakeLock(id);
        }
    }

    static public void clearWatchdogAlarm(long id) {
        SyncManager syncManager = INSTANCE;
        if (syncManager != null) {
            syncManager.clearAlarm(id);
        }
    }

    static public void setWatchdogAlarm(long id, long millis) {
        SyncManager syncManager = INSTANCE;
        if (syncManager != null) {
            syncManager.setAlarm(id, millis);
        }
    }

    static public void alert(Context context, final long id) {
        final SyncManager syncManager = INSTANCE;
        checkSyncManagerServiceRunning();
        if (id < 0) {
            kick("ping SyncManager");
        } else if (syncManager == null) {
            context.startService(new Intent(context, SyncManager.class));
        } else {
            final AbstractSyncService service = syncManager.mServiceMap.get(id);
            if (service != null) {
                // Handle alerts in a background thread, as we are typically called from a
                // broadcast receiver, and are therefore running in the UI thread
                String threadName = "SyncManager Alert: ";
                if (service.mMailbox != null) {
                    threadName += service.mMailbox.mDisplayName;
                }
                new Thread(new Runnable() {
                   public void run() {
                       Mailbox m = Mailbox.restoreMailboxWithId(syncManager, id);
                       if (m != null) {
                           // We ignore drafts completely (doesn't sync).  Changes in Outbox are
                           // handled in the checkMailboxes loop, so we can ignore these pings.
                           if (m.mType == Mailbox.TYPE_DRAFTS || m.mType == Mailbox.TYPE_OUTBOX) {
                               String[] args = new String[] {Long.toString(m.mId)};
                               ContentResolver resolver = INSTANCE.mResolver;
                               resolver.delete(Message.DELETED_CONTENT_URI, WHERE_MAILBOX_KEY,
                                       args);
                               resolver.delete(Message.UPDATED_CONTENT_URI, WHERE_MAILBOX_KEY,
                                       args);
                               return;
                           }
                           service.mAccount = Account.restoreAccountWithId(INSTANCE, m.mAccountKey);
                           service.mMailbox = m;
                           // Send the alarm to the sync service
                           if (!service.alarm()) {
                               // A false return means that we were forced to interrupt the thread
                               // In this case, we release the mailbox so that we can start another
                               // thread to do the work
                               log("Alarm failed; releasing mailbox");
                               synchronized(sSyncLock) {
                                   syncManager.releaseMailbox(id);
                               }
                               // Shutdown the connection manager; this should close all of our
                               // sockets and generate IOExceptions all around.
                               SyncManager.shutdownConnectionManager();
                           }
                       }
                    }}, threadName).start();
            }
        }
    }

    /**
     * See if we need to change the syncInterval for any of our PIM mailboxes based on changes
     * to settings in the AccountManager (sync settings).
     * This code is called 1) when SyncManager starts, and 2) when SyncManager is running and there
     * are changes made (this is detected via a SyncStatusObserver)
     */
    private void updatePIMSyncSettings(Account providerAccount, int mailboxType, String authority) {
        ContentValues cv = new ContentValues();
        long mailboxId =
            Mailbox.findMailboxOfType(this, providerAccount.mId, mailboxType);
        // Presumably there is one, but if not, it's ok.  Just move on...
        if (mailboxId != Mailbox.NO_MAILBOX) {
            // Create an AccountManager style Account
            android.accounts.Account acct =
                new android.accounts.Account(providerAccount.mEmailAddress,
                        Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            // Get the mailbox; this happens rarely so it's ok to get it all
            Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mailboxId);
            if (mailbox == null) return;
            int syncInterval = mailbox.mSyncInterval;
            // If we're syncable, look further...
            if (ContentResolver.getIsSyncable(acct, authority) > 0) {
                // If we're supposed to sync automatically (push), set to push if it's not
                if (ContentResolver.getSyncAutomatically(acct, authority)) {
                    if (syncInterval == Mailbox.CHECK_INTERVAL_NEVER || syncInterval > 0) {
                        log("Sync for " + mailbox.mDisplayName + " in " + acct.name + ": push");
                        cv.put(MailboxColumns.SYNC_INTERVAL, Mailbox.CHECK_INTERVAL_PUSH);
                    }
                    // If we're NOT supposed to push, and we're not set up that way, change it
                } else if (syncInterval != Mailbox.CHECK_INTERVAL_NEVER) {
                    log("Sync for " + mailbox.mDisplayName + " in " + acct.name + ": manual");
                    cv.put(MailboxColumns.SYNC_INTERVAL, Mailbox.CHECK_INTERVAL_NEVER);
                }
                // If not, set it to never check
            } else if (syncInterval != Mailbox.CHECK_INTERVAL_NEVER) {
                log("Sync for " + mailbox.mDisplayName + " in " + acct.name + ": manual");
                cv.put(MailboxColumns.SYNC_INTERVAL, Mailbox.CHECK_INTERVAL_NEVER);
            }

            // If we've made a change, update the Mailbox, and kick
            if (cv.containsKey(MailboxColumns.SYNC_INTERVAL)) {
                mResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                        cv,null, null);
                kick("sync settings change");
            }
        }
    }

    /**
     * Make our sync settings match those of AccountManager
     */
    private void checkPIMSyncSettings() {
        synchronized (mAccountList) {
            for (Account account : mAccountList) {
                updatePIMSyncSettings(account, Mailbox.TYPE_CONTACTS, ContactsContract.AUTHORITY);
                updatePIMSyncSettings(account, Mailbox.TYPE_CALENDAR, Calendar.AUTHORITY);
            }
        }
    }

    /**
     * Compare our account list (obtained from EmailProvider) with the account list owned by
     * AccountManager.  If there are any orphans (an account in one list without a corresponding
     * account in the other list), delete the orphan, as these must remain in sync.
     *
     * Note that the duplication of account information is caused by the Email application's
     * incomplete integration with AccountManager.
     *
     * This function may not be called from the main/UI thread, because it makes blocking calls
     * into the account manager.
     *
     * @param context The context in which to operate
     * @param cachedEasAccounts the exchange provider accounts to work from
     * @param accountManagerAccounts The account manager accounts to work from
     * @param blockExternalChanges FOR TESTING ONLY - block backups, security changes, etc.
     * @param resolver the content resolver for making provider updates (injected for testability)
     */
    /* package */ static void reconcileAccountsWithAccountManager(Context context,
            List<Account> cachedEasAccounts, android.accounts.Account[] accountManagerAccounts,
            boolean blockExternalChanges, ContentResolver resolver) {
        // First, look through our cached EAS Accounts (from EmailProvider) to make sure there's a
        // corresponding AccountManager account
        boolean accountsDeleted = false;
        for (Account providerAccount: cachedEasAccounts) {
            String providerAccountName = providerAccount.mEmailAddress;
            boolean found = false;
            for (android.accounts.Account accountManagerAccount: accountManagerAccounts) {
                if (accountManagerAccount.name.equalsIgnoreCase(providerAccountName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                if ((providerAccount.mFlags & Account.FLAGS_INCOMPLETE) != 0) {
                    log("Account reconciler noticed incomplete account; ignoring");
                    continue;
                }
                // This account has been deleted in the AccountManager!
                alwaysLog("Account deleted in AccountManager; deleting from provider: " +
                        providerAccountName);
                // TODO This will orphan downloaded attachments; need to handle this
                resolver.delete(ContentUris.withAppendedId(Account.CONTENT_URI,
                        providerAccount.mId), null, null);
                accountsDeleted = true;
            }
        }
        // Now, look through AccountManager accounts to make sure we have a corresponding cached EAS
        // account from EmailProvider
        for (android.accounts.Account accountManagerAccount: accountManagerAccounts) {
            String accountManagerAccountName = accountManagerAccount.name;
            boolean found = false;
            for (Account cachedEasAccount: cachedEasAccounts) {
                if (cachedEasAccount.mEmailAddress.equalsIgnoreCase(accountManagerAccountName)) {
                    found = true;
                }
            }
            if (!found) {
                // This account has been deleted from the EmailProvider database
                alwaysLog("Account deleted from provider; deleting from AccountManager: " +
                        accountManagerAccountName);
                // Delete the account
                AccountManagerFuture<Boolean> blockingResult = AccountManager.get(context)
                        .removeAccount(accountManagerAccount, null, null);
                try {
                    // Note: All of the potential errors from removeAccount() are simply logged
                    // here, as there is nothing to actually do about them.
                    blockingResult.getResult();
                } catch (OperationCanceledException e) {
                    Log.w(Email.LOG_TAG, e.toString());
                } catch (AuthenticatorException e) {
                    Log.w(Email.LOG_TAG, e.toString());
                } catch (IOException e) {
                    Log.w(Email.LOG_TAG, e.toString());
                }
                accountsDeleted = true;
            }
        }
        // If we changed the list of accounts, refresh the backup & security settings
        if (!blockExternalChanges && accountsDeleted) {
            AccountBackupRestore.backupAccounts(context);
            SecurityPolicy.getInstance(context).reducePolicies();
            Email.setNotifyUiAccountsChanged(true);
        }
    }

    public class ConnectivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Bundle b = intent.getExtras();
                if (b != null) {
                    NetworkInfo a = (NetworkInfo)b.get(ConnectivityManager.EXTRA_NETWORK_INFO);
                    String info = "Connectivity alert for " + a.getTypeName();
                    State state = a.getState();
                    if (state == State.CONNECTED) {
                        info += " CONNECTED";
                        log(info);
                        synchronized (sConnectivityLock) {
                            sConnectivityLock.notifyAll();
                        }
                        kick("connected");
                    } else if (state == State.DISCONNECTED) {
                        info += " DISCONNECTED";
                        log(info);
                        kick("disconnected");
                    }
                }
            } else if (intent.getAction().equals(
                    ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED)) {
                ConnectivityManager cm = (ConnectivityManager)SyncManager.this
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
                mBackgroundData = cm.getBackgroundDataSetting();
                // If background data is now on, we want to kick SyncManager
                if (mBackgroundData) {
                    kick("background data on");
                    log("Background data on; restart syncs");
                // Otherwise, stop all syncs
                } else {
                    log("Background data off: stop all syncs");
                    synchronized (mAccountList) {
                        for (Account account : mAccountList)
                            SyncManager.stopAccountSyncs(account.mId);
                    }
                }
            }
        }
    }

    /**
     * Starts a service thread and enters it into the service map
     * This is the point of instantiation of all sync threads
     * @param service the service to start
     * @param m the Mailbox on which the service will operate
     */
    private void startServiceThread(AbstractSyncService service, Mailbox m) {
        if (m == null) return;
        synchronized (sSyncLock) {
            String mailboxName = m.mDisplayName;
            String accountName = service.mAccount.mDisplayName;
            Thread thread = new Thread(service, mailboxName + "(" + accountName + ")");
            log("Starting thread for " + mailboxName + " in account " + accountName);
            thread.start();
            mServiceMap.put(m.mId, service);
            runAwake(m.mId);
            if ((m.mServerId != null) && !m.mServerId.startsWith(Eas.ACCOUNT_MAILBOX_PREFIX)) {
                stopPing(m.mAccountKey);
            }
        }
    }

    /**
     * Stop any ping in progress for the given account
     * @param accountId
     */
    private void stopPing(long accountId) {
        // Go through our active mailboxes looking for the right one
        synchronized (sSyncLock) {
            for (long mailboxId: mServiceMap.keySet()) {
                Mailbox m = Mailbox.restoreMailboxWithId(this, mailboxId);
                if (m != null) {
                    String serverId = m.mServerId;
                    if (m.mAccountKey == accountId && serverId != null &&
                            serverId.startsWith(Eas.ACCOUNT_MAILBOX_PREFIX)) {
                        // Here's our account mailbox; reset him (stopping pings)
                        AbstractSyncService svc = mServiceMap.get(mailboxId);
                        svc.reset();
                    }
                }
            }
        }
    }

    private void requestSync(Mailbox m, int reason, Request req) {
        // Don't sync if there's no connectivity
        if (sConnectivityHold || (m == null) || sStop) return;
        synchronized (sSyncLock) {
            Account acct = Account.restoreAccountWithId(this, m.mAccountKey);
            if (acct != null) {
                // Always make sure there's not a running instance of this service
                AbstractSyncService service = mServiceMap.get(m.mId);
                if (service == null) {
                    service = new EasSyncService(this, m);
                    if (!((EasSyncService)service).mIsValid) return;
                    service.mSyncReason = reason;
                    if (req != null) {
                        service.addRequest(req);
                    }
                    startServiceThread(service, m);
                }
            }
        }
    }

    private void stopServiceThreads() {
        synchronized (sSyncLock) {
            ArrayList<Long> toStop = new ArrayList<Long>();

            // Keep track of which services to stop
            for (Long mailboxId : mServiceMap.keySet()) {
                toStop.add(mailboxId);
            }

            // Shut down all of those running services
            for (Long mailboxId : toStop) {
                AbstractSyncService svc = mServiceMap.get(mailboxId);
                if (svc != null) {
                    log("Stopping " + svc.mAccount.mDisplayName + '/' + svc.mMailbox.mDisplayName);
                    svc.stop();
                    if (svc.mThread != null) {
                        svc.mThread.interrupt();
                    }
                }
                releaseWakeLock(mailboxId);
            }
        }
    }

    private void waitForConnectivity() {
        boolean waiting = false;
        ConnectivityManager cm =
            (ConnectivityManager)this.getSystemService(Context.CONNECTIVITY_SERVICE);
        while (!sStop) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null) {
                // We're done if there's an active network
                if (waiting) {
                    // If we've been waiting, release any I/O error holds
                    releaseSyncHolds(this, AbstractSyncService.EXIT_IO_ERROR, null);
                    // And log what's still being held
                    logSyncHolds();
                }
                return;
            } else {
                // If this is our first time through the loop, shut down running service threads
                if (!waiting) {
                    waiting = true;
                    stopServiceThreads();
                }
                // Wait until a network is connected (or 10 mins), but let the device sleep
                // We'll set an alarm just in case we don't get notified (bugs happen)
                synchronized (sConnectivityLock) {
                    runAsleep(SYNC_MANAGER_ID, CONNECTIVITY_WAIT_TIME+5*SECONDS);
                    try {
                        log("Connectivity lock...");
                        sConnectivityHold = true;
                        sConnectivityLock.wait(CONNECTIVITY_WAIT_TIME);
                        log("Connectivity lock released...");
                    } catch (InterruptedException e) {
                        // This is fine; we just go around the loop again
                    } finally {
                        sConnectivityHold = false;
                    }
                    runAwake(SYNC_MANAGER_ID);
                }
            }
        }
    }

    /**
     * Note that there are two ways the EAS SyncManager service can be created:
     *
     * 1) as a background service instantiated via startService (which happens on boot, when the
     * first EAS account is created, etc), in which case the service thread is spun up, mailboxes
     * sync, etc. and
     * 2) to execute an RPC call from the UI, in which case the background service will already be
     * running most of the time (unless we're creating a first EAS account)
     *
     * If the running background service detects that there are no EAS accounts (on boot, if none
     * were created, or afterward if the last remaining EAS account is deleted), it will call
     * stopSelf() to terminate operation.
     *
     * The goal is to ensure that the background service is running at all times when there is at
     * least one EAS account in existence
     *
     * Because there are edge cases in which our process can crash (typically, this has been seen
     * in UI crashes, ANR's, etc.), it's possible for the UI to start up again without the
     * background service having been started.  We explicitly try to start the service in Welcome
     * (to handle the case of the app having been reloaded).  We also start the service on any
     * startSync call (if it isn't already running)
     */
    @Override
    public void onCreate() {
        synchronized (sSyncLock) {
            alwaysLog("!!! EAS SyncManager, onCreate");
            // If we're in the process of shutting down, try again in 5 seconds
            if (sStop) {
                setAlarm(SYNC_MANAGER_SERVICE_ID, 5*SECONDS);
                return;
            }
            if (sDeviceId == null) {
                try {
                    getDeviceId(this);
                } catch (IOException e) {
                    // We can't run in this situation
                    throw new RuntimeException();
                }
            }
            // Run the reconciler and clean up any mismatched accounts - if we weren't running when
            // accounts were deleted, it won't have been called.
            runAccountReconciler();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (sSyncLock) {
            alwaysLog("!!! EAS SyncManager, onStartCommand");
            // Restore accounts, if it has not happened already
            AccountBackupRestore.restoreAccountsIfNeeded(this);
            maybeStartSyncManagerThread();
            if (sServiceThread == null) {
                alwaysLog("!!! EAS SyncManager, stopping self");
                stopSelf();
            }
            return Service.START_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        synchronized(sSyncLock) {
            alwaysLog("!!! EAS SyncManager, onDestroy");
            // Stop the sync manager thread and return
            synchronized (sSyncLock) {
                if (sServiceThread != null) {
                    sStop = true;
                    sServiceThread.interrupt();
                }
            }
        }
    }

    void maybeStartSyncManagerThread() {
        // Start our thread...
        // See if there are any EAS accounts; otherwise, just go away
        if (EmailContent.count(this, HostAuth.CONTENT_URI, WHERE_PROTOCOL_EAS, null) > 0) {
            if (sServiceThread == null || !sServiceThread.isAlive()) {
                log(sServiceThread == null ? "Starting thread..." : "Restarting thread...");
                sServiceThread = new Thread(this, "SyncManager");
                INSTANCE = this;
                sServiceThread.start();
            }
        }
    }

    /**
     * Start up the SyncManager service if it's not already running
     * This is a stopgap for cases in which SyncManager died (due to a crash somewhere in
     * com.android.email) and hasn't been restarted. See the comment for onCreate for details
     */
    static void checkSyncManagerServiceRunning() {
        SyncManager syncManager = INSTANCE;
        if (syncManager == null) return;
        if (sServiceThread == null) {
            alwaysLog("!!! checkSyncManagerServiceRunning; starting service...");
            syncManager.startService(new Intent(syncManager, SyncManager.class));
        }
    }

    public void run() {
        sStop = false;
        alwaysLog("!!! SyncManager thread running");
        // If we're really debugging, turn on all logging
        if (Eas.DEBUG) {
            Eas.USER_LOG = true;
            Eas.PARSER_LOG = true;
            Eas.FILE_LOG = true;
        }

        // If we need to wait for the debugger, do so
        if (Eas.WAIT_DEBUG) {
            Debug.waitForDebugger();
        }

        // Synchronize here to prevent a shutdown from happening while we initialize our observers
        // and receivers
        synchronized (sSyncLock) {
            if (INSTANCE != null) {
                mResolver = getContentResolver();

                // Set up our observers; we need them to know when to start/stop various syncs based
                // on the insert/delete/update of mailboxes and accounts
                // We also observe synced messages to trigger upsyncs at the appropriate time
                mAccountObserver = new AccountObserver(mHandler);
                mResolver.registerContentObserver(Account.CONTENT_URI, true, mAccountObserver);
                mMailboxObserver = new MailboxObserver(mHandler);
                mResolver.registerContentObserver(Mailbox.CONTENT_URI, false, mMailboxObserver);
                mSyncedMessageObserver = new SyncedMessageObserver(mHandler);
                mResolver.registerContentObserver(Message.SYNCED_CONTENT_URI, true,
                        mSyncedMessageObserver);
                mMessageObserver = new MessageObserver(mHandler);
                mResolver.registerContentObserver(Message.CONTENT_URI, true, mMessageObserver);
                mSyncStatusObserver = new EasSyncStatusObserver();
                mStatusChangeListener =
                    ContentResolver.addStatusChangeListener(
                            ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, mSyncStatusObserver);

                // Set up our observer for AccountManager
                mAccountsUpdatedListener = new EasAccountsUpdatedListener();
                AccountManager.get(getApplication()).addOnAccountsUpdatedListener(
                        mAccountsUpdatedListener, mHandler, true);

                // Set up receivers for connectivity and background data setting
                mConnectivityReceiver = new ConnectivityReceiver();
                registerReceiver(mConnectivityReceiver, new IntentFilter(
                        ConnectivityManager.CONNECTIVITY_ACTION));

                mBackgroundDataSettingReceiver = new ConnectivityReceiver();
                registerReceiver(mBackgroundDataSettingReceiver, new IntentFilter(
                        ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED));
                // Save away the current background data setting; we'll keep track of it with the
                // receiver we just registered
                ConnectivityManager cm = (ConnectivityManager)getSystemService(
                        Context.CONNECTIVITY_SERVICE);
                mBackgroundData = cm.getBackgroundDataSetting();

                // See if any settings have changed while we weren't running...
                checkPIMSyncSettings();
            }
        }

        try {
            // Loop indefinitely until we're shut down
            while (!sStop) {
                runAwake(SYNC_MANAGER_ID);
                waitForConnectivity();
                mNextWaitReason = "Heartbeat";
                long nextWait = checkMailboxes();
                try {
                    synchronized (this) {
                        if (!mKicked) {
                            if (nextWait < 0) {
                                log("Negative wait? Setting to 1s");
                                nextWait = 1*SECONDS;
                            }
                            if (nextWait > 10*SECONDS) {
                                log("Next awake in " + nextWait / 1000 + "s: " + mNextWaitReason);
                                runAsleep(SYNC_MANAGER_ID, nextWait + (3*SECONDS));
                            }
                            wait(nextWait);
                        }
                    }
                } catch (InterruptedException e) {
                    // Needs to be caught, but causes no problem
                    log("SyncManager interrupted");
                } finally {
                    synchronized (this) {
                        if (mKicked) {
                            //log("Wait deferred due to kick");
                            mKicked = false;
                        }
                    }
                }
            }
            log("Shutdown requested");
        } catch (RuntimeException e) {
            Log.e(TAG, "RuntimeException in SyncManager", e);
            throw e;
        } finally {
            shutdown();
        }
    }

    private void shutdown() {
        synchronized (sSyncLock) {
            // If INSTANCE is null, we've already been shut down
            if (INSTANCE != null) {
                log("SyncManager shutting down...");

                // Stop our running syncs
                stopServiceThreads();

                // Stop receivers
                if (mConnectivityReceiver != null) {
                    unregisterReceiver(mConnectivityReceiver);
                }
                if (mBackgroundDataSettingReceiver != null) {
                    unregisterReceiver(mBackgroundDataSettingReceiver);
                }

                // Unregister observers
                ContentResolver resolver = getContentResolver();
                if (mSyncedMessageObserver != null) {
                    resolver.unregisterContentObserver(mSyncedMessageObserver);
                    mSyncedMessageObserver = null;
                }
                if (mMessageObserver != null) {
                    resolver.unregisterContentObserver(mMessageObserver);
                    mMessageObserver = null;
                }
                if (mAccountObserver != null) {
                    resolver.unregisterContentObserver(mAccountObserver);
                    mAccountObserver = null;
                }
                if (mMailboxObserver != null) {
                    resolver.unregisterContentObserver(mMailboxObserver);
                    mMailboxObserver = null;
                }
                unregisterCalendarObservers();

                // Remove account listener (registered with AccountManager)
                if (mAccountsUpdatedListener != null) {
                    AccountManager.get(this).removeOnAccountsUpdatedListener(
                            mAccountsUpdatedListener);
                    mAccountsUpdatedListener = null;
                }

                // Remove the sync status change listener (and null out the observer)
                if (mStatusChangeListener != null) {
                    ContentResolver.removeStatusChangeListener(mStatusChangeListener);
                    mStatusChangeListener = null;
                    mSyncStatusObserver = null;
                }

                // Clear pending alarms and associated Intents
                clearAlarms();

                // Release our wake lock, if we have one
                synchronized (mWakeLocks) {
                    if (mWakeLock != null) {
                        mWakeLock.release();
                        mWakeLock = null;
                    }
                }

                INSTANCE = null;
                sServiceThread = null;
                sStop = false;
                log("Goodbye");
            }
        }
    }

    private void releaseMailbox(long mailboxId) {
        mServiceMap.remove(mailboxId);
        releaseWakeLock(mailboxId);
    }

    private long checkMailboxes () {
        // First, see if any running mailboxes have been deleted
        ArrayList<Long> deletedMailboxes = new ArrayList<Long>();
        synchronized (sSyncLock) {
            for (long mailboxId: mServiceMap.keySet()) {
                Mailbox m = Mailbox.restoreMailboxWithId(this, mailboxId);
                if (m == null) {
                    deletedMailboxes.add(mailboxId);
                }
            }
            // If so, stop them or remove them from the map
            for (Long mailboxId: deletedMailboxes) {
                AbstractSyncService svc = mServiceMap.get(mailboxId);
                if (svc == null || svc.mThread == null) {
                    releaseMailbox(mailboxId);
                    continue;
                } else {
                    boolean alive = svc.mThread.isAlive();
                    log("Deleted mailbox: " + svc.mMailboxName);
                    if (alive) {
                        stopManualSync(mailboxId);
                    } else {
                        log("Removing from serviceMap");
                        releaseMailbox(mailboxId);
                    }
                }
            }
        }

        long nextWait = SYNC_MANAGER_HEARTBEAT_TIME;
        long now = System.currentTimeMillis();

        // Start up threads that need it; use a query which finds eas mailboxes where the
        // the sync interval is not "never".  This is the set of mailboxes that we control
        if (mAccountObserver == null) {
            log("mAccountObserver null; service died??");
            return nextWait;
        }
        Cursor c = getContentResolver().query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                mAccountObserver.getSyncableEasMailboxWhere(), null, null);

        // Contacts/Calendar obey this setting from ContentResolver
        // Mail is on its own schedule
        boolean masterAutoSync = ContentResolver.getMasterSyncAutomatically();
        try {
            while (c.moveToNext()) {
                long mid = c.getLong(Mailbox.CONTENT_ID_COLUMN);
                AbstractSyncService service = null;
                synchronized (sSyncLock) {
                    service = mServiceMap.get(mid);
                }
                if (service == null) {
                    // We handle a few types of mailboxes specially
                    int type = c.getInt(Mailbox.CONTENT_TYPE_COLUMN);

                    // If background data is off, we only sync Outbox
                    // Manual syncs are initiated elsewhere, so they will continue to be respected
                    if (!mBackgroundData && type != Mailbox.TYPE_OUTBOX) {
                        continue;
                    }

                    if (type == Mailbox.TYPE_CONTACTS || type == Mailbox.TYPE_CALENDAR) {
                        // We don't sync these automatically if master auto sync is off
                        if (!masterAutoSync) {
                            continue;
                        }
                        // Get the right authority for the mailbox
                        String authority;
                        Account account =
                            getAccountById(c.getInt(Mailbox.CONTENT_ACCOUNT_KEY_COLUMN));
                        if (account != null) {
                            if (type == Mailbox.TYPE_CONTACTS) {
                                authority = ContactsContract.AUTHORITY;
                            } else {
                                authority = Calendar.AUTHORITY;
                                if (!mCalendarObservers.containsKey(account.mId)){
                                    // Make sure we have an observer for this Calendar, as
                                    // we need to be able to detect sync state changes, sigh
                                    registerCalendarObserver(account);
                                }
                            }
                            android.accounts.Account a =
                                new android.accounts.Account(account.mEmailAddress,
                                        Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                            // See if "sync automatically" is set; if not, punt
                            if (!ContentResolver.getSyncAutomatically(a, authority)) {
                                continue;
                            // See if the calendar is enabled; if not, punt
                            } else if ((type == Mailbox.TYPE_CALENDAR) &&
                                    !isCalendarEnabled(account.mId)) {
                                continue;
                            }
                        }
                    } else if (type == Mailbox.TYPE_TRASH) {
                        continue;
                    }

                    // Check whether we're in a hold (temporary or permanent)
                    SyncError syncError = mSyncErrorMap.get(mid);
                    if (syncError != null) {
                        // Nothing we can do about fatal errors
                        if (syncError.fatal) continue;
                        if (now < syncError.holdEndTime) {
                            // If release time is earlier than next wait time,
                            // move next wait time up to the release time
                            if (syncError.holdEndTime < now + nextWait) {
                                nextWait = syncError.holdEndTime - now;
                                mNextWaitReason = "Release hold";
                            }
                            continue;
                        } else {
                            // Keep the error around, but clear the end time
                            syncError.holdEndTime = 0;
                        }
                    }

                    // Otherwise, we use the sync interval
                    long interval = c.getInt(Mailbox.CONTENT_SYNC_INTERVAL_COLUMN);
                    if (interval == Mailbox.CHECK_INTERVAL_PUSH) {
                        Mailbox m = EmailContent.getContent(c, Mailbox.class);
                        requestSync(m, SYNC_PUSH, null);
                    } else if (type == Mailbox.TYPE_OUTBOX) {
                        int cnt = EmailContent.count(this, Message.CONTENT_URI,
                                EasOutboxService.MAILBOX_KEY_AND_NOT_SEND_FAILED,
                                new String[] {Long.toString(mid)});
                        if (cnt > 0) {
                            Mailbox m = EmailContent.getContent(c, Mailbox.class);
                            startServiceThread(new EasOutboxService(this, m), m);
                        }
                    } else if (interval > 0 && interval <= ONE_DAY_MINUTES) {
                        long lastSync = c.getLong(Mailbox.CONTENT_SYNC_TIME_COLUMN);
                        long sinceLastSync = now - lastSync;
                        if (sinceLastSync < 0) {
                            log("WHOA! lastSync in the future for mailbox: " + mid);
                            sinceLastSync = interval*MINUTES;
                        }
                        long toNextSync = interval*MINUTES - sinceLastSync;
                        String name = c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN);
                        if (toNextSync <= 0) {
                            Mailbox m = EmailContent.getContent(c, Mailbox.class);
                            requestSync(m, SYNC_SCHEDULED, null);
                        } else if (toNextSync < nextWait) {
                            nextWait = toNextSync;
                            if (Eas.USER_LOG) {
                                log("Next sync for " + name + " in " + nextWait/1000 + "s");
                            }
                            mNextWaitReason = "Scheduled sync, " + name;
                        } else if (Eas.USER_LOG) {
                            log("Next sync for " + name + " in " + toNextSync/1000 + "s");
                        }
                    }
                } else {
                    Thread thread = service.mThread;
                    // Look for threads that have died and remove them from the map
                    if (thread != null && !thread.isAlive()) {
                        if (Eas.USER_LOG) {
                            log("Dead thread, mailbox released: " +
                                    c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN));
                        }
                        releaseMailbox(mid);
                        // Restart this if necessary
                        if (nextWait > 3*SECONDS) {
                            nextWait = 3*SECONDS;
                            mNextWaitReason = "Clean up dead thread(s)";
                        }
                    } else {
                        long requestTime = service.mRequestTime;
                        if (requestTime > 0) {
                            long timeToRequest = requestTime - now;
                            if (timeToRequest <= 0) {
                                service.mRequestTime = 0;
                                service.alarm();
                            } else if (requestTime > 0 && timeToRequest < nextWait) {
                                if (timeToRequest < 11*MINUTES) {
                                    nextWait = timeToRequest < 250 ? 250 : timeToRequest;
                                    mNextWaitReason = "Sync data change";
                                } else {
                                    log("Illegal timeToRequest: " + timeToRequest);
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            c.close();
        }
        return nextWait;
    }

    static public void serviceRequest(long mailboxId, int reason) {
        serviceRequest(mailboxId, 5*SECONDS, reason);
    }

    static public void serviceRequest(long mailboxId, long ms, int reason) {
        SyncManager syncManager = INSTANCE;
        if (syncManager == null) return;
        Mailbox m = Mailbox.restoreMailboxWithId(syncManager, mailboxId);
        // Never allow manual start of Drafts or Outbox via serviceRequest
        if (m == null || m.mType == Mailbox.TYPE_DRAFTS || m.mType == Mailbox.TYPE_OUTBOX) {
            log("Ignoring serviceRequest for drafts/outbox/null mailbox");
            return;
        }
        try {
            AbstractSyncService service = syncManager.mServiceMap.get(mailboxId);
            if (service != null) {
                service.mRequestTime = System.currentTimeMillis() + ms;
                kick("service request");
            } else {
                startManualSync(mailboxId, reason, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static public void serviceRequestImmediate(long mailboxId) {
        SyncManager syncManager = INSTANCE;
        if (syncManager == null) return;
        AbstractSyncService service = syncManager.mServiceMap.get(mailboxId);
        if (service != null) {
            service.mRequestTime = System.currentTimeMillis();
            Mailbox m = Mailbox.restoreMailboxWithId(syncManager, mailboxId);
            if (m != null) {
                service.mAccount = Account.restoreAccountWithId(syncManager, m.mAccountKey);
                service.mMailbox = m;
                kick("service request immediate");
            }
        }
    }

    static public void sendMessageRequest(Request req) {
        SyncManager syncManager = INSTANCE;
        if (syncManager == null) return;
        Message msg = Message.restoreMessageWithId(syncManager, req.mMessageId);
        if (msg == null) {
            return;
        }
        long mailboxId = msg.mMailboxKey;
        AbstractSyncService service = syncManager.mServiceMap.get(mailboxId);

        if (service == null) {
            service = startManualSync(mailboxId, SYNC_SERVICE_PART_REQUEST, req);
            kick("part request");
        } else {
            service.addRequest(req);
        }
    }

    /**
     * Determine whether a given Mailbox can be synced, i.e. is not already syncing and is not in
     * an error state
     *
     * @param mailboxId
     * @return whether or not the Mailbox is available for syncing (i.e. is a valid push target)
     */
    static public int pingStatus(long mailboxId) {
        SyncManager syncManager = INSTANCE;
        if (syncManager == null) return PING_STATUS_OK;
        // Already syncing...
        if (syncManager.mServiceMap.get(mailboxId) != null) {
            return PING_STATUS_RUNNING;
        }
        // No errors or a transient error, don't ping...
        SyncError error = syncManager.mSyncErrorMap.get(mailboxId);
        if (error != null) {
            if (error.fatal) {
                return PING_STATUS_UNABLE;
            } else if (error.holdEndTime > 0) {
                return PING_STATUS_WAITING;
            }
        }
        return PING_STATUS_OK;
    }

    static public AbstractSyncService startManualSync(long mailboxId, int reason, Request req) {
        SyncManager syncManager = INSTANCE;
        if (syncManager == null) return null;
        synchronized (sSyncLock) {
            if (syncManager.mServiceMap.get(mailboxId) == null) {
                syncManager.mSyncErrorMap.remove(mailboxId);
                Mailbox m = Mailbox.restoreMailboxWithId(syncManager, mailboxId);
                if (m != null) {
                    log("Starting sync for " + m.mDisplayName);
                    syncManager.requestSync(m, reason, req);
                }
            }
        }
        return syncManager.mServiceMap.get(mailboxId);
    }

    // DO NOT CALL THIS IN A LOOP ON THE SERVICEMAP
    static private void stopManualSync(long mailboxId) {
        SyncManager syncManager = INSTANCE;
        if (syncManager == null) return;
        synchronized (sSyncLock) {
            AbstractSyncService svc = syncManager.mServiceMap.get(mailboxId);
            if (svc != null) {
                log("Stopping sync for " + svc.mMailboxName);
                svc.stop();
                svc.mThread.interrupt();
                syncManager.releaseWakeLock(mailboxId);
            }
        }
    }

    /**
     * Wake up SyncManager to check for mailboxes needing service
     */
    static public void kick(String reason) {
       SyncManager syncManager = INSTANCE;
       if (syncManager != null) {
            synchronized (syncManager) {
                //INSTANCE.log("Kick: " + reason);
                syncManager.mKicked = true;
                syncManager.notify();
            }
        }
        if (sConnectivityLock != null) {
            synchronized (sConnectivityLock) {
                sConnectivityLock.notify();
            }
        }
    }

    static public void accountUpdated(long acctId) {
        SyncManager syncManager = INSTANCE;
        if (syncManager == null) return;
        synchronized (sSyncLock) {
            for (AbstractSyncService svc : syncManager.mServiceMap.values()) {
                if (svc.mAccount.mId == acctId) {
                    svc.mAccount = Account.restoreAccountWithId(syncManager, acctId);
                }
            }
        }
    }

    /**
     * Tell SyncManager to remove the mailbox from the map of mailboxes with sync errors
     * @param mailboxId the id of the mailbox
     */
    static public void removeFromSyncErrorMap(long mailboxId) {
        SyncManager syncManager = INSTANCE;
        if (syncManager == null) return;
        synchronized(sSyncLock) {
            syncManager.mSyncErrorMap.remove(mailboxId);
        }
    }

    /**
     * Sent by services indicating that their thread is finished; action depends on the exitStatus
     * of the service.
     *
     * @param svc the service that is finished
     */
    static public void done(AbstractSyncService svc) {
        SyncManager syncManager = INSTANCE;
        if (syncManager == null) return;
        synchronized(sSyncLock) {
            long mailboxId = svc.mMailboxId;
            HashMap<Long, SyncError> errorMap = syncManager.mSyncErrorMap;
            SyncError syncError = errorMap.get(mailboxId);
            syncManager.releaseMailbox(mailboxId);
            int exitStatus = svc.mExitStatus;
            switch (exitStatus) {
                case AbstractSyncService.EXIT_DONE:
                    if (!svc.mRequests.isEmpty()) {
                        // TODO Handle this case
                    }
                    errorMap.remove(mailboxId);
                    // If we've had a successful sync, clear the shutdown count
                    synchronized (SyncManager.class) {
                        sClientConnectionManagerShutdownCount = 0;
                    }
                    break;
                // I/O errors get retried at increasing intervals
                case AbstractSyncService.EXIT_IO_ERROR:
                    Mailbox m = Mailbox.restoreMailboxWithId(syncManager, mailboxId);
                    if (m == null) return;
                    if (syncError != null) {
                        syncError.escalate();
                        log(m.mDisplayName + " held for " + syncError.holdDelay + "ms");
                    } else {
                        errorMap.put(mailboxId, syncManager.new SyncError(exitStatus, false));
                        log(m.mDisplayName + " added to syncErrorMap, hold for 15s");
                    }
                    break;
                // These errors are not retried automatically
                case AbstractSyncService.EXIT_SECURITY_FAILURE:
                case AbstractSyncService.EXIT_LOGIN_FAILURE:
                case AbstractSyncService.EXIT_EXCEPTION:
                    errorMap.put(mailboxId, syncManager.new SyncError(exitStatus, true));
                    break;
            }
            kick("sync completed");
        }
    }

    /**
     * Given the status string from a Mailbox, return the type code for the last sync
     * @param status the syncStatus column of a Mailbox
     * @return
     */
    static public int getStatusType(String status) {
        if (status == null) {
            return -1;
        } else {
            return status.charAt(STATUS_TYPE_CHAR) - '0';
        }
    }

    /**
     * Given the status string from a Mailbox, return the change count for the last sync
     * The change count is the number of adds + deletes + changes in the last sync
     * @param status the syncStatus column of a Mailbox
     * @return
     */
    static public int getStatusChangeCount(String status) {
        try {
            String s = status.substring(STATUS_CHANGE_COUNT_OFFSET);
            return Integer.parseInt(s);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static public Context getContext() {
        return INSTANCE;
    }
}
