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

import java.nio.ByteBuffer;

/**
 * Dumps Type2 charstrings.
 */

class Type2CharStringDump
{
    /**
     * Return value for charstring parsing: continue parsing.
     */
    static final int CHARSTRING_END_CONTINUE = 0;

    /**
     * Return value for charstring parsing: operator {@code endchar} finished the glyph.
     */
    static final int CHARSTRING_END_ENDCHAR = 1;

    /**
     * Return value for charstring parsing: subroutine ended with the
     * {@code return} operator.
     */
    static final int CHARSTRING_END_RETURN = 2;

    /**
     * Return value for charstring parsing: dump of an unused subroutine was interrupted
     * because {@code hintmask} or {@code cntrmask} was encountered.
     */
    static final int CHARSTRING_END_HINTMASK = 3;

    static final String INVALID_NUM_OPERANDS_TEXT = "Invalid number of operands";

    /**
     * Names for one-byte Type2 operators.
     */
    private static final String[] TYPE2_OP_NAMES = {
        null,         //  0 -Reserved-
        "hstem",      //  1
        null,         //  2 -Reserved-
        "vstem",      //  3
        "vmoveto",    //  4
        "rlineto",    //  5
        "hlineto",    //  6
        "vlineto",    //  7
        "rrcurveto",  //  8
        null,         //  9 -Reserved-
        "callsubr",   // 10
        "return",     // 11
        null,         // 12 escape for two-byte operators
        null,         // 13 -Reserved-
        "endchar",    // 14
        null,         // 15 -Reserved-
        null,         // 16 -Reserved-
        null,         // 17 -Reserved-
        "hstemhm" ,   // 18
        "hintmask",   // 19
        "cntrmask",   // 20
        "rmoveto",    // 21
        "hmoveto",    // 22
        "vstemhm",    // 23
        "rcurveline", // 24
        "rlinecurve", // 25
        "vvcurveto",  // 26
        "hhcurveto",  // 27
        null,         // 28 short int
        "callgsubr",  // 29
        "vhcurveto",  // 30
        "hvcurveto"   // 31
    };

    private static final int MAX_SUBR_NESTING = 10;
    private static final String CHARSTRING_INDENT = "    ";

    private ByteBuffer input;
    private CFFDump cffDump;
    private final float[] type2Stack = new float[48];
    private int type2StackCount;
    boolean foundEndChar;
    boolean containsSeacs;
    boolean containsFlex;
    private float[] transientArray = null; // initialized on first use
    private boolean doExplainHintMaskBits;
    boolean unusedSubrDumpInterruptedByHintmask;

    Type2CharStringDump(CFFDump cffDump, ByteBuffer input)
    {
        this.cffDump = cffDump;
        this.input = input;
    }

