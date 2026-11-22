package dev.ticbox.companion.api

import kotlin.test.*

class ToolboxScreenCodecOptionsTest {
    @Test fun entriesLoseTheirSpacesAndKeepTheirOrder() {
        assertEquals("video-qp-max=35,bitrate-mode=1,vendor.mtk.ext.venc.qpvbr.upper-enable:int=0",
            ToolboxScreenCodecOptions.normalize(" video-qp-max=35 , bitrate-mode=1,vendor.mtk.ext.venc.qpvbr.upper-enable:int=0 "))
        assertEquals("a:long=-5,b:float=0.5,c:string=x-y.z", ToolboxScreenCodecOptions.normalize("a:long=-5,b:float=0.5,c:string=x-y.z"))
        assertEquals("", ToolboxScreenCodecOptions.normalize("  "))
    }

    @Test fun anythingThatCouldLeaveTheOptionOrIsNotItsTypeIsRefused() {
        val refused = listOf("a=1 b=2", "a=1,,b=2", "a=1;b=2", "x=1 power_on=false", "a", "=1", "a:int=1.5", "a=", "1a=2",
            "a:double=1", "a:float=NaN", "a:string=x/y", "a=99999999999", List(17) { "k$it=1" }.joinToString(","))
        for (text in refused) assertNull(ToolboxScreenCodecOptions.normalize(text), text)
        assertFailsWith<IllegalArgumentException> { ToolboxScreenOptions(videoCodecOptions = "a=1, b=2") }
        assertEquals("a=1", ToolboxScreenOptions(videoCodecOptions = "a=1").videoCodecOptions)
    }
}
