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

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.impl.client.DefaultHttpClient;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;

import javax.net.ssl.HttpsURLConnection;

public class EasSyncService extends InteractiveSyncService {

    private static final String WINDOW_SIZE = "10";
    private static final String WHERE_ACCOUNT_KEY_AND_SERVER_ID =
        MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.SERVER_ID + "=?";
    private static final String WHERE_SYNC_INTERVAL_PING =
        Mailbox.SYNC_INTERVAL + '=' + Account.CHECK_INTERVAL_PING;
    private static final String AND_FREQUENCY_PING_PUSH_AND_NOT_ACCOUNT_MAILBOX = " AND " +
        MailboxColumns.SYNC_INTERVAL + " IN (" + Account.CHECK_INTERVAL_PING +
        ',' + Account.CHECK_INTERVAL_PUSH + ") AND " + MailboxColumns.TYPE + "!=\"" +
        Mailbox.TYPE_EAS_ACCOUNT_MAILBOX + '\"';

    static private final int CHUNK_SIZE = 16 * 1024;

    // Reasonable default
    String mProtocolVersion = "2.5";
    public Double mProtocolVersionDouble;
    static String mDeviceId = null;
    static String mDeviceType = "Android";
    AbstractSyncAdapter mTarget;
    String mAuthString = null;
    String mCmdString = null;
    String mVersions;
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
    private volatile boolean mStop = false;
    private Object mWaitTarget = new Object();

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
        synchronized (mWaitTarget) {
            mWaitTarget.notify();
        }
    }

    @Override
    public void stop() {
        mStop = true;
     }

    @Override
    public int getSyncStatus() {
        return 0;
    }

    /* (non-Javadoc)
     * @see com.android.exchange.SyncService#validateAccount(java.lang.String, java.lang.String, java.lang.String, int, boolean, android.content.Context)
     */
    @Override
    public void validateAccount(String hostAddress, String userName, String password, int port,
            boolean ssl, Context context) throws MessagingException {
        try {
            userLog("Testing EAS: " + hostAddress + ", " + userName + ", ssl = " + ssl);
            Serializer s = new Serializer();
            s.start(Tags.FOLDER_FOLDER_SYNC).start(Tags.FOLDER_SYNC_KEY).text("0")
                .end().end().done();
            EasSyncService svc = new EasSyncService("%TestAccount%");
            svc.mHostAddress = hostAddress;
            svc.mUserName = userName;
            svc.mPassword = password;
            svc.mSsl = ssl;
            HttpURLConnection uc = svc.sendEASPostCommand("FolderSync", s.toString());
            int code = uc.getResponseCode();
            userLog("Validation response code: " + code);
            if (code == HttpURLConnection.HTTP_OK) {
                // No exception means successful validation
                userLog("Validation successful");
                return;
            }
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    code == HttpURLConnection.HTTP_FORBIDDEN) {
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


    @Override
    public void loadAttachment(Attachment att, IEmailServiceCallback cb) {
        // TODO Auto-generated method stub
    }

    @Override
    public void reloadFolderList() {
        // TODO Auto-generated method stub
    }

    @Override
    public void startSync() {
        // TODO Auto-generated method stub
    }

    @Override
    public void stopSync() {
        // TODO Auto-generated method stub
    }

    protected HttpURLConnection sendEASPostCommand(String cmd, String data) throws IOException {
        HttpURLConnection uc = setupEASCommand("POST", cmd);
        if (uc != null) {
            uc.setRequestProperty("Content-Length", Integer.toString(data.length() + 2));
            OutputStreamWriter w = new OutputStreamWriter(uc.getOutputStream(), "UTF-8");
            w.write(data);
            w.write("\r\n");
            w.flush();
            w.close();
        }
        return uc;
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

    private HttpURLConnection setupEASCommand(String method, String cmd) throws IOException {
        return setupEASCommand(method, cmd, null);
    }

    @SuppressWarnings("deprecation")
    private String makeUriString(String cmd, String extra) {
         // Cache the authentication string and the command string
        if (mDeviceId == null)
            mDeviceId = "droidfu";
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

    private HttpURLConnection setupEASCommand(String method, String cmd, String extra)
            throws IOException {
        try {
            String us = makeUriString(cmd, extra);
            URL u = new URL(us);
            HttpURLConnection uc = (HttpURLConnection)u.openConnection();
            HttpURLConnection.setFollowRedirects(true);

            if (mSsl) {
                ((HttpsURLConnection)uc).setHostnameVerifier(new AllowAllHostnameVerifier());
            }

            uc.setConnectTimeout(10 * SECS);
            uc.setReadTimeout(20 * MINS);
            if (method.equals("POST")) {
                uc.setDoOutput(true);
            }
            uc.setRequestMethod(method);
            uc.setRequestProperty("Authorization", mAuthString);

            if (extra == null) {
                if (cmd != null && cmd.startsWith("SendMail&")) {
                    uc.setRequestProperty("Content-Type", "message/rfc822");
                } else {
                    uc.setRequestProperty("Content-Type", "application/vnd.ms-sync.wbxml");
                }
                uc.setRequestProperty("MS-ASProtocolVersion", mProtocolVersion);
                uc.setRequestProperty("Connection", "keep-alive");
                uc.setRequestProperty("User-Agent", mDeviceType + '/' + Eas.VERSION);
            } else {
                uc.setRequestProperty("Content-Length", "0");
            }

            return uc;
        } catch (MalformedURLException e) {
            // TODO See if there is a better exception to throw here and below
            throw new IOException();
        } catch (ProtocolException e) {
            throw new IOException();
        }
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
                userLog("Account syncKey RESET");
                ContentValues cv = new ContentValues();
                cv.put(AccountColumns.SYNC_KEY, mAccount.mSyncKey);
                mAccount.update(mContext, cv);
            }

            // When we first start up, change all ping mailboxes to push.
            ContentValues cv = new ContentValues();
            cv.put(Mailbox.SYNC_INTERVAL, Account.CHECK_INTERVAL_PUSH);
            if (mContentResolver.update(Mailbox.CONTENT_URI, cv,
                    WHERE_SYNC_INTERVAL_PING, null) > 0) {
                SyncManager.kick();
            }

            userLog("Account syncKey: " + mAccount.mSyncKey);
            // Determine our protocol version, if we haven't already
            if (mAccount.mProtocolVersion == null) {
                HttpURLConnection uc = setupEASCommand("OPTIONS", null);
                if (uc != null) {
                    int code = uc.getResponseCode();
                    userLog("OPTIONS response: " + code);
                    if (code == HttpURLConnection.HTTP_OK) {
                        mVersions = uc.getHeaderField("ms-asprotocolversions");
                        if (mVersions != null) {
                            if (mVersions.contains("12.0")) {
                                mProtocolVersion = "12.0";
                            }
                            mProtocolVersionDouble = Double.parseDouble(mProtocolVersion);
                            mAccount.mProtocolVersion = mProtocolVersion;
                            userLog(mVersions);
                            userLog("Using version " + mProtocolVersion);
                        } else {
                            throw new IOException();
                        }
                    } else {
                        userLog("OPTIONS command failed; throwing IOException");
                        throw new IOException();
                    }
                }
            }
            while (!mStop) {
                Serializer s = new Serializer();
                s.start(Tags.FOLDER_FOLDER_SYNC).start(Tags.FOLDER_SYNC_KEY)
                    .text(mAccount.mSyncKey).end().end().done();
                HttpURLConnection uc = sendEASPostCommand("FolderSync", s.toString());
                int code = uc.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    String encoding = uc.getHeaderField("Transfer-Encoding");
                    if (encoding == null) {
                        int len = uc.getHeaderFieldInt("Content-Length", 0);
                        if (len > 0) {
                            InputStream is = uc.getInputStream();
                            // Returns true if we need to sync again
                            if (new FolderSyncParser(is, this).parse()) {
                                continue;
                            }
                        }
                    } else if (encoding.equalsIgnoreCase("chunked")) {
                        // TODO We don't handle this yet
                    }
                } else if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                        code == HttpURLConnection.HTTP_FORBIDDEN) {
                    mExitStatus = AbstractSyncService.EXIT_LOGIN_FAILURE;
                } else {
                    userLog("FolderSync response error: " + code);
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
            throw new IOException();
        }
    }

    void runPingLoop() throws IOException, StaleFolderListException {
        // Do push for all sync services here
        long endTime = System.currentTimeMillis() + (30*MINS);
        long lastPingTime = 0;

        while (System.currentTimeMillis() < endTime) {
            // Count of pushable mailboxes
            int pushCount = 0;
            // Count of mailboxes that can be pushed right now
            int canPushCount = 0;
            Serializer s = new Serializer();
            HttpURLConnection uc;
            int code;
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
                    if (SyncManager.canSync(c.getLong(Mailbox.CONTENT_ID_COLUMN))) {
                        String syncKey = c.getString(Mailbox.CONTENT_SYNC_KEY_COLUMN);
                        if (syncKey == null || syncKey.equals("0")) {
                            continue;
                        }
                        if (canPushCount++ == 0) {
                            // Initialize the Ping command
                            s.start(Tags.PING_PING).data(Tags.PING_HEARTBEAT_INTERVAL, "900")
                                .start(Tags.PING_FOLDERS);
                        }
                        // When we're ready for Calendar/Contacts, we will check folder type
                        // TODO Save Calendar and Contacts!! Mark as not visible!
                        String folderClass = getTargetCollectionClassFromCursor(c);
                        s.start(Tags.PING_FOLDER)
                            .data(Tags.PING_ID, c.getString(Mailbox.CONTENT_SERVER_ID_COLUMN))
                            .data(Tags.PING_CLASS, folderClass)
                            .end();
                        userLog("Ping ready for: " + folderClass + ", " +
                                c.getString(Mailbox.CONTENT_SERVER_ID_COLUMN) + " (" +
                                c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN) + ')');
                    } else {
                        userLog(c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN) +
                                " not ready for ping");
                    }
                }
            } finally {
                c.close();
            }

            if (canPushCount > 0 && (canPushCount == pushCount)) {
                // If we have some number that are ready for push, send Ping to the server
                s.end().end().done();

                long time = System.currentTimeMillis();
                long timeSinceLastPing = time - lastPingTime;
                if (timeSinceLastPing < 30*SECS) {
                    try {
                        userLog("Waiting to send ping...");
                        Thread.sleep(30*SECS - timeSinceLastPing);
                    } catch (InterruptedException e) {
                        // No need for action
                    }
                }
                uc = sendEASPostCommand("Ping", s.toString());
                Thread.currentThread().setName(mAccount.mDisplayName + ": Ping");
                userLog("Sending ping, timeout: " + uc.getReadTimeout() / 1000 + "s");
                // Don't send request if we've been asked to stop
                if (mStop) return;
                code = uc.getResponseCode();
                // Return immediately if we've been asked to stop
                if (mStop) {
                    userLog("Stooping pingLoop");
                    return;
                }
                userLog("Ping response: " + code);
                if (code == HttpURLConnection.HTTP_OK) {
                    String encoding = uc.getHeaderField("Transfer-Encoding");
                    if (encoding == null) {
                        int len = uc.getHeaderFieldInt("Content-Length", 0);
                        if (len > 0) {
                            if (parsePingResult(uc, mContentResolver) > 0) {
                                lastPingTime = time;
                            }
                        } else {
                            // This implies a connection issue that we can't handle
                            throw new IOException();
                        }
                    } else {
                        // It shouldn't be possible for EAS server to send chunked data here
                        throw new IOException();
                    }
                } else if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                        code == HttpURLConnection.HTTP_FORBIDDEN) {
                    mExitStatus = AbstractSyncService.EXIT_LOGIN_FAILURE;
                    userLog("Authorization error during Ping: " + code);
                    throw new IOException();
                }
            } else if (pushCount > 0) {
                // If we want to Ping, but can't just yet, wait 10 seconds and try again
                userLog("pingLoop waiting for " + (pushCount - canPushCount) + " box(es)");
                sleep(10*SECS);
            } else {
                // We've got nothing to do, so let's hang out for a while
                sleep(10*MINS);
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

    private int parsePingResult(HttpURLConnection uc, ContentResolver cr)
        throws IOException, StaleFolderListException {
        PingParser pp = new PingParser(uc.getInputStream(), this);
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
                                null, "Ping");
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

    /**
     * EAS requires a unique device id, so that sync is possible from a variety of different
     * devices (e.g. the syncKey is specific to a device)  If we're on an emulator or some other
     * device that doesn't provide one, we can create it as droid<n> where <n> is system time.
     * This would work on a real device as well, but it would be better to use the "real" id if
     * it's available
     */
    private String getSimulatedDeviceId() {
        try {
            File f = mContext.getFileStreamPath("deviceName");
            BufferedReader rdr = null;
            String id;
            if (f.exists() && f.canRead()) {
                rdr = new BufferedReader(new FileReader(f), 128);
                id = rdr.readLine();
                rdr.close();
                return id;
            } else if (f.createNewFile()) {
                BufferedWriter w = new BufferedWriter(new FileWriter(f));
                id = "droid" + System.currentTimeMillis();
                w.write(id);
                w.close();
            }
        } catch (FileNotFoundException e) {
            // We'll just use the default below
        } catch (IOException e) {
            // We'll just use the default below
        }
        return "droid0";
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
            runAwake();
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
                mailbox.mSyncInterval = Account.CHECK_INTERVAL_PUSH;
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
            s.data(Tags.SYNC_WINDOW_SIZE, WINDOW_SIZE);
            boolean options = false;
            if (!className.equals("Contacts")) {
                // Set the lookback appropriately (EAS calls this a "filter")
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
                s.start(Tags.SYNC_OPTIONS).data(Tags.SYNC_FILTER_TYPE, filter);
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
                    .data(Tags.BASE_TYPE, Eas.BODY_PREFERENCE_HTML)
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
            HttpURLConnection uc = sendEASPostCommand("Sync", s.toString());
            int code = uc.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                ByteArrayInputStream is = readResponse(uc);
                if (is != null) {
                    moreAvailable = target.parse(is, this);
                    target.cleanup(this);
                }
            } else {
                userLog("Sync response error: " + code);
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                        code == HttpURLConnection.HTTP_FORBIDDEN) {
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
        mDeviceId = android.provider.Settings.Secure.getString(mContext.getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        // Generate a device id if we don't have one
        if (mDeviceId == null) {
            mDeviceId = getSimulatedDeviceId();
        }
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
        boolean accountMailbox = false;
        try {
            if (mMailbox == null || mAccount == null) {
                return;
            } else if (mMailbox.mType == Mailbox.TYPE_EAS_ACCOUNT_MAILBOX) {
                accountMailbox = true;
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
            userLog("Caught IOException");
            mExitStatus = EXIT_IO_ERROR;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (!mStop) {
                userLog(mMailbox.mDisplayName + ": sync finished");
                SyncManager.done(this);
                // If this is the account mailbox, wake up SyncManager
                // Because this box has a "push" interval, it will be restarted immediately
                // which will cause the folder list to be reloaded...
                if (accountMailbox) {
                    SyncManager.kick();
                }
                try {
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
                            break;
                    }
                    SyncManager.callback().syncMailboxStatus(mMailboxId, status, 0);
                } catch (RemoteException e1) {
                    // Don't care if this fails
                }
            } else {
                userLog(mMailbox.mDisplayName + ": stopped thread finished.");
            }
        }
    }
}
