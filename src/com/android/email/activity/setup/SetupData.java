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

package com.android.email.activity.setup;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Policy;

import android.accounts.AccountAuthenticatorResponse;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

public class SetupData implements Parcelable {
    // The "extra" name for the Bundle saved with SetupData
    public static final String EXTRA_SETUP_DATA = "com.android.email.setupdata";

    // NORMAL is the standard entry from the Email app; EAS and POP_IMAP are used when entering via
    // Settings -> Accounts
    public static final int FLOW_MODE_NORMAL = 0;
    public static final int FLOW_MODE_ACCOUNT_MANAGER_EAS = 1;
    public static final int FLOW_MODE_ACCOUNT_MANAGER_POP_IMAP = 2;
    public static final int FLOW_MODE_EDIT = 3;
    public static final int FLOW_MODE_FORCE_CREATE = 4;
    // The following two modes are used to "pop the stack" and return from the setup flow.  We
    // either return to the caller (if we're in an account type flow) or go to the message list
    public static final int FLOW_MODE_RETURN_TO_CALLER = 5;
    public static final int FLOW_MODE_RETURN_TO_MESSAGE_LIST = 6;

    // For debug logging
    private static final String[] FLOW_MODES = {"normal", "eas", "pop/imap", "edit", "force",
            "rtc", "rtl"};

    // Mode bits for AccountSetupCheckSettings, indicating the type of check requested
    public static final int CHECK_INCOMING = 1;
    public static final int CHECK_OUTGOING = 2;
    public static final int CHECK_AUTODISCOVER = 4;

    // All access will be through getters/setters
    private int mFlowMode = FLOW_MODE_NORMAL;
    private Account mAccount;
    private String mUsername;
    private String mPassword;
    private int mCheckSettingsMode = 0;
    private boolean mAllowAutodiscover = true;
    private Policy mPolicy;
    private boolean mAutoSetup = false;
    private boolean mDefault = false;
    private AccountAuthenticatorResponse mAccountAuthenticatorResponse = null;

    // We only have one instance of SetupData; if/when the process is destroyed, this data will be
    // saved in the savedInstanceState Bundle
    private static SetupData INSTANCE = null;

