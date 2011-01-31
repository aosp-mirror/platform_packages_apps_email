/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.email.mail.store;

import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.Utility;
import com.android.email.VendorPolicyLoader;
import com.android.email.mail.AuthenticationFailedException;
import com.android.email.mail.CertificateValidationException;
import com.android.email.mail.FetchProfile;
import com.android.email.mail.Flag;
import com.android.email.mail.Folder;
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Part;
import com.android.email.mail.Store;
import com.android.email.mail.Transport;
import com.android.email.mail.internet.MimeBodyPart;
import com.android.email.mail.internet.MimeHeader;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.internet.MimeMultipart;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.mail.store.imap.ImapConstants;
import com.android.email.mail.store.imap.ImapElement;
import com.android.email.mail.store.imap.ImapList;
import com.android.email.mail.store.imap.ImapResponse;
import com.android.email.mail.store.imap.ImapResponseParser;
import com.android.email.mail.store.imap.ImapString;
import com.android.email.mail.transport.CountingOutputStream;
import com.android.email.mail.transport.DiscourseLogger;
import com.android.email.mail.transport.EOLConvertingOutputStream;
import com.android.email.mail.transport.MailTransport;
import com.beetstra.jutf7.CharsetProvider;

import android.content.Context;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Config;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

/**
 * <pre>
 * TODO Need to start keeping track of UIDVALIDITY
 * TODO Need a default response handler for things like folder updates
 * TODO In fetch(), if we need a ImapMessage and were given
 * TODO Collect ALERT messages and show them to users.
 * something else we can try to do a pre-fetch first.
 *
 * ftp://ftp.isi.edu/in-notes/rfc2683.txt When a client asks for
 * certain information in a FETCH command, the server may return the requested
 * information in any order, not necessarily in the order that it was requested.
 * Further, the server may return the information in separate FETCH responses
 * and may also return information that was not explicitly requested (to reflect
 * to the client changes in the state of the subject message).
 * </pre>
 */
public class ImapStore extends Store {

    // Always check in FALSE
    private static final boolean DEBUG_FORCE_SEND_ID = false;

    private static final Flag[] PERMANENT_FLAGS = { Flag.DELETED, Flag.SEEN, Flag.FLAGGED };

    private final Context mContext;
    private Transport mRootTransport;
    private String mUsername;
    private String mPassword;
    private String mLoginPhrase;
    private String mPathPrefix;
    private String mIdPhrase = null;
    private static String sImapId = null;

    private final ConcurrentLinkedQueue<ImapConnection> mConnectionPool =
            new ConcurrentLinkedQueue<ImapConnection>();

    /**
     * Charset used for converting folder names to and from UTF-7 as defined by RFC 3501.
     */
    private static final Charset MODIFIED_UTF_7_CHARSET =
            new CharsetProvider().charsetForName("X-RFC-3501");

    /**
     * Cache of ImapFolder objects. ImapFolders are attached to a given folder on the server
     * and as long as their associated connection remains open they are reusable between
     * requests. This cache lets us make sure we always reuse, if possible, for a given
     * folder name.
     */
    private HashMap<String, ImapFolder> mFolderCache = new HashMap<String, ImapFolder>();

    /**
     * Next tag to use.  All connections associated to the same ImapStore instance share the same
     * counter to make tests simpler.
     * (Some of the tests involve multiple connections but only have a single counter to track the
     * tag.)
     */
    private final AtomicInteger mNextCommandTag = new AtomicInteger(0);

    /**
     * Static named constructor.
     */
    public static Store newInstance(String uri, Context context, PersistentDataCallbacks callbacks)
            throws MessagingException {
        return new ImapStore(context, uri);
    }

    /**
     * Allowed formats for the Uri:
     * imap://user:password@server:port
     * imap+tls+://user:password@server:port
     * imap+tls+trustallcerts://user:password@server:port
     * imap+ssl+://user:password@server:port
     * imap+ssl+trustallcerts://user:password@server:port
     *
     * @param uriString the Uri containing information to configure this store
     */
    private ImapStore(Context context, String uriString) throws MessagingException {
        mContext = context;
        URI uri;
        try {
            uri = new URI(uriString);
        } catch (URISyntaxException use) {
            throw new MessagingException("Invalid ImapStore URI", use);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !scheme.startsWith(STORE_SCHEME_IMAP)) {
            throw new MessagingException("Unsupported protocol");
        }
        // defaults, which can be changed by security modifiers
        int connectionSecurity = Transport.CONNECTION_SECURITY_NONE;
        int defaultPort = 143;
        // check for security modifiers and apply changes
        if (scheme.contains("+ssl")) {
            connectionSecurity = Transport.CONNECTION_SECURITY_SSL;
            defaultPort = 993;
        } else if (scheme.contains("+tls")) {
            connectionSecurity = Transport.CONNECTION_SECURITY_TLS;
        }
        boolean trustCertificates = scheme.contains(STORE_SECURITY_TRUST_CERTIFICATES);

        mRootTransport = new MailTransport("IMAP");
        mRootTransport.setUri(uri, defaultPort);
        mRootTransport.setSecurity(connectionSecurity, trustCertificates);

        String[] userInfoParts = mRootTransport.getUserInfoParts();
        if (userInfoParts != null) {
            mUsername = userInfoParts[0];
            if (userInfoParts.length > 1) {
                mPassword = userInfoParts[1];

                // build the LOGIN string once (instead of over-and-over again.)
                // apply the quoting here around the built-up password
                mLoginPhrase = ImapConstants.LOGIN + " " + mUsername + " "
                        + Utility.imapQuoted(mPassword);
            }
        }

        if ((uri.getPath() != null) && (uri.getPath().length() > 0)) {
            mPathPrefix = uri.getPath().substring(1);
        }
    }

    /* package */ Collection<ImapConnection> getConnectionPoolForTest() {
        return mConnectionPool;
    }

    /**
     * For testing only.  Injects a different root transport (it will be copied using
     * newInstanceWithConfiguration() each time IMAP sets up a new channel).  The transport
     * should already be set up and ready to use.  Do not use for real code.
     * @param testTransport The Transport to inject and use for all future communication.
     */
    /* package */ void setTransport(Transport testTransport) {
        mRootTransport = testTransport;
    }

