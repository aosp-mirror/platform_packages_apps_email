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
 * Class represents an IMAP response.
 */
public class ImapResponse extends ImapList {
    private final String mTag;
    private final boolean mIsContinuationRequest;

    /* package */ ImapResponse(String tag, boolean isContinuationRequest) {
        mTag = tag;
        mIsContinuationRequest = isContinuationRequest;
    }

    /* package */ static boolean isStatusResponse(String symbol) {
        return     ImapConstants.OK.equalsIgnoreCase(symbol)
                || ImapConstants.NO.equalsIgnoreCase(symbol)
                || ImapConstants.BAD.equalsIgnoreCase(symbol)
                || ImapConstants.PREAUTH.equalsIgnoreCase(symbol)
                || ImapConstants.BYE.equalsIgnoreCase(symbol);
    }

    /**
     * @return whether it's a tagged response.
     */
    public boolean isTagged() {
        return mTag != null;
    }

    /**
     * @return whether it's a continuation request.
     */
    public boolean isContinuationRequest() {
        return mIsContinuationRequest;
    }

    public boolean isStatusResponse() {
        return isStatusResponse(getStringOrEmpty(0).getString());
    }

    /**
     * @return whether it's an OK response.
     */
    public boolean isOk() {
        return is(0, ImapConstants.OK);
    }

    /**
     * @return whether it's an BAD response.
     */
    public boolean isBad() {
        return is(0, ImapConstants.BAD);
    }

    /**
     * @return whether it's an NO response.
     */
    public boolean isNo() {
        return is(0, ImapConstants.NO);
    }

    /**
     * @return whether it's an {@code responseType} data response.  (i.e. not tagged).
     * @param index where {@code responseType} should appear.  e.g. 1 for "FETCH"
     * @param responseType e.g. "FETCH"
     */
    public final boolean isDataResponse(int index, String responseType) {
        return !isTagged() && getStringOrEmpty(index).is(responseType);
    }

    /**
     * @return Response code (RFC 3501 7.1) if it's a status response.
     *
     * e.g. "ALERT" for "* OK [ALERT] System shutdown in 10 minutes"
     */
    public ImapString getResponseCodeOrEmpty() {
        if (!isStatusResponse()) {
            return ImapString.EMPTY; // Not a status response.
        }
        return getListOrEmpty(1).getStringOrEmpty(0);
    }

    /**
     * @return Alert message it it has ALERT response code.
     *
     * e.g. "System shutdown in 10 minutes" for "* OK [ALERT] System shutdown in 10 minutes"
     */
    public ImapString getAlertTextOrEmpty() {
        if (!getResponseCodeOrEmpty().is(ImapConstants.ALERT)) {
            return ImapString.EMPTY; // Not an ALERT
        }
        // The 3rd element contains all the rest of line.
        return getStringOrEmpty(2);
    }

    /**
     * @return Response text in a status response.
     */
    public ImapString getStatusResponseTextOrEmpty() {
        if (!isStatusResponse()) {
            return ImapString.EMPTY;
        }
        return getStringOrEmpty(getElementOrNone(1).isList() ? 2 : 1);
    }

    @Override
    public String toString() {
        String tag = mTag;
        if (isContinuationRequest()) {
            tag = "+";
        }
        return "#" + tag + "# " + super.toString();
    }

    @Override
    public boolean equalsForTest(ImapElement that) {
        if (!super.equalsForTest(that)) {
            return false;
        }
        final ImapResponse thatResponse = (ImapResponse) that;
        if (mTag == null) {
            if (thatResponse.mTag != null) {
                return false;
            }
        } else {
            if (!mTag.equals(thatResponse.mTag)) {
                return false;
            }
        }
        if (mIsContinuationRequest != thatResponse.mIsContinuationRequest) {
            return false;
        }
        return true;
    }
}
