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

package com.android.emailcommon.utility;

import com.android.emailcommon.Logging;
import com.android.mail.utils.LogUtils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Simple class used for debugging only that affords us a view of the raw IMAP or POP3 stream,
 * in addition to the tokenized version.
 *
 * Use of this class *MUST* be restricted to logging-enabled situations only.
 */
public class LoggingInputStream extends FilterInputStream {
    private StringBuilder mSb;
    private boolean mDumpEmptyLines;
    private final String mTag;

    public LoggingInputStream(InputStream in) {
        this(in, "RAW", false);
    }

    public LoggingInputStream(InputStream in, String tag, boolean dumpEmptyLines) {
        super(in);
        mTag = tag + " ";
        mDumpEmptyLines = dumpEmptyLines;
        initBuffer();
        LogUtils.d(Logging.LOG_TAG, mTag + "dump start");
    }

    private void initBuffer() {
        mSb = new StringBuilder(mTag);
    }

    /**
     * Collect chars as read, and log them when EOL reached.
     */
    @Override
    public int read() throws IOException {
        int oneByte = super.read();
        logRaw(oneByte);
        return oneByte;
    }

    /**
     * Collect chars as read, and log them when EOL reached.
     */
    @Override
    public int read(byte[] b, int offset, int length) throws IOException {
        int bytesRead = super.read(b, offset, length);
        int copyBytes = bytesRead;
        while (copyBytes > 0) {
            logRaw(b[offset] & 0xFF);
            copyBytes--;
            offset++;
        }

        return bytesRead;
    }

    /**
     * Write and clear the buffer
     */
    private void logRaw(int oneByte) {
        if (oneByte == '\r') {
            // Don't log.
        } else if (oneByte == '\n') {
            flushLog();
        } else if (0x20 <= oneByte && oneByte <= 0x7e) { // Printable ASCII.
            mSb.append((char)oneByte);
        } else {
            // email protocols are supposed to be all 7bits, but there are wrong implementations
            // that do send 8 bit characters...
            mSb.append("\\x" + Utility.byteToHex(oneByte));
        }
    }

    private void flushLog() {
        if (mDumpEmptyLines || (mSb.length() > mTag.length())) {
            LogUtils.d(Logging.LOG_TAG, mSb.toString());
            initBuffer();
        }
    }

    @Override
    public void close() throws IOException {
        super.close();
        flushLog();
    }
}
