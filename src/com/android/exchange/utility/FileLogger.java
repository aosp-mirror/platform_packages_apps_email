/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.exchange.utility;

import android.content.Context;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;

public class FileLogger {
    private static FileLogger LOGGER = null;
    private static FileWriter mLogWriter = null;
    public static String LOG_FILE_NAME = "/sdcard/emaillog.txt";

    public synchronized static FileLogger getLogger (Context c) {
        LOGGER = new FileLogger();
        return LOGGER;
    }

    private FileLogger() {
        try {
            mLogWriter = new FileWriter(LOG_FILE_NAME, true);
        } catch (IOException e) {
            // Doesn't matter
        }
    }

    static public synchronized void close() {
        if (mLogWriter != null) {
            try {
                mLogWriter.close();
            } catch (IOException e) {
                // Doesn't matter
            }
            mLogWriter = null;
        }
    }

    static public synchronized void log(Exception e) {
        if (mLogWriter != null) {
            log("Exception", "Stack trace follows...");
            PrintWriter pw = new PrintWriter(mLogWriter);
            e.printStackTrace(pw);
            pw.flush();
        }
    }

    @SuppressWarnings("deprecation")
    static public synchronized void log(String prefix, String str) {
        if (LOGGER == null) {
            LOGGER = new FileLogger();
            log("Logger", "\r\n\r\n --- New Log ---");
        }
        Date d = new Date();
        int hr = d.getHours();
        int min = d.getMinutes();
        int sec = d.getSeconds();

        // I don't use DateFormat here because (in my experience), it's much slower
        StringBuffer sb = new StringBuffer(256);
        sb.append('[');
        sb.append(hr);
        sb.append(':');
        if (min < 10)
            sb.append('0');
        sb.append(min);
        sb.append(':');
        if (sec < 10) {
            sb.append('0');
        }
        sb.append(sec);
        sb.append("] ");
        if (prefix != null) {
            sb.append(prefix);
            sb.append("| ");
        }
        sb.append(str);
        sb.append("\r\n");
        String s = sb.toString();

        if (mLogWriter != null) {
            try {
                mLogWriter.write(s);
                mLogWriter.flush();
            } catch (IOException e) {
                // Doesn't matter
            }
        }
    }
}
