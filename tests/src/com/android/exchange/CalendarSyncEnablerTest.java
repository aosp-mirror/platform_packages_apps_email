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

package com.android.exchange;

import com.android.email.AccountTestCase;
import com.android.email.Email;
import com.android.email.service.MailService;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Calendar;
import android.test.MoreAsserts;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class CalendarSyncEnablerTest extends AccountTestCase {

    private HashMap<Account, Boolean> origCalendarSyncStates = new HashMap<Account, Boolean>();

    // To make the rest of the code shorter thus more readable...
    private static final String EAT = Email.EXCHANGE_ACCOUNT_MANAGER_TYPE;

    public CalendarSyncEnablerTest() {
        super();
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        // Delete any test accounts we might have created earlier
        deleteTemporaryAccountManagerAccounts();

        // Save the original calendar sync states.
        for (Account account : AccountManager.get(getContext()).getAccounts()) {
            origCalendarSyncStates.put(account,
                    ContentResolver.getSyncAutomatically(account, Calendar.AUTHORITY));
        }
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        // Delete any test accounts we might have created earlier
        deleteTemporaryAccountManagerAccounts();

        // Restore the original calendar sync states.
        // Note we restore only for Exchange accounts.
        // Other accounts should remain intact throughout the tests.  Plus we don't know if the
        // Calendar.AUTHORITY is supported by other types of accounts.
        for (Account account : getExchangeAccounts()) {
            Boolean state = origCalendarSyncStates.get(account);
            if (state == null) continue; // Shouldn't happen, but just in case.

            ContentResolver.setSyncAutomatically(account, Calendar.AUTHORITY, state);
        }
    }

    public void testEnableEasCalendarSync() {
        final Account[] baseAccounts = getExchangeAccounts();

        String a1 = getTestAccountEmailAddress("1");
        String a2 = getTestAccountEmailAddress("2");

        // 1. Test with 1 account

        CalendarSyncEnabler enabler = new CalendarSyncEnabler(getContext());

        // Add exchange accounts
        createAccountManagerAccount(a1);

        String emailAddresses = enabler.enableEasCalendarSyncInternal();

        // Verify
        verifyCalendarSyncState();

        // There seems to be no good way to examine the contents of Notification, so let's verify
        // we at least (tried to) show the correct email addresses.
        checkNotificationEmailAddresses(emailAddresses, baseAccounts, a1);

        // Delete added account.
        deleteTemporaryAccountManagerAccounts();

        // 2. Test with 2 accounts
        enabler = new CalendarSyncEnabler(getContext());

        // Add exchange accounts
        createAccountManagerAccount(a1);
        createAccountManagerAccount(a2);

        emailAddresses = enabler.enableEasCalendarSyncInternal();

        // Verify
        verifyCalendarSyncState();

        // Check
        checkNotificationEmailAddresses(emailAddresses, baseAccounts, a1, a2);
    }

    private static void checkNotificationEmailAddresses(String actual, Account[] baseAccounts,
            String... addedAddresses) {
        // Build and sort actual string array.
        final String[] actualArray = TextUtils.split(actual, " ");
        Arrays.sort(actualArray);

        // Build and sort expected string array.
        ArrayList<String> expected = new ArrayList<String>();
        for (Account account : baseAccounts) {
            expected.add(account.name);
        }
        for (String address : addedAddresses) {
            expected.add(address);
        }
        final String[] expectedArray = new String[expected.size()];
        expected.toArray(expectedArray);
        Arrays.sort(expectedArray);

        // Check!
        MoreAsserts.assertEquals(expectedArray, actualArray);
    }

    /**
     * For all {@link Account}, confirm that:
     * <ol>
     *   <li>Calendar sync is enabled if it's an Exchange account.<br>
     *       Unfortunately setSyncAutomatically() doesn't take effect immediately, so we skip this
     *       check for now.
             TODO Find a stable way to check this.
     *   <li>Otherwise, calendar sync state isn't changed.
     * </ol>
     */
    private void verifyCalendarSyncState() {
        // It's very unfortunate that setSyncAutomatically doesn't take effect immediately.
        for (Account account : AccountManager.get(getContext()).getAccounts()) {
            String message = "account=" + account.name + "(" + account.type + ")";
            boolean enabled = ContentResolver.getSyncAutomatically(account, Calendar.AUTHORITY);
            int syncable = ContentResolver.getIsSyncable(account, Calendar.AUTHORITY);

            if (EAT.equals(account.type)) {
                // Should be enabled.
                // assertEquals(message, Boolean.TRUE, (Boolean) enabled);
                // assertEquals(message, 1, syncable);
            } else {
                // Shouldn't change.
                assertEquals(message, origCalendarSyncStates.get(account), (Boolean) enabled);
            }
        }
    }

    public void testEnableEasCalendarSyncWithNoExchangeAccounts() {
        // This test can only meaningfully run when there's no exchange accounts
        // set up on the device.  Otherwise there'll be no difference from
        // testEnableEasCalendarSync.
        if (AccountManager.get(getContext()).getAccountsByType(EAT).length > 0) {
            Log.w(Email.LOG_TAG, "testEnableEasCalendarSyncWithNoExchangeAccounts skipped:"
                    + " It only runs when there's no Exchange account on the device.");
            return;
        }
        CalendarSyncEnabler enabler = new CalendarSyncEnabler(getContext());
        String emailAddresses = enabler.enableEasCalendarSyncInternal();

        // Verify (nothing should change)
        verifyCalendarSyncState();

        // No exchange accounts found.
        assertEquals(0, emailAddresses.length());
    }

    public void testShowNotification() {
        CalendarSyncEnabler enabler = new CalendarSyncEnabler(getContext());

        // We can't really check the result, but at least we can make sure it won't crash....
        enabler.showNotification("a@b.com");

        // Remove the notification.  Comment it out when you want to know how it looks like.
        ((NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE))
                .cancel(MailService.NOTIFICATION_ID_EXCHANGE_CALENDAR_ADDED);
    }
}
