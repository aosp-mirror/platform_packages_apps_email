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

import com.android.email.Controller.ControllerService;
import com.android.email.Email;
import com.android.email.ExchangeUtils.NullEmailService;
import com.android.email.NotificationController;
import com.android.email.Utility;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.AttachmentColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.exchange.ExchangeService;

import android.accounts.AccountManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.format.DateUtils;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

public class AttachmentDownloadService extends Service implements Runnable {
    public static final String TAG = "AttachmentService";

    // Our idle time, waiting for notifications; this is something of a failsafe
    private static final int PROCESS_QUEUE_WAIT_TIME = 30 * ((int)DateUtils.MINUTE_IN_MILLIS);
    // How often our watchdog checks for callback timeouts
    private static final int WATCHDOG_CHECK_INTERVAL = 15 * ((int)DateUtils.SECOND_IN_MILLIS);
    // How long we'll wait for a callback before canceling a download and retrying
    private static final int CALLBACK_TIMEOUT = 30 * ((int)DateUtils.SECOND_IN_MILLIS);

    private static final int PRIORITY_NONE = -1;
    @SuppressWarnings("unused")
    // Low priority will be used for opportunistic downloads
    private static final int PRIORITY_LOW = 0;
    // Normal priority is for forwarded downloads in outgoing mail
    private static final int PRIORITY_NORMAL = 1;
    // High priority is for user requests
    private static final int PRIORITY_HIGH = 2;

    // Minimum free storage in order to perform prefetch (25% of total memory)
    private static final float PREFETCH_MINIMUM_STORAGE_AVAILABLE = 0.25F;
    // Maximum prefetch storage (also 25% of total memory)
    private static final float PREFETCH_MAXIMUM_ATTACHMENT_STORAGE = 0.25F;

    // We can try various values here; I think 2 is completely reasonable as a first pass
    private static final int MAX_SIMULTANEOUS_DOWNLOADS = 2;
    // Limit on the number of simultaneous downloads per account
    // Note that a limit of 1 is currently enforced by both Services (MailService and Controller)
    private static final int MAX_SIMULTANEOUS_DOWNLOADS_PER_ACCOUNT = 1;

    private static final Uri SINGLE_ATTACHMENT_URI =
        EmailContent.uriWithLimit(Attachment.CONTENT_URI, 1);

    /*package*/ static AttachmentDownloadService sRunningService = null;

    /*package*/ Context mContext;
    /*package*/ final DownloadSet mDownloadSet = new DownloadSet(new DownloadComparator());

