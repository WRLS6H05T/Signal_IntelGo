package dev.skyward.rf.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The crew count is the number of people on the station, not the number of people in orbit.
 *
 * A Tiangong crew and a free-flying Dragon are both in space; reading the roster's own total
 * would put them aboard the ISS and make the card confidently wrong.
 */
class IssCrewTest {

    private val roster = """
        {"number":10,"iss_expedition":75,"people":[
          {"name":"Zhu Yangzhu","spacecraft":"Shenzhou 23","iss":false},
          {"name":"Pyotr Dubrov","spacecraft":"Soyuz MS-29","iss":true},
          {"name":"Jessica Watkins","spacecraft":"Crew-13 Dragon","iss":true}
        ]}
    """.trimIndent()

    @Test
    fun `the count is the station crew, not everyone in space`() {
        val crew = IssCrew.parse(roster)
        assertEquals(2, crew?.aboard)
        assertEquals(75, crew?.expedition)
        assertEquals("2 · Expedition 75", crew?.label)
    }

    @Test
    fun `a roster with nobody flagged aboard is not a crew of zero`() {
        // Broken or reshaped JSON must leave the card with a dash, not with "0" as a fact.
        assertNull(IssCrew.parse("""{"number":3,"people":[{"name":"A","iss":false}]}"""))
        assertNull(IssCrew.parse("not json at all"))
    }

    @Test
    fun `the label says the count on its own when no expedition is named`() {
        val crew = IssCrew.parse("""{"people":[{"name":"A","iss":true},{"name":"B","iss":true}]}""")
        assertEquals("2", crew?.label)
    }
}
