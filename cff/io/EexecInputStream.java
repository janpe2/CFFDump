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

import java.io.IOException;
import java.io.InputStream;

/**
 * Stream for reading eexec encrypted data of Type1 fonts.
 */

public class EexecInputStream extends InputStream
{
    private InputStream eexecInput;
    private boolean closed = false;
    private int r = 55665; // eexec encryption key

    public EexecInputStream(InputStream is)
    {
        try {
            // First skip all white space between the keyword eexec and the start of
            // the data. The first data byte cannot be white space (see Type 1 Spec.).
            int b00 = -1;
            for (int i = 0; i < 1000; i++) {
                int c = is.read();
                if (c == -1) {
                    break;
                }
                if (!isWhitespaceAfterTokenEexec((char)c)) {
                    b00 = c;
                    break;
                }
            }
            if (b00 == -1) {
                throw new IOException("cannot find start of eexec data");
            }

            // Read 4 bytes
            byte[] b = new byte[4];
            b[0] = (byte)b00; //b00 is the first one of the 4 bytes
            for (int i = 1; i <= 3; i++) {
                int c = is.read();
                if (c == -1) {
                    throw new IOException("unexpected end of eexec data");
                }
                b[i] = (byte)c;
            }

            // If any of the first 4 bytes is non-hex, the data format is binary
            boolean isBin = false;
            for (int i = 0; i < 4; i++) {
                if (!isHexDigit(byteToChar(b[i]))) {
                    isBin = true;
                }
            }

            // The first 4 bytes are garbage. (If hex coding is used, there are
            // 8 hex chars of garbage.) All 4 garbage bytes must be processed with
            // the decryption algorithm to initialize it properly.

            if (isBin) {
                eexecInput = is;
                for (int i = 0; i < 4; i++) {
                    decryptByte(b[i] & 0xFF);
                }
            } else {
                int b0, b1;
                // We get 2 garbage bytes from the `b` array
                b0 = hexToChar(byteToChar(b[0]), byteToChar(b[1])) & 0xFF;
                b1 = hexToChar(byteToChar(b[2]), byteToChar(b[3])) & 0xFF;
                decryptByte(b0);
                decryptByte(b1);
                eexecInput = new ASCIIHexDecoderStream(is);
                // Decrypt two more bytes
                read();
                read();
            }

        } catch (IOException ioe) {
            throw new RuntimeException(ioe.getMessage());
        }
    }

    public int read() throws IOException
    {
        if (closed) {
            return -1;
        }
        int b = eexecInput.read();
        if (b == -1) {
            closed = true;
            return -1;
        }
        b = decryptByte(b);
        return b;
    }

    private int decryptByte(int cipher)
    {
        cipher &= 0xFF;
        int plain = cipher ^ (r >> 8);
        r = ((cipher + r) * 52845 + 22719) & 0xFFFF;
        return plain & 0xFF;
    }

    public void close() throws IOException
    {
        eexecInput.close();
    }

    private static boolean isWhitespaceAfterTokenEexec(char c)
    {
        // We must accept only space, tab, LF, and CR as whitespace after the token "eexec".
        // For example, in file PDFBOX-3091-353869-p8.pdf we have problems...

        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }

    private static char byteToChar(byte b)
    {
        return (char)(b & 0xFF);
    }

    public static boolean isHexDigit(char c)
    {
        return ('0' <= c && c <= '9') || ('A' <= c && c <= 'F') || ('a' <= c && c <= 'f');
    }

    public static char hexToChar(char c0, char c1)
    {
        int ch = 16 * hexToDec(c0) + hexToDec(c1);
        if (ch < 0 || ch > 255) {
            return '?';
        }
        return (char)ch;
    }

    public static int hexToDec(char hex)
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
}
