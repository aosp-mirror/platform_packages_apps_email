/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.email.mail;

import com.android.email.mail.internet.BinaryTempFileBody;
import com.android.email.mail.internet.MimeBodyPart;
import com.android.email.mail.internet.MimeHeader;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.internet.MimeMultipart;
import com.android.email.mail.internet.TextBody;
import com.android.email.mail.store.LocalStore;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;

import android.net.Uri;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Utility class makes it easier for developer to build mail message objects.
 * <p>
 * Typical usage of these helper functions and builder objects are as follows.
 * <p>
 * <pre>
 * String text2 = new TextBuilder("<html>").text("<head></head>")
 *     .text("<body>").cidImg("contetid@domain").text("</body>").build("</html");
 * String text2 = new TextBuilder("<html>").text("<head></head>")
 *     .text("<body>").uriImg(contentUri).text("</body>").build("</html");
 * Message msg = new MessageBuilder()
 *     .setBody(new MultipartBuilder("multipart/mixed")
 *         .addBodyPart(MessageTestUtils.imagePart("image/jpeg", null, 30, store))
 *         .addBodyPart(MessageTestUtils.imagePart("application/pdf", cid1, aid1, store))
 *         .addBodyPart(new MultipartBuilder("multipart/related")
 *             .addBodyPart(MessageTestUtils.textPart("text/html", text2 + text1))
 *             .addBodyPart(MessageTestUtils.imagePart("image/jpg", cid1, aid1, store))
 *             .addBodyPart(MessageTestUtils.imagePart("image/gif", cid2, aid2, store))
 *             .buildBodyPart())
 *         .addBodyPart(MessageTestUtils.imagePart("application/pdf", cid2, aid2, store))
 *         .build())
 *     .build();
 * </pre>
 */

public class MessageTestUtils {

    /**
     * Generate AttachmentProvider content URI from attachment ID and Account.
     * 
     * @param attachmentId attachment id
     * @param account Account object
     * @return AttachmentProvider content URI
     */
    public static Uri contentUri(long attachmentId, EmailContent.Account account) {
        return AttachmentProvider.getAttachmentUri(account.mId, attachmentId);
    }

    /**
     * Create simple MimeBodyPart.
     *  
     * @param mimeType MIME type of body part
     * @param contentId content-id header value (optional - null for no header)
     * @return MimeBodyPart object which body is null.
     * @throws MessagingException
     */
    public static BodyPart bodyPart(String mimeType, String contentId) throws MessagingException {
        final MimeBodyPart bp = new MimeBodyPart(null, mimeType);
        if (contentId != null) {
            bp.setHeader(MimeHeader.HEADER_CONTENT_ID, contentId);
        }
        return bp;
    }
    
    /**
     * Create MimeBodyPart with TextBody.
     * 
     * @param mimeType MIME type of text
     * @param text body text string
     * @return MimeBodyPart object whose body is TextBody
     * @throws MessagingException
     */
    public static BodyPart textPart(String mimeType, String text) throws MessagingException {
        final TextBody textBody = new TextBody(text);
        final MimeBodyPart textPart = new MimeBodyPart(textBody);
        textPart.setHeader(MimeHeader.HEADER_CONTENT_TYPE, mimeType);
        return textPart;
    }

    /**
     * Create attachment BodyPart with content-id.
     * 
     * @param mimeType MIME type of image body
     * @param contentId content-id header value (optional - null for no header)
     * @param attachmentId attachment id of store
     * @param store LocalStore which stores attachment
     * @return LocalAttachmentBodyPart with content-id 
     * @throws MessagingException
     * @throws IOException
     */
    public static BodyPart imagePart(String mimeType, String contentId,
            long attachmentId, LocalStore store) throws MessagingException, IOException {
        final BinaryTempFileBody imageBody = new BinaryTempFileBody();
        final LocalStore.LocalAttachmentBodyPart imagePart =
            store.new LocalAttachmentBodyPart(imageBody, attachmentId);
        imagePart.setHeader(MimeHeader.HEADER_CONTENT_TYPE, mimeType);
        if (contentId != null) {
            imagePart.setHeader(MimeHeader.HEADER_CONTENT_ID, contentId);
        }
        return imagePart;
    }

