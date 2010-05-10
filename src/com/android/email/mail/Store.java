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
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;

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
    public static final String STORE_SCHEME_EAS = "eas";
    public static final String STORE_SCHEME_LOCAL = "local";

    public static final String STORE_SECURITY_SSL = "+ssl";
    public static final String STORE_SECURITY_TLS = "+tls";
    public static final String STORE_SECURITY_TRUST_CERTIFICATES = "+trustallcerts";

    /**
     * A global suggestion to Store implementors on how much of the body
     * should be returned on FetchProfile.Item.BODY_SANE requests.
     */
    public static final int FETCH_BODY_SANE_SUGGESTED_SIZE = (50 * 1024);
    private static final HashMap<String, Store> sStores = new HashMap<String, Store>();

    /**
     * Static named constructor.  It should be overrode by extending class.
     * Because this method will be called through reflection, it can not be protected. 
     */
    public static Store newInstance(String uri, Context context, PersistentDataCallbacks callbacks)
            throws MessagingException {
        throw new MessagingException("Store.newInstance: Unknown scheme in " + uri);
    }

    private static Store instantiateStore(String className, String uri, Context context, 
            PersistentDataCallbacks callbacks)
        throws MessagingException {
        Object o = null;
        try {
            Class<?> c = Class.forName(className);
            // and invoke "newInstance" class method and instantiate store object.
            java.lang.reflect.Method m =
                c.getMethod("newInstance", String.class, Context.class, 
                        PersistentDataCallbacks.class);
            o = m.invoke(null, uri, context, callbacks);
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, String.format(
                    "exception %s invoking %s.newInstance.(String, Context) method for %s",
                    e.toString(), className, uri));
            throw new MessagingException("can not instantiate Store object for " + uri);
        }
        if (!(o instanceof Store)) {
            throw new MessagingException(
                    uri + ": " + className + " create incompatible object");
        }
        return (Store) o;
    }

    /**
     * Look up descriptive information about a particular type of store.
     */
    public static class StoreInfo {
        public String mScheme;
        public String mClassName;
        public boolean mPushSupported = false;
        public int mVisibleLimitDefault;
        public int mVisibleLimitIncrement;
        public int mAccountInstanceLimit;
        
        // TODO cache result for performance - silly to keep reading the XML
        public static StoreInfo getStoreInfo(String scheme, Context context) {
            StoreInfo result = getStoreInfo(R.xml.stores_product, scheme, context);
            if (result == null) {
                result = getStoreInfo(R.xml.stores, scheme, context);
            }
            return result;
        }
        
        public static StoreInfo getStoreInfo(int resourceId, String scheme, Context context) {
            try {
                XmlResourceParser xml = context.getResources().getXml(resourceId);
                int xmlEventType;
                // walk through stores.xml file.
                while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                    if (xmlEventType == XmlResourceParser.START_TAG && 
                            "store".equals(xml.getName())) {
                        String xmlScheme = xml.getAttributeValue(null, "scheme");
                        if (scheme != null && scheme.startsWith(xmlScheme)) {
                            StoreInfo result = new StoreInfo();
                            result.mScheme = xmlScheme;
                            result.mClassName = xml.getAttributeValue(null, "class");
                            result.mPushSupported = xml.getAttributeBooleanValue(
                                    null, "push", false);
                            result.mVisibleLimitDefault = xml.getAttributeIntValue(
                                    null, "visibleLimitDefault", Email.VISIBLE_LIMIT_DEFAULT);
                            result.mVisibleLimitIncrement = xml.getAttributeIntValue(
                                    null, "visibleLimitIncrement", Email.VISIBLE_LIMIT_INCREMENT);
                            result.mAccountInstanceLimit = xml.getAttributeIntValue(
                                    null, "accountInstanceLimit", -1);
                            return result;
                        }
                    }
                }
            } catch (XmlPullParserException e) {
                // ignore
            } catch (IOException e) {
                // ignore
            }
            return null;
        }
    }

    /**
     * Get an instance of a mail store. The URI is parsed as a standard URI and
     * the scheme is used to determine which protocol will be used.
     * 
     * Although the URI format is somewhat protocol-specific, we use the following 
     * guidelines wherever possible:
     * 
     * scheme [+ security [+]] :// username : password @ host [ / resource ]
     * 
     * Typical schemes include imap, pop3, local, eas.
     * Typical security models include SSL or TLS.
     * A + after the security identifier indicates "required".
     * 
     * Username, password, and host are as expected.
     * Resource is protocol specific.  For example, IMAP uses it as the path prefix.  EAS uses it
     * as the domain.
     *
     * @param uri The URI of the store.
     * @return an initialized store of the appropriate class
     * @throws MessagingException
     */
    public synchronized static Store getInstance(String uri, Context context, 
            PersistentDataCallbacks callbacks)
        throws MessagingException {
        Store store = sStores.get(uri);
        if (store == null) {
            StoreInfo info = StoreInfo.getStoreInfo(uri, context);
            if (info != null) {
                store = instantiateStore(info.mClassName, uri, context, callbacks);
            }

            if (store != null) {
                sStores.put(uri, store);
            }
        } else {
            // update the callbacks, which may have been null at creation time.
            store.setPersistentDataCallbacks(callbacks);
        }

        if (store == null) {
            throw new MessagingException("Unable to locate an applicable Store for " + uri);
        }

        return store;
    }
    
    /**
     * Delete an instance of a mail store.
     * 
     * The store should have been notified already by calling delete(), and the caller should
     * also take responsibility for deleting the matching LocalStore, etc.
     * @param storeUri the store to be removed
     */
    public synchronized static void removeInstance(String storeUri) {
        sStores.remove(storeUri);
    }

    /**
     * Get class of SettingActivity for this Store class.
     * @return Activity class that has class method actionEditIncomingSettings(). 
     */
    public Class<? extends android.app.Activity> getSettingActivityClass() {
        // default SettingActivity class
        return com.android.email.activity.setup.AccountSetupIncoming.class;
    }
    
    /**
     * Get class of sync'er for this Store class
     * @return Message Sync controller, or null to use default
     */
    public StoreSynchronizer getMessageSynchronizer() {
        return null;
    }
    
    /**
     * Some stores cannot download a message based only on the uid, and need the message structure
     * to be preloaded and provided to them.  This method allows a remote store to signal this
     * requirement.  Most stores do not need this and do not need to overload this method, which
     * simply returns "false" in the base class.
     * @return Return true if the remote store requires structure prefetch
     */
    public boolean requireStructurePrefetch() {
        return false;
    }
    
    /**
     * Some protocols require that a sent message be copied (uploaded) into the Sent folder
     * while others can take care of it automatically (ideally, on the server).  This function
     * allows a given store to indicate which mode(s) it supports.
     * @return true if the store requires an upload into "sent", false if this happens automatically
     * for any sent message.
     */
    public boolean requireCopyMessageToSentFolder() {
        return true;
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
    
    /**
     * If a Store intends to implement callbacks, it should be prepared to update them
     * via overriding this method.  They may not be available at creation time (in which case they
     * will be passed in as null.
     * @param callbacks The updated provider of store callbacks
     */
    protected void setPersistentDataCallbacks(PersistentDataCallbacks callbacks) {
    }
    
    /**
     * Callback interface by which a Store can read and write persistent data.
     * TODO This needs to be made more generic & flexible
     */
    public interface PersistentDataCallbacks {
        
        /**
         * Provides a small place for Stores to store persistent data.
         * @param key identifier for the data (e.g. "sync.key" or "folder.id")
         * @param value The data to persist.  All data must be encoded into a string,
         * so use base64 or some other encoding if necessary.
         */
        public void setPersistentString(String key, String value);

        /**
         * @param key identifier for the data (e.g. "sync.key" or "folder.id")
         * @param defaultValue The data to return if no data was ever saved for this store
         * @return the data saved by the Store, or null if never set.
         */
        public String getPersistentString(String key, String defaultValue);
    }

    /**
     * Handle discovery of account settings using only the user's email address and password
     * @param context the context of the caller
     * @param emailAddress the email address of the exchange user
     * @param password the password of the exchange user
     * @return a Bundle containing an error code and a HostAuth (if successful)
     * @throws MessagingException
     */
    public Bundle autoDiscover(Context context, String emailAddress, String password)
            throws MessagingException {
        return null;
    }
}