    private final HashMap<Long, Class<? extends Service>> mAccountServiceMap =
        new HashMap<Long, Class<? extends Service>>();
    // A map of attachment storage used per account
    // NOTE: This map is not kept current in terms of deletions (i.e. it stores the last calculated
    // amount plus the size of any new attachments laoded).  If and when we reach the per-account
    // limit, we recalculate the actual usage
    /*package*/ final HashMap<Long, Long> mAttachmentStorageMap = new HashMap<Long, Long>();
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
            time = System.currentTimeMillis();
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
        private AlarmManager mAlarmManager;

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
        public synchronized void onChange(Attachment att) {
            DownloadRequest req = findDownloadRequest(att.mId);
            long priority = getPriority(att);
            if (priority == PRIORITY_NONE) {
                if (Email.DEBUG) {
                    Log.d(TAG, "== Attachment changed: " + att.mId);
                }
                // In this case, there is no download priority for this attachment
                if (req != null) {
                    // If it exists in the map, remove it
                    // NOTE: We don't yet support deleting downloads in progress
                    if (Email.DEBUG) {
                        Log.d(TAG, "== Attachment " + att.mId + " was in queue, removing");
                    }
                    remove(req);
                }
            } else {
                // Ignore changes that occur during download
                if (mDownloadsInProgress.containsKey(att.mId)) return;
                // If this is new, add the request to the queue
                if (req == null) {
                    req = new DownloadRequest(mContext, att);
                    add(req);
                }
                // If the request already existed, we'll update the priority (so that the time is
                // up-to-date); otherwise, we create a new request
                if (Email.DEBUG) {
                    Log.d(TAG, "== Download queued for attachment " + att.mId + ", class " +
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

        /**
         * Run through the AttachmentMap and find DownloadRequests that can be executed, enforcing
         * the limit on maximum downloads
         */
        /*package*/ synchronized void processQueue() {
            if (Email.DEBUG) {
                Log.d(TAG, "== Checking attachment queue, " + mDownloadSet.size() + " entries");
            }
            Iterator<DownloadRequest> iterator = mDownloadSet.descendingIterator();
            // First, start up any required downloads, in priority order
            while (iterator.hasNext() &&
                    (mDownloadsInProgress.size() < MAX_SIMULTANEOUS_DOWNLOADS)) {
                DownloadRequest req = iterator.next();
                 // Enforce per-account limit here
                if (downloadsForAccount(req.accountId) >= MAX_SIMULTANEOUS_DOWNLOADS_PER_ACCOUNT) {
                    if (Email.DEBUG) {
                        Log.d(TAG, "== Skip #" + req.attachmentId + "; maxed for acct #" +
                                req.accountId);
                    }
                    continue;
                }

                if (!req.inProgress) {
                    mDownloadSet.tryStartDownload(req);
                }
            }
            // Then, try opportunistic download of appropriate attachments
            int backgroundDownloads = MAX_SIMULTANEOUS_DOWNLOADS - mDownloadsInProgress.size();
            // Always leave one slot for user requested download
            if (backgroundDownloads > (MAX_SIMULTANEOUS_DOWNLOADS - 1)) {
                boolean repeat = true;
                while (repeat) {
                    // We'll take the most recent unloaded attachment
                    Long prefetchId = Utility.getFirstRowLong(mContext, SINGLE_ATTACHMENT_URI,
                            Attachment.ID_PROJECTION, AttachmentColumns.CONTENT_URI
                            + " isnull AND " + Attachment.FLAGS + "=0", null,
                            Attachment.RECORD_ID + " DESC", Attachment.ID_PROJECTION_COLUMN);
                    if (prefetchId == null) break;
                    if (Email.DEBUG) {
                        Log.d(TAG, ">> Prefetch attachment " + prefetchId);
                    }
                    Attachment att = Attachment.restoreAttachmentWithId(mContext, prefetchId);
                    // If att is null, the attachment must have been deleted out from under us
                    if (att == null) continue;
                    if (getServiceClassForAccount(att.mAccountKey) == null) {
                        // Clean up this orphaned attachment; there's no point in keeping it
                        // around; then try to find another one
                        EmailContent.delete(mContext, Attachment.CONTENT_URI, prefetchId);
                        continue;
                    }
                    repeat = false;
                    // TODO It's possible that we're just over limit for this particular account
                    // Handle this so that attachments from other accounts (if any) can be tried
                    if (canPrefetchForAccount(att.mAccountKey, mContext.getCacheDir())) {
                        DownloadRequest req = new DownloadRequest(mContext, att);
                        mDownloadSet.tryStartDownload(req);
                    }
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

        private void onWatchdogAlarm() {
            long now = System.currentTimeMillis();
            for (DownloadRequest req: mDownloadsInProgress.values()) {
                // Check how long it's been since receiving a callback
                long timeSinceCallback = now - req.lastCallbackTime;
                if (timeSinceCallback > CALLBACK_TIMEOUT) {
                    if (Email.DEBUG) {
                        Log.d(TAG, "== Download of " + req.attachmentId + " timed out");
                    }
                   cancelDownload(req);
                // STOPSHIP Remove this before ship
                } else if (Email.DEBUG) {
                    Log.d(TAG, "== ,  Download of " + req.attachmentId +
                    " last callback " + (timeSinceCallback/1000) + "  secs ago");
                }
            }
            // If there are downloads in progress, reset alarm
            if (mDownloadsInProgress.isEmpty()) {
                if (mAlarmManager != null && mWatchdogPendingIntent != null) {
                    mAlarmManager.cancel(mWatchdogPendingIntent);
                }
            }
            // Check whether we can start new downloads...
            processQueue();
        }

        /**
         * Do the work of starting an attachment download using the EmailService interface, and
         * set our watchdog alarm
         *
         * @param serviceClass the class that will attempt the download
         * @param req the DownloadRequest
         * @throws RemoteException
         */
        private void startDownload(Class<? extends Service> serviceClass, DownloadRequest req)
                throws RemoteException {
            File file = AttachmentProvider.getAttachmentFilename(mContext, req.accountId,
                    req.attachmentId);
            req.startTime = System.currentTimeMillis();
            req.inProgress = true;
            mDownloadsInProgress.put(req.attachmentId, req);
            if (serviceClass.equals(NullEmailService.class)) return;
            // Now, call the service
            EmailServiceProxy proxy =
                new EmailServiceProxy(mContext, serviceClass, mServiceCallback);
            proxy.loadAttachment(req.attachmentId, file.getAbsolutePath(),
                    AttachmentProvider.getAttachmentUri(req.accountId, req.attachmentId)
                    .toString());
            // Lazily initialize our (reusable) pending intent
            if (mWatchdogPendingIntent == null) {
                Intent alarmIntent = new Intent(mContext, Watchdog.class);
                mWatchdogPendingIntent = PendingIntent.getBroadcast(mContext, 0, alarmIntent, 0);
                mAlarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
            }
            // Set the alarm
            mAlarmManager.setRepeating(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + WATCHDOG_CHECK_INTERVAL, WATCHDOG_CHECK_INTERVAL,
                    mWatchdogPendingIntent);
        }

        private synchronized DownloadRequest getDownloadInProgress(long attachmentId) {
            return mDownloadsInProgress.get(attachmentId);
        }

        /**
         * Attempt to execute the DownloadRequest, enforcing the maximum downloads per account
         * parameter
         * @param req the DownloadRequest
         * @return whether or not the download was started
         */
        /*package*/ synchronized boolean tryStartDownload(DownloadRequest req) {
            Class<? extends Service> serviceClass = getServiceClassForAccount(req.accountId);
            if (serviceClass == null) return false;
            try {
                if (Email.DEBUG) {
                    Log.d(TAG, ">> Starting download for attachment #" + req.attachmentId);
                }
                startDownload(serviceClass, req);
            } catch (RemoteException e) {
                // TODO: Consider whether we need to do more in this case...
                // For now, fix up our data to reflect the failure
                cancelDownload(req);
            }
            return true;
        }

        private void cancelDownload(DownloadRequest req) {
            mDownloadsInProgress.remove(req.attachmentId);
            req.inProgress = false;
        }

        /**
         * Called when a download is finished; we get notified of this via our EmailServiceCallback
         * @param attachmentId the id of the attachment whose download is finished
         * @param statusCode the EmailServiceStatus code returned by the Service
         */
        /*package*/ synchronized void endDownload(long attachmentId, int statusCode) {
            // Say we're no longer downloading this
            mDownloadsInProgress.remove(attachmentId);
            DownloadRequest req = mDownloadSet.findDownloadRequest(attachmentId);
            if (statusCode == EmailServiceStatus.CONNECTION_ERROR) {
                // If this needs to be retried, just process the queue again
                if (Email.DEBUG) {
                    Log.d(TAG, "== The download for attachment #" + attachmentId +
                            " will be retried");
                }
                if (req != null) {
                    req.inProgress = false;
                }
                kick();
                return;
            }

            // Remove the request from the queue
            remove(req);
            if (Email.DEBUG) {
                long secs = 0;
                if (req != null) {
                    secs = (System.currentTimeMillis() - req.time) / 1000;
                }
                String status = (statusCode == EmailServiceStatus.SUCCESS) ? "Success" :
                    "Error " + statusCode;
                Log.d(TAG, "<< Download finished for attachment #" + attachmentId + "; " + secs +
                           " seconds from request, status: " + status);
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
                        if (Email.DEBUG) {
                            Log.d(TAG, "== Downloads finished for outgoing msg #" + req.messageId);
                        }
                        MailService.actionSendPendingMail(mContext, req.accountId);
                    }
                }
                if (!deleted) {
                    // Clear the download flags, since we're done for now.  Note that this happens
                    // only for non-recoverable errors.  When these occur for forwarded mail, we can
                    // ignore it and continue; otherwise, it was either 1) a user request, in which
                    // case the user can retry manually or 2) an opportunistic download, in which
                    // case the download wasn't critical
                    ContentValues cv = new ContentValues();
                    int flags =
                        Attachment.FLAG_DOWNLOAD_FORWARD | Attachment.FLAG_DOWNLOAD_USER_REQUEST;
                    cv.put(Attachment.FLAGS, attachment.mFlags &= ~flags);
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
            priorityClass = PRIORITY_NORMAL;
        } else if ((flags & Attachment.FLAG_DOWNLOAD_USER_REQUEST) != 0) {
            priorityClass = PRIORITY_HIGH;
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
        public void loadAttachmentStatus(long messageId, long attachmentId, int statusCode,
                int progress) {
            // Record status and progress
            DownloadRequest req = mDownloadSet.getDownloadInProgress(attachmentId);
            if (req != null) {
                if (Email.DEBUG) {
                    String code;
                    switch(statusCode) {
                        case EmailServiceStatus.SUCCESS: code = "Success"; break;
                        case EmailServiceStatus.IN_PROGRESS: code = "In progress"; break;
                        default: code = Integer.toString(statusCode); break;
                    }
                    if (statusCode != EmailServiceStatus.IN_PROGRESS) {
                        Log.d(TAG, ">> Attachment " + attachmentId + ": " + code);
                    } else if (progress >= (req.lastProgress + 15)) {
                        Log.d(TAG, ">> Attachment " + attachmentId + ": " + progress + "%");
                    }
                }
                req.lastStatusCode = statusCode;
                req.lastProgress = progress;
                req.lastCallbackTime = System.currentTimeMillis();
            }
            switch (statusCode) {
                case EmailServiceStatus.IN_PROGRESS:
                    break;
                default:
                    mDownloadSet.endDownload(attachmentId, statusCode);
                    break;
            }
        }

        @Override
        public void sendMessageStatus(long messageId, String subject, int statusCode, int progress)
                throws RemoteException {
        }

        @Override
        public void syncMailboxListStatus(long accountId, int statusCode, int progress)
                throws RemoteException {
        }

        @Override
        public void syncMailboxStatus(long mailboxId, int statusCode, int progress)
                throws RemoteException {
        }
    }

    /**
     * Return the class of the service used by the account type of the provided account id.  We
     * cache the results to avoid repeated database access
     * @param accountId the id of the account
     * @return the service class for the account or null (if the account no longer exists)
     */
    private synchronized Class<? extends Service> getServiceClassForAccount(long accountId) {
        // TODO: We should have some more data-driven way of determining the service class. I'd
        // suggest adding an attribute in the stores.xml file
        Class<? extends Service> serviceClass = mAccountServiceMap.get(accountId);
        if (serviceClass == null) {
            String protocol = Account.getProtocol(mContext, accountId);
            if (protocol == null) return null;
            if (protocol.equals("eas")) {
                serviceClass = ExchangeService.class;
            } else {
                serviceClass = ControllerService.class;
            }
            mAccountServiceMap.put(accountId, serviceClass);
        }
        return serviceClass;
    }

    /*protected*/ void addServiceClass(long accountId, Class<? extends Service> serviceClass) {
        mAccountServiceMap.put(accountId, serviceClass);
    }

    /*package*/ void onChange(Attachment att) {
        mDownloadSet.onChange(att);
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
            if (Email.DEBUG) {
                Log.d(TAG, "Dequeued attachmentId:  " + attachmentId);
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
        if (sRunningService != null) {
            return sRunningService.getSize();
        }
        return 0;
    }

    /**
     * Ask the service whether a particular attachment is queued for download
     * @param attachmentId the id of the Attachment (as stored by EmailProvider)
     * @return whether or not the attachment is queued for download
     */
    public static boolean isAttachmentQueued(long attachmentId) {
        if (sRunningService != null) {
            return sRunningService.isQueued(attachmentId);
        }
        return false;
    }

    /**
     * Ask the service to remove an attachment from the download queue
     * @param attachmentId the id of the Attachment (as stored by EmailProvider)
     * @return whether or not the attachment was removed from the queue
     */
    public static boolean cancelQueuedAttachment(long attachmentId) {
        if (sRunningService != null) {
            return sRunningService.dequeue(attachmentId);
        }
        return false;
    }

    public static void watchdogAlarm() {
        if (sRunningService != null) {
            sRunningService.mDownloadSet.onWatchdogAlarm();
        }
    }

    /**
     * Called directly by EmailProvider whenever an attachment is inserted or changed
     * @param id the attachment's id
     * @param flags the new flags for the attachment
     */
    public static void attachmentChanged(final long id, final int flags) {
        if (sRunningService == null) return;
        Utility.runAsync(new Runnable() {
            public void run() {
                final Attachment attachment =
                    Attachment.restoreAttachmentWithId(sRunningService, id);
                if (attachment != null) {
                    // Store the flags we got from EmailProvider; given that all of this
                    // activity is asynchronous, we need to use the newest data from
                    // EmailProvider
                    attachment.mFlags = flags;
                    sRunningService.onChange(attachment);
                }
            }});
    }

    /**
     * Determine whether an attachment can be prefetched for the given account
     * @return true if download is allowed, false otherwise
     */
    /*package*/ boolean canPrefetchForAccount(long accountId, File dir) {
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
        Long accountStorage = mAttachmentStorageMap.get(accountId);
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
            mAttachmentStorageMap.put(accountId, accountStorage);
        }

        // Return true if we're using less than the maximum per account
        if (accountStorage < perAccountMaxStorage) {
            return true;
        } else {
            if (Email.DEBUG) {
                Log.d(TAG, ">> Prefetch not allowed for account " + accountId + "; used " +
                        accountStorage + ", limit " + perAccountMaxStorage);
            }
            return false;
        }
    }

    public void run() {
        mContext = this;
        mAccountManagerStub = new AccountManagerStub(this);
        // Run through all attachments in the database that require download and add them to
        // the queue
        int mask = Attachment.FLAG_DOWNLOAD_FORWARD | Attachment.FLAG_DOWNLOAD_USER_REQUEST;
        Cursor c = getContentResolver().query(Attachment.CONTENT_URI,
                EmailContent.ID_PROJECTION, "(" + Attachment.FLAGS + " & ?) != 0",
                new String[] {Integer.toString(mask)}, null);
        try {
            Log.d(TAG, "Count: " + c.getCount());
            while (c.moveToNext()) {
                Attachment attachment = Attachment.restoreAttachmentWithId(
                        this, c.getLong(EmailContent.ID_PROJECTION_COLUMN));
                if (attachment != null) {
                    mDownloadSet.onChange(attachment);
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
            mDownloadSet.processQueue();
            synchronized(mLock) {
                try {
                    mLock.wait(PROCESS_QUEUE_WAIT_TIME);
                } catch (InterruptedException e) {
                    // That's ok; we'll just keep looping
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sRunningService = this;
        return Service.START_STICKY;
    }

    /**
     * The lifecycle of this service is managed by Email.setServicesEnabled(), which is called
     * throughout the code, in particular 1) after boot and 2) after accounts are added or removed
     * The goal is that this service should be running at all times when there's at least one
     * email account present.
     */
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
        Log.d(TAG, "**** ON DESTROY!");
        if (sRunningService != null) {
            mStop = true;
            kick();
        }
        sRunningService = null;
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
                Attachment att = Attachment.restoreAttachmentWithId(mContext, req.attachmentId);
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
                    if (att.mContentUri != null) {
                        pw.print(" ContentUri: " + att.mContentUri);
                    }
                    pw.print(" Mime: ");
                    if (att.mMimeType != null) {
                        pw.print(att.mMimeType);
                    } else {
                        pw.print(AttachmentProvider.inferMimeType(fileName, null));
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
