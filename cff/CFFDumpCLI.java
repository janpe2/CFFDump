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

import java.io.File;
import java.io.IOException;
import cff.gui.CFFDumpFrame;
import cff.io.Filters;
import cff.type1.Type1Dump;

/**
 * CLI-related functions for CFF dumper.
 */
public class CFFDumpCLI
{
    public static void main(String[] args) throws IOException
    {
        if (args.length < 1) {
            printUsage(1);
        }

        String file = null;
        boolean isOpenType = false;
        boolean enableCharstringsDump = false;
        boolean isGUI = false;
        boolean enableOffsetsDump = false;
        boolean isLongFormat = false;
        int filter = Filters.FILTER_NONE;
        int startOffset = 0;
        String singleGlyph = null;
        boolean version = false;
        boolean isType1 = false;
        boolean showHelp = false;
        int numberOfFilters = 0;
        boolean explainHintMaskBits = false;
        boolean dumpUnusedSubrs = false;

        for (int i = 0, numArgs = args.length; i < numArgs; i++) {
            String arg = args[i];
            if (arg.equals("-start")) {
                if (i + 1 >= numArgs) {
                    printUsage(1);
                }
                i++;
                try {
                    startOffset = Integer.parseInt(args[i]);
                } catch (NumberFormatException nfe) {
                    printUsage(1);
                }
            } else if (arg.equals("-g")) {
                if (i + 1 >= numArgs) {
                    printUsage(1);
                }
                i++;
                singleGlyph = args[i];
            } else if (arg.equals("-otf")) {
                isOpenType = true;
            } else if (arg.equals("-deflate")) {
                filter = Filters.FILTER_DEFLATE;
                numberOfFilters++;
            } else if (arg.equals("-hex")) {
                filter = Filters.FILTER_ASCII_HEX;
                numberOfFilters++;
            } else if (arg.equals("-c")) {
                enableCharstringsDump = true;
            } else if (arg.equals("-gui") || arg.equals("gui")) {
                isGUI = true;
            } else if (arg.equals("-offsets")) {
                enableOffsetsDump = true;
            } else if (arg.equals("-long")) {
                isLongFormat = true;
            } else if (arg.equals("-version") || arg.equals("-v")) {
                version = true;
            } else if (arg.equals("-type1") || arg.equals("-t1")) {
                isType1 = true;
            } else if (arg.equals("-help")) {
                showHelp = true;
            } else if (arg.equals("-hm")) {
                explainHintMaskBits = true;
            } else if (arg.equals("-unsub")) {
                dumpUnusedSubrs = true;
            } else if (file == null) {
                file = args[i];
            } else {
                printUsage(1);
            }
        }

        if (version) {
            System.out.println("CFFDump version " + CFFDump.DUMPER_VERSION);
            System.exit(0);
            return;
        }
        if (showHelp) {
            printUsage(0);
            return;
        }
        if (isGUI) {
            CFFDumpFrame.launchGUI(file);
            return;
        }
        if (file == null) {
            printUsage(1);
        }
        if (isOpenType && filter != Filters.FILTER_NONE) {
            System.out.println("Filters cannot be applied to OpenType fonts.");
            System.exit(1);
        }
        if (numberOfFilters > 1) {
            System.out.println("Only one decoding filter is allowed.");
            System.exit(1);
        }

        String dump;
        String errors = "";

        if (isType1) {
            Type1Dump dumper = new Type1Dump(new File(file), startOffset, filter);
            dumper.enableDumpingCharstringsAndSubrs(enableCharstringsDump);
            dumper.parseFont();
            dump = dumper.getResult();
            String specialFeatures = dumper.getSpecialFeatures();
            if (specialFeatures != null) {
                dump = dump + "\n\n" + specialFeatures;
            }
        } else {
            CFFDump dumper;
            if (isOpenType) {
                dumper = CFFDump.createOTFDumper(new File(file));
            } else {
                dumper = new CFFDump(new File(file), startOffset, filter);
            }

            dumper.enableDumpingCharstringsAndSubrs(enableCharstringsDump);
            dumper.enableDumpingOffsetArrays(enableOffsetsDump);
            dumper.setLongFormat(isLongFormat);
            dumper.setExplainHintMaskBits(explainHintMaskBits);
            dumper.setEnableDumpingUnusedSubroutines(dumpUnusedSubrs);
            if (singleGlyph != null) {
                dumper.dumpOnlyOneCharstring(singleGlyph);
            }

            dumper.parseCFF();
            dump = dumper.getResult();
            errors = dumper.hasErrors() ? dumper.getErrors() : "";
        }

        if (errors != null && errors.length() > 0) {
            dump += "\nThere we errors:\n" + errors;
        }
        System.out.println(dump);
    }

    private static void printUsage(int exitStatus)
    {
        System.err.println(
            "Dumps CFF and Type1 fonts in text format.\n" +
            "java -jar CFFDump.jar [options] <input_file>\n" +
            "Options:\n" +
            "  -c               Enable dump of all charstrings and subroutines.\n" +
            "  -deflate         Input data is compressed by deflate.\n" +
            "  -g <id>          Dump only the charstring of the specified CFF glyph.\n" +
            "                   <id> is a glyph index (e.g. 25), a glyph name\n" +
            "                   (e.g. /exclam) or a CID (e.g. CID1200). (CFF only.)\n" +
            "  -gui             Launch graphical user interface. All other options \n" +
            "                   are ignored, except <input_file>.\n" +
            "  -help            Print this usage help text and exit.\n" +
            "  -hex             Input data is ASCII hex encoded.\n" +
            "  -hm              Explain mask bits of hintmask/cntrmask. (1-based\n" +
            "                   numbering; CFF only.)\n" +
            "  -long            Use long dump format in CFF Charset, Encoding, FDSelect,\n" +
            "                   and offset arrays of INDEXes. (CFF only.)\n" +
            "  -offsets         Dump offset arrays of INDEXes. (CFF only.)\n" +
            "  -otf             Input is an OpenType (.otf) font with CFF outlines.\n" +
            "  -start <offset>  Start offset of input data. Default: 0.\n" +
            "  -type1, -t1      Input is a Type1 font file in PFB, PFA or raw format.\n" +
            "                   (Raw is PFB without section headers.)\n" +
            "  -unsub           Try to dump also unused CFF subroutines.\n" +
            "  -version         Print dumper version and exit.\n" +
            "  <input_file>     Input file to be analyzed.\n"
        );
        System.exit(exitStatus);
    }

}
