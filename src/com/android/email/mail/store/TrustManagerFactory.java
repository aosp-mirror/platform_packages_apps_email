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

import org.apache.harmony.xnet.provider.jsse.SSLParameters;

import android.net.http.DomainNameChecker;
import android.util.Log;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

/**
 * This factory creates and returns two types of TrustManagers.
 *
 * The "secure" trust manager performs standard tests of certificates, and throws
 * CertificateException when the tests fail.
 *
 * The "simple" trust manager performs no tests, effectively accepting all certificates.
 */
public final class TrustManagerFactory {
    private static X509TrustManager sUnsecureTrustManager = new SimpleX509TrustManager();

    /**
     * This trust manager performs no tests, effectively accepting all certificates.
     */
    private static class SimpleX509TrustManager implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            logCertificates(chain, "Trusting client", false);
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            logCertificates(chain, "Trusting server", false);
        }

        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }
    }

    /**
     * This trust manager performs full tests, requiring a valid, trusted certificate.
     */
    private static class SecureX509TrustManager implements X509TrustManager {
        private X509TrustManager mTrustManager;
        private String mHost;

        SecureX509TrustManager(X509TrustManager trustManager, String host) {
            mTrustManager = trustManager;
            mHost = host;
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                mTrustManager.checkClientTrusted(chain, authType);
            } catch (CertificateException ce) {
                logCertificates(chain, "Failed client", true);
                throw ce;
            }
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {

            try {
                mTrustManager.checkServerTrusted(chain, authType);
            } catch (CertificateException ce) {
                logCertificates(chain, "Failed server", true);
                throw ce;
            }

            if (!DomainNameChecker.match(chain[0], mHost)) {
                logCertificates(chain, "Failed domain name", true);
                throw new CertificateException("Certificate domain name does not match " + mHost);
            }
        }

        public X509Certificate[] getAcceptedIssuers() {
            return mTrustManager.getAcceptedIssuers();
        }
    }

    /**
     * Logging of certificates, to help debugging trust issues.  Logging strategy:
     *   Trusting a certificate:  Lightweight log about it
     *   Fully checking:  Silent if OK, verbose log it failure
     *
     * @param chain the certificate chain to dump
     * @param caller a prefix that will be added to each log
     * @param verbose if true, the issuer and dates will also be logged
     */
    private static void logCertificates(X509Certificate[] chain, String caller, boolean verbose) {
        if (Email.DEBUG) {
            for (int i = 0; i < chain.length; ++i) {
                Log.d(Email.LOG_TAG, caller + " Certificate #" + i);
                Log.d(Email.LOG_TAG, "  subject=" + chain[i].getSubjectDN());
                if (verbose) {
                    Log.d(Email.LOG_TAG, "  issuer=" + chain[i].getIssuerDN());
                    Log.d(Email.LOG_TAG, "  dates=" + chain[i].getNotBefore()
                            + " to " + chain[i].getNotAfter());
                }
            }
        }
    }

    private TrustManagerFactory() {
    }

    public static X509TrustManager get(String host, boolean secure) {
        if (secure) {
            return new SecureX509TrustManager(SSLParameters.getDefaultTrustManager(), host) ;
        } else {
            return sUnsecureTrustManager;
        }
    }
}
