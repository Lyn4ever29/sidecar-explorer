package cn.jhacker.sidecar

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import java.time.Instant

@Tag("externalFolder")
class ExternalFolderEntry {
    @Attribute
    var path: String = ""

    @Attribute
    var displayName: String = ""

    @Attribute
    var addedAt: Long = 0

    constructor()

    constructor(path: String, displayName: String, addedAt: Long) {
        this.path = path
        this.displayName = displayName
        this.addedAt = addedAt
    }
}

class SidecarProjectState {
    var folders: MutableList<ExternalFolderEntry> = mutableListOf()
    var readOnlyMode: Boolean = true
    var showHiddenFiles: Boolean = true
}

@Service(Service.Level.PROJECT)
@State(
    name = "SidecarExplorerState",
    storages = [Storage("sidecarExplorer.xml")]
)
class SidecarProjectService(private val project: Project) : PersistentStateComponent<SidecarProjectState> {
    private var state = SidecarProjectState()
    private val listeners = mutableListOf<() -> Unit>()

    override fun getState(): SidecarProjectState = state

    override fun loadState(state: SidecarProjectState) {
        this.state = state
        notifyChanged()
    }

    fun folders(): List<ExternalFolderEntry> = state.folders.toList()

    fun isReadOnlyMode(): Boolean = state.readOnlyMode

    fun setReadOnlyMode(enabled: Boolean) {
        if (state.readOnlyMode != enabled) {
            state.readOnlyMode = enabled
            notifyChanged()
        }
    }

    fun isShowHiddenFiles(): Boolean = state.showHiddenFiles

    fun setShowHiddenFiles(enabled: Boolean) {
        if (state.showHiddenFiles != enabled) {
            state.showHiddenFiles = enabled
            notifyChanged()
        }
    }

    fun addFolder(path: String, displayName: String) {
        val normalizedPath = SidecarPathRules.normalizePath(path)
        if (state.folders.any { SidecarPathRules.normalizePath(it.path) == normalizedPath }) {
            return
        }

        state.folders.add(
            ExternalFolderEntry(
                path = normalizedPath,
                displayName = displayName.ifBlank { normalizedPath.substringAfterLast('/') },
                addedAt = Instant.now().toEpochMilli()
            )
        )
        notifyChanged()
    }

    fun removeFolder(path: String) {
        val normalizedPath = SidecarPathRules.normalizePath(path)
        val changed = state.folders.removeIf { SidecarPathRules.normalizePath(it.path) == normalizedPath }
        if (changed) {
            notifyChanged()
        }
    }

    fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it.invoke() }
    }
}
