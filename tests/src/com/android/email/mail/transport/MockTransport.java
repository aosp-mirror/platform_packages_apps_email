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
import java.net.SocketException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * This is a mock Transport that is used to test protocols that use MailTransport.
 */
public class MockTransport implements Transport {

    // All flags defining debug or development code settings must be FALSE
    // when code is checked in or released.
    private static boolean DEBUG_LOG_STREAMS = true;
    
    private static String LOG_TAG = "MockTransport";

    private boolean mSslAllowed = false;
    private boolean mTlsAllowed = false;
    
    private boolean mOpen;
    private boolean mInputOpen;
    private int mConnectionSecurity;
    
    private ArrayList<String> mQueuedInput = new ArrayList<String>();
            
    private static class Transaction {
        public static final int ACTION_INJECT_TEXT = 0;
        public static final int ACTION_SERVER_CLOSE = 1;
        public static final int ACTION_CLIENT_CLOSE = 2;
        
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
                case ACTION_SERVER_CLOSE:
                    return "Close the server connection";
                case ACTION_CLIENT_CLOSE:
                    return "Expect the client to close";
                default:
                    return "(Hmm.  Unknown action.)";
            }
        }
    }
    
    private ArrayList<Transaction> mPairs = new ArrayList<Transaction>();
    
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
     * Tell the Mock Transport that we expect it to be closed.  This will preserve
     * the remaining entries in the expect() stream and allow us to "ride over" the close (which
     * would normally reset everything).
     */
    public void expectClose() {
        mPairs.add(new Transaction(Transaction.ACTION_CLIENT_CLOSE));
    }
    
    private void sendResponse(String[] responses) {
        for (String s : responses) {
            mQueuedInput.add(s);
        }
    }

    public boolean canTrySslSecurity() {
        return (mConnectionSecurity == CONNECTION_SECURITY_SSL_REQUIRED
                || mConnectionSecurity == CONNECTION_SECURITY_SSL_OPTIONAL);
    }
    
    public boolean canTryTlsSecurity() {
        return (mConnectionSecurity == Transport.CONNECTION_SECURITY_TLS_OPTIONAL
                || mConnectionSecurity == Transport.CONNECTION_SECURITY_TLS_REQUIRED);
    }
    
    /**
     * This simulates a condition where the server has closed its side, causing 
     * reads to fail.
     */
    public void closeInputStream() {
        mInputOpen = false;
    }

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

    public String getHost() {
        SmtpSenderUnitTests.fail("getHost() not implemented");
        return null;
    }

    public InputStream getInputStream() {
        SmtpSenderUnitTests.assertTrue(mOpen);
        return new MockInputStream();
    }

    /**
     * This normally serves as a pseudo-clone, for use by Imap.  For the purposes of unit testing,
     * until we need something more complex, we'll just return the actual MockTransport.  Then we 
     * don't have to worry about dealing with test metadata like the expects list or socket state.
     */
    public Transport newInstanceWithConfiguration() {
         return this;
    }

    public OutputStream getOutputStream() {
        SmtpSenderUnitTests.assertTrue(mOpen);
        return new MockOutputStream();
    }

    public int getPort() {
        SmtpSenderUnitTests.fail("getPort() not implemented");
        return 0;
    }

    public int getSecurity() {
        return mConnectionSecurity;
    }

    public String[] getUserInfoParts() {
        SmtpSenderUnitTests.fail("getUserInfoParts() not implemented");
        return null;
    }

    public boolean isOpen() {
        return mOpen;
    }

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
    public String readLine() throws IOException {
        SmtpSenderUnitTests.assertTrue(mOpen);
        if (!mInputOpen) {
            throw new IOException("Reading from MockTransport with closed input");
        }
        // if there's nothing to read, see if we can find a null-pattern response
        if (0 == mQueuedInput.size()) {
            Transaction pair = mPairs.get(0);
            if (pair != null && pair.mPattern == null) {
                mPairs.remove(0);
                sendResponse(pair.mResponses);
            }
        }
        SmtpSenderUnitTests.assertTrue("Underflow reading from MockTransport", 0 != mQueuedInput.size());
        String line = mQueuedInput.remove(0);
        if (DEBUG_LOG_STREAMS) {
            Log.d(LOG_TAG, "<<< " + line);
        }
        return line;
    }

    public void reopenTls() /* throws MessagingException */ {
        SmtpSenderUnitTests.assertTrue(mOpen);
        SmtpSenderUnitTests.assertTrue(mTlsAllowed);
        SmtpSenderUnitTests.fail("reopenTls() not implemented");
    }

    public void setSecurity(int connectionSecurity) {
        mConnectionSecurity = connectionSecurity;
    }

    public void setSoTimeout(int timeoutMilliseconds) /* throws SocketException */ {
    }
    
    public void setUri(URI uri, int defaultPort) {
        SmtpSenderUnitTests.assertTrue("Don't call setUri on a mock transport", false);
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
    public void writeLine(String s, String sensitiveReplacement) /* throws IOException */ {
        if (DEBUG_LOG_STREAMS) {
            Log.d(LOG_TAG, ">>> " + s);
        }
        SmtpSenderUnitTests.assertTrue(mOpen);
        SmtpSenderUnitTests.assertTrue("Overflow writing to MockTransport", 0 != mPairs.size());
        Transaction pair = mPairs.remove(0);
        SmtpSenderUnitTests.assertTrue("Unexpected string written to MockTransport", 
                pair.mPattern != null && s.matches(pair.mPattern));
        if (pair.mResponses != null) {
            sendResponse(pair.mResponses);
        }
    }
    
    /**
     * This is an InputStream that satisfies the needs of getInputStream()
     */
    private class MockInputStream extends InputStream {

        byte[] mNextLine = null;
        int mNextIndex = 0;
        
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

        StringBuilder sb = new StringBuilder();

        @Override
        public void write(int oneByte) throws IOException {
            switch (oneByte) {
            case '\n':
            case '\r':
                if (sb.length() > 0) {
                    writeLine(sb.toString(), null);
                    sb = new StringBuilder();
                }
                break;
            default:
                sb.append((char)oneByte);
            }
        }
    }
}