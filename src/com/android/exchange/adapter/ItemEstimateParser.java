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

package com.android.exchange.adapter;

import com.android.exchange.EasSyncService;

import java.io.IOException;
import java.io.InputStream;

/**
 * Parse the result of a GetItemEstimate command
 **/
public class ItemEstimateParser extends Parser {
    private EasSyncService mService;

    public ItemEstimateParser(InputStream in, EasSyncService service) throws IOException {
        super(in);
        mService = service;
    }

    @Override
    public boolean parse() throws IOException {
        if (nextTag(START_DOCUMENT) != Tags.GIE_GET_ITEM_ESTIMATE) {
            throw new IOException();
        }
        while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
            if (tag == Tags.GIE_RESPONSE) {
                responseParser();
            } else if (tag == Tags.GIE_ESTIMATE) {
                mService.userLog("ItemEstimate: " + getValue());
            } else {
                skipTag();
            }
        }
        return false;
    }

    private void responseParser() throws IOException {
        while (nextTag(Tags.GIE_RESPONSE) != END) {
            if (tag == Tags.GIE_STATUS) {
                int status = getValueInt();
                if (status != 1) {
                    mService.errorLog("GetItemEstimate failed (1 is success): " + status);
                }
            } else if (tag == Tags.GIE_COLLECTION) {
                collectionParser();
            } else {
                skipTag();
            }
        }
    }

    private void collectionParser() throws IOException {
        while (nextTag(Tags.GIE_COLLECTION) != END) {
            if (tag == Tags.GIE_ESTIMATE) {
                mService.userLog("GetItemEstimate: " + getValue() + " items");
            } else {
                skipTag();
            }
        }
    }
}
