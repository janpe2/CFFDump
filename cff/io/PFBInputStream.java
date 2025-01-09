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
 * Stream for reading Type1 fonts as PFB files.
 */

public class PFBInputStream extends InputStream
{
    private InputStream pfbInput;
    private int curSegmentLen, byteCtr;
    private boolean closed;

    public PFBInputStream(InputStream is)
    {
        pfbInput = is;
        // Set the variables so that the first call to read()
        // will parse the first PFB header
        curSegmentLen = 0;
        byteCtr = 1;
    }

    public int read() throws IOException
    {
        if (closed) {
            return -1;
        }
        if (byteCtr >= curSegmentLen) {
            // Segment has ended. Read next segment header
            readSegmentHeader();
            if (closed) {
                // No more segments
                return -1;
            }
        }
        int b = pfbInput.read();
        byteCtr++;
        if (b == -1) {
            close();
            return -1;
        }
        return b;
    }

    public void close() throws IOException
    {
        closed = true;
        curSegmentLen = 0;
        pfbInput.close();
    }

    private void readSegmentHeader() throws IOException
    {
        // If the end-of-file marker is found, this closes the input.

        // There is no need to store the segment type anywhere because this class
        // only strips off the segment headers. All ASCII and binary data is
        // passed through unchanged.

        int magic = pfbInput.read();
        if (magic != 128) {
            throw new IOException("invalid Type1 PFB header magic: " + magic);
        }

        int type = pfbInput.read();

        if (type == 3) {
            // End of file.
            close();
        } else if (type == 1 || type == 2) {
            // ASCII or binary segment.
            byteCtr = 0;
            curSegmentLen = readIntLE();
        } else {
            throw new IOException("invalid Type1 PFB header type: " + type);
        }
    }

    private int readIntLE() throws IOException
    {
        // Reads a 32-bit little-endian integer (low byte first).
        int b0 = pfbInput.read();
        int b1 = pfbInput.read();
        int b2 = pfbInput.read();
        int b3 = pfbInput.read();
        if (b3 == -1) {
            throw new IOException(
                "unexpected end of Type1 PFB data while reading section header bytes");
        }
        return (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
    }
}
