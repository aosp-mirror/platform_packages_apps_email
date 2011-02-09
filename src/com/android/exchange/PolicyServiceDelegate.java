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

package com.android.exchange;

import com.android.email.provider.EmailContent.Account;
import com.android.emailcommon.service.PolicyServiceProxy;
import com.android.emailcommon.service.PolicySet;

import android.content.Context;
import android.os.RemoteException;

public class PolicyServiceDelegate {

    public static boolean isActive(Context context, PolicySet policies) {
        try {
            return new PolicyServiceProxy(context).isActive(policies);
        } catch (RemoteException e) {
        }
        return false;
    }

    public static void policiesRequired(Context context, long accountId) {
        try {
            new PolicyServiceProxy(context).policiesRequired(accountId);
        } catch (RemoteException e) {
            throw new IllegalStateException("PolicyService transaction failed");
        }
    }

    public static void updatePolicies(Context context, long accountId) {
        try {
            new PolicyServiceProxy(context).updatePolicies(accountId);
        } catch (RemoteException e) {
            throw new IllegalStateException("PolicyService transaction failed");
        }
    }

    public static void setAccountHoldFlag(Context context, Account account, boolean newState) {
        try {
            new PolicyServiceProxy(context).setAccountHoldFlag(account.mId, newState);
        } catch (RemoteException e) {
            throw new IllegalStateException("PolicyService transaction failed");
        }
    }

    public static boolean isActiveAdmin(Context context) {
        try {
            return new PolicyServiceProxy(context).isActiveAdmin();
        } catch (RemoteException e) {
        }
        return false;
    }

    public static void remoteWipe(Context context) {
        try {
            new PolicyServiceProxy(context).remoteWipe();
        } catch (RemoteException e) {
            throw new IllegalStateException("PolicyService transaction failed");
        }
    }

    public static boolean isSupported(Context context, PolicySet policies) {
        try {
            return new PolicyServiceProxy(context).isSupported(policies);
        } catch (RemoteException e) {
        }
        return false;
     }

    public static PolicySet clearUnsupportedPolicies(Context context, PolicySet policies) {
        try {
            return new PolicyServiceProxy(context).clearUnsupportedPolicies(policies);
        } catch (RemoteException e) {
        }
        throw new IllegalStateException("PolicyService transaction failed");
    }
}
