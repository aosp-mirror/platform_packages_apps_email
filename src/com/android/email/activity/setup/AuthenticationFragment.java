package com.android.email.activity.setup;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.email.view.CertificateSelector;
import com.android.email.view.CertificateSelector.HostCallback;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Device;
import com.android.emailcommon.Logging;
import com.android.emailcommon.VendorPolicyLoader.OAuthProvider;
import com.android.emailcommon.provider.Credential;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.CertificateRequestor;
import com.android.mail.utils.LogUtils;

import java.io.IOException;


// FLAG:
//  * need to handle clicking on this to clear the auth info.
//  * need to handle getting oauth tokens
//  * need to handle switching from password to oauth and vice versa

public class AuthenticationFragment extends Fragment implements HostCallback {

    private static final int CERTIFICATE_REQUEST = 0;

    // Views
    private View mImapAuthenticationView;
    private View mImapPasswordContainer;
    private EditText mImapPasswordView;
    private View mImapOAuthContainer;
    private TextView mImapOAuthView;
    private View mImapAddAuthenticationView;
    private View mPasswordContainer;
    private EditText mPasswordView;
    private CertificateSelector mClientCertificateSelector;
    private View mDeviceIdSectionView;
    private boolean mViewInitialized;

    // Watcher
    private TextWatcher mValidationTextWatcher;

    private HostAuth mHostAuth;
    private EmailServiceInfo mServiceInfo;

    private boolean mUseOAuth;
    private String mPassword;
    private String mOAuthProvider;
    private String mOAuthAccessToken;
    private String mOAuthRefreshToken;


    private boolean mAuthenticationValid;
    private AuthenticationCallback mAuthenticationCallback;

    public interface AuthenticationCallback {
        public void onValidateStateChanged();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewInitialized = false;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mClientCertificateSelector.setHostActivity(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.authentication_fragment, container, false);
        mImapAuthenticationView = UiUtilities.getView(view, R.id.imap_authentication);
        mImapPasswordContainer = UiUtilities.getView(view, R.id.imap_password_selection);
        mImapPasswordView = UiUtilities.getView(view, R.id.imap_account_password);
        mImapOAuthContainer = UiUtilities.getView(view, R.id.oauth_selection);
        mImapOAuthView = UiUtilities.getView(view, R.id.signed_in_with_service_label);
        mImapAddAuthenticationView = UiUtilities.getView(view, R.id.authentication_selection);
        mPasswordContainer = UiUtilities.getView(view, R.id.standard_password_selection);
        mPasswordView = UiUtilities.getView(view, R.id.account_password);
        mClientCertificateSelector = UiUtilities.getView(view, R.id.client_certificate_selector);
        mDeviceIdSectionView = UiUtilities.getView(view, R.id.device_id_section);

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
        mViewInitialized = true;
        if (mHostAuth != null) {
            loadInfo();
        }
        return view;
    }

    public void setAuthenticationCallback(final AuthenticationCallback host) {
        mAuthenticationCallback = host;
    }

    public boolean getAuthValid() {
        if (mServiceInfo == null || !mViewInitialized) {
            // XXX this is kind of weird. We can get called in onStart() of our
            // parent fragment, when we're still totally uninitialized.
            // Once we get initialized, we need to call back to allow our parent
            // to know what our state is.
            return false;
        }
        if (mServiceInfo.offerOAuth) {
            if (mUseOAuth) {
                return mOAuthProvider != null;
            } else {
                return !TextUtils.isEmpty(mImapPasswordView.getText());
            }
        } else {
            return !TextUtils.isEmpty(mPasswordView.getText());
        }
    }

    public String getPassword() {
        return getPasswordEditText().getText().toString();
    }

    public String getClientCertificate() {
        return mClientCertificateSelector.getCertificate();
    }

    public String getOAuthProvider() {
        // FLAG: need to handle this getting updated.
        return mOAuthProvider;
    }

    private void validateFields() {
        boolean valid = getAuthValid();
        if (valid != mAuthenticationValid) {
            mAuthenticationCallback.onValidateStateChanged();
            mAuthenticationValid = valid;
        }
        // Warn (but don't prevent) if password has leading/trailing spaces
        AccountSettingsUtils.checkPasswordSpaces(getActivity(), getPasswordEditText());
    }

