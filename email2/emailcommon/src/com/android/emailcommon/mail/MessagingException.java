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

package com.android.emailcommon.mail;


/**
 * This exception is used for most types of failures that occur during server interactions.
 *
 * Data passed through this exception should be considered non-localized.  Any strings should
 * either be internal-only (for debugging) or server-generated.
 *
 * TO DO: Does it make sense to further collapse AuthenticationFailedException and
 * CertificateValidationException and any others into this?
 */
public class MessagingException extends Exception {
    public static final long serialVersionUID = -1;

    public static final int NO_ERROR = -1;
    /** Any exception that does not specify a specific issue */
    public static final int UNSPECIFIED_EXCEPTION = 0;
    /** Connection or IO errors */
    public static final int IOERROR = 1;
    /** The configuration requested TLS but the server did not support it. */
    public static final int TLS_REQUIRED = 2;
    /** Authentication is required but the server did not support it. */
    public static final int AUTH_REQUIRED = 3;
    /** General security failures */
    public static final int GENERAL_SECURITY = 4;
    /** Authentication failed */
    public static final int AUTHENTICATION_FAILED = 5;
    /** Attempt to create duplicate account */
    public static final int DUPLICATE_ACCOUNT = 6;
    /** Required security policies reported - advisory only */
    public static final int SECURITY_POLICIES_REQUIRED = 7;
   /** Required security policies not supported */
    public static final int SECURITY_POLICIES_UNSUPPORTED = 8;
   /** The protocol (or protocol version) isn't supported */
    public static final int PROTOCOL_VERSION_UNSUPPORTED = 9;
    /** The server's SSL certificate couldn't be validated */
    public static final int CERTIFICATE_VALIDATION_ERROR = 10;
    /** Authentication failed during autodiscover */
    public static final int AUTODISCOVER_AUTHENTICATION_FAILED = 11;
    /** Autodiscover completed with a result (non-error) */
    public static final int AUTODISCOVER_AUTHENTICATION_RESULT = 12;
    /** Ambiguous failure; server error or bad credentials */
    public static final int AUTHENTICATION_FAILED_OR_SERVER_ERROR = 13;
    /** The server refused access */
    public static final int ACCESS_DENIED = 14;
    /** The server refused access */
    public static final int ATTACHMENT_NOT_FOUND = 15;
    /** A client SSL certificate is required for connections to the server */
    public static final int CLIENT_CERTIFICATE_REQUIRED = 16;
    /** The client SSL certificate specified is invalid */
    public static final int CLIENT_CERTIFICATE_ERROR = 17;

    protected int mExceptionType;
    // Exception type-specific data
    protected Object mExceptionData;

    public MessagingException(String message, Throwable throwable) {
        this(UNSPECIFIED_EXCEPTION, message, throwable);
    }

    public MessagingException(int exceptionType, String message, Throwable throwable) {
        super(message, throwable);
        mExceptionType = exceptionType;
        mExceptionData = null;
    }

    /**
     * Constructs a MessagingException with an exceptionType and a null message.
     * @param exceptionType The exception type to set for this exception.
     */
    public MessagingException(int exceptionType) {
        this(exceptionType, null, null);
    }

    /**
     * Constructs a MessagingException with a message.
     * @param message the message for this exception
     */
    public MessagingException(String message) {
        this(UNSPECIFIED_EXCEPTION, message, null);
    }

    /**
     * Constructs a MessagingException with an exceptionType and a message.
     * @param exceptionType The exception type to set for this exception.
     */
    public MessagingException(int exceptionType, String message) {
        this(exceptionType, message, null);
    }

    /**
     * Constructs a MessagingException with an exceptionType, a message, and data
     * @param exceptionType The exception type to set for this exception.
     * @param message the message for the exception (or null)
     * @param data exception-type specific data for the exception (or null)
     */
    public MessagingException(int exceptionType, String message, Object data) {
        super(message);
        mExceptionType = exceptionType;
        mExceptionData = data;
    }

    /**
     * Return the exception type.  Will be OTHER_EXCEPTION if not explicitly set.
     *
     * @return Returns the exception type.
     */
    public int getExceptionType() {
        return mExceptionType;
    }
    /**
     * Return the exception data.  Will be null if not explicitly set.
     *
     * @return Returns the exception data.
     */
    public Object getExceptionData() {
        return mExceptionData;
    }
}