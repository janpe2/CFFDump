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

package cff.io;

import java.io.InputStream;
import java.io.IOException;

/**
 * ASCII-85 decoder input stream.
 */
public class ASCII85DecoderStream extends InputStream
{
    private final InputStream input;
    private boolean error = false;
    private boolean endOfData = false;
    private int fiveTupleCounter = 0;
    private final byte[] decodedBuff = new byte[4];
    private int numDecodedBytes;
    private int decodeBuffIndex = -1;
    private long codeWord;
    private char currentInputChar;
    private boolean started = false;

    public ASCII85DecoderStream(InputStream is)
    {
        input = is;
    }

    @Override
    public int read() throws IOException
    {
        if (error) {
            return -1;
        }
        // If endOfData equals -1, don't stop yet because we may have some final
        // partial bytes in decodedBuff.

        try {
            if (!decodedBytesAvailable()) {
                // Decode more data
                decodeData();
                if (!decodedBytesAvailable()) {
                    // Couldn't get more data
                    return -1;
                }
            }
            byte b = decodedBuff[decodeBuffIndex++];
            return b & 0xFF;

        } catch (IOException ioe) {
            error = true;
            endOfData = true;
            throw ioe;
        }
    }

    private boolean decodedBytesAvailable()
    {
        return numDecodedBytes > 0 && decodeBuffIndex >= 0 && decodeBuffIndex < numDecodedBytes;
    }

    private void decodeData() throws IOException
    {
        if (endOfData) {
            return;
        }

        fiveTupleCounter = 0;
        numDecodedBytes = 0;
        decodeBuffIndex = -1;
        codeWord = 0;

        if (!started) {
            if (!readNextChar()) {
                return;
            }
            started = true;
        }

        while (true) {
            if (fiveTupleCounter >= 5) {
                if (codeWord > 4294967295L) {
                    // The value of a group of ASCII85 characters must not be greater than 2^32 - 1
                    throw new IOException("Value of ASCII85 group is out of range");
                }
                decodedBuff[0] = (byte)(codeWord >> 24);
                decodedBuff[1] = (byte)(codeWord >> 16);
                decodedBuff[2] = (byte)(codeWord >> 8);
                decodedBuff[3] = (byte)codeWord;
                fiveTupleCounter = 0;
                codeWord = 0;
                numDecodedBytes = 4;
                decodeBuffIndex = 0;
                return;
            }
            switch (currentInputChar) {
                case '~':
                    partialDecode();
                    return;

                case 'z':
                    if (fiveTupleCounter > 0) {
                        throw new IOException("Character 'z' is not allowed in a ASCII85 fivetuple");
                    }
                    // 'z' represents four zero bytes
                    for (int k = 0; k <= 3; k++) {
                        decodedBuff[k] = 0;
                    }
                    fiveTupleCounter = 0;
                    codeWord = 0;
                    numDecodedBytes = 4;
                    decodeBuffIndex = 0;
                    if (!readNextChar()) {
                        partialDecode();
                        return;
                    }
                    return;

                default:
                    if (33 <= currentInputChar && currentInputChar < 118) {
                        codeWord = 85 * codeWord + currentInputChar - 33;
                        fiveTupleCounter++;
                        if (!readNextChar()) {
                            partialDecode();
                            return;
                        }
                    } else {
                        throw new IOException("Illegal ASCII85 character '" + currentInputChar + "'");
                    }
            }
        }
    }

    private boolean readNextChar() throws IOException
    {
        char ch;
        int b;
        while (true) {
            b = input.read();
            if (b == -1) {
                numDecodedBytes = 0;
                decodeBuffIndex = -1;
                endOfData = true;
                return false;
            }
            ch = (char)b;
            if (ch <= ' ') {
                // White space
                continue;
            }
            break;
        }
        currentInputChar = ch;
        return true;
    }

    private void partialDecode() throws IOException
    {
        endOfData = true;
        decodeBuffIndex = 0;

        // Replace missing ASCII85 characters with 'u'
        final int u = 'u';

        switch (fiveTupleCounter) {
            case 0:
                // This case happens if there is no partial group at the end.
                // I.e., the data simply ends with a full group.
                return;
            case 2:
                codeWord = 85 * codeWord + u - 33;
                codeWord = 85 * codeWord + u - 33;
                codeWord = 85 * codeWord + u - 33;
                break;
            case 3:
                codeWord = 85 * codeWord + u - 33;
                codeWord = 85 * codeWord + u - 33;
                break;
            case 4:
                codeWord = 85 * codeWord + u - 33;
                break;
            case 5:
                break;
            default:
                throw new IOException("Illegal ASCII85 final group of " +
                    fiveTupleCounter + " character(s)");
        }

        if (codeWord > 4294967295L) {
            throw new IOException("Value of ASCII85 group is out of range");
        }

        numDecodedBytes = fiveTupleCounter - 1;
        // numDecodedBytes tells how many of these are valid values (not all)
        decodedBuff[0] = (byte)(codeWord >> 24);
        decodedBuff[1] = (byte)(codeWord >> 16);
        decodedBuff[2] = (byte)(codeWord >> 8);
        decodedBuff[3] = (byte)codeWord;
    }

    @Override
    public void close() throws IOException
    {
        input.close();
    }

}
