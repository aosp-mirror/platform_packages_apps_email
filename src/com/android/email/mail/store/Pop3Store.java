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

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.mail.Store;
import com.android.email.mail.Transport;
import com.android.email.mail.transport.MailTransport;
import com.android.emailcommon.Logging;
import com.android.emailcommon.internet.MimeMessage;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.FetchProfile;
import com.android.emailcommon.mail.Flag;
import com.android.emailcommon.mail.Folder;
import com.android.emailcommon.mail.Folder.OpenMode;
import com.android.emailcommon.mail.Message;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.utility.LoggingInputStream;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class Pop3Store extends Store {
    // All flags defining debug or development code settings must be FALSE
    // when code is checked in or released.
    private static boolean DEBUG_FORCE_SINGLE_LINE_UIDL = false;
    private static boolean DEBUG_LOG_RAW_STREAM = false;

    private static final Flag[] PERMANENT_FLAGS = { Flag.DELETED };
    /** The name of the only mailbox available to POP3 accounts */
    private static final String POP3_MAILBOX_NAME = "INBOX";
    private final HashMap<String, Folder> mFolders = new HashMap<String, Folder>();

//    /**
//     * Detected latency, used for usage scaling.
//     * Usage scaling occurs when it is necessary to get information about
//     * messages that could result in large data loads. This value allows
//     * the code that loads this data to decide between using large downloads
//     * (high latency) or multiple round trips (low latency) to accomplish
//     * the same thing.
//     * Default is Integer.MAX_VALUE implying massive latency so that the large
//     * download method is used by default until latency data is collected.
//     */
//    private int mLatencyMs = Integer.MAX_VALUE;
//
//    /**
//     * Detected throughput, used for usage scaling.
//     * Usage scaling occurs when it is necessary to get information about
//     * messages that could result in large data loads. This value allows
//     * the code that loads this data to decide between using large downloads
//     * (high latency) or multiple round trips (low latency) to accomplish
//     * the same thing.
//     * Default is Integer.MAX_VALUE implying massive bandwidth so that the
//     * large download method is used by default until latency data is
//     * collected.
//     */
//    private int mThroughputKbS = Integer.MAX_VALUE;

    /**
     * Static named constructor.
     */
    public static Store newInstance(Account account, Context context) throws MessagingException {
        return new Pop3Store(context, account);
    }

    /**
     * Creates a new store for the given account.
     */
    private Pop3Store(Context context, Account account) throws MessagingException {
        mContext = context;
        mAccount = account;

        HostAuth recvAuth = account.getOrCreateHostAuthRecv(context);
        if (recvAuth == null || !HostAuth.SCHEME_POP3.equalsIgnoreCase(recvAuth.mProtocol)) {
            throw new MessagingException("Unsupported protocol");
        }
        // defaults, which can be changed by security modifiers
        int connectionSecurity = Transport.CONNECTION_SECURITY_NONE;
        int defaultPort = 110;

        // check for security flags and apply changes
        if ((recvAuth.mFlags & HostAuth.FLAG_SSL) != 0) {
            connectionSecurity = Transport.CONNECTION_SECURITY_SSL;
            defaultPort = 995;
        } else if ((recvAuth.mFlags & HostAuth.FLAG_TLS) != 0) {
            connectionSecurity = Transport.CONNECTION_SECURITY_TLS;
        }
        boolean trustCertificates = ((recvAuth.mFlags & HostAuth.FLAG_TRUST_ALL) != 0);

        int port = defaultPort;
        if (recvAuth.mPort != HostAuth.PORT_UNKNOWN) {
            port = recvAuth.mPort;
        }
        mTransport = new MailTransport("POP3");
        mTransport.setHost(recvAuth.mAddress);
        mTransport.setPort(port);
        mTransport.setSecurity(connectionSecurity, trustCertificates);

        String[] userInfoParts = recvAuth.getLogin();
        if (userInfoParts != null) {
            mUsername = userInfoParts[0];
            mPassword = userInfoParts[1];
        }
    }

    /**
     * For testing only.  Injects a different transport.  The transport should already be set
     * up and ready to use.  Do not use for real code.
     * @param testTransport The Transport to inject and use for all future communication.
     */
    /* package */ void setTransport(Transport testTransport) {
        mTransport = testTransport;
    }

    @Override
    public Folder getFolder(String name) {
        Folder folder = mFolders.get(name);
        if (folder == null) {
            folder = new Pop3Folder(name);
            mFolders.put(folder.getName(), folder);
        }
        return folder;
    }

    private final int[] DEFAULT_FOLDERS = {
            Mailbox.TYPE_DRAFTS,
            Mailbox.TYPE_OUTBOX,
            Mailbox.TYPE_SENT,
            Mailbox.TYPE_TRASH
    };

    @Override
    public Folder[] updateFolders() {
        Mailbox mailbox = Mailbox.getMailboxForPath(mContext, mAccount.mId, POP3_MAILBOX_NAME);
        updateMailbox(mailbox, mAccount.mId, POP3_MAILBOX_NAME, '\0', true, Mailbox.TYPE_INBOX);
        // Force the parent key to be "no mailbox" for the mail POP3 mailbox
        mailbox.mParentKey = Mailbox.NO_MAILBOX;
        if (mailbox.isSaved()) {
            mailbox.update(mContext, mailbox.toContentValues());
        } else {
            mailbox.save(mContext);
        }

        // Build default mailboxes as well, in case they're not already made.
        for (int type : DEFAULT_FOLDERS) {
            if (Mailbox.findMailboxOfType(mContext, mAccount.mId, type) == Mailbox.NO_MAILBOX) {
                String name = Controller.getMailboxServerName(mContext, type);
                Mailbox.newSystemMailbox(mAccount.mId, type, name).save(mContext);
            }
        }

        return new Folder[] { getFolder(POP3_MAILBOX_NAME) };
    }

    /**
     * Used by account setup to test if an account's settings are appropriate.  The definition
     * of "checked" here is simply, can you log into the account and does it meet some minimum set
     * of feature requirements?
     *
     * @throws MessagingException if there was some problem with the account
     */
    @Override
    public Bundle checkSettings() throws MessagingException {
        Pop3Folder folder = new Pop3Folder(POP3_MAILBOX_NAME);
        Bundle bundle = null;
        // Close any open or half-open connections - checkSettings should always be "fresh"
        if (mTransport.isOpen()) {
            folder.close(false);
        }
        try {
            folder.open(OpenMode.READ_WRITE);
            bundle = folder.checkSettings();
        } finally {
            folder.close(false);    // false == don't expunge anything
        }
        return bundle;
    }

    class Pop3Folder extends Folder {
        private final HashMap<String, Pop3Message> mUidToMsgMap
                = new HashMap<String, Pop3Message>();
        private final HashMap<Integer, Pop3Message> mMsgNumToMsgMap
                = new HashMap<Integer, Pop3Message>();
        private final HashMap<String, Integer> mUidToMsgNumMap = new HashMap<String, Integer>();
        private final String mName;
        private int mMessageCount;
        private Pop3Capabilities mCapabilities;

        public Pop3Folder(String name) {
            if (name.equalsIgnoreCase(POP3_MAILBOX_NAME)) {
                mName = POP3_MAILBOX_NAME;
            } else {
                mName = name;
            }
        }

        /**
         * Used by account setup to test if an account's settings are appropriate.  Here, we run
         * an additional test to see if UIDL is supported on the server. If it's not we
         * can't service this account.
         *
         * @return Bundle containing validation data (code and, if appropriate, error message)
         * @throws MessagingException if the account is not going to be useable
         */
        public Bundle checkSettings() throws MessagingException {
            Bundle bundle = new Bundle();
            int result = MessagingException.NO_ERROR;
            if (!mCapabilities.uidl) {
                try {
                    UidlParser parser = new UidlParser();
                    executeSimpleCommand("UIDL");
                    // drain the entire output, so additional communications don't get confused.
                    String response;
                    while ((response = mTransport.readLine()) != null) {
                        parser.parseMultiLine(response);
                        if (parser.mEndOfMessage) {
                            break;
                        }
                    }
                } catch (IOException ioe) {
                    mTransport.close();
                    result = MessagingException.IOERROR;
                    bundle.putString(EmailServiceProxy.VALIDATE_BUNDLE_ERROR_MESSAGE,
                            ioe.getMessage());
                }
            }
            bundle.putInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE, result);
            return bundle;
        }

        @Override
        public synchronized void open(OpenMode mode) throws MessagingException {
            if (mTransport.isOpen()) {
                return;
            }

            if (!mName.equalsIgnoreCase(POP3_MAILBOX_NAME)) {
                throw new MessagingException("Folder does not exist");
            }

            try {
                mTransport.open();

                // Eat the banner
                executeSimpleCommand(null);

                mCapabilities = getCapabilities();

                if (mTransport.canTryTlsSecurity()) {
                    if (mCapabilities.stls) {
                        executeSimpleCommand("STLS");
                        mTransport.reopenTls();
                    } else {
                        if (Email.DEBUG) {
                            Log.d(Logging.LOG_TAG, "TLS not supported but required");
                        }
                        throw new MessagingException(MessagingException.TLS_REQUIRED);
                    }
                }

                try {
                    executeSensitiveCommand("USER " + mUsername, "USER /redacted/");
                    executeSensitiveCommand("PASS " + mPassword, "PASS /redacted/");
                } catch (MessagingException me) {
                    if (Email.DEBUG) {
                        Log.d(Logging.LOG_TAG, me.toString());
                    }
                    throw new AuthenticationFailedException(null, me);
                }
            } catch (IOException ioe) {
                mTransport.close();
                if (Email.DEBUG) {
                    Log.d(Logging.LOG_TAG, ioe.toString());
                }
                throw new MessagingException(MessagingException.IOERROR, ioe.toString());
            }

            Exception statException = null;
            try {
                String response = executeSimpleCommand("STAT");
                String[] parts = response.split(" ");
                if (parts.length < 2) {
                    statException = new IOException();
                } else {
                    mMessageCount = Integer.parseInt(parts[1]);
                }
            } catch (IOException ioe) {
                statException = ioe;
            } catch (NumberFormatException nfe) {
                statException = nfe;
            }
            if (statException != null) {
                mTransport.close();
                if (Email.DEBUG) {
                    Log.d(Logging.LOG_TAG, statException.toString());
                }
                throw new MessagingException("POP3 STAT", statException);
            }
            mUidToMsgMap.clear();
            mMsgNumToMsgMap.clear();
            mUidToMsgNumMap.clear();
        }

        @Override
        public OpenMode getMode() {
            return OpenMode.READ_WRITE;
        }

        /**
         * Close the folder (and the transport below it).
         *
         * MUST NOT return any exceptions.
         *
         * @param expunge If true all deleted messages will be expunged (TODO - not implemented)
         */
        @Override
        public void close(boolean expunge) {
            try {
                executeSimpleCommand("QUIT");
            }
            catch (Exception e) {
                // ignore any problems here - just continue closing
            }
            mTransport.close();
        }

        @Override
        public String getName() {
            return mName;
        }

        // POP3 does not folder creation
        @Override
        public boolean canCreate(FolderType type) {
            return false;
        }

        @Override
        public boolean create(FolderType type) {
            return false;
        }

        @Override
        public boolean exists() {
            return mName.equalsIgnoreCase(POP3_MAILBOX_NAME);
        }

        @Override
        public int getMessageCount() {
            return mMessageCount;
        }

        @Override
        public int getUnreadMessageCount() {
            return -1;
        }

        @Override
        public Message getMessage(String uid) throws MessagingException {
            if (mUidToMsgNumMap.size() == 0) {
                try {
                    indexMsgNums(1, mMessageCount);
                } catch (IOException ioe) {
                    mTransport.close();
                    if (Email.DEBUG) {
                        Log.d(Logging.LOG_TAG, "Unable to index during getMessage " + ioe);
                    }
                    throw new MessagingException("getMessages", ioe);
                }
            }
            Pop3Message message = mUidToMsgMap.get(uid);
            return message;
        }

        @Override
        public Message[] getMessages(int start, int end, MessageRetrievalListener listener)
                throws MessagingException {
            if (start < 1 || end < 1 || end < start) {
                throw new MessagingException(String.format("Invalid message set %d %d",
                        start, end));
            }
            try {
                indexMsgNums(start, end);
            } catch (IOException ioe) {
                mTransport.close();
                if (Email.DEBUG) {
                    Log.d(Logging.LOG_TAG, ioe.toString());
                }
                throw new MessagingException("getMessages", ioe);
            }
            ArrayList<Message> messages = new ArrayList<Message>();
            for (int msgNum = start; msgNum <= end; msgNum++) {
                Pop3Message message = mMsgNumToMsgMap.get(msgNum);
                messages.add(message);
                if (listener != null) {
                    listener.messageRetrieved(message);
                }
            }
            return messages.toArray(new Message[messages.size()]);
        }

        /**
         * Ensures that the given message set (from start to end inclusive)
         * has been queried so that uids are available in the local cache.
         * @param start
         * @param end
         * @throws MessagingException
         * @throws IOException
         */
        private void indexMsgNums(int start, int end)
                throws MessagingException, IOException {
            int unindexedMessageCount = 0;
            for (int msgNum = start; msgNum <= end; msgNum++) {
                if (mMsgNumToMsgMap.get(msgNum) == null) {
                    unindexedMessageCount++;
                }
            }
            if (unindexedMessageCount == 0) {
                return;
            }
            UidlParser parser = new UidlParser();
            if (DEBUG_FORCE_SINGLE_LINE_UIDL ||
                    (unindexedMessageCount < 50 && mMessageCount > 5000)) {
                /*
                 * In extreme cases we'll do a UIDL command per message instead of a bulk
                 * download.
                 */
                for (int msgNum = start; msgNum <= end; msgNum++) {
                    Pop3Message message = mMsgNumToMsgMap.get(msgNum);
                    if (message == null) {
                        String response = executeSimpleCommand("UIDL " + msgNum);
                        if (!parser.parseSingleLine(response)) {
                            throw new IOException();
                        }
                        message = new Pop3Message(parser.mUniqueId, this);
                        indexMessage(msgNum, message);
                    }
                }
            } else {
                String response = executeSimpleCommand("UIDL");
                while ((response = mTransport.readLine()) != null) {
                    if (!parser.parseMultiLine(response)) {
                        throw new IOException();
                    }
                    if (parser.mEndOfMessage) {
                        break;
                    }
                    int msgNum = parser.mMessageNumber;
                    if (msgNum >= start && msgNum <= end) {
                        Pop3Message message = mMsgNumToMsgMap.get(msgNum);
                        if (message == null) {
                            message = new Pop3Message(parser.mUniqueId, this);
                            indexMessage(msgNum, message);
                        }
                    }
                }
            }
        }

        private void indexUids(ArrayList<String> uids)
                throws MessagingException, IOException {
            HashSet<String> unindexedUids = new HashSet<String>();
            for (String uid : uids) {
                if (mUidToMsgMap.get(uid) == null) {
                    unindexedUids.add(uid);
                }
            }
            if (unindexedUids.size() == 0) {
                return;
            }
            /*
             * If we are missing uids in the cache the only sure way to
             * get them is to do a full UIDL list. A possible optimization
             * would be trying UIDL for the latest X messages and praying.
             */
            UidlParser parser = new UidlParser();
            String response = executeSimpleCommand("UIDL");
            while ((response = mTransport.readLine()) != null) {
                parser.parseMultiLine(response);
                if (parser.mEndOfMessage) {
                    break;
                }
                if (unindexedUids.contains(parser.mUniqueId)) {
                    Pop3Message message = mUidToMsgMap.get(parser.mUniqueId);
                    if (message == null) {
                        message = new Pop3Message(parser.mUniqueId, this);
                    }
                    indexMessage(parser.mMessageNumber, message);
                }
            }
        }

        /**
         * Simple parser class for UIDL messages.
         *
         * <p>NOTE:  In variance with RFC 1939, we allow multiple whitespace between the
         * message-number and unique-id fields.  This provides greater compatibility with some
         * non-compliant POP3 servers, e.g. mail.comcast.net.
         */
        /* package */ class UidlParser {

            /**
             * Caller can read back message-number from this field
             */
            public int mMessageNumber;
            /**
             * Caller can read back unique-id from this field
             */
            public String mUniqueId;
            /**
             * True if the response was "end-of-message"
             */
            public boolean mEndOfMessage;
            /**
             * True if an error was reported
             */
            public boolean mErr;

            /**
             * Construct & Initialize
             */
            public UidlParser() {
                mErr = true;
            }

            /**
             * Parse a single-line response.  This is returned from a command of the form
             * "UIDL msg-num" and will be formatted as: "+OK msg-num unique-id" or
             * "-ERR diagnostic text"
             *
             * @param response The string returned from the server
             * @return true if the string parsed as expected (e.g. no syntax problems)
             */
            public boolean parseSingleLine(String response) {
                mErr = false;
                if (response == null || response.length() == 0) {
                    return false;
                }
                char first = response.charAt(0);
                if (first == '+') {
                    String[] uidParts = response.split(" +");
                    if (uidParts.length >= 3) {
                        try {
                            mMessageNumber = Integer.parseInt(uidParts[1]);
                        } catch (NumberFormatException nfe) {
                            return false;
                        }
                        mUniqueId = uidParts[2];
                        mEndOfMessage = true;
                        return true;
                    }
                } else if (first == '-') {
                    mErr = true;
                    return true;
                }
                return false;
            }

            /**
             * Parse a multi-line response.  This is returned from a command of the form
             * "UIDL" and will be formatted as: "." or "msg-num unique-id".
             *
             * @param response The string returned from the server
             * @return true if the string parsed as expected (e.g. no syntax problems)
             */
            public boolean parseMultiLine(String response) {
                mErr = false;
                if (response == null || response.length() == 0) {
                    return false;
                }
                char first = response.charAt(0);
                if (first == '.') {
                    mEndOfMessage = true;
                    return true;
                } else {
                    String[] uidParts = response.split(" +");
                    if (uidParts.length >= 2) {
                        try {
                            mMessageNumber = Integer.parseInt(uidParts[0]);
                        } catch (NumberFormatException nfe) {
                            return false;
                        }
                        mUniqueId = uidParts[1];
                        mEndOfMessage = false;
                        return true;
                    }
                }
                return false;
            }
        }

        private void indexMessage(int msgNum, Pop3Message message) {
            mMsgNumToMsgMap.put(msgNum, message);
            mUidToMsgMap.put(message.getUid(), message);
            mUidToMsgNumMap.put(message.getUid(), msgNum);
        }

        @Override
        public Message[] getMessages(String[] uids, MessageRetrievalListener listener) {
            throw new UnsupportedOperationException(
                    "Pop3Folder.getMessage(MessageRetrievalListener)");
        }

        /**
         * Fetch the items contained in the FetchProfile into the given set of
         * Messages in as efficient a manner as possible.
         * @param messages
         * @param fp
         * @throws MessagingException
         */
        @Override
        public void fetch(Message[] messages, FetchProfile fp, MessageRetrievalListener listener)
                throws MessagingException {
            if (messages == null || messages.length == 0) {
                return;
            }
            ArrayList<String> uids = new ArrayList<String>();
            for (Message message : messages) {
                uids.add(message.getUid());
            }
            try {
                indexUids(uids);
                if (fp.contains(FetchProfile.Item.ENVELOPE)) {
                    // Note: We never pass the listener for the ENVELOPE call, because we're going
                    // to be calling the listener below in the per-message loop.
                    fetchEnvelope(messages, null);
                }
            } catch (IOException ioe) {
                mTransport.close();
                if (Email.DEBUG) {
                    Log.d(Logging.LOG_TAG, ioe.toString());
                }
                throw new MessagingException("fetch", ioe);
            }
            for (int i = 0, count = messages.length; i < count; i++) {
                Message message = messages[i];
                if (!(message instanceof Pop3Message)) {
                    throw new MessagingException("Pop3Store.fetch called with non-Pop3 Message");
                }
                Pop3Message pop3Message = (Pop3Message)message;
                try {
                    if (fp.contains(FetchProfile.Item.BODY)) {
                        fetchBody(pop3Message, -1);
                    }
                    else if (fp.contains(FetchProfile.Item.BODY_SANE)) {
                        /*
                         * To convert the suggested download size we take the size
                         * divided by the maximum line size (76).
                         */
                        fetchBody(pop3Message,
                                FETCH_BODY_SANE_SUGGESTED_SIZE / 76);
                    }
                    else if (fp.contains(FetchProfile.Item.STRUCTURE)) {
                        /*
                         * If the user is requesting STRUCTURE we are required to set the body
                         * to null since we do not support the function.
                         */
                        pop3Message.setBody(null);
                    }
                    if (listener != null) {
                        listener.messageRetrieved(message);
                    }
                } catch (IOException ioe) {
                    mTransport.close();
                    if (Email.DEBUG) {
                        Log.d(Logging.LOG_TAG, ioe.toString());
                    }
                    throw new MessagingException("Unable to fetch message", ioe);
                }
            }
        }

        private void fetchEnvelope(Message[] messages,
                MessageRetrievalListener listener)  throws IOException, MessagingException {
            int unsizedMessages = 0;
            for (Message message : messages) {
                if (message.getSize() == -1) {
                    unsizedMessages++;
                }
            }
            if (unsizedMessages == 0) {
                return;
            }
            if (unsizedMessages < 50 && mMessageCount > 5000) {
                /*
                 * In extreme cases we'll do a command per message instead of a bulk request
                 * to hopefully save some time and bandwidth.
                 */
                for (int i = 0, count = messages.length; i < count; i++) {
                    Message message = messages[i];
                    if (!(message instanceof Pop3Message)) {
                        throw new MessagingException(
                                "Pop3Store.fetch called with non-Pop3 Message");
                    }
                    Pop3Message pop3Message = (Pop3Message)message;
                    String response = executeSimpleCommand(String.format("LIST %d",
                            mUidToMsgNumMap.get(pop3Message.getUid())));
                    try {
                        String[] listParts = response.split(" ");
                        int msgNum = Integer.parseInt(listParts[1]);
                        int msgSize = Integer.parseInt(listParts[2]);
                        pop3Message.setSize(msgSize);
                    } catch (NumberFormatException nfe) {
                        throw new IOException();
                    }
                    if (listener != null) {
                        listener.messageRetrieved(pop3Message);
                    }
                }
            } else {
                HashSet<String> msgUidIndex = new HashSet<String>();
                for (Message message : messages) {
                    msgUidIndex.add(message.getUid());
                }
                String response = executeSimpleCommand("LIST");
                while ((response = mTransport.readLine()) != null) {
                    if (response.equals(".")) {
                        break;
                    }
                    Pop3Message pop3Message = null;
                    int msgSize = 0;
                    try {
                        String[] listParts = response.split(" ");
                        int msgNum = Integer.parseInt(listParts[0]);
                        msgSize = Integer.parseInt(listParts[1]);
                        pop3Message = mMsgNumToMsgMap.get(msgNum);
                    } catch (NumberFormatException nfe) {
                        throw new IOException();
                    }
                    if (pop3Message != null && msgUidIndex.contains(pop3Message.getUid())) {
                        pop3Message.setSize(msgSize);
                        if (listener != null) {
                            listener.messageRetrieved(pop3Message);
                        }
                    }
                }
            }
        }

        /**
         * Fetches the body of the given message, limiting the stored data
         * to the specified number of lines. If lines is -1 the entire message
         * is fetched. This is implemented with RETR for lines = -1 or TOP
         * for any other value. If the server does not support TOP it is
         * emulated with RETR and extra lines are thrown away.
         *
         * Note:  Some servers (e.g. live.com) don't support CAPA, but turn out to
         * support TOP after all.  For better performance on these servers, we'll always
         * probe TOP, and fall back to RETR when it's truly unsupported.
         *
         * @param message
         * @param lines
         */
        private void fetchBody(Pop3Message message, int lines)
                throws IOException, MessagingException {
            String response = null;
            int messageId = mUidToMsgNumMap.get(message.getUid());
            if (lines == -1) {
                // Fetch entire message
                response = executeSimpleCommand(String.format("RETR %d", messageId));
            } else {
                // Fetch partial message.  Try "TOP", and fall back to slower "RETR" if necessary
                try {
                    response = executeSimpleCommand(String.format("TOP %d %d", messageId,  lines));
                } catch (MessagingException me) {
                    response = executeSimpleCommand(String.format("RETR %d", messageId));
                }
            }
            if (response != null)  {
                try {
                    InputStream in = mTransport.getInputStream();
                    if (DEBUG_LOG_RAW_STREAM && Email.DEBUG) {
                        in = new LoggingInputStream(in);
                    }
                    message.parse(new Pop3ResponseInputStream(in));
                }
                catch (MessagingException me) {
                    /*
                     * If we're only downloading headers it's possible
                     * we'll get a broken MIME message which we're not
                     * real worried about. If we've downloaded the body
                     * and can't parse it we need to let the user know.
                     */
                    if (lines == -1) {
                        throw me;
                    }
                }
            }
        }

        @Override
        public Flag[] getPermanentFlags() {
            return PERMANENT_FLAGS;
        }

        @Override
        public void appendMessages(Message[] messages) {
        }

        @Override
        public void delete(boolean recurse) {
        }

        @Override
        public Message[] expunge() {
            return null;
        }

        @Override
        public void setFlags(Message[] messages, Flag[] flags, boolean value)
                throws MessagingException {
            if (!value || !Utility.arrayContains(flags, Flag.DELETED)) {
                /*
                 * The only flagging we support is setting the Deleted flag.
                 */
                return;
            }
            try {
                for (Message message : messages) {
                    executeSimpleCommand(String.format("DELE %s",
                            mUidToMsgNumMap.get(message.getUid())));
                }
            }
            catch (IOException ioe) {
                mTransport.close();
                if (Email.DEBUG) {
                    Log.d(Logging.LOG_TAG, ioe.toString());
                }
                throw new MessagingException("setFlags()", ioe);
            }
        }

        @Override
        public void copyMessages(Message[] msgs, Folder folder, MessageUpdateCallbacks callbacks) {
            throw new UnsupportedOperationException("copyMessages is not supported in POP3");
        }

//        private boolean isRoundTripModeSuggested() {
//            long roundTripMethodMs =
//                (uncachedMessageCount * 2 * mLatencyMs);
//            long bulkMethodMs =
//                    (mMessageCount * 58) / (mThroughputKbS * 1024 / 8) * 1000;
//        }

        private Pop3Capabilities getCapabilities() throws IOException {
            Pop3Capabilities capabilities = new Pop3Capabilities();
            try {
                String response = executeSimpleCommand("CAPA");
                while ((response = mTransport.readLine()) != null) {
                    if (response.equals(".")) {
                        break;
                    }
                    if (response.equalsIgnoreCase("STLS")){
                        capabilities.stls = true;
                    }
                    else if (response.equalsIgnoreCase("UIDL")) {
                        capabilities.uidl = true;
                    }
                    else if (response.equalsIgnoreCase("PIPELINING")) {
                        capabilities.pipelining = true;
                    }
                    else if (response.equalsIgnoreCase("USER")) {
                        capabilities.user = true;
                    }
                    else if (response.equalsIgnoreCase("TOP")) {
                        capabilities.top = true;
                    }
                }
            }
            catch (MessagingException me) {
                /*
                 * The server may not support the CAPA command, so we just eat this Exception
                 * and allow the empty capabilities object to be returned.
                 */
            }
            return capabilities;
        }

        /**
         * Send a single command and wait for a single line response.  Reopens the connection,
         * if it is closed.  Leaves the connection open.
         *
         * @param command The command string to send to the server.
         * @return Returns the response string from the server.
         */
        private String executeSimpleCommand(String command) throws IOException, MessagingException {
            return executeSensitiveCommand(command, null);
        }

        /**
         * Send a single command and wait for a single line response.  Reopens the connection,
         * if it is closed.  Leaves the connection open.
         *
         * @param command The command string to send to the server.
         * @param sensitiveReplacement If the command includes sensitive data (e.g. authentication)
         * please pass a replacement string here (for logging).
         * @return Returns the response string from the server.
         */
        private String executeSensitiveCommand(String command, String sensitiveReplacement)
                throws IOException, MessagingException {
            open(OpenMode.READ_WRITE);

            if (command != null) {
                mTransport.writeLine(command, sensitiveReplacement);
            }

            String response = mTransport.readLine();

            if (response.length() > 1 && response.charAt(0) == '-') {
                throw new MessagingException(response);
            }

            return response;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Pop3Folder) {
                return ((Pop3Folder) o).mName.equals(mName);
            }
            return super.equals(o);
        }

        @Override
        @VisibleForTesting
        public boolean isOpen() {
            return mTransport.isOpen();
        }

        @Override
        public Message createMessage(String uid) {
            return new Pop3Message(uid, this);
        }

        @Override
        public Message[] getMessages(SearchParams params, MessageRetrievalListener listener) {
            return null;
        }
    }

    public static class Pop3Message extends MimeMessage {
        public Pop3Message(String uid, Pop3Folder folder) {
            mUid = uid;
            mFolder = folder;
            mSize = -1;
        }

        public void setSize(int size) {
            mSize = size;
        }

        @Override
        public void parse(InputStream in) throws IOException, MessagingException {
            super.parse(in);
        }

        @Override
        public void setFlag(Flag flag, boolean set) throws MessagingException {
            super.setFlag(flag, set);
            mFolder.setFlags(new Message[] { this }, new Flag[] { flag }, set);
        }
    }

    /**
     * POP3 Capabilities as defined in RFC 2449.  This is not a complete list of CAPA
     * responses - just those that we use in this client.
     */
    class Pop3Capabilities {
        /** The STLS (start TLS) command is supported */
        public boolean stls;
        /** the TOP command (retrieve a partial message) is supported */
        public boolean top;
        /** USER and PASS login/auth commands are supported */
        public boolean user;
        /** the optional UIDL command is supported (unused) */
        public boolean uidl;
        /** the server is capable of accepting multiple commands at a time (unused) */
        public boolean pipelining;

        @Override
        public String toString() {
            return String.format("STLS %b, TOP %b, USER %b, UIDL %b, PIPELINING %b",
                    stls,
                    top,
                    user,
                    uidl,
                    pipelining);
        }
    }

    // TODO figure out what is special about this and merge it into MailTransport
    class Pop3ResponseInputStream extends InputStream {
        private final InputStream mIn;
        private boolean mStartOfLine = true;
        private boolean mFinished;

        public Pop3ResponseInputStream(InputStream in) {
            mIn = in;
        }

        @Override
        public int read() throws IOException {
            if (mFinished) {
                return -1;
            }
            int d = mIn.read();
            if (mStartOfLine && d == '.') {
                d = mIn.read();
                if (d == '\r') {
                    mFinished = true;
                    mIn.read();
                    return -1;
                }
            }

            mStartOfLine = (d == '\n');

            return d;
        }
    }
}
