/* Copyright (C) 2010 The Android Open Source Project.
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

package com.android.exchange.adapter;

import com.android.email.SecurityPolicy;
import com.android.email.SecurityPolicy.PolicySet;
import com.android.exchange.EasSyncService;

import java.io.IOException;
import java.io.InputStream;

/**
 * Parse the result of the Provision command
 *
 * Assuming a successful parse, we store the PolicySet and the policy key
 */
public class ProvisionParser extends Parser {
    private EasSyncService mService;
    PolicySet mPolicySet = null;
    String mPolicyKey = null;
    boolean mRemoteWipe = false;

    public ProvisionParser(InputStream in, EasSyncService service) throws IOException {
        super(in);
        mService = service;
        setDebug(true);
    }

    public PolicySet getPolicySet() {
        return mPolicySet;
    }

    public String getPolicyKey() {
        return mPolicyKey;
    }

    public boolean getRemoteWipe() {
        return mRemoteWipe;
    }

    public void parseProvisionDoc() throws IOException {
        int minPasswordLength = 0;
        int passwordMode = PolicySet.PASSWORD_MODE_NONE;
        int maxPasswordFails = 0;
        int maxScreenLockTime = 0;
        boolean canSupport = true;

        while (nextTag(Tags.PROVISION_EAS_PROVISION_DOC) != END) {
            switch (tag) {
                case Tags.PROVISION_DEVICE_PASSWORD_ENABLED:
                    if (getValueInt() == 1) {
                        if (passwordMode == PolicySet.PASSWORD_MODE_NONE) {
                            passwordMode = PolicySet.PASSWORD_MODE_SIMPLE;
                        }
                    }
                    break;
                case Tags.PROVISION_MIN_DEVICE_PASSWORD_LENGTH:
                    minPasswordLength = getValueInt();
                    break;
                case Tags.PROVISION_ALPHA_DEVICE_PASSWORD_ENABLED:
                    if (getValueInt() == 1) {
                        passwordMode = PolicySet.PASSWORD_MODE_STRONG;
                    }
                    break;
                case Tags.PROVISION_MAX_INACTIVITY_TIME_DEVICE_LOCK:
                    // EAS gives us seconds, which is, happily, what the PolicySet requires
                    maxScreenLockTime = getValueInt();
                    break;
                case Tags.PROVISION_MAX_DEVICE_PASSWORD_FAILED_ATTEMPTS:
                    maxPasswordFails = getValueInt();
                    break;
                case Tags.PROVISION_ALLOW_SIMPLE_DEVICE_PASSWORD:
                    // Ignore this unless there's any MSFT documentation for what this means
                    // Hint: I haven't seen any that's more specific than "simple"
                    getValue();
                    break;
                // The following policy, if false, can't be supported at the moment
                case Tags.PROVISION_ATTACHMENTS_ENABLED:
                    if (getValueInt() == 0) {
                        canSupport = false;
                    }
                    break;
                // The following policies, if true, can't be supported at the moment
                case Tags.PROVISION_DEVICE_ENCRYPTION_ENABLED:
                case Tags.PROVISION_PASSWORD_RECOVERY_ENABLED:
                case Tags.PROVISION_DEVICE_PASSWORD_EXPIRATION:
                case Tags.PROVISION_DEVICE_PASSWORD_HISTORY:
                case Tags.PROVISION_MAX_ATTACHMENT_SIZE:
                    if (getValueInt() == 1) {
                        canSupport = false;
                    }
                    break;
                default:
                    skipTag();
            }
        }

        if (canSupport) {
            mPolicySet = new SecurityPolicy.PolicySet(minPasswordLength, passwordMode,
                    maxPasswordFails, maxScreenLockTime, true);
        }
    }

    public void parseProvisionData() throws IOException {
        while (nextTag(Tags.PROVISION_DATA) != END) {
            if (tag == Tags.PROVISION_EAS_PROVISION_DOC) {
                parseProvisionDoc();
            } else {
                skipTag();
            }
        }
    }

    public void parsePolicy() throws IOException {
        while (nextTag(Tags.PROVISION_POLICY) != END) {
            switch (tag) {
                case Tags.PROVISION_POLICY_TYPE:
                    mService.userLog("Policy type: ", getValue());
                    break;
                case Tags.PROVISION_POLICY_KEY:
                    mPolicyKey = getValue();
                    break;
                case Tags.PROVISION_STATUS:
                    mService.userLog("Policy status: ", getValue());
                    break;
                case Tags.PROVISION_DATA:
                    parseProvisionData();
                    break;
                default:
                    skipTag();
            }
        }
    }

    public void parsePolicies() throws IOException {
        while (nextTag(Tags.PROVISION_POLICIES) != END) {
            if (tag == Tags.PROVISION_POLICY) {
                parsePolicy();
            } else {
                skipTag();
            }
        }
    }

    @Override
    public boolean parse() throws IOException {
        boolean res = false;
        if (nextTag(START_DOCUMENT) != Tags.PROVISION_PROVISION) {
            throw new IOException();
        }
        while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
            switch (tag) {
                case Tags.PROVISION_STATUS:
                    int status = getValueInt();
                    mService.userLog("Provision status: ", status);
                    break;
                case Tags.PROVISION_POLICIES:
                    parsePolicies();
                    return (mPolicySet != null || mPolicyKey != null);
                case Tags.PROVISION_REMOTE_WIPE:
                    // Indicate remote wipe command received
                    mRemoteWipe = true;
                    break;
                default:
                    skipTag();
            }
        }
        return res;
    }
}

