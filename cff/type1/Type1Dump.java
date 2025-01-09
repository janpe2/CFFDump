/* Copyright 2025 Jani Pehkonen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cff.type1;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;
import cff.io.ASCIIHexDecoderStream;
import cff.io.EexecInputStream;
import cff.io.Filters;
import cff.io.PFBInputStream;

/**
 * PostScript Type 1 font analyzer.
 * <p>
 * Read also the section 10, "Adobe Type Manager Compatibility", in Adobe Type 1
 * Font Format specification.
 * <p>
 * This class contains a simple parser that executes no PostScript tokens.
 * Parsing Type1 fonts with a (simplified) PS interpreter won't work because many Type1
 * fonts contain arbitrary PS code that requires either a fully-fledged PS interpreter
 * or a parser that executes no PS tokens at all.
 */

public class Type1Dump
{
    private int lenIV = 4;
    private boolean useCharStringDecryption = true;
    private boolean privateFound = false;
    private boolean weightVectorFound;
    private boolean hasStandardEncoding;
    private boolean fontInfoFound;
    private PushbackInputStream input;
    private int encodingSectionsCount;
    private int encodingCodeCount;
    private int fontMatrixFound;
    private int charStringsCount;
    private int subrsCount;
    private StringBuilder stringBuff = new StringBuilder(); // for tokenization
    private StringBuilder dumpBuff = new StringBuilder(1024);
    private StringBuilder otherBuff = new StringBuilder(128);
    private StringBuilder privateDictBuff = new StringBuilder(128);
    private boolean dumpCharstrings = true;
    private boolean errorsFound;
    private boolean usesStem3;
    private boolean usesSeac;
    private boolean usesDiv;
    private boolean usesDotsection;
    private boolean usesSbw;
    private boolean usesCommand15;
    private int lastCharstringNumber;

    private static final char EOF_CHAR = '\uFFFF';
    private static final String CHAR_STRING_INDENT = "    ";
    private static final String SECTION_DIVIDER =
        "\n-------------------------------------------------------------------\n\n";

    private static final String RD = "RD";
    private static final String RD_ALT = "-|";

    private static final String ND = "ND";
    private static final String ND_ALT = "|-";

    private static final String NP = "NP";
    private static final String NP_ALT = "|";

    /**
     * Constructs a new Type1 parser thats reads from an input stream.
     *
     * @param file file to read
     * @param startOffset start offset of Type1 data
     * @throws java.io.IOException if startOffset is invalid or if an I/O error occurs
     */
    public Type1Dump(InputStream is, File fileNameInfo, int startOffsetInfo) throws IOException
    {
        initialize(is, fileNameInfo, startOffsetInfo);
    }

