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

import java.io.IOException;
import java.io.OutputStream;

public interface Part extends Fetchable {
    public void addHeader(String name, String value) throws MessagingException;

    public void removeHeader(String name) throws MessagingException;

    public void setHeader(String name, String value) throws MessagingException;

    public Body getBody() throws MessagingException;

    public String getContentType() throws MessagingException;

    public String getDisposition() throws MessagingException;

    public String getContentId() throws MessagingException;

    public String[] getHeader(String name) throws MessagingException;

    public void setExtendedHeader(String name, String value) throws MessagingException;

    public String getExtendedHeader(String name) throws MessagingException;

    public int getSize() throws MessagingException;

    public boolean isMimeType(String mimeType) throws MessagingException;

    public String getMimeType() throws MessagingException;

    public void setBody(Body body) throws MessagingException;

    public void writeTo(OutputStream out) throws IOException, MessagingException;
}
