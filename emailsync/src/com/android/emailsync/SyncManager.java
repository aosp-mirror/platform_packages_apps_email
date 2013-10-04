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

package com.android.emailsync;

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
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Process;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.ContactsContract;

import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.BodyColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.provider.ProviderUnavailableException;
import com.android.emailcommon.service.AccountServiceProxy;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.IEmailServiceCallback.Stub;
import com.android.emailcommon.service.PolicyServiceProxy;
import com.android.emailcommon.utility.EmailClientConnectionManager;
import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogUtils;

import org.apache.http.conn.params.ConnManagerPNames;
import org.apache.http.conn.params.ConnPerRoute;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The SyncServiceManager handles the lifecycle of various sync adapters used by services that
 * cannot rely on the system SyncManager
 *
 * SyncServiceManager uses ContentObservers to detect changes to accounts, mailboxes, & messages in
 * order to maintain proper 2-way syncing of data.  (More documentation to follow)
 *
 */
public abstract class SyncManager extends Service implements Runnable {

    private static String TAG = "SyncManager";

    // The SyncServiceManager's mailbox "id"
    public static final int EXTRA_MAILBOX_ID = -1;
    public static final int SYNC_SERVICE_MAILBOX_ID = 0;

    private static final int SECONDS = 1000;
    private static final int MINUTES = 60*SECONDS;
    private static final int ONE_DAY_MINUTES = 1440;

    private static final int SYNC_SERVICE_HEARTBEAT_TIME = 15*MINUTES;
    private static final int CONNECTIVITY_WAIT_TIME = 10*MINUTES;

    // Sync hold constants for services with transient errors
    private static final int HOLD_DELAY_MAXIMUM = 4*MINUTES;

    // Reason codes when SyncServiceManager.kick is called (mainly for debugging)
    // UI has changed data, requiring an upsync of changes
    public static final int SYNC_UPSYNC = 0;
    // A scheduled sync (when not using push)
    public static final int SYNC_SCHEDULED = 1;
    // Mailbox was marked push
    public static final int SYNC_PUSH = 2;
    // A ping (EAS push signal) was received
    public static final int SYNC_PING = 3;
    // Misc.
    public static final int SYNC_KICK = 4;
    // A part request (attachment load, for now) was sent to SyncServiceManager
    public static final int SYNC_SERVICE_PART_REQUEST = 5;

    // Requests >= SYNC_CALLBACK_START generate callbacks to the UI
    public static final int SYNC_CALLBACK_START = 6;
    // startSync was requested of SyncServiceManager (other than due to user request)
    public static final int SYNC_SERVICE_START_SYNC = SYNC_CALLBACK_START + 0;
    // startSync was requested of SyncServiceManager (due to user request)
    public static final int SYNC_UI_REQUEST = SYNC_CALLBACK_START + 1;

    protected static final String WHERE_IN_ACCOUNT_AND_PUSHABLE =
        MailboxColumns.ACCOUNT_KEY + "=? and type in (" + Mailbox.TYPE_INBOX + ','
        + Mailbox.TYPE_EAS_ACCOUNT_MAILBOX + ',' + Mailbox.TYPE_CONTACTS + ','
        + Mailbox.TYPE_CALENDAR + ')';
    protected static final String WHERE_IN_ACCOUNT_AND_TYPE_INBOX =
        MailboxColumns.ACCOUNT_KEY + "=? and type = " + Mailbox.TYPE_INBOX ;
    private static final String WHERE_MAILBOX_KEY = Message.MAILBOX_KEY + "=?";
    private static final String WHERE_NOT_INTERVAL_NEVER_AND_ACCOUNT_KEY_IN =
        "(" + MailboxColumns.TYPE + '=' + Mailbox.TYPE_OUTBOX
        + " or " + MailboxColumns.SYNC_INTERVAL + "<" + Mailbox.CHECK_INTERVAL_NEVER + ')'
        + " and " + MailboxColumns.ACCOUNT_KEY + " in (";

    public static final int SEND_FAILED = 1;
    public static final String MAILBOX_KEY_AND_NOT_SEND_FAILED =
            MessageColumns.MAILBOX_KEY + "=? and (" + SyncColumns.SERVER_ID + " is null or " +
            SyncColumns.SERVER_ID + "!=" + SEND_FAILED + ')';

    public static final String CALENDAR_SELECTION =
            Calendars.ACCOUNT_NAME + "=? AND " + Calendars.ACCOUNT_TYPE + "=?";
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
    // Service is disabled by user (checkbox)
    public static final int PING_STATUS_DISABLED = 4;

    private static final int MAX_CLIENT_CONNECTION_MANAGER_SHUTDOWNS = 1;

    // We synchronize on this for all actions affecting the service and error maps
    private static final Object sSyncLock = new Object();
    // All threads can use this lock to wait for connectivity
    public static final Object sConnectivityLock = new Object();
    public static boolean sConnectivityHold = false;

    // Keeps track of running services (by mailbox id)
    public final HashMap<Long, AbstractSyncService> mServiceMap =
        new HashMap<Long, AbstractSyncService>();
    // Keeps track of services whose last sync ended with an error (by mailbox id)
    /*package*/ public ConcurrentHashMap<Long, SyncError> mSyncErrorMap =
        new ConcurrentHashMap<Long, SyncError>();
    // Keeps track of which services require a wake lock (by mailbox id)
    private final HashMap<Long, Long> mWakeLocks = new HashMap<Long, Long>();
    // Keeps track of which services have held a wake lock (by mailbox id)
    private final HashMap<Long, Long> mWakeLocksHistory = new HashMap<Long, Long>();
    // Keeps track of PendingIntents for mailbox alarms (by mailbox id)
    private final HashMap<Long, PendingIntent> mPendingIntents = new HashMap<Long, PendingIntent>();
    // The actual WakeLock obtained by SyncServiceManager
    private WakeLock mWakeLock = null;
    // Keep our cached list of active Accounts here
    public final AccountList mAccountList = new AccountList();
    // Keep track of when we started up
    private long mServiceStartTime;

    // Observers that we use to look for changed mail-related data
    private final Handler mHandler = new Handler();
    private AccountObserver mAccountObserver;
    private MailboxObserver mMailboxObserver;
    private SyncedMessageObserver mSyncedMessageObserver;

    // Concurrent because CalendarSyncAdapter can modify the map during a wipe
    private final ConcurrentHashMap<Long, CalendarObserver> mCalendarObservers =
        new ConcurrentHashMap<Long, CalendarObserver>();

    public ContentResolver mResolver;

    // The singleton SyncServiceManager object, with its thread and stop flag
    protected static SyncManager INSTANCE;
    protected static Thread sServiceThread = null;
    // Cached unique device id
    protected static String sDeviceId = null;
    // HashMap of ConnectionManagers that all EAS threads can use (by HostAuth id)
    private static HashMap<Long, EmailClientConnectionManager> sClientConnectionManagers =
            new HashMap<Long, EmailClientConnectionManager>();
    // Count of ClientConnectionManager shutdowns
    private static volatile int sClientConnectionManagerShutdownCount = 0;

    private static volatile boolean sStartingUp = false;
    private static volatile boolean sStop = false;

    // The reason for SyncServiceManager's next wakeup call
    private String mNextWaitReason;
    // Whether we have an unsatisfied "kick" pending
    private boolean mKicked = false;

    // Receiver of connectivity broadcasts
    private ConnectivityReceiver mConnectivityReceiver = null;
    // The most current NetworkInfo (from ConnectivityManager)
    private NetworkInfo mNetworkInfo;

