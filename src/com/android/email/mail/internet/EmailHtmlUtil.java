/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.email.mail.internet;

import com.android.email.Account;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Multipart;
import com.android.email.mail.Part;
import com.android.email.mail.store.LocalStore.LocalAttachmentBodyPart;
import com.android.email.provider.AttachmentProvider;

import android.content.ContentResolver;
import android.net.Uri;

public class EmailHtmlUtil {

    /**
     * Resolve content-id reference in src attribute of img tag to AttachmentProvider's
     * content uri.  This method calls itself recursively at most the number of
     * LocalAttachmentPart that mime type is image and has content id.
     * The attribute src="cid:content_id" is resolved as src="content://...".
     * This method is package scope for testing purpose.
     *
     * @param text html email text
     * @param part mime part which may contain inline image
     * @return html text in which src attribute of img tag may be replaced with content uri
     */
    public static String resolveInlineImage(
            ContentResolver resolver, Account account, String text, Part part, int depth)
        throws MessagingException {
        // avoid too deep recursive call.
        if (depth >= 10 || text == null) {
            return text;
        }
        String contentType = MimeUtility.unfoldAndDecode(part.getContentType());
        String contentId = part.getContentId();
        if (contentType.startsWith("image/") &&
            contentId != null &&
            part instanceof LocalAttachmentBodyPart) {
            LocalAttachmentBodyPart attachment = (LocalAttachmentBodyPart)part;
            Uri contentUri = AttachmentProvider.resolveAttachmentIdToContentUri(
                    resolver, AttachmentProvider.getAttachmentUri(account, attachment.getAttachmentId()));
            // Regexp which matches ' src="cid:contentId"'.
            String contentIdRe = "\\s+(?i)src=\"cid(?-i):\\Q" + contentId + "\\E\"";
            // Replace all occurrences of src attribute with ' src="content://contentUri"'.
            text = text.replaceAll(contentIdRe, " src=\"" + contentUri + "\""); 
        }

        if (part.getBody() instanceof Multipart) {
            Multipart mp = (Multipart)part.getBody();
            for (int i = 0; i < mp.getCount(); i++) {
                text = resolveInlineImage(resolver, account, text, mp.getBodyPart(i), depth + 1);
            }
        }

        return text;
    }
    
    public static String escapeCharacterToDisplay(String text, boolean plainText) {
        // TODO: implement html escaping as in CL 145919, 148437 to fix bug 1785319 
        
        return text;
    }
}
