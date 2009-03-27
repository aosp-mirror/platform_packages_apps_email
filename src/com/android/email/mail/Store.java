/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.email.mail;

import com.android.email.Email;
import com.android.email.R;

import org.xmlpull.v1.XmlPullParserException;

import android.content.Context;
import android.content.res.XmlResourceParser;

import java.io.IOException;

/**
 * Store is the access point for an email message store. It's location can be
 * local or remote and no specific protocol is defined. Store is intended to
 * loosely model in combination the JavaMail classes javax.mail.Store and
 * javax.mail.Folder along with some additional functionality to improve
 * performance on mobile devices. Implementations of this class should focus on
 * making as few network connections as possible.
 */
public abstract class Store {
    
    /**
     * String constants for known store schemes.
     */
    public static final String STORE_SCHEME_IMAP = "imap";
    public static final String STORE_SCHEME_POP3 = "pop3";
    public static final String STORE_SCHEME_LOCAL = "local";
    
    /**
     * A global suggestion to Store implementors on how much of the body
     * should be returned on FetchProfile.Item.BODY_SANE requests.
     */
    public static final int FETCH_BODY_SANE_SUGGESTED_SIZE = (50 * 1024);

    private static java.util.HashMap<String, Store> mStores =
        new java.util.HashMap<String, Store>();

    /**
     * Static named constructor.  It should be overrode by extending class.
     * Because this method will be called through reflection, it can not be protected. 
     */
    public static Store newInstance(String uri, Context context)
            throws MessagingException {
        throw new MessagingException("Store.newInstance: Unknown scheme in " + uri);
    }

    private static Store instanciateStore(String className, String uri, Context context)
        throws MessagingException {
        Object o = null;
        try {
            Class<?> c = Class.forName(className);
            // and invoke "newInstance" class method and instantiate store object.
            java.lang.reflect.Method m =
                c.getMethod("newInstance", String.class, Context.class);
            o = m.invoke(null, uri, context);
        } catch (Exception e) {
            android.util.Log.e(Email.LOG_TAG,
                    String.format("can not invoke %s.newInstance(String, Context) method for %s",
                            className, uri));
            throw new MessagingException("can not instanciate Store object for " + uri);
        }
        if (!(o instanceof Store)) {
            throw new MessagingException(
                    uri + ": " + className + " create incompatible object");
        }
        return (Store) o;
    }

    /**
     * Find Store implementation object consulting with stores.xml file.
     */
    private static Store findStore(int resourceId, String uri, Context context)
            throws MessagingException {
        Store store = null;
        try {
            XmlResourceParser xml = context.getResources().getXml(resourceId);
            int xmlEventType;
            // walk through stores.xml file.
            while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (xmlEventType == XmlResourceParser.START_TAG &&
                    "store".equals(xml.getName())) {
                    String scheme = xml.getAttributeValue(null, "scheme");
                    if (uri.startsWith(scheme)) {
                        // found store entry whose scheme is matched with uri.
                        // then load store class.
                        String className = xml.getAttributeValue(null, "class");
                        store = instanciateStore(className, uri, context);
                    }
                }
            }
        } catch (XmlPullParserException e) {
            // ignore
        } catch (IOException e) {
            // ignore
        }
        return store;
    }

    /**
     * Get an instance of a mail store. The URI is parsed as a standard URI and
     * the scheme is used to determine which protocol will be used. The
     * following schemes are currently recognized: imap - IMAP with no
     * connection security. Ex: imap://username:password@host/ imap+tls - IMAP
     * with TLS connection security, if the server supports it. Ex:
     * imap+tls://username:password@host imap+tls+ - IMAP with required TLS
     * connection security. Connection fails if TLS is not available. Ex:
     * imap+tls+://username:password@host imap+ssl+ - IMAP with required SSL
     * connection security. Connection fails if SSL is not available. Ex:
     * imap+ssl+://username:password@host
     *
     * @param uri The URI of the store.
     * @return an initialized store of the appropriate class
     * @throws MessagingException
     */
    public synchronized static Store getInstance(String uri, Context context)
        throws MessagingException {
        Store store = mStores.get(uri);
        if (store == null) {
            store = findStore(R.xml.stores_product, uri, context);
            if (store == null) {
                store = findStore(R.xml.stores, uri, context);
            }

            if (store != null) {
                mStores.put(uri, store);
            }
        }

        if (store == null) {
            throw new MessagingException("Unable to locate an applicable Store for " + uri);
        }

        return store;
    }

    /**
     * Get class of SettingActivity for this Store class.
     * @return Activity class that has class method actionEditIncomingSettings(). 
     */
    public Class<? extends android.app.Activity> getSettingActivityClass() {
        // default SettingActivity class
        return com.android.email.activity.setup.AccountSetupIncoming.class;
    }
    
    public abstract Folder getFolder(String name) throws MessagingException;

    public abstract Folder[] getPersonalNamespaces() throws MessagingException;

    public abstract void checkSettings() throws MessagingException;
    
    /**
     * Delete Store and its corresponding resources.
     * @throws MessagingException
     */
    public void delete() throws MessagingException {
    }
}
