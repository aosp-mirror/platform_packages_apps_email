/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.emailcommon.utility;

import com.android.mail.utils.LogUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.*;
import javax.net.ssl.SSLSocketFactory;

public class SSLSocketFactoryWrapper extends javax.net.ssl.SSLSocketFactory {
    private final SSLSocketFactory mFactory;
    private final boolean mSecure;
    private final int mHandshakeTimeout;
    private final String[] mDefaultCipherSuites;

    private final String[] DEPRECATED_CIPHER_SUITES_TO_ENABLE = new String[] {
            "TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA",
            "SSL_DHE_DSS_WITH_3DES_EDE_CBC_SHA",
            "SSL_DHE_RSA_WITH_3DES_EDE_CBC_SHA",
            "SSL_RSA_WITH_3DES_EDE_CBC_SHA",
            "SSL_RSA_WITH_RC4_128_MD5",
            "TLS_ECDH_ECDSA_WITH_3DES_EDE_CBC_SHA",
            "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDH_ECDSA_WITH_RC4_128_SHA",
            "TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA",
            "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDH_RSA_WITH_RC4_128_SHA",
            "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA",
            "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
            "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
            "SSL_RSA_EXPORT_WITH_RC4_40_MD5",
            "SSL_DHE_DSS_WITH_DES_CBC_SHA",
            "SSL_DHE_RSA_WITH_DES_CBC_SHA",
            "SSL_RSA_WITH_DES_CBC_SHA"
    };

    SSLSocketFactoryWrapper(final SSLSocketFactory factory, final boolean secure,
                            int handshakeTimeout) {
        mFactory = factory;
        mSecure = secure;
        mHandshakeTimeout = handshakeTimeout;

        // Find the base factory's list of defaultCipherSuites, and merge our extras with it.
        // Remember that the order is important. We'll add our extras at the end, and only
        // if they weren't already in the base factory's list.
        final String[] baseDefaultCipherSuites = mFactory.getDefaultCipherSuites();
        final List<String> fullCipherSuiteList = new ArrayList<String>(Arrays.asList(
                mFactory.getDefaultCipherSuites()));
        final Set<String> baseDefaultCipherSuiteSet = new HashSet<String>(fullCipherSuiteList);

        final String[] baseSupportedCipherSuites = mFactory.getSupportedCipherSuites();
        final Set<String> baseSupportedCipherSuiteSet = new HashSet<String>(Arrays.asList(
                mFactory.getSupportedCipherSuites()));

        for (String cipherSuite : DEPRECATED_CIPHER_SUITES_TO_ENABLE) {
            if (baseSupportedCipherSuiteSet.contains(cipherSuite) &&
                    !baseDefaultCipherSuiteSet.contains(cipherSuite)) {
                fullCipherSuiteList.add(cipherSuite);
            }
        }
        mDefaultCipherSuites = new String[fullCipherSuiteList.size()];
        fullCipherSuiteList.toArray(mDefaultCipherSuites);
    }

