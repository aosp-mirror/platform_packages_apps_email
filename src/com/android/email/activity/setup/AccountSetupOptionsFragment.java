/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.email.activity.setup;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.service.SyncWindow;

public class AccountSetupOptionsFragment extends AccountSetupFragment {
    private Spinner mCheckFrequencyView;
    private Spinner mSyncWindowView;
    private CheckBox mNotifyView;
    private CheckBox mSyncContactsView;
    private CheckBox mSyncCalendarView;
    private CheckBox mSyncEmailView;
    private CheckBox mBackgroundAttachmentsView;
    private View mAccountSyncWindowRow;

    /** Default sync window for new EAS accounts */
    private static final int SYNC_WINDOW_EAS_DEFAULT = SyncWindow.SYNC_WINDOW_1_WEEK;

    public interface Callback extends AccountSetupFragment.Callback {

    }

    public static AccountSetupOptionsFragment newInstance() {
        return new AccountSetupOptionsFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view = inflateTemplatedView(inflater, container,
                R.layout.account_setup_options_fragment, R.string.account_setup_options_headline);

        mCheckFrequencyView = UiUtilities.getView(view, R.id.account_check_frequency);
        mSyncWindowView = UiUtilities.getView(view, R.id.account_sync_window);
        mNotifyView = UiUtilities.getView(view, R.id.account_notify);
        mNotifyView.setChecked(true);
        mSyncContactsView = UiUtilities.getView(view, R.id.account_sync_contacts);
        mSyncCalendarView = UiUtilities.getView(view, R.id.account_sync_calendar);
        mSyncEmailView = UiUtilities.getView(view, R.id.account_sync_email);
        mSyncEmailView.setChecked(true);
        mBackgroundAttachmentsView = UiUtilities.getView(view, R.id.account_background_attachments);
        mBackgroundAttachmentsView.setChecked(true);
        mAccountSyncWindowRow = UiUtilities.getView(view, R.id.account_sync_window_row);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final View view = getView();

        final SetupDataFragment setupData =
                ((SetupDataFragment.SetupDataContainer) getActivity()).getSetupData();
        final Account account = setupData.getAccount();

        final EmailServiceUtils.EmailServiceInfo serviceInfo =
                setupData.getIncomingServiceInfo(getActivity());

        final CharSequence[] frequencyValues = serviceInfo.syncIntervals;
        final CharSequence[] frequencyEntries = serviceInfo.syncIntervalStrings;

        // Now create the array used by the sync interval Spinner
        final SpinnerOption[] checkFrequencies = new SpinnerOption[frequencyEntries.length];
        for (int i = 0; i < frequencyEntries.length; i++) {
            checkFrequencies[i] = new SpinnerOption(
                    Integer.valueOf(frequencyValues[i].toString()), frequencyEntries[i].toString());
        }
        final ArrayAdapter<SpinnerOption> checkFrequenciesAdapter =
                new ArrayAdapter<SpinnerOption>(getActivity(), android.R.layout.simple_spinner_item,
                        checkFrequencies);
        checkFrequenciesAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCheckFrequencyView.setAdapter(checkFrequenciesAdapter);
        SpinnerOption.setSpinnerOptionValue(mCheckFrequencyView, account.getSyncInterval());

        if (serviceInfo.offerLookback) {
            enableLookbackSpinner(account);
        }

        if (serviceInfo.syncContacts) {
            mSyncContactsView.setVisibility(View.VISIBLE);
            mSyncContactsView.setChecked(true);
            UiUtilities.setVisibilitySafe(view, R.id.account_sync_contacts_divider, View.VISIBLE);
        }
        if (serviceInfo.syncCalendar) {
            mSyncCalendarView.setVisibility(View.VISIBLE);
            mSyncCalendarView.setChecked(true);
            UiUtilities.setVisibilitySafe(view, R.id.account_sync_calendar_divider, View.VISIBLE);
        }

        if (!serviceInfo.offerAttachmentPreload) {
            mBackgroundAttachmentsView.setVisibility(View.GONE);
            UiUtilities.setVisibilitySafe(view, R.id.account_background_attachments_divider,
                    View.GONE);
        }
    }

    /**
     * Enable an additional spinner using the arrays normally handled by preferences
     */
    private void enableLookbackSpinner(Account account) {
        // Show everything
        mAccountSyncWindowRow.setVisibility(View.VISIBLE);

        // Generate spinner entries using XML arrays used by the preferences
        final CharSequence[] windowValues = getResources().getTextArray(
                R.array.account_settings_mail_window_values);
        final CharSequence[] windowEntries = getResources().getTextArray(
                R.array.account_settings_mail_window_entries);

        // Find a proper maximum for email lookback, based on policy (if we have one)
        int maxEntry = windowEntries.length;
        final Policy policy = account.mPolicy;
        if (policy != null) {
            final int maxLookback = policy.mMaxEmailLookback;
            if (maxLookback != 0) {
                // Offset/Code   0      1      2      3      4        5
                // Entries      auto, 1 day, 3 day, 1 week, 2 week, 1 month
                // Lookback     N/A   1 day, 3 day, 1 week, 2 week, 1 month
                // Since our test below is i < maxEntry, we must set maxEntry to maxLookback + 1
                maxEntry = maxLookback + 1;
            }
        }

        // Now create the array used by the Spinner
        final SpinnerOption[] windowOptions = new SpinnerOption[maxEntry];
        int defaultIndex = -1;
        for (int i = 0; i < maxEntry; i++) {
            final int value = Integer.valueOf(windowValues[i].toString());
            windowOptions[i] = new SpinnerOption(value, windowEntries[i].toString());
            if (value == SYNC_WINDOW_EAS_DEFAULT) {
                defaultIndex = i;
            }
        }

        final ArrayAdapter<SpinnerOption> windowOptionsAdapter =
                new ArrayAdapter<SpinnerOption>(getActivity(), android.R.layout.simple_spinner_item,
                        windowOptions);
        windowOptionsAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSyncWindowView.setAdapter(windowOptionsAdapter);

        SpinnerOption.setSpinnerOptionValue(mSyncWindowView, account.getSyncLookback());
        if (defaultIndex >= 0) {
            mSyncWindowView.setSelection(defaultIndex);
        }
    }

    public boolean getBackgroundAttachmentsValue() {
        return mBackgroundAttachmentsView.isChecked();
    }

    public Integer getCheckFrequencyValue() {
        return (Integer)((SpinnerOption)mCheckFrequencyView.getSelectedItem()).value;
    }

    /**
     * @return Sync window value or null if view is hidden
     */
    public Integer getAccountSyncWindowValue() {
        if (mAccountSyncWindowRow.getVisibility() != View.VISIBLE) {
            return null;
        }
        return (Integer)((SpinnerOption)mSyncWindowView.getSelectedItem()).value;
    }

    public boolean getSyncEmailValue() {
        return mSyncEmailView.isChecked();
    }

    public boolean getSyncCalendarValue() {
        return mSyncCalendarView.isChecked();
    }

    public boolean getSyncContactsValue() {
        return mSyncContactsView.isChecked();
    }

    public boolean getNotifyValue() {
        return mNotifyView.isChecked();
    }
}
