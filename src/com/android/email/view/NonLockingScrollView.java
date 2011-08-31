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


package com.android.email.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * A {@link ScrollView} that will never lock scrolling in a particular direction.
 *
 * Usually ScrollView will capture all touch events once a drag has begun. In some cases,
 * we want to delegate those touches to children as normal, even in the middle of a drag. This is
 * useful when there are childviews like a WebView tha handles scrolling in the horizontal direction
 * even while the ScrollView drags vertically.
 *
 * This is only tested to work for ScrollViews where the content scrolls in one direction.
 */
public class NonLockingScrollView extends ScrollView {
    public NonLockingScrollView(Context context) {
        super(context);
    }
    public NonLockingScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public NonLockingScrollView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * Whether or not this view is in the middle of a drag.
     */
    private boolean mInDrag = false;

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        final boolean isUp = action == MotionEvent.ACTION_UP;

        if (isUp && mInDrag) {
            // An up event after a drag should be intercepted so that child views don't handle
            // click events falsely after a drag.
            mInDrag = false;
            onTouchEvent(ev);
            return true;
        }

        // Note the normal scrollview implementation is to intercept all touch events after it has
        // detected a drag starting. We will handle this ourselves.
        mInDrag = super.onInterceptTouchEvent(ev);
        if (mInDrag) {
            onTouchEvent(ev);
        }

        // Don't intercept events - pass them on to children as normal.
        return false;
    }
}
