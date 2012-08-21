/* Copyright (C) 2012 The Android Open Source Project.
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

package com.android.imap2;

import android.content.Context;
import android.os.RemoteException;

import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailsync.PartRequest;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.imap2.Imap2SyncService.Connection;
import com.android.mail.providers.UIProvider;

import org.apache.james.mime4j.decoder.Base64InputStream;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Handle IMAP2 attachment loading
 */
public class AttachmentLoader {
    static private final int CHUNK_SIZE = 16*1024;

    private final Context mContext;
    private final Attachment mAttachment;
    private final long mAttachmentId;
    private final long mMessageId;
    private final Message mMessage;
    private final Imap2SyncService mService;

    public AttachmentLoader(Imap2SyncService service, PartRequest req) {
        mService = service;
        mContext = service.mContext;
        mAttachment = req.mAttachment;
        mAttachmentId = mAttachment.mId;
        mMessageId = mAttachment.mMessageKey;
        mMessage = Message.restoreMessageWithId(mContext, mMessageId);
    }

    private void doStatusCallback(int status) {
        try {
            Imap2SyncManager.callback().loadAttachmentStatus(mMessageId, mAttachmentId, status, 0);
        } catch (RemoteException e) {
            // No danger if the client is no longer around
        }
    }

    private void doProgressCallback(int progress) {
        try {
            Imap2SyncManager.callback().loadAttachmentStatus(mMessageId, mAttachmentId,
                    EmailServiceStatus.IN_PROGRESS, progress);
        } catch (RemoteException e) {
            // No danger if the client is no longer around
        }
    }

    /**
     * Close, ignoring errors (as during cleanup)
     * @param c a Closeable
     */
    private void close(Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
        }
    }

    /**
     * Save away the contentUri for this Attachment and notify listeners
     * @throws IOException
     */
    private void finishLoadAttachment(File file, OutputStream os) throws IOException {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            if (mAttachment.mEncoding != null &&
                    "base64".equals(mAttachment.mEncoding.toLowerCase())) {
                in = new Base64InputStream(in);
            }
            AttachmentUtilities.saveAttachment(mContext, in, mAttachment);
            doStatusCallback(EmailServiceStatus.SUCCESS);
        } catch (FileNotFoundException e) {
            // Not bloody likely, as we just created it successfully
            throw new IOException("Attachment file not found?");
        } finally {
            close(in);
        }
    }

    private void readPart (ImapInputStream in, String tag, OutputStream out) throws IOException {
        String res = in.readLine();
        int bstart = res.indexOf("body[");
        if (bstart < 0)
            bstart = res.indexOf("BODY[");
        if (bstart < 0)
            return;
        int bend = res.indexOf(']', bstart);
        if (bend < 0)
            return;
        int br = res.indexOf('{');
        if (br > 0) {
            Parser p = new Parser(res, br + 1);
            int expectedLength = p.parseInteger();
            int remainingLength = expectedLength;
            int totalRead = 0;
            byte[] buf = new byte[CHUNK_SIZE];
            int lastCallbackPct = -1;
            int lastCallbackTotalRead = 0;
            while (remainingLength > 0) {
                int rdlen = (remainingLength > CHUNK_SIZE ? CHUNK_SIZE : remainingLength);
                int bytesRead = in.read(buf, 0, rdlen);
                totalRead += bytesRead;
                out.write(buf, 0, bytesRead);
                remainingLength -= bytesRead;
                int pct = (totalRead * 100) / expectedLength;
                // Callback only if we've read at least 1% more and have read more than CHUNK_SIZE
                // We don't want to spam the Email app
                if ((pct > lastCallbackPct) && (totalRead > (lastCallbackTotalRead + CHUNK_SIZE))) {
                    // Report progress back to the UI
                    doProgressCallback(pct);
                    lastCallbackTotalRead = totalRead;
                    lastCallbackPct = pct;
                }
            }
            out.close();
            String line = in.readLine();
            if (!line.endsWith(")")) {
                mService.errorLog("Bad part?");
                throw new IOException();
            }
            line = in.readLine();
            if (!line.startsWith(tag)) {
                mService.userLog("Bad part?");
                throw new IOException();
            }
        }
    }

    /**
     * Loads an attachment, based on the PartRequest passed in the constructor
     * @throws IOException
     */
    public void loadAttachment(Connection conn) throws IOException {
        if (mMessage == null) {
            doStatusCallback(EmailServiceStatus.MESSAGE_NOT_FOUND);
            return;
        }
        if (mAttachment.mUiState == UIProvider.AttachmentState.SAVED) {
            return;
        }
        // Say we've started loading the attachment
        doProgressCallback(0);

        try {
            OutputStream os = null;
            File tmpFile = null;
            try {
                tmpFile = File.createTempFile("imap2_", "tmp", mContext.getCacheDir());
                os = new FileOutputStream(tmpFile);
                String tag = mService.writeCommand(conn.writer, "uid fetch " + mMessage.mServerId +
                        " body[" + mAttachment.mLocation + ']');
                readPart(conn.reader, tag, os);
                finishLoadAttachment(tmpFile, os);
                return;
            } catch (FileNotFoundException e) {
                mService.errorLog("Can't get attachment; write file not found?");
                doStatusCallback(EmailServiceStatus.ATTACHMENT_NOT_FOUND);
            } finally {
                close(os);
                if (tmpFile != null) {
                    tmpFile.delete();
                }
            }
        } catch (IOException e) {
            // Report the error, but also report back to the service
            doStatusCallback(EmailServiceStatus.CONNECTION_ERROR);
            throw e;
        } finally {
        }
    }
}
