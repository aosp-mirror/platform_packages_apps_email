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

import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.provider.EmailContent.SyncColumns;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.NetworkInfo.State;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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

    public static final String TAG = "EAS SyncManager";

    public static final int DEFAULT_WINDOW = Integer.MIN_VALUE;
    public static final int SECS = 1000;
    public static final int MINS = 60 * SECS;
    static SyncManager INSTANCE;
    static Object mSyncToken = new Object();
    static Thread mServiceThread = null;
    HashMap<Long, AbstractSyncService> mServiceMap = new HashMap<Long, AbstractSyncService>();
    HashMap<Long, SyncError> mSyncErrorMap = new HashMap<Long, SyncError>();
    boolean mStop = false;
    SharedPreferences mSettings;
    Handler mHandler = new Handler();
    AccountObserver mAccountObserver;
    MailboxObserver mMailboxObserver;
    SyncedMessageObserver mSyncedMessageObserver;
    MessageObserver mMessageObserver;
    String mNextWaitReason;
    IEmailServiceCallback mCallback;

    RemoteCallbackList<IEmailServiceCallback> mCallbackList =
        new RemoteCallbackList<IEmailServiceCallback>();

    static private HashMap<Long, Boolean> mWakeLocks = new HashMap<Long, Boolean>();
    static private HashMap<Long, PendingIntent> mPendingIntents =
        new HashMap<Long, PendingIntent>();
    static private WakeLock mWakeLock = null;

    /**
     * Proxy that can be used by various sync adapters to call into SyncManager's callback system.
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

        public void sendMessageStatus(long messageId, int statusCode, int progress)
                throws RemoteException{
            IEmailServiceCallback cb = INSTANCE == null ? null: INSTANCE.mCallback;
            if (cb != null) {
                cb.sendMessageStatus(messageId, statusCode, progress);
            }
        }

        public void syncMailboxListStatus(long accountId, int statusCode, int progress)
                throws RemoteException{
            IEmailServiceCallback cb = INSTANCE == null ? null: INSTANCE.mCallback;
            if (cb != null) {
                cb.syncMailboxListStatus(accountId, statusCode, progress);
            }
        }

        public void syncMailboxStatus(long mailboxId, int statusCode, int progress)
                throws RemoteException{
            IEmailServiceCallback cb = INSTANCE == null ? null: INSTANCE.mCallback;
            if (cb != null) {
                cb.syncMailboxStatus(mailboxId, statusCode, progress);
            }
        }
    };

    /**
     * Create the binder for EmailService implementation here.  These are the calls that are
     * defined in AbstractSyncService.   Only validate is now implemented; loadAttachment currently
     * spins its wheels counting up to 100%.
     */
    private final IEmailService.Stub mBinder = new IEmailService.Stub() {

        public int validate(String protocol, String host, String userName, String password,
                int port, boolean ssl) throws RemoteException {
            try {
                AbstractSyncService.validate(EasSyncService.class, host, userName, password, port,
                        ssl, SyncManager.this);
                return MessagingException.NO_ERROR;
            } catch (MessagingException e) {
                return e.getExceptionType();
            }
        }

        public void startSync(long mailboxId) throws RemoteException {
            startManualSync(mailboxId, null);
        }

        public void stopSync(long mailboxId) throws RemoteException {
            stopManualSync(mailboxId);
        }

        public void loadAttachment(long attachmentId, String destinationFile,
                String contentUriString) throws RemoteException {
            Attachment att = Attachment.restoreAttachmentWithId(SyncManager.this, attachmentId);
            partRequest(new PartRequest(att, destinationFile, contentUriString));
        }

        public void updateFolderList(long accountId) throws RemoteException {
//            Cursor c = getContentResolver().query(Mailbox.CONTENT_URI,
//                    Mailbox.CONTENT_PROJECTION, MailboxColumns.ACCOUNT_KEY + "=? AND " +
//                    MailboxColumns.SERVER_ID + "=?",
//                    new String[] {Long.toString(accountId), Eas.ACCOUNT_MAILBOX}, null);
            sCallbackProxy.syncMailboxListStatus(accountId, EmailServiceStatus.IN_PROGRESS, 0);
            sCallbackProxy.syncMailboxListStatus(accountId, EmailServiceStatus.SUCCESS, 0);
            // TODO Remove previous two lines; reimplement what's below (this is bug #2026451)
//            try {
//                if (c.moveToFirst()) {
//                    synchronized(mSyncToken) {
//                        AbstractSyncService svc =
//                            INSTANCE.mServiceMap.get(c.getLong(Mailbox.CONTENT_ID_COLUMN));
//                        // Tell the service we're done
//                        svc.stop();
//                        // Interrupt it so that it can stop
//                        svc.mThread.interrupt();
//                    }
//                }
//            } finally {
//                c.close();
//            }
        }

        public void setLogging(boolean on) throws RemoteException {
            Eas.setUserDebug(on);
        }

        public void loadMore(long messageId) throws RemoteException {
            // TODO Auto-generated method stub
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

    class AccountList extends ArrayList<Account> {
        private static final long serialVersionUID = 1L;

        public boolean contains(long id) {
            for (Account account: this) {
                if (account.mId == id) {
                    return true;
                }
            }
            return false;
        }
    }

    class AccountObserver extends ContentObserver {

        // mAccounts keeps track of Accounts that we care about (EAS for now)
        AccountList mAccounts = new AccountList();

        public AccountObserver(Handler handler) {
            super(handler);
            Context context = getContext();

            // At startup, we want to see what EAS accounts exist and cache them
            Cursor c = getContentResolver().query(Account.CONTENT_URI, Account.CONTENT_PROJECTION,
                    null, null, null);
            try {
                collectEasAccounts(c, mAccounts);
            } finally {
                c.close();
            }

            for (Account account: mAccounts) {
                int cnt = Mailbox.count(context, Mailbox.CONTENT_URI, "accountKey=" + account.mId,
                        null);
                if (cnt == 0) {
                    addAccountMailbox(account.mId);
                }
            }
        }

        private boolean accountChanged(Account account) {
            long accountId = account.mId;
            // Reload account from database to get its current state
            account = Account.restoreAccountWithId(getContext(), accountId);
            for (Account oldAccount: mAccounts) {
                if (oldAccount.mId == accountId) {
                    return (oldAccount.mSyncInterval != account.mSyncInterval ||
                            oldAccount.mSyncLookback != account.mSyncLookback);
                }
            }
            // Really, we can't get here, but we don't want the compiler to complain
            return false;
        }

        @Override
        public void onChange(boolean selfChange) {
            // A change to the list requires us to scan for deletions (to stop running syncs)
            // At startup, we want to see what accounts exist and cache them
            AccountList currentAccounts = new AccountList();
            Cursor c = getContentResolver().query(Account.CONTENT_URI, Account.CONTENT_PROJECTION,
                    null, null, null);
            try {
                collectEasAccounts(c, currentAccounts);
                for (Account account : mAccounts) {
                    if (!currentAccounts.contains(account.mId)) {
                        // This is a deletion; shut down any account-related syncs
                        stopAccountSyncs(account.mId);
                    } else {
                        // See whether any of our accounts has changed sync interval or window
                        if (accountChanged(account)) {
                            // Here's one that has...
                            INSTANCE.log("Account " + account.mDisplayName +
                                    " changed; stopping running syncs...");
                            stopAccountSyncs(account.mId);
                        }
                    }
                }
                for (Account account: currentAccounts) {
                    if (!mAccounts.contains(account.mId)) {
                        // This is an addition; create our magic hidden mailbox...
                        addAccountMailbox(account.mId);
                        mAccounts.add(account);
                    }
                }
            } finally {
                c.close();
            }

            // See if there's anything to do...
            kick();
        }

        void collectEasAccounts(Cursor c, ArrayList<Account> accounts) {
            Context context = getContext();
            while (c.moveToNext()) {
                long hostAuthId = c.getLong(Account.CONTENT_HOST_AUTH_KEY_RECV_COLUMN);
                if (hostAuthId > 0) {
                    HostAuth ha = HostAuth.restoreHostAuthWithId(context, hostAuthId);
                    if (ha != null && ha.mProtocol.equals("eas")) {
                        accounts.add(new Account().restore(c));
                    }
                }
            }
        }

        void addAccountMailbox(long acctId) {
            Account acct = Account.restoreAccountWithId(getContext(), acctId);
            Mailbox main = new Mailbox();
            main.mDisplayName = Eas.ACCOUNT_MAILBOX;
            main.mServerId = Eas.ACCOUNT_MAILBOX + System.nanoTime();
            main.mAccountKey = acct.mId;
            main.mType = Mailbox.TYPE_EAS_ACCOUNT_MAILBOX;
            main.mSyncInterval = Account.CHECK_INTERVAL_PUSH;
            main.mFlagVisible = false;
            main.save(getContext());
            INSTANCE.log("Initializing account: " + acct.mDisplayName);
        }

        void stopAccountSyncs(long acctId) {
            synchronized (mSyncToken) {
                List<Long> deletedBoxes = new ArrayList<Long>();
                for (Long mid : INSTANCE.mServiceMap.keySet()) {
                    Mailbox box = Mailbox.restoreMailboxWithId(INSTANCE, mid);
                    if (box != null) {
                        if (box.mAccountKey == acctId) {
                            AbstractSyncService svc = INSTANCE.mServiceMap.get(mid);
                            if (svc != null) {
                                svc.stop();
                                svc.mThread.interrupt();
                            }
                            deletedBoxes.add(mid);
                        }
                    }
                }
                for (Long mid : deletedBoxes) {
                    INSTANCE.mServiceMap.remove(mid);
                }
            }
        }
    }

    class MailboxObserver extends ContentObserver {
        public MailboxObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            // See if there's anything to do...
            kick();
        }
    }

    class SyncedMessageObserver extends ContentObserver {
        long maxChangedId = 0;
        long maxDeletedId = 0;
        Intent syncAlarmIntent = new Intent(INSTANCE, EmailSyncAlarmReceiver.class);
        PendingIntent syncAlarmPendingIntent =
            PendingIntent.getBroadcast(INSTANCE, 0, syncAlarmIntent, 0);
        AlarmManager alarmManager = (AlarmManager)INSTANCE.getSystemService(Context.ALARM_SERVICE);
        final String[] MAILBOX_DATA_PROJECTION = {MessageColumns.MAILBOX_KEY, SyncColumns.DATA};

        public SyncedMessageObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            INSTANCE.log("SyncedMessage changed: (re)setting alarm for 10s");
            alarmManager.set(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + (10*SECS), syncAlarmPendingIntent);
        }
    }

    class MessageObserver extends ContentObserver {

        public MessageObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            INSTANCE.log("MessageObserver");
            // A rather blunt instrument here.  But we don't have information about the URI that
            // triggered this, though it must have been an insert
            kick();
        }
    }

    static public IEmailServiceCallback callback() {
        return sCallbackProxy;
    }

    static public AccountList getAccountList() {
        if (INSTANCE != null) {
            return INSTANCE.mAccountObserver.mAccounts;
        } else {
            return null;
        }
    }

    public class SyncStatus {
        static public final int NOT_RUNNING = 0;
        static public final int DIED = 1;
        static public final int SYNC = 2;
        static public final int IDLE = 3;
    }

    class SyncError {
        int reason;
        boolean fatal = false;
        long holdEndTime;
        long holdDelay = 0;

        SyncError(int _reason, boolean _fatal) {
            reason = _reason;
            fatal = _fatal;
            escalate();
        }

        /**
         * We increase the hold on I/O errors in 30 second increments to 5 minutes
         */
        void escalate() {
            if (holdDelay < 5*MINS) {
                holdDelay += 30*SECS;
            }
            holdEndTime = System.currentTimeMillis() + holdDelay;
        }
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    public void log(String str) {
        if (Eas.USER_LOG) {
            Log.d(TAG, str);
        }
    }

    @Override
    public void onCreate() {
        if (INSTANCE != null) {
            throw new RuntimeException("\n************ ALREADY RUNNING *************\n");
        }
        INSTANCE = this;

        mAccountObserver = new AccountObserver(mHandler);
        mMailboxObserver = new MailboxObserver(mHandler);
        mSyncedMessageObserver = new SyncedMessageObserver(mHandler);
        mMessageObserver = new MessageObserver(mHandler);

        // Start our thread...
        if (mServiceThread == null || !mServiceThread.isAlive()) {
            log(mServiceThread == null ? "Starting thread..." : "Restarting thread...");
            mServiceThread = new Thread(this, "SyncManager");
            mServiceThread.start();
        } else {
            log("Attempt to start SyncManager though already started before?");
        }
    }

    /**
     * Informs SyncManager that an account has a new folder list; as a result, any existing folder
     * might have become invalid.  Therefore, we act as if the account has been deleted, and then
     * we reinitialize it.
     *
     * @param acctId
     */
    static public void folderListReloaded(long acctId) {
        if (INSTANCE != null) {
            AccountObserver obs = INSTANCE.mAccountObserver;
            obs.stopAccountSyncs(acctId);
            obs.addAccountMailbox(acctId);
        }
    }

    static public void acquireWakeLock(long id) {
        synchronized (mWakeLocks) {
            Boolean lock = mWakeLocks.get(id);
            if (lock == null) {
                INSTANCE.log("+WakeLock requested for " + id);
                if (mWakeLock == null) {
                    PowerManager pm = (PowerManager)INSTANCE
                            .getSystemService(Context.POWER_SERVICE);
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MAIL_SERVICE");
                    mWakeLock.acquire();
                    INSTANCE.log("+WAKE LOCK ACQUIRED");
                }
                mWakeLocks.put(id, true);
            }
        }
    }

    static public void releaseWakeLock(long id) {
        synchronized (mWakeLocks) {
            Boolean lock = mWakeLocks.get(id);
            if (lock != null) {
                INSTANCE.log("+WakeLock not needed for " + id);
                mWakeLocks.remove(id);
                if (mWakeLocks.isEmpty()) {
                    mWakeLock.release();
                    mWakeLock = null;
                    INSTANCE.log("+WAKE LOCK RELEASED");
                }
            }
        }
    }

    static private String alarmOwner(long id) {
        if (id == -1) {
            return "MailService";
        } else
            return "Mailbox " + Long.toString(id);
    }

    static private void clearAlarm(long id) {
        synchronized (mPendingIntents) {
            PendingIntent pi = mPendingIntents.get(id);
            if (pi != null) {
                AlarmManager alarmManager = (AlarmManager)INSTANCE
                        .getSystemService(Context.ALARM_SERVICE);
                alarmManager.cancel(pi);
                INSTANCE.log("+Alarm cleared for " + alarmOwner(id));
                mPendingIntents.remove(id);
            }
        }
    }

    static private void setAlarm(long id, long millis) {
        synchronized (mPendingIntents) {
            PendingIntent pi = mPendingIntents.get(id);
            if (pi == null) {
                Intent i = new Intent(INSTANCE, MailboxAlarmReceiver.class);
                i.putExtra("mailbox", id);
                i.setData(Uri.parse("Box" + id));
                pi = PendingIntent.getBroadcast(INSTANCE, 0, i, 0);
                mPendingIntents.put(id, pi);

                AlarmManager alarmManager = (AlarmManager)INSTANCE
                        .getSystemService(Context.ALARM_SERVICE);
                alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + millis, pi);
                INSTANCE.log("+Alarm set for " + alarmOwner(id) + ", " + millis + "ms");
            }
        }
    }

    static private void clearAlarms() {
        AlarmManager alarmManager = (AlarmManager)INSTANCE.getSystemService(Context.ALARM_SERVICE);
        synchronized (mPendingIntents) {
            for (PendingIntent pi : mPendingIntents.values()) {
                alarmManager.cancel(pi);
            }
            mPendingIntents.clear();
        }
    }

    static public void runAwake(long id) {
        acquireWakeLock(id);
        clearAlarm(id);
    }

    static public void runAsleep(long id, long millis) {
        setAlarm(id, millis);
        releaseWakeLock(id);
    }

    static public void ping(long id) {
        if (id < 0) {
            kick();
        } else {
            AbstractSyncService service = INSTANCE.mServiceMap.get(id);
            if (service != null) {
                Mailbox m = Mailbox.restoreMailboxWithId(INSTANCE, id);
                if (m != null) {
                    service.mAccount = Account.restoreAccountWithId(INSTANCE, m.mAccountKey);
                    service.mMailbox = m;
                    service.ping();
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        log("!!! MaiLService onDestroy");
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
        clearAlarms();
    }

    public class ConnectivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle b = intent.getExtras();
            if (b != null) {
                NetworkInfo a = (NetworkInfo)b.get("networkInfo");
                String info = "CM Info: " + a.getTypeName();
                State state = a.getState();
                if (state == State.CONNECTED) {
                    info += " CONNECTED";
                    kick();
                } else if (state == State.CONNECTING) {
                    info += " CONNECTING";
                } else if (state == State.DISCONNECTED) {
                    info += " DISCONNECTED";
                    kick();
                } else if (state == State.DISCONNECTING) {
                    info += " DISCONNECTING";
                } else if (state == State.SUSPENDED) {
                    info += " SUSPENDED";
                } else if (state == State.UNKNOWN) {
                    info += " UNKNOWN";
                }
                log("CONNECTIVITY: " + info);
            }
        }
    }

    private void pause(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
        }
    }

    private void startService(AbstractSyncService service, Mailbox m) {
        synchronized (mSyncToken) {
            String mailboxName = m.mDisplayName;
            String accountName = service.mAccount.mDisplayName;
            Thread thread = new Thread(service, mailboxName + "(" + accountName + ")");
            log("Starting thread for " + mailboxName + " in account " + accountName);
            thread.start();
            mServiceMap.put(m.mId, service);
        }
    }

    private void startService(Mailbox m, PartRequest req) {
        synchronized (mSyncToken) {
            Account acct = Account.restoreAccountWithId(this, m.mAccountKey);
            if (acct != null) {
                AbstractSyncService service;
                service = new EasSyncService(this, m);
                if (req != null) {
                    service.addPartRequest(req);
                }
                startService(service, m);
            }
        }
    }

    private void stopServices() {
        synchronized (mSyncToken) {
            ArrayList<Long> toStop = new ArrayList<Long>();

            // Keep track of which services to stop
            for (Long mailboxId : mServiceMap.keySet()) {
                toStop.add(mailboxId);
            }

            // Shut down all of those running services
            for (Long mailboxId : toStop) {
                AbstractSyncService svc = mServiceMap.get(mailboxId);
                log("Shutting down " + svc.mAccount.mDisplayName + '/' + svc.mMailbox.mDisplayName);
                svc.stop();
                svc.mThread.interrupt();
            }
        }
    }

    public void run() {
        mStop = false;

        // If we're really debugging, turn on all logging
        if (Eas.DEBUG) {
            Eas.PARSER_LOG = true;
            Eas.USER_LOG = true;
        }

        runAwake(-1);

        // Set up our observers; we need them to know when to start/stop various syncs based
        // on the insert/delete/update of mailboxes and accounts
        // We also observe synced messages to trigger upsyncs at the appropriate time
        ContentResolver resolver = getContentResolver();
        resolver.registerContentObserver(Account.CONTENT_URI, false, mAccountObserver);
        resolver.registerContentObserver(Mailbox.CONTENT_URI, false, mMailboxObserver);
        resolver.registerContentObserver(Message.SYNCED_CONTENT_URI, true, mSyncedMessageObserver);
        resolver.registerContentObserver(Message.CONTENT_URI, false, mMessageObserver);

        ConnectivityReceiver cr = new ConnectivityReceiver();
        registerReceiver(cr, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        ConnectivityManager cm =
            (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        try {
            while (!mStop) {
                runAwake(-1);
                log("Looking for something to do...");
                int cnt = 0;
                while (!mStop) {
                    NetworkInfo info = cm.getActiveNetworkInfo();
                    if (info != null && info.isConnected()) {
                        break;
                    } else {
                        if (cnt++ == 2) {
                            stopServices();
                        }
                        pause(10*SECS);
                    }
                }
                if (!mStop) {
                    mNextWaitReason = "Heartbeat";
                    long nextWait = checkMailboxes();
                    try {
                        synchronized (INSTANCE) {
                            if (nextWait < 0) {
                                log("Negative wait? Setting to 1s");
                                nextWait = 1*SECS;
                            }
                            if (nextWait > (30*SECS)) {
                                runAsleep(-1, nextWait - 1000);
                            }
                            log("Next awake in " + (nextWait / 1000) + "s: " + mNextWaitReason);
                            INSTANCE.wait(nextWait);
                        }
                    } catch (InterruptedException e) {
                        // Needs to be caught, but causes no problem
                    }
                } else {
                    stopServices();
                    log("Shutdown requested");
                    return;
                }

            }
        } finally {
            log("Goodbye");
        }

        startService(new Intent(this, SyncManager.class));
        throw new RuntimeException("MailService crash; please restart me...");
    }

    long checkMailboxes () {
        // First, see if any running mailboxes have been deleted
        ArrayList<Long> deadMailboxes = new ArrayList<Long>();
        synchronized (mSyncToken) {
            for (long mailboxId: mServiceMap.keySet()) {
                Mailbox m = Mailbox.restoreMailboxWithId(INSTANCE, mailboxId);
                if (m == null) {
                    deadMailboxes.add(mailboxId);
                    log("Stopping sync for mailbox " + mailboxId + "; record not found.");
                }
            }
        }
        // If so, stop them
        for (Long mailboxId: deadMailboxes) {
            stopManualSync(mailboxId);
        }

        long nextWait = 10*MINS;
        long now = System.currentTimeMillis();
        // Start up threads that need it...
        Cursor c = getContentResolver().query(Mailbox.CONTENT_URI,
                Mailbox.CONTENT_PROJECTION, null, null, null);
        try {
            while (c.moveToNext()) {
                // TODO Could be much faster - just get cursor of
                // ones we're watching...
                long aid = c.getLong(Mailbox.CONTENT_ACCOUNT_KEY_COLUMN);
                // Only check mailboxes for EAS accounts
                if (!mAccountObserver.mAccounts.contains(aid)) {
                    continue;
                }
                long mid = c.getLong(Mailbox.CONTENT_ID_COLUMN);
                AbstractSyncService service = mServiceMap.get(mid);
                if (service == null) {
                    // Check whether we're in a hold (temporary or permanent)
                    SyncError syncError = mSyncErrorMap.get(mid);
                    if (syncError != null && (syncError.fatal || now < syncError.holdEndTime)) {
                        if (!syncError.fatal) {
                            if (syncError.holdEndTime < (now + nextWait)) {
                                nextWait = syncError.holdEndTime - now;
                                mNextWaitReason = "Release hold";
                            }
                        }
                        continue;
                    }
                    long freq = c.getInt(Mailbox.CONTENT_SYNC_INTERVAL_COLUMN);
                    if (freq == Account.CHECK_INTERVAL_PUSH) {
                        Mailbox m = EmailContent.getContent(c, Mailbox.class);
                        startService(m, null);
                    } else if (c.getInt(Mailbox.CONTENT_TYPE_COLUMN) == Mailbox.TYPE_OUTBOX) {
                        int cnt = EmailContent.count(this, Message.CONTENT_URI,
                                "mailboxKey=" + mid + " and syncServerId=0", null);
                        if (cnt > 0) {
                            Mailbox m = EmailContent.getContent(c, Mailbox.class);
                            startService(new EasOutboxService(this, m), m);
                        }
                    } else if (freq > 0 && freq <= 1440) {
                        long lastSync = c.getLong(Mailbox.CONTENT_SYNC_TIME_COLUMN);
                        if (now - lastSync > (freq*MINS)) {
                            Mailbox m = EmailContent.getContent(c, Mailbox.class);
                            startService(m, null);
                        }
                    }
                } else {
                    Thread thread = service.mThread;
                    if (!thread.isAlive()) {
                        mServiceMap.remove(mid);
                        // Restart this if necessary
                        if (nextWait > 3*SECS) {
                            nextWait = 3*SECS;
                            mNextWaitReason = "Clean up dead thread(s)";
                        }
                    } else {
                        long requestTime = service.mRequestTime;
                        if (requestTime > 0) {
                            long timeToRequest = requestTime - now;
                            if (service instanceof AbstractSyncService && timeToRequest <= 0) {
                                service.mRequestTime = 0;
                                service.ping();
                            } else if (requestTime > 0 && timeToRequest < nextWait) {
                                if (timeToRequest < 11*MINS) {
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

    static public void serviceRequest(Mailbox m) {
        serviceRequest(m.mId, 5*SECS);
    }

    static public void serviceRequest(long mailboxId) {
        serviceRequest(mailboxId, 5*SECS);
    }

    static public void serviceRequest(long mailboxId, long ms) {
        try {
            if (INSTANCE == null) {
                return;
            }
            AbstractSyncService service = INSTANCE.mServiceMap.get(mailboxId);
            if (service != null) {
                service.mRequestTime = System.currentTimeMillis() + ms;
                kick();
            } else {
                startManualSync(mailboxId, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static public void serviceRequestImmediate(long mailboxId) {
        AbstractSyncService service = INSTANCE.mServiceMap.get(mailboxId);
        if (service != null) {
            service.mRequestTime = System.currentTimeMillis();
            Mailbox m = Mailbox.restoreMailboxWithId(INSTANCE, mailboxId);
            service.mAccount = Account.restoreAccountWithId(INSTANCE, m.mAccountKey);
            service.mMailbox = m;
            kick();
        }
    }

    static public void partRequest(PartRequest req) {
        Message msg = Message.restoreMessageWithId(INSTANCE, req.emailId);
        if (msg == null) {
            return;
        }
        long mailboxId = msg.mMailboxKey;
        AbstractSyncService service = INSTANCE.mServiceMap.get(mailboxId);

        if (service == null) {
            service = startManualSync(mailboxId, req);
            kick();
        } else {
            service.addPartRequest(req);
        }
    }

    static public PartRequest hasPartRequest(long emailId, String part) {
        Message msg = Message.restoreMessageWithId(INSTANCE, emailId);
        if (msg == null) {
            return null;
        }
        long mailboxId = msg.mMailboxKey;
        AbstractSyncService service = INSTANCE.mServiceMap.get(mailboxId);
        if (service != null) {
            return service.hasPartRequest(emailId, part);
        }
        return null;
    }

    static public void cancelPartRequest(long emailId, String part) {
        Message msg = Message.restoreMessageWithId(INSTANCE, emailId);
        if (msg == null) {
            return;
        }
        long mailboxId = msg.mMailboxKey;
        AbstractSyncService service = INSTANCE.mServiceMap.get(mailboxId);
        if (service != null) {
            service.cancelPartRequest(emailId, part);
        }
    }

    /**
     * Determine whether a given Mailbox can be synced, i.e. is not already syncing and is not in
     * an error state
     *
     * @param mailboxId
     * @return whether or not the Mailbox is available for syncing (i.e. is a valid push target)
     */
    static public boolean canSync(long mailboxId) {
        // Already syncing...
        if (INSTANCE.mServiceMap.get(mailboxId) != null) {
            return false;
        }
        // Blocked from syncing (transient or permanent)
        if (INSTANCE.mSyncErrorMap.get(mailboxId) != null) {
            return false;
        }
        return true;
    }

    static public int getSyncStatus(long mailboxId) {
        synchronized (mSyncToken) {
            if (INSTANCE == null || INSTANCE.mServiceMap == null) {
                return SyncStatus.NOT_RUNNING;
            }
            AbstractSyncService svc = INSTANCE.mServiceMap.get(mailboxId);
            if (svc == null) {
                return SyncStatus.NOT_RUNNING;
            } else {
                if (!svc.mThread.isAlive()) {
                    return SyncStatus.DIED;
                } else {
                    return svc.getSyncStatus();
                }
            }
        }
    }

    static public AbstractSyncService startManualSync(long mailboxId, PartRequest req) {
        if (INSTANCE == null || INSTANCE.mServiceMap == null) {
            return null;
        }
        INSTANCE.log("startManualSync");
        synchronized (mSyncToken) {
            if (INSTANCE.mServiceMap.get(mailboxId) == null) {
                INSTANCE.mSyncErrorMap.remove(mailboxId);
                Mailbox m = Mailbox.restoreMailboxWithId(INSTANCE, mailboxId);
                INSTANCE.log("Starting sync for " + m.mDisplayName);
                INSTANCE.startService(m, req);
            }
        }
        return INSTANCE.mServiceMap.get(mailboxId);
    }

    // DO NOT CALL THIS IN A LOOP ON THE SERVICEMAP
    static public void stopManualSync(long mailboxId) {
        if (INSTANCE == null || INSTANCE.mServiceMap == null) {
            return;
        }
        synchronized (mSyncToken) {
            AbstractSyncService svc = INSTANCE.mServiceMap.get(mailboxId);
            if (svc != null) {
                INSTANCE.log("Stopping sync for " + svc.mMailboxName);
                svc.stop();
                svc.mThread.interrupt();
            }
        }
    }

    /**
     * Wake up SyncManager to check for mailboxes needing service
     */
    static public void kick() {
        if (INSTANCE != null) {
            synchronized (INSTANCE) {
                INSTANCE.notify();
            }
        }
    }

    static public void kick(long mailboxId) {
        Mailbox m = Mailbox.restoreMailboxWithId(INSTANCE, mailboxId);
        int syncType = m.mSyncInterval;
        if (syncType == Account.CHECK_INTERVAL_PUSH) {
            SyncManager.serviceRequestImmediate(mailboxId);
        } else {
            SyncManager.startManualSync(mailboxId, null);
        }
    }

    static public void accountUpdated(long acctId) {
        synchronized (mSyncToken) {
            for (AbstractSyncService svc : INSTANCE.mServiceMap.values()) {
                if (svc.mAccount.mId == acctId) {
                    svc.mAccount = Account.restoreAccountWithId(INSTANCE, acctId);
                }
            }
        }
    }

    /**
     * Sent by services indicating that their thread is finished; action depends on the exitStatus
     * of the service.
     *
     * @param svc the service that is finished
     */
    static public void done(AbstractSyncService svc) {
        synchronized(mSyncToken) {
            long mailboxId = svc.mMailboxId;
            HashMap<Long, SyncError> errorMap = INSTANCE.mSyncErrorMap;
            SyncError syncError = errorMap.get(mailboxId);
            INSTANCE.mServiceMap.remove(mailboxId);
            int exitStatus = svc.mExitStatus;
            switch (exitStatus) {
                case AbstractSyncService.EXIT_DONE:
                    if (!svc.mPartRequests.isEmpty()) {
                        // TODO Handle this case
                    }
                    errorMap.remove(mailboxId);
                    break;
                case AbstractSyncService.EXIT_IO_ERROR:
                    if (syncError != null) {
                        syncError.escalate();
                    } else {
                        errorMap.put(mailboxId, INSTANCE.new SyncError(exitStatus, false));
                    }
                    kick();
                    break;
                case AbstractSyncService.EXIT_LOGIN_FAILURE:
                case AbstractSyncService.EXIT_EXCEPTION:
                    errorMap.put(mailboxId, INSTANCE.new SyncError(exitStatus, true));
                    break;
            }
        }
    }

    public static void shutdown() {
        INSTANCE.mStop = true;
        kick();
        INSTANCE.stopSelf();
    }

    static public String serviceName(long id) {
        if (id < 0) {
            return "SyncManager";
        } else {
            AbstractSyncService service = INSTANCE.mServiceMap.get(id);
            if (service != null) {
                return service.mThread.getName();
            } else {
                return "Not running?";
            }
        }
    }

    static public Context getContext() {
        if (INSTANCE == null) {
            return null;
        }
        return INSTANCE;
    }
}
