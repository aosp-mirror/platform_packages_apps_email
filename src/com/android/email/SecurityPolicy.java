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

import com.android.email.provider.EmailContent.Account;

import android.app.DeviceAdmin;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

/**
 * Utility functions to support reading and writing security policies
 */
public class SecurityPolicy {

    private static SecurityPolicy sInstance = null;
    private Context mContext;

    /**
     * This projection on Account is for scanning/reading 
     */
    private static final String[] ACCOUNT_SECURITY_PROJECTION = new String[] {
        Account.RECORD_ID, Account.SECURITY_FLAGS
    };
    private static final int ACCOUNT_SECURITY_COLUMN_FLAGS = 1;
    // Note, this handles the NULL case to deal with older accounts where the column was added
    private static final String WHERE_ACCOUNT_SECURITY_NONZERO =
        Account.SECURITY_FLAGS + " IS NOT NULL AND " + Account.SECURITY_FLAGS + "!=0";

    /**
     * Get the security policy instance
     */
    public synchronized static SecurityPolicy getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SecurityPolicy(context);
        }
        return sInstance;
    }

    /**
     * Private constructor (one time only)
     */
    private SecurityPolicy(Context context) {
        mContext = context;
    }

    /**
     * For testing only: Inject context into already-created instance
     */
    /* package */ void setContext(Context context) {
        mContext = context;
    }

    /**
     * Compute the aggregate policy for all accounts that require it, and record it.
     *
     * The business logic is as follows:
     *  min password length         take the max
     *  password mode               take the max (strongest mode)
     *  max password fails          take the min
     *  max screen lock time        take the min
     *  require remote wipe         take the max (logical or)
     * 
     * @return a policy representing the strongest aggregate, or null if none are defined
     */
    /* package */ PolicySet computeAggregatePolicy() {
        boolean policiesFound = false;

        int minPasswordLength = Integer.MIN_VALUE;
        int passwordMode = Integer.MIN_VALUE;
        int maxPasswordFails = Integer.MAX_VALUE;
        int maxScreenLockTime = Integer.MAX_VALUE;
        boolean requireRemoteWipe = false;

        Cursor c = mContext.getContentResolver().query(Account.CONTENT_URI,
                ACCOUNT_SECURITY_PROJECTION, WHERE_ACCOUNT_SECURITY_NONZERO, null, null);
        try {
            while (c.moveToNext()) {
                int flags = c.getInt(ACCOUNT_SECURITY_COLUMN_FLAGS);
                if (flags != 0) {
                    PolicySet p = new PolicySet(flags);
                    if (p.mMinPasswordLength > 0) {
                        minPasswordLength = Math.max(p.mMinPasswordLength, minPasswordLength);
                    }
                    if (p.mPasswordMode > 0) {
                        passwordMode  = Math.max(p.mPasswordMode, passwordMode);
                    }
                    if (p.mMaxPasswordFails > 0) {
                        maxPasswordFails = Math.min(p.mMaxPasswordFails, maxPasswordFails);
                    }
                    if (p.mMaxScreenLockTime > 0) {
                        maxScreenLockTime = Math.min(p.mMaxScreenLockTime, maxScreenLockTime);
                    }
                    requireRemoteWipe |= p.mRequireRemoteWipe;
                    policiesFound = true;
                }
            }
        } finally {
            c.close();
        }
        if (policiesFound) {
            return new PolicySet(minPasswordLength, passwordMode, maxPasswordFails,
                    maxScreenLockTime, requireRemoteWipe);
        } else {
            return null;
        }
    }

    /**
     * Query used to determine if a given policy is "possible" (irrespective of current
     * device state.  This is used when creating new accounts.
     *
     * TO BE IMPLEMENTED
     *
     * @param policies the policies requested
     * @return true if the policies are supported, false if not supported
     */
    public boolean isSupported(PolicySet policies) {
        return true;
    }

    /**
     * Query used to determine if a given policy is "active" (the device is operating at
     * the required security level).  This is used when creating new accounts.
     *
     * TO BE IMPLEMENTED
     *
     * @param policies the policies requested
     * @return true if the policies are active, false if not active
     */
    public boolean isActive(PolicySet policies) {
        return true;
    }

    /**
     * Sync service should call this any time a sync fails due to isActive() returning false.
     * This will kick off the acquire-admin-state process and/or increase the security level.
     * The caller needs to write the required policies into this account before making this call.
     *
     * @param accountId the account for which sync cannot proceed
     */
    public void policiesRequired(long accountId) {
        // implement....
    }

    /**
     * Class for tracking policies and reading/writing into accounts
     */
    public static class PolicySet {

        // Security (provisioning) flags
            // bits 0..4: password length (0=no password required)
        private static final int PASSWORD_LENGTH_MASK = 31;
        private static final int PASSWORD_LENGTH_SHIFT = 0;
        public static final int PASSWORD_LENGTH_MAX = 31;
            // bits 5..8: password mode
        private static final int PASSWORD_MODE_SHIFT = 5;
        private static final int PASSWORD_MODE_MASK = 15 << PASSWORD_MODE_SHIFT;
        public static final int PASSWORD_MODE_NONE = 0 << PASSWORD_MODE_SHIFT;
        public static final int PASSWORD_MODE_SIMPLE = 1 << PASSWORD_MODE_SHIFT;
        public static final int PASSWORD_MODE_STRONG = 2 << PASSWORD_MODE_SHIFT;
            // bits 9..13: password failures -> wipe device (0=disabled)
        private static final int PASSWORD_MAX_FAILS_SHIFT = 9;
        private static final int PASSWORD_MAX_FAILS_MASK = 31 << PASSWORD_MAX_FAILS_SHIFT;
        public static final int PASSWORD_MAX_FAILS_MAX = 31;
            // bits 14..24: seconds to screen lock (0=not required)
        private static final int SCREEN_LOCK_TIME_SHIFT = 14;
        private static final int SCREEN_LOCK_TIME_MASK = 2047 << SCREEN_LOCK_TIME_SHIFT;
        public static final int SCREEN_LOCK_TIME_MAX = 2047;
            // bit 25: remote wipe capability required
        private static final int REQUIRE_REMOTE_WIPE = 1 << 25;

        public final int mMinPasswordLength;
        public final int mPasswordMode;
        public final int mMaxPasswordFails;
        public final int mMaxScreenLockTime;
        public final boolean mRequireRemoteWipe;

        /**
         * Create from raw values.
         * @param minPasswordLength (0=not enforced)
         * @param passwordMode
         * @param maxPasswordFails (0=not enforced)
         * @param maxScreenLockTime (0=not enforced)
         * @param requireRemoteWipe
         */
        public PolicySet(int minPasswordLength, int passwordMode, int maxPasswordFails,
                int maxScreenLockTime, boolean requireRemoteWipe) {
            if (minPasswordLength > PASSWORD_LENGTH_MAX) {
                throw new IllegalArgumentException("password length");
            }
            if (passwordMode < PASSWORD_MODE_NONE
                    || passwordMode > PASSWORD_MODE_STRONG) {
                throw new IllegalArgumentException("password mode");
            }
            if (maxPasswordFails > PASSWORD_MAX_FAILS_MAX) {
                throw new IllegalArgumentException("password max fails");
            }
            if (maxScreenLockTime > SCREEN_LOCK_TIME_MAX) {
                throw new IllegalArgumentException("max screen lock time");
            }

            mMinPasswordLength = minPasswordLength;
            mPasswordMode = passwordMode;
            mMaxPasswordFails = maxPasswordFails;
            mMaxScreenLockTime = maxScreenLockTime;
            mRequireRemoteWipe = requireRemoteWipe;
        }

        /**
         * Create from values encoded in an account
         * @param account
         */
        public PolicySet(Account account) {
            this(account.mSecurityFlags);
        }

        /**
         * Create from values encoded in an account flags int
         */
        public PolicySet(int flags) {
            mMinPasswordLength =
                (flags & PASSWORD_LENGTH_MASK) >> PASSWORD_LENGTH_SHIFT;
            mPasswordMode =
                (flags & PASSWORD_MODE_MASK);
            mMaxPasswordFails =
                (flags & PASSWORD_MAX_FAILS_MASK) >> PASSWORD_MAX_FAILS_SHIFT;
            mMaxScreenLockTime =
                (flags & SCREEN_LOCK_TIME_MASK) >> SCREEN_LOCK_TIME_SHIFT;
            mRequireRemoteWipe = 0 != (flags & REQUIRE_REMOTE_WIPE);
        }

        /**
         * Record flags (and a sync key for the flags) into an Account
         * Note: the hash code is defined as the encoding used in Account
         * @param account to write the values mSecurityFlags and mSecuritySyncKey
         * @param syncKey the value to write into the account's mSecuritySyncKey
         */
        public void writeAccount(Account account, String syncKey) {
          account.mSecurityFlags = hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof PolicySet) {
                PolicySet other = (PolicySet)o;
                return (this.mMinPasswordLength == other.mMinPasswordLength)
                        && (this.mPasswordMode == other.mPasswordMode)
                        && (this.mMaxPasswordFails == other.mMaxPasswordFails)
                        && (this.mMaxScreenLockTime == other.mMaxScreenLockTime)
                        && (this.mRequireRemoteWipe == other.mRequireRemoteWipe);
            }
            return false;
        }

        /**
         * Note: the hash code is defined as the encoding used in Account
         */
        @Override
        public int hashCode() {
            int flags = 0;
            flags = mMinPasswordLength << PASSWORD_LENGTH_SHIFT;
            flags |= mPasswordMode;
            flags |= mMaxPasswordFails << PASSWORD_MAX_FAILS_SHIFT;
            flags |= mMaxScreenLockTime << SCREEN_LOCK_TIME_SHIFT;
            if (mRequireRemoteWipe) {
                flags |= REQUIRE_REMOTE_WIPE;
            }
            return flags;
        }

        @Override
        public String toString() {
            return "{ " + "pw-len-min=" + mMinPasswordLength + " pw-mode=" + mPasswordMode
                    + " pw-fails-max=" + mMaxPasswordFails + " screenlock-max="
                    + mMaxScreenLockTime + " remote-wipe-req=" + mRequireRemoteWipe + "}";
        }
    }

    /**
     * Device Policy administrator.  This is primarily a listener for device state changes.
     */
    private static class PolicyAdmin extends DeviceAdmin {

        boolean mEnabled = false;

        /**
         * Called after the administrator is first enabled.
         */
        @Override
        public void onEnabled(Context context, Intent intent) {
            mEnabled = true;
            // do something
        }
        
        /**
         * Called prior to the administrator being disabled.
         */
        @Override
        public void onDisabled(Context context, Intent intent) {
            mEnabled = false;
            // do something
        }
        
        /**
         * Called after the user has changed their password.
         */
        @Override
        public void onPasswordChanged(Context context, Intent intent) {
            // do something
        }
        
        /**
         * Called after the user has failed at entering their current password.
         */
        @Override
        public void onPasswordFailed(Context context, Intent intent) {
            // do something
        }
    }
}
