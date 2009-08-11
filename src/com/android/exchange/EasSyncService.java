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

import com.android.email.R;
import com.android.email.activity.AccountFolderList;
import com.android.email.mail.AuthenticationFailedException;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.AttachmentColumns;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.exchange.adapter.AbstractSyncAdapter;
import com.android.exchange.adapter.ContactsSyncAdapter;
import com.android.exchange.adapter.EmailSyncAdapter;
import com.android.exchange.adapter.FolderSyncParser;
import com.android.exchange.adapter.PingParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.exchange.adapter.Parser.EasParserException;
import com.android.exchange.utility.Base64;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.os.RemoteException;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;

public class EasSyncService extends AbstractSyncService {

    private static final String EMAIL_WINDOW_SIZE = "5";
    public static final String PIM_WINDOW_SIZE = "20";
    private static final String WHERE_ACCOUNT_KEY_AND_SERVER_ID =
        MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.SERVER_ID + "=?";
    private static final String WHERE_ACCOUNT_AND_SYNC_INTERVAL_PING =
        MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.SYNC_INTERVAL +
        '=' + Account.CHECK_INTERVAL_PING;
    private static final String AND_FREQUENCY_PING_PUSH_AND_NOT_ACCOUNT_MAILBOX = " AND " +
        MailboxColumns.SYNC_INTERVAL + " IN (" + Account.CHECK_INTERVAL_PING +
        ',' + Account.CHECK_INTERVAL_PUSH + ") AND " + MailboxColumns.TYPE + "!=\"" +
        Mailbox.TYPE_EAS_ACCOUNT_MAILBOX + '\"';
    private static final String WHERE_PUSH_HOLD_NOT_ACCOUNT_MAILBOX =
        MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.SYNC_INTERVAL +
        '=' + Account.CHECK_INTERVAL_PUSH_HOLD;

    static private final int CHUNK_SIZE = 16*1024;

    static private final String PING_COMMAND = "Ping";
    static private final int COMMAND_TIMEOUT = 20*SECONDS;
    static private final int PING_COMMAND_TIMEOUT = 20*MINUTES;

    // For mobile, we use a 5 minute timeout (less a few seconds)
    static private final String PING_HEARTBEAT_MOBILE = "295";
    // For wifi, we use a 15 minute timeout
    static private final String PING_HEARTBEAT_WIFI = "900";

    // Fallbacks (in minutes) for ping loop failures
    static private final int MAX_PING_FAILURES = 2;
    static private final int PING_FALLBACK_INBOX = 5;
    static private final int PING_FALLBACK_PIM = 30;

    // Reasonable default
    String mProtocolVersion = "2.5";
    public Double mProtocolVersionDouble;
    private String mDeviceId = null;
    private String mDeviceType = "Android";
    AbstractSyncAdapter mTarget;
    String mAuthString = null;
    String mCmdString = null;
    public String mHostAddress;
    public String mUserName;
    public String mPassword;
    String mDomain = null;
    boolean mSentCommands;
    boolean mIsIdle = false;
    boolean mSsl = true;
    public Context mContext;
    public ContentResolver mContentResolver;
    String[] mBindArguments = new String[2];
    InputStream mPendingPartInputStream = null;
    HttpPost mPendingPost = null;
    int mNetworkType;

    public EasSyncService(Context _context, Mailbox _mailbox) {
        super(_context, _mailbox);
        mContext = _context;
        mContentResolver = _context.getContentResolver();
        HostAuth ha = HostAuth.restoreHostAuthWithId(_context, mAccount.mHostAuthKeyRecv);
        mSsl = (ha.mFlags & HostAuth.FLAG_SSL) != 0;
    }

    private EasSyncService(String prefix) {
        super(prefix);
    }

    public EasSyncService() {
        this("EAS Validation");
    }

    @Override
    public void ping() {
        userLog("We've been pinged!");
        Object synchronizer = getSynchronizer();
        synchronized (synchronizer) {
            synchronizer.notify();
        }
    }

    @Override
    public void stop() {
        mStop = true;
        synchronized(getSynchronizer()) {
            if (mPendingPost != null) {
                mPendingPost.abort();
            }
        }
    }

    @Override
    public int getSyncStatus() {
        return 0;
    }

    private boolean isAuthError(int code) {
        return (code == HttpURLConnection.HTTP_UNAUTHORIZED
                || code == HttpURLConnection.HTTP_FORBIDDEN
                || code == HttpURLConnection.HTTP_INTERNAL_ERROR);
    }

