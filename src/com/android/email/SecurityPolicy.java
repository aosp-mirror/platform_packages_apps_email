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

import com.android.email.activity.setup.AccountSecurity;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.service.EmailBroadcastProcessorService;

import android.app.admin.DeviceAdminInfo;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * Utility functions to support reading and writing security policies, and handshaking the device
 * into and out of various security states.
 */
public class SecurityPolicy {
    private static final String TAG = "SecurityPolicy";
    private static SecurityPolicy sInstance = null;
    private Context mContext;
    private DevicePolicyManager mDPM;
    private ComponentName mAdminName;
    private PolicySet mAggregatePolicy;

    /* package */ static final PolicySet NO_POLICY_SET =
            new PolicySet(0, PolicySet.PASSWORD_MODE_NONE, 0, 0, false, 0, 0, 0, false);

    /**
     * This projection on Account is for scanning/reading
     */
    private static final String[] ACCOUNT_SECURITY_PROJECTION = new String[] {
        AccountColumns.ID, AccountColumns.SECURITY_FLAGS
    };
    private static final int ACCOUNT_SECURITY_COLUMN_ID = 0;
    private static final int ACCOUNT_SECURITY_COLUMN_FLAGS = 1;

    // Messages used for DevicePolicyManager callbacks
    private static final int DEVICE_ADMIN_MESSAGE_ENABLED = 1;
    private static final int DEVICE_ADMIN_MESSAGE_DISABLED = 2;
    private static final int DEVICE_ADMIN_MESSAGE_PASSWORD_CHANGED = 3;
    private static final int DEVICE_ADMIN_MESSAGE_PASSWORD_EXPIRING = 4;

