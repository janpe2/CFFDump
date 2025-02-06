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

package cff;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.zip.InflaterInputStream;
import cff.io.Filters;

/**
 * Reads CFF (Compact Font Format) data and dumps it in ASCII form.
 * <p>
 * Dictionary structures are dumped using a simple PostScript syntax:
 * <ul>
 *   <li>&lt;&lt; and &gt;&gt; delimit a dictionary.
 *   <li>Parentheses ( ) delimit a string.
 *   <li>Brackets [ ] delimit an array.
 *   <li>Names (dictionary keys, glyph names) start with a slash /.
 *   <li>Comments start with a percent sign %. (Comments are not part of the CFF syntax
 *       but they provide helpful information about the data.)
 *   <li>Operands come first and then the operator. For example, {@code 110 -14 rmoveto}.
 *       However, dictionary entries are dumped like {@code /FontName (Untitled)} although
 *       the CFF format specifies them using the concept of operands and operators.
 * </ul>
 *
 * Nomenclature:
 * <dl>
 *   <dt>Base font, simple font</dt>
 *       <dd>font that uses glyph names to select glyphs</dd>
 *   <dt>CIDFont</dt>
 *       <dd>font that uses integer CIDs to select glyphs</dd>
 *   <dt>GID</dt>
 *       <dd>glyph index</dd>
 *   <dt>SID</dt>
 *       <dd>string identifier</dd>
 * </dl>
 *
 * In charstrings and subroutines, the dump shows numbers whose Type2 encoding has
 * been decoded, so the values are not raw byte values. An exception to this are
 * the mask bytes of operators {@code hintmask} and {@code cntrmask}, which are
 * dumped as raw bytes.
 * <p>
 * CFFDump can detect some errors in CFF data but not all. This class should not
 * be used as a CFF sanitizer tool.
 */

public class CFFDump
{
    private ByteBuffer input;
    private final StringBuilder sbMain = new StringBuilder(4096);
    private Stack<DictValue> dictStack = new Stack<DictValue>();
    private String dumpOnlyThisGlyph = null;
    private boolean containsSupplementalEncodings;
    private boolean dumpCharstringsAndSubrs = true;
    private int unusedGlobalSubrs = 0;
    private int unusedLocalSubrs = 0;
    private static final int NO_DEFAULT = Integer.MIN_VALUE;
    private boolean enablePrintingOffsetArrays;
    private boolean isCIDFont;
    private boolean enableStoringCharsetAndMetrics;
    HashMap<Integer,Float> gidToWidth;
    private String[] charsetArray;
    private int numberOfGlyphs;
    private String fontName;
    private double[] topDictFontBBox;
    private int globalSubrBias;
    private int globalSubrINDEXOffset;
    private String[] globalSubrDumps;
    private final StringBuilder privateDictsDump = new StringBuilder();
    private HashMap<String,Integer> errors = new HashMap<String,Integer>();
    private String charStringsDump;
    private String encodingDump;
    private String charsetDump;
    private String fdSelectDump;
    private String fontDictIdxDump;
    private Type2CharStringDump type2Dumper;
    private boolean enableDumpingUnusedSubrs;
    private boolean silenceNumOperandsErrorsInUnusedSubr;
    private boolean hasAnyNumOperandsErrorsInUnusedSubrs;

    public static final String DUMPER_VERSION = "2.1.0";

    /**
     * The contents of the String INDEX, the non-standard strings.
     * These are referenced by SIDs starting from 391, so nonStandardStrings[0]
     * corresponds to SID 391.
     */
    private String[] nonStandardStrings;

    /**
     * FDSelect maps glyph indices to FD indices in CIDFonts.
     */
    private Map<Integer,Integer> fdSelect;

    /**
     * Specifies the start offset of Local Subr INDEXes for each FD dictionary.
     * This contains only one entry for a base font.
     */
    private int[] localSubrINDEXOffsets;

    /**
     * Specifies the subroutine count of Local Subr INDEXes for each FD dictionary.
     * This contains only one entry for a base font.
     */
    private int[] localSubrCounts;

    /**
     * Informational header strings of Local Subr INDEXes for each FD dictionary.
     * This contains only one entry for a base font.
     */
    private String[] localSubrINDEXHeaders;

    /**
     * Specifies the nominalWidthX values for each Private DICT.
     * This contains only one entry for a base font.
     */
    float[] nominalWidths;

    /**
     * Specifies the defaultWidthX values for each Private DICT.
     * This contains only one entry for a base font.
     */
    float[] defaultWidths;

    /**
     * If true, dump Charset, Encoding, FDSelect, and offset arrays in long format.
     * Otherwise, dump them in wide format.
     */
    private boolean isLongFormat;

    /**
     * Dumps of local subroutines. The indices of the outer list are FD indices
     * (or a single 0 for a base font). Each inner list describes the contents of one
     * Local Subr INDEX. Thus the indices of the inner lists are local subroutine indices
     * and the values are subroutine dumps in String form.
     */
    private final ArrayList< ArrayList<String> > localSubrDumps =
        new ArrayList< ArrayList<String> >();

    private static final String SECTION_DIVIDER =
        "\n--------------------------------------------------------------------------------\n\n";
    private static final int MAX_STEM_HINTS = 96;

    /**
     * Dictionary type: Top DICT.
     */
    private static final int DICT_TYPE_TOP = 1;

    /**
     * Dictionary type: Private DICT.
     */
    private static final int DICT_TYPE_PRIVATE = 2;

    /**
     * Dictionary type: Font DICT in a Font DICT INDEX (a.k.a. FDArray).
     */
    private static final int DICT_TYPE_FD = 4;

    private static final int OTF_OTTO = 0x4F54544F;

    /**
     * Predefined standard strings for SIDs 0...390.
     */
    private final String[] standardStrings = {
        ".notdef", "space", "exclam", "quotedbl", "numbersign", "dollar", "percent",
        "ampersand", "quoteright", "parenleft", "parenright", "asterisk", "plus",
        "comma", "hyphen", "period", "slash", "zero", "one", "two", "three", "four",
        "five", "six", "seven", "eight", "nine", "colon", "semicolon", "less", "equal",
        "greater", "question", "at", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J",
        "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "bracketleft", "backslash", "bracketright", "asciicircum", "underscore",
        "quoteleft", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
        "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "braceleft",
        "bar", "braceright", "asciitilde", "exclamdown", "cent", "sterling", "fraction",
        "yen", "florin", "section", "currency", "quotesingle", "quotedblleft",
        "guillemotleft", "guilsinglleft", "guilsinglright", "fi", "fl", "endash",
        "dagger", "daggerdbl", "periodcentered", "paragraph", "bullet", "quotesinglbase",
        "quotedblbase", "quotedblright", "guillemotright", "ellipsis", "perthousand",
        "questiondown", "grave", "acute", "circumflex", "tilde", "macron", "breve",
        "dotaccent", "dieresis", "ring", "cedilla", "hungarumlaut", "ogonek", "caron",
        "emdash", "AE", "ordfeminine", "Lslash", "Oslash", "OE", "ordmasculine", "ae",
        "dotlessi", "lslash", "oslash", "oe", "germandbls", "onesuperior", "logicalnot",
        "mu", "trademark", "Eth", "onehalf", "plusminus", "Thorn", "onequarter", "divide",
        "brokenbar", "degree", "thorn", "threequarters", "twosuperior", "registered",
        "minus", "eth", "multiply", "threesuperior", "copyright", "Aacute", "Acircumflex",
        "Adieresis", "Agrave", "Aring", "Atilde", "Ccedilla", "Eacute", "Ecircumflex",
        "Edieresis", "Egrave", "Iacute", "Icircumflex", "Idieresis", "Igrave", "Ntilde",
        "Oacute", "Ocircumflex", "Odieresis", "Ograve", "Otilde", "Scaron", "Uacute",
        "Ucircumflex", "Udieresis", "Ugrave", "Yacute", "Ydieresis", "Zcaron", "aacute",
        "acircumflex", "adieresis", "agrave", "aring", "atilde", "ccedilla", "eacute",
        "ecircumflex", "edieresis", "egrave", "iacute", "icircumflex", "idieresis",
        "igrave", "ntilde", "oacute", "ocircumflex", "odieresis", "ograve", "otilde",
        "scaron", "uacute", "ucircumflex", "udieresis", "ugrave", "yacute", "ydieresis",
        "zcaron", "exclamsmall", "Hungarumlautsmall", "dollaroldstyle", "dollarsuperior",
        "ampersandsmall", "Acutesmall", "parenleftsuperior", "parenrightsuperior",
        "twodotenleader", "onedotenleader", "zerooldstyle", "oneoldstyle", "twooldstyle",
        "threeoldstyle", "fouroldstyle", "fiveoldstyle", "sixoldstyle", "sevenoldstyle",
        "eightoldstyle", "nineoldstyle", "commasuperior", "threequartersemdash",
        "periodsuperior", "questionsmall", "asuperior", "bsuperior", "centsuperior",
        "dsuperior", "esuperior", "isuperior", "lsuperior", "msuperior", "nsuperior",
        "osuperior", "rsuperior", "ssuperior", "tsuperior", "ff", "ffi", "ffl",
        "parenleftinferior", "parenrightinferior", "Circumflexsmall", "hyphensuperior",
        "Gravesmall", "Asmall", "Bsmall", "Csmall", "Dsmall", "Esmall", "Fsmall",
        "Gsmall", "Hsmall", "Ismall", "Jsmall", "Ksmall", "Lsmall", "Msmall", "Nsmall",
        "Osmall", "Psmall", "Qsmall", "Rsmall", "Ssmall", "Tsmall", "Usmall", "Vsmall",
        "Wsmall", "Xsmall", "Ysmall", "Zsmall", "colonmonetary", "onefitted", "rupiah",
        "Tildesmall", "exclamdownsmall", "centoldstyle", "Lslashsmall", "Scaronsmall",
        "Zcaronsmall", "Dieresissmall", "Brevesmall", "Caronsmall", "Dotaccentsmall",
        "Macronsmall", "figuredash", "hypheninferior", "Ogoneksmall", "Ringsmall",
        "Cedillasmall", "questiondownsmall", "oneeighth", "threeeighths", "fiveeighths",
        "seveneighths", "onethird", "twothirds", "zerosuperior", "foursuperior",
        "fivesuperior", "sixsuperior", "sevensuperior", "eightsuperior", "ninesuperior",
        "zeroinferior", "oneinferior", "twoinferior", "threeinferior", "fourinferior",
        "fiveinferior", "sixinferior", "seveninferior", "eightinferior", "nineinferior",
        "centinferior", "dollarinferior", "periodinferior", "commainferior", "Agravesmall",
        "Aacutesmall", "Acircumflexsmall", "Atildesmall", "Adieresissmall", "Aringsmall",
        "AEsmall", "Ccedillasmall", "Egravesmall", "Eacutesmall", "Ecircumflexsmall",
        "Edieresissmall", "Igravesmall", "Iacutesmall", "Icircumflexsmall", "Idieresissmall",
        "Ethsmall", "Ntildesmall", "Ogravesmall", "Oacutesmall", "Ocircumflexsmall",
        "Otildesmall", "Odieresissmall", "OEsmall", "Oslashsmall", "Ugravesmall",
        "Uacutesmall", "Ucircumflexsmall", "Udieresissmall", "Yacutesmall", "Thornsmall",
        "Ydieresissmall", "001.000", "001.001", "001.002", "001.003", "Black", "Bold",
        "Book", "Light", "Medium", "Regular", "Roman", "Semibold"
    };

    /**
     * Keys for all DICT entries (plus a few of our own).
     */
    private static final String
        KEY_VERSION            = "version",
        KEY_NOTICE             = "Notice",
        KEY_FULLNAME           = "FullName",
        KEY_FAMILYNAME         = "FamilyName",
        KEY_WEIGHT             = "Weight",
        KEY_FONTBBOX           = "FontBBox",
        KEY_CHARSET            = "charset",
        KEY_ENCODING_OFFSET    = "Encoding",
        KEY_CHARSTRINGS_OFFSET = "CharStrings",
        KEY_LOCAL_SUBRS_OFFSET = "Subrs",
        KEY_DEFAULTWIDTHX      = "defaultWidthX",
        KEY_NOMINALWIDTHX      = "nominalWidthX",
        KEY_COPYRIGHT          = "Copyright",
        KEY_ISFIXEDPITCH       = "isFixedPitch",
        KEY_ITALICANGLE        = "ItalicAngle",
        KEY_UNDERLINEPOSITION  = "UnderlinePosition",
        KEY_UNDERLINETHICKNESS = "UnderlineThickness",
        KEY_PAINTTYPE          = "PaintType",
        KEY_CHARSTRINGTYPE     = "CharstringType",
        KEY_FONTMATRIX         = "FontMatrix",
        KEY_STROKEWIDTH        = "StrokeWidth",
        KEY_FORCEBOLD          = "ForceBold",
        KEY_SYNTHETICBASE      = "SyntheticBase",
        KEY_POSTSCRIPT         = "PostScript",
        KEY_ROS                = "ROS",
        KEY_CIDFONTVERSION     = "CIDFontVersion",
        KEY_CIDFONTREVISION    = "CIDFontRevision",
        KEY_CIDFONTTYPE        = "CIDFontType",
        KEY_CIDCOUNT           = "CIDCount",
        KEY_FDARRAY_OFFSET     = "FDArray",
        KEY_FDSELECT_OFFSET    = "FDSelect",
        KEY_FONTNAME           = "FontName",
        KEY_PRIVATE_OFFSET     = "Private-Offset", // our own
        KEY_PRIVATE_SIZE       = "Private-Size",   // our own
        KEY_BLUEVALUES         = "BlueValues",
        KEY_OTHERBLUES         = "OtherBlues",
        KEY_FAMILYBLUES        = "FamilyBlues",
        KEY_FAMILYOTHERBLUES   = "FamilyOtherBlues",
        KEY_STDHW              = "StdHW",
        KEY_STDVW              = "StdVW",
        KEY_UNIQUEID           = "UniqueID",
        KEY_XUID               = "XUID",
        KEY_BLUESCALE          = "BlueScale",
        KEY_BLUESHIFT          = "BlueShift",
        KEY_BLUEFUZZ           = "BlueFuzz",
        KEY_STEMSNAPH          = "StemSnapH",
        KEY_STEMSNAPV          = "StemSnapV",
        KEY_LANGUAGEGROUP      = "LanguageGroup",
        KEY_EXPANSIONFACTOR    = "ExpansionFactor",
        KEY_INITIALRANDOMSEED  = "initialRandomSeed",
        KEY_BASEFONTNAME       = "BaseFontName",
        KEY_BASEFONTBLEND      = "BaseFontBlend",
        KEY_UIDBASE            = "UIDBase";

