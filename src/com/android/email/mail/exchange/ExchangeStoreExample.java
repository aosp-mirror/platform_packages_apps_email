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

package com.android.email.mail.exchange;

import com.android.email.Email;
import com.android.email.mail.Folder;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;
import com.android.email.mail.StoreSynchronizer;

import android.content.Context;
import android.util.Config;
import android.util.Log;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;

/**
 * This is a placeholder for use in Exchange implementations.  It is based on the notion of
 * lightweight adapter classes for Store, Folder, and Sender, and a common facade for the common
 * Transport code.
 * 
 * To enable this placeholder, please add:
 *   <store scheme="eas" class="com.android.email.mail.exchange.ExchangeStoreExample" />
 * to res/xml/stores.xml
 */
public class ExchangeStoreExample extends Store {
    public static final String LOG_TAG = "ExchangeStoreExample";
    
    private final Context mContext;
    private URI mUri;
    private PersistentDataCallbacks mCallbacks;

    private final ExchangeTransportExample mTransport;
    private final HashMap<String, Folder> mFolders = new HashMap<String, Folder>();
    
    private boolean mPushModeRunning = false;

    /**
     * Factory method.
     */
    public static Store newInstance(String uri, Context context, PersistentDataCallbacks callbacks)
    throws MessagingException {
        return new ExchangeStoreExample(uri, context, callbacks);
    }

    /**
     * eas://user:password@server/domain
     *
     * @param _uri
     * @param application
     * @throws MessagingException
     */
    private ExchangeStoreExample(String _uri, Context context, PersistentDataCallbacks callbacks)
            throws MessagingException {
        mContext = context;
        try {
            mUri = new URI(_uri);
        } catch (URISyntaxException e) {
            throw new MessagingException("Invalid uri for ExchangeStoreExample");
        }
        mCallbacks = callbacks;

        String scheme = mUri.getScheme();
        int connectionSecurity;
        if (scheme.equals("eas")) {
            connectionSecurity = ExchangeTransportExample.CONNECTION_SECURITY_NONE;
        } else if (scheme.equals("eas+ssl+")) {
            connectionSecurity = ExchangeTransportExample.CONNECTION_SECURITY_SSL_REQUIRED;
        } else {
            throw new MessagingException("Unsupported protocol");
        }

        mTransport = ExchangeTransportExample.getInstance(mUri, context);
    }

    /**
     * Retrieve the underlying transport.  Used primarily for testing.
     * @return
     */
    /* package */ ExchangeTransportExample getTransport() {
        return mTransport;
    }

    @Override
    public void checkSettings() throws MessagingException {
        mTransport.checkSettings(mUri);
    }

    @Override
    public Folder getFolder(String name) throws MessagingException {
        synchronized (mFolders) {
            Folder folder = mFolders.get(name);
            if (folder == null) {
                folder = new ExchangeFolderExample(this, name);
                mFolders.put(folder.getName(), folder);
            }
            return folder;
        }
    }

    @Override
    public Folder[] getPersonalNamespaces() throws MessagingException {
        return new Folder[] {
                getFolder(ExchangeTransportExample.FOLDER_INBOX),
        };
    }
    
    /**
     * For a store that supports push mode, this is the API that enables it or disables it.
     * The store should use this API to start or stop its persistent connection service or thread.
     * 
     * <p>Note, may be called multiple times, even after push mode has been started or stopped.
     * 
     * @param enablePushMode start or stop push mode delivery
     */
    @Override
    public void enablePushModeDelivery(boolean enablePushMode) {
        if (Config.LOGD && Email.DEBUG) {
            if (enablePushMode && !mPushModeRunning) {
                Log.d(Email.LOG_TAG, "start push mode");
            } else if (!enablePushMode && mPushModeRunning) {
                Log.d(Email.LOG_TAG, "stop push mode");
            } else {
                Log.d(Email.LOG_TAG, enablePushMode ?
                        "push mode already started" : "push mode already stopped");
            }
        }
        mPushModeRunning = enablePushMode;
    }
    
    /**
     * Get class of SettingActivity for this Store class.
     * @return Activity class that has class method actionEditIncomingSettings(). 
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
}

