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

import com.android.emailcommon.provider.Policy;

/**
 * Legacy class for policy storage as a bit field of flags
 */
public class LegacyPolicySet {

    // Security (provisioning) flags
        // bits 0..4: password length (0=no password required)
    public static final int PASSWORD_LENGTH_MASK = 31;
    public static final int PASSWORD_LENGTH_SHIFT = 0;
    public static final int PASSWORD_LENGTH_MAX = 30;
        // bits 5..8: password mode
    public static final int PASSWORD_MODE_SHIFT = 5;
    public static final int PASSWORD_MODE_MASK = 15 << PASSWORD_MODE_SHIFT;
    public static final int PASSWORD_MODE_NONE = 0 << PASSWORD_MODE_SHIFT;
    public static final int PASSWORD_MODE_SIMPLE = 1 << PASSWORD_MODE_SHIFT;
    public static final int PASSWORD_MODE_STRONG = 2 << PASSWORD_MODE_SHIFT;
        // bits 9..13: password failures -> wipe device (0=disabled)
    public static final int PASSWORD_MAX_FAILS_SHIFT = 9;
    public static final int PASSWORD_MAX_FAILS_MASK = 31 << PASSWORD_MAX_FAILS_SHIFT;
    public static final int PASSWORD_MAX_FAILS_MAX = 31;
        // bits 14..24: seconds to screen lock (0=not required)
    public static final int SCREEN_LOCK_TIME_SHIFT = 14;
    public static final int SCREEN_LOCK_TIME_MASK = 2047 << SCREEN_LOCK_TIME_SHIFT;
    public static final int SCREEN_LOCK_TIME_MAX = 2047;
        // bit 25: remote wipe capability required
    public static final int REQUIRE_REMOTE_WIPE = 1 << 25;
        // bit 26..35: password expiration (days; 0=not required)
    public static final int PASSWORD_EXPIRATION_SHIFT = 26;
    public static final long PASSWORD_EXPIRATION_MASK = 1023L << PASSWORD_EXPIRATION_SHIFT;
    public static final int PASSWORD_EXPIRATION_MAX = 1023;
        // bit 36..43: password history (length; 0=not required)
    public static final int PASSWORD_HISTORY_SHIFT = 36;
    public static final long PASSWORD_HISTORY_MASK = 255L << PASSWORD_HISTORY_SHIFT;
    public static final int PASSWORD_HISTORY_MAX = 255;
        // bit 44..48: min complex characters (0=not required)
    public static final int PASSWORD_COMPLEX_CHARS_SHIFT = 44;
    public static final long PASSWORD_COMPLEX_CHARS_MASK = 31L << PASSWORD_COMPLEX_CHARS_SHIFT;
    public static final int PASSWORD_COMPLEX_CHARS_MAX = 31;
        // bit 49: requires device encryption (internal)
    public static final long REQUIRE_ENCRYPTION = 1L << 49;
        // bit 50: requires external storage encryption
    public static final long REQUIRE_ENCRYPTION_EXTERNAL = 1L << 50;

    /**
     * Convert legacy policy flags to a Policy
     * @param flags legacy policy flags
     * @return a Policy representing the legacy policy flag
     */
    public static Policy flagsToPolicy(long flags) {
        Policy policy = new Policy();
        policy.mPasswordMode = ((int) (flags & PASSWORD_MODE_MASK)) >> PASSWORD_MODE_SHIFT;
        policy.mPasswordMinLength = (int) ((flags & PASSWORD_LENGTH_MASK) >> PASSWORD_LENGTH_SHIFT);
        policy.mPasswordMaxFails =
            (int) ((flags & PASSWORD_MAX_FAILS_MASK) >> PASSWORD_MAX_FAILS_SHIFT);
        policy.mPasswordComplexChars =
            (int) ((flags & PASSWORD_COMPLEX_CHARS_MASK) >> PASSWORD_COMPLEX_CHARS_SHIFT);
        policy.mPasswordHistory = (int) ((flags & PASSWORD_HISTORY_MASK) >> PASSWORD_HISTORY_SHIFT);
        policy.mPasswordExpirationDays =
            (int) ((flags & PASSWORD_EXPIRATION_MASK) >> PASSWORD_EXPIRATION_SHIFT);
        policy.mMaxScreenLockTime =
            (int) ((flags & SCREEN_LOCK_TIME_MASK) >> SCREEN_LOCK_TIME_SHIFT);
        policy.mRequireRemoteWipe = 0 != (flags & REQUIRE_REMOTE_WIPE);
        policy.mRequireEncryption = 0 != (flags & REQUIRE_ENCRYPTION);
        policy.mRequireEncryptionExternal = 0 != (flags & REQUIRE_ENCRYPTION_EXTERNAL);
        return policy;
    }
}

