/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.accounts.AccountManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.format.DateUtils;

import com.android.email.AttachmentInfo;
import com.android.email.EmailConnectivityManager;
import com.android.email.NotificationController;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.AttachmentColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.emailcommon.utility.Utility;
import com.android.mail.providers.UIProvider.AttachmentState;
import com.android.mail.utils.LogUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

public class AttachmentDownloadService extends Service implements Runnable {
    public static final String TAG = LogUtils.TAG;

    // Minimum wait time before retrying a download that failed due to connection error
    private static final long CONNECTION_ERROR_RETRY_MILLIS = 10 * DateUtils.SECOND_IN_MILLIS;
    // Number of retries before we start delaying between
    private static final long CONNECTION_ERROR_DELAY_THRESHOLD = 5;
    // Maximum time to retry for connection errors.
    private static final long CONNECTION_ERROR_MAX_RETRIES = 10;

    // Our idle time, waiting for notifications; this is something of a failsafe
    private static final int PROCESS_QUEUE_WAIT_TIME = 30 * ((int)DateUtils.MINUTE_IN_MILLIS);
    // How often our watchdog checks for callback timeouts
    private static final int WATCHDOG_CHECK_INTERVAL = 20 * ((int)DateUtils.SECOND_IN_MILLIS);
    // How long we'll wait for a callback before canceling a download and retrying
    private static final int CALLBACK_TIMEOUT = 30 * ((int)DateUtils.SECOND_IN_MILLIS);
    // Try to download an attachment in the background this many times before giving up
    private static final int MAX_DOWNLOAD_RETRIES = 5;
    private static final int PRIORITY_NONE = -1;
    @SuppressWarnings("unused")
    // Low priority will be used for opportunistic downloads
    private static final int PRIORITY_BACKGROUND = 0;
    // Normal priority is for forwarded downloads in outgoing mail
    private static final int PRIORITY_SEND_MAIL = 1;
    // High priority is for user requests
    private static final int PRIORITY_FOREGROUND = 2;

    // Minimum free storage in order to perform prefetch (25% of total memory)
    private static final float PREFETCH_MINIMUM_STORAGE_AVAILABLE = 0.25F;
    // Maximum prefetch storage (also 25% of total memory)
    private static final float PREFETCH_MAXIMUM_ATTACHMENT_STORAGE = 0.25F;

    // We can try various values here; I think 2 is completely reasonable as a first pass
    private static final int MAX_SIMULTANEOUS_DOWNLOADS = 2;
    // Limit on the number of simultaneous downloads per account
    // Note that a limit of 1 is currently enforced by both Services (MailService and Controller)
    private static final int MAX_SIMULTANEOUS_DOWNLOADS_PER_ACCOUNT = 1;
    // Limit on the number of attachments we'll check for background download
    private static final int MAX_ATTACHMENTS_TO_CHECK = 25;

    private static final String EXTRA_ATTACHMENT =
        "com.android.email.AttachmentDownloadService.attachment";

    // sRunningService is only set in the UI thread; it's visibility elsewhere is guaranteed
    // by the use of "volatile"
    /*package*/ static volatile AttachmentDownloadService sRunningService = null;

    /*package*/ Context mContext;
    /*package*/ EmailConnectivityManager mConnectivityManager;

    /*package*/ final DownloadSet mDownloadSet = new DownloadSet(new DownloadComparator());

    private final HashMap<Long, Intent> mAccountServiceMap = new HashMap<Long, Intent>();
    // A map of attachment storage used per account
    // NOTE: This map is not kept current in terms of deletions (i.e. it stores the last calculated
    // amount plus the size of any new attachments laoded).  If and when we reach the per-account
    // limit, we recalculate the actual usage
    /*package*/ final HashMap<Long, Long> mAttachmentStorageMap = new HashMap<Long, Long>();
    // A map of attachment ids to the number of failed attempts to download the attachment
    // NOTE: We do not want to persist this. This allows us to retry background downloading
    // if any transient network errors are fixed & and the app is restarted
    /* package */ final HashMap<Long, Integer> mAttachmentFailureMap = new HashMap<Long, Integer>();
    private final ServiceCallback mServiceCallback = new ServiceCallback();

    private final Object mLock = new Object();
    private volatile boolean mStop = false;

    /*package*/ AccountManagerStub mAccountManagerStub;

    /**
     * We only use the getAccounts() call from AccountManager, so this class wraps that call and
     * allows us to build a mock account manager stub in the unit tests
     */
    /*package*/ static class AccountManagerStub {
        private int mNumberOfAccounts;
        private final AccountManager mAccountManager;

        AccountManagerStub(Context context) {
            if (context != null) {
                mAccountManager = AccountManager.get(context);
            } else {
                mAccountManager = null;
            }
        }

        /*package*/ int getNumberOfAccounts() {
            if (mAccountManager != null) {
                return mAccountManager.getAccounts().length;
            } else {
                return mNumberOfAccounts;
            }
        }

        /*package*/ void setNumberOfAccounts(int numberOfAccounts) {
            mNumberOfAccounts = numberOfAccounts;
        }
    }

