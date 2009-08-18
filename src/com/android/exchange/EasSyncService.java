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

import com.android.email.codec.binary.Base64;
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

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
        '=' + Mailbox.CHECK_INTERVAL_PING;
    private static final String AND_FREQUENCY_PING_PUSH_AND_NOT_ACCOUNT_MAILBOX = " AND " +
        MailboxColumns.SYNC_INTERVAL + " IN (" + Mailbox.CHECK_INTERVAL_PING +
        ',' + Mailbox.CHECK_INTERVAL_PUSH + ") AND " + MailboxColumns.TYPE + "!=\"" +
        Mailbox.TYPE_EAS_ACCOUNT_MAILBOX + '\"';
    private static final String WHERE_PUSH_HOLD_NOT_ACCOUNT_MAILBOX =
        MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.SYNC_INTERVAL +
        '=' + Mailbox.CHECK_INTERVAL_PUSH_HOLD;
    private static final String[] SYNC_STATUS_PROJECTION =
        new String[] {MailboxColumns.ID, MailboxColumns.SYNC_STATUS};
    static private final int CHUNK_SIZE = 16*1024;

    static private final String PING_COMMAND = "Ping";
    static private final int COMMAND_TIMEOUT = 20*SECONDS;

    /**
     * We start with an 8 minute timeout, and increase/decrease by 3 minutes at a time.  There's
     * no point having a timeout shorter than 5 minutes, I think; at that point, we can just let
     * the ping exception out.  The maximum I use is 17 minutes, which is really an empirical
     * choice; too long and we risk silent connection loss and loss of push for that period.  Too
     * short and we lose efficiency/battery life.
     *
     * If we ever have to drop the ping timeout, we'll never increase it again.  There's no point
     * going into hysteresis; the NAT timeout isn't going to change without a change in connection,
     * which will cause the sync service to be restarted at the starting heartbeat and going through
     * the process again.
     *
     * One issue we've got is that there's no foolproof way of knowing that we hit a NAT timeout,
     * rather than some other disconnect.  In my experience, we see a particular IOException, but
     * this might be too fragile an indicator, so we'll have to consider changes to this approach.
     */
    static private final int PING_MINUTES = 60; // in seconds
    static private final int PING_FUDGE_LOW = 10;
    static private final int PING_STARTING_HEARTBEAT = (8*PING_MINUTES)-PING_FUDGE_LOW;
    static private final int PING_MIN_HEARTBEAT = (5*PING_MINUTES)-PING_FUDGE_LOW;
    static private final int PING_MAX_HEARTBEAT = (17*PING_MINUTES)-PING_FUDGE_LOW;
    static private final int PING_HEARTBEAT_INCREMENT = 3*PING_MINUTES;

    static private final int PROTOCOL_PING_STATUS_COMPLETED = 1;
    static private final int PROTOCOL_PING_STATUS_CHANGES_FOUND = 2;

    // Fallbacks (in minutes) for ping loop failures
    static private final int MAX_PING_FAILURES = 1;
    static private final int PING_FALLBACK_INBOX = 5;
    static private final int PING_FALLBACK_PIM = 30;

    // Reasonable default
    String mProtocolVersion = "2.5";
    public Double mProtocolVersionDouble;
    private String mDeviceId = null;
    private String mDeviceType = "Android";
    private String mAuthString = null;
    private String mCmdString = null;
    public String mHostAddress;
    public String mUserName;
    public String mPassword;
    private boolean mSsl = true;
    public ContentResolver mContentResolver;
    private String[] mBindArguments = new String[2];
    private ArrayList<String> mPingChangeList;
    private HttpPost mPendingPost = null;
    // The ping time (in seconds)
    private int mPingHeartbeat = PING_STARTING_HEARTBEAT;
    // The longest successful ping heartbeat
    private int mPingHighWaterMark = 0;
    // Whether we've ever lowered the heartbeat
    private boolean mPingHeartbeatDropped = false;

    public EasSyncService(Context _context, Mailbox _mailbox) {
        super(_context, _mailbox);
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
        userLog("Alarm ping received!");
        synchronized(getSynchronizer()) {
            if (mPendingPost != null) {
                userLog("Aborting pending POST!");
                mPendingPost.abort();
            }
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

    /**
     * Determine whether an HTTP code represents an authentication error
     * @param code the HTTP code returned by the server
     * @return whether or not the code represents an authentication error
     */
    private boolean isAuthError(int code) {
        return (code == HttpStatus.SC_UNAUTHORIZED
                || code == HttpStatus.SC_FORBIDDEN
//                || code == HttpStatus.SC_INTERNAL_SERVER_ERROR
                );
    }

    @Override
    public void validateAccount(String hostAddress, String userName, String password, int port,
            boolean ssl, Context context) throws MessagingException {
        try {
            userLog("Testing EAS: ", hostAddress, ", ", userName, ", ssl = ", ssl ? "1" : "0");
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
            if (code == HttpStatus.SC_OK) {
                // No exception means successful validation
                userLog("Validation successful");
                return;
            }
            if (isAuthError(code)) {
                userLog("Authentication failed");
                throw new AuthenticationFailedException("Validation failed");
            } else {
                // TODO Need to catch other kinds of errors (e.g. policy) For now, report the code.
                userLog("Validation failed, reporting I/O error: ", code);
                throw new MessagingException(MessagingException.IOERROR);
            }
        } catch (IOException e) {
            userLog("IOException caught, reporting I/O error: ", e.getMessage());
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
        if (status == HttpStatus.SC_OK) {
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
            mAuthString = "Basic " + new String(Base64.encodeBase64(cs.getBytes()));
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
        HttpConnectionParams.setConnectionTimeout(params, 15*SECONDS);
        HttpConnectionParams.setSoTimeout(params, timeout);
        return new DefaultHttpClient(params);
    }

    protected HttpResponse sendHttpClientPost(String cmd, byte[] bytes) throws IOException {
        return sendHttpClientPost(cmd, new ByteArrayEntity(bytes), COMMAND_TIMEOUT);
    }

    protected HttpResponse sendHttpClientPost(String cmd, HttpEntity entity) throws IOException {
        return sendHttpClientPost(cmd, entity, COMMAND_TIMEOUT);
    }

    protected HttpResponse sendPing(byte[] bytes, int pingHeartbeat) throws IOException {
       int timeout = (pingHeartbeat+15)*SECONDS;
       Thread.currentThread().setName(mAccount.mDisplayName + ": Ping");

       if (Eas.USER_LOG) {
           userLog("Sending ping, timeout: " + pingHeartbeat +
                   "s, high water mark: " + mPingHighWaterMark + 's');
       }

       SyncManager.runAsleep(mMailboxId, timeout);
       try {
           HttpResponse res = sendHttpClientPost(PING_COMMAND, new ByteArrayEntity(bytes), timeout);
           return res;
       } finally {
           SyncManager.runAwake(mMailboxId);
       }
    }

    protected HttpResponse sendHttpClientPost(String cmd, HttpEntity entity, int timeout)
            throws IOException {
        HttpClient client = getHttpClient(timeout);
        String us = makeUriString(cmd, null);
        HttpPost method = new HttpPost(URI.create(us));
        if (cmd.startsWith("SendMail&")) {
            method.setHeader("Content-Type", "message/rfc822");
        } else {
            method.setHeader("Content-Type", "application/vnd.ms-sync.wbxml");
        }
        setHeaders(method);
        method.setEntity(entity);
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

            // When we first start up, change all mailboxes to push.
            ContentValues cv = new ContentValues();
            cv.put(Mailbox.SYNC_INTERVAL, Mailbox.CHECK_INTERVAL_PUSH);
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
                userLog("OPTIONS response: ", code);
                if (code == HttpStatus.SC_OK) {
                    Header header = resp.getFirstHeader("ms-asprotocolversions");
                    String versions = header.getValue();
                    if (versions != null) {
                        if (versions.contains("12.0")) {
                            mProtocolVersion = "12.0";
                        }
                        mProtocolVersionDouble = Double.parseDouble(mProtocolVersion);
                        mAccount.mProtocolVersion = mProtocolVersion;
                        userLog(versions);
                        userLog("Using version ", mProtocolVersion);
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
                cv.put(Mailbox.SYNC_INTERVAL, Mailbox.CHECK_INTERVAL_PUSH);
                if (mContentResolver.update(Mailbox.CONTENT_URI, cv,
                        SyncManager.WHERE_IN_ACCOUNT_AND_PUSHABLE,
                        new String[] {Long.toString(mAccount.mId)}) > 0) {
                    userLog("Push account; set pushable boxes to push...");
                }
            }

            while (!mStop) {
                 userLog("Sending Account syncKey: ", mAccount.mSyncKey);
                 Serializer s = new Serializer();
                 s.start(Tags.FOLDER_FOLDER_SYNC).start(Tags.FOLDER_SYNC_KEY)
                     .text(mAccount.mSyncKey).end().end().done();
                 HttpResponse resp = sendHttpClientPost("FolderSync", s.toByteArray());
                 if (mStop) break;
                 int code = resp.getStatusLine().getStatusCode();
                 if (code == HttpStatus.SC_OK) {
                     HttpEntity entity = resp.getEntity();
                     int len = (int)entity.getContentLength();
                     if (len > 0) {
                         InputStream is = entity.getContent();
                         // Returns true if we need to sync again
                         userLog("FolderSync, deviceId = ", mDeviceId);
                         if (new FolderSyncParser(is, this).parse()) {
                             continue;
                         }
                     }
                 } else if (isAuthError(code)) {
                    mExitStatus = EXIT_LOGIN_FAILURE;
                } else {
                    userLog("FolderSync response error: ", code);
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
        int mins = PING_FALLBACK_PIM;
        if (mailbox.mType == Mailbox.TYPE_INBOX) {
            mins = PING_FALLBACK_INBOX;
        }
        cv.put(Mailbox.SYNC_INTERVAL, mins);
        mContentResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                cv, null, null);
        errorLog("*** PING ERROR LOOP: Backing off sync of " + mailbox.mDisplayName + " to " +
                mins + " mins");
        SyncManager.kick("push fallback");
    }

    /**
     * Check the boxes reporting changes to see if there really were any...
     * We do this because bugs in various Exchange servers can put us into a looping
     * behavior by continually reporting changes in a mailbox, even when there aren't any.
     *
     * This behavior is seemingly random, and therefore we must code defensively by
     * backing off of push behavior when it is detected.
     *
     * One known cause, on certain Exchange 2003 servers, is acknowledged by Microsoft, and the
     * server hotfix for this case can be found at http://support.microsoft.com/kb/923282
     */

    void checkPingErrors(HashMap<String, Integer> errorMap) {
        mBindArguments[0] = Long.toString(mAccount.mId);
        for (String serverId: mPingChangeList) {
            // Find the id and sync status for each box
            mBindArguments[1] = serverId;
            Cursor c = mContentResolver.query(Mailbox.CONTENT_URI, SYNC_STATUS_PROJECTION,
                    WHERE_ACCOUNT_KEY_AND_SERVER_ID, mBindArguments, null);
            try {
                if (c.moveToFirst()) {
                    String status = c.getString(1);
                    int type = SyncManager.getStatusType(status);
                    // This check should always be true...
                    if (type == SyncManager.SYNC_PING) {
                        int changeCount = SyncManager.getStatusChangeCount(status);
                        if (changeCount > 0) {
                            errorMap.remove(serverId);
                        } else if (changeCount == 0) {
                            // This means that a ping reported changes in error; we keep a count
                            // of consecutive errors of this kind
                            Integer failures = errorMap.get(serverId);
                            if (failures == null) {
                                errorMap.put(serverId, 1);
                            } else if (failures > MAX_PING_FAILURES) {
                                // We'll back off of push for this box
                                pushFallback(c.getLong(0));
                                return;
                            } else {
                                errorMap.put(serverId, failures + 1);
                            }
                        }
                    }
                }
            } finally {
                c.close();
            }
        }
    }

    void runPingLoop() throws IOException, StaleFolderListException {
        int pingHeartbeat = mPingHeartbeat;

        // Do push for all sync services here
        long endTime = System.currentTimeMillis() + (30*MINUTES);
        HashMap<String, Integer> pingErrorMap = new HashMap<String, Integer>();

        while (System.currentTimeMillis() < endTime && !mStop) {
            // Count of pushable mailboxes
            int pushCount = 0;
            // Count of mailboxes that can be pushed right now
            int canPushCount = 0;
            Serializer s = new Serializer();
            Cursor c = mContentResolver.query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                    MailboxColumns.ACCOUNT_KEY + '=' + mAccount.mId +
                    AND_FREQUENCY_PING_PUSH_AND_NOT_ACCOUNT_MAILBOX, null, null);
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

                        if (canPushCount++ == 0) {
                            // Initialize the Ping command
                            s.start(Tags.PING_PING)
                                .data(Tags.PING_HEARTBEAT_INTERVAL,
                                        Integer.toString(pingHeartbeat))
                                .start(Tags.PING_FOLDERS);
                        }

                        String folderClass = getTargetCollectionClassFromCursor(c);
                        s.start(Tags.PING_FOLDER)
                            .data(Tags.PING_ID, c.getString(Mailbox.CONTENT_SERVER_ID_COLUMN))
                            .data(Tags.PING_CLASS, folderClass)
                            .end();
                        userLog("Ping ready for: ", folderClass, ", ", mailboxName, " (",
                                c.getString(Mailbox.CONTENT_SERVER_ID_COLUMN), ")");
                    } else if (pingStatus == SyncManager.PING_STATUS_RUNNING ||
                            pingStatus == SyncManager.PING_STATUS_WAITING) {
                        userLog(mailboxName, " not ready for ping");
                    } else if (pingStatus == SyncManager.PING_STATUS_UNABLE) {
                        pushCount--;
                        userLog(mailboxName, " in error state; ignore");
                        continue;
                    }
                }
            } finally {
                c.close();
            }

            if (canPushCount > 0 && (canPushCount == pushCount)) {
                // If we have some number that are ready for push, send Ping to the server
                s.end().end().done();

                // If we've been stopped, this is a good time to return
                if (mStop) return;

                try {
                    // Send the ping, wrapped by appropriate timeout/alarm
                    HttpResponse res = sendPing(s.toByteArray(), pingHeartbeat);

                    int code = res.getStatusLine().getStatusCode();
                    userLog("Ping response: ", code);

                    // Return immediately if we've been asked to stop during the ping
                    if (mStop) {
                        userLog("Stopping pingLoop");
                        return;
                    }

                    if (code == HttpStatus.SC_OK) {
                        HttpEntity e = res.getEntity();
                        int len = (int)e.getContentLength();
                        InputStream is = res.getEntity().getContent();
                        if (len > 0) {
                            int status = parsePingResult(is, mContentResolver);
                            // If our ping completed (status = 1), and we're not at the maximum,
                            // try increasing timeout by two minutes
                            if (status == PROTOCOL_PING_STATUS_COMPLETED &&
                                    pingHeartbeat > mPingHighWaterMark) {
                                userLog("Setting ping high water mark at: ", mPingHighWaterMark);
                                mPingHighWaterMark = pingHeartbeat;
                            }
                            if (status == PROTOCOL_PING_STATUS_COMPLETED &&
                                    pingHeartbeat < PING_MAX_HEARTBEAT &&
                                    !mPingHeartbeatDropped) {
                                pingHeartbeat += PING_HEARTBEAT_INCREMENT;
                                if (pingHeartbeat > PING_MAX_HEARTBEAT) {
                                    pingHeartbeat = PING_MAX_HEARTBEAT;
                                }
                                userLog("Increasing ping heartbeat to ", pingHeartbeat, "s");
                            } else if (status == PROTOCOL_PING_STATUS_CHANGES_FOUND) {
                                checkPingErrors(pingErrorMap);
                            }
                        } else {
                            userLog("Ping returned empty result; throwing IOException");
                            throw new IOException();
                        }
                    } else if (isAuthError(code)) {
                        mExitStatus = EXIT_LOGIN_FAILURE;
                        userLog("Authorization error during Ping: ", code);
                        throw new IOException();
                    }
                } catch (IOException e) {
                    String msg = e.getMessage();
                    // If we get the exception that is indicative of a NAT timeout and if we
                    // haven't yet "fixed" the timeout, back off by two minutes and "fix" it
                    if (msg != null && msg.contains("reset by peer")) {
                        if (pingHeartbeat > PING_MIN_HEARTBEAT &&
                                pingHeartbeat > mPingHighWaterMark) {
                            pingHeartbeat -= PING_HEARTBEAT_INCREMENT;
                            mPingHeartbeatDropped = true;
                            if (pingHeartbeat < PING_MIN_HEARTBEAT) {
                                pingHeartbeat = PING_MIN_HEARTBEAT;
                            }
                            userLog("Decreased ping heartbeat to ", pingHeartbeat, "s");
                        }
                    } else {
                        userLog("IOException detected in runPingLoop: " +
                                ((msg != null) ? msg : "no message"));
                        throw e;
                    }
                }
            } else if (pushCount > 0) {
                // If we want to Ping, but can't just yet, wait 10 seconds and try again
                // No point giving up the wake lock for 10 seconds...
                userLog("pingLoop waiting for: ", (pushCount - canPushCount), " box(es)");
                sleep(10*SECONDS);
            } else {
                // We've got nothing to do, so we'll check again in 30 minutes at which time
                // we'll update the folder list.  Let the device sleep in the meantime...
                SyncManager.runAsleep(mMailboxId, (30*MINUTES)+(15*SECONDS));
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
            mPingChangeList = pp.getSyncList();
            for (String serverId: mPingChangeList) {
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
        return pp.getSyncStatus();
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
        Mailbox mailbox = target.mMailbox;

        boolean moreAvailable = true;
        while (!mStop && moreAvailable) {
            // If we have no connectivity, just exit cleanly.  SyncManager will start us up again
            // when connectivity has returned
            if (!hasConnectivity()) {
                userLog("No connectivity in sync; finishing sync");
                mExitStatus = EXIT_DONE;
                return;
            }

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
            userLog("Sending ", className, " syncKey: ", mailbox.mSyncKey);
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
                    .end();
            }
            if (options) {
                s.end();
            }

            // Send our changes up to the server
            target.sendLocalChanges(s, this);

            s.end().end().end().done();
            userLog("Sync, deviceId = ", mDeviceId);
            HttpResponse resp = sendHttpClientPost("Sync", s.toByteArray());
            int code = resp.getStatusLine().getStatusCode();
            if (code == HttpStatus.SC_OK) {
                 InputStream is = resp.getEntity().getContent();
                if (is != null) {
                    moreAvailable = target.parse(is, this);
                    target.cleanup(this);
                }
            } else {
                userLog("Sync response error: ", code);
                if (isAuthError(code)) {
                    mExitStatus = EXIT_LOGIN_FAILURE;
                } else {
                    mExitStatus = EXIT_IO_ERROR;
                }
                return;
            }
        }
        mExitStatus = EXIT_DONE;
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
        } catch (IOException e) {
            String message = e.getMessage();
            userLog("Caught IOException: ", ((message == null) ? "" : message));
            mExitStatus = EXIT_IO_ERROR;
        } catch (Exception e) {
            Log.e(TAG, "Uncaught exception in EasSyncService", e);
        } finally {
            if (!mStop) {
                userLog(mMailbox.mDisplayName, ": sync finished");
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
                userLog(mMailbox.mDisplayName, ": stopped thread finished.");
            }

            // Make sure SyncManager knows about this
            SyncManager.kick("sync finished");
       }
    }
}
