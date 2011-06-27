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

package com.android.email.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.emailcommon.Api;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;

/**
 * Utility functions for EmailService support.
 */
public class EmailServiceUtils {
    /**
     * Starts an EmailService by name
     */
    public static void startService(Context context, String intentAction) {
        context.startService(new Intent(intentAction));
    }

    /**
     * Returns an {@link IEmailService} for the service; otherwise returns an empty
     * {@link IEmailService} implementation.
     *
     * @param context
     * @param callback Object to get callback, or can be null
     */
    public static IEmailService getService(Context context, String intentAction,
            IEmailServiceCallback callback) {
        return new EmailServiceProxy(context, intentAction, callback);
    }

    /**
     * Determine if the EmailService is available
     */
    public static boolean isServiceAvailable(Context context, String intentAction) {
        return new EmailServiceProxy(context, intentAction, null).test();
    }

    public static void startExchangeService(Context context) {
        startService(context, EmailServiceProxy.EXCHANGE_INTENT);
    }

    public static IEmailService getExchangeService(Context context,
            IEmailServiceCallback callback) {
        return getService(context, EmailServiceProxy.EXCHANGE_INTENT, callback);
    }

    public static boolean isExchangeAvailable(Context context) {
        return isServiceAvailable(context, EmailServiceProxy.EXCHANGE_INTENT);
    }

    /**
     * An empty {@link IEmailService} implementation which is used instead of
     * {@link com.android.exchange.ExchangeService} on the build with no exchange support.
     *
     * <p>In theory, the service in question isn't used on the no-exchange-support build,
     * because we won't have any exchange accounts in that case, so we wouldn't have to have this
     * class.  However, there are a few places we do use the service even if there's no exchange
     * accounts (e.g. setLogging), so this class is added for safety and simplicity.
     */
    public static class NullEmailService extends Service implements IEmailService {
        public static final NullEmailService INSTANCE = new NullEmailService();

        public int getApiLevel() {
            return Api.LEVEL;
        }

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

        public void loadAttachment(long attachmentId, boolean background) throws RemoteException {
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

        public void setLogging(int flags) throws RemoteException {
        }

        public void startSync(long mailboxId, boolean userRequest) throws RemoteException {
        }

        public void stopSync(long mailboxId) throws RemoteException {
        }

        public void updateFolderList(long accountId) throws RemoteException {
        }

        public Bundle validate(HostAuth hostAuth) throws RemoteException {
            return null;
        }

        public void deleteAccountPIMData(long accountId) throws RemoteException {
        }

        public int searchMessages(long accountId, SearchParams searchParams, long destMailboxId) {
            return 0;
        }

        public IBinder asBinder() {
            return null;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }
}
