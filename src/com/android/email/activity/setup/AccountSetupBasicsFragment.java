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
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.emailcommon.mail.Address;

public class AccountSetupBasicsFragment extends AccountSetupFragment {
    private EditText mEmailView;
    private View mManualSetupView;
    private boolean mManualSetup;

    public interface Callback extends AccountSetupFragment.Callback {
    }

    public static AccountSetupBasicsFragment newInstance() {
        return new AccountSetupBasicsFragment();
    }

    public AccountSetupBasicsFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view = inflateTemplatedView(inflater, container,
                R.layout.account_setup_basics_fragment, -1);

        mEmailView = UiUtilities.getView(view, R.id.account_email);
        mManualSetupView = UiUtilities.getView(view, R.id.manual_setup);
        mManualSetupView.setOnClickListener(this);

        final TextWatcher textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                validateFields();
            }
        };

        mEmailView.addTextChangedListener(textWatcher);

        setPreviousButtonVisibility(View.GONE);

        setManualSetupButtonVisibility(View.VISIBLE);

        return view;
    }

    @Override
    public void onViewStateRestored(Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        validateFields();
    }

    @Override
    public void onClick(View v) {
        final int viewId = v.getId();
        final Callback callback = (Callback) getActivity();

        if (viewId == R.id.next) {
            // Handle "Next" button here so we can reset the manual setup diversion
            mManualSetup = false;
            callback.onNextButton();
        } else if (viewId == R.id.manual_setup) {
            mManualSetup = true;
            callback.onNextButton();
        } else {
            super.onClick(v);
        }
    }

    private void validateFields() {
        final String emailField = getEmail();
        final Address[] addresses = Address.parse(emailField);

        final boolean emailValid = !TextUtils.isEmpty(emailField)
                && addresses.length == 1
                && !TextUtils.isEmpty(addresses[0].getAddress());

        setNextButtonEnabled(emailValid);
    }


    /**
     * Set visibitlity of the "manual setup" button
     * @param visibility {@link View#INVISIBLE}, {@link View#VISIBLE}, {@link View#GONE}
     */
    public void setManualSetupButtonVisibility(int visibility) {
        mManualSetupView.setVisibility(visibility);
    }

    @Override
    public void setNextButtonEnabled(boolean enabled) {
        super.setNextButtonEnabled(enabled);
        mManualSetupView.setEnabled(enabled);
        final float manualButtonAlpha;
        if (enabled) {
            manualButtonAlpha =
                    getResources().getFraction(R.fraction.manual_setup_enabled_alpha, 1, 1);
        } else {
            manualButtonAlpha =
                    getResources().getFraction(R.fraction.manual_setup_disabled_alpha, 1, 1);
        }
        mManualSetupView.setAlpha(manualButtonAlpha);
    }

    public void setEmail(final String email) {
        mEmailView.setText(email);
    }

    public String getEmail() {
        return mEmailView.getText().toString().trim();
    }

    public boolean isManualSetup() {
        return mManualSetup;
    }
}