    /**
     * Executes a Type2 charstring operator.
     */
    private int type2Operator(int op, StringBuilder s, GlyphStatus gs, boolean isDumpingUnusedSubr)
    throws CFFParseException
    {
        boolean doClear = true;
        String opName = (op >= 0 && op < TYPE2_OP_NAMES.length) ? TYPE2_OP_NAMES[op] : null;

        switch (op) {
            case 1:  // hstem
            case 3:  // vstem
            case 18: // hstemhm
            case 23: // vstemhm
               s.append(opName);
               gs.stemHintCount += type2StackCount / 2;
               if (type2StackCount < 2) {
                   invalidNumOperands(s, opName);
               }
               if (!gs.foundGlyphWidth) {
                   int numHintOperands;
                   if ((type2StackCount % 2) == 1) {
                       numHintOperands = type2StackCount - 1;
                       gs.setGlyphWidth(type2Stack[0], s);
                   } else {
                       numHintOperands = type2StackCount;
                       gs.setDefaultWidth(s);
                   }
                   gs.foundGlyphWidth = true;
                   if((numHintOperands % 2) != 0) {
                       invalidNumOperands(s, opName);
                   }
               } else {
                   if ((type2StackCount % 2) != 0) {
                       invalidNumOperands(s, opName);
                   }
               }
               s.append('\n');
               break;

            case 4:  // vmoveto
            case 22: // hmoveto
                s.append(opName);
                if (!gs.foundGlyphWidth) {
                    if (type2StackCount < 1 || type2StackCount > 2) {
                        invalidNumOperands(s, opName);
                    }
                    if (type2StackCount == 2) {
                        gs.setGlyphWidth(type2Stack[0], s);
                    } else {
                        gs.setDefaultWidth(s);
                    }
                    gs.foundGlyphWidth = true;
                } else {
                    if (type2StackCount != 1) {
                        invalidNumOperands(s, opName);
                    }
                }
                s.append('\n');
                break;

            case 5: // rlineto
                s.append(opName).append('\n');
                gs.checkWidthFound(s, opName);
                if (type2StackCount < 2 || (type2StackCount % 2) != 0) {
                    invalidNumOperands(s, opName);
                }
                break;

            case 6: // hlineto
            case 7: // vlineto
                s.append(opName).append('\n');
                gs.checkWidthFound(s, opName);
                if (type2StackCount < 1) {
                    invalidNumOperands(s, opName);
                }
                break;

            case 8: // rrcurveto
                s.append(opName).append('\n');
                gs.checkWidthFound(s, opName);
                if (type2StackCount < 6 || (type2StackCount % 6) != 0) {
                    invalidNumOperands(s, opName);
                }
                break;

            case 10: { // callsubr
                // doClear = false;
                int subrNo = t2PopInt();
                if (isDumpingUnusedSubr) {
                    int bias = cffDump.getLocalSubrBias(gs.fdIndex);
                    s.append(opName).append("  % subr# ").append(subrNo + bias).append('\n');
                    clearType2Stack();
                    return CHARSTRING_END_CONTINUE;
                } else {
                    gs.subrLevel++;
                    int ret = cffDump.executeLocalSubr(subrNo, s, gs, false, null);
                    gs.subrLevel--;
                    gs.ensureWidthIsPrinted(s);
                    return ret;
                }
            }

            case 11: // return
                s.append(opName).append('\n');
                return CHARSTRING_END_RETURN;

            case 14: // endchar
                s.append(opName);
                foundEndChar = true;
                // The number of operands must be 0, 1, 4, or 5.
                if (!(type2StackCount == 0 || type2StackCount == 1 ||
                      type2StackCount == 4 || type2StackCount == 5)) {
                    invalidNumOperands(s, opName);
                }
                if (type2StackCount == 4 || type2StackCount == 5) {
                    s.append("  % \"seac\"");
                    containsSeacs = true;
                }
                if (!gs.foundGlyphWidth) {
                    if (type2StackCount == 1 || type2StackCount == 5) {
                        gs.setGlyphWidth(type2Stack[0], s);
                    } else {
                        gs.setDefaultWidth(s);
                    }
                    gs.foundGlyphWidth = true;
                }
                s.append('\n');
                clearType2Stack();
                return CHARSTRING_END_ENDCHAR;

            case 19:   // hintmask
            case 20: { // cntrmask
                // hintmask and cntrmask may take vstem values and glyph width as operands.
                int numVstems = type2StackCount / 2;
                gs.stemHintCount += numVstems;
                s.append(opName).append(' ');
                if (isDumpingUnusedSubr) {
                    s.append("...\n    % <Cannot dump further because ").append(opName)
                        .append(" was encountered in an unused subroutine>\n");
                    unusedSubrDumpInterruptedByHintmask = true;
                    return CHARSTRING_END_HINTMASK;
                }
                if (gs.stemHintCount == 0) {
                    String errorMsg = "No stem hints exist for " + opName;
                    s.append("  % ERROR! ").append(errorMsg).append(' ');
                    cffDump.addError(errorMsg);
                } else {
                    // Mask bit bytes follow the operator. They are "raw" bytes that
                    // are not in Type 2 encoded format.
                    String vstemsStr = numVstems > 0
                        ? " % adds " + numVstems + " vstems "
                        : "";
                    readHintMaskBytes(gs.stemHintCount, s,  vstemsStr);
                }
                if (!gs.foundGlyphWidth) {
                    if ((type2StackCount % 2) == 1) {
                        gs.setGlyphWidth(type2Stack[0], s);
                    } else {
                        gs.setDefaultWidth(s);
                    }
                    gs.foundGlyphWidth = true;
                } else {
                    if ((type2StackCount % 2) == 1) {
                        invalidNumOperands(s, opName);
                    }
                }
                s.append('\n');
                break;
            }

            case 21: { // rmoveto
                // Ghostscript says: Some Type 2 charstrings omit the vstemhm operator before
                // rmoveto even though this is only allowed before hintmask and cntrmask.
                // We don't support that.
                s.append(opName);
                int n = type2StackCount;
                if (!gs.foundGlyphWidth) {
                    if (n < 2 || n > 3) {
                        invalidNumOperands(s, opName);
                    }
                    if ((n % 2) != 0) {
                        gs.setGlyphWidth(type2Stack[0], s);
                        n--;
                    } else {
                        gs.setDefaultWidth(s);
                    }
                    gs.foundGlyphWidth = true;
                } else {
                    if (n != 2) {
                        invalidNumOperands(s, opName);
                    }
                }
                s.append('\n');
                break;
            }

            case 24:
                // rcurveline
                s.append(opName).append('\n');
                gs.checkWidthFound(s, opName);
                if (type2StackCount < 8 || ((type2StackCount - 2) % 6) != 0) {
                    invalidNumOperands(s, opName);
                }
                break;

            case 25:
                // rlinecurve
                s.append(opName).append('\n');
                gs.checkWidthFound(s, opName);
                if (type2StackCount < 8 || ((type2StackCount - 6) % 2) != 0) {
                    invalidNumOperands(s, opName);
                }
                break;

            case 26: // vvcurveto
            case 27: // hhcurveto
                s.append(opName).append('\n');
                gs.checkWidthFound(s, opName);
                if (type2StackCount < 4 ||
                    ((type2StackCount % 4) != 0 && ((type2StackCount - 1) % 4) != 0)) {
                    invalidNumOperands(s, opName);
                }
                break;

            case 29: {
                // callgsubr
                // doClear = false;
                int subrNo = t2PopInt();
                if (isDumpingUnusedSubr) {
                    int bias = cffDump.getGlobalSubrBias();
                    s.append(opName).append("  % gsubr# ").append(subrNo + bias).append('\n');
                    clearType2Stack();
                    return CHARSTRING_END_CONTINUE;
                } else {
                    gs.subrLevel++;
                    int ret = cffDump.executeGlobalSubr(subrNo, s, gs, false, null);
                    gs.subrLevel--;
                    gs.ensureWidthIsPrinted(s);
                    return ret;
                }
            }

            case 30: // vhcurveto
            case 31: { // hvcurveto
                s.append(opName).append('\n');
                gs.checkWidthFound(s, opName);
                if (type2StackCount < 4) {
                    invalidNumOperands(s, opName);
                }
                boolean ok = false;
                if (((type2StackCount - 4) % 8) == 0) {
                    ok = true;
                } else if (type2StackCount >= 5 && ((type2StackCount - 5) % 8) == 0) {
                    ok = true;
                } else if (type2StackCount >= 8 && ((type2StackCount - 8) % 8) == 0) {
                    ok = true;
                } else if (type2StackCount >= 9 && ((type2StackCount - 9) % 8) == 0) {
                    ok = true;
                }
                if (!ok) {
                    invalidNumOperands(s, opName);
                }
                break;
            }

            default:
                s.append("operator_").append(op);
                s.append("  % ERROR! Invalid operator ").append(op).append('\n');
                cffDump.addError("Invalid operator in charstring");
                // throw new CFFParseException(
                //    "Invalid Type2 operator (" + op + ") in glyph #" + gs.gid);
                break;
        }

        if (doClear) {
            clearType2Stack();
        }
        return CHARSTRING_END_CONTINUE;
    }

