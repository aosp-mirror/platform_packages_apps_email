/**
 * Copyright (c) 2012, Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.activity;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;

import com.android.emailcommon.mail.MeetingInfo;
import com.android.emailcommon.mail.PackedString;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.utility.Utility;

import java.text.ParseException;

public class EventViewer extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = getIntent().getData();
        long messageId = Long.parseLong(uri.getLastPathSegment());
        Message msg = Message.restoreMessageWithId(this, messageId);
        if (msg == null) {
            finish();
        } else {
            PackedString info = new PackedString(msg.mMeetingInfo);
            String uid = info.get(MeetingInfo.MEETING_UID);
            long eventId = -1;
            if (uid != null) {
                Cursor c = getContentResolver().query(CalendarContract.Events.CONTENT_URI,
                        new String[] {CalendarContract.Events._ID},
                        CalendarContract.Events.SYNC_DATA2 + "=?",
                        new String[] {uid}, null);
                if (c != null) {
                    try {
                        if (c.getCount() == 1) {
                            c.moveToFirst();
                            eventId = c.getLong(0);
                        }
                    } finally {
                        c.close();
                    }
                }
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (eventId != -1) {
                uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
            } else {
                long time;
                try {
                    time = Utility.parseEmailDateTimeToMillis(info.get(MeetingInfo.MEETING_DTSTART));
                } catch (ParseException e) {
                    finish();
                    return;
                }
                uri = Uri.parse("content://com.android.calendar/time/" + time);
                intent.putExtra("VIEW", "DAY");
            }
            intent.setData(uri);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
            startActivity(intent);
            finish();
        }
    }
}
