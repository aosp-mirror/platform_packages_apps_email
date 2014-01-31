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

package com.android.emailcommon.provider;
import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.emailcommon.utility.TextUtilities;
import com.android.emailcommon.utility.Utility;

import java.util.ArrayList;

/**
 * The Policy class represents a set of security requirements that are associated with an Account.
 * The requirements may be either device-specific (e.g. password) or application-specific (e.g.
 * a limit on the sync window for the Account)
 */
public final class Policy extends EmailContent implements EmailContent.PolicyColumns, Parcelable {
    public static final boolean DEBUG_POLICY = false;  // DO NOT SUBMIT WITH THIS SET TO TRUE
    public static final String TAG = "Email/Policy";

    public static final String TABLE_NAME = "Policy";
    public static Uri CONTENT_URI;

    public static void initPolicy() {
        CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/policy");
    }

    /* Convert days to mSec (used for password expiration) */
    private static final long DAYS_TO_MSEC = 24 * 60 * 60 * 1000;
    /* Small offset (2 minutes) added to policy expiration to make user testing easier. */
    private static final long EXPIRATION_OFFSET_MSEC = 2 * 60 * 1000;

    public static final int PASSWORD_MODE_NONE = 0;
    public static final int PASSWORD_MODE_SIMPLE = 1;
    public static final int PASSWORD_MODE_STRONG = 2;

    public static final char POLICY_STRING_DELIMITER = '\1';

    public int mPasswordMode;
    public int mPasswordMinLength;
    public int mPasswordMaxFails;
    public int mPasswordExpirationDays;
    public int mPasswordHistory;
    public int mPasswordComplexChars;
    public int mMaxScreenLockTime;
    public boolean mRequireRemoteWipe;
    public boolean mRequireEncryption;
    public boolean mRequireEncryptionExternal;
    public boolean mRequireManualSyncWhenRoaming;
    public boolean mDontAllowCamera;
    public boolean mDontAllowAttachments;
    public boolean mDontAllowHtml;
    public int mMaxAttachmentSize;
    public int mMaxTextTruncationSize;
    public int mMaxHtmlTruncationSize;
    public int mMaxEmailLookback;
    public int mMaxCalendarLookback;
    public boolean mPasswordRecoveryEnabled;
    public String mProtocolPoliciesEnforced;
    public String mProtocolPoliciesUnsupported;

    public static final int CONTENT_ID_COLUMN = 0;
    public static final int CONTENT_PASSWORD_MODE_COLUMN = 1;
    public static final int CONTENT_PASSWORD_MIN_LENGTH_COLUMN = 2;
    public static final int CONTENT_PASSWORD_EXPIRATION_DAYS_COLUMN = 3;
    public static final int CONTENT_PASSWORD_HISTORY_COLUMN = 4;
    public static final int CONTENT_PASSWORD_COMPLEX_CHARS_COLUMN = 5;
    public static final int CONTENT_PASSWORD_MAX_FAILS_COLUMN = 6;
    public static final int CONTENT_MAX_SCREEN_LOCK_TIME_COLUMN = 7;
    public static final int CONTENT_REQUIRE_REMOTE_WIPE_COLUMN = 8;
    public static final int CONTENT_REQUIRE_ENCRYPTION_COLUMN = 9;
    public static final int CONTENT_REQUIRE_ENCRYPTION_EXTERNAL_COLUMN = 10;
    public static final int CONTENT_REQUIRE_MANUAL_SYNC_WHEN_ROAMING = 11;
    public static final int CONTENT_DONT_ALLOW_CAMERA_COLUMN = 12;
    public static final int CONTENT_DONT_ALLOW_ATTACHMENTS_COLUMN = 13;
    public static final int CONTENT_DONT_ALLOW_HTML_COLUMN = 14;
    public static final int CONTENT_MAX_ATTACHMENT_SIZE_COLUMN = 15;
    public static final int CONTENT_MAX_TEXT_TRUNCATION_SIZE_COLUMN = 16;
    public static final int CONTENT_MAX_HTML_TRUNCATION_SIZE_COLUMN = 17;
    public static final int CONTENT_MAX_EMAIL_LOOKBACK_COLUMN = 18;
    public static final int CONTENT_MAX_CALENDAR_LOOKBACK_COLUMN = 19;
    public static final int CONTENT_PASSWORD_RECOVERY_ENABLED_COLUMN = 20;
    public static final int CONTENT_PROTOCOL_POLICIES_ENFORCED_COLUMN = 21;
    public static final int CONTENT_PROTOCOL_POLICIES_UNSUPPORTED_COLUMN = 22;

