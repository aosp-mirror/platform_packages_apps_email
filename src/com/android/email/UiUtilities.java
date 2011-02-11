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

package com.android.email;

import android.content.Context;
import android.content.res.Resources;

public class UiUtilities {

    /**
     * Formats the given size as a String in bytes, kB, MB or GB.  Ex: 12,315,000 = 11 MB
     */
    public static String formatSize(Context context, long size) {
        final Resources res = context.getResources();
        final long KB = 1024;
        final long MB = (KB * 1024);
        final long GB  = (MB * 1024);

        int resId;
        int value;

        if (size < KB) {
            resId = R.plurals.message_view_attachment_bytes;
            value = (int) size;
        } else if (size < MB) {
            resId = R.plurals.message_view_attachment_kilobytes;
            value = (int) (size / KB);
        } else if (size < GB) {
            resId = R.plurals.message_view_attachment_megabytes;
            value = (int) (size / MB);
        } else {
            resId = R.plurals.message_view_attachment_gigabytes;
            value = (int) (size / GB);
        }
        return res.getQuantityString(resId, value, value);
    }

    public static String getMessageCountForUi(Context context, int count,
            boolean replaceZeroWithBlank) {
        if (replaceZeroWithBlank && (count == 0)) {
            return "";
        } else if (count > 999) {
            return context.getString(R.string.more_than_999);
        } else {
            return Integer.toString(count);
        }
    }
}
