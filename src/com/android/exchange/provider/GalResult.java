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

    public void addGalData(long id, String displayName, String emailAddress) {
        galData.add(new GalData(id, displayName, emailAddress));
    }

    public static class GalData {
        final long _id;
        final String displayName;
        final String emailAddress;

        private GalData(long id, String _displayName, String _emailAddress) {
            _id = id;
            displayName = _displayName;
            emailAddress = _emailAddress;
        }
    }
}
