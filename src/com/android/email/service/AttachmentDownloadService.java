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

import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.activity.Welcome;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Message;
import com.android.exchange.SyncManager;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentFilter.MalformedMimeTypeException;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.RemoteViews;

import java.io.File;
import java.util.HashMap;
import java.util.TreeMap;

public class AttachmentDownloadService extends Service implements Runnable {
    public static final String TAG = "AttachmentService";

    // Our idle time, waiting for notifications; this is something of a failsafe
    private static final int PROCESS_QUEUE_WAIT_TIME = 30 * ((int)DateUtils.MINUTE_IN_MILLIS);

    private static final long PRIORITY_NONE = -1;
    @SuppressWarnings("unused")
    private static final long PRIORITY_LOW = 0;
    private static final long PRIORITY_NORMAL = 1;
    private static final long PRIORITY_HIGH = 2;

    // We can try various values here; I think 2 is completely reasonable as a first pass
    private static final int MAX_SIMULTANEOUS_DOWNLOADS = 2;
    // Limit on the number of simultaneous downloads per account
    // Note that a limit of 1 is currently enforced by both Services (MailService and Controller)
    private static final int MAX_SIMULTANEOUS_DOWNLOADS_PER_ACCOUNT = 1;

    private static AttachmentDownloadService sRunningService = null;

    private AttachmentReceiver mAttachmentReceiver;
    private Context mContext;
    private final AttachmentMap mAttachmentMap = new AttachmentMap();
    private final HashMap<Long, Class<? extends Service>> mAccountServiceMap =
        new HashMap<Long, Class<? extends Service>>();
    private final ServiceCallback mServiceCallback = new ServiceCallback();
    private final Object mLock = new Object();
    private volatile boolean mStop = false;

    private static class DownloadRequest {
        long attachmentId;
        long messageId = -1;
        long accountId;
        boolean inProgress = false;

        private DownloadRequest(Context context, Attachment attachment) {
            attachmentId = attachment.mId;
            Message msg = Message.restoreMessageWithId(context, attachment.mMessageKey);
            if (msg != null) {
                accountId = msg.mAccountKey;
                messageId = msg.mId;
            }
        }
    }

    /**
     * The AttachmentMap is a TreeMap sorted by "priority key", which is determined first by the
     * priority class (e.g. low, high, etc.) and the time of the request.  Higher priority requests
     * are always processed first; among equals, the oldest request is processed first.  The
     * priority key represents this ordering.  Note: All methods that change the attachment map are
     * synchronized on the map itself
     */
    private class AttachmentMap extends TreeMap<Long, DownloadRequest> {
        private static final long serialVersionUID = 1L;

        private final HashMap<Long, DownloadRequest> mDownloadsInProgress =
            new HashMap<Long, DownloadRequest>();

        /**
         * Calculate the download priority of an Attachment.  A priority of zero means that the
         * attachment is not marked for download.
         * @param att the Attachment
         * @return the priority key of the Attachment
         */
        private long getPriorityKey(Attachment att) {
            long priorityClass = PRIORITY_NONE;
            int flags = att.mFlags;
            if ((flags & Attachment.FLAG_DOWNLOAD_FORWARD) != 0) {
                priorityClass = PRIORITY_NORMAL;
            } else if ((flags & Attachment.FLAG_DOWNLOAD_USER_REQUEST) != 0) {
                priorityClass = PRIORITY_HIGH;
            } else {
                return 0;
            }
            return (priorityKey(priorityClass, System.currentTimeMillis()));
        }

        /**
         * onChange is called by the AttachmentReceiver upon receipt of a valid notification from
         * EmailProvider that an attachment has been inserted or modified.  It's not strictly
         * necessary that we detect a deleted attachment, as the code always checks for the
         * existence of an attachment before acting on it.
         */
        public synchronized void onChange(Attachment att) {
            long attKey = findPriorityKey(att.mId);
            long priorityKey = getPriorityKey(att);
            if (priorityKey == 0) {
                if (Email.DEBUG) {
                    Log.d(TAG, "== Attachment changed: " + att.mId);
                }
                // In this case, there is no download priority for this attachment
                if (attKey != 0) {
                    // If it exists in the map, remove it and try to stop any download in progress
                    // NOTE: We don't yet support deleting downloads in progress
                    if (Email.DEBUG) {
                        Log.d(TAG, "== Attachment " + att.mId + " was in queue, removing");
                    }
                    remove(attKey, true);
                }
            } else {
                // Ignore changes that occur during download
                if (mDownloadsInProgress.containsKey(att.mId)) return;
                // Remove from the map, but don't stop a download in progress
                DownloadRequest req = remove(attKey, false);
                if (req == null) {
                    req = new DownloadRequest(mContext, att);
                }
                // If the request already existed, we'll update the priority (so that the time is
                // up-to-date); otherwise, we create a new request
                if (Email.DEBUG) {
                    Log.d(TAG, "== Download queued for attachment " + att.mId + ", class " +
                            priorityClass(priorityKey) + ", priority time " +
                            priorityTime(priorityKey));
                }
                // Store the request away
                put(priorityKey, req);
            }
            // Process the queue if we're in a wait
            kick();
        }

