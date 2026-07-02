package cn.jhacker.sidecar

import com.intellij.openapi.vfs.VirtualFile
import javax.swing.tree.DefaultMutableTreeNode

class SidecarTreeNode(val item: SidecarNodeItem) : DefaultMutableTreeNode(item) {
    var childrenLoaded: Boolean = false
    var childrenLoading: Boolean = false
}

sealed interface SidecarNodeItem {
    val label: String
    val path: String?
}

data object SidecarRootItem : SidecarNodeItem {
    override val label: String = "文件"
    override val path: String? = null
}

data object LoadingItem : SidecarNodeItem {
    override val label: String = "Loading..."
    override val path: String? = null
}

data class ExternalRootItem(
    val entry: ExternalFolderEntry,
    val virtualFile: VirtualFile?
) : SidecarNodeItem {
    override val label: String = if (virtualFile == null) "${entry.displayName} (missing)" else entry.displayName
    override val path: String = entry.path
}

data class VirtualFileItem(val file: VirtualFile) : SidecarNodeItem {
    override val label: String = file.name
    override val path: String = file.path
}
