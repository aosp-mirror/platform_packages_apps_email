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

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;

import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;
import com.android.exchange.EmailContent.Attachment;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.NetworkInfo.State;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.database.ContentObserver;

public class SyncManager extends Service implements Runnable {

    public static final int AWAKE = 0;
    public static final int SLEEP_WEEKEND = 1;
    public static final int SLEEP_HOURS = 2;
    public static final int OFFLINE = 3;

    public static final int DEFAULT_WINDOW = Integer.MIN_VALUE;

    public static final int SECS = 1000;
    public static final int MINS = 60*SECS;

    static SyncManager INSTANCE;
    static int mStatus = AWAKE;
    static boolean mToothpicks = false;
    static Object mSyncToken = new Object();
    static Thread mServiceThread = null;

    HashMap<Long, ProtocolService> serviceMap = new HashMap<Long, ProtocolService> ();
    boolean mStop = false;
    SharedPreferences mSettings;
    Handler mHandler = new Handler();
    AccountObserver mAccountObserver;
    MailboxObserver mMailboxObserver;

    final RemoteCallbackList<ISyncManagerCallback> mCallbacks
    = new RemoteCallbackList<ISyncManagerCallback>();

    private final ISyncManager.Stub mBinder = new ISyncManager.Stub() {
        public int validate(String protocol, String host, String userName, String password,
                int port, boolean ssl) throws RemoteException {
            try {
                ProtocolService.validate(EasService.class, host, userName, password, port, ssl,
                        SyncManager.this);
                return MessagingException.NO_ERROR;
            } catch (MessagingException e) {
                return e.getExceptionType();
            }
        }
        public void registerCallback(ISyncManagerCallback cb) {
            if (cb != null) mCallbacks.register(cb);
        }
        public void unregisterCallback(ISyncManagerCallback cb) {
            if (cb != null) mCallbacks.unregister(cb);
        }
        public boolean startSync(long mailboxId) throws RemoteException {
            // TODO Auto-generated method stub
            return false;
        }
        public boolean stopSync(long mailboxId) throws RemoteException {
            // TODO Auto-generated method stub
            return false;
        }
        public boolean updateFolderList(long accountId) throws RemoteException {
            // TODO Auto-generated method stub
            return false;
        }
        public boolean loadMore(long messageId, ISyncManagerCallback cb) throws RemoteException {
            // TODO Auto-generated method stub
            return false;
        }
        public boolean createFolder(long accountId, String name) throws RemoteException {
            // TODO Auto-generated method stub
            return false;
        }
        public boolean deleteFolder(long accountId, String name) throws RemoteException {
            // TODO Auto-generated method stub
            return false;
        }
        public boolean renameFolder(long accountId, String oldName, String newName)
        throws RemoteException {
            // TODO Auto-generated method stub
            return false;
        }
        public boolean loadAttachment(long messageId, Attachment att, ISyncManagerCallback cb)
        throws RemoteException {
            // TODO Auto-generated method stub
            return false;
        }
    };

    class AccountObserver extends ContentObserver {
        ArrayList<Long> accountIds = new ArrayList<Long>();

        public AccountObserver(Handler handler) {
            super(handler);

            // At startup, we want to see what accounts exist and cache them
            Cursor c = getContentResolver().query(EmailContent.Account.CONTENT_URI, EmailContent.Account.CONTENT_PROJECTION, null, null, null);
            try {
                if (c.moveToFirst()) {
                    do {
                        accountIds.add(c.getLong(EmailContent.Account.CONTENT_ID_COLUMN));
                    } while (c.moveToNext());
                }
            } finally {
                c.close();
            }

            for (long accountId: accountIds) {
                Context context = getContext();
                int cnt = EmailContent.Mailbox.count(context, EmailContent.Mailbox.CONTENT_URI, "accountKey=" + accountId, null);
                if (cnt == 0) {
                    initializeAccount(accountId);
                }
            }
        }

