/* Copyright (C) 2011 The Android Open Source Project
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

import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;

import android.app.admin.DevicePolicyManager;
import android.content.ContentValues;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;


/**
 * Class for tracking policies and reading/writing into accounts
 */
public class PolicySet implements Parcelable {

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

    public final int mMinPasswordLength;
    public final int mPasswordMode;
    public final int mMaxPasswordFails;
    public final int mMaxScreenLockTime;
    public final boolean mRequireRemoteWipe;
    public final int mPasswordExpirationDays;
    public final int mPasswordHistory;
    public final int mPasswordComplexChars;
    public final boolean mRequireEncryption;

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

