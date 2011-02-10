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

package com.android.emailcommon.mail;

/**
 * Flags that can be applied to Messages.
 */
public enum Flag {
    
    // If adding new flags: ALL FLAGS MUST BE UPPER CASE.

    DELETED,
    SEEN,
    ANSWERED,
    FLAGGED,
    DRAFT,
    RECENT,

    /*
     * The following flags are for internal library use only.
     * TODO Eventually we should creates a Flags class that extends ArrayList that allows
     * these flags and Strings to represent user defined flags. At that point the below
     * flags should become user defined flags.
     */
    /**
     * Delete and remove from the LocalStore immediately.
     */
    X_DESTROYED,

    /**
     * Sending of an unsent message failed. It will be retried. Used to show status.
     */
    X_SEND_FAILED,

    /**
     * Sending of an unsent message is in progress.
     */
    X_SEND_IN_PROGRESS,

    /**
     * Indicates that a message is fully downloaded from the server and can be viewed normally.
     * This does not include attachments, which are never downloaded fully.
     */
    X_DOWNLOADED_FULL,

    /**
     * Indicates that a message is partially downloaded from the server and can be viewed but
     * more content is available on the server.
     * This does not include attachments, which are never downloaded fully.
     */
    X_DOWNLOADED_PARTIAL,
    
    /**
     * General purpose flag that can be used by any remote store.  The flag will be 
     * saved and restored by the LocalStore.
     */
    X_STORE_1,
    
    /**
     * General purpose flag that can be used by any remote store.  The flag will be 
     * saved and restored by the LocalStore.
     */
    X_STORE_2,
    
}