    private void type2EscapedOperator(int op, StringBuilder s, GlyphStatus gs)
    throws CFFParseException
    {
        // Stack manipulation operators are dumped so that their arguments reflect the
        // state of the stack before the operator has been executed. For example, if the
        // dump is "1 2 exch" ==> after {@code exch} has been executed, the stack is |- 2 1.

        // "Weird" operators like exch, sqrt, random are typically never used in
        // actual glyph charstrings. (I think they are meant for MultipleMaster
        // parameter computations [that are not charstrings]. The support for MM
        // fonts has been removed from the CFF Spec a long time ago. In practice,
        // CFF MM fonts don't exist.)

        boolean doClear = false;
        float x, y;

        switch (op) {
            case 0: // dotsection (deprecated)
                doClear = true;
                if (type2StackCount != 0) {
                    invalidNumOperands(s, "dotsection");
                }
                s.append("dotsection");
                break;
            case 3: // and
                if (type2StackCount < 2) {
                    invalidNumOperands(s, "and");
                }
                x = t2Pop();
                y = t2Pop();
                t2Push( (x != 0 && y != 0) ? 1 : 0 );
                s.append("and");
                break;
            case 4:  // or
                if (type2StackCount < 2) {
                    invalidNumOperands(s, "or");
                }
                x = t2Pop();
                y = t2Pop();
                t2Push( (x != 0 || y != 0) ? 1 : 0 );
                s.append("or");
                break;
            case 5:  // not
                if (type2StackCount < 1) {
                    invalidNumOperands(s, "not");
                }
                x = t2Pop();
                t2Push( (x != 0) ? 0 : 1 );
                s.append("not");
                break;
            case 9:  // abs
                if (type2StackCount < 1) {
                    invalidNumOperands(s, "abs");
                }
                x = t2Pop();
                t2Push(Math.abs(x));
                s.append("abs");
                break;
            case 10: // add
                if (type2StackCount < 2) {
                    invalidNumOperands(s, "add");
                }
                x = t2Pop();
                y = t2Pop();
                t2Push(x + y);
                s.append("add");
                break;
            case 11: // sub
                if (type2StackCount < 2) {
                    invalidNumOperands(s, "sub");
                }
                y = t2Pop();
                x = t2Pop();
                t2Push(x - y);
                s.append("sub");
                break;
            case 12: // div
                if (type2StackCount < 2) {
                    invalidNumOperands(s, "div");
                }
                y = t2Pop();
                x = t2Pop();
                if (y == 0) {
                    t2Push(0);
                } else {
                    t2Push(x / y);
                }
                s.append("div");
                break;
            case 14: // neg
                if (type2StackCount < 1) {
                    invalidNumOperands(s, "neg");
                }
                x = t2Pop();
                t2Push(-x);
                s.append("neg");
                break;
            case 15: // eq
                if (type2StackCount < 2) {
                    invalidNumOperands(s, "eq");
                }
                x = t2Pop();
                y = t2Pop();
                t2Push( (x == y) ? 1 : 0 );
                s.append("eq");
                break;
            case 18: // drop
                if (type2StackCount < 1) {
                    invalidNumOperands(s, "drop");
                }
                t2Pop();
                s.append("drop");
                break;
            case 20: { // put
                if (type2StackCount < 2) {
                    invalidNumOperands(s, "put");
                }
                if (transientArray == null) {
                    transientArray = new float[32];
                }
                int i = t2PopInt();
                float val = t2Pop();
                transientArray[i] = val;
                s.append("put");
                break;
            }
            case 21: { // get
                if (type2StackCount < 1) {
                    invalidNumOperands(s, "get");
                }
                if (transientArray == null) {
                    transientArray = new float[32];
                }
                int i = t2PopInt();
                float val = transientArray[i];
                t2Push(val);
                s.append("get");
                break;
            }
            case 22: { // ifelse
                if (type2StackCount < 4) {
                    invalidNumOperands(s, "ifelse");
                }
                float s1, s2, v1, v2;
                v2 = t2Pop();
                v1 = t2Pop();
                s2 = t2Pop();
                s1 = t2Pop();
                if (v1 <= v2) {
                    t2Push(s1);
                } else {
                    t2Push(s2);
                }
                s.append("ifelse");
                break;
            }
            case 23: // random
                // We should use the initialRandomSeed entry of the Private DICT.
                // Actually, it would be better to use the same randomizing algorithm
                // as in PostScript interpreters.
                if (type2StackCount != 0) {
                    invalidNumOperands(s, "random");
                }
                t2Push((float)Math.random());
                s.append("random");
                break;
            case 24: // mul
                if (type2StackCount < 2) {
                    invalidNumOperands(s, "mul");
                }
                y = t2Pop();
                x = t2Pop();
                t2Push(x * y);
                s.append("mul");
                break;
            case 26: // sqrt
                if (type2StackCount < 1) {
                    invalidNumOperands(s, "sqrt");
                }
                x = t2Pop();
                if (x < 0) {
                    t2Push(0);
                } else {
                    t2Push((float)Math.sqrt(x));
                }
                s.append("sqrt");
                break;
            case 27: // dup
                if (type2StackCount < 1) {
                    invalidNumOperands(s, "dup");
                }
                x = t2Pop();
                t2Push(x);
                t2Push(x);
                s.append("dup");
                break;
            case 28: // exch
                if (type2StackCount < 2) {
                    invalidNumOperands(s, "exch");
                }
                x = t2Pop();
                y = t2Pop();
                t2Push(x);
                t2Push(y);
                s.append("exch");
                break;
            case 29: { // index
                if (type2StackCount < 1) {
                    invalidNumOperands(s, "index");
                }
                int i = t2PopInt();
                if (i < 0) {
                    x = t2Pop();
                    t2Push(x);
                    t2Push(x);
                } else if (i >= type2StackCount) {
                    // Undefined operation.
                    cffDump.addError("Invalid stack access in operator 'index'");
                } else {
                    x = type2Stack[type2StackCount - i - 1];
                    t2Push(x);
                }
                s.append("index");
                break;
            }
            case 30: { // roll
                if (type2StackCount < 2) {
                    invalidNumOperands(s, "roll");
                }
                int j = t2PopInt();
                int n = t2PopInt();
                if (n <= 1 || n > type2StackCount) {
                    cffDump.addError("Invalid stack access in operator 'roll'");
                    return;
                }
                // This implementation is very inefficient but fortunately the
                // roll operator is not used in fonts.
                if (j > 0) {
                    for (int i = 0; i < j; i++) {
                        rollUpOneStep(n);
                    }
                } else {
                    j = -j;
                    for (int i = 0; i < j; i++) {
                        rollDownOneStep(n);
                    }
                }
                s.append("roll");
                break;
            }
            case 34: // hflex
                gs.checkWidthFound(s, "hflex");
                if (type2StackCount != 7) {
                    invalidNumOperands(s, "hflex");
                }
                doClear = true;
                containsFlex = true;
                s.append("hflex");
                break;
            case 35: // flex
                gs.checkWidthFound(s, "flex");
                if (type2StackCount != 13) {
                    invalidNumOperands(s, "flex");
                }
                doClear = true;
                containsFlex = true;
                s.append("flex");
                break;
            case 36: // hflex1
                gs.checkWidthFound(s, "hflex1");
                if (type2StackCount != 9) {
                    invalidNumOperands(s, "hflex1");
                }
                doClear = true;
                containsFlex = true;
                s.append("hflex1");
                break;
            case 37:  // flex1
                gs.checkWidthFound(s, "flex1");
                if (type2StackCount != 11) {
                    invalidNumOperands(s, "flex1");
                }
                doClear = true;
                containsFlex = true;
                s.append("flex1");
                break;
            default:
                s.append("operator_12_").append(op);
                s.append("  % ERROR! Invalid operator 12 ").append(op).append('\n');
                cffDump.addError("Invalid operator in charstring");
                // throw new CFFParseException(
                //     "Invalid Type2 operator (12 " + op + ") in glyph #" + gs.gid);
                break;
        }

        s.append('\n');
        if (doClear) {
            clearType2Stack();
        }
    }

