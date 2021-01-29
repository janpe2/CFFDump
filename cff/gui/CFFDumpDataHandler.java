/* Copyright 2021 Jani Pehkonen
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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.zip.InflaterInputStream;
import cff.CFFDump;
import cff.io.ASCII85DecoderStream;

public class CFFDumpDataHandler
{
    private final File file;
    private final boolean isOTF;
    private final boolean isFlate;
    private final boolean isA85;
    private final int fileOffset;
    private final boolean analyzeCharstrings;

    CFFDumpDataHandler(File file, boolean isOTF, boolean isFlate, boolean isA85,
    String fileOffset, boolean analyzeCharstrings)
    throws NumberFormatException
    {
        this.file = file;
        this.isOTF = isOTF;
        this.isFlate = isFlate;
        this.isA85 = isA85;
        this.fileOffset = Integer.parseInt(fileOffset);
        this.analyzeCharstrings = analyzeCharstrings;
    }

    String analyze() throws IOException
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
            String dump = "";

            try {
                cffDump.parseCFF();
                dump = cffDump.getResult();
            } catch (Exception ex) {
                dump += "\nException: " + ex + "\n";
                // ex.printStackTrace();
                if (cffDump.hasErrors()) {
                    dump += "\nThere we errors:\n" + cffDump.getErrors();
                }
            }

            return dump;

        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    private InputStream applyFilters(InputStream is)
    {
        if (isFlate && isA85) {
            return new InflaterInputStream(new ASCII85DecoderStream(is));
        }
        if (isFlate) {
            return new InflaterInputStream(is);
        }
        if (isA85) {
            return new ASCII85DecoderStream(is);
        }
        return is;
    }
}
