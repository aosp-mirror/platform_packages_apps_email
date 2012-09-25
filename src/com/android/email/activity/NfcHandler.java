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

package com.android.email.activity;

import android.app.Activity;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.text.TextUtils;

import com.android.emailcommon.provider.Account;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
  * This class implements sharing the e-mail address of the
  * active account to another device using NFC. NFC sharing is only
  * enabled when the activity is in the foreground and resumed.
  * When an NFC link is established, {@link #createMessage}
  * will be called to create the data to be sent over the link,
  * which is a vCard in this case.
  */
public class NfcHandler implements NfcAdapter.CreateNdefMessageCallback {
    final UIControllerBase mUiController;
    final Activity mActivity;

    String mCurrentEmail;

    public static NfcHandler register(UIControllerBase controller, Activity activity) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
        if (adapter == null) {
            return null;  // NFC not available on this device
        }
        NfcHandler nfcHandler = new NfcHandler(controller, activity);
        adapter.setNdefPushMessageCallback(nfcHandler, activity);
        return nfcHandler;
    }

    public NfcHandler(UIControllerBase controller, Activity activity) {
        mUiController = controller;
        mActivity = activity;
    }

    public void onAccountChanged() {
        if (mUiController.isActualAccountSelected()) {
            final long accountId = mUiController.getActualAccountId();
            final Account account = Account.restoreAccountWithId(mActivity, accountId);
            if (account == null) return;
            mCurrentEmail = account.mEmailAddress;
        } else {
            mCurrentEmail = null;
        }
    }

    private static NdefMessage buildMailtoNdef(String address) {
        if (TextUtils.isEmpty(address)) {
            return null;
        }
        byte[] accountBytes;
        try {
            accountBytes = URLEncoder.encode(address, "UTF-8")
                    .getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
        byte[] recordBytes = new byte[accountBytes.length + 1];
        recordBytes[0] = 0x06; // NDEF mailto: prefix
        System.arraycopy(accountBytes, 0, recordBytes, 1, accountBytes.length);
        NdefRecord mailto = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_URI,
                new byte[0], recordBytes);
        return new NdefMessage(new NdefRecord[] { mailto });
    }

    @Override
    public NdefMessage createNdefMessage(NfcEvent event) {
        if (mCurrentEmail != null) {
            return buildMailtoNdef(mCurrentEmail);
        } else {
            return null;
        }
    }
}
