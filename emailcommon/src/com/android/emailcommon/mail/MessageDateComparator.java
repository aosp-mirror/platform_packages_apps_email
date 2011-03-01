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

import java.util.Comparator;

public class MessageDateComparator implements Comparator<Message> {
    public int compare(Message o1, Message o2) {
        try {
            if (o1.getSentDate() == null) {
                return 1;
            } else if (o2.getSentDate() == null) {
                return -1;
            } else
                return o2.getSentDate().compareTo(o1.getSentDate());
        } catch (Exception e) {
            return 0;
        }
    }
}