    /**
     * Constructs a new Type1 parser that can read encoded/compressed data.
     *
     * @param file file to read
     * @param startOffset start offset of Type1 data
     * @param filter filter for data encoding/compression
     * @throws java.io.IOException if startOffset is invalid or if an I/O error occurs
     */
    public Type1Dump(File file, int startOffset, int filter)
    throws IOException
    {
        if (startOffset < 0) {
            throw new IOException("Invalid start offset");
        }

        InputStream is = null;

        try {
            is = new FileInputStream(file);

            if (startOffset > 0) {
                is.skip(startOffset);
            }

            if (filter == Filters.FILTER_DEFLATE) {
                is = new InflaterInputStream(is);
            } else if (filter == Filters.FILTER_ASCII_HEX) {
                is = new ASCIIHexDecoderStream(is);
            }

            initialize(is, file, startOffset);

        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    private void initialize(InputStream is, File fileNameInfo, int startOffsetInfo)
    throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);

        try {
            int b;
            while ((b = is.read()) != -1) {
                baos.write(b);
            }

        } finally {
            if (is != null) {
                is.close();
            }
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        input = new PushbackInputStream(bais, 4);

        String fileInfoStr = (fileNameInfo == null) ? "<unknown file>" : fileNameInfo.toString();

        dumpBuff.append("% Type1 Dump Output\n");
        dumpBuff.append("% File: ").append(fileInfoStr).append('\n');
        if (startOffsetInfo > 0) {
            dumpBuff.append("% Start offset: ").append(startOffsetInfo).append('\n');
        }
    }

    public void parseFont() throws IOException
    {
        // Check if the input is a PFB file
        int b0 = input.read();
        int b1 = input.read();
        if (b1 == -1) {
            throw new RuntimeException("Type1 font has no data");
        }
        // These bytes must be unread also if it's a PFB data stream:
        unread(b1);
        unread(b0);
        if (b0 == 0x80 && b1 == 0x01) {
            input = new PushbackInputStream(new PFBInputStream(input), 4);
            dumpBuff.append("% File format detected: PFB\n");
        } else {
            // The file is not necessarily a hexadecimal PFA file.
            // It could be also a binary file without PFB headers.
            // dumpBuff.append("% File format detected: PFA\n");
        }

        int loopCounter = 0;

        while (true) {
            String tok = nextToken();

            if (tok == null) {
                // Unexpected end. This typically happens if we fail to read the
                // CharStrings section that would be the desired end of the font.
                dumpBuff.append("Error: Unexpected end of stream");
                errorsFound = true;
                break;
            }

            if (tok.equals("/FontMatrix")) {
                readFontMatrix();
            } else if (tok.equals("/Encoding")) {
                readEncoding();
            } else if (tok.equals("eexec")) {
                startEexec();
            } else if (tok.equals("/lenIV")) {
                read_lenIV();
            } else if (tok.equals("/Private")) {
                privateFound = true;
            } else if (tok.equals("/Subrs")) {
                readSubroutines();
            } else if (tok.equals("/WeightVector")) {
                readWeightVector();
            } else if (tok.equals("/FontBBox")) {
                readInfoEntry(tok, true);
            } else if (tok.equals("/PaintType")) {
                readInfoEntry(tok, false);
            } else if (tok.equals("/StrokeWidth")) {
                readInfoEntry(tok, false);
            } else if (tok.equals("/FontName")) {
                readInfoEntry(tok, false); // value is a name object
            } else if (tok.equals("/FontInfo")) {
                readFontInfoDict();
            } else if (tok.equals("/BlueFuzz")) {
                readPrivateEntry(tok, false);
            } else if (tok.equals("/BlueScale")) {
                readPrivateEntry(tok, false);
            } else if (tok.equals("/BlueShift")) {
                readPrivateEntry(tok, false);
            } else if (tok.equals("/BlueValues")) {
                readPrivateEntry(tok, true);
            } else if (tok.equals("/ExpansionFactor")) {
                readPrivateEntry(tok, false);
            } else if (tok.equals("/FamilyBlues")) {
                readPrivateEntry(tok, true);
            } else if (tok.equals("/FamilyOtherBlues")) {
                readPrivateEntry(tok, true);
            } else if (tok.equals("/ForceBold")) {
                readPrivateEntry(tok, false);
            } else if (tok.equals("/LanguageGroup")) {
                readPrivateEntry(tok, false);
            } else if (tok.equals("/OtherBlues")) {
                readPrivateEntry(tok, true);
            } else if (tok.equals("/RndStemUp")) {
                readPrivateEntry(tok, false);
            } else if (tok.equals("/StdHW")) {
                readPrivateEntry(tok, true);
            } else if (tok.equals("/StdVW")) {
                readPrivateEntry(tok, true);
            } else if (tok.equals("/StemSnapH")) {
                readPrivateEntry(tok, true);
            } else if (tok.equals("/StemSnapV")) {
                readPrivateEntry(tok, true);
            } else if (tok.equals("/UniqueID")) {
                readPrivateEntry(tok, false);
            } else if (tok.equals("/XUID")) {
                readPrivateEntry(tok, true);
            } else if (tok.equals("/CharStrings")) {
                if (readCharStrings()) {
                    // The Type1 font ends when the CharStrings section ends because
                    // "No assignments may occur in the Type 1 font program after the CharStrings
                    // dictionary is completed", as stated in the Type1 specification, section 10.
                    break;
                }
            }
            // else if (tok.equals("closefile")) {
            //    It's a bad idea to use the token "closefile" as the end of the font.
            //    Some fonts contain arbitrary PS code that includes the token closefile.
            //    That causes a premature end of font.
            //    break;
            // }

            // All other tokens are ignored.

            loopCounter++;
            if (loopCounter > 5000000) {
                throw new RuntimeException("Font parser in infinite loop");
            }
        }

        dumpBuff.append(SECTION_DIVIDER).append("Other Dictionary Entries:\n");
        dumpBuff.append(otherBuff).append("\n\n");
        dumpBuff.append(SECTION_DIVIDER).append("Private Dictionary Entries:\n");
        dumpBuff.append(privateDictBuff).append("\n\n");
        dumpBuff.append(SECTION_DIVIDER).append("Summary:\n");

        if (charStringsCount == 0) {
            dumpBuff.append(
                "  Warning: Type1 font has no CharStrings. Parsing has probably failed?\n");
        } else {
            dumpBuff.append("  ").append(charStringsCount).append(" entries in CharStrings.\n");
        }

        if (fontMatrixFound == 0) {
            dumpBuff.append("  Warning: FontMatrix was not found.\n");
        } else if (fontMatrixFound > 1) {
            dumpBuff.append("  Warning: More than one FontMatrix entries were found.\n");
        }

        if (subrsCount == 0) {
            dumpBuff.append("  Subrs was not found.\n"); // some fonts have no Subrs
        } else {
            dumpBuff.append("  ").append(subrsCount).append(" subroutines in Subrs array.\n");
        }

        if (encodingSectionsCount > 1) {
            dumpBuff.append("  Warning: More than one Encoding sections were found.\n");
        }

        if (hasStandardEncoding && encodingCodeCount > 0) {
            dumpBuff.append("  Warning: Both StandardEncoding and custom encoding were found.\n");
        } else if (hasStandardEncoding) {
            dumpBuff.append("  Encoding is StandardEncoding.\n");
        } else if (encodingCodeCount == 0) {
            dumpBuff.append("  Warning: Encoding was not found.\n");
        } else {
            dumpBuff.append("  ").append(encodingCodeCount).append(" codes in Encoding.\n");
        }

        if (weightVectorFound) {
            dumpBuff.append(
                "  This seems to be a MultipleMaster font because WeightVector was found.\n");
        }

        dumpBuff.append("\n\n");

        // The Type1 spec says:
        // "All font dictionary assignments (except for CharStrings and Private) must take
        // place before the first occurrence of the keyword /Private."
        // We use the variable privateFound to tell if the keyword /Private has been found.
        // If privateFound is true, we no longer accept entries Encoding and FontMatrix.
    }

    /**
     * Parses one complete charstring.
     */
    private void parseCharString(byte[] charString)
    {
        dumpBuff.append(CHAR_STRING_INDENT);

        int[] r = { 4330 }; // decryption key
        int len = charString.length;
        if (len <= lenIV) {
            return;
        }

        // The first lenIV bytes are garbage. Also those bytes must be processed
        // with the decryption algorithm to initialize it properly.
        for (int i = 0; i < lenIV; i++) {
            getCharstringByte(charString, i, r);
        }

        int i = lenIV, b;

        while (i < len) {
            b = getCharstringByte(charString, i, r);
            if (b >= 32) {
                i += parseNumber(b, charString, i, r);
            } else if (b == 12) {
                // Escaped two-byte command.
                b = getCharstringByte(charString, i + 1, r);
                if (executeEscapedCommand(b)) {
                    break;
                }
                dumpBuff.append(CHAR_STRING_INDENT);
                i += 2;
            } else {
                if (executeCommand(b)) {
                    break;
                }
                dumpBuff.append(CHAR_STRING_INDENT);
                i++;
            }
        }
    }

    private int getCharstringByte(byte[] charString, int i, int[] decrKey)
    {
        // We must be able to parse also subroutine charstrings in the middle of a glyph's
        // charstring when operator callsubr invokes a subroutine. Even nested calls are
        // allowed. Note the special solutions:
        // - Decryption key and charstring (byte[]) are not instance variables of the
        //   class. Instead, they are passed as arguments to each parse method.
        // - No instance variable stores the encyption status.

        // If you call this method more than once with the same value of i,
        // the decryption process fails and generates garbage for the rest of the charstring.

        int cipher = charString[i] & 0xFF;

        if (useCharStringDecryption) {
            int r = decrKey[0];
            int plain = cipher ^ (r >> 8);
            r = ((cipher + r) * 52845 + 22719) & 0xFFFF;
            decrKey[0] = r;
            return plain & 0xFF;
        } else {
            return cipher;
        }
    }

    /**
     * Parses a charstring number.
     * The decoding results are printed in the dump output.
     * The returned value tells how many charstring bytes the number consumes in total.
     * v must be >= 32 because values <= 31 are commands, not numbers.
     */
    private int parseNumber(int v, byte[] charString, int i, int[] r)
    {
        int num, incr;

        if (v <= 246) {
            // 32...246
            num = v - 139;
            incr = 1;
        } else if (v <= 250) {
            // 247...250
            int w = getCharstringByte(charString, i + 1, r);
            num = (v - 247) * 256 + w + 108;
            incr = 2;
        } else if (v <= 254) {
            // 251...254
            int w = getCharstringByte(charString, i + 1, r);
            num = -256 * (v - 251) - w - 108;
            incr = 2;
        } else if (v == 255) {
            num = 0;
            i++;
            for (int j = 3; j >= 0; j--) {
                int w = getCharstringByte(charString, i++, r);
                num |= w << (8 * j);
            }
            incr = 5;
        } else {
            throw new RuntimeException("Invalid Type1 number: first byte is " + v);
        }

        lastCharstringNumber = num;
        dumpBuff.append(num);
        dumpBuff.append(' ');
        return incr;
    }

    private boolean executeCommand(int cmd)
    {
        // Returns true if command is "endchar", "return", or unrecognized.

        switch (cmd) {
            case 1:
                // |- <y> <dy> hstem |-
                dumpBuff.append("hstem\n");
                break;
            case 3:
                // |- <x> <dx> vstem |-
                dumpBuff.append("vstem\n");
                break;
            case 4:
                // |- <dy> vmoveto |-
                dumpBuff.append("vmoveto\n");
                break;
            case 5:
                // |- <dx> <dy> rlineto |-
                dumpBuff.append("rlineto\n");
                break;
            case 6:
                // |- <dx> hlineto |-
                dumpBuff.append("hlineto\n");
                break;
            case 7:
                // |- <dy> vlineto |-
                dumpBuff.append("vlineto\n");
                break;
            case 8:
                // |- <dx1> <dy1> <dx2> <dy2> <dx3> <dy3> rrcurveto |-
                dumpBuff.append("rrcurveto\n");
                break;
            case 9:
                // - closepath |-
                dumpBuff.append("closepath\n");
                break;
            case 10:
                // <subr#> callsubr -
                dumpBuff.append("callsubr\n");
                break;
            case 11:
                // - return -
                dumpBuff.append("return\n");
                return true;
            // case 12:
                // escape, two-byte commands
                // break;
            case 13:
                // |- <sbx> <wx> hsbw |-
                dumpBuff.append("hsbw  % glyph width = ").append(lastCharstringNumber)
                    .append('\n');
                break;
            case 14:
                // - endchar |-
                dumpBuff.append("endchar\n");
                return true;
            case 15:
                // Command 15 is obsolete and undocumented. It may be found in some very
                // old Adobe fonts.
                dumpBuff.append("Command_15\n");
                usesCommand15 = true;
                break;
            case 21:
                // |- <dx> <dy> rmoveto |-
                dumpBuff.append("rmoveto\n");
                break;
            case 22:
                // |- <dx> hmoveto |-
                dumpBuff.append("hmoveto\n");
                break;
            case 30:
                // |- <dy1> <dx2> <dy2> <dx3> vhcurveto |-
                dumpBuff.append("vhcurveto\n");
                break;
            case 31:
                // |- <dx1> <dx2> <dy2> <dy3> hvcurveto |-
                dumpBuff.append("hvcurveto\n");
                break;
            default:
                dumpBuff.append("Unknown_Command_").append(cmd).append("  % Error!\n");
                errorsFound = true;
                return true;
        }

        return false;
    }

    private boolean executeEscapedCommand(int cmd)
    {
        // Returns true if the command is "seac" or unrecognized.

        switch (cmd) {
            case 0:
                // - dotsection |-
                dumpBuff.append("dotsection\n");
                usesDotsection = true;
                break;
            case 1:
                // |- <x0> <dx0> <x1> <dx1> <x2> <dx2> vstem3 |-
                dumpBuff.append("vstem3\n");
                usesStem3 = true;
                break;
            case 2:
                // |- <y0> <dy0> <y1> <dy1> <y2> <dy2> hstem3 |-
                dumpBuff.append("hstem3\n");
                usesStem3 = true;
                break;
            case 6:
                // |- <asb> <adx> <ady> <bchar> <achar> seac |-
                dumpBuff.append("seac\n");
                usesSeac = true;
                return true;
            case 7:
                // |- <sbx> <sby> <wx> <wy> sbw |-
                dumpBuff.append("sbw\n");
                usesSbw = true;
                break;
            case 12:
                // <num1> <num2> div <quotient>
                dumpBuff.append("div\n");
                usesDiv = true;
                break;
            case 16:
                // <arg1> ... <argn> <n> <otherSubr#> callothersubr -
                dumpBuff.append("callothersubr");
                explainOtherSubr(lastCharstringNumber);
                break;
            case 17:
                // - pop <num>
                // Moves a number object from PS interpreter stack to Type1 stack.
                dumpBuff.append("pop\n");
                break;
            case 33:
                // |- <x> <y> setcurrentpoint |-
                dumpBuff.append("setcurrentpoint\n");
                break;
            default:
                dumpBuff.append("Unknown_Command_12_").append(cmd).append("  % Error!\n");
                errorsFound = true;
                return true;
        }

        return false;
    }

    private void explainOtherSubr(int otherSubr)
    {
        switch (otherSubr) {
            case 0:
                dumpBuff.append("  % End of flex\n");
                break;
            case 1:
                dumpBuff.append("  % Flex begins\n");
                break;
            case 2:
                dumpBuff.append("  % Add flex point\n");
                break;
            case 3:
                dumpBuff.append("  % Hint replacement\n");
                break;
            case 12:
            case 13:
                dumpBuff.append("  % Counter control\n");
                break;
            case 14:
            case 15:
            case 16:
            case 17:
            case 18:
                dumpBuff.append("  % MultipleMaster interpolation\n");
                break;
            default:
                dumpBuff.append("  % operands: <arg1> ... <argn> <n> <otherSubrNo>\n");
                break;
        }
    }

    private void addEncodingEntry(String codeStr, String glyphName)
    {
        int code = parseInt(codeStr);

        if (glyphName.startsWith("/")) {
            // For example, "/A", "/space", or "/".
            // Some Type1 fonts specify undefined glyphs using lines like this:
            // dup 141 / put
            // Leave glyphName as it is, even if it is just "/".
            dumpBuff.append("    ").append(code).append(": ").append(glyphName).append('\n');

        } else {
            dumpBuff.append("    ").append(code).append(": ").append(glyphName)
                .append("  % Error: Invalid glyph name\n");
            errorsFound = true;
        }
    }

    private void startEexec()
    {
        // From now on, we are reading the eexec-encrypted section.
        // Note that EexecInputStream won't handle this whitespace.
        // Read comments in EexecInputStream.

        InputStream is = input;
        is = new EexecInputStream(is);
        input = new PushbackInputStream(is, 4);
    }

    private void clearStringBuff()
    {
        stringBuff.setLength(0);
    }

    private void unread(int b)
    {
        try {
            input.unread(b);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    private int parseInt(String str)
    {
        try {
            int hashIdx = str.indexOf('#');
            if (hashIdx == 1 || hashIdx == 2) {
                // It's a radix number, like 8#40.
                int radix = Integer.parseInt(str.substring(0, hashIdx));
                return Integer.parseInt(str.substring(hashIdx + 1), radix);
            } else {
                return Integer.parseInt(str);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Invalid integer '" + str + "'");
        }
    }

    private boolean isPositiveIntToken(String str)
    {
        if (str == null || str.isEmpty()) {
            return false;
        }

        for (int i = 0, len = str.length(); i < len; i++) {
            char c = str.charAt(i);
            if (c == '#' || ('0' <= c && c <= '9')) {
                // OK
            } else {
                return false;
            }
        }

        return true;
    }

    private String nextToken()
    {
        // Reads the next token from the input stream using PostScript parsing rules.

        // PS strings are not parsed (see comments below).
        // Numbers are returned as Strings too.
        // PS comments are skipped altogether and not returned as tokens.
        // Literal name objects will have a leading '/' when returned.

        boolean readMore = true;
        char ch, ch2 = ' ';
        String token = null;

        while (readMore) {
            ch = readChar();
            if (ch == EOF_CHAR) {
                break;
            }
            if (isWhitespace(ch)) {
                continue;
            }
            switch (ch) {
                case '%':
                    skipLineComment();
                    break;
                case '(':
                    // skipPsString(); // It's better not to read... Read comments below.
                    token = "(";
                    break;
                case '<':
                    ch2 = readChar();
                    if (ch2 == EOF_CHAR) {
                        readMore = false;
                        break;
                    }
                    if (ch2 == '>') {
                        // Empty hex string.
                        token = "( )";
                    } else if (ch2 == '<') {
                        token = "<<";
                    } else {
                        // skipHexString(ch2); // It's better not to read... Read comments below.
                        token = "<";
                        // unread(ch2);
                    }
                    break;
                case '>':
                    ch2 = readChar();
                    if (ch2 == EOF_CHAR) {
                        readMore = false;
                        break;
                    }
                    if (ch2 == '>') {
                        token = ">>";
                    } else {
                        unread(ch2);
                        token = ">";
                    }
                    break;
                case '[':
                    token = "[";
                    break;
                case ']':
                    token = "]";
                    break;
                case '{':
                    token = "{";
                    break;
                case '}':
                    token = "}";
                    break;
                case '/':
                    // A literal name object.
                    ch2 = readChar();
                    if (ch2 == EOF_CHAR) {
                        readMore = false;
                        break;
                    }
                    if (isWhitespace(ch2) || isSelfDelimAfterToken(ch2)) {
                        // It's a zero-length literal name object /.
                        token = "/";
                        if (isSelfDelimAfterToken(ch2)) {
                            unread(ch2);
                        }
                    } else {
                        // No need to unread ch2 since parseToken() can include ch2 in the token.
                        token = parseToken(true, ch2, true);
                    }
                    break;
                default:
                    // A number or an executable PS name object.
                    token = parseToken(true, ch, false);
                    break;
            }

            if (token != null) {
                return token;
            }
        }

        // return ""; // This will cause infinite loops!

        return null;
        // throw new RuntimeException("Tokenization failed, possibly an unexpected end of data");

        // It's better not to read complete string and hex string objects. We had
        // a problem with a font that has two Subrs sections.
        // This class will only read the first one of them. The second one will be skipped
        // token-by-token in parseType1Font(). Unfortunately the Subrs section that we wanted
        // to skip contained many '(' and '<' chars in the binary subroutine data. Thus nextToken()
        // considered them as starts of strings. They were unbalanced, so string parser methods
        // continued to the following CharStrings section to find a matching ')' or '>'.
        // Thus also the token /CharStrings was consumed by the string parsers and we
        // never encountered /CharStrings. As a result, no charstrings were loaded
        // and the font remained empty.

        // Similarly skipped Subrs sections may contain '%' chars but they should not be
        // a problem because the comment ends when there is a newline at the end of each
        // subroutine definition.
        // Note that we MUST skip comment lines. Some fonts contain comments in a Subrs
        // section. We fail to read the section if nextToken() does not skip all comments
        // automatically.
    }

    private void skipLineComment()
    {
        // The '%' must have been just read.
        // If the comment line ends with a DOS linebreak "\r\n", this
        // will not consume the '\n' character.

        while (true) {
            char ch = readChar();
            if (ch == '\n' || ch == '\r' || ch == EOF_CHAR) {
                break;
            }
        }
    }

    private char readChar()
    {
        // This returns EOF_CHAR if the file ends.

        try {
            int b = input.read();
            if (b == -1) {
                return EOF_CHAR;
            }
            // We clear the upper 8 bits, so we can't accidentally return EOF_CHAR.
            // Consequently this method can read only 8-bit ASCII characters.
            return (char)(b & 0xFF);

        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    private String parseToken(boolean includeCh0, char ch0, boolean isLiteralName)
    {
        clearStringBuff();
        if (isLiteralName) {
            stringBuff.append('/');
        }
        if (includeCh0) {
            stringBuff.append(ch0);
        }

        while (true) {
            char ch = readChar();
            if (ch == EOF_CHAR) {
                return stringBuff.toString();
            }
            if (isWhitespace(ch)) {
                // No need to unread.
                break;
            } else if (isSelfDelimAfterToken(ch)) {
                unread(ch);
                break;
            } else {
                stringBuff.append(ch);
            }
        }

        return stringBuff.toString();
    }

    private float[] readPsNumberArray(int minLen, int maxLen, boolean strict)
    {
        // If this fails, `strict` specifies whether a RuntimeException is thrown
        // or null is returned. This requires that the array length is between
        // `minLen` and `maxLen` (both inclusive).

        String tok = nextToken();

        if (tok != null && (tok.equals("[") || tok.equals("{"))) {
            // OK
        } else {
            if (strict) {
                throw new RuntimeException("Cannot find start of array");
            }
            return null;
        }

        int numElems = 0;
        float[] arr = new float[maxLen];

        for (int i = 0; ; i++) {
            tok = nextToken();
            if (tok == null) {
                if (strict) throw new RuntimeException("Token is null");
                return null;
            }
            if (tok.equals("]") || tok.equals("}")) {
                // End of array
                break;
            }
            if (i >= maxLen) {
                if (strict) throw new RuntimeException("Array length too large");
                return null;
            }
            try {
                arr[i] = (float)Double.parseDouble(tok);
                numElems++;
            } catch (NumberFormatException nfe) {
                if (strict) {
                    throw new RuntimeException("Invalid number in array: " + tok);
                }
                return null;
            }
        }

        if (numElems < minLen) {
            if (strict) {
                throw new RuntimeException("Array length too small");
            }
            return null;
        }
        if (numElems < arr.length) {
            arr = Arrays.copyOf(arr, numElems); // remove unused elements
        }
        return arr;
    }

    private void readInfoEntry(String key, boolean isArray)
    {
        // This writes to otherBuff.

        readAndAppendArrayOrToken(key, isArray, otherBuff);
    }

    private void readPrivateEntry(String key, boolean isArray)
    {
        // This is for Private entries like BlueValues and OtherBlues.
        // This writes to privateDictBuff.

        if (!privateFound) {
            return;
        }
        readAndAppendArrayOrToken(key, isArray, privateDictBuff);
    }

    private void readAndAppendArrayOrToken(String key, boolean isArray, StringBuilder out)
    {
        // This writes to the specified StringBuilder.
        // If `isArray` is true, entry's value must be an array.
        // Otherwise the value must be a single PS toke, like a number or a boolean value.

        int len0 = out.length();
        out.append("  ");

        if (key.startsWith("/")) {
            out.append(key, 1, key.length());
        } else {
            out.append(key);
        }
        out.append(": ");

        if (isArray) {
            char ch0 = startPsArray();
            if (ch0 == EOF_CHAR) {
                out.setLength(len0);
                return;
            }
            dumpPsArray(ch0, out);
        } else {
            String tok = nextToken();
            out.append(tok);
        }

        out.append('\n');
    }

    private void dumpNextToken()
    {
        String tok = nextToken();
        dumpBuff.append(tok);
    }

    private void dumpNextArray()
    {
        char ch0 = startPsArray();
        if (ch0 != EOF_CHAR) {
            dumpPsArray(ch0, dumpBuff);
        }
    }

    private void readFontMatrix()
    {
        // Parses a line like "/FontMatrix [0.001 0 0 0.001 0 0]readonly def"
        // The token "/FontMatrix" must have been just read.

        if (privateFound) {
            return;
        }

        try {
            int len0 = dumpBuff.length();
            dumpBuff.append(SECTION_DIVIDER).append("FontMatrix:\n    ");

            // float[] arr = readPsNumberArray(6, 6, true);
            // dumpNumbersArray(arr);

            char ch0 = startPsArray();
            if (ch0 == EOF_CHAR) {
                dumpBuff.setLength(len0);
                return;
            }
            dumpPsArray(ch0, dumpBuff);

            dumpBuff.append("\n\n");
            fontMatrixFound++;

        } catch (Exception ex) {
            dumpBuff.append("Error: " + ex.toString() + " while parsing FontMatrix");
            errorsFound = true;
        }
    }

    private char startPsArray()
    {
        // Returns EOF_CHAR if the next token does not start an array.

        String tok = nextToken();

        if (tok != null && (tok.equals("[") || tok.equals("{"))) {
            return tok.charAt(0);
        } else {
            return EOF_CHAR;
            // throw new RuntimeException("Cannot find start of array");
        }
    }

    private void dumpPsArray(char startChar, StringBuilder out)
    {
        // The array should have started with a '[' or '{', which is specified
        // as the argument startChar. Thus the opening bracket or brace must have
        // been already read.

        out.append(startChar);

        while (true) {
            char ch = readChar();
            if (ch == EOF_CHAR) {
                throw new RuntimeException("Unexpected end of file while reading PS array");
            }
            out.append(ch); // append also right bracket or brace
            if (ch == ']' || ch == '}') {
                break;
            }
        }
    }

    private void dumpNestedPsArray(StringBuilder out)
    {
        // This can dump nested PS arrays.
        // Only brackets are allowed, not braces.
        // No opening bracket must have been read before calling this.

        int bracketCount = 0;

        while (true) {
            char ch = readChar();
            if (ch == EOF_CHAR) {
                throw new RuntimeException("Unexpected end of file while reading PS array");
            }
            out.append(ch);
            if (ch == '[') {
                bracketCount++;
            } else if (ch == ']') {
                bracketCount--;
                if (bracketCount == 0) {
                    break;
                }
            }
        }
    }


    private void read_lenIV()
    {
        // Parses a line like "/lenIV 4 def"
        // The token "/lenIV" must have been just read.
        // Some fonts could have e.g. /lenIV 0 def

        if (!privateFound) {
            return;
        }

        try {
            String tok = nextToken();
            lenIV = parseInt(tok);
            privateDictBuff.append("  lenIV: ").append(lenIV).append('\n');

        } catch (Exception ex) {
            privateDictBuff.append("Error while parsing lenIV: ")
                .append(ex.toString()).append(". Using 4 as lenIV.\n");
            errorsFound = true;
            lenIV = 4; // hopefully this default is OK
        }

        if (lenIV < 0) {
            useCharStringDecryption = false;
            lenIV = 0;
        }
    }

    private void readEncoding()
    {
        // The token "/Encoding" must have been just read.

        if (privateFound) {
            return;
        }

        dumpBuff.append(SECTION_DIVIDER).append("Encoding:\n");
        if (encodingSectionsCount > 0) {
            dumpBuff.append("  Warning. More than one Encoding sections found.\n");
        }
        encodingSectionsCount++;

        try {
            String tok1 = nextToken();
            String tok2 = nextToken();
            if ("StandardEncoding".equals(tok1) && "def".equals(tok2)) {
                dumpBuff.append("    StandardEncoding\n");
                hasStandardEncoding = true;
                return;
            }

            // Skip to the first "dup" token (see comments below)
            // if (!findForward("dup", 300, input)) {
            //    throw new Exception("Failed to find start of Encoding");
            // }
            // That won't work, so instead...

            if (!skipByTokenizing("dup",   "def", "readonly", null)) {
                // End of Encoding. No "dup" lines were found.
                dumpBuff.append("    Info: Encoding is empty");
                return;
            }

            dumpBuff.append("    (<code>: /<glyphName>)\n");
            boolean isFirst = true;

            while (true) {
                String tok;
                if (isFirst) {
                    // We already consumed the first token "dup" above.
                    tok = "dup";
                    isFirst = false;
                } else {
                    tok = nextToken();
                }

                if (tok.equals("def") || tok.equals("readonly")) {
                    // End of Encoding
                    break;
                }
                // Otherwise it must be "dup"
                if (!tok.equals("dup")) {
                    throw new Exception("Failed to read Encoding 'dup' line");
                }

                String codeStr = nextToken();
                String glyphName = nextToken();
                addEncodingEntry(codeStr, glyphName);
                encodingCodeCount++;

                tok = nextToken();
                if (!tok.equals("put")) {
                    throw new Exception("Failed to read Encoding 'put' line");
                }
            }

        } catch (Exception ex) {
            dumpBuff.append("Error: " + ex.toString() + " while parsing Encoding");
            errorsFound = true;
        }

        // A typical Encoding is defined like this:
        //     /Encoding 256 array
        //     0 1 255 {1 index exch /.notdef put} for
        //     dup 65 /A put
        //     dup 66 /B put
        //     dup 67 /C put
        //     ...
        //     readonly def
        // Also this is acceptable:
        //     /Encoding StandardEncoding def

        // About 'Skip to the first "dup" token':
        // Even though the Type1 spec says ATM skips to the first dup token, we
        // can't do it like that. This is why:
        // Some fonts define an Encoding array that contains only /.notdef names like this:
        //     /Encoding 256 array
        //     0 1 255 {1 index exch /.notdef put} for
        //     readonly def
        // This works only if the PDF Font dict specifies an Encoding.
        // Note that there are no "dup" tokens, so we can't skip to the first "dup"
        // using findForward(). Instead, we try to find "dup" by using skipByTokenizing().
    }

    private void readSubroutines()
    {
        // The token "/Subrs" must have been just read.

        // Subrs should begin like this: "/Subrs 21 array"
        // Syntax of subroutine definitions:
        //     dup 7 23 RD ~23~binary~bytes~ NP
        // Alternatively, RD can be replaced with -| and NP can be replaced with | or noaccess put.
        // We can't read the subroutines as text lines because they contain binary data.

        int numSubrs;

        try {
            String tok = nextToken();
            if (!isPositiveIntToken(tok)) {
                // Maybe not an actual Subrs section.
                return;
            }
            numSubrs = parseInt(tok);
        } catch (Exception ex) {
            dumpBuff.append("Error: " + ex.toString() + " while parsing Subrs");
            errorsFound = true;
            return;
        }

        dumpBuff.append(SECTION_DIVIDER).append("Subroutines (Subrs):\n\n");

        // A weird font had no whitespace before the token /Subrs:
        // hires{userdict/fsmkr save put}if/Subrs 92 array

        if (subrsCount > 0) {
            dumpBuff.append("Warning: Type1 font has more than one Subrs sections. " +
                "Ignoring all but the first one.");
            // Some weird fonts have two Subrs sections. Acrobat Reader seems
            // to ignore the latter one. Thus return.
            return;
        }

        if (numSubrs < 0 || numSubrs > 65535) {
            return;
        }
        if (numSubrs < 1) {
            // Some fonts say "/Subrs 0 array"
            return;
        }

        // Note: There may actually be less subr definitions than numSubrs.
        // Additionally, we can't assume the subroutines appear numbered sequentially.

        if (!skipByTokenizing("dup", ND, ND_ALT, "noaccess")) {
            // End of Subrs. No "dup" lines were found.
            dumpBuff.append("Info: Subrs is empty in Type1 font ");
            return;
        }

        boolean isFirst = true;

        try {
            while (true) {
                String tok;

                if (isFirst) {
                    // skipByTokenizing() consumed the first "dup".
                    tok = "dup";
                    isFirst = false;
                } else {
                    tok = nextToken();
                }

                if (tok.equals(ND) || tok.equals(ND_ALT)) {
                    // End of Subrs section
                    break;
                }
                if (tok.equals("noaccess")) {
                    // Also "noaccess def" may end the Subrs section.
                    tok = nextToken();
                    if (tok.equals("def")) {
                        break;
                    }
                    throw new RuntimeException(
                        "Invalid Subrs end sequence 'noaccess " + tok + "'");
                }

                // Skipping comments is not needed here because nextToken() will automatically
                // skip all comments and won't return them as tokens. Some Type1 fonts
                // contain PostScript comments in the Subrs section. They must be skipped.
                // if (tok.startsWith("%")) {
                //     skipLineComment();
                //     continue;
                // }

                if (!tok.equals("dup")) {
                    throw new RuntimeException(
                        "Failed to read subroutine, expected 'dup', found '" + tok + "'");
                }

                int subrIdx = parseInt( nextToken() );
                int binLen = parseInt( nextToken() );

                tok = nextToken();
                if (tok.equals(RD) || tok.equals(RD_ALT)) {
                    // OK
                } else {
                    throw new RuntimeException(
                        "Invalid subroutine start token '" + tok + "', expected 'RD' or '-|'");
                }

                byte[] subr = new byte[binLen];
                readFully(input, subr);
                subrsCount++;
                if (dumpCharstrings) {
                    dumpBuff.append('[').append(subrIdx).append("]:");
                    if (subrIdx < 0 || subrIdx >= numSubrs) {
                        dumpBuff.append("  % Error: subrNo out of range. Must be 0 <= subrno < ")
                            .append(numSubrs);
                        errorsFound = true;
                    }
                    dumpBuff.append('\n');
                    parseCharString(subr);
                    dumpBuff.append('\n');
                }

                tok = nextToken();
                if (tok.equals(NP) || tok.equals(NP_ALT)) {
                    // OK
                } else if (tok.equals("noaccess")) {
                    // Expecting "noaccess put". Read the token "put":
                    tok = nextToken();
                    if (tok.equals("def")) {
                        // End of Subrs section
                        break;
                    }
                    if (tok.equals("put")) {
                        // OK, end of subroutine
                    } else {
                        throw new RuntimeException("Invalid subroutine end sequence 'noaccess " +
                            tok + "'");
                    }
                } else {
                    throw new RuntimeException("Invalid subroutine end token '" + tok + "'");
                }
            }

        } catch (Exception ex) {
            dumpBuff.append("Error: " + ex.toString() + " while parsing Subrs");
            errorsFound = true;
        }

        // Each subroutine definition should end with a token "NP" or "|". However, some
        // fonts contain the sequence "noaccess put".
    }

    private boolean readCharStrings()
    {
        // The token "/CharStrings" must have been just read.
        //
        // Syntax of CharStrings definitions:
        //   /comma 29 RD ~29~binary~bytes~ ND
        // Alternatively, RD can be replaced with -| and ND can be replaced with |-
        // or noaccess def. We can't read the CharStrings as text lines because they contain
        // binary data.
        //
        // An example of a CharStrings section that has 3 glyphs:
        //   2 index /CharStrings 3 dict dup begin
        //   /.notdef 9 RD ~9~binary~bytes~ ND
        //   /Alpha 186 RD ~186~binary~bytes~ ND
        //   /Beta 78 RD ~78~binary~bytes~ ND
        //   end

        // if (charStringsCount > 0) {
        //     // This never happens because we stop the font parsing after the first
        //     // CharStrings section is done.
        //     dumpBuff.append("Warning: Type1 font has more than one CharStrings sections.");
        //     return false;
        // }

        try {
            // Find the first sequence "</glyphname> <integer> RD" (or "-|" instead of "RD").
            // When we have found "RD" or "-|", the variables firstGlyphName and intToken
            // will contain the tokens </glyphname> and <integer>.
            String firstGlyphName = null, intToken = null;
            while (true) {
                String tok = nextToken();
                if (tok == null) {
                    return true; // error
                }
                if (tok.equals("end")) {
                    // Error. CharStrings should contain at least the /.notdef glyph.
                    dumpBuff.append("Warning: CharStrings is empty");
                    return true;
                }
                if (tok.startsWith("/")) {
                    // Possibly the glyph name
                    firstGlyphName = tok;
                } else if (isPositiveIntToken(tok)) {
                    // Possibly the integer before "RD"
                    intToken = tok;
                } else if (tok.equals(RD) || tok.equals(RD_ALT)) {
                    // We found the first charstring
                    break;
                }
                // else ignore all other tokens
            }
            if (firstGlyphName == null || intToken == null) {
                dumpBuff.append("Warning: Failed to find start of first charstring");
                return false;
            }

            dumpBuff.append(SECTION_DIVIDER).append("CharStrings:\n\n");
            boolean isFirst = true;

            while (true) {
                int binLen;
                String glyphName;

                if (isFirst) {
                    isFirst = false;
                    binLen = parseInt(intToken);
                    glyphName = firstGlyphName;
                    if (glyphName.startsWith("/")) {
                        // Remove '/' from start. See comment "empty glyph names" below.
                        glyphName = glyphName.substring(1);
                    } else {
                        throw new RuntimeException("Invalid glyph name '" +
                            escapeJavaString(glyphName) + "' in CharStrings");
                    }
                } else {
                    String tok = nextToken();
                    if (tok.equals("end")) {
                        // End of CharStrings
                        break;
                    }

                    if (tok.startsWith("/")) {
                        // Remove '/' from start. See comment "empty glyph names" below.
                        glyphName = tok.substring(1);
                    } else {
                        throw new RuntimeException("Invalid glyph name '" +
                            escapeJavaString(tok) + "' in CharStrings");
                    }

                    binLen = parseInt( nextToken() );

                    tok = nextToken();
                    if (tok.equals(RD) || tok.equals(RD_ALT)) {
                        //OK
                    } else {
                        throw new RuntimeException("Invalid charstring start token '" +
                            tok + "', expected 'RD' or '-|'");
                    }
                }

                byte[] chStr = new byte[binLen];
                readFully(input, chStr);

                if (dumpCharstrings) {
                    dumpBuff.append('/').append(glyphName).append(":\n");
                    lastCharstringNumber = 0;
                    parseCharString(chStr);
                    dumpBuff.append('\n');
                }
                charStringsCount++;

                String tok = nextToken();
                if (tok != null && (tok.equals(ND) || tok.equals(ND_ALT))) {
                    // OK
                } else {
                    throw new RuntimeException("Invalid charstring end token: " + tok +
                        ", expected ND or |-");
                }
            }

        } catch (Exception ex) {
            dumpBuff.append("Error: " + ex.toString() + " while parsing CharStrings");
            errorsFound = true;
        }

        return true;

        // Empty glyph names:
        // Some Type1 fonts contain charstrings that have an empty glyph name.
        // We accept also this case when tok is "/". For example:
        //   / 47 RD ..... ND
        // What glyph name should we use now? It's best not to use .notdef because then
        // we might overwrite the actual .notdef glyph. (Note that charStrings is a Map,
        // so it cannot contain duplicate entries.) Otherwise this situation is the
        // same as the one when the glyph name "/" appears in the Encoding section;
        // see comments in addEncodingEntry().

        // Some Type1 fonts contain the token /CharStrings many times. For example:
        //     2 index /CharStrings 229 dict dup begin
        // But later there is also a line like:
        //     dup/CharStrings /Avenir-Book findfont/CharStrings get put
        // We can read this font because we stop font parsing when the first CharStrings
        // section is done. Thus we'll never encounter the latter /CharStrings tokens.

        // CharStrings section may start like this:
        //     dup/CharStrings hires{userdict/fsmkr save put}if
        //     229 dict dup begin
        // Thus there is no integer right after the token /CharStrings.

        // We tried to jump from token /CharStrings to the char '/' where the
        // first glyph name starts. But that didn't work in a font
        // in which "/" appears in token /fsmkr (see above). Instead, we try to jump to
        // the first charstring as shown above in.
    }

    private boolean skipByTokenizing(String findThisToken, String stop1, String stop2,
    String stop3)
    {
        // Tokenizes input until one of the arguments is found.
        // Returns true if `findThisToken` is found.
        // Returns false if `stop1`, `stop2`, or `stop3` is found.
        // `findThisToken` and `stop1` must be non-null.

        while (true) {
            String tok = nextToken();
            if (tok == null) {
                return false;
            }
            if (tok.equals(findThisToken)) {
                // We found it
                return true;
            }

            if (stop1 != null && tok.equals(stop1)) {
                return false;
            }
            if (stop2 != null && tok.equals(stop2)) {
                return false;
            }
            if (stop3 != null && tok.equals(stop3)) {
                return false;
            }
        }
    }

    private void readWeightVector()
    {
        // WeightVector specifies the coefficients for the default interpolated font
        // instance. If the MM font file is used as such, its WeightVector is used.
        // If some intelligent font manager is able to create actual MM instances
        // from the design values in font names (like AdobeSansMM_200_600_), then the
        // WeightVector of that instance would be different, as appropriate for
        // the requested instance.

        // For example: /WeightVector [0.31502 0.13499 0.38499 0.16499 ] def

        if (weightVectorFound) {
            // Already parsed
            return;
        }

        // Specify false for non-strict because we must not choke on PS code
        // lines like: /WeightVector get length eq
        float[] arr = readPsNumberArray(2, 16, false);

        if (arr == null) {
            return;
        }
        int k = arr.length; //number of masters

        dumpBuff.append(SECTION_DIVIDER).append("WeightVector:\n    ");
        dumpNumbersArray(arr);
        dumpBuff.append("\n    Thus this seems to be a MultipleMaster font with ")
            .append(k).append(" masters.\n");

        if (k == 2 || k == 4 || k == 8 || k == 16) {
            // OK
            weightVectorFound = true;
        } else {
            dumpBuff.append("Error: Type1 MultipleMaster font has ")
                .append(k).append(" masters (based on WeightVector). " +
                    "Allowed values are 2, 4, 8, and 16.");
            errorsFound = true;
        }
    }

    public boolean hasErrors()
    {
        return errorsFound;
    }

    private void readFully(InputStream is, byte[] arr) throws IOException
    {
        // Use this instead of InputStream.read(byte[]) to make sure the whole byte
        // array is filled all the way.
        // This is similar to RandomAccessFile.readFully(byte[]).

        int left = arr.length, off = 0;
        if (left <= 0) {
            return;
        }
        while (left > 0) {
            int n = is.read(arr, off, left);
            if (n <= 0) {
                throw new IOException("unexpected end of file");
            }
            off += n;
            left -= n;
        }
    }

    public static boolean isWhitespace(char c)
    {
        if (c <= 32) {
            return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\u0000' || c == '\f';
        }
        return false;
    }

    public static boolean isSelfDelimAfterToken(char c)
    {
        // The characters ()<>[]{}/% are special. They delimit syntactic entities
        // such as strings, procedure bodies, name literals, and comments. Any of these
        // characters terminates the entity preceding it and is not included in the entity.

        return c == '(' || c == '<' || c == '>' || c == '[' || c == ']' ||
               c == '{' || c == '}' || c == '/' || c == '%';

        // This checks for self delimiters appearing after tokens. Thus there is no
        // need to check for ')'. However, char '>' can appear in keyword >>, like
        // here: /Pages 2 0 R>>. So the char '>' must end the token R.
    }

    static String escapeJavaString(String str)
    {
        StringBuilder sb = new StringBuilder();
        int len = str.length();
        for (int i = 0; i < len; i++) {
            escapeJavaChar(str.charAt(i), sb, false);
        }
        return sb.toString();
    }

    static void escapeJavaChar(char c, StringBuilder sb, boolean forceHexEsc)
    {
        if (c < 32 || forceHexEsc) {
            switch (c) {
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\u0000':
                    sb.append("\\u0000");
                    break;
                default:
                    String hex = Integer.toHexString(c);
                    sb.append("\\u");
                    for (int k = hex.length(); k < 4; k++) {
                        sb.append('0');
                    }
                    sb.append(hex);
                    break;
            }
        } else {
            sb.append(c);
        }
    }

    public void enableDumpingCharstringsAndSubrs(boolean b)
    {
        dumpCharstrings = b;
    }

    public String getResult()
    {
        return dumpBuff.toString();
    }

    public String getSpecialFeatures()
    {
        StringBuilder s = new StringBuilder();

        if (weightVectorFound) {
            s.append("* This is a MultipleMaster font.\n");
        }
        if (usesStem3) {
            s.append("* Uses command 'hstem3' or 'vstem3'.\n");
        }
        if (usesSeac) {
            s.append("* Uses command 'seac'.\n");
        }
        if (usesDiv) {
            s.append("* Uses command 'div'.\n");
        }
        if (usesDotsection) {
            s.append("* Uses command 'dotsection'.\n");
        }
        if (usesSbw) {
            s.append("* Uses command 'sbw'.\n");
        }
        if (usesCommand15) {
            s.append("* Uses obsolete command 15.\n");
        }

        if (s.length() == 0) {
            return null;
        } else {
            s.insert(0, "Special features:\n");
            return s.toString();
        }
    }

    private void dumpNumbersArray(float[] arr)
    {
        dumpBuff.append('[');
        for (int i = 0, end = arr.length - 1; i <= end; i++) {
            dumpBuff.append(arr[i]);
            if (i < end) {
                dumpBuff.append(' ');
            }
        }
        dumpBuff.append(']');
    }

    private void readFontInfoDict()
    {
        if (fontInfoFound) {
            return;
        }
        fontInfoFound = true;
        dumpBuff.append(SECTION_DIVIDER).append("FontInfo:\n");
        skipByTokenizing("begin", "end", null, null);
        int loopCounter = 0;

        while (true) {
            String tok = nextToken();

            if (tok == null) {
                dumpBuff.append("Error: Unexpected end of stream while parsing FontInfo");
                errorsFound = true;
                break;
            }

            if (tok.equals("readonly") || tok.equals("def")) {
                // Ignore "readonly def"
            } else if (tok.equals("end")) {
                // End of FontInfo dict
                break;
            } else {
                int len0 = dumpBuff.length();
                boolean dumpThisEntry = true;
                dumpBuff.append("  ");
                if (tok.startsWith("/")) {
                    dumpBuff.append(tok, 1, tok.length());
                } else {
                    dumpBuff.append(tok);
                }
                dumpBuff.append(": ");

                if (tok.equals("/version")) {
                    dumpNextPsString(dumpBuff);
                } else if (tok.equals("/Notice")) {
                    dumpNextPsString(dumpBuff);
                } else if (tok.equals("/FullName")) {
                    dumpNextPsString(dumpBuff);
                } else if (tok.equals("/FamilyName")) {
                    dumpNextPsString(dumpBuff);
                } else if (tok.equals("/Weight")) {
                    dumpNextPsString(dumpBuff);
                } else if (tok.equals("/ItalicAngle")) {
                    dumpNextToken();
                } else if (tok.equals("/isFixedPitch")) {
                    dumpNextToken();
                } else if (tok.equals("/UnderlinePosition")) {
                    dumpNextToken();
                } else if (tok.equals("/UnderlineThickness")) {
                    dumpNextToken();
                } else if (tok.equals("/BlendDesignPositions")) {
                    dumpNestedPsArray(dumpBuff);
                } else if (tok.equals("/BlendDesignMap")) {
                    dumpNestedPsArray(dumpBuff);
                } else if (tok.equals("/BlendAxisTypes")) {
                    dumpNextArray();
                } else {
                    dumpThisEntry = false;
                }

                dumpBuff.append('\n');

                if (!dumpThisEntry) {
                    // Some fonts may have extra FontInfo entries that are not tested above.
                    // For example: /FSType 0 def
                    // We omit those from the dump output.
                    dumpBuff.setLength(len0);
                }
            }

            loopCounter++;
            if (loopCounter > 5000000) {
                throw new RuntimeException("Font parser in infinite loop while parsing FontInfo");
            }
        }

        dumpBuff.append('\n');
    }

    private void dumpNextPsString(StringBuilder out)
    {
        skipWhitespace();
        char ch = readChar();

        if (ch == '<') {
            dumpHexString(out);
            return;
        }

        // Now we have already read the opening parenthesis.
        int parenCount = 1;
        out.append('(');

        while (true) {
            ch = readChar();
            if (ch == EOF_CHAR) {
                break;
            }
            out.append(ch);
            switch (ch) {
                case '\\':
                    char c2 = readChar();
                    if (c2 == EOF_CHAR) {
                        throw new RuntimeException(
                            "Unexpected end of stream while reading string");
                    }
                    out.append(c2);
                    // c2 is the char following the backslash. Just skip it.
                    // This case '\\' is needed to distinguish between escaped and
                    // unescaped parentheses. The escaped ones must not change parenCount.
                    break;
                case '(':
                    // It's an unescaped opening parenthesis.
                    parenCount++;
                    break;
                case ')':
                    // It's an unescaped closing parenthesis.
                    parenCount--;
                    if (parenCount == 0) {
                        // End of PS string has been reached when we have found an equal number
                        // of opening and closing parentheses.
                        return;
                    }
                    break;
            }
        }

        // Note that escaped parenthesis characters \( and \) are not included in
        // the value of parenCount. This way we can read also PS strings that contain
        // balanced, unescaped parentheses. For example: (a(b)c).
    }

    private void dumpHexString(StringBuilder out)
    {
        // The opening '<' must have already been read.
        // This won't decode the hexadecimal data.

        out.append('<');
        while (true) {
            char ch = readChar();
            if (ch == EOF_CHAR) {
                throw new RuntimeException("Unexpected end of stream while reading hex string");
            }
            out.append(ch);
            if (ch == '>') {
                break;
            }
        }
    }

    private void skipWhitespace()
    {
        while (true) {
            char ch = readChar();
            if (ch == EOF_CHAR) {
                break;
            }
            if (!isWhitespace(ch)) {
                unread(ch);
                break;
            }
        }
    }

}