        public void onChange (boolean selfChange) {
            // A change to the list of accounts requires us to scan for deletions (so we can stop running syncs)
            // At startup, we want to see what accounts exist and cache them
            ArrayList<Long> currentIds = new ArrayList<Long>();
            Cursor c = getContentResolver().query(EmailContent.Account.CONTENT_URI, EmailContent.Account.CONTENT_PROJECTION, null, null, null);
            try {
                if (c.moveToFirst()) {
                    do {
                        currentIds.add(c.getLong(EmailContent.Account.CONTENT_ID_COLUMN));
                    } while (c.moveToNext());
                }
                for (long accountId: accountIds) {
                    if (!currentIds.contains(accountId)) {
                        // This is a deletion; shut down any account-related syncs
                        accountDeleted(accountId);
                    }
                }
                for (long accountId: currentIds) {
                    if (!accountIds.contains(accountId)) {
                        // This is an addition; create our magic hidden mailbox...
                        initializeAccount(accountId);
                    }
                }
            } finally {
                c.close();
            }

            // See if there's anything to do...
            kick();
        }

        private void initializeAccount (long acctId) {
            EmailContent.Account acct = EmailContent.Account.restoreAccountWithId(getContext(), acctId);
            EmailContent.Mailbox main = new EmailContent.Mailbox();
            main.mDisplayName = "_main";
            main.mServerId = "_main";
            main.mAccountKey = acct.mId;
            main.mType = EmailContent.Mailbox.TYPE_MAIL;
            main.mSyncFrequency = EmailContent.Account.CHECK_INTERVAL_PUSH;
            main.mFlagVisible = false;
            main.save(getContext());
            INSTANCE.log("Initializing account: " + acct.mDisplayName);

        }

        private void accountDeleted (long acctId) {
            synchronized (mSyncToken) {
                List<Long> deletedBoxes = new ArrayList<Long>();
                for (Long mid : INSTANCE.serviceMap.keySet()) {
                    EmailContent.Mailbox box = EmailContent.Mailbox.restoreMailboxWithId(INSTANCE, mid);
                    if (box != null) {
                        if (box.mAccountKey == acctId) {
                            ProtocolService svc = INSTANCE.serviceMap.get(mid);
                            if (svc != null) {
                                svc.stop();
                                svc.mThread.interrupt();
                            }
                            deletedBoxes.add(mid);
                        }
                    }
                }
                for (Long mid : deletedBoxes) {
                    INSTANCE.serviceMap.remove(mid);
                }
            }
        }
    }

    class MailboxObserver extends ContentObserver {
        public MailboxObserver(Handler handler) {
            super(handler);
        }

        public void onChange (boolean selfChange) {
            // See if there's anything to do...
            kick();
        }
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    public void log (String str) {
        Log.v("EmailApp:MailService", str);
    }

    @Override
    public void onCreate () {
        INSTANCE = this;

        mAccountObserver = new AccountObserver(mHandler);
        mMailboxObserver = new MailboxObserver(mHandler);

        // Start our thread...
        if (mServiceThread == null || !mServiceThread.isAlive()) {
            log(mServiceThread == null ? "Starting thread..." : "Restarting thread...");
            mServiceThread = new Thread(this, "<MailService>");
            mServiceThread.start();
        } else {
            log("Attempt to start MailService though already started before?");
        }
    }

    static private HashMap<Long, Boolean> mWakeLocks = new HashMap<Long, Boolean>();
    static private HashMap<Long, PendingIntent> mPendingIntents = new HashMap<Long, PendingIntent>();
    static private WakeLock mWakeLock = null;

    static public void acquireWakeLock (long id) {
        synchronized (mWakeLocks) {
            Boolean lock = mWakeLocks.get(id);
            if (lock == null) {
                INSTANCE.log("+WakeLock requested for " + id);
                if (mWakeLock == null) {
                    PowerManager pm = (PowerManager) INSTANCE.getSystemService(Context.POWER_SERVICE);
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MAIL_SERVICE");
                    mWakeLock.acquire();
                    INSTANCE.log("+WAKE LOCK ACQUIRED");
                }
                mWakeLocks.put(id, true);
            }
        }
    }

