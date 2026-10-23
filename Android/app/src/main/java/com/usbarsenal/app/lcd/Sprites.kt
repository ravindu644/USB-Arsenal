package com.usbarsenal.app.lcd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import kotlin.math.floor
import kotlin.math.max

// A bitmap written as rows of '#' (lit) and '.' (unlit), turned into one path of 1x1 cells
class Sprite(rows: List<String>) {
    val width = rows.maxOf { it.length }
    val height = rows.size
    internal val path = Path().apply {
        rows.forEachIndexed { y, row ->
            var x = 0
            while (x < row.length) {
                if (row[x] != '#') {
                    x++
                    continue
                }
                val start = x
                while (x < row.length && row[x] == '#') x++
                addRect(Rect(start.toFloat(), y.toFloat(), x.toFloat(), y + 1f))
            }
        }
    }
}

/*
 * One sprite pixel is [cell] dp, rounded down to whole device pixels so every edge lands on a
 * pixel boundary. The leftover is split around the sprite.
 */
@Composable
fun SpriteIcon(sprite: Sprite, cell: Dp, modifier: Modifier = Modifier, color: Color = LocalLcd.current.ink) {
    Canvas(modifier.size(cell * sprite.width, cell * sprite.height)) {
        val px = max(1f, floor(cell.toPx()))
        val left = floor((size.width - px * sprite.width) / 2)
        val top = floor((size.height - px * sprite.height) / 2)
        translate(left, top) {
            scale(px, pivot = Offset.Zero) { drawPath(sprite.path, color) }
        }
    }
}

// Pin, eyes open. The 2.6s loop shuts them twice, as lcd.css's .shut does at 70-76% and 84-90%
@Composable
fun Pin(cell: Dp, modifier: Modifier = Modifier, color: Color = LocalLcd.current.ink) {
    val shut = loop(2600) { it in 1820 until 1976 || it in 2184 until 2340 }
    SpriteIcon(if (shut) Sprites.PinBlink else Sprites.Pin, cell, modifier, color)
}

// Bitmaps from DESIGN.md and the Pixel kit screen (docs/developer/design/screens/Kit.dc.html)
object Sprites {
    private val pinTop = listOf("...##########...", "...#........#...")
    private val pinBody = listOf(
        "..############..",
        ".##############.",
        ".##############.",
        ".######..######.",
        ".##############.",
        ".##############.",
        "..############..",
        "....##.##.##....",
        "....##.##.##....",
        "...###.##.###...",
        ".......##.......",
        ".......####.....",
        "..........##....",
    )

    val Pin = Sprite(pinTop + listOf("...#.##..##.#...", "...#.##..##.#...", "...#........#...") + pinBody)
    val PinBlink = Sprite(pinTop + listOf("...#........#...", "...#.##..##.#...", "...#........#...") + pinBody)

    // Plugged in (^ ^) and after running into the port (x x), in the bottom bar
    val PinHappy = Sprite(pinTop + listOf("...#.#....#.#...", "...##.#..#.##...", "...#........#...") + pinBody)
    val PinDizzy = Sprite(pinTop + listOf("...##.#..#.##...", "...#.#....#.#...", "...##.#..#.##...") + pinBody)

    val Storage = Sprite(
        listOf(
            ".....######.....",
            "...##......##...",
            "..#.#........#..",
            ".#.##.........#.",
            ".#....####....#.",
            "#....#....#....#",
            "#...#......#...#",
            "#...#..##..#...#",
            "#...#..##..#...#",
            "#...#......#...#",
            "#....#....#....#",
            ".#....####....#.",
            ".#............#.",
            "..#..........#..",
            "...##......##...",
            ".....######.....",
        ),
    )

    val Tether = Sprite(
        listOf(
            "################",
            "#..............#",
            "#..##..##..##..#",
            "#..##..##..##..#",
            "#..............#",
            "#..............#",
            "#..............#",
            "#..............#",
            "###..........###",
            "..#..........#..",
            "..#..........#..",
            "..###......###..",
            "....#......#....",
            "....########....",
        ),
    )

    val Hid = Sprite(
        listOf(
            "###############",
            "#.............#",
            "#.#.#.#.#.#.#.#",
            "#.............#",
            "#..#.#.#.#.#..#",
            "#.............#",
            "#.#..#####..#.#",
            "#.............#",
            "###############",
        ),
    )

    val Usb = Sprite(
        listOf(
            ".......#.......",
            "......###......",
            ".....#####.....",
            ".......#....###",
            "..##...#....###",
            ".####..#....###",
            ".####..#.....#.",
            "..##...#.....#.",
            "...#...#....#..",
            "....#..#...#...",
            ".....#.#..#....",
            "......###......",
            ".......#.......",
            "......###......",
            ".....#####.....",
            "......###......",
        ),
    )

    val Eject = Sprite(
        listOf(
            ".....##.....",
            "....####....",
            "...######...",
            "..########..",
            ".##########.",
            "############",
            "............",
            "............",
            "############",
            "############",
        ),
    )

    // The empty storage screen's question, beside Pin
    val Question = Sprite(listOf("..#####..", ".##...##.", "......##.", ".....##..", "....##...", "....##...", ".........", "....##...", "....##..."))

    // Quacky's: a rubber duck facing left, eye, beak, wing and feet
    val Duck = Sprite(
        listOf(
            "....####........",
            "...######.......",
            "...#.#####......",
            "##########......",
            ".#########......",
            "...######.......",
            "....####......#.",
            "..############.#",
            ".##############.",
            ".###.......####.",
            ".####.....#####.",
            "..############..",
            "...##########...",
            "................",
            "....##...##.....",
            "...###..###.....",
        ),
    )

    // Keys + mouse's arrow keys
    val ArrowUp = Sprite(listOf("...#...", "..###..", ".#####.", "#######", "..###..", "..###..", "..###.."))
    val ArrowRight = Sprite(listOf("...#...", "...##..", "######.", "#######", "######.", "...##..", "...#..."))
    val ArrowDown = Sprite(listOf("..###..", "..###..", "..###..", "#######", ".#####.", "..###..", "...#..."))
    val ArrowLeft = Sprite(listOf("...#...", "..##...", ".######", "#######", ".######", "..##...", "...#..."))

    val Spark = Sprite(listOf("...#...", ".......", "...#...", "#.###.#", "...#...", ".......", "...#..."))

    // The trackpad's way into full screen and back out: corners out, corners in
    val Expand = Sprite(listOf("###...###", "#.......#", "#.......#", ".........", ".........", ".........", "#.......#", "#.......#", "###...###"))
    val Collapse = Sprite(listOf("..#...#..", "..#...#..", "###...###", ".........", ".........", ".........", "###...###", "..#...#..", "..#...#.."))

    // The slider's thumb: a round dot on the pixel grid
    val Dot = Sprite(
        listOf(
            "....######....",
            "..##########..",
            ".############.",
            ".############.",
            "##############",
            "##############",
            "##############",
            "##############",
            "##############",
            "##############",
            ".############.",
            ".############.",
            "..##########..",
            "....######....",
        ),
    )

    val Pointer = Sprite(
        listOf(
            "#.......",
            "##......",
            "###.....",
            "####....",
            "#####...",
            "######..",
            "#######.",
            "####....",
            "##.##...",
            "#..##...",
            "....##..",
            "....##..",
        ),
    )
}
