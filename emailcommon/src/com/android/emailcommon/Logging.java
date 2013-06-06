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

import com.android.mail.utils.LogTag;

public class Logging {
    public static final String LOG_TAG = LogTag.getLogTag();

    /**
     * Set this to 'true' to enable as much Email logging as possible.
     */
    public static final boolean LOGD;

    /**
     * If this is enabled then logging that normally hides sensitive information
     * like passwords will show that information.
     */
    public static final boolean DEBUG_SENSITIVE;

    /**
     * If true, logging regarding UI (such as activity/fragment lifecycle) will be enabled.
     *
     * TODO rename it to DEBUG_UI.
     */
    public static final boolean DEBUG_LIFECYCLE;

    static {
        // Declare values here to avoid dead code warnings; it means we have some extra
        // "if" statements in the byte code that always evaluate to "if (false)"
        LOGD = false; // DO NOT CHECK IN WITH TRUE
        DEBUG_SENSITIVE = false; // DO NOT CHECK IN WITH TRUE
        DEBUG_LIFECYCLE = false; // DO NOT CHECK IN WITH TRUE
    }
}