    /*
    private static final String[] DICT_KEYS = {
        KEY_VERSION,           // 0
        KEY_NOTICE,            // 1
        KEY_FULLNAME,          // 2
        KEY_FAMILYNAME,        // 3
        KEY_WEIGHT,            // 4
        KEY_FONTBBOX,          // 5
        KEY_BLUEVALUES,        // 6
        KEY_OTHERBLUES,        // 7
        KEY_FAMILYBLUES,       // 8
        KEY_FAMILYOTHERBLUES,  // 9
        KEY_STDHW,             // 10
        KEY_STDVW,             // 11
        null,                  // 12 (escape)
        KEY_UNIQUEID,          // 13
        KEY_XUID,              // 14
        KEY_CHARSET,           // 15
        KEY_ENCODING_OFFSET,   // 16
        KEY_CHARSTRINGS_OFFSET,// 17
        "Private",             // 18
        KEY_LOCAL_SUBRS_OFFSET,// 19
        KEY_DEFAULTWIDTHX,     // 20
        KEY_NOMINALWIDTHX      // 21
    };

    private static final String[] DICT_KEYS_ESC = {
        KEY_COPYRIGHT,         // 0
        KEY_ISFIXEDPITCH,      // 1
        KEY_ITALICANGLE,       // 2
        KEY_UNDERLINEPOSITION, // 3
        KEY_UNDERLINETHICKNESS,// 4
        KEY_PAINTTYPE,         // 5
        KEY_CHARSTRINGTYPE,    // 6
        KEY_FONTMATRIX,        // 7
        KEY_STROKEWIDTH,       // 8
        KEY_BLUESCALE,         // 9
        KEY_BLUESHIFT,         // 10
        KEY_BLUEFUZZ,          // 11
        KEY_STEMSNAPH,         // 12
        KEY_STEMSNAPV,         // 13
        KEY_FORCEBOLD,         // 14
        null,                  // 15 (reserved)
        null,                  // 16 (reserved)
        KEY_LANGUAGEGROUP,     // 17
        KEY_EXPANSIONFACTOR,   // 18
        KEY_INITIALRANDOMSEED, // 19
        KEY_SYNTHETICBASE,     // 20
        KEY_POSTSCRIPT,        // 21
        KEY_BASEFONTNAME,      // 22
        KEY_BASEFONTBLEND,     // 23
        null,                  // 24 (reserved)
        null,                  // 25 (reserved)
        null,                  // 26 (reserved)
        null,                  // 27 (reserved)
        null,                  // 28 (reserved)
        null,                  // 29 (reserved)
        KEY_ROS,               // 30
        KEY_CIDFONTVERSION,    // 31
        KEY_CIDFONTREVISION,   // 32
        KEY_CIDFONTTYPE,       // 33
        KEY_CIDCOUNT,          // 34
        KEY_UIDBASE,           // 35
        KEY_FDARRAY_OFFSET,    // 36
        KEY_FDSELECT_OFFSET,   // 37
        KEY_FONTNAME           // 38
    };
    */

    /**
     * Constructs a new CFF parser thats reads from a file.
     * If an OpenType font file is specified, {@code startOffset} must specify
     * the start of the {@code 'CFF '} table.
     *
     * @param file file to read
     * @param startOffset start offset of CFF data
     * @throws java.io.IOException if startOffset is invalid or if an I/O error occurs
     */
    public CFFDump(File file, int startOffset)
    throws IOException
    {
        if (startOffset < 0) {
            throw new IOException("Invalid start offset");
        }

        long len = file.length() - startOffset;
        if (len <= 0) {
            throw new IOException("Invalid start offset or empty file");
        }

        byte[] array = null;
        InputStream is = null;

        try {
            is = new FileInputStream(file);
            if (startOffset > 0) {
                is.skip(startOffset);
            }
            array = inputToBytes(is);

        } finally {
            if (is != null) {
                is.close();
            }
        }

        initialize(array, startOffset, file.getName(), null);

        // There might be other data in the file following the CFF
        // data. We load also that data into the ByteBuffer. That causes no
        // harm because CFF parsing ignores extra bytes at the end. The parsing
        // simply ends when all CFF blocks have been processed.
    }

    /**
     * Constructs a new CFF parser that can read encoded/compressed data.
     *
     * @param file file to read
     * @param startOffset start offset of CFF data
     * @param filter filter for data encoding/compression
     * @throws java.io.IOException if startOffset is invalid or if an I/O error occurs
     */
    public CFFDump(File file, int startOffset, int filter)
    throws IOException
    {
        if (startOffset < 0) {
            throw new IOException("Invalid start offset");
        }

        InputStream is = null;
        byte[] array = null;

        try {
            is = new FileInputStream(file);

            // startOffset is specified in terms of encoded/compressed data, so
            // skip to startOffset before applying filters to {@code is}.
            if (startOffset > 0) {
                is.skip(startOffset);
            }

            if (filter == Filters.FILTER_DEFLATE) {
                is = new InflaterInputStream(is);
            }

            if (filter == Filters.FILTER_ASCII_HEX) {
                array = hexToBytes(is);
            } else {
                array = inputToBytes(is);
            }

        } finally {
            if (is != null) {
                is.close();
            }
        }

        initialize(array, startOffset, file.getName(), null);
    }

    /**
     * Constructs a new CFF parser that reads data from an InputStream.
     * This always closes the stream.
     *
     * @param is InputStream to read from
     * @param fileNameInfo file name, only to be shown as information in the dump, can be null
     * @throws java.io.IOException if an I/O error occurs
     */
    public CFFDump(InputStream is, File fileNameInfo)
    throws IOException
    {
        byte[] array = null;

        try {
            array = inputToBytes(is);

        } finally {
            if (is != null) {
                is.close();
            }
        }

        String fileInfoStr = (fileNameInfo == null) ? "<unknown file>" : fileNameInfo.getName();
        initialize(array, 0, fileInfoStr, null);
    }

    /**
     * Constructs a new CFF parser that reads from a byte array.
     *
     * @param cffData a complete block of CFF data
     * @param file filename to be displayed in the heading of the dump output
     * @param message optional message to be printed in the heading of the dump; can be null
     */
    public CFFDump(byte[] cffData, String file, String message)
    {
        initialize(cffData, 0, file, message);
    }

    /**
     * Constructs a new CFF parser that reads from an OpenType (.otf) font file.
     * This automatically locates the {@code 'CFF '} table within the file structure.
     *
     * @param file OpenType font file
     * @return CFFDump object
     * @throws java.io.IOException if an I/O error occurs
     */
    public static CFFDump createOTFDumper(File file)
    throws IOException
    {
        int[] ret = { 0, 0 };
        byte[] cffData = findCffDataInOtfFile(file, ret);

        String startOffStr = Integer.toHexString(ret[0]).toUpperCase();
        String message =
          "% Dumping an OpenType font file.\n" +
          "% CFF data starts at 0x" + startOffStr + " and its length is " + ret[1] + " bytes.\n" +
          "% All dumped offsets are relative to 0x" + startOffStr + ".\n";

        return new CFFDump(cffData, file.getName(), message);
    }

    /**
     * Inititalize the parser.
     * {@code file} and {@code startOffset} arguments are for information only.
     * This method will not open {@code file} for reading.
     */
    private void initialize(byte[] arr, int startOffset, String file, String message)
    {
        input = ByteBuffer.wrap(arr);
        type2Dumper = new Type2CharStringDump(this, input);

        sbMain.append("% CFF Dump Output\n");
        sbMain.append("% File: ").append(file).append('\n');
        if (startOffset > 0) {
            sbMain.append("% Start offset: 0x");
            sbMain.append(Integer.toHexString(startOffset).toUpperCase()).append('\n');
            sbMain.append("% All dumped block offsets are relative to the start offset.\n");
        }
        if (message != null) {
            sbMain.append(message);
        }
        sbMain.append('\n');
    }

    private static byte[] inputToBytes(InputStream is) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        is = new BufferedInputStream(is, 1024);

