/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.SSLCertificateSocketFactory;
import android.security.KeyChain;
import android.security.KeyChainException;

import com.android.emailcommon.provider.EmailContent.HostAuthColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.mail.utils.LogUtils;
import com.google.common.annotations.VisibleForTesting;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

public class SSLUtils {
    // All secure factories are the same; all insecure factories are associated with HostAuth's
    private static SSLCertificateSocketFactory sSecureFactory;

    private static final boolean LOG_ENABLED = false;
    private static final String TAG = "Email.Ssl";

    // A 30 second SSL handshake should be more than enough.
    private static final int SSL_HANDSHAKE_TIMEOUT = 30000;

    /**
     * A trust manager specific to a particular HostAuth.  The first time a server certificate is
     * encountered for the HostAuth, its certificate is saved; subsequent checks determine whether
     * the PublicKey of the certificate presented matches that of the saved certificate
     * TODO: UI to ask user about changed certificates
     */
    private static class SameCertificateCheckingTrustManager implements X509TrustManager {
        private final HostAuth mHostAuth;
        private final Context mContext;
        // The public key associated with the HostAuth; we'll lazily initialize it
        private PublicKey mPublicKey;

        SameCertificateCheckingTrustManager(Context context, HostAuth hostAuth) {
            mContext = context;
            mHostAuth = hostAuth;
            // We must load the server cert manually (the ContentCache won't handle blobs
            Cursor c = context.getContentResolver().query(HostAuth.CONTENT_URI,
                    new String[] {HostAuthColumns.SERVER_CERT}, HostAuthColumns._ID + "=?",
                    new String[] {Long.toString(hostAuth.mId)}, null);
            if (c != null) {
                try {
                    if (c.moveToNext()) {
                        mHostAuth.mServerCert = c.getBlob(0);
                    }
                } finally {
                    c.close();
                }
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            // We don't check client certificates
            throw new CertificateException("We don't check client certificates");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain.length == 0) {
                throw new CertificateException("No certificates?");
            } else {
                X509Certificate serverCert = chain[0];
                if (mHostAuth.mServerCert != null) {
                    // Compare with the current public key
                    if (mPublicKey == null) {
                        ByteArrayInputStream bais = new ByteArrayInputStream(mHostAuth.mServerCert);
                        Certificate storedCert =
                                CertificateFactory.getInstance("X509").generateCertificate(bais);
                        mPublicKey = storedCert.getPublicKey();
                        try {
                            bais.close();
                        } catch (IOException e) {
                            // Yeah, right.
                        }
                    }
                    if (!mPublicKey.equals(serverCert.getPublicKey())) {
                        throw new CertificateException(
                                "PublicKey has changed since initial connection!");
                    }
                } else {
                    // First time; save this away
                    byte[] encodedCert = serverCert.getEncoded();
                    mHostAuth.mServerCert = encodedCert;
                    ContentValues values = new ContentValues();
                    values.put(HostAuthColumns.SERVER_CERT, encodedCert);
                    mContext.getContentResolver().update(
                            ContentUris.withAppendedId(HostAuth.CONTENT_URI, mHostAuth.mId),
                            values, null, null);
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }
    }

    /**
     * Returns a {@link javax.net.ssl.SSLSocketFactory}.
     * Optionally bypass all SSL certificate checks.
     *
     * @param insecure if true, bypass all SSL certificate checks
     */
    public synchronized static SSLCertificateSocketFactory getSSLSocketFactory(Context context,
            HostAuth hostAuth, boolean insecure) {
        if (insecure) {
            SSLCertificateSocketFactory insecureFactory = (SSLCertificateSocketFactory)
                    SSLCertificateSocketFactory.getInsecure(SSL_HANDSHAKE_TIMEOUT, null);
            insecureFactory.setTrustManagers(
                    new TrustManager[] {
                            new SameCertificateCheckingTrustManager(context, hostAuth)});
            return insecureFactory;
        } else {
            if (sSecureFactory == null) {
                sSecureFactory = (SSLCertificateSocketFactory)
                        SSLCertificateSocketFactory.getDefault(SSL_HANDSHAKE_TIMEOUT, null);
            }
            return sSecureFactory;
        }
    }

    /**
     * Returns a {@link org.apache.http.conn.ssl.SSLSocketFactory SSLSocketFactory} for use with the
     * Apache HTTP stack.
     */
    public static SSLSocketFactory getHttpSocketFactory(Context context, HostAuth hostAuth,
            KeyManager keyManager, boolean insecure) {
        SSLCertificateSocketFactory underlying = getSSLSocketFactory(context, hostAuth, insecure);
        if (keyManager != null) {
            underlying.setKeyManagers(new KeyManager[] { keyManager });
        }
        SSLSocketFactory wrapped = new SSLSocketFactory(underlying);
        if (insecure) {
            wrapped.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
        }
        return wrapped;
    }

    // Character.isLetter() is locale-specific, and will potentially return true for characters
    // outside of ascii a-z,A-Z
    private static boolean isAsciiLetter(char c) {
        return ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z');
    }

    // Character.isDigit() is locale-specific, and will potentially return true for characters
    // outside of ascii 0-9
    private static boolean isAsciiNumber(char c) {
        return ('0' <= c && c <= '9');
    }

    /**
     * Escapes the contents a string to be used as a safe scheme name in the URI according to
     * http://tools.ietf.org/html/rfc3986#section-3.1
     *
     * This does not ensure that the first character is a letter (which is required by the RFC).
     */
    @VisibleForTesting
    public static String escapeForSchemeName(String s) {
        // According to the RFC, scheme names are case-insensitive.
        s = s.toLowerCase();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isAsciiLetter(c) || isAsciiNumber(c)
                    || ('-' == c) || ('.' == c)) {
                // Safe - use as is.
                sb.append(c);
            } else if ('+' == c) {
                // + is used as our escape character, so double it up.
                sb.append("++");
            } else {
                // Unsafe - escape.
                sb.append('+').append((int) c);
            }
        }
        return sb.toString();
    }