    @Override
    public void validateAccount(String hostAddress, String userName, String password, int port,
            boolean ssl, Context context) throws MessagingException {
        try {
            userLog("Testing EAS: " + hostAddress + ", " + userName + ", ssl = " + ssl);
            EasSyncService svc = new EasSyncService("%TestAccount%");
            svc.mContext = context;
            svc.mHostAddress = hostAddress;
            svc.mUserName = userName;
            svc.mPassword = password;
            svc.mSsl = ssl;
            svc.mDeviceId = SyncManager.getDeviceId();
            HttpResponse resp = svc.sendHttpClientOptions();
            int code = resp.getStatusLine().getStatusCode();
            userLog("Validation (OPTIONS) response: " + code);
            if (code == HttpURLConnection.HTTP_OK) {
                // No exception means successful validation
                userLog("Validation successful");
                return;
            }
            if (isAuthError(code)) {
                userLog("Authentication failed");
                throw new AuthenticationFailedException("Validation failed");
            } else {
                // TODO Need to catch other kinds of errors (e.g. policy) For now, report the code.
                userLog("Validation failed, reporting I/O error: " + code);
                throw new MessagingException(MessagingException.IOERROR);
            }
        } catch (IOException e) {
            userLog("IOException caught, reporting I/O error: " + e.getMessage());
            throw new MessagingException(MessagingException.IOERROR);
        }

    }

    private void doStatusCallback(long messageId, long attachmentId, int status) {
        try {
            SyncManager.callback().loadAttachmentStatus(messageId, attachmentId, status, 0);
        } catch (RemoteException e) {
            // No danger if the client is no longer around
        }
    }

    private void doProgressCallback(long messageId, long attachmentId, int progress) {
        try {
            SyncManager.callback().loadAttachmentStatus(messageId, attachmentId,
                    EmailServiceStatus.IN_PROGRESS, progress);
        } catch (RemoteException e) {
            // No danger if the client is no longer around
        }
    }

    public File createUniqueFileInternal(String dir, String filename) {
        File directory;
        if (dir == null) {
            directory = mContext.getFilesDir();
        } else {
            directory = new File(dir);
        }
        if (!directory.exists()) {
            directory.mkdirs();
        }
        File file = new File(directory, filename);
        if (!file.exists()) {
            return file;
        }
        // Get the extension of the file, if any.
        int index = filename.lastIndexOf('.');
        String name = filename;
        String extension = "";
        if (index != -1) {
            name = filename.substring(0, index);
            extension = filename.substring(index);
        }
        for (int i = 2; i < Integer.MAX_VALUE; i++) {
            file = new File(directory, name + '-' + i + extension);
            if (!file.exists()) {
                return file;
            }
        }
        return null;
    }

    /**
     * Loads an attachment, based on the PartRequest passed in.  The PartRequest is basically our
     * wrapper for Attachment
     * @param req the part (attachment) to be retrieved
     * @throws IOException
     */
    protected void getAttachment(PartRequest req) throws IOException {
        Attachment att = req.att;
        Message msg = Message.restoreMessageWithId(mContext, att.mMessageKey);
        doProgressCallback(msg.mId, att.mId, 0);
        DefaultHttpClient client = new DefaultHttpClient();
        String us = makeUriString("GetAttachment", "&AttachmentName=" + att.mLocation);
        HttpPost method = new HttpPost(URI.create(us));
        method.setHeader("Authorization", mAuthString);

        HttpResponse res = client.execute(method);
        int status = res.getStatusLine().getStatusCode();
        if (status == HttpURLConnection.HTTP_OK) {
            HttpEntity e = res.getEntity();
            int len = (int)e.getContentLength();
            String type = e.getContentType().getValue();
            InputStream is = res.getEntity().getContent();
            File f = (req.destination != null)
                    ? new File(req.destination)
                    : createUniqueFileInternal(req.destination, att.mFileName);
            if (f != null) {
                // Ensure that the target directory exists
                File destDir = f.getParentFile();
                if (!destDir.exists()) {
                    destDir.mkdirs();
                }
                FileOutputStream os = new FileOutputStream(f);
                if (len > 0) {
                    try {
                        mPendingPartRequest = req;
                        mPendingPartInputStream = is;
                        byte[] bytes = new byte[CHUNK_SIZE];
                        int length = len;
                        while (len > 0) {
                            int n = (len > CHUNK_SIZE ? CHUNK_SIZE : len);
                            int read = is.read(bytes, 0, n);
                            os.write(bytes, 0, read);
                            len -= read;
                            int pct = ((length - len) * 100 / length);
                            doProgressCallback(msg.mId, att.mId, pct);
                        }
                    } finally {
                        mPendingPartRequest = null;
                        mPendingPartInputStream = null;
                    }
                }
                os.flush();
                os.close();

                // EmailProvider will throw an exception if we try to update an unsaved attachment
                if (att.isSaved()) {
                    String contentUriString = (req.contentUriString != null)
                            ? req.contentUriString
                            : "file://" + f.getAbsolutePath();
                    ContentValues cv = new ContentValues();
                    cv.put(AttachmentColumns.CONTENT_URI, contentUriString);
                    cv.put(AttachmentColumns.MIME_TYPE, type);
                    att.update(mContext, cv);
                    doStatusCallback(msg.mId, att.mId, EmailServiceStatus.SUCCESS);
                }
            }
        } else {
            doStatusCallback(msg.mId, att.mId, EmailServiceStatus.MESSAGE_NOT_FOUND);
        }
    }

