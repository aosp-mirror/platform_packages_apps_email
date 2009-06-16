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

import java.net.HttpURLConnection;

import com.android.email.provider.EmailContent;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

public class EasOutboxService extends EasService {

    public EasOutboxService(Context _context, EmailContent.Mailbox _mailbox) {
        super(_context, _mailbox);
        mContext = _context;
        EmailContent.HostAuth ha = 
            EmailContent.HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
        mHostAddress = ha.mAddress;
        mUserName = ha.mLogin;
        mPassword = ha.mPassword;
    }

    public void run () {
        mThread = Thread.currentThread();
        String uniqueId = android.provider.Settings.System.getString(mContext.getContentResolver(), 
                android.provider.Settings.System.ANDROID_ID);
        try {
            Cursor c = mContext.getContentResolver().query(EmailContent.Message.CONTENT_URI, 
                    EmailContent.Message.CONTENT_PROJECTION, "mMailbox=" + mMailbox, null, null);
            try {
                if (c.moveToFirst()) {
                    EmailContent.Message msg = new EmailContent.Message().restore(c);
                    if (msg != null) {
                        String data = Rfc822Formatter
                        .writeEmailAsRfc822String(mContext, mAccount, msg, uniqueId);
                        HttpURLConnection uc = sendEASPostCommand("SendMail&SaveInSent=T", data);
                        int code = uc.getResponseCode();
                        //Intent intent = new Intent(MessageListView.MAIL_UPDATE);
                        //intent.putExtra("type", "toast");
                        if (code == HttpURLConnection.HTTP_OK) {
                            //intent.putExtra("text", "Your message with subject \"" + msg.mSubject + "\" has been sent.");
                            log("Deleting message...");
                            mContext.getContentResolver().delete(ContentUris.withAppendedId(
                                    EmailContent.Message.CONTENT_URI, msg.mId), null, null);
                        } else {
                            ContentValues cv = new ContentValues();
                            cv.put("uid", 1);
                            EmailContent.Message.update(mContext, 
                                    EmailContent.Message.CONTENT_URI, msg.mId, cv);
                            //intent.putExtra("text", "WHOA!  Your message with subject \"" + msg.mSubject + "\" failed to send.");
                        }
                        //mContext.sendBroadcast(intent);
                        updateUI();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                c.close();
            }
        } catch (RuntimeException e1) {
            e1.printStackTrace();
        }
    }
}