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

import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.activity.setup.SetupDataFragment.SetupDataContainer;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.emailcommon.Logging;
import com.android.emailcommon.VendorPolicyLoader.OAuthProvider;
import com.android.emailcommon.VendorPolicyLoader.Provider;
import com.android.emailcommon.provider.HostAuth;
import com.android.mail.utils.LogUtils;

import java.util.List;

public class SignInFragment extends Fragment implements OnClickListener {

    private View mOAuthGroup;
    private View mOAuthButton;
    private EditText mImapPasswordText;
    private EditText mRegularPasswordText;
    private TextWatcher mValidationTextWatcher;
    private String mEmailAddress;
    private EmailServiceInfo mServiceInfo;
    private String mProviderId;
    private SignInCallback mCallback;
    private Context mContext;

    public interface SignInCallback {
        public void onOAuthSignIn(final String providerId, final String accessToken,
                final String refreshToken, final int expiresInSeconds);

        public void onValidate();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.sign_in_fragment, container, false);

        mImapPasswordText = UiUtilities.getView(view, R.id.imap_password);
        mRegularPasswordText = UiUtilities.getView(view, R.id.regular_password);
        mOAuthGroup = UiUtilities.getView(view, R.id.oauth_group);
        mOAuthButton = UiUtilities.getView(view, R.id.sign_in_with_google);
        mOAuthButton.setOnClickListener(this);

        // After any text edits, call validateFields() which enables or disables the Next button
        mValidationTextWatcher = new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                validatePassword();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
        };
        mImapPasswordText.addTextChangedListener(mValidationTextWatcher);
        mRegularPasswordText.addTextChangedListener(mValidationTextWatcher);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        LogUtils.d(Logging.LOG_TAG, "onActivityCreated");
        mContext = getActivity();
        if (mContext instanceof SetupDataContainer) {
            final SetupDataContainer setupContainer = (SetupDataContainer)mContext;
            SetupDataFragment setupData = setupContainer.getSetupData();
            final HostAuth hostAuth = setupData.getAccount().getOrCreateHostAuthRecv(mContext);
            mServiceInfo = EmailServiceUtils.getServiceInfo(mContext,
                    hostAuth.mProtocol);

            if (mServiceInfo.offerOAuth) {
                mOAuthGroup.setVisibility(View.VISIBLE);
                mRegularPasswordText.setVisibility(View.GONE);
            } else {
                mOAuthGroup.setVisibility(View.GONE);
                mRegularPasswordText.setVisibility(View.VISIBLE);
            }
        }
        validatePassword();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mImapPasswordText.removeTextChangedListener(mValidationTextWatcher);
        mImapPasswordText = null;
        mRegularPasswordText.removeTextChangedListener(mValidationTextWatcher);
        mRegularPasswordText = null;
    }

    public void validatePassword() {
        mCallback.onValidate();
        // Warn (but don't prevent) if password has leading/trailing spaces
        AccountSettingsUtils.checkPasswordSpaces(mContext, mImapPasswordText);
        AccountSettingsUtils.checkPasswordSpaces(mContext, mRegularPasswordText);
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode,
            final Intent data) {
        if (requestCode == OAuthAuthenticationActivity.REQUEST_OAUTH) {
            if (resultCode == OAuthAuthenticationActivity.RESULT_OAUTH_SUCCESS) {
                final String accessToken = data.getStringExtra(
                        OAuthAuthenticationActivity.EXTRA_OAUTH_ACCESS_TOKEN);
                final String refreshToken = data.getStringExtra(
                        OAuthAuthenticationActivity.EXTRA_OAUTH_REFRESH_TOKEN);
                final int expiresInSeconds = data.getIntExtra(
                        OAuthAuthenticationActivity.EXTRA_OAUTH_EXPIRES_IN, 0);
                mCallback.onOAuthSignIn(mProviderId, accessToken, refreshToken, expiresInSeconds);
            } else if (resultCode == OAuthAuthenticationActivity.RESULT_OAUTH_FAILURE
                    || resultCode == OAuthAuthenticationActivity.RESULT_OAUTH_USER_CANCELED) {
                LogUtils.i(Logging.LOG_TAG, "Result from oauth %d", resultCode);
            } else {
                LogUtils.wtf(Logging.LOG_TAG, "Unknown result code from OAUTH: %d", resultCode);
            }
        } else {
            LogUtils.e(Logging.LOG_TAG, "Unknown request code for onActivityResult in"
                    + " AccountSetupBasics: %d", requestCode);
        }
    }

    @Override
    public void onClick(View view) {
        if (view == mOAuthButton) {
            List<OAuthProvider> oauthProviders = AccountSettingsUtils.getAllOAuthProviders(
                    mContext);
            // FLAG currently the only oauth provider we support is google.
            // If we ever have more than 1 oauth provider, then we need to implement some sort
            // of picker UI. For now, just always take the first oauth provider.
            if (oauthProviders.size() > 0) {
                mProviderId = oauthProviders.get(0).id;
                final Intent i = new Intent(mContext, OAuthAuthenticationActivity.class);
                i.putExtra(OAuthAuthenticationActivity.EXTRA_EMAIL_ADDRESS, mEmailAddress);
                i.putExtra(OAuthAuthenticationActivity.EXTRA_PROVIDER, mProviderId);
                startActivityForResult(i, OAuthAuthenticationActivity.REQUEST_OAUTH);
            }
        }
    }

    public void setEmailAddress(final String emailAddress) {
        mEmailAddress = emailAddress;
    }

    public String getEmailAddress() {
        return mEmailAddress;
    }

    public EmailServiceInfo setServiceInfo() {
        return mServiceInfo;
    }
    public String getPassword() {
        if (mServiceInfo.offerOAuth) {
            return mImapPasswordText.getText().toString();
        } else {
            return mRegularPasswordText.getText().toString();
        }
    }

    public void setSignInCallback(SignInCallback callback) {
        mCallback = callback;
    }
}
