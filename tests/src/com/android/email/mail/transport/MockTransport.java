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

package com.android.email.mail.transport;

import com.android.email.mail.Transport;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Pattern;

import junit.framework.Assert;

/**
 * This is a mock Transport that is used to test protocols that use MailTransport.
 */
public class MockTransport implements Transport {

    // All flags defining debug or development code settings must be FALSE
    // when code is checked in or released.
    private static boolean DEBUG_LOG_STREAMS = true;

    private static String LOG_TAG = "MockTransport";

    private static final String SPECIAL_RESPONSE_IOEXCEPTION = "!!!IOEXCEPTION!!!";

    private boolean mTlsStarted = false;

    private boolean mOpen;
    private boolean mInputOpen;
    private int mConnectionSecurity;
    private boolean mTrustCertificates;
    private String mHost;
    private InetAddress mLocalAddress;

    private ArrayList<String> mQueuedInput = new ArrayList<String>();

    private static class Transaction {
        public static final int ACTION_INJECT_TEXT = 0;
        public static final int ACTION_CLIENT_CLOSE = 1;
        public static final int ACTION_IO_EXCEPTION = 2;
        public static final int ACTION_START_TLS = 3;

        int mAction;
        String mPattern;
        String[] mResponses;

        Transaction(String pattern, String[] responses) {
            mAction = ACTION_INJECT_TEXT;
            mPattern = pattern;
            mResponses = responses;
        }

        Transaction(int otherType) {
            mAction = otherType;
            mPattern = null;
            mResponses = null;
        }

        @Override
        public String toString() {
            switch (mAction) {
                case ACTION_INJECT_TEXT:
                    return mPattern + ": " + Arrays.toString(mResponses);
                case ACTION_CLIENT_CLOSE:
                    return "Expect the client to close";
                case ACTION_IO_EXCEPTION:
                    return "Expect IOException";
                case ACTION_START_TLS:
                    return "Expect StartTls";
                default:
                    return "(Hmm.  Unknown action.)";
            }
        }
    }

    private ArrayList<Transaction> mPairs = new ArrayList<Transaction>();

    /**
     * Give the mock a pattern to wait for.  No response will be sent.
     * @param pattern Java RegEx to wait for
     */
    public void expect(String pattern) {
        expect(pattern, (String[])null);
    }

    /**
     * Give the mock a pattern to wait for and a response to send back.
     * @param pattern Java RegEx to wait for
     * @param response String to reply with, or null to acccept string but not respond to it
     */
    public void expect(String pattern, String response) {
        expect(pattern, (response == null) ? null : new String[] { response });
    }

    /**
     * Give the mock a pattern to wait for and a multi-line response to send back.
     * @param pattern Java RegEx to wait for
     * @param responses Strings to reply with
     */
    public void expect(String pattern, String[] responses) {
        Transaction pair = new Transaction(pattern, responses);
        mPairs.add(pair);
    }

    /**
     * Same as {@link #expect(String, String[])}, but the first arg is taken literally, rather than
     * as a regexp.
     */
    public void expectLiterally(String literal, String[] responses) {
        expect("^" + Pattern.quote(literal) + "$", responses);
    }

    /**
     * Tell the Mock Transport that we expect it to be closed.  This will preserve
     * the remaining entries in the expect() stream and allow us to "ride over" the close (which
     * would normally reset everything).
     */
    public void expectClose() {
        mPairs.add(new Transaction(Transaction.ACTION_CLIENT_CLOSE));
    }

    public void expectIOException() {
        mPairs.add(new Transaction(Transaction.ACTION_IO_EXCEPTION));
    }

    public void expectStartTls() {
        mPairs.add(new Transaction(Transaction.ACTION_START_TLS));
    }

