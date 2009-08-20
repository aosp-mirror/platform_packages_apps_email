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
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.provider.EmailContent.SyncColumns;

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
        MessageColumns.MAILBOX_KEY + "=? and " + SyncColumns.SERVER_ID + "!=" + SEND_FAILED;

    public EasOutboxService(Context _context, Mailbox _mailbox) {
        super(_context, _mailbox);
        mContext = _context;
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
    void sendMessage(File cacheDir, long msgId) throws IOException, MessagingException {
        sendCallback(msgId, null, EmailServiceStatus.IN_PROGRESS);
        File tmpFile = File.createTempFile("eas_", "tmp", cacheDir);
        // Write the output to a temporary file
        try {
            FileOutputStream fileStream = new FileOutputStream(tmpFile);
            Rfc822Output.writeTo(mContext, msgId, fileStream);
            fileStream.close();
            // Now, get an input stream to our new file and create an entity with it
            FileInputStream inputStream = new FileInputStream(tmpFile);
            InputStreamEntity inputEntity =
                new InputStreamEntity(inputStream, tmpFile.length());
            // Send the post to the server
            HttpResponse resp =
                sendHttpClientPost("SendMail&SaveInSent=T", inputEntity);
            inputStream.close();
            int code = resp.getStatusLine().getStatusCode();
            if (code == HttpStatus.SC_OK) {
                userLog("Deleting message...");
                // Yes it would be marginally faster to get only the subject, but it would also
                // be more code; note, we need the subject for the callback below, since the
                // message gets deleted just below.  This allows the UI to present the subject
                // of the message in a Toast or other notification
                Message msg = Message.restoreMessageWithId(mContext, msgId);
                mContext.getContentResolver().delete(ContentUris.withAppendedId(
                        Message.CONTENT_URI, msgId), null, null);
                sendCallback(-1, msg.mSubject, EmailServiceStatus.SUCCESS);
            } else {
                ContentValues cv = new ContentValues();
                cv.put(SyncColumns.SERVER_ID, SEND_FAILED);
                Message.update(mContext, Message.CONTENT_URI, msgId, cv);
                // TODO REMOTE_EXCEPTION is temporary; add better error codes
                int result = EmailServiceStatus.REMOTE_EXCEPTION;
                if (isAuthError(code)) {
                    result = EmailServiceStatus.LOGIN_FAILED;
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
    }

    @Override
    public void run() {
        setupService();

        File cacheDir = mContext.getCacheDir();
        try {
            Cursor c = mContext.getContentResolver().query(Message.CONTENT_URI,
                    Message.ID_COLUMN_PROJECTION, MAILBOX_KEY_AND_NOT_SEND_FAILED,
                    new String[] {Long.toString(mMailbox.mId)}, null);
             try {
                while (c.moveToNext()) {
                    long msgId = c.getLong(0);
                    if (msgId != 0) {
                        sendMessage(cacheDir, msgId);
                    }
                }
            } finally {
                 c.close();
            }
        } catch (Exception e) {
            mExitStatus = EXIT_EXCEPTION;
        } finally {
            userLog(mMailbox.mDisplayName, ": sync finished");
            SyncManager.done(this);
        }
    }
}