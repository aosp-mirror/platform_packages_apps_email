package com.android.email.activity.setup;

import android.app.FragmentTransaction;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ImageButton;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Credential;
import com.android.emailcommon.provider.HostAuth;
import com.android.mail.utils.LogUtils;

public class SignInActivity extends AccountSetupActivity implements SignInFragment.SignInCallback,
        View.OnClickListener, AccountCheckSettingsFragment.Callbacks{

    public static final String EXTRA_MANUAL_SETUP = "manual";
    public static final String EXTRA_FLOW_MODE_INITIAL = "initial";

    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_OAUTH_PROVIDER = "provider";
    public static final String EXTRA_OAUTH_ACCESS_TOKEN = "accessToken";
    public static final String EXTRA_OAUTH_REFRESH_TOKEN = "refreshToken";
    public static final String EXTRA_OAUTH_EXPIRES_IN_SECONDS = "expiresInSeconds";

    private SignInFragment mFragment;
    private ImageButton mNextButton;
    private ImageButton mPrevButton;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sign_in_activity);
        final String emailAddress = mSetupData.getAccount().mEmailAddress;
        mFragment = (SignInFragment)
                getFragmentManager().findFragmentById(R.id.sign_in_fragment);
        mFragment.setEmailAddress(emailAddress);

        mFragment.setSignInCallback(this);
        mNextButton = UiUtilities.getView(this, R.id.next);
        mPrevButton = UiUtilities.getView(this, R.id.previous);
        mNextButton.setOnClickListener(this);
        mPrevButton.setOnClickListener(this);

        // Assume canceled until we find out otherwise.
        setResult(RESULT_CANCELED);
    }

    @Override
    public void onOAuthSignIn(final String providerId, final String accessToken,
            final String refreshToken, final int expiresInSeconds) {
        if (getIntent().getBooleanExtra(EXTRA_FLOW_MODE_INITIAL, false)) {
            // On initial setup, we now try to validate the account.
            final Account account = mSetupData.getAccount();
            final HostAuth recvAuth = account.getOrCreateHostAuthRecv(this);
            final Credential cred = recvAuth.getOrCreateCredential(this);
            cred.mProviderId = providerId;
            cred.mAccessToken = accessToken;
            cred.mRefreshToken = refreshToken;
            cred.mExpiration = System.currentTimeMillis() +
                    expiresInSeconds * DateUtils.SECOND_IN_MILLIS;

            // TODO: For now, assume that we will use SSL because that's what
            // gmail wants. This needs to be parameterized from providers.xml
            recvAuth.mFlags |= HostAuth.FLAG_SSL;
            recvAuth.mFlags |= HostAuth.FLAG_OAUTH;

            final HostAuth sendAuth = account.getOrCreateHostAuthSend(this);
            sendAuth.mCredential = cred;
            sendAuth.mFlags |= HostAuth.FLAG_SSL;
            sendAuth.mFlags |= HostAuth.FLAG_OAUTH;
            startAuthenticationCheck();
        } else {
            // On regular settings, we just return the auth info to the caller.
            final Intent intent = new Intent();
            intent.putExtra(EXTRA_OAUTH_PROVIDER, providerId);
            intent.putExtra(EXTRA_OAUTH_ACCESS_TOKEN, accessToken);
            intent.putExtra(EXTRA_OAUTH_REFRESH_TOKEN, refreshToken);
            intent.putExtra(EXTRA_OAUTH_EXPIRES_IN_SECONDS, expiresInSeconds);
            setResult(RESULT_OK, intent);
            finish();
        }
    }

    private void onNext() {
        final String password = mFragment.getPassword();
        // This only applies for password authentication.
        if (getIntent().getBooleanExtra(EXTRA_FLOW_MODE_INITIAL, false)) {
            // On initial setup, we now try to validate the account.

            final Account account = mSetupData.getAccount();
            final HostAuth recvAuth = account.getOrCreateHostAuthRecv(this);
            recvAuth.mPassword = password;

            final HostAuth sendAuth = account.getOrCreateHostAuthSend(this);
            sendAuth.mPassword = password;

            startAuthenticationCheck();
        } else {
            // On regular settings, we just return the auth info to the caller.
            final Intent intent = new Intent();
            intent.putExtra(EXTRA_PASSWORD, password);
            setResult(RESULT_OK, intent);
            finish();
        }
    }

    private void startAuthenticationCheck() {
        final AccountCheckSettingsFragment checkerFragment =
                AccountCheckSettingsFragment.newInstance(
                    SetupDataFragment.CHECK_INCOMING | SetupDataFragment.CHECK_OUTGOING,
                        null);
            final FragmentTransaction transaction = getFragmentManager().beginTransaction();
            transaction.add(checkerFragment, AccountCheckSettingsFragment.TAG);
            transaction.addToBackStack(null);
            transaction.commit();
    }

    @Override
    public void onValidate() {
        mNextButton.setEnabled(!TextUtils.isEmpty(mFragment.getPassword()));
    }

    @Override
    public void onClick(View view) {
        if (view == mNextButton) {
            onNext();
        } else if (view == mPrevButton) {
            onBackPressed();
        }
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     *
     * This is used in automatic setup mode to jump directly down to the options screen.
     *
     * This is the only case where we finish() this activity but account setup is continuing,
     * so we inhibit reporting any error back to the Account manager.
     */
    @Override
    public void onCheckSettingsComplete(int result, SetupDataFragment setupData) {
        mSetupData = setupData;
        if (result == AccountCheckSettingsFragment.CHECK_SETTINGS_OK) {
            AccountSetupFinal.actionFinal(this, mSetupData);
            mSetupData.setReportAccountAuthenticationError(false);
            finish();
        } else {
            // FLAG: DO I need to do anything else here?
            LogUtils.d(Logging.LOG_TAG, "failure on check setup");
        }
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     * This is overridden only by AccountSetupIncoming
     */
    @Override
    public void onAutoDiscoverComplete(int result, SetupDataFragment setupData) {
        throw new IllegalStateException();
    }
}
