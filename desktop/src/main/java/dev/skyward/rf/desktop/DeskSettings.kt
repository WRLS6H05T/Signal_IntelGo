package dev.skyward.rf.desktop

import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.Settings
import dev.skyward.rf.core.SettingsStore
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The desk's settings, in the phone's own store.
 *
 * It is the same [Settings] type and the same file the handset writes, under the same
 * `~/.signal-intelgo` directory, so a choice made in Setup on either end reads the same on the
 * other. Anything the desk cannot honour is still shown, because a setting that exists only on the
 * phone would make the two ends disagree about what the app is set to.
 */
class DeskSettings(home: File) {
    private val store = SettingsStore(FileStore(home))

    val state: StateFlow<Settings> = store.state

    val current: Settings get() = store.current

    fun update(block: (Settings) -> Settings) {
        store.update(block)
    }
}
