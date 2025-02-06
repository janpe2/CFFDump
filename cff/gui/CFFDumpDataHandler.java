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

package cff.gui;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.zip.InflaterInputStream;
import cff.CFFDump;
import cff.io.ASCII85DecoderStream;
import cff.type1.Type1Dump;
import cff.FileFormatException;

public class CFFDumpDataHandler
{
    public static enum PDFFilter
    {
        NONE,
        ASCII85,
        FLATE,
        ASCII85_FLATE,
        ASCIIHEX
    }

    private final File file;
    private final String fontType;
    private final boolean isOTF;
    private final PDFFilter filter;
    private final int fileOffset;
    private final boolean analyzeCharstrings;
    private final boolean explainHintMaskBits;
    private final boolean dumpUnusedSubrs;

    CFFDumpDataHandler(File file, String fontType, boolean isOTF, PDFFilter filter,
    String fileOffset, boolean analyzeCharstrings, boolean explainHintMaskBits,
    boolean dumpUnusedSubrs)
    throws NumberFormatException
    {
        this.file = file;
        this.fontType = fontType;
        this.isOTF = isOTF;
        this.filter = filter;
        this.fileOffset = Integer.parseInt(fileOffset);
        this.analyzeCharstrings = analyzeCharstrings;
        this.explainHintMaskBits = explainHintMaskBits;
        this.dumpUnusedSubrs = dumpUnusedSubrs;
    }

    DumpResult analyze() throws IOException
    {
        if ("type1".equals(fontType)) {
            return analyzeType1();
        } else {
            return analyzeCFF();
        }
    }

    private DumpResult analyzeType1() throws IOException
    {
        Type1Dump type1Dump;
        InputStream is = null;

        try {
            is = new FileInputStream(file);
            if (fileOffset > 0) {
                is.skip(fileOffset);
            }
            is = applyFilters(is);
            type1Dump = new Type1Dump(is, file, fileOffset);
            type1Dump.enableDumpingCharstringsAndSubrs(analyzeCharstrings);
            String dump = "";

            try {
                type1Dump.parseFont();
                dump = type1Dump.getResult();
                String specialFeatures = type1Dump.getSpecialFeatures();
                if (specialFeatures != null) {
                    dump = dump + "\n\n" + specialFeatures;
                }
            } catch (FileFormatException ex) {
                dump += "\n" + ex.getMessage();
            } catch (Exception ex) {
                dump += "\nException: " + ex + "\n";
            }

            return new DumpResult(dump, type1Dump.hasErrors());

        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    private DumpResult analyzeCFF() throws IOException
    {
        CFFDump cffDump;
        InputStream is = null;

        try {
            if (isOTF) {
                cffDump = CFFDump.createOTFDumper(file);
            } else {
                is = new FileInputStream(file);
                if (fileOffset > 0) {
                    is.skip(fileOffset);
                }
                is = applyFilters(is);
                cffDump = new CFFDump(is, file);
            }

            // We can now close the stream.
            if (is != null) {
                is.close();
                is = null;
            }

            cffDump.enableDumpingCharstringsAndSubrs(analyzeCharstrings);
            cffDump.setExplainHintMaskBits(explainHintMaskBits);
            cffDump.setEnableDumpingUnusedSubroutines(dumpUnusedSubrs);
            String dump = "";

            try {
                cffDump.parseCFF();
                dump = cffDump.getResult();
            } catch (FileFormatException ex) {
                dump += "\n" + ex.getMessage();
            } catch (Exception ex) {
                dump += "\nException: " + ex + "\n";
                // ex.printStackTrace();
                if (cffDump.hasErrors()) {
                    dump += "\nThere we errors:\n" + cffDump.getErrors();
                }
            }

            return new DumpResult(dump, cffDump.hasErrors());

        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    private InputStream applyFilters(InputStream is) throws IOException
    {
        switch (filter) {
            case NONE:
                return is;
            case ASCII85:
                return new ASCII85DecoderStream(is);
            case FLATE:
                return new InflaterInputStream(is);
            case ASCII85_FLATE:
                is = new ASCII85DecoderStream(is);
                return new InflaterInputStream(is);
            case ASCIIHEX:
                byte[] bytes = CFFDump.hexToBytes(is);
                return new ByteArrayInputStream(bytes);
        }
        return is;
    }

    public static class DumpResult
    {
        public final String dump;
        public final boolean hasErrors;

        public DumpResult(String dump, boolean hasErrors)
        {
            this.dump = dump;
            this.hasErrors = hasErrors;
        }
    }
}
