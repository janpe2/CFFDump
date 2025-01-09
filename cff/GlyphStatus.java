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

package cff;

/**
 * Stores information related to a Type2 glyph.
 */

class GlyphStatus
{
    final int gid;
    final int fdIndex;
    float width = 0;
    int stemHintCount = 0;
    boolean foundGlyphWidth = false;
    int subrLevel = 0;
    boolean widthWasPrinted = false;
    private String widthString;
    private CFFDump cffDump;
    boolean isUnusedSubr = false;

    GlyphStatus(int gid, int fdIndex, CFFDump cffDump)
    {
        this.gid = gid;
        this.fdIndex = fdIndex;
        this.cffDump = cffDump;
    }

    void setGlyphWidth(float w, StringBuilder s)
    {
        if (isUnusedSubr) {
            return;
        }
        float nomWidthX = cffDump.nominalWidths[fdIndex];
        float actualWidth = w + nomWidthX;
        this.width = actualWidth;
        this.foundGlyphWidth = true;
        widthString = "  % glyph width = " + doubleToString(w) +
            " + nominalWidthX = " + doubleToString(actualWidth);

        if (subrLevel == 0) {
            s.append(widthString);
            widthWasPrinted = true;
        }
        if (cffDump.gidToWidth != null) {
            cffDump.gidToWidth.put(gid, actualWidth);
        }
    }

    void setDefaultWidth(StringBuilder s)
    {
        if (isUnusedSubr) {
            return;
        }
        float defaultWidthX = cffDump.defaultWidths[fdIndex];
        this.width = defaultWidthX;
        this.foundGlyphWidth = true;
        widthString = "  % glyph width = defaultWidthX = " + doubleToString(defaultWidthX);

        if (subrLevel == 0) {
            s.append(widthString);
            widthWasPrinted = true;
        }
        if (cffDump.gidToWidth != null) {
            cffDump.gidToWidth.put(gid, defaultWidthX);
        }
    }

    void checkWidthFound(StringBuilder s, String op)
    {
        if (!foundGlyphWidth && !isUnusedSubr) {
            if (s.length() > 0 && s.charAt(s.length() - 1) == '\n') {
                s.setLength(s.length() - 1);
            }
            s.append(
                "  % ERROR! Glyph width should have been specified before this operator\n");
            cffDump.addError("Glyph width missing");
        }
    }

    void ensureWidthIsPrinted(StringBuilder s)
    {
        // Some fonts (like AGaramondPro-Regular.otf) specify glyph widths in subroutines.
        // We defer printing the width until the subroutine has been executed. It's
        // not a good idea to print the width in a subroutine dump because its value
        // may vary depending on which glyph called the subroutine.

        if (!widthWasPrinted && subrLevel == 0 && widthString != null && !isUnusedSubr) {
            s.append("  ").append(widthString).append('\n');
            widthWasPrinted = true;
        }
    }

    private String doubleToString(double f)
    {
        int i = (int)f;
        if (f == i) {
            return Integer.toString(i);
        }
        return Double.toString(f);
    }
}
