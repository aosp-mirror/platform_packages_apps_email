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

package com.android.exchange.utility;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.android.exchange.EmailContent.Account;
import com.android.exchange.EmailContent.Attachment;
import com.android.exchange.EmailContent.Body;
import com.android.exchange.EmailContent.Message;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.Html;
import android.text.SpannedString;

/**
 * Generates RFC822 formatted message data from a Message object.  This functionality is also needed
 * by the SMTP code, so we should use a single piece of code for this purpose.  stadler is currently
 * planning on rewriting SMTP code to handle this task, and we will use that code when it is ready.
 *
 */
public class Rfc822Formatter {

    static final SimpleDateFormat rfc822DateFormat = new SimpleDateFormat("dd MMM yy HH:mm:ss Z");
    static final SimpleDateFormat friendlyDateFormat = new SimpleDateFormat("MMM dd, yyyy");
    static final SimpleDateFormat friendlyTimeFormat = new SimpleDateFormat("hh:mm a");

    static public final String HTML_DRAFT_HEADER = "<!--AMDraft-->";
    static public final String HTML_REPLY_HEADER = "<!--AMReply-->";

    static final String CRLF = "\r\n";

    static public String writeEmailAsRfc822String(Context context, Account acct, 
            Message msg, String uniqueId) throws IOException {
        StringWriter w = new StringWriter();
        writeEmailAsRfc822(context, acct, msg, w, uniqueId);
        return w.toString();
    }

    static public boolean writeEmailAsRfc822(Context context, Account acct, 
            Message msg, Writer writer, String uniqueId) throws IOException {
        // For now, multi-part alternative means an HTML reply...
        boolean alternativeParts = false;

        Uri u = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, msg.mId);
        Cursor c = context.getContentResolver().query(u, Attachment.CONTENT_PROJECTION, 
                null, null, null);
        try {
            if (c.moveToFirst()) {
                // Loop through attachments now
            }
        } finally {
            c.close();
        }
 
        boolean mixedParts = false; //(!msg.attachments.isEmpty());
        Message reply = null;
        boolean forward = false;

        long referenceId = msg.mReferenceKey;
        if (referenceId != 0) {
            if (referenceId < 0) {
                referenceId = 0 - referenceId;
                forward = true;
            }
            reply = Message.restoreMessageWithId(context, referenceId);
            alternativeParts = true;
        }

        String date = rfc822DateFormat.format(new Date());
        String alternativeBoundary = null;
        String mixedBoundary = null;

        writeHeader(writer, "Date", date); //"6 Jan 09 12:34:55 PST");

        String displayName = acct.mDisplayName;
        if (displayName == null || displayName.length() == 0) {
            writeHeader(writer, "From", acct.mEmailAddress);
        } else
            writeHeader(writer, "From", displayName + " <" + acct.mEmailAddress + '>');

        writeHeader(writer, "Subject", msg.mSubject);
        // TODO Be consistent with SMTP
        writeHeader(writer, "Message-ID", "<" + (uniqueId == null ? "emu" : uniqueId) + '.' + 
                System.currentTimeMillis() + "@AndroidMail.com");

        String to = msg.mTo;
        writeHeaderAddress(writer, "To", to);
        writeHeaderAddress(writer, "Cc", msg.mCc);
        writeHeader(writer, "X-Mailer", "AndroidMail v0.3.x");

        if (mixedParts || alternativeParts)
            writeHeader(writer, "MIME-Version", "1.0");


        if (mixedParts) {
            mixedBoundary = "--=_AndroidMail_mixed_boundary_" + System.nanoTime();
            writeHeader(writer, "Content-Type",  "multipart/mixed; boundary=\"" 
                    + mixedBoundary + "\"");
            writer.write(CRLF);
        }

        if (alternativeParts) {
            alternativeBoundary = "--=_AndroidMail_alternative_boundary_" + System.nanoTime();
            writeHeader(writer, "Content-Type",  "multipart/alternative; boundary=\"" 
                    + alternativeBoundary + "\"");
            writer.write(CRLF);
        }

        // Write text part
        if (alternativeParts) {
            writeBoundary(writer, alternativeBoundary, false);
        } else if (mixedParts) {
            writeBoundary(writer, mixedBoundary, false);
        }

