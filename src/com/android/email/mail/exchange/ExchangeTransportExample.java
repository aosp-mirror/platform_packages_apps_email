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
import com.android.email.mail.MessagingException;

import android.content.Context;
import android.text.TextUtils;

import java.net.URI;
import java.util.HashMap;

/**
 * Sample code for implementing a new server transport.  See also ExchangeStoreExample,
 * ExchangeFolderExample, and ExchangeSenderExample.
 */
public class ExchangeTransportExample {
    public static final int CONNECTION_SECURITY_NONE = 0;
    public static final int CONNECTION_SECURITY_SSL_REQUIRED = 1;
    
    public static final String FOLDER_INBOX = Email.INBOX;
    
    private final Context mContext;
    
    private String mHost;
    private String mDomain;
    private String mUsername;
    private String mPassword;

    private static HashMap<String, ExchangeTransportExample> sUriToInstanceMap =
        new HashMap<String, ExchangeTransportExample>();
    private static final HashMap<String, Integer> sFolderMap = new HashMap<String, Integer>();

    
    /**
     * Public factory.  The transport should be a singleton (per Uri)
     */
    public synchronized static ExchangeTransportExample getInstance(URI uri, Context context)
    throws MessagingException {
        if (!uri.getScheme().equals("eas") && !uri.getScheme().equals("eas+ssl+")) {
            throw new MessagingException("Invalid scheme");
        }

        final String key = uri.toString();
        ExchangeTransportExample transport = sUriToInstanceMap.get(key);
        if (transport == null) { 
            transport = new ExchangeTransportExample(uri, context);
            sUriToInstanceMap.put(key, transport);
        }
        return transport;
    }

    /**
     * Private constructor - use public factory.
     */
    private ExchangeTransportExample(URI uri, Context context) throws MessagingException {
        mContext = context;
        setUri(uri);
    }

    /**
     * Use the Uri to set up a newly-constructed transport
     * @param uri
     * @throws MessagingException
     */
    private void setUri(final URI uri) throws MessagingException {
        mHost = uri.getHost();
        if (mHost == null) {
            throw new MessagingException("host not specified");
        }

        mDomain = uri.getPath();
        if (!TextUtils.isEmpty(mDomain)) {
            mDomain = mDomain.substring(1);
        }

        final String userInfo = uri.getUserInfo();
        if (userInfo == null) {
            throw new MessagingException("user information not specifed");
        }
        final String[] uinfo = userInfo.split(":", 2);
        if (uinfo.length != 2) {
            throw new MessagingException("user name and password not specified");
        }
        mUsername = uinfo[0];
        mPassword = uinfo[1];
    }

    /**
     * Blocking call that checks for a useable server connection, credentials, etc.
     * @param uri the server/account to try and connect to
     * @throws MessagingException thrown if the connection, server, account are not useable
     */
    public void checkSettings(URI uri) throws MessagingException {
        setUri(uri);
        // Perform a server connection here
        // Throw MessageException if not useable
    }
    
    /**
     * Typical helper function:  Return existence of a given folder
     */
    public boolean isFolderAvailable(final String folder) {
        return sFolderMap.containsKey(folder);
    }
}