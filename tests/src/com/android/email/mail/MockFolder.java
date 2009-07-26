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

package com.android.email.mail;

public class MockFolder extends Folder {

    @Override
    public void appendMessages(Message[] messages) throws MessagingException {
        // TODO Auto-generated method stub

    }

    @Override
    public void close(boolean expunge) throws MessagingException {
        // TODO Auto-generated method stub

    }

    @Override
    public void copyMessages(Message[] msgs, Folder folder, 
            MessageUpdateCallbacks callbacks) throws MessagingException {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean create(FolderType type) throws MessagingException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void delete(boolean recurse) throws MessagingException {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean exists() throws MessagingException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public Message[] expunge() throws MessagingException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void fetch(Message[] messages, FetchProfile fp, MessageRetrievalListener listener)
            throws MessagingException {
        // TODO Auto-generated method stub

    }

    @Override
    public Message getMessage(String uid) throws MessagingException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int getMessageCount() throws MessagingException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public Message[] getMessages(int start, int end, MessageRetrievalListener listener)
            throws MessagingException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Message[] getMessages(MessageRetrievalListener listener) throws MessagingException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Message[] getMessages(String[] uids, MessageRetrievalListener listener)
            throws MessagingException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public OpenMode getMode() throws MessagingException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getName() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Flag[] getPermanentFlags() throws MessagingException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int getUnreadMessageCount() throws MessagingException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public boolean isOpen() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void open(OpenMode mode, PersistentDataCallbacks callbacks) throws MessagingException {
        // TODO Auto-generated method stub

    }

    @Override
    public void setFlags(Message[] messages, Flag[] flags, boolean value) throws MessagingException {
        // TODO Auto-generated method stub

    }

}
