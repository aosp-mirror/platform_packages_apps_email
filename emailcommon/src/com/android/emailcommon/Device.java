/*
 /*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.emailcommon;

import android.content.Context;
import android.telephony.TelephonyManager;

import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class Device {
    private static String sDeviceId = null;

    /**
     * EAS requires a unique device id, so that sync is possible from a variety of different
     * devices (e.g. the syncKey is specific to a device)  If we're on an emulator or some other
     * device that doesn't provide one, we can create it as android<n> where <n> is system time.
     * This would work on a real device as well, but it would be better to use the "real" id if
     * it's available
     */
    static public synchronized String getDeviceId(Context context) throws IOException {
        if (sDeviceId == null) {
            sDeviceId = getDeviceIdInternal(context);
        }
        return sDeviceId;
    }

    static private String getDeviceIdInternal(Context context) throws IOException {
        if (context == null) {
            throw new IllegalStateException("getDeviceId requires a Context");
        }
        File f = context.getFileStreamPath("deviceName");
        BufferedReader rdr = null;
        String id;
        if (f.exists()) {
            if (f.canRead()) {
                rdr = new BufferedReader(new FileReader(f), 128);
                id = rdr.readLine();
                rdr.close();
                if (id == null) {
                    // It's very bad if we read a null device id; let's delete that file
                    if (!f.delete()) {
                        LogUtils.e(Logging.LOG_TAG,
                                "Can't delete null deviceName file; try overwrite.");
                    }
                } else {
                    return id;
                }
            } else {
                LogUtils.w(Logging.LOG_TAG, f.getAbsolutePath() + ": File exists, but can't read?" +
                    "  Trying to remove.");
                if (!f.delete()) {
                    LogUtils.w(Logging.LOG_TAG, "Remove failed. Tring to overwrite.");
                }
            }
        }
        BufferedWriter w = new BufferedWriter(new FileWriter(f), 128);
        final String consistentDeviceId = getConsistentDeviceId(context);
        if (consistentDeviceId != null) {
            // Use different prefix from random IDs.
            id = "androidc" + consistentDeviceId;
        } else {
            id = "android" + System.currentTimeMillis();
        }
        w.write(id);
        w.close();
        return id;
    }

    /**
     * @return Device's unique ID if available.  null if the device has no unique ID.
     */
    public static String getConsistentDeviceId(Context context) {
        final String deviceId;
        try {
            TelephonyManager tm =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) {
                return null;
            }
            deviceId = tm.getDeviceId();
            if (deviceId == null) {
                return null;
            }
        } catch (Exception e) {
            LogUtils.d(Logging.LOG_TAG, "Error in TelephonyManager.getDeviceId(): "
                    + e.getMessage());
            return null;
        }
        return Utility.getSmallHash(deviceId);
    }
}
