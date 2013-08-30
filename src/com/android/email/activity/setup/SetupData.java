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

import android.accounts.AccountAuthenticatorResponse;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Policy;

public class SetupData implements Parcelable {
    // The "extra" name for the Bundle saved with SetupData
    public static final String EXTRA_SETUP_DATA = "com.android.email.setupdata";

    // NORMAL is the standard entry from the Email app; EAS and POP_IMAP are used when entering via
    // Settings -> Accounts
    public static final int FLOW_MODE_UNSPECIFIED = -1;
    public static final int FLOW_MODE_NORMAL = 0;
    public static final int FLOW_MODE_ACCOUNT_MANAGER = 1;
    public static final int FLOW_MODE_EDIT = 3;
    public static final int FLOW_MODE_FORCE_CREATE = 4;
    // The following two modes are used to "pop the stack" and return from the setup flow.  We
    // either return to the caller (if we're in an account type flow) or go to the message list
    public static final int FLOW_MODE_RETURN_TO_CALLER = 5;
    public static final int FLOW_MODE_RETURN_TO_MESSAGE_LIST = 6;
    public static final int FLOW_MODE_RETURN_NO_ACCOUNTS_RESULT = 7;
    public static final int FLOW_MODE_NO_ACCOUNTS = 8;

    // Mode bits for AccountSetupCheckSettings, indicating the type of check requested
    public static final int CHECK_INCOMING = 1;
    public static final int CHECK_OUTGOING = 2;
    public static final int CHECK_AUTODISCOVER = 4;

    // All access will be through getters/setters
    private int mFlowMode = FLOW_MODE_NORMAL;
    private String mFlowAccountType;
    private Account mAccount;
    private String mUsername;
    private String mPassword;
    private int mCheckSettingsMode = 0;
    private boolean mAllowAutodiscover = true;
    private Policy mPolicy;
    private AccountAuthenticatorResponse mAccountAuthenticatorResponse = null;

    public interface SetupDataContainer {
        public SetupData getSetupData();
        public void setSetupData(SetupData setupData);
    }

    public SetupData() {
        mPolicy = null;
        mAllowAutodiscover = true;
        mCheckSettingsMode = 0;
        mAccount = new Account();
        mUsername = null;
        mPassword = null;
        mAccountAuthenticatorResponse = null;
    }

    public SetupData(int flowMode) {
        this();
        mFlowMode = flowMode;
    }

    public SetupData(int flowMode, String accountType) {
        this(flowMode);
        mFlowAccountType = accountType;
    }

    public SetupData(int flowMode, Account account) {
        this(flowMode);
        mAccount = account;
    }

    public int getFlowMode() {
        return mFlowMode;
    }

    public String getFlowAccountType() {
        return mFlowAccountType;
    }

    public void setFlowMode(int flowMode) {
        mFlowMode = flowMode;
    }

    public Account getAccount() {
        return mAccount;
    }

    public void setAccount(Account account) {
        mAccount = account;
    }

    public String getUsername() {
        return mUsername;
    }

    public void setUsername(String username) {
        mUsername = username;
    }

    public String getPassword() {
        return mPassword;
    }

    public void setPassword(String password) {
        mPassword = password;
    }

    public void setCheckSettingsMode(int checkSettingsMode) {
        mCheckSettingsMode = checkSettingsMode;
    }

    public boolean isCheckIncoming() {
        return (mCheckSettingsMode & CHECK_INCOMING) != 0;
    }

    public boolean isCheckOutgoing() {
        return (mCheckSettingsMode & CHECK_OUTGOING) != 0;
    }
    public boolean isCheckAutodiscover() {
        return (mCheckSettingsMode & CHECK_AUTODISCOVER) != 0;
    }
    public boolean isAllowAutodiscover() {
        return mAllowAutodiscover;
    }

    public void setAllowAutodiscover(boolean mAllowAutodiscover) {
        mAllowAutodiscover = mAllowAutodiscover;
    }

    public Policy getPolicy() {
        return mPolicy;
    }

    public void setPolicy(Policy policy) {
        mPolicy = policy;
        mAccount.mPolicy = policy;
    }

    public AccountAuthenticatorResponse getAccountAuthenticatorResponse() {
        return mAccountAuthenticatorResponse;
    }

    public void setAccountAuthenticatorResponse(AccountAuthenticatorResponse response) {
        mAccountAuthenticatorResponse = response;
    }

    // Parcelable methods
    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<SetupData> CREATOR =
            new Parcelable.Creator<SetupData>() {
        @Override
        public SetupData createFromParcel(Parcel in) {
            return new SetupData(in);
        }

        @Override
        public SetupData[] newArray(int size) {
            return new SetupData[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mFlowMode);
        dest.writeParcelable(mAccount, 0);
        dest.writeString(mUsername);
        dest.writeString(mPassword);
        dest.writeInt(mCheckSettingsMode);
        dest.writeInt(mAllowAutodiscover ? 1 : 0);
        dest.writeParcelable(mPolicy, 0);
        dest.writeParcelable(mAccountAuthenticatorResponse, 0);
    }

    public SetupData(Parcel in) {
        final ClassLoader loader = getClass().getClassLoader();
        mFlowMode = in.readInt();
        mAccount = in.readParcelable(loader);
        mUsername = in.readString();
        mPassword = in.readString();
        mCheckSettingsMode = in.readInt();
        mAllowAutodiscover = in.readInt() == 1;
        mPolicy = in.readParcelable(loader);
        mAccountAuthenticatorResponse = in.readParcelable(loader);
    }

    public String debugString() {
        final StringBuilder sb = new StringBuilder("SetupData");
        sb.append(":acct=");
        sb.append(mAccount == null ? "none" :mAccount.mId);
        if (mUsername != null) {
            sb.append(":user=");
            sb.append(mUsername);
        }
        if (mPassword != null) {
            sb.append(":pass=");
            sb.append(mPassword);
        }
        sb.append(":a/d=");
        sb.append(mAllowAutodiscover);
        sb.append(":check=");
        if (isCheckIncoming()) sb.append("in+");
        if (isCheckOutgoing()) sb.append("out+");
        if (isCheckAutodiscover()) sb.append("a/d");
        sb.append(":policy=");
        sb.append(mPolicy == null ? "none" : "exists");
        return sb.toString();
    }
}
