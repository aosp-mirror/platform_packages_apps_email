/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.emailsync;

import com.android.emailsync.Request;

/**
 * MessageMoveRequest is the EAS wrapper for requesting a "move to folder"
 */
public class MessageMoveRequest extends Request {
    public final long mMailboxId;

    public MessageMoveRequest(long messageId, long mailboxId) {
        super(messageId);
        mMailboxId = mailboxId;
    }

    // MessageMoveRequests are unique by their message id (i.e. it's meaningless to have two
    // separate message moves queued at the same time)
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MessageMoveRequest)) return false;
        return ((MessageMoveRequest)o).mMessageId == mMessageId;
    }

    @Override
    public int hashCode() {
        return (int)mMessageId;
    }
}
