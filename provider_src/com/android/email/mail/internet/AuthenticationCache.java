package com.android.email.mail.internet;

import android.content.Context;
import android.text.format.DateUtils;

import com.android.email.mail.internet.OAuthAuthenticator.AuthenticationResult;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Credential;
import com.android.emailcommon.provider.HostAuth;
import com.android.mail.utils.LogUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AuthenticationCache {
    private static AuthenticationCache sCache;

    // Threshold for refreshing a token. If the token is expected to expire within this amount of
    // time, we won't even bother attempting to use it and will simply force a refresh.
    private static final long EXPIRATION_THRESHOLD = 5 * DateUtils.MINUTE_IN_MILLIS;

    private final Map<Long, CacheEntry> mCache;
    private final OAuthAuthenticator mAuthenticator;

    private class CacheEntry {
        CacheEntry(long accountId, String providerId, String accessToken, String refreshToken,
                long expirationTime) {
            mAccountId = accountId;
            mProviderId = providerId;
            mAccessToken = accessToken;
            mRefreshToken = refreshToken;
            mExpirationTime = expirationTime;
        }

        final long mAccountId;
        String mProviderId;
        String mAccessToken;
        String mRefreshToken;
        long mExpirationTime;
    }

    public static AuthenticationCache getInstance() {
        synchronized (AuthenticationCache.class) {
            if (sCache == null) {
                sCache = new AuthenticationCache();
            }
            return sCache;
        }
    }

    private AuthenticationCache() {
        mCache = new HashMap<Long, CacheEntry>();
        mAuthenticator = new OAuthAuthenticator();
    }

    // Gets an access token for the given account. This may be whatever is currently cached, or
    // it may query the server to get a new one if the old one is expired or nearly expired.
    public String retrieveAccessToken(Context context, Account account) throws
            MessagingException, IOException {
        // Currently, we always use the same OAuth info for both sending and receiving.
        // If we start to allow different credential objects for sending and receiving, this
        // will need to be updated.
        CacheEntry entry = null;
        synchronized (mCache) {
            entry = getEntry(context, account);
        }
        synchronized (entry) {
            final long actualExpiration = entry.mExpirationTime - EXPIRATION_THRESHOLD;
            if (System.currentTimeMillis() > actualExpiration) {
                // This access token is pretty close to end of life. Don't bother trying to use it,
                // it might just time out while we're trying to sync. Go ahead and refresh it
                // immediately.
                refreshEntry(context, entry);
            }
            return entry.mAccessToken;
        }
    }

    public String refreshAccessToken(Context context, Account account) throws
            MessagingException, IOException {
        CacheEntry entry = getEntry(context, account);
        synchronized (entry) {
            refreshEntry(context, entry);
            return entry.mAccessToken;
        }
    }

    private CacheEntry getEntry(Context context, Account account) {
        CacheEntry entry;
        if (account.isSaved() && !account.isTemporary()) {
            entry = mCache.get(account.mId);
            if (entry == null) {
                LogUtils.d(Logging.LOG_TAG, "initializing entry from database");
                final HostAuth hostAuth = account.getOrCreateHostAuthRecv(context);
                final Credential credential = hostAuth.getOrCreateCredential(context);
                entry = new CacheEntry(account.mId, credential.mProviderId, credential.mAccessToken,
                        credential.mRefreshToken, credential.mExpiration);
                mCache.put(account.mId, entry);
            }
        } else {
            // This account is temporary, just create a temporary entry. Don't store
            // it in the cache, it won't be findable because we don't yet have an account Id.
            final HostAuth hostAuth = account.getOrCreateHostAuthRecv(context);
            final Credential credential = hostAuth.getCredential(context);
            entry = new CacheEntry(account.mId, credential.mProviderId, credential.mAccessToken,
                    credential.mRefreshToken, credential.mExpiration);
        }
        return entry;
    }

    private void refreshEntry(Context context, CacheEntry entry) throws
            IOException, MessagingException {
        LogUtils.d(Logging.LOG_TAG, "AuthenticationCache refreshEntry %d", entry.mAccountId);
        try {
            final AuthenticationResult result = mAuthenticator.requestRefresh(context,
                    entry.mProviderId, entry.mRefreshToken);
            // Don't set the refresh token here, it's not returned by the refresh response,
            // so setting it here would make it blank.
            entry.mAccessToken = result.mAccessToken;
            entry.mExpirationTime = result.mExpiresInSeconds * DateUtils.SECOND_IN_MILLIS +
                    System.currentTimeMillis();
            saveEntry(context, entry);
        } catch (AuthenticationFailedException e) {
            // This is fatal. Clear the tokens and rethrow the exception.
            LogUtils.d(Logging.LOG_TAG, "authentication failed, clearning");
            clearEntry(context, entry);
            throw e;
        } catch (MessagingException e) {
            LogUtils.d(Logging.LOG_TAG, "messaging exception");
            throw e;
        } catch (IOException e) {
            LogUtils.d(Logging.LOG_TAG, "IO exception");
            throw e;
        }
    }

    private void saveEntry(Context context, CacheEntry entry) {
        LogUtils.d(Logging.LOG_TAG, "saveEntry");

        final Account account = Account.restoreAccountWithId(context,  entry.mAccountId);
        final HostAuth hostAuth = account.getOrCreateHostAuthRecv(context);
        final Credential cred = hostAuth.getOrCreateCredential(context);
        cred.mProviderId = entry.mProviderId;
        cred.mAccessToken = entry.mAccessToken;
        cred.mRefreshToken = entry.mRefreshToken;
        cred.mExpiration = entry.mExpirationTime;
        cred.update(context, cred.toContentValues());
    }

    private void clearEntry(Context context, CacheEntry entry) {
        LogUtils.d(Logging.LOG_TAG, "clearEntry");
        entry.mAccessToken = "";
        entry.mRefreshToken = "";
        entry.mExpirationTime = 0;
        saveEntry(context, entry);
        mCache.remove(entry.mAccountId);
    }
}
