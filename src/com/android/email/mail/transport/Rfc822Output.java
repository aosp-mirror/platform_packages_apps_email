/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.email.mail.transport;

import com.android.email.codec.binary.Base64;
import com.android.email.mail.Address;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.Message;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Utility class to output RFC 822 messages from provider email messages
 */
public class Rfc822Output {

    // In MIME, en_US-like date format should be used. In other words "MMM" should be encoded to
    // "Jan", not the other localized format like "Ene" (meaning January in locale es).
    static final SimpleDateFormat mDateFormat =
        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);

    /**
     * Write the entire message to an output stream.  This method provides buffering, so it is
     * not necessary to pass in a buffered output stream here.
     *
     * @param context system context for accessing the provider
     * @param messageId the message to write out
     * @param out the output stream to write the message to
     *
     * TODO is there anything in the flags fields we need to look at?
     * TODO alternative parts (e.g. text+html) are not supported here.
     */
    public static void writeTo(Context context, long messageId, OutputStream out)
            throws IOException {
        Message message = Message.restoreMessageWithId(context, messageId);
        if (message == null) {
            // throw something?
            return;
        }

        OutputStream stream = new BufferedOutputStream(out, 1024);
        Writer writer = new OutputStreamWriter(stream);

        // Write the fixed headers.  Ordering is arbitrary (the legacy code iterated through a
        // hashmap here).

        String date = mDateFormat.format(new Date(message.mTimeStamp));
        writeHeader(writer, "Date", date);

        writeEncodedHeader(writer, "Subject", message.mSubject);

        writeHeader(writer, "Message-ID", message.mMessageId);

        // Address fields.  Note, obviously, we skip bcc here
        writeAddressHeader(writer, "From", message.mFrom);
        writeAddressHeader(writer, "To", message.mTo);
        writeAddressHeader(writer, "Cc", message.mCc);
        writeAddressHeader(writer, "Reply-To", message.mReplyTo);

        // Analyze message and determine if we have multiparts
        // TODO count attachments
        boolean mixedParts = false;
        String mixedBoundary = null;

        // Simplified case for no multipart - just emit text and be done.
        if (!mixedParts) {
            String text = Body.restoreBodyTextWithMessageId(context, messageId);
            writeTextWithHeaders(writer, stream, text);
        } else {
            // continue with multipart headers, then into multipart body
            writeHeader(writer, "MIME-Version", "1.0");

            mixedBoundary = "--_com.android.email_" + System.nanoTime();
            writeHeader(writer, "Content-Type",
                    "multipart/mixed; boundary=\"" + mixedBoundary + "\"");

            // Finish headers and prepare for body section(s)
            writer.write("\r\n");

            // first multipart element is the body
            String text = Body.restoreBodyTextWithMessageId(context, messageId);
            writeTextWithHeaders(writer, stream, text);

            // TODO: attachments here

            // end of multipart section
            writeBoundary(writer, mixedBoundary, true);
        }

        writer.flush();
        out.flush();
    }

    /**
     * Write a single header with no wrapping or encoding
     *
     * @param writer the output writer
     * @param name the header name
     * @param value the header value
     */
    private static void writeHeader(Writer writer, String name, String value) throws IOException {
        if (value != null && value.length() > 0) {
            writer.append(name);
            writer.append(": ");
            writer.append(value);
            writer.append("\r\n");
        }
    }

    /**
     * Write a single header using appropriate folding & encoding
     *
     * @param writer the output writer
     * @param name the header name
     * @param value the header value
     */
    private static void writeEncodedHeader(Writer writer, String name, String value)
            throws IOException {
        if (value != null && value.length() > 0) {
            writer.append(name);
            writer.append(": ");
            writer.append(MimeUtility.foldAndEncode2(value, name.length() + 2));
            writer.append("\r\n");
        }
    }

    /**
     * Unpack, encode, and fold address(es) into a header
     *
     * @param writer the output writer
     * @param name the header name
     * @param value the header value (a packed list of addresses)
     */
    private static void writeAddressHeader(Writer writer, String name, String value)
            throws IOException {
        if (value != null && value.length() > 0) {
            writer.append(name);
            writer.append(": ");
            writer.append(MimeUtility.fold(Address.packedToHeader(value), name.length() + 2));
            writer.append("\r\n");
        }
    }

    /**
     * Write a multipart boundary
     *
     * @param writer the output writer
     * @param boundary the boundary string
     * @param end false if inner boundary, true if final boundary
     */
    private static void writeBoundary(Writer writer, String boundary, boolean end)
            throws IOException {
        writer.append("--");
        writer.append(boundary);
        if (end) {
            writer.append("--");
        }
        writer.append("\r\n");
    }

    /**
     * Write text (either as main body or inside a multipart), preceded by appropriate headers.
     *
     * Note this always uses base64, even when not required.  Slightly less efficient for
     * US-ASCII text, but handles all formats even when non-ascii chars are involved.  A small
     * optimization might be to prescan the string for safety and send raw if possible.
     *
     * @param writer the output writer
     * @param out the output stream inside the writer (used for byte[] access)
     * @param text The original text of the message
     */
    private static void writeTextWithHeaders(Writer writer, OutputStream out, String text)
            throws IOException {
        writeHeader(writer, "Content-Type", "text/plain; charset=utf-8");
        writeHeader(writer, "Content-Transfer-Encoding", "base64");
        writer.write("\r\n");

        byte[] bytes = text.getBytes("UTF-8");
        writer.flush();
        out.write(Base64.encodeBase64Chunked(bytes));
        writer.write(text);
        writer.write("\r\n");
    }
}
