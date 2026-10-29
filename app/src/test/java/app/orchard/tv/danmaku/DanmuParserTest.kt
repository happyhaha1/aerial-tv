package app.orchard.tv.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The service's comment JSON as it arrives from the field (`docs/DANMAKU.md` §1): three envelope
 * shapes, the short form, and lines that are junk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DanmuParserTest {
    @Test
    fun commentsEnvelopeIsRead() {
        val comments = DanmuParser.parse(
            """{"errorCode":0,"comments":[{"m":"first","p":"1.5,1,16777215,abc"}]}""",
        )
        assertEquals(1, comments.size)
        assertEquals("first", comments[0].text)
        assertEquals(1.5f, comments[0].atSec, 0.001f)
        assertEquals(Danmaku.SCROLL, comments[0].mode)
        assertEquals(0xFFFFFFFF.toInt(), comments[0].colorArgb)
    }

    @Test
    fun dataEnvelopeIsRead() {
        val comments = DanmuParser.parse("""{"data":[{"m":"second","p":"2,5,255,abc"}]}""")
        assertEquals(listOf("second"), comments.map { it.text })
        assertEquals(Danmaku.TOP, comments[0].mode)
        assertEquals(0xFF0000FF.toInt(), comments[0].colorArgb)
    }

    @Test
    fun bareArrayIsRead() {
        val comments = DanmuParser.parse("""[{"m":"third","p":"3,4,16777215,abc"}]""")
        assertEquals(listOf("third"), comments.map { it.text })
        assertEquals(Danmaku.BOTTOM, comments[0].mode)
    }

    @Test
    fun theShortFormTakesTimeFromPAndColourFromC() {
        val comment = DanmuParser.parse("""[{"m":"short","p":"12.5","c":16711680}]""").single()
        assertEquals(12.5f, comment.atSec, 0.001f)
        assertEquals(0xFFFF0000.toInt(), comment.colorArgb)
        assertEquals(Danmaku.SCROLL, comment.mode)
    }

    @Test
    fun legacyScrollModesAreNormalizedAndAdvancedModesAreDropped() {
        val comments = DanmuParser.parse(
            """[
              {"m":"one","p":"1,1,16777215,abc"},
              {"m":"two","p":"2,2,16777215,abc"},
              {"m":"three","p":"3,3,16777215,abc"},
              {"m":"reverse","p":"4,6,16777215,abc"},
              {"m":"special","p":"5,7,16777215,abc"},
              {"m":"code","p":"6,8,16777215,abc"},
              {"m":"bas","p":"7,9,16777215,abc"},
              {"m":"junk","p":"8,scroll,16777215,abc"},
              {"m":"zero","p":"9,0,16777215,abc"},
              {"m":"bottom","p":"10,4,16777215,abc"},
              {"m":"top","p":"11,5,16777215,abc"}
            ]""".trimIndent(),
        )
        // Types 2 and 3 are the legacy right-to-left scroll; 7, 8, 9 and junk have no lane here.
        assertEquals(listOf("one", "two", "three", "reverse", "bottom", "top"), comments.map { it.text })
        assertEquals(
            listOf(
                Danmaku.SCROLL, Danmaku.SCROLL, Danmaku.SCROLL, Danmaku.REVERSE,
                Danmaku.BOTTOM, Danmaku.TOP,
            ),
            comments.map { it.mode },
        )
    }

    @Test
    fun malformedLinesAreSkippedAndBreaksBecomeSpaces() {
        val comments = DanmuParser.parse(
            """
            [
              {"m":"good","p":"1,1,16777215,abc"},
              {"m":"","p":"2,1,16777215,abc"},
              {"m":"nan","p":"soon,1,16777215,abc"},
              {"m":"negative","p":"-4,1,16777215,abc"},
              {"p":"3,1,16777215,abc"},
              {"m":"two\nlines","p":"4,1,16777215,abc"},
              "not an entry",
              {"m":"short","p":"5.5"},
              {"m":"after","p":"6,1,16777215,abc"}
            ]
            """.trimIndent(),
        )
        assertEquals(listOf("good", "two lines", "short", "after"), comments.map { it.text })
        assertEquals(listOf(1f, 4f, 5.5f, 6f), comments.map { it.atSec })
        assertEquals(listOf(true, true), comments.drop(2).map { it.colorArgb == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun anUnreadableBodyReadsAsNoComments() {
        assertTrue(DanmuParser.parse("").isEmpty())
        assertTrue(DanmuParser.parse("not json").isEmpty())
        assertTrue(DanmuParser.parse("{}").isEmpty())
        assertTrue(DanmuParser.parse("""{"comments":{}}""").isEmpty())
        assertTrue(DanmuParser.parse("[]").isEmpty())
    }

    @Test
    fun moreCommentsThanTheCapAreThinnedToIt() {
        val body = (0 until 20).joinToString(",", "[", "]") { """{"m":"c$it","p":"$it,1,16777215,abc"}""" }
        val comments = DanmuParser.parse(body, limit = 5)
        assertEquals(5, comments.size)
        assertEquals(comments.map { it.atSec }.sorted(), comments.map { it.atSec })
    }
}