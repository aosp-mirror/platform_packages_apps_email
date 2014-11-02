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

package com.android.email.service;

import com.android.email.FixedLengthInputStream;
import com.android.email.mail.store.imap.ImapResponse;
import com.android.email.mail.store.imap.ImapResponseParser;
import com.android.email.mail.store.imap.ImapString;
import com.android.emailcommon.Logging;
import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogUtils;

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Subclass of {@link ImapString} used for literals backed by a temp file.
 */
public class ImapTempFileLiteral extends ImapString {
    /* package for test */ final File mFile;

    /** Size is purely for toString() */
    private final int mSize;

    /* package */  ImapTempFileLiteral(FixedLengthInputStream stream) throws IOException {
        mSize = stream.getLength();
        mFile = File.createTempFile("imap", ".tmp", TempDirectory.getTempDirectory());

        // Unfortunately, we can't really use deleteOnExit(), because temp filenames are random
        // so it'd simply cause a memory leak.
        // deleteOnExit() simply adds filenames to a static list and the list will never shrink.
        // mFile.deleteOnExit();
        OutputStream out = new FileOutputStream(mFile);
        IOUtils.copy(stream, out);
        out.close();
    }

    /**
     * Make sure we delete the temp file.
     *
     * We should always be calling {@link ImapResponse#destroy()}, but it's here as a last resort.
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }

    @Override
    public InputStream getAsStream() {
        checkNotDestroyed();
        try {
            return new FileInputStream(mFile);
        } catch (FileNotFoundException e) {
            // It's probably possible if we're low on storage and the system clears the cache dir.
            LogUtils.w(Logging.LOG_TAG, "ImapTempFileLiteral: Temp file not found");

            // Return 0 byte stream as a dummy...
            return new ByteArrayInputStream(new byte[0]);
        }
    }

    @Override
    public String getString() {
        checkNotDestroyed();
        try {
            byte[] bytes = IOUtils.toByteArray(getAsStream());
            // Prevent crash from OOM; we've seen this, but only rarely and not reproducibly
            if (bytes.length > ImapResponseParser.LITERAL_KEEP_IN_MEMORY_THRESHOLD) {
                throw new IOException();
            }
            return Utility.fromAscii(bytes);
        } catch (IOException e) {
            LogUtils.w(Logging.LOG_TAG, "ImapTempFileLiteral: Error while reading temp file", e);
            return "";
        }
    }

    @Override
    public void destroy() {
        try {
            if (!isDestroyed() && mFile.exists()) {
                mFile.delete();
            }
        } catch (RuntimeException re) {
            // Just log and ignore.
            LogUtils.w(Logging.LOG_TAG, "Failed to remove temp file: " + re.getMessage());
        }
        super.destroy();
    }

    @Override
    public String toString() {
        return String.format("{%d byte literal(file)}", mSize);
    }

    public boolean tempFileExistsForTest() {
        return mFile.exists();
    }
}
