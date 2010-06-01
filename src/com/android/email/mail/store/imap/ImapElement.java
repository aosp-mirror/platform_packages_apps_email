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

/**
 * Class representing "element"s in IMAP responses.
 *
 * <p>Class hierarchy:
 * <pre>
 * ImapElement
 *   |
 *   |-- ImapElement.NONE (for 'index out of range')
 *   |
 *   |-- ImapList (isList() == true)
 *   |   |
 *   |   |-- ImapList.EMPTY
 *   |   |
 *   |   --- ImapResponse
 *   |
 *   --- ImapString (isString() == true)
 *       |
 *       |-- ImapString.EMPTY
 *       |
 *       |-- ImapSimpleString
 *       |
 *       |-- ImapMemoryLiteral
 *       |
 *       --- ImapTempFileLiteral
 * </pre>
 */
public abstract class ImapElement {
    /**
     * An element that is returned by {@link ImapList#getElementOrNone} to indicate an index
     * is out of range.
     */
    public static final ImapElement NONE = new ImapElement() {
        @Override public void destroy() {
            // Don't call super.destroy().
            // It's a shared object.  We don't want the mDestroyed to be set on this.
        }

        @Override public boolean isList() {
            return false;
        }

        @Override public boolean isString() {
            return false;
        }

        @Override public String toString() {
            return "[NO ELEMENT]";
        }

        @Override
        public boolean equalsForTest(ImapElement that) {
            return super.equalsForTest(that);
        }
    };

    private boolean mDestroyed = false;

    public abstract boolean isList();

    public abstract boolean isString();

    protected boolean isDestroyed() {
        return mDestroyed;
    }

    /**
     * Clean up the resources used by the instance.
     * It's for removing a temp file used by {@link ImapTempFileLiteral}.
     */
    public void destroy() {
        mDestroyed = true;
    }

    /**
     * Throws {@link RuntimeException} if it's already destroyed.
     */
    protected final void checkNotDestroyed() {
        if (mDestroyed) {
            throw new RuntimeException("Already destroyed");
        }
    }

    /**
     * Return a string that represents this object; it's purely for the debug purpose.  Don't
     * mistake it for {@link ImapString#getString}.
     *
     * Abstract to force subclasses to implement it.
     */
    @Override
    public abstract String toString();

    /**
     * The equals implementation that is intended to be used only for unit testing.
     * (Because it may be heavy and has a special sense of "equal" for testing.)
     */
    public boolean equalsForTest(ImapElement that) {
        if (that == null) {
            return false;
        }
        return this.getClass() == that.getClass(); // Has to be the same class.
    }
}
