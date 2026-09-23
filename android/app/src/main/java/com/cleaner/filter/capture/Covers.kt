package com.cleaner.filter.capture

internal data class CoverRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * NudeNet boxes are often tiny (nipple / genital crop). Grow them into a body-sized
 * cover using the frame short side, not the box size, so the rest of the body does
 * not stay visible. [fraction] is kept for tests; live path uses [label]-aware padding.
 */
internal fun expandCover(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    maxWidth: Float,
    maxHeight: Float,
    fraction: Float = 0.12f,
    label: String = "",
): CoverRect {
    val boxW = (right - left).coerceAtLeast(1f)
    val boxH = (bottom - top).coerceAtLeast(1f)

    // Unlabeled / explicit fraction path keeps the old geometry (tests + callers).
    if (label.isBlank()) {
        val dx = boxW * fraction
        val dy = boxH * fraction
        return CoverRect(
            (left - dx).coerceAtLeast(0f),
            (top - dy).coerceAtLeast(0f),
            (right + dx).coerceAtMost(maxWidth),
            (bottom + dy).coerceAtMost(maxHeight),
        )
    }

    val shortSide = minOf(maxWidth, maxHeight).coerceAtLeast(1f)
    // Pad by a large fraction of the screen so a nipple-sized hit still blacks out torso.
    val (padX, padY, biasY) = when (coverKind(label)) {
        CoverKind.BREAST -> Triple(shortSide * 0.30f, shortSide * 0.36f, 0.40f)
        CoverKind.GENITAL -> Triple(shortSide * 0.32f, shortSide * 0.38f, -0.30f)
        CoverKind.BUTTOCKS -> Triple(shortSide * 0.34f, shortSide * 0.34f, 0.10f)
        CoverKind.GENERIC -> Triple(shortSide * 0.26f, shortSide * 0.26f, 0f)
    }

    val cx = (left + right) * 0.5f
    val cy = (top + bottom) * 0.5f + biasY * padY
    val halfW = maxOf(boxW * 0.5f + padX, shortSide * 0.22f)
    val halfH = maxOf(boxH * 0.5f + padY, shortSide * 0.24f)
    return CoverRect(
        (cx - halfW).coerceAtLeast(0f),
        (cy - halfH).coerceAtLeast(0f),
        (cx + halfW).coerceAtMost(maxWidth),
        (cy + halfH).coerceAtMost(maxHeight),
    )
}

internal enum class CoverKind { BREAST, GENITAL, BUTTOCKS, GENERIC }

internal fun coverKind(label: String): CoverKind {
    val upper = label.uppercase()
    return when {
        upper.contains("BREAST") -> CoverKind.BREAST
        upper.contains("GENITALIA") || upper.contains("ANUS") -> CoverKind.GENITAL
        upper.contains("BUTTOCKS") -> CoverKind.BUTTOCKS
        else -> CoverKind.GENERIC
    }
}

/** Union of [covers] grown modestly into a torso plate that still fits the frame. */
internal fun bodySafetyPlate(covers: List<CoverRect>, maxWidth: Float, maxHeight: Float): CoverRect {
    var left = covers.minOf { it.left }
    var top = covers.minOf { it.top }
    var right = covers.maxOf { it.right }
    var bottom = covers.maxOf { it.bottom }
    val shortSide = minOf(maxWidth, maxHeight)
    // Modest growth — do not force a near-fullscreen plate (chrome must stay visible).
    left = (left - shortSide * 0.12f).coerceAtLeast(0f)
    right = (right + shortSide * 0.12f).coerceAtMost(maxWidth)
    top = (top - shortSide * 0.14f).coerceAtLeast(0f)
    bottom = (bottom + shortSide * 0.18f).coerceAtMost(maxHeight)
    return CoverRect(left, top, right, bottom)
}

/** Union nearby/overlapping covers so left+right breast become one torso plate. */
internal fun mergeCovers(covers: List<CoverRect>, gap: Float = 0f): List<CoverRect> {
    if (covers.size <= 1) return covers
    val remaining = covers.toMutableList()
    val merged = ArrayList<CoverRect>()
    while (remaining.isNotEmpty()) {
        var current = remaining.removeAt(0)
        var grew: Boolean
        do {
            grew = false
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (coversOverlapOrNear(current, other, gap)) {
                    current = CoverRect(
                        minOf(current.left, other.left),
                        minOf(current.top, other.top),
                        maxOf(current.right, other.right),
                        maxOf(current.bottom, other.bottom),
                    )
                    iterator.remove()
                    grew = true
                }
            }
        } while (grew)
        merged += current
    }
    return merged
}

private fun coversOverlapOrNear(a: CoverRect, b: CoverRect, gap: Float): Boolean {
    return a.left <= b.right + gap &&
        a.right + gap >= b.left &&
        a.top <= b.bottom + gap &&
        a.bottom + gap >= b.top
}
