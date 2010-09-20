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

package com.android.email.mail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URI;

/**
 * This interface defines a "transport", which is defined here as being one layer below the 
 * specific wire protocols such as POP3, IMAP, or SMTP.  
 * 
 * Practically speaking, it provides a definition of the common functionality between them
 * (dealing with sockets & streams, SSL, logging, and so forth), and provides a seam just below
 * the individual protocols to enable better testing.
 * 
 * The following features are supported and presumed to be common:
 * 
 *  Interpretation of URI
 *  Support for SSL and TLS wireline security
 */
public interface Transport {
    
    /**
     * Connection security options for transport that supports SSL and/or TLS
     */
    public static final int CONNECTION_SECURITY_NONE = 0;
    public static final int CONNECTION_SECURITY_SSL = 1;
    public static final int CONNECTION_SECURITY_TLS = 2;
    
    /**
     * Get a new transport, using an existing one as a model.  The new transport is configured as if
     * setUri() and setSecurity() have been called, but not opened or connected in any way.
     * @return a new Transport ready to open()
     */
    public Transport newInstanceWithConfiguration();

    /**
     * Set the Uri for the connection.
     * 
     * @param uri The Uri for the connection
     * @param defaultPort If the Uri does not include an explicit port, this value will be used.
     */
    public void setUri(URI uri, int defaultPort);
    
    /**
     * @return Returns the host part of the Uri
     */
    public String getHost();
    
    /**
     * @return Returns the port (either from the Uri or from the default)
     */
    public int getPort();
    
    /**
     * Returns the user info parts of the Uri, if any were supplied.  Typically, [0] is the user 
     * and [1] is the password.
     * @return Returns the user info parts of the Uri.  Null if none were supplied.
     */
    public String[] getUserInfoParts();

    /**
     * Set the desired security mode for this connection.
     * @param connectionSecurity A value indicating the desired security mode.
     * @param trustAllCertificates true to allow unverifiable certificates to be used
     */
    public void setSecurity(int connectionSecurity, boolean trustAllCertificates);
    
    /**
     * @return Returns the desired security mode for this connection.
     */
    public int getSecurity();
    
    /**
     * @return true if the security mode indicates that SSL is possible
     */
    public boolean canTrySslSecurity();
    
    /**
     * @return true if the security mode indicates that TLS is possible
     */
    public boolean canTryTlsSecurity();

    /**
     * @return true if the security mode indicates that all certificates can be trusted
     */
    public boolean canTrustAllCertificates();

    /**
     * Set the socket timeout.
     * @param timeoutMilliseconds the read timeout value if greater than {@code 0}, or
     *            {@code 0} for an infinite timeout.
     */
    public void setSoTimeout(int timeoutMilliseconds) throws SocketException;

        /**
     * Attempts to open the connection using the supplied parameters, and using SSL if indicated.
     */
    public void open() throws MessagingException, CertificateValidationException;
    
    /**
     * Attempts to reopen the connection using TLS.
     */
    public void reopenTls() throws MessagingException;
    
    /**
     * @return true if the connection is open
     */
    public boolean isOpen();
    
    /**
     * Closes the connection.  Does not send any closure messages, simply closes the socket and the
     * associated streams.  Best effort only.  Catches all exceptions and always returns.  
     * 
     * MUST NOT throw any exceptions.
     */
    public void close();
    
    /**
     * @return returns the active input stream
     */
    public InputStream getInputStream();
    
    /**
     * @return returns the active output stream
     */
    public OutputStream getOutputStream();
    
    /**
     * Write a single line to the server, and may generate a log entry (if enabled).
     * @param s The text to send to the server.
     * @param sensitiveReplacement If the command includes sensitive data (e.g. authentication)
     * please pass a replacement string here (for logging).  Most callers simply pass null,
     */
    void writeLine(String s, String sensitiveReplacement) throws IOException;
    
    /**
     * Reads a single line from the server.  Any delimiter characters will not be included in the
     * result.  May generate a log entry, if enabled.
     * @return Returns the string from the server.
     * @throws IOException
     */
    String readLine() throws IOException;

    /**
     * @return The local address.  If we have an open socket, get the local address from this.
     *     Otherwise simply use {@link InetAddress#getLocalHost}.
     */
    InetAddress getLocalAddress() throws IOException;
}
