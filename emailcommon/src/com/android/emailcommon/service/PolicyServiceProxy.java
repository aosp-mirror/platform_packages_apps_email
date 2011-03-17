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

import com.android.emailcommon.provider.EmailContent.Account;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

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
    public PolicySet clearUnsupportedPolicies(final PolicySet arg0) throws RemoteException {
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
            // Can this happen?
            return null;
        } else {
            return (PolicySet)mReturn;
        }
    }

    @Override
    public boolean isActive(final PolicySet arg0) throws RemoteException {
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
            // Can this happen?
            return false;
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
            // Can this happen?
            return false;
        } else {
            return (Boolean)mReturn;
        }
    }

    @Override
    public boolean isSupported(final PolicySet arg0) throws RemoteException {
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
            // Can this happen?
            return false;
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
    public void updatePolicies(final long arg0) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mService.updatePolicies(arg0);
            }
        }, "updatePolicies");
    }

    // Static methods that encapsulate the proxy calls above
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

