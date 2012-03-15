/*
 *  Copyright (C) 2008-2009 Marc Blank
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

package com.android.emailcommon.service;

/**
 * Definitions of service status codes returned to IEmailServiceCallback's status method
 */
public interface EmailServiceStatus {
    public static final int SUCCESS = 0;
    public static final int IN_PROGRESS = 1;

    public static final int MESSAGE_NOT_FOUND = 0x10;
    public static final int ATTACHMENT_NOT_FOUND = 0x11;
    public static final int FOLDER_NOT_DELETED = 0x12;
    public static final int FOLDER_NOT_RENAMED = 0x13;
    public static final int FOLDER_NOT_CREATED = 0x14;
    public static final int REMOTE_EXCEPTION = 0x15;
    public static final int LOGIN_FAILED = 0x16;
    public static final int SECURITY_FAILURE = 0x17;
    public static final int ACCOUNT_UNINITIALIZED = 0x18;
    public static final int ACCESS_DENIED = 0x19;

    // Maybe we should automatically retry these?
    public static final int CONNECTION_ERROR = 0x20;

    // Client certificates used to authenticate cannot be retrieved from the system.
    public static final int CLIENT_CERTIFICATE_ERROR = 0x21;
}
