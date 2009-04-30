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

import com.android.email.Utility;

import org.apache.james.mime4j.codec.EncoderUtil;
import org.apache.james.mime4j.decoder.DecoderUtil;

import android.text.TextUtils;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * This class represent email address.
 * 
 * RFC822 email address may have following format.
 *   "name" <address> (comment)
 *   "name" <address>
 *   name <address>
 *   address
 * Name and comment part should be MIME/base64 encoded in header if necessary.
 *
 */
public class Address {
    /**
     *  Address part, in the form local_part@domain_part. No surrounding angle brackets.
     */
    String mAddress;

    /**
     * Name part. No surrounding double quote, and no MIME/base64 encoding.
     * This must be null if Address has no name part.
     */
    String mPersonal;

    // Regex that matches address surrounded by '<>' optionally. '^<?([^>]+)>?$'
    private static final Pattern REMOVE_OPTIONAL_BRACKET = Pattern.compile("^<?([^>]+)>?$");
    // Regex that matches personal name surrounded by '""' optionally. '^"?([^"]+)"?$'
    private static final Pattern REMOVE_OPTIONAL_DQUOTE = Pattern.compile("^\"?([^\"]*)\"?$");
    // Regex that matches escaped character '\\([\\"])'
    private static final Pattern UNQUOTE = Pattern.compile("\\\\([\\\\\"])");

    public Address(String address, String personal) {
        setAddress(address);
        setPersonal(personal);
    }

    public Address(String address) {
        setAddress(address);
    }

    public String getAddress() {
        return mAddress;
    }

    public void setAddress(String address) {
        this.mAddress = REMOVE_OPTIONAL_BRACKET.matcher(address).replaceAll("$1");;
    }

    /**
     * Get name part as UTF-16 string. No surrounding double quote, and no MIME/base64 encoding.
     * 
     * @return Name part of email address. Returns null if it is omitted.
     */
    public String getPersonal() {
        return mPersonal;
    }

    /**
     * Set name part from UTF-16 string. Optional surrounding double quote will be removed.
     * It will be also unquoted and MIME/base64 decoded.
     * 
     * @param Personal name part of email address as UTF-16 string. Null is acceptable.
     */
    public void setPersonal(String personal) {
        if (personal != null) {
            personal = REMOVE_OPTIONAL_DQUOTE.matcher(personal).replaceAll("$1");
            personal = UNQUOTE.matcher(personal).replaceAll("$1");
            personal = DecoderUtil.decodeEncodedWords(personal);
            if (personal.length() == 0) {
                personal = null;
            }
        }
        this.mPersonal = personal;
    }

    /**
     * Parse a comma-delimited list of addresses in RFC822 format and return an
     * array of Address objects.
     * 
     * @param addressList Address list in comma-delimited string.
     * @return An array of 0 or more Addresses.
     */
    public static Address[] parse(String addressList) {
        if (addressList == null || addressList.length() == 0) {
            return new Address[] {};
        }
        Rfc822Token[] tokens = Rfc822Tokenizer.tokenize(addressList);
        ArrayList<Address> addresses = new ArrayList<Address>();
        for (int i = 0, length = tokens.length; i < length; ++i) {
            Rfc822Token token = tokens[i];
            String address = token.getAddress();
            if (!TextUtils.isEmpty(address)) {
                // Note: Some email provider may violate the standard, so here we only check that
                // address consists of two part that are separated by '@', and domain part contains
                // at least one '.'.
                int len = address.length();
                int firstAt = address.indexOf('@');
                int lastAt = address.lastIndexOf('@');
                int firstDot = address.indexOf('.', lastAt + 1);
                int lastDot = address.lastIndexOf('.');
                if (firstAt > 0 && firstAt == lastAt && lastAt + 1 < firstDot
                        && firstDot <= lastDot && lastDot < len - 1) {
                    String name = token.getName();
                    if (TextUtils.isEmpty(name)) {
                        name = null;
                    }
                    addresses.add(new Address(address, name));
                }
            }
        }
        return addresses.toArray(new Address[] {});
    }
    
    @Override
    public boolean equals(Object o) {
        if (o instanceof Address) {
            return getAddress().equals(((Address) o).getAddress());
        }
        return super.equals(o);
    }

    /**
     * Get human readable address string.
     * Do not use this for email header.
     * 
     * @return Human readable address string.  Not quoted and not encoded.
     */
    public String toString() {
        if (mPersonal != null) {
            if (mPersonal.matches(".*[\\(\\)<>@,;:\\\\\".\\[\\]].*")) {
                return Utility.quoteString(mPersonal) + " <" + mAddress + ">";
            } else {
                return mPersonal + " <" + mAddress + ">";
            }
        } else {
            return mAddress;
        }
    }

