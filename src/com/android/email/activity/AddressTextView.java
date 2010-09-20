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

import android.widget.AutoCompleteTextView.Validator;
import android.widget.MultiAutoCompleteTextView;
import android.view.KeyEvent;
import android.content.Context;
import android.util.AttributeSet;
import android.graphics.drawable.Drawable;
import android.graphics.Rect;
import com.android.email.R;

/**
 * This is a MultiAutoCompleteTextView which sets the error state
 * (@see TextView.setError) when email address validation fails.
 */
class AddressTextView extends MultiAutoCompleteTextView {
    private class ForwardValidator implements Validator {
        private Validator mValidator = null;

        public CharSequence fixText(CharSequence invalidText) {
            mIsValid = false;
            return invalidText;
        }

        public boolean isValid(CharSequence text) {
            return mValidator != null ? mValidator.isValid(text) : true;
        }

        public void setValidator(Validator validator) {
            mValidator = validator;
        }
    }

    private boolean mIsValid = true;
    private final ForwardValidator mInternalValidator = new ForwardValidator();

    public AddressTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        super.setValidator(mInternalValidator);
    }

    @Override
    public void setValidator(Validator validator) {
        mInternalValidator.setValidator(validator);
    }

    @Override
    public void performValidation() {
        mIsValid = true;
        super.performValidation();
        markError(!mIsValid);
    }

    private void markError(boolean enable) {
        if (enable) {
            setError(getContext().getString(R.string.message_compose_error_invalid_email));
        } else {
            setError(null);
        }
    }
}
