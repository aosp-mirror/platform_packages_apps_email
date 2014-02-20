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

package com.android.email;

import android.content.Context;
import android.os.Bundle;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.emailcommon.VendorPolicyLoader;
import com.android.emailcommon.VendorPolicyLoader.Provider;

@SmallTest
public class VendorPolicyLoaderTest extends AndroidTestCase {
    private String mTestApkPackageName;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestApkPackageName = getContext().getPackageName() + ".tests";
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        VendorPolicyLoader.clearInstanceForTest();
    }

    /**
     * Test for the case where the helper package doesn't exist.
     */
    public void testPackageNotExist() {
        VendorPolicyLoader pl = new VendorPolicyLoader(getContext(), "no.such.package",
                "no.such.Class", true);

        // getPolicy() shouldn't throw any exception.
        assertEquals(Bundle.EMPTY, pl.getPolicy(null, null));
    }

    public void testIsSystemPackage() {
        final Context c = getContext();
        assertEquals(false, VendorPolicyLoader.isSystemPackage(c, "no.such.package"));
        assertEquals(false, VendorPolicyLoader.isSystemPackage(c, mTestApkPackageName));
        assertEquals(true, VendorPolicyLoader.isSystemPackage(c, "com.android.settings"));
    }

    /**
     * Actually call {@link VendorPolicyLoader#getPolicy}, using MockVendorPolicy as a vendor
     * policy.
     */
    public void testGetPolicy() {
        MockVendorPolicy.inject(getContext());
        VendorPolicyLoader pl = VendorPolicyLoader.getInstance(getContext());

        // Prepare result
        Bundle result = new Bundle();
        result.putInt("ret", 1);
        MockVendorPolicy.mockResult = result;

        // Arg to pass
        Bundle args = new Bundle();
        args.putString("arg1", "a");

        // Call!
        Bundle actualResult = pl.getPolicy("policy1", args);

        // Check passed args
        assertEquals("policy", "policy1", MockVendorPolicy.passedPolicy);
        assertEquals("arg", "a", MockVendorPolicy.passedBundle.getString("arg1"));

        // Check return value
        assertEquals("result", 1, actualResult.getInt("ret"));
    }

    /**
     * Same as {@link #testGetPolicy}, but with the system-apk check.  It's a test for the case
     * where we have a non-system vendor policy installed, which shouldn't be used.
     */
    public void testGetPolicyNonSystem() {
        VendorPolicyLoader pl = new VendorPolicyLoader(getContext(), mTestApkPackageName,
                MockVendorPolicy.class.getName(), false);

        MockVendorPolicy.passedPolicy = null;

        // getPolicy() shouldn't throw any exception.
        assertEquals(Bundle.EMPTY, pl.getPolicy("policy1", null));

        // MockVendorPolicy.getPolicy() shouldn't get called.
        assertNull(MockVendorPolicy.passedPolicy);
    }

    /**
     * Test that any vendor policy that happens to be installed returns legal values
     * for getImapIdValues() per its API.
     *
     * Note, in most cases very little will happen in this test, because there is
     * no vendor policy package.  Most of this test exists to test a vendor policy
     * package itself, to make sure that its API returns reasonable values.
     */
    public void testGetImapIdValues() {
        VendorPolicyLoader pl = VendorPolicyLoader.getInstance(getContext());
        String id = pl.getImapIdValues("user-name", "server.yahoo.com",
                "IMAP4rev1 STARTTLS AUTH=GSSAPI");
        // null is a reasonable result
        if (id == null) return;

        // if non-null, basic sanity checks on format
        assertEquals("\"", id.charAt(0));
        assertEquals("\"", id.charAt(id.length()-1));
        // see if we can break it up properly
        String[] elements = id.split("\"");
        assertEquals(0, elements.length % 4);
        for (int i = 0; i < elements.length; ) {
            // Because we split at quotes, we expect to find:
            // [i] = null or one or more spaces
            // [i+1] = key
            // [i+2] = one or more spaces
            // [i+3] = value
            // Here are some incomplete checks of the above
            assertTrue(elements[i] == null || elements[i].startsWith(" "));
            assertTrue(elements[i+1].charAt(0) != ' ');
            assertTrue(elements[i+2].startsWith(" "));
            assertTrue(elements[i+3].charAt(0) != ' ');
            i += 4;
        }
    }

    /**
     * Test that findProviderForDomain() returns legal values, or functions properly when
     * none is installed.
     */
    public void testFindProviderForDomain() {
        VendorPolicyLoader pl = VendorPolicyLoader.getInstance(getContext());
        Provider p = pl.findProviderForDomain("yahoo.com");
        // null is a reasonable result (none installed)
        if (p == null) return;

        // if non-null, basic sanity checks on format
        assertNull(p.id);
        assertNull(p.label);
        assertEquals("yahoo.com", p.domain);
        assertNotNull(p.incomingUriTemplate);
        assertNotNull(p.incomingUsernameTemplate);
        assertNotNull(p.outgoingUriTemplate);
        assertNotNull(p.outgoingUsernameTemplate);
        assertTrue(p.note == null || p.note.length() > 0);  // no empty string
    }
}