    // For sync logging
    protected static boolean sUserLog = false;
    protected static boolean sFileLog = false;

    /**
     * Return an AccountObserver for this manager; the subclass must implement the newAccount()
     * method, which is called whenever the observer discovers that a new account has been created.
     * The subclass should do any housekeeping necessary
     * @param handler a Handler
     * @return the AccountObserver
     */
    public abstract AccountObserver getAccountObserver(Handler handler);

    /**
     * Perform any housekeeping necessary upon startup of the manager
     */
    public abstract void onStartup();

    /**
     * Returns a String that can be used as a WHERE clause in SQLite that selects accounts whose
     * syncs are managed by this manager
     * @return the account selector String
     */
    public abstract String getAccountsSelector();

    /**
     * Returns an appropriate sync service for the passed in mailbox
     * @param context the caller's context
     * @param mailbox the Mailbox to be synced
     * @return a service that will sync the Mailbox
     */
    public abstract AbstractSyncService getServiceForMailbox(Context context, Mailbox mailbox);

    /**
     * Return a list of all Accounts in EmailProvider.  Because the result of this call may be used
     * in account reconciliation, an exception is thrown if the result cannot be guaranteed accurate
     * @param context the caller's context
     * @param accounts a list that Accounts will be added into
     * @return the list of Accounts
     * @throws ProviderUnavailableException if the list of Accounts cannot be guaranteed valid
     */
    public abstract AccountList collectAccounts(Context context, AccountList accounts);

    /**
     * Returns the AccountManager type (e.g. com.android.exchange) for this sync service
     */
    public abstract String getAccountManagerType();

    /**
     * Returns the intent used for this sync service
     */
    public abstract Intent getServiceIntent();

    /**
     * Returns the callback proxy used for communicating back with the Email app
     */
    public abstract Stub getCallbackProxy();

    /**
     * Called when a sync service has started (in case any action is needed). This method must
     * not perform any long-lived actions (db access, network access, etc)
     */
    public abstract void onStartService(Mailbox mailbox);

    public class AccountList extends ArrayList<Account> {
        private static final long serialVersionUID = 1L;

        private final WeakHashMap<Account, android.accounts.Account> mAmMap =
                new WeakHashMap<Account, android.accounts.Account>();

        @Override
        public boolean add(Account account) {
            // Cache the account manager account
            mAmMap.put(account, account.getAccountManagerAccount(getAccountManagerType()));
            super.add(account);
            return true;
        }

        public android.accounts.Account getAmAccount(Account account) {
            return mAmMap.get(account);
        }

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

        public Account getByName(String accountName) {
            for (Account account : this) {
                if (account.mEmailAddress.equalsIgnoreCase(accountName)) {
                    return account;
                }
            }
            return null;
        }
    }

    public static void setUserDebug(int state) {
        sUserLog = (state & EmailServiceProxy.DEBUG_BIT) != 0;
        sFileLog = (state & EmailServiceProxy.DEBUG_FILE_BIT) != 0;
        if (sFileLog) {
            sUserLog = true;
        }
        LogUtils.d("Sync Debug", "Logging: " + (sUserLog ? "User " : "")
                + (sFileLog ? "File" : ""));
    }

    private static boolean onSecurityHold(Account account) {
        return (account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0;
    }

    public static String getAccountSelector() {
        SyncManager ssm = INSTANCE;
        if (ssm == null) return "";
        return ssm.getAccountsSelector();
    }

    public abstract class AccountObserver extends ContentObserver {
        String mSyncableMailboxSelector = null;
        String mAccountSelector = null;

        // Runs when SyncServiceManager first starts
        @SuppressWarnings("deprecation")
        public AccountObserver(Handler handler) {
            super(handler);
            // At startup, we want to see what EAS accounts exist and cache them
            // TODO: Move database work out of UI thread
            Context context = getContext();
            synchronized (mAccountList) {
                try {
                    collectAccounts(context, mAccountList);
                } catch (ProviderUnavailableException e) {
                    // Just leave if EmailProvider is unavailable
                    return;
                }
                // Create an account mailbox for any account without one
                for (Account account : mAccountList) {
                    int cnt = Mailbox.count(context, Mailbox.CONTENT_URI, "accountKey="
                            + account.mId, null);
                    if (cnt == 0) {
                        // This case handles a newly created account
                        newAccount(account.mId);
                    }
                }
            }
            // Run through accounts and update account hold information
            Utility.runAsync(new Runnable() {
                @Override
                public void run() {
                    synchronized (mAccountList) {
                        for (Account account : mAccountList) {
                            if (onSecurityHold(account)) {
                                // If we're in a security hold, and our policies are active, release
                                // the hold
                                if (PolicyServiceProxy.isActive(SyncManager.this, null)) {
                                    PolicyServiceProxy.setAccountHoldFlag(SyncManager.this,
                                            account, false);
                                    log("isActive true; release hold for " + account.mDisplayName);
                                }
                            }
                        }
                    }
                }});
        }

        /**
         * Returns a String suitable for appending to a where clause that selects for all syncable
         * mailboxes in all eas accounts
         * @return a complex selection string that is not to be cached
         */
        public String getSyncableMailboxWhere() {
            if (mSyncableMailboxSelector == null) {
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
                mSyncableMailboxSelector = sb.toString();
            }
            return mSyncableMailboxSelector;
        }

        private void onAccountChanged() {
            try {
                maybeStartSyncServiceManagerThread();
                Context context = getContext();

                // A change to the list requires us to scan for deletions (stop running syncs)
                // At startup, we want to see what accounts exist and cache them
                AccountList currentAccounts = new AccountList();
                try {
                    collectAccounts(context, currentAccounts);
                } catch (ProviderUnavailableException e) {
                    // Just leave if EmailProvider is unavailable
                    return;
                }
                synchronized (mAccountList) {
                    for (Account account : mAccountList) {
                        boolean accountIncomplete =
                            (account.mFlags & Account.FLAGS_INCOMPLETE) != 0;
                        // If the current list doesn't include this account and the account wasn't
                        // incomplete, then this is a deletion
                        if (!currentAccounts.contains(account.mId) && !accountIncomplete) {
                            // The implication is that the account has been deleted; let's find out
                            alwaysLog("Observer found deleted account: " + account.mDisplayName);
                            // Run the reconciler (the reconciliation itself runs in the Email app)
                            runAccountReconcilerSync(SyncManager.this);
                            // See if the account is still around
                            Account deletedAccount =
                                Account.restoreAccountWithId(context, account.mId);
                            if (deletedAccount != null) {
                                // It is; add it to our account list
                                alwaysLog("Account still in provider: " + account.mDisplayName);
                                currentAccounts.add(account);
                            } else {
                                // It isn't; stop syncs and clear our selectors
                                alwaysLog("Account deletion confirmed: " + account.mDisplayName);
                                stopAccountSyncs(account.mId, true);
                                mSyncableMailboxSelector = null;
                                mAccountSelector = null;
                            }
                        } else {
                            // Get the newest version of this account
                            Account updatedAccount =
                                Account.restoreAccountWithId(context, account.mId);
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
                            newAccount(account.mId);
                            mAccountList.add(account);
                            mSyncableMailboxSelector = null;
                            mAccountSelector = null;
                        }
                    }
                    // Finally, make sure our account list is up to date
                    mAccountList.clear();
                    mAccountList.addAll(currentAccounts);
                }

                // See if there's anything to do...
                kick("account changed");
            } catch (ProviderUnavailableException e) {
                alwaysLog("Observer failed; provider unavailable");
            }
        }

        @Override
        public void onChange(boolean selfChange) {
            new Thread(new Runnable() {
               @Override
            public void run() {
                   onAccountChanged();
                }}, "Account Observer").start();
        }

        public abstract void newAccount(long acctId);
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
        SyncManager ssm = INSTANCE;
        if (ssm == null) return;
        ContentResolver resolver = ssm.mResolver;
        for (CalendarObserver observer: ssm.mCalendarObservers.values()) {
            resolver.unregisterContentObserver(observer);
        }
        ssm.mCalendarObservers.clear();
    }

    public static Uri asSyncAdapter(Uri uri, String account, String accountType) {
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType).build();
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
        final long mAccountId;
        final String mAccountName;
        long mCalendarId;
        long mSyncEvents;

        public CalendarObserver(Handler handler, Account account) {
            super(handler);
            mAccountId = account.mId;
            mAccountName = account.mEmailAddress;
            // Find the Calendar for this account
            Cursor c = mResolver.query(Calendars.CONTENT_URI,
                    new String[] {Calendars._ID, Calendars.SYNC_EVENTS},
                    CALENDAR_SELECTION,
                    new String[] {account.mEmailAddress, getAccountManagerType()},
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
                    @Override
                    public void run() {
                        try {
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
                                        ContentValues cv = new ContentValues();
                                        if (newSyncEvents == 0) {
                                            // When sync is disabled, we're supposed to delete
                                            // all events in the calendar
                                            log("Deleting events and setting syncKey to 0 for " +
                                                    mAccountName);
                                            // First, stop any sync that's ongoing
                                            stopManualSync(mailbox.mId);
                                            // Set the syncKey to 0 (reset)
                                            AbstractSyncService service = getServiceForMailbox(
                                                    INSTANCE, mailbox);
                                            service.resetCalendarSyncKey();
                                            // Reset the sync key locally and stop syncing
                                            cv.put(Mailbox.SYNC_KEY, "0");
                                            cv.put(Mailbox.SYNC_INTERVAL,
                                                    Mailbox.CHECK_INTERVAL_NEVER);
                                            mResolver.update(ContentUris.withAppendedId(
                                                    Mailbox.CONTENT_URI, mailbox.mId), cv, null,
                                                    null);
                                            // Delete all events using the sync adapter
                                            // parameter so that the deletion is only local
                                            Uri eventsAsSyncAdapter =
                                                asSyncAdapter(
                                                    Events.CONTENT_URI,
                                                    mAccountName,
                                                    getAccountManagerType());
                                            mResolver.delete(eventsAsSyncAdapter, WHERE_CALENDAR_ID,
                                                    new String[] {Long.toString(mCalendarId)});
                                        } else {
                                            // Make this a push mailbox and kick; this will start
                                            // a resync of the Calendar; the account mailbox will
                                            // ping on this during the next cycle of the ping loop
                                            cv.put(Mailbox.SYNC_INTERVAL,
                                                    Mailbox.CHECK_INTERVAL_PUSH);
                                            mResolver.update(ContentUris.withAppendedId(
                                                    Mailbox.CONTENT_URI, mailbox.mId), cv, null,
                                                    null);
                                            kick("calendar sync changed");
                                        }

                                        // Save away the new value
                                        mSyncEvents = newSyncEvents;
                                    }
                                }
                            } finally {
                                c.close();
                            }
                        } catch (ProviderUnavailableException e) {
                            LogUtils.w(TAG, "Observer failed; provider unavailable");
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
            alarmManager.set(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 10*SECONDS, syncAlarmPendingIntent);
        }
    }

