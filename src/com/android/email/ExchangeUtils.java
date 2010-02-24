/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.email;

import com.android.email.mail.MessagingException;
import com.android.email.service.EmailServiceProxy;
import com.android.email.service.IEmailService;
import com.android.email.service.IEmailServiceCallback;
import com.android.exchange.CalendarSyncEnabler;
import com.android.exchange.SyncManager;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * Utility functions for Exchange support.
 */
public class ExchangeUtils {
    /**
     * Starts the service for Exchange, if supported.
     */
    public static void startExchangeService(Context context) {
        //EXCHANGE-REMOVE-SECTION-START
        context.startService(new Intent(context, SyncManager.class));
        //EXCHANGE-REMOVE-SECTION-END
    }

    /**
     * Returns an {@link IEmailService} for the Exchange service, if supported.  Otherwise it'll
     * return an empty {@link IEmailService} implementation.
     *
     * @param context
     * @param callback Object to get callback, or can be null
     */
    public static IEmailService getExchangeEmailService(Context context,
            IEmailServiceCallback callback) {
        IEmailService ret = null;
        //EXCHANGE-REMOVE-SECTION-START
        ret = new EmailServiceProxy(context, SyncManager.class, callback);
        //EXCHANGE-REMOVE-SECTION-END
        if (ret == null) {
            ret = NullEmailService.INSTANCE;
        }
        return ret;
    }

    /**
     * Enable calendar sync for all the existing exchange accounts, and post a notification if any.
     */
    public static void enableEasCalendarSync(Context context) {
        //EXCHANGE-REMOVE-SECTION-START
        new CalendarSyncEnabler(context).enableEasCalendarSync();
        //EXCHANGE-REMOVE-SECTION-END
    }

    /**
     * An empty {@link IEmailService} implementation which is used instead of
     * {@link com.android.exchange.SyncManager} on the build with no exchange support.
     *
     * <p>In theory, the service in question isn't used on the no-exchange-support build,
     * because we won't have any exchange accounts in that case, so we wouldn't have to have this
     * class.  However, there are a few places we do use the service even if there's no exchange
     * accounts (e.g. setLogging), so this class is added for safety and simplicity.
     */
    private static class NullEmailService implements IEmailService {
        public static final NullEmailService INSTANCE = new NullEmailService();

        public Bundle autoDiscover(String userName, String password) throws RemoteException {
            return Bundle.EMPTY;
        }

        public boolean createFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        public boolean deleteFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        public void hostChanged(long accountId) throws RemoteException {
        }

        public void loadAttachment(long attachmentId, String destinationFile,
                String contentUriString) throws RemoteException {
        }

        public void loadMore(long messageId) throws RemoteException {
        }

        public boolean renameFolder(long accountId, String oldName, String newName)
                throws RemoteException {
            return false;
        }

        public void sendMeetingResponse(long messageId, int response) throws RemoteException {
        }

        public void setCallback(IEmailServiceCallback cb) throws RemoteException {
        }

        public void setLogging(int on) throws RemoteException {
        }

        public void startSync(long mailboxId) throws RemoteException {
        }

        public void stopSync(long mailboxId) throws RemoteException {
        }

        public void updateFolderList(long accountId) throws RemoteException {
        }

        public int validate(String protocol, String host, String userName, String password,
                int port, boolean ssl, boolean trustCertificates) throws RemoteException {
            return MessagingException.UNSPECIFIED_EXCEPTION;
        }

        public IBinder asBinder() {
            return null;
        }
    }
}
