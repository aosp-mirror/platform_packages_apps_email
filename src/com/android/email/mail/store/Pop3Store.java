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

import com.android.email.mail.Store;
import com.android.email.mail.transport.MailTransport;
import com.android.email2.ui.MailActivityEmail;
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
import com.android.mail.utils.LogUtils;
import com.google.common.annotations.VisibleForTesting;

import org.apache.james.mime4j.EOLConvertingInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class Pop3Store extends Store {
    // All flags defining debug or development code settings must be FALSE
    // when code is checked in or released.
    private static boolean DEBUG_FORCE_SINGLE_LINE_UIDL = false;
    private static boolean DEBUG_LOG_RAW_STREAM = false;

    private static final Flag[] PERMANENT_FLAGS = { Flag.DELETED };
    /** The name of the only mailbox available to POP3 accounts */
    private static final String POP3_MAILBOX_NAME = "INBOX";
    private final HashMap<String, Folder> mFolders = new HashMap<String, Folder>();
    private final Message[] mOneMessage = new Message[1];

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
        mTransport = new MailTransport(context, "POP3", recvAuth);
        String[] userInfoParts = recvAuth.getLogin();
        mUsername = userInfoParts[0];
        mPassword = userInfoParts[1];
    }

    /**
     * For testing only.  Injects a different transport.  The transport should already be set
     * up and ready to use.  Do not use for real code.
     * @param testTransport The Transport to inject and use for all future communication.
     */
    /* package */ void setTransport(MailTransport testTransport) {
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

    @Override
    public Folder[] updateFolders() {
        Mailbox mailbox = Mailbox.restoreMailboxOfType(mContext, mAccount.mId, Mailbox.TYPE_INBOX);
        if (mailbox == null) {
            mailbox = Mailbox.newSystemMailbox(mContext, mAccount.mId, Mailbox.TYPE_INBOX);
        }
        if (mailbox.isSaved()) {
            mailbox.update(mContext, mailbox.toContentValues());
        } else {
            mailbox.save(mContext);
        }
        return new Folder[] { getFolder(mailbox.mServerId) };
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

    public class Pop3Folder extends Folder {
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
            try {
                UidlParser parser = new UidlParser();
                executeSimpleCommand("UIDL");
                // drain the entire output, so additional communications don't get confused.
                String response;
                while ((response = mTransport.readLine(false)) != null) {
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
                        if (MailActivityEmail.DEBUG) {
                            LogUtils.d(Logging.LOG_TAG, "TLS not supported but required");
                        }
                        throw new MessagingException(MessagingException.TLS_REQUIRED);
                    }
                }

                try {
                    executeSensitiveCommand("USER " + mUsername, "USER /redacted/");
                    executeSensitiveCommand("PASS " + mPassword, "PASS /redacted/");
                } catch (MessagingException me) {
                    if (MailActivityEmail.DEBUG) {
                        LogUtils.d(Logging.LOG_TAG, me.toString());
                    }
                    throw new AuthenticationFailedException(null, me);
                }
            } catch (IOException ioe) {
                mTransport.close();
                if (MailActivityEmail.DEBUG) {
                    LogUtils.d(Logging.LOG_TAG, ioe.toString());
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
            } catch (MessagingException me) {
                statException = me;
            } catch (IOException ioe) {
                statException = ioe;
            } catch (NumberFormatException nfe) {
                statException = nfe;
            }
            if (statException != null) {
                mTransport.close();
                if (MailActivityEmail.DEBUG) {
                    LogUtils.d(Logging.LOG_TAG, statException.toString());
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
                    if (MailActivityEmail.DEBUG) {
                        LogUtils.d(Logging.LOG_TAG, "Unable to index during getMessage " + ioe);
                    }
                    throw new MessagingException("getMessages", ioe);
                }
            }
            Pop3Message message = mUidToMsgMap.get(uid);
            return message;
        }

        @Override
        public Pop3Message[] getMessages(int start, int end, MessageRetrievalListener listener)
                throws MessagingException {
            return null;
        }

        @Override
        public Pop3Message[] getMessages(long startDate, long endDate,
                MessageRetrievalListener listener) throws MessagingException {
            return null;
        }

        public Pop3Message[] getMessages(int end, final int limit)
                throws MessagingException {
            try {
                indexMsgNums(1, end);
            } catch (IOException ioe) {
                mTransport.close();
                if (MailActivityEmail.DEBUG) {
                    LogUtils.d(Logging.LOG_TAG, ioe.toString());
                }
                throw new MessagingException("getMessages", ioe);
            }
            ArrayList<Message> messages = new ArrayList<Message>();
            for (int msgNum = end; msgNum > 0 && (messages.size() < limit); msgNum--) {
                Pop3Message message = mMsgNumToMsgMap.get(msgNum);
                if (message != null) {
                    messages.add(message);
                }
            }
            return messages.toArray(new Pop3Message[messages.size()]);
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
            if (!mMsgNumToMsgMap.isEmpty()) {
                return;
            }
            UidlParser parser = new UidlParser();
            if (DEBUG_FORCE_SINGLE_LINE_UIDL || (mMessageCount > 5000)) {
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
                while ((response = mTransport.readLine(false)) != null) {
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
            throw new UnsupportedOperationException(
                    "Pop3Folder.fetch(Message[], FetchProfile, MessageRetrievalListener)");
        }

        /**
         * Fetches the body of the given message, limiting the stored data
         * to the specified number of lines. If lines is -1 the entire message
         * is fetched. This is implemented with RETR for lines = -1 or TOP
         * for any other value. If the server does not support TOP it is
         * emulated with RETR and extra lines are thrown away.
         *
         * @param message
         * @param lines
         * @param callback optional callback that reports progress of the fetch
         */
        public void fetchBody(Pop3Message message, int lines,
                EOLConvertingInputStream.Callback callback) throws IOException, MessagingException {
            String response = null;
            int messageId = mUidToMsgNumMap.get(message.getUid());
            if (lines == -1) {
                // Fetch entire message
                response = executeSimpleCommand(String.format(Locale.US, "RETR %d", messageId));
            } else {
                // Fetch partial message.  Try "TOP", and fall back to slower "RETR" if necessary
                try {
                    response = executeSimpleCommand(
                            String.format(Locale.US, "TOP %d %d", messageId,  lines));
                } catch (MessagingException me) {
                    try {
                        response = executeSimpleCommand(
                                String.format(Locale.US, "RETR %d", messageId));
                    } catch (MessagingException e) {
                        LogUtils.w(Logging.LOG_TAG, "Can't read message " + messageId);
                    }
                }
            }
            if (response != null)  {
                try {
                    int ok = response.indexOf("OK");
                    if (ok > 0) {
                        try {
                            int start = ok + 3;
                            if (start > response.length()) {
                                // No length was supplied, this is a protocol error.
                                LogUtils.e(Logging.LOG_TAG, "No body length supplied");
                                message.setSize(0);
                            } else {
                                int end = response.indexOf(" ", start);
                                final String intString;
                                if (end > 0) {
                                    intString = response.substring(start, end);
                                } else {
                                    intString = response.substring(start);
                                }
                                message.setSize(Integer.parseInt(intString));
                            }
                        } catch (NumberFormatException e) {
                            // We tried
                        }
                    }
                    InputStream in = mTransport.getInputStream();
                    if (DEBUG_LOG_RAW_STREAM && MailActivityEmail.DEBUG) {
                        in = new LoggingInputStream(in);
                    }
                    message.parse(new Pop3ResponseInputStream(in), callback);
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
        public void appendMessage(Context context, Message message, boolean noTimeout) {
        }

        @Override
        public void delete(boolean recurse) {
        }

        @Override
        public Message[] expunge() {
            return null;
        }

        public void deleteMessage(Message message) throws MessagingException {
            mOneMessage[0] = message;
            setFlags(mOneMessage, PERMANENT_FLAGS, true);
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
                    try {
                        String uid = message.getUid();
                        int msgNum = mUidToMsgNumMap.get(uid);
                        executeSimpleCommand(String.format(Locale.US, "DELE %s", msgNum));
                        // Remove from the maps
                        mMsgNumToMsgMap.remove(msgNum);
                        mUidToMsgNumMap.remove(uid);
                    } catch (MessagingException e) {
                        // A failed deletion isn't a problem
                    }
                }
            }
            catch (IOException ioe) {
                mTransport.close();
                if (MailActivityEmail.DEBUG) {
                    LogUtils.d(Logging.LOG_TAG, ioe.toString());
                }
                throw new MessagingException("setFlags()", ioe);
            }
        }

        @Override
        public void copyMessages(Message[] msgs, Folder folder, MessageUpdateCallbacks callbacks) {
            throw new UnsupportedOperationException("copyMessages is not supported in POP3");
        }

        private Pop3Capabilities getCapabilities() throws IOException {
            Pop3Capabilities capabilities = new Pop3Capabilities();
            try {
                String response = executeSimpleCommand("CAPA");
                while ((response = mTransport.readLine(true)) != null) {
                    if (response.equals(".")) {
                        break;
                    } else if (response.equalsIgnoreCase("STLS")){
                        capabilities.stls = true;
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

            String response = mTransport.readLine(true);

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

        @Override
        public String toString() {
            return String.format("STLS %b", stls);
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
