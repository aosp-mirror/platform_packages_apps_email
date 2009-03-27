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

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

/**
 * Simple class used for debugging only that affords us a view of the raw IMAP or POP3 stream,
 * in addition to the tokenized version.
 * 
 * Use of this class *MUST* be restricted to logging-enabled situations only.
 */
public class LoggingInputStream extends InputStream {

    InputStream mIn;
    StringBuilder mSb;
    boolean mBufferDirty;
    
    private final String LINE_TAG = "RAW ";

    public LoggingInputStream(InputStream in) {
        super();
        mIn = in;
        mSb = new StringBuilder(LINE_TAG);
        mBufferDirty = false;
    }

    /**
     * Collect chars as read, and log them when EOL reached.
     */
    @Override
    public int read() throws IOException {
        int oneByte = mIn.read();
        logRaw(oneByte);
        return oneByte;
    }

    /**
     * Collect chars as read, and log them when EOL reached.
     */
    @Override
    public int read(byte[] b, int offset, int length) throws IOException {
        int bytesRead = mIn.read(b, offset, length);
        int copyBytes = bytesRead;
        while (copyBytes > 0) {
            logRaw((char)b[offset]);
            copyBytes--;
            offset++;
        }

        return bytesRead;
    }

    /**
     * Write and clear the buffer
     */
    private void logRaw(int oneByte) {
        if (oneByte == '\r' || oneByte == '\n') {          
            if (mBufferDirty) {
                Log.d(Email.LOG_TAG, mSb.toString());
                mSb = new StringBuilder(LINE_TAG);
                mBufferDirty = false;
            }
        } else {
            mSb.append((char)oneByte);
            mBufferDirty = true;
        }
    }
}
