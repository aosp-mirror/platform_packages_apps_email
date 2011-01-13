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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
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
    boolean mIsSupportable = true;

    public ProvisionParser(InputStream in, EasSyncService service) throws IOException {
        super(in);
        mService = service;
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

    public boolean hasSupportablePolicySet() {
        return (mPolicySet != null) && mIsSupportable;
    }

    private void parseProvisionDocWbxml() throws IOException {
        int minPasswordLength = 0;
        int passwordMode = PolicySet.PASSWORD_MODE_NONE;
        int maxPasswordFails = 0;
        int maxScreenLockTime = 0;
        int passwordExpirationDays = 0;
        int passwordHistory = 0;
        int passwordComplexChars = 0;

        while (nextTag(Tags.PROVISION_EAS_PROVISION_DOC) != END) {
            boolean tagIsSupported = true;
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
                case Tags.PROVISION_DEVICE_PASSWORD_EXPIRATION:
                    passwordExpirationDays = getValueInt();
                    break;
                case Tags.PROVISION_DEVICE_PASSWORD_HISTORY:
                    passwordHistory = getValueInt();
                    break;
                case Tags.PROVISION_ALLOW_SIMPLE_DEVICE_PASSWORD:
                    // Ignore this unless there's any MSFT documentation for what this means
                    // Hint: I haven't seen any that's more specific than "simple"
                    getValue();
                    break;
                // The following policies, if false, can't be supported at the moment
                case Tags.PROVISION_ATTACHMENTS_ENABLED:
                case Tags.PROVISION_ALLOW_STORAGE_CARD:
                case Tags.PROVISION_ALLOW_CAMERA:
                case Tags.PROVISION_ALLOW_UNSIGNED_APPLICATIONS:
                case Tags.PROVISION_ALLOW_UNSIGNED_INSTALLATION_PACKAGES:
                case Tags.PROVISION_ALLOW_WIFI:
                case Tags.PROVISION_ALLOW_TEXT_MESSAGING:
                case Tags.PROVISION_ALLOW_POP_IMAP_EMAIL:
                case Tags.PROVISION_ALLOW_IRDA:
                case Tags.PROVISION_ALLOW_HTML_EMAIL:
                case Tags.PROVISION_ALLOW_BROWSER:
                case Tags.PROVISION_ALLOW_CONSUMER_EMAIL:
                case Tags.PROVISION_ALLOW_INTERNET_SHARING:
                    if (getValueInt() == 0) {
                        tagIsSupported = false;
                    }
                    break;
                // Bluetooth: 0 = no bluetooth; 1 = only hands-free; 2 = allowed
                case Tags.PROVISION_ALLOW_BLUETOOTH:
                    if (getValueInt() != 2) {
                        tagIsSupported = false;
                    }
                    break;
                // The following policies, if true, can't be supported at the moment
                case Tags.PROVISION_DEVICE_ENCRYPTION_ENABLED:
                case Tags.PROVISION_PASSWORD_RECOVERY_ENABLED:
                case Tags.PROVISION_REQUIRE_DEVICE_ENCRYPTION:
                case Tags.PROVISION_REQUIRE_SIGNED_SMIME_MESSAGES:
                case Tags.PROVISION_REQUIRE_ENCRYPTED_SMIME_MESSAGES:
                case Tags.PROVISION_REQUIRE_SIGNED_SMIME_ALGORITHM:
                case Tags.PROVISION_REQUIRE_ENCRYPTION_SMIME_ALGORITHM:
                case Tags.PROVISION_REQUIRE_MANUAL_SYNC_WHEN_ROAMING:
                    if (getValueInt() == 1) {
                        tagIsSupported = false;
                    }
                    break;
                // The following, if greater than zero, can't be supported at the moment
                case Tags.PROVISION_MAX_ATTACHMENT_SIZE:
                    if (getValueInt() > 0) {
                        tagIsSupported = false;
                    }
                    break;
                // Complex characters are supported
                case Tags.PROVISION_MIN_DEVICE_PASSWORD_COMPLEX_CHARS:
                    passwordComplexChars = getValueInt();
                    break;
                // The following policies are moot; they allow functionality that we don't support
                case Tags.PROVISION_ALLOW_DESKTOP_SYNC:
                case Tags.PROVISION_ALLOW_SMIME_ENCRYPTION_NEGOTIATION:
                case Tags.PROVISION_ALLOW_SMIME_SOFT_CERTS:
                case Tags.PROVISION_ALLOW_REMOTE_DESKTOP:
                    skipTag();
                    break;
                // We don't handle approved/unapproved application lists
                case Tags.PROVISION_UNAPPROVED_IN_ROM_APPLICATION_LIST:
                case Tags.PROVISION_APPROVED_APPLICATION_LIST:
                    // Parse and throw away the content
                    if (specifiesApplications(tag)) {
                        tagIsSupported = false;
                    }
                    break;
                // NOTE: We can support these entirely within the email application if we choose
                case Tags.PROVISION_MAX_CALENDAR_AGE_FILTER:
                case Tags.PROVISION_MAX_EMAIL_AGE_FILTER:
                    // 0 indicates no specified filter
                    if (getValueInt() != 0) {
                        tagIsSupported = false;
                    }
                    break;
                // NOTE: We can support these entirely within the email application if we choose
                case Tags.PROVISION_MAX_EMAIL_BODY_TRUNCATION_SIZE:
                case Tags.PROVISION_MAX_EMAIL_HTML_BODY_TRUNCATION_SIZE:
                    String value = getValue();
                    // -1 indicates no required truncation
                    if (!value.equals("-1")) {
                        tagIsSupported = false;
                    }
                    break;
                default:
                    skipTag();
            }

            if (!tagIsSupported) {
                log("Policy not supported: " + tag);
                mIsSupportable = false;
            }
        }

        mPolicySet = new SecurityPolicy.PolicySet(minPasswordLength, passwordMode,
                maxPasswordFails, maxScreenLockTime, true, passwordExpirationDays, passwordHistory,
                passwordComplexChars, false);
    }

    /**
     * Return whether or not either of the application list tags specifies any applications
     * @param endTag the tag whose children we're walking through
     * @return whether any applications were specified (by name or by hash)
     * @throws IOException
     */
    private boolean specifiesApplications(int endTag) throws IOException {
        boolean specifiesApplications = false;
        while (nextTag(endTag) != END) {
            switch (tag) {
                case Tags.PROVISION_APPLICATION_NAME:
                case Tags.PROVISION_HASH:
                    specifiesApplications = true;
                    break;
                default:
                    skipTag();
            }
        }
        return specifiesApplications;
    }

    class ShadowPolicySet {
        int mMinPasswordLength = 0;
        int mPasswordMode = PolicySet.PASSWORD_MODE_NONE;
        int mMaxPasswordFails = 0;
        int mMaxScreenLockTime = 0;
        int mPasswordExpiration = 0;
        int mPasswordHistory = 0;
        int mPasswordComplexChars = 0;
    }

    /*package*/ void parseProvisionDocXml(String doc) throws IOException {
        ShadowPolicySet sps = new ShadowPolicySet();

        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new ByteArrayInputStream(doc.getBytes()), "UTF-8");
            int type = parser.getEventType();
            if (type == XmlPullParser.START_DOCUMENT) {
                type = parser.next();
                if (type == XmlPullParser.START_TAG) {
                    String tagName = parser.getName();
                    if (tagName.equals("wap-provisioningdoc")) {
                        parseWapProvisioningDoc(parser, sps);
                    }
                }
            }
        } catch (XmlPullParserException e) {
           throw new IOException();
        }

        mPolicySet = new PolicySet(sps.mMinPasswordLength, sps.mPasswordMode, sps.mMaxPasswordFails,
                sps.mMaxScreenLockTime, true, sps.mPasswordExpiration, sps.mPasswordHistory,
                sps.mPasswordComplexChars, false);
    }

    /**
     * Return true if password is required; otherwise false.
     */
    private boolean parseSecurityPolicy(XmlPullParser parser, ShadowPolicySet sps)
            throws XmlPullParserException, IOException {
        boolean passwordRequired = true;
        while (true) {
            int type = parser.nextTag();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("characteristic")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String tagName = parser.getName();
                if (tagName.equals("parm")) {
                    String name = parser.getAttributeValue(null, "name");
                    if (name.equals("4131")) {
                        String value = parser.getAttributeValue(null, "value");
                        if (value.equals("1")) {
                            passwordRequired = false;
                        }
                    }
                }
            }
        }
        return passwordRequired;
    }

    private void parseCharacteristic(XmlPullParser parser, ShadowPolicySet sps)
            throws XmlPullParserException, IOException {
        boolean enforceInactivityTimer = true;
        while (true) {
            int type = parser.nextTag();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("characteristic")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                if (parser.getName().equals("parm")) {
                    String name = parser.getAttributeValue(null, "name");
                    String value = parser.getAttributeValue(null, "value");
                    if (name.equals("AEFrequencyValue")) {
                        if (enforceInactivityTimer) {
                            if (value.equals("0")) {
                                sps.mMaxScreenLockTime = 1;
                            } else {
                                sps.mMaxScreenLockTime = 60*Integer.parseInt(value);
                            }
                        }
                    } else if (name.equals("AEFrequencyType")) {
                        // "0" here means we don't enforce an inactivity timeout
                        if (value.equals("0")) {
                            enforceInactivityTimer = false;
                        }
                    } else if (name.equals("DeviceWipeThreshold")) {
                        sps.mMaxPasswordFails = Integer.parseInt(value);
                    } else if (name.equals("CodewordFrequency")) {
                        // Ignore; has no meaning for us
                    } else if (name.equals("MinimumPasswordLength")) {
                        sps.mMinPasswordLength = Integer.parseInt(value);
                    } else if (name.equals("PasswordComplexity")) {
                        if (value.equals("0")) {
                            sps.mPasswordMode = PolicySet.PASSWORD_MODE_STRONG;
                        } else {
                            sps.mPasswordMode = PolicySet.PASSWORD_MODE_SIMPLE;
                        }
                    }
                }
            }
        }
    }

    private void parseRegistry(XmlPullParser parser, ShadowPolicySet sps)
            throws XmlPullParserException, IOException {
      while (true) {
          int type = parser.nextTag();
          if (type == XmlPullParser.END_TAG && parser.getName().equals("characteristic")) {
              break;
          } else if (type == XmlPullParser.START_TAG) {
              String name = parser.getName();
              if (name.equals("characteristic")) {
                  parseCharacteristic(parser, sps);
              }
          }
      }
    }

    private void parseWapProvisioningDoc(XmlPullParser parser, ShadowPolicySet sps)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.nextTag();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("wap-provisioningdoc")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals("characteristic")) {
                    String atype = parser.getAttributeValue(null, "type");
                    if (atype.equals("SecurityPolicy")) {
                        // If a password isn't required, stop here
                        if (!parseSecurityPolicy(parser, sps)) {
                            return;
                        }
                    } else if (atype.equals("Registry")) {
                        parseRegistry(parser, sps);
                        return;
                    }
                }
            }
        }
    }

    private void parseProvisionData() throws IOException {
        while (nextTag(Tags.PROVISION_DATA) != END) {
            if (tag == Tags.PROVISION_EAS_PROVISION_DOC) {
                parseProvisionDocWbxml();
            } else {
                skipTag();
            }
        }
    }

    private void parsePolicy() throws IOException {
        String policyType = null;
        while (nextTag(Tags.PROVISION_POLICY) != END) {
            switch (tag) {
                case Tags.PROVISION_POLICY_TYPE:
                    policyType = getValue();
                    mService.userLog("Policy type: ", policyType);
                    break;
                case Tags.PROVISION_POLICY_KEY:
                    mPolicyKey = getValue();
                    break;
                case Tags.PROVISION_STATUS:
                    mService.userLog("Policy status: ", getValue());
                    break;
                case Tags.PROVISION_DATA:
                    if (policyType.equalsIgnoreCase(EasSyncService.EAS_2_POLICY_TYPE)) {
                        // Parse the old style XML document
                        parseProvisionDocXml(getValue());
                    } else {
                        // Parse the newer WBXML data
                        parseProvisionData();
                    }
                    break;
                default:
                    skipTag();
            }
        }
    }

    private void parsePolicies() throws IOException {
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
                    res = (status == 1);
                    break;
                case Tags.PROVISION_POLICIES:
                    parsePolicies();
                    break;
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

