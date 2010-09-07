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

import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Class to generate a short 'snippet' from either plain text or html text
 *
 * If the sync protocol can get plain text, that's great, but we'll still strip out extraneous
 * whitespace.  If it's HTML, we'll 1) strip out tags, 2) turn entities into the appropriate
 * characters, and 3) strip out extraneous whitespace, all in one pass
 *
 * Why not use an existing class?  The best answer is performance; yet another answer is
 * correctness (e.g. Html.textFromHtml simply doesn't generate well-stripped text).  But performance
 * is key; we frequently sync text that is 10K or (much) longer, yet we really only care about a
 * small amount of text for the snippet.  So it's critically important that we just stop when we've
 * gotten enough; existing methods that exist will go through the entire incoming string, at great
 * (and useless) expense.
 */
public class Snippet {
    // This is how many chars we'll allow in a snippet
    private static final int MAX_PLAIN_TEXT_SCAN_LENGTH = 200;
    // For some reason, isWhitespace() returns false with the following...
    /*package*/ static final char NON_BREAKING_SPACE_CHARACTER = (char)160;

    // Note: ESCAPE_STRINGS is taken from the StringUtil class which is part of the
    // unbundled_google package
    static final Map<String, Character> ESCAPE_STRINGS;
    static {
        // HTML character entity references as defined in HTML 4
        // see http://www.w3.org/TR/REC-html40/sgml/entities.html
        ESCAPE_STRINGS = new HashMap<String, Character>(252);

        ESCAPE_STRINGS.put("&nbsp", '\u00A0');
        ESCAPE_STRINGS.put("&iexcl", '\u00A1');
        ESCAPE_STRINGS.put("&cent", '\u00A2');
        ESCAPE_STRINGS.put("&pound", '\u00A3');
        ESCAPE_STRINGS.put("&curren", '\u00A4');
        ESCAPE_STRINGS.put("&yen", '\u00A5');
        ESCAPE_STRINGS.put("&brvbar", '\u00A6');
        ESCAPE_STRINGS.put("&sect", '\u00A7');
        ESCAPE_STRINGS.put("&uml", '\u00A8');
        ESCAPE_STRINGS.put("&copy", '\u00A9');
        ESCAPE_STRINGS.put("&ordf", '\u00AA');
        ESCAPE_STRINGS.put("&laquo", '\u00AB');
        ESCAPE_STRINGS.put("&not", '\u00AC');
        ESCAPE_STRINGS.put("&shy", '\u00AD');
        ESCAPE_STRINGS.put("&reg", '\u00AE');
        ESCAPE_STRINGS.put("&macr", '\u00AF');
        ESCAPE_STRINGS.put("&deg", '\u00B0');
        ESCAPE_STRINGS.put("&plusmn", '\u00B1');
        ESCAPE_STRINGS.put("&sup2", '\u00B2');
        ESCAPE_STRINGS.put("&sup3", '\u00B3');
        ESCAPE_STRINGS.put("&acute", '\u00B4');
        ESCAPE_STRINGS.put("&micro", '\u00B5');
        ESCAPE_STRINGS.put("&para", '\u00B6');
        ESCAPE_STRINGS.put("&middot", '\u00B7');
        ESCAPE_STRINGS.put("&cedil", '\u00B8');
        ESCAPE_STRINGS.put("&sup1", '\u00B9');
        ESCAPE_STRINGS.put("&ordm", '\u00BA');
        ESCAPE_STRINGS.put("&raquo", '\u00BB');
        ESCAPE_STRINGS.put("&frac14", '\u00BC');
        ESCAPE_STRINGS.put("&frac12", '\u00BD');
        ESCAPE_STRINGS.put("&frac34", '\u00BE');
        ESCAPE_STRINGS.put("&iquest", '\u00BF');
        ESCAPE_STRINGS.put("&Agrave", '\u00C0');
        ESCAPE_STRINGS.put("&Aacute", '\u00C1');
        ESCAPE_STRINGS.put("&Acirc", '\u00C2');
        ESCAPE_STRINGS.put("&Atilde", '\u00C3');
        ESCAPE_STRINGS.put("&Auml", '\u00C4');
        ESCAPE_STRINGS.put("&Aring", '\u00C5');
        ESCAPE_STRINGS.put("&AElig", '\u00C6');
        ESCAPE_STRINGS.put("&Ccedil", '\u00C7');
        ESCAPE_STRINGS.put("&Egrave", '\u00C8');
        ESCAPE_STRINGS.put("&Eacute", '\u00C9');
        ESCAPE_STRINGS.put("&Ecirc", '\u00CA');
        ESCAPE_STRINGS.put("&Euml", '\u00CB');
        ESCAPE_STRINGS.put("&Igrave", '\u00CC');
        ESCAPE_STRINGS.put("&Iacute", '\u00CD');
        ESCAPE_STRINGS.put("&Icirc", '\u00CE');
        ESCAPE_STRINGS.put("&Iuml", '\u00CF');
        ESCAPE_STRINGS.put("&ETH", '\u00D0');
        ESCAPE_STRINGS.put("&Ntilde", '\u00D1');
        ESCAPE_STRINGS.put("&Ograve", '\u00D2');
        ESCAPE_STRINGS.put("&Oacute", '\u00D3');
        ESCAPE_STRINGS.put("&Ocirc", '\u00D4');
        ESCAPE_STRINGS.put("&Otilde", '\u00D5');
        ESCAPE_STRINGS.put("&Ouml", '\u00D6');
        ESCAPE_STRINGS.put("&times", '\u00D7');
        ESCAPE_STRINGS.put("&Oslash", '\u00D8');
        ESCAPE_STRINGS.put("&Ugrave", '\u00D9');
        ESCAPE_STRINGS.put("&Uacute", '\u00DA');
        ESCAPE_STRINGS.put("&Ucirc", '\u00DB');
        ESCAPE_STRINGS.put("&Uuml", '\u00DC');
        ESCAPE_STRINGS.put("&Yacute", '\u00DD');
        ESCAPE_STRINGS.put("&THORN", '\u00DE');
        ESCAPE_STRINGS.put("&szlig", '\u00DF');
        ESCAPE_STRINGS.put("&agrave", '\u00E0');
        ESCAPE_STRINGS.put("&aacute", '\u00E1');
        ESCAPE_STRINGS.put("&acirc", '\u00E2');
        ESCAPE_STRINGS.put("&atilde", '\u00E3');
        ESCAPE_STRINGS.put("&auml", '\u00E4');
        ESCAPE_STRINGS.put("&aring", '\u00E5');
        ESCAPE_STRINGS.put("&aelig", '\u00E6');
        ESCAPE_STRINGS.put("&ccedil", '\u00E7');
        ESCAPE_STRINGS.put("&egrave", '\u00E8');
        ESCAPE_STRINGS.put("&eacute", '\u00E9');
        ESCAPE_STRINGS.put("&ecirc", '\u00EA');
        ESCAPE_STRINGS.put("&euml", '\u00EB');
        ESCAPE_STRINGS.put("&igrave", '\u00EC');
        ESCAPE_STRINGS.put("&iacute", '\u00ED');
        ESCAPE_STRINGS.put("&icirc", '\u00EE');
        ESCAPE_STRINGS.put("&iuml", '\u00EF');
        ESCAPE_STRINGS.put("&eth", '\u00F0');
        ESCAPE_STRINGS.put("&ntilde", '\u00F1');
        ESCAPE_STRINGS.put("&ograve", '\u00F2');
        ESCAPE_STRINGS.put("&oacute", '\u00F3');
        ESCAPE_STRINGS.put("&ocirc", '\u00F4');
        ESCAPE_STRINGS.put("&otilde", '\u00F5');
        ESCAPE_STRINGS.put("&ouml", '\u00F6');
        ESCAPE_STRINGS.put("&divide", '\u00F7');
        ESCAPE_STRINGS.put("&oslash", '\u00F8');
        ESCAPE_STRINGS.put("&ugrave", '\u00F9');
        ESCAPE_STRINGS.put("&uacute", '\u00FA');
        ESCAPE_STRINGS.put("&ucirc", '\u00FB');
        ESCAPE_STRINGS.put("&uuml", '\u00FC');
        ESCAPE_STRINGS.put("&yacute", '\u00FD');
        ESCAPE_STRINGS.put("&thorn", '\u00FE');
        ESCAPE_STRINGS.put("&yuml", '\u00FF');
        ESCAPE_STRINGS.put("&fnof", '\u0192');
        ESCAPE_STRINGS.put("&Alpha", '\u0391');
        ESCAPE_STRINGS.put("&Beta", '\u0392');
        ESCAPE_STRINGS.put("&Gamma", '\u0393');
        ESCAPE_STRINGS.put("&Delta", '\u0394');
        ESCAPE_STRINGS.put("&Epsilon", '\u0395');
        ESCAPE_STRINGS.put("&Zeta", '\u0396');
        ESCAPE_STRINGS.put("&Eta", '\u0397');
        ESCAPE_STRINGS.put("&Theta", '\u0398');
        ESCAPE_STRINGS.put("&Iota", '\u0399');
        ESCAPE_STRINGS.put("&Kappa", '\u039A');
        ESCAPE_STRINGS.put("&Lambda", '\u039B');
        ESCAPE_STRINGS.put("&Mu", '\u039C');
        ESCAPE_STRINGS.put("&Nu", '\u039D');
        ESCAPE_STRINGS.put("&Xi", '\u039E');
        ESCAPE_STRINGS.put("&Omicron", '\u039F');
        ESCAPE_STRINGS.put("&Pi", '\u03A0');
        ESCAPE_STRINGS.put("&Rho", '\u03A1');
        ESCAPE_STRINGS.put("&Sigma", '\u03A3');
        ESCAPE_STRINGS.put("&Tau", '\u03A4');
        ESCAPE_STRINGS.put("&Upsilon", '\u03A5');
        ESCAPE_STRINGS.put("&Phi", '\u03A6');
        ESCAPE_STRINGS.put("&Chi", '\u03A7');
        ESCAPE_STRINGS.put("&Psi", '\u03A8');
        ESCAPE_STRINGS.put("&Omega", '\u03A9');
        ESCAPE_STRINGS.put("&alpha", '\u03B1');
        ESCAPE_STRINGS.put("&beta", '\u03B2');
        ESCAPE_STRINGS.put("&gamma", '\u03B3');
        ESCAPE_STRINGS.put("&delta", '\u03B4');
        ESCAPE_STRINGS.put("&epsilon", '\u03B5');
        ESCAPE_STRINGS.put("&zeta", '\u03B6');
        ESCAPE_STRINGS.put("&eta", '\u03B7');
        ESCAPE_STRINGS.put("&theta", '\u03B8');
        ESCAPE_STRINGS.put("&iota", '\u03B9');
        ESCAPE_STRINGS.put("&kappa", '\u03BA');
        ESCAPE_STRINGS.put("&lambda", '\u03BB');
        ESCAPE_STRINGS.put("&mu", '\u03BC');
        ESCAPE_STRINGS.put("&nu", '\u03BD');
        ESCAPE_STRINGS.put("&xi", '\u03BE');
        ESCAPE_STRINGS.put("&omicron", '\u03BF');
        ESCAPE_STRINGS.put("&pi", '\u03C0');
        ESCAPE_STRINGS.put("&rho", '\u03C1');
        ESCAPE_STRINGS.put("&sigmaf", '\u03C2');
        ESCAPE_STRINGS.put("&sigma", '\u03C3');
        ESCAPE_STRINGS.put("&tau", '\u03C4');
        ESCAPE_STRINGS.put("&upsilon", '\u03C5');
        ESCAPE_STRINGS.put("&phi", '\u03C6');
        ESCAPE_STRINGS.put("&chi", '\u03C7');
        ESCAPE_STRINGS.put("&psi", '\u03C8');
        ESCAPE_STRINGS.put("&omega", '\u03C9');
        ESCAPE_STRINGS.put("&thetasym", '\u03D1');
        ESCAPE_STRINGS.put("&upsih", '\u03D2');
        ESCAPE_STRINGS.put("&piv", '\u03D6');
        ESCAPE_STRINGS.put("&bull", '\u2022');
        ESCAPE_STRINGS.put("&hellip", '\u2026');
        ESCAPE_STRINGS.put("&prime", '\u2032');
        ESCAPE_STRINGS.put("&Prime", '\u2033');
        ESCAPE_STRINGS.put("&oline", '\u203E');
        ESCAPE_STRINGS.put("&frasl", '\u2044');
        ESCAPE_STRINGS.put("&weierp", '\u2118');
        ESCAPE_STRINGS.put("&image", '\u2111');
        ESCAPE_STRINGS.put("&real", '\u211C');
        ESCAPE_STRINGS.put("&trade", '\u2122');
        ESCAPE_STRINGS.put("&alefsym", '\u2135');
        ESCAPE_STRINGS.put("&larr", '\u2190');
        ESCAPE_STRINGS.put("&uarr", '\u2191');
        ESCAPE_STRINGS.put("&rarr", '\u2192');
        ESCAPE_STRINGS.put("&darr", '\u2193');
        ESCAPE_STRINGS.put("&harr", '\u2194');
        ESCAPE_STRINGS.put("&crarr", '\u21B5');
        ESCAPE_STRINGS.put("&lArr", '\u21D0');
        ESCAPE_STRINGS.put("&uArr", '\u21D1');
        ESCAPE_STRINGS.put("&rArr", '\u21D2');
        ESCAPE_STRINGS.put("&dArr", '\u21D3');
        ESCAPE_STRINGS.put("&hArr", '\u21D4');
        ESCAPE_STRINGS.put("&forall", '\u2200');
        ESCAPE_STRINGS.put("&part", '\u2202');
        ESCAPE_STRINGS.put("&exist", '\u2203');
        ESCAPE_STRINGS.put("&empty", '\u2205');
        ESCAPE_STRINGS.put("&nabla", '\u2207');
        ESCAPE_STRINGS.put("&isin", '\u2208');
        ESCAPE_STRINGS.put("&notin", '\u2209');
        ESCAPE_STRINGS.put("&ni", '\u220B');
        ESCAPE_STRINGS.put("&prod", '\u220F');
        ESCAPE_STRINGS.put("&sum", '\u2211');
        ESCAPE_STRINGS.put("&minus", '\u2212');
        ESCAPE_STRINGS.put("&lowast", '\u2217');
        ESCAPE_STRINGS.put("&radic", '\u221A');
        ESCAPE_STRINGS.put("&prop", '\u221D');
        ESCAPE_STRINGS.put("&infin", '\u221E');
        ESCAPE_STRINGS.put("&ang", '\u2220');
        ESCAPE_STRINGS.put("&and", '\u2227');
        ESCAPE_STRINGS.put("&or", '\u2228');
        ESCAPE_STRINGS.put("&cap", '\u2229');
        ESCAPE_STRINGS.put("&cup", '\u222A');
        ESCAPE_STRINGS.put("&int", '\u222B');
        ESCAPE_STRINGS.put("&there4", '\u2234');
        ESCAPE_STRINGS.put("&sim", '\u223C');
        ESCAPE_STRINGS.put("&cong", '\u2245');
        ESCAPE_STRINGS.put("&asymp", '\u2248');
        ESCAPE_STRINGS.put("&ne", '\u2260');
        ESCAPE_STRINGS.put("&equiv", '\u2261');
        ESCAPE_STRINGS.put("&le", '\u2264');
        ESCAPE_STRINGS.put("&ge", '\u2265');
        ESCAPE_STRINGS.put("&sub", '\u2282');
        ESCAPE_STRINGS.put("&sup", '\u2283');
        ESCAPE_STRINGS.put("&nsub", '\u2284');
        ESCAPE_STRINGS.put("&sube", '\u2286');
        ESCAPE_STRINGS.put("&supe", '\u2287');
        ESCAPE_STRINGS.put("&oplus", '\u2295');
        ESCAPE_STRINGS.put("&otimes", '\u2297');
        ESCAPE_STRINGS.put("&perp", '\u22A5');
        ESCAPE_STRINGS.put("&sdot", '\u22C5');
        ESCAPE_STRINGS.put("&lceil", '\u2308');
        ESCAPE_STRINGS.put("&rceil", '\u2309');
        ESCAPE_STRINGS.put("&lfloor", '\u230A');
        ESCAPE_STRINGS.put("&rfloor", '\u230B');
        ESCAPE_STRINGS.put("&lang", '\u2329');
        ESCAPE_STRINGS.put("&rang", '\u232A');
        ESCAPE_STRINGS.put("&loz", '\u25CA');
        ESCAPE_STRINGS.put("&spades", '\u2660');
        ESCAPE_STRINGS.put("&clubs", '\u2663');
        ESCAPE_STRINGS.put("&hearts", '\u2665');
        ESCAPE_STRINGS.put("&diams", '\u2666');
        ESCAPE_STRINGS.put("&quot", '\u0022');
        ESCAPE_STRINGS.put("&amp", '\u0026');
        ESCAPE_STRINGS.put("&lt", '\u003C');
        ESCAPE_STRINGS.put("&gt", '\u003E');
        ESCAPE_STRINGS.put("&OElig", '\u0152');
        ESCAPE_STRINGS.put("&oelig", '\u0153');
        ESCAPE_STRINGS.put("&Scaron", '\u0160');
        ESCAPE_STRINGS.put("&scaron", '\u0161');
        ESCAPE_STRINGS.put("&Yuml", '\u0178');
        ESCAPE_STRINGS.put("&circ", '\u02C6');
        ESCAPE_STRINGS.put("&tilde", '\u02DC');
        ESCAPE_STRINGS.put("&ensp", '\u2002');
        ESCAPE_STRINGS.put("&emsp", '\u2003');
        ESCAPE_STRINGS.put("&thinsp", '\u2009');
        ESCAPE_STRINGS.put("&zwnj", '\u200C');
        ESCAPE_STRINGS.put("&zwj", '\u200D');
        ESCAPE_STRINGS.put("&lrm", '\u200E');
        ESCAPE_STRINGS.put("&rlm", '\u200F');
        ESCAPE_STRINGS.put("&ndash", '\u2013');
        ESCAPE_STRINGS.put("&mdash", '\u2014');
        ESCAPE_STRINGS.put("&lsquo", '\u2018');
        ESCAPE_STRINGS.put("&rsquo", '\u2019');
        ESCAPE_STRINGS.put("&sbquo", '\u201A');
        ESCAPE_STRINGS.put("&ldquo", '\u201C');
        ESCAPE_STRINGS.put("&rdquo", '\u201D');
        ESCAPE_STRINGS.put("&bdquo", '\u201E');
        ESCAPE_STRINGS.put("&dagger", '\u2020');
        ESCAPE_STRINGS.put("&Dagger", '\u2021');
        ESCAPE_STRINGS.put("&permil", '\u2030');
        ESCAPE_STRINGS.put("&lsaquo", '\u2039');
        ESCAPE_STRINGS.put("&rsaquo", '\u203A');
        ESCAPE_STRINGS.put("&euro", '\u20AC');
    }

    public static String fromHtmlText(String text) {
        return fromText(text, true);
    }

    public static String fromPlainText(String text) {
        return fromText(text, false);
    }

    public static String fromText(String text, boolean stripHtml) {
        // Handle null and empty string
        if (TextUtils.isEmpty(text)) return "";

        final int length = text.length();
        // Use char[] instead of StringBuilder purely for performance; fewer method calls, etc.
        char[] buffer = new char[MAX_PLAIN_TEXT_SCAN_LENGTH];
        // skipCount is an array of a single int; that int is set inside stripHtmlEntity and is
        // used to determine how many characters can be "skipped" due to the transformation of the
        // entity to a single character.  When Java allows multiple return values, we can make this
        // much cleaner :-)
        int[] skipCount = new int[1];
        int bufferCount = 0;
        // Start with space as last character to avoid leading whitespace
        char last = ' ';
        // Indicates whether we're in the middle of an HTML tag
        boolean inTag = false;

        // Walk through the text until we're done with the input OR we've got a large enough snippet
        for (int i = 0; i < length && bufferCount < MAX_PLAIN_TEXT_SCAN_LENGTH; i++) {
            char c = text.charAt(i);
            if (stripHtml && !inTag && (c == '<')) {
                // Find tags to strip; they will begin with <! or !- or </ or <letter
                if (i < (length - 1)) {
                    char peek = text.charAt(i + 1);
                    if (peek == '!' || peek == '-' || peek == '/' || Character.isLetter(peek)) {
                        inTag = true;
                    }
                }
            } else if (stripHtml && inTag && (c == '>')) {
                // Terminate stripping here
                inTag = false;
                continue;
            }

            if (inTag) {
                // We just skip by everything while we're in a tag
                continue;
            } else if (stripHtml && (c == '&')) {
                // Handle a possible HTML entity here
                // We always get back a character to use; we also get back a "skip count",
                // indicating how many characters were eaten from the entity
                c = stripHtmlEntity(text, i, skipCount);
                i += skipCount[0];
            }

            if (Character.isWhitespace(c) || (c == NON_BREAKING_SPACE_CHARACTER)) {
                // The idea is to find the content in the message, not the whitespace, so we'll
                // turn any combination of contiguous whitespace into a single space
                if (last == ' ') {
                    continue;
                } else {
                    // Make every whitespace character a simple space
                    c = ' ';
                }
            } else if ((c == '-' || c == '=') && (last == c)) {
                // Lots of messages (especially digests) have whole lines of --- or ===
                // We'll get rid of those duplicates here
                continue;
            }

            // After all that, maybe we've got a character for our snippet
            buffer[bufferCount++] = c;
            last = c;
        }

        // Lose trailing space and return our snippet
        if ((bufferCount > 0) && (last == ' ')) {
            bufferCount--;
        }
        return new String(buffer, 0, bufferCount);
    }

    static /*package*/ char stripHtmlEntity(String text, int pos, int[] skipCount) {
        int length = text.length();
        // Ugly, but we store our skip count in this array; we can't use a static here, because
        // multiple threads might be calling in
        skipCount[0] = 0;
        // All entities are <= 8 characters long, so that's how far we'll look for one (+ & and ;)
        int end = pos + 10;
        String entity = null;
        // Isolate the entity
        for (int i = pos; (i < length) && (i < end); i++) {
            if (text.charAt(i) == ';') {
                entity = text.substring(pos, i);
                break;
            }
        }
        if (entity == null) {
            // This wasn't really an HTML entity
            return '&';
        } else {
            // Skip count is the length of the entity
            Character mapping = ESCAPE_STRINGS.get(entity);
            int entityLength = entity.length();
            if (mapping != null) {
                skipCount[0] = entityLength;
                return mapping;
            } else if ((entityLength > 2) && (entity.charAt(1) == '#')) {
                // &#nn; means ascii nn (decimal) and &#xnn means ascii nn (hex)
                char c = '?';
                try {
                    int i;
                    if ((entity.charAt(2) == 'x') && (entityLength > 3)) {
                        i = Integer.parseInt(entity.substring(3), 16);
                    } else {
                        i = Integer.parseInt(entity.substring(2));
                    }
                    c = (char)i;
                } catch (NumberFormatException e) {
                    // We'll just return the ? in this case
                }
                skipCount[0] = entityLength;
                return c;
            }
        }
        // Worst case, we return the original start character, ampersand
        return '&';
    }

}
