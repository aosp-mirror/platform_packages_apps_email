/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.email.mail.store;

import com.android.email.ExchangeUtils;
import com.android.email.mail.Store;
import com.android.email.mail.StoreSynchronizer;
import com.android.emailcommon.mail.Folder;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.HostAuth;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.IEmailService;

import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;

import java.util.HashMap;

/**
 * Our Exchange service does not use the sender/store model.  This class exists for exactly two
 * purposes, (1) to provide a hook for checking account connections, and (2) to return
 * "AccountSetupExchange.class" for getSettingActivityClass().
 */
public class ExchangeStore extends Store {
    public static final String LOG_TAG = "ExchangeStore";

    private final ExchangeTransport mTransport;

    /**
     * Static named constructor.
     */
    public static Store newInstance(Account account, Context context,
            PersistentDataCallbacks callbacks) throws MessagingException {
        return new ExchangeStore(account, context, callbacks);
    }

    /**
     * Creates a new store for the given account.
     */
    private ExchangeStore(Account account, Context context, PersistentDataCallbacks callbacks)
            throws MessagingException {
        mTransport = ExchangeTransport.getInstance(account, context);
    }

    @Override
    public Bundle checkSettings() throws MessagingException {
        return mTransport.checkSettings();
    }

    @Override
    public Folder getFolder(String name) {
        return null;
    }

    @Override
    public Folder[] updateFolders() {
        return null;
    }

    /**
     * Get class of SettingActivity for this Store class.
     * @return Activity class that has class method actionEditIncomingSettings()
     */
    @Override
    public Class<? extends android.app.Activity> getSettingActivityClass() {
        return com.android.email.activity.setup.AccountSetupExchange.class;
    }

    /**
     * Get class of sync'er for this Store class.  Because exchange Sync rules are so different
     * than IMAP or POP3, it's likely that an Exchange implementation will need its own sync
     * controller.  If so, this function must return a non-null value.
     *
     * @return Message Sync controller, or null to use default
     */
    @Override
    public StoreSynchronizer getMessageSynchronizer() {
        return null;
    }

    /**
     * Inform MessagingController that this store requires message structures to be prefetched
     * before it can fetch message bodies (this is due to EAS protocol restrictions.)
     * @return always true for EAS
     */
    @Override
    public boolean requireStructurePrefetch() {
        return true;
    }

    /**
     * Inform MessagingController that messages sent via EAS will be placed in the Sent folder
     * automatically (server-side) and don't need to be uploaded.
     * @return always false for EAS (assuming server-side copy is supported)
     */
    @Override
    public boolean requireCopyMessageToSentFolder() {
        return false;
    }

    public static class ExchangeTransport {
        private final Context mContext;
        private String mHost;
        private String mDomain;
        private int mPort;
        private boolean mSsl;
        private boolean mTSsl;
        private String mUsername;
        private String mPassword;

        private static final HashMap<String, ExchangeTransport> sUriToInstanceMap =
            new HashMap<String, ExchangeTransport>();

        /**
         * Public factory.  The transport should be a singleton (per Uri)
         */
        public synchronized static ExchangeTransport getInstance(Account account, Context context)
                throws MessagingException {
            final String storeKey = getStoreKey(context, account);
            ExchangeTransport transport = sUriToInstanceMap.get(storeKey);
            if (transport == null) {
                transport = new ExchangeTransport(account, context);
                sUriToInstanceMap.put(storeKey, transport);
            }
            return transport;
        }

        /**
         * Private constructor - use public factory.
         */
        private ExchangeTransport(Account account, Context context) throws MessagingException {
            mContext = context.getApplicationContext();
            setAccount(account);
        }

        private void setAccount(final Account account) throws MessagingException {
            HostAuth recvAuth = account.getOrCreateHostAuthRecv(mContext);
            if (recvAuth == null || !STORE_SCHEME_EAS.equalsIgnoreCase(recvAuth.mProtocol)) {
                throw new MessagingException("Unsupported protocol");
            }
            mHost = recvAuth.mAddress;
            if (mHost == null) {
                throw new MessagingException("host not specified");
            }
            mDomain = recvAuth.mDomain;
            if (!TextUtils.isEmpty(mDomain)) {
                mDomain = mDomain.substring(1);
            }
            mPort = 80;
            if ((recvAuth.mFlags & HostAuth.FLAG_SSL) != 0) {
                mPort = 443;
                mSsl = true;
            }
            mTSsl = ((recvAuth.mFlags & HostAuth.FLAG_TRUST_ALL) != 0);

            String[] userInfoParts = recvAuth.getLogin();
            if (userInfoParts != null) {
                mUsername = userInfoParts[0];
                mPassword = userInfoParts[1];
                if (TextUtils.isEmpty(mPassword)) {
                    throw new MessagingException("user name and password not specified");
                }
            } else {
                throw new MessagingException("user information not specifed");
            }
        }

        /**
         * Here's where we check the settings for EAS.
         * @throws MessagingException if we can't authenticate the account
         */
        public Bundle checkSettings() throws MessagingException {
            try {
                IEmailService svc = ExchangeUtils.getExchangeService(mContext, null);
                // Use a longer timeout for the validate command.  Note that the instanceof check
                // shouldn't be necessary; we'll do it anyway, just to be safe
                if (svc instanceof EmailServiceProxy) {
                    ((EmailServiceProxy)svc).setTimeout(90);
                }
                return svc.validate("eas", mHost, mUsername, mPassword, mPort, mSsl, mTSsl);
            } catch (RemoteException e) {
                throw new MessagingException("Call to validate generated an exception", e);
            }
        }
    }

    /**
     * We handle AutoDiscover for Exchange 2007 (and later) here, wrapping the EmailService call.
     * The service call returns a HostAuth and we return null if there was a service issue
     */
    @Override
    public Bundle autoDiscover(Context context, String username, String password) {
        try {
            return ExchangeUtils.getExchangeService(context, null).autoDiscover(username, password);
        } catch (RemoteException e) {
            return null;
        }
    }
}
