/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.email.widget;

import com.android.email.R;
import com.android.email.provider.EmailContent.Message;

import android.content.Context;
import android.text.TextUtils;

/**
 * Types of views that we're prepared to show in the widget - all mail, unread mail, and starred
 * mail; we rotate between them.  Each ViewType is composed of a selection string and a title.
 */
/* package */ enum ViewType {
    ALL_INBOX(Message.INBOX_SELECTION, null, R.string.widget_all_mail),
    UNREAD(Message.UNREAD_SELECTION, null, R.string.widget_unread),
    STARRED(Message.ALL_FAVORITE_SELECTION, null, R.string.widget_starred),
    ACCOUNT(Message.PER_ACCOUNT_INBOX_SELECTION, new String[1], 0);

    /* package */ final String selection;
    /* package */ final String[] selectionArgs;
    private final int mTitleResource;

    // TODO Can this class be immutable??
    private String mTitle;

    ViewType(String _selection, String[] _selectionArgs, int _titleResource) {
        selection = _selection;
        selectionArgs = _selectionArgs;
        mTitleResource = _titleResource;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    public String getTitle(Context context) {
        if (TextUtils.isEmpty(mTitle) && mTitleResource != 0) {
            mTitle = context.getString(mTitleResource);
        }
        return mTitle;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ViewType:selection=\"");
        sb.append(selection);
        sb.append("\"");
        if (selectionArgs != null && selectionArgs.length > 0) {
            sb.append(",args=(");
            for (String arg : selectionArgs) {
                sb.append(arg);
                sb.append(", ");
            }
            sb.append(")");
        }

        return sb.toString();
    }
}