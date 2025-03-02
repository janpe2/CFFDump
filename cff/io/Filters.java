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

package cff.io;

public class Filters
{
    /**
     * Data is not encoded nor compressed.
     */
    public static final int FILTER_NONE = 0;

    /**
     * Data is compressed with the deflate algorithm.
     * PDF filter {@code FlateDecode}.
     */
    public static final int FILTER_DEFLATE = 1;

    /**
     * Data is encoded with ASCII hex.
     */
    public static final int FILTER_ASCII_HEX = 2;
}
