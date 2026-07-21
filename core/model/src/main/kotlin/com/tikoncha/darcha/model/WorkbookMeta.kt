package com.tikoncha.darcha.model

/**
 * A reference to one worksheet as declared in `xl/workbook.xml`, with its
 * relationship resolved to a concrete ZIP part path (TECH_SPEC §7 step 2).
 *
 * @property name the user-visible sheet name (may be non-ASCII).
 * @property sheetId the workbook-scoped id from the `sheetId` attribute.
 * @property relId the relationship id (`r:id`) linking to the worksheet part.
 * @property partPath the normalized ZIP path of the worksheet XML, e.g.
 *   `xl/worksheets/sheet1.xml`.
 */
public data class SheetRef(
    public val name: String,
    public val sheetId: Int,
    public val relId: String,
    public val partPath: String,
)

/**
 * Top-level workbook metadata parsed from `xl/workbook.xml` (TECH_SPEC §7 step 2).
 *
 * @property date1904 whether serial dates use the 1904 epoch (from `workbookPr`;
 *   default `false`, i.e. the 1900 epoch).
 * @property sheets the worksheets in document order, as declared in `<sheets>`.
 */
public data class WorkbookMeta(
    public val date1904: Boolean,
    public val sheets: List<SheetRef>,
)
