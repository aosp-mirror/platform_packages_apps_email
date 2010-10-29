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

import com.android.email.SecurityPolicy;
import com.android.email.Utility;
import com.android.email.SecurityPolicy.PolicySet;
import com.android.email.mail.Address;
import com.android.email.mail.AuthenticationFailedException;
import com.android.email.mail.MeetingInfo;
import com.android.email.mail.MessagingException;
import com.android.email.mail.PackedString;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.AttachmentColumns;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.service.EmailServiceConstants;
import com.android.email.service.EmailServiceProxy;
import com.android.email.service.EmailServiceStatus;
import com.android.exchange.adapter.AbstractSyncAdapter;
import com.android.exchange.adapter.AccountSyncAdapter;
import com.android.exchange.adapter.CalendarSyncAdapter;
import com.android.exchange.adapter.ContactsSyncAdapter;
import com.android.exchange.adapter.EmailSyncAdapter;
import com.android.exchange.adapter.FolderSyncParser;
import com.android.exchange.adapter.GalParser;
import com.android.exchange.adapter.MeetingResponseParser;
import com.android.exchange.adapter.PingParser;
import com.android.exchange.adapter.ProvisionParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.exchange.adapter.Parser.EasParserException;
import com.android.exchange.provider.GalResult;
import com.android.exchange.utility.CalendarUtilities;

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
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Entity;
import android.database.Cursor;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Calendar.Attendees;
import android.provider.Calendar.Events;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.Thread.State;
import java.net.URI;
import java.net.URLEncoder;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;

public class EasSyncService extends AbstractSyncService {
    // DO NOT CHECK IN SET TO TRUE
    public static final boolean DEBUG_GAL_SERVICE = false;

    private static final String EMAIL_WINDOW_SIZE = "5";
    public static final String PIM_WINDOW_SIZE = "4";
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
    // Command timeout is the the time allowed for reading data from an open connection before an
    // IOException is thrown.  After a small added allowance, our watchdog alarm goes off (allowing
    // us to detect a silently dropped connection).  The allowance is defined below.
    static private final int COMMAND_TIMEOUT = 30*SECONDS;
    // Connection timeout is the time given to connect to the server before reporting an IOException
    static private final int CONNECTION_TIMEOUT = 20*SECONDS;
    // The extra time allowed beyond the COMMAND_TIMEOUT before which our watchdog alarm triggers
    static private final int WATCHDOG_TIMEOUT_ALLOWANCE = 30*SECONDS;

    // The amount of time the account mailbox will sleep if there are no pingable mailboxes
    // This could happen if the sync time is set to "never"; we always want to check in from time
    // to time, however, for folder list/policy changes
    static private final int ACCOUNT_MAILBOX_SLEEP_TIME = 20*MINUTES;
    static private final String ACCOUNT_MAILBOX_SLEEP_TEXT =
        "Account mailbox sleeping for " + (ACCOUNT_MAILBOX_SLEEP_TIME / MINUTES) + "m";

    static private final String AUTO_DISCOVER_SCHEMA_PREFIX =
        "http://schemas.microsoft.com/exchange/autodiscover/mobilesync/";
    static private final String AUTO_DISCOVER_PAGE = "/autodiscover/autodiscover.xml";
    static private final int AUTO_DISCOVER_REDIRECT_CODE = 451;

    static public final String EAS_12_POLICY_TYPE = "MS-EAS-Provisioning-WBXML";
    static public final String EAS_2_POLICY_TYPE = "MS-WAP-Provisioning-XML";

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
    static private final int PING_HEARTBEAT_INCREMENT = 3*PING_MINUTES;

    // Maximum number of times we'll allow a sync to "loop" with MoreAvailable true before
    // forcing it to stop.  This number has been determined empirically.
    static private final int MAX_LOOPING_COUNT = 100;

    static private final int PROTOCOL_PING_STATUS_COMPLETED = 1;

    // The amount of time we allow for a thread to release its post lock after receiving an alert
    static private final int POST_LOCK_TIMEOUT = 10*SECONDS;

    // Fallbacks (in minutes) for ping loop failures
    static private final int MAX_PING_FAILURES = 1;
    static private final int PING_FALLBACK_INBOX = 5;
    static private final int PING_FALLBACK_PIM = 25;

    // MSFT's custom HTTP result code indicating the need to provision
    static private final int HTTP_NEED_PROVISIONING = 449;

    // The EAS protocol Provision status for "we implement all of the policies"
    static private final String PROVISION_STATUS_OK = "1";
    // The EAS protocol Provision status meaning "we partially implement the policies"
    static private final String PROVISION_STATUS_PARTIAL = "2";

    // Reasonable default
    public String mProtocolVersion = Eas.DEFAULT_PROTOCOL_VERSION;
    public Double mProtocolVersionDouble;
    protected String mDeviceId = null;
    /*package*/ String mDeviceType = "Android";
    /*package*/ String mAuthString = null;
    private String mCmdString = null;
    public String mHostAddress;
    public String mUserName;
    public String mPassword;
    private boolean mSsl = true;
    private boolean mTrustSsl = false;
    public ContentResolver mContentResolver;
    private String[] mBindArguments = new String[2];
    private ArrayList<String> mPingChangeList;
    // The HttpPost in progress
    private volatile HttpPost mPendingPost = null;
    // Our heartbeat when we are waiting for ping boxes to be ready
    /*package*/ int mPingForceHeartbeat = 2*PING_MINUTES;
    // The minimum heartbeat we will send
    /*package*/ int mPingMinHeartbeat = (5*PING_MINUTES)-PING_FUDGE_LOW;
    // The maximum heartbeat we will send
    /*package*/ int mPingMaxHeartbeat = (17*PING_MINUTES)-PING_FUDGE_LOW;
    // The ping time (in seconds)
    /*package*/ int mPingHeartbeat = PING_STARTING_HEARTBEAT;
    // The longest successful ping heartbeat
    private int mPingHighWaterMark = 0;
    // Whether we've ever lowered the heartbeat
    /*package*/ boolean mPingHeartbeatDropped = false;
    // Whether a POST was aborted due to alarm (watchdog alarm)
    private boolean mPostAborted = false;
    // Whether a POST was aborted due to reset
    private boolean mPostReset = false;
    // Whether or not the sync service is valid (usable)
    public boolean mIsValid = true;

