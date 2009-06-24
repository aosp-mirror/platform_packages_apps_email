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

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

import com.android.exchange.EmailContent.Mailbox;

import android.content.Context;

public class EasPingService extends EasService {

    EasService mCaller;
    HttpURLConnection mConnection = null;

    public EasPingService(Context _context, Mailbox _mailbox, EasService _caller) {
        super(_context, _mailbox);
        mCaller = _caller;
        mHostAddress = _caller.mHostAddress;
        mUserName = _caller.mUserName;
        mPassword = _caller.mPassword;
    }

    class EASPingParser extends EasParser {
        protected boolean mMoreAvailable = false;

        public EASPingParser(InputStream in, EasService service) throws IOException {
            super(in);
            mMailbox = service.mMailbox;
            setDebug(true);
        }

        public void parse() throws IOException {
            int status;
            if (nextTag(START_DOCUMENT) != EasTags.PING_PING) {
                throw new IOException();
            }
            while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
                if (tag == EasTags.PING_STATUS) {
                    status = getValueInt();
                    log("Ping completed, status = " + status);
                    if (status == 1 || status == 2) {
                    }
                    mCaller.ping();
                } else {
                    skipTag();
                }
            }
        }
    }

    public void stop () {
        mConnection.disconnect();
    }

    public void run () {
        try {
            EASSerializer s = new EASSerializer();
            s.start("Ping").data("HeartbeatInterval", "900").start("PingFolders")
                .start("PingFolder").data("PingId", mMailbox.mServerId).data("PingClass", "Email")
                .end("PingFolder").end("PingFolders").end("Ping").end();
            String data = s.toString();
            HttpURLConnection uc = sendEASPostCommand("Ping", data);
            mConnection = uc;
            log("Sending ping, read timeout: " + uc.getReadTimeout() / 1000 + "s");
            int code = uc.getResponseCode();
            log("Response code: " + code);
            if (code == HttpURLConnection.HTTP_OK) {
                String encoding = uc.getHeaderField("Transfer-Encoding");
                if (encoding == null) {
                    int len = uc.getHeaderFieldInt("Content-Length", 0);
                    if (len > 0) {
                        new EASPingParser(uc.getInputStream(), this).parse();
                    }
                }
            }
        } catch (IOException e1) {
            e1.printStackTrace();
        } catch (RuntimeException e1) {
            e1.printStackTrace();
        }

        mCaller.ping();
        log(Thread.currentThread().getName() + " thread completed...");
    }
}
