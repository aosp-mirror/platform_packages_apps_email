package com.android.email.activity.setup;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.view.CertificateSelector;
import com.android.email.view.CertificateSelector.HostCallback;
import com.android.emailcommon.Device;
import com.android.emailcommon.VendorPolicyLoader.OAuthProvider;
import com.android.emailcommon.provider.Credential;
import com.android.emailcommon.provider.HostAuth;

import java.io.IOException;

public class AuthenticationView extends LinearLayout implements HostCallback, OnClickListener {

    private final static String SUPER_STATE = "super_state";
    private final static String SAVE_PASSWORD = "save_password";
    private final static String SAVE_OFFER_OAUTH = "save_offer_oauth";
    private final static String SAVE_OFFER_CERTS = "save_offer_certs";
    private final static String SAVE_USE_OAUTH = "save_use_oauth";
    private final static String SAVE_OAUTH_PROVIDER = "save_oauth_provider";

    // Views
    private View mImapAuthenticationView;
    private View mImapPasswordContainer;
    private EditText mImapPasswordView;
    private View mImapOAuthContainer;
    private TextView mImapOAuthView;
    private View mImapAddAuthenticationView;
    private View mPasswordContainer;
    private View mClearImapPasswordView;
    private View mClearOAuthView;
    private View mAddAuthenticationView;
    private EditText mPasswordView;
    private CertificateSelector mClientCertificateSelector;
    private View mDeviceIdSectionView;

    private TextWatcher mValidationTextWatcher;

    private boolean mOfferOAuth;
    private boolean mOfferCerts;
    private boolean mUseOAuth;
    private String mOAuthProvider;

    private boolean mAuthenticationValid;
    private AuthenticationCallback mAuthenticationCallback;

    public interface AuthenticationCallback {
        public void onValidateStateChanged();

        public void onCertificateRequested();

        public void onRequestSignIn();
    }

    public AuthenticationView(Context context) {
        this(context, null);
    }

    public AuthenticationView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AuthenticationView(Context context, AttributeSet attrs, int defstyle) {
        super(context, attrs, defstyle);
        LayoutInflater.from(context).inflate(R.layout.authentication_view, this, true);
    }


    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mImapAuthenticationView = UiUtilities.getView(this, R.id.imap_authentication);
        mImapPasswordContainer = UiUtilities.getView(this, R.id.imap_password_selection);
        mImapPasswordView = UiUtilities.getView(this, R.id.imap_account_password);
        mImapOAuthContainer = UiUtilities.getView(this, R.id.oauth_selection);
        mImapOAuthView = UiUtilities.getView(this, R.id.signed_in_with_service_label);
        mImapAddAuthenticationView = UiUtilities.getView(this, R.id.authentication_selection);
        mPasswordContainer = UiUtilities.getView(this, R.id.standard_password_selection);
        mPasswordView = UiUtilities.getView(this, R.id.account_password);
        mClientCertificateSelector = UiUtilities.getView(this, R.id.client_certificate_selector);
        mDeviceIdSectionView = UiUtilities.getView(this, R.id.device_id_section);
        mClearImapPasswordView = UiUtilities.getView(this, R.id.clear_password);
        mClearOAuthView = UiUtilities.getView(this, R.id.clear_oauth);
        mAddAuthenticationView = UiUtilities.getView(this, R.id.add_authentication);

        mClearImapPasswordView.setOnClickListener(this);
        mClearOAuthView.setOnClickListener(this);
        mAddAuthenticationView.setOnClickListener(this);

        mClientCertificateSelector.setHostCallback(this);

