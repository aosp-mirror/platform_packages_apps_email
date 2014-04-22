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
import com.android.emailcommon.Device;
import com.android.emailcommon.VendorPolicyLoader.OAuthProvider;
import com.android.emailcommon.provider.Credential;
import com.android.emailcommon.provider.HostAuth;
import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;

public class AuthenticationView extends LinearLayout implements OnClickListener {

    private final static String SUPER_STATE = "super_state";
    private final static String SAVE_PASSWORD = "save_password";
    private final static String SAVE_OFFER_OAUTH = "save_offer_oauth";
    private final static String SAVE_USE_OAUTH = "save_use_oauth";
    private final static String SAVE_OAUTH_PROVIDER = "save_oauth_provider";

    // Views
    private TextView mAuthenticationHeader;
    private View mPasswordWrapper;
    private View mOAuthWrapper;
    private View mNoAuthWrapper;
    private TextView mPasswordLabel;
    private EditText mPasswordEdit;
    private TextView mOAuthLabel;
    private View mClearPasswordView;
    private View mClearOAuthView;
    private View mAddAuthenticationView;

    private TextWatcher mValidationTextWatcher;

    private boolean mOfferOAuth;
    private boolean mUseOAuth;
    private String mOAuthProvider;

    private boolean mAuthenticationValid;
    private AuthenticationCallback mAuthenticationCallback;

    public interface AuthenticationCallback {
        public void onValidateStateChanged();

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
        mPasswordWrapper = UiUtilities.getView(this, R.id.password_wrapper);
        mOAuthWrapper = UiUtilities.getView(this, R.id.oauth_wrapper);
        mNoAuthWrapper = UiUtilities.getView(this, R.id.no_auth_wrapper);
        mPasswordEdit = UiUtilities.getView(this, R.id.password_edit);
        mOAuthLabel =  UiUtilities.getView(this, R.id.oauth_label);
        mClearPasswordView = UiUtilities.getView(this, R.id.clear_password);
        mClearOAuthView = UiUtilities.getView(this, R.id.clear_oauth);
        mAddAuthenticationView = UiUtilities.getView(this, R.id.add_authentication);
        // Don't use UiUtilities here, in some configurations, these view doesn't exist and
        // UiUtilities throws an exception in this case.
        mPasswordLabel = (TextView)findViewById(R.id.password_label);
        mAuthenticationHeader = (TextView)findViewById(R.id.authentication_header);

        mClearPasswordView.setOnClickListener(this);
        mClearOAuthView.setOnClickListener(this);
        mAddAuthenticationView.setOnClickListener(this);

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
        mPasswordEdit.addTextChangedListener(mValidationTextWatcher);
    }

    public void setAuthenticationCallback(final AuthenticationCallback host) {
        mAuthenticationCallback = host;
    }

    public boolean getAuthValid() {
        if (mOfferOAuth & mUseOAuth) {
            return mOAuthProvider != null;
        } else {
            return !TextUtils.isEmpty(mPasswordEdit.getText());
        }
    }

    @VisibleForTesting
    public void setPassword(final String password) {
        mPasswordEdit.setText(password);
    }

    public String getPassword() {
        return mPasswordEdit.getText().toString();
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
        AccountSettingsUtils.checkPasswordSpaces(getContext(), mPasswordEdit);
    }

    public void setAuthInfo(final boolean offerOAuth, final HostAuth hostAuth) {
        mOfferOAuth = offerOAuth;

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
        mPasswordEdit.setText(hostAuth.mPassword);

        if (mOfferOAuth && mUseOAuth) {
            // We're authenticated with OAuth.
            final OAuthProvider provider = AccountSettingsUtils.findOAuthProvider(
                    getContext(), mOAuthProvider);
            mOAuthLabel.setText(getContext().getString(R.string.signed_in_with_service_label,
                    provider.label));
        }

        updateVisibility();
        validateFields();
    }

    private void updateVisibility() {
        if (mOfferOAuth) {
            if (mAuthenticationHeader != null) {
                mAuthenticationHeader.setVisibility(View.VISIBLE);
                mAuthenticationHeader.setText(R.string.authentication_label);
            }
            if (mUseOAuth) {
                // We're authenticated with OAuth.
                mOAuthWrapper.setVisibility(View.VISIBLE);
                mPasswordWrapper.setVisibility(View.GONE);
                mNoAuthWrapper.setVisibility(View.GONE);
                if (mPasswordLabel != null) {
                    mPasswordLabel.setVisibility(View.VISIBLE);
                }
            } else if (!TextUtils.isEmpty(getPassword())) {
                // We're authenticated with a password.
                mOAuthWrapper.setVisibility(View.GONE);
                mPasswordWrapper.setVisibility(View.VISIBLE);
                mNoAuthWrapper.setVisibility(View.GONE);
                if (TextUtils.isEmpty(mPasswordEdit.getText())) {
                    mPasswordEdit.requestFocus();
                }
                mClearPasswordView.setVisibility(View.VISIBLE);
            } else {
                // We have no authentication, we need to allow either password or oauth.
                mOAuthWrapper.setVisibility(View.GONE);
                mPasswordWrapper.setVisibility(View.GONE);
                mNoAuthWrapper.setVisibility(View.VISIBLE);
            }
        } else {
            // We're using a POP or Exchange account, which does not offer oAuth.
            if (mAuthenticationHeader != null) {
                mAuthenticationHeader.setVisibility(View.VISIBLE);
                mAuthenticationHeader.setText(R.string.account_setup_incoming_password_label);
            }
            mOAuthWrapper.setVisibility(View.GONE);
            mPasswordWrapper.setVisibility(View.VISIBLE);
            mNoAuthWrapper.setVisibility(View.GONE);
            mClearPasswordView.setVisibility(View.GONE);
            if (TextUtils.isEmpty(mPasswordEdit.getText())) {
                mPasswordEdit.requestFocus();
            }
            if (mPasswordLabel != null) {
                mPasswordLabel.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(SUPER_STATE, super.onSaveInstanceState());
        bundle.putBoolean(SAVE_OFFER_OAUTH, mOfferOAuth);
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
            mUseOAuth = bundle.getBoolean(SAVE_USE_OAUTH);
            mOAuthProvider = bundle.getString(SAVE_OAUTH_PROVIDER);

            final String password = bundle.getString(SAVE_PASSWORD);
            mPasswordEdit.setText(password);
            if (!TextUtils.isEmpty(mOAuthProvider)) {
                final OAuthProvider provider = AccountSettingsUtils.findOAuthProvider(
                        getContext(), mOAuthProvider);
                if (provider != null) {
                    mOAuthLabel.setText(getContext().getString(R.string.signed_in_with_service_label,
                            provider.label));
                }
            }
            updateVisibility();
        }
    }

    @Override
    public void onClick(View view) {
        if (view == mClearPasswordView) {
            mPasswordEdit.setText(null);
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
