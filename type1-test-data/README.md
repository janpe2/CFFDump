# Type1 Test Data (Option -t1)

File formats:
* .pfb - Binary font with section headers that identify textual and binary sections.
* .pfa - ASCII font in which the `eexec` section has been encoded by ASCII-hex.
* .type1 - Binary font like PFB but without section headers. Typical in embedded fonts in PDF.

`embedded-a85-deflate-offset_597.pdf`
This PDF file contains an embedded Type1 font. The data starts at file offset 597 and it has been compressed by deflate and ASCII-85. The needed filter options are available only in GUI.

`embedded-deflate-offset_779.pdf`
This PDF file contains an embedded Type1 font. The data starts at file offset 779 and it has been compressed by deflate. Thus the needed options are `-t1 -deflate -start 779`.

`flex-1.pfa`
Includes a flex section.

`flex-2.type1`
Includes commands div and dotsection. Has flexes (in unusual positions) in glyphs a, d, n, u. Flexes also use hmovetos although in Type1 spec flexes have only rmovetos.

`futura-cond.type1`
Special commands: vstem3, seac, div, dotsection. Subroutines are not in ascending order: 0, 1, 10, 100, ..., 29, 3, 30, ... Hint replacement is used but glyphs directly invoke `callothersubr` instead of wrapping it in a subr.

`hint-replacement.pfa`
Simple hint replacement. Subroutine 4 (OtherSubr 3) clears all hints and then subroutines 5 and 6 set new hints.

`mm-1.type1`
MultipleMaster font.

`seac-1.type1`
Accented "seac" glyphs.

`simple.pfa`
A simplistic Type1 font in PFA (ASCII) format.

`simple.pfb`
A simplistic Type1 font in PFB (binary) format.

`simple.type1`
A simplistic Type1 font in raw binary format, typically used in PDF files.

`simple-dump.txt`
The dump result of `simple.type1`.

`simple_flate.type1`
A simplistic Type1 font in raw binary format, deflate compressed.

`simple_hex.type1`
A simplistic Type1 font in raw binary format, hex encoded.


