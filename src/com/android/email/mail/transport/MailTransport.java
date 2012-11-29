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

import com.android.email.Email;
import com.android.email.mail.Transport;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.CertificateValidationException;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.utility.SSLUtils;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * This class implements the common aspects of "transport", one layer below the
 * specific wire protocols such as POP3, IMAP, or SMTP.
 */
public class MailTransport implements Transport {

    // TODO protected eventually
    /*protected*/ public static final int SOCKET_CONNECT_TIMEOUT = 10000;
    /*protected*/ public static final int SOCKET_READ_TIMEOUT = 60000;

    private static final HostnameVerifier HOSTNAME_VERIFIER =
            HttpsURLConnection.getDefaultHostnameVerifier();

    private String mDebugLabel;

    private String mHost;
    private int mPort;
    private String[] mUserInfoParts;

    /**
     * One of the {@code Transport.CONNECTION_SECURITY_*} values.
     */
    private int mConnectionSecurity;

    /**
     * Whether or not to trust all server certificates (i.e. skip host verification) in SSL
     * handshakes
     */
    private boolean mTrustCertificates;

    private Socket mSocket;
    private InputStream mIn;
    private OutputStream mOut;

    /**
     * Simple constructor for starting from scratch.  Call setUri() and setSecurity() to
     * complete the configuration.
     * @param debugLabel Label used for Log.d calls
     */
    public MailTransport(String debugLabel) {
        super();
        mDebugLabel = debugLabel;
    }

    /**
     * Returns a new transport, using the current transport as a model. The new transport is
     * configured identically (as if {@link #setSecurity(int, boolean)}, {@link #setPort(int)}
     * and {@link #setHost(String)} were invoked), but not opened or connected in any way.
     */
    @Override
    public Transport clone() {
        MailTransport newObject = new MailTransport(mDebugLabel);

        newObject.mDebugLabel = mDebugLabel;
        newObject.mHost = mHost;
        newObject.mPort = mPort;
        if (mUserInfoParts != null) {
            newObject.mUserInfoParts = mUserInfoParts.clone();
        }
        newObject.mConnectionSecurity = mConnectionSecurity;
        newObject.mTrustCertificates = mTrustCertificates;
        return newObject;
    }

    @Override
    public void setHost(String host) {
        mHost = host;
    }

    @Override
    public void setPort(int port) {
        mPort = port;
    }

    @Override
    public String getHost() {
        return mHost;
    }

    @Override
    public int getPort() {
        return mPort;
    }

    @Override
    public void setSecurity(int connectionSecurity, boolean trustAllCertificates) {
        mConnectionSecurity = connectionSecurity;
        mTrustCertificates = trustAllCertificates;
    }

    @Override
    public int getSecurity() {
        return mConnectionSecurity;
    }

    @Override
    public boolean canTrySslSecurity() {
        return mConnectionSecurity == Transport.CONNECTION_SECURITY_SSL;
    }

    @Override
    public boolean canTryTlsSecurity() {
        return mConnectionSecurity == Transport.CONNECTION_SECURITY_TLS;
    }

    @Override
    public boolean canTrustAllCertificates() {
        return mTrustCertificates;
    }

    /**
     * Attempts to open a connection using the Uri supplied for connection parameters.  Will attempt
     * an SSL connection if indicated.
     */
    @Override
    public void open() throws MessagingException, CertificateValidationException {
        if (Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "*** " + mDebugLabel + " open " +
                    getHost() + ":" + String.valueOf(getPort()));
        }

