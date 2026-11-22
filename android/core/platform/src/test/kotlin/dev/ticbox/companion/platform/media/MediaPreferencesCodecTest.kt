package dev.ticbox.companion.platform.media

import dev.ticbox.companion.api.MediaControlsSettings
import kotlin.test.*

class MediaPreferencesCodecTest {
    @Test fun `local media settings round trip unicode and newline package strings`() {
        val settings = MediaControlsSettings(false, "example.播放器\nsecond", true)
        assertEquals(settings, MediaPreferencesCodec.decode(MediaPreferencesCodec.encode(settings)))
    }
    @Test fun `missing and future preference fields preserve conservative auto launch default`() {
        assertEquals(MediaControlsSettings(), MediaPreferencesCodec.decode("future=value\nenabled=nonsense".toByteArray()))
        assertFailsWith<IllegalArgumentException> { MediaPreferencesCodec.decode(ByteArray(MediaPreferencesCodec.MAX_BYTES + 1)) }
    }
}
