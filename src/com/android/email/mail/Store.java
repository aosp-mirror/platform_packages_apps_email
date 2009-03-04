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

import java.util.HashMap;

import android.app.Application;

import com.android.email.mail.store.ImapStore;
import com.android.email.mail.store.LocalStore;
import com.android.email.mail.store.Pop3Store;

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

    private static HashMap<String, Store> mStores = new HashMap<String, Store>();

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
    public synchronized static Store getInstance(String uri, Application application) throws MessagingException {
        Store store = mStores.get(uri);
        if (store == null) {
            if (uri.startsWith(STORE_SCHEME_IMAP)) {
                store = new ImapStore(uri);
            } else if (uri.startsWith(STORE_SCHEME_POP3)) {
                store = new Pop3Store(uri);
            } else if (uri.startsWith(STORE_SCHEME_LOCAL)) {
                store = new LocalStore(uri, application);
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

    public abstract Folder getFolder(String name) throws MessagingException;

    public abstract Folder[] getPersonalNamespaces() throws MessagingException;

    public abstract void checkSettings() throws MessagingException;
}
