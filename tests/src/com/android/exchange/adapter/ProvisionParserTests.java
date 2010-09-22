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

package com.android.exchange.adapter;

import com.android.email.SecurityPolicy.PolicySet;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.adapter.ProvisionParserTests email
 */
public class ProvisionParserTests extends SyncAdapterTestCase {
    private final ByteArrayInputStream mTestInputStream =
        new ByteArrayInputStream("ABCDEFG".getBytes());

    // A good sample of an Exchange 2003 (WAP) provisioning document for end-to-end testing
    private String mWapProvisioningDoc1 =
        "<wap-provisioningdoc>" +
            "<characteristic type=\"SecurityPolicy\"><parm name=\"4131\" value=\"0\"/>" +
            "</characteristic>" +
            "<characteristic type=\"Registry\">" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\\AE\\" +
                        "{50C13377-C66D-400C-889E-C316FC4AB374}\">" +
                    "<parm name=\"AEFrequencyType\" value=\"1\"/>" +
                    "<parm name=\"AEFrequencyValue\" value=\"5\"/>" +
                "</characteristic>" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\">" +
                    "<parm name=\"DeviceWipeThreshold\" value=\"20\"/>" +
                "</characteristic>" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\">" +
                    "<parm name=\"CodewordFrequency\" value=\"5\"/>" +
                "</characteristic>" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\\LAP\\lap_pw\">" +
                    "<parm name=\"MinimumPasswordLength\" value=\"8\"/>" +
                "</characteristic>" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\\LAP\\lap_pw\">" +
                    "<parm name=\"PasswordComplexity\" value=\"0\"/>" +
                "</characteristic>" +
            "</characteristic>" +
        "</wap-provisioningdoc>";

    // Provisioning document with passwords turned off
    private String mWapProvisioningDoc2 =
        "<wap-provisioningdoc>" +
            "<characteristic type=\"SecurityPolicy\"><parm name=\"4131\" value=\"1\"/>" +
            "</characteristic>" +
            "<characteristic type=\"Registry\">" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\\AE\\" +
                        "{50C13377-C66D-400C-889E-C316FC4AB374}\">" +
                    "<parm name=\"AEFrequencyType\" value=\"0\"/>" +
                    "<parm name=\"AEFrequencyValue\" value=\"5\"/>" +
                "</characteristic>" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\">" +
                    "<parm name=\"DeviceWipeThreshold\" value=\"20\"/>" +
                "</characteristic>" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\">" +
                    "<parm name=\"CodewordFrequency\" value=\"5\"/>" +
                "</characteristic>" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\\LAP\\lap_pw\">" +
                    "<parm name=\"MinimumPasswordLength\" value=\"8\"/>" +
                "</characteristic>" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\\LAP\\lap_pw\">" +
                    "<parm name=\"PasswordComplexity\" value=\"0\"/>" +
                "</characteristic>" +
            "</characteristic>" +
        "</wap-provisioningdoc>";

    // Provisioning document with simple password, 4 chars, 5 failures
    private String mWapProvisioningDoc3 =
        "<wap-provisioningdoc>" +
            "<characteristic type=\"SecurityPolicy\"><parm name=\"4131\" value=\"0\"/>" +
            "</characteristic>" +
            "<characteristic type=\"Registry\">" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\\AE\\" +
                        "{50C13377-C66D-400C-889E-C316FC4AB374}\">" +
                    "<parm name=\"AEFrequencyType\" value=\"1\"/>" +
                    "<parm name=\"AEFrequencyValue\" value=\"2\"/>" +
                "</characteristic>" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\">" +
                    "<parm name=\"DeviceWipeThreshold\" value=\"5\"/>" +
                "</characteristic>" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\">" +
                    "<parm name=\"CodewordFrequency\" value=\"5\"/>" +
                "</characteristic>" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\\LAP\\lap_pw\">" +
                    "<parm name=\"MinimumPasswordLength\" value=\"4\"/>" +
                "</characteristic>" +
                "<characteristic type=\"HKLM\\Comm\\Security\\Policy\\LASSD\\LAP\\lap_pw\">" +
                    "<parm name=\"PasswordComplexity\" value=\"1\"/>" +
                "</characteristic>" +
            "</characteristic>" +
        "</wap-provisioningdoc>";

    public void testWapProvisionParser1() throws IOException {
         ProvisionParser parser = new ProvisionParser(mTestInputStream, getTestService());
        parser.parseProvisionDocXml(mWapProvisioningDoc1);
        PolicySet ps = parser.getPolicySet();
        assertNotNull(ps);
        // Check the settings to make sure they were parsed correctly
        assertEquals(5*60, ps.getMaxScreenLockTimeForTest());  // Screen lock time is in seconds
        assertEquals(8, ps.getMinPasswordLengthForTest());
        assertEquals(PolicySet.PASSWORD_MODE_STRONG, ps.getPasswordModeForTest());
        assertEquals(20, ps.getMaxPasswordFailsForTest());
        assertTrue(ps.isRequireRemoteWipeForTest());
    }

    public void testWapProvisionParser2() throws IOException {
        ProvisionParser parser = new ProvisionParser(mTestInputStream, getTestService());
        parser.parseProvisionDocXml(mWapProvisioningDoc2);
        PolicySet ps = parser.getPolicySet();
        assertNotNull(ps);
        // Password should be set to none; others are ignored in this case.
        assertEquals(PolicySet.PASSWORD_MODE_NONE, ps.getPasswordModeForTest());
    }

    public void testWapProvisionParser3() throws IOException {
        ProvisionParser parser = new ProvisionParser(mTestInputStream, getTestService());
        parser.parseProvisionDocXml(mWapProvisioningDoc3);
        PolicySet ps = parser.getPolicySet();
        assertNotNull(ps);
        // Password should be set to simple
        assertEquals(2*60, ps.getMaxScreenLockTimeForTest());  // Screen lock time is in seconds
        assertEquals(4, ps.getMinPasswordLengthForTest());
        assertEquals(PolicySet.PASSWORD_MODE_SIMPLE, ps.getPasswordModeForTest());
        assertEquals(5, ps.getMaxPasswordFailsForTest());
        assertTrue(ps.isRequireRemoteWipeForTest());
    }
}