    private void sendResponse(Transaction pair) {
        switch (pair.mAction) {
            case Transaction.ACTION_INJECT_TEXT:
                for (String s : pair.mResponses) {
                    mQueuedInput.add(s);
                }
                break;
            case Transaction.ACTION_IO_EXCEPTION:
                mQueuedInput.add(SPECIAL_RESPONSE_IOEXCEPTION);
                break;
            default:
                Assert.fail("Invalid action for sendResponse: " + pair.mAction);
        }
    }

    @Override
    public boolean canTrySslSecurity() {
        return (mConnectionSecurity == CONNECTION_SECURITY_SSL);
    }

    @Override
    public boolean canTryTlsSecurity() {
        return (mConnectionSecurity == Transport.CONNECTION_SECURITY_TLS);
    }

    @Override
    public boolean canTrustAllCertificates() {
        return mTrustCertificates;
    }

    /**
     * Check that TLS was started
     */
    public boolean isTlsStarted() {
        return mTlsStarted;
    }

    /**
     * This simulates a condition where the server has closed its side, causing
     * reads to fail.
     */
    public void closeInputStream() {
        mInputOpen = false;
    }

    @Override
    public void close() {
        mOpen = false;
        mInputOpen = false;
        // unless it was expected as part of a test, reset the stream
        if (mPairs.size() > 0) {
            Transaction expect = mPairs.remove(0);
            if (expect.mAction == Transaction.ACTION_CLIENT_CLOSE) {
                return;
            }
        }
        mQueuedInput.clear();
        mPairs.clear();
    }

    @Override
    public void setHost(String host) {
        mHost = host;
    }

    @Override
    public String getHost() {
        return mHost;
    }

    public void setMockLocalAddress(InetAddress address) {
        mLocalAddress = address;
    }

    @Override
    public InputStream getInputStream() {
        SmtpSenderUnitTests.assertTrue(mOpen);
        return new MockInputStream();
    }

    /**
     * This normally serves as a pseudo-clone, for use by Imap.  For the purposes of unit testing,
     * until we need something more complex, we'll just return the actual MockTransport.  Then we
     * don't have to worry about dealing with test metadata like the expects list or socket state.
     */
    @Override
    public Transport clone() {
         return this;
    }

    @Override
    public OutputStream getOutputStream() {
        Assert.assertTrue(mOpen);
        return new MockOutputStream();
    }

    @Override
    public void setPort(int port) {
        SmtpSenderUnitTests.fail("setPort() not implemented");
    }

    @Override
    public int getPort() {
        SmtpSenderUnitTests.fail("getPort() not implemented");
        return 0;
    }

    @Override
    public int getSecurity() {
        return mConnectionSecurity;
    }

    @Override
    public boolean isOpen() {
        return mOpen;
    }

    @Override
    public void open() /* throws MessagingException, CertificateValidationException */ {
        mOpen = true;
        mInputOpen = true;
    }

    /**
     * This returns one string (if available) to the caller.  Usually this simply pulls strings
     * from the mQueuedInput list, but if the list is empty, we also peek the expect list.  This
     * supports banners, multi-line responses, and any other cases where we respond without
     * a specific expect pattern.
     *
     * If no response text is available, we assert (failing our test) as an underflow.
     *
     * Logs the read text if DEBUG_LOG_STREAMS is true.
     */
    @Override
    public String readLine() throws IOException {
        SmtpSenderUnitTests.assertTrue(mOpen);
        if (!mInputOpen) {
            throw new IOException("Reading from MockTransport with closed input");
        }
        // if there's nothing to read, see if we can find a null-pattern response
        if ((mQueuedInput.size() == 0) && (mPairs.size() > 0)) {
            Transaction pair = mPairs.get(0);
            if (pair.mPattern == null) {
                mPairs.remove(0);
                sendResponse(pair);
            }
        }
        if (mQueuedInput.size() == 0) {
            // MailTransport returns "" at EOS.
            Log.w(LOG_TAG, "Underflow reading from MockTransport");
            return "";
        }
        String line = mQueuedInput.remove(0);
        if (DEBUG_LOG_STREAMS) {
            Log.d(LOG_TAG, "<<< " + line);
        }
        if (SPECIAL_RESPONSE_IOEXCEPTION.equals(line)) {
            throw new IOException("Expected IOException.");
        }
        return line;
    }

