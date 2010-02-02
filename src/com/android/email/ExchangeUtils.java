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

import com.android.email.service.EmailServiceProxy;
import com.android.email.service.IEmailService;
import com.android.email.service.IEmailServiceCallback;
import com.android.exchange.SyncManager;

import android.content.Context;
import android.content.Intent;

/**
 * Utility functions for Exchange support.
 */
public class ExchangeUtils {
    /**
     * Starts the service for Exchange, if supported.
     */
    public static void startExchangeService(Context context) {
        context.startService(new Intent(context, SyncManager.class));
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
        // TODO Return an empty IEmailService impl if exchange support is removed
        return new EmailServiceProxy(context, SyncManager.class, callback);
    }
}