    @SuppressWarnings("deprecation")
    private String makeUriString(String cmd, String extra) throws IOException {
         // Cache the authentication string and the command string
        String safeUserName = URLEncoder.encode(mUserName);
        if (mAuthString == null) {
            String cs = mUserName + ':' + mPassword;
            mAuthString = "Basic " + Base64.encodeBytes(cs.getBytes());
            mCmdString = "&User=" + safeUserName + "&DeviceId=" + mDeviceId + "&DeviceType="
                    + mDeviceType;
        }
        String us = (mSsl ? "https" : "http") + "://" + mHostAddress +
            "/Microsoft-Server-ActiveSync";
        if (cmd != null) {
            us += "?Cmd=" + cmd + mCmdString;
        }
        if (extra != null) {
            us += extra;
        }
        return us;
    }

    private void setHeaders(HttpRequestBase method) {
        method.setHeader("Authorization", mAuthString);
        method.setHeader("MS-ASProtocolVersion", mProtocolVersion);
        method.setHeader("Connection", "keep-alive");
        method.setHeader("User-Agent", mDeviceType + '/' + Eas.VERSION);
    }

    private HttpClient getHttpClient(int timeout) {
        HttpParams params = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(params, 10*SECONDS);
        HttpConnectionParams.setSoTimeout(params, timeout);
        return new DefaultHttpClient(params);
    }

    protected HttpResponse sendHttpClientPost(String cmd, byte[] bytes) throws IOException {
        HttpClient client =
            getHttpClient(cmd.equals(PING_COMMAND) ? PING_COMMAND_TIMEOUT : COMMAND_TIMEOUT);
        String us = makeUriString(cmd, null);
        HttpPost method = new HttpPost(URI.create(us));
        if (cmd.startsWith("SendMail&")) {
            method.setHeader("Content-Type", "message/rfc822");
        } else {
            method.setHeader("Content-Type", "application/vnd.ms-sync.wbxml");
        }
        setHeaders(method);
        method.setEntity(new ByteArrayEntity(bytes));
        synchronized(getSynchronizer()) {
            mPendingPost = method;
        }
        try {
            return client.execute(method);
        } finally {
            synchronized(getSynchronizer()) {
                mPendingPost = null;
            }
        }
    }

    protected HttpResponse sendHttpClientOptions() throws IOException {
        HttpClient client = getHttpClient(COMMAND_TIMEOUT);
        String us = makeUriString("OPTIONS", null);
        HttpOptions method = new HttpOptions(URI.create(us));
        setHeaders(method);
        return client.execute(method);
    }

    String getTargetCollectionClassFromCursor(Cursor c) {
        int type = c.getInt(Mailbox.CONTENT_TYPE_COLUMN);
        if (type == Mailbox.TYPE_CONTACTS) {
            return "Contacts";
        } else if (type == Mailbox.TYPE_CALENDAR) {
            return "Calendar";
        } else {
            return "Email";
        }
    }

