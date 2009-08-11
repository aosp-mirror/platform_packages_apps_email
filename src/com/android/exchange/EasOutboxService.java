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

import com.android.email.mail.transport.Rfc822Output;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.provider.EmailContent.SyncColumns;

import org.apache.http.HttpResponse;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;

public class EasOutboxService extends EasSyncService {

    public EasOutboxService(Context _context, Mailbox _mailbox) {
        super(_context, _mailbox);
        mContext = _context;
        HostAuth ha = HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
        mHostAddress = ha.mAddress;
        mUserName = ha.mLogin;
        mPassword = ha.mPassword;
    }

    @Override
    public void run() {
        mThread = Thread.currentThread();
        try {
            Cursor c = mContext.getContentResolver().query(Message.CONTENT_URI,
                    Message.CONTENT_PROJECTION, MessageColumns.MAILBOX_KEY + '=' + mMailbox.mId,
                    null, null);
            try {
                while (c.moveToNext()) {
                    Message msg = new Message().restore(c);
                    if (msg != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
                        Rfc822Output.writeTo(mContext, msg.mId, baos);
                        HttpResponse resp =
                            sendHttpClientPost("SendMail&SaveInSent=T", baos.toByteArray());
                        int code = resp.getStatusLine().getStatusCode();
                        if (code == HttpURLConnection.HTTP_OK) {
                            userLog("Deleting message...");
                            mContext.getContentResolver().delete(ContentUris.withAppendedId(
                                    Message.CONTENT_URI, msg.mId), null, null);
                        } else {
                            ContentValues cv = new ContentValues();
                            cv.put(SyncColumns.SERVER_ID, 1);
                            Message.update(mContext, Message.CONTENT_URI, msg.mId, cv);
                        }
                        // TODO How will the user know that the message sent or not?
                    }
                }
            } finally {
                c.close();
            }
        } catch (IOException e) {
            userLog("Caught IOException");
            mExitStatus = EXIT_IO_ERROR;
        } catch (Exception e) {
            mExitStatus = EXIT_EXCEPTION;
        } finally {
            userLog(mMailbox.mDisplayName + ": sync finished");
            SyncManager.done(this);
        }
    }
}