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

import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.utility.Utility;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

public class MimeHeader {
    /**
     * Application specific header that contains Store specific information about an attachment.
     * In IMAP this contains the IMAP BODYSTRUCTURE part id so that the ImapStore can later
     * retrieve the attachment at will from the server.
     * The info is recorded from this header on LocalStore.appendMessages and is put back
     * into the MIME data by LocalStore.fetch.
     */
    public static final String HEADER_ANDROID_ATTACHMENT_STORE_DATA = "X-Android-Attachment-StoreData";
    /**
     * Application specific header that is used to tag body parts for quoted/forwarded messages.
     */
    public static final String HEADER_ANDROID_BODY_QUOTED_PART = "X-Android-Body-Quoted-Part";

    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";
    public static final String HEADER_CONTENT_DISPOSITION = "Content-Disposition";
    public static final String HEADER_CONTENT_ID = "Content-ID";

    /**
     * Fields that should be omitted when writing the header using writeTo()
     */
    private static final String[] WRITE_OMIT_FIELDS = {
//        HEADER_ANDROID_ATTACHMENT_DOWNLOADED,
//        HEADER_ANDROID_ATTACHMENT_ID,
        HEADER_ANDROID_ATTACHMENT_STORE_DATA
    };

    protected final ArrayList<Field> mFields = new ArrayList<Field>();

    public void clear() {
        mFields.clear();
    }

    public String getFirstHeader(String name) throws MessagingException {
        String[] header = getHeader(name);
        if (header == null) {
            return null;
        }
        return header[0];
    }

    public void addHeader(String name, String value) throws MessagingException {
        mFields.add(new Field(name, value));
    }

    public void setHeader(String name, String value) throws MessagingException {
        if (name == null || value == null) {
            return;
        }
        removeHeader(name);
        addHeader(name, value);
    }

    public String[] getHeader(String name) throws MessagingException {
        ArrayList<String> values = new ArrayList<String>();
        for (Field field : mFields) {
            if (field.name.equalsIgnoreCase(name)) {
                values.add(field.value);
            }
        }
        if (values.size() == 0) {
            return null;
        }
        return values.toArray(new String[] {});
    }

    public void removeHeader(String name) throws MessagingException {
        ArrayList<Field> removeFields = new ArrayList<Field>();
        for (Field field : mFields) {
            if (field.name.equalsIgnoreCase(name)) {
                removeFields.add(field);
            }
        }
        mFields.removeAll(removeFields);
    }

    /**
     * Write header into String
     * 
     * @return CR-NL separated header string except the headers in writeOmitFields
     * null if header is empty
     */
    public String writeToString() {
        if (mFields.size() == 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (Field field : mFields) {
            if (!Utility.arrayContains(WRITE_OMIT_FIELDS, field.name)) {
                builder.append(field.name + ": " + field.value + "\r\n");
            }
        }
        return builder.toString();
    }
    
    public void writeTo(OutputStream out) throws IOException, MessagingException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out), 1024);
        for (Field field : mFields) {
            if (!Utility.arrayContains(WRITE_OMIT_FIELDS, field.name)) {
                writer.write(field.name + ": " + field.value + "\r\n");
            }
        }
        writer.flush();
    }

    private static class Field {
        final String name;
        final String value;

        public Field(String name, String value) {
            this.name = name;
            this.value = value;
        }
        
        @Override
        public String toString() {
            return name + "=" + value;
        }
    }

    @Override
    public String toString() {
        return (mFields == null) ? null : mFields.toString();
    }
}
