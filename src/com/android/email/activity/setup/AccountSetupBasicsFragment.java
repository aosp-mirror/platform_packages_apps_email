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

public class AccountSetupBasicsFragment extends AccountSetupFragment implements
        View.OnClickListener {
    private EditText mEmailView;

    public interface Callback extends AccountSetupFragment.Callback {
        void onBasicsManualSetupButton();
    }

    public static AccountSetupBasicsFragment newInstance() {
        return new AccountSetupBasicsFragment();
    }

    public AccountSetupBasicsFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.account_setup_basics_fragment, container,
                false);

        mEmailView = UiUtilities.getView(view, R.id.account_email);
        final View manualSetupButton = UiUtilities.getView(view, R.id.manual_setup);
        manualSetupButton.setOnClickListener(this);

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

        return view;
    }

    @Override
    public void onViewStateRestored(Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        validateFields();
    }

    private void validateFields() {
        final String emailField = getEmail();
        final Address[] addresses = Address.parse(emailField);

        final boolean emailValid = !TextUtils.isEmpty(emailField)
                && addresses.length == 1
                && !TextUtils.isEmpty(addresses[0].getAddress());

        final Callback callback = (Callback) getActivity();
        callback.setNextButtonEnabled(emailValid);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.manual_setup) {
            Callback callback = (Callback) getActivity();
            callback.onBasicsManualSetupButton();
        }
    }

    public void setEmail(final String email) {
        mEmailView.setText(email);
    }

    public String getEmail() {
        return mEmailView.getText().toString().trim();
    }
}
