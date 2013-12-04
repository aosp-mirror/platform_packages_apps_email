package com.android.email.activity.setup;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.VendorPolicyLoader.OAuthProvider;
import com.android.mail.utils.LogUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;


/**
 * Activity to display a webview to perform oauth authentication. This activity
 * should obtain an authorization code, which can be used to obtain access and
 * refresh tokens.
 */
public class OAuthAuthenticationActivity extends Activity {
    private final static String TAG = Logging.LOG_TAG;

    public static final String EXTRA_EMAIL_ADDRESS = "email_address";
    public static final String EXTRA_PROVIDER = "provider";

    WebView mWv;
    OAuthProvider mProvider;

    private class MyWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView wv, String url) {
            // TODO: This method works for Google's redirect url to https://localhost.
            // Does it work for the general case? I don't know what redirect url other
            // providers use, or how the authentication code is returned.
            LogUtils.d(TAG, "shouldOverrideUrlLoading %s", url);
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
                    // TODO display failure screen
                    LogUtils.d(TAG, "error code %s", error);
                    Toast.makeText(OAuthAuthenticationActivity.this,
                            "Couldn't authenticate", Toast.LENGTH_LONG).show();
                } else {
                    // TODO  use this token to request the access and refresh tokens
                    final String code = uri.getQueryParameter("code");
                    LogUtils.d(TAG, "authorization code %s", code);
                    Toast.makeText(OAuthAuthenticationActivity.this,
                            "OAuth not implemented", Toast.LENGTH_LONG).show();
                }
                finish();
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
        LogUtils.d(Logging.LOG_TAG, "launching '%s'", uri);
        mWv.loadUrl(uri.toString());
    }
}
