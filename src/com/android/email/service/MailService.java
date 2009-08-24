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

package com.android.email.service;

import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.activity.MessageList;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Mailbox;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Config;
import android.util.Log;

import java.util.HashMap;

/**
 * Background service for refreshing non-push email accounts.
 */
public class MailService extends Service {
    /** DO NOT CHECK IN "TRUE" */
    private static final boolean DEBUG_FORCE_QUICK_REFRESH = false;        // force 1-minute refresh

    public static int NEW_MESSAGE_NOTIFICATION_ID = 1;

    private static final String ACTION_CHECK_MAIL =
        "com.android.email.intent.action.MAIL_SERVICE_WAKEUP";
    private static final String ACTION_RESCHEDULE =
        "com.android.email.intent.action.MAIL_SERVICE_RESCHEDULE";
    private static final String ACTION_CANCEL =
        "com.android.email.intent.action.MAIL_SERVICE_CANCEL";
    private static final String ACTION_NOTIFY_MAIL =
        "com.android.email.intent.action.MAIL_SERVICE_NOTIFY";

    private static final String EXTRA_CHECK_ACCOUNT = "com.android.email.intent.extra.ACCOUNT";
    private static final String EXTRA_ACCOUNT_INFO = "com.android.email.intent.extra.ACCOUNT_INFO";

    private static final String[] NEW_MESSAGE_COUNT_PROJECTION =
        new String[] {AccountColumns.NEW_MESSAGE_COUNT};

    private Controller.Result mControllerCallback = new ControllerResults();

    private int mStartId;

    /**
     * Access must be synchronized, because there are accesses from the Controller callback
     */
    private static HashMap<Long,AccountSyncReport> mSyncReports =
        new HashMap<Long,AccountSyncReport>();

    /**
     * Simple template used for clearing new message count in accounts
     */
    static ContentValues mClearNewMessages;
    static {
        mClearNewMessages = new ContentValues();
        mClearNewMessages.put(Account.NEW_MESSAGE_COUNT, 0);
    }

    public static void actionReschedule(Context context) {
        Intent i = new Intent();
        i.setClass(context, MailService.class);
        i.setAction(MailService.ACTION_RESCHEDULE);
        context.startService(i);
    }

    public static void actionCancel(Context context)  {
        Intent i = new Intent();
        i.setClass(context, MailService.class);
        i.setAction(MailService.ACTION_CANCEL);
        context.startService(i);
    }

    /**
     * Reset new message counts for one or all accounts.  This clears both our local copy and
     * the values (if any) stored in the account records.
     *
     * @param accountId account to clear, or -1 for all accounts
     */
    public static void resetNewMessageCount(Context context, long accountId) {
        synchronized (mSyncReports) {
            for (AccountSyncReport report : mSyncReports.values()) {
                if (accountId == -1 || accountId == report.accountId) {
                    report.numNewMessages = 0;
                }
            }
        }
        // now do the database - all accounts, or just one of them
        Uri uri;
        if (accountId == -1) {
            uri = Account.CONTENT_URI;
        } else {
            uri = ContentUris.withAppendedId(Account.CONTENT_URI, accountId);
        }
        context.getContentResolver().update(uri, mClearNewMessages, null, null);
    }

    /**
     * Entry point for asynchronous message services (e.g. push mode) to post notifications of new
     * messages.  This assumes that the push provider has already synced the messages into the
     * appropriate database - this simply triggers the notification mechanism.
     *
     * @param context a context
     * @param accountId the id of the account that is reporting new messages
     * @param newCount the number of new messages
     */
    public static void actionNotifyNewMessages(Context context, long accountId) {
        Intent i = new Intent(ACTION_NOTIFY_MAIL);
        i.setClass(context, MailService.class);
        i.putExtra(EXTRA_CHECK_ACCOUNT, accountId);
        context.startService(i);
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);

        // TODO this needs to be passed through the controller and back to us
        this.mStartId = startId;
        String action = intent.getAction();

        Controller controller = Controller.getInstance(getApplication());
        controller.addResultCallback(mControllerCallback);

