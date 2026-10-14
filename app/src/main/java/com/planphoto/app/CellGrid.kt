package com.planphoto.app

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A1, B10 방식 좌표 격자.
 * 도면 쪽을 바둑판처럼 나눠 가로 칸은 A, B, C … Z, AA, AB …, 세로 칸은 1, 2, 3 … 으로 부른다.
 * 엑셀처럼 왼쪽 위 칸이 A1.
 * 가로 칸 수는 도면마다 정할 수 있고(기본 26칸 = A~Z), 세로 칸 수는 칸이 정사각형이 되도록 자동으로 정하거나 직접 정한다.
 */
object CellGrid {

    const val DEFAULT_COLS = 26

    data class Dims(val cols: Int, val rows: Int)

    /** 쪽 크기(가로 w, 세로 h — 단위 무관)에 대한 격자 칸 수 */
    fun dims(plan: Plan, w: Float, h: Float): Dims {
        val cols = (if (plan.gridCols > 0) plan.gridCols else DEFAULT_COLS).coerceIn(1, 702)
        val rows = if (plan.gridRows > 0) plan.gridRows.coerceIn(1, 999)
        else if (w <= 0f || h <= 0f) cols
        else max(1, (cols * h / w).roundToInt()).coerceAtMost(999)
        return Dims(cols, rows)
    }

    /** 0 → A, 25 → Z, 26 → AA … */
    fun colName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.append('A' + r)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** 페이지 기준 0~1 위치 → (열 번호 0부터, 행 번호 0부터) */
    fun cellIndex(nx: Float, ny: Float, d: Dims): Pair<Int, Int> {
        val c = floor(nx * d.cols).toInt().coerceIn(0, d.cols - 1)
        val r = floor(ny * d.rows).toInt().coerceIn(0, d.rows - 1)
        return c to r
    }

    /** 화면 표기: "B10" */
    fun label(nx: Float, ny: Float, d: Dims): String {
        val (c, r) = cellIndex(nx, ny, d)
        return colName(c) + (r + 1)
    }

    /**
     * 파일 이름 표기: 이름순 정렬이 좌표순이 되도록 자릿수를 맞춘다.
     * 예) B10 → "B10", B2 → "B02". 27열 이상인 격자에서는 한 글자 열 앞에 '-'를 붙여 AA보다 앞에 오게 한다 ("-B02").
     */
    fun fileLabel(nx: Float, ny: Float, d: Dims): String {
        val (c, r) = cellIndex(nx, ny, d)
        val colWidth = colName(d.cols - 1).length
        val rowWidth = max(2, d.rows.toString().length)
        val col = colName(c).padStart(colWidth, '-')
        return col + (r + 1).toString().padStart(rowWidth, '0')
    }
}
