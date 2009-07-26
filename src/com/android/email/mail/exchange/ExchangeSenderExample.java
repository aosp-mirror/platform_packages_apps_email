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

import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Sender;

import android.content.Context;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Sample code for implementing a new Sender.  See also ExchangeSenderExample,
 * ExchangeFolderExample, and ExchangeTransportExample.
 * 
 * To enable this placeholder, please add:
 *   <sender scheme="eas" class="com.android.email.mail.exchange.ExchangeSenderExample" />
 * to res/xml/senders.xml
 */
public class ExchangeSenderExample extends Sender {

    private final Context mContext;
    private final ExchangeTransportExample mTransport;

    /**
     * Factory method.
     */
    public static Sender newInstance(String uri, Context context) throws MessagingException {
        return new ExchangeSenderExample(uri, context);
    }

    private ExchangeSenderExample(String _uri, Context context) throws MessagingException {
        mContext = context;

        URI uri = null;
        try {
            uri = new URI(_uri);
        } catch (URISyntaxException e) {
            throw new MessagingException("Invalid uri for ExchangeSenderExample");
        }
        
        String scheme = uri.getScheme();
        int connectionSecurity;
        if (scheme.equals("eas")) {
            connectionSecurity = ExchangeTransportExample.CONNECTION_SECURITY_NONE;
        } else if (scheme.equals("eas+ssl+")) {
            connectionSecurity = ExchangeTransportExample.CONNECTION_SECURITY_SSL_REQUIRED;
        } else {
            throw new MessagingException("Unsupported protocol");
        }

        mTransport = ExchangeTransportExample.getInstance(uri, context);
    }

    @Override
    public void close() throws MessagingException {
        // TODO Auto-generated method stub

    }

    @Override
    public void open() throws MessagingException {
        // TODO Auto-generated method stub

    }

    @Override
    public void sendMessage(Message message) throws MessagingException {
        // TODO Auto-generated method stub

    }

    /**
     * Get class of SettingActivity for this Sender class.
     * @return Activity class that has class method actionEditOutgoingSettings(), or null if
     * outgoing settings should not be presented (e.g. they're handled by the incoming settings
     * screen).
     */
    @Override
    public Class<? extends android.app.Activity> getSettingActivityClass() {
        return null;
    }
}
