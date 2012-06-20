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

package com.android.emailcommon;

import android.content.Context;

import com.android.emailcommon.provider.Account;

/**
 * Constants for tagging threads for traffic stats, and associated utilities
 *
 * Example usage:
 *  TrafficStats.setThreadStatsTag(accountId | DATA_EMAIL | REASON_SYNC);
 */
public class TrafficFlags {
    // Bits 0->15, account id
    private static final int ACCOUNT_MASK = 0x0000FFFF;

    // Bits 18&19, type (0 = EMAIL)
    private static final int DATA_SHIFT = 18;
    private static final int DATA_MASK = 3 << DATA_SHIFT;
    public static final int DATA_EMAIL = 0 << DATA_SHIFT;
    public static final int DATA_CONTACTS = 1 << DATA_SHIFT;
    public static final int DATA_CALENDAR = 2 << DATA_SHIFT;

    // Bits 20&21, reason (if protocol != SMTP)
    private static final int REASON_SHIFT = 20;
    private static final int REASON_MASK = 3 << REASON_SHIFT;
    public static final int REASON_SYNC = 0 << REASON_SHIFT;
    public static final int REASON_ATTACHMENT_USER = 1 << REASON_SHIFT;
    // Note: We don't yet use the PRECACHE reason (it's complicated...)
    public static final int REASON_ATTACHMENT_PRECACHE = 2 << REASON_SHIFT;
    private static final String[] REASONS = new String[] {"sync", "attachment", "precache"};

    /**
     * Get flags indicating sync of the passed-in account; note that, by default, these flags
     * indicate an email sync; to change the type of sync, simply "or" in DATA_CONTACTS or
     * DATA_CALENDAR (since DATA_EMAIL = 0)
     *
     * @param context the caller's context
     * @param account the account being used
     * @return flags for syncing this account
     */
    public static int getSyncFlags(Context context, Account account) {
        return (int)account.mId | REASON_SYNC;
    }

    /**
     * Get flags indicating attachment loading from the passed-in account
     *
     * @param context the caller's context
     * @param account the account being used
     * @return flags for loading an attachment in this account
     */
    public static int getAttachmentFlags(Context context, Account account) {
        return (int)account.mId | REASON_ATTACHMENT_USER;
    }

    /**
     * Get flags indicating sending SMTP email from the passed-in account
     *
     * @param context the caller's context
     * @param account the account being used
     * @return flags for sending SMTP email from this account
     */
    public static int getSmtpFlags(Context context, Account account) {
        return (int)account.mId | REASON_SYNC;
    }

    public static String toString(int flags) {
        StringBuilder sb = new StringBuilder();
        sb.append("account ");
        sb.append(flags & ACCOUNT_MASK);
        sb.append(',');
        sb.append(REASONS[(flags & REASON_MASK) >> REASON_SHIFT]);
        int maskedData = flags & DATA_MASK;
        if (maskedData != 0) {
            sb.append(',');
            sb.append(maskedData == DATA_CALENDAR ? "calendar" : "contacts");
        }
        return sb.toString();
    }
}