        try {
            SocketAddress socketAddress = new InetSocketAddress(getHost(), getPort());
            if (canTrySslSecurity()) {
                mSocket = SSLUtils.getSSLSocketFactory(
                        canTrustAllCertificates(), SOCKET_CONNECT_TIMEOUT).createSocket();
            } else {
                mSocket = new Socket();
            }
            mSocket.connect(socketAddress, SOCKET_CONNECT_TIMEOUT);
            // After the socket connects to an SSL server, confirm that the hostname is as expected
            if (canTrySslSecurity() && !canTrustAllCertificates()) {
                verifyHostname(mSocket, getHost());
            }
            mIn = new BufferedInputStream(mSocket.getInputStream(), 1024);
            mOut = new BufferedOutputStream(mSocket.getOutputStream(), 512);

        } catch (SSLException e) {
            if (Email.DEBUG) {
                Log.d(Logging.LOG_TAG, e.toString());
            }
            throw new CertificateValidationException(e.getMessage(), e);
        } catch (IOException ioe) {
            if (Email.DEBUG) {
                Log.d(Logging.LOG_TAG, ioe.toString());
            }
            throw new MessagingException(MessagingException.IOERROR, ioe.toString());
        }
    }

    /**
     * Attempts to reopen a TLS connection using the Uri supplied for connection parameters.
     *
     * NOTE: No explicit hostname verification is required here, because it's handled automatically
     * by the call to createSocket().
     *
     * TODO should we explicitly close the old socket?  This seems funky to abandon it.
     */
    @Override
    public void reopenTls() throws MessagingException {
        try {
            mSocket =
                    SSLUtils.getSSLSocketFactory(canTrustAllCertificates(), SOCKET_CONNECT_TIMEOUT)
                            .createSocket(mSocket, getHost(), getPort(), true);
            mSocket.setSoTimeout(SOCKET_READ_TIMEOUT);
            mIn = new BufferedInputStream(mSocket.getInputStream(), 1024);
            mOut = new BufferedOutputStream(mSocket.getOutputStream(), 512);

        } catch (SSLException e) {
            if (Email.DEBUG) {
                Log.d(Logging.LOG_TAG, e.toString());
            }
            throw new CertificateValidationException(e.getMessage(), e);
        } catch (IOException ioe) {
            if (Email.DEBUG) {
                Log.d(Logging.LOG_TAG, ioe.toString());
            }
            throw new MessagingException(MessagingException.IOERROR, ioe.toString());
        }
    }

    /**
     * Lightweight version of SSLCertificateSocketFactory.verifyHostname, which provides this
     * service but is not in the public API.
     *
     * Verify the hostname of the certificate used by the other end of a
     * connected socket.  You MUST call this if you did not supply a hostname
     * to SSLCertificateSocketFactory.createSocket().  It is harmless to call this method
     * redundantly if the hostname has already been verified.
     *
     * <p>Wildcard certificates are allowed to verify any matching hostname,
     * so "foo.bar.example.com" is verified if the peer has a certificate
     * for "*.example.com".
     *
     * @param socket An SSL socket which has been connected to a server
     * @param hostname The expected hostname of the remote server
     * @throws IOException if something goes wrong handshaking with the server
     * @throws SSLPeerUnverifiedException if the server cannot prove its identity
      */
    private void verifyHostname(Socket socket, String hostname) throws IOException {
        // The code at the start of OpenSSLSocketImpl.startHandshake()
        // ensures that the call is idempotent, so we can safely call it.
        SSLSocket ssl = (SSLSocket) socket;
        ssl.startHandshake();

        SSLSession session = ssl.getSession();
        if (session == null) {
            throw new SSLException("Cannot verify SSL socket without session");
        }
        // TODO: Instead of reporting the name of the server we think we're connecting to,
        // we should be reporting the bad name in the certificate.  Unfortunately this is buried
        // in the verifier code and is not available in the verifier API, and extracting the
        // CN & alts is beyond the scope of this patch.
        if (!HOSTNAME_VERIFIER.verify(hostname, session)) {
            throw new SSLPeerUnverifiedException(
                    "Certificate hostname not useable for server: " + hostname);
        }
    }

    /**
     * Set the socket timeout.
     * @param timeoutMilliseconds the read timeout value if greater than {@code 0}, or
     *            {@code 0} for an infinite timeout.
     */
    @Override
    public void setSoTimeout(int timeoutMilliseconds) throws SocketException {
        mSocket.setSoTimeout(timeoutMilliseconds);
    }

    @Override
    public boolean isOpen() {
        return (mIn != null && mOut != null &&
                mSocket != null && mSocket.isConnected() && !mSocket.isClosed());
    }

    /**
     * Close the connection.  MUST NOT return any exceptions - must be "best effort" and safe.
     */
    @Override
    public void close() {
        if (Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "*** " + mDebugLabel + " close " +
                    getHost() + ":" + String.valueOf(getPort()));
        }

        try {
            mIn.close();
        } catch (Exception e) {
            // May fail if the connection is already closed.
            if (Email.DEBUG) {
                Log.d(Logging.LOG_TAG, e.toString());
            }
        }
        try {
            mOut.close();
        } catch (Exception e) {
            // May fail if the connection is already closed.
            if (Email.DEBUG) {
                Log.d(Logging.LOG_TAG, e.toString());
            }
        }
        try {
            mSocket.close();
        } catch (Exception e) {
            // May fail if the connection is already closed.
            if (Email.DEBUG) {
                Log.d(Logging.LOG_TAG, e.toString());
            }
        }
        mIn = null;
        mOut = null;
        mSocket = null;
    }

    @Override
    public InputStream getInputStream() {
        return mIn;
    }

    @Override
    public OutputStream getOutputStream() {
        return mOut;
    }

    /**
     * Writes a single line to the server using \r\n termination.
     */
    @Override
    public void writeLine(String s, String sensitiveReplacement) throws IOException {
        if (Email.DEBUG) {
            if (sensitiveReplacement != null && !Logging.DEBUG_SENSITIVE) {
                Log.d(Logging.LOG_TAG, ">>> " + sensitiveReplacement);
            } else {
                Log.d(Logging.LOG_TAG, ">>> " + s);
            }
        }

        OutputStream out = getOutputStream();
        out.write(s.getBytes());
        out.write('\r');
        out.write('\n');
        out.flush();
    }

    /**
     * Reads a single line from the server, using either \r\n or \n as the delimiter.  The
     * delimiter char(s) are not included in the result.
     */
    @Override
    public String readLine() throws IOException {
        StringBuffer sb = new StringBuffer();
        InputStream in = getInputStream();
        int d;
        while ((d = in.read()) != -1) {
            if (((char)d) == '\r') {
                continue;
            } else if (((char)d) == '\n') {
                break;
            } else {
                sb.append((char)d);
            }
        }
        if (d == -1 && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "End of stream reached while trying to read line.");
        }
        String ret = sb.toString();
        if (Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "<<< " + ret);
        }
        return ret;
    }

    @Override
    public InetAddress getLocalAddress() {
        if (isOpen()) {
            return mSocket.getLocalAddress();
        } else {
            return null;
        }
    }
}
