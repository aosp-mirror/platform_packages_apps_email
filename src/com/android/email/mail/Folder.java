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


public abstract class Folder {
    public enum OpenMode {
        READ_WRITE, READ_ONLY,
    }

    public enum FolderType {
        HOLDS_FOLDERS, HOLDS_MESSAGES,
    }
    
    /**
     * Identifiers of "special" folders.
     */
    public enum FolderRole {
        INBOX,      // NOTE:  The folder's name must be INBOX
        TRASH,
        SENT,
        DRAFTS,
        
        OUTBOX,     // Local folders only - not used in remote Stores
        OTHER,      // this folder has no specific role
        UNKNOWN     // the role of this folder is unknown
    }

    /**
     * Callback for each message retrieval.
     *
     * Not all {@link Folder} implementation won't call it.
     * (Currently {@link com.android.email.mail.store.LocalStore.LocalFolder} won't.)
     */
    public interface MessageRetrievalListener {
        public void messageRetrieved(Message message);
    }

    /**
     * Forces an open of the MailProvider. If the provider is already open this
     * function returns without doing anything.
     *
     * @param mode READ_ONLY or READ_WRITE
     * @param callbacks Pointer to callbacks class.  This may be used by the folder between this
     * time and when close() is called.  This is only used for remote stores - should be null
     * for LocalStore.LocalFolder.
     */
    public abstract void open(OpenMode mode, PersistentDataCallbacks callbacks)
            throws MessagingException;

    /**
     * Forces a close of the MailProvider. Any further access will attempt to
     * reopen the MailProvider.
     *
     * @param expunge If true all deleted messages will be expunged.
     */
    public abstract void close(boolean expunge) throws MessagingException;

    /**
     * @return True if further commands are not expected to have to open the
     *         connection.
     */
    // TODO not used, get rid of this - it's a transport function
    public abstract boolean isOpen();

    /**
     * Get the mode the folder was opened with. This may be different than the mode the open
     * was requested with.
     * @return
     */
    public abstract OpenMode getMode() throws MessagingException;

    /**
     * Reports if the Store is able to create folders of the given type.
     * Does not actually attempt to create a folder.
     * @param type
     * @return true if can create, false if cannot create
     */
    public abstract boolean canCreate(FolderType type);

    /**
     * Attempt to create the given folder remotely using the given type.
     * @param type
     * @return true if created, false if cannot create (e.g. server side)
     */
    public abstract boolean create(FolderType type) throws MessagingException;

    public abstract boolean exists() throws MessagingException;

    /**
     * @return A count of the messages in the selected folder.
     */
    public abstract int getMessageCount() throws MessagingException;

    public abstract int getUnreadMessageCount() throws MessagingException;

    public abstract Message getMessage(String uid) throws MessagingException;

    public abstract Message[] getMessages(int start, int end, MessageRetrievalListener listener)
            throws MessagingException;

    /**
     * Fetches the given list of messages. The specified listener is notified as
     * each fetch completes. Messages are downloaded as (as) lightweight (as
     * possible) objects to be filled in with later requests. In most cases this
     * means that only the UID is downloaded.
     *
     * @param uids
     * @param listener
     */
    public abstract Message[] getMessages(MessageRetrievalListener listener)
            throws MessagingException;

    public abstract Message[] getMessages(String[] uids, MessageRetrievalListener listener)
            throws MessagingException;
    
    /**
     * Return a set of messages based on the state of the flags.
     * Note: Not typically implemented in remote stores, so not abstract.
     * 
     * @param setFlags The flags that should be set for a message to be selected (can be null)
     * @param clearFlags The flags that should be clear for a message to be selected (can be null)
     * @param listener
     * @return A list of messages matching the desired flag states.
     * @throws MessagingException
     */
    public Message[] getMessages(Flag[] setFlags, Flag[] clearFlags, 
            MessageRetrievalListener listener) throws MessagingException {
        throw new MessagingException("Not implemented");
    }

    public abstract void appendMessages(Message[] messages) throws MessagingException;

    public abstract void copyMessages(Message[] msgs, Folder folder,
            MessageUpdateCallbacks callbacks) throws MessagingException;

    public abstract void setFlags(Message[] messages, Flag[] flags, boolean value)
            throws MessagingException;

    public abstract Message[] expunge() throws MessagingException;

    public abstract void fetch(Message[] messages, FetchProfile fp,
            MessageRetrievalListener listener) throws MessagingException;

    public abstract void delete(boolean recurse) throws MessagingException;

    public abstract String getName();

    public abstract Flag[] getPermanentFlags() throws MessagingException;

    /**
     * This method returns a string identifying the name of a "role" folder
     * (such as inbox, draft, sent, or trash).  Stores that do not implement this
     * feature can be used - the account UI will provide default strings.  To
     * let the server identify specific folder roles, simply override this method.
     * 
     * @return The server- or protocol- specific role for this folder.  If some roles are known
     * but this is not one of them, return FolderRole.OTHER.  If roles are unsupported here,
     * return FolderRole.UNKNOWN.  
     */
    public FolderRole getRole() {
        return FolderRole.UNKNOWN;
    }
    
    /**
     * This function will be called after the messaging controller has called 
     * getPersonalNamespaces() and has created a matching LocalFolder object.  This can
     * be used as a trigger for the folder to write back any folder-specific persistent data using
     * callbacks.
     * 
     * This is not abstract because most folders do not require this functionality and do not
     * need to implement it.
     */
    @SuppressWarnings("unused")
    public void localFolderSetupComplete(Folder localFolder) throws MessagingException {
        // Do nothing - return immediately
    }

    /**
     * Create an empty message of the appropriate type for the Folder.
     */
    public abstract Message createMessage(String uid) throws MessagingException;

    /**
     * Callback interface by which a Folder can read and write persistent data.
     * TODO This needs to be made more generic & flexible
     */
    public interface PersistentDataCallbacks {
        
        /**
         * Provides keyed storage of strings.  Should be used for per-folder data.  Do not use for
         * per-message data.
         * @param key identifier for the data (e.g. "sync.key" or "folder.id")
         * @param value Data to persist.  All data must be encoded into a string,
         * so use base64 or some other encoding if necessary.
         */
        public void setPersistentString(String key, String value);

        /**
         * @param key identifier for the data of interest
         * @return the data saved by the Folder, or defaultValue if never set.
         */
        public String getPersistentString(String key, String defaultValue);
        
        /**
         * In a single transaction:  Set a key/value pair for the folder, and bulk set or clear
         * message flags.  Typically used at the beginning or conclusion of a bulk sync operation.
         * 
         * @param key if non-null, the transaction will set this folder persistent value
         * @param value the value that will be stored for the key
         * @param setFlags if non-null, flag(s) will be set for all messages in the folder
         * @param clearFlags if non-null, flag(s) will be cleared for all messages in the folder
         */
        public void setPersistentStringAndMessageFlags(String key, String value,
                Flag[] setFlags, Flag[] clearFlags) throws MessagingException;
    }

    /**
     * Callback interface by which a folder can report UID changes caused by certain operations.
     */
    public interface MessageUpdateCallbacks {
        /**
         * The operation caused the message's UID to change
         * @param message The message for which the UID changed
         * @param newUid The new UID for the message
         */
        public void onMessageUidChange(Message message, String newUid) throws MessagingException;

        /**
         * The operation could not be completed because the message doesn't exist
         * (for example, it was already deleted from the server side.)
         * @param message The message that does not exist
         * @throws MessagingException
         */
        public void onMessageNotFound(Message message) throws MessagingException;
    }

    @Override
    public String toString() {
        return getName();
    }
}
