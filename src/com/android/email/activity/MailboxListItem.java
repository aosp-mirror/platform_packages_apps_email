/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.email.activity;

import com.android.email.R;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.internal.util.ArrayUtils;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.RelativeLayout;

public class MailboxListItem extends RelativeLayout {
    // Colors used for drop targets
    private static Integer sDropAvailableColor;
    private static Integer sDropUnavailableColor;

    public long mMailboxId;
    public Integer mMailboxType;
    public MailboxesAdapter mAdapter;

    private Drawable mBackground;

    public MailboxListItem(Context context) {
        super(context);
    }

    public MailboxListItem(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MailboxListItem(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBackground = getBackground();
        if (sDropAvailableColor == null) {
            Resources res = getResources();
            sDropAvailableColor = res.getColor(R.color.mailbox_drop_available_color);
            sDropUnavailableColor = res.getColor(R.color.mailbox_drop_unavailable_color);
        }
    }

    public boolean isDropTarget(long itemMailbox) {
        if ((mMailboxId < 0) || (itemMailbox == mMailboxId)) {
            return false;
        }
        return !ArrayUtils.contains(Mailbox.INVALID_DROP_TARGETS, mMailboxType);
    }

    public boolean setDropTargetBackground(boolean dragInProgress, long itemMailbox) {
        if (dragInProgress) {
            if (isDropTarget(itemMailbox)) {
                setBackgroundColor(sDropAvailableColor);
                return true;
            } else {
                setBackgroundColor(sDropUnavailableColor);
                return false;
            }
        } else {
            setBackgroundDrawable(mBackground);
            return false;
        }
    }
}
