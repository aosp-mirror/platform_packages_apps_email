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

package com.android.emailcommon.service;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Policy;
import com.android.mail.utils.LogUtils;

public class PolicyServiceProxy extends ServiceProxy implements IPolicyService {
    private static final boolean DEBUG_PROXY = false; // DO NOT CHECK THIS IN SET TO TRUE
    private static final String TAG = "PolicyServiceProxy";

    private IPolicyService mService = null;
    private Object mReturn = null;

    public PolicyServiceProxy(Context _context) {
        super(_context, getIntentForEmailPackage(_context, "POLICY_INTENT"));
    }

    @Override
    public void onConnected(IBinder binder) {
        mService = IPolicyService.Stub.asInterface(binder);
    }

    @Override
    public IBinder asBinder() {
        return null;
    }

    @Override
    public boolean isActive(final Policy arg0) throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException {
                mReturn = mService.isActive(arg0);
            }
        }, "isActive");
        waitForCompletion();
        if (DEBUG_PROXY) {
            LogUtils.v(TAG, "isActive: " + ((mReturn == null) ? "null" : mReturn));
        }
        if (mReturn == null) {
            // This is not a great situation, but it's better to act like the policy isn't enforced
            // rather than crash.
            LogUtils.e(TAG, "PolicyService unavailable in isActive; assuming false");
            return false;
        } else {
            return (Boolean)mReturn;
        }
    }

    @Override
    public void setAccountPolicy(final long accountId, final Policy policy,
            final String securityKey) throws RemoteException {
        setAccountPolicy2(accountId, policy, securityKey, true /* notify */);
    }

    @Override
    public void setAccountPolicy2(final long accountId, final Policy policy,
            final String securityKey, final boolean notify) throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException {
                mService.setAccountPolicy2(accountId, policy, securityKey, notify);
            }
        }, "setAccountPolicy2");
        waitForCompletion();
    }

    @Override
    public void remoteWipe() throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException {
                mService.remoteWipe();
            }
        }, "remoteWipe");
    }

    @Override
    public void setAccountHoldFlag(final long arg0, final boolean arg1) throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException {
                mService.setAccountHoldFlag(arg0, arg1);
            }
        }, "setAccountHoldFlag");
    }

    // Static methods that encapsulate the proxy calls above
    public static boolean isActive(Context context, Policy policies) {
        try {
            return new PolicyServiceProxy(context).isActive(policies);
        } catch (RemoteException e) {
        }
        return false;
    }

    public static void setAccountHoldFlag(Context context, Account account, boolean newState) {
        try {
            new PolicyServiceProxy(context).setAccountHoldFlag(account.mId, newState);
        } catch (RemoteException e) {
            throw new IllegalStateException("PolicyService transaction failed");
        }
    }

    public static void remoteWipe(Context context) {
        try {
            new PolicyServiceProxy(context).remoteWipe();
        } catch (RemoteException e) {
            throw new IllegalStateException("PolicyService transaction failed");
        }
    }

    public static void setAccountPolicy(Context context, long accountId, Policy policy,
            String securityKey) {
        setAccountPolicy2(context, accountId, policy, securityKey, true /* notify */);
    }

    public static void setAccountPolicy2(Context context, long accountId, Policy policy,
            String securityKey, boolean notify) {
        try {
            new PolicyServiceProxy(context).setAccountPolicy2(accountId, policy, securityKey,
                    notify);
            return;
        } catch (RemoteException e) {
        }
        throw new IllegalStateException("PolicyService transaction failed");
    }
}

