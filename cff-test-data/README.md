# CFF Test Data

`CIDFont-1.cff`
A simplistic CIDFont.

`CIDFont-charset-error.cff`
A CIDFont that has a `charset` of 0 (ISOAdobe), which is an error. CIDFonts must not use predefined charsets 0, 1, and 2.

`CIDFont-FD-FontMatrix.cff`
A CIDFont whose Font DICT contains the entry `/FontMatrix [.00048828125 0 0 .00048828125 0 0]`.

`embedded-a85-deflate-offset_434.pdf`
This PDF file contains an embedded CFF font. The data starts at file offset 434 and it has been compressed by deflate and encoded by ASCII85. The needed filter options are available only in GUI.

`embedded-deflate-offset_2243.pdf`
This PDF file contains an embedded CFF font. The data starts at file offset 2243 and it has been compressed by deflate. Thus the needed options are `-deflate -start 2243`.

`encoding-format-1.cff`
Encoding block has format 1. Charset block has format 1.

`error-operator-9.cff`
Invalid operator 9 appears repeatedly in charstrings. All numbers in charstrings have been stored very inefficiently as 5-byte sequences (255 + fixed-point 16.16).

`flex-1.otf`
Includes a flex section.

`flex-and-hintmask.cff`
Flex segments and hintmasks.

`garamond-light.cff`
Lots of hintmasks and flexes. Glyph 'g' has so many stem hints that the mask bits of hintmask require 2 bytes. Glyph 'm' has cntrmask. Glyph 'w' has hflex1. Glyph '1' has flex1.

`global_subrs.cff`
Contains a non-empty `Global Subr INDEX`. This font also has some local and global subroutines that are never used.

`header-size-12.cff`
Header size (hdrSize) is 12 instead of the usual value 4. This is a CIDFont with Japan1 ordering and four DICTs in FDArray.

`MM-snapshot.cff`
Despite its name, this is not a Multiple Master font, but a "snapshot" of an MM font with fixed interpolation factors. Top DICT has entry BaseFontBlend but no MultipleMaster entry. Private DICT has a weird operator `12 15`, which seems to be ForceBoldThreshold that is mentioned in CFF spec 1998 edition.

`no-private.cff`
A font whose Top DICT has no `Private` entry. That is an error. However, it would be useful if the dumper could show the contents of the whole font instead of stopping at the error.

`no-private-CIDFont.cff`
A CIDFont whose Font DICT has no `Private` entry.

`opentype.otf`
A simplistic OpenType font with CFF data. Thus the needed option is `-otf`.

`seac-2.cff`
Accented glyphs created with the `endchar` operator the same way as the `seac` command is used in Type1 fonts.

`seac-and-vvcurveto-error.cff`
Accented glyphs created with the `endchar` operator the same way as the `seac` command is used in Type1 fonts. Additionally this font has an error: `vvcurveto` gets no operands.

`simple.cff`
A simplistic base font.

`simple.hex`
The same font as `simple.cff` but encoded with ASCII hex.

`simple-dump.txt`
The dump of `simple.cff`.

`special-ops.cff`
Special operators in charstrings: add, mul, exch, roll, ifelse.

`supplemental-encoding.cff`
Encoding contains a Supplemental encoding, which allows to encode one glyph name to more than one character codes.

`unused-subrs.cff`
Contains subroutines that are never called - both global and local.
