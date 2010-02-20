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

public class SimpleIcsWriter extends CharArrayWriter {
    public static final int MAX_LINE_LENGTH = 75;
    public static final int LINE_BREAK_LENGTH = 3;
    public static final String LINE_BREAK = "\r\n\t";
    int mLineCount = 0;

    public SimpleIcsWriter() {
        super();
    }

    private void newLine() {
        write('\r');
        write('\n');
        mLineCount = 0;
    }

    @Override
    public void write(String str) {
        int len = str.length();
        // Handle the simple case here to avoid unnecessary looping
        if (mLineCount + len < MAX_LINE_LENGTH) {
            mLineCount += len;
            write(str);
            return;
        }
        for (int i = 0; i < len; i++, mLineCount++) {
            if (mLineCount == MAX_LINE_LENGTH) {
                write('\r');
                write('\n');
                write('\t');
                mLineCount = 0;
            }
            write(str.charAt(i));
        }
    }

    public void writeTag(String name, String value) {
        write(name);
        write(":");
        write(value);
        newLine();
    }
}
