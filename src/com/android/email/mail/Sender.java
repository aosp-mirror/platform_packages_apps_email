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

package com.android.email.mail;

import com.android.email.mail.transport.SmtpSender;

public abstract class Sender {
    protected static final int SOCKET_CONNECT_TIMEOUT = 10000;

    public synchronized static Sender getInstance(String uri) throws MessagingException {
        if (uri.startsWith("smtp")) {
            return new SmtpSender(uri);
        } else {
            throw new MessagingException("Unable to locate an applicable Transport for " + uri);
        }
    }

    public abstract void open() throws MessagingException;

    public abstract void sendMessage(Message message) throws MessagingException;

    public abstract void close() throws MessagingException;
}
