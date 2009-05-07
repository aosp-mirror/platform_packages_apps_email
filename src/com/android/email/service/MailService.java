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

import com.android.email.Account;
import com.android.email.Email;
import com.android.email.MessagingController;
import com.android.email.MessagingListener;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.activity.Accounts;
import com.android.email.activity.FolderMessageList;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;
import com.android.email.mail.store.LocalStore;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

/**
 */
public class MailService extends Service {
    private static final String ACTION_CHECK_MAIL = "com.android.email.intent.action.MAIL_SERVICE_WAKEUP";
    private static final String ACTION_RESCHEDULE = "com.android.email.intent.action.MAIL_SERVICE_RESCHEDULE";
    private static final String ACTION_CANCEL = "com.android.email.intent.action.MAIL_SERVICE_CANCEL";
    
    private static final String EXTRA_CHECK_ACCOUNT = "com.android.email.intent.extra.ACCOUNT";

    private Listener mListener = new Listener();

    private int mStartId;

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
     * Entry point for asynchronous message services (e.g. push mode) to post notifications of new
     * messages.  Note:  Although this is not a blocking call, it will start the MessagingController
     * which will attempt to load the new messages.  So the Store should expect to be opened and
     * fetched from shortly after making this call.
     * 
     * @param storeUri the Uri of the store that is reporting new messages
     */
    public static void actionNotifyNewMessages(Context context, String storeUri) {
        Intent i = new Intent(ACTION_CHECK_MAIL);
        i.setClass(context, MailService.class);
        i.putExtra(EXTRA_CHECK_ACCOUNT, storeUri);
        context.startService(i);
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        this.mStartId = startId;

        MessagingController controller = MessagingController.getInstance(getApplication());
        controller.addListener(mListener);
        if (ACTION_CHECK_MAIL.equals(intent.getAction())) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "*** MailService: checking mail");
            }
            // Only check mail for accounts that have enabled automatic checking.  There is still
            // a bug here in that we check every enabled account, on every refresh - irrespective
            // of that account's refresh frequency - but this fixes the worst case of checking 
            // accounts that should not have been checked at all.
            // Also note:  Due to the organization of this service, you must gather the accounts
            // and make a single call to controller.checkMail().
            
            // TODO: Notification for single push account will fire up checks on all other
            // accounts.  This needs to be cleaned up for better efficiency.
            String specificStoreUri = intent.getStringExtra(EXTRA_CHECK_ACCOUNT);
            
            ArrayList<Account> accountsToCheck = new ArrayList<Account>();
            for (Account account : Preferences.getPreferences(this).getAccounts()) {
                int interval = account.getAutomaticCheckIntervalMinutes();
                String storeUri = account.getStoreUri();
                if (interval > 0 || (storeUri != null && storeUri.equals(specificStoreUri))) {
                    accountsToCheck.add(account);
                }
                
                // For each account, switch pushmail on or off
                enablePushMail(account, interval == Account.CHECK_INTERVAL_PUSH);
            }
            Account[] accounts = accountsToCheck.toArray(new Account[accountsToCheck.size()]);
            controller.checkMail(this, accounts, mListener);
        }
        else if (ACTION_CANCEL.equals(intent.getAction())) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "*** MailService: cancel");
            }
            cancel();
            stopSelf(startId);
        }
        else if (ACTION_RESCHEDULE.equals(intent.getAction())) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "*** MailService: reschedule");
            }
            reschedule();
            stopSelf(startId);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        MessagingController.getInstance(getApplication()).removeListener(mListener);
    }

    private void cancel() {
        AlarmManager alarmMgr = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent();
        i.setClassName("com.android.email", "com.android.email.service.MailService");
        i.setAction(ACTION_CHECK_MAIL);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        alarmMgr.cancel(pi);
    }

    private void reschedule() {
        AlarmManager alarmMgr = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent();
        i.setClassName("com.android.email", "com.android.email.service.MailService");
        i.setAction(ACTION_CHECK_MAIL);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);

        int shortestInterval = -1;
        for (Account account : Preferences.getPreferences(this).getAccounts()) {
            int interval = account.getAutomaticCheckIntervalMinutes();
            if (interval > 0 && (interval < shortestInterval || shortestInterval == -1)) {
                shortestInterval = interval;
            }
            enablePushMail(account, interval == Account.CHECK_INTERVAL_PUSH);
        }

        if (shortestInterval == -1) {
            alarmMgr.cancel(pi);
        }
        else {
            alarmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime()
                    + (shortestInterval * (60 * 1000)), pi);
        }
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    class Listener extends MessagingListener {
        HashMap<Account, Integer> accountsWithNewMail = new HashMap<Account, Integer>();

        // TODO this should be redone because account is usually null, not very interesting.
        // I think it would make more sense to pass Account[] here in case anyone uses it
        // In any case, it should be noticed that this is called once per cycle
        @Override
        public void checkMailStarted(Context context, Account account) {
            accountsWithNewMail.clear();
        }

        // Called once per checked account
        @Override
        public void checkMailFailed(Context context, Account account, String reason) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "*** MailService: checkMailFailed: " + reason);
            }
            reschedule();
            stopSelf(mStartId);
        }

        // Called once per checked account
        @Override
        public void synchronizeMailboxFinished(
                Account account,
                String folder,
                int totalMessagesInMailbox,
                int numNewMessages) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "*** MailService: synchronizeMailboxFinished: total=" + 
                        totalMessagesInMailbox + " new=" + numNewMessages);
            }
            if (account.isNotifyNewMail() && numNewMessages > 0) {
                accountsWithNewMail.put(account, numNewMessages);
            }
        }

        // TODO this should be redone because account is usually null, not very interesting.
        // I think it would make more sense to pass Account[] here in case anyone uses it
        // In any case, it should be noticed that this is called once per cycle
        @Override
        public void checkMailFinished(Context context, Account account) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "*** MailService: checkMailFinished");
            }
            NotificationManager notifMgr = (NotificationManager)context
                    .getSystemService(Context.NOTIFICATION_SERVICE);

            if (accountsWithNewMail.size() > 0) {
                Notification notif = new Notification(R.drawable.stat_notify_email_generic,
                        getString(R.string.notification_new_title), System.currentTimeMillis());
                boolean vibrate = false;
                String ringtone = null;
                if (accountsWithNewMail.size() > 1) {
                    for (Account account1 : accountsWithNewMail.keySet()) {
                        if (account1.isVibrate()) vibrate = true;
                        ringtone = account1.getRingtone();
                    }
                    Intent i = new Intent(context, Accounts.class);
                    PendingIntent pi = PendingIntent.getActivity(context, 0, i, 0);
                    notif.setLatestEventInfo(context, getString(R.string.notification_new_title),
                            getResources().
                                getQuantityString(R.plurals.notification_new_multi_account_fmt,
                                    accountsWithNewMail.size(),
                                    accountsWithNewMail.size()), pi);
                } else {
                    Account account1 = accountsWithNewMail.keySet().iterator().next();
                    int totalNewMails = accountsWithNewMail.get(account1);
                    Intent i = FolderMessageList.actionHandleAccountIntent(context, account1, Email.INBOX);
                    PendingIntent pi = PendingIntent.getActivity(context, 0, i, 0);
                    notif.setLatestEventInfo(context, getString(R.string.notification_new_title),
                            getResources().
                                getQuantityString(R.plurals.notification_new_one_account_fmt,
                                    totalNewMails, totalNewMails,
                                    account1.getDescription()), pi);
                    vibrate = account1.isVibrate();
                    ringtone = account1.getRingtone();
                }
                notif.defaults = Notification.DEFAULT_LIGHTS;
                notif.sound = TextUtils.isEmpty(ringtone) ? null : Uri.parse(ringtone);
                if (vibrate) {
                    notif.defaults |= Notification.DEFAULT_VIBRATE;
                }
                notifMgr.notify(1, notif);
            }

            reschedule();
            stopSelf(mStartId);
        }
    }

    /**
     * For any account that wants push mail, get its Store and start the pushmail service.
     * This function makes no attempt to optimize, so accounts may have push enabled (or disabled)
     * repeatedly, and should handle this appropriately.
     * 
     * @param account the account that needs push delivery enabled
     */
    private void enablePushMail(Account account, boolean enable) {
        try {
            String localUri = account.getLocalStoreUri();
            String storeUri = account.getStoreUri();
            if (localUri != null && storeUri != null) {
                LocalStore localStore = (LocalStore) Store.getInstance(
                        localUri, this.getBaseContext(), null);
                Store store = Store.getInstance(storeUri, this.getBaseContext(), 
                        localStore.getPersistentCallbacks());
                if (store != null) {
                    store.enablePushModeDelivery(enable);
                }
            }
        } catch (MessagingException me) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "Failed to enable push mail for account" + account.getName() +
                        " with exception " + me.toString());
            }
        }
    }
}