    /**
     * Builder class for Multipart.
     * 
     * This builder object accepts any number of BodyParts and then can produce
     * Multipart or BodyPart which contains accepted BodyParts. Usually combined with other
     * builder object and helper method.
     */
    public static class MultipartBuilder {
        private final String mContentType;
        private final ArrayList<BodyPart> mParts = new ArrayList<BodyPart>();
        
        /**
         * Create builder object with MIME type and dummy boundary string.
         * 
         * @param mimeType MIME type of this Multipart  
         */
        public MultipartBuilder(String mimeType) {
            this(mimeType, "this_is_boundary");
        }

        /**
         * Create builder object with MIME type and boundary string.
         * 
         * @param mimeType MIME type of this Multipart
         * @param boundary boundary string
         */
        public MultipartBuilder(String mimeType, String boundary) {
            mContentType = mimeType + "; boundary=" + boundary;
        }

        /**
         * Modifier method to add BodyPart to intended Multipart.
         * 
         * @param bodyPart BodyPart to be added
         * @return builder object itself
         */
        public MultipartBuilder addBodyPart(final BodyPart bodyPart) {
            mParts.add(bodyPart);
            return this;
        }

        /**
         * Build method to create Multipart.
         * 
         * @return intended Multipart object
         * @throws MessagingException
         */
        public Multipart build() throws MessagingException {
            final MimeMultipart mp = new MimeMultipart(mContentType);
            for (BodyPart p : mParts) {
                mp.addBodyPart(p);
            }
            return mp;
        }

        /**
         * Build method to create BodyPart that contains this "Multipart"
         * @return BodyPart whose body is intended Multipart.
         * @throws MessagingException
         */
        public BodyPart buildBodyPart() throws MessagingException {
            final BodyPart bp = new MimeBodyPart();
            bp.setBody(this.build());
            return bp;
        }
    }

    /**
     * Builder class for Message
     * 
     * This builder object accepts Body and then can produce Message object.
     * Usually combined with other builder object and helper method.
     */
    public static class MessageBuilder {
        private Body mBody;
       
        /**
         * Create Builder object.
         */
        public MessageBuilder() {
        }

        /**
         * Modifier method to set Body.
         * 
         * @param body Body of intended Message
         * @return builder object itself
         */
        public MessageBuilder setBody(final Body body) {
            mBody = body;
            return this;
        }

        /**
         * Build method to create Message.
         * 
         * @return intended Message object
         * @throws MessagingException
         */
        public Message build() throws MessagingException {
            final MimeMessage msg = new MimeMessage();
            if (mBody == null) {
                throw new MessagingException("body is not specified");
            }
            msg.setBody(mBody);
            return msg;
        }
    }

    /**
     * Builder class for simple HTML String. 
     * This builder object accepts some type of object or and string and then create String object.
     * Usually combined with other builder object and helper method.
     */
    public static class TextBuilder {
        final StringBuilder mBuilder = new StringBuilder();

        /**
         * Create builder with preamble string
         * @param preamble 
         */
        public TextBuilder(String preamble) {
            mBuilder.append(preamble);
        }
        
        /**
         * Modifier method to add img tag that has cid: src attribute.
         * @param contentId content id string
         * @return builder object itself
         */
        public TextBuilder addCidImg(String contentId) {
            return addTag("img", "SRC", "cid:" + contentId);
        }

        /**
         * Modifier method to add img tag that has content:// src attribute.
         * @param contentUri content uri object
         * @return builder object itself
         */
        public TextBuilder addUidImg(Uri contentUri) {
            return addTag("img", "src", contentUri.toString());
        }

        /**
         * Modifier method to add tag with specified attribute and value.
         * 
         * @param tag tag name
         * @param attribute attribute name
         * @param value attribute value
         * @return builder object itself
         */
        public TextBuilder addTag(String tag, String attribute, String value) {
            return addText(String.format("<%s %s=\"%s\">", tag, attribute, value));
        }

        /**
         * Modifier method to add simple string.
         * @param text string to add
         * @return builder object itself
         */
        public TextBuilder addText(String text) {
            mBuilder.append(text);
            return this;
        }

        /**
         * Build method to create intended String
         * @param epilogue string to add to the end
         * @return intended String
         */
        public String build(String epilogue) {
            mBuilder.append(epilogue);
            return mBuilder.toString();
        }
    }

}
