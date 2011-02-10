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

import com.android.email.SecurityPolicy;
import com.android.emailcommon.service.IPolicyService;
import com.android.emailcommon.service.PolicySet;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

public class PolicyService extends Service {

    private SecurityPolicy mSecurityPolicy;
    private Context mContext;

    private final IPolicyService.Stub mBinder = new IPolicyService.Stub() {
        public boolean isActive(PolicySet policies) {
            return mSecurityPolicy.isActive(policies);
        }

        public void policiesRequired(long accountId) {
            mSecurityPolicy.policiesRequired(accountId);
        }

        public void updatePolicies(long accountId) {
            mSecurityPolicy.updatePolicies(accountId);
        }

        public void setAccountHoldFlag(long accountId, boolean newState) {
            SecurityPolicy.setAccountHoldFlag(mContext, accountId, newState);
        }

        public boolean isActiveAdmin() {
            return mSecurityPolicy.isActiveAdmin();
        }

        public void remoteWipe() {
            mSecurityPolicy.remoteWipe();
        }

        public boolean isSupported(PolicySet policies) {
            return mSecurityPolicy.isSupported(policies);
        }

        public PolicySet clearUnsupportedPolicies(PolicySet policies) {
            return mSecurityPolicy.clearUnsupportedPolicies(policies);
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