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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

import java.util.Hashtable;

import javax.net.ssl.HttpsURLConnection;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.impl.client.DefaultHttpClient;

import com.android.email.Account;
import com.android.email.mail.AuthenticationFailedException;
import com.android.email.mail.MessagingException;
import com.android.exchange.EmailContent.AttachmentColumns;
import com.android.exchange.EmailContent.HostAuth;
import com.android.exchange.EmailContent.Mailbox;
import com.android.exchange.EmailContent.Message;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

public class EasService extends ProtocolService {

    public static final String TAG = "EasService";

    private static final String WINDOW_SIZE = "10";

    //    From EAS spec 
    //                  Mail  Cal
    //    0 No filter    Yes  Yes
    //    1 1 day ago    Yes  No
    //    2 3 days ago   Yes  No
    //    3 1 week ago   Yes  No
    //    4 2 weeks ago  Yes  Yes
    //    5 1 month ago  Yes  Yes
    //    6 3 months ago No   Yes
    //    7 6 months ago No   Yes

    private static final String FILTER_ALL = "0";
    private static final String FILTER_1_DAY = "1";
    private static final String FILTER_3_DAYS = "2";
    private static final String FILTER_1_WEEK = "3";
    private static final String FILTER_2_WEEKS = "4";
    private static final String FILTER_1_MONTH = "5";
    //private static final String FILTER_3_MONTHS = "6";
    //private static final String FILTER_6_MONTHS = "7";

    private static final String BODY_PREFERENCE_TEXT = "1";
    //private static final String BODY_PREFERENCE_HTML = "2";

    // Reasonable to be static for now
    static String mProtocolVersion = "12.0"; //"2.5";
    static String mDeviceId = null;
    static String mDeviceType = "Android";

    String mAuthString = null;
    String mCmdString = null;
    String mVersions;
    String mHostAddress;
    String mUserName;
    String mPassword;
    boolean mSentCommands;
    boolean mIsIdle = false;
    Context mContext;
    InputStream mPendingPartInputStream = null;

    private boolean mStop = false;
    private Object mWaitTarget = new Object();

    public EasService (Context _context, Mailbox _mailbox) {
        // A comment
        super(_context, _mailbox);
        mContext = _context;
    }

    private EasService (String prefix) {
        super(prefix);
    }

    public EasService () {
        this("EAS Validation");
    }

    @Override
    public void ping() {
        // TODO Auto-generated method stub
        log("We've been pinged!");
        synchronized (mWaitTarget) {
            mWaitTarget.notify();
        }
    }

    @Override
    public void stop() {
        // TODO Auto-generated method stub
        mStop = true;
    }

    public int getSyncStatus () {
        return 0;
    }

    public void validateAccount (String hostAddress, String userName, String password,
            int port, boolean ssl, Context context) throws MessagingException {
        try {
            log("Testing EAS: " + hostAddress + ", " + userName + ", ssl = " + ssl);
            EASSerializer s = new EASSerializer();
            s.start("FolderSync").start("FolderSyncKey").text("0").end("FolderSyncKey")
                .end("FolderSync").end();
            String data = s.toString();
            EasService svc = new EasService("%TestAccount%");
            svc.mHostAddress = hostAddress;
            svc.mUserName = userName;
            svc.mPassword = password;
            HttpURLConnection uc = svc.sendEASPostCommand("FolderSync", data);
            int code = uc.getResponseCode();
            Log.v(TAG, "Validation response code: " + code);
            if (code == HttpURLConnection.HTTP_OK) {
                return;
            }
            if (code == 401 || code == 403) {
                Log.v(TAG, "Authentication failed");
                throw new AuthenticationFailedException("Validation failed");
            }
            else {
                //TODO Need to catch other kinds of errors (e.g. policy related)
                Log.v(TAG, "Validation failed, reporting I/O error");
                throw new MessagingException(MessagingException.IOERROR);
            }
        } catch (IOException e) {
            Log.v(TAG, "IOException caught, reporting I/O error: " + e.getMessage());
            throw new MessagingException(MessagingException.IOERROR);
        }

    }