    static public void releaseWakeLock (long id) {
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

    static private String alarmOwner (long id) {
        if (id == -1) {
            return "MailService";
        }
        else return "Mailbox " + Long.toString(id);
    }

    static private void clearAlarm (long id) {
        synchronized (mPendingIntents) {
            PendingIntent pi = mPendingIntents.get(id);
            if (pi != null) {
                AlarmManager alarmManager = (AlarmManager)INSTANCE.getSystemService(Context.ALARM_SERVICE);
                alarmManager.cancel(pi);
                INSTANCE.log("+Alarm cleared for " + alarmOwner(id));
                mPendingIntents.remove(id);
            }
        }
    }

    static private void setAlarm (long id, long millis) {
        synchronized (mPendingIntents) {
            PendingIntent pi = mPendingIntents.get(id);
            if (pi == null) {
                Intent i = new Intent(INSTANCE, KeepAliveReceiver.class);
                i.putExtra("mailbox", id);
                i.setData(Uri.parse("Box" + id));
                pi = PendingIntent.getBroadcast(INSTANCE, 0, i, 0);
                mPendingIntents.put(id, pi);

                AlarmManager alarmManager = (AlarmManager)INSTANCE.getSystemService(Context.ALARM_SERVICE);
                alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + millis, pi);
                INSTANCE.log("+Alarm set for " + alarmOwner(id) + ", " + millis + "ms");
            }
        }
    }

    static private void clearAlarms () {
        AlarmManager alarmManager = (AlarmManager)INSTANCE.getSystemService(Context.ALARM_SERVICE);
        synchronized (mPendingIntents) {
            for (PendingIntent pi : mPendingIntents.values()) {
                alarmManager.cancel(pi);
            }
            mPendingIntents.clear();
        }
    }   

    static public void runAwake (long id) {
        acquireWakeLock(id);
        clearAlarm(id);
    }

    static public void runAsleep (long id, long millis) {
        setAlarm(id, millis);
        releaseWakeLock(id);
    }

    static public void ping (long id) {
        ProtocolService service = INSTANCE.serviceMap.get(id);
        if (service != null) {
            EmailContent.Mailbox m = EmailContent.Mailbox.restoreMailboxWithId(INSTANCE, id);
            if (m != null) {
                service.mAccount = EmailContent.Account.restoreAccountWithId(INSTANCE, m.mAccountKey);
                service.mMailbox = m;
                service.ping();
            }
        }
    }

    @Override
    public void onDestroy () {
        log("!!! MaiLService onDestroy");
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
        clearAlarms();
    }

