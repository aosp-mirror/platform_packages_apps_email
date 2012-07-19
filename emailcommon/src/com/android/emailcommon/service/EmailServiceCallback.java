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

package com.android.emailcommon.service;

import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.android.emailcommon.service.IEmailServiceCallback.Stub;

public class EmailServiceCallback extends Stub {

    private final RemoteCallbackList<IEmailServiceCallback> mCallbackList;

    public EmailServiceCallback(RemoteCallbackList<IEmailServiceCallback> callbackList) {
        mCallbackList = callbackList;
    }
    /**
     * Broadcast a callback to the everyone that's registered
     *
     * @param wrapper the ServiceCallbackWrapper used in the broadcast
     */
    private synchronized void broadcastCallback(ServiceCallbackWrapper wrapper) {
        RemoteCallbackList<IEmailServiceCallback> callbackList = mCallbackList;
        if (callbackList != null) {
            // Call everyone on our callback list
            int count = callbackList.beginBroadcast();
            try {
                for (int i = 0; i < count; i++) {
                    try {
                        wrapper.call(callbackList.getBroadcastItem(i));
                    } catch (RemoteException e) {
                        // Safe to ignore
                    } catch (RuntimeException e) {
                        // We don't want an exception in one call to prevent other calls, so
                        // we'll just log this and continue
                        Log.e("EmailServiceCallback", "Caught RuntimeException in broadcast", e);
                    }
                }
            } finally {
                // No matter what, we need to finish the broadcast
                callbackList.finishBroadcast();
            }
        }
    }

    @Override
    public void loadAttachmentStatus(final long messageId, final long attachmentId,
            final int status, final int progress) {
        broadcastCallback(new ServiceCallbackWrapper() {
            @Override
            public void call(IEmailServiceCallback cb) throws RemoteException {
                cb.loadAttachmentStatus(messageId, attachmentId, status, progress);
            }
        });
    }

    @Override
    public void loadMessageStatus(final long messageId, final int status, final int progress) {
        broadcastCallback(new ServiceCallbackWrapper() {
            @Override
            public void call(IEmailServiceCallback cb) throws RemoteException {
                cb.loadMessageStatus(messageId, status, progress);
            }
        });
    }

    @Override
    public void sendMessageStatus(final long messageId, final String subject, final int status,
            final int progress) {
        broadcastCallback(new ServiceCallbackWrapper() {
            @Override
            public void call(IEmailServiceCallback cb) throws RemoteException {
                cb.sendMessageStatus(messageId, subject, status, progress);
            }
        });
    }

    @Override
    public void syncMailboxListStatus(final long accountId, final int status,
            final int progress) {
        broadcastCallback(new ServiceCallbackWrapper() {
            @Override
            public void call(IEmailServiceCallback cb) throws RemoteException {
                cb.syncMailboxListStatus(accountId, status, progress);
            }
        });
    }

    @Override
    public void syncMailboxStatus(final long mailboxId, final int status,
            final int progress) {
        broadcastCallback(new ServiceCallbackWrapper() {
            @Override
            public void call(IEmailServiceCallback cb) throws RemoteException {
                cb.syncMailboxStatus(mailboxId, status, progress);
            }
        });
    }

    private interface ServiceCallbackWrapper {
        public void call(IEmailServiceCallback cb) throws RemoteException;
    }
}