    @Override
    public void reopenTls() /* throws MessagingException */ {
        SmtpSenderUnitTests.assertTrue(mOpen);
        Transaction expect = mPairs.remove(0);
        SmtpSenderUnitTests.assertTrue(expect.mAction == Transaction.ACTION_START_TLS);
        mTlsStarted = true;
    }

    @Override
    public void setSecurity(int connectionSecurity, boolean trustAllCertificates) {
        mConnectionSecurity = connectionSecurity;
        mTrustCertificates = trustAllCertificates;
    }

    @Override
    public void setSoTimeout(int timeoutMilliseconds) /* throws SocketException */ {
    }

    /**
     * Accepts a single string (command or text) that was written by the code under test.
     * Because we are essentially mocking a server, we check to see if this string was expected.
     * If the string was expected, we push the corresponding responses into the mQueuedInput
     * list, for subsequent calls to readLine().  If the string does not match, we assert
     * the mismatch.  If no string was expected, we assert it as an overflow.
     *
     * Logs the written text if DEBUG_LOG_STREAMS is true.
     */
    @Override
    public void writeLine(String s, String sensitiveReplacement) throws IOException {
        if (DEBUG_LOG_STREAMS) {
            Log.d(LOG_TAG, ">>> " + s);
        }
        SmtpSenderUnitTests.assertTrue(mOpen);
        SmtpSenderUnitTests.assertTrue("Overflow writing to MockTransport: Getting " + s,
                0 != mPairs.size());
        Transaction pair = mPairs.remove(0);
        if (pair.mAction == Transaction.ACTION_IO_EXCEPTION) {
            throw new IOException("Expected IOException.");
        }
        SmtpSenderUnitTests.assertTrue("Unexpected string written to MockTransport: Actual=" + s
                + "  Expected=" + pair.mPattern,
                pair.mPattern != null && s.matches(pair.mPattern));
        if (pair.mResponses != null) {
            sendResponse(pair);
        }
    }

    /**
     * This is an InputStream that satisfies the needs of getInputStream()
     */
    private class MockInputStream extends InputStream {

        private byte[] mNextLine = null;
        private int mNextIndex = 0;

        /**
         * Reads from the same input buffer as readLine()
         */
        @Override
        public int read() throws IOException {
            if (!mInputOpen) {
                throw new IOException();
            }

            if (mNextLine != null && mNextIndex < mNextLine.length) {
                return mNextLine[mNextIndex++];
            }

            // previous line was exhausted so try to get another one
            String next = readLine();
            if (next == null) {
                throw new IOException("Reading from MockTransport with closed input");
            }
            mNextLine = (next + "\r\n").getBytes();
            mNextIndex = 0;

            if (mNextLine != null && mNextIndex < mNextLine.length) {
                return mNextLine[mNextIndex++];
            }

            // no joy - throw an exception
            throw new IOException();
        }
    }

    /**
     * This is an OutputStream that satisfies the needs of getOutputStream()
     */
    private class MockOutputStream extends OutputStream {

        private StringBuilder sb = new StringBuilder();

        @Override
        public void write(int oneByte) throws IOException {
            // CR or CRLF will immediately dump previous line (w/o CRLF)
            if (oneByte == '\r') {
                writeLine(sb.toString(), null);
                sb = new StringBuilder();
            } else if (oneByte == '\n') {
                // swallow it
            } else {
                sb.append((char)oneByte);
            }
        }
    }

    @Override
    public InetAddress getLocalAddress() {
        if (isOpen()) {
            return mLocalAddress;
        } else {
            return null;
        }
    }
}