        if (ACTION_CHECK_MAIL.equals(action)) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "*** MailService: checking mail");
            }
            // If we have the data, restore the last-sync-times for each account
            // These are cached in the wakeup intent in case the process was killed.
            restoreSyncReports(intent);

            // Sync a specific account if given
            long checkAccountId = intent.getLongExtra(EXTRA_CHECK_ACCOUNT, -1);
            if (checkAccountId != -1) {
                // launch an account sync in the controller
                syncOneAccount(controller, checkAccountId, startId);
            } else {
                // Find next account to sync, and reschedule
                AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
                reschedule(alarmManager);
                stopSelf(startId);
            }
        }
        else if (ACTION_CANCEL.equals(action)) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "*** MailService: cancel");
            }
            cancel();
            stopSelf(startId);
        }
        else if (ACTION_RESCHEDULE.equals(action)) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "*** MailService: reschedule");
            }
            AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
            reschedule(alarmManager);
            stopSelf(startId);
        } else if (ACTION_NOTIFY_MAIL.equals(action)) {
            long accountId = intent.getLongExtra(EXTRA_CHECK_ACCOUNT, -1);
            // Get the current new message count
            Cursor c = getContentResolver().query(
                    ContentUris.withAppendedId(Account.CONTENT_URI, accountId),
                    NEW_MESSAGE_COUNT_PROJECTION, null, null, null);
            int newMessageCount = 0;
            try {
                if (c.moveToFirst()) {
                    newMessageCount = c.getInt(0);
                } else {
                    // If the account no longer exists, set to -1 (which is handled below)
                    accountId = -1;
                }
            } finally {
                c.close();
            }
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "*** MailService: notify accountId=" + Long.toString(accountId)
                        + " count=" + newMessageCount);
            }
            if (accountId != -1) {
                updateAccountReport(accountId, newMessageCount);
                notifyNewMessages(accountId);
            }
            stopSelf(startId);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Controller.getInstance(getApplication()).removeResultCallback(mControllerCallback);
    }

    private void cancel() {
        AlarmManager alarmMgr = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = createAlarmIntent(-1, null);
        alarmMgr.cancel(pi);
    }

    /**
     * Create and send an alarm with the entire list.  This also sends a list of known last-sync
     * times with the alarm, so if we are killed between alarms, we don't lose this info.
     *
     * @param alarmMgr passed in so we can mock for testing.
     */
    /* package */ void reschedule(AlarmManager alarmMgr) {
        // restore the reports if lost
        setupSyncReports(-1);
        synchronized (mSyncReports) {
            int numAccounts = mSyncReports.size();
            long[] accountInfo = new long[numAccounts * 2];     // pairs of { accountId, lastSync }
            int accountInfoIndex = 0;

            long nextCheckTime = Long.MAX_VALUE;
            AccountSyncReport nextAccount = null;
            long timeNow = SystemClock.elapsedRealtime();

            for (AccountSyncReport report : mSyncReports.values()) {
                if (report.syncInterval <= 0) {                         // no timed checks - skip
                    continue;
                }
                // select next account to sync
                if ((report.prevSyncTime == 0)                          // never checked
                        || (report.nextSyncTime < timeNow)) {           // overdue
                    nextCheckTime = 0;
                    nextAccount = report;
                } else if (report.nextSyncTime < nextCheckTime) {       // next to be checked
                    nextCheckTime = report.nextSyncTime;
                    nextAccount = report;
                }
                // collect last-sync-times for all accounts
                // this is using pairs of {long,long} to simplify passing in a bundle
                accountInfo[accountInfoIndex++] = report.accountId;
                accountInfo[accountInfoIndex++] = report.prevSyncTime;
            }

            // set/clear alarm as needed
            long idToCheck = (nextAccount == null) ? -1 : nextAccount.accountId;
            PendingIntent pi = createAlarmIntent(idToCheck, accountInfo);

            if (nextAccount == null) {
                alarmMgr.cancel(pi);
                Log.d(Email.LOG_TAG, "alarm cancel - no account to check");
            } else {
                alarmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextCheckTime, pi);
                Log.d(Email.LOG_TAG, "alarm set at " + nextCheckTime + " for " + nextAccount);
            }
        }
    }

    /**
     * Return a pending intent for use by this alarm.  Most of the fields must be the same
     * (in order for the intent to be recognized by the alarm manager) but the extras can
     * be different, and are passed in here as parameters.
     */
    /* package */ PendingIntent createAlarmIntent(long checkId, long[] accountInfo) {
        Intent i = new Intent();
        i.setClassName("com.android.email", "com.android.email.service.MailService");
        i.setAction(ACTION_CHECK_MAIL);
        i.putExtra(EXTRA_CHECK_ACCOUNT, checkId);
        i.putExtra(EXTRA_ACCOUNT_INFO, accountInfo);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        return pi;
    }

    /**
     * Start a controller sync for a specific account
     */
    private void syncOneAccount(Controller controller, long checkAccountId, int startId) {
        long inboxId = Mailbox.findMailboxOfType(this, checkAccountId, Mailbox.TYPE_INBOX);
        if (inboxId == Mailbox.NO_MAILBOX) {
            // no inbox??  sync mailboxes
        } else {
            controller.serviceCheckMail(checkAccountId, inboxId, startId, mControllerCallback);
        }
    }

    /**
     * Note:  Times are relative to SystemClock.elapsedRealtime()
     */
    private static class AccountSyncReport {
        long accountId;
        long prevSyncTime;      // 0 == unknown
        long nextSyncTime;      // 0 == ASAP  -1 == don't sync
        int numNewMessages;

        int syncInterval;
        boolean notify;
        boolean vibrate;
        Uri ringtoneUri;

        String displayName;     // temporary, for debug logging


        @Override
        public String toString() {
            return displayName + ": prevSync=" + prevSyncTime + " nextSync=" + nextSyncTime
                    + " numNew=" + numNewMessages;
        }
    }

    /**
     * scan accounts to create a list of { acct, prev sync, next sync, #new }
     * use this to create a fresh copy.  assumes all accounts need sync
     *
     * @param accountId -1 will rebuild the list if empty.  other values will force loading
     *   of a single account (e.g if it was created after the original list population)
     */
    /* package */ void setupSyncReports(long accountId) {
        synchronized (mSyncReports) {
            if (accountId == -1) {
                // -1 == reload the list if empty, otherwise exit immediately
                if (mSyncReports.size() > 0) {
                    return;
                }
            } else {
                // load a single account if it doesn't already have a sync record
                if (mSyncReports.containsKey(accountId)) {
                    return;
                }
            }

            // setup to add a single account or all accounts
            Uri uri;
            if (accountId == -1) {
                uri = Account.CONTENT_URI;
            } else {
                uri = ContentUris.withAppendedId(Account.CONTENT_URI, accountId);
            }

            // TODO use a narrower projection here
            Cursor c = getContentResolver().query(uri, Account.CONTENT_PROJECTION,
                    null, null, null);
            try {
                while (c.moveToNext()) {
                    AccountSyncReport report = new AccountSyncReport();
                    int syncInterval = c.getInt(Account.CONTENT_SYNC_INTERVAL_COLUMN);
                    int flags = c.getInt(Account.CONTENT_FLAGS_COLUMN);
                    String ringtoneString = c.getString(Account.CONTENT_RINGTONE_URI_COLUMN);

                    // For debugging only
                    if (DEBUG_FORCE_QUICK_REFRESH && syncInterval >= 0) {
                        syncInterval = 1;
                    }

                    report.accountId = c.getLong(Account.CONTENT_ID_COLUMN);
                    report.prevSyncTime = 0;
                    report.nextSyncTime = (syncInterval > 0) ? 0 : -1;  // 0 == ASAP -1 == no sync
                    report.numNewMessages = 0;

                    report.syncInterval = syncInterval;
                    report.notify = (flags & Account.FLAGS_NOTIFY_NEW_MAIL) != 0;
                    report.vibrate = (flags & Account.FLAGS_VIBRATE) != 0;
                    report.ringtoneUri = (ringtoneString == null) ? null
                                                                  : Uri.parse(ringtoneString);

                    report.displayName = c.getString(Account.CONTENT_DISPLAY_NAME_COLUMN);

                    // TODO lookup # new in inbox
                    mSyncReports.put(report.accountId, report);
                }
            } finally {
                c.close();
            }
        }
    }

    /**
     * Update list with a single account's sync times and unread count
     *
     * @param accountId the account being udpated
     * @param newCount the number of new messages, or -1 if not being reported (don't update)
     * @return the report for the updated account, or null if it doesn't exist (e.g. deleted)
     */
    /* package */ AccountSyncReport updateAccountReport(long accountId, int newCount) {
        // restore the reports if lost
        setupSyncReports(accountId);
        synchronized (mSyncReports) {
            AccountSyncReport report = mSyncReports.get(accountId);
            if (report == null) {
                // discard result - there is no longer an account with this id
                Log.d(Email.LOG_TAG, "No account to update for id=" + Long.toString(accountId));
                return null;
            }

            // report found - update it (note - editing the report while in-place in the hashmap)
            report.prevSyncTime = SystemClock.elapsedRealtime();
            if (report.syncInterval > 0) {
                report.nextSyncTime = report.prevSyncTime + (report.syncInterval * 1000 * 60);
            }
            if (newCount != -1) {
                report.numNewMessages = newCount;
            }
            Log.d(Email.LOG_TAG, "update account " + report.toString());
            return report;
        }
    }

    /**
     * when we receive an alarm, update the account sync reports list if necessary
     * this will be the case when if we have restarted the process and lost the data
     * in the global.
     *
     * @param restoreIntent the intent with the list
     */
    /* package */ void restoreSyncReports(Intent restoreIntent) {
        // restore the reports if lost
        setupSyncReports(-1);
        synchronized (mSyncReports) {
            long[] accountInfo = restoreIntent.getLongArrayExtra(EXTRA_ACCOUNT_INFO);
            if (accountInfo == null) {
                Log.d(Email.LOG_TAG, "no data in intent to restore");
                return;
            }
            int accountInfoIndex = 0;
            int accountInfoLimit = accountInfo.length;
            while (accountInfoIndex < accountInfoLimit) {
                long accountId = accountInfo[accountInfoIndex++];
                long prevSync = accountInfo[accountInfoIndex++];
                AccountSyncReport report = mSyncReports.get(accountId);
                if (report != null) {
                    if (report.prevSyncTime == 0) {
                        report.prevSyncTime = prevSync;
                        Log.d(Email.LOG_TAG, "restore prev sync for account" + report);
                    }
                }
            }
        }
    }

    class ControllerResults implements Controller.Result {

        public void loadAttachmentCallback(MessagingException result, long messageId,
                long attachmentId, int progress) {
        }

        public void updateMailboxCallback(MessagingException result, long accountId,
                long mailboxId, int progress, int numNewMessages) {
            if (result == null) {
                updateAccountReport(accountId, numNewMessages);
                if (numNewMessages > 0) {
                    notifyNewMessages(accountId);
                }
            } else {
                updateAccountReport(accountId, -1);
            }
        }

        public void updateMailboxListCallback(MessagingException result, long accountId,
                int progress) {
        }

        public void serviceCheckMailCallback(MessagingException result, long accountId,
                long mailboxId, int progress, long tag) {
            if (progress == 100) {
                AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
                reschedule(alarmManager);
                int serviceId = MailService.this.mStartId;
                if (tag != 0) {
                    serviceId = (int) tag;
                }
                stopSelf(serviceId);
            }
        }

        public void sendMailCallback(MessagingException result, long accountId, long messageId,
                int progress) {
        }
    }

    /**
     * Prepare notifications for a given new account having received mail
     * The notification is organized around the account that has the new mail (e.g. selecting
     * the alert preferences) but the notification will include a summary if other
     * accounts also have new mail.
     */
    private void notifyNewMessages(long accountId) {
        boolean notify = false;
        boolean vibrate = false;
        Uri ringtone = null;
        int accountsWithNewMessages = 0;
        int numNewMessages = 0;
        String reportName = null;
        synchronized (mSyncReports) {
            for (AccountSyncReport report : mSyncReports.values()) {
                if (report.numNewMessages == 0) {
                    continue;
                }
                numNewMessages += report.numNewMessages;
                accountsWithNewMessages += 1;
                if (report.accountId == accountId) {
                    notify = report.notify;
                    vibrate = report.vibrate;
                    ringtone = report.ringtoneUri;
                    reportName = report.displayName;
                }
            }
        }
        if (!notify) {
            return;
        }

        // set up to post a notification
        Intent intent;
        String reportString;

        if (accountsWithNewMessages == 1) {
            // Prepare a report for a single account
            // "12 unread (gmail)"
            reportString = getResources().getQuantityString(
                    R.plurals.notification_new_one_account_fmt, numNewMessages,
                    numNewMessages, reportName);
            intent = MessageList.actionHandleAccountIntent(this,
                    accountId, -1, Mailbox.TYPE_INBOX);
        } else {
            // Prepare a report for multiple accounts
            // "4 accounts"
            reportString = getResources().getQuantityString(
                    R.plurals.notification_new_multi_account_fmt, accountsWithNewMessages,
                    accountsWithNewMessages);
            intent = MessageList.actionHandleAccountIntent(this,
                    -1, Mailbox.QUERY_ALL_INBOXES, -1);
        }

        // prepare appropriate pending intent, set up notification, and send
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent, 0);

        Notification notification = new Notification(
                R.drawable.stat_notify_email_generic,
                getString(R.string.notification_new_title),
                System.currentTimeMillis());
        notification.setLatestEventInfo(this,
                getString(R.string.notification_new_title),
                reportString,
                pending);

        notification.sound = ringtone;
        notification.defaults = vibrate
            ? Notification.DEFAULT_LIGHTS | Notification.DEFAULT_VIBRATE
            : Notification.DEFAULT_LIGHTS;

        NotificationManager notificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NEW_MESSAGE_NOTIFICATION_ID, notification);
    }
}
