package com.android.email.activity.setup;

import android.app.Activity;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.android.email.mail.internet.OAuthAuthenticator;
import com.android.email.mail.internet.OAuthAuthenticator.AuthenticationResult;
import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.VendorPolicyLoader.OAuthProvider;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.MessagingException;
import com.android.mail.ui.MailAsyncTaskLoader;
import com.android.mail.utils.LogUtils;

import java.io.IOException;


/**
 * Activity to display a webview to perform oauth authentication. This activity
 * should obtain an authorization code, which can be used to obtain access and
 * refresh tokens.
 */
public class OAuthAuthenticationActivity extends Activity implements
        LoaderCallbacks<AuthenticationResult> {
    public static final String EXTRA_EMAIL_ADDRESS = "email_address";
    public static final String EXTRA_PROVIDER = "provider";
    public static final String EXTRA_PROVIDER_ID = "provider_id";
    public static final String EXTRA_AUTHENTICATION_CODE = "authentication_code";

    public static final int LOADER_ID_OAUTH_TOKEN = 1;

    public static final String EXTRA_OAUTH_ACCESS_TOKEN = "accessToken";
    public static final String EXTRA_OAUTH_REFRESH_TOKEN = "refreshToken";
    public static final String EXTRA_OAUTH_EXPIRES_IN = "expiresIn";

    public static final int REQUEST_OAUTH = 1;

    public static final int RESULT_OAUTH_SUCCESS = Activity.RESULT_FIRST_USER + 0;
    public static final int RESULT_OAUTH_USER_CANCELED = Activity.RESULT_FIRST_USER + 1;
    public static final int RESULT_OAUTH_FAILURE = Activity.RESULT_FIRST_USER + 2;

    private WebView mWv;
    private OAuthProvider mProvider;
    private String mAuthenticationCode;

    private class MyWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView wv, String url) {
            // TODO: This method works for Google's redirect url to https://localhost.
            // Does it work for the general case? I don't know what redirect url other
            // providers use, or how the authentication code is returned.
            final String deparameterizedUrl;
            int i = url.lastIndexOf('?');
            if (i == -1) {
                deparameterizedUrl = url;
            } else {
                deparameterizedUrl = url.substring(0,i);
            }

            if (TextUtils.equals(deparameterizedUrl, mProvider.redirectUri)) {
                final Uri uri = Uri.parse(url);
                // Check the params of this uri, they contain success/failure info,
                // along with the authentication token.
                final String error = uri.getQueryParameter("error");

                if (error != null) {
                    final Intent intent = new Intent();
                    setResult(RESULT_OAUTH_USER_CANCELED, intent);
                    finish();
                } else {
                    mAuthenticationCode = uri.getQueryParameter("code");
                    Bundle params = new Bundle();
                    params.putString(EXTRA_PROVIDER_ID, mProvider.id);
                    params.putString(EXTRA_AUTHENTICATION_CODE, mAuthenticationCode);
                    getLoaderManager().initLoader(LOADER_ID_OAUTH_TOKEN, params,
                            OAuthAuthenticationActivity.this);
                }
                return true;
            } else {
                return false;
            }
        }
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        CookieSyncManager.createInstance(this);
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookie();

        mWv = new WebView(this);
        mWv.setWebViewClient(new MyWebViewClient());
        mWv.getSettings().setJavaScriptEnabled(true);
        setContentView(mWv);

        final Intent i = getIntent();
        final String email = i.getStringExtra(EXTRA_EMAIL_ADDRESS);
        final String providerName = i.getStringExtra(EXTRA_PROVIDER);
        mProvider = AccountSettingsUtils.findOAuthProvider(this, providerName);
        final Uri uri = AccountSettingsUtils.createOAuthRegistrationRequest(this, mProvider, email);
        mWv.loadUrl(uri.toString());

        if (bundle != null) {
            mAuthenticationCode = bundle.getString(EXTRA_AUTHENTICATION_CODE);
        } else {
            mAuthenticationCode = null;
        }
        if (mAuthenticationCode != null) {
            Bundle params = new Bundle();
            params.putString(EXTRA_PROVIDER_ID, mProvider.id);
            params.putString(EXTRA_AUTHENTICATION_CODE, mAuthenticationCode);
            getLoaderManager().initLoader(LOADER_ID_OAUTH_TOKEN, params,
                    OAuthAuthenticationActivity.this);
        }
        // Set the result to cancelled until we have success.
        setResult(RESULT_OAUTH_USER_CANCELED, null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(EXTRA_AUTHENTICATION_CODE, mAuthenticationCode);
    }

    private static class OAuthTokenLoader extends MailAsyncTaskLoader<AuthenticationResult> {
        private final String mProviderId;
        private final String mCode;

        public OAuthTokenLoader(Context context, String providerId, String code) {
            super(context);
            mProviderId = providerId;
            mCode = code;
        }

        @Override
        protected void onDiscardResult(AuthenticationResult result) {

        }

        @Override
        public AuthenticationResult loadInBackground() {
            try {
                final OAuthAuthenticator authenticator = new OAuthAuthenticator();
                final AuthenticationResult result = authenticator.requestAccess(
                        getContext(), mProviderId, mCode);
                LogUtils.d(Logging.LOG_TAG, "authentication %s", result);
                return result;
                // TODO: do I need a better UI for displaying exceptions?
            } catch (AuthenticationFailedException e) {
            } catch (MessagingException e) {
            } catch (IOException e) {
            }
            return null;
        }
    }

    @Override
    public Loader<AuthenticationResult> onCreateLoader(int id, Bundle data) {
        if (id == LOADER_ID_OAUTH_TOKEN) {
            final String providerId = data.getString(EXTRA_PROVIDER_ID);
            final String code = data.getString(EXTRA_AUTHENTICATION_CODE);
            return new OAuthTokenLoader(this, providerId, code);
        }
        return null;
    }

    @Override
    public void onLoadFinished(Loader<AuthenticationResult> loader,
        AuthenticationResult data) {
        if (data == null) {
            // TODO: need a better way to display errors. We might get IO or
            // MessagingExceptions.
            setResult(RESULT_OAUTH_FAILURE, null);
            Toast.makeText(this, R.string.oauth_error_description, Toast.LENGTH_SHORT).show();
            LogUtils.w(Logging.LOG_TAG, "null oauth result");
        } else {
            final Intent intent = new Intent();
            intent.putExtra(EXTRA_OAUTH_ACCESS_TOKEN, data.mAccessToken);
            intent.putExtra(EXTRA_OAUTH_REFRESH_TOKEN, data.mRefreshToken);
            intent.putExtra(EXTRA_OAUTH_EXPIRES_IN, data.mExpiresInSeconds);
            setResult(RESULT_OAUTH_SUCCESS, intent);
        }
        finish();
    }

    @Override
    public void onLoaderReset(Loader<AuthenticationResult> loader) {

    }
}