    protected HttpURLConnection sendEASPostCommand (String cmd, String data) throws IOException {
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

    static private final int CHUNK_SIZE = 16*1024;

    protected void getAttachment (PartRequest req) throws IOException {
        DefaultHttpClient client = new DefaultHttpClient();
        String us = makeUriString("GetAttachment", "&AttachmentName=" + req.att.mLocation);
        HttpPost method = new HttpPost(URI.create(us));
        method.setHeader("Authorization", mAuthString);

        HttpResponse res = client.execute(method);
        int status = res.getStatusLine().getStatusCode();
        if (status == HttpURLConnection.HTTP_OK) {
            HttpEntity e = res.getEntity();
            int len = (int)e.getContentLength();
            String type = e.getContentType().getValue();
            Log.v(TAG, "Attachment code: " + status + ", Length: " + len + ", Type: " + type);
            InputStream is = res.getEntity().getContent();
            File f = null; //Attachment.openAttachmentFile(req);
            if (f != null) {
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
                            if (req.handler != null) {
                                long pct = ((length - len) * 100 / length);
                                req.handler.sendEmptyMessage((int)pct);
                            }
                        }
                    } finally {
                        mPendingPartRequest = null;
                        mPendingPartInputStream = null;
                    }
                }
                os.flush();
                os.close();

                ContentValues cv = new ContentValues();
                cv.put(AttachmentColumns.CONTENT_URI, f.getAbsolutePath());
                cv.put(AttachmentColumns.MIME_TYPE, type);
                req.att.update(mContext, cv);
                // TODO Inform UI that we're done
            }
        }
    }

    private HttpURLConnection setupEASCommand (String method, String cmd) {
        return setupEASCommand(method, cmd, null);
    }

    private String makeUriString (String cmd, String extra) {
        if (mAuthString == null) {
            String cs = mUserName + ':' + mPassword;
            mAuthString = "Basic " + Base64.encodeBytes(cs.getBytes());
            mCmdString = "&User=" + mUserName + "&DeviceId=" + mDeviceId
            + "&DeviceType=" + mDeviceType;
        }

        boolean ssl = true;

        // TODO Remove after testing
        if (mHostAddress.equalsIgnoreCase("owa.electricmail.com")) {
            ssl = false;
        }

        String scheme = ssl ? "https" : "http";
        String us = scheme + "://" + mHostAddress + "/Microsoft-Server-ActiveSync";
        if (cmd != null) {
            us += "?Cmd=" + cmd + mCmdString;
        }
        if (extra != null) {
            us += extra;
        }
        return us;
    }

    private HttpURLConnection setupEASCommand (String method, String cmd, String extra) {

        // Hack for now
        boolean ssl = true;

        // TODO Remove this when no longer needed
        if (mHostAddress.equalsIgnoreCase("owa.electricmail.com")) {
            ssl = false;
        }

        try {
            String us = makeUriString(cmd, extra);
            URL u = new URL(us);
            HttpURLConnection uc = (HttpURLConnection)u.openConnection();
            try {
                HttpURLConnection.setFollowRedirects(true);
            } catch (Exception e) {
            }

            if (ssl) {
                ((HttpsURLConnection)uc).setHostnameVerifier(new AllowAllHostnameVerifier());
            }

            uc.setConnectTimeout(10*SECS);
            uc.setReadTimeout(20*MINS);
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
                uc.setRequestProperty("User-Agent", mDeviceType + "/0.3");
            } else {
                uc.setRequestProperty("Content-Length", "0");
            }

            return uc;
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (ProtocolException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    static class EASSerializer extends WbxmlSerializer {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        static Hashtable<String, Object> tagTable = null;

        EASSerializer () {
            super();
            try {
                setOutput(byteStream, null);
                if (tagTable == null) {
                    String[][] pages = EasTags.pages;
                    for (int i = 0; i < pages.length; i++) {
                        String[] page = pages[i];
                        if (page.length > 0)
                            setTagTable(i, page);
                    }
                    tagTable = getTagTable();
                } else {
                    setTagTable(tagTable);
                }
                startDocument("UTF-8", false);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        EASSerializer start (String tag) throws IOException {
            startTag(null, tag);
            return this;
        }

        EASSerializer end (String tag) throws IOException {
            endTag(null, tag);
            return this;
        }

        EASSerializer end () throws IOException {
            endDocument();
            return this;
        }

        EASSerializer data (String tag, String value) throws IOException {
            startTag(null, tag);
            text(value);
            endTag(null, tag);
            return this;
        }

        EASSerializer tag (String tag) throws IOException {
            startTag(null, tag);
            endTag(null, tag);
            return this;
        }

        public EASSerializer text (String str) throws IOException {
            super.text(str);
            return this;
        }

        ByteArrayOutputStream getByteStream () {
            return byteStream;
        }

        public String toString () {
            return byteStream.toString();
        }
    }

    public void runMain () {
        try {
            if (mAccount.mSyncKey == null) {
                mAccount.mSyncKey = "0";
                Log.w(TAG, "Account syncKey RESET");
                mAccount.saveOrUpdate(mContext);
            }
            Log.v(TAG, "Account syncKey: " + mAccount.mSyncKey);
            HttpURLConnection uc = setupEASCommand("OPTIONS", null);
            if (uc != null) {
                int code = uc.getResponseCode();
                Log.v(TAG, "OPTIONS response: " + code);
                if (code == HttpURLConnection.HTTP_OK) {
                    mVersions = uc.getHeaderField("ms-asprotocolversions");
                    if (mVersions != null) {
                        // Determine which version we want to use..
                        //List<String> versions = new Chain(mVersions, ',').toList();
                        //if (versions.contains("12.0")) {
                        //    mProtocolVersion = "12.0";
                        //} else                             if (versions.contains("2.5"))
                        mProtocolVersion = "2.5";
                        Log.v(TAG, mVersions);
                    }
                    else {
                        String s = readResponseString(uc);
                        Log.e(TAG, "No EAS versions: " + s);
                    }

                    while (!mStop) {
                        EASSerializer s = new EASSerializer();
                        s.start("FolderSync").start("FolderSyncKey").text(mAccount.mSyncKey)
                            .end("FolderSyncKey").end("FolderSync").end();
                        String data = s.toString();
                        uc = sendEASPostCommand("FolderSync", data);
                        code = uc.getResponseCode();
                        Log.v(TAG, "FolderSync response code: " + code);
                        if (code == HttpURLConnection.HTTP_OK) {
                            String encoding = uc.getHeaderField("Transfer-Encoding");
                            if (encoding == null) {
                                int len = uc.getHeaderFieldInt("Content-Length", 0);
                                if (len > 0) {
                                    try {
                                        new EasFolderSyncParser(uc.getInputStream(), this).parse();
                                    } catch (IOException e) {
                                        // TODO Auto-generated catch block
                                        e.printStackTrace();
                                    }
                                }
                            } else if (encoding.equalsIgnoreCase("chunked")) {
                                // TODO We don't handle this yet
                            }
                        }

                        // For now, we'll just loop
                        try {
                            Thread.sleep(15*MINS);
                        } catch (InterruptedException e) {
                        }
                    }
                }
            }

        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    long handleLocalDeletes (EASSerializer s) throws IOException {
        long maxDeleteId = -1;
//        //**PROVIDER
//        Cursor c = Email.getLocalDeletedCursor(mDatabase, mMailboxId);
//        try {
//            if (c.moveToFirst()) {
//                s.start("Commands");
//                mSentCommands = true;
//                do {
//                    String serverId = c.getString(Email.MPN_UID_COLUMN);
//                    s.start("Delete").data("ServerId", serverId).end("Delete");
//                    mLogger.log("Sending delete of " + serverId);
//                    long id = c.getLong(Email.MPN_ID_COLUMN);
//                    if (id > maxDeleteId)
//                        maxDeleteId = id;
//                } while (c.moveToNext());
//            }
//        } finally {
//            c.close();
//        }
        return maxDeleteId;
    }

    void handleLocalMoves () throws IOException {
//        long maxMoveId = -1;
//
//        Cursor c = LocalChange.getCursorWhere(mDatabase, "mailbox=\"" + mMailbox.mServerId + "\" and type=" + LocalChange.MOVE_TYPE);
//        try {
//            if (c.moveToFirst()) {
//                EASSerializer s = new EASSerializer();
//                s.start("MoveItems");
//
//                do {
//                    s.start("Move").data("SrcMsgId", c.getString(LocalChange.EMAIL_ID_COLUMN)).data("SrcFldId", c.getString(LocalChange.MAILBOX_COLUMN)).data("DstFldId", c.getString(LocalChange.VALUE_COLUMN)).end("Move");
//                } while (c.moveToNext());
//
//                s.end("MoveItems").end();
//                HttpURLConnection uc = sendEASPostCommand("MoveItems", s.toString());
//                int code = uc.getResponseCode();
//                System.err.println("Response code: " + code);
//                if (code == HttpURLConnection.HTTP_OK) {
//                    ByteArrayInputStream is = readResponse(uc);
//                    if (is != null) {
//                        EASMoveParser p = new EASMoveParser(is, this);
//                        p.parse();
//                        if (maxMoveId > -1)
//                            LocalChange.deleteWhere(mDatabase, "_id<=" + maxMoveId + " AND mailbox=" + mMailboxId + " AND type=" + LocalChange.MOVE_TYPE);
//                    }
//                } else {
//                    // TODO What?
//                }
//            }
//        } finally {
//            c.close();
//        }
    }

    long handleLocalReads (EASSerializer s) throws IOException {
        Cursor c = mContext.getContentResolver().query(Message.CONTENT_URI, Message.LIST_PROJECTION, "mailboxKey=" + mMailboxId, null, null);
        long maxReadId = -1;
        try {
            //            if (c.moveToFirst()) {
            //                if (!mSentCommands) {
            //                    s.start("Commands");
            //                    mSentCommands = true;
            //                }
            //                do {
            //                    String serverId = c.getString(LocalChange.STRING_ARGS_COLUMN);
            //                    if (serverId == null) {
            //                        long id = c.getInt(LocalChange.EMAIL_ID_COLUMN);
            //                        Email.Message msg = Messages.restoreFromId(mContext, id);
            //                        serverId = msg.serverId;
            //                        if (serverId == null)
            //                            serverId = "0:0";
            //                    }
            //
            //                    String value = c.getString(LocalChange.VALUE_COLUMN);
            //                    s.start("Change").data("ServerId", serverId).start("ApplicationData").data("Read", value).end("ApplicationData").end("Change");
            //                    mLogger.log("Sending read of " + serverId + " = " + value);
            //                    long id = c.getLong(LocalChange.ID_COLUMN);
            //                    if (id > maxReadId)
            //                        maxReadId = id;
            //                } while (c.moveToNext());
            //            }
        } finally {
            c.close();
        }
        return maxReadId;
        //return -1;
    }

    ByteArrayInputStream readResponse (HttpURLConnection uc) throws IOException {
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

    String readResponseString (HttpURLConnection uc) throws IOException {
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

    private String getSimulatedDeviceId () {
        try {
            File f = mContext.getFileStreamPath("deviceName");
            BufferedReader rdr = null;
            String id;
            if (f.exists()) {
                rdr = new BufferedReader(new FileReader(f));
                id = rdr.readLine();
                rdr.close();
                return id;
            } else if (f.createNewFile()) {
                BufferedWriter w = new BufferedWriter(new FileWriter(f));
                id = "emu" + System.currentTimeMillis();
                w.write(id);
                w.close();
            }
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
        }
        return null;
    }

    public void run() {
        mThread = Thread.currentThread();
        mDeviceId = android.provider.Settings.System
        .getString(mContext.getContentResolver(), android.provider.Settings.System.ANDROID_ID);

        HostAuth ha = HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
        mHostAddress = ha.mAddress;
        mUserName = ha.mLogin;
        mPassword = ha.mPassword;

        if (mDeviceId == null)
            mDeviceId = getSimulatedDeviceId();
        Log.v(TAG, "Device id: " + mDeviceId);

        if (mMailbox.mServerId.equals("_main")) {
            runMain();
            return;
        }
        try {
            while (!mStop) {
                runAwake();
                waitForConnectivity();
                try {
//                    while (true) {
//                        PartRequest req = null;
//                        synchronized(mPartRequests) {
//                            if (mPartRequests.isEmpty()) {
//                                break;
//                            }
//                            req = mPartRequests.get(0);
//                            getAttachment(req);
//                        }
//
//                        synchronized(mPartRequests) {
//                            mPartRequests.remove(req);
//                        }
//                    }

                    boolean moreAvailable = true;
                    while (!mStop && moreAvailable) {
                        EASSerializer s = new EASSerializer();
                        if (mMailbox.mSyncKey == null) {
                            Log.w(TAG, "Mailbox syncKey RESET");
                            mMailbox.mSyncKey = "0";
                        }
                        Log.v(TAG, "Mailbox syncKey: " + mMailbox.mSyncKey);
                        s.start("Sync").start("Collections").start("Collection")
                            .data("Class", "Email")
                            .data("SyncKey", mMailbox.mSyncKey)
                            .data("CollectionId", mMailbox.mServerId);

                        // Set the lookback appropriately (EAS calls it a "filter")
                        String filter = FILTER_1_WEEK;
                        switch (mAccount.mSyncLookback) {
                            case Account.SYNC_WINDOW_1_DAY: {
                                filter = FILTER_1_DAY;
                                break;
                            }
                            case Account.SYNC_WINDOW_3_DAYS: {
                                filter = FILTER_3_DAYS;
                                break;
                            }
                            case Account.SYNC_WINDOW_1_WEEK: {
                                filter = FILTER_1_WEEK;
                                break;
                            }
                            case Account.SYNC_WINDOW_2_WEEKS: {
                                filter = FILTER_2_WEEKS;
                                break;
                            }
                            case Account.SYNC_WINDOW_1_MONTH: {
                                filter = FILTER_1_MONTH;
                                break;
                            }
                            case Account.SYNC_WINDOW_ALL: {
                                filter = FILTER_ALL;
                                break;
                            }
                        }

                        // For some crazy reason, GetChanges can't be used with a SyncKey of 0
                        if (!mMailbox.mSyncKey.equals("0")) {
                            if (mProtocolVersion.equals("12.0"))
                                s.tag("DeletesAsMoves")
                                .tag("GetChanges")
                                .data("WindowSize", WINDOW_SIZE)
                                .start("Options")
                                .data("FilterType", filter)
                                .start("BodyPreference")
                                .data("BodyPreferenceType", BODY_PREFERENCE_TEXT)   // Plain text to start
                                .data("BodyPreferenceTruncationSize", "50000")
                                .end("BodyPreference")
                                .end("Options");
                            else
                                s.tag("DeletesAsMoves")
                                .tag("GetChanges")
                                .data("WindowSize", WINDOW_SIZE)
                                .start("Options")
                                .data("FilterType", filter)
                                .end("Options");
                        }

                        // Send our changes up to the server
                        mSentCommands = false;
//                        // Send local deletes to server
//                        long maxDeleteId = handleLocalDeletes(s);
//                        // Send local read changes
//                        long maxReadId = handleLocalReads(s);
                        if (mSentCommands) {
                            s.end("Commands");
                        }

                        s.end("Collection").end("Collections").end("Sync").end();
                        HttpURLConnection uc = sendEASPostCommand("Sync", s.toString());
                        int code = uc.getResponseCode();
                        Log.v(TAG, "Sync response code: " + code);
                        if (code == HttpURLConnection.HTTP_OK) {
                            ByteArrayInputStream is = readResponse(uc);
                            if (is != null) {
                                EasEmailSyncParser p = new EasEmailSyncParser(is, this);
                                p.parse();
//                                if (maxDeleteId > -1)
//                                    Messages.deleteFromLocalDeletedWhere(mContext, "_id<=" + maxDeleteId);
//                                if (maxReadId > -1)
//                                    LocalChange.deleteWhere(mDatabase, "_id<=" + maxReadId + " AND mailbox=" + mMailboxId + " AND type=" + LocalChange.READ_TYPE);
                                moreAvailable = p.mMoreAvailable;
                            }
                        } else {
                            // TODO What?
                        }
                    }

                    // Handle local moves
                    handleLocalMoves();

                    if (mMailbox.mSyncFrequency != Account.CHECK_INTERVAL_PUSH) {
                        return;
                    }

                    // Handle push here...
                    Thread pingThread = null;
                    EasPingService pingService = new EasPingService(mContext, mMailbox, this);
                    runAsleep(10*MINS);
                    synchronized (mWaitTarget) {
                        mIsIdle = true;
                        try {
                            log("Wait...");
                            pingThread = new Thread(pingService);
                            pingThread.setName("Ping " + pingThread.getId());
                            log("Starting thread " + pingThread.getName());
                            pingThread.start();
                            mWaitTarget.wait(14*MINS);
                        } catch (InterruptedException e) {
                        } finally {
                            runAwake();
                        }
                        log("Wait terminated.");
                        if (pingThread != null  && pingThread.isAlive()) {
                            // Make the ping service stop, one way or another
                            log("Stopping " + pingThread.getName());
                            pingService.stop();
                            pingThread.interrupt();
                        }
                        mIsIdle = false;
                    }
                } catch (IOException e) {
                    log("IOException: " + e.getMessage());
                    //logException(e);
                }
            }
        } catch (Exception e) {
            log("Exception: " + e.getMessage());
            //logException(e);
        } finally {
            log("EAS sync finished.");
            //MailService.done(this);
        }
    }

}
