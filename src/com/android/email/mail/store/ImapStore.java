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
import com.android.email.LegacyConversions;
import com.android.email.Preferences;
import com.android.email.VendorPolicyLoader;
import com.android.email.mail.Store;
import com.android.email.mail.Transport;
import com.android.email.mail.store.imap.ImapConstants;
import com.android.email.mail.store.imap.ImapList;
import com.android.email.mail.store.imap.ImapResponse;
import com.android.email.mail.store.imap.ImapResponseParser;
import com.android.email.mail.store.imap.ImapString;
import com.android.email.mail.store.imap.ImapUtility;
import com.android.email.mail.transport.DiscourseLogger;
import com.android.email.mail.transport.MailTransport;
import com.android.emailcommon.Logging;
import com.android.emailcommon.internet.MimeMessage;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.CertificateValidationException;
import com.android.emailcommon.mail.Flag;
import com.android.emailcommon.mail.Folder;
import com.android.emailcommon.mail.Message;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.HostAuth;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.utility.Utility;
import com.beetstra.jutf7.CharsetProvider;
import com.google.common.annotations.VisibleForTesting;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
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

    static final int COPY_BUFFER_SIZE = 16*1024;

    static final Flag[] PERMANENT_FLAGS = { Flag.DELETED, Flag.SEEN, Flag.FLAGGED };
    private final Context mContext;
    private final Account mAccount;
    private Transport mRootTransport;
    private String mUsername;
    private String mPassword;
    private String mLoginPhrase;
    private String mIdPhrase = null;
    @VisibleForTesting static String sImapId = null;
    /*package*/ String mPathPrefix;
    /*package*/ String mPathSeparator;

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
    private final HashMap<String, ImapFolder> mFolderCache = new HashMap<String, ImapFolder>();

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
    public static Store newInstance(Account account, Context context,
            PersistentDataCallbacks callbacks) throws MessagingException {
        return new ImapStore(context, account);
    }

    /**
     * Creates a new store for the given account.
     */
    private ImapStore(Context context, Account account) throws MessagingException {
        mContext = context;
        mAccount = account;

        HostAuth recvAuth = account.getOrCreateHostAuthRecv(context);
        if (recvAuth == null || !STORE_SCHEME_IMAP.equalsIgnoreCase(recvAuth.mProtocol)) {
            throw new MessagingException("Unsupported protocol");
        }
        // defaults, which can be changed by security modifiers
        int connectionSecurity = Transport.CONNECTION_SECURITY_NONE;
        int defaultPort = 143;

        // check for security flags and apply changes
        if ((recvAuth.mFlags & HostAuth.FLAG_SSL) != 0) {
            connectionSecurity = Transport.CONNECTION_SECURITY_SSL;
            defaultPort = 993;
        } else if ((recvAuth.mFlags & HostAuth.FLAG_TLS) != 0) {
            connectionSecurity = Transport.CONNECTION_SECURITY_TLS;
        }
        boolean trustCertificates = ((recvAuth.mFlags & HostAuth.FLAG_TRUST_ALL) != 0);
        int port = defaultPort;
        if (recvAuth.mPort != HostAuth.PORT_UNKNOWN) {
            port = recvAuth.mPort;
        }
        mRootTransport = new MailTransport("IMAP");
        mRootTransport.setHost(recvAuth.mAddress);
        mRootTransport.setPort(port);
        mRootTransport.setSecurity(connectionSecurity, trustCertificates);

        String[] userInfoParts = recvAuth.getLogin();
        if (userInfoParts != null) {
            mUsername = userInfoParts[0];
            mPassword = userInfoParts[1];

            // build the LOGIN string once (instead of over-and-over again.)
            // apply the quoting here around the built-up password
            mLoginPhrase = ImapConstants.LOGIN + " " + mUsername + " "
                    + ImapUtility.imapQuoted(mPassword);
        }
        mPathPrefix = recvAuth.mDomain;
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
     * @param capabilities a list of the capabilities from the server
     * @return a String for use in an IMAP ID message.
     */
    @VisibleForTesting static String getImapId(Context context, String userName, String host,
            String capabilities) {
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
                capabilities);
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
            Log.d(Logging.LOG_TAG, "couldn't obtain SHA-1 hash for device UID");
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
    @VisibleForTesting static String makeCommonImapId(String packageName, String version,
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
    public Folder getFolder(String name) {
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

    /**
     * Creates a mailbox hierarchy out of the flat data provided by the server.
     */
    @VisibleForTesting
    static void createHierarchy(HashMap<String, ImapFolder> mailboxes) {
        Set<String> pathnames = mailboxes.keySet();
        for (String path : pathnames) {
            final ImapFolder folder = mailboxes.get(path);
            final Mailbox mailbox = folder.mMailbox;
            int delimiterIdx = mailbox.mServerId.lastIndexOf(mailbox.mDelimiter);
            long parentKey = -1L;
            if (delimiterIdx != -1) {
                String parentPath = path.substring(0, delimiterIdx);
                final ImapFolder parentFolder = mailboxes.get(parentPath);
                final Mailbox parentMailbox = (parentFolder == null) ? null : parentFolder.mMailbox;
                if (parentMailbox != null) {
                    parentKey = parentMailbox.mId;
                    parentMailbox.mFlags
                            |= (Mailbox.FLAG_HAS_CHILDREN | Mailbox.FLAG_CHILDREN_VISIBLE);
                }
            }
            mailbox.mParentKey = parentKey;
        }
    }

    /**
     * Creates a {@link Folder} and associated {@link Mailbox}. If the folder does not already
     * exist in the local database, a new row will immediately be created in the mailbox table.
     * Otherwise, the existing row will be used. Any changes to existing rows, will not be stored
     * to the database immediately.
     * @param accountId The ID of the account the mailbox is to be associated with
     * @param mailboxPath The path of the mailbox to add
     * @param delimiter A path delimiter. May be {@code null} if there is no delimiter.
     * @param selectable If {@code true}, the mailbox can be selected and used to store messages.
     */
    private ImapFolder addMailbox(Context context, long accountId, String mailboxPath,
            char delimiter, boolean selectable) {
        ImapFolder folder = (ImapFolder) getFolder(mailboxPath);
        Mailbox mailbox = getMailboxForPath(context, accountId, mailboxPath);
        if (mailbox.isSaved()) {
            // existing mailbox
            // mailbox retrieved from database; save hash _before_ updating fields
            folder.mHash = mailbox.getHashes();
        }
        updateMailbox(mailbox, accountId, mailboxPath, delimiter, selectable,
                LegacyConversions.inferMailboxTypeFromName(context, mailboxPath));
        if (folder.mHash == null) {
            // new mailbox
            // save hash after updating. allows tracking changes if the mailbox is saved
            // outside of #saveMailboxList()
            folder.mHash = mailbox.getHashes();
            // We must save this here to make sure we have a valid ID for later
            mailbox.save(mContext);
        }
        folder.mMailbox = mailbox;
        return folder;
    }

    /**
     * Persists the folders in the given list.
     */
    private static void saveMailboxList(Context context, HashMap<String, ImapFolder> folderMap) {
        for (ImapFolder imapFolder : folderMap.values()) {
            imapFolder.save(context);
        }
    }

    @Override
    public Folder[] updateFolders() throws MessagingException {
        ImapConnection connection = getConnection();
        try {
            HashMap<String, ImapFolder> mailboxes = new HashMap<String, ImapFolder>();
            // Establish a connection to the IMAP server; if necessary
            // This ensures a valid prefix if the prefix is automatically set by the server
            connection.executeSimpleCommand(ImapConstants.NOOP);
            String imapCommand = ImapConstants.LIST + " \"\" \"*\"";
            if (mPathPrefix != null) {
                imapCommand = ImapConstants.LIST + " \"\" \"" + mPathPrefix + "*\"";
            }
            List<ImapResponse> responses = connection.executeSimpleCommand(imapCommand);
            for (ImapResponse response : responses) {
                // S: * LIST (\Noselect) "/" ~/Mail/foo
                if (response.isDataResponse(0, ImapConstants.LIST)) {
                    // Get folder name.
                    ImapString encodedFolder = response.getStringOrEmpty(3);
                    if (encodedFolder.isEmpty()) continue;

                    String folderName = decodeFolderName(encodedFolder.getString(), mPathPrefix);
                    if (ImapConstants.INBOX.equalsIgnoreCase(folderName)) continue;

                    // Parse attributes.
                    boolean selectable =
                        !response.getListOrEmpty(1).contains(ImapConstants.FLAG_NO_SELECT);
                    String delimiter = response.getStringOrEmpty(2).getString();
                    char delimiterChar = '\0';
                    if (!TextUtils.isEmpty(delimiter)) {
                        delimiterChar = delimiter.charAt(0);
                    }
                    ImapFolder folder =
                        addMailbox(mContext, mAccount.mId, folderName, delimiterChar, selectable);
                    mailboxes.put(folderName, folder);
                }
            }
            Folder newFolder =
                addMailbox(mContext, mAccount.mId, ImapConstants.INBOX, '\0', true /*selectable*/);
            mailboxes.put(ImapConstants.INBOX, (ImapFolder)newFolder);
            createHierarchy(mailboxes);
            saveMailboxList(mContext, mailboxes);
            return mailboxes.values().toArray(new Folder[] {});
        } catch (IOException ioe) {
            connection.close();
            throw new MessagingException("Unable to get folder list.", ioe);
        } catch (AuthenticationFailedException afe) {
            // We do NOT want this connection pooled, or we will continue to send NOOP and SELECT
            // commands to the server
            connection.destroyResponses();
            connection = null;
            throw afe;
        } finally {
            if (connection != null) {
                connection.destroyResponses();
                poolConnection(connection);
            }
        }
    }

    @Override
    public Bundle checkSettings() throws MessagingException {
        int result = MessagingException.NO_ERROR;
        Bundle bundle = new Bundle();
        ImapConnection connection = new ImapConnection();
        try {
            connection.open();
            connection.close();
        } catch (IOException ioe) {
            bundle.putString(EmailServiceProxy.VALIDATE_BUNDLE_ERROR_MESSAGE, ioe.getMessage());
            result = MessagingException.IOERROR;
        } finally {
            connection.destroyResponses();
        }
        bundle.putInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE, result);
        return bundle;
    }

    /**
     * Fixes the path prefix, if necessary. The path prefix must always end with the
     * path separator.
     */
    /*package*/ void ensurePrefixIsValid() {
        // Make sure the path prefix ends with the path separator
        if (!TextUtils.isEmpty(mPathPrefix) && !TextUtils.isEmpty(mPathSeparator)) {
            if (!mPathPrefix.endsWith(mPathSeparator)) {
                mPathPrefix = mPathPrefix + mPathSeparator;
            }
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

    /**
     * Prepends the folder name with the given prefix and UTF-7 encodes it.
     */
    /* package */ static String encodeFolderName(String name, String prefix) {
        // do NOT add the prefix to the special name "INBOX"
        if (ImapConstants.INBOX.equalsIgnoreCase(name)) return name;

        // Prepend prefix
        if (prefix != null) {
            name = prefix + name;
        }

        // TODO bypass the conversion if name doesn't have special char.
        ByteBuffer bb = MODIFIED_UTF_7_CHARSET.encode(name);
        byte[] b = new byte[bb.limit()];
        bb.get(b);

        return Utility.fromAscii(b);
    }

    /**
     * UTF-7 decodes the folder name and removes the given path prefix.
     */
    /* package */ static String decodeFolderName(String name, String prefix) {
        // TODO bypass the conversion if name doesn't have special char.
        String folder;
        folder = MODIFIED_UTF_7_CHARSET.decode(ByteBuffer.wrap(Utility.toAscii(name))).toString();
        if ((prefix != null) && folder.startsWith(prefix)) {
            folder = folder.substring(prefix.length());
        }
        return folder;
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

    /**
     * A cacheable class that stores the details for a single IMAP connection.
     */
    class ImapConnection {
        /** ID capability per RFC 2971*/
        public static final int CAPABILITY_ID        = 1 << 0;
        /** NAMESPACE capability per RFC 2342 */
        public static final int CAPABILITY_NAMESPACE = 1 << 1;
        /** STARTTLS capability per RFC 3501 */
        public static final int CAPABILITY_STARTTLS  = 1 << 2;
        /** UIDPLUS capability per RFC 4315 */
        public static final int CAPABILITY_UIDPLUS   = 1 << 3;

        /** The capabilities supported; a set of CAPABILITY_* values. */
        private int mCapabilities;
        private static final String IMAP_DEDACTED_LOG = "[IMAP command redacted]";
        Transport mTransport;
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
                ImapResponse capabilities = queryCapabilities();

                boolean hasStartTlsCapability =
                    capabilities.contains(ImapConstants.STARTTLS);

                // TLS
                ImapResponse newCapabilities = doStartTls(hasStartTlsCapability);
                if (newCapabilities != null) {
                    capabilities = newCapabilities;
                }

                // NOTE: An IMAP response MUST be processed before issuing any new IMAP
                // requests. Subsequent requests may destroy previous response data. As
                // such, we save away capability information here for future use.
                setCapabilities(capabilities);
                String capabilityString = capabilities.flatten();

                // ID
                doSendId(isCapable(CAPABILITY_ID), capabilityString);

                // LOGIN
                doLogin();

                // NAMESPACE (only valid in the Authenticated state)
                doGetNamespace(isCapable(CAPABILITY_NAMESPACE));

                // Gets the path separator from the server
                doGetPathSeparator();

                ensurePrefixIsValid();
            } catch (SSLException e) {
                if (Email.DEBUG) {
                    Log.d(Logging.LOG_TAG, e.toString());
                }
                throw new CertificateValidationException(e.getMessage(), e);
            } catch (IOException ioe) {
                // NOTE:  Unlike similar code in POP3, I'm going to rethrow as-is.  There is a lot
                // of other code here that catches IOException and I don't want to break it.
                // This catch is only here to enhance logging of connection-time issues.
                if (Email.DEBUG) {
                    Log.d(Logging.LOG_TAG, ioe.toString());
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
         * Returns whether or not the specified capability is supported by the server.
         */
        public boolean isCapable(int capability) {
            return (mCapabilities & capability) != 0;
        }

        /**
         * Sets the capability flags according to the response provided by the server.
         * Note: We only set the capability flags that we are interested in. There are many IMAP
         * capabilities that we do not track.
         */
        private void setCapabilities(ImapResponse capabilities) {
            if (capabilities.contains(ImapConstants.ID)) {
                mCapabilities |= CAPABILITY_ID;
            }
            if (capabilities.contains(ImapConstants.NAMESPACE)) {
                mCapabilities |= CAPABILITY_NAMESPACE;
            }
            if (capabilities.contains(ImapConstants.UIDPLUS)) {
                mCapabilities |= CAPABILITY_UIDPLUS;
            }
            if (capabilities.contains(ImapConstants.STARTTLS)) {
                mCapabilities |= CAPABILITY_STARTTLS;
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

        /*package*/ List<ImapResponse> executeSimpleCommand(String command) throws IOException,
                MessagingException {
            return executeSimpleCommand(command, false);
        }

        /*package*/ List<ImapResponse> executeSimpleCommand(String command, boolean sensitive)
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

        /**
         * Sends client identification information to the IMAP server per RFC 2971. If
         * the server does not support the ID command, this will perform no operation.
         *
         * Interoperability hack:  Never send ID to *.secureserver.net, which sends back a
         * malformed response that our parser can't deal with.
         */
        private void doSendId(boolean hasIdCapability, String capabilities)
                throws MessagingException {
            if (!hasIdCapability) return;

            // Never send ID to *.secureserver.net
            String host = mRootTransport.getHost();
            if (host.toLowerCase().endsWith(".secureserver.net")) return;

            // Assign user-agent string (for RFC2971 ID command)
            String mUserAgent = getImapId(mContext, mUsername, host, capabilities);

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
                    if (Email.DEBUG) {
                        Log.d(Logging.LOG_TAG, ie.toString());
                    }
                } catch (IOException ioe) {
                    // Special case to handle malformed OK responses and ignore them.
                    // A true IOException will recur on the following login steps
                    // This can go away after the parser is fixed - see bug 2138981
                }
            }
        }

        /**
         * Gets the user's Personal Namespace from the IMAP server per RFC 2342. If the user
         * explicitly sets a namespace (using setup UI) or if the server does not support the
         * namespace command, this will perform no operation.
         */
        private void doGetNamespace(boolean hasNamespaceCapability) throws MessagingException {
            // user did not specify a hard-coded prefix; try to get it from the server
            if (hasNamespaceCapability && TextUtils.isEmpty(mPathPrefix)) {
                List<ImapResponse> responseList = Collections.emptyList();

                try {
                    responseList = executeSimpleCommand(ImapConstants.NAMESPACE);
                } catch (ImapException ie) {
                    // Log for debugging, but this is not a fatal problem.
                    if (Email.DEBUG) {
                        Log.d(Logging.LOG_TAG, ie.toString());
                    }
                } catch (IOException ioe) {
                    // Special case to handle malformed OK responses and ignore them.
                }

                for (ImapResponse response: responseList) {
                    if (response.isDataResponse(0, ImapConstants.NAMESPACE)) {
                        ImapList namespaceList = response.getListOrEmpty(1);
                        ImapList namespace = namespaceList.getListOrEmpty(0);
                        String namespaceString = namespace.getStringOrEmpty(0).getString();
                        if (!TextUtils.isEmpty(namespaceString)) {
                            mPathPrefix = decodeFolderName(namespaceString, null);
                            mPathSeparator = namespace.getStringOrEmpty(1).getString();
                        }
                    }
                }
            }
        }

        /**
         * Logs into the IMAP server
         */
        private void doLogin()
                throws IOException, MessagingException, AuthenticationFailedException {
            try {
                // TODO eventually we need to add additional authentication
                // options such as SASL
                executeSimpleCommand(mLoginPhrase, true);
            } catch (ImapException ie) {
                if (Email.DEBUG) {
                    Log.d(Logging.LOG_TAG, ie.toString());
                }
                throw new AuthenticationFailedException(ie.getAlertText(), ie);

            } catch (MessagingException me) {
                throw new AuthenticationFailedException(null, me);
            }
        }

        /**
         * Gets the path separator per the LIST command in RFC 3501. If the path separator
         * was obtained while obtaining the namespace or there is no prefix defined, this
         * will perform no operation.
         */
        private void doGetPathSeparator() throws MessagingException {
            // user did not specify a hard-coded prefix; try to get it from the server
            if (TextUtils.isEmpty(mPathSeparator) && !TextUtils.isEmpty(mPathPrefix)) {
                List<ImapResponse> responseList = Collections.emptyList();

                try {
                    responseList = executeSimpleCommand(ImapConstants.LIST + " \"\" \"\"");
                } catch (ImapException ie) {
                    // Log for debugging, but this is not a fatal problem.
                    if (Email.DEBUG) {
                        Log.d(Logging.LOG_TAG, ie.toString());
                    }
                } catch (IOException ioe) {
                    // Special case to handle malformed OK responses and ignore them.
                }

                for (ImapResponse response: responseList) {
                    if (response.isDataResponse(0, ImapConstants.LIST)) {
                        mPathSeparator = response.getStringOrEmpty(2).getString();
                    }
                }
            }
        }

        /**
         * Starts a TLS session with the IMAP server per RFC 3501. If the user has not opted
         * to use TLS or the server does not support the TLS capability, this will perform
         * no operation.
         */
        private ImapResponse doStartTls(boolean hasStartTlsCapability)
                throws IOException, MessagingException {
            if (mTransport.canTryTlsSecurity()) {
                if (hasStartTlsCapability) {
                    // STARTTLS
                    executeSimpleCommand(ImapConstants.STARTTLS);

                    mTransport.reopenTls();
                    mTransport.setSoTimeout(MailTransport.SOCKET_READ_TIMEOUT);
                    createParser();
                    // Per RFC requirement (3501-6.2.1) gather new capabilities
                    return(queryCapabilities());
                } else {
                    if (Email.DEBUG) {
                        Log.d(Logging.LOG_TAG, "TLS not supported but required");
                    }
                    throw new MessagingException(MessagingException.TLS_REQUIRED);
                }
            }
            return null;
        }

        /** @see DiscourseLogger#logLastDiscourse() */
        public void logLastDiscourse() {
            mDiscourse.logLastDiscourse();
        }
    }

    static class ImapMessage extends MimeMessage {
        ImapMessage(String uid, ImapFolder folder) {
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
        private static final long serialVersionUID = 1L;

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
