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

package com.android.emailcommon.utility;

import com.android.emailcommon.provider.EmailContent;

import android.net.Uri;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.File;

@SmallTest
public class AttachmentUtilitiesTests extends AndroidTestCase {
    /**
     * Test static inferMimeType()
     * From the method doc:
     *
     * <pre>
     *                   |---------------------------------------------------------|
     *                   |                  E X T E N S I O N                      |
     *                   |---------------------------------------------------------|
     *                   | .eml        | known(.png) | unknown(.abc) | none        |
     * | M |-----------------------------------------------------------------------|
     * | I | none        | msg/rfc822  | image/png   | app/abc       | app/oct-str |
     * | M |-------------| (always     |             |               |             |
     * | E | app/oct-str |  overrides  |             |               |             |
     * | T |-------------|             |             |-----------------------------|
     * | Y | text/plain  |             |             | text/plain                  |
     * | P |-------------|             |-------------------------------------------|
     * | E | any/type    |             | any/type                                  |
     * |---|-----------------------------------------------------------------------|
     * </pre>
     *
     * Also, all results should be in lowercase.
     */
    public void testInferMimeType() {
        final String DEFAULT_MIX = "Application/Octet-stream";
        final String DEFAULT_LOWER = DEFAULT_MIX.toLowerCase();
        final String TEXT_PLAIN = "text/plain";
        final String TYPE_IMG_PNG = "image/png";
        final String FILE_PNG = "myfile.false.pNg";
        final String FILE_ABC = "myfile.false.aBc";
        final String FILE_NO_EXT = "myfile";

        // .eml files always override mime type
        assertEquals("message/rfc822", AttachmentUtilities.inferMimeType("a.eml", null));
        assertEquals("message/rfc822", AttachmentUtilities.inferMimeType("a.eml", ""));
        assertEquals("message/rfc822",
                AttachmentUtilities.inferMimeType("a.eml", DEFAULT_LOWER));
        assertEquals("message/rfc822",
                AttachmentUtilities.inferMimeType("a.eMl", TEXT_PLAIN));
        assertEquals("message/rfc822",
                AttachmentUtilities.inferMimeType("a.eml", TYPE_IMG_PNG));

        // Non-generic, non-empty mime type; return it
        assertEquals("mime/type", AttachmentUtilities.inferMimeType(FILE_PNG, "Mime/TyPe"));
        assertEquals("mime/type", AttachmentUtilities.inferMimeType(FILE_ABC, "Mime/TyPe"));
        assertEquals("mime/type",
                AttachmentUtilities.inferMimeType(FILE_NO_EXT, "Mime/TyPe"));
        assertEquals("mime/type", AttachmentUtilities.inferMimeType(null, "Mime/TyPe"));
        assertEquals("mime/type", AttachmentUtilities.inferMimeType("", "Mime/TyPe"));

        // Recognizable file extension; return known type
        assertEquals("image/png", AttachmentUtilities.inferMimeType(FILE_PNG, null));
        assertEquals("image/png", AttachmentUtilities.inferMimeType(FILE_PNG, ""));
        assertEquals("image/png", AttachmentUtilities.inferMimeType(FILE_PNG, DEFAULT_MIX));
        assertEquals("image/png", AttachmentUtilities.inferMimeType(FILE_PNG, TEXT_PLAIN));

        // Unrecognized and non-empty file extension, non-"text/plain" type; generate mime type
        assertEquals("application/abc", AttachmentUtilities.inferMimeType(FILE_ABC, null));
        assertEquals("application/abc", AttachmentUtilities.inferMimeType(FILE_ABC, ""));
        assertEquals("application/abc",
                AttachmentUtilities.inferMimeType(FILE_ABC, DEFAULT_MIX));

        // Unrecognized and empty file extension, non-"text/plain" type; return "app/octet-stream"
        assertEquals(DEFAULT_LOWER, AttachmentUtilities.inferMimeType(FILE_NO_EXT, null));
        assertEquals(DEFAULT_LOWER, AttachmentUtilities.inferMimeType(FILE_NO_EXT, ""));
        assertEquals(DEFAULT_LOWER,
                AttachmentUtilities.inferMimeType(FILE_NO_EXT, DEFAULT_MIX));
        assertEquals(DEFAULT_LOWER, AttachmentUtilities.inferMimeType(null, null));
        assertEquals(DEFAULT_LOWER, AttachmentUtilities.inferMimeType("", ""));

        // Unrecognized or empty file extension, "text/plain" type; return "text/plain"
        assertEquals(TEXT_PLAIN, AttachmentUtilities.inferMimeType(FILE_ABC, TEXT_PLAIN));
        assertEquals(TEXT_PLAIN,
                AttachmentUtilities.inferMimeType(FILE_NO_EXT, TEXT_PLAIN));
        assertEquals(TEXT_PLAIN, AttachmentUtilities.inferMimeType(null, TEXT_PLAIN));
        assertEquals(TEXT_PLAIN, AttachmentUtilities.inferMimeType("", TEXT_PLAIN));
    }

    /**
     * Text extension extractor
     */
    public void testGetFilenameExtension() {
        final String FILE_NO_EXTENSION = "myfile";
        final String FILE_EXTENSION = "myfile.pDf";
        final String FILE_TWO_EXTENSIONS = "myfile.false.AbC";

        assertNull(AttachmentUtilities.getFilenameExtension(null));
        assertNull(AttachmentUtilities.getFilenameExtension(""));
        assertNull(AttachmentUtilities.getFilenameExtension(FILE_NO_EXTENSION));

        assertEquals("pdf", AttachmentUtilities.getFilenameExtension(FILE_EXTENSION));
        assertEquals("abc", AttachmentUtilities.getFilenameExtension(FILE_TWO_EXTENSIONS));

        // The API makes no claim as to how these are handled (it probably should),
        // but make sure that they don't crash.
        AttachmentUtilities.getFilenameExtension("filename.");
        AttachmentUtilities.getFilenameExtension(".extension");
    }
}
