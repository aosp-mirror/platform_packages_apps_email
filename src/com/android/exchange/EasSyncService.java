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
import com.android.exchange.adapter.AccountSyncAdapter;
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
import org.apache.http.conn.ClientConnectionManager;
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
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;

public class EasSyncService extends AbstractSyncService {
    private static final String EMAIL_WINDOW_SIZE = "5";
    public static final String PIM_WINDOW_SIZE = "5";
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
     */
    static private final int PING_MINUTES = 60; // in seconds
    static private final int PING_FUDGE_LOW = 10;
    static private final int PING_STARTING_HEARTBEAT = (8*PING_MINUTES)-PING_FUDGE_LOW;
    static private final int PING_MIN_HEARTBEAT = (5*PING_MINUTES)-PING_FUDGE_LOW;
    static private final int PING_MAX_HEARTBEAT = (17*PING_MINUTES)-PING_FUDGE_LOW;
    static private final int PING_HEARTBEAT_INCREMENT = 3*PING_MINUTES;
    static private final int PING_FORCE_HEARTBEAT = 2*PING_MINUTES;

    static private final int PROTOCOL_PING_STATUS_COMPLETED = 1;

    // Fallbacks (in minutes) for ping loop failures
    static private final int MAX_PING_FAILURES = 1;
    static private final int PING_FALLBACK_INBOX = 5;
    static private final int PING_FALLBACK_PIM = 25;

    // Reasonable default
    String mProtocolVersion = "2.5";
    public Double mProtocolVersionDouble;
    protected String mDeviceId = null;
    private String mDeviceType = "Android";
    private String mAuthString = null;
    private String mCmdString = null;
    public String mHostAddress;
    public String mUserName;
    public String mPassword;
    private boolean mSsl = true;
    private boolean mTrustSsl = false;
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
    // Whether a POST was aborted due to watchdog timeout
    private boolean mAborted = false;

