/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.mail.providers.protos.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AccountReceiver extends BroadcastReceiver {
    /**
     * Intent used to notify interested parties that the Mail provider has been created.
     */
    public static final String ACTION_PROVIDER_CREATED
            = "com.android.mail.providers.protos.boot.intent.ACTION_PROVIDER_CREATED";

    @Override
    public void onReceive(Context context, Intent intent) {
        intent.setClass(context, EmailAccountService.class);
        context.startService(intent);
    }
}
