package dev.ticbox.companion.ui.faces

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PhotoResamplerTest {
    private class Picture(
        override val width: Int,
        override val height: Int,
        val pixel: (Int, Int) -> Int,
    ) : PixelRows {
        override fun read(y: Int, into: IntArray) {
            for (x in 0 until width) into[x] = pixel(x, y)
        }
    }

    private fun gray(value: Int) = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    private fun level(pixel: Int) = pixel shr 16 and 0xFF

    @Test
    fun everyFilterKeepsAnEvenColorWhenShrinkingOrEnlarging() {
        val color = 0xFF336699.toInt()
        val flat = Picture(300, 200) { _, _ -> color }
        for (filter in ResampleFilter.entries) {
            for (side in listOf(150.0, 40.0)) {
                val out =
                    renderCrop(flat, PhotoCrop(150.0, 100.0, side), 64, filter, Sharpening.OFF)
                assertTrue(out.all { it == color }, "$filter at $side")
            }
        }
    }

    @Test
    fun aCropAtTheScreensSizeIsCopiedPixelForPixel() {
        val noise = Picture(20, 20) { x, y -> gray((x * 37 + y * 91) % 256) }
        for (filter in ResampleFilter.entries) {
            val out = renderCrop(noise, PhotoCrop(10.0, 10.0, 8.0), 8, filter, Sharpening.OFF)
            for (y in 0 until 8) {
                for (x in 0 until 8) assertEquals(
                    noise.pixel(x + 6, y + 6),
                    out[y * 8 + x],
                    "$filter",
                )
            }
        }
    }

    @Test
    fun shrinkingAveragesFineDetailInsteadOfSkippingIt() {
        val stripes = Picture(64, 64) { x, _ -> gray(if (x % 2 == 0) 0 else 254) }
        for (filter in ResampleFilter.entries) {
            val out = renderCrop(stripes, PhotoCrop(32.0, 32.0, 64.0), 16, filter, Sharpening.OFF)
            for (x in 3..12) assertEquals(127, level(out[8 * 16 + x]), "$filter at $x")
        }
    }

    @Test
    fun whatLiesPastThePhotoIsBlack() {
        val white = Picture(100, 50) { _, _ -> gray(255) }
        // A 100 pixel square around a 100 × 50 photo: a quarter above it and below it is empty.
        val out =
            renderCrop(
                white,
                PhotoCrop(50.0, 25.0, 100.0),
                20,
                ResampleFilter.LANCZOS3,
                Sharpening.OFF,
            )
        assertEquals(
            listOf(0, 0, 255, 255, 255, 0),
            listOf(0, 4, 5, 10, 14, 15).map { level(out[it * 20 + 10]) },
        )
    }

    @Test
    fun sharpeningLeavesEvenAreasAndStrengthensEdges() {
        val edge = Picture(32, 32) { x, _ -> gray(if (x < 16) 60 else 180) }
        val crop = PhotoCrop(16.0, 16.0, 32.0)
        val plain = renderCrop(edge, crop, 32, ResampleFilter.BILINEAR, Sharpening.OFF)
        val sharp = renderCrop(edge, crop, 32, ResampleFilter.BILINEAR, Sharpening.STRONG)
        val row = 16 * 32
        assertEquals(level(plain[row + 2]), level(sharp[row + 2]))
        assertTrue(level(sharp[row + 15]) < level(plain[row + 15]))
        assertTrue(level(sharp[row + 16]) > level(plain[row + 16]))
    }

    @Test
    fun theCropStaysOnThePhotoAndZoomsAroundTheFingers() {
        val start = PhotoCrop.fill(400, 300)
        assertEquals(PhotoCrop(200.0, 150.0, 300.0), start)
        fun move(panX: Float, zoom: Float, focus: Float) =
            start.moved(300f, focus, focus, panX, 0f, zoom).within(400, 300, WatchScreenPixels)
        // Dragged far to the right, it stops at the photo's left edge.
        assertEquals(PhotoCrop(150.0, 150.0, 300.0), move(panX = 500f, zoom = 1f, focus = 150f))
        // Zoomed in at the top left corner, that corner stays put.
        assertEquals(PhotoCrop(125.0, 75.0, 150.0), move(panX = 0f, zoom = 2f, focus = 0f))
        // Zoomed far out, the whole photo fits inside the square and no more.
        assertEquals(400.0, move(panX = 0f, zoom = 0.1f, focus = 150f).side)
        // Zoomed far in, it stops at four times enlarged.
        assertEquals(WatchScreenPixels / 4.0, move(panX = 0f, zoom = 100f, focus = 150f).side)
    }
}
