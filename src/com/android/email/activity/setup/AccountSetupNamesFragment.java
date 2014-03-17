/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.app.LoaderManager;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.emailcommon.provider.Account;

public class AccountSetupNamesFragment extends AccountSetupFragment {
    private EditText mDescription;
    private EditText mName;
    private View mAccountNameLabel;
    private boolean mRequiresName = true;

    public interface Callback extends AccountSetupFragment.Callback {

    }

    public static AccountSetupNamesFragment newInstance() {
        return new AccountSetupNamesFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view = inflateTemplatedView(inflater, container,
                R.layout.account_setup_names_fragment, R.string.account_setup_names_headline);
        mDescription = UiUtilities.getView(view, R.id.account_description);
        mName = UiUtilities.getView(view, R.id.account_name);
        mAccountNameLabel = UiUtilities.getView(view, R.id.account_name_label);

        final TextWatcher validationTextWatcher = new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                validateFields();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        };
        mName.addTextChangedListener(validationTextWatcher);
        mName.setKeyListener(TextKeyListener.getInstance(false, TextKeyListener.Capitalize.WORDS));

        setPreviousButtonVisibility(View.INVISIBLE);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // Make sure the layout is inflated before twiddling with it
        getView();

        final SetupDataFragment setupData =
                ((SetupDataFragment.SetupDataContainer) getActivity()).getSetupData();
        final int flowMode = setupData.getFlowMode();

        final Account account = setupData.getAccount();

        if (flowMode != SetupDataFragment.FLOW_MODE_FORCE_CREATE
                && flowMode != SetupDataFragment.FLOW_MODE_EDIT) {
            final String accountEmail = account.mEmailAddress;
            mDescription.setText(accountEmail);

            // Move cursor to the end so it's easier to erase in case the user doesn't like it.
            mDescription.setSelection(accountEmail.length());
        }

        // Remember whether we're an EAS account, since it doesn't require the user name field
        final EmailServiceUtils.EmailServiceInfo info =
                setupData.getIncomingServiceInfo(getActivity());
        if (!info.usesSmtp) {
            mRequiresName = false;
            mName.setVisibility(View.GONE);
            mAccountNameLabel.setVisibility(View.GONE);
        } else {
            if (account.getSenderName() != null) {
                mName.setText(account.getSenderName());
            } else if (flowMode != SetupDataFragment.FLOW_MODE_FORCE_CREATE
                    && flowMode != SetupDataFragment.FLOW_MODE_EDIT) {
                // Attempt to prefill the name field from the profile if we don't have it,
                final Context loaderContext = getActivity().getApplicationContext();
                getLoaderManager().initLoader(0, null, new LoaderManager.LoaderCallbacks<Cursor>() {
                    @Override
                    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
                        final String[] projection =
                                new String[] { ContactsContract.Profile.DISPLAY_NAME };
                        return new CursorLoader(loaderContext, ContactsContract.Profile.CONTENT_URI,
                                projection, null, null, null);
                    }

                    @Override
                    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
                        if (data == null || !TextUtils.isEmpty(mName.getText())) {
                            return;
                        }
                        final String name;
                        if (data.moveToFirst()) {
                            name = data.getString(0);
                        } else {
                            name = "";
                        }
                        mName.setText(name);
                    }

                    @Override
                    public void onLoaderReset(Loader<Cursor> loader) {}
                });
            }
        }

        // Make sure the "done" button is in the proper state
        validateFields();
    }

    /**
     * Check input fields for legal values and enable/disable next button
     */
    private void validateFields() {
        boolean enableNextButton = true;
        // Validation is based only on the "user name" field, not shown for EAS accounts
        if (mRequiresName) {
            final String userName = mName.getText().toString().trim();
            if (TextUtils.isEmpty(userName)) {
                enableNextButton = false;
                mName.setError(getString(R.string.account_setup_names_user_name_empty_error));
            } else {
                mName.setError(null);
            }
        }
        setNextButtonEnabled(enableNextButton);
    }

    public String getDescription() {
        return mDescription.getText().toString().trim();
    }

    public String getSenderName() {
        return mName.getText().toString().trim();
    }
}
