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

package com.android.email.mail.store.imap;

import com.android.emailcommon.Logging;
import com.android.mail.utils.LogUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Class represents an IMAP "element" that is not a list.
 *
 * An atom, quoted string, literal, are all represented by this.  Values like OK, STATUS are too.
 * Also, this class class may contain more arbitrary value like "BODY[HEADER.FIELDS ("DATE")]".
 * See {@link ImapResponseParser}.
 */
public abstract class ImapString extends ImapElement {
    private static final byte[] EMPTY_BYTES = new byte[0];

    public static final ImapString EMPTY = new ImapString() {
        @Override public void destroy() {
            // Don't call super.destroy().
            // It's a shared object.  We don't want the mDestroyed to be set on this.
        }

        @Override public String getString() {
            return "";
        }

        @Override public InputStream getAsStream() {
            return new ByteArrayInputStream(EMPTY_BYTES);
        }

        @Override public String toString() {
            return "";
        }
    };

    // This is used only for parsing IMAP's FETCH ENVELOPE command, in which
    // en_US-like date format is used like "01-Jan-2009 11:20:39 -0800", so this should be
    // handled by Locale.US
    private final static SimpleDateFormat DATE_TIME_FORMAT =
            new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", Locale.US);

    private boolean mIsInteger;
    private int mParsedInteger;
    private Date mParsedDate;

    @Override
    public final boolean isList() {
        return false;
    }

    @Override
    public final boolean isString() {
        return true;
    }

    /**
     * @return true if and only if the length of the string is larger than 0.
     *
     * Note: IMAP NIL is considered an empty string. See {@link ImapResponseParser
     * #parseBareString}.
     * On the other hand, a quoted/literal string with value NIL (i.e. "NIL" and {3}\r\nNIL) is
     * treated literally.
     */
    public final boolean isEmpty() {
        return getString().length() == 0;
    }

    public abstract String getString();

    public abstract InputStream getAsStream();

    /**
     * @return whether it can be parsed as a number.
     */
    public final boolean isNumber() {
        if (mIsInteger) {
            return true;
        }
        try {
            mParsedInteger = Integer.parseInt(getString());
            mIsInteger = true;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * @return value parsed as a number.
     */
    public final int getNumberOrZero() {
        if (!isNumber()) {
            return 0;
        }
        return mParsedInteger;
    }

    /**
     * @return whether it can be parsed as a date using {@link #DATE_TIME_FORMAT}.
     */
    public final boolean isDate() {
        if (mParsedDate != null) {
            return true;
        }
        if (isEmpty()) {
            return false;
        }
        try {
            mParsedDate = DATE_TIME_FORMAT.parse(getString());
            return true;
        } catch (ParseException e) {
            LogUtils.w(Logging.LOG_TAG, getString() + " can't be parsed as a date.");
            return false;
        }
    }

    /**
     * @return value it can be parsed as a {@link Date}, or null otherwise.
     */
    public final Date getDateOrNull() {
        if (!isDate()) {
            return null;
        }
        return mParsedDate;
    }

    /**
     * @return whether the value case-insensitively equals to {@code s}.
     */
    public final boolean is(String s) {
        if (s == null) {
            return false;
        }
        return getString().equalsIgnoreCase(s);
    }


    /**
     * @return whether the value case-insensitively starts with {@code s}.
     */
    public final boolean startsWith(String prefix) {
        if (prefix == null) {
            return false;
        }
        final String me = this.getString();
        if (me.length() < prefix.length()) {
            return false;
        }
        return me.substring(0, prefix.length()).equalsIgnoreCase(prefix);
    }

    // To force subclasses to implement it.
    @Override
    public abstract String toString();

    @Override
    public final boolean equalsForTest(ImapElement that) {
        if (!super.equalsForTest(that)) {
            return false;
        }
        ImapString thatString = (ImapString) that;
        return getString().equals(thatString.getString());
    }
}