    public static final String[] CONTENT_PROJECTION = new String[] {RECORD_ID,
        PolicyColumns.PASSWORD_MODE, PolicyColumns.PASSWORD_MIN_LENGTH,
        PolicyColumns.PASSWORD_EXPIRATION_DAYS, PolicyColumns.PASSWORD_HISTORY,
        PolicyColumns.PASSWORD_COMPLEX_CHARS, PolicyColumns.PASSWORD_MAX_FAILS,
        PolicyColumns.MAX_SCREEN_LOCK_TIME, PolicyColumns.REQUIRE_REMOTE_WIPE,
        PolicyColumns.REQUIRE_ENCRYPTION, PolicyColumns.REQUIRE_ENCRYPTION_EXTERNAL,
        PolicyColumns.REQUIRE_MANUAL_SYNC_WHEN_ROAMING, PolicyColumns.DONT_ALLOW_CAMERA,
        PolicyColumns.DONT_ALLOW_ATTACHMENTS, PolicyColumns.DONT_ALLOW_HTML,
        PolicyColumns.MAX_ATTACHMENT_SIZE, PolicyColumns.MAX_TEXT_TRUNCATION_SIZE,
        PolicyColumns.MAX_HTML_TRUNCATION_SIZE, PolicyColumns.MAX_EMAIL_LOOKBACK,
        PolicyColumns.MAX_CALENDAR_LOOKBACK, PolicyColumns.PASSWORD_RECOVERY_ENABLED,
        PolicyColumns.PROTOCOL_POLICIES_ENFORCED, PolicyColumns.PROTOCOL_POLICIES_UNSUPPORTED
    };

    public static final Policy NO_POLICY = new Policy();

    private static final String[] ATTACHMENT_RESET_PROJECTION =
        new String[] {EmailContent.RECORD_ID, AttachmentColumns.SIZE, AttachmentColumns.FLAGS};
    private static final int ATTACHMENT_RESET_PROJECTION_ID = 0;
    private static final int ATTACHMENT_RESET_PROJECTION_SIZE = 1;
    private static final int ATTACHMENT_RESET_PROJECTION_FLAGS = 2;

    public Policy() {
        mBaseUri = CONTENT_URI;
        // By default, the password mode is "none"
        mPasswordMode = PASSWORD_MODE_NONE;
        // All server policies require the ability to wipe the device
        mRequireRemoteWipe = true;
    }

    public static Policy restorePolicyWithId(Context context, long id) {
        return restorePolicyWithId(context, id, null);
    }

    public static Policy restorePolicyWithId(Context context, long id, ContentObserver observer) {
        return EmailContent.restoreContentWithId(context, Policy.class, Policy.CONTENT_URI,
                Policy.CONTENT_PROJECTION, id, observer);
    }

    @Override
    protected Uri getContentNotificationUri() {
        return Policy.CONTENT_URI;
    }

    public static long getAccountIdWithPolicyKey(Context context, long id) {
        return Utility.getFirstRowLong(context, Account.CONTENT_URI, Account.ID_PROJECTION,
                AccountColumns.POLICY_KEY + "=?", new String[] {Long.toString(id)}, null,
                Account.ID_PROJECTION_COLUMN, Account.NO_ACCOUNT);
    }

    public static ArrayList<String> addPolicyStringToList(String policyString,
            ArrayList<String> policyList) {
        if (policyString != null) {
            int start = 0;
            int len = policyString.length();
            while(start < len) {
                int end = policyString.indexOf(POLICY_STRING_DELIMITER, start);
                if (end > start) {
                    policyList.add(policyString.substring(start, end));
                    start = end + 1;
                } else {
                    break;
                }
            }
        }
        return policyList;
    }

    // We override this method to insure that we never write invalid policy data to the provider
    @Override
    public Uri save(Context context) {
        normalize();
        return super.save(context);
    }