        mValidationTextWatcher = new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                validateFields();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
        };
        mPasswordView.addTextChangedListener(mValidationTextWatcher);
        mImapPasswordView.addTextChangedListener(mValidationTextWatcher);
    }

    public void setAuthenticationCallback(final AuthenticationCallback host) {
        mAuthenticationCallback = host;
    }

    public boolean getAuthValid() {
        if (mOfferOAuth) {
            if (mUseOAuth) {
                return mOAuthProvider != null;
            } else {
                return !TextUtils.isEmpty(mImapPasswordView.getText());
            }
        } else {
            return !TextUtils.isEmpty(mPasswordView.getText());
        }
    }

    public void setPassword(final String password) {
        getPasswordEditText().setText(password);
    }

    public String getPassword() {
        return getPasswordEditText().getText().toString();
    }

    public String getClientCertificate() {
        return mClientCertificateSelector.getCertificate();
    }

    public String getOAuthProvider() {
        return mOAuthProvider;
    }

    private void validateFields() {
        boolean valid = getAuthValid();
        if (valid != mAuthenticationValid) {
            mAuthenticationCallback.onValidateStateChanged();
            mAuthenticationValid = valid;
        }
        // Warn (but don't prevent) if password has leading/trailing spaces
        AccountSettingsUtils.checkPasswordSpaces(getContext(), getPasswordEditText());
    }

    private EditText getPasswordEditText() {
        if (mOfferOAuth) {
            return mImapPasswordView;
        } else {
            return mPasswordView;
        }
    }

    public void setAuthInfo(final boolean offerOAuth, final boolean offerCerts,
            final HostAuth hostAuth) {
        mOfferOAuth = offerOAuth;
        mOfferCerts = offerCerts;

        if (mOfferOAuth) {
            final Credential cred = hostAuth.getCredential(getContext());
            if (cred != null) {
                // We're authenticated with OAuth.
                mUseOAuth = true;
                mOAuthProvider = cred.mProviderId;
            } else {
                mUseOAuth = false;
            }
        } else {
            // We're using a POP or Exchange account, which does not offer oAuth.
            mUseOAuth = false;
        }
        getPasswordEditText().setText(hostAuth.mPassword);

        if (mOfferOAuth && mUseOAuth) {
            // We're authenticated with OAuth.
            final OAuthProvider provider = AccountSettingsUtils.findOAuthProvider(
                    getContext(), mOAuthProvider);
            mImapOAuthView.setText(getContext().getString(R.string.signed_in_with_service_label,
                    provider.label));
        }

        updateVisibility();
        validateFields();
    }

    private void updateVisibility() {
        mClientCertificateSelector.setVisibility(
                mOfferCerts ? View.VISIBLE : View.GONE);

        if (mOfferOAuth) {
            mPasswordContainer.setVisibility(View.GONE);
            mImapAuthenticationView.setVisibility(View.VISIBLE);

            if (mUseOAuth) {
                // We're authenticated with OAuth.
                mImapPasswordContainer.setVisibility(View.GONE);
                mImapOAuthContainer.setVisibility(View.VISIBLE);
                mImapAddAuthenticationView.setVisibility(View.GONE);
            } else if (!TextUtils.isEmpty(getPassword())) {
                // We're authenticated with a password.
                mImapPasswordContainer.setVisibility(View.VISIBLE);
                mImapOAuthContainer.setVisibility(View.GONE);
                mImapAddAuthenticationView.setVisibility(View.GONE);
                if (TextUtils.isEmpty(mImapPasswordView.getText())) {
                    mImapPasswordView.requestFocus();
                }
            } else {
                // We have no authentication, we need to allow either password or oauth.
                mImapPasswordContainer.setVisibility(View.GONE);
                mImapOAuthContainer.setVisibility(View.GONE);
                mImapAddAuthenticationView.setVisibility(View.VISIBLE);
            }
        } else {
            // We're using a POP or Exchange account, which does not offer oAuth.
            mImapAuthenticationView.setVisibility(View.GONE);
            mPasswordContainer.setVisibility(View.VISIBLE);
            mPasswordView.setVisibility(View.VISIBLE);
            if (TextUtils.isEmpty(mPasswordView.getText())) {
                mPasswordView.requestFocus();
            }
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(SUPER_STATE, super.onSaveInstanceState());
        bundle.putBoolean(SAVE_OFFER_OAUTH, mOfferOAuth);
        bundle.putBoolean(SAVE_OFFER_CERTS, mOfferCerts);
        bundle.putBoolean(SAVE_USE_OAUTH, mUseOAuth);
        bundle.putString(SAVE_PASSWORD, getPassword());
        bundle.putString(SAVE_OAUTH_PROVIDER, mOAuthProvider);
        return bundle;
    }

    @Override
    public void onRestoreInstanceState(Parcelable parcelable) {
        if (parcelable instanceof Bundle) {
            Bundle bundle = (Bundle)parcelable;
            super.onRestoreInstanceState(bundle.getParcelable(SUPER_STATE));
            mOfferOAuth = bundle.getBoolean(SAVE_OFFER_OAUTH);
            mOfferCerts = bundle.getBoolean(SAVE_OFFER_CERTS);
            mUseOAuth = bundle.getBoolean(SAVE_USE_OAUTH);
            mOAuthProvider = bundle.getString(SAVE_OAUTH_PROVIDER);

            final String password = bundle.getString(SAVE_PASSWORD);
            getPasswordEditText().setText(password);
            if (!TextUtils.isEmpty(mOAuthProvider)) {
                final OAuthProvider provider = AccountSettingsUtils.findOAuthProvider(
                        getContext(), mOAuthProvider);
                if (provider != null) {
                    mImapOAuthView.setText(getContext().getString(R.string.signed_in_with_service_label,
                            provider.label));
                }
            }
            updateVisibility();
        }
    }

    @Override
    public void onCertificateRequested() {
        mAuthenticationCallback.onCertificateRequested();
    }

    public void setCertificate(final String certAlias) {
        mClientCertificateSelector.setCertificate(certAlias);
    }

    public void onUseSslChanged(boolean useSsl) {
        if (mOfferCerts) {
            final int mode = useSsl ? View.VISIBLE : View.GONE;
            mClientCertificateSelector.setVisibility(mode);
            String deviceId = "";
            try {
                deviceId = Device.getDeviceId(getContext());
            } catch (IOException e) {
                // Not required
            }
            ((TextView) UiUtilities.getView(this, R.id.device_id)).setText(deviceId);
            mDeviceIdSectionView.setVisibility(mode);
        }
    }

    @Override
    public void onClick(View view) {
        if (view == mClearImapPasswordView) {
            getPasswordEditText().setText(null);
            updateVisibility();
            validateFields();
        } else if (view == mClearOAuthView) {
            mUseOAuth = false;
            mOAuthProvider = null;
            updateVisibility();
            validateFields();
        } else if (view == mAddAuthenticationView) {
            mAuthenticationCallback.onRequestSignIn();
        }
    }
}
