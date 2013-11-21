/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Intent;

import com.android.mail.MailIntentService;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;

/**
 * A service to handle various intents asynchronously.
 */
public class EmailIntentService extends MailIntentService {
    private static final String LOG_TAG = LogTag.getLogTag();

    public EmailIntentService() {
        super("EmailIntentService");
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        super.onHandleIntent(intent);

        if (UIProvider.ACTION_UPDATE_NOTIFICATION.equals(intent.getAction())) {
            NotificationController.handleUpdateNotificationIntent(this, intent);
        }

        LogUtils.v(LOG_TAG, "Handling intent %s", intent);
    }
}
