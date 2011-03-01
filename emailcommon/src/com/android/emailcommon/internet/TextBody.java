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

package com.android.emailcommon.internet;

import com.android.emailcommon.mail.Body;
import com.android.emailcommon.mail.MessagingException;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;

public class TextBody implements Body {
    String mBody;

    public TextBody(String body) {
        this.mBody = body;
    }

    public void writeTo(OutputStream out) throws IOException, MessagingException {
        byte[] bytes = mBody.getBytes("UTF-8");
        out.write(Base64.encode(bytes, Base64.CRLF));
    }

    /**
     * Get the text of the body in it's unencoded format.
     * @return
     */
    public String getText() {
        return mBody;
    }

    /**
     * Returns an InputStream that reads this body's text in UTF-8 format.
     */
    public InputStream getInputStream() throws MessagingException {
        try {
            byte[] b = mBody.getBytes("UTF-8");
            return new ByteArrayInputStream(b);
        }
        catch (UnsupportedEncodingException usee) {
            return null;
        }
    }
}