        writeHeader(writer, "Content-Type", "text/plain; charset=us-ascii");
        writeHeader(writer, "Content-Transfer-Encoding", "7bit");
        writer.write(CRLF);

        String text = Body.restoreBodyTextWithMessageId(context, msg.mId);
        writeWithCRLF(writer, text);

        if (alternativeParts) {
            writeBoundary(writer, alternativeBoundary, false);
            writeHeader(writer, "Content-Type", "text/html; charset=us-ascii");
            writeHeader(writer, "Content-Transfer-Encoding", "quoted-printable");
            writer.write(CRLF);
            String html = Html.toHtml(new SpannedString(text));
            writeWithCRLF(writer, HTML_DRAFT_HEADER);
            writeWithCRLF(writer, html);
            writeWithCRLF(writer, HTML_REPLY_HEADER);
            if (reply != null) {
                html = Html.toHtml(new SpannedString(forward ? createForwardIntro(reply) : 
                    createReplyIntro(reply)));
                writeWithCRLF(writer, html);
                String replyText = "Body of reply text"; //Messages.getBody(context, reply);
                if (msg.mHtmlInfo == null)
                    replyText = Html.toHtml(new SpannedString(replyText));
                writeWithCRLF(writer, QuotedPrintable.encode(replyText));
            }
            writeBoundary(writer, alternativeBoundary, true);
        }

        if (mixedParts) {
            for (Attachment att: msg.mAttachments) {
                writeBoundary(writer, mixedBoundary, false);
                writeHeader(writer, "Content-Type", att.mMimeType);
                writeHeader(writer, "Content-Transfer-Encoding", "base64");
                String name = att.mContentUri;
                if (name == null || name.length() == 0)
                    name = "picture.jpg";
                writeHeader(writer, "Content-Disposition", "attachment; filename=\"" + name + "\"");
                writer.write(CRLF);

                // Write content of attachment here...
                String fn = att.mFileName;
                Uri uri = Uri.parse(fn);

                InputStream inputStream = null;
                if (fn.startsWith("/")) {
                    File f = new File(fn);
                    if (f.exists())
                        inputStream = new FileInputStream(f);
                }
                else
                    inputStream = context.getContentResolver().openInputStream(uri);

                if (inputStream != null) {
                    Base64.encodeInputStreamToWriter(inputStream, writer);
                    inputStream.close();
                }
                inputStream.close();
                writer.write(CRLF);
            }
            writeBoundary(writer, mixedBoundary, true);
        }

        writer.flush();
        return true;
    }

    protected static String createReplyIntro (Message msg) {
        StringBuilder sb = new StringBuilder(2048);
        Date d = new Date(msg.mTimeStamp);
        sb.append("\n\n-----\nOn ");
        sb.append(friendlyDateFormat.format(d));
        sb.append(", at ");
        sb.append(friendlyTimeFormat.format(d).toLowerCase());
        sb.append(", ");
        sb.append(msg.mSender);
        sb.append(" wrote: \n\n");
        return sb.toString();
    }

    protected static String createForwardIntro (Message msg) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("\n\nBegin forwarded message:\n\n");
        sb.append("From: ");
        sb.append(msg.mFrom);
        sb.append("\nTo: ");
        sb.append(msg.mTo);
        sb.append("\nDate: ");
        Date d = new Date(msg.mTimeStamp);
        sb.append(friendlyDateFormat.format(d));
        sb.append(", at ");
        sb.append(friendlyTimeFormat.format(d).toLowerCase());
        sb.append("\nSubject: ");
        sb.append(msg.mSubject);
        sb.append("\n\n");
        return sb.toString();
    }

    private static void writeBoundary (Writer w, String boundary, boolean last) throws IOException {
        w.write("--");
        w.write(boundary);
        if (last)
            w.write("--");
        w.write("\r\n");
    }

    private static void writeWithCRLF (Writer w, String stuff) throws IOException {
        w.write(stuff);
        w.write("\r\n");
    }

    private static void writeHeaderAddress (Writer w, String header, String addressList) 
    throws IOException {
        if (addressList != null && addressList.length() > 0)
            writeHeader(w, header, addressList);
    }

    private static void writeHeader (Writer w, String header, String value) throws IOException {
        w.write(header);
        w.write(": ");
        w.write(value);
        w.write("\r\n");
    }

}