    /**
     * Get the security policy instance
     */
    public synchronized static SecurityPolicy getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SecurityPolicy(context.getApplicationContext());
        }
        return sInstance;
    }

    /**
     * Private constructor (one time only)
     */
    private SecurityPolicy(Context context) {
        mContext = context.getApplicationContext();
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
     *  password history            take the max (strongest mode)
     *  password expiration         take the min (strongest mode)
     *  password complex chars      take the max (strongest mode)
     *  encryption                  take the max (logical or)
     *
     * @return a policy representing the strongest aggregate.  If no policy sets are defined,
     * a lightweight "nothing required" policy will be returned.  Never null.
     */
    /*package*/ PolicySet computeAggregatePolicy() {
        boolean policiesFound = false;

        int minPasswordLength = Integer.MIN_VALUE;
        int passwordMode = Integer.MIN_VALUE;
        int maxPasswordFails = Integer.MAX_VALUE;
        int maxScreenLockTime = Integer.MAX_VALUE;
        boolean requireRemoteWipe = false;
        int passwordHistory = Integer.MIN_VALUE;
        int passwordExpirationDays = Integer.MAX_VALUE;
        int passwordComplexChars = Integer.MIN_VALUE;
        boolean requireEncryption = false;

        Cursor c = mContext.getContentResolver().query(Account.CONTENT_URI,
                ACCOUNT_SECURITY_PROJECTION, Account.SECURITY_NONZERO_SELECTION, null, null);
        try {
            while (c.moveToNext()) {
                long flags = c.getLong(ACCOUNT_SECURITY_COLUMN_FLAGS);
                if (flags != 0) {
                    PolicySet p = new PolicySet(flags);
                    minPasswordLength = Math.max(p.mMinPasswordLength, minPasswordLength);
                    passwordMode  = Math.max(p.mPasswordMode, passwordMode);
                    if (p.mMaxPasswordFails > 0) {
                        maxPasswordFails = Math.min(p.mMaxPasswordFails, maxPasswordFails);
                    }
                    if (p.mMaxScreenLockTime > 0) {
                        maxScreenLockTime = Math.min(p.mMaxScreenLockTime, maxScreenLockTime);
                    }
                    if (p.mPasswordHistory > 0) {
                        passwordHistory = Math.max(p.mPasswordHistory, passwordHistory);
                    }
                    if (p.mPasswordExpirationDays > 0) {
                        passwordExpirationDays =
                                Math.min(p.mPasswordExpirationDays, passwordExpirationDays);
                    }
                    if (p.mPasswordComplexChars > 0) {
                        passwordComplexChars = Math.max(p.mPasswordComplexChars,
                                passwordComplexChars);
                    }
                    requireRemoteWipe |= p.mRequireRemoteWipe;
                    requireEncryption |= p.mRequireEncryption;
                    policiesFound = true;
                }
            }
        } finally {
            c.close();
        }
        if (policiesFound) {
            // final cleanup pass converts any untouched min/max values to zero (not specified)
            if (minPasswordLength == Integer.MIN_VALUE) minPasswordLength = 0;
            if (passwordMode == Integer.MIN_VALUE) passwordMode = 0;
            if (maxPasswordFails == Integer.MAX_VALUE) maxPasswordFails = 0;
            if (maxScreenLockTime == Integer.MAX_VALUE) maxScreenLockTime = 0;
            if (passwordHistory == Integer.MIN_VALUE) passwordHistory = 0;
            if (passwordExpirationDays == Integer.MAX_VALUE) passwordExpirationDays = 0;
            if (passwordComplexChars == Integer.MIN_VALUE) passwordComplexChars = 0;

            return new PolicySet(minPasswordLength, passwordMode, maxPasswordFails,
                    maxScreenLockTime, requireRemoteWipe, passwordExpirationDays, passwordHistory,
                    passwordComplexChars, requireEncryption);
        } else {
            return NO_POLICY_SET;
        }
    }

    /**
     * Return updated aggregate policy, from cached value if possible
     */
    public synchronized PolicySet getAggregatePolicy() {
        if (mAggregatePolicy == null) {
            mAggregatePolicy = computeAggregatePolicy();
        }
        return mAggregatePolicy;
    }

    /**
     * Get the dpm.  This mainly allows us to make some utility calls without it, for testing.
     */
    /* package */ synchronized DevicePolicyManager getDPM() {
        if (mDPM == null) {
            mDPM = (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        }
        return mDPM;
    }

    /**
     * API: Report that policies may have been updated due to rewriting values in an Account.
     * @param accountId the account that has been updated, -1 if unknown/deleted
     */
    public synchronized void updatePolicies(long accountId) {
        mAggregatePolicy = null;
    }

    /**
     * API: Report that policies may have been updated *and* the caller vouches that the
     * change is a reduction in policies.  This forces an immediate change to device state.
     * Typically used when deleting accounts, although we may use it for server-side policy
     * rollbacks.
     */
    public void reducePolicies() {
        updatePolicies(-1);
        setActivePolicies();
    }

    /**
     * API: Query if the proposed set of policies are supported on the device.
     *
     * @param policies the polices that were requested
     * @return boolean if supported
     */
    public boolean isSupported(PolicySet policies) {
        // IMPLEMENTATION:  At this time, the only policy which might not be supported is
        // encryption (which requires low-level systems support).  Other policies are fully
        // supported by the framework and do not need to be checked.
        if (policies.mRequireEncryption) {
            int encryptionStatus = getDPM().getStorageEncryptionStatus();
            if (encryptionStatus == DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * API: Remove any unsupported policies
     *
     * This is used when we have a set of polices that have been requested, but the server
     * is willing to allow unsupported policies to be considered optional.
     *
     * @param policies the polices that were requested
     * @return the same PolicySet if all are supported;  A replacement PolicySet if any
     *   unsupported policies were removed
     */
    public PolicySet clearUnsupportedPolicies(PolicySet policies) {
        PolicySet result = policies;
        // IMPLEMENTATION:  At this time, the only policy which might not be supported is
        // encryption (which requires low-level systems support).  Other policies are fully
        // supported by the framework and do not need to be checked.
        if (policies.mRequireEncryption) {
            int encryptionStatus = getDPM().getStorageEncryptionStatus();
            if (encryptionStatus == DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED) {
                // Make new PolicySet w/o encryption
                result = new PolicySet(policies.mMinPasswordLength, policies.mPasswordMode,
                        policies.mMaxPasswordFails, policies.mMaxScreenLockTime,
                        policies.mRequireRemoteWipe, policies.mPasswordExpirationDays,
                        policies.mPasswordHistory, policies.mPasswordComplexChars, false);
            }
        }
        return result;
    }

    /**
     * API: Query used to determine if a given policy is "active" (the device is operating at
     * the required security level).
     *
     * @param policies the policies requested, or null to check aggregate stored policies
     * @return true if the requested policies are active, false if not.
     */
    public boolean isActive(PolicySet policies) {
        int reasons = getInactiveReasons(policies);
        return reasons == 0;
    }

    /**
     * Return bits from isActive:  Device Policy Manager has not been activated
     */
    public final static int INACTIVE_NEED_ACTIVATION = 1;

    /**
     * Return bits from isActive:  Some required configuration is not correct (no user action).
     */
    public final static int INACTIVE_NEED_CONFIGURATION = 2;

    /**
     * Return bits from isActive:  Password needs to be set or updated
     */
    public final static int INACTIVE_NEED_PASSWORD = 4;

    /**
     * Return bits from isActive:  Encryption has not be enabled
     */
    public final static int INACTIVE_NEED_ENCRYPTION = 8;

    /**
     * API: Query used to determine if a given policy is "active" (the device is operating at
     * the required security level).
     *
     * This can be used when syncing a specific account, by passing a specific set of policies
     * for that account.  Or, it can be used at any time to compare the device
     * state against the aggregate set of device policies stored in all accounts.
     *
     * This method is for queries only, and does not trigger any change in device state.
     *
     * NOTE:  If there are multiple accounts with password expiration policies, the device
     * password will be set to expire in the shortest required interval (most secure).  This method
     * will return 'false' as soon as the password expires - irrespective of which account caused
     * the expiration.  In other words, all accounts (that require expiration) will run/stop
     * based on the requirements of the account with the shortest interval.
     *
     * @param policies the policies requested, or null to check aggregate stored policies
     * @return zero if the requested policies are active, non-zero bits indicates that more work
     * is needed (typically, by the user) before the required security polices are fully active.
     */
    public int getInactiveReasons(PolicySet policies) {
        // select aggregate set if needed
        if (policies == null) {
            policies = getAggregatePolicy();
        }
        // quick check for the "empty set" of no policies
        if (policies == NO_POLICY_SET) {
            return 0;
        }
        int reasons = 0;
        DevicePolicyManager dpm = getDPM();
        if (isActiveAdmin()) {
            // check each policy explicitly
            if (policies.mMinPasswordLength > 0) {
                if (dpm.getPasswordMinimumLength(mAdminName) < policies.mMinPasswordLength) {
                    reasons |= INACTIVE_NEED_PASSWORD;
                }
            }
            if (policies.mPasswordMode > 0) {
                if (dpm.getPasswordQuality(mAdminName) < policies.getDPManagerPasswordQuality()) {
                    reasons |= INACTIVE_NEED_PASSWORD;
                }
                if (!dpm.isActivePasswordSufficient()) {
                    reasons |= INACTIVE_NEED_PASSWORD;
                }
            }
            if (policies.mMaxScreenLockTime > 0) {
                // Note, we use seconds, dpm uses milliseconds
                if (dpm.getMaximumTimeToLock(mAdminName) > policies.mMaxScreenLockTime * 1000) {
                    reasons |= INACTIVE_NEED_CONFIGURATION;
                }
            }
            if (policies.mPasswordExpirationDays > 0) {
                // confirm that expirations are currently set
                long currentTimeout = dpm.getPasswordExpirationTimeout(mAdminName);
                if (currentTimeout == 0
                        || currentTimeout > policies.getDPManagerPasswordExpirationTimeout()) {
                    reasons |= INACTIVE_NEED_PASSWORD;
                }
                // confirm that the current password hasn't expired
                long expirationDate = dpm.getPasswordExpiration(mAdminName);
                long timeUntilExpiration = expirationDate - System.currentTimeMillis();
                boolean expired = timeUntilExpiration < 0;
                if (expired) {
                    reasons |= INACTIVE_NEED_PASSWORD;
                }
            }
            if (policies.mPasswordHistory > 0) {
                if (dpm.getPasswordHistoryLength(mAdminName) < policies.mPasswordHistory) {
                    reasons |= INACTIVE_NEED_PASSWORD;
                }
            }
            if (policies.mPasswordComplexChars > 0) {
                if (dpm.getPasswordMinimumNonLetter(mAdminName) < policies.mPasswordComplexChars) {
                    reasons |= INACTIVE_NEED_PASSWORD;
                }
            }
            if (policies.mRequireEncryption) {
                int encryptionStatus = getDPM().getStorageEncryptionStatus();
                if (encryptionStatus != DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE) {
                    reasons |= INACTIVE_NEED_ENCRYPTION;
                }
            }
            // password failures are counted locally - no test required here
            // no check required for remote wipe (it's supported, if we're the admin)

            // If we made it all the way, reasons == 0 here.  Otherwise it's a list of grievances.
            return reasons;
        }
        // return false, not active
        return INACTIVE_NEED_ACTIVATION;
    }

    /**
     * Set the requested security level based on the aggregate set of requests.
     * If the set is empty, we release our device administration.  If the set is non-empty,
     * we only proceed if we are already active as an admin.
     */
    public void setActivePolicies() {
        DevicePolicyManager dpm = getDPM();
        // compute aggregate set of policies
        PolicySet policies = getAggregatePolicy();
        // if empty set, detach from policy manager
        if (policies == NO_POLICY_SET) {
            dpm.removeActiveAdmin(mAdminName);
        } else if (isActiveAdmin()) {
            // set each policy in the policy manager
            // password mode & length
            dpm.setPasswordQuality(mAdminName, policies.getDPManagerPasswordQuality());
            dpm.setPasswordMinimumLength(mAdminName, policies.mMinPasswordLength);
            // screen lock time
            dpm.setMaximumTimeToLock(mAdminName, policies.mMaxScreenLockTime * 1000);
            // local wipe (failed passwords limit)
            dpm.setMaximumFailedPasswordsForWipe(mAdminName, policies.mMaxPasswordFails);
            // password expiration (days until a password expires).  API takes mSec.
            dpm.setPasswordExpirationTimeout(mAdminName,
                    policies.getDPManagerPasswordExpirationTimeout());
            // password history length (number of previous passwords that may not be reused)
            dpm.setPasswordHistoryLength(mAdminName, policies.mPasswordHistory);
            // password minimum complex characters
            dpm.setPasswordMinimumNonLetter(mAdminName, policies.mPasswordComplexChars);
            // encryption required
            dpm.setStorageEncryption(mAdminName, policies.mRequireEncryption);
        }
    }

    /**
     * API: Set/Clear the "hold" flag in any account.  This flag serves a dual purpose:
     * Setting it gives us an indication that it was blocked, and clearing it gives EAS a
     * signal to try syncing again.
     * @param context
     * @param account The account to update
     * @param newState true = security hold, false = free to sync
     */
    public static void setAccountHoldFlag(Context context, Account account, boolean newState) {
        if (newState) {
            account.mFlags |= Account.FLAGS_SECURITY_HOLD;
        } else {
            account.mFlags &= ~Account.FLAGS_SECURITY_HOLD;
        }
        ContentValues cv = new ContentValues();
        cv.put(AccountColumns.FLAGS, account.mFlags);
        account.update(context, cv);
    }

    /**
     * API: Sync service should call this any time a sync fails due to isActive() returning false.
     * This will kick off the notify-acquire-admin-state process and/or increase the security level.
     * The caller needs to write the required policies into this account before making this call.
     * Should not be called from UI thread - uses DB lookups to prepare new notifications
     *
     * @param accountId the account for which sync cannot proceed
     */
    public void policiesRequired(long accountId) {
        Account account = EmailContent.Account.restoreAccountWithId(mContext, accountId);

        // Mark the account as "on hold".
        setAccountHoldFlag(mContext, account, true);

        // Put up a notification
        String tickerText = mContext.getString(R.string.security_notification_ticker_fmt,
                account.getDisplayName());
        String contentTitle = mContext.getString(R.string.security_notification_content_title);
        String contentText = account.getDisplayName();
        Intent intent = AccountSecurity.actionUpdateSecurityIntent(mContext, accountId);
        NotificationController.getInstance(mContext).postAccountNotification(
                account, tickerText, contentTitle, contentText, intent,
                NotificationController.NOTIFICATION_ID_SECURITY_NEEDED);
    }

    /**
     * Called from the notification's intent receiver to register that the notification can be
     * cleared now.
     */
    public void clearNotification(long accountId) {
        NotificationController.getInstance(mContext).cancelNotification(
                NotificationController.NOTIFICATION_ID_SECURITY_NEEDED);
    }

    /**
     * API: Remote wipe (from server).  This is final, there is no confirmation.  It will only
     * return to the caller if there is an unexpected failure.
     */
    public void remoteWipe() {
        DevicePolicyManager dpm = getDPM();
        if (dpm.isAdminActive(mAdminName)) {
            dpm.wipeData(0);
        } else {
            Log.d(Email.LOG_TAG, "Could not remote wipe because not device admin.");
        }
    }

    /**
     * Class for tracking policies and reading/writing into accounts
     */
    public static class PolicySet implements Parcelable {

        // Security (provisioning) flags
            // bits 0..4: password length (0=no password required)
        private static final int PASSWORD_LENGTH_MASK = 31;
        private static final int PASSWORD_LENGTH_SHIFT = 0;
        public static final int PASSWORD_LENGTH_MAX = 30;
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
            // bit 26..35: password expiration (days; 0=not required)
        private static final int PASSWORD_EXPIRATION_SHIFT = 26;
        private static final long PASSWORD_EXPIRATION_MASK = 1023L << PASSWORD_EXPIRATION_SHIFT;
        public static final int PASSWORD_EXPIRATION_MAX = 1023;
            // bit 36..43: password history (length; 0=not required)
        private static final int PASSWORD_HISTORY_SHIFT = 36;
        private static final long PASSWORD_HISTORY_MASK = 255L << PASSWORD_HISTORY_SHIFT;
        public static final int PASSWORD_HISTORY_MAX = 255;
            // bit 44..48: min complex characters (0=not required)
        private static final int PASSWORD_COMPLEX_CHARS_SHIFT = 44;
        private static final long PASSWORD_COMPLEX_CHARS_MASK = 31L << PASSWORD_COMPLEX_CHARS_SHIFT;
        public static final int PASSWORD_COMPLEX_CHARS_MAX = 31;
            // bit 49: requires device encryption
        private static final long REQUIRE_ENCRYPTION = 1L << 49;

        /* Convert days to mSec (used for password expiration) */
        private static final long DAYS_TO_MSEC = 24 * 60 * 60 * 1000;
        /* Small offset (2 minutes) added to policy expiration to make user testing easier. */
        private static final long EXPIRATION_OFFSET_MSEC = 2 * 60 * 1000;

        /*package*/ final int mMinPasswordLength;
        /*package*/ final int mPasswordMode;
        /*package*/ final int mMaxPasswordFails;
        /*package*/ final int mMaxScreenLockTime;
        /*package*/ final boolean mRequireRemoteWipe;
        /*package*/ final int mPasswordExpirationDays;
        /*package*/ final int mPasswordHistory;
        /*package*/ final int mPasswordComplexChars;
        /*package*/ final boolean mRequireEncryption;

        public int getMinPasswordLengthForTest() {
            return mMinPasswordLength;
        }

        public int getPasswordModeForTest() {
            return mPasswordMode;
        }

        public int getMaxPasswordFailsForTest() {
            return mMaxPasswordFails;
        }

        public int getMaxScreenLockTimeForTest() {
            return mMaxScreenLockTime;
        }

        public boolean isRequireRemoteWipeForTest() {
            return mRequireRemoteWipe;
        }

        public boolean isRequireEncryptionForTest() {
            return mRequireEncryption;
        }

        /**
         * Create from raw values.
         * @param minPasswordLength (0=not enforced)
         * @param passwordMode
         * @param maxPasswordFails (0=not enforced)
         * @param maxScreenLockTime in seconds (0=not enforced)
         * @param requireRemoteWipe
         * @param passwordExpirationDays in days (0=not enforced)
         * @param passwordHistory (0=not enforced)
         * @param passwordComplexChars (0=not enforced)
         * @throws IllegalArgumentException for illegal arguments.
         */
        public PolicySet(int minPasswordLength, int passwordMode, int maxPasswordFails,
                int maxScreenLockTime, boolean requireRemoteWipe, int passwordExpirationDays,
                int passwordHistory, int passwordComplexChars, boolean requireEncryption)
                throws IllegalArgumentException {
            // If we're not enforcing passwords, make sure we clean up related values, since EAS
            // can send non-zero values for any or all of these
            if (passwordMode == PASSWORD_MODE_NONE) {
                maxPasswordFails = 0;
                maxScreenLockTime = 0;
                minPasswordLength = 0;
                passwordComplexChars = 0;
                passwordHistory = 0;
                passwordExpirationDays = 0;
            } else {
                if ((passwordMode != PASSWORD_MODE_SIMPLE) &&
                        (passwordMode != PASSWORD_MODE_STRONG)) {
                    throw new IllegalArgumentException("password mode");
                }
                // If we're only requiring a simple password, set complex chars to zero; note
                // that EAS can erroneously send non-zero values in this case
                if (passwordMode == PASSWORD_MODE_SIMPLE) {
                    passwordComplexChars = 0;
                }
                // The next four values have hard limits which cannot be supported if exceeded.
                if (minPasswordLength > PASSWORD_LENGTH_MAX) {
                    throw new IllegalArgumentException("password length");
                }
                if (passwordExpirationDays > PASSWORD_EXPIRATION_MAX) {
                    throw new IllegalArgumentException("password expiration");
                }
                if (passwordHistory > PASSWORD_HISTORY_MAX) {
                    throw new IllegalArgumentException("password history");
                }
                if (passwordComplexChars > PASSWORD_COMPLEX_CHARS_MAX) {
                    throw new IllegalArgumentException("complex chars");
                }
                // This value can be reduced (which actually increases security) if necessary
                if (maxPasswordFails > PASSWORD_MAX_FAILS_MAX) {
                    maxPasswordFails = PASSWORD_MAX_FAILS_MAX;
                }
                // This value can be reduced (which actually increases security) if necessary
                if (maxScreenLockTime > SCREEN_LOCK_TIME_MAX) {
                    maxScreenLockTime = SCREEN_LOCK_TIME_MAX;
                }
            }
            mMinPasswordLength = minPasswordLength;
            mPasswordMode = passwordMode;
            mMaxPasswordFails = maxPasswordFails;
            mMaxScreenLockTime = maxScreenLockTime;
            mRequireRemoteWipe = requireRemoteWipe;
            mPasswordExpirationDays = passwordExpirationDays;
            mPasswordHistory = passwordHistory;
            mPasswordComplexChars = passwordComplexChars;
            mRequireEncryption = requireEncryption;
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
        public PolicySet(long flags) {
            mMinPasswordLength =
                (int) ((flags & PASSWORD_LENGTH_MASK) >> PASSWORD_LENGTH_SHIFT);
            mPasswordMode =
                (int) (flags & PASSWORD_MODE_MASK);
            mMaxPasswordFails =
                (int) ((flags & PASSWORD_MAX_FAILS_MASK) >> PASSWORD_MAX_FAILS_SHIFT);
            mMaxScreenLockTime =
                (int) ((flags & SCREEN_LOCK_TIME_MASK) >> SCREEN_LOCK_TIME_SHIFT);
            mRequireRemoteWipe = 0 != (flags & REQUIRE_REMOTE_WIPE);
            mPasswordExpirationDays =
                (int) ((flags & PASSWORD_EXPIRATION_MASK) >> PASSWORD_EXPIRATION_SHIFT);
            mPasswordHistory =
                (int) ((flags & PASSWORD_HISTORY_MASK) >> PASSWORD_HISTORY_SHIFT);
            mPasswordComplexChars =
                (int) ((flags & PASSWORD_COMPLEX_CHARS_MASK) >> PASSWORD_COMPLEX_CHARS_SHIFT);
            mRequireEncryption = 0 != (flags & REQUIRE_ENCRYPTION);
        }

        /**
         * Helper to map our internal encoding to DevicePolicyManager password modes.
         */
        public int getDPManagerPasswordQuality() {
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
         * Helper to map expiration times to the millisecond values used by DevicePolicyManager.
         */
        public long getDPManagerPasswordExpirationTimeout() {
            long result = mPasswordExpirationDays * DAYS_TO_MSEC;
            // Add a small offset to the password expiration.  This makes it easier to test
            // by changing (for example) 1 day to 1 day + 5 minutes.  If you set an expiration
            // that is within the warning period, you should get a warning fairly quickly.
            if (result > 0) {
                result += EXPIRATION_OFFSET_MSEC;
            }
            return result;
        }

        /**
         * Record flags (and a sync key for the flags) into an Account
         * Note: the hash code is defined as the encoding used in Account
         *
         * @param account to write the values mSecurityFlags and mSecuritySyncKey
         * @param syncKey the value to write into the account's mSecuritySyncKey
         * @param update if true, also writes the account back to the provider (updating only
         *  the fields changed by this API)
         * @param context a context for writing to the provider
         * @return true if the actual policies changed, false if no change (note, sync key
         *  does not affect this)
         */
        public boolean writeAccount(Account account, String syncKey, boolean update,
                Context context) {
            long newFlags = getSecurityCode();
            boolean dirty = (newFlags != account.mSecurityFlags);
            account.mSecurityFlags = newFlags;
            account.mSecuritySyncKey = syncKey;
            if (update) {
                if (account.isSaved()) {
                    ContentValues cv = new ContentValues();
                    cv.put(AccountColumns.SECURITY_FLAGS, account.mSecurityFlags);
                    cv.put(AccountColumns.SECURITY_SYNC_KEY, account.mSecuritySyncKey);
                    account.update(context, cv);
                } else {
                    account.save(context);
                }
            }
            return dirty;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof PolicySet) {
                PolicySet other = (PolicySet)o;
                return (this.getSecurityCode() == other.getSecurityCode());
            }
            return false;
        }

        /**
         * Supports Parcelable
         */
        public int describeContents() {
            return 0;
        }

        /**
         * Supports Parcelable
         */
        public static final Parcelable.Creator<PolicySet> CREATOR
                = new Parcelable.Creator<PolicySet>() {
            public PolicySet createFromParcel(Parcel in) {
                return new PolicySet(in);
            }

            public PolicySet[] newArray(int size) {
                return new PolicySet[size];
            }
        };

        /**
         * Supports Parcelable
         */
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mMinPasswordLength);
            dest.writeInt(mPasswordMode);
            dest.writeInt(mMaxPasswordFails);
            dest.writeInt(mMaxScreenLockTime);
            dest.writeInt(mRequireRemoteWipe ? 1 : 0);
            dest.writeInt(mPasswordExpirationDays);
            dest.writeInt(mPasswordHistory);
            dest.writeInt(mPasswordComplexChars);
            dest.writeInt(mRequireEncryption ? 1 : 0);
        }

        /**
         * Supports Parcelable
         */
        public PolicySet(Parcel in) {
            mMinPasswordLength = in.readInt();
            mPasswordMode = in.readInt();
            mMaxPasswordFails = in.readInt();
            mMaxScreenLockTime = in.readInt();
            mRequireRemoteWipe = in.readInt() == 1;
            mPasswordExpirationDays = in.readInt();
            mPasswordHistory = in.readInt();
            mPasswordComplexChars = in.readInt();
            mRequireEncryption = in.readInt() == 1;
        }

        @Override
        public int hashCode() {
            long code = getSecurityCode();
            return (int) code;
        }

        public long getSecurityCode() {
            long flags = 0;
            flags = (long)mMinPasswordLength << PASSWORD_LENGTH_SHIFT;
            flags |= mPasswordMode;
            flags |= (long)mMaxPasswordFails << PASSWORD_MAX_FAILS_SHIFT;
            flags |= (long)mMaxScreenLockTime << SCREEN_LOCK_TIME_SHIFT;
            if (mRequireRemoteWipe) flags |= REQUIRE_REMOTE_WIPE;
            flags |= (long)mPasswordHistory << PASSWORD_HISTORY_SHIFT;
            flags |= (long)mPasswordExpirationDays << PASSWORD_EXPIRATION_SHIFT;
            flags |= (long)mPasswordComplexChars << PASSWORD_COMPLEX_CHARS_SHIFT;
            if (mRequireEncryption) flags |= REQUIRE_ENCRYPTION;
            return flags;
        }

        @Override
        public String toString() {
            return "{ " + "pw-len-min=" + mMinPasswordLength + " pw-mode=" + mPasswordMode
                    + " pw-fails-max=" + mMaxPasswordFails + " screenlock-max="
                    + mMaxScreenLockTime + " remote-wipe-req=" + mRequireRemoteWipe
                    + " pw-expiration=" + mPasswordExpirationDays
                    + " pw-history=" + mPasswordHistory
                    + " pw-complex-chars=" + mPasswordComplexChars
                    + " require-encryption=" + mRequireEncryption + "}";
        }
    }

    /**
     * If we are not the active device admin, try to become so.
     *
     * Also checks for any policies that we have added during the lifetime of this app.
     * This catches the case where the user granted an earlier (smaller) set of policies
     * but an app upgrade requires that new policies be granted.
     *
     * @return true if we are already active, false if we are not
     */
    public boolean isActiveAdmin() {
        DevicePolicyManager dpm = getDPM();
        return dpm.isAdminActive(mAdminName)
                && dpm.hasGrantedPolicy(mAdminName, DeviceAdminInfo.USES_POLICY_EXPIRE_PASSWORD)
                && dpm.hasGrantedPolicy(mAdminName, DeviceAdminInfo.USES_ENCRYPTED_STORAGE);
    }

    /**
     * Report admin component name - for making calls into device policy manager
     */
    public ComponentName getAdminComponent() {
        return mAdminName;
    }

    /**
     * Delete all accounts whose security flags aren't zero (i.e. they have security enabled).
     * This method is synchronous, so it should normally be called within a worker thread (the
     * exception being for unit tests)
     *
     * @param context the caller's context
     */
    /*package*/ void deleteSecuredAccounts(Context context) {
        ContentResolver cr = context.getContentResolver();
        // Find all accounts with security and delete them
        Cursor c = cr.query(Account.CONTENT_URI, EmailContent.ID_PROJECTION,
                AccountColumns.SECURITY_FLAGS + "!=0", null, null);
        try {
            Log.w(TAG, "Email administration disabled; deleting " + c.getCount() +
                    " secured account(s)");
            while (c.moveToNext()) {
                Controller.getInstance(context).deleteAccountSync(
                        c.getLong(EmailContent.ID_PROJECTION_COLUMN), context);
            }
        } finally {
            c.close();
        }
        updatePolicies(-1);
    }

    /**
     * Internal handler for enabled->disabled transitions.  Deletes all secured accounts.
     * Must call from worker thread, not on UI thread.
     */
    /*package*/ void onAdminEnabled(boolean isEnabled) {
        if (!isEnabled) {
            deleteSecuredAccounts(mContext);
        }
    }

    /**
     * Handle password expiration - if any accounts appear to have triggered this, put up
     * warnings, or even shut them down.
     *
     * NOTE:  If there are multiple accounts with password expiration policies, the device
     * password will be set to expire in the shortest required interval (most secure).  The logic
     * in this method operates based on the aggregate setting - irrespective of which account caused
     * the expiration.  In other words, all accounts (that require expiration) will run/stop
     * based on the requirements of the account with the shortest interval.
     */
    private void onPasswordExpiring(Context context) {
        // 1.  Do we have any accounts that matter here?
        long nextExpiringAccountId = findShortestExpiration(context);

        // 2.  If not, exit immediately
        if (nextExpiringAccountId == -1) {
            return;
        }

        // 3.  If yes, are we warning or expired?
        long expirationDate = getDPM().getPasswordExpiration(mAdminName);
        long timeUntilExpiration = expirationDate - System.currentTimeMillis();
        boolean expired = timeUntilExpiration < 0;
        if (!expired) {
            // 4.  If warning, simply put up a generic notification and report that it came from
            // the shortest-expiring account.
            Account account = Account.restoreAccountWithId(context, nextExpiringAccountId);
            if (account == null) return;
            Intent intent = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
            String ticker = context.getString(
                    R.string.password_expire_warning_ticker_fmt, account.getDisplayName());
            String contentTitle = context.getString(
                    R.string.password_expire_warning_content_title);
            String contentText = context.getString(
                    R.string.password_expire_warning_content_text_fmt, account.getDisplayName());
            NotificationController nc = NotificationController.getInstance(mContext);
            nc.postAccountNotification(account, ticker, contentTitle, contentText, intent,
                    NotificationController.NOTIFICATION_ID_PASSWORD_EXPIRING);
        } else {
            // 5.  Actually expired - find all accounts that expire passwords, and wipe them
            boolean wiped = wipeExpiredAccounts(context, Controller.getInstance(context));
            if (wiped) {
                // Post notification
                Account account = Account.restoreAccountWithId(context, nextExpiringAccountId);
                if (account == null) return;
                Intent intent =
                    new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
                String ticker = context.getString(R.string.password_expired_ticker);
                String contentTitle = context.getString(R.string.password_expired_content_title);
                String contentText = context.getString(R.string.password_expired_content_text);
                NotificationController nc = NotificationController.getInstance(mContext);
                nc.postAccountNotification(account, ticker, contentTitle,
                        contentText, intent,
                        NotificationController.NOTIFICATION_ID_PASSWORD_EXPIRED);
            }
        }
    }

    /**
     * Find the account with the shortest expiration time.  This is always assumed to be
     * the account that forces the password to be refreshed.
     * @return -1 if no expirations, or accountId if one is found
     */
    /* package */ static long findShortestExpiration(Context context) {
        long nextExpiringAccountId = -1;
        long shortestExpiration = Long.MAX_VALUE;
        Cursor c = context.getContentResolver().query(Account.CONTENT_URI,
                ACCOUNT_SECURITY_PROJECTION, Account.SECURITY_NONZERO_SELECTION, null, null);
        try {
            while (c.moveToNext()) {
                long flags = c.getLong(ACCOUNT_SECURITY_COLUMN_FLAGS);
                if (flags != 0) {
                    PolicySet p = new PolicySet(flags);
                    if (p.mPasswordExpirationDays > 0 &&
                            p.mPasswordExpirationDays < shortestExpiration) {
                        nextExpiringAccountId = c.getLong(ACCOUNT_SECURITY_COLUMN_ID);
                        shortestExpiration = p.mPasswordExpirationDays;
                    }
                }
            }
        } finally {
            c.close();
        }
        return nextExpiringAccountId;
    }

    /**
     * For all accounts that require password expiration, put them in security hold and wipe
     * their data.
     * @param context
     * @param controller
     * @return true if one or more accounts were wiped
     */
    /* package */ static boolean wipeExpiredAccounts(Context context, Controller controller) {
        boolean result = false;
        Cursor c = context.getContentResolver().query(Account.CONTENT_URI,
                ACCOUNT_SECURITY_PROJECTION, Account.SECURITY_NONZERO_SELECTION, null, null);
        try {
            while (c.moveToNext()) {
                long flags = c.getLong(ACCOUNT_SECURITY_COLUMN_FLAGS);
                if (flags != 0) {
                    PolicySet p = new PolicySet(flags);
                    if (p.mPasswordExpirationDays > 0) {
                        long accountId = c.getLong(ACCOUNT_SECURITY_COLUMN_ID);
                        Account account = Account.restoreAccountWithId(context, accountId);
                        if (account != null) {
                            // Mark the account as "on hold".
                            setAccountHoldFlag(context, account, true);
                            // Erase data
                            controller.deleteSyncedDataSync(accountId);
                            // Report one or more were found
                            result = true;
                        }
                    }
                }
            }
        } finally {
            c.close();
        }
        return result;
    }

    /**
     * Callback from EmailBroadcastProcessorService.  This provides the workers for the
     * DeviceAdminReceiver calls.  These should perform the work directly and not use async
     * threads for completion.
     */
    public static void onDeviceAdminReceiverMessage(Context context, int message) {
        SecurityPolicy instance = SecurityPolicy.getInstance(context);
        switch (message) {
            case DEVICE_ADMIN_MESSAGE_ENABLED:
                instance.onAdminEnabled(true);
                break;
            case DEVICE_ADMIN_MESSAGE_DISABLED:
                instance.onAdminEnabled(false);
                break;
            case DEVICE_ADMIN_MESSAGE_PASSWORD_CHANGED:
                // TODO make a small helper for this
                // Clear security holds (if any)
                Account.clearSecurityHoldOnAllAccounts(context);
                // Cancel any active notifications (if any are posted)
                NotificationController nc = NotificationController.getInstance(context);
                nc.cancelNotification(NotificationController.NOTIFICATION_ID_PASSWORD_EXPIRING);
                nc.cancelNotification(NotificationController.NOTIFICATION_ID_PASSWORD_EXPIRED);
                break;
            case DEVICE_ADMIN_MESSAGE_PASSWORD_EXPIRING:
                instance.onPasswordExpiring(instance.mContext);
                break;
        }
    }

    /**
     * Device Policy administrator.  This is primarily a listener for device state changes.
     * Note:  This is instantiated by incoming messages.
     * Note:  This is actually a BroadcastReceiver and must remain within the guidelines required
     *        for proper behavior, including avoidance of ANRs.
     * Note:  We do not implement onPasswordFailed() because the default behavior of the
     *        DevicePolicyManager - complete local wipe after 'n' failures - is sufficient.
     */
    public static class PolicyAdmin extends DeviceAdminReceiver {

        /**
         * Called after the administrator is first enabled.
         */
        @Override
        public void onEnabled(Context context, Intent intent) {
            EmailBroadcastProcessorService.processDevicePolicyMessage(context,
                    DEVICE_ADMIN_MESSAGE_ENABLED);
        }

        /**
         * Called prior to the administrator being disabled.
         */
        @Override
        public void onDisabled(Context context, Intent intent) {
            EmailBroadcastProcessorService.processDevicePolicyMessage(context,
                    DEVICE_ADMIN_MESSAGE_DISABLED);
        }

        /**
         * Called when the user asks to disable administration; we return a warning string that
         * will be presented to the user
         */
        @Override
        public CharSequence onDisableRequested(Context context, Intent intent) {
            return context.getString(R.string.disable_admin_warning);
        }

        /**
         * Called after the user has changed their password.
         */
        @Override
        public void onPasswordChanged(Context context, Intent intent) {
            EmailBroadcastProcessorService.processDevicePolicyMessage(context,
                    DEVICE_ADMIN_MESSAGE_PASSWORD_CHANGED);
        }

        /**
         * Called when device password is expiring
         */
        @Override
        public void onPasswordExpiring(Context context, Intent intent) {
            EmailBroadcastProcessorService.processDevicePolicyMessage(context,
                    DEVICE_ADMIN_MESSAGE_PASSWORD_EXPIRING);
        }
    }
}