    void clearType2Stack()
    {
        type2StackCount = 0;
    }

    private void t2Push(float f) throws CFFParseException
    {
        if (type2StackCount >= type2Stack.length) {
            throw new CFFParseException("Type 2 stack overflow");
        }
        type2Stack[ type2StackCount++ ] = f;
    }

    private float t2Pop() throws CFFParseException
    {
        if (type2StackCount <= 0) {
            throw new CFFParseException("Type 2 stack underflow");
        }
        return type2Stack[ --type2StackCount ];
    }

    private int t2PopInt() throws CFFParseException
    {
        return (int)t2Pop();
    }

    private int readT2Byte() throws CFFParseException
    {
        if (input.remaining() < 1) {
            throw new CFFParseException("ByteBuffer underflow (read byte)");
        }
        return input.get() & 0xFF;
    }

    private int readT2Short() throws CFFParseException
    {
        if (input.remaining() < 1) {
            throw new CFFParseException("ByteBuffer underflow (read ShortInt)");
        }
        return input.getShort();
    }

    private int readT2Int() throws CFFParseException
    {
        if (input.remaining() < 1) {
            throw new CFFParseException("ByteBuffer underflow (read LongInt 16.16)");
        }
        return input.getInt();
    }

    private void rollUpOneStep(int n)
    {
        if (n <= 1) {
            return;
        }
        float topElem = type2Stack[type2StackCount - 1];
        int botIdx = type2StackCount - n;
        for (int i = type2StackCount - 2; i >= botIdx; i--) {
            type2Stack[i + 1] = type2Stack[i];
        }
        type2Stack[botIdx] = topElem;
    }

