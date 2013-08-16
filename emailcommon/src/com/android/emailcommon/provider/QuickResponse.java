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


package com.android.emailcommon.provider;

import android.net.Uri;

import com.android.emailcommon.provider.EmailContent.QuickResponseColumns;

/**
 * A user-modifiable message that may be quickly inserted into the body while user is composing
 * a message. Tied to a specific account.
 */
public abstract class QuickResponse extends EmailContent
        implements QuickResponseColumns {
    public static final String TABLE_NAME = "QuickResponse";
    public static Uri CONTENT_URI;
    public static Uri ACCOUNT_ID_URI;

    public static void initQuickResponse() {
        CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/quickresponse");
        ACCOUNT_ID_URI = Uri.parse(EmailContent.CONTENT_URI + "/quickresponse/account");
    }
}