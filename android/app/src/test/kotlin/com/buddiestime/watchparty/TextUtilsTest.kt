package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Test

class TextUtilsTest {
    @Test fun capitalizes_lowercase_first_letter() {
        assertEquals("Priya", "priya".capitalizeFirst())
    }
    @Test fun leaves_already_capitalized_untouched() {
        assertEquals("Priya", "Priya".capitalizeFirst())
    }
    @Test fun leaves_blank_string_untouched() {
        assertEquals("", "".capitalizeFirst())
    }
    @Test fun only_affects_first_character() {
        assertEquals("Priya sonu", "priya sonu".capitalizeFirst())
    }
}