    private static abstract class StubKeyManager extends X509ExtendedKeyManager {
        @Override public abstract String chooseClientAlias(
                String[] keyTypes, Principal[] issuers, Socket socket);

        @Override public abstract X509Certificate[] getCertificateChain(String alias);

        @Override public abstract PrivateKey getPrivateKey(String alias);


        // The following methods are unused.

        @Override
        public final String chooseServerAlias(
                String keyType, Principal[] issuers, Socket socket) {
            // not a client SSLSocket callback
            throw new UnsupportedOperationException();
        }

        @Override
        public final String[] getClientAliases(String keyType, Principal[] issuers) {
            // not a client SSLSocket callback
            throw new UnsupportedOperationException();
        }

        @Override
        public final String[] getServerAliases(String keyType, Principal[] issuers) {
            // not a client SSLSocket callback
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A dummy {@link KeyManager} which keeps track of the last time a server has requested
     * a client certificate.
     */
    public static class TrackingKeyManager extends StubKeyManager {
        private volatile long mLastTimeCertRequested = 0L;

        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            if (LOG_ENABLED) {
                InetAddress address = socket.getInetAddress();
                LogUtils.i(TAG, "TrackingKeyManager: requesting a client cert alias for "
                        + address.getCanonicalHostName());
            }
            mLastTimeCertRequested = System.currentTimeMillis();
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            if (LOG_ENABLED) {
                LogUtils.i(TAG, "TrackingKeyManager: returning a null cert chain");
            }
            return null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            if (LOG_ENABLED) {
                LogUtils.i(TAG, "TrackingKeyManager: returning a null private key");
            }
            return null;
        }

        /**
         * @return the last time that this {@link KeyManager} detected a request by a server
         *     for a client certificate (in millis since epoch).
         */
        public long getLastCertReqTime() {
            return mLastTimeCertRequested;
        }
    }

    /**
     * A {@link KeyManager} that reads uses credentials stored in the system {@link KeyChain}.
     */
    public static class KeyChainKeyManager extends StubKeyManager {
        private final String mClientAlias;
        private final X509Certificate[] mCertificateChain;
        private final PrivateKey mPrivateKey;

        /**
         * Builds an instance of a KeyChainKeyManager using the given certificate alias.
         * If for any reason retrieval of the credentials from the system {@link KeyChain} fails,
         * a {@code null} value will be returned.
         */
        public static KeyChainKeyManager fromAlias(Context context, String alias)
                throws CertificateException {
            X509Certificate[] certificateChain;
            try {
                certificateChain = KeyChain.getCertificateChain(context, alias);
            } catch (KeyChainException e) {
                logError(alias, "certificate chain", e);
                throw new CertificateException(e);
            } catch (InterruptedException e) {
                logError(alias, "certificate chain", e);
                throw new CertificateException(e);
            }

            PrivateKey privateKey;
            try {
                privateKey = KeyChain.getPrivateKey(context, alias);
            } catch (KeyChainException e) {
                logError(alias, "private key", e);
                throw new CertificateException(e);
            } catch (InterruptedException e) {
                logError(alias, "private key", e);
                throw new CertificateException(e);
            }

            if (certificateChain == null || privateKey == null) {
                throw new CertificateException("Can't access certificate from keystore");
            }

            return new KeyChainKeyManager(alias, certificateChain, privateKey);
        }

        private static void logError(String alias, String type, Exception ex) {
            // Avoid logging PII when explicit logging is not on.
            if (LOG_ENABLED) {
                LogUtils.e(TAG, "Unable to retrieve " + type + " for [" + alias + "] due to " + ex);
            } else {
                LogUtils.e(TAG, "Unable to retrieve " + type + " due to " + ex);
            }
        }

        private KeyChainKeyManager(
                String clientAlias, X509Certificate[] certificateChain, PrivateKey privateKey) {
            mClientAlias = clientAlias;
            mCertificateChain = certificateChain;
            mPrivateKey = privateKey;
        }


        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            if (LOG_ENABLED) {
                LogUtils.i(TAG, "Requesting a client cert alias for " + Arrays.toString(keyTypes));
            }
            return mClientAlias;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            if (LOG_ENABLED) {
                LogUtils.i(TAG, "Requesting a client certificate chain for alias [" + alias + "]");
            }
            return mCertificateChain;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            if (LOG_ENABLED) {
                LogUtils.i(TAG, "Requesting a client private key for alias [" + alias + "]");
            }
            return mPrivateKey;
        }
    }
}
