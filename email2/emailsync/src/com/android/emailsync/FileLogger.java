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

package com.android.emailsync;

import android.content.Context;
import android.os.Environment;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;

public class FileLogger {
    private static FileLogger LOGGER = null;
    private static FileWriter sLogWriter = null;
    public static String LOG_FILE_NAME =
        Environment.getExternalStorageDirectory() + "/emaillog.txt";

    public synchronized static FileLogger getLogger (Context c) {
        LOGGER = new FileLogger();
        return LOGGER;
    }

    private FileLogger() {
        try {
            sLogWriter = new FileWriter(LOG_FILE_NAME, true);
        } catch (IOException e) {
            // Doesn't matter
        }
    }

    static public synchronized void close() {
        if (sLogWriter != null) {
            try {
                sLogWriter.close();
            } catch (IOException e) {
                // Doesn't matter
            }
            sLogWriter = null;
        }
    }

    static public synchronized void log(Exception e) {
        if (sLogWriter != null) {
            log("Exception", "Stack trace follows...");
            PrintWriter pw = new PrintWriter(sLogWriter);
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

        if (sLogWriter != null) {
            try {
                sLogWriter.write(s);
                sLogWriter.flush();
            } catch (IOException e) {
                // Something might have happened to the sdcard
                if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                    // If the card is mounted and we can create the writer, retry
                    LOGGER = new FileLogger();
                    if (sLogWriter != null) {
                        try {
                            log("FileLogger", "Exception writing log; recreating...");
                            log(prefix, str);
                        } catch (Exception e1) {
                            // Nothing to do at this point
                        }
                    }
                }
            }
        }
    }
}
