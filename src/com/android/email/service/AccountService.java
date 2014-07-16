/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.email.NotificationController;
import com.android.email.ResourceHelper;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Configuration;
import com.android.emailcommon.Device;
import com.android.emailcommon.VendorPolicyLoader;
import com.android.emailcommon.service.IAccountService;
import com.android.emailcommon.utility.EmailAsyncTask;

import java.io.IOException;

public class AccountService extends Service {

    // Save context
    private Context mContext;

    private final IAccountService.Stub mBinder = new IAccountService.Stub() {

        @Override
        public int getAccountColor(long accountId) {
            return ResourceHelper.getInstance(mContext).getAccountColor(accountId);
        }

        @Override
        public Bundle getConfigurationData(String accountType) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(Configuration.EXCHANGE_CONFIGURATION_USE_ALTERNATE_STRINGS,
                    VendorPolicyLoader.getInstance(mContext).useAlternateExchangeStrings());
            return bundle;
        }

        @Override
        public String getDeviceId() {
            try {
                EmailAsyncTask.runAsyncSerial(new Runnable() {
                    @Override
                    public void run() {
                        // Make sure remote services are running (re: lifecycle)
                        EmailServiceUtils.startRemoteServices(mContext);
                        // Send current logging flags
                        MailActivityEmail.updateLoggingFlags(mContext);
                    }});
                return Device.getDeviceId(mContext);
            } catch (IOException e) {
                return null;
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        if (mContext == null) {
            mContext = this;
        }
        // Make sure we have a valid deviceId (just retrieves a static String except first time)
        try {
            Device.getDeviceId(this);
        } catch (IOException e) {
        }
        return mBinder;
    }
}
