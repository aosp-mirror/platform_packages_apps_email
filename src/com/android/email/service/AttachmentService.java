/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.format.DateUtils;

import com.android.email.AttachmentInfo;
import com.android.email.EmailConnectivityManager;
import com.android.email.NotificationController;
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
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AttachmentService extends Service implements Runnable {
    // For logging.
    public static final String LOG_TAG = "AttachmentService";

    // Minimum wait time before retrying a download that failed due to connection error
    private static final long CONNECTION_ERROR_RETRY_MILLIS = 10 * DateUtils.SECOND_IN_MILLIS;
    // Number of retries before we start delaying between
    private static final long CONNECTION_ERROR_DELAY_THRESHOLD = 5;
    // Maximum time to retry for connection errors.
    private static final long CONNECTION_ERROR_MAX_RETRIES = 10;

    // Our idle time, waiting for notifications; this is something of a failsafe
    private static final int PROCESS_QUEUE_WAIT_TIME = 30 * ((int)DateUtils.MINUTE_IN_MILLIS);
    // How long we'll wait for a callback before canceling a download and retrying
    private static final int CALLBACK_TIMEOUT = 30 * ((int)DateUtils.SECOND_IN_MILLIS);
    // Try to download an attachment in the background this many times before giving up
    private static final int MAX_DOWNLOAD_RETRIES = 5;

    /* package */ static final int PRIORITY_NONE = -1;
    // High priority is for user requests
    /* package */ static final int PRIORITY_FOREGROUND = 0;
    /* package */ static final int PRIORITY_HIGHEST = PRIORITY_FOREGROUND;
    // Normal priority is for forwarded downloads in outgoing mail
    /* package */ static final int PRIORITY_SEND_MAIL = 1;
    // Low priority will be used for opportunistic downloads
    /* package */ static final int PRIORITY_BACKGROUND = 2;
    /* package */ static final int PRIORITY_LOWEST = PRIORITY_BACKGROUND;

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

    private static final String EXTRA_ATTACHMENT = "com.android.email.AttachmentService.attachment";

    // This callback is invoked by the various service backends to give us download progress
    // since those modules are responsible for the actual download.
    private final ServiceCallback mServiceCallback = new ServiceCallback();

    // sRunningService is only set in the UI thread; it's visibility elsewhere is guaranteed
    // by the use of "volatile"
    /*package*/ static volatile AttachmentService sRunningService = null;

    // Signify that we are being shut down & destroyed.
    private volatile boolean mStop = false;

    /*package*/ Context mContext;
    /*package*/ EmailConnectivityManager mConnectivityManager;
    /*package*/ final AttachmentWatchdog mWatchdog = new AttachmentWatchdog();

    private final Object mLock = new Object();

    // A map of attachment storage used per account as we have account based maximums to follow.
    // NOTE: This map is not kept current in terms of deletions (i.e. it stores the last calculated
    // amount plus the size of any new attachments loaded).  If and when we reach the per-account
    // limit, we recalculate the actual usage
    /*package*/ final ConcurrentHashMap<Long, Long> mAttachmentStorageMap =
            new ConcurrentHashMap<Long, Long>();

    // A map of attachment ids to the number of failed attempts to download the attachment
    // NOTE: We do not want to persist this. This allows us to retry background downloading
    // if any transient network errors are fixed & and the app is restarted
    /* package */ final ConcurrentHashMap<Long, Integer> mAttachmentFailureMap =
            new ConcurrentHashMap<Long, Integer>();

    // Keeps tracks of downloads in progress based on an attachment ID to DownloadRequest mapping.
    /*package*/ final ConcurrentHashMap<Long, DownloadRequest> mDownloadsInProgress =
            new ConcurrentHashMap<Long, DownloadRequest>();

    // TODO: Remove in favor of the DownloadQueue when we are ready to move over (soon).
    /*package*/ final DownloadSet mDownloadSet = new DownloadSet(new DownloadComparator());
    /*package*/ final DownloadQueue mDownloadQueue = new DownloadQueue();

    /**
     * This class is used to contain the details and state of a particular request to download
     * an attachment. These objects are constructed and either placed in the {@link DownloadQueue}
     * or in the in-progress map used to keep track of downloads that are currently happening
     * in the system
     */
//    public static class DownloadRequest {
    /*package*/ static class DownloadRequest {
        // Details of the request.
        final int mPriority;
        final long mTime;
        final long mAttachmentId;
        final long mMessageId;
        final long mAccountId;

        // Status of the request.
        boolean mInProgress = false;
        int mLastStatusCode;
        int mLastProgress;
        long mLastCallbackTime;
        long mStartTime;
        long mRetryCount;
        long mRetryStartTime;

        /**
         * This constructor is mainly used for tests
         * @param attPriority The priority of this attachment
         * @param attId The id of the row in the attachment table.
         */
        @VisibleForTesting
        DownloadRequest(final int attPriority, final long attId) {
            // This constructor should only be used for unit tests.
            mTime = SystemClock.elapsedRealtime();
            mPriority = attPriority;
            mAttachmentId = attId;
            mAccountId = -1;
            mMessageId = -1;
        }

        private DownloadRequest(final Context context, final Attachment attachment) {
            mAttachmentId = attachment.mId;
            final Message msg = Message.restoreMessageWithId(context, attachment.mMessageKey);
            if (msg != null) {
                mAccountId = msg.mAccountKey;
                mMessageId = msg.mId;
            } else {
                mAccountId = mMessageId = -1;
            }
            mPriority = getPriority(attachment);
            mTime = SystemClock.elapsedRealtime();
        }

        private DownloadRequest(final DownloadRequest orig, final long newTime) {
            mPriority = orig.mPriority;
            mAttachmentId = orig.mAttachmentId;
            mMessageId = orig.mMessageId;
            mAccountId = orig.mAccountId;
            mTime = newTime;
            mInProgress = orig.mInProgress;
            mLastStatusCode = orig.mLastStatusCode;
            mLastProgress = orig.mLastProgress;
            mLastCallbackTime = orig.mLastCallbackTime;
            mStartTime = orig.mStartTime;
            mRetryCount = orig.mRetryCount;
            mRetryStartTime = orig.mRetryStartTime;
        }

        @Override
        public int hashCode() {
            return (int)mAttachmentId;
        }

        /**
         * Two download requests are equals if their attachment id's are equals
         */
        @Override
        public boolean equals(final Object object) {
            if (!(object instanceof DownloadRequest)) return false;
            final DownloadRequest req = (DownloadRequest)object;
            return req.mAttachmentId == mAttachmentId;
        }
    }

    /**
     * Comparator class for the download set; we first compare by priority.  Requests with equal
     * priority are compared by the time the request was created (older requests come first)
     * TODO: Move this into the DownloadQueue as a private static class when we finally remove
     * the DownloadSet class from the implementation.
     */
    private static class DownloadComparator implements Comparator<DownloadRequest> {
        @Override
        public int compare(DownloadRequest req1, DownloadRequest req2) {
            int res;
            if (req1.mPriority != req2.mPriority) {
                res = (req1.mPriority < req2.mPriority) ? -1 : 1;
            } else {
                if (req1.mTime == req2.mTime) {
                    res = 0;
                } else {
                    res = (req1.mTime < req2.mTime) ? -1 : 1;
                }
            }
            return res;
        }
    }

    /**
     * This class is used to organize the various download requests that are pending.
     * We need a class that allows us to prioritize a collection of {@link DownloadRequest} objects
     * while being able to pull off request with the highest priority but we also need
     * to be able to find a particular {@link DownloadRequest} by id or by reference for retrieval.
     * Bonus points for an implementation that does not require an iterator to accomplish its tasks
     * as we can avoid pesky ConcurrentModificationException when one thread has the iterator
     * and another thread modifies the collection.
     */
    /*package*/ static class DownloadQueue {
        private final int DEFAULT_SIZE = 10;

        // For synchronization
        private final Object mLock = new Object();

        // For prioritization of DownloadRequests.
        /*package*/ final PriorityQueue<DownloadRequest> mRequestQueue =
                new PriorityQueue<DownloadRequest>(DEFAULT_SIZE, new DownloadComparator());

        // Secondary collection to quickly find objects w/o the help of an iterator.
        // This class should be kept in lock step with the priority queue.
        /*package*/ final ConcurrentHashMap<Long, DownloadRequest> mRequestMap =
                new ConcurrentHashMap<Long, DownloadRequest>();

        /**
         * This function will add the request to our collections if it does not already
         * exist. If it does exist, the function will silently succeed.
         * @param request The {@link DownloadRequest} that should be added to our queue
         * @return true if it was added (or already exists), false otherwise
         */
        public synchronized boolean addRequest(final DownloadRequest request)
                throws NullPointerException {
            // It is key to keep the map and queue in lock step
            if (request == null) {
                // We can't add a null entry into the queue so let's throw what the underlying
                // data structure would throw.
                throw new NullPointerException();
            }
            final long requestId = request.mAttachmentId;
            if (requestId < 0) {
                // Invalid request
                LogUtils.wtf(AttachmentService.LOG_TAG,
                        "Adding a DownloadRequest with an invalid id");
                return false;
            }
            synchronized (mLock) {
                // Check to see if this request is is already in the queue
                final boolean exists = mRequestMap.containsKey(requestId);
                if (!exists) {
                    mRequestQueue.offer(request);
                    mRequestMap.put(requestId, request);
                }
            }
            return true;
        }

        /**
         * This function will remove the specified request from the internal collections.
         * @param request The {@link DownloadRequest} that should be removed from our queue
         * @return true if it was removed or the request was invalid (meaning that the request
         * is not in our queue), false otherwise.
         */
        public synchronized boolean removeRequest(final DownloadRequest request) {
            if (request == null) {
                // If it is invalid, its not in the queue.
                return true;
            }
            final boolean result;
            synchronized (mLock) {
                // It is key to keep the map and queue in lock step
                result = mRequestQueue.remove(request);
                if (result) {
                    mRequestMap.remove(request.mAttachmentId);
                }
                return result;
            }
        }

        /**
         * Return the next request from our queue.
         * @return The next {@link DownloadRequest} object or null if the queue is empty
         */
        public synchronized DownloadRequest getNextRequest() {
            // It is key to keep the map and queue in lock step
            final DownloadRequest returnRequest;
            synchronized (mLock) {
                returnRequest = mRequestQueue.poll();
                if (returnRequest != null) {
                    final long requestId = returnRequest.mAttachmentId;
                    mRequestMap.remove(requestId);
                }
            }
            return returnRequest;
        }

        /**
         * Return the {@link DownloadRequest} with the given ID (attachment ID)
         * @param requestId The ID of the request in question
         * @return The associated {@link DownloadRequest} object or null if it does not exist
         */
        public DownloadRequest findRequestById(final long requestId) {
            if (requestId < 0) {
                return null;
            }
            return mRequestMap.get(requestId);
        }

        public int getSize() {
            return mRequestMap.size();
        }

        public boolean isEmpty() {
            return mRequestMap.isEmpty();
        }
    }

    /**
     * Watchdog alarm receiver; responsible for making sure that downloads in progress are not
     * stalled, as determined by the timing of the most recent service callback
     */
    public static class AttachmentWatchdog extends BroadcastReceiver {
        // How often our watchdog checks for callback timeouts
        private static final int WATCHDOG_CHECK_INTERVAL = 20 * ((int)DateUtils.SECOND_IN_MILLIS);
        public static final String EXTRA_CALLBACK_TIMEOUT = "callback_timeout";
        private PendingIntent mWatchdogPendingIntent;

        public void setWatchdogAlarm(final Context context, final long delay,
                final int callbackTimeout) {
            // Lazily initialize the pending intent
            if (mWatchdogPendingIntent == null) {
                Intent intent = new Intent(context, AttachmentWatchdog.class);
                intent.putExtra(EXTRA_CALLBACK_TIMEOUT, callbackTimeout);
                mWatchdogPendingIntent =
                        PendingIntent.getBroadcast(context, 0, intent, 0);
            }
            // Set the alarm
            final AlarmManager am = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay,
                    mWatchdogPendingIntent);
        }

        public void setWatchdogAlarm(final Context context) {
            // Call the real function with default values.
            setWatchdogAlarm(context, WATCHDOG_CHECK_INTERVAL, CALLBACK_TIMEOUT);
        }

        @Override
        public void onReceive(final Context context, final Intent intent) {
            final int callbackTimeout = intent.getIntExtra(EXTRA_CALLBACK_TIMEOUT,
                    CALLBACK_TIMEOUT);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final AttachmentService service = AttachmentService.sRunningService;
                    if (service != null) {
                        // If our service instance is gone, just leave
                        if (service.mStop) {
                            return;
                        }
                        // Get the timeout time from the intent.
                        watchdogAlarm(service, callbackTimeout);
                    }
                }
            }, "AttachmentService AttachmentWatchdog").start();
        }

        /**
         * Watchdog for downloads; we use this in case we are hanging on a download, which might
         * have failed silently (the connection dropped, for example)
         */
        /*package*/ void watchdogAlarm(final AttachmentService service, final int callbackTimeout) {
            final long now = System.currentTimeMillis();
            // We want to iterate on each of the downloads that are currently in progress and
            // cancel the ones that seem to be taking too long.
            final Collection<DownloadRequest> inProgressRequests = service.getInProgressDownloads();
            for (DownloadRequest req: inProgressRequests) {
                // Check how long it's been since receiving a callback
                final long timeSinceCallback = now - req.mLastCallbackTime;
                if (timeSinceCallback > callbackTimeout) {
                    if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                        LogUtils.d(LOG_TAG, "== Download of " + req.mAttachmentId + " timed out");
                    }
                    service.cancelDownload(req);
                }
            }
            // Check whether we can start new downloads...
            if (service.isConnected()) {
                service.processQueue();
            }
            if (service.areDownloadsInProgress()) {
                if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                    LogUtils.d(LOG_TAG, "Reschedule watchdog...");
                }
                setWatchdogAlarm(service);
            }
        }
    }

    /**
     * Temporary function implemented as a transition between DownloadSet to DownloadQueue.
     * Will be property implemented and documented in a subsequent CL.
     * @param req The {@link DownloadRequest} to be cancelled.
     */
    /*package*/ void cancelDownload(final DownloadRequest req) {
        mDownloadSet.cancelDownload(req);
        return;
    }

    /**
     * Temporary function implemented as a transition between DownloadSet to DownloadQueue
     */
    /*package*/ void processQueue() {
        mDownloadSet.processQueue();
        return;
    }

    /*package*/ boolean isConnected() {
        if (mConnectivityManager != null) {
            return mConnectivityManager.hasConnectivity();
        }
        return false;
    }

    /*package*/ Collection<DownloadRequest> getInProgressDownloads() {
        return mDownloadsInProgress.values();
    }

    /*package*/ boolean areDownloadsInProgress() {
        return !mDownloadsInProgress.isEmpty();
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

        /*package*/ DownloadSet(Comparator<? super DownloadRequest> comparator) {
            super(comparator);
        }

        private void markAttachmentAsFailed(final Attachment att) {
            final ContentValues cv = new ContentValues();
            final int flags = Attachment.FLAG_DOWNLOAD_FORWARD | Attachment.FLAG_DOWNLOAD_USER_REQUEST;
            cv.put(AttachmentColumns.FLAGS, att.mFlags &= ~flags);
            cv.put(AttachmentColumns.UI_STATE, AttachmentState.FAILED);
            att.update(mContext, cv);
        }

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
                if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                    LogUtils.d(LOG_TAG, "== Attachment changed: " + att.mId);
                }
                // In this case, there is no download priority for this attachment
                if (req != null) {
                    // If it exists in the map, remove it
                    // NOTE: We don't yet support deleting downloads in progress
                    if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                        LogUtils.d(LOG_TAG, "== Attachment " + att.mId + " was in queue, removing");
                    }
                    remove(req);
                }
            } else {
                // Ignore changes that occur during download
                if (mDownloadsInProgress.containsKey(att.mId)) return;
                // If this is new, add the request to the queue
                if (req == null) {
                    req = new DownloadRequest(context, att);
                    final AttachmentInfo attachInfo = new AttachmentInfo(context, att);
                    if (!attachInfo.isEligibleForDownload()) {
                        // We can't download this file due to policy, depending on what type
                        // of request we received, we handle the response differently.
                        if (((att.mFlags & Attachment.FLAG_DOWNLOAD_USER_REQUEST) != 0) ||
                                ((att.mFlags & Attachment.FLAG_POLICY_DISALLOWS_DOWNLOAD) != 0)) {
                            // There are a couple of situations where we will not even allow this
                            // request to go in the queue because we can already process it as a
                            // failure.
                            // 1. The user explictly wants to download this attachment from the
                            // email view but they should not be able to...either because there is
                            // no app to view it or because its been marked as a policy violation.
                            // 2. The user is forwarding an email and the attachment has been
                            // marked as a policy violation. If the attachment is non viewable
                            // that is OK for forwarding a message so we'll let it pass through
                            markAttachmentAsFailed(att);
                            return;
                        }
                        // If we get this far it a forward of an attachment that is only
                        // ineligible because we can't view it or process it. Not because we
                        // can't download it for policy reasons. Let's let this go through because
                        // the final recipient of this forward email might be able to process it.
                    }
                    add(req);
                }
                // If the request already existed, we'll update the priority (so that the time is
                // up-to-date); otherwise, we create a new request
                if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                    LogUtils.d(LOG_TAG, "== Download queued for attachment " + att.mId + ", class " +
                            req.mPriority + ", priority time " + req.mTime);
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
                if (req.mAttachmentId == id) {
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
            if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                LogUtils.d(LOG_TAG, "== Checking attachment queue, " + mDownloadSet.size()
                        + " entries");
            }
            Iterator<DownloadRequest> iterator = mDownloadSet.descendingIterator();
            // First, start up any required downloads, in priority order
            while (iterator.hasNext() &&
                    (mDownloadsInProgress.size() < MAX_SIMULTANEOUS_DOWNLOADS)) {
                DownloadRequest req = iterator.next();
                 // Enforce per-account limit here
                if (downloadsForAccount(req.mAccountId) >= MAX_SIMULTANEOUS_DOWNLOADS_PER_ACCOUNT) {
                    if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                        LogUtils.d(LOG_TAG, "== Skip #" + req.mAttachmentId + "; maxed for acct #" +
                                req.mAccountId);
                    }
                    continue;
                } else if (Attachment.restoreAttachmentWithId(mContext, req.mAttachmentId) == null) {
                    continue;
                }
                if (!req.mInProgress) {
                    final long currentTime = SystemClock.elapsedRealtime();
                    if (req.mRetryCount > 0 && req.mRetryStartTime > currentTime) {
                        LogUtils.d(LOG_TAG, "== waiting to retry attachment %d", req.mAttachmentId);
                        mWatchdog.setWatchdogAlarm(mContext, CONNECTION_ERROR_RETRY_MILLIS,
                                CALLBACK_TIMEOUT);
                        continue;
                    }
                    // TODO: We try to gate ineligible downloads from entering the queue but its
                    // always possible that they made it in here regardless in the future.  In a
                    // perfect world, we would make it bullet proof with a check for eligibility
                    // here instead/also.
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
                        null, AttachmentColumns._ID + " DESC");
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
                            } else {
                                // If this attachment was ineligible for download
                                // because of policy related issues, its flags would be set to
                                // FLAG_POLICY_DISALLOWS_DOWNLOAD and would not show up in the
                                // query results. We are most likely here for other reasons such
                                // as the inability to view the attachment. In that case, let's just
                                // skip it for now.
                                LogUtils.e(LOG_TAG, "== skip attachment %d, it is ineligible", att.mId);
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
                if (req.mAccountId == accountId) {
                    count++;
                }
            }
            return count;
        }

        /**
         * Attempt to execute the DownloadRequest, enforcing the maximum downloads per account
         * parameter
         * @param req the DownloadRequest
         * @return whether or not the download was started
         */
        /*package*/ synchronized boolean tryStartDownload(DownloadRequest req) {
            EmailServiceProxy service = EmailServiceUtils.getServiceForAccount(
                    AttachmentService.this, req.mAccountId);

            // Do not download the same attachment multiple times
            boolean alreadyInProgress = mDownloadsInProgress.get(req.mAttachmentId) != null;
            if (alreadyInProgress) return false;

            try {
                if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                    LogUtils.d(LOG_TAG, ">> Starting download for attachment #" + req.mAttachmentId);
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
            req.mStartTime = System.currentTimeMillis();
            req.mInProgress = true;
            mDownloadsInProgress.put(req.mAttachmentId, req);
            service.loadAttachment(mServiceCallback, req.mAccountId, req.mAttachmentId,
                    req.mPriority != PRIORITY_FOREGROUND);
            mWatchdog.setWatchdogAlarm(mContext);
        }

        /*package*/ synchronized void cancelDownload(DownloadRequest req) {
            LogUtils.d(LOG_TAG, "cancelDownload #%d", req.mAttachmentId);
            req.mInProgress = false;
            mDownloadsInProgress.remove(req.mAttachmentId);
            // Remove the download from our queue, and then decide whether or not to add it back.
            remove(req);
            req.mRetryCount++;
            if (req.mRetryCount > CONNECTION_ERROR_MAX_RETRIES) {
                LogUtils.d(LOG_TAG, "too many failures, giving up");
            } else {
                LogUtils.d(LOG_TAG, "moving to end of queue, will retry");
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
                    req.mRetryCount++;
                    if (req.mRetryCount > CONNECTION_ERROR_MAX_RETRIES) {
                        LogUtils.d(LOG_TAG, "Connection Error #%d, giving up", attachmentId);
                        remove(req);
                    } else if (req.mRetryCount > CONNECTION_ERROR_DELAY_THRESHOLD) {
                        // TODO: I'm not sure this is a great retry/backoff policy, but we're
                        // afraid of changing behavior too much in case something relies upon it.
                        // So now, for the first five errors, we'll retry immediately. For the next
                        // five tries, we'll add a ten second delay between each. After that, we'll
                        // give up.
                        LogUtils.d(LOG_TAG, "ConnectionError #%d, retried %d times, adding delay",
                                attachmentId, req.mRetryCount);
                        req.mInProgress = false;
                        req.mRetryStartTime = SystemClock.elapsedRealtime() +
                                CONNECTION_ERROR_RETRY_MILLIS;
                        mWatchdog.setWatchdogAlarm(mContext, CONNECTION_ERROR_RETRY_MILLIS,
                                CALLBACK_TIMEOUT);
                    } else {
                        LogUtils.d(LOG_TAG, "ConnectionError #%d, retried %d times, adding delay",
                                attachmentId, req.mRetryCount);
                        req.mInProgress = false;
                        req.mRetryStartTime = 0;
                        kick();
                    }
                }
                return;
            }

            // If the request is still in the queue, remove it
            if (req != null) {
                remove(req);
            }
            if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                long secs = 0;
                if (req != null) {
                    secs = (System.currentTimeMillis() - req.mTime) / 1000;
                }
                String status = (statusCode == EmailServiceStatus.SUCCESS) ? "Success" :
                    "Error " + statusCode;
                LogUtils.d(LOG_TAG, "<< Download finished for attachment #" + attachmentId + "; " + secs
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
                        if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                            LogUtils.d(LOG_TAG, "== Downloads finished for outgoing msg #"
                                    + req.mMessageId);
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
                    cv.put(AttachmentColumns.FLAGS, attachment.mFlags &= ~flags);
                    cv.put(AttachmentColumns.UI_STATE, AttachmentState.SAVED);
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
                if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                    String code;
                    switch(statusCode) {
                        case EmailServiceStatus.SUCCESS: code = "Success"; break;
                        case EmailServiceStatus.IN_PROGRESS: code = "In progress"; break;
                        default: code = Integer.toString(statusCode); break;
                    }
                    if (statusCode != EmailServiceStatus.IN_PROGRESS) {
                        LogUtils.d(LOG_TAG, ">> Attachment status " + attachmentId + ": " + code);
                    } else if (progress >= (req.mLastProgress + 10)) {
                        LogUtils.d(LOG_TAG, ">> Attachment progress %d: %d%%", attachmentId, progress);
                    }
                }
                req.mLastStatusCode = statusCode;
                req.mLastProgress = progress;
                req.mLastCallbackTime = System.currentTimeMillis();
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
            if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                LogUtils.d(LOG_TAG, "Dequeued attachmentId:  " + attachmentId);
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
        AttachmentService service = sRunningService;
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
        AttachmentService service = sRunningService;
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
        AttachmentService service = sRunningService;
        if (service != null) {
            return service.dequeue(attachmentId);
        }
        return false;
    }

    // The queue entries here are entries of the form {id, flags}, with the values passed in to
    // attachmentChanged()
    private static final Queue<long[]> sAttachmentChangedQueue =
            new ConcurrentLinkedQueue<long[]>();
    private static AsyncTask<Void, Void, Void> sAttachmentChangedTask;

    /**
     * Called directly by EmailProvider whenever an attachment is inserted or changed
     * @param context the caller's context
     * @param id the attachment's id
     * @param flags the new flags for the attachment
     */
    public static void attachmentChanged(final Context context, final long id, final int flags) {
        synchronized (sAttachmentChangedQueue) {
            sAttachmentChangedQueue.add(new long[]{id, flags});

            if (sAttachmentChangedTask == null) {
                sAttachmentChangedTask = new AsyncTask<Void, Void, Void>() {
                    @Override
                    protected Void doInBackground(Void... params) {
                        while (true) {
                            final long[] change;
                            synchronized (sAttachmentChangedQueue) {
                                change = sAttachmentChangedQueue.poll();
                                if (change == null) {
                                    sAttachmentChangedTask = null;
                                    return null;
                                }
                            }
                            final long id = change[0];
                            final long flags = change[1];
                            final Attachment attachment =
                                    Attachment.restoreAttachmentWithId(context, id);
                            if (attachment == null) {
                                continue;
                            }
                            attachment.mFlags = (int) flags;
                            final Intent intent =
                                    new Intent(context, AttachmentService.class);
                            intent.putExtra(EXTRA_ATTACHMENT, attachment);
                            context.startService(intent);
                        }
                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        }
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
            if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                LogUtils.d(LOG_TAG, ">> Prefetch not allowed for account " + account.mId + "; used " +
                        accountStorage + ", limit " + perAccountMaxStorage);
            }
            return false;
        }
    }

    @Override
    public void run() {
        // These fields are only used within the service thread
        mContext = this;
        mConnectivityManager = new EmailConnectivityManager(this, LOG_TAG);
        mAccountManagerStub = new AccountManagerStub(this);

        // Run through all attachments in the database that require download and add them to
        // the queue
        int mask = Attachment.FLAG_DOWNLOAD_FORWARD | Attachment.FLAG_DOWNLOAD_USER_REQUEST;
        Cursor c = getContentResolver().query(Attachment.CONTENT_URI,
                EmailContent.ID_PROJECTION, "(" + AttachmentColumns.FLAGS + " & ?) != 0",
                new String[] {Integer.toString(mask)}, null);
        try {
            LogUtils.d(LOG_TAG, "Count: " + c.getCount());
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
                LogUtils.d(LOG_TAG, "*** All done; shutting down service");
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
            Attachment att = intent.getParcelableExtra(EXTRA_ATTACHMENT);
            onChange(att);
        }
        return Service.START_STICKY;
    }

    @Override
    public void onCreate() {
        // Start up our service thread
        new Thread(this, "AttachmentService").start();
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

    // For Debugging.
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("AttachmentService");
        long time = System.currentTimeMillis();
        synchronized(mDownloadSet) {
            pw.println("  Queue, " + mDownloadSet.size() + " entries");
            Iterator<DownloadRequest> iterator = mDownloadSet.descendingIterator();
            // First, start up any required downloads, in priority order
            while (iterator.hasNext()) {
                DownloadRequest req = iterator.next();
                pw.println("    Account: " + req.mAccountId + ", Attachment: " + req.mAttachmentId);
                pw.println("      Priority: " + req.mPriority + ", Time: " + req.mTime +
                        (req.mInProgress ? " [In progress]" : ""));
                Attachment att = Attachment.restoreAttachmentWithId(this, req.mAttachmentId);
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
                if (req.mInProgress) {
                    pw.println("      Status: " + req.mLastStatusCode + ", Progress: " +
                            req.mLastProgress);
                    pw.println("      Started: " + req.mStartTime + ", Callback: " +
                            req.mLastCallbackTime);
                    pw.println("      Elapsed: " + ((time - req.mStartTime) / 1000L) + "s");
                    if (req.mLastCallbackTime > 0) {
                        pw.println("      CB: " + ((time - req.mLastCallbackTime) / 1000L) + "s");
                    }
                }
            }
        }
    }

    // For Testing
    /*package*/ AccountManagerStub mAccountManagerStub;
    private final HashMap<Long, Intent> mAccountServiceMap = new HashMap<Long, Intent>();

    /*package*/ void addServiceIntentForTest(long accountId, Intent intent) {
        mAccountServiceMap.put(accountId, intent);
    }

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
}
