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

import com.android.email.provider.EmailContent.Mailbox;
import com.android.internal.util.ArrayUtils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.RelativeLayout;

public class MailboxListItem extends RelativeLayout {
    // STOPSHIP Need final color/ui
    // Color used for valid drop targets
    private static final int DROP_AVAILABLE = 0xFFFFFF33;
    private static final int DROP_UNAVAILABLE = 0xFFFFFFFF;

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
                setBackgroundColor(DROP_AVAILABLE);
                return true;
            } else {
                setBackgroundColor(DROP_UNAVAILABLE);
                return false;
            }
        } else {
            setBackgroundDrawable(mBackground);
            return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // TODO Can we know we're in mailbox list / message list two-pane mode?  If so, we should
        // check for this instead...
        // We don't want a touch very near the right edge to be used to visit a folder, as it might
        // easily have been an attempt to drag
        // STOPSHIP
        if (Welcome.useTwoPane(getContext()) && event.getAction() == MotionEvent.ACTION_DOWN) {
            if (event.getX() + 10 > this.getWidth()) {
                return true;
            }
        }
        return super.onTouchEvent(event);
    }
}