    public static SSLSocketFactory getDefault(final KeyManager[] keyManagers, int handshakeTimeout)
            throws NoSuchAlgorithmException, KeyManagementException{
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, null, null);
        return new SSLSocketFactoryWrapper(context.getSocketFactory(), true, handshakeTimeout);
    }

    public static SSLSocketFactory getInsecure(final KeyManager[] keyManagers,
                                               final TrustManager[] trustManagers,
                                               int handshakeTimeout)
            throws NoSuchAlgorithmException, KeyManagementException {
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, trustManagers, null);
        return new SSLSocketFactoryWrapper(context.getSocketFactory(), false, handshakeTimeout);
    }

    public Socket createSocket()throws IOException {
        return mFactory.createSocket();
    }

    public Socket createSocket(final Socket socket, final String host, final int port,
                        final boolean autoClose) throws IOException {
        final SSLSocket sslSocket = (SSLSocket)mFactory.createSocket(socket, host, port, autoClose);
        setHandshakeTimeout(sslSocket, mHandshakeTimeout);
        sslSocket.setEnabledCipherSuites(mDefaultCipherSuites);
        if (mSecure) {
            verifyHostname(sslSocket, host);
        }
        return sslSocket;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
        final SSLSocket sslSocket = (SSLSocket)mFactory.createSocket(host, port);
        setHandshakeTimeout(sslSocket, mHandshakeTimeout);
        sslSocket.setEnabledCipherSuites(mDefaultCipherSuites);
        if (mSecure) {
            verifyHostname(sslSocket, host);
        }
        return sslSocket;
    }

    @Override
    public Socket createSocket(String host, int i, InetAddress inetAddress, int i2) throws
            IOException, UnknownHostException {
        final SSLSocket sslSocket = (SSLSocket)mFactory.createSocket(host, i, inetAddress, i2);
        setHandshakeTimeout(sslSocket, mHandshakeTimeout);
        sslSocket.setEnabledCipherSuites(mDefaultCipherSuites);
        if (mSecure) {
            verifyHostname(sslSocket, host);
        }
        return sslSocket;
    }

    @Override
    public Socket createSocket(InetAddress inetAddress, int i) throws IOException {
        final SSLSocket sslSocket = (SSLSocket)mFactory.createSocket(inetAddress, i);
        setHandshakeTimeout(sslSocket, mHandshakeTimeout);
        sslSocket.setEnabledCipherSuites(mDefaultCipherSuites);
        return sslSocket;
    }

    @Override
    public Socket createSocket(InetAddress inetAddress, int i, InetAddress inetAddress2, int i2)
            throws IOException {
        final SSLSocket sslSocket = (SSLSocket)mFactory.createSocket(inetAddress, i, inetAddress2,
                i2);
        setHandshakeTimeout(sslSocket, mHandshakeTimeout);
        sslSocket.setEnabledCipherSuites(mDefaultCipherSuites);
        return sslSocket;
    }

    public String[] getDefaultCipherSuites() {
        return mDefaultCipherSuites.clone();
    }

    public String[] getSupportedCipherSuites() {
        return mFactory.getSupportedCipherSuites();
    }

    /**
     * Attempt to set the hostname of the socket.
     * @param sslSocket The SSLSocket
     * @param hostname the hostname
     * @return true if able to set the hostname, false if not.
     */
    public static boolean potentiallyEnableSni(SSLSocket sslSocket, String hostname) {
        try {
            // Many implementations of SSLSocket support setHostname, although it is not part of
            // the class definition. We will attempt to setHostname using reflection. If the
            // particular SSLSocket implementation we are using does not support this meethod,
            // we'll fail and return false.
            sslSocket.getClass().getMethod("setHostname", String.class).invoke(sslSocket, hostname);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Attempt to enable session tickets.
     * @param sslSocket the SSLSocket.
     * @return true if able to enable session tickets, false otherwise.
     */
    public static boolean potentiallyEnableSessionTickets(SSLSocket sslSocket) {
        try {
            // Many implementations of SSLSocket support setUseSessionTickets, although it is not
            // part of the class definition. We will attempt to setHostname using reflection. If the
            // particular SSLSocket implementation we are using does not support this meethod,
            // we'll fail and return false.
            sslSocket.getClass().getMethod("setUseSessionTickets", boolean.class)
                    .invoke(sslSocket, true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verify the hostname of the certificate used by the other end of a
     * connected socket.  You MUST call this if you did not supply a hostname
     * to {@link #createSocket()}.  It is harmless to call this method
     * redundantly if the hostname has already been verified.
     *
     * @param socket An SSL socket which has been connected to a server
     * @param hostname The expected hostname of the remote server
     * @throws IOException if something goes wrong handshaking with the server
     * @throws SSLPeerUnverifiedException if the server cannot prove its identity
     *
     * @hide
     */
    public static void verifyHostname(Socket socket, String hostname) throws IOException {
        if (!(socket instanceof SSLSocket)) {
            throw new IllegalArgumentException("Attempt to verify non-SSL socket");
        }

        // The code at the start of OpenSSLSocketImpl.startHandshake()
        // ensures that the call is idempotent, so we can safely call it.
        SSLSocket ssl = (SSLSocket) socket;
        ssl.startHandshake();

        SSLSession session = ssl.getSession();
        if (session == null) {
            throw new SSLException("Cannot verify SSL socket without session");
        }
        LogUtils.d(LogUtils.TAG, "using cipherSuite %s", session.getCipherSuite());
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)) {
            throw new SSLPeerUnverifiedException("Cannot verify hostname: " + hostname);
        }
    }

    private void setHandshakeTimeout(SSLSocket sslSocket, int timeout) {
        try {
            // Most implementations of SSLSocket support setHandshakeTimeout(), but it is not
            // actually part of the class definition. We will attempt to set it using reflection.
            // If the particular implementation of SSLSocket we are using does not support this
            // function, then we will just have to use the default handshake timeout.
            sslSocket.getClass().getMethod("setHandshakeTimeout", int.class).invoke(sslSocket,
                    timeout);
        } catch (Exception e) {
            LogUtils.w(LogUtils.TAG, e, "unable to set handshake timeout");
        }
    }
}
