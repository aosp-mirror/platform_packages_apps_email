/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.text.TextUtils;
import android.util.Log;

import com.android.email.Email;
import com.android.email.mail.Transport;
import com.android.email.mail.store.ImapStore.ImapException;
import com.android.email.mail.store.imap.ImapConstants;
import com.android.email.mail.store.imap.ImapList;
import com.android.email.mail.store.imap.ImapResponse;
import com.android.email.mail.store.imap.ImapResponseParser;
import com.android.email.mail.store.imap.ImapUtility;
import com.android.email.mail.transport.DiscourseLogger;
import com.android.email.mail.transport.MailTransport;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.CertificateValidationException;
import com.android.emailcommon.mail.MessagingException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

/**
 * A cacheable class that stores the details for a single IMAP connection.
 */
class ImapConnection {
    // Always check in FALSE
    private static final boolean DEBUG_FORCE_SEND_ID = false;

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
    private static final String IMAP_REDACTED_LOG = "[IMAP command redacted]";
    Transport mTransport;
    private ImapResponseParser mParser;
    private ImapStore mImapStore;
    private String mUsername;
    private String mLoginPhrase;
    private String mIdPhrase = null;
    /** # of command/response lines to log upon crash. */
    private static final int DISCOURSE_LOGGER_SIZE = 64;
    private final DiscourseLogger mDiscourse = new DiscourseLogger(DISCOURSE_LOGGER_SIZE);
    /**
     * Next tag to use.  All connections associated to the same ImapStore instance share the same
     * counter to make tests simpler.
     * (Some of the tests involve multiple connections but only have a single counter to track the
     * tag.)
     */
    private final AtomicInteger mNextCommandTag = new AtomicInteger(0);


    // Keep others from instantiating directly
    ImapConnection(ImapStore store, String username, String password) {
        setStore(store, username, password);
    }

    void setStore(ImapStore store, String username, String password) {
        if (username != null && password != null) {
            mUsername = username;

            // build the LOGIN string once (instead of over-and-over again.)
            // apply the quoting here around the built-up password
            mLoginPhrase = ImapConstants.LOGIN + " " + mUsername + " "
                    + ImapUtility.imapQuoted(password);
        }
        mImapStore = store;
    }
    void open() throws IOException, MessagingException {
        if (mTransport != null && mTransport.isOpen()) {
            return;
        }

        try {
            // copy configuration into a clean transport, if necessary
            if (mTransport == null) {
                mTransport = mImapStore.cloneTransport();
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

            mImapStore.ensurePrefixIsValid();
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

    /**
     * Closes the connection and releases all resources. This connection can not be used again
     * until {@link #setStore(ImapStore, String, String)} is called.
     */
    void close() {
        if (mTransport != null) {
            mTransport.close();
            mTransport = null;
        }
        destroyResponses();
        mParser = null;
        mImapStore = null;
    }

    /**
     * Returns whether or not the specified capability is supported by the server.
     */
    private boolean isCapable(int capability) {
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

    void destroyResponses() {
        if (mParser != null) {
            mParser.destroyResponses();
        }
    }

    boolean isTransportOpenForTest() {
        return mTransport != null ? mTransport.isOpen() : false;
    }

    ImapResponse readResponse() throws IOException, MessagingException {
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
    String sendCommand(String command, boolean sensitive)
        throws MessagingException, IOException {
        open();
        String tag = Integer.toString(mNextCommandTag.incrementAndGet());
        String commandToSend = tag + " " + command;
        mTransport.writeLine(commandToSend, sensitive ? IMAP_REDACTED_LOG : null);
        mDiscourse.addSentCommand(sensitive ? IMAP_REDACTED_LOG : commandToSend);
        return tag;
    }


    /**
     * Send a single, complex command to the server.  The command will be preceded by an IMAP
     * command tag and followed by \r\n (caller need not supply them).  After each piece of the
     * command, a response will be read which MUST be a continuation request.
     *
     * @param commands An array of Strings comprising the command to be sent to the server
     * @return Returns the command tag that was sent
     */
    String sendComplexCommand(List<String> commands, boolean sensitive) throws MessagingException,
            IOException {
        open();
        String tag = Integer.toString(mNextCommandTag.incrementAndGet());
        int len = commands.size();
        for (int i = 0; i < len; i++) {
            String commandToSend = commands.get(i);
            // The first part of the command gets the tag
            if (i == 0) {
                commandToSend = tag + " " + commandToSend;
            } else {
                // Otherwise, read the response from the previous part of the command
                ImapResponse response = readResponse();
                // If it isn't a continuation request, that's an error
                if (!response.isContinuationRequest()) {
                    throw new MessagingException("Expected continuation request");
                }
            }
            // Send the command
            mTransport.writeLine(commandToSend, null);
            mDiscourse.addSentCommand(sensitive ? IMAP_REDACTED_LOG : commandToSend);
        }
        return tag;
    }

    List<ImapResponse> executeSimpleCommand(String command) throws IOException,
            MessagingException {
        return executeSimpleCommand(command, false);
    }

    /**
     * Read and return all of the responses from the most recent command sent to the server
     *
     * @return a list of ImapResponses
     * @throws IOException
     * @throws MessagingException
     */
    List<ImapResponse> getCommandResponses() throws IOException, MessagingException {
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
     * Execute a simple command at the server, a simple command being one that is sent in a single
     * line of text
     *
     * @param command the command to send to the server
     * @param sensitive whether the command should be redacted in logs (used for login)
     * @return a list of ImapResponses
     * @throws IOException
     * @throws MessagingException
     */
     List<ImapResponse> executeSimpleCommand(String command, boolean sensitive)
            throws IOException, MessagingException {
        sendCommand(command, sensitive);
        return getCommandResponses();
    }

     /**
      * Execute a complex command at the server, a complex command being one that must be sent in
      * multiple lines due to the use of string literals
      *
      * @param commands a list of strings that comprise the command to be sent to the server
      * @param sensitive whether the command should be redacted in logs (used for login)
      * @return a list of ImapResponses
      * @throws IOException
      * @throws MessagingException
      */
      List<ImapResponse> executeComplexCommand(List<String> commands, boolean sensitive)
            throws IOException, MessagingException {
        sendComplexCommand(commands, sensitive);
        return getCommandResponses();
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
        String host = mTransport.getHost();
        if (host.toLowerCase().endsWith(".secureserver.net")) return;

        // Assign user-agent string (for RFC2971 ID command)
        String mUserAgent =
                ImapStore.getImapId(mImapStore.getContext(), mUsername, host, capabilities);

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
        if (hasNamespaceCapability && !mImapStore.isUserPrefixSet()) {
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
                        mImapStore.setPathPrefix(ImapStore.decodeFolderName(namespaceString, null));
                        mImapStore.setPathSeparator(namespace.getStringOrEmpty(1).getString());
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
        if (mImapStore.isUserPrefixSet()) {
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
                    mImapStore.setPathSeparator(response.getStringOrEmpty(2).getString());
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
    void logLastDiscourse() {
        mDiscourse.logLastDiscourse();
    }
}