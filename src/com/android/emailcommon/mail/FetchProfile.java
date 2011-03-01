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

import java.util.ArrayList;

/**
 * <pre>
 * A FetchProfile is a list of items that should be downloaded in bulk for a set of messages.
 * FetchProfile can contain the following objects:
 *      FetchProfile.Item:      Described below.
 *      Message:                Indicates that the body of the entire message should be fetched.
 *                              Synonymous with FetchProfile.Item.BODY.
 *      Part:                   Indicates that the given Part should be fetched. The provider
 *                              is expected have previously created the given BodyPart and stored
 *                              any information it needs to download the content.
 * </pre>
 */
public class FetchProfile extends ArrayList<Fetchable> {
    /**
     * Default items available for pre-fetching. It should be expected that any
     * item fetched by using these items could potentially include all of the
     * previous items.
     */
    public enum Item implements Fetchable {
        /**
         * Download the flags of the message.
         */
        FLAGS,

        /**
         * Download the envelope of the message. This should include at minimum
         * the size and the following headers: date, subject, from, content-type, to, cc
         */
        ENVELOPE,

        /**
         * Download the structure of the message. This maps directly to IMAP's BODYSTRUCTURE
         * and may map to other providers.
         * The provider should, if possible, fill in a properly formatted MIME structure in
         * the message without actually downloading any message data. If the provider is not
         * capable of this operation it should specifically set the body of the message to null
         * so that upper levels can detect that a full body download is needed.
         */
        STRUCTURE,

        /**
         * A sane portion of the entire message, cut off at a provider determined limit.
         * This should generaly be around 50kB.
         */
        BODY_SANE,

        /**
         * The entire message.
         */
        BODY,
    }

    /**
     * @return the first {@link Part} in this collection, or null if it doesn't contain
     * {@link Part}.
     */
    public Part getFirstPart() {
        for (Fetchable o : this) {
            if (o instanceof Part) {
                return (Part) o;
            }
        }
        return null;
    }
}
