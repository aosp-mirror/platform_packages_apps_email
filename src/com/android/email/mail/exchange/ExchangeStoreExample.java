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

import com.android.email.mail.Folder;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;

import android.content.Context;

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
    
    private final Context mContext;
    private URI mUri;

    private final ExchangeTransportExample mTransport;
    private final HashMap<String, Folder> mFolders = new HashMap<String, Folder>();

    /**
     * Factory method.
     */
    public static Store newInstance(String uri, Context context)
    throws MessagingException {
        return new ExchangeStoreExample(uri, context);
    }

    /**
     * eas://user:password@server/domain
     *
     * @param _uri
     * @param application
     * @throws MessagingException
     */
    private ExchangeStoreExample(String _uri, Context context) throws MessagingException {
        mContext = context;
        try {
            mUri = new URI(_uri);
        } catch (URISyntaxException e) {
            throw new MessagingException("Invalid uri for ExchangeStoreExample");
        }

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
     * Get class of SettingActivity for this Store class.
     * @return Activity class that has class method actionEditIncomingSettings(). 
     */
    @Override
    public Class<? extends android.app.Activity> getSettingActivityClass() {
        return com.android.email.activity.setup.AccountSetupExchange.class;
    }
}

