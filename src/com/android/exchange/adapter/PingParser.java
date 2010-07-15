/* Copyright (C) 2008-2009 Marc Blank
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
import com.android.exchange.IllegalHeartbeatException;
import com.android.exchange.StaleFolderListException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Parse the result of a Ping command.
 *
 * If there are folders with changes, add the serverId of those folders to the syncList array.
 * If the folder list needs to be reloaded, throw a StaleFolderListException, which will be caught
 * by the sync server, which will sync the updated folder list.
 */
public class PingParser extends Parser {
    private ArrayList<String> syncList = new ArrayList<String>();
    private EasSyncService mService;
    private int mSyncStatus = 0;

    public ArrayList<String> getSyncList() {
        return syncList;
    }

    public int getSyncStatus() {
        return mSyncStatus;
    }

    public PingParser(InputStream in, EasSyncService service) throws IOException {
        super(in);
        mService = service;
    }

    public void parsePingFolders(ArrayList<String> syncList) throws IOException {
        while (nextTag(Tags.PING_FOLDERS) != END) {
            if (tag == Tags.PING_FOLDER) {
                // Here we'll keep track of which mailboxes need syncing
                String serverId = getValue();
                syncList.add(serverId);
                mService.userLog("Changes found in: ", serverId);
            } else {
                skipTag();
            }
        }
    }

    @Override
    public boolean parse() throws IOException, StaleFolderListException, IllegalHeartbeatException {
        boolean res = false;
        if (nextTag(START_DOCUMENT) != Tags.PING_PING) {
            throw new IOException();
        }
        while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
            if (tag == Tags.PING_STATUS) {
                int status = getValueInt();
                mSyncStatus = status;
                mService.userLog("Ping completed, status = ", status);
                if (status == 2) {
                    res = true;
                } else if (status == 7 || status == 4) {
                    // Status of 7 or 4 indicate a stale folder list
                    throw new StaleFolderListException();
                } else if (status == 5) {
                    // Status 5 means our heartbeat is beyond allowable limits
                    // In this case, there will be a heartbeat interval set
                }
            } else if (tag == Tags.PING_FOLDERS) {
                parsePingFolders(syncList);
            } else if (tag == Tags.PING_HEARTBEAT_INTERVAL) {
                // Throw an exception, saving away the legal heartbeat interval specified
                throw new IllegalHeartbeatException(getValueInt());
            } else {
                skipTag();
            }
        }
        return res;
    }
}

