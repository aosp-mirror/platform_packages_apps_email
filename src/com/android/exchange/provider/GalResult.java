/* Copyright (C) 2010 The Android Open Source Project.
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

package com.android.exchange.provider;

import com.android.emailcommon.mail.PackedString;

import java.util.ArrayList;

/**
 * A container for GAL results from EAS
 * Each element of the galData array becomes an element of the list used by autocomplete
 */
public class GalResult {
    // Total number of matches in this result
    public int total;
    public ArrayList<GalData> galData = new ArrayList<GalData>();

    public GalResult() {
    }

    /**
     * Legacy method for email address autocomplete
     */
    public void addGalData(long id, String displayName, String emailAddress) {
        galData.add(new GalData(id, displayName, emailAddress));
    }

    public void addGalData(GalData data) {
        galData.add(data);
    }

    public static class GalData {
        // PackedString constants for GalData
        public static final String ID = "_id";
        public static final String DISPLAY_NAME = "displayName";
        public static final String EMAIL_ADDRESS = "emailAddress";
        public static final String WORK_PHONE = "workPhone";
        public static final String HOME_PHONE = "homePhone";
        public static final String MOBILE_PHONE = "mobilePhone";
        public static final String FIRST_NAME = "firstName";
        public static final String LAST_NAME = "lastName";
        public static final String COMPANY = "company";
        public static final String TITLE = "title";
        public static final String OFFICE = "office";
        public static final String ALIAS = "alias";
        // The Builder we use to construct the PackedString
        PackedString.Builder builder = new PackedString.Builder();

        // The following three fields are for legacy email autocomplete
        public long _id = 0;
        public String displayName;
        public String emailAddress;

        /**
         * Legacy constructor for email address autocomplete
         */
        private GalData(long id, String _displayName, String _emailAddress) {
            put(ID, Long.toString(id));
            _id = id;
            put(DISPLAY_NAME, _displayName);
            displayName = _displayName;
            put(EMAIL_ADDRESS, _emailAddress);
            emailAddress = _emailAddress;
        }

        public GalData() {
        }

        public String get(String field) {
            return builder.get(field);
        }

        public void put(String field, String value) {
            builder.put(field, value);
        }

        public String toPackedString() {
            return builder.toString();
        }
    }
}