    public static synchronized SetupData getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new SetupData();
        }
        return INSTANCE;
    }

    // Don't allow instantiation outside of this class
    private SetupData() {
    }

    static public int getFlowMode() {
        return getInstance().mFlowMode;
    }

    static public void setFlowMode(int mFlowMode) {
        getInstance().mFlowMode = mFlowMode;
    }

    static public Account getAccount() {
        return getInstance().mAccount;
    }

    static public void setAccount(Account mAccount) {
        getInstance().mAccount = mAccount;
    }

    static public String getUsername() {
        return getInstance().mUsername;
    }

    static public void setUsername(String mUsername) {
        getInstance().mUsername = mUsername;
    }

    static public String getPassword() {
        return getInstance().mPassword;
    }

    static public void setPassword(String mPassword) {
        getInstance().mPassword = mPassword;
    }

    static public void setCheckSettingsMode(int mCheckSettingsMode) {
        getInstance().mCheckSettingsMode = mCheckSettingsMode;
    }

    static public boolean isCheckIncoming() {
        return (getInstance().mCheckSettingsMode & CHECK_INCOMING) != 0;
    }

    static public boolean isCheckOutgoing() {
        return (getInstance().mCheckSettingsMode & CHECK_OUTGOING) != 0;
    }
    static public boolean isCheckAutodiscover() {
        return (getInstance().mCheckSettingsMode & CHECK_AUTODISCOVER) != 0;
    }
    static public boolean isAllowAutodiscover() {
        return getInstance().mAllowAutodiscover;
    }

    static public void setAllowAutodiscover(boolean mAllowAutodiscover) {
        getInstance().mAllowAutodiscover = mAllowAutodiscover;
    }

    static public Policy getPolicy() {
        return getInstance().mPolicy;
    }

    static public void setPolicy(Policy policy) {
        SetupData data = getInstance();
        data.mPolicy = policy;
        data.mAccount.mPolicy = policy;
    }

    static public boolean isAutoSetup() {
        return getInstance().mAutoSetup;
    }

    static public void setAutoSetup(boolean autoSetup) {
        getInstance().mAutoSetup = autoSetup;
    }

    static public boolean isDefault() {
        return getInstance().mDefault;
    }

    static public void setDefault(boolean _default) {
        getInstance().mDefault = _default;
    }

    static public AccountAuthenticatorResponse getAccountAuthenticatorResponse() {
        return getInstance().mAccountAuthenticatorResponse;
    }

    static public void setAccountAuthenticatorResponse(AccountAuthenticatorResponse response) {
        getInstance().mAccountAuthenticatorResponse = response;
    }

    public static void init(int flowMode) {
        SetupData data = getInstance();
        data.commonInit();
        data.mFlowMode = flowMode;
    }

    public static void init(int flowMode, Account account) {
        SetupData data = getInstance();
        data.commonInit();
        data.mFlowMode = flowMode;
        data.mAccount = account;
    }

    void commonInit() {
        mPolicy = null;
        mAutoSetup = false;
        mAllowAutodiscover = true;
        mCheckSettingsMode = 0;
        mAccount = new Account();
        mDefault = false;
        mUsername = null;
        mPassword = null;
        mAccountAuthenticatorResponse = null;
    }

    // Parcelable methods
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<SetupData> CREATOR =
            new Parcelable.Creator<SetupData>() {
        public SetupData createFromParcel(Parcel in) {
            return new SetupData(in);
        }

        public SetupData[] newArray(int size) {
            return new SetupData[size];
        }
    };

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mFlowMode);
        dest.writeParcelable(mAccount, 0);
        dest.writeString(mUsername);
        dest.writeString(mPassword);
        dest.writeInt(mCheckSettingsMode);
        dest.writeInt(mAllowAutodiscover ? 1 : 0);
        dest.writeParcelable(mPolicy, 0);
        dest.writeInt(mAutoSetup ? 1 : 0);
        dest.writeInt(mDefault ? 1 : 0);
        dest.writeParcelable(mAccountAuthenticatorResponse, 0);
    }

    public SetupData(Parcel in) {
        ClassLoader loader = getClass().getClassLoader();
        mFlowMode = in.readInt();
        mAccount = in.readParcelable(loader);
        mUsername = in.readString();
        mPassword = in.readString();
        mCheckSettingsMode = in.readInt();
        mAllowAutodiscover = in.readInt() == 1;
        mPolicy = in.readParcelable(loader);
        mAutoSetup = in.readInt() == 1;
        mDefault = in.readInt() == 1;
        mAccountAuthenticatorResponse = in.readParcelable(loader);
    }

    // Save/restore our SetupData (used in AccountSetupActivity)
    static public void save(Bundle bundle) {
        bundle.putParcelable(EXTRA_SETUP_DATA, getInstance());
    }

    static public synchronized SetupData restore(Bundle bundle) {
        if (bundle != null && bundle.containsKey(EXTRA_SETUP_DATA)) {
            INSTANCE = bundle.getParcelable(EXTRA_SETUP_DATA);
            return INSTANCE;
        } else {
            return getInstance();
        }
    }

    public static String debugString() {
        StringBuilder sb = new StringBuilder("SetupData");
        SetupData data = getInstance();
        sb.append(":flow=" + FLOW_MODES[data.mFlowMode]);
        sb.append(":acct=" + (data.mAccount == null ? "none" : data.mAccount.mId));
        if (data.mUsername != null) {
            sb.append(":user=" + data.mUsername);
        }
        if (data.mPassword != null) {
            sb.append(":pass=" + data.mPassword);
        }
        sb.append(":a/d=" + data.mAllowAutodiscover);
        sb.append(":auto=" + data.mAutoSetup);
        sb.append(":default=" + data.mDefault);
        sb.append(":check=");
        if (SetupData.isCheckIncoming()) sb.append("in+");
        if (SetupData.isCheckOutgoing()) sb.append("out+");
        if (SetupData.isCheckAutodiscover()) sb.append("a/d");
        sb.append(":policy=" + (data.mPolicy == null ? "none" : "exists"));
        return sb.toString();
    }
}
