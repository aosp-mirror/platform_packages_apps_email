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
import android.app.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

/**
 * Utility functions to support reading and writing security policies
 */
public class SecurityPolicy {

    /** STOPSHIP - ok to check in true for now, but must be false for shipping */
    /** DO NOT CHECK IN WHILE 'true' */
    private static final boolean DEBUG_ALWAYS_ACTIVE = true;

    private static SecurityPolicy sInstance = null;
    private Context mContext;
    private DevicePolicyManager mDPM;
    private ComponentName mAdminName;
    private PolicySet mAggregatePolicy;

    private static final PolicySet NO_POLICY_SET =
            new PolicySet(0, PolicySet.PASSWORD_MODE_NONE, 0, 0, false);

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
    * These are hardcoded limits based on knowledge of the current DevicePolicyManager
    * and screen lock mechanisms.  Wherever possible, these should be replaced with queries of
    * dynamic capabilities of the device (e.g. what password modes are supported?)
    */
   private static final int LIMIT_MIN_PASSWORD_LENGTH = 16;
   private static final int LIMIT_PASSWORD_MODE = PolicySet.PASSWORD_MODE_STRONG;
   private static final int LIMIT_SCREENLOCK_TIME = PolicySet.SCREEN_LOCK_TIME_MAX;

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
        mDPM = null;
        mAdminName = new ComponentName(context, PolicyAdmin.class);
        mAggregatePolicy = null;
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
     * @return a policy representing the strongest aggregate.  If no policy sets are defined,
     * a lightweight "nothing required" policy will be returned.  Never null.
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
            return NO_POLICY_SET;
        }
    }

    /**
     * Get the dpm.  This mainly allows us to make some utility calls without it, for testing.
     */
    private synchronized DevicePolicyManager getDPM() {
        if (mDPM == null) {
            mDPM = (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        }
        return mDPM;
    }

    /**
     * API: Query used to determine if a given policy is "possible" (irrespective of current
     * device state.  This is used when creating new accounts.
     *
     * TODO: This is hardcoded based on knowledge of the current DevicePolicyManager
     * and screen lock mechanisms.  It would be nice to replace these tests with something
     * more dynamic.
     *
     * @param policies the policies requested
     * @return true if the policies are supported, false if not supported
     */
    public boolean isSupported(PolicySet policies) {
        if (policies.mMinPasswordLength > LIMIT_MIN_PASSWORD_LENGTH) {
            return false;
        }
        if (policies.mPasswordMode > LIMIT_PASSWORD_MODE ) {
            return false;
        }
        // No limit on password fail count
        if (policies.mMaxScreenLockTime > LIMIT_SCREENLOCK_TIME ) {
            return false;
        }
        // No limit on remote wipe capable

        return true;
    }

    /**
     * API: Report that policies may have been updated due to rewriting values in an Account.
     */
    public synchronized void updatePolicies() {
        mAggregatePolicy = null;
    }

    /**
     * API: Query used to determine if a given policy is "active" (the device is operating at
     * the required security level).  This is used when creating new accounts.  This method
     * is for queries only, and does not trigger any change in device state.
     *
     * @param policies the policies requested
     * @return true if the policies are active, false if not active
     */
    public boolean isActive(PolicySet policies) {
        DevicePolicyManager dpm = getDPM();
        if (dpm.isAdminActive(mAdminName)) {
            // check each policy
            PolicySet aggregate;
            synchronized (this) {
                if (mAggregatePolicy == null) {
                    mAggregatePolicy = computeAggregatePolicy();
                }
                aggregate = mAggregatePolicy;
            }
            // quick check for the "empty set" of no policies
            if (aggregate == NO_POLICY_SET) {
                return true;
            }
            // check each policy explicitly
            if (aggregate.mMinPasswordLength > 0) {
                if (dpm.getPasswordMinimumLength(mAdminName) < aggregate.mMinPasswordLength) {
                    return false;
                }
            }
            if (aggregate.mPasswordMode > 0) {
                if (dpm.getPasswordQuality(mAdminName) < aggregate.getDPManagerPasswordMode()) {
                    return false;
                }
                if (!dpm.isActivePasswordSufficient()) {
                    return false;
                }
            }
            if (aggregate.mMaxScreenLockTime > 0) {
                // Note, we use seconds, dpm uses milliseconds
                if (dpm.getMaximumTimeToLock(mAdminName) > aggregate.mMaxScreenLockTime * 1000) {
                    return false;
                }
            }
            // password failures are counted locally - no test required here
            // no check required for remote wipe (it's supported, if we're the admin)
        }
        // return false, not active - unless debugging enabled
        return DEBUG_ALWAYS_ACTIVE;
    }

    /**
     * Sync service should call this any time a sync fails due to isActive() returning false.
     * This will kick off the notify-acquire-admin-state process and/or increase the security level.
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
         * @param maxScreenLockTime in seconds (0=not enforced)
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
         * Helper to map DevicePolicyManager password modes to our internal encoding.
         */
        public int getDPManagerPasswordMode() {
            switch (mPasswordMode) {
                case PASSWORD_MODE_SIMPLE:
                    return DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
                case PASSWORD_MODE_STRONG:
                    return DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC;
                default:
                    return DevicePolicyManager .PASSWORD_QUALITY_UNSPECIFIED;
            }
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