    static public Account getAccountById(long accountId) {
        SyncManager ssm = INSTANCE;
        if (ssm != null) {
            AccountList accountList = ssm.mAccountList;
            synchronized (accountList) {
                return accountList.getById(accountId);
            }
        }
        return null;
    }

    static public Account getAccountByName(String accountName) {
        SyncManager ssm = INSTANCE;
        if (ssm != null) {
            AccountList accountList = ssm.mAccountList;
            synchronized (accountList) {
                return accountList.getByName(accountName);
            }
        }
        return null;
    }

    public class SyncStatus {
        static public final int NOT_RUNNING = 0;
        static public final int DIED = 1;
        static public final int SYNC = 2;
        static public final int IDLE = 3;
    }

    /*package*/ public class SyncError {
        int reason;
        public boolean fatal = false;
        long holdDelay = 15*SECONDS;
        public long holdEndTime = System.currentTimeMillis() + holdDelay;

        public SyncError(int _reason, boolean _fatal) {
            reason = _reason;
            fatal = _fatal;
        }

        /**
         * We double the holdDelay from 15 seconds through 8 mins
         */
        void escalate() {
            if (holdDelay <= HOLD_DELAY_MAXIMUM) {
                holdDelay *= 2;
            }
            holdEndTime = System.currentTimeMillis() + holdDelay;
        }
    }