    /**
     * Review all attachment records for this account, and reset the "don't allow download" flag
     * as required by the account's new security policies
     * @param context the caller's context
     * @param account the account whose attachments need to be reviewed
     * @param policy the new policy for this account
     */
    public static void setAttachmentFlagsForNewPolicy(Context context, Account account,
            Policy policy) {
        // A nasty bit of work; start with all attachments for a given account
        ContentResolver resolver = context.getContentResolver();
        Cursor c = resolver.query(Attachment.CONTENT_URI, ATTACHMENT_RESET_PROJECTION,
                AttachmentColumns.ACCOUNT_KEY + "=?", new String[] {Long.toString(account.mId)},
                null);
        ContentValues cv = new ContentValues();
        try {
            // Get maximum allowed size (0 if we don't allow attachments at all)
            int policyMax = policy.mDontAllowAttachments ? 0 : (policy.mMaxAttachmentSize > 0) ?
                    policy.mMaxAttachmentSize : Integer.MAX_VALUE;
            while (c.moveToNext()) {
                int flags = c.getInt(ATTACHMENT_RESET_PROJECTION_FLAGS);
                int size = c.getInt(ATTACHMENT_RESET_PROJECTION_SIZE);
                boolean wasRestricted = (flags & Attachment.FLAG_POLICY_DISALLOWS_DOWNLOAD) != 0;
                boolean isRestricted = size > policyMax;
                if (isRestricted != wasRestricted) {
                    if (isRestricted) {
                        flags |= Attachment.FLAG_POLICY_DISALLOWS_DOWNLOAD;
                    } else {
                        flags &= ~Attachment.FLAG_POLICY_DISALLOWS_DOWNLOAD;
                    }
                    long id = c.getLong(ATTACHMENT_RESET_PROJECTION_ID);
                    cv.put(AttachmentColumns.FLAGS, flags);
                    resolver.update(ContentUris.withAppendedId(Attachment.CONTENT_URI, id),
                            cv, null, null);
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * Normalize the Policy.  If the password mode is "none", zero out all password-related fields;
     * zero out complex characters for simple passwords.
     */
    public void normalize() {
        if (mPasswordMode == PASSWORD_MODE_NONE) {
            mPasswordMaxFails = 0;
            mMaxScreenLockTime = 0;
            mPasswordMinLength = 0;
            mPasswordComplexChars = 0;
            mPasswordHistory = 0;
            mPasswordExpirationDays = 0;
        } else {
            if ((mPasswordMode != PASSWORD_MODE_SIMPLE) &&
                    (mPasswordMode != PASSWORD_MODE_STRONG)) {
                throw new IllegalArgumentException("password mode");
            }
            // If we're only requiring a simple password, set complex chars to zero; note
            // that EAS can erroneously send non-zero values in this case
            if (mPasswordMode == PASSWORD_MODE_SIMPLE) {
                mPasswordComplexChars = 0;
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Policy)) return false;
        Policy otherPolicy = (Policy)other;
        // Policies here are enforced by the DPM
        if (mRequireEncryption != otherPolicy.mRequireEncryption) return false;
        if (mRequireEncryptionExternal != otherPolicy.mRequireEncryptionExternal) return false;
        if (mRequireRemoteWipe != otherPolicy.mRequireRemoteWipe) return false;
        if (mMaxScreenLockTime != otherPolicy.mMaxScreenLockTime) return false;
        if (mPasswordComplexChars != otherPolicy.mPasswordComplexChars) return false;
        if (mPasswordExpirationDays != otherPolicy.mPasswordExpirationDays) return false;
        if (mPasswordHistory != otherPolicy.mPasswordHistory) return false;
        if (mPasswordMaxFails != otherPolicy.mPasswordMaxFails) return false;
        if (mPasswordMinLength != otherPolicy.mPasswordMinLength) return false;
        if (mPasswordMode != otherPolicy.mPasswordMode) return false;
        if (mDontAllowCamera != otherPolicy.mDontAllowCamera) return false;

        // Policies here are enforced by the Exchange sync manager
        // They should eventually be removed from Policy and replaced with some opaque data
        if (mRequireManualSyncWhenRoaming != otherPolicy.mRequireManualSyncWhenRoaming) {
            return false;
        }
        if (mDontAllowAttachments != otherPolicy.mDontAllowAttachments) return false;
        if (mDontAllowHtml != otherPolicy.mDontAllowHtml) return false;
        if (mMaxAttachmentSize != otherPolicy.mMaxAttachmentSize) return false;
        if (mMaxTextTruncationSize != otherPolicy.mMaxTextTruncationSize) return false;
        if (mMaxHtmlTruncationSize != otherPolicy.mMaxHtmlTruncationSize) return false;
        if (mMaxEmailLookback != otherPolicy.mMaxEmailLookback) return false;
        if (mMaxCalendarLookback != otherPolicy.mMaxCalendarLookback) return false;
        if (mPasswordRecoveryEnabled != otherPolicy.mPasswordRecoveryEnabled) return false;

        if (!TextUtilities.stringOrNullEquals(mProtocolPoliciesEnforced,
                otherPolicy.mProtocolPoliciesEnforced)) {
            return false;
        }
        if (!TextUtilities.stringOrNullEquals(mProtocolPoliciesUnsupported,
                otherPolicy.mProtocolPoliciesUnsupported)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int code = mRequireEncryption ? 1 : 0;
        code += (mRequireEncryptionExternal ? 1 : 0) << 1;
        code += (mRequireRemoteWipe ? 1 : 0) << 2;
        code += (mMaxScreenLockTime << 3);
        code += (mPasswordComplexChars << 6);
        code += (mPasswordExpirationDays << 12);
        code += (mPasswordHistory << 15);
        code += (mPasswordMaxFails << 18);
        code += (mPasswordMinLength << 22);
        code += (mPasswordMode << 26);
        // Don't need to include the other fields
        return code;
    }

    @Override
    public void restore(Cursor cursor) {
        mBaseUri = CONTENT_URI;
        mId = cursor.getLong(CONTENT_ID_COLUMN);
        mPasswordMode = cursor.getInt(CONTENT_PASSWORD_MODE_COLUMN);
        mPasswordMinLength = cursor.getInt(CONTENT_PASSWORD_MIN_LENGTH_COLUMN);
        mPasswordMaxFails = cursor.getInt(CONTENT_PASSWORD_MAX_FAILS_COLUMN);
        mPasswordHistory = cursor.getInt(CONTENT_PASSWORD_HISTORY_COLUMN);
        mPasswordExpirationDays = cursor.getInt(CONTENT_PASSWORD_EXPIRATION_DAYS_COLUMN);
        mPasswordComplexChars = cursor.getInt(CONTENT_PASSWORD_COMPLEX_CHARS_COLUMN);
        mMaxScreenLockTime = cursor.getInt(CONTENT_MAX_SCREEN_LOCK_TIME_COLUMN);
        mRequireRemoteWipe = cursor.getInt(CONTENT_REQUIRE_REMOTE_WIPE_COLUMN) == 1;
        mRequireEncryption = cursor.getInt(CONTENT_REQUIRE_ENCRYPTION_COLUMN) == 1;
        mRequireEncryptionExternal =
            cursor.getInt(CONTENT_REQUIRE_ENCRYPTION_EXTERNAL_COLUMN) == 1;
        mRequireManualSyncWhenRoaming =
            cursor.getInt(CONTENT_REQUIRE_MANUAL_SYNC_WHEN_ROAMING) == 1;
        mDontAllowCamera = cursor.getInt(CONTENT_DONT_ALLOW_CAMERA_COLUMN) == 1;
        mDontAllowAttachments = cursor.getInt(CONTENT_DONT_ALLOW_ATTACHMENTS_COLUMN) == 1;
        mDontAllowHtml = cursor.getInt(CONTENT_DONT_ALLOW_HTML_COLUMN) == 1;
        mMaxAttachmentSize = cursor.getInt(CONTENT_MAX_ATTACHMENT_SIZE_COLUMN);
        mMaxTextTruncationSize = cursor.getInt(CONTENT_MAX_TEXT_TRUNCATION_SIZE_COLUMN);
        mMaxHtmlTruncationSize = cursor.getInt(CONTENT_MAX_HTML_TRUNCATION_SIZE_COLUMN);
        mMaxEmailLookback = cursor.getInt(CONTENT_MAX_EMAIL_LOOKBACK_COLUMN);
        mMaxCalendarLookback = cursor.getInt(CONTENT_MAX_CALENDAR_LOOKBACK_COLUMN);
        mPasswordRecoveryEnabled = cursor.getInt(CONTENT_PASSWORD_RECOVERY_ENABLED_COLUMN) == 1;
        mProtocolPoliciesEnforced = cursor.getString(CONTENT_PROTOCOL_POLICIES_ENFORCED_COLUMN);
        mProtocolPoliciesUnsupported =
            cursor.getString(CONTENT_PROTOCOL_POLICIES_UNSUPPORTED_COLUMN);
    }

    @Override
    public ContentValues toContentValues() {
        ContentValues values = new ContentValues();
        values.put(PolicyColumns.PASSWORD_MODE, mPasswordMode);
        values.put(PolicyColumns.PASSWORD_MIN_LENGTH, mPasswordMinLength);
        values.put(PolicyColumns.PASSWORD_MAX_FAILS, mPasswordMaxFails);
        values.put(PolicyColumns.PASSWORD_HISTORY, mPasswordHistory);
        values.put(PolicyColumns.PASSWORD_EXPIRATION_DAYS, mPasswordExpirationDays);
        values.put(PolicyColumns.PASSWORD_COMPLEX_CHARS, mPasswordComplexChars);
        values.put(PolicyColumns.MAX_SCREEN_LOCK_TIME, mMaxScreenLockTime);
        values.put(PolicyColumns.REQUIRE_REMOTE_WIPE, mRequireRemoteWipe);
        values.put(PolicyColumns.REQUIRE_ENCRYPTION, mRequireEncryption);
        values.put(PolicyColumns.REQUIRE_ENCRYPTION_EXTERNAL, mRequireEncryptionExternal);
        values.put(PolicyColumns.REQUIRE_MANUAL_SYNC_WHEN_ROAMING, mRequireManualSyncWhenRoaming);
        values.put(PolicyColumns.DONT_ALLOW_CAMERA, mDontAllowCamera);
        values.put(PolicyColumns.DONT_ALLOW_ATTACHMENTS, mDontAllowAttachments);
        values.put(PolicyColumns.DONT_ALLOW_HTML, mDontAllowHtml);
        values.put(PolicyColumns.MAX_ATTACHMENT_SIZE, mMaxAttachmentSize);
        values.put(PolicyColumns.MAX_TEXT_TRUNCATION_SIZE, mMaxTextTruncationSize);
        values.put(PolicyColumns.MAX_HTML_TRUNCATION_SIZE, mMaxHtmlTruncationSize);
        values.put(PolicyColumns.MAX_EMAIL_LOOKBACK, mMaxEmailLookback);
        values.put(PolicyColumns.MAX_CALENDAR_LOOKBACK, mMaxCalendarLookback);
        values.put(PolicyColumns.PASSWORD_RECOVERY_ENABLED, mPasswordRecoveryEnabled);
        values.put(PolicyColumns.PROTOCOL_POLICIES_ENFORCED, mProtocolPoliciesEnforced);
        values.put(PolicyColumns.PROTOCOL_POLICIES_UNSUPPORTED, mProtocolPoliciesUnsupported);
        return values;
    }

    /**
     * Helper to map our internal encoding to DevicePolicyManager password modes.
     */
    public int getDPManagerPasswordQuality() {
        switch (mPasswordMode) {
            case PASSWORD_MODE_SIMPLE:
                return DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
            case PASSWORD_MODE_STRONG:
                if (mPasswordComplexChars == 0) {
                    return DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC;
                } else {
                    return DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
                }
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

    private static void appendPolicy(StringBuilder sb, String code, int value) {
        sb.append(code);
        sb.append(":");
        sb.append(value);
        sb.append(" ");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        if (equals(NO_POLICY)) {
            sb.append("No policies]");
        } else {
            if (mPasswordMode == PASSWORD_MODE_NONE) {
                sb.append("Pwd none ");
            } else {
                appendPolicy(sb, "Pwd strong", mPasswordMode == PASSWORD_MODE_STRONG ? 1 : 0);
                appendPolicy(sb, "len", mPasswordMinLength);
                appendPolicy(sb, "cmpx", mPasswordComplexChars);
                appendPolicy(sb, "expy", mPasswordExpirationDays);
                appendPolicy(sb, "hist", mPasswordHistory);
                appendPolicy(sb, "fail", mPasswordMaxFails);
                appendPolicy(sb, "idle", mMaxScreenLockTime);
            }
            if (mRequireEncryption) {
                sb.append("encrypt ");
            }
            if (mRequireEncryptionExternal) {
                sb.append("encryptsd ");
            }
            if (mDontAllowCamera) {
                sb.append("nocamera ");
            }
            if (mDontAllowAttachments) {
                sb.append("noatts ");
            }
            if (mRequireManualSyncWhenRoaming) {
                sb.append("nopushroam ");
            }
            if (mMaxAttachmentSize > 0) {
                appendPolicy(sb, "attmax", mMaxAttachmentSize);
            }
            sb.append("]");
        }
        return sb.toString();
    }

    /**
     * Supports Parcelable
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Supports Parcelable
     */
    public static final Parcelable.Creator<Policy> CREATOR = new Parcelable.Creator<Policy>() {
        @Override
        public Policy createFromParcel(Parcel in) {
            return new Policy(in);
        }

        @Override
        public Policy[] newArray(int size) {
            return new Policy[size];
        }
    };

    /**
     * Supports Parcelable
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // mBaseUri is not parceled
        dest.writeLong(mId);
        dest.writeInt(mPasswordMode);
        dest.writeInt(mPasswordMinLength);
        dest.writeInt(mPasswordMaxFails);
        dest.writeInt(mPasswordHistory);
        dest.writeInt(mPasswordExpirationDays);
        dest.writeInt(mPasswordComplexChars);
        dest.writeInt(mMaxScreenLockTime);
        dest.writeInt(mRequireRemoteWipe ? 1 : 0);
        dest.writeInt(mRequireEncryption ? 1 : 0);
        dest.writeInt(mRequireEncryptionExternal ? 1 : 0);
        dest.writeInt(mRequireManualSyncWhenRoaming ? 1 : 0);
        dest.writeInt(mDontAllowCamera ? 1 : 0);
        dest.writeInt(mDontAllowAttachments ? 1 : 0);
        dest.writeInt(mDontAllowHtml ? 1 : 0);
        dest.writeInt(mMaxAttachmentSize);
        dest.writeInt(mMaxTextTruncationSize);
        dest.writeInt(mMaxHtmlTruncationSize);
        dest.writeInt(mMaxEmailLookback);
        dest.writeInt(mMaxCalendarLookback);
        dest.writeInt(mPasswordRecoveryEnabled ? 1 : 0);
        dest.writeString(mProtocolPoliciesEnforced);
        dest.writeString(mProtocolPoliciesUnsupported);
    }

    /**
     * Supports Parcelable
     */
    public Policy(Parcel in) {
        mBaseUri = CONTENT_URI;
        mId = in.readLong();
        mPasswordMode = in.readInt();
        mPasswordMinLength = in.readInt();
        mPasswordMaxFails = in.readInt();
        mPasswordHistory = in.readInt();
        mPasswordExpirationDays = in.readInt();
        mPasswordComplexChars = in.readInt();
        mMaxScreenLockTime = in.readInt();
        mRequireRemoteWipe = in.readInt() == 1;
        mRequireEncryption = in.readInt() == 1;
        mRequireEncryptionExternal = in.readInt() == 1;
        mRequireManualSyncWhenRoaming = in.readInt() == 1;
        mDontAllowCamera = in.readInt() == 1;
        mDontAllowAttachments = in.readInt() == 1;
        mDontAllowHtml = in.readInt() == 1;
        mMaxAttachmentSize = in.readInt();
        mMaxTextTruncationSize = in.readInt();
        mMaxHtmlTruncationSize = in.readInt();
        mMaxEmailLookback = in.readInt();
        mMaxCalendarLookback = in.readInt();
        mPasswordRecoveryEnabled = in.readInt() == 1;
        mProtocolPoliciesEnforced = in.readString();
        mProtocolPoliciesUnsupported = in.readString();
    }
}