        /**
         * Remove a DownloadRequest from the queue, given its priority key
         * @param priorityKey the priority key to remove (a Long)
         * @param stopDownload whether we should try to stop an in-progress download of the
         * attachment with this priority key (not implemented)
         * @return the DownloadRequest for this priority key (or null, if none)
         */
        public synchronized DownloadRequest remove(Object priorityKey, boolean stopDownload) {
            DownloadRequest req = remove(priorityKey);
            if ((req != null) && req.inProgress && stopDownload) {
                // TODO: Stop download
                if (Email.DEBUG) {
                    Log.d(TAG, "== Download of " + req.attachmentId + " stopped; NOT implemented");
                }
            }
            return req;
        }

        /**
         * Find the priority key of a queued DownloadRequest, given the attachment's id
         * @param id the id of the attachment
         * @return the priority key for that attachment (or zero, if none)
         */
        private synchronized long findPriorityKey(long id) {
            for (Entry<Long, DownloadRequest> entry: entrySet()) {
                if (entry.getValue().attachmentId == id) {
                    return entry.getKey();
                }
            }
            return 0;
        }

        /**
         * Run through the AttachmentMap and find DownloadRequests that can be executed, enforcing
         * the limit on maximum downloads
         */
        private synchronized void processQueue() {
            if (Email.DEBUG) {
                Log.d(TAG, "== Checking attachment queue, " + mAttachmentMap.size() + " entries");
            }
            Entry<Long, DownloadRequest> entry = mAttachmentMap.lastEntry();
            // First, start up any required downloads, in priority order
            while ((entry != null) && (mDownloadsInProgress.size() < MAX_SIMULTANEOUS_DOWNLOADS)) {
                DownloadRequest req = entry.getValue();
                if (!req.inProgress) {
                    mAttachmentMap.tryStartDownload(req);
                }
                entry = mAttachmentMap.lowerEntry(entry.getKey());
            }
            // Then, try opportunistic download of appropriate attachments
            int backgroundDownloads = MAX_SIMULTANEOUS_DOWNLOADS - mDownloadsInProgress.size();
            if (backgroundDownloads > 0) {
                // TODO Code for background downloads here
                if (Email.DEBUG) {
                    Log.d(TAG, "== We'd look for up to " + backgroundDownloads +
                            " background download(s) now...");
                }
            }
        }

