/*
 * Copyright (C) 2009 The Android Open Source Project
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
import com.android.email.provider.EmailProvider;
import com.android.email.provider.EmailContent.Message;

import android.content.ClipData;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.RelativeLayout;

/**
 * This custom View is the list item for the MessageList activity, and serves two purposes:
 * 1.  It's a container to store message metadata (e.g. the ids of the message, mailbox, & account)
 * 2.  It handles internal clicks such as the checkbox or the favorite star
 */
public class MessageListItem extends RelativeLayout {
    private static final String TAG = "MessageListItem";
    // Note: messagesAdapter directly fiddles with these fields.
    /* package */ long mMessageId;
    /* package */ long mMailboxId;
    /* package */ long mAccountId;
    /* package */ boolean mRead;
    /* package */ boolean mFavorite;

    private MessagesAdapter mAdapter;

    private boolean mDownEvent;
    private boolean mCachedViewPositions;
    private int mDragRight = -1;
    private int mCheckRight;
    private int mStarLeft;

    // Padding to increase clickable areas on left & right of each list item
    private final static float CHECKMARK_PAD = 20.0F;
    private final static float STAR_PAD = 20.0F;

    public static final String MESSAGE_LIST_ITEMS_CLIP_LABEL =
        "com.android.email.MESSAGE_LIST_ITEMS";

    public MessageListItem(Context context) {
        super(context);
    }

    public MessageListItem(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MessageListItem(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * Called by the adapter at bindView() time
     *
     * @param adapter the adapter that creates this view
     */
    public void bindViewInit(MessagesAdapter adapter) {
        mAdapter = adapter;
        mCachedViewPositions = false;
    }

    // This is tentative drag & drop UI
    // STOPSHIP this entire class needs to be rewritten based on the actual UI design
    private static class ThumbnailBuilder extends DragThumbnailBuilder {
        private static Drawable sBackground;
        private static TextPaint sPaint;

        private View mView;
        private static final int mWidth = 250;
        private final Bitmap mDragHandle;
        private final float mDragHandleX;
        private final float mDragHandleY;
        private String mDragDesc;
        private float mDragDescX;
        private float mDragDescY;

        public ThumbnailBuilder(View view, int count) {
            super(view);
            Resources resources = view.getResources();
            mView = view;
            mDragHandle = BitmapFactory.decodeResource(resources, R.drawable.drag_handle);
            mDragHandleY = view.getHeight() - (mDragHandle.getHeight() / 2);
            View handleView = view.findViewById(R.id.handle);
            mDragHandleX = handleView.getLeft() + handleView.getPaddingLeft();
            mDragDesc = resources.getQuantityString(R.plurals.move_messages, count, count);
            mDragDescX = handleView.getRight() + 50;
            // Use height of this font??
            mDragDescY = view.getHeight() / 2;
            if (sBackground == null) {
                sBackground = resources.getDrawable(R.drawable.drag_background_holo);
                sBackground.setBounds(0, 0, mWidth, view.getHeight());
                sPaint = new TextPaint();
                sPaint.setTypeface(Typeface.DEFAULT_BOLD);
                sPaint.setTextSize(18);
            }
        }

        @Override
        public void onProvideThumbnailMetrics(Point thumbnailSize, Point thumbnailTouchPoint) {
            //float width = mView.getWidth();
            float height = mView.getHeight();
            thumbnailSize.set(mWidth, (int) height);
            thumbnailTouchPoint.set((int) mDragHandleX, (int) mDragHandleY / 2);
        }

        @Override
        public void onDrawThumbnail(Canvas canvas) {
            super.onDrawThumbnail(canvas);
            sBackground.draw(canvas);
            canvas.drawBitmap(mDragHandle, mDragHandleX, mDragHandleY, sPaint);
            canvas.drawText(mDragDesc, mDragDescX, mDragDescY, sPaint);
        }
    }

    /**
     * Overriding this method allows us to "catch" clicks in the checkbox or star
     * and process them accordingly.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = false;
        int touchX = (int) event.getX();

        if (!mCachedViewPositions) {
            final float paddingScale = getContext().getResources().getDisplayMetrics().density;
            final int checkPadding = (int) ((CHECKMARK_PAD * paddingScale) + 0.5);
            final int starPadding = (int) ((STAR_PAD * paddingScale) + 0.5);
            View dragHandle = findViewById(R.id.handle);
            if (dragHandle != null) {
                mDragRight = dragHandle.getRight();
            }
            mCheckRight = findViewById(R.id.selected).getRight() + checkPadding;
            mStarLeft = findViewById(R.id.favorite).getLeft() - starPadding;
            mCachedViewPositions = true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (touchX < mDragRight) {
                    Context context = getContext();
                    // Drag
                    ClipData data = ClipData.newUri(context.getContentResolver(),
                            MessageListItem.MESSAGE_LIST_ITEMS_CLIP_LABEL, null,
                            Message.CONTENT_URI.buildUpon()
                                .appendPath(Long.toString(mMessageId))
                                .appendQueryParameter(
                                        EmailProvider.MESSAGE_URI_PARAMETER_MAILBOX_ID,
                                        Long.toString(mMailboxId))
                                .build());
                    startDrag(data, new ThumbnailBuilder(this, 1), false);
                    handled = true;
                } else {
                    mDownEvent = true;
                    if ((touchX < mCheckRight) || (touchX > mStarLeft)) {
                        handled = true;
                    }
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                mDownEvent = false;
                break;

            case MotionEvent.ACTION_UP:
                if (mDownEvent) {
                    if (touchX < mCheckRight) {
                        mAdapter.toggleSelected(this);
                        handled = true;
                    } else if (touchX > mStarLeft) {
                        mFavorite = !mFavorite;
                        mAdapter.updateFavorite(this, mFavorite);
                        handled = true;
                    }
                }
                break;
        }

        if (handled) {
            postInvalidate();
        } else {
            handled = super.onTouchEvent(event);
        }

        return handled;
    }
}
