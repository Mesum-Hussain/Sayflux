package app.whistleflux.engine

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Kotlin clean-up must match the desktop app's, case for case. */
class CleanupTest {
    @Test
    fun matchesSharedCases() {
        // Unit tests run from mobile/android/app.
        val file = File("../../../shared/cleanup-cases.json")
        val cases = JSONObject(file.readText()).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            assertEquals("input: ${case.getString("in")}", case.getString("out"), Cleanup.clean(case.getString("in")))
        }
    }
}
