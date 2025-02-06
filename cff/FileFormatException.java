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

import java.io.IOException;

/**
 * Exception that is thrown when CFF dump cannot recognize input file type.
 */

public class FileFormatException extends IOException
{
    public FileFormatException(int firstFourBytes)
    {
        super(checkDataFormat(firstFourBytes));
    }

    public FileFormatException(String message)
    {
        super(message);
    }

    private static String checkDataFormat(int firstFourBytes)
    {
        char[] chars = new char[4];
        for (int i = 0; i < 4; i++) {
            int shift = 24 - 8 * i;
            chars[i] = (char)((firstFourBytes >> shift) & 0xFF);
        }
        String firstFourStr = new String(chars);

        if (firstFourStr.startsWith("OTTO")) {
            return "Incorrect format selected. This might be an OpenType-CFF font.";
        }
        if (firstFourStr.startsWith("%!PS")) {
            return "Incorrect format selected. This might be a Type1 font.";
        }
        if (firstFourStr.startsWith("\u0080\u0001")) {
            return "Incorrect format selected. This might be a Type1 (.pfb) font.";
        }
        if (firstFourStr.startsWith("%PDF")) {
            return "When analyzing a font in PDF, start offset and data filter are needed.";
        }
        if (firstFourStr.startsWith("wOFF")) {
            return "WOFF is not supported";
        }
        if (firstFourStr.startsWith("wOF2")) {
            return "WOFF2 is not supported";
        }
        if (firstFourStr.startsWith("x")) {
            return "Check data filter. Flate decode might be needed.";
        }
        if (firstFourStr.startsWith("\u0001\u0000")) {
            return "Incorrect format selected. " +
                "This might be raw CFF data (or TrueType (unsupported)).";
        }
        return "Unsupported data format. Check also offset and data filter.";
    }
}