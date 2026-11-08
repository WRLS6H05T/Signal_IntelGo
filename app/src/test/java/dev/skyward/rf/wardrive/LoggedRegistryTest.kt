package dev.skyward.rf.wardrive

import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The registry is the map's memory, and it only exists on disk.
 *
 * A mesh node cannot re-log itself the way a satellite or an aircraft does: a repeater
 * advertises every few hours, so whatever is not written survives only until the process ends.
 * These tests pin the two ways that used to go wrong - a table the file cannot encode, and a
 * write that failed while the table was marked saved.
 */
class LoggedRegistryTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "logged_" + System.nanoTime()).apply { mkdirs() }

    @Test
    fun `every mesh entity comes back after a restart`() {
        val dir = tempDir()
        val registry = LoggedRegistry(FileStore(dir))
        registry.logBatch(
            1_000L,
            (1..25).map { i ->
                LoggedRegistry.Sighting(Layer.MESH, "!node$i", "Roof $i", 50.0 + i / 1000.0, -1.0 - i / 1000.0)
            },
        )
        registry.save()

        val reopened = LoggedRegistry(FileStore(dir))
        val mesh = reopened.state.value.values.filter { it.layer == Layer.MESH }
        assertEquals(25, mesh.size)
        assertTrue(mesh.all { it.lat in 50.0..51.0 })
    }

    @Test
    fun `one unencodable sighting cannot stop the registry being written`() {
        val dir = tempDir()
        val registry = LoggedRegistry(FileStore(dir))
        // A bad element set or a feed that answers oddly can produce a NaN; JSON cannot hold
        // one, and before the guard that poisoned the whole file for the rest of the session.
        registry.log(Layer.SATELLITE, "25544", "ISS", Double.NaN, 0.0)
        registry.log(Layer.AIRCRAFT, "abc123", "Plane", 51.0, Double.POSITIVE_INFINITY)
        registry.log(Layer.MESH, "!ok", "Roof", 51.0, -0.1)
        registry.save()

        val reopened = LoggedRegistry(FileStore(dir))
        assertEquals(1, reopened.all().size)
        assertEquals("MESH:!ok", reopened.all()[0].key)
    }

    @Test
    fun `a sighting with no position is not stored`() {
        val registry = LoggedRegistry(FileStore(tempDir()))
        registry.log(Layer.MESH, "!nowhere", "Roof", Double.NaN, -0.1)
        assertEquals(0, registry.all().size)
    }
}