        /**
         * Count the number of running downloads in progress for this account
         * @param accountId the id of the account
         * @return the count of running downloads
         */
        private int downloadsForAccount(long accountId) {
            int count = 0;
            for (DownloadRequest req: mDownloadsInProgress.values()) {
                if (req.accountId == accountId) {
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
        private synchronized boolean tryStartDownload(DownloadRequest req) {
            // Enforce per-account limit
            if (downloadsForAccount(req.accountId) >= MAX_SIMULTANEOUS_DOWNLOADS_PER_ACCOUNT) {
                if (Email.DEBUG) {
                    Log.d(TAG, "== Skip #" + req.attachmentId + "; maxed for acct #" +
                            req.accountId);
                }
                return false;
            }
            Class<? extends Service> serviceClass = getServiceClassForAccount(req.accountId);
            if (serviceClass == null) return false;
            EmailServiceProxy proxy =
                new EmailServiceProxy(mContext, serviceClass, mServiceCallback);
            try {
                File file = AttachmentProvider.getAttachmentFilename(mContext, req.accountId,
                        req.attachmentId);
                if (Email.DEBUG) {
                    Log.d(TAG, ">> Starting download for attachment #" + req.attachmentId);
                }
                proxy.loadAttachment(req.attachmentId, file.getAbsolutePath(),
                        AttachmentProvider.getAttachmentUri(req.accountId, req.attachmentId)
                            .toString());
                mDownloadsInProgress.put(req.attachmentId, req);
                req.inProgress = true;
            } catch (RemoteException e) {
                // TODO: Consider whether we need to do more in this case...
                // For now, fix up our data to reflect the failure
                mDownloadsInProgress.remove(req.attachmentId);
                req.inProgress = false;
            }
            return true;
        }

        /**
         * Called when a download is finished; we get notified of this via our EmailServiceCallback
         * @param attachmentId the id of the attachment whose download is finished
         * @param statusCode the EmailServiceStatus code returned by the Service
         */
        private synchronized void endDownload(long attachmentId, int statusCode) {
            // Say we're no longer downloading this
            mDownloadsInProgress.remove(attachmentId);
            long priorityKey = mAttachmentMap.findPriorityKey(attachmentId);
            if (statusCode == EmailServiceStatus.CONNECTION_ERROR) {
                // If this needs to be retried, just process the queue again
                if (Email.DEBUG) {
                    Log.d(TAG, "== The download for attachment #" + attachmentId +
                            " will be retried");
                }
                DownloadRequest req = mAttachmentMap.get(priorityKey);
                if (req != null) {
                    req.inProgress = false;
                }
                kick();
                return;
            }
            DownloadRequest req = mAttachmentMap.remove(priorityKey);
            if (Email.DEBUG) {
                long secs = (priorityTime(System.currentTimeMillis()) -
                        priorityKeyToSystemTime(priorityKey)) / 1000;
                String status = (statusCode == EmailServiceStatus.SUCCESS) ? "Success" :
                    "Error " + statusCode;
                Log.d(TAG, "<< Download finished for attachment #" + attachmentId + "; " + secs +
                           " seconds from request, status: " + status);
            }
            Attachment attachment = Attachment.restoreAttachmentWithId(mContext, attachmentId);
            if (attachment != null) {
                boolean deleted = false;
                if ((attachment.mFlags & Attachment.FLAG_DOWNLOAD_FORWARD) != 0) {
                    if (statusCode == EmailServiceStatus.ATTACHMENT_NOT_FOUND) {
                        // If this is a forwarding download, and the attachment doesn't exist (or 
                        // can't be downloaded) delete it from the outgoing message, lest that
                        // message never get sent
                        EmailContent.delete(mContext, Attachment.CONTENT_URI, attachment.mId);
                        // TODO: Talk to UX about whether this is even worth doing
                        showDownloadForwardFailedNotification(attachment);
                        deleted = true;
                    }
                    // If we're an attachment on forwarded mail, and if we're not still blocked,
                    // try to send pending mail now (as mediated by MailService)
                    if (!Utility.hasUnloadedAttachments(mContext, attachment.mMessageKey)) {
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

    private static int systemTimeToPriorityTime(long systemTime) {
        return Integer.MAX_VALUE - priorityTime(systemTime);
    }

    private static long priorityKeyToSystemTime(long priorityKey) {
        return Integer.MAX_VALUE - priorityTime(priorityKey);
    }

    private static int priorityTime(long priorityKeyOrSystemTime) {
        return (int)(priorityKeyOrSystemTime & 0xFFFFFFFF);
    }

    private static long priorityClass(long priorityKey) {
        return priorityKey >> 32;
    }

    private static long priorityKey(long priorityClass, long timeInMillis) {
        // High 32 bits = priority class
        // Low 32 bits = priority time
        return (priorityClass<<32) | systemTimeToPriorityTime(timeInMillis);        
    }

    private void kick() {
        synchronized(mLock) {
            mLock.notify();
        }
    }

    /**
     * The AttachmentReceiver handles broadcasts from EmailProvider when an attachment is inserted
     * or updated.  Assuming that the attachment still exists, we just call the AttachmentMap's
     * onChange() method
     */
    public class AttachmentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final Uri attachmentUri = intent.getData();
            String action = intent.getAction();
            if (action.equals(EmailProvider.ACTION_ATTACHMENT_UPDATED)) {
                // We need to look at the flags and see if loading is appropriate
                final int flags =
                    intent.getIntExtra(EmailProvider.ATTACHMENT_UPDATED_EXTRA_FLAGS, -1);
                // If the flags didn't change, we're done
                if (flags == -1) return;
                new Thread(new Runnable() {
                    public void run() {
                        long id = Long.parseLong(attachmentUri.getLastPathSegment());
                        Attachment attachment =
                            Attachment.restoreAttachmentWithId(AttachmentDownloadService.this, id);
                        if (attachment != null) {
                            // Store the flags we got from EmailProvider; given that all of this
                            // activity is asynchronous, we need to use the newest data from
                            // EmailProvider
                            attachment.mFlags = flags;
                            mAttachmentMap.onChange(attachment);
                        }
                    }}).run();
            }
        }
    }

    /**
     * We use an EmailServiceCallback to keep track of the progress of downloads.  These callbacks
     * come from either Controller (IMAP) or SyncManager (EAS).  Note that we only implement the
     * single callback that's defined by the EmailServiceCallback interface.
     */
    private class ServiceCallback extends IEmailServiceCallback.Stub {
        public void loadAttachmentStatus(long messageId, long attachmentId, int statusCode,
                int progress) {
            if (Email.DEBUG) {
                String code;
                switch(statusCode) {
                    case EmailServiceStatus.SUCCESS:
                        code = "Success";
                        break;
                    case EmailServiceStatus.IN_PROGRESS:
                        code = "In progress";
                        break;
                    default:
                        code = Integer.toString(statusCode);
                }
                Log.d(TAG, "loadAttachmentStatus, id = " + attachmentId + " code = "+ code +
                        ", " + progress + "%");
            }
            // The only thing we're interested in here is whether the download is finished and, if
            // so, what the result was.
            switch (statusCode) {
                case EmailServiceStatus.IN_PROGRESS:
                    break;
                default:
                    mAttachmentMap.endDownload(attachmentId, statusCode);
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
     * Alert the user that an attachment couldn't be forwarded.  This is a very unusual case, and
     * perhaps we shouldn't even send a notification. For now, it's helpful for debugging.
     * Note the STOPSHIP below...
     */
    void showDownloadForwardFailedNotification(Attachment att) {
        // STOPSHIP: Tentative UI; if we use a notification, replace this text with a resource
        RemoteViews contentView = new RemoteViews(getPackageName(),
                R.layout.attachment_forward_failed_notification);
        contentView.setImageViewResource(R.id.image, R.drawable.ic_email_attachment);
        contentView.setTextViewText(R.id.text,
                getString(R.string.forward_download_failed_notification, att.mFileName));
        Notification n = new Notification(R.drawable.stat_notify_email_generic,
                getString(R.string.forward_download_failed_ticker), System.currentTimeMillis());
        n.contentView = contentView;
        Intent i = new Intent(mContext, Welcome.class);
        PendingIntent pending = PendingIntent.getActivity(mContext, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT);
        n.contentIntent = pending;
        n.flags = Notification.FLAG_AUTO_CANCEL;
        NotificationManager nm =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(MailService.NOTIFICATION_ID_WARNING, n);
    }

    /**
     * Return the class of the service used by the account type of the provided account id.  We
     * cache the results to avoid repeated database access
     * @param accountId the id of the account
     * @return the service class for the account
     */
    private synchronized Class<? extends Service> getServiceClassForAccount(long accountId) {
        // TODO: We should have some more data-driven way of determining the service class. I'd
        // suggest adding an attribute in the stores.xml file
        Class<? extends Service> serviceClass = mAccountServiceMap.get(accountId);
        if (serviceClass == null) {
            String protocol = Account.getProtocol(mContext, accountId);
            if (protocol.equals("eas")) {
                serviceClass = SyncManager.class;
            } else {
                // Uncomment this when Controller has been made a Service
                //serviceClass = Controller.class;
            }
            mAccountServiceMap.put(accountId, serviceClass);
        }
        return serviceClass;
    }

    private void onChange(Attachment att) {
        mAttachmentMap.onChange(att);
    }

    private boolean isQueued(long attachmentId) {
        return mAttachmentMap.findPriorityKey(attachmentId) != 0;
    }

    private boolean dequeue(long attachmentId) {
        long priority = mAttachmentMap.findPriorityKey(attachmentId);
        if (priority == 0) return false;
        DownloadRequest req = mAttachmentMap.remove(priority);
        return req != null;
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

    /**
     * Called directly by EmailProvider whenever an attachment is inserted or changed
     * @param id the attachment's id
     * @param flags the new flags for the attachment
     */
    public static void attachmentChanged(final long id, final int flags) {
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

    public void run() {
        mContext = this;
        // Set up our receiver for EmailProvider attachment updates
        mAttachmentReceiver = new AttachmentReceiver();
        try {
            getApplicationContext().registerReceiver(mAttachmentReceiver,
                    new IntentFilter(EmailProvider.ACTION_ATTACHMENT_UPDATED,
                            EmailProvider.EMAIL_ATTACHMENT_MIME_TYPE));
        } catch (MalformedMimeTypeException e1) {
            // Since we're passing in a constant mime type, this can't happen
        }

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
                    mAttachmentMap.onChange(attachment);
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
            mAttachmentMap.processQueue();
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
}