    public EasSyncService(Context _context, Mailbox _mailbox) {
        super(_context, _mailbox);
        mContentResolver = _context.getContentResolver();
        if (mAccount == null) {
            mIsValid = false;
            return;
        }
        HostAuth ha = HostAuth.restoreHostAuthWithId(_context, mAccount.mHostAuthKeyRecv);
        if (ha == null) {
            mIsValid = false;
            return;
        }
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
    /**
     * Try to wake up a sync thread that is waiting on an HttpClient POST and has waited past its
     * socket timeout without having thrown an Exception
     *
     * @return true if the POST was successfully stopped; false if we've failed and interrupted
     * the thread
     */
    public boolean alarm() {
        HttpPost post;
        if (mThread == null) return true;
        String threadName = mThread.getName();

        // Synchronize here so that we are guaranteed to have valid mPendingPost and mPostLock
        // executePostWithTimeout (which executes the HttpPost) also uses this lock
        synchronized(getSynchronizer()) {
            // Get a reference to the current post lock
            post = mPendingPost;
            if (post != null) {
                if (Eas.USER_LOG) {
                    URI uri = post.getURI();
                    if (uri != null) {
                        String query = uri.getQuery();
                        if (query == null) {
                            query = "POST";
                        }
                        userLog(threadName, ": Alert, aborting ", query);
                    } else {
                        userLog(threadName, ": Alert, no URI?");
                    }
                }
                // Abort the POST
                mPostAborted = true;
                post.abort();
            } else {
                // If there's no POST, we're done
                userLog("Alert, no pending POST");
                return true;
            }
        }

        // Wait for the POST to finish
        try {
            Thread.sleep(POST_LOCK_TIMEOUT);
        } catch (InterruptedException e) {
        }

        State s = mThread.getState();
        if (Eas.USER_LOG) {
            userLog(threadName + ": State = " + s.name());
        }

        synchronized (getSynchronizer()) {
            // If the thread is still hanging around and the same post is pending, let's try to
            // stop the thread with an interrupt.
            if ((s != State.TERMINATED) && (mPendingPost != null) && (mPendingPost == post)) {
                mStop = true;
                mThread.interrupt();
                userLog("Interrupting...");
                // Let the caller know we had to interrupt the thread
                return false;
            }
        }
        // Let the caller know that the alarm was handled normally
        return true;
    }

    @Override
    public void reset() {
        synchronized(getSynchronizer()) {
            if (mPendingPost != null) {
                URI uri = mPendingPost.getURI();
                if (uri != null) {
                    String query = uri.getQuery();
                    if (query.startsWith("Cmd=Ping")) {
                        userLog("Reset, aborting Ping");
                        mPostReset = true;
                        mPendingPost.abort();
                    }
                }
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
        return (code == HttpStatus.SC_UNAUTHORIZED) || (code == HttpStatus.SC_FORBIDDEN);
    }

    /**
     * Determine whether an HTTP code represents a provisioning error
     * @param code the HTTP code returned by the server
     * @return whether or not the code represents an provisioning error
     */
    protected boolean isProvisionError(int code) {
        return (code == HTTP_NEED_PROVISIONING) || (code == HttpStatus.SC_FORBIDDEN);
    }

    private void setupProtocolVersion(EasSyncService service, Header versionHeader)
            throws MessagingException {
        // The string is a comma separated list of EAS versions in ascending order
        // e.g. 1.0,2.0,2.5,12.0,12.1
        String supportedVersions = versionHeader.getValue();
        userLog("Server supports versions: ", supportedVersions);
        String[] supportedVersionsArray = supportedVersions.split(",");
        String ourVersion = null;
        // Find the most recent version we support
        for (String version: supportedVersionsArray) {
            if (version.equals(Eas.SUPPORTED_PROTOCOL_EX2003) ||
                    version.equals(Eas.SUPPORTED_PROTOCOL_EX2007)) {
                ourVersion = version;
            }
        }
        // If we don't support any of the servers supported versions, throw an exception here
        // This will cause validation to fail
        if (ourVersion == null) {
            Log.w(TAG, "No supported EAS versions: " + supportedVersions);
            throw new MessagingException(MessagingException.PROTOCOL_VERSION_UNSUPPORTED);
        } else {
            service.mProtocolVersion = ourVersion;
            service.mProtocolVersionDouble = Double.parseDouble(ourVersion);
            if (service.mAccount != null) {
                service.mAccount.mProtocolVersion = ourVersion;
            }
        }
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
            // We mustn't use the "real" device id or we'll screw up current accounts
            // Any string will do, but we'll go for "validate"
            svc.mDeviceId = "validate";
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

                // Make sure we've got the right protocol version set up
                setupProtocolVersion(svc, versions);

                // Run second test here for provisioning failures...
                Serializer s = new Serializer();
                userLog("Validate: try folder sync");
                s.start(Tags.FOLDER_FOLDER_SYNC).start(Tags.FOLDER_SYNC_KEY).text("0")
                    .end().end().done();
                resp = svc.sendHttpClientPost("FolderSync", s.toByteArray());
                code = resp.getStatusLine().getStatusCode();
                // We'll get one of the following responses if policies are required by the server
                if (code == HttpStatus.SC_FORBIDDEN || code == HTTP_NEED_PROVISIONING) {
                    // Get the policies and see if we are able to support them
                    userLog("Validate: provisioning required");
                    if (svc.canProvision() != null) {
                        // If so, send the advisory Exception (the account may be created later)
                        userLog("Validate: provisioning is possible");
                        throw new MessagingException(MessagingException.SECURITY_POLICIES_REQUIRED);
                    } else
                        userLog("Validate: provisioning not possible");
                        // If not, send the unsupported Exception (the account won't be created)
                        throw new MessagingException(
                                MessagingException.SECURITY_POLICIES_UNSUPPORTED);
                } else if (code == HttpStatus.SC_NOT_FOUND) {
                    userLog("Wrong address or bad protocol version");
                    // We get a 404 from OWA addresses (which are NOT EAS addresses)
                    throw new MessagingException(MessagingException.PROTOCOL_VERSION_UNSUPPORTED);
                } else if (code != HttpStatus.SC_OK) {
                    // Fail generically with anything other than success
                    userLog("Unexpected response for FolderSync: ", code);
                    throw new MessagingException(MessagingException.UNSPECIFIED_EXCEPTION);
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

    /**
     * Gets the redirect location from the HTTP headers and uses that to modify the HttpPost so that
     * it can be reused
     *
     * @param resp the HttpResponse that indicates a redirect (451)
     * @param post the HttpPost that was originally sent to the server
     * @return the HttpPost, updated with the redirect location
     */
    private HttpPost getRedirect(HttpResponse resp, HttpPost post) {
        Header locHeader = resp.getFirstHeader("X-MS-Location");
        if (locHeader != null) {
            String loc = locHeader.getValue();
            // If we've gotten one and it shows signs of looking like an address, we try
            // sending our request there
            if (loc != null && loc.startsWith("http")) {
                post.setURI(URI.create(loc));
                return post;
            }
        }
        return null;
    }

    /**
     * Send the POST command to the autodiscover server, handling a redirect, if necessary, and
     * return the HttpResponse.  If we get a 401 (unauthorized) error and we're using the
     * full email address, try the bare user name instead (e.g. foo instead of foo@bar.com)
     *
     * @param client the HttpClient to be used for the request
     * @param post the HttpPost we're going to send
     * @param canRetry whether we can retry using the bare name on an authentication failure (401)
     * @return an HttpResponse from the original or redirect server
     * @throws IOException on any IOException within the HttpClient code
     * @throws MessagingException
     */
    private HttpResponse postAutodiscover(HttpClient client, HttpPost post, boolean canRetry)
            throws IOException, MessagingException {
        userLog("Posting autodiscover to: " + post.getURI());
        HttpResponse resp = executePostWithTimeout(client, post, COMMAND_TIMEOUT);
        int code = resp.getStatusLine().getStatusCode();
        // On a redirect, try the new location
        if (code == AUTO_DISCOVER_REDIRECT_CODE) {
            post = getRedirect(resp, post);
            if (post != null) {
                userLog("Posting autodiscover to redirect: " + post.getURI());
                return executePostWithTimeout(client, post, COMMAND_TIMEOUT);
            }
        // 401 (Unauthorized) is for true auth errors when used in Autodiscover
        } else if (code == HttpStatus.SC_UNAUTHORIZED) {
            if (canRetry && mUserName.contains("@")) {
                // Try again using the bare user name
                int atSignIndex = mUserName.indexOf('@');
                mUserName = mUserName.substring(0, atSignIndex);
                cacheAuthAndCmdString();
                userLog("401 received; trying username: ", mUserName);
                // Recreate the basic authentication string and reset the header
                post.removeHeaders("Authorization");
                post.setHeader("Authorization", mAuthString);
                return postAutodiscover(client, post, false);
            }
            throw new MessagingException(MessagingException.AUTHENTICATION_FAILED);
        // 403 (and others) we'll just punt on
        } else if (code != HttpStatus.SC_OK) {
            // We'll try the next address if this doesn't work
            userLog("Code: " + code + ", throwing IOException");
            throw new IOException();
        }
        return resp;
    }

    /**
     * Use the Exchange 2007 AutoDiscover feature to try to retrieve server information using
     * only an email address and the password
     *
     * @param userName the user's email address
     * @param password the user's password
     * @return a HostAuth ready to be saved in an Account or null (failure)
     */
    public Bundle tryAutodiscover(String userName, String password) throws RemoteException {
        XmlSerializer s = Xml.newSerializer();
        ByteArrayOutputStream os = new ByteArrayOutputStream(1024);
        HostAuth hostAuth = new HostAuth();
        Bundle bundle = new Bundle();
        bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                MessagingException.NO_ERROR);
        try {
            // Build the XML document that's sent to the autodiscover server(s)
            s.setOutput(os, "UTF-8");
            s.startDocument("UTF-8", false);
            s.startTag(null, "Autodiscover");
            s.attribute(null, "xmlns", AUTO_DISCOVER_SCHEMA_PREFIX + "requestschema/2006");
            s.startTag(null, "Request");
            s.startTag(null, "EMailAddress").text(userName).endTag(null, "EMailAddress");
            s.startTag(null, "AcceptableResponseSchema");
            s.text(AUTO_DISCOVER_SCHEMA_PREFIX + "responseschema/2006");
            s.endTag(null, "AcceptableResponseSchema");
            s.endTag(null, "Request");
            s.endTag(null, "Autodiscover");
            s.endDocument();
            String req = os.toString();

            // Initialize the user name and password
            mUserName = userName;
            mPassword = password;
            // Make sure the authentication string is recreated and cached
            cacheAuthAndCmdString();

            // Split out the domain name
            int amp = userName.indexOf('@');
            // The UI ensures that userName is a valid email address
            if (amp < 0) {
                throw new RemoteException();
            }
            String domain = userName.substring(amp + 1);

            // There are up to four attempts here; the two URLs that we're supposed to try per the
            // specification, and up to one redirect for each (handled in postAutodiscover)
            // Note: The expectation is that, of these four attempts, only a single server will
            // actually be identified as the autodiscover server.  For the identified server,
            // we may also try a 2nd connection with a different format (bare name).

            // Try the domain first and see if we can get a response
            HttpPost post = new HttpPost("https://" + domain + AUTO_DISCOVER_PAGE);
            setHeaders(post, false);
            post.setHeader("Content-Type", "text/xml");
            post.setEntity(new StringEntity(req));
            HttpClient client = getHttpClient(COMMAND_TIMEOUT);
            HttpResponse resp;
            try {
                resp = postAutodiscover(client, post, true /*canRetry*/);
            } catch (IOException e1) {
                userLog("IOException in autodiscover; trying alternate address");
                // We catch the IOException here because we have an alternate address to try
                post.setURI(URI.create("https://autodiscover." + domain + AUTO_DISCOVER_PAGE));
                // If we fail here, we're out of options, so we let the outer try catch the
                // IOException and return null
                resp = postAutodiscover(client, post, true /*canRetry*/);
            }

            // Get the "final" code; if it's not 200, just return null
            int code = resp.getStatusLine().getStatusCode();
            userLog("Code: " + code);
            if (code != HttpStatus.SC_OK) return null;

            // At this point, we have a 200 response (SC_OK)
            HttpEntity e = resp.getEntity();
            InputStream is = e.getContent();
            try {
                // The response to Autodiscover is regular XML (not WBXML)
                // If we ever get an error in this process, we'll just punt and return null
                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                XmlPullParser parser = factory.newPullParser();
                parser.setInput(is, "UTF-8");
                int type = parser.getEventType();
                if (type == XmlPullParser.START_DOCUMENT) {
                    type = parser.next();
                    if (type == XmlPullParser.START_TAG) {
                        String name = parser.getName();
                        if (name.equals("Autodiscover")) {
                            hostAuth = new HostAuth();
                            parseAutodiscover(parser, hostAuth);
                            // On success, we'll have a server address and login
                            if (hostAuth.mAddress != null) {
                                // Fill in the rest of the HostAuth
                                // We use the user name and password that were successful during
                                // the autodiscover process
                                hostAuth.mLogin = mUserName;
                                hostAuth.mPassword = mPassword;
                                hostAuth.mPort = 443;
                                hostAuth.mProtocol = "eas";
                                hostAuth.mFlags =
                                    HostAuth.FLAG_SSL | HostAuth.FLAG_AUTHENTICATE;
                                bundle.putParcelable(
                                        EmailServiceProxy.AUTO_DISCOVER_BUNDLE_HOST_AUTH, hostAuth);
                            } else {
                                bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                                        MessagingException.UNSPECIFIED_EXCEPTION);
                            }
                        }
                    }
                }
            } catch (XmlPullParserException e1) {
                // This would indicate an I/O error of some sort
                // We will simply return null and user can configure manually
            }
        // There's no reason at all for exceptions to be thrown, and it's ok if so.
        // We just won't do auto-discover; user can configure manually
       } catch (IllegalArgumentException e) {
             bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                     MessagingException.UNSPECIFIED_EXCEPTION);
       } catch (IllegalStateException e) {
            bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                    MessagingException.UNSPECIFIED_EXCEPTION);
       } catch (IOException e) {
            userLog("IOException in Autodiscover", e);
            bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                    MessagingException.IOERROR);
        } catch (MessagingException e) {
            bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                    MessagingException.AUTHENTICATION_FAILED);
        }
        return bundle;
    }

    void parseServer(XmlPullParser parser, HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        boolean mobileSync = false;
        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("Server")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals("Type")) {
                    if (parser.nextText().equals("MobileSync")) {
                        mobileSync = true;
                    }
                } else if (mobileSync && name.equals("Url")) {
                    String url = parser.nextText().toLowerCase();
                    // This will look like https://<server address>/Microsoft-Server-ActiveSync
                    // We need to extract the <server address>
                    if (url.startsWith("https://") &&
                            url.endsWith("/microsoft-server-activesync")) {
                        int lastSlash = url.lastIndexOf('/');
                        hostAuth.mAddress = url.substring(8, lastSlash);
                        userLog("Autodiscover, server: " + hostAuth.mAddress);
                    }
                }
            }
        }
    }

    void parseSettings(XmlPullParser parser, HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("Settings")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals("Server")) {
                    parseServer(parser, hostAuth);
                }
            }
        }
    }

    void parseAction(XmlPullParser parser, HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("Action")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals("Error")) {
                    // Should parse the error
                } else if (name.equals("Redirect")) {
                    Log.d(TAG, "Redirect: " + parser.nextText());
                } else if (name.equals("Settings")) {
                    parseSettings(parser, hostAuth);
                }
            }
        }
    }

    void parseUser(XmlPullParser parser, HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("User")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals("EMailAddress")) {
                    String addr = parser.nextText();
                    userLog("Autodiscover, email: " + addr);
                } else if (name.equals("DisplayName")) {
                    String dn = parser.nextText();
                    userLog("Autodiscover, user: " + dn);
                }
            }
        }
    }

    void parseResponse(XmlPullParser parser, HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("Response")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals("User")) {
                    parseUser(parser, hostAuth);
                } else if (name.equals("Action")) {
                    parseAction(parser, hostAuth);
                }
            }
        }
    }

    void parseAutodiscover(XmlPullParser parser, HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.nextTag();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("Autodiscover")) {
                break;
            } else if (type == XmlPullParser.START_TAG && parser.getName().equals("Response")) {
                parseResponse(parser, hostAuth);
            }
        }
    }

    /**
     * Contact the GAL and obtain a list of matching accounts
     * @param context caller's context
     * @param accountId the account Id to search
     * @param filter the characters entered so far
     * @return a result record
     *
     * TODO: shorter timeout for interactive lookup
     * TODO: make watchdog actually work (it doesn't understand our service w/Mailbox == 0)
     * TODO: figure out why sendHttpClientPost() hangs - possibly pool exhaustion
     */
    static public GalResult searchGal(Context context, long accountId, String filter) {
        Account acct = SyncManager.getAccountById(accountId);
        if (acct != null) {
            HostAuth ha = HostAuth.restoreHostAuthWithId(context, acct.mHostAuthKeyRecv);
            EasSyncService svc = new EasSyncService("%GalLookupk%");
            try {
                svc.mContext = context;
                svc.mHostAddress = ha.mAddress;
                svc.mUserName = ha.mLogin;
                svc.mPassword = ha.mPassword;
                svc.mSsl = (ha.mFlags & HostAuth.FLAG_SSL) != 0;
                svc.mTrustSsl = (ha.mFlags & HostAuth.FLAG_TRUST_ALL_CERTIFICATES) != 0;
                svc.mDeviceId = SyncManager.getDeviceId();
                svc.mAccount = acct;
                Serializer s = new Serializer();
                s.start(Tags.SEARCH_SEARCH).start(Tags.SEARCH_STORE);
                s.data(Tags.SEARCH_NAME, "GAL").data(Tags.SEARCH_QUERY, filter);
                s.start(Tags.SEARCH_OPTIONS);
                s.data(Tags.SEARCH_RANGE, "0-19");  // Return 0..20 results
                s.end().end().end().done();
                if (DEBUG_GAL_SERVICE) svc.userLog("GAL lookup starting for " + ha.mAddress);
                HttpResponse resp = svc.sendHttpClientPost("Search", s.toByteArray());
                int code = resp.getStatusLine().getStatusCode();
                if (code == HttpStatus.SC_OK) {
                    InputStream is = resp.getEntity().getContent();
                    GalParser gp = new GalParser(is, svc);
                    if (gp.parse()) {
                        if (DEBUG_GAL_SERVICE) svc.userLog("GAL lookup OK for " + ha.mAddress);
                        return gp.getGalResult();
                    } else {
                        if (DEBUG_GAL_SERVICE) svc.userLog("GAL lookup returned no matches");
                    }
                } else {
                    svc.userLog("GAL lookup returned " + code);
                }
            } catch (IOException e) {
                // GAL is non-critical; we'll just go on
                svc.userLog("GAL lookup exception " + e);
            }
        }
        return null;
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
        Attachment att = req.mAttachment;
        Message msg = Message.restoreMessageWithId(mContext, att.mMessageKey);
        doProgressCallback(msg.mId, att.mId, 0);

        String cmd = "GetAttachment&AttachmentName=" + att.mLocation;
        HttpResponse res = sendHttpClientPost(cmd, null, COMMAND_TIMEOUT);

        int status = res.getStatusLine().getStatusCode();
        if (status == HttpStatus.SC_OK) {
            HttpEntity e = res.getEntity();
            int len = (int)e.getContentLength();
            InputStream is = res.getEntity().getContent();
            File f = (req.mDestination != null)
                    ? new File(req.mDestination)
                    : createUniqueFileInternal(req.mDestination, att.mFileName);
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
                        mPendingRequest = req;
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
                                int pct = (totalRead * 100) / length;
                                doProgressCallback(msg.mId, att.mId, pct);
                            }
                       }
                    } finally {
                        mPendingRequest = null;
                    }
                }
                os.flush();
                os.close();

                // EmailProvider will throw an exception if we try to update an unsaved attachment
                if (att.isSaved()) {
                    String contentUriString = (req.mContentUriString != null)
                            ? req.mContentUriString
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

    /**
     * Send an email responding to a Message that has been marked as a meeting request.  The message
     * will consist a little bit of event information and an iCalendar attachment
     * @param msg the meeting request email
     */
    private void sendMeetingResponseMail(Message msg, int response) {
        // Get the meeting information; we'd better have some...
        PackedString meetingInfo = new PackedString(msg.mMeetingInfo);
        if (meetingInfo == null) return;

        // This will come as "First Last" <box@server.blah>, so we use Address to
        // parse it into parts; we only need the email address part for the ics file
        Address[] addrs = Address.parse(meetingInfo.get(MeetingInfo.MEETING_ORGANIZER_EMAIL));
        // It shouldn't be possible, but handle it anyway
        if (addrs.length != 1) return;
        String organizerEmail = addrs[0].getAddress();

        String dtStamp = meetingInfo.get(MeetingInfo.MEETING_DTSTAMP);
        String dtStart = meetingInfo.get(MeetingInfo.MEETING_DTSTART);
        String dtEnd = meetingInfo.get(MeetingInfo.MEETING_DTEND);

        // What we're doing here is to create an Entity that looks like an Event as it would be
        // stored by CalendarProvider
        ContentValues entityValues = new ContentValues();
        Entity entity = new Entity(entityValues);

        // Fill in times, location, title, and organizer
        entityValues.put("DTSTAMP",
                CalendarUtilities.convertEmailDateTimeToCalendarDateTime(dtStamp));
        entityValues.put(Events.DTSTART, Utility.parseEmailDateTimeToMillis(dtStart));
        entityValues.put(Events.DTEND, Utility.parseEmailDateTimeToMillis(dtEnd));
        entityValues.put(Events.EVENT_LOCATION, meetingInfo.get(MeetingInfo.MEETING_LOCATION));
        entityValues.put(Events.TITLE, meetingInfo.get(MeetingInfo.MEETING_TITLE));
        entityValues.put(Events.ORGANIZER, organizerEmail);

        // Add ourselves as an attendee, using our account email address
        ContentValues attendeeValues = new ContentValues();
        attendeeValues.put(Attendees.ATTENDEE_RELATIONSHIP,
                Attendees.RELATIONSHIP_ATTENDEE);
        attendeeValues.put(Attendees.ATTENDEE_EMAIL, mAccount.mEmailAddress);
        entity.addSubValue(Attendees.CONTENT_URI, attendeeValues);

        // Add the organizer
        ContentValues organizerValues = new ContentValues();
        organizerValues.put(Attendees.ATTENDEE_RELATIONSHIP,
                Attendees.RELATIONSHIP_ORGANIZER);
        organizerValues.put(Attendees.ATTENDEE_EMAIL, organizerEmail);
        entity.addSubValue(Attendees.CONTENT_URI, organizerValues);

        // Create a message from the Entity we've built.  The message will have fields like
        // to, subject, date, and text filled in.  There will also be an "inline" attachment
        // which is in iCalendar format
        int flag;
        switch(response) {
            case EmailServiceConstants.MEETING_REQUEST_ACCEPTED:
                flag = Message.FLAG_OUTGOING_MEETING_ACCEPT;
                break;
            case EmailServiceConstants.MEETING_REQUEST_DECLINED:
                flag = Message.FLAG_OUTGOING_MEETING_DECLINE;
                break;
            case EmailServiceConstants.MEETING_REQUEST_TENTATIVE:
            default:
                flag = Message.FLAG_OUTGOING_MEETING_TENTATIVE;
                break;
        }
        Message outgoingMsg =
            CalendarUtilities.createMessageForEntity(mContext, entity, flag,
                    meetingInfo.get(MeetingInfo.MEETING_UID), mAccount);
        // Assuming we got a message back (we might not if the event has been deleted), send it
        if (outgoingMsg != null) {
            EasOutboxService.sendMessage(mContext, mAccount.mId, outgoingMsg);
        }
    }

    /**
     * Responds to a meeting request.  The MeetingResponseRequest is basically our
     * wrapper for the meetingResponse service call
     * @param req the request (message id and response code)
     * @throws IOException
     */
    protected void sendMeetingResponse(MeetingResponseRequest req) throws IOException {
        // Retrieve the message and mailbox; punt if either are null
        Message msg = Message.restoreMessageWithId(mContext, req.mMessageId);
        if (msg == null) return;
        Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, msg.mMailboxKey);
        if (mailbox == null) return;
        Serializer s = new Serializer();
        s.start(Tags.MREQ_MEETING_RESPONSE).start(Tags.MREQ_REQUEST);
        s.data(Tags.MREQ_USER_RESPONSE, Integer.toString(req.mResponse));
        s.data(Tags.MREQ_COLLECTION_ID, mailbox.mServerId);
        s.data(Tags.MREQ_REQ_ID, msg.mServerId);
        s.end().end().done();
        HttpResponse res = sendHttpClientPost("MeetingResponse", s.toByteArray());
        int status = res.getStatusLine().getStatusCode();
        if (status == HttpStatus.SC_OK) {
            HttpEntity e = res.getEntity();
            int len = (int)e.getContentLength();
            InputStream is = res.getEntity().getContent();
            if (len != 0) {
                new MeetingResponseParser(is, this).parse();
                sendMeetingResponseMail(msg, req.mResponse);
            }
        } else if (isAuthError(status)) {
            throw new EasAuthenticationException();
        } else {
            userLog("Meeting response request failed, code: " + status);
            throw new IOException();
        }
    }

    /**
     * Using mUserName and mPassword, create and cache mAuthString and mCacheString, which are used
     * in all HttpPost commands.  This should be called if these strings are null, or if mUserName
     * and/or mPassword are changed
     */
    @SuppressWarnings("deprecation")
    private void cacheAuthAndCmdString() {
        String safeUserName = URLEncoder.encode(mUserName);
        String cs = mUserName + ':' + mPassword;
        mAuthString = "Basic " + Base64.encodeToString(cs.getBytes(), Base64.NO_WRAP);
        mCmdString = "&User=" + safeUserName + "&DeviceId=" + mDeviceId +
            "&DeviceType=" + mDeviceType;
    }

    private String makeUriString(String cmd, String extra) throws IOException {
         // Cache the authentication string and the command string
        if (mAuthString == null || mCmdString == null) {
            cacheAuthAndCmdString();
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

    /**
     * Set standard HTTP headers, using a policy key if required
     * @param method the method we are going to send
     * @param usePolicyKey whether or not a policy key should be sent in the headers
     */
    /*package*/ void setHeaders(HttpRequestBase method, boolean usePolicyKey) {
        method.setHeader("Authorization", mAuthString);
        method.setHeader("MS-ASProtocolVersion", mProtocolVersion);
        method.setHeader("Connection", "keep-alive");
        method.setHeader("User-Agent", mDeviceType + '/' + Eas.VERSION);
        if (usePolicyKey) {
            // If there's an account in existence, use its key; otherwise (we're creating the
            // account), send "0".  The server will respond with code 449 if there are policies
            // to be enforced
            String key = "0";
            if (mAccount != null) {
                String accountKey = mAccount.mSecuritySyncKey;
                if (!TextUtils.isEmpty(accountKey)) {
                    key = accountKey;
                }
            }
            method.setHeader("X-MS-PolicyKey", key);
        }
    }

    private ClientConnectionManager getClientConnectionManager() {
        return SyncManager.getClientConnectionManager();
    }

    private HttpClient getHttpClient(int timeout) {
        HttpParams params = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(params, CONNECTION_TIMEOUT);
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

    /**
     * Convenience method for executePostWithTimeout for use other than with the Ping command
     */
    protected HttpResponse executePostWithTimeout(HttpClient client, HttpPost method, int timeout)
            throws IOException {
        return executePostWithTimeout(client, method, timeout, false);
    }

    /**
     * Handle executing an HTTP POST command with proper timeout, watchdog, and ping behavior
     * @param client the HttpClient
     * @param method the HttpPost
     * @param timeout the timeout before failure, in ms
     * @param isPingCommand whether the POST is for the Ping command (requires wakelock logic)
     * @return the HttpResponse
     * @throws IOException
     */
    protected HttpResponse executePostWithTimeout(HttpClient client, HttpPost method, int timeout,
            boolean isPingCommand) throws IOException {
        synchronized(getSynchronizer()) {
            mPendingPost = method;
            long alarmTime = timeout + WATCHDOG_TIMEOUT_ALLOWANCE;
            if (isPingCommand) {
                SyncManager.runAsleep(mMailboxId, alarmTime);
            } else {
                SyncManager.setWatchdogAlarm(mMailboxId, alarmTime);
            }
        }
        try {
            return client.execute(method);
        } finally {
            synchronized(getSynchronizer()) {
                if (isPingCommand) {
                    SyncManager.runAwake(mMailboxId);
                } else {
                    SyncManager.clearWatchdogAlarm(mMailboxId);
                }
                mPendingPost = null;
            }
        }
    }

    protected HttpResponse sendHttpClientPost(String cmd, HttpEntity entity, int timeout)
            throws IOException {
        HttpClient client = getHttpClient(timeout);
        boolean isPingCommand = cmd.equals(PING_COMMAND);

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
        setHeaders(method, !cmd.equals(PING_COMMAND));
        method.setEntity(entity);
        return executePostWithTimeout(client, method, timeout, isPingCommand);
    }

    protected HttpResponse sendHttpClientOptions() throws IOException {
        HttpClient client = getHttpClient(COMMAND_TIMEOUT);
        String us = makeUriString("OPTIONS", null);
        HttpOptions method = new HttpOptions(URI.create(us));
        setHeaders(method, false);
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
     * Negotiate provisioning with the server.  First, get policies form the server and see if
     * the policies are supported by the device.  Then, write the policies to the account and
     * tell SecurityPolicy that we have policies in effect.  Finally, see if those policies are
     * active; if so, acknowledge the policies to the server and get a final policy key that we
     * use in future EAS commands and write this key to the account.
     * @return whether or not provisioning has been successful
     * @throws IOException
     */
    private boolean tryProvision() throws IOException {
        // First, see if provisioning is even possible, i.e. do we support the policies required
        // by the server
        ProvisionParser pp = canProvision();
        if (pp != null) {
            SecurityPolicy sp = SecurityPolicy.getInstance(mContext);
            // Get the policies from ProvisionParser
            PolicySet ps = pp.getPolicySet();
            // Update the account with a null policyKey (the key we've gotten is
            // temporary and cannot be used for syncing)
            if (ps.writeAccount(mAccount, null, true, mContext)) {
                sp.updatePolicies(mAccount.mId);
            }
            if (pp.getRemoteWipe()) {
                // We've gotten a remote wipe command
                SyncManager.alwaysLog("!!! Remote wipe request received");
                // Start by setting the account to security hold
                sp.setAccountHoldFlag(mAccount, true);
                // Force a stop to any running syncs for this account (except this one)
                SyncManager.stopNonAccountMailboxSyncsForAccount(mAccount.mId);

                // If we're not the admin, we can't do the wipe, so just return
                if (!sp.isActiveAdmin()) {
                    SyncManager.alwaysLog("!!! Not device admin; can't wipe");
                    return false;
                }
                // First, we've got to acknowledge it, but wrap the wipe in try/catch so that
                // we wipe the device regardless of any errors in acknowledgment
                try {
                    SyncManager.alwaysLog("!!! Acknowledging remote wipe to server");
                    acknowledgeRemoteWipe(pp.getPolicyKey());
                } catch (Exception e) {
                    // Because remote wipe is such a high priority task, we don't want to
                    // circumvent it if there's an exception in acknowledgment
                }
                // Then, tell SecurityPolicy to wipe the device
                SyncManager.alwaysLog("!!! Executing remote wipe");
                sp.remoteWipe();
                return false;
            } else if (sp.isActive(ps)) {
                // See if the required policies are in force; if they are, acknowledge the policies
                // to the server and get the final policy key
                String policyKey = acknowledgeProvision(pp.getPolicyKey(), PROVISION_STATUS_OK);
                if (policyKey != null) {
                    // Write the final policy key to the Account and say we've been successful
                    ps.writeAccount(mAccount, policyKey, true, mContext);
                    // Release any mailboxes that might be in a security hold
                    SyncManager.releaseSecurityHold(mAccount);
                    return true;
                }
            } else {
                // Notify that we are blocked because of policies
                sp.policiesRequired(mAccount.mId);
            }
        }
        return false;
    }

    private String getPolicyType() {
        return (mProtocolVersionDouble >=
            Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) ? EAS_12_POLICY_TYPE : EAS_2_POLICY_TYPE;
    }

    /**
     * Obtain a set of policies from the server and determine whether those policies are supported
     * by the device.
     * @return the ProvisionParser (holds policies and key) if we receive policies and they are
     * supported by the device; null otherwise
     * @throws IOException
     */
    private ProvisionParser canProvision() throws IOException {
        Serializer s = new Serializer();
        s.start(Tags.PROVISION_PROVISION).start(Tags.PROVISION_POLICIES);
        s.start(Tags.PROVISION_POLICY).data(Tags.PROVISION_POLICY_TYPE, getPolicyType())
            .end().end().end().done();
        HttpResponse resp = sendHttpClientPost("Provision", s.toByteArray());
        int code = resp.getStatusLine().getStatusCode();
        if (code == HttpStatus.SC_OK) {
            InputStream is = resp.getEntity().getContent();
            ProvisionParser pp = new ProvisionParser(is, this);
            if (pp.parse()) {
                // The PolicySet in the ProvisionParser will have the requirements for all KNOWN
                // policies.  If others are required, hasSupportablePolicySet will be false
                if (pp.hasSupportablePolicySet()) {
                    // If the policies are supportable (in this context, meaning that there are no
                    // completely unimplemented policies required), just return the parser itself
                    return pp;
                } else {
                    // Try to acknowledge using the "partial" status (i.e. we can partially
                    // accommodate the required policies).  The server will agree to this if the
                    // "allow non-provisionable devices" setting is enabled on the server
                    String policyKey = acknowledgeProvision(pp.getPolicyKey(),
                            PROVISION_STATUS_PARTIAL);
                    // Return either the parser (success) or null (failure)
                    return (policyKey != null) ? pp : null;
                }
            }
        }
        // On failures, simply return null
        return null;
    }

    /**
     * Acknowledge that we support the policies provided by the server, and that these policies
     * are in force.
     * @param tempKey the initial (temporary) policy key sent by the server
     * @return the final policy key, which can be used for syncing
     * @throws IOException
     */
    private void acknowledgeRemoteWipe(String tempKey) throws IOException {
        acknowledgeProvisionImpl(tempKey, PROVISION_STATUS_OK, true);
    }

    private String acknowledgeProvision(String tempKey, String result) throws IOException {
        return acknowledgeProvisionImpl(tempKey, result, false);
    }

    private String acknowledgeProvisionImpl(String tempKey, String status,
            boolean remoteWipe) throws IOException {
        Serializer s = new Serializer();
        s.start(Tags.PROVISION_PROVISION).start(Tags.PROVISION_POLICIES);
        s.start(Tags.PROVISION_POLICY);

        // Use the proper policy type, depending on EAS version
        s.data(Tags.PROVISION_POLICY_TYPE, getPolicyType());

        s.data(Tags.PROVISION_POLICY_KEY, tempKey);
        s.data(Tags.PROVISION_STATUS, status);
        s.end().end(); // PROVISION_POLICY, PROVISION_POLICIES
        if (remoteWipe) {
            s.start(Tags.PROVISION_REMOTE_WIPE);
            s.data(Tags.PROVISION_STATUS, PROVISION_STATUS_OK);
            s.end();
        }
        s.end().done(); // PROVISION_PROVISION
        HttpResponse resp = sendHttpClientPost("Provision", s.toByteArray());
        int code = resp.getStatusLine().getStatusCode();
        if (code == HttpStatus.SC_OK) {
            InputStream is = resp.getEntity().getContent();
            ProvisionParser pp = new ProvisionParser(is, this);
            if (pp.parse()) {
                // Return the final policy key from the ProvisionParser
                return pp.getPolicyKey();
            }
        }
        // On failures, return null
        return null;
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

            // Determine our protocol version, if we haven't already and save it in the Account
            // Also re-check protocol version at least once a day (in case of upgrade)
            if (mAccount.mProtocolVersion == null ||
                    ((System.currentTimeMillis() - mMailbox.mSyncTime) > DAYS)) {
                userLog("Determine EAS protocol version");
                HttpResponse resp = sendHttpClientOptions();
                int code = resp.getStatusLine().getStatusCode();
                userLog("OPTIONS response: ", code);
                if (code == HttpStatus.SC_OK) {
                    Header header = resp.getFirstHeader("MS-ASProtocolCommands");
                    userLog(header.getValue());
                    header = resp.getFirstHeader("ms-asprotocolversions");
                    try {
                        setupProtocolVersion(this, header);
                    } catch (MessagingException e) {
                        // Since we've already validated, this can't really happen
                        // But if it does, we'll rethrow this...
                        throw new IOException();
                    }
                    // Save the protocol version
                    cv.clear();
                    // Save the protocol version in the account
                    cv.put(Account.PROTOCOL_VERSION, mProtocolVersion);
                    mAccount.update(mContext, cv);
                    cv.clear();
                    // Save the sync time of the account mailbox to current time
                    cv.put(Mailbox.SYNC_TIME, System.currentTimeMillis());
                    mMailbox.update(mContext, cv);
                 } else {
                    errorLog("OPTIONS command failed; throwing IOException");
                    throw new IOException();
                }
            }

            // Change all pushable boxes to push when we start the account mailbox
            if (mAccount.mSyncInterval == Account.CHECK_INTERVAL_PUSH) {
                cv.clear();
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
                } else if (isProvisionError(code)) {
                    // If the sync error is a provisioning failure (perhaps the policies changed),
                    // let's try the provisioning procedure
                    // Provisioning must only be attempted for the account mailbox - trying to
                    // provision any other mailbox may result in race conditions and the creation
                    // of multiple policy keys.
                    if (!tryProvision()) {
                        // Set the appropriate failure status
                        mExitStatus = EXIT_SECURITY_FAILURE;
                        return;
                    } else {
                        // If we succeeded, try again...
                        continue;
                    }
                } else if (isAuthError(code)) {
                    mExitStatus = EXIT_LOGIN_FAILURE;
                    return;
                } else {
                    userLog("FolderSync response error: ", code);
                }

                // Change all push/hold boxes to push
                cv.clear();
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

                // Before each run of the pingLoop, if this Account has a PolicySet, make sure it's
                // active; otherwise, clear out the key/flag.  This should cause a provisioning
                // error on the next POST, and start the security sequence over again
                String key = mAccount.mSecuritySyncKey;
                if (!TextUtils.isEmpty(key)) {
                    PolicySet ps = new PolicySet(mAccount);
                    SecurityPolicy sp = SecurityPolicy.getInstance(mContext);
                    if (!sp.isActive(ps)) {
                        cv.clear();
                        cv.put(AccountColumns.SECURITY_FLAGS, 0);
                        cv.putNull(AccountColumns.SECURITY_SYNC_KEY);
                        long accountId = mAccount.mId;
                        mContentResolver.update(ContentUris.withAppendedId(
                                Account.CONTENT_URI, accountId), cv, null, null);
                        sp.policiesRequired(accountId);
                    }
                }

                // Wait for push notifications.
                String threadName = Thread.currentThread().getName();
                try {
                    runPingLoop();
                } catch (StaleFolderListException e) {
                    // We break out if we get told about a stale folder list
                    userLog("Ping interrupted; folder list requires sync...");
                } catch (IllegalHeartbeatException e) {
                    // If we're sending an illegal heartbeat, reset either the min or the max to
                    // that heartbeat
                    resetHeartbeats(e.mLegalHeartbeat);
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

    /**
     * Reset either our minimum or maximum ping heartbeat to a heartbeat known to be legal
     * @param legalHeartbeat a known legal heartbeat (from the EAS server)
     */
    /*package*/ void resetHeartbeats(int legalHeartbeat) {
        userLog("Resetting min/max heartbeat, legal = " + legalHeartbeat);
        // We are here because the current heartbeat (mPingHeartbeat) is invalid.  Depending on
        // whether the argument is above or below the current heartbeat, we can infer the need to
        // change either the minimum or maximum heartbeat
        if (legalHeartbeat > mPingHeartbeat) {
            // The legal heartbeat is higher than the ping heartbeat; therefore, our minimum was
            // too low.  We respond by raising either or both of the minimum heartbeat or the
            // force heartbeat to the argument value
            if (mPingMinHeartbeat < legalHeartbeat) {
                mPingMinHeartbeat = legalHeartbeat;
            }
            if (mPingForceHeartbeat < legalHeartbeat) {
                mPingForceHeartbeat = legalHeartbeat;
            }
            // If our minimum is now greater than the max, bring them together
            if (mPingMinHeartbeat > mPingMaxHeartbeat) {
                mPingMaxHeartbeat = legalHeartbeat;
            }
        } else if (legalHeartbeat < mPingHeartbeat) {
            // The legal heartbeat is lower than the ping heartbeat; therefore, our maximum was
            // too high.  We respond by lowering the maximum to the argument value
            mPingMaxHeartbeat = legalHeartbeat;
            // If our maximum is now less than the minimum, bring them together
            if (mPingMaxHeartbeat < mPingMinHeartbeat) {
                mPingMinHeartbeat = legalHeartbeat;
            }
        }
        // Set current heartbeat to the legal heartbeat
        mPingHeartbeat = legalHeartbeat;
        // Allow the heartbeat logic to run
        mPingHeartbeatDropped = false;
    }

    private void pushFallback(long mailboxId) {
        Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, mailboxId);
        if (mailbox == null) {
            return;
        }
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

    /**
     * Simplistic attempt to determine a NAT timeout, based on experience with various carriers
     * and networks.  The string "reset by peer" is very common in these situations, so we look for
     * that specifically.  We may add additional tests here as more is learned.
     * @param message
     * @return whether this message is likely associated with a NAT failure
     */
    private boolean isLikelyNatFailure(String message) {
        if (message == null) return false;
        if (message.contains("reset by peer")) {
            return true;
        }
        return false;
    }

    private void runPingLoop() throws IOException, StaleFolderListException,
            IllegalHeartbeatException {
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
                mPostReset = false;
                mPostAborted = false;

                // If we've been stopped, this is a good time to return
                if (mStop) return;

                long pingTime = SystemClock.elapsedRealtime();
                try {
                    // Send the ping, wrapped by appropriate timeout/alarm
                    if (forcePing) {
                        userLog("Forcing ping after waiting for all boxes to be ready");
                    }
                    HttpResponse res =
                        sendPing(s.toByteArray(), forcePing ? mPingForceHeartbeat : pingHeartbeat);

                    int code = res.getStatusLine().getStatusCode();
                    userLog("Ping response: ", code);

                    // Return immediately if we've been asked to stop during the ping
                    if (mStop) {
                        userLog("Stopping pingLoop");
                        return;
                    }

                    if (code == HttpStatus.SC_OK) {
                        // Make sure to clear out any pending sync errors
                        SyncManager.removeFromSyncErrorMap(mMailboxId);
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
                                if ((pingHeartbeat < mPingMaxHeartbeat) &&
                                        !mPingHeartbeatDropped) {
                                    pingHeartbeat += PING_HEARTBEAT_INCREMENT;
                                    if (pingHeartbeat > mPingMaxHeartbeat) {
                                        pingHeartbeat = mPingMaxHeartbeat;
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
                    if (mPostReset) {
                        // Nothing to do in this case; this is SyncManager telling us to try another
                        // ping.
                    } else if (mPostAborted || isLikelyNatFailure(message)) {
                        long pingLength = SystemClock.elapsedRealtime() - pingTime;
                        if ((pingHeartbeat > mPingMinHeartbeat) &&
                                (pingHeartbeat > mPingHighWaterMark)) {
                            pingHeartbeat -= PING_HEARTBEAT_INCREMENT;
                            mPingHeartbeatDropped = true;
                            if (pingHeartbeat < mPingMinHeartbeat) {
                                pingHeartbeat = mPingMinHeartbeat;
                            }
                            userLog("Decreased ping heartbeat to ", pingHeartbeat, "s");
                        } else if (mPostAborted) {
                            // There's no point in throwing here; this can happen in two cases
                            // 1) An alarm, which indicates minutes without activity; no sense
                            //    backing off
                            // 2) SyncManager abort, due to sync of mailbox.  Again, we want to
                            //    keep on trying to ping
                            userLog("Ping aborted; retry");
                        } else if (pingLength < 2000) {
                            userLog("Abort or NAT type return < 2 seconds; throwing IOException");
                            throw e;
                        } else {
                            userLog("NAT type IOException");
                        }
                    } else if (hasMessage && message.contains("roken pipe")) {
                        // The "broken pipe" error (uppercase or lowercase "b") seems to be an
                        // internal error, so let's not throw an exception (which leads to delays)
                        // but rather simply run through the loop again
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
                // We've got nothing to do, so we'll check again in 20 minutes at which time
                // we'll update the folder list, check for policy changes and/or remote wipe, etc.
                // Let the device sleep in the meantime...
                userLog(ACCOUNT_MAILBOX_SLEEP_TEXT);
                sleep(ACCOUNT_MAILBOX_SLEEP_TIME, true);
            }
        }

        // Save away the current heartbeat
        mPingHeartbeat = pingHeartbeat;
    }

    private void sleep(long ms, boolean runAsleep) {
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
            throws IOException, StaleFolderListException, IllegalHeartbeatException {
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

    private String getEmailFilter() {
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
        int loopingCount = 0;
        while (!mStop && moreAvailable) {
            // If we have no connectivity, just exit cleanly.  SyncManager will start us up again
            // when connectivity has returned
            if (!hasConnectivity()) {
                userLog("No connectivity in sync; finishing sync");
                mExitStatus = EXIT_DONE;
                return;
            }

            // Every time through the loop we check to see if we're still syncable
            if (!target.isSyncable()) {
                mExitStatus = EXIT_DONE;
                return;
            }

            // Now, handle various requests
            while (true) {
                Request req = null;
                synchronized (mRequests) {
                    if (mRequests.isEmpty()) {
                        break;
                    } else {
                        req = mRequests.get(0);
                    }
                }

                // Our two request types are PartRequest (loading attachment) and
                // MeetingResponseRequest (respond to a meeting request)
                if (req instanceof PartRequest) {
                    getAttachment((PartRequest)req);
                } else if (req instanceof MeetingResponseRequest) {
                    sendMeetingResponse((MeetingResponseRequest)req);
                }

                // If there's an exception handling the request, we'll throw it
                // Otherwise, we remove the request
                synchronized(mRequests) {
                    mRequests.remove(req);
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
                .data(Tags.SYNC_COLLECTION_ID, mailbox.mServerId);

            // Start with the default timeout
            int timeout = COMMAND_TIMEOUT;
            if (!syncKey.equals("0")) {
                // EAS doesn't allow GetChanges in an initial sync; sending other options
                // appears to cause the server to delay its response in some cases, and this delay
                // can be long enough to result in an IOException and total failure to sync.
                // Therefore, we don't send any options with the initial sync.
                s.tag(Tags.SYNC_DELETES_AS_MOVES);
                s.tag(Tags.SYNC_GET_CHANGES);
                s.data(Tags.SYNC_WINDOW_SIZE,
                        className.equals("Email") ? EMAIL_WINDOW_SIZE : PIM_WINDOW_SIZE);
                // Handle options
                s.start(Tags.SYNC_OPTIONS);
                // Set the lookback appropriately (EAS calls this a "filter") for all but Contacts
                if (className.equals("Email")) {
                    s.data(Tags.SYNC_FILTER_TYPE, getEmailFilter());
                } else if (className.equals("Calendar")) {
                    // TODO Force two weeks for calendar until we can set this!
                    s.data(Tags.SYNC_FILTER_TYPE, Eas.FILTER_2_WEEKS);
                }
                // Set the truncation amount for all classes
                if (mProtocolVersionDouble >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
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
            } else {
                // Use enormous timeout for initial sync, which empirically can take a while longer
                timeout = 120*SECONDS;
            }
            // Send our changes up to the server
            target.sendLocalChanges(s);

            s.end().end().end().done();
            HttpResponse resp = sendHttpClientPost("Sync", new ByteArrayEntity(s.toByteArray()),
                    timeout);
            int code = resp.getStatusLine().getStatusCode();
            if (code == HttpStatus.SC_OK) {
                InputStream is = resp.getEntity().getContent();
                if (is != null) {
                    moreAvailable = target.parse(is);
                    if (target.isLooping()) {
                        loopingCount++;
                        userLog("** Looping: " + loopingCount);
                        // After the maximum number of loops, we'll set moreAvailable to false and
                        // allow the sync loop to terminate
                        if (moreAvailable && (loopingCount > MAX_LOOPING_COUNT)) {
                            userLog("** Looping force stopped");
                            moreAvailable = false;
                        }
                    } else {
                        loopingCount = 0;
                    }
                    target.cleanup();
                } else {
                    userLog("Empty input stream in sync command response");
                }
            } else {
                userLog("Sync response error: ", code);
                if (isProvisionError(code)) {
                    mExitStatus = EXIT_SECURITY_FAILURE;
                } else if (isAuthError(code)) {
                    mExitStatus = EXIT_LOGIN_FAILURE;
                } else {
                    mExitStatus = EXIT_IO_ERROR;
                }
                return;
            }
        }
        mExitStatus = EXIT_DONE;
    }

    protected boolean setupService() {
        // Make sure account and mailbox are always the latest from the database
        mAccount = Account.restoreAccountWithId(mContext, mAccount.mId);
        if (mAccount == null) return false;
        mMailbox = Mailbox.restoreMailboxWithId(mContext, mMailbox.mId);
        if (mMailbox == null) return false;
        mThread = Thread.currentThread();
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        TAG = mThread.getName();

        HostAuth ha = HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
        if (ha == null) return false;
        mHostAddress = ha.mAddress;
        mUserName = ha.mLogin;
        mPassword = ha.mPassword;

        // Set up our protocol version from the Account
        mProtocolVersion = mAccount.mProtocolVersion;
        // If it hasn't been set up, start with default version
        if (mProtocolVersion == null) {
            mProtocolVersion = Eas.DEFAULT_PROTOCOL_VERSION;
        }
        mProtocolVersionDouble = Double.parseDouble(mProtocolVersion);
        return true;
    }

    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    public void run() {
        if (!setupService()) return;

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
                if (mMailbox.mType == Mailbox.TYPE_CONTACTS) {
                    target = new ContactsSyncAdapter(mMailbox, this);
                } else if (mMailbox.mType == Mailbox.TYPE_CALENDAR) {
                    target = new CalendarSyncAdapter(mMailbox, this);
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
        } catch (EasAuthenticationException e) {
            userLog("Caught authentication error");
            mExitStatus = EXIT_LOGIN_FAILURE;
        } catch (IOException e) {
            String message = e.getMessage();
            userLog("Caught IOException: ", (message == null) ? "No message" : message);
            mExitStatus = EXIT_IO_ERROR;
        } catch (Exception e) {
            userLog("Uncaught exception in EasSyncService", e);
        } finally {
            int status;

            if (!mStop) {
                userLog("Sync finished");
                SyncManager.done(this);
                switch (mExitStatus) {
                    case EXIT_IO_ERROR:
                        status = EmailServiceStatus.CONNECTION_ERROR;
                        break;
                    case EXIT_DONE:
                        status = EmailServiceStatus.SUCCESS;
                        ContentValues cv = new ContentValues();
                        cv.put(Mailbox.SYNC_TIME, System.currentTimeMillis());
                        String s = "S" + mSyncReason + ':' + status + ':' + mChangeCount;
                        cv.put(Mailbox.SYNC_STATUS, s);
                        mContentResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI,
                                mMailboxId), cv, null, null);
                        break;
                    case EXIT_LOGIN_FAILURE:
                        status = EmailServiceStatus.LOGIN_FAILED;
                        break;
                    case EXIT_SECURITY_FAILURE:
                        status = EmailServiceStatus.SECURITY_FAILURE;
                        // Ask for a new folder list.  This should wake up the account mailbox; a
                        // security error in account mailbox should start the provisioning process
                        SyncManager.reloadFolderList(mContext, mAccount.mId, true);
                        break;
                    default:
                        status = EmailServiceStatus.REMOTE_EXCEPTION;
                        errorLog("Sync ended due to an exception.");
                        break;
                }
            } else {
                userLog("Stopped sync finished.");
                status = EmailServiceStatus.SUCCESS;
            }

            try {
                SyncManager.callback().syncMailboxStatus(mMailboxId, status, 0);
            } catch (RemoteException e1) {
                // Don't care if this fails
            }

            // Make sure SyncManager knows about this
            SyncManager.kick("sync finished");
       }
    }
}
