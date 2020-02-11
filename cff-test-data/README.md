# CFF Test Data

`CIDFont-1.cff`
A simplistic CIDFont.

`CIDFont-charset-error.cff`
A CIDFont that has a `charset` of 0 (ISOAdobe), which is an error. CIDFonts must not use predefined charsets 0, 1, and 2.

`CIDFont-FD-FontMatrix.cff`
A CIDFont whose Font DICT contains the entry `/FontMatrix [.00048828125 0 0 .00048828125 0 0]`.

`embedded-deflate.pdf`
This PDF file contains an embedded CFF font. the data starts at file offset 2243 and it has been compressed by deflate. Thus the needed options are `-deflate -start 2243`.

`encoding-format-1.cff`
Encoding block has format 1. Charset block has format 1.

`flex-and-hintmask.cff`
Flex segments and hintmasks.

`opentype.otf`
A simplistic OpenType font with CFF data. Thus the needed option is `-otf`.

`seac-and-vvcurveto-error.cff`
Accented glyphs created with the `endchar` operator the same way as the `seac` command is used in Type1 fonts. Additionally this font has an error: `vvcurveto` gets no operands.

`simple.cff`
A simplistic base font.

`simple.hex`
The same font as `simple.cff` but encoded with ASCII hex.

`simple-dump.txt`
The dump of `simple.cff`.

`supplemental-encoding.cff`
Encoding contains a Supplemental encoding, which allows to encode one glyph name to more than one character codes.
