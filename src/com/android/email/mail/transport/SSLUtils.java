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

package com.android.email.mail.transport;

import android.net.SSLCertificateSocketFactory;

import javax.net.ssl.SSLSocketFactory;

public class SSLUtils {
    private static SSLSocketFactory sInsecureFactory;
    private static SSLSocketFactory sSecureFactory;

    /**
     * Returns a {@link SSLSocketFactory}.  Optionally bypass all SSL certificate checks.
     *
     * @param insecure if true, bypass all SSL certificate checks
     */
    public synchronized static final SSLSocketFactory getSSLSocketFactory(boolean insecure) {
        if (insecure) {
            if (sInsecureFactory == null) {
                sInsecureFactory = SSLCertificateSocketFactory.getInsecure(0, null);
            }
            return sInsecureFactory;
        } else {
            if (sSecureFactory == null) {
                sSecureFactory = SSLCertificateSocketFactory.getDefault(0, null);
            }
            return sSecureFactory;
        }
    }
}
