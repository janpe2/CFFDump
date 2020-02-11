/* Copyright 2020 Jani Pehkonen
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
 * Represents a value in a CFF dictionary (DICT).
 * <p>
 * DictValue provides a special handling for real number values. They are
 * stored in their original BCD (string) format like they are in CFF.
 * Otherwise CFF's real values would get rounded when they are converted to Java's float
 * or double and the original CFF real representation would be lost. For example, if CFF's
 * real is given as "0A 36 36 36 FF", converting it to Java's float and then to
 * String gives "0.36363598704338074". The correct result is "0.363636".
 */

class DictValue
{
    private final int intValue;
    private final double realValue;
    private String realValueString;
    private final boolean isReal;

    public DictValue(int integer)
    {
        intValue = integer;
        realValue = integer;
        isReal = false;
    }

    public DictValue(double real, String realStr)
    {
        realValueString = realStr;
        realValue = real;
        intValue = (int)real;
        isReal = true;
    }

    public boolean isInt()
    {
        return !isReal;
    }

    public boolean isReal()
    {
        return isReal;
    }

    public int getInt()
    {
        if (isReal) {
            return (int)realValue;
        }
        return intValue;
    }

    public double getReal()
    {
        if (isReal) {
            return realValue;
        }
        return intValue;
    }

    public boolean getBoolean()
    {
        if (isReal) {
            return realValue != 0;
        }
        return intValue != 0;
    }

    public String getRealString()
    {
        return realValueString;
    }

    @Override
    public String toString()
    {
        return isReal ? realValueString : Integer.toString(intValue);
    }
}
