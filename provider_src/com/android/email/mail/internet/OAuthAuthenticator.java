package com.android.email.mail.internet;

import android.content.Context;
import android.text.format.DateUtils;

import com.android.email.activity.setup.AccountSettingsUtils;
import com.android.emailcommon.Logging;
import com.android.emailcommon.VendorPolicyLoader.OAuthProvider;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.MessagingException;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

public class OAuthAuthenticator {
    private static final String TAG = Logging.LOG_TAG;

    public static final String OAUTH_REQUEST_CODE = "code";
    public static final String OAUTH_REQUEST_REFRESH_TOKEN = "refresh_token";
    public static final String OAUTH_REQUEST_CLIENT_ID = "client_id";
    public static final String OAUTH_REQUEST_CLIENT_SECRET = "client_secret";
    public static final String OAUTH_REQUEST_REDIRECT_URI = "redirect_uri";
    public static final String OAUTH_REQUEST_GRANT_TYPE = "grant_type";

    public static final String JSON_ACCESS_TOKEN = "access_token";
    public static final String JSON_REFRESH_TOKEN = "refresh_token";
    public static final String JSON_EXPIRES_IN = "expires_in";


    private static final long CONNECTION_TIMEOUT = 20 * DateUtils.SECOND_IN_MILLIS;
    private static final long COMMAND_TIMEOUT = 30 * DateUtils.SECOND_IN_MILLIS;

    final HttpClient mClient;

    public static class AuthenticationResult {
        public AuthenticationResult(final String accessToken, final String refreshToken,
                final int expiresInSeconds) {
            mAccessToken = accessToken;
            mRefreshToken = refreshToken;
            mExpiresInSeconds = expiresInSeconds;
        }

        @Override
        public String toString() {
            return "result access " + (mAccessToken==null?"null":"[REDACTED]") +
                    " refresh " + (mRefreshToken==null?"null":"[REDACTED]") +
                    " expiresInSeconds " + mExpiresInSeconds;
        }

        public final String mAccessToken;
        public final String mRefreshToken;
        public final int mExpiresInSeconds;
    }

    public OAuthAuthenticator() {
        final HttpParams params = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(params, (int)(CONNECTION_TIMEOUT));
        HttpConnectionParams.setSoTimeout(params, (int)(COMMAND_TIMEOUT));
        HttpConnectionParams.setSocketBufferSize(params, 8192);
        mClient = new DefaultHttpClient(params);
    }

    public AuthenticationResult requestAccess(final Context context, final String providerId,
            final String code) throws MessagingException, IOException {
        final OAuthProvider provider = AccountSettingsUtils.findOAuthProvider(context, providerId);
        if (provider == null) {
            LogUtils.e(TAG, "invalid provider %s", providerId);
            // This shouldn't happen, but if it does, it's a fatal. Throw an authentication failed
            // exception, this will at least give the user a heads up to set up their account again.
            throw new AuthenticationFailedException("Invalid provider" + providerId);
        }

        final HttpPost post = new HttpPost(provider.tokenEndpoint);
        post.setHeader("Content-Type", "application/x-www-form-urlencoded");
        final List<BasicNameValuePair> nvp = new ArrayList<BasicNameValuePair>();
        nvp.add(new BasicNameValuePair(OAUTH_REQUEST_CODE, code));
        nvp.add(new BasicNameValuePair(OAUTH_REQUEST_CLIENT_ID, provider.clientId));
        nvp.add(new BasicNameValuePair(OAUTH_REQUEST_CLIENT_SECRET, provider.clientSecret));
        nvp.add(new BasicNameValuePair(OAUTH_REQUEST_REDIRECT_URI, provider.redirectUri));
        nvp.add(new BasicNameValuePair(OAUTH_REQUEST_GRANT_TYPE, "authorization_code"));
        try {
            post.setEntity(new UrlEncodedFormEntity(nvp));
        } catch (UnsupportedEncodingException e) {
            LogUtils.e(TAG, e, "unsupported encoding");
            // This shouldn't happen, but if it does, it's a fatal. Throw an authentication failed
            // exception, this will at least give the user a heads up to set up their account again.
            throw new AuthenticationFailedException("Unsupported encoding", e);
        }

        return doRequest(post);
    }

