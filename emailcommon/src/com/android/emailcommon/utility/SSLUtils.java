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

    /**
     * Escapes the contents a string to be used as a safe scheme name in the URI according to
     * http://tools.ietf.org/html/rfc3986#section-3.1
     *
     * This does not ensure that the first character is a letter (which is required by the RFC).
     */
    public static String escapeForSchemeName(String s) {
        // According to the RFC, scheme names are case-insensitive.
        s = s.toLowerCase();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c) || Character.isDigit(c)
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
}