    private void rollDownOneStep(int n)
    {
        if (n <= 1) {
            return;
        }
        int botIdx = type2StackCount - n;
        float botElem = type2Stack[botIdx];
        for (int i = botIdx; i + 1 < type2StackCount; i++) {
            type2Stack[i] = type2Stack[i + 1];
        }
        type2Stack[type2StackCount - 1] = botElem;
    }

    /**
     * Executes a glyph charstring or a subroutine.
     *
     * A subroutine may end with an {@code endchar} and there is no {@code return}.
     * This {@code endchar} ends the glyph that invoked the subroutine. To successfully finish
     * charstring parsing also in this situation, we return the value CHARSTRING_END_ENDCHAR
     * from executeCharString() that is parsing the subroutine.
     *
     * prevLimit is needed to allow using limits in (nested) subroutines.
     * Otherwise limits would fail because subroutines would set their
     * own limit and clear the earlier limit.
     * For example in PDFJS-10544_CFF.pdf fonts cannot be parsed without
     * using limits. Some CFF glyphs in this PDF contain garbage data that
     * is read correctly only if we use limits. If we didn't use
     * limits, we would read charstrings way too far before we encounter an
     * {@code endchar} that actually belongs to some other glyph.
     */
    int executeCharString(StringBuilder s, GlyphStatus gs, int endOffset, boolean isDumpingUnusedSubr)
    throws CFFParseException
    {
        // When charstrings are dumped, this method truly processes the operands of all
        // operators using the Type2 stack and executes all subroutine calls.
        // If the subroutines were not truly called, the following problem would arise:
        // If a subroutine invokes stem hint operators ({@code hstem, vstem, hstemhm, vstemhm}),
        // the calling charstring knows nothing about them. Then, if the calling charstring
        // contains operators {@code hintmask} and {@code cntrmask}, their mask
        // bytes cannot be read correctly because the hints of the subroutines cannot be
        // included in stem hint count. As a result, the rest of the charstring dumping fails
        // because the mask bytes of {@code hintmask} and {@code cntrmask} will be used
        // erroneously as operator codes or operands.
        //
        // The approach has a disadvantage: If a CFF font has any subroutines that are
        // never executed, their content can't be dumped.
        //
        // If the Type2 stack was not used, the following problem would arise:
        // If subroutines push or pop values to/from the Type2 stack, the calling
        // charstring knows nothing about these modifications. Thus errors would appear
        // in the dump.

        if (gs.subrLevel > MAX_SUBR_NESTING) {
            cffDump.addError("Subroutines are too deeply nested");
        }

        final int prevLimit = input.limit(); // save previous limit
        input.limit(endOffset);

        s.append(CHARSTRING_INDENT);
        int returnValue = CHARSTRING_END_CONTINUE;

        while (input.position() < endOffset) {
            int b = readT2Byte();
            if (b == -1) {
                break;
            }
            if (b >= 32) {
                parseCharStringNumber(b, s);
            } else if (b == 12) {
                // Escaped two-byte command.
                if (input.position() >= endOffset) {
                    throw new CFFParseException(
                        "End of charstring while reading a two-byte operator");
                }
                b = readT2Byte();
                if (b == -1) {
                    break;
                }
                type2EscapedOperator(b, s, gs);
                s.append(CHARSTRING_INDENT);
            } else if (b == 28) {
                // A ShortInt value.
                if (input.position() + 1 >= endOffset) {
                    throw new CFFParseException("End of charstring while reading a ShortInt");
                }
                int shortInt = readT2Short();
                t2Push(shortInt);
                s.append(shortInt); // if cast to float, make sure the sign of short gets extended
                s.append(' ');
            } else {
                // Commands {@code endchar} and {@code return} finish this charstring analysis
                int ret = type2Operator(b, s, gs, isDumpingUnusedSubr);
                if (ret != CHARSTRING_END_CONTINUE) {
                    returnValue = ret;
                    break;
                }
                s.append(CHARSTRING_INDENT);
            }
        }

        if (input.position() != endOffset && !isDumpingUnusedSubr) {
            s.append("    % ERROR: Invalid charstring end offset (reading ended at ").
                append(input.position()).append(" but INDEX specifies end at ").
                append(endOffset).append(")\n");
            cffDump.addError("Invalid charstring end offset");
        }

        /*
        // This is commented out because this will report an error in most FontFolio fonts.
        // It seems that it is OK to omit {@code return} in a subroutine that ends with a call
        // to another subroutine that ends with an {@code endchar}.
        if (isSubr && lastOperator != 11 && lastOperator != 14) {
            // Subroutines must end with a {@code return} or {@code endchar}. If the subroutine
            // ends with an {@code endchar}, operator {@code return} is not necessary.
            s.append("    % ERROR: Subroutine does not end with 'return'\n");
            cffDump.addError("Subroutine does not end with 'return'");
        }
        */

        if (s.length() > 0 && s.charAt(s.length() - 1) != '\n') {
            s.append('\n');
        }

        input.limit(prevLimit); // Restore previous limit
        return returnValue;
    }

