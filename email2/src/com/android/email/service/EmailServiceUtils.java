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

import android.content.Context;
import android.content.Intent;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;

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
    public static EmailServiceProxy getService(Context context, String intentAction,
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

    public static EmailServiceProxy getExchangeService(Context context,
            IEmailServiceCallback callback) {
        return getService(context, EmailServiceProxy.EXCHANGE_INTENT, callback);
    }

    public static EmailServiceProxy getImapService(Context context,
            IEmailServiceCallback callback) {
        return new EmailServiceProxy(context, ImapService.class, callback);
    }

    public static EmailServiceProxy getPop3Service(Context context,
            IEmailServiceCallback callback) {
        return new EmailServiceProxy(context, Pop3Service.class, callback);
    }

    public static boolean isExchangeAvailable(Context context) {
        return isServiceAvailable(context, EmailServiceProxy.EXCHANGE_INTENT);
    }

    /**
     * For a given account id, return a service proxy if applicable, or null.
     *
     * @param accountId the message of interest
     * @result service proxy, or null if n/a
     */
    public static EmailServiceProxy getServiceForAccount(Context context,
            IEmailServiceCallback callback, long accountId) {
        String protocol = Account.getProtocol(context, accountId);
        if (HostAuth.SCHEME_IMAP.equals(protocol)) {
            return getImapService(context, callback);
        } else if (HostAuth.SCHEME_POP3.equals(protocol)) {
            return getPop3Service(context, callback);
        } else if (HostAuth.SCHEME_EAS.equals(protocol)) {
        return getExchangeService(context, callback);
        } else {
            return null;
        }
    }
}
