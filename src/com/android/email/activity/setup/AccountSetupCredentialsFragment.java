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
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.EditText;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.emailcommon.VendorPolicyLoader.OAuthProvider;
import com.android.emailcommon.provider.Credential;
import com.android.emailcommon.provider.HostAuth;
import com.android.mail.utils.LogUtils;

import java.util.List;

public class AccountSetupCredentialsFragment extends AccountSetupFragment
        implements OnClickListener {
    private static final String EXTRA_EMAIL = "email";
    private static final String EXTRA_PROTOCOL = "protocol";

    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_OAUTH_PROVIDER = "provider";
    public static final String EXTRA_OAUTH_ACCESS_TOKEN = "accessToken";
    public static final String EXTRA_OAUTH_REFRESH_TOKEN = "refreshToken";
    public static final String EXTRA_OAUTH_EXPIRES_IN_SECONDS = "expiresInSeconds";

    private View mOAuthGroup;
    private View mOAuthButton;
    private EditText mImapPasswordText;
    private EditText mRegularPasswordText;
    private TextWatcher mValidationTextWatcher;
    private String mEmailAddress;
    private boolean mOfferOAuth;
    private String mProviderId;
    private Context mAppContext;
    private Bundle mResults;

    public interface Callback extends AccountSetupFragment.Callback {
        void onCredentialsComplete(Bundle results);
    }

    /**
     * Create a new instance of this fragment with the appropriate email and protocol
     * @param email login address for OAuth purposes
     * @param protocol protocol of the service we're gathering credentials for
     * @return new fragment instance
     */
    public static AccountSetupCredentialsFragment newInstance(final String email,
            final String protocol) {
        final AccountSetupCredentialsFragment f = new AccountSetupCredentialsFragment();
        final Bundle b = new Bundle(2);
        b.putString(EXTRA_EMAIL, email);
        b.putString(EXTRA_PROTOCOL, protocol);
        f.setArguments(b);
        return f;
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.account_setup_credentials_fragment, container,
                false);

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
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mAppContext = getActivity().getApplicationContext();
        mEmailAddress = getArguments().getString(EXTRA_EMAIL);
        final String protocol = getArguments().getString(EXTRA_PROTOCOL);
        if (protocol != null) {
            final EmailServiceInfo info = EmailServiceUtils.getServiceInfo(mAppContext, protocol);
            mOfferOAuth = info.offerOAuth;
        } else {
            // TODO: for now, we might not know what protocol we're using, so just default to
            // offering oauth
            mOfferOAuth = true;
        }
        if (mOfferOAuth) {
            mOAuthGroup.setVisibility(View.VISIBLE);
            mRegularPasswordText.setVisibility(View.GONE);
        } else {
            mOAuthGroup.setVisibility(View.GONE);
            mRegularPasswordText.setVisibility(View.VISIBLE);
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
        final Callback callback = (Callback) getActivity();
        if (callback != null) {
            callback.setNextButtonEnabled(!TextUtils.isEmpty(getPassword()));
        }
        // Warn (but don't prevent) if password has leading/trailing spaces
        AccountSettingsUtils.checkPasswordSpaces(mAppContext, mImapPasswordText);
        AccountSettingsUtils.checkPasswordSpaces(mAppContext, mRegularPasswordText);
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        if (requestCode == OAuthAuthenticationActivity.REQUEST_OAUTH) {
            if (resultCode == OAuthAuthenticationActivity.RESULT_OAUTH_SUCCESS) {
                final String accessToken = data.getStringExtra(
                        OAuthAuthenticationActivity.EXTRA_OAUTH_ACCESS_TOKEN);
                final String refreshToken = data.getStringExtra(
                        OAuthAuthenticationActivity.EXTRA_OAUTH_REFRESH_TOKEN);
                final int expiresInSeconds = data.getIntExtra(
                        OAuthAuthenticationActivity.EXTRA_OAUTH_EXPIRES_IN, 0);
                final Bundle results = new Bundle(4);
                results.putString(EXTRA_OAUTH_PROVIDER, mProviderId);
                results.putString(EXTRA_OAUTH_ACCESS_TOKEN, accessToken);
                results.putString(EXTRA_OAUTH_REFRESH_TOKEN, refreshToken);
                results.putInt(EXTRA_OAUTH_EXPIRES_IN_SECONDS, expiresInSeconds);
                mResults = results;
                final Callback callback = (Callback) getActivity();
                callback.onCredentialsComplete(results);
            } else if (resultCode == OAuthAuthenticationActivity.RESULT_OAUTH_FAILURE
                    || resultCode == OAuthAuthenticationActivity.RESULT_OAUTH_USER_CANCELED) {
                LogUtils.i(LogUtils.TAG, "Result from oauth %d", resultCode);
            } else {
                LogUtils.wtf(LogUtils.TAG, "Unknown result code from OAUTH: %d", resultCode);
            }
        } else {
            LogUtils.e(LogUtils.TAG, "Unknown request code for onActivityResult in"
                    + " AccountSetupBasics: %d", requestCode);
        }
    }

    @Override
    public void onClick(final View view) {
        if (view == mOAuthButton) {
            List<OAuthProvider> oauthProviders = AccountSettingsUtils.getAllOAuthProviders(
                    mAppContext);
            // TODO currently the only oauth provider we support is google.
            // If we ever have more than 1 oauth provider, then we need to implement some sort
            // of picker UI. For now, just always take the first oauth provider.
            if (oauthProviders.size() > 0) {
                mProviderId = oauthProviders.get(0).id;
                final Intent i = new Intent(getActivity(), OAuthAuthenticationActivity.class);
                i.putExtra(OAuthAuthenticationActivity.EXTRA_EMAIL_ADDRESS, mEmailAddress);
                i.putExtra(OAuthAuthenticationActivity.EXTRA_PROVIDER, mProviderId);
                startActivityForResult(i, OAuthAuthenticationActivity.REQUEST_OAUTH);
            }
        }
    }

    public String getPassword() {
        if (mOfferOAuth) {
            return mImapPasswordText.getText().toString();
        } else {
            return mRegularPasswordText.getText().toString();
        }
    }

    public Bundle getCredentialResults() {
        if (mResults != null) {
            return mResults;
        }

        final Bundle results = new Bundle(1);
        results.putString(EXTRA_PASSWORD, getPassword());
        return results;
    }

    public static void populateHostAuthWithResults(final Context context, final HostAuth hostAuth,
            final Bundle results) {
        if (results == null) {
            return;
        }
        final String password = results.getString(AccountSetupCredentialsFragment.EXTRA_PASSWORD);
        if (!TextUtils.isEmpty(password)) {
            hostAuth.mPassword = password;
            hostAuth.removeCredential();
        } else {
            Credential cred = hostAuth.getOrCreateCredential(context);
            cred.mProviderId = results.getString(
                    AccountSetupCredentialsFragment.EXTRA_OAUTH_PROVIDER);
            cred.mAccessToken = results.getString(
                    AccountSetupCredentialsFragment.EXTRA_OAUTH_ACCESS_TOKEN);
            cred.mRefreshToken = results.getString(
                    AccountSetupCredentialsFragment.EXTRA_OAUTH_REFRESH_TOKEN);
            cred.mExpiration = System.currentTimeMillis()
                    + results.getInt(
                    AccountSetupCredentialsFragment.EXTRA_OAUTH_EXPIRES_IN_SECONDS, 0)
                    * DateUtils.SECOND_IN_MILLIS;
            hostAuth.mPassword = null;
        }
    }
}
