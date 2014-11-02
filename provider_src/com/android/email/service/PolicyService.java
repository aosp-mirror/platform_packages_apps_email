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
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.email.SecurityPolicy;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.service.IPolicyService;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;

public class PolicyService extends Service {
    private static final String LOG_TAG = LogTag.getLogTag();

    private SecurityPolicy mSecurityPolicy;
    private Context mContext;

    private final IPolicyService.Stub mBinder = new IPolicyService.Stub() {
        @Override
        public boolean isActive(Policy policy) {
            try {
                return mSecurityPolicy.isActive(policy);
            } catch (RuntimeException e) {
                // Catch, log and rethrow the exception, as otherwise when the exception is
                // ultimately handled, the complete stack trace is losk
                LogUtils.e(LOG_TAG, e, "Exception thrown during call to SecurityPolicy#isActive");
                throw e;
            }
        }

        @Override
        public void setAccountHoldFlag(long accountId, boolean newState) {
            SecurityPolicy.setAccountHoldFlag(mContext, accountId, newState);
        }

        @Override
        public void remoteWipe() {
            try {
                mSecurityPolicy.remoteWipe();
            } catch (RuntimeException e) {
                // Catch, log and rethrow the exception, as otherwise when the exception is
                // ultimately handled, the complete stack trace is losk
                LogUtils.e(LOG_TAG, e, "Exception thrown during call to SecurityPolicy#remoteWipe");
                throw e;
            }
        }

        @Override
        public void setAccountPolicy(long accountId, Policy policy, String securityKey) {
            setAccountPolicy2(accountId, policy, securityKey, true /* notify */);
        }

        @Override
        public void setAccountPolicy2(long accountId, Policy policy, String securityKey,
                boolean notify) {
            try {
                mSecurityPolicy.setAccountPolicy(accountId, policy, securityKey, notify);
            } catch (RuntimeException e) {
                // Catch, log and rethrow the exception, as otherwise when the exception is
                // ultimately handled, the complete stack trace is losk
                LogUtils.e(LOG_TAG, e,
                        "Exception thrown from call to SecurityPolicy#setAccountPolicy");
                throw e;
            }
        }

        public boolean canDisableCamera() {
            // TODO: This is not a clean way to do this, but there is not currently
            // any api that can answer the question "will disabling the camera work?"
            // We need to answer this question here so that we can tell the server what
            // policies we are able to support, and only apply them after it confirms that
            // our partial support is acceptable.
            DevicePolicyManager dpm =
                    (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
            final ComponentName adminName = new ComponentName(mContext, SecurityPolicy.PolicyAdmin.class);
            final boolean cameraDisabled = dpm.getCameraDisabled(adminName);
            if (cameraDisabled) {
                // The camera is already disabled, by this admin.
                // Apparently we can support disabling the camera.
                return true;
            } else {
                try {
                    dpm.setCameraDisabled(adminName, true);
                    dpm.setCameraDisabled(adminName, false);
                } catch (SecurityException e) {
                    // Apparently we cannot support disabling the camera.
                    LogUtils.w(LOG_TAG, "SecurityException checking camera disabling.");
                    return false;
                }
            }
            return true;
        }

    };

    @Override
    public IBinder onBind(Intent intent) {
        // When we bind this service, save the context and SecurityPolicy singleton
        mContext = this;
        mSecurityPolicy = SecurityPolicy.getInstance(this);
        return mBinder;
    }
}