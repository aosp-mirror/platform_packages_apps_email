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

package com.android.exchange.adapter;

import com.android.exchange.EasSyncService;

import java.io.IOException;
import java.io.InputStream;

/**
 * Parse the result of a MeetingRequest command.
 */
public class MeetingResponseParser extends Parser {
    private EasSyncService mService;

    public MeetingResponseParser(InputStream in, EasSyncService service) throws IOException {
        super(in);
        mService = service;
    }

    public void parseResult() throws IOException {
        while (nextTag(Tags.MREQ_RESULT) != END) {
            if (tag == Tags.MREQ_STATUS) {
                int status = getValueInt();
                if (status != 1) {
                    mService.userLog("Error in meeting response: " + status);
                }
            } else if (tag == Tags.MREQ_CAL_ID) {
                mService.userLog("Meeting response calendar id: " + getValue());
            } else {
                skipTag();
            }
        }
    }

    @Override
    public boolean parse() throws IOException {
        boolean res = false;
        if (nextTag(START_DOCUMENT) != Tags.MREQ_MEETING_RESPONSE) {
            throw new IOException();
        }
        while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
            if (tag == Tags.MREQ_RESULT) {
                parseResult();
            } else {
                skipTag();
            }
        }
        return res;
    }
}