    /**
     * Return, or create and return, an string suitable for use in an IMAP ID message.
     * This is constructed similarly to the way the browser sets up its user-agent strings.
     * See RFC 2971 for more details.  The output of this command will be a series of key-value
     * pairs delimited by spaces (there is no point in returning a structured result because
     * this will be sent as-is to the IMAP server).  No tokens, parenthesis or "ID" are included,
     * because some connections may append additional values.
     *
     * The following IMAP ID keys may be included:
     *   name                   Android package name of the program
     *   os                     "android"
     *   os-version             "version; model; build-id"
     *   vendor                 Vendor of the client/server
     *   x-android-device-model Model (only revealed if release build)
     *   x-android-net-operator Mobile network operator (if known)
     *   AGUID                  A device+account UID
     *
     * In addition, a vendor policy .apk can append key/value pairs.
     *
     * @param userName the username of the account
     * @param host the host (server) of the account
     * @param capabilityResponse the capabilities list from the server
     * @return a String for use in an IMAP ID message.
     */
    /* package */ static String getImapId(Context context, String userName, String host,
            ImapResponse capabilityResponse) {
        // The first section is global to all IMAP connections, and generates the fixed
        // values in any IMAP ID message
        synchronized (ImapStore.class) {
            if (sImapId == null) {
                TelephonyManager tm =
                        (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                String networkOperator = tm.getNetworkOperatorName();
                if (networkOperator == null) networkOperator = "";

                sImapId = makeCommonImapId(context.getPackageName(), Build.VERSION.RELEASE,
                        Build.VERSION.CODENAME, Build.MODEL, Build.ID, Build.MANUFACTURER,
                        networkOperator);
            }
        }

        // This section is per Store, and adds in a dynamic elements like UID's.
        // We don't cache the result of this work, because the caller does anyway.
        StringBuilder id = new StringBuilder(sImapId);

        // Optionally add any vendor-supplied id keys
        String vendorId = VendorPolicyLoader.getInstance(context).getImapIdValues(userName, host,
                capabilityResponse.flatten());
        if (vendorId != null) {
            id.append(' ');
            id.append(vendorId);
        }

        // Generate a UID that mixes a "stable" device UID with the email address
        try {
            String devUID = Preferences.getPreferences(context).getDeviceUID();
            MessageDigest messageDigest;
            messageDigest = MessageDigest.getInstance("SHA-1");
            messageDigest.update(userName.getBytes());
            messageDigest.update(devUID.getBytes());
            byte[] uid = messageDigest.digest();
            String hexUid = Base64.encodeToString(uid, Base64.NO_WRAP);
            id.append(" \"AGUID\" \"");
            id.append(hexUid);
            id.append('\"');
        } catch (NoSuchAlgorithmException e) {
            Log.d(Email.LOG_TAG, "couldn't obtain SHA-1 hash for device UID");
        }
        return id.toString();
    }

    /**
     * Helper function that actually builds the static part of the IMAP ID string.  This is
     * separated from getImapId for testability.  There is no escaping or encoding in IMAP ID so
     * any rogue chars must be filtered here.
     *
     * @param packageName context.getPackageName()
     * @param version Build.VERSION.RELEASE
     * @param codeName Build.VERSION.CODENAME
     * @param model Build.MODEL
     * @param id Build.ID
     * @param vendor Build.MANUFACTURER
     * @param networkOperator TelephonyManager.getNetworkOperatorName()
     * @return the static (never changes) portion of the IMAP ID
     */
    /* package */ static String makeCommonImapId(String packageName, String version,
            String codeName, String model, String id, String vendor, String networkOperator) {

        // Before building up IMAP ID string, pre-filter the input strings for "legal" chars
        // This is using a fairly arbitrary char set intended to pass through most reasonable
        // version, model, and vendor strings: a-z A-Z 0-9 - _ + = ; : . , / <space>
        // The most important thing is *not* to pass parens, quotes, or CRLF, which would break
        // the format of the IMAP ID list.
        Pattern p = Pattern.compile("[^a-zA-Z0-9-_\\+=;:\\.,/ ]");
        packageName = p.matcher(packageName).replaceAll("");
        version = p.matcher(version).replaceAll("");
        codeName = p.matcher(codeName).replaceAll("");
        model = p.matcher(model).replaceAll("");
        id = p.matcher(id).replaceAll("");
        vendor = p.matcher(vendor).replaceAll("");
        networkOperator = p.matcher(networkOperator).replaceAll("");

        // "name" "com.android.email"
        StringBuffer sb = new StringBuffer("\"name\" \"");
        sb.append(packageName);
        sb.append("\"");

        // "os" "android"
        sb.append(" \"os\" \"android\"");

        // "os-version" "version; build-id"
        sb.append(" \"os-version\" \"");
        if (version.length() > 0) {
            sb.append(version);
        } else {
            // default to "1.0"
            sb.append("1.0");
        }
        // add the build ID or build #
        if (id.length() > 0) {
            sb.append("; ");
            sb.append(id);
        }
        sb.append("\"");

        // "vendor" "the vendor"
        if (vendor.length() > 0) {
            sb.append(" \"vendor\" \"");
            sb.append(vendor);
            sb.append("\"");
        }

        // "x-android-device-model" the device model (on release builds only)
        if ("REL".equals(codeName)) {
            if (model.length() > 0) {
                sb.append(" \"x-android-device-model\" \"");
                sb.append(model);
                sb.append("\"");
            }
        }

        // "x-android-mobile-net-operator" "name of network operator"
        if (networkOperator.length() > 0) {
            sb.append(" \"x-android-mobile-net-operator\" \"");
            sb.append(networkOperator);
            sb.append("\"");
        }

        return sb.toString();
    }


    @Override
    public Folder getFolder(String name) throws MessagingException {
        ImapFolder folder;
        synchronized (mFolderCache) {
            folder = mFolderCache.get(name);
            if (folder == null) {
                folder = new ImapFolder(this, name);
                mFolderCache.put(name, folder);
            }
        }
        return folder;
    }

    @Override
    public Folder[] getPersonalNamespaces() throws MessagingException {
        ImapConnection connection = getConnection();
        try {
            ArrayList<Folder> folders = new ArrayList<Folder>();
            List<ImapResponse> responses = connection.executeSimpleCommand(
                    String.format(ImapConstants.LIST + " \"\" \"%s*\"",
                            mPathPrefix == null ? "" : mPathPrefix));
            for (ImapResponse response : responses) {
                // S: * LIST (\Noselect) "/" ~/Mail/foo
                if (response.isDataResponse(0, ImapConstants.LIST)) {
                    boolean includeFolder = true;

                    // Get folder name.
                    ImapString encodedFolder = response.getStringOrEmpty(3);
                    if (encodedFolder.isEmpty()) continue;
                    String folder = decodeFolderName(encodedFolder.getString());
                    if (ImapConstants.INBOX.equalsIgnoreCase(folder)) {
                        continue;
                    }

                    // Parse attributes.
                    if (response.getListOrEmpty(1).contains(ImapConstants.FLAG_NO_SELECT)) {
                        includeFolder = false;
                    }
                    if (includeFolder) {
                        folders.add(getFolder(folder));
                    }
                }
            }
            folders.add(getFolder(ImapConstants.INBOX));
            return folders.toArray(new Folder[] {});
        } catch (IOException ioe) {
            connection.close();
            throw new MessagingException("Unable to get folder list.", ioe);
        } finally {
            connection.destroyResponses();
            poolConnection(connection);
        }
    }

    @Override
    public void checkSettings() throws MessagingException {
        ImapConnection connection = new ImapConnection();
        try {
            connection.open();
            connection.close();
        } catch (IOException ioe) {
            throw new MessagingException(MessagingException.IOERROR, ioe.toString());
        } finally {
            connection.destroyResponses();
        }
    }

    /**
     * Gets a connection if one is available from the pool, or creates a new one if not.
     */
    /* package */ ImapConnection getConnection() {
        ImapConnection connection = null;
        while ((connection = mConnectionPool.poll()) != null) {
            try {
                connection.executeSimpleCommand(ImapConstants.NOOP);
                break;
            } catch (MessagingException e) {
                // Fall through
            } catch (IOException e) {
                // Fall through
            } finally {
                connection.destroyResponses();
            }
            connection.close();
            connection = null;
        }
        if (connection == null) {
            connection = new ImapConnection();
        }
        return connection;
    }

    /**
     * Save a {@link ImapConnection} in the pool for reuse.
     */
    /* package */ void poolConnection(ImapConnection connection) {
        if (connection != null) {
            mConnectionPool.add(connection);
        }
    }

    /* package */ static String encodeFolderName(String name) {
        // TODO bypass the conversion if name doesn't have special char.
        ByteBuffer bb = MODIFIED_UTF_7_CHARSET.encode(name);
        byte[] b = new byte[bb.limit()];
        bb.get(b);
        return Utility.fromAscii(b);
    }

    /* package */ static String decodeFolderName(String name) {
        // TODO bypass the conversion if name doesn't have special char.
        /*
         * Convert the encoded name to US-ASCII, then pass it through the modified UTF-7
         * decoder and return the Unicode String.
         */
        return MODIFIED_UTF_7_CHARSET.decode(ByteBuffer.wrap(Utility.toAscii(name))).toString();
    }

    /**
     * Returns UIDs of Messages joined with "," as the separator.
     */
    /* package */ static String joinMessageUids(Message[] messages) {
        StringBuilder sb = new StringBuilder();
        boolean notFirst = false;
        for (Message m : messages) {
            if (notFirst) {
                sb.append(',');
            }
            sb.append(m.getUid());
            notFirst = true;
        }
        return sb.toString();
    }

    static class ImapFolder extends Folder {
        private final ImapStore mStore;
        private final String mName;
        private int mMessageCount = -1;
        private ImapConnection mConnection;
        private OpenMode mMode;
        private boolean mExists;

        public ImapFolder(ImapStore store, String name) {
            mStore = store;
            mName = name;
        }

        private void destroyResponses() {
            if (mConnection != null) {
                mConnection.destroyResponses();
            }
        }

        @Override
        public void open(OpenMode mode, PersistentDataCallbacks callbacks)
                throws MessagingException {
            try {
                if (isOpen()) {
                    if (mMode == mode) {
                        // Make sure the connection is valid.
                        // If it's not we'll close it down and continue on to get a new one.
                        try {
                            mConnection.executeSimpleCommand(ImapConstants.NOOP);
                            return;

                        } catch (IOException ioe) {
                            ioExceptionHandler(mConnection, ioe);
                        } finally {
                            destroyResponses();
                        }
                    } else {
                        // Return the connection to the pool, if exists.
                        close(false);
                    }
                }
                synchronized (this) {
                    mConnection = mStore.getConnection();
                }
                // * FLAGS (\Answered \Flagged \Deleted \Seen \Draft NonJunk
                // $MDNSent)
                // * OK [PERMANENTFLAGS (\Answered \Flagged \Deleted \Seen \Draft
                // NonJunk $MDNSent \*)] Flags permitted.
                // * 23 EXISTS
                // * 0 RECENT
                // * OK [UIDVALIDITY 1125022061] UIDs valid
                // * OK [UIDNEXT 57576] Predicted next UID
                // 2 OK [READ-WRITE] Select completed.
                try {
                    List<ImapResponse> responses = mConnection.executeSimpleCommand(
                            String.format(ImapConstants.SELECT + " \"%s\"",
                                    encodeFolderName(mName)));
                    /*
                     * If the command succeeds we expect the folder has been opened read-write
                     * unless we are notified otherwise in the responses.
                     */
                    mMode = OpenMode.READ_WRITE;

                    int messageCount = -1;
                    for (ImapResponse response : responses) {
                        if (response.isDataResponse(1, ImapConstants.EXISTS)) {
                            messageCount = response.getStringOrEmpty(0).getNumberOrZero();

                        } else if (response.isOk()) {
                            final ImapString responseCode = response.getResponseCodeOrEmpty();
                            if (responseCode.is(ImapConstants.READ_ONLY)) {
                                mMode = OpenMode.READ_ONLY;
                            } else if (responseCode.is(ImapConstants.READ_WRITE)) {
                                mMode = OpenMode.READ_WRITE;
                            }
                        } else if (response.isTagged()) { // Not OK
                            throw new MessagingException("Can't open mailbox: "
                                    + response.getStatusResponseTextOrEmpty());
                        }
                    }

                    if (messageCount == -1) {
                        throw new MessagingException("Did not find message count during select");
                    }
                    mMessageCount = messageCount;
                    mExists = true;

                } catch (IOException ioe) {
                    throw ioExceptionHandler(mConnection, ioe);
                } finally {
                    destroyResponses();
                }
            } catch (MessagingException e) {
                mExists = false;
                close(false);
                throw e;
            }
        }

        @Override
        public boolean isOpen() {
            return mExists && mConnection != null;
        }

        @Override
        public OpenMode getMode() throws MessagingException {
            return mMode;
        }

        @Override
        public void close(boolean expunge) {
            // TODO implement expunge
            mMessageCount = -1;
            synchronized (this) {
                destroyResponses();
                mStore.poolConnection(mConnection);
                mConnection = null;
            }
        }

        @Override
        public String getName() {
            return mName;
        }

        @Override
        public boolean exists() throws MessagingException {
            if (mExists) {
                return true;
            }
            /*
             * This method needs to operate in the unselected mode as well as the selected mode
             * so we must get the connection ourselves if it's not there. We are specifically
             * not calling checkOpen() since we don't care if the folder is open.
             */
            ImapConnection connection = null;
            synchronized(this) {
                if (mConnection == null) {
                    connection = mStore.getConnection();
                } else {
                    connection = mConnection;
                }
            }
            try {
                connection.executeSimpleCommand(String.format(
                        ImapConstants.STATUS + " \"%s\" (" + ImapConstants.UIDVALIDITY + ")",
                        encodeFolderName(mName)));
                mExists = true;
                return true;

            } catch (MessagingException me) {
                return false;

            } catch (IOException ioe) {
                throw ioExceptionHandler(connection, ioe);

            } finally {
                connection.destroyResponses();
                if (mConnection == null) {
                    mStore.poolConnection(connection);
                }
            }
        }

        // IMAP supports folder creation
        @Override
        public boolean canCreate(FolderType type) {
            return true;
        }

        @Override
        public boolean create(FolderType type) throws MessagingException {
            /*
             * This method needs to operate in the unselected mode as well as the selected mode
             * so we must get the connection ourselves if it's not there. We are specifically
             * not calling checkOpen() since we don't care if the folder is open.
             */
            ImapConnection connection = null;
            synchronized(this) {
                if (mConnection == null) {
                    connection = mStore.getConnection();
                } else {
                    connection = mConnection;
                }
            }
            try {
                connection.executeSimpleCommand(String.format(ImapConstants.CREATE + " \"%s\"",
                        encodeFolderName(mName)));
                return true;

            } catch (MessagingException me) {
                return false;

            } catch (IOException ioe) {
                throw ioExceptionHandler(connection, ioe);

            } finally {
                connection.destroyResponses();
                if (mConnection == null) {
                    mStore.poolConnection(connection);
                }
            }
        }

        @Override
        public void copyMessages(Message[] messages, Folder folder,
                MessageUpdateCallbacks callbacks) throws MessagingException {
            checkOpen();
            try {
                mConnection.executeSimpleCommand(
                        String.format(ImapConstants.UID_COPY + " %s \"%s\"",
                                joinMessageUids(messages),
                                encodeFolderName(folder.getName())));
            } catch (IOException ioe) {
                throw ioExceptionHandler(mConnection, ioe);
            } finally {
                destroyResponses();
            }
        }

        @Override
        public int getMessageCount() {
            return mMessageCount;
        }

        @Override
        public int getUnreadMessageCount() throws MessagingException {
            checkOpen();
            try {
                int unreadMessageCount = 0;
                List<ImapResponse> responses = mConnection.executeSimpleCommand(String.format(
                        ImapConstants.STATUS + " \"%s\" (" + ImapConstants.UNSEEN + ")",
                        encodeFolderName(mName)));
                // S: * STATUS mboxname (MESSAGES 231 UIDNEXT 44292)
                for (ImapResponse response : responses) {
                    if (response.isDataResponse(0, ImapConstants.STATUS)) {
                        unreadMessageCount = response.getListOrEmpty(2)
                                .getKeyedStringOrEmpty(ImapConstants.UNSEEN).getNumberOrZero();
                    }
                }
                return unreadMessageCount;
            } catch (IOException ioe) {
                throw ioExceptionHandler(mConnection, ioe);
            } finally {
                destroyResponses();
            }
        }

        @Override
        public void delete(boolean recurse) throws MessagingException {
            throw new Error("ImapStore.delete() not yet implemented");
        }

        /* package */ String[] searchForUids(String searchCriteria)
                throws MessagingException {
            checkOpen();
            List<ImapResponse> responses;
            try {
                try {
                    responses = mConnection.executeSimpleCommand(
                            ImapConstants.UID_SEARCH + " " + searchCriteria);
                } catch (ImapException e) {
                    return Utility.EMPTY_STRINGS; // not found;
                } catch (IOException ioe) {
                    throw ioExceptionHandler(mConnection, ioe);
                }
                // S: * SEARCH 2 3 6
                final ArrayList<String> uids = new ArrayList<String>();
                for (ImapResponse response : responses) {
                    if (!response.isDataResponse(0, ImapConstants.SEARCH)) {
                        continue;
                    }
                    // Found SEARCH response data
                    for (int i = 1; i < response.size(); i++) {
                        ImapString s = response.getStringOrEmpty(i);
                        if (s.isString()) {
                            uids.add(s.getString());
                        }
                    }
                }
                return uids.toArray(Utility.EMPTY_STRINGS);
            } finally {
                destroyResponses();
            }
        }

        @Override
        public Message getMessage(String uid) throws MessagingException {
            checkOpen();

            String[] uids = searchForUids(ImapConstants.UID + " " + uid);
            for (int i = 0; i < uids.length; i++) {
                if (uids[i].equals(uid)) {
                    return new ImapMessage(uid, this);
                }
            }
            return null;
        }

        @Override
        public Message[] getMessages(int start, int end, MessageRetrievalListener listener)
                throws MessagingException {
            if (start < 1 || end < 1 || end < start) {
                throw new MessagingException(String.format("Invalid range: %d %d", start, end));
            }
            return getMessagesInternal(
                    searchForUids(String.format("%d:%d NOT DELETED", start, end)), listener);
        }

        @Override
        public Message[] getMessages(MessageRetrievalListener listener) throws MessagingException {
            return getMessages(null, listener);
        }

        @Override
        public Message[] getMessages(String[] uids, MessageRetrievalListener listener)
                throws MessagingException {
            if (uids == null) {
                uids = searchForUids("1:* NOT DELETED");
            }
            return getMessagesInternal(uids, listener);
        }

        public Message[] getMessagesInternal(String[] uids, MessageRetrievalListener listener)
                throws MessagingException {
            final ArrayList<Message> messages = new ArrayList<Message>(uids.length);
            for (int i = 0; i < uids.length; i++) {
                final String uid = uids[i];
                final ImapMessage message = new ImapMessage(uid, this);
                messages.add(message);
                if (listener != null) {
                    listener.messageRetrieved(message);
                }
            }
            return messages.toArray(Message.EMPTY_ARRAY);
        }

        @Override
        public void fetch(Message[] messages, FetchProfile fp, MessageRetrievalListener listener)
                throws MessagingException {
            try {
                fetchInternal(messages, fp, listener);
            } catch (RuntimeException e) { // Probably a parser error.
                Log.w(Email.LOG_TAG, "Exception detected: " + e.getMessage());
                if (mConnection != null) {
                    mConnection.logLastDiscourse();
                }
                throw e;
            }
        }

        public void fetchInternal(Message[] messages, FetchProfile fp,
                MessageRetrievalListener listener) throws MessagingException {
            if (messages.length == 0) {
                return;
            }
            checkOpen();
            HashMap<String, Message> messageMap = new HashMap<String, Message>();
            for (Message m : messages) {
                messageMap.put(m.getUid(), m);
            }

            /*
             * Figure out what command we are going to run:
             * FLAGS     - UID FETCH (FLAGS)
             * ENVELOPE  - UID FETCH (INTERNALDATE UID RFC822.SIZE FLAGS BODY.PEEK[
             *                            HEADER.FIELDS (date subject from content-type to cc)])
             * STRUCTURE - UID FETCH (BODYSTRUCTURE)
             * BODY_SANE - UID FETCH (BODY.PEEK[]<0.N>) where N = max bytes returned
             * BODY      - UID FETCH (BODY.PEEK[])
             * Part      - UID FETCH (BODY.PEEK[ID]) where ID = mime part ID
             */

            final LinkedHashSet<String> fetchFields = new LinkedHashSet<String>();

            fetchFields.add(ImapConstants.UID);
            if (fp.contains(FetchProfile.Item.FLAGS)) {
                fetchFields.add(ImapConstants.FLAGS);
            }
            if (fp.contains(FetchProfile.Item.ENVELOPE)) {
                fetchFields.add(ImapConstants.INTERNALDATE);
                fetchFields.add(ImapConstants.RFC822_SIZE);
                fetchFields.add(ImapConstants.FETCH_FIELD_HEADERS);
            }
            if (fp.contains(FetchProfile.Item.STRUCTURE)) {
                fetchFields.add(ImapConstants.BODYSTRUCTURE);
            }

            if (fp.contains(FetchProfile.Item.BODY_SANE)) {
                fetchFields.add(ImapConstants.FETCH_FIELD_BODY_PEEK_SANE);
            }
            if (fp.contains(FetchProfile.Item.BODY)) {
                fetchFields.add(ImapConstants.FETCH_FIELD_BODY_PEEK);
            }

            final Part fetchPart = fp.getFirstPart();
            if (fetchPart != null) {
                String[] partIds =
                        fetchPart.getHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA);
                if (partIds != null) {
                    fetchFields.add(ImapConstants.FETCH_FIELD_BODY_PEEK_BARE
                            + "[" + partIds[0] + "]");
                }
            }

            try {
                mConnection.sendCommand(String.format(
                        ImapConstants.UID_FETCH + " %s (%s)", joinMessageUids(messages),
                        Utility.combine(fetchFields.toArray(new String[fetchFields.size()]), ' ')
                        ), false);
                ImapResponse response;
                int messageNumber = 0;
                do {
                    response = null;
                    try {
                        response = mConnection.readResponse();

                        if (!response.isDataResponse(1, ImapConstants.FETCH)) {
                            continue; // Ignore
                        }
                        final ImapList fetchList = response.getListOrEmpty(2);
                        final String uid = fetchList.getKeyedStringOrEmpty(ImapConstants.UID)
                                .getString();
                        if (TextUtils.isEmpty(uid)) continue;

                        ImapMessage message = (ImapMessage) messageMap.get(uid);
                        if (message == null) continue;

                        if (fp.contains(FetchProfile.Item.FLAGS)) {
                            final ImapList flags =
                                fetchList.getKeyedListOrEmpty(ImapConstants.FLAGS);
                            for (int i = 0, count = flags.size(); i < count; i++) {
                                final ImapString flag = flags.getStringOrEmpty(i);
                                if (flag.is(ImapConstants.FLAG_DELETED)) {
                                    message.setFlagInternal(Flag.DELETED, true);
                                } else if (flag.is(ImapConstants.FLAG_ANSWERED)) {
                                    message.setFlagInternal(Flag.ANSWERED, true);
                                } else if (flag.is(ImapConstants.FLAG_SEEN)) {
                                    message.setFlagInternal(Flag.SEEN, true);
                                } else if (flag.is(ImapConstants.FLAG_FLAGGED)) {
                                    message.setFlagInternal(Flag.FLAGGED, true);
                                }
                            }
                        }
                        if (fp.contains(FetchProfile.Item.ENVELOPE)) {
                            final Date internalDate = fetchList.getKeyedStringOrEmpty(
                                    ImapConstants.INTERNALDATE).getDateOrNull();
                            final int size = fetchList.getKeyedStringOrEmpty(
                                    ImapConstants.RFC822_SIZE).getNumberOrZero();
                            final String header = fetchList.getKeyedStringOrEmpty(
                                    ImapConstants.BODY_BRACKET_HEADER, true).getString();

                            message.setInternalDate(internalDate);
                            message.setSize(size);
                            message.parse(Utility.streamFromAsciiString(header));
                        }
                        if (fp.contains(FetchProfile.Item.STRUCTURE)) {
                            ImapList bs = fetchList.getKeyedListOrEmpty(
                                    ImapConstants.BODYSTRUCTURE);
                            if (!bs.isEmpty()) {
                                try {
                                    parseBodyStructure(bs, message, ImapConstants.TEXT);
                                } catch (MessagingException e) {
                                    if (Email.LOGD) {
                                        Log.v(Email.LOG_TAG, "Error handling message", e);
                                    }
                                    message.setBody(null);
                                }
                            }
                        }
                        if (fp.contains(FetchProfile.Item.BODY)
                                || fp.contains(FetchProfile.Item.BODY_SANE)) {
                            // Body is keyed by "BODY[...".
                            // TOOD Should we accept "RFC822" as well??
                            // The old code didn't really check the key, so it accepted any literal
                            // that first appeared.
                            ImapString body = fetchList.getKeyedStringOrEmpty("BODY[", true);
                            InputStream bodyStream = body.getAsStream();
                            message.parse(bodyStream);
                        }
                        if (fetchPart != null && fetchPart.getSize() > 0) {
                            InputStream bodyStream =
                                    fetchList.getKeyedStringOrEmpty("BODY[", true).getAsStream();
                            String contentType = fetchPart.getContentType();
                            String contentTransferEncoding = fetchPart.getHeader(
                                    MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING)[0];

                            // TODO Don't create 2 temp files.
                            // decodeBody creates BinaryTempFileBody, but we could avoid this
                            // if we implement ImapStringBody.
                            // (We'll need to share a temp file.  Protect it with a ref-count.)
                            fetchPart.setBody(MimeUtility.decodeBody(
                                    bodyStream,
                                    contentTransferEncoding));
                        }

                        if (listener != null) {
                            listener.messageRetrieved(message);
                        }
                    } finally {
                        destroyResponses();
                    }
                } while (!response.isTagged());
            } catch (IOException ioe) {
                throw ioExceptionHandler(mConnection, ioe);
            }
        }

        @Override
        public Flag[] getPermanentFlags() throws MessagingException {
            return PERMANENT_FLAGS;
        }

        /**
         * Handle any untagged responses that the caller doesn't care to handle themselves.
         * @param responses
         */
        private void handleUntaggedResponses(List<ImapResponse> responses) {
            for (ImapResponse response : responses) {
                handleUntaggedResponse(response);
            }
        }

        /**
         * Handle an untagged response that the caller doesn't care to handle themselves.
         * @param response
         */
        private void handleUntaggedResponse(ImapResponse response) {
            if (response.isDataResponse(1, ImapConstants.EXISTS)) {
                mMessageCount = response.getStringOrEmpty(0).getNumberOrZero();
            }
        }

        private static void parseBodyStructure(ImapList bs, Part part, String id)
                throws MessagingException {
            if (bs.getElementOrNone(0).isList()) {
                /*
                 * This is a multipart/*
                 */
                MimeMultipart mp = new MimeMultipart();
                for (int i = 0, count = bs.size(); i < count; i++) {
                    ImapElement e = bs.getElementOrNone(i);
                    if (e.isList()) {
                        /*
                         * For each part in the message we're going to add a new BodyPart and parse
                         * into it.
                         */
                        MimeBodyPart bp = new MimeBodyPart();
                        if (id.equals(ImapConstants.TEXT)) {
                            parseBodyStructure(bs.getListOrEmpty(i), bp, Integer.toString(i + 1));

                        } else {
                            parseBodyStructure(bs.getListOrEmpty(i), bp, id + "." + (i + 1));
                        }
                        mp.addBodyPart(bp);

                    } else {
                        if (e.isString()) {
                            mp.setSubType(bs.getStringOrEmpty(i).getString().toLowerCase());
                        }
                        break; // Ignore the rest of the list.
                    }
                }
                part.setBody(mp);
            } else {
                /*
                 * This is a body. We need to add as much information as we can find out about
                 * it to the Part.
                 */

                /*
                 body type
                 body subtype
                 body parameter parenthesized list
                 body id
                 body description
                 body encoding
                 body size
                 */

                final ImapString type = bs.getStringOrEmpty(0);
                final ImapString subType = bs.getStringOrEmpty(1);
                final String mimeType =
                        (type.getString() + "/" + subType.getString()).toLowerCase();

                final ImapList bodyParams = bs.getListOrEmpty(2);
                final ImapString cid = bs.getStringOrEmpty(3);
                final ImapString encoding = bs.getStringOrEmpty(5);
                final int size = bs.getStringOrEmpty(6).getNumberOrZero();

                if (MimeUtility.mimeTypeMatches(mimeType, MimeUtility.MIME_TYPE_RFC822)) {
                    // A body type of type MESSAGE and subtype RFC822
                    // contains, immediately after the basic fields, the
                    // envelope structure, body structure, and size in
                    // text lines of the encapsulated message.
                    // [MESSAGE, RFC822, [NAME, filename.eml], NIL, NIL, 7BIT, 5974, NIL,
                    //     [INLINE, [FILENAME*0, Fwd: Xxx..., FILENAME*1, filename.eml]], NIL]
                    /*
                     * This will be caught by fetch and handled appropriately.
                     */
                    throw new MessagingException("BODYSTRUCTURE " + MimeUtility.MIME_TYPE_RFC822
                            + " not yet supported.");
                }

                /*
                 * Set the content type with as much information as we know right now.
                 */
                final StringBuilder contentType = new StringBuilder(mimeType);

                /*
                 * If there are body params we might be able to get some more information out
                 * of them.
                 */
                for (int i = 1, count = bodyParams.size(); i < count; i += 2) {

                    // TODO We need to convert " into %22, but
                    // because MimeUtility.getHeaderParameter doesn't recognize it,
                    // we can't fix it for now.
                    contentType.append(String.format(";\n %s=\"%s\"",
                            bodyParams.getStringOrEmpty(i - 1).getString(),
                            bodyParams.getStringOrEmpty(i).getString()));
                }

                part.setHeader(MimeHeader.HEADER_CONTENT_TYPE, contentType.toString());

                // Extension items
                final ImapList bodyDisposition;

                if (type.is(ImapConstants.TEXT) && bs.getElementOrNone(9).isList()) {
                    // If media-type is TEXT, 9th element might be: [body-fld-lines] := number
                    // So, if it's not a list, use 10th element.
                    // (Couldn't find evidence in the RFC if it's ALWAYS 10th element.)
                    bodyDisposition = bs.getListOrEmpty(9);
                } else {
                    bodyDisposition = bs.getListOrEmpty(8);
                }

                final StringBuilder contentDisposition = new StringBuilder();

                if (bodyDisposition.size() > 0) {
                    final String bodyDisposition0Str =
                            bodyDisposition.getStringOrEmpty(0).getString().toLowerCase();
                    if (!TextUtils.isEmpty(bodyDisposition0Str)) {
                        contentDisposition.append(bodyDisposition0Str);
                    }

                    final ImapList bodyDispositionParams = bodyDisposition.getListOrEmpty(1);
                    if (!bodyDispositionParams.isEmpty()) {
                        /*
                         * If there is body disposition information we can pull some more
                         * information about the attachment out.
                         */
                        for (int i = 1, count = bodyDispositionParams.size(); i < count; i += 2) {

                            // TODO We need to convert " into %22.  See above.
                            contentDisposition.append(String.format(";\n %s=\"%s\"",
                                    bodyDispositionParams.getStringOrEmpty(i - 1)
                                            .getString().toLowerCase(),
                                    bodyDispositionParams.getStringOrEmpty(i).getString()));
                        }
                    }
                }

                if ((size > 0)
                        && (MimeUtility.getHeaderParameter(contentDisposition.toString(), "size")
                                == null)) {
                    contentDisposition.append(String.format(";\n size=%d", size));
                }

                if (contentDisposition.length() > 0) {
                    /*
                     * Set the content disposition containing at least the size. Attachment
                     * handling code will use this down the road.
                     */
                    part.setHeader(MimeHeader.HEADER_CONTENT_DISPOSITION,
                            contentDisposition.toString());
                }

                /*
                 * Set the Content-Transfer-Encoding header. Attachment code will use this
                 * to parse the body.
                 */
                if (!encoding.isEmpty()) {
                    part.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING,
                            encoding.getString());
                }

                /*
                 * Set the Content-ID header.
                 */
                if (!cid.isEmpty()) {
                    part.setHeader(MimeHeader.HEADER_CONTENT_ID, cid.getString());
                }

                if (size > 0) {
                    if (part instanceof ImapMessage) {
                        ((ImapMessage) part).setSize(size);
                    } else if (part instanceof MimeBodyPart) {
                        ((MimeBodyPart) part).setSize(size);
                    } else {
                        throw new MessagingException("Unknown part type " + part.toString());
                    }
                }
                part.setHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA, id);
            }

        }

        /**
         * Appends the given messages to the selected folder. This implementation also determines
         * the new UID of the given message on the IMAP server and sets the Message's UID to the
         * new server UID.
         */
        @Override
        public void appendMessages(Message[] messages) throws MessagingException {
            checkOpen();
            try {
                for (Message message : messages) {
                    // Create output count
                    CountingOutputStream out = new CountingOutputStream();
                    EOLConvertingOutputStream eolOut = new EOLConvertingOutputStream(out);
                    message.writeTo(eolOut);
                    eolOut.flush();
                    // Create flag list (most often this will be "\SEEN")
                    String flagList = "";
                    Flag[] flags = message.getFlags();
                    if (flags.length > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0, count = flags.length; i < count; i++) {
                            Flag flag = flags[i];
                            if (flag == Flag.SEEN) {
                                sb.append(" " + ImapConstants.FLAG_SEEN);
                            } else if (flag == Flag.FLAGGED) {
                                sb.append(" " + ImapConstants.FLAG_FLAGGED);
                            }
                        }
                        if (sb.length() > 0) {
                            flagList = sb.substring(1);
                        }
                    }

                    mConnection.sendCommand(
                            String.format(ImapConstants.APPEND + " \"%s\" (%s) {%d}",
                                    encodeFolderName(mName),
                                    flagList,
                                    out.getCount()), false);
                    ImapResponse response;
                    do {
                        response = mConnection.readResponse();
                        if (response.isContinuationRequest()) {
                            eolOut = new EOLConvertingOutputStream(
                                    mConnection.mTransport.getOutputStream());
                            message.writeTo(eolOut);
                            eolOut.write('\r');
                            eolOut.write('\n');
                            eolOut.flush();
                        } else if (!response.isTagged()) {
                            handleUntaggedResponse(response);
                        }
                    } while (!response.isTagged());

                    // TODO Why not check the response?

                    /*
                     * Try to recover the UID of the message from an APPENDUID response.
                     * e.g. 11 OK [APPENDUID 2 238268] APPEND completed
                     */
                    final ImapList appendList = response.getListOrEmpty(1);
                    if ((appendList.size() >= 3) && appendList.is(0, ImapConstants.APPENDUID)) {
                        String serverUid = appendList.getStringOrEmpty(2).getString();
                        if (!TextUtils.isEmpty(serverUid)) {
                            message.setUid(serverUid);
                            continue;
                        }
                    }

                    /*
                     * Try to find the UID of the message we just appended using the
                     * Message-ID header.  If there are more than one response, take the
                     * last one, as it's most likely the newest (the one we just uploaded).
                     */
                    String messageId = message.getMessageId();
                    if (messageId == null || messageId.length() == 0) {
                        continue;
                    }
                    String[] uids = searchForUids(
                            String.format("(HEADER MESSAGE-ID %s)", messageId));
                    if (uids.length > 0) {
                        message.setUid(uids[0]);
                    }
                }
            } catch (IOException ioe) {
                throw ioExceptionHandler(mConnection, ioe);
            } finally {
                destroyResponses();
            }
        }

        @Override
        public Message[] expunge() throws MessagingException {
            checkOpen();
            try {
                handleUntaggedResponses(mConnection.executeSimpleCommand(ImapConstants.EXPUNGE));
            } catch (IOException ioe) {
                throw ioExceptionHandler(mConnection, ioe);
            } finally {
                destroyResponses();
            }
            return null;
        }

        @Override
        public void setFlags(Message[] messages, Flag[] flags, boolean value)
                throws MessagingException {
            checkOpen();

            String allFlags = "";
            if (flags.length > 0) {
                StringBuilder flagList = new StringBuilder();
                for (int i = 0, count = flags.length; i < count; i++) {
                    Flag flag = flags[i];
                    if (flag == Flag.SEEN) {
                        flagList.append(" " + ImapConstants.FLAG_SEEN);
                    } else if (flag == Flag.DELETED) {
                        flagList.append(" " + ImapConstants.FLAG_DELETED);
                    } else if (flag == Flag.FLAGGED) {
                        flagList.append(" " + ImapConstants.FLAG_FLAGGED);
                    }
                }
                allFlags = flagList.substring(1);
            }
            try {
                mConnection.executeSimpleCommand(String.format(
                        ImapConstants.UID_STORE + " %s %s" + ImapConstants.FLAGS_SILENT + " (%s)",
                        joinMessageUids(messages),
                        value ? "+" : "-",
                        allFlags));

            } catch (IOException ioe) {
                throw ioExceptionHandler(mConnection, ioe);
            } finally {
                destroyResponses();
            }
        }

        private void checkOpen() throws MessagingException {
            if (!isOpen()) {
                throw new MessagingException("Folder " + mName + " is not open.");
            }
        }

        private MessagingException ioExceptionHandler(ImapConnection connection, IOException ioe)
                throws MessagingException {
            if (Email.DEBUG) {
                Log.d(Email.LOG_TAG, "IO Exception detected: ", ioe);
            }
            connection.destroyResponses();
            connection.close();
            if (connection == mConnection) {
                mConnection = null; // To prevent close() from returning the connection to the pool.
                close(false);
            }
            return new MessagingException("IO Error", ioe);
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof ImapFolder) {
                return ((ImapFolder)o).mName.equals(mName);
            }
            return super.equals(o);
        }

        @Override
        public Message createMessage(String uid) throws MessagingException {
            return new ImapMessage(uid, this);
        }
    }

    /**
     * A cacheable class that stores the details for a single IMAP connection.
     */
    class ImapConnection {
        private static final String IMAP_DEDACTED_LOG = "[IMAP command redacted]";
        private Transport mTransport;
        private ImapResponseParser mParser;
        /** # of command/response lines to log upon crash. */
        private static final int DISCOURSE_LOGGER_SIZE = 64;
        private final DiscourseLogger mDiscourse = new DiscourseLogger(DISCOURSE_LOGGER_SIZE);

        public void open() throws IOException, MessagingException {
            if (mTransport != null && mTransport.isOpen()) {
                return;
            }

            try {
                // copy configuration into a clean transport, if necessary
                if (mTransport == null) {
                    mTransport = mRootTransport.newInstanceWithConfiguration();
                }

                mTransport.open();
                mTransport.setSoTimeout(MailTransport.SOCKET_READ_TIMEOUT);

                createParser();

                // BANNER
                mParser.readResponse();

                // CAPABILITY
                ImapResponse capabilityResponse = queryCapabilities();

                // TLS
                if (mTransport.canTryTlsSecurity()) {
                    if (capabilityResponse.contains(ImapConstants.STARTTLS)) {
                        // STARTTLS
                        executeSimpleCommand(ImapConstants.STARTTLS);

                        mTransport.reopenTls();
                        mTransport.setSoTimeout(MailTransport.SOCKET_READ_TIMEOUT);
                        createParser();
                        // Per RFC requirement (3501-6.2.1) gather new capabilities
                        capabilityResponse = queryCapabilities();
                    } else {
                        if (Config.LOGD && Email.DEBUG) {
                            Log.d(Email.LOG_TAG, "TLS not supported but required");
                        }
                        throw new MessagingException(MessagingException.TLS_REQUIRED);
                    }
                }

                // ID
                if (capabilityResponse.contains(ImapConstants.ID)) {
                    // Assign user-agent string (for RFC2971 ID command)
                    String mUserAgent = getImapId(mContext, mUsername, mRootTransport.getHost(),
                            capabilityResponse);
                    if (mUserAgent != null) {
                        mIdPhrase = ImapConstants.ID + " (" + mUserAgent + ")";
                    } else if (DEBUG_FORCE_SEND_ID) {
                        mIdPhrase = ImapConstants.ID + " " + ImapConstants.NIL;
                    }
                    // else: mIdPhrase = null, no ID will be emitted

                    // Send user-agent in an RFC2971 ID command
                    if (mIdPhrase != null) {
                        try {
                            executeSimpleCommand(mIdPhrase);
                        } catch (ImapException ie) {
                            // Log for debugging, but this is not a fatal problem.
                            if (Config.LOGD && Email.DEBUG) {
                                Log.d(Email.LOG_TAG, ie.toString());
                            }
                        } catch (IOException ioe) {
                            // Special case to handle malformed OK responses and ignore them.
                            // A true IOException will recur on the following login steps
                            // This can go away after the parser is fixed - see bug 2138981
                        }
                    }
                }

                // LOGIN
                try {
                    // TODO eventually we need to add additional authentication
                    // options such as SASL
                    executeSimpleCommand(mLoginPhrase, true);
                } catch (ImapException ie) {
                    if (Config.LOGD && Email.DEBUG) {
                        Log.d(Email.LOG_TAG, ie.toString());
                    }
                    throw new AuthenticationFailedException(ie.getAlertText(), ie);

                } catch (MessagingException me) {
                    throw new AuthenticationFailedException(null, me);
                }
            } catch (SSLException e) {
                if (Config.LOGD && Email.DEBUG) {
                    Log.d(Email.LOG_TAG, e.toString());
                }
                throw new CertificateValidationException(e.getMessage(), e);
            } catch (IOException ioe) {
                // NOTE:  Unlike similar code in POP3, I'm going to rethrow as-is.  There is a lot
                // of other code here that catches IOException and I don't want to break it.
                // This catch is only here to enhance logging of connection-time issues.
                if (Config.LOGD && Email.DEBUG) {
                    Log.d(Email.LOG_TAG, ioe.toString());
                }
                throw ioe;
            } finally {
                destroyResponses();
            }
        }

        public void close() {
            if (mTransport != null) {
                mTransport.close();
                mTransport = null;
            }
        }

        /**
         * Create an {@link ImapResponseParser} from {@code mTransport.getInputStream()} and
         * set it to {@link #mParser}.
         *
         * If we already have an {@link ImapResponseParser}, we
         * {@link #destroyResponses()} and throw it away.
         */
        private void createParser() {
            destroyResponses();
            mParser = new ImapResponseParser(mTransport.getInputStream(), mDiscourse);
        }

        public void destroyResponses() {
            if (mParser != null) {
                mParser.destroyResponses();
            }
        }

        /* package */ boolean isTransportOpenForTest() {
            return mTransport != null ? mTransport.isOpen() : false;
        }

        public ImapResponse readResponse() throws IOException, MessagingException {
            return mParser.readResponse();
        }

        /**
         * Send a single command to the server.  The command will be preceded by an IMAP command
         * tag and followed by \r\n (caller need not supply them).
         *
         * @param command The command to send to the server
         * @param sensitive If true, the command will not be logged
         * @return Returns the command tag that was sent
         */
        public String sendCommand(String command, boolean sensitive)
            throws MessagingException, IOException {
            open();
            String tag = Integer.toString(mNextCommandTag.incrementAndGet());
            String commandToSend = tag + " " + command;
            mTransport.writeLine(commandToSend, sensitive ? IMAP_DEDACTED_LOG : null);
            mDiscourse.addSentCommand(sensitive ? IMAP_DEDACTED_LOG : commandToSend);
            return tag;
        }

        public List<ImapResponse> executeSimpleCommand(String command) throws IOException,
                MessagingException {
            return executeSimpleCommand(command, false);
        }

        public List<ImapResponse> executeSimpleCommand(String command, boolean sensitive)
                throws IOException, MessagingException {
            String tag = sendCommand(command, sensitive);
            ArrayList<ImapResponse> responses = new ArrayList<ImapResponse>();
            ImapResponse response;
            do {
                response = mParser.readResponse();
                responses.add(response);
            } while (!response.isTagged());
            if (!response.isOk()) {
                final String toString = response.toString();
                final String alert = response.getAlertTextOrEmpty().getString();
                destroyResponses();
                throw new ImapException(toString, alert);
            }
            return responses;
        }

        /**
         * Query server for capabilities.
         */
        private ImapResponse queryCapabilities() throws IOException, MessagingException {
            ImapResponse capabilityResponse = null;
            for (ImapResponse r : executeSimpleCommand(ImapConstants.CAPABILITY)) {
                if (r.is(0, ImapConstants.CAPABILITY)) {
                    capabilityResponse = r;
                    break;
                }
            }
            if (capabilityResponse == null) {
                throw new MessagingException("Invalid CAPABILITY response received");
            }
            return capabilityResponse;
        }

        /** @see ImapResponseParser#logLastDiscourse() */
        public void logLastDiscourse() {
            mDiscourse.logLastDiscourse();
        }
    }

    static class ImapMessage extends MimeMessage {
        ImapMessage(String uid, Folder folder) throws MessagingException {
            this.mUid = uid;
            this.mFolder = folder;
        }

        public void setSize(int size) {
            this.mSize = size;
        }

        @Override
        public void parse(InputStream in) throws IOException, MessagingException {
            super.parse(in);
        }

        public void setFlagInternal(Flag flag, boolean set) throws MessagingException {
            super.setFlag(flag, set);
        }

        @Override
        public void setFlag(Flag flag, boolean set) throws MessagingException {
            super.setFlag(flag, set);
            mFolder.setFlags(new Message[] { this }, new Flag[] { flag }, set);
        }
    }

    static class ImapException extends MessagingException {
        String mAlertText;

        public ImapException(String message, String alertText, Throwable throwable) {
            super(message, throwable);
            this.mAlertText = alertText;
        }

        public ImapException(String message, String alertText) {
            super(message);
            this.mAlertText = alertText;
        }

        public String getAlertText() {
            return mAlertText;
        }

        public void setAlertText(String alertText) {
            mAlertText = alertText;
        }
    }
}