    public AuthenticationResult requestRefresh(final Context context, final String providerId,
            final String refreshToken) throws MessagingException, IOException {
        final OAuthProvider provider = AccountSettingsUtils.findOAuthProvider(context, providerId);
        if (provider == null) {
            LogUtils.e(TAG, "invalid provider %s", providerId);
            // This shouldn't happen, but if it does, it's a fatal. Throw an authentication failed
            // exception, this will at least give the user a heads up to set up their account again.
            throw new AuthenticationFailedException("Invalid provider" + providerId);
        }
        final HttpPost post = new HttpPost(provider.refreshEndpoint);
        post.setHeader("Content-Type", "application/x-www-form-urlencoded");
        final List<BasicNameValuePair> nvp = new ArrayList<BasicNameValuePair>();
        nvp.add(new BasicNameValuePair(OAUTH_REQUEST_REFRESH_TOKEN, refreshToken));
        nvp.add(new BasicNameValuePair(OAUTH_REQUEST_CLIENT_ID, provider.clientId));
        nvp.add(new BasicNameValuePair(OAUTH_REQUEST_CLIENT_SECRET, provider.clientSecret));
        nvp.add(new BasicNameValuePair(OAUTH_REQUEST_GRANT_TYPE, "refresh_token"));
        try {
            post.setEntity(new UrlEncodedFormEntity(nvp));
        } catch (UnsupportedEncodingException e) {
            LogUtils.e(TAG, e, "unsupported encoding");
            // This shouldn't happen, but if it does, it's a fatal. Throw an authentication failed
            // exception, this will at least give the user a heads up to set up their account again.
            throw new AuthenticationFailedException("Unsuported encoding", e);
        }

        return doRequest(post);
    }

    private AuthenticationResult doRequest(HttpPost post) throws MessagingException,
            IOException {
        final HttpResponse response;
        response = mClient.execute(post);
        final int status = response.getStatusLine().getStatusCode();
        if (status == HttpStatus.SC_OK) {
            return parseResponse(response);
        } else if (status == HttpStatus.SC_FORBIDDEN || status == HttpStatus.SC_UNAUTHORIZED ||
                status == HttpStatus.SC_BAD_REQUEST) {
            LogUtils.e(TAG, "HTTP Authentication error getting oauth tokens %d", status);
            // This is fatal, and we probably should clear our tokens after this.
            throw new AuthenticationFailedException("Auth error getting auth token");
        } else {
            LogUtils.e(TAG, "HTTP Error %d getting oauth tokens", status);
            // This is probably a transient error, we can try again later.
            throw new MessagingException("HTTPError " + status + " getting oauth token");
        }
    }

    private AuthenticationResult parseResponse(HttpResponse response) throws IOException,
            MessagingException {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.getEntity().getContent(), "UTF-8"));
        final StringBuilder builder = new StringBuilder();
        for (String line = null; (line = reader.readLine()) != null;) {
            builder.append(line).append("\n");
        }
        try {
            final JSONObject jsonResult = new JSONObject(builder.toString());
            final String accessToken = jsonResult.getString(JSON_ACCESS_TOKEN);
            final String expiresIn = jsonResult.getString(JSON_EXPIRES_IN);
            final String refreshToken;
            if (jsonResult.has(JSON_REFRESH_TOKEN)) {
                refreshToken = jsonResult.getString(JSON_REFRESH_TOKEN);
            } else {
                refreshToken = null;
            }
            try {
                int expiresInSeconds = Integer.valueOf(expiresIn);
                return new AuthenticationResult(accessToken, refreshToken, expiresInSeconds);
            } catch (NumberFormatException e) {
                LogUtils.e(TAG, e, "Invalid expiration %s", expiresIn);
                // This indicates a server error, we can try again later.
                throw new MessagingException("Invalid number format", e);
            }
        } catch (JSONException e) {
            LogUtils.e(TAG, e, "Invalid JSON");
            // This indicates a server error, we can try again later.
            throw new MessagingException("Invalid JSON", e);
        }
    }
}