    private void parseCharStringNumber(int v, StringBuilder s) throws CFFParseException
    {
        int num = 0;

        if (v <= 246) {
            // 32 <= v <= 246
            num = v - 139;
        } else if (v <= 250) {
            // 247 <= v <= 250
            int w = readT2Byte();
            num = (v - 247) * 256 + w + 108;
        } else if (v <= 254) {
            // 251 <= v <= 254
            int w = readT2Byte();
            num = -256 * (v - 251) - w - 108;
        } else if (v == 255) {
            double fixed = readT2Int() / 65536.0;
            double roundEightDecimals = Math.round(fixed * 1e8) / 1e8;
            s.append(roundEightDecimals);
            s.append(' ');
            t2Push((float)fixed);
            return;
        } else {
            throw new CFFParseException("Illegal Type 2 number (byte " + v + ")");
        }

        s.append(num);
        s.append(' ');
        t2Push(num);
    }

    private void readHintMaskBytes(int numStemHints, StringBuilder s, String vstemsStr)
    {
        int bitCounter = -1;
        int currentByte = 0;
        String activeHints =   "\n    % --> Active hints #:   ";
        String inactiveHints = "\n    %     Inactive hints #: ";

        for (int i = 0; i < numStemHints; i++) {
            if (bitCounter < 0) {
                if (input.position() >= input.limit()) {
                    return;
                }
                currentByte = input.get() & 0xFF;
                bitCounter = 7;
                String hex = Integer.toHexString(currentByte).toUpperCase();
                s.append("0x").append(hex.length() < 2 ? "0" : "").append(hex).append(' ');
            }
            if (doExplainHintMaskBits) {
                int bit = currentByte & (1 << bitCounter);
                boolean isActive = bit != 0;
                int hintNumber = i + 1;
                if (isActive) {
                    activeHints = activeHints + hintNumber + " ";
                } else {
                    inactiveHints = inactiveHints + hintNumber + " ";
                }
            }
            bitCounter--;
        }

        s.append(vstemsStr);
        if (doExplainHintMaskBits) {
            s.append(activeHints).append(inactiveHints);
        }
    }

    private void invalidNumOperands(StringBuilder s, String op)
    {
        if (s.length() > 0 && s.charAt(s.length() - 1) == '\n') {
            s.setLength(s.length() - 1);
        }
        s.append("  % ERROR! ").append(INVALID_NUM_OPERANDS_TEXT).append('\n');
        cffDump.addError(INVALID_NUM_OPERANDS_TEXT + " for " + op);
    }

    void setExplainHintMaskBits(boolean doExplainHintMaskBits)
    {
        this.doExplainHintMaskBits = doExplainHintMaskBits;
    }
}
