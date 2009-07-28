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

import com.android.email.provider.EmailContent.Attachment;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * PartRequest is the EAS wrapper for attachment loading requests.  In addition to information about
 * the attachment to be loaded, it also contains the callback to be used for status/progress
 * updates to the UI.
 */
public class PartRequest {
    public long timeStamp;
    public long emailId;
    public Attachment att;
    public String destination;
    public String contentUriString;
    public String loc;
    public IEmailServiceCallback callback;

    static IEmailServiceCallback sCallback = new IEmailServiceCallback () {

        /* (non-Javadoc)
         * @see com.android.exchange.IEmailServiceCallback#status(int, int)
         */
        public void status(long messageId, long attachmentId, int statusCode, int progress)
                throws RemoteException {
            // This is a placeholder, so that all PartRequests have a callback (prevents a lot of
            // useless checking in the sync service).  When debugging, logs the status and progress
            // of the download.
            if (Eas.TEST_DEBUG) {
                Log.d("PartRequestStatus", "Message " + messageId + ", Attachment " + attachmentId
                        + ", Code " + statusCode + ", progress " + progress);
            }
        }

        public IBinder asBinder() { return null; }
    };

    public PartRequest(Attachment _att) {
        timeStamp = System.currentTimeMillis();
        emailId = _att.mMessageKey;
        att = _att;
        loc = att.mLocation;
        callback = sCallback;
    }

    public PartRequest(Attachment _att, String _destination, String _contentUriString,
            IEmailServiceCallback _callback) {
        this(_att);
        destination = _destination;
        contentUriString = _contentUriString;
        callback = _callback;
    }
}