    /**
     * Watchdog alarm receiver; responsible for making sure that downloads in progress are not
     * stalled, as determined by the timing of the most recent service callback
     */
    public static class Watchdog extends BroadcastReceiver {
        @Override
        public void onReceive(final Context context, Intent intent) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    watchdogAlarm();
                }
            }, "AttachmentDownloadService Watchdog").start();
        }
    }

    public static class DownloadRequest {
        final int priority;
        final long time;
        final long attachmentId;
        final long messageId;
        final long accountId;
        boolean inProgress = false;
        int lastStatusCode;
        int lastProgress;
        long lastCallbackTime;
        long startTime;
        long retryCount;
        long retryStartTime;

        private DownloadRequest(Context context, Attachment attachment) {
            attachmentId = attachment.mId;
            Message msg = Message.restoreMessageWithId(context, attachment.mMessageKey);
            if (msg != null) {
                accountId = msg.mAccountKey;
                messageId = msg.mId;
            } else {
                accountId = messageId = -1;
            }
            priority = getPriority(attachment);
            time = SystemClock.elapsedRealtime();
        }

        private DownloadRequest(DownloadRequest orig, long newTime) {
            priority = orig.priority;
            attachmentId = orig.attachmentId;
            messageId = orig.messageId;
            accountId = orig.accountId;
            time = newTime;
            inProgress = orig.inProgress;
            lastStatusCode = orig.lastStatusCode;
            lastProgress = orig.lastProgress;
            lastCallbackTime = orig.lastCallbackTime;
            startTime = orig.startTime;
            retryCount = orig.retryCount;
            retryStartTime = orig.retryStartTime;
        }


        @Override
        public int hashCode() {
            return (int)attachmentId;
        }

        /**
         * Two download requests are equals if their attachment id's are equals
         */
        @Override
        public boolean equals(Object object) {
            if (!(object instanceof DownloadRequest)) return false;
            DownloadRequest req = (DownloadRequest)object;
            return req.attachmentId == attachmentId;
        }
    }

    /**
     * Comparator class for the download set; we first compare by priority.  Requests with equal
     * priority are compared by the time the request was created (older requests come first)
     */
    /*protected*/ static class DownloadComparator implements Comparator<DownloadRequest> {
        @Override
        public int compare(DownloadRequest req1, DownloadRequest req2) {
            int res;
            if (req1.priority != req2.priority) {
                res = (req1.priority < req2.priority) ? -1 : 1;
            } else {
                if (req1.time == req2.time) {
                    res = 0;
                } else {
                    res = (req1.time > req2.time) ? -1 : 1;
                }
            }
            return res;
        }
    }

    /**
     * The DownloadSet is a TreeSet sorted by priority class (e.g. low, high, etc.) and the
     * time of the request.  Higher priority requests
     * are always processed first; among equals, the oldest request is processed first.  The
     * priority key represents this ordering.  Note: All methods that change the attachment map are
     * synchronized on the map itself
     */
    /*package*/ class DownloadSet extends TreeSet<DownloadRequest> {
        private static final long serialVersionUID = 1L;
        private PendingIntent mWatchdogPendingIntent;

        /*package*/ DownloadSet(Comparator<? super DownloadRequest> comparator) {
            super(comparator);
        }

        /**
         * Maps attachment id to DownloadRequest
         */
        /*package*/ final ConcurrentHashMap<Long, DownloadRequest> mDownloadsInProgress =
            new ConcurrentHashMap<Long, DownloadRequest>();

        /**
         * onChange is called by the AttachmentReceiver upon receipt of a valid notification from
         * EmailProvider that an attachment has been inserted or modified.  It's not strictly
         * necessary that we detect a deleted attachment, as the code always checks for the
         * existence of an attachment before acting on it.
         */
        public synchronized void onChange(Context context, Attachment att) {
            DownloadRequest req = findDownloadRequest(att.mId);
            long priority = getPriority(att);
            if (priority == PRIORITY_NONE) {
                if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                    LogUtils.d(TAG, "== Attachment changed: " + att.mId);
                }
                // In this case, there is no download priority for this attachment
                if (req != null) {
                    // If it exists in the map, remove it
                    // NOTE: We don't yet support deleting downloads in progress
                    if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                        LogUtils.d(TAG, "== Attachment " + att.mId + " was in queue, removing");
                    }
                    remove(req);
                }
            } else {
                // Ignore changes that occur during download
                if (mDownloadsInProgress.containsKey(att.mId)) return;
                // If this is new, add the request to the queue
                if (req == null) {
                    req = new DownloadRequest(context, att);
                    add(req);
                }
                // If the request already existed, we'll update the priority (so that the time is
                // up-to-date); otherwise, we create a new request
                if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                    LogUtils.d(TAG, "== Download queued for attachment " + att.mId + ", class " +
                            req.priority + ", priority time " + req.time);
                }
            }
            // Process the queue if we're in a wait
            kick();
        }

        /**
         * Find a queued DownloadRequest, given the attachment's id
         * @param id the id of the attachment
         * @return the DownloadRequest for that attachment (or null, if none)
         */
        /*package*/ synchronized DownloadRequest findDownloadRequest(long id) {
            Iterator<DownloadRequest> iterator = iterator();
            while(iterator.hasNext()) {
                DownloadRequest req = iterator.next();
                if (req.attachmentId == id) {
                    return req;
                }
            }
            return null;
        }

        @Override
        public synchronized boolean isEmpty() {
            return super.isEmpty() && mDownloadsInProgress.isEmpty();
        }

        /**
         * Run through the AttachmentMap and find DownloadRequests that can be executed, enforcing
         * the limit on maximum downloads
         */
        /*package*/ synchronized void processQueue() {
            if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                LogUtils.d(TAG, "== Checking attachment queue, " + mDownloadSet.size()
                        + " entries");
            }

            Iterator<DownloadRequest> iterator = mDownloadSet.descendingIterator();
            // First, start up any required downloads, in priority order
            while (iterator.hasNext() &&
                    (mDownloadsInProgress.size() < MAX_SIMULTANEOUS_DOWNLOADS)) {
                DownloadRequest req = iterator.next();
                 // Enforce per-account limit here
                if (downloadsForAccount(req.accountId) >= MAX_SIMULTANEOUS_DOWNLOADS_PER_ACCOUNT) {
                    if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                        LogUtils.d(TAG, "== Skip #" + req.attachmentId + "; maxed for acct #" +
                                req.accountId);
                    }
                    continue;
                } else if (Attachment.restoreAttachmentWithId(mContext, req.attachmentId) == null) {
                    continue;
                }
                if (!req.inProgress) {
                    final long currentTime = SystemClock.elapsedRealtime();
                    if (req.retryCount > 0 && req.retryStartTime > currentTime) {
                        LogUtils.d(TAG, "== waiting to retry attachment %d", req.attachmentId);
                        setWatchdogAlarm(CONNECTION_ERROR_RETRY_MILLIS);
                        continue;
                    }
                    mDownloadSet.tryStartDownload(req);
                }
            }

            // Don't prefetch if background downloading is disallowed
            EmailConnectivityManager ecm = mConnectivityManager;
            if (ecm == null) return;
            if (!ecm.isAutoSyncAllowed()) return;
            // Don't prefetch unless we're on a WiFi network
            if (ecm.getActiveNetworkType() != ConnectivityManager.TYPE_WIFI) {
                return;
            }
            // Then, try opportunistic download of appropriate attachments
            int backgroundDownloads = MAX_SIMULTANEOUS_DOWNLOADS - mDownloadsInProgress.size();
            // Always leave one slot for user requested download
            if (backgroundDownloads > (MAX_SIMULTANEOUS_DOWNLOADS - 1)) {
                // We'll load up the newest 25 attachments that aren't loaded or queued
                Uri lookupUri = EmailContent.uriWithLimit(Attachment.CONTENT_URI,
                        MAX_ATTACHMENTS_TO_CHECK);
                Cursor c = mContext.getContentResolver().query(lookupUri,
                        Attachment.CONTENT_PROJECTION,
                        EmailContent.Attachment.PRECACHE_INBOX_SELECTION,
                        null, Attachment.RECORD_ID + " DESC");
                File cacheDir = mContext.getCacheDir();
                try {
                    while (c.moveToNext()) {
                        Attachment att = new Attachment();
                        att.restore(c);
                        Account account = Account.restoreAccountWithId(mContext, att.mAccountKey);
                        if (account == null) {
                            // Clean up this orphaned attachment; there's no point in keeping it
                            // around; then try to find another one
                            EmailContent.delete(mContext, Attachment.CONTENT_URI, att.mId);
                        } else {
                            // Check that the attachment meets system requirements for download
                            AttachmentInfo info = new AttachmentInfo(mContext, att);
                            if (info.isEligibleForDownload()) {
                                // Either the account must be able to prefetch or this must be
                                // an inline attachment
                                if (att.mContentId != null ||
                                        (canPrefetchForAccount(account, cacheDir))) {
                                    Integer tryCount;
                                    tryCount = mAttachmentFailureMap.get(att.mId);
                                    if (tryCount != null && tryCount > MAX_DOWNLOAD_RETRIES) {
                                        // move onto the next attachment
                                        continue;
                                    }
                                    // Start this download and we're done
                                    DownloadRequest req = new DownloadRequest(mContext, att);
                                    mDownloadSet.tryStartDownload(req);
                                    break;
                                }
                            }
                        }
                    }
                } finally {
                    c.close();
                }
            }
        }

        /**
         * Count the number of running downloads in progress for this account
         * @param accountId the id of the account
         * @return the count of running downloads
         */
        /*package*/ synchronized int downloadsForAccount(long accountId) {
            int count = 0;
            for (DownloadRequest req: mDownloadsInProgress.values()) {
                if (req.accountId == accountId) {
                    count++;
                }
            }
            return count;
        }

        /**
         * Watchdog for downloads; we use this in case we are hanging on a download, which might
         * have failed silently (the connection dropped, for example)
         */
        private void onWatchdogAlarm() {
            // If our service instance is gone, just leave
            if (mStop) {
                return;
            }
            long now = System.currentTimeMillis();
            for (DownloadRequest req: mDownloadsInProgress.values()) {
                // Check how long it's been since receiving a callback
                long timeSinceCallback = now - req.lastCallbackTime;
                if (timeSinceCallback > CALLBACK_TIMEOUT) {
                    if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                        LogUtils.d(TAG, "== Download of " + req.attachmentId + " timed out");
                    }
                    cancelDownload(req);
                }
            }
            // Check whether we can start new downloads...
            if (mConnectivityManager != null && mConnectivityManager.hasConnectivity()) {
                processQueue();
            }
            // If there are downloads in progress, reset alarm
            if (!mDownloadsInProgress.isEmpty()) {
                if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                    LogUtils.d(TAG, "Reschedule watchdog...");
                }
                setWatchdogAlarm();
            }
        }

        /**
         * Attempt to execute the DownloadRequest, enforcing the maximum downloads per account
         * parameter
         * @param req the DownloadRequest
         * @return whether or not the download was started
         */
        /*package*/ synchronized boolean tryStartDownload(DownloadRequest req) {
            EmailServiceProxy service = EmailServiceUtils.getServiceForAccount(
                    AttachmentDownloadService.this, req.accountId);

            // Do not download the same attachment multiple times
            boolean alreadyInProgress = mDownloadsInProgress.get(req.attachmentId) != null;
            if (alreadyInProgress) return false;

            try {
                if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                    LogUtils.d(TAG, ">> Starting download for attachment #" + req.attachmentId);
                }
                startDownload(service, req);
            } catch (RemoteException e) {
                // TODO: Consider whether we need to do more in this case...
                // For now, fix up our data to reflect the failure
                cancelDownload(req);
            }
            return true;
        }

        private synchronized DownloadRequest getDownloadInProgress(long attachmentId) {
            return mDownloadsInProgress.get(attachmentId);
        }

        private void setWatchdogAlarm(final long delay) {
            // Lazily initialize the pending intent
            if (mWatchdogPendingIntent == null) {
                Intent intent = new Intent(mContext, Watchdog.class);
                mWatchdogPendingIntent =
                    PendingIntent.getBroadcast(mContext, 0, intent, 0);
            }
            // Set the alarm
            AlarmManager am = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay,
                    mWatchdogPendingIntent);
        }

        private void setWatchdogAlarm() {
            setWatchdogAlarm(WATCHDOG_CHECK_INTERVAL);
        }

        /**
         * Do the work of starting an attachment download using the EmailService interface, and
         * set our watchdog alarm
         *
         * @param service the service handling the download
         * @param req the DownloadRequest
         * @throws RemoteException
         */
        private void startDownload(EmailServiceProxy service, DownloadRequest req)
                throws RemoteException {
            req.startTime = System.currentTimeMillis();
            req.inProgress = true;
            mDownloadsInProgress.put(req.attachmentId, req);
            service.loadAttachment(mServiceCallback, req.attachmentId,
                    req.priority != PRIORITY_FOREGROUND);
            setWatchdogAlarm();
        }

        private void cancelDownload(DownloadRequest req) {
            LogUtils.d(TAG, "cancelDownload #%d", req.attachmentId);
            req.inProgress = false;
            mDownloadsInProgress.remove(req.attachmentId);
            // Remove the download from our queue, and then decide whether or not to add it back.
            remove(req);
            req.retryCount++;
            if (req.retryCount > CONNECTION_ERROR_MAX_RETRIES) {
                LogUtils.d(TAG, "too many failures, giving up");
            } else {
                LogUtils.d(TAG, "moving to end of queue, will retry");
                // The time field of DownloadRequest is final, because it's unsafe to change it
                // as long as the DownloadRequest is in the DownloadSet. It's needed for the
                // comparator, so changing time would make the request unfindable.
                // Instead, we'll create a new DownloadRequest with an updated time.
                // This will sort at the end of the set.
                req = new DownloadRequest(req, SystemClock.elapsedRealtime());
                add(req);
            }
        }

        /**
         * Called when a download is finished; we get notified of this via our EmailServiceCallback
         * @param attachmentId the id of the attachment whose download is finished
         * @param statusCode the EmailServiceStatus code returned by the Service
         */
        /*package*/ synchronized void endDownload(long attachmentId, int statusCode) {
            // Say we're no longer downloading this
            mDownloadsInProgress.remove(attachmentId);

            // TODO: This code is conservative and treats connection issues as failures.
            // Since we have no mechanism to throttle reconnection attempts, it makes
            // sense to be cautious here. Once logic is in place to prevent connecting
            // in a tight loop, we can exclude counting connection issues as "failures".

            // Update the attachment failure list if needed
            Integer downloadCount;
            downloadCount = mAttachmentFailureMap.remove(attachmentId);
            if (statusCode != EmailServiceStatus.SUCCESS) {
                if (downloadCount == null) {
                    downloadCount = 0;
                }
                downloadCount += 1;
                mAttachmentFailureMap.put(attachmentId, downloadCount);
            }

            DownloadRequest req = mDownloadSet.findDownloadRequest(attachmentId);
            if (statusCode == EmailServiceStatus.CONNECTION_ERROR) {
                // If this needs to be retried, just process the queue again
                if (req != null) {
                    req.retryCount++;
                    if (req.retryCount > CONNECTION_ERROR_MAX_RETRIES) {
                        LogUtils.d(TAG, "Connection Error #%d, giving up", attachmentId);
                        remove(req);
                    } else if (req.retryCount > CONNECTION_ERROR_DELAY_THRESHOLD) {
                        // TODO: I'm not sure this is a great retry/backoff policy, but we're
                        // afraid of changing behavior too much in case something relies upon it.
                        // So now, for the first five errors, we'll retry immediately. For the next
                        // five tries, we'll add a ten second delay between each. After that, we'll
                        // give up.
                        LogUtils.d(TAG, "ConnectionError #%d, retried %d times, adding delay",
                                attachmentId, req.retryCount);
                        req.inProgress = false;
                        req.retryStartTime = SystemClock.elapsedRealtime() +
                                CONNECTION_ERROR_RETRY_MILLIS;
                        setWatchdogAlarm(CONNECTION_ERROR_RETRY_MILLIS);
                    } else {
                        LogUtils.d(TAG, "ConnectionError #%d, retried %d times, adding delay",
                                attachmentId, req.retryCount);
                        req.inProgress = false;
                        req.retryStartTime = 0;
                        kick();
                    }
                }
                return;
            }

            // If the request is still in the queue, remove it
            if (req != null) {
                remove(req);
            }
            if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                long secs = 0;
                if (req != null) {
                    secs = (System.currentTimeMillis() - req.time) / 1000;
                }
                String status = (statusCode == EmailServiceStatus.SUCCESS) ? "Success" :
                    "Error " + statusCode;
                LogUtils.d(TAG, "<< Download finished for attachment #" + attachmentId + "; " + secs
                        + " seconds from request, status: " + status);
            }

            Attachment attachment = Attachment.restoreAttachmentWithId(mContext, attachmentId);
            if (attachment != null) {
                long accountId = attachment.mAccountKey;
                // Update our attachment storage for this account
                Long currentStorage = mAttachmentStorageMap.get(accountId);
                if (currentStorage == null) {
                    currentStorage = 0L;
                }
                mAttachmentStorageMap.put(accountId, currentStorage + attachment.mSize);
                boolean deleted = false;
                if ((attachment.mFlags & Attachment.FLAG_DOWNLOAD_FORWARD) != 0) {
                    if (statusCode == EmailServiceStatus.ATTACHMENT_NOT_FOUND) {
                        // If this is a forwarding download, and the attachment doesn't exist (or
                        // can't be downloaded) delete it from the outgoing message, lest that
                        // message never get sent
                        EmailContent.delete(mContext, Attachment.CONTENT_URI, attachment.mId);
                        // TODO: Talk to UX about whether this is even worth doing
                        NotificationController nc = NotificationController.getInstance(mContext);
                        nc.showDownloadForwardFailedNotification(attachment);
                        deleted = true;
                    }
                    // If we're an attachment on forwarded mail, and if we're not still blocked,
                    // try to send pending mail now (as mediated by MailService)
                    if ((req != null) &&
                            !Utility.hasUnloadedAttachments(mContext, attachment.mMessageKey)) {
                        if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                            LogUtils.d(TAG, "== Downloads finished for outgoing msg #"
                                    + req.messageId);
                        }
                        EmailServiceProxy service = EmailServiceUtils.getServiceForAccount(
                                mContext, accountId);
                        try {
                            service.sendMail(accountId);
                        } catch (RemoteException e) {
                            // We tried
                        }
                    }
                }
                if (statusCode == EmailServiceStatus.MESSAGE_NOT_FOUND) {
                    Message msg = Message.restoreMessageWithId(mContext, attachment.mMessageKey);
                    if (msg == null) {
                        // If there's no associated message, delete the attachment
                        EmailContent.delete(mContext, Attachment.CONTENT_URI, attachment.mId);
                    } else {
                        // If there really is a message, retry
                        // TODO: How will this get retried? It's still marked as inProgress?
                        kick();
                        return;
                    }
                } else if (!deleted) {
                    // Clear the download flags, since we're done for now.  Note that this happens
                    // only for non-recoverable errors.  When these occur for forwarded mail, we can
                    // ignore it and continue; otherwise, it was either 1) a user request, in which
                    // case the user can retry manually or 2) an opportunistic download, in which
                    // case the download wasn't critical
                    ContentValues cv = new ContentValues();
                    int flags =
                        Attachment.FLAG_DOWNLOAD_FORWARD | Attachment.FLAG_DOWNLOAD_USER_REQUEST;
                    cv.put(Attachment.FLAGS, attachment.mFlags &= ~flags);
                    cv.put(Attachment.UI_STATE, AttachmentState.SAVED);
                    attachment.update(mContext, cv);
                }
            }
            // Process the queue
            kick();
        }
    }

    /**
     * Calculate the download priority of an Attachment.  A priority of zero means that the
     * attachment is not marked for download.
     * @param att the Attachment
     * @return the priority key of the Attachment
     */
    private static int getPriority(Attachment att) {
        int priorityClass = PRIORITY_NONE;
        int flags = att.mFlags;
        if ((flags & Attachment.FLAG_DOWNLOAD_FORWARD) != 0) {
            priorityClass = PRIORITY_SEND_MAIL;
        } else if ((flags & Attachment.FLAG_DOWNLOAD_USER_REQUEST) != 0) {
            priorityClass = PRIORITY_FOREGROUND;
        }
        return priorityClass;
    }

    private void kick() {
        synchronized(mLock) {
            mLock.notify();
        }
    }

    /**
     * We use an EmailServiceCallback to keep track of the progress of downloads.  These callbacks
     * come from either Controller (IMAP) or ExchangeService (EAS).  Note that we only implement the
     * single callback that's defined by the EmailServiceCallback interface.
     */
    private class ServiceCallback extends IEmailServiceCallback.Stub {
        @Override
        public void loadAttachmentStatus(long messageId, long attachmentId, int statusCode,
                int progress) {
            // Record status and progress
            DownloadRequest req = mDownloadSet.getDownloadInProgress(attachmentId);
            if (req != null) {
                if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                    String code;
                    switch(statusCode) {
                        case EmailServiceStatus.SUCCESS: code = "Success"; break;
                        case EmailServiceStatus.IN_PROGRESS: code = "In progress"; break;
                        default: code = Integer.toString(statusCode); break;
                    }
                    if (statusCode != EmailServiceStatus.IN_PROGRESS) {
                        LogUtils.d(TAG, ">> Attachment status " + attachmentId + ": " + code);
                    } else if (progress >= (req.lastProgress + 10)) {
                        LogUtils.d(TAG, ">> Attachment progress %d: %d%%", attachmentId, progress);
                    }
                }
                req.lastStatusCode = statusCode;
                req.lastProgress = progress;
                req.lastCallbackTime = System.currentTimeMillis();
                Attachment attachment = Attachment.restoreAttachmentWithId(mContext, attachmentId);
                 if (attachment != null  && statusCode == EmailServiceStatus.IN_PROGRESS) {
                    ContentValues values = new ContentValues();
                    values.put(AttachmentColumns.UI_DOWNLOADED_SIZE,
                            attachment.mSize * progress / 100);
                    // Update UIProvider with updated download size
                    // Individual services will set contentUri and state when finished
                    attachment.update(mContext, values);
                }
            }
            switch (statusCode) {
                case EmailServiceStatus.IN_PROGRESS:
                    break;
                default:
                    mDownloadSet.endDownload(attachmentId, statusCode);
                    break;
            }
        }
    }

    /*package*/ void addServiceIntentForTest(long accountId, Intent intent) {
        mAccountServiceMap.put(accountId, intent);
    }

    /*package*/ void onChange(Attachment att) {
        mDownloadSet.onChange(this, att);
    }

    /*package*/ boolean isQueued(long attachmentId) {
        return mDownloadSet.findDownloadRequest(attachmentId) != null;
    }

    /*package*/ int getSize() {
        return mDownloadSet.size();
    }

    /*package*/ boolean dequeue(long attachmentId) {
        DownloadRequest req = mDownloadSet.findDownloadRequest(attachmentId);
        if (req != null) {
            if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                LogUtils.d(TAG, "Dequeued attachmentId:  " + attachmentId);
            }
            mDownloadSet.remove(req);
            return true;
        }
        return false;
    }

    /**
     * Ask the service for the number of items in the download queue
     * @return the number of items queued for download
     */
    public static int getQueueSize() {
        AttachmentDownloadService service = sRunningService;
        if (service != null) {
            return service.getSize();
        }
        return 0;
    }

    /**
     * Ask the service whether a particular attachment is queued for download
     * @param attachmentId the id of the Attachment (as stored by EmailProvider)
     * @return whether or not the attachment is queued for download
     */
    public static boolean isAttachmentQueued(long attachmentId) {
        AttachmentDownloadService service = sRunningService;
        if (service != null) {
            return service.isQueued(attachmentId);
        }
        return false;
    }

    /**
     * Ask the service to remove an attachment from the download queue
     * @param attachmentId the id of the Attachment (as stored by EmailProvider)
     * @return whether or not the attachment was removed from the queue
     */
    public static boolean cancelQueuedAttachment(long attachmentId) {
        AttachmentDownloadService service = sRunningService;
        if (service != null) {
            return service.dequeue(attachmentId);
        }
        return false;
    }

    public static void watchdogAlarm() {
        AttachmentDownloadService service = sRunningService;
        if (service != null) {
            service.mDownloadSet.onWatchdogAlarm();
        }
    }

    /**
     * Called directly by EmailProvider whenever an attachment is inserted or changed
     * @param context the caller's context
     * @param id the attachment's id
     * @param flags the new flags for the attachment
     */
    public static void attachmentChanged(final Context context, final long id, final int flags) {
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                Attachment attachment = Attachment.restoreAttachmentWithId(context, id);
                if (attachment != null) {
                    // Store the flags we got from EmailProvider; given that all of this
                    // activity is asynchronous, we need to use the newest data from
                    // EmailProvider
                    attachment.mFlags = flags;
                    Intent intent = new Intent(context, AttachmentDownloadService.class);
                    intent.putExtra(EXTRA_ATTACHMENT, attachment);
                    context.startService(intent);
                }
            }});
    }

    /**
     * Determine whether an attachment can be prefetched for the given account
     * @return true if download is allowed, false otherwise
     */
    public boolean canPrefetchForAccount(Account account, File dir) {
        // Check account, just in case
        if (account == null) return false;
        // First, check preference and quickly return if prefetch isn't allowed
        if ((account.mFlags & Account.FLAGS_BACKGROUND_ATTACHMENTS) == 0) return false;

        long totalStorage = dir.getTotalSpace();
        long usableStorage = dir.getUsableSpace();
        long minAvailable = (long)(totalStorage * PREFETCH_MINIMUM_STORAGE_AVAILABLE);

        // If there's not enough overall storage available, stop now
        if (usableStorage < minAvailable) {
            return false;
        }

        int numberOfAccounts = mAccountManagerStub.getNumberOfAccounts();
        long perAccountMaxStorage =
            (long)(totalStorage * PREFETCH_MAXIMUM_ATTACHMENT_STORAGE / numberOfAccounts);

        // Retrieve our idea of currently used attachment storage; since we don't track deletions,
        // this number is the "worst case".  If the number is greater than what's allowed per
        // account, we walk the directory to determine the actual number
        Long accountStorage = mAttachmentStorageMap.get(account.mId);
        if (accountStorage == null || (accountStorage > perAccountMaxStorage)) {
            // Calculate the exact figure for attachment storage for this account
            accountStorage = 0L;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    accountStorage += file.length();
                }
            }
            // Cache the value
            mAttachmentStorageMap.put(account.mId, accountStorage);
        }

        // Return true if we're using less than the maximum per account
        if (accountStorage < perAccountMaxStorage) {
            return true;
        } else {
            if (LogUtils.isLoggable(TAG, LogUtils.DEBUG)) {
                LogUtils.d(TAG, ">> Prefetch not allowed for account " + account.mId + "; used " +
                        accountStorage + ", limit " + perAccountMaxStorage);
            }
            return false;
        }
    }

    @Override
    public void run() {
        // These fields are only used within the service thread
        mContext = this;
        mConnectivityManager = new EmailConnectivityManager(this, TAG);
        mAccountManagerStub = new AccountManagerStub(this);

        // Run through all attachments in the database that require download and add them to
        // the queue
        int mask = Attachment.FLAG_DOWNLOAD_FORWARD | Attachment.FLAG_DOWNLOAD_USER_REQUEST;
        Cursor c = getContentResolver().query(Attachment.CONTENT_URI,
                EmailContent.ID_PROJECTION, "(" + Attachment.FLAGS + " & ?) != 0",
                new String[] {Integer.toString(mask)}, null);
        try {
            LogUtils.d(TAG, "Count: " + c.getCount());
            while (c.moveToNext()) {
                Attachment attachment = Attachment.restoreAttachmentWithId(
                        this, c.getLong(EmailContent.ID_PROJECTION_COLUMN));
                if (attachment != null) {
                    mDownloadSet.onChange(this, attachment);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            c.close();
        }

        // Loop until stopped, with a 30 minute wait loop
        while (!mStop) {
            // Here's where we run our attachment loading logic...
            // Make a local copy of the variable so we don't null-crash on service shutdown
            final EmailConnectivityManager ecm = mConnectivityManager;
            if (ecm != null) {
                ecm.waitForConnectivity();
            }
            if (mStop) {
                // We might be bailing out here due to the service shutting down
                break;
            }
            mDownloadSet.processQueue();
            if (mDownloadSet.isEmpty()) {
                LogUtils.d(TAG, "*** All done; shutting down service");
                stopSelf();
                break;
            }
            synchronized(mLock) {
                try {
                    mLock.wait(PROCESS_QUEUE_WAIT_TIME);
                } catch (InterruptedException e) {
                    // That's ok; we'll just keep looping
                }
            }
        }

        // Unregister now that we're done
        // Make a local copy of the variable so we don't null-crash on service shutdown
        final EmailConnectivityManager ecm = mConnectivityManager;
        if (ecm != null) {
            ecm.unregister();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (sRunningService == null) {
            sRunningService = this;
        }
        if (intent != null && intent.hasExtra(EXTRA_ATTACHMENT)) {
            Attachment att = (Attachment)intent.getParcelableExtra(EXTRA_ATTACHMENT);
            onChange(att);
        }
        return Service.START_STICKY;
    }

    @Override
    public void onCreate() {
        // Start up our service thread
        new Thread(this, "AttachmentDownloadService").start();
    }
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        // Mark this instance of the service as stopped
        mStop = true;
        if (sRunningService != null) {
            kick();
            sRunningService = null;
        }
        if (mConnectivityManager != null) {
            mConnectivityManager.unregister();
            mConnectivityManager.stopWait();
            mConnectivityManager = null;
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("AttachmentDownloadService");
        long time = System.currentTimeMillis();
        synchronized(mDownloadSet) {
            pw.println("  Queue, " + mDownloadSet.size() + " entries");
            Iterator<DownloadRequest> iterator = mDownloadSet.descendingIterator();
            // First, start up any required downloads, in priority order
            while (iterator.hasNext()) {
                DownloadRequest req = iterator.next();
                pw.println("    Account: " + req.accountId + ", Attachment: " + req.attachmentId);
                pw.println("      Priority: " + req.priority + ", Time: " + req.time +
                        (req.inProgress ? " [In progress]" : ""));
                Attachment att = Attachment.restoreAttachmentWithId(this, req.attachmentId);
                if (att == null) {
                    pw.println("      Attachment not in database?");
                } else if (att.mFileName != null) {
                    String fileName = att.mFileName;
                    String suffix = "[none]";
                    int lastDot = fileName.lastIndexOf('.');
                    if (lastDot >= 0) {
                        suffix = fileName.substring(lastDot);
                    }
                    pw.print("      Suffix: " + suffix);
                    if (att.getContentUri() != null) {
                        pw.print(" ContentUri: " + att.getContentUri());
                    }
                    pw.print(" Mime: ");
                    if (att.mMimeType != null) {
                        pw.print(att.mMimeType);
                    } else {
                        pw.print(AttachmentUtilities.inferMimeType(fileName, null));
                        pw.print(" [inferred]");
                    }
                    pw.println(" Size: " + att.mSize);
                }
                if (req.inProgress) {
                    pw.println("      Status: " + req.lastStatusCode + ", Progress: " +
                            req.lastProgress);
                    pw.println("      Started: " + req.startTime + ", Callback: " +
                            req.lastCallbackTime);
                    pw.println("      Elapsed: " + ((time - req.startTime) / 1000L) + "s");
                    if (req.lastCallbackTime > 0) {
                        pw.println("      CB: " + ((time - req.lastCallbackTime) / 1000L) + "s");
                    }
                }
            }
        }
    }
}
