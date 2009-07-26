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

import com.android.email.mail.FetchProfile;
import com.android.email.mail.Flag;
import com.android.email.mail.Folder;
import com.android.email.mail.Message;
import com.android.email.mail.MessageRetrievalListener;
import com.android.email.mail.MessagingException;

/**
 * Sample code for implementing a new server folder.  See also ExchangeStoreExample,
 * ExchangeSenderExample, and ExchangeTransportExample.
 */
public class ExchangeFolderExample extends Folder {

    private final ExchangeTransportExample mTransport;
    private final ExchangeStoreExample mStore;
    private final String mName;

    private PersistentDataCallbacks mPersistenceCallbacks;

    public ExchangeFolderExample(ExchangeStoreExample store, String name) 
            throws MessagingException {
        mStore = store;
        mTransport = store.getTransport();
        mName = name;
        if (!mTransport.isFolderAvailable(name)) {
            throw new MessagingException("folder not supported: " + name);
        }
    }

    @Override
    public void appendMessages(Message[] messages) throws MessagingException {
        // TODO Implement this function
    }

    @Override
    public void close(boolean expunge) throws MessagingException {
        mPersistenceCallbacks = null;
        // TODO Implement this function
    }

    @Override
    public void copyMessages(Message[] msgs, Folder folder, MessageUpdateCallbacks callbacks)
            throws MessagingException {
        // TODO Implement this function
    }

    @Override
    public boolean create(FolderType type) throws MessagingException {
        // TODO Implement this function
        return false;
    }

    @Override
    public void delete(boolean recurse) throws MessagingException {
        // TODO Implement this function
    }

    @Override
    public boolean exists() throws MessagingException {
        // TODO Implement this function
        return false;
    }

    @Override
    public Message[] expunge() throws MessagingException {
        // TODO Implement this function
        return null;
    }

    @Override
    public void fetch(Message[] messages, FetchProfile fp, MessageRetrievalListener listener)
            throws MessagingException {
        // TODO Implement this function
    }

    @Override
    public Message getMessage(String uid) throws MessagingException {
        // TODO Implement this function
        return null;
    }

    @Override
    public int getMessageCount() throws MessagingException {
        // TODO Implement this function
        return 0;
    }

    @Override
    public Message[] getMessages(int start, int end, MessageRetrievalListener listener)
            throws MessagingException {
        // TODO Implement this function
        return null;
    }

    @Override
    public Message[] getMessages(MessageRetrievalListener listener) throws MessagingException {
        // TODO Implement this function
        return null;
    }

    @Override
    public Message[] getMessages(String[] uids, MessageRetrievalListener listener)
            throws MessagingException {
        // TODO Implement this function
        return null;
    }

    @Override
    public OpenMode getMode() throws MessagingException {
        // TODO Implement this function
        return null;
    }

    @Override
    public String getName() {
        // TODO Implement this function
        return null;
    }

    @Override
    public Flag[] getPermanentFlags() throws MessagingException {
        // TODO Implement this function
        return null;
    }

    @Override
    public int getUnreadMessageCount() throws MessagingException {
        // TODO Implement this function
        return 0;
    }

    @Override
    public boolean isOpen() {
        // TODO Implement this function
        return false;
    }

    @Override
    public void open(OpenMode mode, PersistentDataCallbacks callbacks) throws MessagingException {
        mPersistenceCallbacks = callbacks;
        // TODO Implement this function
    }

    @Override
    public void setFlags(Message[] messages, Flag[] flags, boolean value) throws MessagingException {
        // TODO Implement this function
    }


}