    public class ConnectivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive (Context context, Intent intent) {
            Bundle b = intent.getExtras();
            if (b != null) {
                NetworkInfo a = (NetworkInfo)b.get("networkInfo");
                String info = "CM Info: " + a.getTypeName();
                State state = a.getState();
                if (state == State.CONNECTED) {
                    info += " CONNECTED";
                } else if (state == State.CONNECTING) {
                    info += " CONNECTING";
                } else if (state == State.DISCONNECTED) {
                    info += " DISCONNECTED";
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

    private void pause (int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
        }
    }

    private void startService (ProtocolService service, EmailContent.Mailbox m) {
        synchronized (mSyncToken) {
            String mailboxName = m.mDisplayName;
            String accountName = service.mAccount.mDisplayName;
            Thread thread = new Thread(service, mailboxName + "(" + accountName + ")");
            log("Starting thread for " + mailboxName + " in account " + accountName);
            thread.start();
            serviceMap.put(m.mId, service);
        }
    }

    private void startService (EmailContent.Mailbox m) {
        synchronized (mSyncToken) {
            EmailContent.Account acct = EmailContent.Account.restoreAccountWithId(this, m.mAccountKey);
            if (acct != null) {
                ProtocolService service;
                service = new EasService(this, m);
                startService(service, m);
            }
        }
    }

    private void startSleep () {
        synchronized (mSyncToken) {
            // Shut everything down
            boolean stoppedOne = false;
            // Keep track of which services we've stopped
            ArrayList<Long> toStop = new ArrayList<Long>();
            // Shut down all of our running services
            for (Long mid : serviceMap.keySet()) {
                toStop.add(mid);
                stoppedOne = true;
            }

            for (Long mid: toStop) {
                ProtocolService svc = serviceMap.get(mid);
                log("Going to sleep: shutting down "    + svc.mAccount.mDisplayName + "/" + svc.mMailboxName);
                svc.stop();
                svc.mThread.interrupt();
                stoppedOne = true;
            }
            // Remove the stopped services from the map
            //for (Long mid : stopped)
            //  serviceMap.remove(mid);
            // Let the UI know
            if (stoppedOne) {
            }
        }
    }

    private void broadcastSleep () {
    }

    public void run () {
        log("MailService: run");
        Debug.waitForDebugger();
        mStop = false;

        runAwake(-1);

        ContentResolver resolver = getContentResolver();
        resolver.registerContentObserver(EmailContent.Account.CONTENT_URI, false, mAccountObserver);
        resolver.registerContentObserver(EmailContent.Mailbox.CONTENT_URI, false, mMailboxObserver);

        ConnectivityReceiver cr = new ConnectivityReceiver();
        registerReceiver(cr, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        mSettings = PreferenceManager.getDefaultSharedPreferences(this);
        GregorianCalendar calendar = new GregorianCalendar();

        mStatus = AWAKE;

        try {
            while (!mStop) {
                runAwake(-1);
                log("%%MailService heartbeat");
                while (!mStop) {
                    NetworkInfo info = cm.getActiveNetworkInfo();
                    if (info != null && info.isConnected()) {
                        break;
                    } else {
                        pause(10*SECS);
                    }
                }

                long nextWait = 10*MINS;
                long now = System.currentTimeMillis();

                // We can be MUCH smarter!  We can send notices of sleep time changes and otherwise cache all of this...
                long sleepHours = mSettings.getLong("sleep_hours", 0);
                if (sleepHours != 0) {
                    boolean pastStartTime = false;
                    boolean beforeEndTime = false;
                    boolean wantSleep = false;
                    calendar.setTimeInMillis(now);
                    int nowHour = calendar.get(GregorianCalendar.HOUR_OF_DAY);
                    int nowMinute = calendar.get(GregorianCalendar.MINUTE);

                    long sleepStart = sleepHours >> 32;
                    int startHour = (int)(sleepStart / 100);
                    int startMinute = (int)(sleepStart % 100);

                    long sleepEnd = sleepHours & 0x00000000FFFFFFFFL;
                    int endHour = (int)(sleepEnd / 100);
                    int endMinute = (int)(sleepEnd % 100);

                    if (sleepStart > sleepEnd) {
                        if ((nowHour > startHour) || (nowHour == startHour && nowMinute >= startMinute) || (nowHour < endHour) || (nowHour == endHour && nowMinute <= endMinute))
                            wantSleep = true;
                    } else if (((startHour < nowHour || (startHour == nowHour && nowMinute >= startMinute)) && ((nowHour < endHour) || (nowHour == endHour && nowMinute <= endMinute))))
                        wantSleep = true;

                    if (wantSleep && (mStatus == AWAKE)) {
                        mStatus = SLEEP_HOURS;
                        log("Going to sleep now...");
                        log("startHour: " + startHour + ", startMinute: " + startMinute + ", endHour: " + endHour + ", endMinute: " + endMinute);
                        log("nowHour: " + nowHour + ", nowMinute: " + nowMinute);
                        startSleep();
                        broadcastSleep();
                    } else if (!wantSleep && (mStatus == SLEEP_HOURS)) {
                        mStatus = AWAKE;
                        log("Waking up now: " + (pastStartTime ? "pastStartTime, " : "not pastStartTime, ") + (beforeEndTime ? "beforeEndTime" : "not beforeEndTime"));
                        log("startHour: " + startHour + ", startMinute: " + startMinute + ", endHour: " + endHour + ", endMinute: " + endMinute);
                        log("nowHour: " + nowHour + ", nowMinute: " + nowMinute);
                        broadcastSleep();
                    }
                }

                boolean sleepWeekends = mSettings.getBoolean("sleep_weekends", false);
                if ((mStatus != SLEEP_HOURS) && ((mStatus != AWAKE) || sleepWeekends)) {
                    boolean wantSleep = false;
                    calendar.setTimeInMillis(now);
                    int day = calendar.get(GregorianCalendar.DAY_OF_WEEK);
                    if (sleepWeekends && (day == GregorianCalendar.SATURDAY || day == GregorianCalendar.SUNDAY)) {
                        wantSleep = true;
                    }
                    if ((mStatus == AWAKE) && wantSleep) {
                        mStatus = SLEEP_WEEKEND;
                        startSleep();
                        broadcastSleep();
                    } else if ((mStatus != AWAKE) && !wantSleep) {
                        // Wake up!!
                        mStatus = AWAKE;
                        broadcastSleep();
                    }
                }

                boolean offline = mSettings.getBoolean("offline", false);
                if (mStatus == AWAKE || mStatus == OFFLINE) {
                    boolean wantSleep = offline;
                    if ((mStatus == AWAKE) && wantSleep) {
                        mStatus = OFFLINE;
                        startSleep();
                        broadcastSleep();
                    } else if ((mStatus == OFFLINE) && !wantSleep) {
                        // Wake up!!
                        mStatus = AWAKE;
                        broadcastSleep();
                    }
                }

                if (!mStop && ((mStatus == AWAKE) || mToothpicks)) {
                    // Start up threads that need it...
                    try {
                        Cursor c = getContentResolver().query(EmailContent.Mailbox.CONTENT_URI, EmailContent.Mailbox.CONTENT_PROJECTION, null, null, null);
                        if (c.moveToFirst()) {
                            // TODO Could be much faster - just get cursor of ones we're watching...
                            do {
                                long mid = c.getLong(EmailContent.Mailbox.CONTENT_ID_COLUMN);
                                ProtocolService service = serviceMap.get(mid);
                                if (service == null) {
                                    long freq = c.getInt(EmailContent.Mailbox.CONTENT_SYNC_FREQUENCY_COLUMN);
                                    if (freq == EmailContent.Account.CHECK_INTERVAL_PUSH) {
                                        EmailContent.Mailbox m = EmailContent.getContent(c, EmailContent.Mailbox.class);
                                        // Either we're good to go, or it's been 30 minutes (the default for idle timeout)
                                        if (((m.mFlags & EmailContent.Mailbox.FLAG_CANT_PUSH) == 0) || ((now - m.mSyncTime) > (1000 * 60 * 30L))) {
                                            startService(m);
                                        }
                                    } else if (freq == -19) {
                                        // See if we've got anything to do...
                                        int cnt = EmailContent.count(this, EmailContent.Message.CONTENT_URI, "mailboxKey=" + mid + " and syncServerId=0", null);
                                        if (cnt > 0) {
                                            EmailContent.Mailbox m = EmailContent.getContent(c, EmailContent.Mailbox.class);
                                            startService(new EasOutboxService(this, m), m);
                                        }
                                    } else if (freq > 0 && freq <= 1440) {
                                        long lastSync = c.getLong(EmailContent.Mailbox.CONTENT_SYNC_TIME_COLUMN);
                                        if (now - lastSync > (freq * 60000L)) {
                                            EmailContent.Mailbox m = EmailContent.getContent(c, EmailContent.Mailbox.class);
                                            startService(m);
                                        }
                                    }
                                } else {
                                    Thread thread = service.mThread;
                                    if (!thread.isAlive()) {
                                        log("Removing dead thread for " + c.getString(EmailContent.Mailbox.CONTENT_DISPLAY_NAME_COLUMN));
                                        serviceMap.remove(mid);
                                        // Restart this if necessary
                                        if (nextWait > 3*SECS) {
                                            nextWait = 3*SECS;
                                        }
                                    } else {
                                        long requestTime = service.mRequestTime;
                                        if (requestTime > 0) {
                                            long timeToRequest = requestTime - now;
                                            if (service instanceof ProtocolService && timeToRequest <= 0) {
                                                service.mRequestTime = 0;
                                                service.ping();
                                            } else if (requestTime > 0 && timeToRequest < nextWait) {
                                                if (timeToRequest < 11*MINS) {
                                                    nextWait = timeToRequest < 250 ? 250 : timeToRequest;
                                                } else {
                                                    log("Illegal timeToRequest: " + timeToRequest);
                                                }
                                            }
                                        }
                                    }
                                }
                            } while (c.moveToNext());
                        }
                        c.close();

                    } catch (Exception e1) {
                        log("Exception to follow...");
                    }
                }

                try {
                    synchronized (INSTANCE) {
                        if (nextWait < 0) {
                            System.err.println("WTF?");
                            nextWait = 1*SECS;
                        }
                        if (nextWait > 30*SECS) {
                            runAsleep(-1, nextWait - 1000);
                        }

                        log("%%MailService sleeping for " + (nextWait / 1000) + " s");
                        INSTANCE.wait(nextWait);
                    }
                } catch (InterruptedException e) {
                    log("IOException to follow...");
                }

                if (mStop) {
                    startSleep();
                    log("Shutdown requested.");
                    return;
                }

            }
        } catch (Throwable e) {
            log("MailService crashed.");
        } finally {
            log("Goodbye.");
        }

        startService(new Intent(this, SyncManager.class));
        throw new RuntimeException("MailService crash; please restart me...");
    }

    static public void serviceRequest (EmailContent.Mailbox m) {
        serviceRequest(m.mId, 10*SECS);
    }

    static public void serviceRequest (long mailboxId) {
        serviceRequest(mailboxId, 10*SECS);
    }

    static public void serviceRequest (long mailboxId, long ms) {
        try {
            if (INSTANCE == null)
                return;
            ProtocolService service = INSTANCE.serviceMap.get(mailboxId);
            if (service != null) {
                service.mRequestTime = System.currentTimeMillis() + ms;
                kick();
            } else {
                startManualSync(mailboxId);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static public void serviceRequestImmediate (long mailboxId) {
        ProtocolService service = INSTANCE.serviceMap.get(mailboxId);
        if (service != null) {
            service.mRequestTime = System.currentTimeMillis() ;
            EmailContent.Mailbox m = EmailContent.Mailbox.restoreMailboxWithId(INSTANCE, mailboxId);
            service.mAccount = EmailContent.Account.restoreAccountWithId(INSTANCE, m.mAccountKey);
            service.mMailbox = m;
            kick();
        }
    }

    static public void partRequest (PartRequest req) {
        EmailContent.Message msg = EmailContent.Message.restoreMessageWithId(INSTANCE, req.emailId);
        if (msg == null) {
            return;
        }
        long mailboxId = msg.mMailboxKey;
        ProtocolService service = INSTANCE.serviceMap.get(mailboxId);

        if (service == null) 
            service = startManualSync(mailboxId);

        if (service != null) {
            service.mRequestTime = System.currentTimeMillis();
            service.addPartRequest(req);
            kick();
        }
    }

    static public PartRequest hasPartRequest(long emailId, String part) {
        EmailContent.Message msg = EmailContent.Message.restoreMessageWithId(INSTANCE, emailId);
        if (msg == null) {
            return null;
        }
        long mailboxId = msg.mMailboxKey;
        ProtocolService service = INSTANCE.serviceMap.get(mailboxId);
        if (service != null) {
            service.mRequestTime = System.currentTimeMillis();
            return service.hasPartRequest(emailId, part);
        }
        return null;
    }

    static public void cancelPartRequest(long emailId, String part) {
        EmailContent.Message msg = EmailContent.Message.restoreMessageWithId(INSTANCE, emailId);
        if (msg == null) {
            return;
        }
        long mailboxId = msg.mMailboxKey;
        ProtocolService service = INSTANCE.serviceMap.get(mailboxId);
        if (service != null) {
            service.mRequestTime = System.currentTimeMillis();
            service.cancelPartRequest(emailId, part);
        }
    }

    public class SyncStatus {
        static public final int NOT_RUNNING = 0;
        static public final int DIED = 1;
        static public final int SYNC = 2;
        static public final int IDLE = 3;
    }

    static public int getSyncStatus (long mid) {
        synchronized (mSyncToken) {
            if (INSTANCE == null || INSTANCE.serviceMap == null) {
                return SyncStatus.NOT_RUNNING;
            }
            ProtocolService svc = INSTANCE.serviceMap.get(mid);
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

    static public ProtocolService startManualSync (long mid) {
        if (INSTANCE == null || INSTANCE.serviceMap == null)
            return null;
        INSTANCE.log("startManualSync");
        synchronized (mSyncToken) {
            if (INSTANCE.serviceMap.get(mid) == null) {
                EmailContent.Mailbox m = EmailContent.Mailbox.restoreMailboxWithId(INSTANCE, mid);
                INSTANCE.log("Starting sync for " + m.mDisplayName);
                INSTANCE.startService(m);
            }
        }
        return INSTANCE.serviceMap.get(mid);
    }

    // DO NOT CALL THIS IN A LOOP ON THE SERVICEMAP
    static public void stopManualSync (long mid) {
        if (INSTANCE == null || INSTANCE.serviceMap == null) {
            return;
        }
        synchronized (mSyncToken) {
            ProtocolService svc = INSTANCE.serviceMap.get(mid);
            if (svc != null) {
                INSTANCE.log("Stopping sync for " + svc.mMailboxName);
                svc.stop();
                svc.mThread.interrupt();
            }
        }
    }

    static public void kick () {
        if (INSTANCE == null) {
            return;
        }
        synchronized (INSTANCE) {
            INSTANCE.log("We've been kicked!");
            INSTANCE.notify();
        }
    }

    static public void kick (long mid) {
        EmailContent.Mailbox m = EmailContent.Mailbox.restoreMailboxWithId(INSTANCE, mid);
        int syncType = m.mSyncFrequency;
        if (syncType == EmailContent.Account.CHECK_INTERVAL_PUSH) {
            SyncManager.serviceRequestImmediate(mid);
        } else {
            SyncManager.startManualSync(mid);
        }
    }


    static public void accountUpdated (long acctId) {
        synchronized (mSyncToken) {
            for (ProtocolService svc : INSTANCE.serviceMap.values()) {
                if (svc.mAccount.mId == acctId) {
                    svc.mAccount = EmailContent.Account.restoreAccountWithId(INSTANCE, acctId);
                }
            }
        }
    }

    static public int status () {
        return mStatus;
    }

    static public boolean isSleeping () {
        return (mStatus == SLEEP_HOURS || mStatus == SLEEP_WEEKEND);
    }

    static public void forceAwake (boolean wake) {
        mToothpicks = wake;
        kick();
    }

    static public boolean isForceAwake () {
        return mToothpicks;
    }

    static public void done (ProtocolService svc) {
        INSTANCE.serviceMap.remove(svc.mMailboxId);
    }

    public static void shutdown () {
        INSTANCE.mStop = true;
        kick();
        INSTANCE.stopSelf();
    }

    static public String serviceName (long id) {
        if (id < 0) {
            return "MailService";
        } else {
            ProtocolService service = INSTANCE.serviceMap.get(id);
            if (service != null) {
                return service.mThread.getName();
            } else {
                return "Not running?";
            }
        }
    }

    static public Context getContext () {
        if (INSTANCE == null) {
            return null;
        }
        return (Context)INSTANCE;
    }
}
