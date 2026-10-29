package app.orchard.tv.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroVisibilityTest {
    @Test
    fun heroBelongsOnlyToFnTileAndItsControls() {
        assertTrue(heroVisible("fn", "fn", heroFocused = false, pillFocused = false))
        assertTrue(heroVisible("fn", "fn", heroFocused = true, pillFocused = false))
        assertTrue(heroVisible("mango", "fn", heroFocused = false, pillFocused = true))
        assertFalse(heroVisible("mango", "fn", heroFocused = false, pillFocused = false))
        assertFalse(heroVisible(null, "fn", heroFocused = false, pillFocused = false))
    }
}