    private EditText getPasswordEditText() {
        if (mServiceInfo.offerOAuth) {
            return mImapPasswordView;
        } else {
            return mPasswordView;
        }
    }

    public void setAuthInfo(final EmailServiceInfo serviceInfo, final HostAuth hostAuth) {
        mServiceInfo = serviceInfo;
        mHostAuth = hostAuth;

        if (mViewInitialized) {
            loadInfo();
            validateFields();
        }
    }

    private void loadInfo() {
        mServiceInfo = EmailServiceUtils.getServiceInfo(getActivity(), mHostAuth.mProtocol);

        mClientCertificateSelector.setVisibility(
                mServiceInfo.offerCerts ? View.VISIBLE : View.GONE);

        mImapPasswordView.setText(null);
        mPasswordView.setText(null);
        if (mServiceInfo.offerOAuth) {
            mPasswordContainer.setVisibility(View.GONE);
            mImapAuthenticationView.setVisibility(View.VISIBLE);

            final Credential cred = mHostAuth.getCredential(getActivity());
            if (cred != null) {
                // We're authenticated with OAuth.
                mUseOAuth = true;
                mOAuthProvider = cred.mProviderId;
                mOAuthAccessToken = cred.mAccessToken;
                mOAuthRefreshToken = cred.mRefreshToken;

                final OAuthProvider provider = AccountSettingsUtils.findOAuthProvider(
                        getActivity(), cred.mProviderId);
                mImapPasswordContainer.setVisibility(View.GONE);
                mImapOAuthContainer.setVisibility(View.VISIBLE);
                mImapAddAuthenticationView.setVisibility(View.GONE);
                mImapOAuthView.setText(getString(R.string.signed_in_with_service_label,
                        provider.label));
//            } else if (!TextUtils.isEmpty(hostAuth.mPassword)) {
            } else {
                // We're authenticated with a password.
                mUseOAuth = false;
                mPassword = mHostAuth.mPassword;

                // XXX need to handle clicking on this to clear the password.
                mImapPasswordContainer.setVisibility(View.VISIBLE);
                mImapPasswordView.setText(mHostAuth.mPassword);
                mImapOAuthContainer.setVisibility(View.GONE);
                mImapAddAuthenticationView.setVisibility(View.GONE);
/*            } else {
 * XXX Allow us to choose what type of authentication when the password is unset.
                // We have no authentication, we need to allow either password or oauth.
                mUseOAuth = false;
                mPassword = null;

                // XXX need to handle clicking on this to go to the add auth fragment.
                mImapPasswordContainer.setVisibility(View.GONE);
                mImapOAuthContainer.setVisibility(View.GONE);
                mImapAddAuthenticationView.setVisibility(View.VISIBLE); */
            }
        } else {
            // We're using a POP or Exchange account, which does not offer oAuth.
            mUseOAuth = false;
            mPassword = mHostAuth.mPassword;

            mImapAuthenticationView.setVisibility(View.GONE);
            mPasswordContainer.setVisibility(View.VISIBLE);
            mPasswordView.setVisibility(View.VISIBLE);
            mPasswordView.setText(mHostAuth.mPassword);
            if (TextUtils.isEmpty(mHostAuth.mPassword)) {
                mPasswordView.requestFocus();
            }
        }
        validateFields();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AuthenticationFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onCertificateRequested() {
        final Intent intent = new Intent(CertificateRequestor.ACTION_REQUEST_CERT);
        intent.setData(Uri.parse("eas://com.android.emailcommon/certrequest"));
        startActivityForResult(intent, CERTIFICATE_REQUEST);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CERTIFICATE_REQUEST && resultCode == Activity.RESULT_OK) {
            final String certAlias = data.getStringExtra(CertificateRequestor.RESULT_ALIAS);
            if (certAlias != null) {
                mClientCertificateSelector.setCertificate(certAlias);
            }
        }
    }


    public void onUseSslChanged(boolean useSsl) {
        if (mServiceInfo.offerCerts) {
            final int mode = useSsl ? View.VISIBLE : View.GONE;
            mClientCertificateSelector.setVisibility(mode);
            String deviceId = "";
            try {
                deviceId = Device.getDeviceId(getActivity());
            } catch (IOException e) {
                // Not required
            }
            ((TextView) UiUtilities.getView(getView(), R.id.device_id)).setText(deviceId);
            mDeviceIdSectionView.setVisibility(mode);
        }
    }
}