    /**
     * Get human readable comma-delimited address string.
     * 
     * @param addresses Address array
     * @return Human readable comma-delimited address string.
     */
    public static String toString(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        if (addresses.length == 1) {
            return addresses[0].toString();
        }
        StringBuffer sb = new StringBuffer(addresses[0].toString());
        for (int i = 1; i < addresses.length; i++) {
            sb.append(',');
            sb.append(addresses[i].toString());
        }
        return sb.toString();
    }
    
    /**
     * Get RFC822/MIME compatible address string.
     * 
     * @return RFC822/MIME compatible address string.
     * It may be surrounded by double quote or quoted and MIME/base64 encoded if necessary.
     */
    public String toHeader() {
        if (mPersonal != null) {
            return EncoderUtil.encodeAddressDisplayName(mPersonal) + " <" + mAddress + ">";
        } else {
            return mAddress;
        }
    }

    /**
     * Get RFC822/MIME compatible comma-delimited address string.
     * 
     * @param addresses Address array
     * @return RFC822/MIME compatible comma-delimited address string.
     * it may be surrounded by double quoted or quoted and MIME/base64 encoded if necessary.
     */
    public static String toHeader(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        if (addresses.length == 1) {
            return addresses[0].toHeader();
        }
        StringBuffer sb = new StringBuffer(addresses[0].toHeader());
        for (int i = 1; i < addresses.length; i++) {
            // We need space character to be able to fold line.
            sb.append(", ");
            sb.append(addresses[i].toHeader());
        }
        return sb.toString();
    }
    
    /**
     * Get Human friendly address string.
     * 
     * @return the personal part of this Address, or the address part if the 
     * personal part is not available
     */
    public String toFriendly() {
        if (mPersonal != null && mPersonal.length() > 0) {
            return mPersonal;
        } else {
            return mAddress;
        }
    }
    
    /**
     * Creates a comma-delimited list of addresses in the "friendly" format (see toFriendly() for 
     * details on the per-address conversion).
     * 
     * @param addresses Array of Address[] values
     * @return A comma-delimited string listing all of the addresses supplied.  Null if source
     * was null or empty.
     */
    public static String toFriendly(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        if (addresses.length == 1) {
            return addresses[0].toFriendly();
        }
        StringBuffer sb = new StringBuffer(addresses[0].toFriendly());
        for (int i = 1; i < addresses.length; i++) {
            sb.append(',');
            sb.append(addresses[i].toFriendly());
        }
        return sb.toString();
    }
    
    /**
     * Unpacks an address list previously packed with packAddressList()
     * @param list
     * @return
     */
    public static Address[] unpack(String addressList) {
        if (addressList == null || addressList.length() == 0) {
            return new Address[] { };
        }
        ArrayList<Address> addresses = new ArrayList<Address>();
        int length = addressList.length();
        int pairStartIndex = 0;
        int pairEndIndex = 0;
        int addressEndIndex = 0;
        while (pairStartIndex < length) {
            pairEndIndex = addressList.indexOf(',', pairStartIndex);
            if (pairEndIndex == -1) {
                pairEndIndex = length;
            }
            addressEndIndex = addressList.indexOf(';', pairStartIndex);
            String address = null;
            String personal = null;
            if (addressEndIndex == -1 || addressEndIndex > pairEndIndex) {
                address = Utility.fastUrlDecode(addressList.substring(pairStartIndex, pairEndIndex));
            }
            else {
                address = Utility.fastUrlDecode(addressList.substring(pairStartIndex, addressEndIndex));
                personal = Utility.fastUrlDecode(addressList.substring(addressEndIndex + 1, pairEndIndex));
            }
            addresses.add(new Address(address, personal));
            pairStartIndex = pairEndIndex + 1;
        }
        return addresses.toArray(new Address[] { });
    }
    
    /**
     * Packs an address list into a String that is very quick to read
     * and parse. Packed lists can be unpacked with unpackAddressList()
     * The packed list is a comma separated list of:
     * URLENCODE(address)[;URLENCODE(personal)] 
     * @param list
     * @return
     */
    public static String pack(Address[] addresses) {
        if (addresses == null) {
            return null;
        } else if (addresses.length == 0) {
            return "";
        }
        StringBuffer sb = new StringBuffer();
        for (int i = 0, count = addresses.length; i < count; i++) {
            Address address = addresses[i];
            try {
                sb.append(URLEncoder.encode(address.getAddress(), "UTF-8"));
                if (address.getPersonal() != null) {
                    sb.append(';');
                    sb.append(URLEncoder.encode(address.getPersonal(), "UTF-8"));
                }
                if (i < count - 1) {
                    sb.append(',');
                }
            }
            catch (UnsupportedEncodingException uee) {
                return null;
            }
        }
        return sb.toString();
    }
}
