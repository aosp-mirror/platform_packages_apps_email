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

package com.android.emailcommon.mail;


public class AuthenticationFailedException extends MessagingException {
    public static final long serialVersionUID = -1;

    public AuthenticationFailedException(String message) {
        super(MessagingException.AUTHENTICATION_FAILED, message);
    }

    public AuthenticationFailedException(int exceptionType, String message) {
        super(exceptionType, message);
    }

    public AuthenticationFailedException(String message, Throwable throwable) {
        super(MessagingException.AUTHENTICATION_FAILED, message, throwable);
    }
}