    /**
     * Performs FolderSync
     *
     * @throws IOException
     * @throws EasParserException
     */
    public void runAccountMailbox() throws IOException, EasParserException {
        // Initialize exit status to success
        mNetworkType = waitForConnectivity();
        mExitStatus = EmailServiceStatus.SUCCESS;
        try {
            try {
                SyncManager.callback()
                    .syncMailboxListStatus(mAccount.mId, EmailServiceStatus.IN_PROGRESS, 0);
            } catch (RemoteException e1) {
                // Don't care if this fails
            }

            if (mAccount.mSyncKey == null) {
                mAccount.mSyncKey = "0";
                userLog("Account syncKey INIT to 0");
                ContentValues cv = new ContentValues();
                cv.put(AccountColumns.SYNC_KEY, mAccount.mSyncKey);
                mAccount.update(mContext, cv);
            }

            boolean firstSync = mAccount.mSyncKey.equals("0");
            if (firstSync) {
                userLog("Initial FolderSync");
            }

            // When we first start up, change all ping mailboxes to push.
            ContentValues cv = new ContentValues();
            cv.put(Mailbox.SYNC_INTERVAL, Account.CHECK_INTERVAL_PUSH);
            if (mContentResolver.update(Mailbox.CONTENT_URI, cv,
                    WHERE_ACCOUNT_AND_SYNC_INTERVAL_PING,
                    new String[] {Long.toString(mAccount.mId)}) > 0) {
                SyncManager.kick("change ping boxes to push");
            }

            // Determine our protocol version, if we haven't already
            if (mAccount.mProtocolVersion == null) {
                userLog("Determine EAS protocol version");
                HttpResponse resp = sendHttpClientOptions();
                int code = resp.getStatusLine().getStatusCode();
                userLog("OPTIONS response: " + code);
                if (code == HttpURLConnection.HTTP_OK) {
                    Header header = resp.getFirstHeader("ms-asprotocolversions");
                    String versions = header.getValue();
                    if (versions != null) {
                        if (versions.contains("12.0")) {
                            mProtocolVersion = "12.0";
                        }
                        mProtocolVersionDouble = Double.parseDouble(mProtocolVersion);
                        mAccount.mProtocolVersion = mProtocolVersion;
                        userLog(versions);
                        userLog("Using version " + mProtocolVersion);
                    } else {
                        errorLog("No protocol versions in OPTIONS response");
                        throw new IOException();
                    }
                } else {
                    errorLog("OPTIONS command failed; throwing IOException");
                    throw new IOException();
                }
            }

            // Change all pushable boxes to push when we start the account mailbox
            if (mAccount.mSyncInterval == Account.CHECK_INTERVAL_PUSH) {
                cv = new ContentValues();
                cv.put(Mailbox.SYNC_INTERVAL, Account.CHECK_INTERVAL_PUSH);
                if (mContentResolver.update(Mailbox.CONTENT_URI, cv,
                        SyncManager.WHERE_IN_ACCOUNT_AND_PUSHABLE,
                        new String[] {Long.toString(mAccount.mId)}) > 0) {
                    userLog("Push account; set pushable boxes to push...");
                }
            }

            while (!mStop) {
                 userLog("Sending Account syncKey: " + mAccount.mSyncKey);
                 Serializer s = new Serializer();
                 s.start(Tags.FOLDER_FOLDER_SYNC).start(Tags.FOLDER_SYNC_KEY)
                     .text(mAccount.mSyncKey).end().end().done();
                 HttpResponse resp = sendHttpClientPost("FolderSync", s.toByteArray());
                 if (mStop) break;
                 int code = resp.getStatusLine().getStatusCode();
                 if (code == HttpURLConnection.HTTP_OK) {
                     HttpEntity entity = resp.getEntity();
                     int len = (int)entity.getContentLength();
                     if (len > 0) {
                         InputStream is = entity.getContent();
                         // Returns true if we need to sync again
                         userLog("FolderSync, deviceId = " + mDeviceId);
                         if (new FolderSyncParser(is, this).parse()) {
                             continue;
                         }
                     }
                 } else if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                        code == HttpURLConnection.HTTP_FORBIDDEN) {
                    mExitStatus = AbstractSyncService.EXIT_LOGIN_FAILURE;
                } else {
                    userLog("FolderSync response error: " + code);
                }

                // Change all push/hold boxes to push
                cv = new ContentValues();
                cv.put(Mailbox.SYNC_INTERVAL, Account.CHECK_INTERVAL_PUSH);
                if (mContentResolver.update(Mailbox.CONTENT_URI, cv,
                        WHERE_PUSH_HOLD_NOT_ACCOUNT_MAILBOX,
                        new String[] {Long.toString(mAccount.mId)}) > 0) {
                    userLog("Set push/hold boxes to push...");
                }

                try {
                    SyncManager.callback()
                        .syncMailboxListStatus(mAccount.mId, mExitStatus, 0);
                } catch (RemoteException e1) {
                    // Don't care if this fails
                }

                // Wait for push notifications.
                String threadName = Thread.currentThread().getName();
                try {
                    runPingLoop();
                } catch (StaleFolderListException e) {
                    // We break out if we get told about a stale folder list
                    userLog("Ping interrupted; folder list requires sync...");
                } finally {
                    Thread.currentThread().setName(threadName);
                }
            }
         } catch (IOException e) {
            // We catch this here to send the folder sync status callback
            // A folder sync failed callback will get sent from run()
            try {
                if (!mStop) {
                    SyncManager.callback()
                        .syncMailboxListStatus(mAccount.mId,
                                EmailServiceStatus.CONNECTION_ERROR, 0);
                }
            } catch (RemoteException e1) {
                // Don't care if this fails
            }
            throw e;
        }
    }

    void pushFallback(long mailboxId) {
        Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, mailboxId);
        ContentValues cv = new ContentValues();
        if (mailbox.mType == Mailbox.TYPE_INBOX) {
            cv.put(Mailbox.SYNC_INTERVAL, PING_FALLBACK_INBOX);
            errorLog("*** PING LOOP: Turning off push due to ping loop on inbox...");
            mContentResolver.update(Mailbox.CONTENT_URI, cv,
                    MailboxColumns.ACCOUNT_KEY + '=' + mAccount.mId
                    + AND_FREQUENCY_PING_PUSH_AND_NOT_ACCOUNT_MAILBOX, null);
            // Now, change the account as well
            cv.clear();
            cv.put(Account.SYNC_INTERVAL, PING_FALLBACK_INBOX);
            mContentResolver.update(ContentUris.withAppendedId(Account.CONTENT_URI, mAccount.mId),
                    cv, null, null);

            // Let the SyncManager know that something's changed
            SyncManager.kick("push fallback");

            // TODO Discuss the best way to alert the user
            // Alert the user about what we've done
            NotificationManager nm = (NotificationManager)mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
            Notification note =
                new Notification(R.drawable.stat_notify_email_generic,
                        mContext.getString(R.string.notification_ping_loop_title),
                        System.currentTimeMillis());
            Intent i = new Intent(mContext, AccountFolderList.class);
            PendingIntent pi = PendingIntent.getActivity(mContext, 0, i, 0);
            note.setLatestEventInfo(mContext,
                    mContext.getString(R.string.notification_ping_loop_title),
                    mContext.getString(R.string.notification_ping_loop_text), pi);
            nm.notify(Eas.EXCHANGE_ERROR_NOTIFICATION, note);
        } else {
            // Just change this box to sync every 10 minutes
            cv.put(Mailbox.SYNC_INTERVAL, PING_FALLBACK_PIM);
            mContentResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                    cv, null, null);
            errorLog("*** PING LOOP: Backing off sync of " + mailbox.mDisplayName + " to 10 mins");
        }
    }

    void runPingLoop() throws IOException, StaleFolderListException {
        // Do push for all sync services here
        ArrayList<Mailbox> pushBoxes = new ArrayList<Mailbox>();
        long endTime = System.currentTimeMillis() + (30*MINUTES);
        HashMap<Long, Integer> pingFailureMap = new HashMap<Long, Integer>();

        while (System.currentTimeMillis() < endTime) {
            // Count of pushable mailboxes
            int pushCount = 0;
            // Count of mailboxes that can be pushed right now
            int canPushCount = 0;
            Serializer s = new Serializer();
            int code;
            Cursor c = mContentResolver.query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                    MailboxColumns.ACCOUNT_KEY + '=' + mAccount.mId +
                    AND_FREQUENCY_PING_PUSH_AND_NOT_ACCOUNT_MAILBOX, null, null);

            pushBoxes.clear();

            try {
                // Loop through our pushed boxes seeing what is available to push
                while (c.moveToNext()) {
                    pushCount++;
                    // Two requirements for push:
                    // 1) SyncManager tells us the mailbox is syncable (not running, not stopped)
                    // 2) The syncKey isn't "0" (i.e. it's synced at least once)
                    long mailboxId = c.getLong(Mailbox.CONTENT_ID_COLUMN);
                    int pingStatus = SyncManager.pingStatus(mailboxId);
                    String mailboxName = c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN);
                    if (pingStatus == SyncManager.PING_STATUS_OK) {
                        String syncKey = c.getString(Mailbox.CONTENT_SYNC_KEY_COLUMN);
                        if (syncKey == null || syncKey.equals("0")) {
                            pushCount--;
                            continue;
                        }

                        // Take a peek at this box's behavior last sync
                        // We do this because some Exchange 2003 servers put themselves (and
                        // therefore our client) into a "ping loop" in which the client is
                        // continuously told of server changes, only to find that there aren't any.
                        // This behavior is seemingly random, and we must code defensively by
                        // backing off of push behavior when this is detected.
                        // The server fix is at http://support.microsoft.com/kb/923282

                        // Sync status is encoded as S<type>:<exitstatus>:<changes>
                        String status = c.getString(Mailbox.CONTENT_SYNC_STATUS_COLUMN);
                        int type = SyncManager.getStatusType(status);
                        if (type == SyncManager.SYNC_PING) {
                            int changeCount = SyncManager.getStatusChangeCount(status);
                            if (changeCount > 0) {
                                pingFailureMap.remove(mailboxId);
                            } else if (changeCount == 0) {
                                // This means that a ping failed; we'll keep track of this
                                Integer failures = pingFailureMap.get(mailboxId);
                                if (failures == null) {
                                    pingFailureMap.put(mailboxId, 1);
                                } else if (failures > MAX_PING_FAILURES) {
                                    // Change all push/ping boxes (except account) to 5 minute sync
                                    pushFallback(mailboxId);
                                    return;
                                } else {
                                    pingFailureMap.put(mailboxId, failures + 1);
                                }
                            }
                        }

                        if (canPushCount++ == 0) {
                            // Initialize the Ping command
                            String pingHeartbeat = PING_HEARTBEAT_MOBILE;
                            if (mNetworkType == ConnectivityManager.TYPE_WIFI) {
                                pingHeartbeat = PING_HEARTBEAT_WIFI;
                            }
                            s.start(Tags.PING_PING)
                                .data(Tags.PING_HEARTBEAT_INTERVAL, pingHeartbeat)
                                .start(Tags.PING_FOLDERS);
                        }
                        // When we're ready for Calendar/Contacts, we will check folder type
                        // TODO Save Calendar and Contacts!! Mark as not visible!
                        String folderClass = getTargetCollectionClassFromCursor(c);
                        s.start(Tags.PING_FOLDER)
                            .data(Tags.PING_ID, c.getString(Mailbox.CONTENT_SERVER_ID_COLUMN))
                            .data(Tags.PING_CLASS, folderClass)
                            .end();
                        userLog("Ping ready for: " + folderClass + ", " + mailboxName + " (" +
                                c.getString(Mailbox.CONTENT_SERVER_ID_COLUMN) + ')');
                        pushBoxes.add(new Mailbox().restore(c));
                    } else if (pingStatus == SyncManager.PING_STATUS_RUNNING ||
                            pingStatus == SyncManager.PING_STATUS_WAITING) {
                        userLog(mailboxName + " not ready for ping");
                    } else if (pingStatus == SyncManager.PING_STATUS_UNABLE) {
                        pushCount--;
                        userLog(mailboxName + " in error state; ignore");
                        continue;
                    }
                }
            } finally {
                c.close();
            }

            if (canPushCount > 0 && (canPushCount == pushCount)) {
                // If we have some number that are ready for push, send Ping to the server
                s.end().end().done();

                Thread.currentThread().setName(mAccount.mDisplayName + ": Ping");
                userLog("Sending ping, timeout: " + PING_COMMAND_TIMEOUT / MINUTES + "m");

                SyncManager.runAsleep(mMailboxId, PING_COMMAND_TIMEOUT);
                long time = System.currentTimeMillis();
                HttpResponse res = sendHttpClientPost(PING_COMMAND, s.toByteArray());
                SyncManager.runAwake(mMailboxId);

                // Don't send request if we've been asked to stop
                if (mStop) return;
                code = res.getStatusLine().getStatusCode();

                // Get elapsed time
                time = System.currentTimeMillis() - time;
                userLog("Ping response: " + code + " in " + time + "ms");

                // Return immediately if we've been asked to stop
                if (mStop) {
                    userLog("Stopping pingLoop");
                    return;
                }

                if (code == HttpURLConnection.HTTP_OK) {
                    HttpEntity e = res.getEntity();
                    int len = (int)e.getContentLength();
                    InputStream is = res.getEntity().getContent();
                    if (len > 0) {
                        parsePingResult(is, mContentResolver);
                    } else {
                        throw new IOException();
                    }
                } else if (isAuthError(code)) {
                    mExitStatus = AbstractSyncService.EXIT_LOGIN_FAILURE;
                    userLog("Authorization error during Ping: " + code);
                    throw new IOException();
                }
            } else if (pushCount > 0) {
                // If we want to Ping, but can't just yet, wait 10 seconds and try again
                userLog("pingLoop waiting for " + (pushCount - canPushCount) + " box(es)");
                sleep(10*SECONDS);
            } else {
                // We've got nothing to do, so we'll check again in 30 minutes at which time
                // we'll update the folder list.
                SyncManager.runAsleep(mMailboxId, 30*MINUTES);
                sleep(30*MINUTES);
                SyncManager.runAwake(mMailboxId);
            }
        }
    }

    void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Doesn't matter whether we stop early; it's the thought that counts
        }
    }

    private int parsePingResult(InputStream is, ContentResolver cr)
        throws IOException, StaleFolderListException {
        PingParser pp = new PingParser(is, this);
        if (pp.parse()) {
            // True indicates some mailboxes need syncing...
            // syncList has the serverId's of the mailboxes...
            mBindArguments[0] = Long.toString(mAccount.mId);
            ArrayList<String> syncList = pp.getSyncList();
            for (String serverId: syncList) {
                mBindArguments[1] = serverId;
                Cursor c = cr.query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                        WHERE_ACCOUNT_KEY_AND_SERVER_ID, mBindArguments, null);
                try {
                    if (c.moveToFirst()) {
                        SyncManager.startManualSync(c.getLong(Mailbox.CONTENT_ID_COLUMN),
                                SyncManager.SYNC_PING, null);
                    }
                } finally {
                    c.close();
                }
            }
        }
        return pp.getSyncList().size();
    }

    ByteArrayInputStream readResponse(HttpURLConnection uc) throws IOException {
        String encoding = uc.getHeaderField("Transfer-Encoding");
        if (encoding == null) {
            int len = uc.getHeaderFieldInt("Content-Length", 0);
            if (len > 0) {
                InputStream in = uc.getInputStream();
                byte[] bytes = new byte[len];
                int remain = len;
                int offs = 0;
                while (remain > 0) {
                    int read = in.read(bytes, offs, remain);
                    remain -= read;
                    offs += read;
                }
                return new ByteArrayInputStream(bytes);
            }
        } else if (encoding.equalsIgnoreCase("chunked")) {
            // TODO We don't handle this yet
            return null;
        }
        return null;
    }

    String readResponseString(HttpURLConnection uc) throws IOException {
        String encoding = uc.getHeaderField("Transfer-Encoding");
        if (encoding == null) {
            int len = uc.getHeaderFieldInt("Content-Length", 0);
            if (len > 0) {
                InputStream in = uc.getInputStream();
                byte[] bytes = new byte[len];
                int remain = len;
                int offs = 0;
                while (remain > 0) {
                    int read = in.read(bytes, offs, remain);
                    remain -= read;
                    offs += read;
                }
                return new String(bytes);
            }
        } else if (encoding.equalsIgnoreCase("chunked")) {
            // TODO We don't handle this yet
            return null;
        }
        return null;
    }

    private String getFilterType() {
        String filter = Eas.FILTER_1_WEEK;
        switch (mAccount.mSyncLookback) {
            case com.android.email.Account.SYNC_WINDOW_1_DAY: {
                filter = Eas.FILTER_1_DAY;
                break;
            }
            case com.android.email.Account.SYNC_WINDOW_3_DAYS: {
                filter = Eas.FILTER_3_DAYS;
                break;
            }
            case com.android.email.Account.SYNC_WINDOW_1_WEEK: {
                filter = Eas.FILTER_1_WEEK;
                break;
            }
            case com.android.email.Account.SYNC_WINDOW_2_WEEKS: {
                filter = Eas.FILTER_2_WEEKS;
                break;
            }
            case com.android.email.Account.SYNC_WINDOW_1_MONTH: {
                filter = Eas.FILTER_1_MONTH;
                break;
            }
            case com.android.email.Account.SYNC_WINDOW_ALL: {
                filter = Eas.FILTER_ALL;
                break;
            }
        }
        return filter;
    }

    /**
     * Common code to sync E+PIM data
     *
     * @param target, an EasMailbox, EasContacts, or EasCalendar object
     */
    public void sync(AbstractSyncAdapter target) throws IOException {
        mTarget = target;
        Mailbox mailbox = target.mMailbox;

        boolean moreAvailable = true;
        while (!mStop && moreAvailable) {
            waitForConnectivity();

            while (true) {
                PartRequest req = null;
                synchronized (mPartRequests) {
                    if (mPartRequests.isEmpty()) {
                        break;
                    } else {
                        req = mPartRequests.get(0);
                    }
                }
                getAttachment(req);
                synchronized(mPartRequests) {
                    mPartRequests.remove(req);
                }
            }

            Serializer s = new Serializer();
            if (mailbox.mSyncKey == null) {
                userLog("Mailbox syncKey RESET");
                mailbox.mSyncKey = "0";
            }
            String className = target.getCollectionName();
            userLog("Sending " + className + " syncKey: " + mailbox.mSyncKey);
            s.start(Tags.SYNC_SYNC)
                .start(Tags.SYNC_COLLECTIONS)
                .start(Tags.SYNC_COLLECTION)
                .data(Tags.SYNC_CLASS, className)
                .data(Tags.SYNC_SYNC_KEY, mailbox.mSyncKey)
                .data(Tags.SYNC_COLLECTION_ID, mailbox.mServerId)
                .tag(Tags.SYNC_DELETES_AS_MOVES);

            // EAS doesn't like GetChanges if the syncKey is "0"; not documented
            if (!mailbox.mSyncKey.equals("0")) {
                s.tag(Tags.SYNC_GET_CHANGES);
            }
            s.data(Tags.SYNC_WINDOW_SIZE,
                    className.equals("Email") ? EMAIL_WINDOW_SIZE : PIM_WINDOW_SIZE);
            boolean options = false;
            if (!className.equals("Contacts")) {
                // Set the lookback appropriately (EAS calls this a "filter")
                s.start(Tags.SYNC_OPTIONS).data(Tags.SYNC_FILTER_TYPE, getFilterType());
                // No truncation in this version
                //if (mProtocolVersionDouble < 12.0) {
                //    s.data(Tags.SYNC_TRUNCATION, "7");
                //}
                options = true;
            }
            if (mProtocolVersionDouble >= 12.0) {
                if (!options) {
                    options = true;
                    s.start(Tags.SYNC_OPTIONS);
                }
                s.start(Tags.BASE_BODY_PREFERENCE)
                    // HTML for email; plain text for everything else
                    .data(Tags.BASE_TYPE, (className.equals("Email") ? Eas.BODY_PREFERENCE_HTML
                            : Eas.BODY_PREFERENCE_TEXT))
                // No truncation in this version
                //.data(Tags.BASE_TRUNCATION_SIZE, Eas.DEFAULT_BODY_TRUNCATION_SIZE)
                    .end();
            }
            if (options) {
                s.end();
            }

            // Send our changes up to the server
            target.sendLocalChanges(s, this);

            s.end().end().end().done();
            userLog("Sync, deviceId = " + mDeviceId);
            HttpResponse resp = sendHttpClientPost("Sync", s.toByteArray());
            int code = resp.getStatusLine().getStatusCode();
            if (code == HttpURLConnection.HTTP_OK) {
                 InputStream is = resp.getEntity().getContent();
                if (is != null) {
                    moreAvailable = target.parse(is, this);
                    target.cleanup(this);
                }
            } else {
                userLog("Sync response error: " + code);
                if (isAuthError(code)) {
                    mExitStatus = AbstractSyncService.EXIT_LOGIN_FAILURE;
                }
                return;
            }
        }
    }

    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    public void run() {
        mThread = Thread.currentThread();
        TAG = mThread.getName();

        HostAuth ha = HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
        mHostAddress = ha.mAddress;
        mUserName = ha.mLogin;
        mPassword = ha.mPassword;

        try {
            SyncManager.callback().syncMailboxStatus(mMailboxId, EmailServiceStatus.IN_PROGRESS, 0);
        } catch (RemoteException e1) {
            // Don't care if this fails
        }

        // Make sure account and mailbox are always the latest from the database
        mAccount = Account.restoreAccountWithId(mContext, mAccount.mId);
        mMailbox = Mailbox.restoreMailboxWithId(mContext, mMailbox.mId);
        // Whether or not we're the account mailbox
        try {
            mDeviceId = SyncManager.getDeviceId();
            if (mMailbox == null || mAccount == null) {
                return;
            } else if (mMailbox.mType == Mailbox.TYPE_EAS_ACCOUNT_MAILBOX) {
                runAccountMailbox();
            } else {
                AbstractSyncAdapter target;
                mAccount = Account.restoreAccountWithId(mContext, mAccount.mId);
                mProtocolVersion = mAccount.mProtocolVersion;
                mProtocolVersionDouble = Double.parseDouble(mProtocolVersion);
                if (mMailbox.mType == Mailbox.TYPE_CONTACTS)
                    target = new ContactsSyncAdapter(mMailbox, this);
                else {
                    target = new EmailSyncAdapter(mMailbox, this);
                }
                // We loop here because someone might have put a request in while we were syncing
                // and we've missed that opportunity...
                do {
                    if (mRequestTime != 0) {
                        userLog("Looping for user request...");
                        mRequestTime = 0;
                    }
                    sync(target);
                } while (mRequestTime != 0);
            }
            mExitStatus = EXIT_DONE;
        } catch (IOException e) {
            String message = e.getMessage();
            userLog("Caught IOException: " + ((message == null) ? "" : message));
            mExitStatus = EXIT_IO_ERROR;
        } catch (Exception e) {
            Log.e(TAG, "Uncaught exception in EasSyncService", e);
        } finally {
            if (!mStop) {
                userLog(mMailbox.mDisplayName + ": sync finished");
                SyncManager.done(this);
                // If this is the account mailbox, wake up SyncManager
                // Because this box has a "push" interval, it will be restarted immediately
                // which will cause the folder list to be reloaded...
                int status;
                switch (mExitStatus) {
                    case EXIT_IO_ERROR:
                        status = EmailServiceStatus.CONNECTION_ERROR;
                        break;
                    case EXIT_DONE:
                        status = EmailServiceStatus.SUCCESS;
                        break;
                    case EXIT_LOGIN_FAILURE:
                        status = EmailServiceStatus.LOGIN_FAILED;
                        break;
                    default:
                        status = EmailServiceStatus.REMOTE_EXCEPTION;
                        errorLog("Sync ended due to an exception.");
                        break;
                }

                try {
                    SyncManager.callback().syncMailboxStatus(mMailboxId, status, 0);
                } catch (RemoteException e1) {
                    // Don't care if this fails
                }

                // Save the sync time and status
                ContentValues cv = new ContentValues();
                cv.put(Mailbox.SYNC_TIME, System.currentTimeMillis());
                String s = "S" + mSyncReason + ':' + status + ':' + mChangeCount;
                cv.put(Mailbox.SYNC_STATUS, s);
                mContentResolver.update(ContentUris
                        .withAppendedId(Mailbox.CONTENT_URI, mMailboxId), cv, null, null);
            } else {
                userLog(mMailbox.mDisplayName + ": stopped thread finished.");
            }

            // Make sure SyncManager knows about this
            SyncManager.kick("sync finished");
       }
    }
}
