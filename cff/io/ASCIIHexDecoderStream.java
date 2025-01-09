/* Copyright 2024 Jani Pehkonen
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

package cff.io;

import java.io.InputStream;
import java.io.IOException;

/**
 * ASCIIHex decoder stream.
 */

public class ASCIIHexDecoderStream extends InputStream
{
    private InputStream input;
    private boolean error;
    private boolean endOfData;

    public ASCIIHexDecoderStream(InputStream is)
    {
        input = is;
    }

    public int read() throws IOException
    {
        if (error || endOfData) {
            return -1;
        }

        try {
            int hexCount = 1;
            int b = 0;
            char ch = 0, c1 = 0, c2 = 0;

            // Read a pair of hex chars ignoring white space
            while (true) {
                b = input.read();
                if (b == -1 || b == '>') {
                    endOfData = true;
                    if (hexCount == 2) {
                        // If ASCIIHexDecode encounters EOD when it has read an odd number of
                        // hex digits, it will behave as if it had read an additional '0' digit.
                        int dec = hexToByte(c1, '0');
                        return dec & 0xFF;
                    }
                    return -1;
                }

                ch = (char)b;
                if (isWhitespace(ch)) {
                    continue;
                }

                if (hexCount == 1){
                    c1 = ch;
                    hexCount++;
                } else {
                    c2 = ch;
                    break;
                }
            }

            int dec = hexToByte(c1, c2);
            return dec & 0xFF;

        } catch (IOException ioe) {
            error = true;
            endOfData = true;
            throw ioe;
        }

    }

    private int hexToByte(char c1, char c2) throws IOException
    {
        if (isHex(c1) && isHex(c2)) {
            return 16 * hexToDec(c1) + hexToDec(c2);
        }
        throw new IOException("Illegal hex character");
    }

    private boolean isHex(char c)
    {
        return ('0' <= c && c <= '9') || ('A' <= c && c <= 'F') || ('a' <= c && c <= 'f');
    }

    private int hexToDec(char hex)
    {
        int dec = 0;

        if ('0' <= hex && hex <= '9') {
            dec = (int)hex - 48; // 0x0...0x9 = 0...9
        } else if ('A' <= hex && hex <= 'F') {
            dec = (int)hex - 55; // 0xA...0xF = 10...15
        } else if ('a' <= hex && hex <= 'f') {
            dec = (int)hex - 87; // 0xa...0xf = 10...15
        }

        return dec;
    }

    private boolean isWhitespace(char c)
    {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\u0000' || c == '\f';
    }

    public void close() throws IOException
    {
        input.close();
    }

}
