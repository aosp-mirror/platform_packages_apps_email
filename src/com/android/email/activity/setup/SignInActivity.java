package com.android.email.activity.setup;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.android.email.R;

public class SignInActivity extends Activity implements SignInFragment.SignInCallback {

    public static final String EXTRA_EMAIL = "email";

    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_OAUTH_PROVIDER = "provider";
    public static final String EXTRA_OAUTH_ACCESS_TOKEN = "accessToken";
    public static final String EXTRA_OAUTH_REFRESH_TOKEN = "refreshToken";
    public static final String EXTRA_OAUTH_EXPIRES_IN_SECONDS = "expiresInSeconds";

    private SignInFragment mFragment;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sign_in_activity);
        mFragment = (SignInFragment)
                getFragmentManager().findFragmentById(R.id.sign_in_fragment);
        mFragment.setEmailAddress(getIntent().getStringExtra(EXTRA_EMAIL));
        mFragment.setSignInCallback(this);
    }

    @Override
    public void onOAuthSignIn(final String providerId, final String accessToken,
            final String refreshToken, final int expiresInSeconds) {
        final Intent intent = new Intent();
        intent.putExtra(EXTRA_OAUTH_PROVIDER, providerId);
        intent.putExtra(EXTRA_OAUTH_ACCESS_TOKEN, accessToken);
        intent.putExtra(EXTRA_OAUTH_REFRESH_TOKEN, refreshToken);
        intent.putExtra(EXTRA_OAUTH_EXPIRES_IN_SECONDS, expiresInSeconds);
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public void onPasswordSignIn(final String password) {
        final Intent intent = new Intent();
        intent.putExtra(EXTRA_PASSWORD, password);
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public void onCancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

}
