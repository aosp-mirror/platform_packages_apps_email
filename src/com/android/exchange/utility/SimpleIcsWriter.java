/* Copyright 2010, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.exchange.utility;

import com.android.emailcommon.utility.Utility;

import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Class to generate iCalender object (*.ics) per RFC 5545.
 */
public class SimpleIcsWriter {
    private static final int MAX_LINE_LENGTH = 75; // In bytes, excluding CRLF
    private static final int CHAR_MAX_BYTES_IN_UTF8 = 4;  // Used to be 6, but RFC3629 limited it.
    private final ByteArrayOutputStream mOut = new ByteArrayOutputStream();

    public SimpleIcsWriter() {
    }

    /**
     * Low level method to write a line, performing line-folding if necessary.
     */
    /* package for testing */ void writeLine(String string) {
        int numBytes = 0;
        for (byte b : Utility.toUtf8(string)) {
            // Fold it when necessary.
            // To make it simple, we assume all chars are 4 bytes.
            // If not (and usually it's not), we end up wrapping earlier than necessary, but that's
            // completely fine.
            if (numBytes > (MAX_LINE_LENGTH - CHAR_MAX_BYTES_IN_UTF8)
                    && Utility.isFirstUtf8Byte(b)) { // Only wrappable if it's before the first byte
                mOut.write((byte) '\r');
                mOut.write((byte) '\n');
                mOut.write((byte) '\t');
                numBytes = 1; // for TAB
            }
            mOut.write(b);
            numBytes++;
        }
        mOut.write((byte) '\r');
        mOut.write((byte) '\n');
    }

    /**
     * Write a tag with a value.
     */
    public void writeTag(String name, String value) {
        // Belt and suspenders here; don't crash on null value; just return
        if (TextUtils.isEmpty(value)) {
            return;
        }

        // The following properties take a TEXT value, which need to be escaped.
        // (These property names should be all interned, so using equals() should be faster than
        // using a hash table.)

        // TODO make constants for these literals
        if ("CALSCALE".equals(name)
                || "METHOD".equals(name)
                || "PRODID".equals(name)
                || "VERSION".equals(name)
                || "CATEGORIES".equals(name)
                || "CLASS".equals(name)
                || "COMMENT".equals(name)
                || "DESCRIPTION".equals(name)
                || "LOCATION".equals(name)
                || "RESOURCES".equals(name)
                || "STATUS".equals(name)
                || "SUMMARY".equals(name)
                || "TRANSP".equals(name)
                || "TZID".equals(name)
                || "TZNAME".equals(name)
                || "CONTACT".equals(name)
                || "RELATED-TO".equals(name)
                || "UID".equals(name)
                || "ACTION".equals(name)
                || "REQUEST-STATUS".equals(name)
                || "X-LIC-LOCATION".equals(name)
                ) {
            value = escapeTextValue(value);
        }
        writeLine(name + ":" + value);
    }

    /**
     * For debugging
     */
    @Override
    public String toString() {
        return Utility.fromUtf8(getBytes());
    }

    /**
     * @return the entire iCalendar invitation object.
     */
    public byte[] getBytes() {
        try {
            mOut.flush();
        } catch (IOException wonthappen) {
        }
        return mOut.toByteArray();
    }

    /**
     * Quote a param-value string, according to RFC 5545, section 3.1
     */
    public static String quoteParamValue(String paramValue) {
        if (paramValue == null) {
            return null;
        }
        // Wrap with double quotes.
        // The spec doesn't allow putting double-quotes in a param value, so let's use single quotes
        // as a substitute.
        // It's not the smartest implementation.  e.g. we don't have to wrap an empty string with
        // double quotes.  But it works.
        return "\"" + paramValue.replace("\"", "'") + "\"";
    }

    /**
     * Escape a TEXT value per RFC 5545 section 3.3.11
     */
    /* package for testing */ static String escapeTextValue(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n') {
                sb.append("\\n");
            } else if (ch == '\r') {
                // Remove CR
            } else if (ch == ',' || ch == ';' || ch == '\\') {
                sb.append('\\');
                sb.append(ch);
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
