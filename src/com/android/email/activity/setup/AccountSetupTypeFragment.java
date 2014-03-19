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
import android.widget.RelativeLayout;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;

public class AccountSetupTypeFragment extends AccountSetupFragment
        implements View.OnClickListener {
    private int mLastId;

    public interface Callback extends AccountSetupFragment.Callback {
        /**
         * called when the user has selected a protocol type for the account
         * @param protocol {@link EmailServiceUtils.EmailServiceInfo#protocol}
         */
        void onChooseProtocol(String protocol);
    }

    public static AccountSetupTypeFragment newInstance() {
        return new AccountSetupTypeFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view = inflateTemplatedView(inflater, container,
                R.layout.account_setup_type_fragment, R.string.account_setup_account_type_headline);

        final Context appContext = inflater.getContext().getApplicationContext();

        final ViewGroup parent = UiUtilities.getView(view, R.id.accountTypes);
        View lastView = parent.getChildAt(0);
        int i = 1;
        for (final EmailServiceUtils.EmailServiceInfo info
                : EmailServiceUtils.getServiceInfoList(appContext)) {
            if (EmailServiceUtils.isServiceAvailable(appContext, info.protocol)) {
                // Don't show types with "hide" set
                if (info.hide) {
                    continue;
                }
                inflater.inflate(R.layout.account_type, parent);
                final Button button = (Button)parent.getChildAt(i);
                if (parent instanceof RelativeLayout) {
                    final RelativeLayout.LayoutParams params =
                            (RelativeLayout.LayoutParams)button.getLayoutParams();
                    params.addRule(RelativeLayout.BELOW, lastView.getId());
                }
                button.setId(i);
                button.setTag(info.protocol);
                button.setText(info.name);
                button.setOnClickListener(this);
                lastView = button;
                i++;
            }
        }
        mLastId = i - 1;

        setNextButtonVisibility(View.INVISIBLE);

        return view;
    }

    @Override
    public void onClick(View v) {
        final int viewId = v.getId();
        if (viewId <= mLastId) {
            final String protocol = (String) v.getTag();
            final Callback callback = (Callback) getActivity();
            callback.onChooseProtocol(protocol);
        } else {
            super.onClick(v);
        }
    }
}
