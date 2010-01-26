/*
 * Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

package com.android.exchange;

import com.android.email.provider.EmailContent.Attachment;

/**
 * PartRequest is the EAS wrapper for attachment loading requests.  In addition to information about
 * the attachment to be loaded, it also contains the callback to be used for status/progress
 * updates to the UI.
 */
public class PartRequest extends Request {
    public Attachment mAttachment;
    public String mDestination;
    public String mContentUriString;
    public String mLocation;

    public PartRequest(Attachment _att) {
        mMessageId = _att.mMessageKey;
        mAttachment = _att;
        mLocation = mAttachment.mLocation;
    }

    public PartRequest(Attachment _att, String _destination, String _contentUriString) {
        this(_att);
        mDestination = _destination;
        mContentUriString = _contentUriString;
    }
}
