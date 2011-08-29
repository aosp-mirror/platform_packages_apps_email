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
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Policy;

public class PolicyServiceProxy extends ServiceProxy implements IPolicyService {
    private static final boolean DEBUG_PROXY = false; // DO NOT CHECK THIS IN SET TO TRUE
    private static final String TAG = "PolicyServiceProxy";

    // The intent used by sync adapter services to connect to the PolicyService
    public static final String POLICY_INTENT = "com.android.email.POLICY_INTENT";

    private IPolicyService mService = null;
    private Object mReturn = null;

    public PolicyServiceProxy(Context _context) {
        super(_context, new Intent(POLICY_INTENT));
    }

    @Override
    public void onConnected(IBinder binder) {
        mService = IPolicyService.Stub.asInterface(binder);
    }

    public IBinder asBinder() {
        return null;
    }

    @Override
    public Policy clearUnsupportedPolicies(final Policy arg0) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mReturn = mService.clearUnsupportedPolicies(arg0);
            }
        }, "clearUnsupportedPolicies");
        waitForCompletion();
        if (DEBUG_PROXY) {
            Log.v(TAG, "clearUnsupportedPolicies: " + ((mReturn == null) ? "null" : mReturn));
        }
        if (mReturn == null) {
            throw new ServiceUnavailableException("clearUnsupportedPolicies");
        } else {
            return (Policy)mReturn;
        }
    }

    @Override
    public boolean isActive(final Policy arg0) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mReturn = mService.isActive(arg0);
            }
        }, "isActive");
        waitForCompletion();
        if (DEBUG_PROXY) {
            Log.v(TAG, "isActive: " + ((mReturn == null) ? "null" : mReturn));
        }
        if (mReturn == null) {
            throw new ServiceUnavailableException("isActive");
        } else {
            return (Boolean)mReturn;
        }
    }

    @Override
    public boolean isActiveAdmin() throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mReturn = mService.isActiveAdmin();
            }
        }, "isActiveAdmin");
        waitForCompletion();
        if (DEBUG_PROXY) {
            Log.v(TAG, "isActiveAdmin: " + ((mReturn == null) ? "null" : mReturn));
        }
        if (mReturn == null) {
            throw new ServiceUnavailableException("isActiveAdmin");
        } else {
            return (Boolean)mReturn;
        }
    }

    @Override
    public boolean isSupported(final Policy arg0) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mReturn = mService.isSupported(arg0);
            }
        }, "isSupported");
        waitForCompletion();
        if (DEBUG_PROXY) {
            Log.v(TAG, "isSupported: " + ((mReturn == null) ? "null" : mReturn));
        }
        if (mReturn == null) {
            throw new ServiceUnavailableException("isSupported");
        } else {
            return (Boolean)mReturn;
        }
    }

    @Override
    public void policiesRequired(final long arg0) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mService.policiesRequired(arg0);
            }
        }, "policiesRequired");
    }

    @Override
    public void remoteWipe() throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mService.remoteWipe();
            }
        }, "remoteWipe");
    }

    @Override
    public void setAccountHoldFlag(final long arg0, final boolean arg1) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mService.setAccountHoldFlag(arg0, arg1);
            }
        }, "setAccountHoldFlag");
    }

    @Override
    public void policiesUpdated(final long arg0) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mService.policiesUpdated(arg0);
            }
        }, "policiesUpdated");
    }

    // Static methods that encapsulate the proxy calls above
    public static boolean isActive(Context context, Policy policies) {
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

    public static void policiesUpdated(Context context, long accountId) {
        try {
            new PolicyServiceProxy(context).policiesUpdated(accountId);
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

    public static boolean isSupported(Context context, Policy policy) {
        try {
            return new PolicyServiceProxy(context).isSupported(policy);
        } catch (RemoteException e) {
        }
        return false;
     }

    public static Policy clearUnsupportedPolicies(Context context, Policy policy) {
        try {
            return new PolicyServiceProxy(context).clearUnsupportedPolicies(policy);
        } catch (RemoteException e) {
        }
        throw new IllegalStateException("PolicyService transaction failed");
    }
}

