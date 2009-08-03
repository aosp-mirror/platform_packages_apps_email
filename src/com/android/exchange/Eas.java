/*
 * Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

package com.android.exchange;

/**
 * Constants used throughout the EAS implementation are stored here.
 *
 */
public class Eas {
    // For debugging
    public static boolean WAIT_DEBUG = false;   // DO NOT CHECK IN WITH THIS SET TO TRUE
    public static boolean DEBUG = false;        // DO NOT CHECK IN WITH THIS SET TO TRUE

    // The following two are for user logging (the second providing more detail)
    public static boolean USER_LOG = false;     // DO NOT CHECK IN WITH THIS SET TO TRUE
    public static boolean PARSER_LOG = false;   // DO NOT CHECK IN WITH THIS SET TO TRUE

    public static final String VERSION = "0.2";
    public static final String ACCOUNT_MANAGER_TYPE = "com.android.exchange";
    public static final String ACCOUNT_MAILBOX = "__eas";

    // From EAS spec
    //                Mail Cal
    // 0 No filter    Yes  Yes
    // 1 1 day ago    Yes  No
    // 2 3 days ago   Yes  No
    // 3 1 week ago   Yes  No
    // 4 2 weeks ago  Yes  Yes
    // 5 1 month ago  Yes  Yes
    // 6 3 months ago No   Yes
    // 7 6 months ago No   Yes

    public static final String FILTER_ALL = "0";
    public static final String FILTER_1_DAY = "1";
    public static final String FILTER_3_DAYS = "2";
    public static final String FILTER_1_WEEK = "3";
    public static final String FILTER_2_WEEKS = "4";
    public static final String FILTER_1_MONTH = "5";
    public static final String FILTER_3_MONTHS = "6";
    public static final String FILTER_6_MONTHS = "7";
    public static final String BODY_PREFERENCE_TEXT = "1";
    public static final String BODY_PREFERENCE_HTML = "2";

    public static final String DEFAULT_BODY_TRUNCATION_SIZE = "50000";

    public static final int FOLDER_STATUS_OK = 1;
    public static final int FOLDER_STATUS_INVALID_KEY = 9;

    public static void setUserDebug(boolean state) {
        // DEBUG takes precedence and is never true in a user build
        if (!DEBUG) {
            USER_LOG = state;
        }
     }
}
