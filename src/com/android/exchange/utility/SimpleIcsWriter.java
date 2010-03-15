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

import java.io.CharArrayWriter;
import java.io.IOException;

public class SimpleIcsWriter extends CharArrayWriter {
    public static final int MAX_LINE_LENGTH = 75;
    public static final int LINE_BREAK_LENGTH = 3;
    public static final String LINE_BREAK = "\r\n\t";
    int mColumnCount = 0;

    public SimpleIcsWriter() {
        super();
    }

    private void newLine() {
        write('\r');
        write('\n');
        mColumnCount = 0;
    }

    @Override
    public void write(String str) throws IOException {
        int len = str.length();
        for (int i = 0; i < len; i++, mColumnCount++) {
            if (mColumnCount == MAX_LINE_LENGTH) {
                write('\r');
                write('\n');
                write('\t');
                // Line count will get immediately incremented to one (the tab)
                mColumnCount = 0;
            }
            char c = str.charAt(i);
            if (c == '\r') {
                // Ignore CR
                mColumnCount--;
                continue;
            } else if (c == '\n') {
                // On LF, set to -1, which will immediately get incremented to zero
                write("\\");
                write("n");
                mColumnCount = -1;
                continue;
            }
            write(c);
        }
    }

    public void writeTag(String name, String value) throws IOException {
        // Belt and suspenders here; don't crash on null value.  Use something innocuous
        if (value == null) value = "0";
        write(name);
        write(":");
        write(value);
        newLine();
    }

    /**
     * Quote a param-value string, according to RFC 5545, section 3.1
     */
    public static String quoteParamValue(String paramValue) {
        if (paramValue == null) {
            return null;
        }
        // Wrap with double quotes.  You can't put double-quotes itself in it, so remove them first.
        // We can be smarter -- e.g. we don't have to wrap an empty string with dquotes -- but
        // we don't have to.
        return "\"" + paramValue.replace("\"", "") + "\"";
    }
}
