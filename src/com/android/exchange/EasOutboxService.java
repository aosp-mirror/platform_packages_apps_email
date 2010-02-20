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

package com.android.exchange;

import com.android.email.mail.MessagingException;
import com.android.email.mail.transport.Rfc822Output;
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.BodyColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.provider.EmailContent.SyncColumns;
import com.android.email.service.EmailServiceStatus;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.entity.InputStreamEntity;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.RemoteException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class EasOutboxService extends EasSyncService {

    public static final int SEND_FAILED = 1;
    public static final String MAILBOX_KEY_AND_NOT_SEND_FAILED =
        MessageColumns.MAILBOX_KEY + "=? and (" + SyncColumns.SERVER_ID + " is null or " +
            SyncColumns.SERVER_ID + "!=" + SEND_FAILED + ')';
    public static final String[] BODY_SOURCE_PROJECTION =
        new String[] {BodyColumns.SOURCE_MESSAGE_KEY};
    public static final String WHERE_MESSAGE_KEY = Body.MESSAGE_KEY + "=?";

    // This needs to be long enough to send the longest reasonable message, without being so long
    // as to effectively "hang" sending of mail.  The standard 30 second timeout isn't long enough
    // for pictures and the like.  For now, we'll use 15 minutes, in the knowledge that any socket
    // failure would probably generate an Exception before timing out anyway
    public static final int SEND_MAIL_TIMEOUT = 15*MINUTES;

    public EasOutboxService(Context _context, Mailbox _mailbox) {
        super(_context, _mailbox);
    }

    private void sendCallback(long msgId, String subject, int status) {
        try {
            SyncManager.callback().sendMessageStatus(msgId, subject, status, 0);
        } catch (RemoteException e) {
            // It's all good
        }
    }

    /**
     * Send a single message via EAS
     * Note that we mark messages SEND_FAILED when there is a permanent failure, rather than an
     * IOException, which is handled by SyncManager with retries, backoffs, etc.
     *
     * @param cacheDir the cache directory for this context
     * @param msgId the _id of the message to send
     * @throws IOException
     */
    int sendMessage(File cacheDir, long msgId) throws IOException, MessagingException {
        int result;
        sendCallback(msgId, null, EmailServiceStatus.IN_PROGRESS);
        File tmpFile = File.createTempFile("eas_", "tmp", cacheDir);
        // Write the output to a temporary file
        try {
            String[] cols = getRowColumns(Message.CONTENT_URI, msgId, MessageColumns.FLAGS,
                    MessageColumns.SUBJECT);
            int flags = Integer.parseInt(cols[0]);
            String subject = cols[1];

            boolean reply = (flags & Message.FLAG_TYPE_REPLY) != 0;
            boolean forward = (flags & Message.FLAG_TYPE_FORWARD) != 0;
            // The reference message and mailbox are called item and collection in EAS
            String itemId = null;
            String collectionId = null;
            if (reply || forward) {
                // First, we need to get the id of the reply/forward message
                cols = getRowColumns(Body.CONTENT_URI, BODY_SOURCE_PROJECTION,
                        WHERE_MESSAGE_KEY, new String[] {Long.toString(msgId)});
                if (cols != null) {
                    long refId = Long.parseLong(cols[0]);
                    // Then, we need the serverId and mailboxKey of the message
                    cols = getRowColumns(Message.CONTENT_URI, refId, SyncColumns.SERVER_ID,
                            MessageColumns.MAILBOX_KEY);
                    if (cols != null) {
                        itemId = cols[0];
                        long boxId = Long.parseLong(cols[1]);
                        // Then, we need the serverId of the mailbox
                        cols = getRowColumns(Mailbox.CONTENT_URI, boxId, MailboxColumns.SERVER_ID);
                        if (cols != null) {
                            collectionId = cols[0];
                        }
                    }
                }
            }

            boolean smartSend = itemId != null && collectionId != null;

            // Write the message in rfc822 format to the temporary file
            FileOutputStream fileStream = new FileOutputStream(tmpFile);
            Rfc822Output.writeTo(mContext, msgId, fileStream, !smartSend, true);
            fileStream.close();

            // Now, get an input stream to our temporary file and create an entity with it
            FileInputStream inputStream = new FileInputStream(tmpFile);
            InputStreamEntity inputEntity =
                new InputStreamEntity(inputStream, tmpFile.length());

            // Create the appropriate command and POST it to the server
            String cmd = "SendMail&SaveInSent=T";
            if (smartSend) {
                cmd = reply ? "SmartReply" : "SmartForward";
                cmd += "&ItemId=" + itemId + "&CollectionId=" + collectionId + "&SaveInSent=T";
            }
            userLog("Send cmd: " + cmd);
            HttpResponse resp = sendHttpClientPost(cmd, inputEntity, SEND_MAIL_TIMEOUT);

            inputStream.close();
            int code = resp.getStatusLine().getStatusCode();
            if (code == HttpStatus.SC_OK) {
                userLog("Deleting message...");
                mContentResolver.delete(ContentUris.withAppendedId(Message.CONTENT_URI, msgId),
                        null, null);
                result = EmailServiceStatus.SUCCESS;
                sendCallback(-1, subject, EmailServiceStatus.SUCCESS);
            } else {
                userLog("Message sending failed, code: " + code);
                ContentValues cv = new ContentValues();
                cv.put(SyncColumns.SERVER_ID, SEND_FAILED);
                Message.update(mContext, Message.CONTENT_URI, msgId, cv);
                // We mark the result as SUCCESS on a non-auth failure since the message itself is
                // already marked failed and we don't want to stop other messages from trying to
                // send.
                if (isAuthError(code)) {
                    result = EmailServiceStatus.LOGIN_FAILED;
                } else {
                    result = EmailServiceStatus.SUCCESS;
                }
                sendCallback(msgId, null, result);

            }
        } catch (IOException e) {
            // We catch this just to send the callback
            sendCallback(msgId, null, EmailServiceStatus.CONNECTION_ERROR);
            throw e;
        } finally {
            // Clean up the temporary file
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
        }
        return result;
    }

    @Override
    public void run() {
        setupService();
        File cacheDir = mContext.getCacheDir();
        try {
            mDeviceId = SyncManager.getDeviceId();
            Cursor c = mContext.getContentResolver().query(Message.CONTENT_URI,
                    Message.ID_COLUMN_PROJECTION, MAILBOX_KEY_AND_NOT_SEND_FAILED,
                    new String[] {Long.toString(mMailbox.mId)}, null);
             try {
                while (c.moveToNext()) {
                    long msgId = c.getLong(0);
                    if (msgId != 0) {
                        int result = sendMessage(cacheDir, msgId);
                        // If there's an error, it should stop the service; we will distinguish
                        // at least between login failures and everything else
                        if (result == EmailServiceStatus.LOGIN_FAILED) {
                            mExitStatus = EXIT_LOGIN_FAILURE;
                            return;
                        } else if (result == EmailServiceStatus.REMOTE_EXCEPTION) {
                            mExitStatus = EXIT_EXCEPTION;
                            return;
                        }
                    }
                }
            } finally {
                 c.close();
            }
            mExitStatus = EXIT_DONE;
        } catch (IOException e) {
            mExitStatus = EXIT_IO_ERROR;
        } catch (Exception e) {
            userLog("Exception caught in EasOutboxService", e);
            mExitStatus = EXIT_EXCEPTION;
        } finally {
            userLog(mMailbox.mDisplayName, ": sync finished");
            userLog("Outbox exited with status ", mExitStatus);
            SyncManager.done(this);
        }
    }

    /**
     * Convenience method for adding a Message to an account's outbox
     * @param context the context of the caller
     * @param accountId the accountId for the sending account
     * @param msg the message to send
     */
    public static void sendMessage(Context context, long accountId, Message msg) {
        Mailbox mailbox = Mailbox.restoreMailboxOfType(context, accountId, Mailbox.TYPE_OUTBOX);
        if (mailbox != null) {
            msg.mMailboxKey = mailbox.mId;
            msg.mAccountKey = accountId;
            msg.save(context);
        }
    }
}