        int b;
        while ((b = is.read()) != -1) {
            baos.write(b);
        }
        return baos.toByteArray();
    }

    public static byte[] hexToBytes(InputStream is) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        is = new BufferedInputStream(is, 1024);
        int prevByte = 0, hexCounter = 0;

        while (true) {
            int b = is.read();
            if (b == -1) {
                if (hexCounter > 0) {
                    throw new IOException("Odd number of hex characters");
                }
                break;
            }
            if (b <= 32) {
                // White space
                continue;
            }
            switch (hexCounter) {
                case 0:
                    hexCounter = 1;
                    prevByte = b;
                    break;
                case 1:
                    baos.write((decodeHexChar(prevByte) << 4) | decodeHexChar(b));
                    hexCounter = 0;
                    prevByte = 0;
                    break;
            }
        }

        return baos.toByteArray();
    }

    private static int decodeHexChar(int hex) throws IOException
    {
        if ('0' <= hex && hex <= '9') {
            hex -= 48;
        } else if ('A' <= hex && hex <= 'F') {
            hex -= 55;
        } else if ('a' <= hex && hex <= 'f') {
            hex -= 87;
        } else {
            throw new IOException("Invalid hex character " + (char)hex);
        }
        return hex;
    }

    /**
     * Parses CFF data and generates the dump.
     *
     * @throws cff.CFFParseException if CFF data is invalid or contains unsupported operations
     */
    public void parseCFF() throws CFFParseException, FileFormatException
    {
        // Header
        printSectionHeading("Header", sbMain);
        int major = readCard8(); // major version
        int minor = readCard8(); // minor version
        sbMain.append("    major: ").append(major).append('\n');
        sbMain.append("    minor: ").append(minor).append('\n');
        if (major == 2) {
            throw new CFFParseException("CFF2 font is not supported");
        }
        if (major != 1) {
            if (input.capacity() >= 4) {
                int fourBytes = (major << 24) | (minor << 16) | (readCard8() << 8) | readCard8();
                throw new FileFormatException(fourBytes);
            }
            throw new FileFormatException("Unsupported CFF version " + major + "." + minor);
        }
        int hdrSize = readCard8();
        sbMain.append("    hdrSize: ").append(hdrSize).append('\n');
        int absoluteOffsetSize = readOffSize();
        sbMain.append("    offSize: ").append(absoluteOffsetSize).append('\n');
        input.position(hdrSize); // skip rest of header

        // Name INDEX
        printSectionHeading("Name INDEX", sbMain);
        String[] fontNames = readNameINDEX(sbMain);
        int numFontNames = fontNames.length;
        if (numFontNames != 1) {
            throw new CFFParseException("Cannot analyze font that has more than one " +
                "font name in Name INDEX");
        }
        fontName = fontNames[0];

        // Top DICT INDEX
        // String INDEX must be parsed before Top DICT INDEX because Top DICTs use SIDs
        // to refer to non-standard strings. To get String INDEX appear before Top DICTs
        // in the dump output, we use the temporary StringBuilder {@code sbStrIdx} when reading the
        // String INDEX below. Thus note that String INDEX is dumped to {@code sbStrIdx},
        // not to the instance variable {@code sbMain}.
        int topDictINDEXPos = input.position();
        skipINDEX();

        // String INDEX
        int stringINDEXStartPos = input.position();
        StringBuilder sbStrIdx = new StringBuilder(128);
        nonStandardStrings = readStringINDEX(sbStrIdx, true);
        int stringINDEXEndPos = input.position();

        // Actually read the Top DICT INDEX
        input.position(topDictINDEXPos);
        printSectionHeading("Top DICT INDEX", sbMain);
        List< HashMap<String,String> > topDicts = readDICTINDEX(sbMain, DICT_TYPE_TOP, "Top DICT");
        input.position(stringINDEXEndPos); // don't read String INDEX again

        // Actually dump the String INDEX
        printSectionHeading("String INDEX", stringINDEXStartPos, sbMain, true);
        sbMain.append(sbStrIdx);

        // Global Subr INDEX
        globalSubrINDEXOffset = input.position();
        int[] globalSubrINDEXOffSize = { -1 };
        int gsubrCount = getINDEXCount(-1, globalSubrINDEXOffSize);
        // Validate offsets but don't dump. (Optional dumping is done in printGlobalSubrDumps().)
        int curPosGsubr = input.position();
        input.position(globalSubrINDEXOffset);
        validateAndPrintINDEXOffsets(-1, 0, null, 0, 0, "Global Subr");
        input.position(curPosGsubr);
        globalSubrBias = computeSubrBias(gsubrCount);
        globalSubrDumps = new String[gsubrCount];

        // --------------------------------------------------
        // Do not write anything to sbMain after this. The dump of Global Subrs
        // is generated when charstrings are dumped, so we can't write to sbMain
        // before Global Subrs have been analyzed.
        // --------------------------------------------------

        int numDicts = topDicts.size();
        if (numDicts != 1) {
            throw new CFFParseException("Cannot analyze font that has more than one " +
                "dictionary in Top DICT INDEX");
        }

        HashMap<String,String> dict = topDicts.get(0);

        if (fontName.isEmpty() || fontName.charAt(0) == '\u0000') {
            // If font name begins with \u0000, that font has been marked as "removed"
            // although its data is still present.
            throw new CFFParseException(
                "Cannot analyze a CFF font that has been removed from file.");
        }

        boolean isSynthetic = dict.containsKey(KEY_SYNTHETICBASE);
        if (isSynthetic) {
            throw new CFFParseException("Cannot analyze a synthetic CFF font");
        }

        isCIDFont = dict.containsKey(KEY_ROS); // the "ROS" entry indicates a CIDFont
        // printFontDumpHeading(fontName, sbMain);
        this.numberOfGlyphs = getNumberOfGlyphs(dict);

        if (isCIDFont) {
            loadCIDFontDictData(dict, numberOfGlyphs);
        } else {
            loadFontDictData(dict, numberOfGlyphs);
        }

        // Finally, print the delayed dumps that are now finished

        printGlobalSubrDumps(gsubrCount, globalSubrINDEXOffSize[0]);

        if (encodingDump != null) {
            sbMain.append(encodingDump);
        }
        sbMain.append(charsetDump);
        if (fdSelectDump != null) {
            sbMain.append(fdSelectDump);
        }
        if (fontDictIdxDump != null) {
            sbMain.append(fontDictIdxDump);
        }
        sbMain.append(charStringsDump);
        if (privateDictsDump != null) {
            sbMain.append(privateDictsDump);
        }
        for (int fdIdx = 0, numFDs = localSubrDumps.size(); fdIdx < numFDs; fdIdx++) {
            printLocalSubrDumps(localSubrDumps.get(fdIdx), fdIdx);
        }
        sbMain.append(SECTION_DIVIDER);
        appendMessages();
        sbMain.append("\n% End of dump\n");
    }

    /**
     * Prints the dumps of global subroutines from {@code globalSubrDumps}.
     */
    private void printGlobalSubrDumps(int gsubrCount, int offSize)
    throws CFFParseException
    {
        printSectionHeading("Global Subr INDEX", globalSubrINDEXOffset, sbMain, true);
        sbMain.append("  count: ").append(gsubrCount);
        if (offSize > -1) {
            sbMain.append(", offSize: ").append(offSize);
        }
        if (gsubrCount <= 0) {
            sbMain.append("\n  <No global subroutines>\n");
            return;
        }
        if (!dumpCharstringsAndSubrs) {
            sbMain.append("\n  <Subroutine dumping is disabled>\n");
            return;
        }
        sbMain.append(", subroutine bias: ").append(computeSubrBias(gsubrCount)).append('\n');

        int curPos = input.position();
        input.position(globalSubrINDEXOffset);
        validateAndPrintINDEXOffsets(-1, 0, sbMain, 0, 0, "Global Subr");
        input.position(curPos);

        for (int gsubrNo = 0; gsubrNo < gsubrCount; gsubrNo++) {
            String gsubr = globalSubrDumps[gsubrNo];
            if (gsubr == null) {
                dumpUnusedSubroutine(gsubrNo, 0, false);
                unusedGlobalSubrs++;
            } else {
                sbMain.append(gsubr);
            }
        }
    }

    /**
     * Prints the dumps of local subroutines from the specified list.
     * In the case of a CIDFont, the list contains the dumps for one
     * Font DICT specified in the Font DICT INDEX.
     */
    private void printLocalSubrDumps(ArrayList<String> localSubrs, Integer fdIdx)
    throws CFFParseException
    {
        int count = localSubrCounts[fdIdx];
        String header = localSubrINDEXHeaders[fdIdx];
        if (header == null) {
            int localSubrINDEXOff = localSubrINDEXOffsets[fdIdx];
            header = getLocalSubrINDEXHeader(localSubrINDEXOff, fdIdx, count, -1);
        }
        sbMain.append(header);

        if (count <= 0) {
            sbMain.append("  <No local subroutines>\n");
            return;
        }
        /*
        if ((localSubrs == null || localSubrs.isEmpty()) && !enableDumpingUnusedSubrs) {
            // We don't have any dumps in this INDEX. It is possible that a font has
            // some subroutines but no charstring ever calls them.
            sbMain.append("  <Local subroutines are never called or dumping is disabled>\n");
            return;
        }
        */
        if (!dumpCharstringsAndSubrs) {
            sbMain.append("  <Subroutine dumping is disabled>\n");
            return;
        }

        for (int lsubrNo = 0; lsubrNo < count; lsubrNo++) {
            String lsubr = (lsubrNo < localSubrs.size()) ? localSubrs.get(lsubrNo) : null;
            if (lsubr == null) {
                dumpUnusedSubroutine(lsubrNo, fdIdx, true);
                unusedLocalSubrs++;
            } else {
                sbMain.append(lsubr);
            }
        }
    }

    private void dumpUnusedSubroutine(int subrNo, int fdIdx, boolean isLocal)
    {
        try {
            // Unused subroutines often have an invalid number of operands when
            // they are dumped without executing them because the actual operands
            // cannot be obtained from the caller. Thus silence those errors.
            silenceNumOperandsErrorsInUnusedSubr = true;

            int[] offLen = getOffsetAndLengthOfSubroutine(subrNo, isLocal, fdIdx);
            int offset = offLen[0], length = offLen[1];
            if (length <= 0) {
                printTitleForSubr(subrNo, offset, sbMain);
                sbMain.append("    <This subroutine has 0 bytes.>\n");
                return;
            }
            if (!enableDumpingUnusedSubrs) {
                printTitleForSubr(subrNo, offset, sbMain);
                sbMain.append("    <This subroutine is never called. Unable to dump. Length is ")
                    .append(length).append(" bytes.>\n");
                return;
            }

            String unusedInfo = "    % <This subroutine is never called. Length is " +
                length + " bytes.>\n";
            StringBuilder tmp = new StringBuilder();
            GlyphStatus gs = new GlyphStatus(-1, fdIdx, this);
            gs.widthWasPrinted = true;
            gs.isUnusedSubr = true;
            type2Dumper.clearType2Stack();
            type2Dumper.foundEndChar = false;
            if (isLocal) {
                executeLocalSubr(subrNo, tmp, gs, true, unusedInfo);
            } else {
                executeGlobalSubr(subrNo, tmp, gs, true, unusedInfo);
            }
            String subrDump = "";
            if (isLocal) {
                ArrayList<String> subrsForFD =
                    fdIdx < localSubrDumps.size() ? localSubrDumps.get(fdIdx) : null;
                subrDump = subrsForFD != null && subrNo < subrsForFD.size()
                    ? subrsForFD.get(subrNo)
                    : "";
            } else {
                subrDump = globalSubrDumps[subrNo];
            }
            sbMain.append(subrDump);

        } catch (Exception ex) {
            sbMain.append("    <Dump failed>\n");
            System.out.println(ex);
        } finally {
            silenceNumOperandsErrorsInUnusedSubr = false;
        }
    }

    private void printTitleForSubr(int subrNo, int offset, StringBuilder s)
    {
        s.append("  [").append(subrNo).append("] ");
        printOffset(offset, s);
        s.append(":\n");
    }

    private void pushDictInt(int intValue)
    {
        dictStack.push(new DictValue(intValue));
    }

    private DictValue popDictValue()
    {
        return dictStack.pop();
    }

    private int popDictInt()
    {
        DictValue e = popDictValue();
        if (e.isInt()) {
            return e.getInt();
        }
        return (int)e.getReal();
    }

    private String popDictString()
    {
        int sid = popDictInt();
        return sidToString(sid);
    }

    private void clearDictStack()
    {
        dictStack.clear();
    }

    private void unlimit()
    {
        input.limit(input.capacity());
    }

    private int readCard8()
    {
        return input.get() & 0xFF;
    }

    private int readCard16()
    {
        return input.getShort() & 0xFFFF;
    }

    private int readOffSize() throws CFFParseException
    {
        int offSize = input.get() & 0xFF;
        if (1 <= offSize && offSize <= 4) {
            return offSize;
        }
        throw new CFFParseException("Invalid OffSize value " + offSize);
    }

    private int readOffset(int offSize) throws CFFParseException
    {
        switch (offSize) {
            case 1:
                return input.get() & 0xFF;
            case 2:
                return input.getShort() & 0xFFFF;
            case 3:
                int hi8 = input.get() & 0xFF;
                int low16 = input.getShort() & 0xFFFF;
                return (hi8 << 16) | low16;
            case 4:
                return input.getInt();
            default:
                throw new CFFParseException("Invalid OffSize value " + offSize);
        }
    }

    private int readSID()
    {
        return input.getShort() & 0xFFFF;
    }

    private String readString(int len)
    {
        char[] arr = new char[len];

        for (int i = 0; i < len; i++) {
            char c = (char)readCard8();
            if (c < 32 && c != '\n' && c != '\r' && c != '\t') {
                // Remove control chars. For example, '\u0000' would truncate
                // the dump when copied to Windows clipboard.
                c = '.';
            }
            arr[i] = c;
        }

        return new String(arr);
    }

    private void dictIntEntry(String key, StringBuilder s, HashMap<String,String> d)
    {
        DictValue v = popDictValue();
        String intStr = Integer.toString(v.getInt());
        String valStr = v.toString();

        s.append("    ");
        s.append('/').append(key).append(' ').append(valStr);

        if (!v.isInt()) {
            s.append("  % ERROR: Value must be an integer!");
            addError("DICT entry " + key + " must be an integer, not " + valStr);
        }
        s.append('\n');
        d.put(key, intStr);
    }

    private void dictStringEntry(String key, StringBuilder s, HashMap<String,String> d)
    {
        DictValue v = popDictValue();
        int sid = v.getInt();
        String str = sidToString(sid);

        s.append("    ");
        s.append('/').append(key).append(" (").append(str).append(")  % SID ");
        s.append(v.toString());

        if (!v.isInt()) {
            s.append("  % ERROR: SID value must be an integer!");
            addError("DICT entry " + key + " must be a SID, not " + v);
        }
        s.append('\n');
        d.put(key, str);
    }

    private void dictNumberEntry(String key, StringBuilder s, HashMap<String,String> d)
    {
        // The operand can be either an integer or a real, so no type checking is added here.
        DictValue v = popDictValue();
        String real = v.toString();

        s.append("    ");
        s.append('/').append(key).append(' ').append(real).append('\n');
        d.put(key, real);
    }

    private void dictBoolEntry(String key, StringBuilder s, HashMap<String,String> d)
    {
        DictValue v = popDictValue();
        int i = v.getInt();
        boolean val = (i != 0);

        s.append("    ");
        s.append('/').append(key).append(' ').append(val);

        if (!v.isInt() || i < 0 || i > 1) {
            s.append("  % ERROR: Boolean value (0 or 1) expected!");
            addError("DICT entry " + key + " must be a boolean (0 or 1), not " + v);
        }
        s.append('\n');
        d.put(key, Boolean.toString(val));
    }

    private int dictArrayEntry(String key, StringBuilder s, HashMap<String,String> d,
    int neededCount)
    {
        s.append("    ");
        s.append('/').append(key).append(' ');
        int count = dictStack.size();
        int pos = s.length();
        s.append('[');

        for (int i = 0; i < count; i++) {
            DictValue e = dictStack.get(i);
            if (e.isReal()) {
                s.append(e.getRealString());
            } else {
                s.append(e.getInt());
            }

            if (i < count - 1) {
                s.append(' ');
            }
        }

        s.append(']');
        String val = s.substring(pos);

        if (neededCount > -1 && count != neededCount) {
            s.append("  % ERROR: Invalid number of array elements!");
            addError("DICT entry " + key + " must have " + neededCount +
                " array elements, not " + count);
        }
        s.append('\n');
        d.put(key, val);
        return count;
    }

    private int dictDeltaArrayEntry(String key, StringBuilder s,
    HashMap<String,String> d, boolean lengthMustBeEvent)
    {
        s.append("    ");
        s.append('/').append(key).append(' ');
        int pos = s.length();
        s.append('[');
        int count = dictStack.size();

        StringBuilder tmp = new StringBuilder();
        tmp.append("  % Original delta values: [");

        if (count > 0) {
            DictValue v0 = dictStack.get(0);
            double curVal = v0.getReal();
            printDouble(curVal, s);
            tmp.append(v0.toString());
            if (count > 1) {
                s.append(' ');
                tmp.append(' ');
            }
            for (int i = 1; i < count; i++) {
                DictValue v = dictStack.get(i);
                curVal += v.getReal();
                printDouble(curVal, s);
                tmp.append(v.toString());
                if (i < count - 1) {
                    s.append(' ');
                    tmp.append(' ');
                }
            }
        }

        s.append(']');
        tmp.append(']');

        String val = s.substring(pos);
        if (lengthMustBeEvent && (count % 2) != 0) {
            s.append("  % ERROR: Even number of array elements is needed!");
            addError("DICT entry " + key + " must have an even number of delta array elements");
        }

        s.append(tmp);
        s.append('\n');
        d.put(key, val);
        return count;
    }

    private void printDouble(double f, StringBuilder s)
    {
        int i = (int)f;
        if (f == i) {
            s.append(i);
        } else {
            s.append(f);
        }
    }

    /**
     * Reads a CFF {@code DICT}.
     * Note that this puts only a few selected integer entries to the Map object.
     * Not all entries are added to it.
     * {@code endLimit} must be one byte after the last byte of the {@code DICT}.
     *
     * @return end position
     */
    private int readDICT(HashMap<String,String> d, int endLimit, StringBuilder s, int dictType)
    throws CFFParseException
    {
        // We use the same parsing methods for both Top DICTs and Private DICTs, so
        // our methods accept also illegal operators, such as a {@code charset} operator
        // in a Private DICT.

        s.append("  <<\n");
        input.limit(endLimit);
        clearDictStack();
        int b;

        while (input.hasRemaining()) {
            b = input.get() & 0xFF;
            if (b == 12) {
                b = input.get() & 0xFF;
                parseEscapedDICTOperator(b, d, s);
            } else if (b <= 21) {
                parseDICTOperator(b, d, s, dictType);
            } else if (b == 30) {
                readDICTReal();
            } else if (b >= 32) {
                readDICTInteger(b);
            } else if (b == 28) {
                int i = input.getShort(); // signed
                pushDictInt(i);
            } else if (b == 29) {
                int i = input.getInt();
                pushDictInt(i);
            }
        }

        // Note: Instance variable isCIDFont is still uninitialized.
        boolean isCID = d.containsKey(KEY_ROS);
        s.append("    % ----- Following entries are missing, so they get default values: -----\n");

        switch (dictType) {
            case DICT_TYPE_TOP:
            case DICT_TYPE_FD:
                printMissingEntry(KEY_ISFIXEDPITCH, "false", d, s);
                printMissingEntry(KEY_ITALICANGLE, "0", d, s);
                printMissingEntry(KEY_UNDERLINEPOSITION, "-100", d, s);
                printMissingEntry(KEY_UNDERLINETHICKNESS, "50", d, s);
                printMissingEntry(KEY_PAINTTYPE, "0", d, s);
                printMissingEntry(KEY_CHARSTRINGTYPE, "2", d, s);
                printMissingEntry(KEY_FONTMATRIX, "[0.001 0 0 0.001 0 0]", d, s);
                printMissingEntry(KEY_FONTBBOX, "[0 0 0 0]", d, s);
                printMissingEntry(KEY_STROKEWIDTH, "0", d, s);
                if (dictType == DICT_TYPE_TOP) {
                    if (isCID) {
                        printMissingEntry(KEY_CIDFONTVERSION, "0", d, s);
                        printMissingEntry(KEY_CIDFONTREVISION, "0", d, s);
                        printMissingEntry(KEY_CIDFONTTYPE, "0", d, s);
                        printMissingEntry(KEY_CIDCOUNT, "8720", d, s);
                    } else {
                        printMissingEntry(KEY_CHARSET, "/ISOAdobe", d, s);
                        printMissingEntry(KEY_ENCODING_OFFSET, "/StandardEncoding", d, s);
                    }
                }
                break;
            case DICT_TYPE_PRIVATE:
                printMissingEntry(KEY_BLUESCALE, "0.039625", d, s);
                printMissingEntry(KEY_BLUESHIFT, "7", d, s);
                printMissingEntry(KEY_BLUEFUZZ, "1", d, s);
                printMissingEntry(KEY_FORCEBOLD, "false", d, s);
                printMissingEntry(KEY_LANGUAGEGROUP, "0", d, s);
                printMissingEntry(KEY_EXPANSIONFACTOR, "0.06", d, s);
                printMissingEntry(KEY_INITIALRANDOMSEED, "0", d, s);
                printMissingEntry(KEY_DEFAULTWIDTHX, "0", d, s);
                printMissingEntry(KEY_NOMINALWIDTHX, "0", d, s);
                break;
        }

        int endsAt = input.position();
        clearDictStack();
        unlimit(); // clear limit
        s.append("  >>\n");

        return endsAt;
    }

    private void printMissingEntry(String key, String defaultValue,
    HashMap<String,String> dict, StringBuilder s)
    {
        if (!dict.containsKey(key)) {
            s.append("    /").append(key).append(' ').append(defaultValue).append("  % default\n");
        }
    }

    private void readDICTInteger(int b0)
    {
        int num;

        if (b0 <= 246) {
            // 32 <= b0 <= 246
            num = b0 - 139;
        } else if (b0 <= 250) {
            // 247 <= b0 <= 250
            int b1 = input.get() & 0xFF;
            num = (b0 - 247) * 256 + b1 + 108;
        } else if (b0 <= 254) {
            // 251 <= b0 <= 254
            int b1 = input.get() & 0xFF;
            num = -256 * (b0 - 251) - b1 - 108;
        } else {
            return; // error
        }

        pushDictInt(num);
    }

    private void readDICTReal()
    {
        StringBuilder sb = new StringBuilder();
        int b;
        while (true) {
            b = input.get();
            if (parseRealNibble((b >> 4) & 0x0F, sb)) {
                // high 4 bits
                break;
            }
            if (parseRealNibble(b & 0x0F, sb)) {
                // low 4 bits
                break;
            }
        }
        final String realStr = sb.toString();

        try {
            double real = Double.parseDouble(realStr);
            dictStack.push( new DictValue(real, realStr) );

        } catch (NumberFormatException nfe) {
            addError("DICT real number entry is invalid: " + realStr);
            dictStack.push( new DictValue(0.0, "<Error! Invalid real number '" + realStr + "'>") );
        }
    }

    private boolean parseRealNibble(int nibble, StringBuilder s)
    {
        switch (nibble) {
            case 0x00:
            case 0x01:
            case 0x02:
            case 0x03:
            case 0x04:
            case 0x05:
            case 0x06:
            case 0x07:
            case 0x08:
            case 0x09:
                s.append( (char)(nibble + 48) );
                break;
            case 0x0A:
                s.append('.');
                break;
            case 0x0B:
                s.append('E');
                break;
            case 0x0C:
                s.append("E-");
                break;
            // case 0x0D: Reserved
            case 0x0E:
                s.append('-');
                break;
            case 0x0F:
                // End of number
                return true;
            default:
                addError("DICT real number contains invalid nibble 0x" +
                    Integer.toHexString(nibble));
                s.append('?'); // to generate a NumberFormatException
                break;
        }

        return false;
    }

    private void parseDICTOperator(int op, HashMap<String,String> d, StringBuilder s, int dictType)
    {
        // Note: Top DICTs use SIDs, so String INDEX must be read before the Top DICT INDEX.
        // All DICT entries are added also to {@code d}.

        switch (op) {
            case 0:
                dictStringEntry(KEY_VERSION, s, d);
                break;
            case 1:
                dictStringEntry(KEY_NOTICE, s, d);
                break;
            case 2:
                dictStringEntry(KEY_FULLNAME, s, d);
                break;
            case 3:
                dictStringEntry(KEY_FAMILYNAME, s, d);
                break;
            case 4:
                dictStringEntry(KEY_WEIGHT, s, d);
                break;
            case 5:
                parseFontBBox(s, d, dictType);
                break;
            case 6:
                dictDeltaArrayEntry(KEY_BLUEVALUES, s, d, true);
                break;
            case 7:
                dictDeltaArrayEntry(KEY_OTHERBLUES, s, d, true);
                break;
            case 8:
                dictDeltaArrayEntry(KEY_FAMILYBLUES, s, d, true);
                break;
            case 9:
                dictDeltaArrayEntry(KEY_FAMILYOTHERBLUES, s, d, true);
                break;
            case 10:
                dictNumberEntry(KEY_STDHW, s, d);
                break;
            case 11:
                dictNumberEntry(KEY_STDVW, s, d);
                break;
            // case 12:
            //  escape
            //  break;
            case 13:
                dictIntEntry(KEY_UNIQUEID, s, d);
                break;
            case 14:
                dictArrayEntry(KEY_XUID, s, d, -1);
                break;
            case 15: {
                // Charset
                int charset = popDictInt();
                d.put(KEY_CHARSET, Integer.toString(charset));
                s.append("    /charset ").append(charset);
                if (charset == 0) {
                    s.append("  % = ISOAdobe\n");
                } else if (charset == 1) {
                    s.append("  % = Expert\n");
                } else if (charset == 2) {
                    s.append("  % = ExpertSubset\n");
                } else {
                    s.append("  % offset\n");
                }
                break;
            }
            case 16: {
                // Encoding
                int enc = popDictInt();
                d.put(KEY_ENCODING_OFFSET, Integer.toString(enc));
                s.append("    /Encoding ").append(enc);
                if (enc == 0) {
                    s.append("  % = StandardEncoding\n");
                } else if (enc == 1) {
                    s.append("  % = ExpertEncoding\n");
                } else {
                    s.append("  % offset\n");
                }
                break;
            }
            case 17: {
                // CharStrings
                int offset = popDictInt();
                s.append("    /CharStrings ").append(offset).append("  % offset\n");
                d.put(KEY_CHARSTRINGS_OFFSET, Integer.toString(offset));
                break;
            }
            case 18: {
                // Private DICT offset and size
                int offset = popDictInt();
                int size = popDictInt();
                s.append("    /Private [").append(size).append(' ').append(offset);
                s.append("]  % [size offset]\n");
                d.put(KEY_PRIVATE_OFFSET, Integer.toString(offset));
                d.put(KEY_PRIVATE_SIZE, Integer.toString(size));
                break;
            }
            case 19: {
                // Subrs
                int offset = popDictInt();
                s.append("    /Subrs ").append(offset);
                s.append("  % offset to Local Subr INDEX (relative to start of Private DICT)\n");
                d.put(KEY_LOCAL_SUBRS_OFFSET, Integer.toString(offset));
                break;
            }
            case 20: {
                // defaultWidthX
                DictValue e = popDictValue();
                String realStr = e.isReal() ? e.getRealString() : Integer.toString(e.getInt());
                s.append("    /defaultWidthX ").append(realStr).append('\n');
                d.put(KEY_DEFAULTWIDTHX, realStr);
                break;
            }
            case 21: {
                // nominalWidthX
                DictValue e = popDictValue();
                String realStr = e.isReal() ? e.getRealString() : Integer.toString(e.getInt());
                s.append("    /nominalWidthX ").append(realStr).append('\n');
                d.put(KEY_NOMINALWIDTHX, realStr);
                break;
            }
            // case 22: -Reserved-
            // case 23: -Reserved-
            // case 24: -Reserved-
            // case 25: -Reserved-
            // case 26: -Reserved-
            // case 27: -Reserved-
            // case 28: shortint
            // case 29: longint
            // case 30: real number ("BCD")
            // case 31: Multiple Master Type 2 program follows (within DICT)
            // case 255: -Reserved-
            default:
                s.append("    % ERROR! Unknown DICT operator: ").append(op).append('\n');
                addError("DICT entry " + op + " is unknown");
                break;
        }

        clearDictStack();
    }

    private void parseEscapedDICTOperator(int op, HashMap<String,String> d, StringBuilder s)
    throws CFFParseException
    {
        switch (op) {
            case 0:
                dictStringEntry(KEY_COPYRIGHT, s, d);
                break;
            case 1:
                dictBoolEntry(KEY_ISFIXEDPITCH, s, d);
                break;
            case 2:
                dictNumberEntry(KEY_ITALICANGLE, s, d);
                break;
            case 3:
                dictNumberEntry(KEY_UNDERLINEPOSITION, s, d);
                break;
            case 4:
                dictNumberEntry(KEY_UNDERLINETHICKNESS, s, d);
                break;
            case 5:
                dictIntEntry(KEY_PAINTTYPE, s, d);
                break;
            case 6:
                dictIntEntry(KEY_CHARSTRINGTYPE, s, d);
                break;
            case 7:
                dictArrayEntry(KEY_FONTMATRIX, s, d, 6);
                break;
            case 8:
                dictNumberEntry(KEY_STROKEWIDTH, s, d);
                break;
            case 9:
                dictNumberEntry(KEY_BLUESCALE, s, d);
                break;
            case 10:
                dictNumberEntry(KEY_BLUESHIFT, s, d);
                break;
            case 11:
                dictNumberEntry(KEY_BLUEFUZZ, s, d);
                break;
            case 12:
                dictDeltaArrayEntry(KEY_STEMSNAPH, s, d, false);
                break;
            case 13:
                dictDeltaArrayEntry(KEY_STEMSNAPV, s, d, false);
                break;
            case 14:
                dictBoolEntry(KEY_FORCEBOLD, s, d);
                break;
            case 15:
                // Operator 12 15 is -Reserved- in the current CFF spec. The previous
                // version of the spec (1998) defines this code as ForceBoldThreshold.
                // See MM-snapshot.cff (from PS_3010-3011.Supplement.pdf).
                addError("DICT entry ForceBoldThreshold (12 15) is outdated");
                s.append("    /ForceBoldThreshold ").append(popDictValue()).append("  % ERROR!\n");
                break;
            // case 16:
            //  -Reserved-
            //  break;
            case 17:
                dictIntEntry(KEY_LANGUAGEGROUP, s, d);
                break;
            case 18:
                dictNumberEntry(KEY_EXPANSIONFACTOR, s, d);
                break;
            case 19:
                dictIntEntry(KEY_INITIALRANDOMSEED, s, d);
                break;
            case 20:
                dictIntEntry(KEY_SYNTHETICBASE, s, d);
                break;
            case 21:
                dictStringEntry(KEY_POSTSCRIPT, s, d);
                break;
            case 22:
                dictStringEntry(KEY_BASEFONTNAME, s, d);
                break;
            case 23:
                dictDeltaArrayEntry(KEY_BASEFONTBLEND, s, d, false);
                break;
            case 24:
                throw new CFFParseException("Unable to dump a Multiple Master font " +
                    "(DICT operator 24).");
                // break; // unreachable line
            // case 25:
            //  -Reserved-
            //  break;
            case 26: {
                s.append("    /BlendAxisTypes [");
                for (DictValue e : dictStack) {
                    int sid = e.getInt();
                    s.append('(').append(sidToString(sid)).append(") ");
                }
                s.append("]\n");
                d.put("BlendAxisTypes", "[...]"); // dummy value
                break;
            }
            // case 27: -Reserved-
            // case 28: -Reserved-
            // case 29: -Reserved-
            case 30:
                loadROS(s);
                d.put(KEY_ROS, "..."); // add a dummy value (only to indicate a CIDFont)
                break;
            case 31:
                dictNumberEntry(KEY_CIDFONTVERSION, s, d);
                break;
            case 32:
                dictNumberEntry(KEY_CIDFONTREVISION, s, d);
                break;
            case 33:
                dictNumberEntry(KEY_CIDFONTTYPE, s, d);
                break;
            case 34:
                dictIntEntry(KEY_CIDCOUNT, s, d);
                break;
            case 35:
                dictIntEntry(KEY_UIDBASE, s, d);
                break;
            case 36: {
                int i = popDictInt();
                d.put(KEY_FDARRAY_OFFSET, Integer.toString(i));
                s.append("    /FDArray ").append(i).append("  % offset\n");
                break;
            }
            case 37: {
                int i = popDictInt();
                d.put(KEY_FDSELECT_OFFSET, Integer.toString(i));
                s.append("    /FDSelect ").append(i).append("  % offset\n");
                break;
            }
            case 38:
                dictStringEntry(KEY_FONTNAME, s, d); // only in FDs of CIDFonts
                break;
            case 39:
                dictIntEntry("Chameleon", s, d);
                break;
            default:
                s.append("    % ERROR! Unknown DICT operator: 12 ").append(op).append('\n');
                addError("DICT entry (12 " + op + ") is unknown");
                break;
        }

        clearDictStack();
    }

    private void loadROS(StringBuilder s)
    {
        DictValue supplement = popDictValue();
        String ordering = popDictString();
        String registry = popDictString();

        s.append("    /ROS << /Registry (").append(registry).append(") /Ordering (");
        s.append(ordering).append(") /Supplement ").append(supplement).append(" >>\n");
    }

    /**
     * Name INDEX specifies FontNames (or CIDFontNames). Exceptionally,
     * these are not stored as SIDs.
     */
    private String[] readNameINDEX(StringBuilder s) throws CFFParseException
    {
        String[] names = readStringINDEX(s, false);
        if (names.length <= 0) {
            throw new CFFParseException("CFF contains no fonts");
        }
        return names;
    }

    /**
     * Validates and optionally dumps the offset array of an INDEX.
     * This writes only to the argument {@code s}.
     * Printing offset arrays is typically disabled because they consume
     * lots of space in the dump.
     * Read the count and offSize before calling this, so the input is
     * positioned at the first offset of the array.
     */
    private void validateAndPrintINDEXOffsets(int count, int offSize, StringBuilder s,
    int offArrStart, int offRef, String description)
    throws CFFParseException
    {
        final boolean enableDump = enablePrintingOffsetArrays && s != null;
        final int curPos = input.position();

        if (count < 0) {
            count = readCard16();
            if (count == 0) {
                input.position(curPos);
                return;
            }
            offSize = readCard8();
            offArrStart = input.position();
            offRef = getOffRef(offArrStart, offSize, count);
        }
        if (enableDump) {
            s.append("  Offsets of INDEX (relative to ").append(offRef).append("):\n    ");
        }

        int maxIndexLength = getLengthAsString(count) + 2; // +2 for '[' and ']'
        int maxValidOffset = input.capacity();
        int maxValueRepresentableByOffSize = 1 << (8 * offSize);
        int maxOffsetStringLength = getLengthAsString(maxValueRepresentableByOffSize);
        final int numColumns = 8;
        int columnCounter = 0;

        for (int i = 0; i <= count; i++) {
            final int offset = readOffset(offSize);
            int absOffset = offset + offRef;
            if (absOffset > maxValidOffset) {
                addError("Invalid offset in " + description + " INDEX: " +
                    absOffset + " > font data length");
            }
            if (i == 0 && offset != 1) {
                addError("Invalid offset in " + description + " INDEX: " +
                    "first offset must be 1, not " + offset);
            }
            if (enableDump) {
                printPadded("[" + i + "]", -1, s, maxIndexLength);
                s.append(" = ");
                printPadded(Integer.toString(offset), -1, s, maxOffsetStringLength);
                s.append(' ');
                columnCounter += incrementColumnCounter(i == count);
                if (columnCounter >= numColumns) {
                    s.append("\n    ");
                    columnCounter = 0;
                }
            }
        }

        if (enableDump) {
            s.append("\n  Data:\n");
        }
        input.position(curPos); // revert back to original position
    }

    private List< HashMap<String,String> > readDICTINDEX(StringBuilder s, int dictType,
    String description) throws CFFParseException
    {
        int numDicts = readCard16();
        // Generic arrays cannot be created, so use ArrayList instead.
        ArrayList< HashMap<String,String> > dicts =
            new ArrayList< HashMap<String,String> >(numDicts);

        s.append("  count: ").append(numDicts);
        if (numDicts == 0) {
            s.append('\n');
            return dicts;
        }
        int offSize = readOffSize();
        s.append(", offSize: ").append(offSize).append('\n');

        int offArrStart = input.position();
        int offRef = getOffRef(offArrStart, offSize, numDicts);
        validateAndPrintINDEXOffsets(numDicts, offSize, s, offArrStart, offRef, description);

        for (int i = 0; i < numDicts; i++) {
            HashMap<String,String> dict = new HashMap<String,String>();
            dicts.add(dict);
            input.position(offArrStart + i * offSize);
            int off = readOffset(offSize);
            int nextOff = readOffset(offSize);
            int startPos = off + offRef;
            int endPos = nextOff + offRef;
            input.position(startPos);
            s.append("  [").append(i).append("] ");
            printCurrentOffset(s);
            s.append(":\n");

            int ended = readDICT(dict, endPos, s, dictType);

            if (endPos != ended) {
                String msg = "DICT end offset is wrong (start = " + startPos +
                    ", end = " + endPos + ")";
                s.append("    % ERROR: ").append(msg).append("\n");
                addError(msg);
            }
        }

        return dicts;
    }

    private String[] readStringINDEX(StringBuilder s, boolean printSIDs) throws CFFParseException
    {
        // This writes only to the argument {@code s}, not to {@code sbMain}.

        int numStrings = readCard16();
        s.append("  count: ").append(numStrings);
        if (numStrings <= 0) {
            s.append('\n');
            return new String[0];
        }
        int offSize = readOffSize();
        s.append(", offSize: ").append(offSize).append('\n');

        int offArrStart = input.position();
        int offRef = getOffRef(offArrStart, offSize, numStrings);
        validateAndPrintINDEXOffsets(numStrings, offSize, s, offArrStart, offRef, "String");

        String[] arr = new String[numStrings];
        int sid = 391;

        for (int i = 0; i < numStrings; i++) {
            s.append("    [").append(i).append(']');
            if (printSIDs) {
                s.append("(SID = ").append(sid).append(')');
            }
            s.append(": (");
            input.position(offArrStart + i * offSize);
            int off = readOffset(offSize);
            int nextOff = readOffset(offSize);
            int len = nextOff - off;
            input.position(off + offRef);
            arr[i] = readString(len);
            s.append(arr[i]).append(")\n");
            sid++;
        }
        return arr;
    }

    /**
     * Skips a whole INDEX structure.
     * Position {@code input} at the start of the INDEX before calling this.
     */
    private void skipINDEX() throws CFFParseException
    {
        int count = readCard16();
        if (count == 0) {
            return;
        }
        int offSize = readOffSize();

        int offArrStart = input.position();
        int offRef = getOffRef(offArrStart, offSize, count);

        // Read the last offset of this INDEX
        int lastOffPos = offArrStart + count * offSize;
        input.position(lastOffPos);
        int off = readOffset(offSize);

        // Go to the last offset. Then we are at the first byte after this INDEX.
        input.position(offRef + off);
    }

    /**
     * Returns the dump result.
     */
    public String getResult()
    {
        String str = sbMain.toString();
        if (str.isEmpty()) {
            str = "\n<No dumping results>\n";
        }
        return str;
    }

    /**
     * Loads the data blocks of a simple font dictionary.
     * This method is for base fonts (non-CIDFonts).
     */
    private void loadFontDictData(HashMap<String,String> fontDict, int numGlyphs)
    throws CFFParseException
    {
        localSubrINDEXOffsets = new int[1];
        localSubrCounts = new int[]{ -1 };
        localSubrINDEXHeaders = new String[1];
        nominalWidths = new float[1];
        defaultWidths = new float[1];

        // First read charset. We need it when parsing the encoding.
        StringBuilder sbCharset = new StringBuilder();
        String[] charset = parseCharset(fontDict, numGlyphs, sbCharset);
        charsetDump = sbCharset.toString();
        if (enableStoringCharsetAndMetrics) {
            this.charsetArray = charset;
        }

        encodingDump = parseEncoding(fontDict, charset, numGlyphs);

        localSubrDumps.add(new ArrayList<String>());
        loadPrivateDict(fontDict, "Private DICT", true, 0);
        int singleGidToDump = parseSingleCharDump(charset);

        readCharStrings(fontDict, charset, singleGidToDump);
    }

    /**
     * Loads the data blocks of a CIDFont dictionary.
     * This method is for CIDFonts.
     */
    private void loadCIDFontDictData(HashMap<String,String> cidFontDict, int numGlyphs)
    throws CFFParseException
    {
        StringBuilder sbCharset = new StringBuilder();
        String[] charset = parseCharset(cidFontDict, numGlyphs, sbCharset);
        charsetDump = sbCharset.toString();
        if (enableStoringCharsetAndMetrics) {
            this.charsetArray = charset;
        }

        StringBuilder sbFDI = new StringBuilder();
        input.position( dictGetInt(cidFontDict, KEY_FDARRAY_OFFSET, NO_DEFAULT) );
        printSectionHeading("Font DICT INDEX (a.k.a. FDArray)", sbFDI);
        List< HashMap<String,String> > fdDicts = readDICTINDEX(sbFDI, DICT_TYPE_FD,
            "FD DICT");
        int numFDs = fdDicts.size();
        fontDictIdxDump = sbFDI.toString();

        localSubrINDEXOffsets = new int[numFDs];
        localSubrCounts = new int[numFDs];
        Arrays.fill(localSubrCounts, -1);
        localSubrINDEXHeaders = new String[numFDs];
        nominalWidths = new float[numFDs];
        defaultWidths = new float[numFDs];

        for (int i = 0; i < numFDs; i++) {
            localSubrDumps.add(new ArrayList<String>());
        }

        privateDictsDump.append(SECTION_DIVIDER);
        privateDictsDump.append("CIDFont's Private DICTs:\n");
        int fdIdx = 0;
        for (HashMap<String,String> fd : fdDicts) {
            String heading = "  Private DICT for Font DICT #" + fdIdx;
            loadPrivateDict(fd, heading, false, fdIdx);
            fdIdx++;
        }

        int fdSelectOff = dictGetInt(cidFontDict, KEY_FDSELECT_OFFSET, NO_DEFAULT);
        fdSelect = new HashMap<Integer,Integer>();
        fdSelectDump = parseFDSelect(fdSelectOff, numGlyphs, numFDs);
        int singleGidToDump = parseSingleCharDump(charset);

        readCharStrings(cidFontDict, charset, singleGidToDump);
    }

    private int parseSingleCharDump(String[] charset) throws CFFParseException
    {
        if (dumpOnlyThisGlyph == null) {
            return -1;
        }

        String id;
        if (isCIDFont && dumpOnlyThisGlyph.startsWith("CID")) {
            // It's a CID value.
            id = dumpOnlyThisGlyph.substring(3);
        } else if (dumpOnlyThisGlyph.startsWith("/")) {
            // It's a glyph name.
            id = dumpOnlyThisGlyph.substring(1);
        } else {
            // It's a glyph index.
            try {
                return Integer.parseInt(dumpOnlyThisGlyph);
            } catch (NumberFormatException nfe) {
                throw new CFFParseException("Invalid glyph index for single glyph dump: " +
                    dumpOnlyThisGlyph);
            }
        }

        // We can't use Arrays.binarySearch() because charset has not been sorted.
        for (int gid = 0, charsetLen = charset.length; gid < charsetLen; gid++) {
            if (id.equals(charset[gid])) {
                return gid;
            }
        }
        throw new CFFParseException("Cannot find glyph " + dumpOnlyThisGlyph +
            " for single glyph dump");
    }

    /**
     * Reads the count field of the CharStrings INDEX.
     */
    private int getNumberOfGlyphs(HashMap<String,String> fontDict) throws CFFParseException
    {
        int curPos = input.position();
        int chStrPos = dictGetInt(fontDict, KEY_CHARSTRINGS_OFFSET, NO_DEFAULT);
        input.position(chStrPos);
        int numGlyphs = readCard16();
        input.position(curPos); // revert to original position
        return numGlyphs;
    }

    /**
     * Returns the charset as an array of strings.
     * For simple fonts, the strings are glyph names.
     * For CIDFonts, the array contains string representations of integer CIDs.
     */
    private String[] parseCharset(HashMap<String,String> fontDict, int numGlyphs, StringBuilder s)
    throws CFFParseException
    {
        int offset = dictGetInt(fontDict, KEY_CHARSET, 0); // default: 0 = ISOAdobe

        if (isCIDFont && 0 <= offset && offset <= 2) {
            addError("CIDFont must not use a predefined charset " + offset);
            // We probably need to create an identity charset
            String[] charset = new String[numGlyphs];
            for (int i = 0; i < numGlyphs; i++) {
                charset[i] = Integer.toString(i);
            }
            return charset;
        }

        switch (offset) {
            case 0:
                return convertPredefinedCharset(getISOAdobeCharset());
            case 1:
                return convertPredefinedCharset(getExpertCharset());
            case 2:
                return convertPredefinedCharset(getExpertSubsetCharset());
        }

        // Otherwise it's a custom charset at the specified offset.
        input.position(offset);
        int format = readCard8();
        printSectionHeading("Charset", offset, s, true);
        s.append("  (format ").append(format).append("; [GID] = <");
        s.append(isCIDFont ? "CID" : "glyphName").append(">):\n");

        String[] charset;

        if (format == 0) {
            charset = parseCharsetFormat0(numGlyphs);
        } else if (format == 1 || format == 2) {
            charset = parseCharsetFormat1or2(numGlyphs, format);
        } else {
            throw new CFFParseException("Invalid CFF Charset format " + format);
        }

        // Length of GIDs as String
        int maxGIDLength = getLengthAsString(numGlyphs);
        maxGIDLength += 2; // "[" and "]"

        // Length of glyph names or CIDs
        int maxGlyphNameLength = 0;
        for (String glyphName : charset) {
            maxGlyphNameLength = Math.max(maxGlyphNameLength, glyphName.length());
        }
        if (isCIDFont) {
            maxGlyphNameLength += 4; // "CID "
        } else {
            maxGlyphNameLength += 1; // "/"
        }

        // GID 0 is always omitted in CFF charsets. Add it to the dump.
        s.append("  ([0] = ");
        if (isCIDFont) {
            s.append("CID 0),\n");
        } else {
            s.append("/.notdef),\n");
        }
        s.append("  ");

        final int numColumns = 4;
        int columnCounter = 0;

        for (int gid = 1; gid < numGlyphs; gid++) {
            printPadded("[" + gid + "]", -1, s, maxGIDLength);
            s.append(" = ");
            String glyphName = (isCIDFont ? "CID " : "/") + charset[gid];
            printPadded(glyphName, ',', s, maxGlyphNameLength);
            s.append(' ');
            columnCounter += incrementColumnCounter(gid == numGlyphs - 1);
            if (columnCounter >= numColumns) {
                s.append("\n  ");
                columnCounter = 0;
            }
        }

        s.append('\n');
        return charset;
    }

    private void printPadded(String str, int ch, StringBuilder sb, int paddedLength)
    {
        sb.append(str);
        if (ch > -1) {
            sb.append((char)ch);
        }
        for (int i = str.length(); i < paddedLength; i++) {
            sb.append(' ');
        }
    }

    private String[] parseCharsetFormat0(int numGlyphs)
    {
        String[] names = new String[numGlyphs];
        names[0] = isCIDFont ? "0" : ".notdef";

        // Charset starts from GID 1
        for (int gid = 1; gid < numGlyphs; gid++) {
            int sid = readSID();
            if (isCIDFont) {
                names[gid] = Integer.toString(sid);
            } else {
                names[gid] = sidToName(sid);
            }
        }

        return names;
    }

    private String[] parseCharsetFormat1or2(int numGlyphs, int format)
    {
        String[] names = new String[numGlyphs];
        names[0] = isCIDFont ? "0" : ".notdef";

        int gid = 1;
        boolean fmt2 = (format == 2);
        while (gid < numGlyphs) {
            int first = readSID();
            int left = fmt2 ? readCard16() : readCard8();
            for (int i = 0; i <= left; i++) {
                if (isCIDFont) {
                    names[gid] = Integer.toString(first);
                } else {
                    names[gid] = sidToName(first);
                }
                gid++;
                first++;
            }
        }

        return names;
    }

    private String parseEncoding(HashMap<String,String> fontDict, String[] charset, int numGlyphs)
    throws CFFParseException
    {
        // OpenType-CFF files (.otf) typically omit the Encoding entry in their
        // Top DICT. Thus the encoding basically defaults to StandardEncoding but that's
        // not true. Instead, the actual encoding of .otf fonts is defined in the SFNT
        // table tagged 'cmap'. CFFDump is not able to read 'cmap' tables.

        // Default: 0 = StandardEncoding
        int encOffset = dictGetInt(fontDict, KEY_ENCODING_OFFSET, 0);
        if (encOffset == 0 || encOffset == 1) {
            // 0 = StandardEncoding, 1 = ExpertEncoding
            return "";
        }

        // So it's a custom Encoding at the specified offset.

        StringBuilder s = new StringBuilder();
        printSectionHeading("Encoding", encOffset, s, true);
        s.append("  ");
        input.position(encOffset);
        int format = readCard8();
        boolean hasSupplemental = (format & 0x80) != 0;
        format &= 0x7F; // clear supplemental bit

        // Length of codes as strings. Codes are stored as Card8 values, so the
        // largest code is 255.
        int maxCodeLength = getLengthAsString(255);
        int maxGIDLength = getLengthAsString(numGlyphs - 1);
        // Length of glyph names
        int maxGlyphNameLength = 0;
        for (String glyphName : charset) {
            maxGlyphNameLength = Math.max(maxGlyphNameLength, glyphName.length());
        }
        maxGlyphNameLength += 1; // "/"

        if (format == 0) {
            parseEncodingFormat0(charset, s, maxCodeLength, maxGlyphNameLength, maxGIDLength);
        } else if (format == 1) {
            parseEncodingFormat1(charset, s, maxCodeLength, maxGlyphNameLength, maxGIDLength);
        } else {
            throw new CFFParseException("Invalid CFF Encoding format " + format);
        }

        if (hasSupplemental) {
            parseSupplementalEncoding(s, maxCodeLength, maxGlyphNameLength);
            containsSupplementalEncodings = true;
        }

        s.append('\n');
        return s.toString();
    }

    private void parseEncodingFormat0(String[] charset, StringBuilder s,
    int maxCodeLength, int maxGlyphNameLength, int maxGIDLength)
    {
        int nCodes = readCard8();

        s.append("(format 0; ").append(nCodes).append(" codes; <code> = <[GID]> = <glyphName>)\n  ");

        int gid = 1; // encoding starts from GID 1
        final int numColumns = 4;
        int columnCounter = 0;

        for (int i = 0; i < nCodes; i++, gid++) {
            int code = readCard8();
            String glyphName = arrayGet(charset, gid);

            printPadded(Integer.toString(code), -1, s, maxCodeLength);
            s.append(" = [");
            printPadded(Integer.toString(gid), ']', s, maxGIDLength);
            s.append(" = /");
            printPadded(glyphName, ',', s, maxGlyphNameLength);
            s.append(' ');
            columnCounter += incrementColumnCounter(i == nCodes - 1);

            if (columnCounter >= numColumns) {
                s.append("\n  ");
                columnCounter = 0;
            }
        }
    }

    private void parseEncodingFormat1(String[] charset, StringBuilder s,
    int maxCodeLength, int maxGlyphNameLength, int maxGIDLength)
    {
        int nRanges = readCard8();

        s.append("(format 1; ").append(nRanges).append(" ranges; <code> = <[GID]> = <glyphName>)\n  ");

        int gid = 1; // encoding starts from GID 1
        final int numColumns = 4;
        int columnCounter = 0;

        for (int i = 0; i < nRanges; i++) {
            int first = readCard8();
            int left = readCard8();
            for (int j = 0; j <= left; j++) {
                int code = first;
                String glyphName = arrayGet(charset, gid);

                printPadded(Integer.toString(code), -1, s, maxCodeLength);
                s.append(" = [");
                printPadded(Integer.toString(gid), ']', s, maxGIDLength);
                s.append(" = /");
                printPadded(glyphName, ',', s, maxGlyphNameLength);
                s.append(' ');
                columnCounter += incrementColumnCounter(i == nRanges - 1);

                if (columnCounter >= numColumns) {
                    s.append("\n  ");
                    columnCounter = 0;
                }
                gid++;
                first++;
            }
        }
    }

    private void parseSupplementalEncoding(StringBuilder s,
    int maxCodeLength, int maxGlyphNameLength)
    {
        int nSups = readCard8();
        s.append("\n\nSupplemental encodings (").append(nSups);
        s.append(" mappings; <code> = <glyphName>):\n  ");

        final int numColumns = 4;
        int columnCounter = 0;

        for (int i = 0; i < nSups; i++) {
            int code = readCard8();
            int sid = readSID();
            String glyphName = sidToName(sid);

            printPadded(Integer.toString(code), -1, s, maxCodeLength);
            s.append(" = /");
            printPadded(glyphName, ',', s, maxGlyphNameLength);
            s.append(' ');
            columnCounter += incrementColumnCounter(i == nSups - 1);

            if (columnCounter >= numColumns) {
                s.append("\n  ");
                columnCounter = 0;
            }
        }
    }

    /**
     * Loads a Private DICT.
     * This dumps everything to {@code privateDictsDump}.
     * privateDictNo is the index of this Private DICT. Base fonts have only one
     * Private DICT but CIDFonts may have many because each FD has its own Private DICT.
     */
    private void loadPrivateDict(HashMap<String,String> fontDict, String heading,
    boolean printDivider, int privateDictNo) throws CFFParseException
    {
        int privateOffset = dictGetInt(fontDict, KEY_PRIVATE_OFFSET, -1);
        int privateSize = dictGetInt(fontDict, KEY_PRIVATE_SIZE, -1);
        printSectionHeading(heading, privateOffset, privateDictsDump, printDivider);

        if (privateSize <= 0) {
            if (privateSize == -1) {
                String privateError = "Private DICT is required";
                if (isCIDFont) {
                    privateError = privateError + " in Font DICT #" + privateDictNo;
                }
                addError(privateError);
            }
            privateDictsDump.append(
            "  <<\n" +
            "    % This private DICT is empty, so the following default entries are used:\n" +
            "    /BlueScale 0.039625  % default\n" +
            "    /BlueShift 7  % default\n" +
            "    /BlueFuzz 1  % default\n" +
            "    /ForceBold false  % default\n" +
            "    /LanguageGroup 0  % default\n" +
            "    /ExpansionFactor 0.06  % default\n" +
            "    /initialRandomSeed 0  % default\n" +
            "    /defaultWidthX 0  % default\n" +
            "    /nominalWidthX 0  % default\n" +
            "  >>\n");
            return;
        }

        HashMap<String,String> priv = new HashMap<String,String>();
        int endPos = privateOffset + privateSize;
        input.position(privateOffset);

        int ended = readDICT(priv, endPos, privateDictsDump, DICT_TYPE_PRIVATE);

        if (endPos != ended) {
            String msg = "Private DICT end offset is wrong (start = " + privateOffset +
                ", end = " + endPos + ")";
            privateDictsDump.append("    % ERROR: ").append(msg).append("\n");
            addError(msg);
        }

        int localSubrsOffset = 0, lsubrCount = -1;
        if (priv.containsKey(KEY_LOCAL_SUBRS_OFFSET)) {
            // Local Subrs offset is relative to the beginning of the Private DICT data.
            localSubrsOffset = dictGetInt(priv, KEY_LOCAL_SUBRS_OFFSET, NO_DEFAULT) + privateOffset;
            lsubrCount = getINDEXCount(localSubrsOffset, null);
        }
        localSubrINDEXOffsets[privateDictNo] = localSubrsOffset;
        localSubrCounts[privateDictNo] = lsubrCount;

        float nomWidthX = dictGetReal(priv, KEY_NOMINALWIDTHX, 0);
        nominalWidths[privateDictNo] = nomWidthX;

        float defWidthX = dictGetReal(priv, KEY_DEFAULTWIDTHX, 0);
        defaultWidths[privateDictNo] = defWidthX;
    }

    private int[] getISOAdobeCharset()
    {
        // ISOAdobe contains simply all SIDs 1...228.
        // We add .notdef at index 0.

        int[] isoAdobe = new int[229];
        for (int i = 0; i < 229; i++) {
            isoAdobe[i] = i;
        }
        return isoAdobe;
    }

    private int[] getExpertCharset()
    {
        return new int[] {
            0, // .notdef added
            1, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 13, 14, 15, 99, 239, 240,
            241, 242, 243, 244, 245, 246, 247, 248, 27, 28, 249, 250, 251, 252, 253, 254,
            255, 256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266, 109, 110, 267, 268,
            269, 270, 271, 272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 282, 283, 284,
            285, 286, 287, 288, 289, 290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300,
            301, 302, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313, 314, 315, 316,
            317, 318, 158, 155, 163, 319, 320, 321, 322, 323, 324, 325, 326, 150, 164, 169,
            327, 328, 329, 330, 331, 332, 333, 334, 335, 336, 337, 338, 339, 340, 341, 342,
            343, 344, 345, 346, 347, 348, 349, 350, 351, 352, 353, 354, 355, 356, 357, 358,
            359, 360, 361, 362, 363, 364, 365, 366, 367, 368, 369, 370, 371, 372, 373, 374,
            375, 376, 377, 378
        };
    }

    private int[] getExpertSubsetCharset()
    {
        return new int[] {
            0, // .notdef added
            1, 231, 232, 235, 236, 237, 238, 13, 14, 15, 99, 239, 240, 241, 242, 243, 244,
            245, 246, 247, 248, 27, 28, 249, 250, 251, 253, 254, 255, 256, 257, 258, 259,
            260, 261, 262, 263, 264, 265, 266, 109, 110, 267, 268, 269, 270, 272, 300, 301,
            302, 305, 314, 315, 158, 155, 163, 320, 321, 322, 323, 324, 325, 326, 150, 164,
            169, 327, 328, 329, 330, 331, 332, 333, 334, 335, 336, 337, 338, 339, 340, 341,
            342, 343, 344, 345, 346
        };
    }

    private String[] convertPredefinedCharset(int[] sids) throws CFFParseException
    {
        // Converts SIDs to Strings.

        int n = sids.length;
        String[] names = new String[n];

        for (int i = 0; i < n; i++) {
            if (isCIDFont) {
                names[i] = Integer.toString(sids[i]); // CID
            } else {
                names[i] = sidToName(sids[i]);
            }
        }
        return names;
    }

    private String sidToString(int sid)
    {
        // Returns a standard string or one from String INDEX.

        if (sid >= 391) {
            sid -= 391;
            if (sid < nonStandardStrings.length) {
                return nonStandardStrings[sid];
            }
            addError("SID value " + sid + " is out of bounds");
            return "ERROR: SID value out of bounds!";
        } else if (sid < 0) {
            addError("SID value " + sid + " is negative");
            return "ERROR: SID value is negative!";
        } else {
            return standardStrings[sid];
        }
    }

    private String sidToName(int sid)
    {
        return sidToString(sid);
    }

    private int incrementColumnCounter(boolean isLast)
    {
        final int LONG_FORMAT_LARGE_VALUE = 1000000;
        return isLongFormat && !isLast ? LONG_FORMAT_LARGE_VALUE : 1;
    }

    private String arrayGet(String[] arr, int idx)
    {
        if (arr == null) {
            return ".notdef";
        }
        if (0 <= idx && idx < arr.length) {
            return arr[idx];
        }
        return ".notdef";
    }

    private int dictGetInt(HashMap<String,String> dict, String key, int defaultValue)
    throws CFFParseException
    {
        // Specify NO_DEFAULT as defaultValue if the entry has no default value.

        String val = dict.get(key);
        if (val == null) {
            if (defaultValue == NO_DEFAULT) {
                throw new CFFParseException("DICT key " + key + " is missing");
            }
            return defaultValue;
        }

        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException ex) {
            throw new CFFParseException("Invalid integer '" + val + "'");
        }
    }

    private float dictGetReal(HashMap<String,String> dict, String key, float defaultValue)
    throws CFFParseException
    {
        // Specify NO_DEFAULT as defaultValue if the entry has no default value.

        String val = dict.get(key);
        if (val == null) {
            if (defaultValue == NO_DEFAULT) {
                throw new CFFParseException("DICT key " + key + " is missing");
            }
            return defaultValue;
        }

        try {
            return Float.parseFloat(val);
        } catch (NumberFormatException ex) {
            throw new CFFParseException("Invalid real number '" + val + "'");
        }
    }

    /**
     * Loads the FDSelect mapping data to the instance variable {@code fdSelect}
     * and returns its textual dump.
     */
    private String parseFDSelect(int fdSelectOff, int numGlyphs, int numFDs)
    throws CFFParseException
    {
        // .notdef *is* included in FDSelect, so the number of FD indices equals
        // numGlyphs, not numGlyphs - 1.

        StringBuilder s = new StringBuilder();
        input.position(fdSelectOff);
        printSectionHeading("FDSelect", s);
        int format = readCard8();

        if (format == 0) {
            readFDSelectFormat0(numGlyphs, s, numFDs);
        } else if (format == 3) {
            readFDSelectFormat3(numGlyphs, s);
        } else {
            throw new CFFParseException("Invalid FDSelect format " + format);
        }

        return s.toString();
    }

    private void readFDSelectFormat0(int numGlyphs, StringBuilder s, int numFDs)
    {
        s.append("  (format 0; [GID] = <FD#>):\n  ");

        int maxGIDLength = getLengthAsString(numGlyphs);
        int maxFDLength = getLengthAsString(numFDs);
        final int numColumns = 4;
        int columnCounter = 0;

        for (int gid = 0; gid < numGlyphs; gid++) {
            int fdIdx = readCard8();
            fdSelect.put(gid, fdIdx);

            printPadded("[" + gid + "]", -1, s, maxGIDLength);
            s.append(" = ");
            printPadded(Integer.toString(fdIdx), ',', s, maxFDLength);
            s.append(' ');
            columnCounter += incrementColumnCounter(gid == numGlyphs - 1);

            if (columnCounter >= numColumns) {
                s.append("\n  ");
                columnCounter = 0;
            }
        }

        s.append('\n');
    }

    private void readFDSelectFormat3(int numGlyphs, StringBuilder s)
    {
        s.append("  (format 3)\n");
        int nRanges = readCard16();
        if (nRanges == 0) {
            return;
        }
        final int sentinel = numGlyphs;
        int firstGid = readCard16();
        if (firstGid >= sentinel) {
            // error
            return;
        }

        while (true) {
            int fdIdx = readCard8();
            int lastGid = readCard16(); // actually the last GID is lastGid - 1

            s.append("  FD #").append(fdIdx).append(" for GIDs ");
            s.append(firstGid).append("...").append(lastGid - 1).append('\n');

            for (int gid = firstGid; gid < lastGid; gid++) {
                fdSelect.put(gid, fdIdx);
            }

            if (lastGid >= sentinel) {
                break;
            }
            firstGid = lastGid;
        }

        s.append('\n');
    }

    private void readCharStrings(HashMap<String,String> fontDict, String[] charset,
    int singleGidToDump) throws CFFParseException
    {
        StringBuilder s = new StringBuilder();

        input.position( dictGetInt(fontDict, KEY_CHARSTRINGS_OFFSET, NO_DEFAULT) );
        printSectionHeading("CharStrings INDEX", s);

        int numGlyphs = readCard16();
        if (numGlyphs <= 0) {
            // At least .notdef is needed.
            throw new CFFParseException("Font has no charstrings");
        }

        int offSize = readOffSize();
        int offArrStart = input.position();
        int offRef = getOffRef(offArrStart, offSize, numGlyphs);

        s.append("  count: ").append(numGlyphs);
        s.append(", offSize: ").append(offSize).append('\n');
        validateAndPrintINDEXOffsets(numGlyphs, offSize, s, offArrStart, offRef, "CharStrings");

        if (!dumpCharstringsAndSubrs && dumpOnlyThisGlyph == null) {
            s.append("  <CharStrings dumping is disabled>\n");
            charStringsDump = s.toString();
            return;
        }

        if (isCIDFont) {
            s.append("  ([GID] <CID> (offset): <charstring>)\n");
        } else {
            s.append("  ([GID] <glyphName> (offset): <charstring>)\n");
        }

        int startGid = 0, endGid = numGlyphs - 1;
        if (singleGidToDump > -1) {
            if (singleGidToDump >= numGlyphs) {
                throw new CFFParseException("Glyph index " + singleGidToDump + " is out of bounds");
            }
            s.append("  <Dumping only one chastring as requested>\n");
            startGid = singleGidToDump;
            endGid = singleGidToDump;
        }

        for (int gid = startGid; gid <= endGid; gid++) {
            dumpCharString(gid, offArrStart, offSize, offRef, charset, s);
        }

        charStringsDump = s.toString();
    }

    private void dumpCharString(int gid, int offArrStart, int offSize, int offRef,
    String[] charset, StringBuilder s) throws CFFParseException
    {
        unlimit();
        input.position(offArrStart + gid * offSize);
        int off = readOffset(offSize);
        int nextOff = readOffset(offSize);
        // int len = nextOff - off;
        int startOff = off + offRef;
        int endOff = nextOff + offRef;
        s.append("  [").append(gid).append("] ");
        String cidOrGlyphName = arrayGet(charset, gid);
        s.append(isCIDFont ? "CID " : "/").append(cidOrGlyphName);
        s.append(' ');
        printOffset(startOff, s);
        s.append(":\n");
        input.position(startOff);

        int fdIndex = isCIDFont ? getFDIndexForGid(gid) : 0;
        GlyphStatus gs = new GlyphStatus(gid, fdIndex, this);

        type2Dumper.clearType2Stack();
        type2Dumper.foundEndChar = false;
        type2Dumper.executeCharString(s, gs, endOff, false);

        if (gs.stemHintCount > MAX_STEM_HINTS) {
            s.append("    % ERROR: Glyph has too many stem hints\n");
            addError("Glyph has too many stem hints");
        }
        if (!type2Dumper.foundEndChar) {
            // Note the {@code endchar} may appear in a (nested) subroutine.
            s.append("    % ERROR: Glyph does not end with 'endchar'\n");
            addError("Glyph does not end with 'endchar'");
        }
    }

    private void appendMessages()
    {
        if (!errors.isEmpty()) {
            sbMain.append("Font has errors:\n");
            for (String e : errors.keySet()) {
                sbMain.append("ERROR: ").append(e);
                Integer count = errors.get(e);
                if (count > 1) {
                    sbMain.append(" (repeated ").append(count).append(" times)");
                }
                sbMain.append('\n');
            }
        }

        sbMain.append("Info messages:\n");
        boolean hasInfo = false;

        if (unusedGlobalSubrs > 0) {
            sbMain.append("Info: Font has ").append(unusedGlobalSubrs).
                append(" unused global subroutine(s).\n");
            hasInfo = true;
        }
        if (unusedLocalSubrs > 0) {
            sbMain.append("Info: Font has ").append(unusedLocalSubrs).
                append(" unused local subroutine(s).\n");
            hasInfo = true;
        }
        if (isCIDFont) {
            sbMain.append("Info: This is a CIDFont.\n");
            hasInfo = true;
        }
        if (type2Dumper.containsSeacs) {
            sbMain.append("Info: Operator endchar is used as \"seac\".\n");
            hasInfo = true;
        }
        if (containsSupplementalEncodings) {
            sbMain.append("Info: Font uses supplemental encoding.\n");
            hasInfo = true;
        }
        if (type2Dumper.containsFlex) {
            sbMain.append("Info: Font contains flex segments\n");
            hasInfo = true;
        }
        if (type2Dumper.unusedSubrDumpInterruptedByHintmask) {
            sbMain.append(
                "Info: When dumping subroutines that are never called, hintmask or cntrmask was\n" +
                "      encountered and subroutine dump was truncated. The number of mask bytes\n" +
                "      following those operators cannot be known reliably if subroutine\n" +
                "      is never called.\n"
            );
            hasInfo = true;
        }
        if (hasAnyNumOperandsErrorsInUnusedSubrs) {
            sbMain.append(
                "Info: There were one or more \"" + Type2CharStringDump.INVALID_NUM_OPERANDS_TEXT + "\" errors\n" +
                "      in unused subroutines. These might not be real errors but they\n" +
                "      are the side effect of dumping subroutines without calling them.\n"
            );
            hasInfo = true;
        }

        if (!hasInfo) {
            sbMain.append("<None>\n");
        }
    }

    private void printOffset(int off, StringBuilder s)
    {
        if (off < 0) {
            s.append("(OFFSET_ERROR)");
            return;
        }

        String hex = Integer.toHexString(off);
        s.append("(0x");
        // Make hex offset 8 chars long
        for (int i = hex.length(); i < 8; i++) {
            s.append('0');
        }
        s.append(hex);
        s.append(')');
    }

    private void printCurrentOffset(StringBuilder s)
    {
        printOffset(input.position(), s);
    }

    /**
     * Set position before calling this.
     */
    private void printSectionHeading(String name, StringBuilder s)
    {
        printSectionHeading(name, input.position(), s, true);
    }

    private void printSectionHeading(String name, int offset, StringBuilder s,
    boolean printDivider)
    {
        if (printDivider) {
            s.append(SECTION_DIVIDER);
        }
        s.append(name);
        s.append(' ');
        printOffset(offset, s);
        s.append(":\n");
    }

    private static byte[] findCffDataInOtfFile(File file, int[] retOffsetAndLength)
    throws IOException
    {
        RandomAccessFile raf = null;

        try {
            raf = new RandomAccessFile(file, "r");
            int cffOffset = 0, cffLength = 0;
            boolean found = true;

            int sfntVersion = raf.readInt();
            // sfnt version must be "OTTO"
            if (sfntVersion != OTF_OTTO) {
                throw new FileFormatException(sfntVersion);
            }

            int numTables = raf.readUnsignedShort();
            raf.readUnsignedShort(); // searchRange
            raf.readUnsignedShort(); // entrySelector
            raf.readUnsignedShort(); // rangeShift

            for (int i = 0; i < numTables; i++) {
                int tag = raf.readInt(); // tag
                raf.readInt(); // checkSum
                int off = raf.readInt(); // offset (actually ULONG)
                int len = raf.readInt(); // length (actually ULONG)
                if (tag == 0x43464620) { // "CFF "
                    cffOffset = off;
                    cffLength = len;
                    found = true;
                    break;
                }
            }

            if (!found) {
                throw new CFFParseException("Cannot find 'CFF ' table in OpenType file");
            }

            retOffsetAndLength[0] = cffOffset;
            retOffsetAndLength[1] = cffLength;
            raf.seek(cffOffset);
            byte[] arr = new byte[cffLength];
            raf.read(arr);
            return arr;

        } finally {
            try {
                if (raf != null) {
                    raf.close();
                }
            } catch (Exception ex) {
                //
            }
        }
    }

    /**
     * Returns the FD index that corresponds to the specified GID in FDSelect.
     */
    private int getFDIndexForGid(int gid) throws CFFParseException
    {
        if (!isCIDFont) {
            return 0;
        }
        if (fdSelect == null) {
            throw new CFFParseException("FDSelect is missing");
        }
        Integer fdIdx = fdSelect.get(gid);
        if (fdIdx == null) {
            throw new CFFParseException("Cannot find FD index for GID " + gid);
        }
        return fdIdx;
    }

    private int computeSubrBias(int numSubrs)
    {
        if (numSubrs < 1240) {
            return 107;
        }
        if (numSubrs < 33900) {
            return 1131;
        }
        return 32768;
    }

    /**
     * Reads the count field of an INDEX structure.
     * If offsetToINDEX is &lt; 0, this starts reading the INDEX from the current position.
     * If returnOffSize != null, this reads and returns also the OffSize value.
     */
    private int getINDEXCount(int offsetToINDEX, int[] returnOffSize) throws CFFParseException
    {
        // Note: If INDEX's count is 0, there is no OffSize field.

        int posOrig = input.position();
        if (offsetToINDEX >= 0) {
            input.position(offsetToINDEX);
        }
        int count = readCard16();
        if (count > 0 && returnOffSize != null) {
            returnOffSize[0] = readOffSize();
        }
        input.position(posOrig);
        return count;
    }

    /**
     * Executes a subroutine.
     * Bias must have been added to {@code subrNo}.
     * {@code type} is String "subr" or "gsubr".
     */
    private int executeSubr(int subrNo, int subrINDEXOffset, StringBuilder tmp,
    String type, StringBuilder s, boolean isLocal, GlyphStatus gs,
    boolean isUnused, String unusedInfo) throws CFFParseException
    {
        int posOrig = input.position();
        unlimit();
        input.position(subrINDEXOffset);
        int count = readCard16();

        if (subrNo < 0 || subrNo >= count) {
            s.append("call").append(type).append("  % ").append(type).append("# ").append(subrNo);
            if (subrNo < 0) {
               s.append("  % ERROR: Subroutine index is negative!\n");
            } else {
               s.append("  % ERROR: Subroutine index is too large (");
               s.append(subrNo).append(" >= ").append(count).append(")!\n");
            }
            addError("Subroutine index out of bounds");
            input.position(posOrig);
            return Type2CharStringDump.CHARSTRING_END_CONTINUE;
        }

        int offSize = readOffSize();
        int offArrStart = input.position();
        int offRef = getOffRef(offArrStart, offSize, count);
        input.position(offArrStart + subrNo * offSize);
        int startOff = readOffset(offSize) + offRef;
        int endOff = readOffset(offSize) + offRef;
        // int len = endOff - startOff;

        printTitleForSubr(subrNo, startOff, tmp);
        if (isUnused && unusedInfo != null) {
            tmp.append(unusedInfo);
        }

        input.position(startOff);
        int ret = type2Dumper.executeCharString(tmp, gs, endOff, isUnused);

        if (ret == Type2CharStringDump.CHARSTRING_END_RETURN) {
            ret = Type2CharStringDump.CHARSTRING_END_CONTINUE;
        }

        if (!isUnused) {
            s.append("call").append(type).append("  % ").append(type).append("# ").append(subrNo);
            if (isLocal && isCIDFont) {
                s.append(" (FD #").append(gs.fdIndex).append(')');
            }
            s.append('\n');
        }
        input.position(posOrig);
        return ret;
    }

    int executeGlobalSubr(int subrNo, StringBuilder s, GlyphStatus gs, boolean isUnused,
    String unusedInfo) throws CFFParseException
    {
        subrNo = subrNo + (isUnused ? 0 : globalSubrBias);
        StringBuilder tmp = new StringBuilder(); // subroutine charstring is dumped into this

        int ret = executeSubr(
            subrNo, globalSubrINDEXOffset, tmp, "gsubr", s, false, gs, isUnused, unusedInfo
        );

        globalSubrDumps[subrNo] = tmp.toString();
        return ret;
    }

    int executeLocalSubr(int subrNo, StringBuilder s, GlyphStatus gs, boolean isUnused,
    String unusedInfo) throws CFFParseException
    {
        int localSubrINDEXOff = localSubrINDEXOffsets[gs.fdIndex];
        int count = localSubrCounts[gs.fdIndex];
        int localsubrBias = isUnused ? 0 : computeSubrBias(count);
        subrNo = subrNo + localsubrBias;
        StringBuilder tmp = new StringBuilder(); // subroutine charstring is dumped into this

        int ret = executeSubr(
            subrNo, localSubrINDEXOff, tmp, "subr", s, true, gs, isUnused, unusedInfo
        );

        ArrayList<String> list = localSubrDumps.get(gs.fdIndex);
        while (subrNo >= list.size()) {
            list.add(null);
        }
        list.set(subrNo, tmp.toString());

        if (localSubrINDEXHeaders[gs.fdIndex] == null) {
            // Print INDEX header.
            int posOrig = input.position();
            tmp.setLength(0);
            input.position(localSubrINDEXOff);
            count = readCard16();
            int offSize = readOffSize();
            tmp.append(getLocalSubrINDEXHeader(localSubrINDEXOff, gs.fdIndex, count, offSize));
            localSubrINDEXHeaders[gs.fdIndex] = tmp.toString();
            input.position(posOrig);
        }

        return ret;
    }

    private String getLocalSubrINDEXHeader(int offset, int fdIdx, int count, int offSize)
    throws CFFParseException
    {
        StringBuilder s = new StringBuilder();

        s.append(SECTION_DIVIDER);
        s.append("Local Subr INDEX");

        if (isCIDFont) {
            s.append(" for Font DICT #").append(fdIdx);
        }
        if (offset > 0) {
            s.append(' ');
            printOffset(offset, s);
        }
        s.append(":\n");
        if (count >= 0) {
            s.append("  count: ").append(count);
        }
        if (offSize > 0) {
            s.append(", offSize: ").append(offSize);
        }
        if (count >= 0) {
            s.append(", subroutine bias: ").append(computeSubrBias(count)).append('\n');
        }

        if (count > 0) {
            int curPos = input.position();
            input.position(offset);
            validateAndPrintINDEXOffsets(-1, 0, s, 0, 0, "Local Subr");
            input.position(curPos);
        }
        return s.toString();
    }

    /**
     * Enables the dumping of charstrings and subroutines.
     * If chastrings are not executed and dumped, subroutines cannot be dumped either
     * because subroutine dumps are generated during the execution of glyph charstrings.
     */
    public void enableDumpingCharstringsAndSubrs(boolean b)
    {
        dumpCharstringsAndSubrs = b;
        if (dumpOnlyThisGlyph != null) {
            dumpCharstringsAndSubrs = false;
        }
    }

    /**
     * Enables the dumping of offset arrays of INDEX structures.
     */
    public void enableDumpingOffsetArrays(boolean b)
    {
        enablePrintingOffsetArrays = b;
    }

    /**
     * Enables the dumping of a single charstring that has the specified identifier.
     *
     * @param id a glyph index (e.g. "25"), a glyph name (e.g. "/exclam") or a CID (e.g. "CID1200")
     */
    public void dumpOnlyOneCharstring(String id)
    {
        dumpOnlyThisGlyph = id;
        dumpCharstringsAndSubrs = false;
    }

    /**
     * Enables the storing of Charset data and glyph advance widths.
     */
    public void enableStoringCharsetAndMetrics()
    {
        enableStoringCharsetAndMetrics = true;
        gidToWidth = new HashMap<Integer,Float>();
    }

    /**
     * Returns true if any errors were found.
     */
    public boolean hasErrors()
    {
        return !errors.isEmpty();
    }

    /**
     * Returns a string of error messages, or null if no errors occurred.
     */
    public String getErrors()
    {
        if (errors.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (String e : errors.keySet()) {
            sb.append(e);
            Integer count = errors.get(e);
            if (count > 1) {
                sb.append(" (repeated ").append(count).append(" times)");
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Returns true if the CFF data represents a CIDFont, or false if it is
     * a base font.
     */
    public boolean isCIDFont()
    {
        return isCIDFont;
    }

    /**
     * Returns the number of glyphs.
     */
    public int getNumberOfGlyphs()
    {
        return numberOfGlyphs;
    }

    /**
     * Returns an array of glyph names. The index of the array is the glyph index
     * for each glyph. If isCIDFont() returns true, the array contains CID values
     * (string representations of integers).
     */
    public String[] getGlyphNames()
    {
        return charsetArray;
    }

    /**
     * Returns glyph advance widths. The keys are glyph indices.
     * The result is available only if
     * {@link #enableStoringCharsetAndMetrics enableStoringCharsetAndMetrics()}
     * was called before starting the dumping. Otherwise this returns null.
     */
    public HashMap<Integer,Float> getGlyphWidths()
    {
        return gidToWidth;
    }

    /**
     * Returns the font name.
     */
    public String getFontName()
    {
        return fontName;
    }

    private void parseFontBBox(StringBuilder s, HashMap<String,String> d, int dictType)
    {
        if (dictType == DICT_TYPE_TOP) {
            int count = dictStack.size();
            topDictFontBBox = new double[count];
            for (int i = 0; i < count; i++) {
                DictValue e = dictStack.get(i);
                topDictFontBBox[i] = e.getReal();
            }
        }

        dictArrayEntry(KEY_FONTBBOX, s, d, 4);
    }

    /**
     * Returns the FontBBox as stored in the Top DICT.
     */
    public double[] getTopDictFontBBox()
    {
        return topDictFontBBox;
    }

    void addError(String msg)
    {
        if (
            silenceNumOperandsErrorsInUnusedSubr &&
            msg.indexOf(Type2CharStringDump.INVALID_NUM_OPERANDS_TEXT) > -1
        ) {
            hasAnyNumOperandsErrorsInUnusedSubrs = true;
            return;
        }
        Integer count = errors.get(msg);
        if (count != null) {
            errors.put(msg, Integer.valueOf(count + 1));
        } else {
            errors.put(msg, Integer.valueOf(1));
        }
    }

    /**
     * Returns the number of errors in the font.
     */
    public int getNumberOfErrors()
    {
        return errors.size();
    }

    private static int getLengthAsString(int value)
    {
        // Positive integers 0...65535.

        if (value >= 10000) {
            return 5;
        }
        if (value >= 1000) {
            return 4;
        }
        if (value >= 100) {
            return 3;
        }
        if (value >= 10) {
            return 2;
        }
        return 1;
    }

    private int[] getOffsetAndLengthOfSubroutine(int subrNo, boolean isLocal, int fdIdx)
    throws CFFParseException
    {
        int subrINDEXOffset;
        if (isLocal) {
            subrINDEXOffset = localSubrINDEXOffsets[fdIdx];
        } else {
            subrINDEXOffset = globalSubrINDEXOffset;
        }

        int posOrig = input.position();
        unlimit();
        input.position(subrINDEXOffset);
        int count = readCard16();
        if (subrNo < 0 || subrNo >= count) {
            return new int[]{ -1, 0 };
        }
        int offSize = readOffSize();
        int offArrStart = input.position();
        int offRef = getOffRef(offArrStart, offSize, count);
        input.position(offArrStart + subrNo * offSize);
        int startOff = readOffset(offSize) + offRef;
        int endOff = readOffset(offSize) + offRef;
        int len = endOff - startOff;

        input.position(posOrig);
        return new int[]{ startOff, len };
    }

    /**
     * Computes "the point zero" of offsets in an INDEX. All offsets are relative
     * to the returned value.
     */
    private static int getOffRef(int offArrStart, int offSize, int count)
    {
        return offArrStart + offSize * (count + 1) - 1;
    }

    /**
     * Sets long or wide dump format for Charset, Encoding, FDSelect, and
     * offset arrays of INDEXes.
     * If long format is enabled, the mentioned blocks are dumped one entry on each line.
     * If wide format is enabled, the blocks are dumped many entries
     * per line as long as a reasonable line length is achieved.
     *
     * @param b true for long format, false for wide format
     */
    public void setLongFormat(boolean b)
    {
        isLongFormat = b;
    }

    /**
     * Enable explanations of mask bits of operators hintmask and cntrmask.
     */
    public void setExplainHintMaskBits(boolean doExplainHintMaskBits)
    {
        type2Dumper.setExplainHintMaskBits(doExplainHintMaskBits);
    }

    /**
     * Enable dumping subroutines that are never called.
     */
    public void setEnableDumpingUnusedSubroutines(boolean enable)
    {
        enableDumpingUnusedSubrs = enable;
    }

    int getGlobalSubrBias()
    {
        return globalSubrBias;
    }

    int getLocalSubrBias(int fdIdx)
    {
        int count = localSubrCounts[fdIdx];
        return computeSubrBias(count);
    }
}