    private void logSyncHolds() {
        if (sUserLog) {
            log("Sync holds:");
            long time = System.currentTimeMillis();
            for (long mailboxId : mSyncErrorMap.keySet()) {
                Mailbox m = Mailbox.restoreMailboxWithId(this, mailboxId);
                if (m == null) {
                    log("Mailbox " + mailboxId + " no longer exists");
                } else {
                    SyncError error = mSyncErrorMap.get(mailboxId);
                    if (error != null) {
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
        SyncManager ssm = INSTANCE;
        if (ssm != null) {
            ssm.releaseSyncHolds(INSTANCE, AbstractSyncService.EXIT_SECURITY_FAILURE,
                    account);
        }
    }

    /**
     * Release a specific type of hold (the reason) for the specified Account; if the account
     * is null, mailboxes from all accounts with the specified hold will be released
     * @param reason the reason for the SyncError (AbstractSyncService.EXIT_XXX)
     * @param account an Account whose mailboxes should be released (or all if null)
     * @return whether or not any mailboxes were released
     */
    public /*package*/ boolean releaseSyncHolds(Context context, int reason, Account account) {
        boolean holdWasReleased = releaseSyncHoldsImpl(context, reason, account);
        kick("security release");
        return holdWasReleased;
    }

    private boolean releaseSyncHoldsImpl(Context context, int reason, Account account) {
        boolean holdWasReleased = false;
        for (long mailboxId: mSyncErrorMap.keySet()) {
            if (account != null) {
                Mailbox m = Mailbox.restoreMailboxWithId(context, mailboxId);
                if (m == null) {
                    mSyncErrorMap.remove(mailboxId);
                } else if (m.mAccountKey != account.mId) {
                    continue;
                }
            }
            SyncError error = mSyncErrorMap.get(mailboxId);
            if (error != null && error.reason == reason) {
                mSyncErrorMap.remove(mailboxId);
                holdWasReleased = true;
            }
        }
        return holdWasReleased;
    }

    public static void log(String str) {
        log(TAG, str);
    }

    public static void log(String tag, String str) {
        if (sUserLog) {
            LogUtils.d(tag, str);
            if (sFileLog) {
                FileLogger.log(tag, str);
            }
        }
    }

    public static void alwaysLog(String str) {
        if (!sUserLog) {
            LogUtils.d(TAG, str);
        } else {
            log(str);
        }
    }

    /**
     * EAS requires a unique device id, so that sync is possible from a variety of different
     * devices (e.g. the syncKey is specific to a device)  If we're on an emulator or some other
     * device that doesn't provide one, we can create it as "device".
     * This would work on a real device as well, but it would be better to use the "real" id if
     * it's available
     */
    static public String getDeviceId(Context context) {
        if (sDeviceId == null) {
            sDeviceId = new AccountServiceProxy(context).getDeviceId();
            alwaysLog("Received deviceId from Email app: " + sDeviceId);
        }
        return sDeviceId;
    }

    static public ConnPerRoute sConnPerRoute = new ConnPerRoute() {
        @Override
        public int getMaxForRoute(HttpRoute route) {
            return 8;
        }
    };

    static public synchronized EmailClientConnectionManager getClientConnectionManager(
            Context context, HostAuth hostAuth) {
        // We'll use a different connection manager for each HostAuth
        EmailClientConnectionManager mgr = null;
        // We don't save managers for validation/autodiscover
        if (hostAuth.mId != HostAuth.NOT_SAVED) {
            mgr = sClientConnectionManagers.get(hostAuth.mId);
        }
        if (mgr == null) {
            // After two tries, kill the process.  Most likely, this will happen in the background
            // The service will restart itself after about 5 seconds
            if (sClientConnectionManagerShutdownCount > MAX_CLIENT_CONNECTION_MANAGER_SHUTDOWNS) {
                alwaysLog("Shutting down process to unblock threads");
                Process.killProcess(Process.myPid());
            }
            HttpParams params = new BasicHttpParams();
            params.setIntParameter(ConnManagerPNames.MAX_TOTAL_CONNECTIONS, 25);
            params.setParameter(ConnManagerPNames.MAX_CONNECTIONS_PER_ROUTE, sConnPerRoute);
            boolean ssl = hostAuth.shouldUseSsl();
            int port = hostAuth.mPort;
            mgr = EmailClientConnectionManager.newInstance(context, params, hostAuth);
            log("Creating connection manager for port " + port + ", ssl: " + ssl);
            sClientConnectionManagers.put(hostAuth.mId, mgr);
        }
        // Null is a valid return result if we get an exception
        return mgr;
    }

    static private synchronized void shutdownConnectionManager() {
        log("Shutting down ClientConnectionManagers");
        for (EmailClientConnectionManager mgr: sClientConnectionManagers.values()) {
            mgr.shutdown();
        }
        sClientConnectionManagers.clear();
    }

    public static void stopAccountSyncs(long acctId) {
        SyncManager ssm = INSTANCE;
        if (ssm != null) {
            ssm.stopAccountSyncs(acctId, true);
        }
    }

    public void stopAccountSyncs(long acctId, boolean includeAccountMailbox) {
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

    /**
     * Informs SyncServiceManager that an account has a new folder list; as a result, any existing
     * folder might have become invalid.  Therefore, we act as if the account has been deleted, and
     * then we reinitialize it.
     *
     * @param acctId
     */
    static public void stopNonAccountMailboxSyncsForAccount(long acctId) {
        SyncManager ssm = INSTANCE;
        if (ssm != null) {
            ssm.stopAccountSyncs(acctId, false);
            kick("reload folder list");
        }
    }

    private boolean hasWakeLock(long id) {
        synchronized (mWakeLocks) {
            return mWakeLocks.get(id) != null;
        }
    }

    private void acquireWakeLock(long id) {
        synchronized (mWakeLocks) {
            Long lock = mWakeLocks.get(id);
            if (lock == null) {
                if (mWakeLock == null) {
                    PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MAIL_SERVICE");
                    mWakeLock.acquire();
                    // STOPSHIP Remove
                    log("+WAKE LOCK ACQUIRED");
                }
                mWakeLocks.put(id, System.currentTimeMillis());
             }
        }
    }

    private void releaseWakeLock(long id) {
        synchronized (mWakeLocks) {
            Long lock = mWakeLocks.get(id);
            if (lock != null) {
                Long startTime = mWakeLocks.remove(id);
                Long historicalTime = mWakeLocksHistory.get(id);
                if (historicalTime == null) {
                    historicalTime = 0L;
                }
                mWakeLocksHistory.put(id,
                        historicalTime + (System.currentTimeMillis() - startTime));
                if (mWakeLocks.isEmpty()) {
                    if (mWakeLock != null) {
                        mWakeLock.release();
                    }
                    mWakeLock = null;
                    // STOPSHIP Remove
                    log("+WAKE LOCK RELEASED");
                } else {
                    log("Release request for lock not held: " + id);
                }
            }
        }
    }

    static public String alarmOwner(long id) {
        if (id == EXTRA_MAILBOX_ID) {
            return TAG;
        } else {
            String name = Long.toString(id);
            if (sUserLog && INSTANCE != null) {
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

    static public boolean isHoldingWakeLock(long id) {
        SyncManager ssm = INSTANCE;
        if (ssm != null) {
            return ssm.hasWakeLock(id);
        }
        return false;
    }

    static public void runAwake(long id) {
        SyncManager ssm = INSTANCE;
        if (ssm != null) {
            ssm.acquireWakeLock(id);
            ssm.clearAlarm(id);
        }
    }

    static public void runAsleep(long id, long millis) {
        SyncManager ssm = INSTANCE;
        if (ssm != null) {
            ssm.setAlarm(id, millis);
            ssm.releaseWakeLock(id);
        }
    }

    static public void clearWatchdogAlarm(long id) {
        SyncManager ssm = INSTANCE;
        if (ssm != null) {
            ssm.clearAlarm(id);
        }
    }

    static public void setWatchdogAlarm(long id, long millis) {
        SyncManager ssm = INSTANCE;
        if (ssm != null) {
            ssm.setAlarm(id, millis);
        }
    }

    static public void alert(Context context, final long id) {
        final SyncManager ssm = INSTANCE;
        checkSyncManagerRunning();
        if (id < 0) {
            log("SyncServiceManager alert");
            kick("ping SyncServiceManager");
        } else if (ssm == null) {
            context.startService(new Intent(context, SyncManager.class));
        } else {
            final AbstractSyncService service = ssm.getRunningService(id);
            if (service != null) {
                // Handle alerts in a background thread, as we are typically called from a
                // broadcast receiver, and are therefore running in the UI thread
                String threadName = "SyncServiceManager Alert: ";
                if (service.mMailbox != null) {
                    threadName += service.mMailbox.mDisplayName;
                }
                new Thread(new Runnable() {
                   @Override
                public void run() {
                       Mailbox m = Mailbox.restoreMailboxWithId(ssm, id);
                       if (m != null) {
                           // We ignore drafts completely (doesn't sync).  Changes in Outbox are
                           // handled in the checkMailboxes loop, so we can ignore these pings.
                           if (sUserLog) {
                               LogUtils.d(TAG, "Alert for mailbox " + id + " ("
                                       + m.mDisplayName + ")");
                           }
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
                                   ssm.releaseMailbox(id);
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

    public class ConnectivityReceiver extends BroadcastReceiver {
        @SuppressWarnings("deprecation")
        @Override
        public void onReceive(Context context, Intent intent) {
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
        }
    }

    /**
     * Starts a service thread and enters it into the service map
     * This is the point of instantiation of all sync threads
     * @param service the service to start
     * @param m the Mailbox on which the service will operate
     */
    private void startServiceThread(AbstractSyncService service) {
        final Mailbox mailbox = service.mMailbox;
        synchronized (sSyncLock) {
            String mailboxName = mailbox.mDisplayName;
            String accountName = service.mAccount.mDisplayName;
            Thread thread = new Thread(service, mailboxName + "[" + accountName + "]");
            log("Starting thread for " + mailboxName + " in account " + accountName);
            thread.start();
            mServiceMap.put(mailbox.mId, service);
            runAwake(mailbox.mId);
        }
        onStartService(mailbox);
    }

    private void requestSync(Mailbox m, int reason, Request req) {
        int syncStatus = EmailContent.SYNC_STATUS_BACKGROUND;
        // Don't sync if there's no connectivity
        if (sConnectivityHold || (m == null) || sStop) {
            return;
        }
        synchronized (sSyncLock) {
            Account acct = Account.restoreAccountWithId(this, m.mAccountKey);
            if (acct != null) {
                // Always make sure there's not a running instance of this service
                AbstractSyncService service = mServiceMap.get(m.mId);
                if (service == null) {
                    service = getServiceForMailbox(this, m);
                    if (!service.mIsValid) return;
                    service.mSyncReason = reason;
                    if (req != null) {
                        service.addRequest(req);
                    }
                    startServiceThread(service);
                    if (reason >= SYNC_CALLBACK_START) {
                        syncStatus = EmailContent.SYNC_STATUS_USER;
                    }
                    setMailboxSyncStatus(m.mId, syncStatus);
                }
            }
        }
    }

    public void setMailboxSyncStatus(long id, int status) {
        ContentValues values = new ContentValues();
        values.put(Mailbox.UI_SYNC_STATUS, status);
        mResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, id), values, null, null);
    }

    public void setMailboxLastSyncResult(long id, int result) {
        ContentValues values = new ContentValues();
        values.put(Mailbox.UI_LAST_SYNC_RESULT, result);
        mResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, id), values, null, null);
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
            (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        while (!sStop) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null) {
                mNetworkInfo = info;
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
                    runAsleep(EXTRA_MAILBOX_ID, CONNECTIVITY_WAIT_TIME+5*SECONDS);
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
                    runAwake(EXTRA_MAILBOX_ID);
                }
            }
        }
    }

    /**
     * Note that there are two ways the EAS SyncServiceManager service can be created:
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
    @SuppressWarnings("deprecation")
    @Override
    public void onCreate() {
        TAG = getClass().getSimpleName();
        EmailContent.init(this);
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                // Quick checks first, before getting the lock
                if (sStartingUp) return;
                synchronized (sSyncLock) {
                    alwaysLog("!!! onCreate");
                    // Try to start up properly; we might be coming back from a crash that the Email
                    // application isn't aware of.
                    startService(getServiceIntent());
                    if (sStop) {
                        return;
                    }
                }
            }});
    }

    @SuppressWarnings("deprecation")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        alwaysLog("!!! onStartCommand, startingUp = " + sStartingUp + ", running = " +
                (INSTANCE != null));
        if (!sStartingUp && INSTANCE == null) {
            sStartingUp = true;
            Utility.runAsync(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized (sSyncLock) {
                            // SyncServiceManager cannot start unless we connect to AccountService
                            if (!new AccountServiceProxy(SyncManager.this).test()) {
                                alwaysLog("!!! Email application not found; stopping self");
                                stopSelf();
                            }
                            String deviceId = getDeviceId(SyncManager.this);
                            if (deviceId == null) {
                                alwaysLog("!!! deviceId unknown; stopping self and retrying");
                                stopSelf();
                                // Try to restart ourselves in a few seconds
                                Utility.runAsync(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Thread.sleep(5000);
                                        } catch (InterruptedException e) {
                                        }
                                        startService(getServiceIntent());
                                    }});
                                return;
                            }
                            // Run the reconciler and clean up mismatched accounts - if we weren't
                            // running when accounts were deleted, it won't have been called.
                            runAccountReconcilerSync(SyncManager.this);
                            // Update other services depending on final account configuration
                            maybeStartSyncServiceManagerThread();
                            if (sServiceThread == null) {
                                log("!!! EAS SyncServiceManager, stopping self");
                                stopSelf();
                            } else if (sStop) {
                                // If we were trying to stop, attempt a restart in 5 secs
                                setAlarm(SYNC_SERVICE_MAILBOX_ID, 5*SECONDS);
                            } else {
                                mServiceStartTime = System.currentTimeMillis();
                            }
                        }
                    } finally {
                        sStartingUp = false;
                    }
                }});
        }
        return Service.START_STICKY;
    }

    public static void reconcileAccounts(Context context) {
        SyncManager ssm = INSTANCE;
        if (ssm != null) {
            ssm.runAccountReconcilerSync(context);
        }
    }

    protected abstract void runAccountReconcilerSync(Context context);

    @SuppressWarnings("deprecation")
    @Override
    public void onDestroy() {
        log("!!! onDestroy");
        // Handle shutting down off the UI thread
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                // Quick checks first, before getting the lock
                if (INSTANCE == null || sServiceThread == null) return;
                synchronized(sSyncLock) {
                    // Stop the sync manager thread and return
                    if (sServiceThread != null) {
                        sStop = true;
                        sServiceThread.interrupt();
                    }
                }
            }});
    }

    void maybeStartSyncServiceManagerThread() {
        // Start our thread...
        // See if there are any EAS accounts; otherwise, just go away
        if (sServiceThread == null || !sServiceThread.isAlive()) {
            AccountList currentAccounts = new AccountList();
            try {
                collectAccounts(this, currentAccounts);
            } catch (ProviderUnavailableException e) {
                // Just leave if EmailProvider is unavailable
                return;
            }
            if (!currentAccounts.isEmpty()) {
                log(sServiceThread == null ? "Starting thread..." : "Restarting thread...");
                sServiceThread = new Thread(this, TAG);
                INSTANCE = this;
                sServiceThread.start();
            }
        }
    }

    /**
     * Start up the SyncManager service if it's not already running
     * This is a stopgap for cases in which SyncServiceManager died (due to a crash somewhere in
     * com.android.email) and hasn't been restarted. See the comment for onCreate for details
     */
    static void checkSyncManagerRunning() {
        SyncManager ssm = INSTANCE;
        if (ssm == null) return;
        if (sServiceThread == null) {
            log("!!! checkSyncServiceManagerServiceRunning; starting service...");
            ssm.startService(new Intent(ssm, SyncManager.class));
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void run() {
        sStop = false;
        alwaysLog("Service thread running");

        TempDirectory.setTempDirectory(this);

        // Synchronize here to prevent a shutdown from happening while we initialize our observers
        // and receivers
        synchronized (sSyncLock) {
            if (INSTANCE != null) {
                mResolver = getContentResolver();

                // Set up our observers; we need them to know when to start/stop various syncs based
                // on the insert/delete/update of mailboxes and accounts
                // We also observe synced messages to trigger upsyncs at the appropriate time
                mAccountObserver = getAccountObserver(mHandler);
                mResolver.registerContentObserver(Account.NOTIFIER_URI, true, mAccountObserver);
                mMailboxObserver = new MailboxObserver(mHandler);
                mResolver.registerContentObserver(Mailbox.CONTENT_URI, false, mMailboxObserver);
                mSyncedMessageObserver = new SyncedMessageObserver(mHandler);
                mResolver.registerContentObserver(Message.SYNCED_CONTENT_URI, true,
                        mSyncedMessageObserver);

                mConnectivityReceiver = new ConnectivityReceiver();
                registerReceiver(mConnectivityReceiver, new IntentFilter(
                        ConnectivityManager.CONNECTIVITY_ACTION));

                onStartup();
            }
        }

        try {
            // Loop indefinitely until we're shut down
            while (!sStop) {
                runAwake(EXTRA_MAILBOX_ID);
                waitForConnectivity();
                mNextWaitReason = null;
                long nextWait = checkMailboxes();
                try {
                    synchronized (this) {
                        if (!mKicked) {
                            if (nextWait < 0) {
                                log("Negative wait? Setting to 1s");
                                nextWait = 1*SECONDS;
                            }
                            if (nextWait > 10*SECONDS) {
                                if (mNextWaitReason != null) {
                                    log("Next awake " + nextWait / 1000 + "s: " + mNextWaitReason);
                                }
                                runAsleep(EXTRA_MAILBOX_ID, nextWait + (3*SECONDS));
                            }
                            wait(nextWait);
                        }
                    }
                } catch (InterruptedException e) {
                    // Needs to be caught, but causes no problem
                    log("SyncServiceManager interrupted");
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
        } catch (ProviderUnavailableException pue) {
            // Shutdown cleanly in this case
            // NOTE: Sync adapters will also crash with this error, but that is already handled
            // in the adapters themselves, i.e. they return cleanly via done().  When the Email
            // process starts running again, remote processes will be started again in due course
            LogUtils.e(TAG, "EmailProvider unavailable; shutting down");
            // Ask for our service to be restarted; this should kick-start the Email process as well
            startService(new Intent(this, SyncManager.class));
        } catch (RuntimeException e) {
            // Crash; this is a completely unexpected runtime error
            LogUtils.e(TAG, "RuntimeException", e);
            throw e;
        } finally {
            shutdown();
        }
    }

    private void shutdown() {
        synchronized (sSyncLock) {
            // If INSTANCE is null, we've already been shut down
            if (INSTANCE != null) {
                log("Shutting down...");

                // Stop our running syncs
                stopServiceThreads();

                // Stop receivers
                if (mConnectivityReceiver != null) {
                    unregisterReceiver(mConnectivityReceiver);
                }

                // Unregister observers
                ContentResolver resolver = getContentResolver();
                if (mSyncedMessageObserver != null) {
                    resolver.unregisterContentObserver(mSyncedMessageObserver);
                    mSyncedMessageObserver = null;
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

    /**
     * Release a mailbox from the service map and release its wake lock.
     * NOTE: This method MUST be called while holding sSyncLock!
     *
     * @param mailboxId the id of the mailbox to be released
     */
    public void releaseMailbox(long mailboxId) {
        mServiceMap.remove(mailboxId);
        releaseWakeLock(mailboxId);
    }

    /**
     * Retrieve a running sync service for the passed-in mailbox id in a threadsafe manner
     *
     * @param mailboxId the id of the mailbox whose service is to be found
     * @return the running service (a subclass of AbstractSyncService) or null if none
     */
    public AbstractSyncService getRunningService(long mailboxId) {
        synchronized(sSyncLock) {
            return mServiceMap.get(mailboxId);
        }
    }

    /**
     * Check whether an Outbox (referenced by a Cursor) has any messages that can be sent
     * @param c the cursor to an Outbox
     * @return true if there is mail to be sent
     */
    private boolean hasSendableMessages(Cursor outboxCursor) {
        Cursor c = mResolver.query(Message.CONTENT_URI, Message.ID_COLUMN_PROJECTION,
                MAILBOX_KEY_AND_NOT_SEND_FAILED,
                new String[] {Long.toString(outboxCursor.getLong(Mailbox.CONTENT_ID_COLUMN))},
                null);
        try {
            while (c.moveToNext()) {
                if (!Utility.hasUnloadedAttachments(this, c.getLong(Message.CONTENT_ID_COLUMN))) {
                    return true;
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return false;
    }

    /**
     * Taken from ConnectivityManager using public constants
     */
    public static boolean isNetworkTypeMobile(int networkType) {
        switch (networkType) {
            case ConnectivityManager.TYPE_MOBILE:
            case ConnectivityManager.TYPE_MOBILE_MMS:
            case ConnectivityManager.TYPE_MOBILE_SUPL:
            case ConnectivityManager.TYPE_MOBILE_DUN:
            case ConnectivityManager.TYPE_MOBILE_HIPRI:
                return true;
            default:
                return false;
        }
    }

    /**
     * Determine whether the account is allowed to sync automatically, as opposed to manually, based
     * on whether the "require manual sync when roaming" policy is in force and applicable
     * @param account the account
     * @return whether or not the account can sync automatically
     */
    /*package*/ public static boolean canAutoSync(Account account) {
        SyncManager ssm = INSTANCE;
        if (ssm == null) {
            return false;
        }
        NetworkInfo networkInfo = ssm.mNetworkInfo;

        // Enforce manual sync only while roaming here
        long policyKey = account.mPolicyKey;
        // Quick exit from this check
        if ((policyKey != 0) && (networkInfo != null) &&
                isNetworkTypeMobile(networkInfo.getType())) {
            // We'll cache the Policy data here
            Policy policy = account.mPolicy;
            if (policy == null) {
                policy = Policy.restorePolicyWithId(INSTANCE, policyKey);
                account.mPolicy = policy;
                if (!PolicyServiceProxy.isActive(ssm, policy)) {
                    PolicyServiceProxy.setAccountHoldFlag(ssm, account, true);
                    log("canAutoSync; policies not active, set hold flag");
                    return false;
                }
            }
            if (policy != null && policy.mRequireManualSyncWhenRoaming && networkInfo.isRoaming()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Convenience method to determine whether Email sync is enabled for a given account
     * @param account the Account in question
     * @return whether Email sync is enabled
     */
    private static boolean canSyncEmail(android.accounts.Account account) {
        return ContentResolver.getSyncAutomatically(account, EmailContent.AUTHORITY);
    }

    /**
     * Determine whether a mailbox of a given type in a given account can be synced automatically
     * by SyncServiceManager.  This is an increasingly complex determination, taking into account
     * security policies and user settings (both within the Email application and in the Settings
     * application)
     *
     * @param account the Account that the mailbox is in
     * @param type the type of the Mailbox
     * @return whether or not to start a sync
     */
    private boolean isMailboxSyncable(Account account, int type) {
        // This 'if' statement performs checks to see whether or not a mailbox is a
        // candidate for syncing based on policies, user settings, & other restrictions
        if (type == Mailbox.TYPE_OUTBOX) {
            // Outbox is always syncable
            return true;
        } else if (type == Mailbox.TYPE_EAS_ACCOUNT_MAILBOX) {
            // Always sync EAS mailbox unless master sync is off
            return ContentResolver.getMasterSyncAutomatically();
        } else if (type == Mailbox.TYPE_CONTACTS || type == Mailbox.TYPE_CALENDAR) {
            // Contacts/Calendar obey this setting from ContentResolver
            if (!ContentResolver.getMasterSyncAutomatically()) {
                return false;
            }
            // Get the right authority for the mailbox
            String authority;
            if (type == Mailbox.TYPE_CONTACTS) {
                authority = ContactsContract.AUTHORITY;
            } else {
                authority = CalendarContract.AUTHORITY;
                if (!mCalendarObservers.containsKey(account.mId)){
                    // Make sure we have an observer for this Calendar, as
                    // we need to be able to detect sync state changes, sigh
                    registerCalendarObserver(account);
                }
            }
            // See if "sync automatically" is set; if not, punt
            if (!ContentResolver.getSyncAutomatically(mAccountList.getAmAccount(account),
                    authority)) {
                return false;
            // See if the calendar is enabled from the Calendar app UI; if not, punt
            } else if ((type == Mailbox.TYPE_CALENDAR) && !isCalendarEnabled(account.mId)) {
                return false;
            }
        // Never automatically sync trash
        } else if (type == Mailbox.TYPE_TRASH) {
            return false;
        // For non-outbox, non-account mail, we do two checks:
        // 1) are we restricted by policy (i.e. manual sync only),
        // 2) has the user checked the "Sync Email" box in Account Settings, and
        } else if (!canAutoSync(account) || !canSyncEmail(mAccountList.getAmAccount(account))) {
            return false;
        }
        return true;
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

        long nextWait = SYNC_SERVICE_HEARTBEAT_TIME;
        long now = System.currentTimeMillis();

        // Start up threads that need it; use a query which finds eas mailboxes where the
        // the sync interval is not "never".  This is the set of mailboxes that we control
        if (mAccountObserver == null) {
            log("mAccountObserver null; service died??");
            return nextWait;
        }

        Cursor c = getContentResolver().query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                mAccountObserver.getSyncableMailboxWhere(), null, null);
        if (c == null) throw new ProviderUnavailableException();
        try {
            while (c.moveToNext()) {
                long mailboxId = c.getLong(Mailbox.CONTENT_ID_COLUMN);
                AbstractSyncService service = getRunningService(mailboxId);
                if (service == null) {
                    // Get the cached account
                    Account account = getAccountById(c.getInt(Mailbox.CONTENT_ACCOUNT_KEY_COLUMN));
                    if (account == null) continue;

                    // We handle a few types of mailboxes specially
                    int mailboxType = c.getInt(Mailbox.CONTENT_TYPE_COLUMN);
                    if (!isMailboxSyncable(account, mailboxType)) {
                        continue;
                    }

                    // Check whether we're in a hold (temporary or permanent)
                    SyncError syncError = mSyncErrorMap.get(mailboxId);
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
                    long syncInterval = c.getInt(Mailbox.CONTENT_SYNC_INTERVAL_COLUMN);
                    if (syncInterval == Mailbox.CHECK_INTERVAL_PUSH) {
                        Mailbox m = EmailContent.getContent(c, Mailbox.class);
                        requestSync(m, SYNC_PUSH, null);
                    } else if (mailboxType == Mailbox.TYPE_OUTBOX) {
                        if (hasSendableMessages(c)) {
                            Mailbox m = EmailContent.getContent(c, Mailbox.class);
                            startServiceThread(getServiceForMailbox(this, m));
                        }
                    } else if (syncInterval > 0 && syncInterval <= ONE_DAY_MINUTES) {
                        // TODO: Migrating to use system SyncManager, so this should be dead code.
                        long lastSync = c.getLong(Mailbox.CONTENT_SYNC_TIME_COLUMN);
                        long sinceLastSync = now - lastSync;
                        long toNextSync = syncInterval*MINUTES - sinceLastSync;
                        String name = c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN);
                        if (toNextSync <= 0) {
                            Mailbox m = EmailContent.getContent(c, Mailbox.class);
                            requestSync(m, SYNC_SCHEDULED, null);
                        } else if (toNextSync < nextWait) {
                            nextWait = toNextSync;
                            if (sUserLog) {
                                log("Next sync for " + name + " in " + nextWait/1000 + "s");
                            }
                            mNextWaitReason = "Scheduled sync, " + name;
                        } else if (sUserLog) {
                            log("Next sync for " + name + " in " + toNextSync/1000 + "s");
                        }
                    }
                } else {
                    Thread thread = service.mThread;
                    // Look for threads that have died and remove them from the map
                    if (thread != null && !thread.isAlive()) {
                        if (sUserLog) {
                            log("Dead thread, mailbox released: " +
                                    c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN));
                        }
                        synchronized (sSyncLock) {
                            releaseMailbox(mailboxId);
                        }
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

    /**
     * Return a boolean indicating whether the mailbox can be synced
     * @param m the mailbox
     * @return whether or not the mailbox can be synced
     */
    public static boolean isSyncable(Mailbox m) {
        return m.mType != Mailbox.TYPE_DRAFTS
                && m.mType != Mailbox.TYPE_OUTBOX
                && m.mType != Mailbox.TYPE_SEARCH
                && m.mType < Mailbox.TYPE_NOT_SYNCABLE;
    }

    static public void serviceRequest(long mailboxId, long ms, int reason) {
        SyncManager ssm = INSTANCE;
        if (ssm == null) return;
        Mailbox m = Mailbox.restoreMailboxWithId(ssm, mailboxId);
        if (m == null || !isSyncable(m)) return;
        try {
            AbstractSyncService service = ssm.getRunningService(mailboxId);
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
        SyncManager ssm = INSTANCE;
        if (ssm == null) return;
        AbstractSyncService service = ssm.getRunningService(mailboxId);
        if (service != null) {
            service.mRequestTime = System.currentTimeMillis();
            Mailbox m = Mailbox.restoreMailboxWithId(ssm, mailboxId);
            if (m != null) {
                service.mAccount = Account.restoreAccountWithId(ssm, m.mAccountKey);
                service.mMailbox = m;
                kick("service request immediate");
            }
        }
    }

    static public void sendMessageRequest(Request req) {
        SyncManager ssm = INSTANCE;
        if (ssm == null) return;
        Message msg = Message.restoreMessageWithId(ssm, req.mMessageId);
        if (msg == null) return;
        long mailboxId = msg.mMailboxKey;
        Mailbox mailbox = Mailbox.restoreMailboxWithId(ssm, mailboxId);
        if (mailbox == null) return;

        // If we're loading an attachment for Outbox, we want to look at the source message
        // to find the loading mailbox
        if (mailbox.mType == Mailbox.TYPE_OUTBOX) {
            long sourceId = Utility.getFirstRowLong(ssm, Body.CONTENT_URI,
                    new String[] {BodyColumns.SOURCE_MESSAGE_KEY},
                    BodyColumns.MESSAGE_KEY + "=?",
                    new String[] {Long.toString(msg.mId)}, null, 0, -1L);
            if (sourceId != -1L) {
                EmailContent.Message sourceMsg =
                        EmailContent.Message.restoreMessageWithId(ssm, sourceId);
                if (sourceMsg != null) {
                    mailboxId = sourceMsg.mMailboxKey;
                }
            }
        }
        sendRequest(mailboxId, req);
    }

    static public void sendRequest(long mailboxId, Request req) {
        SyncManager ssm = INSTANCE;
        if (ssm == null) return;
        AbstractSyncService service = ssm.getRunningService(mailboxId);
        if (service == null) {
            startManualSync(mailboxId, SYNC_SERVICE_PART_REQUEST, req);
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
        SyncManager ssm = INSTANCE;
        if (ssm == null) return PING_STATUS_OK;
        // Already syncing...
        if (ssm.getRunningService(mailboxId) != null) {
            return PING_STATUS_RUNNING;
        }
        // No errors or a transient error, don't ping...
        SyncError error = ssm.mSyncErrorMap.get(mailboxId);
        if (error != null) {
            if (error.fatal) {
                return PING_STATUS_UNABLE;
            } else if (error.holdEndTime > 0) {
                return PING_STATUS_WAITING;
            }
        }
        return PING_STATUS_OK;
    }

    static public void startManualSync(long mailboxId, int reason, Request req) {
        SyncManager ssm = INSTANCE;
        if (ssm == null) return;
        synchronized (sSyncLock) {
            AbstractSyncService svc = ssm.mServiceMap.get(mailboxId);
            if (svc == null) {
                if (ssm.mSyncErrorMap.containsKey(mailboxId) && reason == SyncManager.SYNC_UPSYNC) {
                    return;
                } else if (reason != SyncManager.SYNC_UPSYNC) {
                    ssm.mSyncErrorMap.remove(mailboxId);
                }
                Mailbox m = Mailbox.restoreMailboxWithId(ssm, mailboxId);
                if (m != null) {
                    log("Starting sync for " + m.mDisplayName);
                    ssm.requestSync(m, reason, req);
                }
            } else {
                // If this is a ui request, set the sync reason for the service
                if (reason >= SYNC_CALLBACK_START) {
                    svc.mSyncReason = reason;
                }
            }
        }
    }

    // DO NOT CALL THIS IN A LOOP ON THE SERVICEMAP
    static public void stopManualSync(long mailboxId) {
        SyncManager ssm = INSTANCE;
        if (ssm == null) return;
        synchronized (sSyncLock) {
            AbstractSyncService svc = ssm.mServiceMap.get(mailboxId);
            if (svc != null) {
                log("Stopping sync for " + svc.mMailboxName);
                svc.stop();
                svc.mThread.interrupt();
                ssm.releaseWakeLock(mailboxId);
            }
        }
    }

    /**
     * Wake up SyncServiceManager to check for mailboxes needing service
     */
    static public void kick(String reason) {
       SyncManager ssm = INSTANCE;
       if (ssm != null) {
            synchronized (ssm) {
                //INSTANCE.log("Kick: " + reason);
                ssm.mKicked = true;
                ssm.notify();
            }
        }
        if (sConnectivityLock != null) {
            synchronized (sConnectivityLock) {
                sConnectivityLock.notify();
            }
        }
    }

    /**
     * Tell SyncServiceManager to remove the mailbox from the map of mailboxes with sync errors
     * @param mailboxId the id of the mailbox
     */
    static public void removeFromSyncErrorMap(long mailboxId) {
        SyncManager ssm = INSTANCE;
        if (ssm != null) {
            ssm.mSyncErrorMap.remove(mailboxId);
        }
    }

    private boolean isRunningInServiceThread(long mailboxId) {
        AbstractSyncService syncService = getRunningService(mailboxId);
        Thread thisThread = Thread.currentThread();
        return syncService != null && syncService.mThread != null &&
            thisThread == syncService.mThread;
    }

    /**
     * Sent by services indicating that their thread is finished; action depends on the exitStatus
     * of the service.
     *
     * @param svc the service that is finished
     */
    static public void done(AbstractSyncService svc) {
        SyncManager ssm = INSTANCE;
        if (ssm == null) return;
        synchronized(sSyncLock) {
            long mailboxId = svc.mMailboxId;
            // If we're no longer the syncing thread for the mailbox, just return
            if (!ssm.isRunningInServiceThread(mailboxId)) {
                return;
            }
            ssm.releaseMailbox(mailboxId);
            ssm.setMailboxSyncStatus(mailboxId, EmailContent.SYNC_STATUS_NONE);

            ConcurrentHashMap<Long, SyncError> errorMap = ssm.mSyncErrorMap;
            SyncError syncError = errorMap.get(mailboxId);

            int exitStatus = svc.mExitStatus;
            Mailbox m = Mailbox.restoreMailboxWithId(ssm, mailboxId);
            if (m == null) return;

            if (exitStatus != AbstractSyncService.EXIT_LOGIN_FAILURE) {
                long accountId = m.mAccountKey;
                Account account = Account.restoreAccountWithId(ssm, accountId);
                if (account == null) return;
                if (ssm.releaseSyncHolds(ssm,
                        AbstractSyncService.EXIT_LOGIN_FAILURE, account)) {
                    new AccountServiceProxy(ssm).notifyLoginSucceeded(accountId);
                }
            }

            int lastResult = EmailContent.LAST_SYNC_RESULT_SUCCESS;
            // For error states, whether the error is fatal (won't automatically be retried)
            boolean errorIsFatal = true;
            try {
                switch (exitStatus) {
                    case AbstractSyncService.EXIT_DONE:
                        if (svc.hasPendingRequests()) {
                            // TODO Handle this case
                        }
                        errorMap.remove(mailboxId);
                        // If we've had a successful sync, clear the shutdown count
                        synchronized (SyncManager.class) {
                            sClientConnectionManagerShutdownCount = 0;
                        }
                        // Leave now; other statuses are errors
                        return;
                    // I/O errors get retried at increasing intervals
                    case AbstractSyncService.EXIT_IO_ERROR:
                        if (syncError != null) {
                            syncError.escalate();
                            log(m.mDisplayName + " held for " + (syncError.holdDelay/ 1000) + "s");
                            return;
                        } else {
                            log(m.mDisplayName + " added to syncErrorMap, hold for 15s");
                        }
                        lastResult = EmailContent.LAST_SYNC_RESULT_CONNECTION_ERROR;
                        errorIsFatal = false;
                        break;
                    // These errors are not retried automatically
                    case AbstractSyncService.EXIT_LOGIN_FAILURE:
                        new AccountServiceProxy(ssm).notifyLoginFailed(m.mAccountKey, svc.mExitReason);
                        lastResult = EmailContent.LAST_SYNC_RESULT_AUTH_ERROR;
                        break;
                    case AbstractSyncService.EXIT_SECURITY_FAILURE:
                    case AbstractSyncService.EXIT_ACCESS_DENIED:
                        lastResult = EmailContent.LAST_SYNC_RESULT_SECURITY_ERROR;
                        break;
                    case AbstractSyncService.EXIT_EXCEPTION:
                        lastResult = EmailContent.LAST_SYNC_RESULT_INTERNAL_ERROR;
                        break;
                }
                // Add this box to the error map
                errorMap.put(mailboxId, ssm.new SyncError(exitStatus, errorIsFatal));
            } finally {
                // Always set the last result
                ssm.setMailboxLastSyncResult(mailboxId, lastResult);
                kick("sync completed");
            }
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

    private void writeWakeLockTimes(PrintWriter pw, HashMap<Long, Long> map, boolean historical) {
        long now = System.currentTimeMillis();
        for (long mailboxId: map.keySet()) {
            Long time = map.get(mailboxId);
            if (time == null) {
                // Just in case...
                continue;
            }
            Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mailboxId);
            StringBuilder sb = new StringBuilder();
            if (mailboxId == EXTRA_MAILBOX_ID) {
                sb.append("    SyncManager");
            } else if (mailbox == null) {
                sb.append("    Mailbox " + mailboxId + " (deleted?)");
            } else {
                String protocol = Account.getProtocol(this, mailbox.mAccountKey);
                sb.append("    Mailbox " + mailboxId + " (" + protocol + ", type " +
                        mailbox.mType + ")");
            }
            long logTime = historical ? time : (now - time);
            sb.append(" held for " + (logTime / 1000) + "s");
            pw.println(sb.toString());
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        long uptime = System.currentTimeMillis() - mServiceStartTime;
        pw.println("SyncManager: " + TAG + " up for " + (uptime / 1000 / 60) + " m");
        if (mWakeLock != null) {
            pw.println("  Holding WakeLock");
            writeWakeLockTimes(pw, mWakeLocks, false);
        } else {
            pw.println("  Not holding WakeLock");
        }
        if (!mWakeLocksHistory.isEmpty()) {
            pw.println("  Historical times");
            writeWakeLockTimes(pw, mWakeLocksHistory, true);
        }
    }
}