    public EasSyncService(Context _context, Mailbox _mailbox) {
        super(_context, _mailbox);
        mContentResolver = _context.getContentResolver();
        HostAuth ha = HostAuth.restoreHostAuthWithId(_context, mAccount.mHostAuthKeyRecv);
        mSsl = (ha.mFlags & HostAuth.FLAG_SSL) != 0;
        mTrustSsl = (ha.mFlags & HostAuth.FLAG_TRUST_ALL_CERTIFICATES) != 0;
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
                mAborted = true;
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
    protected boolean isAuthError(int code) {
        return ((code == HttpStatus.SC_UNAUTHORIZED) || (code == HttpStatus.SC_FORBIDDEN));
    }

    @Override
    public void validateAccount(String hostAddress, String userName, String password, int port,
            boolean ssl, boolean trustCertificates, Context context) throws MessagingException {
        try {
            userLog("Testing EAS: ", hostAddress, ", ", userName, ", ssl = ", ssl ? "1" : "0");
            EasSyncService svc = new EasSyncService("%TestAccount%");
            svc.mContext = context;
            svc.mHostAddress = hostAddress;
            svc.mUserName = userName;
            svc.mPassword = password;
            svc.mSsl = ssl;
            svc.mTrustSsl = trustCertificates;
            svc.mDeviceId = SyncManager.getDeviceId();
            HttpResponse resp = svc.sendHttpClientOptions();
            int code = resp.getStatusLine().getStatusCode();
            userLog("Validation (OPTIONS) response: " + code);
            if (code == HttpStatus.SC_OK) {
                // No exception means successful validation
                Header commands = resp.getFirstHeader("MS-ASProtocolCommands");
                Header versions = resp.getFirstHeader("ms-asprotocolversions");
                if (commands == null || versions == null) {
                    userLog("OPTIONS response without commands or versions; reporting I/O error");
                    throw new MessagingException(MessagingException.IOERROR);
                }
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
            Throwable cause = e.getCause();
            if (cause != null && cause instanceof CertificateException) {
                userLog("CertificateException caught: ", e.getMessage());
                throw new MessagingException(MessagingException.GENERAL_SECURITY);
            }
            userLog("IOException caught: ", e.getMessage());
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

        String cmd = "GetAttachment&AttachmentName=" + att.mLocation;
        HttpResponse res = sendHttpClientPost(cmd, null, COMMAND_TIMEOUT);

        int status = res.getStatusLine().getStatusCode();
        if (status == HttpStatus.SC_OK) {
            HttpEntity e = res.getEntity();
            int len = (int)e.getContentLength();
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
                // len > 0 means that Content-Length was set in the headers
                // len < 0 means "chunked" transfer-encoding
                if (len != 0) {
                    try {
                        mPendingPartRequest = req;
                        byte[] bytes = new byte[CHUNK_SIZE];
                        int length = len;
                        // Loop terminates 1) when EOF is reached or 2) if an IOException occurs
                        // One of these is guaranteed to occur
                        int totalRead = 0;
                        userLog("Attachment content-length: ", len);
                        while (true) {
                            int read = is.read(bytes, 0, CHUNK_SIZE);

                            // read < 0 means that EOF was reached
                            if (read < 0) {
                                userLog("Attachment load reached EOF, totalRead: ", totalRead);
                                break;
                            }

                            // Keep track of how much we've read for progress callback
                            totalRead += read;

                            // Write these bytes out
                            os.write(bytes, 0, read);

                            // We can't report percentages if this is chunked; by definition, the
                            // length of incoming data is unknown
                            if (length > 0) {
                                // Belt and suspenders check to prevent runaway reading
                                if (totalRead > length) {
                                    errorLog("totalRead is greater than attachment length?");
                                    break;
                                }
                                int pct = (totalRead * 100 / length);
                                doProgressCallback(msg.mId, att.mId, pct);
                            }
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
        String us = (mSsl ? (mTrustSsl ? "httpts" : "https") : "http") + "://" + mHostAddress +
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

    private ClientConnectionManager getClientConnectionManager() {
        return SyncManager.getClientConnectionManager();
    }

    private HttpClient getHttpClient(int timeout) {
        HttpParams params = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(params, 15*SECONDS);
        HttpConnectionParams.setSoTimeout(params, timeout);
        HttpConnectionParams.setSocketBufferSize(params, 8192);
        HttpClient client = new DefaultHttpClient(getClientConnectionManager(), params);
        return client;
    }

    protected HttpResponse sendHttpClientPost(String cmd, byte[] bytes) throws IOException {
        return sendHttpClientPost(cmd, new ByteArrayEntity(bytes), COMMAND_TIMEOUT);
    }

    protected HttpResponse sendHttpClientPost(String cmd, HttpEntity entity) throws IOException {
        return sendHttpClientPost(cmd, entity, COMMAND_TIMEOUT);
    }

    protected HttpResponse sendPing(byte[] bytes, int heartbeat) throws IOException {
       Thread.currentThread().setName(mAccount.mDisplayName + ": Ping");
       if (Eas.USER_LOG) {
           userLog("Send ping, timeout: " + heartbeat + "s, high: " + mPingHighWaterMark + 's');
       }
       return sendHttpClientPost(PING_COMMAND, new ByteArrayEntity(bytes), (heartbeat+5)*SECONDS);
    }

    protected HttpResponse sendHttpClientPost(String cmd, HttpEntity entity, int timeout)
            throws IOException {
        HttpClient client = getHttpClient(timeout);
        boolean sleepAllowed = cmd.equals(PING_COMMAND);

        // Split the mail sending commands
        String extra = null;
        boolean msg = false;
        if (cmd.startsWith("SmartForward&") || cmd.startsWith("SmartReply&")) {
            int cmdLength = cmd.indexOf('&');
            extra = cmd.substring(cmdLength);
            cmd = cmd.substring(0, cmdLength);
            msg = true;
        } else if (cmd.startsWith("SendMail&")) {
            msg = true;
        }

        String us = makeUriString(cmd, extra);
        HttpPost method = new HttpPost(URI.create(us));
        // Send the proper Content-Type header
        // If entity is null (e.g. for attachments), don't set this header
        if (msg) {
            method.setHeader("Content-Type", "message/rfc822");
        } else if (entity != null) {
            method.setHeader("Content-Type", "application/vnd.ms-sync.wbxml");
        }
        setHeaders(method);
        method.setEntity(entity);
        synchronized(getSynchronizer()) {
            mPendingPost = method;
            if (sleepAllowed) {
                SyncManager.runAsleep(mMailboxId, timeout+(10*SECONDS));
            }
        }
        try {
            return client.execute(method);
        } finally {
            synchronized(getSynchronizer()) {
                if (sleepAllowed) {
                    SyncManager.runAwake(mMailboxId);
                }
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
                    Header header = resp.getFirstHeader("MS-ASProtocolCommands");
                    userLog(header.getValue());
                    header = resp.getFirstHeader("ms-asprotocolversions");
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
                     if (len != 0) {
                         InputStream is = entity.getContent();
                         // Returns true if we need to sync again
                         if (new FolderSyncParser(is, new AccountSyncAdapter(mMailbox, this))
                                 .parse()) {
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
        errorLog("*** PING ERROR LOOP: Set " + mailbox.mDisplayName + " to " + mins + " min sync");
        SyncManager.kick("push fallback");
    }

    void runPingLoop() throws IOException, StaleFolderListException {
        int pingHeartbeat = mPingHeartbeat;
        userLog("runPingLoop");
        // Do push for all sync services here
        long endTime = System.currentTimeMillis() + (30*MINUTES);
        HashMap<String, Integer> pingErrorMap = new HashMap<String, Integer>();
        ArrayList<String> readyMailboxes = new ArrayList<String>();
        ArrayList<String> notReadyMailboxes = new ArrayList<String>();
        int pingWaitCount = 0;
        
        while ((System.currentTimeMillis() < endTime) && !mStop) {
            // Count of pushable mailboxes
            int pushCount = 0;
            // Count of mailboxes that can be pushed right now
            int canPushCount = 0;
            // Count of uninitialized boxes
            int uninitCount = 0;
            
            Serializer s = new Serializer();
            Cursor c = mContentResolver.query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                    MailboxColumns.ACCOUNT_KEY + '=' + mAccount.mId +
                    AND_FREQUENCY_PING_PUSH_AND_NOT_ACCOUNT_MAILBOX, null, null);
            notReadyMailboxes.clear();
            readyMailboxes.clear();
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
                        if ((syncKey == null) || syncKey.equals("0")) {
                            // We can't push until the initial sync is done
                            pushCount--;
                            uninitCount++;
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
                        readyMailboxes.add(mailboxName);
                    } else if ((pingStatus == SyncManager.PING_STATUS_RUNNING) ||
                            (pingStatus == SyncManager.PING_STATUS_WAITING)) {
                        notReadyMailboxes.add(mailboxName);
                    } else if (pingStatus == SyncManager.PING_STATUS_UNABLE) {
                        pushCount--;
                        userLog(mailboxName, " in error state; ignore");
                        continue;
                    }
                }
            } finally {
                c.close();
            }

            if (Eas.USER_LOG) {
                if (!notReadyMailboxes.isEmpty()) {
                    userLog("Ping not ready for: " + notReadyMailboxes);
                }
                if (!readyMailboxes.isEmpty()) {
                    userLog("Ping ready for: " + readyMailboxes);
                }
            }
            
            // If we've waited 10 seconds or more, just ping with whatever boxes are ready
            // But use a shorter than normal heartbeat
            boolean forcePing = !notReadyMailboxes.isEmpty() && (pingWaitCount > 5);

            if ((canPushCount > 0) && ((canPushCount == pushCount) || forcePing)) {
                // If all pingable boxes are ready for push, send Ping to the server
                s.end().end().done();
                pingWaitCount = 0;

                // If we've been stopped, this is a good time to return
                if (mStop) return;

                long pingTime = SystemClock.elapsedRealtime();
                try {
                    // Send the ping, wrapped by appropriate timeout/alarm
                    if (forcePing) {
                        userLog("Forcing ping after waiting for all boxes to be ready");
                    }
                    HttpResponse res =
                        sendPing(s.toByteArray(), forcePing ? PING_FORCE_HEARTBEAT : pingHeartbeat);

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
                        if (len != 0) {
                            int pingResult = parsePingResult(is, mContentResolver, pingErrorMap);
                            // If our ping completed (status = 1), and we weren't forced and we're
                            // not at the maximum, try increasing timeout by two minutes
                            if (pingResult == PROTOCOL_PING_STATUS_COMPLETED && !forcePing) {
                                if (pingHeartbeat > mPingHighWaterMark) {
                                    mPingHighWaterMark = pingHeartbeat;
                                    userLog("Setting high water mark at: ", mPingHighWaterMark);
                                }
                                if ((pingHeartbeat < PING_MAX_HEARTBEAT) &&
                                        !mPingHeartbeatDropped) {
                                    pingHeartbeat += PING_HEARTBEAT_INCREMENT;
                                    if (pingHeartbeat > PING_MAX_HEARTBEAT) {
                                        pingHeartbeat = PING_MAX_HEARTBEAT;
                                    }
                                    userLog("Increasing ping heartbeat to ", pingHeartbeat, "s");
                                }
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
                    String message = e.getMessage();
                    // If we get the exception that is indicative of a NAT timeout and if we
                    // haven't yet "fixed" the timeout, back off by two minutes and "fix" it
                    boolean hasMessage = message != null;
                    userLog("IOException runPingLoop: " + (hasMessage ? message : "[no message]"));
                    if (mAborted || (hasMessage && message.contains("reset by peer"))) {
                        long pingLength = SystemClock.elapsedRealtime() - pingTime;
                        if ((pingHeartbeat > PING_MIN_HEARTBEAT) &&
                                (pingHeartbeat > mPingHighWaterMark)) {
                            pingHeartbeat -= PING_HEARTBEAT_INCREMENT;
                            mPingHeartbeatDropped = true;
                            if (pingHeartbeat < PING_MIN_HEARTBEAT) {
                                pingHeartbeat = PING_MIN_HEARTBEAT;
                            }
                            userLog("Decreased ping heartbeat to ", pingHeartbeat, "s");
                        } else if (mAborted || (pingLength < 2000)) {
                            userLog("Abort or NAT type return < 2 seconds; throwing IOException");
                            throw e;
                        } else {
                            userLog("NAT type IOException > 2 seconds?");
                        }
                    } else {
                        throw e;
                    }
                }
            } else if (forcePing) {
                // In this case, there aren't any boxes that are pingable, but there are boxes
                // waiting (for IOExceptions)
                userLog("pingLoop waiting 60s for any pingable boxes");
                sleep(60*SECONDS, true);
            } else if (pushCount > 0) {
                // If we want to Ping, but can't just yet, wait a little bit
                // TODO Change sleep to wait and use notify from SyncManager when a sync ends
                sleep(2*SECONDS, false);
                pingWaitCount++;
                //userLog("pingLoop waited 2s for: ", (pushCount - canPushCount), " box(es)");
            } else if (uninitCount > 0) {
                // In this case, we're doing an initial sync of at least one mailbox.  Since this
                // is typically a one-time case, I'm ok with trying again every 10 seconds until
                // we're in one of the other possible states.
                userLog("pingLoop waiting for initial sync of ", uninitCount, " box(es)");
                sleep(10*SECONDS, true);
            } else {
                // We've got nothing to do, so we'll check again in 30 minutes at which time
                // we'll update the folder list.  Let the device sleep in the meantime...
                userLog("pingLoop sleeping for 30m");
                sleep(30*MINUTES, true);
            }
        }
    }

    void sleep(long ms, boolean runAsleep) {
        if (runAsleep) {
            SyncManager.runAsleep(mMailboxId, ms+(5*SECONDS));
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Doesn't matter whether we stop early; it's the thought that counts
        } finally {
            if (runAsleep) {
                SyncManager.runAwake(mMailboxId);
            }
        }
    }

    private int parsePingResult(InputStream is, ContentResolver cr,
            HashMap<String, Integer> errorMap)
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

                        /**
                         * Check the boxes reporting changes to see if there really were any...
                         * We do this because bugs in various Exchange servers can put us into a
                         * looping behavior by continually reporting changes in a mailbox, even when
                         * there aren't any.
                         *
                         * This behavior is seemingly random, and therefore we must code defensively
                         * by backing off of push behavior when it is detected.
                         *
                         * One known cause, on certain Exchange 2003 servers, is acknowledged by
                         * Microsoft, and the server hotfix for this case can be found at
                         * http://support.microsoft.com/kb/923282
                         */

                        // Check the status of the last sync
                        String status = c.getString(Mailbox.CONTENT_SYNC_STATUS_COLUMN);
                        int type = SyncManager.getStatusType(status);
                        // This check should always be true...
                        if (type == SyncManager.SYNC_PING) {
                            int changeCount = SyncManager.getStatusChangeCount(status);
                            if (changeCount > 0) {
                                errorMap.remove(serverId);
                            } else if (changeCount == 0) {
                                // This means that a ping reported changes in error; we keep a count
                                // of consecutive errors of this kind
                                String name = c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN);
                                Integer failures = errorMap.get(serverId);
                                if (failures == null) {
                                    userLog("Last ping reported changes in error for: ", name);
                                    errorMap.put(serverId, 1);
                                } else if (failures > MAX_PING_FAILURES) {
                                    // We'll back off of push for this box
                                    pushFallback(c.getLong(Mailbox.CONTENT_ID_COLUMN));
                                    continue;
                                } else {
                                    userLog("Last ping reported changes in error for: ", name);
                                    errorMap.put(serverId, failures + 1);
                                }
                            }
                        }

                        // If there were no problems with previous sync, we'll start another one
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
            String className = target.getCollectionName();
            String syncKey = target.getSyncKey();
            userLog("sync, sending ", className, " syncKey: ", syncKey);
            s.start(Tags.SYNC_SYNC)
                .start(Tags.SYNC_COLLECTIONS)
                .start(Tags.SYNC_COLLECTION)
                .data(Tags.SYNC_CLASS, className)
                .data(Tags.SYNC_SYNC_KEY, syncKey)
                .data(Tags.SYNC_COLLECTION_ID, mailbox.mServerId)
                .tag(Tags.SYNC_DELETES_AS_MOVES);

            // EAS doesn't like GetChanges if the syncKey is "0"; not documented
            if (!syncKey.equals("0")) {
                s.tag(Tags.SYNC_GET_CHANGES);
            }
            s.data(Tags.SYNC_WINDOW_SIZE,
                    className.equals("Email") ? EMAIL_WINDOW_SIZE : PIM_WINDOW_SIZE);

            // Handle options
            s.start(Tags.SYNC_OPTIONS);
            // Set the lookback appropriately (EAS calls this a "filter") for all but Contacts
            if (!className.equals("Contacts")) {
                s.data(Tags.SYNC_FILTER_TYPE, getFilterType());
            }
            // Set the truncation amount for all classes
            if (mProtocolVersionDouble >= 12.0) {
                s.start(Tags.BASE_BODY_PREFERENCE)
                    // HTML for email; plain text for everything else
                    .data(Tags.BASE_TYPE, (className.equals("Email") ? Eas.BODY_PREFERENCE_HTML
                        : Eas.BODY_PREFERENCE_TEXT))
                    .data(Tags.BASE_TRUNCATION_SIZE, Eas.EAS12_TRUNCATION_SIZE)
                    .end();
            } else {
                s.data(Tags.SYNC_TRUNCATION, Eas.EAS2_5_TRUNCATION_SIZE);
            }
            s.end();

            // Send our changes up to the server
            target.sendLocalChanges(s);

            s.end().end().end().done();
            HttpResponse resp = sendHttpClientPost("Sync", s.toByteArray());
            int code = resp.getStatusLine().getStatusCode();
            if (code == HttpStatus.SC_OK) {
                InputStream is = resp.getEntity().getContent();
                if (is != null) {
                    moreAvailable = target.parse(is);
                    target.cleanup();
                } else {
                    userLog("Empty input stream in sync command response");
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

    protected void setupService() {
        // Make sure account and mailbox are always the latest from the database
        mAccount = Account.restoreAccountWithId(mContext, mAccount.mId);
        mMailbox = Mailbox.restoreMailboxWithId(mContext, mMailbox.mId);

        mThread = Thread.currentThread();
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        TAG = mThread.getName();

        HostAuth ha = HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
        mHostAddress = ha.mAddress;
        mUserName = ha.mLogin;
        mPassword = ha.mPassword;
    }

    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    public void run() {
        setupService();

        try {
            SyncManager.callback().syncMailboxStatus(mMailboxId, EmailServiceStatus.IN_PROGRESS, 0);
        } catch (RemoteException e1) {
            // Don't care if this fails
        }

        // Whether or not we're the account mailbox
        try {
            mDeviceId = SyncManager.getDeviceId();
            if ((mMailbox == null) || (mAccount == null)) {
                return;
            } else if (mMailbox.mType == Mailbox.TYPE_EAS_ACCOUNT_MAILBOX) {
                runAccountMailbox();
            } else {
                AbstractSyncAdapter target;
                mProtocolVersion = mAccount.mProtocolVersion;
                mProtocolVersionDouble = Double.parseDouble(mProtocolVersion);
                if (mMailbox.mType == Mailbox.TYPE_CONTACTS) {
                    target = new ContactsSyncAdapter(mMailbox, this);
                } else {
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
            userLog("Caught IOException: ", ((message == null) ? "No message" : message));
            mExitStatus = EXIT_IO_ERROR;
        } catch (Exception e) {
            userLog("Uncaught exception in EasSyncService", e);
        } finally {
            if (!mStop) {
                userLog("Sync finished");
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

                if (mExitStatus == EXIT_DONE) {
                    // Save the sync time and status
                    ContentValues cv = new ContentValues();
                    cv.put(Mailbox.SYNC_TIME, System.currentTimeMillis());
                    String s = "S" + mSyncReason + ':' + status + ':' + mChangeCount;
                    cv.put(Mailbox.SYNC_STATUS, s);
                    mContentResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI,
                            mMailboxId), cv, null, null);
                }
            } else {
                userLog("Stopped sync finished.");
            }

            // Make sure SyncManager knows about this
            SyncManager.kick("sync finished");
       }
    }
}
