package learn

import org.junit.Test
import kotlin.test.assertEquals

class IntLearn {

    @Test
    fun `divide integer behaves as floor`() {
        assertEquals(0, 0 / 2)
        assertEquals(0, 1 / 2)
        assertEquals(1, 2 / 2)
        assertEquals(1, 3 / 2)
        assertEquals(4, 8 / 2)
        assertEquals(4, 9 / 2)
        assertEquals(5, 10 / 2)
    }
}