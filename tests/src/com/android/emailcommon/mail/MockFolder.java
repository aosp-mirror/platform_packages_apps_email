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

package com.android.emailcommon.mail;

import android.content.Context;

import com.android.emailcommon.service.SearchParams;


public class MockFolder extends Folder {

    @Override
    public void appendMessage(Context context, Message message, boolean noTimeout) {
    }

    @Override
    public void close(boolean expunge) {
    }

    @Override
    public void copyMessages(Message[] msgs, Folder folder,
            MessageUpdateCallbacks callbacks) {
    }

    @Override
    public boolean canCreate(FolderType type) {
        return false;
    }

    @Override
    public boolean create(FolderType type) {
        return false;
    }

    @Override
    public void delete(boolean recurse) {
    }

    @Override
    public boolean exists() {
        return false;
    }

    @Override
    public Message[] expunge() {
        return null;
    }

    @Override
    public void fetch(Message[] messages, FetchProfile fp, MessageRetrievalListener listener) {
    }

    @Override
    public Message getMessage(String uid) {
        return null;
    }

    @Override
    public int getMessageCount() {
        return 0;
    }

    @Override
    public Message[] getMessages(int start, int end, MessageRetrievalListener listener) {
        return null;
    }

    @Override
    public Message[] getMessages(String[] uids, MessageRetrievalListener listener) {
        return null;
    }

    @Override
    public OpenMode getMode() {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public Flag[] getPermanentFlags() {
        return null;
    }

    @Override
    public int getUnreadMessageCount() {
        return 0;
    }

    @Override
    public boolean isOpen() {
        return false;
    }

    @Override
    public void open(OpenMode mode) {
    }

    @Override
    public void setFlags(Message[] messages, Flag[] flags, boolean value) {
    }

    @Override
    public Message createMessage(String uid) {
        return null;
    }

    @Override
    public Message[] getMessages(SearchParams params, MessageRetrievalListener listener)
            throws MessagingException {
        return null;
    }

    /* (non-Javadoc)
     * @see com.android.emailcommon.mail.Folder#getMessages(long, long,
     * com.android.emailcommon.mail.Folder.MessageRetrievalListener)
     */
    @Override
    public Message[] getMessages(long startDate, long endDate, MessageRetrievalListener listener)
            throws MessagingException {
        return null;
    }

}
