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

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;

public class AccountSetupABFragment extends AccountSetupFragment {

    private static final String ACCOUNT_EMAIL_ARG = "accountEmail";
    private static final String USER_PROTOCOL_ARG = "userProtocol";
    private static final String PROVIDER_PROTOCOL_ARG = "providerProtocol";

    private String mAccountEmail;
    private String mUserProtocol;
    private String mProviderProtocol;

    public interface Callback extends AccountSetupFragment.Callback {
        void onABProtocolDisambiguated(String chosenProtocol);
    }

    public AccountSetupABFragment() {}

    /**
     * Setup flow fragment for disambiguating the user's choice of protocol (when launched from the
     * system account manager) and what is indicated in providers.xml
     *
     * @param accountEmail Email address of account being set up
     * @param userProtocol Protocol that the user initiated account creation for
     * @param providerProtocol Protocol indicated in providers.xml
     * @return Fresh ready-to-use disambiguation fragment
     */
    public static AccountSetupABFragment newInstance(final String accountEmail,
            final String userProtocol, final String providerProtocol) {
        final Bundle b = new Bundle(3);
        b.putString(ACCOUNT_EMAIL_ARG, accountEmail);
        b.putString(USER_PROTOCOL_ARG, userProtocol);
        b.putString(PROVIDER_PROTOCOL_ARG, providerProtocol);
        final AccountSetupABFragment f = new AccountSetupABFragment();
        f.setArguments(b);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Bundle b = getArguments();
        mAccountEmail = b.getString(ACCOUNT_EMAIL_ARG);
        mUserProtocol = b.getString(USER_PROTOCOL_ARG);
        mProviderProtocol = b.getString(PROVIDER_PROTOCOL_ARG);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final Context context = inflater.getContext();

        final String userProtocolName =
                EmailServiceUtils.getServiceInfo(context, mUserProtocol).name;
        final String providerProtocolName =
                EmailServiceUtils.getServiceInfo(context, mProviderProtocol).name;

        final View view = inflateTemplatedView(inflater, container,
                R.layout.account_setup_ab_fragment, R.string.account_setup_ab_headline);

        final TextView abInstructions = UiUtilities.getView(view, R.id.ab_instructions);
        abInstructions.setText(context.getString(R.string.account_setup_ab_instructions_format,
                mAccountEmail, userProtocolName, providerProtocolName));

        final View nextButton = UiUtilities.getView(view, R.id.next);
        nextButton.setVisibility(View.INVISIBLE);

        final Button abButtonA = UiUtilities.getView(view, R.id.ab_button_a);
        abButtonA.setOnClickListener(this);
        abButtonA.setText(userProtocolName);

        final Button abButtonB = UiUtilities.getView(view, R.id.ab_button_b);
        abButtonB.setOnClickListener(this);
        abButtonB.setText(providerProtocolName);

        return view;
    }

    @Override
    public void onClick(View v) {
        final int viewId = v.getId();
        final Callback callback = (Callback) getActivity();
        if (viewId == R.id.ab_button_a) {
            callback.onABProtocolDisambiguated(mUserProtocol);
        } else if (viewId == R.id.ab_button_b) {
            callback.onABProtocolDisambiguated(mProviderProtocol);
        } else {
            super.onClick(v);
        }
    }
}
