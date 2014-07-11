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
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AttachmentService extends Service implements Runnable {
    // For logging.
    public static final String LOG_TAG = "AttachmentService";

    // STOPSHIP Set this to 0 before shipping.
    private static final int ENABLE_ATTACHMENT_SERVICE_DEBUG = 0;

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

    static final int PRIORITY_NONE = -1;
    // High priority is for user requests
    static final int PRIORITY_FOREGROUND = 0;
    static final int PRIORITY_HIGHEST = PRIORITY_FOREGROUND;
    // Normal priority is for forwarded downloads in outgoing mail
    static final int PRIORITY_SEND_MAIL = 1;
    // Low priority will be used for opportunistic downloads
    static final int PRIORITY_BACKGROUND = 2;
    static final int PRIORITY_LOWEST = PRIORITY_BACKGROUND;

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

    private static final String EXTRA_ATTACHMENT_ID =
            "com.android.email.AttachmentService.attachment_id";
    private static final String EXTRA_ATTACHMENT_FLAGS =
            "com.android.email.AttachmentService.attachment_flags";

    // This callback is invoked by the various service implementations to give us download progress
    // since those modules are responsible for the actual download.
    final ServiceCallback mServiceCallback = new ServiceCallback();

    // sRunningService is only set in the UI thread; it's visibility elsewhere is guaranteed
    // by the use of "volatile"
    static volatile AttachmentService sRunningService = null;

    // Signify that we are being shut down & destroyed.
    private volatile boolean mStop = false;

    EmailConnectivityManager mConnectivityManager;

    // Helper class that keeps track of in progress downloads to make sure that they
    // are progressing well.
    final AttachmentWatchdog mWatchdog = new AttachmentWatchdog();

    private final Object mLock = new Object();

    // A map of attachment storage used per account as we have account based maximums to follow.
    // NOTE: This map is not kept current in terms of deletions (i.e. it stores the last calculated
    // amount plus the size of any new attachments loaded).  If and when we reach the per-account
    // limit, we recalculate the actual usage
    final ConcurrentHashMap<Long, Long> mAttachmentStorageMap = new ConcurrentHashMap<Long, Long>();

    // A map of attachment ids to the number of failed attempts to download the attachment
    // NOTE: We do not want to persist this. This allows us to retry background downloading
    // if any transient network errors are fixed & and the app is restarted
    final ConcurrentHashMap<Long, Integer> mAttachmentFailureMap = new ConcurrentHashMap<Long, Integer>();

    // Keeps tracks of downloads in progress based on an attachment ID to DownloadRequest mapping.
    final ConcurrentHashMap<Long, DownloadRequest> mDownloadsInProgress =
            new ConcurrentHashMap<Long, DownloadRequest>();

    final DownloadQueue mDownloadQueue = new DownloadQueue();

    // The queue entries here are entries of the form {id, flags}, with the values passed in to
    // attachmentChanged(). Entries in the queue are picked off in processQueue().
    private static final Queue<long[]> sAttachmentChangedQueue =
            new ConcurrentLinkedQueue<long[]>();

    // Extra layer of control over debug logging that should only be enabled when
    // we need to take an extra deep dive at debugging the workflow in this class.
    static private void debugTrace(final String format, final Object... args) {
        if (ENABLE_ATTACHMENT_SERVICE_DEBUG > 0) {
            LogUtils.d(LOG_TAG, String.format(format, args));
        }
    }

    /**
     * This class is used to contain the details and state of a particular request to download
     * an attachment. These objects are constructed and either placed in the {@link DownloadQueue}
     * or in the in-progress map used to keep track of downloads that are currently happening
     * in the system
     */
    static class DownloadRequest {
        // Details of the request.
        final int mPriority;
        final long mCreatedTime;
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
            mCreatedTime = SystemClock.elapsedRealtime();
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
            mPriority = getAttachmentPriority(attachment);
            mCreatedTime = SystemClock.elapsedRealtime();
        }

        private DownloadRequest(final DownloadRequest orig, final long newTime) {
            mPriority = orig.mPriority;
            mAttachmentId = orig.mAttachmentId;
            mMessageId = orig.mMessageId;
            mAccountId = orig.mAccountId;
            mCreatedTime = newTime;
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
     * This class is used to organize the various download requests that are pending.
     * We need a class that allows us to prioritize a collection of {@link DownloadRequest} objects
     * while being able to pull off request with the highest priority but we also need
     * to be able to find a particular {@link DownloadRequest} by id or by reference for retrieval.
     * Bonus points for an implementation that does not require an iterator to accomplish its tasks
     * as we can avoid pesky ConcurrentModificationException when one thread has the iterator
     * and another thread modifies the collection.
     */
    static class DownloadQueue {
        private final int DEFAULT_SIZE = 10;

        // For synchronization
        private final Object mLock = new Object();

        /**
         * Comparator class for the download set; we first compare by priority.  Requests with equal
         * priority are compared by the time the request was created (older requests come first)
         */
        private static class DownloadComparator implements Comparator<DownloadRequest> {
            @Override
            public int compare(DownloadRequest req1, DownloadRequest req2) {
                int res;
                if (req1.mPriority != req2.mPriority) {
                    res = (req1.mPriority < req2.mPriority) ? -1 : 1;
                } else {
                    if (req1.mCreatedTime == req2.mCreatedTime) {
                        res = 0;
                    } else {
                        res = (req1.mCreatedTime < req2.mCreatedTime) ? -1 : 1;
                    }
                }
                return res;
            }
        }

        // For prioritization of DownloadRequests.
        final PriorityQueue<DownloadRequest> mRequestQueue =
                new PriorityQueue<DownloadRequest>(DEFAULT_SIZE, new DownloadComparator());

        // Secondary collection to quickly find objects w/o the help of an iterator.
        // This class should be kept in lock step with the priority queue.
        final ConcurrentHashMap<Long, DownloadRequest> mRequestMap =
                new ConcurrentHashMap<Long, DownloadRequest>();

        /**
         * This function will add the request to our collections if it does not already
         * exist. If it does exist, the function will silently succeed.
         * @param request The {@link DownloadRequest} that should be added to our queue
         * @return true if it was added (or already exists), false otherwise
         */
        public boolean addRequest(final DownloadRequest request)
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
                LogUtils.d(LOG_TAG, "Not adding a DownloadRequest with an invalid attachment id");
                return false;
            }
            debugTrace("Queuing DownloadRequest #%d", requestId);
            synchronized (mLock) {
                // Check to see if this request is is already in the queue
                final boolean exists = mRequestMap.containsKey(requestId);
                if (!exists) {
                    mRequestQueue.offer(request);
                    mRequestMap.put(requestId, request);
                } else {
                    debugTrace("DownloadRequest #%d was already in the queue");
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
        public boolean removeRequest(final DownloadRequest request) {
            if (request == null) {
                // If it is invalid, its not in the queue.
                return true;
            }
            debugTrace("Removing DownloadRequest #%d", request.mAttachmentId);
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
        public DownloadRequest getNextRequest() {
            // It is key to keep the map and queue in lock step
            final DownloadRequest returnRequest;
            synchronized (mLock) {
                returnRequest = mRequestQueue.poll();
                if (returnRequest != null) {
                    final long requestId = returnRequest.mAttachmentId;
                    mRequestMap.remove(requestId);
                }
            }
            if (returnRequest != null) {
                debugTrace("Retrieved DownloadRequest #%d", returnRequest.mAttachmentId);
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
            synchronized (mLock) {
                return mRequestMap.get(requestId);
            }
        }

        public int getSize() {
            synchronized (mLock) {
                return mRequestMap.size();
            }
        }

        public boolean isEmpty() {
            synchronized (mLock) {
                return mRequestMap.isEmpty();
            }
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
            debugTrace("Set up a watchdog for %d millis in the future", delay);
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
                    // TODO: Really don't like hard coding the AttachmentService reference here
                    // as it makes testing harder if we are trying to mock out the service
                    // We should change this with some sort of getter that returns the
                    // static (or test) AttachmentService instance to use.
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

        boolean validateDownloadRequest(final DownloadRequest dr, final int callbackTimeout,
                final long now) {
            // Check how long it's been since receiving a callback
            final long timeSinceCallback = now - dr.mLastCallbackTime;
            if (timeSinceCallback > callbackTimeout) {
                LogUtils.d(LOG_TAG, "Timeout for DownloadRequest #%d ", dr.mAttachmentId);
                return true;
            }
            return false;
        }

        /**
         * Watchdog for downloads; we use this in case we are hanging on a download, which might
         * have failed silently (the connection dropped, for example)
         */
        void watchdogAlarm(final AttachmentService service, final int callbackTimeout) {
            debugTrace("Received a timer callback in the watchdog");

            // We want to iterate on each of the downloads that are currently in progress and
            // cancel the ones that seem to be taking too long.
            final Collection<DownloadRequest> inProgressRequests =
                    service.mDownloadsInProgress.values();
            for (DownloadRequest req: inProgressRequests) {
                debugTrace("Checking in-progress request with id: %d", req.mAttachmentId);
                final boolean shouldCancelDownload = validateDownloadRequest(req, callbackTimeout,
                        System.currentTimeMillis());
                if (shouldCancelDownload) {
                    LogUtils.w(LOG_TAG, "Cancelling DownloadRequest #%d", req.mAttachmentId);
                    service.cancelDownload(req);
                    // TODO: Should we also mark the attachment as failed at this point in time?
                }
            }
            // Check whether we can start new downloads...
            if (service.isConnected()) {
                service.processQueue();
            }
            issueNextWatchdogAlarm(service);
        }

        void issueNextWatchdogAlarm(final AttachmentService service) {
            if (!service.mDownloadsInProgress.isEmpty()) {
                debugTrace("Rescheduling watchdog...");
                setWatchdogAlarm(service);
            }
        }
    }

    /**
     * We use an EmailServiceCallback to keep track of the progress of downloads.  These callbacks
     * come from either Controller (IMAP/POP) or ExchangeService (EAS).  Note that we only
     * implement the single callback that's defined by the EmailServiceCallback interface.
     */
    class ServiceCallback extends IEmailServiceCallback.Stub {

        /**
         * Simple routine to generate updated status values for the Attachment based on the
         * service callback. Right now it is very simple but factoring out this code allows us
         * to test easier and very easy to expand in the future.
         */
        ContentValues getAttachmentUpdateValues(final Attachment attachment,
                final int statusCode, final int progress) {
            final ContentValues values = new ContentValues();
            if (attachment != null) {
                if (statusCode == EmailServiceStatus.IN_PROGRESS) {
                    // TODO: What else do we want to expose about this in-progress download through
                    // the provider?  If there is more, make sure that the service implementation
                    // reports it and make sure that we add it here.
                    values.put(AttachmentColumns.UI_STATE, AttachmentState.DOWNLOADING);
                    values.put(AttachmentColumns.UI_DOWNLOADED_SIZE,
                            attachment.mSize * progress / 100);
                }
            }
            return values;
        }

        @Override
        public void loadAttachmentStatus(final long messageId, final long attachmentId,
                final int statusCode, final int progress) {
            debugTrace(LOG_TAG, "ServiceCallback for attachment #%d", attachmentId);

            // Record status and progress
            final DownloadRequest req = mDownloadsInProgress.get(attachmentId);
            if (req != null) {
                final long now = System.currentTimeMillis();
                debugTrace("ServiceCallback: status code changing from %d to %d",
                        req.mLastStatusCode, statusCode);
                debugTrace("ServiceCallback: progress changing from %d to %d",
                        req.mLastProgress,progress);
                debugTrace("ServiceCallback: last callback time changing from %d to %d",
                        req.mLastCallbackTime, now);

                // Update some state to keep track of the progress of the download
                req.mLastStatusCode = statusCode;
                req.mLastProgress = progress;
                req.mLastCallbackTime = now;

                // Update the attachment status in the provider.
                final Attachment attachment =
                        Attachment.restoreAttachmentWithId(AttachmentService.this, attachmentId);
                final ContentValues values = getAttachmentUpdateValues(attachment, statusCode,
                        progress);
                if (values.size() > 0) {
                    attachment.update(AttachmentService.this, values);
                }

                switch (statusCode) {
                    case EmailServiceStatus.IN_PROGRESS:
                        break;
                    default:
                        // It is assumed that any other error is either a success or an error
                        // Either way, the final updates to the DownloadRequest and attachment
                        // objects will be handed there.
                        LogUtils.d(LOG_TAG, "Attachment #%d is done", attachmentId);
                        endDownload(attachmentId, statusCode);
                        break;
                }
            } else {
                // The only way that we can get a callback from the service implementation for
                // an attachment that doesn't exist is if it was cancelled due to the
                // AttachmentWatchdog. This is a valid scenario and the Watchdog should have already
                // marked this attachment as failed/cancelled.
            }
        }
    }

    /**
     * Called directly by EmailProvider whenever an attachment is inserted or changed. Since this
     * call is being invoked on the UI thread, we need to make sure that the downloads are
     * happening in the background.
     * @param context the caller's context
     * @param id the attachment's id
     * @param flags the new flags for the attachment
     */
    public static void attachmentChanged(final Context context, final long id, final int flags) {
        LogUtils.d(LOG_TAG, "Attachment with id: %d will potentially be queued for download", id);
        // Throw this info into an intent and send it to the attachment service.
        final Intent intent = new Intent(context, AttachmentService.class);
        debugTrace("Calling startService with extras %d & %d", id, flags);
        intent.putExtra(EXTRA_ATTACHMENT_ID, id);
        intent.putExtra(EXTRA_ATTACHMENT_FLAGS, flags);
        context.startService(intent);
    }

    /**
     * The main entry point for this service, the attachment to download can be identified
     * by the EXTRA_ATTACHMENT extra in the intent.
     */
    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (sRunningService == null) {
            sRunningService = this;
        }
        if (intent != null) {
            // Let's add this id/flags combo to the list of potential attachments to process.
            final long attachment_id = intent.getLongExtra(EXTRA_ATTACHMENT_ID, -1);
            final int attachment_flags = intent.getIntExtra(EXTRA_ATTACHMENT_FLAGS, -1);
            if ((attachment_id >= 0) && (attachment_flags >= 0)) {
                sAttachmentChangedQueue.add(new long[]{attachment_id, attachment_flags});
                // Process the queue if we're in a wait
                kick();
            } else {
                debugTrace("Received an invalid intent w/o the required extras %d & %d",
                        attachment_id, attachment_flags);
            }
        } else {
            debugTrace("Received a null intent in onStartCommand");
        }
        return Service.START_STICKY;
    }

    /**
     * Most of the leg work is done by our service thread that is created when this
     * service is created.
     */
    @Override
    public void onCreate() {
        // Start up our service thread.
        new Thread(this, "AttachmentService").start();
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        debugTrace("Destroying AttachmentService object");
        dumpInProgressDownloads();

        // Mark this instance of the service as stopped. Our main loop for the AttachmentService
        // checks for this flag along with the AttachmentWatchdog.
        mStop = true;
        if (sRunningService != null) {
            // Kick it awake to get it to realize that we are stopping.
            kick();
            sRunningService = null;
        }
        if (mConnectivityManager != null) {
            mConnectivityManager.unregister();
            mConnectivityManager.stopWait();
            mConnectivityManager = null;
        }
    }

    /**
     * The main routine for our AttachmentService service thread.
     */
    @Override
    public void run() {
        // These fields are only used within the service thread
        mConnectivityManager = new EmailConnectivityManager(this, LOG_TAG);
        mAccountManagerStub = new AccountManagerStub(this);

        // Run through all attachments in the database that require download and add them to
        // the queue. This is the case where a previous AttachmentService may have been notified
        // to stop before processing everything in its queue.
        final int mask = Attachment.FLAG_DOWNLOAD_FORWARD | Attachment.FLAG_DOWNLOAD_USER_REQUEST;
        final Cursor c = getContentResolver().query(Attachment.CONTENT_URI,
                EmailContent.ID_PROJECTION, "(" + AttachmentColumns.FLAGS + " & ?) != 0",
                new String[] {Integer.toString(mask)}, null);
        try {
            LogUtils.d(LOG_TAG,
                    "Count of previous downloads to resume (from db): %d", c.getCount());
            while (c.moveToNext()) {
                final Attachment attachment = Attachment.restoreAttachmentWithId(
                        this, c.getLong(EmailContent.ID_PROJECTION_COLUMN));
                if (attachment != null) {
                    debugTrace("Attempting to download attachment #%d again.", attachment.mId);
                    onChange(this, attachment);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
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
                LogUtils.d(LOG_TAG, "AttachmentService has been instructed to stop");
                break;
            }

            // In advanced debug mode, let's look at the state of all in-progress downloads
            // after processQueue() runs.
            debugTrace("Downloads Map before processQueue");
            dumpInProgressDownloads();
            processQueue();
            debugTrace("Downloads Map after processQueue");
            dumpInProgressDownloads();

            if (mDownloadQueue.isEmpty() && (mDownloadsInProgress.size() < 1)) {
                LogUtils.d(LOG_TAG, "Shutting down service. No in-progress or pending downloads.");
                stopSelf();
                break;
            }
            debugTrace("Run() wait for mLock");
            synchronized(mLock) {
                try {
                    mLock.wait(PROCESS_QUEUE_WAIT_TIME);
                } catch (InterruptedException e) {
                    // That's ok; we'll just keep looping
                }
            }
            debugTrace("Run() got mLock");
        }

        // Unregister now that we're done
        // Make a local copy of the variable so we don't null-crash on service shutdown
        final EmailConnectivityManager ecm = mConnectivityManager;
        if (ecm != null) {
            ecm.unregister();
        }
    }

    /*
     * Function that kicks the service into action as it may be waiting for this object
     * as it processed the last round of attachments.
     */
    private void kick() {
        synchronized(mLock) {
            mLock.notify();
        }
    }

    /**
     * onChange is called by the AttachmentReceiver upon receipt of a valid notification from
     * EmailProvider that an attachment has been inserted or modified.  It's not strictly
     * necessary that we detect a deleted attachment, as the code always checks for the
     * existence of an attachment before acting on it.
     */
    public synchronized void onChange(final Context context, final Attachment att) {
        debugTrace("onChange() for Attachment: #%d", att.mId);
        DownloadRequest req = mDownloadQueue.findRequestById(att.mId);
        final long priority = getAttachmentPriority(att);
        if (priority == PRIORITY_NONE) {
            LogUtils.d(LOG_TAG, "Attachment #%d has no priority and will not be downloaded",
                    att.mId);
            // In this case, there is no download priority for this attachment
            if (req != null) {
                // If it exists in the map, remove it
                // NOTE: We don't yet support deleting downloads in progress
                mDownloadQueue.removeRequest(req);
            }
        } else {
            // Ignore changes that occur during download
            if (mDownloadsInProgress.containsKey(att.mId)) {
                debugTrace("Attachment #%d was already in the queue", att.mId);
                return;
            }
            // If this is new, add the request to the queue
            if (req == null) {
                LogUtils.d(LOG_TAG, "Attachment #%d is a new download request", att.mId);
                req = new DownloadRequest(context, att);
                final AttachmentInfo attachInfo = new AttachmentInfo(context, att);
                if (!attachInfo.isEligibleForDownload()) {
                    LogUtils.w(LOG_TAG, "Attachment #%d is not eligible for download", att.mId);
                    // We can't download this file due to policy, depending on what type
                    // of request we received, we handle the response differently.
                    if (((att.mFlags & Attachment.FLAG_DOWNLOAD_USER_REQUEST) != 0) ||
                            ((att.mFlags & Attachment.FLAG_POLICY_DISALLOWS_DOWNLOAD) != 0)) {
                        LogUtils.w(LOG_TAG, "Attachment #%d cannot be downloaded ever", att.mId);
                        // There are a couple of situations where we will not even allow this
                        // request to go in the queue because we can already process it as a
                        // failure.
                        // 1. The user explicitly wants to download this attachment from the
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
                mDownloadQueue.addRequest(req);
            }
            // TODO: If the request already existed, we'll update the priority (so that the time is
            // up-to-date); otherwise, create a new request
            LogUtils.d(LOG_TAG,
                    "Attachment #%d queued for download, priority: %d, created time: %d",
                    att.mId, req.mPriority, req.mCreatedTime);
        }
        // Process the queue if we're in a wait
        kick();
    }

    /**
     * Set the bits in the provider to mark this download as failed.
     * @param att The attachment that failed to download.
     */
    void markAttachmentAsFailed(final Attachment att) {
        final ContentValues cv = new ContentValues();
        final int flags = Attachment.FLAG_DOWNLOAD_FORWARD | Attachment.FLAG_DOWNLOAD_USER_REQUEST;
        cv.put(AttachmentColumns.FLAGS, att.mFlags &= ~flags);
        cv.put(AttachmentColumns.UI_STATE, AttachmentState.FAILED);
        att.update(this, cv);
    }

    /**
     * Set the bits in the provider to mark this download as completed.
     * @param att The attachment that was downloaded.
     */
    void markAttachmentAsCompleted(final Attachment att) {
        final ContentValues cv = new ContentValues();
        final int flags = Attachment.FLAG_DOWNLOAD_FORWARD | Attachment.FLAG_DOWNLOAD_USER_REQUEST;
        cv.put(AttachmentColumns.FLAGS, att.mFlags &= ~flags);
        cv.put(AttachmentColumns.UI_STATE, AttachmentState.SAVED);
        att.update(this, cv);
    }

    /**
     * Run through the AttachmentMap and find DownloadRequests that can be executed, enforcing
     * the limit on maximum downloads
     */
    synchronized void processQueue() {
        debugTrace("Processing changed queue, num entries: %d", sAttachmentChangedQueue.size());

        // First thing we need to do is process the list of "potential downloads" that we
        // added to sAttachmentChangedQueue
        long[] change = sAttachmentChangedQueue.poll();
        while (change != null) {
            // Process this change
            final long id = change[0];
            final long flags = change[1];
            final Attachment attachment = Attachment.restoreAttachmentWithId(this, id);
            if (attachment == null) {
                LogUtils.w(LOG_TAG, "Could not restore attachment #%d", id);
                continue;
            }
            attachment.mFlags = (int) flags;
            onChange(this, attachment);
            change = sAttachmentChangedQueue.poll();
        }

        debugTrace("Processing download queue, num entries: %d", mDownloadQueue.getSize());

        while (mDownloadsInProgress.size() < MAX_SIMULTANEOUS_DOWNLOADS) {
            final DownloadRequest req = mDownloadQueue.getNextRequest();
            if (req == null) {
                // No more queued requests?  We are done for now.
                break;
            }
            // Enforce per-account limit here
            if (getDownloadsForAccount(req.mAccountId) >= MAX_SIMULTANEOUS_DOWNLOADS_PER_ACCOUNT) {
                LogUtils.w(LOG_TAG, "Skipping #%d; maxed for acct %d",
                        req.mAttachmentId, req.mAccountId);
                continue;
            }
            if (Attachment.restoreAttachmentWithId(this, req.mAttachmentId) == null) {
                LogUtils.e(LOG_TAG, "Could not load attachment: #%d", req.mAttachmentId);
                continue;
            }
            if (!req.mInProgress) {
                final long currentTime = SystemClock.elapsedRealtime();
                if (req.mRetryCount > 0 && req.mRetryStartTime > currentTime) {
                    debugTrace("Need to wait before retrying attachment #%d", req.mAttachmentId);
                    mWatchdog.setWatchdogAlarm(this, CONNECTION_ERROR_RETRY_MILLIS,
                            CALLBACK_TIMEOUT);
                    continue;
                }
                // TODO: We try to gate ineligible downloads from entering the queue but its
                // always possible that they made it in here regardless in the future.  In a
                // perfect world, we would make it bullet proof with a check for eligibility
                // here instead/also.
                tryStartDownload(req);
            }
        }

        // Check our ability to be opportunistic regarding background downloads.
        final EmailConnectivityManager ecm = mConnectivityManager;
        if ((ecm == null) || !ecm.isAutoSyncAllowed() ||
                (ecm.getActiveNetworkType() != ConnectivityManager.TYPE_WIFI)) {
            // Only prefetch if it if connectivity is available, prefetch is enabled
            // and we are on WIFI
            LogUtils.d(LOG_TAG, "Skipping opportunistic downloads since WIFI is not available");
            return;
        }

        // Then, try opportunistic download of appropriate attachments
        final int availableBackgroundThreads =
                MAX_SIMULTANEOUS_DOWNLOADS - mDownloadsInProgress.size() - 1;
        if (availableBackgroundThreads < 1) {
            // We want to leave one spot open for a user requested download that we haven't
            // started processing yet.
            LogUtils.d(LOG_TAG, "Skipping opportunistic downloads, %d threads available",
                    availableBackgroundThreads);
            return;
        }

        debugTrace("Launching up to %d opportunistic downloads", availableBackgroundThreads);

        // We'll load up the newest 25 attachments that aren't loaded or queued
        // TODO: We are always looking for MAX_ATTACHMENTS_TO_CHECK, shouldn't this be
        // backgroundDownloads instead?  We should fix and test this.
        final Uri lookupUri = EmailContent.uriWithLimit(Attachment.CONTENT_URI,
                MAX_ATTACHMENTS_TO_CHECK);
        final Cursor c = this.getContentResolver().query(lookupUri,
                Attachment.CONTENT_PROJECTION,
                EmailContent.Attachment.PRECACHE_INBOX_SELECTION,
                null, AttachmentColumns._ID + " DESC");
        File cacheDir = this.getCacheDir();
        try {
            while (c.moveToNext()) {
                final Attachment att = new Attachment();
                att.restore(c);
                final Account account = Account.restoreAccountWithId(this, att.mAccountKey);
                if (account == null) {
                    // Clean up this orphaned attachment; there's no point in keeping it
                    // around; then try to find another one
                    debugTrace("Found orphaned attachment #%d", att.mId);
                    EmailContent.delete(this, Attachment.CONTENT_URI, att.mId);
                } else {
                    // Check that the attachment meets system requirements for download
                    // Note that there couple be policy that does not allow this attachment
                    // to be downloaded.
                    final AttachmentInfo info = new AttachmentInfo(this, att);
                    if (info.isEligibleForDownload()) {
                        // Either the account must be able to prefetch or this must be
                        // an inline attachment.
                        if (att.mContentId != null || canPrefetchForAccount(account, cacheDir)) {
                            final Integer tryCount = mAttachmentFailureMap.get(att.mId);
                            if (tryCount != null && tryCount > MAX_DOWNLOAD_RETRIES) {
                                // move onto the next attachment
                                LogUtils.w(LOG_TAG,
                                        "Too many failed attempts for attachment #%d ", att.mId);
                                continue;
                            }
                            // Start this download and we're done
                            final DownloadRequest req = new DownloadRequest(this, att);
                            tryStartDownload(req);
                            break;
                        }
                    } else {
                        // If this attachment was ineligible for download
                        // because of policy related issues, its flags would be set to
                        // FLAG_POLICY_DISALLOWS_DOWNLOAD and would not show up in the
                        // query results. We are most likely here for other reasons such
                        // as the inability to view the attachment. In that case, let's just
                        // skip it for now.
                        LogUtils.w(LOG_TAG, "Skipping attachment #%d, it is ineligible", att.mId);
                    }
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * Attempt to execute the DownloadRequest, enforcing the maximum downloads per account
     * parameter
     * @param req the DownloadRequest
     * @return whether or not the download was started
     */
    synchronized boolean tryStartDownload(final DownloadRequest req) {
        final EmailServiceProxy service = EmailServiceUtils.getServiceForAccount(
                AttachmentService.this, req.mAccountId);

        // Do not download the same attachment multiple times
        boolean alreadyInProgress = mDownloadsInProgress.get(req.mAttachmentId) != null;
        if (alreadyInProgress) {
            debugTrace("This attachment #%d is already in progress", req.mAttachmentId);
            return false;
        }

        try {
            startDownload(service, req);
        } catch (RemoteException e) {
            // TODO: Consider whether we need to do more in this case...
            // For now, fix up our data to reflect the failure
            cancelDownload(req);
        }
        return true;
    }

    /**
     * Do the work of starting an attachment download using the EmailService interface, and
     * set our watchdog alarm
     *
     * @param service the service handling the download
     * @param req the DownloadRequest
     * @throws RemoteException
     */
    private void startDownload(final EmailServiceProxy service, final DownloadRequest req)
            throws RemoteException {
        LogUtils.d(LOG_TAG, "Starting download for Attachment #%d", req.mAttachmentId);
        req.mStartTime = System.currentTimeMillis();
        req.mInProgress = true;
        mDownloadsInProgress.put(req.mAttachmentId, req);
        service.loadAttachment(mServiceCallback, req.mAccountId, req.mAttachmentId,
                req.mPriority != PRIORITY_FOREGROUND);
        mWatchdog.setWatchdogAlarm(this);
    }

    synchronized void cancelDownload(final DownloadRequest req) {
        LogUtils.d(LOG_TAG, "Cancelling download for Attachment #%d", req.mAttachmentId);
        req.mInProgress = false;
        mDownloadsInProgress.remove(req.mAttachmentId);
        // Remove the download from our queue, and then decide whether or not to add it back.
        mDownloadQueue.removeRequest(req);
        req.mRetryCount++;
        if (req.mRetryCount > CONNECTION_ERROR_MAX_RETRIES) {
            LogUtils.w(LOG_TAG, "Too many failures giving up on Attachment #%d", req.mAttachmentId);
        } else {
            debugTrace("Moving to end of queue, will retry #%d", req.mAttachmentId);
            // The time field of DownloadRequest is final, because it's unsafe to change it
            // as long as the DownloadRequest is in the DownloadSet. It's needed for the
            // comparator, so changing time would make the request unfindable.
            // Instead, we'll create a new DownloadRequest with an updated time.
            // This will sort at the end of the set.
            final DownloadRequest newReq = new DownloadRequest(req, SystemClock.elapsedRealtime());
            mDownloadQueue.addRequest(newReq);
        }
    }

    /**
     * Called when a download is finished; we get notified of this via our EmailServiceCallback
     * @param attachmentId the id of the attachment whose download is finished
     * @param statusCode the EmailServiceStatus code returned by the Service
     */
    synchronized void endDownload(final long attachmentId, final int statusCode) {
        LogUtils.d(LOG_TAG, "Finishing download #%d", attachmentId);

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
            LogUtils.w(LOG_TAG, "This attachment failed, adding #%d to failure map", attachmentId);
            mAttachmentFailureMap.put(attachmentId, downloadCount);
        }

        final DownloadRequest req = mDownloadQueue.findRequestById(attachmentId);
        if (statusCode == EmailServiceStatus.CONNECTION_ERROR) {
            // If this needs to be retried, just process the queue again
            if (req != null) {
                req.mRetryCount++;
                if (req.mRetryCount > CONNECTION_ERROR_MAX_RETRIES) {
                    // We are done, we maxed out our total number of tries.
                    // Not that we do not flag this attachment with any special flags so the
                    // AttachmentService will try to download this attachment again the next time
                    // that it starts up.
                    LogUtils.w(LOG_TAG, "Too many tried for connection errors, giving up #%d",
                            attachmentId);
                    mDownloadQueue.removeRequest(req);
                    // Note that we are not doing anything with the attachment right now
                    // We will annotate it later in this function if needed.
                } else if (req.mRetryCount > CONNECTION_ERROR_DELAY_THRESHOLD) {
                    // TODO: I'm not sure this is a great retry/backoff policy, but we're
                    // afraid of changing behavior too much in case something relies upon it.
                    // So now, for the first five errors, we'll retry immediately. For the next
                    // five tries, we'll add a ten second delay between each. After that, we'll
                    // give up.
                    LogUtils.w(LOG_TAG, "ConnectionError #%d, retried %d times, adding delay",
                            attachmentId, req.mRetryCount);
                    req.mInProgress = false;
                    req.mRetryStartTime = SystemClock.elapsedRealtime() +
                            CONNECTION_ERROR_RETRY_MILLIS;
                    mWatchdog.setWatchdogAlarm(this, CONNECTION_ERROR_RETRY_MILLIS,
                            CALLBACK_TIMEOUT);
                } else {
                    LogUtils.w(LOG_TAG, "ConnectionError for #%d, retried %d times, adding delay",
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
            mDownloadQueue.removeRequest(req);
        }

        if (ENABLE_ATTACHMENT_SERVICE_DEBUG > 0) {
            long secs = 0;
            if (req != null) {
                secs = (System.currentTimeMillis() - req.mCreatedTime) / 1000;
            }
            final String status = (statusCode == EmailServiceStatus.SUCCESS) ? "Success" :
                "Error " + statusCode;
            debugTrace("Download finished for attachment #%d; %d seconds from request, status: %s",
                    attachmentId, secs, status);
        }

        final Attachment attachment = Attachment.restoreAttachmentWithId(this, attachmentId);
        if (attachment != null) {
            final long accountId = attachment.mAccountKey;
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
                    EmailContent.delete(this, Attachment.CONTENT_URI, attachment.mId);
                    // TODO: Talk to UX about whether this is even worth doing
                    NotificationController nc = NotificationController.getInstance(this);
                    nc.showDownloadForwardFailedNotificationSynchronous(attachment);
                    deleted = true;
                    LogUtils.w(LOG_TAG, "Deleting forwarded attachment #%d for message #%d",
                        attachmentId, attachment.mMessageKey);
                }
                // If we're an attachment on forwarded mail, and if we're not still blocked,
                // try to send pending mail now (as mediated by MailService)
                if ((req != null) &&
                        !Utility.hasUnloadedAttachments(this, attachment.mMessageKey)) {
                    debugTrace("Downloads finished for outgoing msg #%d", req.mMessageId);
                    EmailServiceProxy service = EmailServiceUtils.getServiceForAccount(
                            this, accountId);
                    try {
                        service.sendMail(accountId);
                    } catch (RemoteException e) {
                        LogUtils.e(LOG_TAG, "RemoteException while trying to send message: #%d, %s",
                                req.mMessageId, e.toString());
                    }
                }
            }
            if (statusCode == EmailServiceStatus.MESSAGE_NOT_FOUND) {
                Message msg = Message.restoreMessageWithId(this, attachment.mMessageKey);
                if (msg == null) {
                    LogUtils.w(LOG_TAG, "Deleting attachment #%d with no associated message #%d",
                            attachment.mId, attachment.mMessageKey);
                    // If there's no associated message, delete the attachment
                    EmailContent.delete(this, Attachment.CONTENT_URI, attachment.mId);
                } else {
                    // If there really is a message, retry
                    // TODO: How will this get retried? It's still marked as inProgress?
                    LogUtils.w(LOG_TAG, "Retrying attachment #%d with associated message #%d",
                            attachment.mId, attachment.mMessageKey);
                    kick();
                    return;
                }
            } else if (!deleted) {
                // Clear the download flags, since we're done for now.  Note that this happens
                // only for non-recoverable errors.  When these occur for forwarded mail, we can
                // ignore it and continue; otherwise, it was either 1) a user request, in which
                // case the user can retry manually or 2) an opportunistic download, in which
                // case the download wasn't critical
                LogUtils.d(LOG_TAG, "Attachment #%d successfully downloaded!", attachment.mId);
                markAttachmentAsCompleted(attachment);
            }
        }
        // Process the queue
        kick();
    }

    /**
     * Count the number of running downloads in progress for this account
     * @param accountId the id of the account
     * @return the count of running downloads
     */
    synchronized int getDownloadsForAccount(final long accountId) {
        int count = 0;
        for (final DownloadRequest req: mDownloadsInProgress.values()) {
            if (req.mAccountId == accountId) {
                count++;
            }
        }
        return count;
    }

    /**
     * Calculate the download priority of an Attachment.  A priority of zero means that the
     * attachment is not marked for download.
     * @param att the Attachment
     * @return the priority key of the Attachment
     */
    private static int getAttachmentPriority(final Attachment att) {
        int priorityClass = PRIORITY_NONE;
        final int flags = att.mFlags;
        if ((flags & Attachment.FLAG_DOWNLOAD_FORWARD) != 0) {
            priorityClass = PRIORITY_SEND_MAIL;
        } else if ((flags & Attachment.FLAG_DOWNLOAD_USER_REQUEST) != 0) {
            priorityClass = PRIORITY_FOREGROUND;
        }
        return priorityClass;
    }

    /**
     * Determine whether an attachment can be prefetched for the given account based on
     * total download size restrictions tied to the account.
     * @return true if download is allowed, false otherwise
     */
    public boolean canPrefetchForAccount(final Account account, final File dir) {
        // Check account, just in case
        if (account == null) return false;

        // First, check preference and quickly return if prefetch isn't allowed
        if ((account.mFlags & Account.FLAGS_BACKGROUND_ATTACHMENTS) == 0) {
            debugTrace("Prefetch is not allowed for this account: %d", account.getId());
            return false;
        }

        final long totalStorage = dir.getTotalSpace();
        final long usableStorage = dir.getUsableSpace();
        final long minAvailable = (long)(totalStorage * PREFETCH_MINIMUM_STORAGE_AVAILABLE);

        // If there's not enough overall storage available, stop now
        if (usableStorage < minAvailable) {
            debugTrace("Not enough physical storage for prefetch");
            return false;
        }

        final int numberOfAccounts = mAccountManagerStub.getNumberOfAccounts();
        // Calculate an even per-account storage although it would make a lot of sense to not
        // do this as you may assign more storage to your corporate account rather than a personal
        // account.
        final long perAccountMaxStorage =
                (long)(totalStorage * PREFETCH_MAXIMUM_ATTACHMENT_STORAGE / numberOfAccounts);

        // Retrieve our idea of currently used attachment storage; since we don't track deletions,
        // this number is the "worst case".  If the number is greater than what's allowed per
        // account, we walk the directory to determine the actual number.
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
            // Cache the value. No locking here since this is a concurrent collection object.
            mAttachmentStorageMap.put(account.mId, accountStorage);
        }

        // Return true if we're using less than the maximum per account
        if (accountStorage >= perAccountMaxStorage) {
            debugTrace("Prefetch not allowed for account %d; used: %d, limit %d",
                    account.mId, accountStorage, perAccountMaxStorage);
            return false;
        }
        return true;
    }

    boolean isConnected() {
        if (mConnectivityManager != null) {
            return mConnectivityManager.hasConnectivity();
        }
        return false;
    }

    // For Debugging.
    synchronized public void dumpInProgressDownloads() {
        if (ENABLE_ATTACHMENT_SERVICE_DEBUG < 1) {
            LogUtils.d(LOG_TAG, "Advanced logging not configured.");
        }
        for (final DownloadRequest req : mDownloadsInProgress.values()) {
            LogUtils.d(LOG_TAG, "--BEGIN DownloadRequest DUMP--");
            LogUtils.d(LOG_TAG, "Account: #%d", req.mAccountId);
            LogUtils.d(LOG_TAG, "Message: #%d", req.mMessageId);
            LogUtils.d(LOG_TAG, "Attachment: #%d", req.mAttachmentId);
            LogUtils.d(LOG_TAG, "Created Time: %d", req.mCreatedTime);
            LogUtils.d(LOG_TAG, "Priority: %d", req.mPriority);
            if (req.mInProgress == true) {
                LogUtils.d(LOG_TAG, "This download is in progress");
            } else {
                LogUtils.d(LOG_TAG, "This download is not in progress");
            }
            LogUtils.d(LOG_TAG, "Start Time: %d", req.mStartTime);
            LogUtils.d(LOG_TAG, "Retry Count: %d", req.mRetryCount);
            LogUtils.d(LOG_TAG, "Retry Start Tiome: %d", req.mRetryStartTime);
            LogUtils.d(LOG_TAG, "Last Status Code: %d", req.mLastStatusCode);
            LogUtils.d(LOG_TAG, "Last Progress: %d", req.mLastProgress);
            LogUtils.d(LOG_TAG, "Last Callback Time: %d", req.mLastCallbackTime);
            LogUtils.d(LOG_TAG, "------------------------------");
        }
    }


    @Override
    public void dump(final FileDescriptor fd, final PrintWriter pw, final String[] args) {
        pw.println("AttachmentService");
        final long time = System.currentTimeMillis();
        synchronized(mDownloadQueue) {
            pw.println("  Queue, " + mDownloadQueue.getSize() + " entries");
            // If you iterate over the queue either via iterator or collection, they are not
            // returned in any particular order. With all things being equal its better to go with
            // a collection to avoid any potential ConcurrentModificationExceptions.
            // If we really want this sorted, we can sort it manually since performance isn't a big
            // concern with this debug method.
            for (final DownloadRequest req : mDownloadQueue.mRequestMap.values()) {
                pw.println("    Account: " + req.mAccountId + ", Attachment: " + req.mAttachmentId);
                pw.println("      Priority: " + req.mPriority + ", Time: " + req.mCreatedTime +
                        (req.mInProgress ? " [In progress]" : ""));
                final Attachment att = Attachment.restoreAttachmentWithId(this, req.mAttachmentId);
                if (att == null) {
                    pw.println("      Attachment not in database?");
                } else if (att.mFileName != null) {
                    final String fileName = att.mFileName;
                    final String suffix;
                    final int lastDot = fileName.lastIndexOf('.');
                    if (lastDot >= 0) {
                        suffix = fileName.substring(lastDot);
                    } else {
                        suffix = "[none]";
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
    AccountManagerStub mAccountManagerStub;
    private final HashMap<Long, Intent> mAccountServiceMap = new HashMap<Long, Intent>();

    void addServiceIntentForTest(final long accountId, final Intent intent) {
        mAccountServiceMap.put(accountId, intent);
    }

    /**
     * We only use the getAccounts() call from AccountManager, so this class wraps that call and
     * allows us to build a mock account manager stub in the unit tests
     */
    static class AccountManagerStub {
        private int mNumberOfAccounts;
        private final AccountManager mAccountManager;

        AccountManagerStub(final Context context) {
            if (context != null) {
                mAccountManager = AccountManager.get(context);
            } else {
                mAccountManager = null;
            }
        }

        int getNumberOfAccounts() {
            if (mAccountManager != null) {
                return mAccountManager.getAccounts().length;
            } else {
                return mNumberOfAccounts;
            }
        }

        void setNumberOfAccounts(final int numberOfAccounts) {
            mNumberOfAccounts = numberOfAccounts;
        }
    }
}
