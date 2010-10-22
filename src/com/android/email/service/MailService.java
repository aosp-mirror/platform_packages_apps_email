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

import com.android.email.AccountBackupRestore;
import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.activity.MessageList;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.HostAuth;
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
import android.media.AudioManager;
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
    private static final boolean DEBUG_FORCE_QUICK_REFRESH = false;     // force 1-minute refresh

    private static final String LOG_TAG = "Email-MailService";

    public static final int NOTIFICATION_ID_NEW_MESSAGES = 1;
    public static final int NOTIFICATION_ID_SECURITY_NEEDED = 2;
    public static final int NOTIFICATION_ID_EXCHANGE_CALENDAR_ADDED = 3;

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
    private static final String EXTRA_DEBUG_WATCHDOG = "com.android.email.intent.extra.WATCHDOG";

    private static final int WATCHDOG_DELAY = 10 * 60 * 1000;   // 10 minutes

    private static final String[] NEW_MESSAGE_COUNT_PROJECTION =
        new String[] {AccountColumns.NEW_MESSAGE_COUNT};

    private final Controller.Result mControllerCallback = new ControllerResults();

    private int mStartId;

    /**
     * Access must be synchronized, because there are accesses from the Controller callback
     */
    private static HashMap<Long,AccountSyncReport> mSyncReports =
        new HashMap<Long,AccountSyncReport>();

    /**
     * Simple template used for clearing new message count in accounts
     */
    private static final ContentValues CLEAR_NEW_MESSAGES;
    static {
        CLEAR_NEW_MESSAGES = new ContentValues();
        CLEAR_NEW_MESSAGES.put(Account.NEW_MESSAGE_COUNT, 0);
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
        context.getContentResolver().update(uri, CLEAR_NEW_MESSAGES, null, null);
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
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        // Restore accounts, if it has not happened already
        AccountBackupRestore.restoreAccountsIfNeeded(this);

        // TODO this needs to be passed through the controller and back to us
        this.mStartId = startId;
        String action = intent.getAction();

        Controller controller = Controller.getInstance(getApplication());
        controller.addResultCallback(mControllerCallback);

        if (ACTION_CHECK_MAIL.equals(action)) {
            // If we have the data, restore the last-sync-times for each account
            // These are cached in the wakeup intent in case the process was killed.
            restoreSyncReports(intent);

            // Sync a specific account if given
            AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
            long checkAccountId = intent.getLongExtra(EXTRA_CHECK_ACCOUNT, -1);
            if (Config.LOGD && Email.DEBUG) {
                Log.d(LOG_TAG, "action: check mail for id=" + checkAccountId);
            }
            if (checkAccountId >= 0) {
                setWatchdog(checkAccountId, alarmManager);
            }
            // if no account given, or the given account cannot be synced - reschedule
            if (checkAccountId == -1 || !syncOneAccount(controller, checkAccountId, startId)) {
                // Prevent runaway on the current account by pretending it updated
                if (checkAccountId != -1) {
                    updateAccountReport(checkAccountId, 0);
                }
                // Find next account to sync, and reschedule
                reschedule(alarmManager);
                stopSelf(startId);
            }
        }
        else if (ACTION_CANCEL.equals(action)) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(LOG_TAG, "action: cancel");
            }
            cancel();
            stopSelf(startId);
        }
        else if (ACTION_RESCHEDULE.equals(action)) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(LOG_TAG, "action: reschedule");
            }
            // As a precaution, clear any outstanding Email notifications
            // We could be smarter and only do this when the list of accounts changes,
            // but that's an edge condition and this is much safer.
            NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.cancel(NOTIFICATION_ID_NEW_MESSAGES);

            // When called externally, we refresh the sync reports table to pick up
            // any changes in the account list or account settings
            refreshSyncReports();
            // Finally, scan for the next needing update, and set an alarm for it
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
                Log.d(LOG_TAG, "notify accountId=" + Long.toString(accountId)
                        + " count=" + newMessageCount);
            }
            if (accountId != -1) {
                updateAccountReport(accountId, newMessageCount);
                notifyNewMessages(accountId);
            }
            stopSelf(startId);
        }
        
        // Returning START_NOT_STICKY means that if a mail check is killed (e.g. due to memory
        // pressure, there will be no explicit restart.  This is OK;  Note that we set a watchdog
        // alarm before each mailbox check.  If the mailbox check never completes, the watchdog
        // will fire and get things running again.
        return START_NOT_STICKY;
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
        PendingIntent pi = createAlarmIntent(-1, null, false);
        alarmMgr.cancel(pi);
    }

    /**
     * Refresh the sync reports, to pick up any changes in the account list or account settings.
     */
    private void refreshSyncReports() {
        synchronized (mSyncReports) {
            // Make shallow copy of sync reports so we can recover the prev sync times
            HashMap<Long,AccountSyncReport> oldSyncReports =
                new HashMap<Long,AccountSyncReport>(mSyncReports);

            // Delete the sync reports to force a refresh from live account db data
            mSyncReports.clear();
            setupSyncReportsLocked(-1);

            // Restore prev-sync & next-sync times for any reports in the new list
            for (AccountSyncReport newReport : mSyncReports.values()) {
                AccountSyncReport oldReport = oldSyncReports.get(newReport.accountId);
                if (oldReport != null) {
                    newReport.prevSyncTime = oldReport.prevSyncTime;
                    if (newReport.syncInterval > 0 && newReport.prevSyncTime != 0) {
                        newReport.nextSyncTime =
                            newReport.prevSyncTime + (newReport.syncInterval * 1000 * 60);
                    }
                }
            }
        }
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
                if ("eas".equals(report.protocol)) {                    // no checks for eas accts
                    continue;
                }
                long prevSyncTime = report.prevSyncTime;
                long nextSyncTime = report.nextSyncTime;

                // select next account to sync
                if ((prevSyncTime == 0) || (nextSyncTime < timeNow)) {  // never checked, or overdue
                    nextCheckTime = 0;
                    nextAccount = report;
                } else if (nextSyncTime < nextCheckTime) {              // next to be checked
                    nextCheckTime = nextSyncTime;
                    nextAccount = report;
                }
                // collect last-sync-times for all accounts
                // this is using pairs of {long,long} to simplify passing in a bundle
                accountInfo[accountInfoIndex++] = report.accountId;
                accountInfo[accountInfoIndex++] = report.prevSyncTime;
            }

            // Clear out any unused elements in the array
            while (accountInfoIndex < accountInfo.length) {
                accountInfo[accountInfoIndex++] = -1;
            }

            // set/clear alarm as needed
            long idToCheck = (nextAccount == null) ? -1 : nextAccount.accountId;
            PendingIntent pi = createAlarmIntent(idToCheck, accountInfo, false);

            if (nextAccount == null) {
                alarmMgr.cancel(pi);
                if (Config.LOGD && Email.DEBUG) {
                    Log.d(LOG_TAG, "reschedule: alarm cancel - no account to check");
                }
            } else {
                alarmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextCheckTime, pi);
                if (Config.LOGD && Email.DEBUG) {
                    Log.d(LOG_TAG, "reschedule: alarm set at " + nextCheckTime
                            + " for " + nextAccount);
                }
            }
        }
    }

    /**
     * Create a watchdog alarm and set it.  This is used in case a mail check fails (e.g. we are
     * killed by the system due to memory pressure.)  Normally, a mail check will complete and
     * the watchdog will be replaced by the call to reschedule().
     * @param accountId the account we were trying to check
     * @param alarmMgr system alarm manager
     */
    private void setWatchdog(long accountId, AlarmManager alarmMgr) {
        PendingIntent pi = createAlarmIntent(accountId, null, true);
        long timeNow = SystemClock.elapsedRealtime();
        long nextCheckTime = timeNow + WATCHDOG_DELAY;
        alarmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextCheckTime, pi);
    }

    /**
     * Return a pending intent for use by this alarm.  Most of the fields must be the same
     * (in order for the intent to be recognized by the alarm manager) but the extras can
     * be different, and are passed in here as parameters.
     */
    /* package */ PendingIntent createAlarmIntent(long checkId, long[] accountInfo,
            boolean isWatchdog) {
        Intent i = new Intent();
        i.setClass(this, MailService.class);
        i.setAction(ACTION_CHECK_MAIL);
        i.putExtra(EXTRA_CHECK_ACCOUNT, checkId);
        i.putExtra(EXTRA_ACCOUNT_INFO, accountInfo);
        if (isWatchdog) {
            i.putExtra(EXTRA_DEBUG_WATCHDOG, true);
        }
        PendingIntent pi = PendingIntent.getService(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        return pi;
    }

    /**
     * Start a controller sync for a specific account
     *
     * @param controller The controller to do the sync work
     * @param checkAccountId the account Id to try and check
     * @param startId the id of this service launch
     * @return true if mail checking has started, false if it could not (e.g. bad account id)
     */
    private boolean syncOneAccount(Controller controller, long checkAccountId, int startId) {
        long inboxId = Mailbox.findMailboxOfType(this, checkAccountId, Mailbox.TYPE_INBOX);
        if (inboxId == Mailbox.NO_MAILBOX) {
            return false;
        } else {
            controller.serviceCheckMail(checkAccountId, inboxId, startId, mControllerCallback);
            return true;
        }
    }

    /**
     * Note:  Times are relative to SystemClock.elapsedRealtime()
     */
    private static class AccountSyncReport {
        long accountId;
        String protocol;
        long prevSyncTime;      // 0 == unknown
        long nextSyncTime;      // 0 == ASAP  -1 == don't sync
        int numNewMessages;

        int syncInterval;
        boolean notify;
        boolean vibrate;
        boolean vibrateWhenSilent;
        Uri ringtoneUri;

        String displayName;     // temporary, for debug logging


        @Override
        public String toString() {
            return displayName + ": id=" + accountId + " prevSync=" + prevSyncTime
                    + " nextSync=" + nextSyncTime + " numNew=" + numNewMessages;
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
            setupSyncReportsLocked(accountId);
        }
    }

    /**
     * Handle the work of setupSyncReports.  Must be synchronized on mSyncReports.
     */
    private void setupSyncReportsLocked(long accountId) {
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

                long acctId = c.getLong(Account.CONTENT_ID_COLUMN);
                Account account = Account.restoreAccountWithId(this, acctId);
                if (account == null) continue;
                HostAuth hostAuth = HostAuth.restoreHostAuthWithId(this, account.mHostAuthKeyRecv);
                if (hostAuth == null) continue;
                report.accountId = acctId;
                report.protocol = hostAuth.mProtocol;
                report.prevSyncTime = 0;
                report.nextSyncTime = (syncInterval > 0) ? 0 : -1;  // 0 == ASAP -1 == no sync
                report.numNewMessages = 0;

                report.syncInterval = syncInterval;
                report.notify = (flags & Account.FLAGS_NOTIFY_NEW_MAIL) != 0;
                report.vibrate = (flags & Account.FLAGS_VIBRATE_ALWAYS) != 0;
                report.vibrateWhenSilent = (flags & Account.FLAGS_VIBRATE_WHEN_SILENT) != 0;
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

    /**
     * Update list with a single account's sync times and unread count
     *
     * @param accountId the account being updated
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
                Log.d(LOG_TAG, "No account to update for id=" + Long.toString(accountId));
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
            if (Config.LOGD && Email.DEBUG) {
                Log.d(LOG_TAG, "update account " + report.toString());
            }
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
                Log.d(LOG_TAG, "no data in intent to restore");
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
                        if (report.syncInterval > 0 && report.prevSyncTime != 0) {
                            report.nextSyncTime =
                                report.prevSyncTime + (report.syncInterval * 1000 * 60);
                        }
                    }
                }
            }
        }
    }

    class ControllerResults implements Controller.Result {

        public void loadMessageForViewCallback(MessagingException result, long messageId,
                int progress) {
        }

        public void loadAttachmentCallback(MessagingException result, long messageId,
                long attachmentId, int progress) {
        }

        public void updateMailboxCallback(MessagingException result, long accountId,
                long mailboxId, int progress, int numNewMessages) {
            if (result != null || progress == 100) {
                // We only track the inbox here in the service - ignore other mailboxes
                long inboxId = Mailbox.findMailboxOfType(MailService.this,
                        accountId, Mailbox.TYPE_INBOX);
                if (mailboxId == inboxId) {
                    if (progress == 100) {
                        updateAccountReport(accountId, numNewMessages);
                        if (numNewMessages > 0) {
                            notifyNewMessages(accountId);
                        }
                    } else {
                        updateAccountReport(accountId, -1);
                    }
                }
                // Call the global refresh tracker for all mailboxes
                Email.updateMailboxRefreshTime(mailboxId);
            }
        }

        public void updateMailboxListCallback(MessagingException result, long accountId,
                int progress) {
        }

        public void serviceCheckMailCallback(MessagingException result, long accountId,
                long mailboxId, int progress, long tag) {
            if (result != null || progress == 100) {
                if (result != null) {
                    // the checkmail ended in an error.  force an update of the refresh
                    // time, so we don't just spin on this account
                    updateAccountReport(accountId, -1);
                }
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
        boolean vibrateWhenSilent = false;
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
                    vibrateWhenSilent = report.vibrateWhenSilent;
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
            intent = MessageList.createIntent(this, accountId, -1, Mailbox.TYPE_INBOX);
        } else {
            // Prepare a report for multiple accounts
            // "4 accounts"
            reportString = getResources().getQuantityString(
                    R.plurals.notification_new_multi_account_fmt, accountsWithNewMessages,
                    accountsWithNewMessages);
            intent = MessageList.createIntent(this, -1, Mailbox.QUERY_ALL_INBOXES, -1);
        }

        // prepare appropriate pending intent, set up notification, and send
        PendingIntent pending =
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification(
                R.drawable.stat_notify_email_generic,
                getString(R.string.notification_new_title),
                System.currentTimeMillis());
        notification.setLatestEventInfo(this,
                getString(R.string.notification_new_title),
                reportString,
                pending);

        notification.sound = ringtone;
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        boolean nowSilent = audioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;

        // Use same code here as in Gmail and GTalk for vibration
        if (vibrate || (vibrateWhenSilent && nowSilent)) {
            notification.defaults |= Notification.DEFAULT_VIBRATE;
        }

        // This code is identical to that used by Gmail and GTalk for notifications
        notification.flags |= Notification.FLAG_SHOW_LIGHTS;
        notification.defaults |= Notification.DEFAULT_LIGHTS;

        NotificationManager notificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID_NEW_MESSAGES, notification);
    }
}
