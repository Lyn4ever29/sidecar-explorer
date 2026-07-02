package cn.jhacker.sidecar

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.pom.Navigatable
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class SidecarToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SidecarExplorerPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

private class SidecarExplorerPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) : JPanel(BorderLayout()), Disposable {
    private val service = project.getService(SidecarProjectService::class.java)
    private val rootNode = SidecarTreeNode(SidecarRootItem)
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = SidecarTree()
    private val removeListener = service.addListener { reloadRoots() }

    init {
        installToolWindowIconUpdater()
        toolWindow.setTitleActions(createTitleActions())
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = SidecarTreeCellRenderer(project)
        TreeUtil.installActions(tree)
        TreeSpeedSearch(tree) { path ->
            ((path.lastPathComponent as? SidecarTreeNode)?.item?.label).orEmpty()
        }

        tree.addTreeWillExpandListener(object : javax.swing.event.TreeWillExpandListener {
            override fun treeWillExpand(event: javax.swing.event.TreeExpansionEvent) {
                ensureChildrenLoaded(event.path.lastPathComponent as? SidecarTreeNode ?: return)
            }

            override fun treeWillCollapse(event: javax.swing.event.TreeExpansionEvent) = Unit
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    openSelectedFileOnly()
                }
            }

            override fun mousePressed(event: MouseEvent) = maybeShowPopup(event)

            override fun mouseReleased(event: MouseEvent) = maybeShowPopup(event)
        })

        add(createToolbar("SidecarExplorerMainToolbar", createMainToolbarActions()).component, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        reloadRoots()
    }

    private fun installToolWindowIconUpdater() {
        updateToolWindowIcon()
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    updateToolWindowIcon()
                }

                override fun toolWindowShown(toolWindow: ToolWindow) {
                    updateToolWindowIcon()
                }
            }
        )
    }

    private fun updateToolWindowIcon() {
        toolWindow.setIcon(
            if (toolWindow.isVisible) {
                SELECTED_TOOL_WINDOW_ICON
            } else {
                DEFAULT_TOOL_WINDOW_ICON
            }
        )
    }

    private fun createToolbar(place: String, group: DefaultActionGroup) = ActionManager.getInstance()
        .createActionToolbar(place, group, true)
        .also { it.targetComponent = tree }

    private fun createMainToolbarActions() = DefaultActionGroup(
        AddFolderAction(),
        RemoveFolderAction(),
        RefreshAction(),
        RevealAction()
    )

    private fun createTitleActions() = listOf(
        LocateOpenFileAction(),
        ExpandAllAction(),
        CollapseAllAction()
    )

    private fun reloadRoots() {
        rootNode.removeAllChildren()

        service.folders().forEach { entry ->
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(entry.path)
            val node = SidecarTreeNode(ExternalRootItem(entry, virtualFile))
            if (virtualFile?.isDirectory == true) {
                node.add(SidecarTreeNode(LoadingItem))
            }
            rootNode.add(node)
        }

        treeModel.reload()
    }

    private fun ensureChildrenLoaded(node: SidecarTreeNode) {
        if (node.childrenLoaded || node.childrenLoading) {
            return
        }

        val directory = directoryFor(node) ?: return
        val showHiddenFiles = service.isShowHiddenFiles()

        node.childrenLoading = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val children = directory.children
                .asSequence()
                .filterNot { SidecarPathRules.isIgnored(it, showHiddenFiles) }
                .sortedWith { left, right -> SidecarPathRules.compareFiles(left, right) }
                .toList()

            SwingUtilities.invokeLater {
                node.removeAllChildren()
                children.forEach { child ->
                    val childNode = SidecarTreeNode(VirtualFileItem(child))
                    if (child.isDirectory) {
                        childNode.add(SidecarTreeNode(LoadingItem))
                    }
                    node.add(childNode)
                }
                node.childrenLoaded = true
                node.childrenLoading = false
                treeModel.reload(node)
            }
        }
    }

    private fun directoryFor(node: SidecarTreeNode): VirtualFile? {
        val file = when (val item = node.item) {
            is ExternalRootItem -> item.virtualFile
            is VirtualFileItem -> item.file
            else -> null
        }
        return file?.takeIf { it.isDirectory }
    }

    private fun maybeShowPopup(event: MouseEvent) {
        if (!event.isPopupTrigger) {
            return
        }

        tree.getPathForLocation(event.x, event.y)?.let { tree.selectionPath = it }
        val popup = ActionManager.getInstance().createActionPopupMenu(
            "SidecarExplorerPopup",
            createContextMenuActions()
        )
        popup.component.show(tree, event.x, event.y)
    }

    private fun createContextMenuActions(): DefaultActionGroup {
        val group = DefaultActionGroup()
        val manager = ActionManager.getInstance()

        if (service.isReadOnlyMode()) {
            group.add(OpenAction())
            group.add(CopyPathAction())
            group.add(RevealAction())
        } else {
            val projectViewPopup = manager.getAction("ProjectViewPopupMenu") as? ActionGroup
            if (projectViewPopup != null) {
                group.addAll(projectViewPopup)
            } else {
                group.add(OpenAction())
                group.add(CopyPathAction())
                group.add(RevealAction())
            }
            group.addSeparator()
            group.add(CopyPathAction())
            group.add(RevealAction())
        }

        if (selectedNode()?.item is ExternalRootItem) {
            group.addSeparator()
            group.add(RemoveFolderAction("Remove Root"))
        }

        return group
    }

    private fun openSelectedFileOnly() {
        val item = selectedNode()?.item as? VirtualFileItem ?: return
        if (!item.file.isDirectory) {
            FileEditorManager.getInstance(project).openFile(item.file, true)
        }
    }

    private fun openOrToggleSelected() {
        val node = selectedNode() ?: return
        when (val item = node.item) {
            is ExternalRootItem -> toggleDirectory(node)
            is VirtualFileItem -> {
                if (item.file.isDirectory) {
                    toggleDirectory(node)
                } else {
                    FileEditorManager.getInstance(project).openFile(item.file, true)
                }
            }
            else -> Unit
        }
    }

    private fun toggleDirectory(node: SidecarTreeNode) {
        val path = TreePath(node.path)
        if (tree.isExpanded(path)) {
            tree.collapsePath(path)
        } else {
            ensureChildrenLoaded(node)
            tree.expandPath(path)
        }
    }

    private fun selectedNode(): SidecarTreeNode? = tree.selectionPath?.lastPathComponent as? SidecarTreeNode

    private fun selectedPath(): String? = selectedNode()?.item?.path

    private fun selectedVirtualFile(): VirtualFile? = when (val item = selectedNode()?.item) {
        is ExternalRootItem -> item.virtualFile
        is VirtualFileItem -> item.file
        else -> null
    }

    private fun removeSelectedRoot() {
        val item = selectedNode()?.item as? ExternalRootItem ?: return
        service.removeFolder(item.entry.path)
    }

    private fun copySelectedPath() {
        val path = selectedPath() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(path))
    }

    private fun revealSelected() {
        val path = selectedPath() ?: return
        val file = File(path)
        val target = if (file.isDirectory) file else file.parentFile
        if (target != null && target.exists() && Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(target)
        }
    }

    private fun selectLoadedFile(file: VirtualFile) {
        rootNode.depthFirstEnumeration().asSequence()
            .filterIsInstance<SidecarTreeNode>()
            .firstOrNull { (it.item as? VirtualFileItem)?.file == file }
            ?.let { node ->
                val path = TreePath(node.path)
                tree.selectionPath = path
                tree.scrollPathToVisible(path)
            }
    }

    override fun dispose() {
        removeListener.invoke()
    }

    private inner class SidecarTree : Tree(treeModel), DataProvider {
        override fun getData(dataId: String): Any? {
            val virtualFile = selectedVirtualFile()

            return when {
                CommonDataKeys.PROJECT.`is`(dataId) -> project
                CommonDataKeys.VIRTUAL_FILE.`is`(dataId) -> virtualFile
                CommonDataKeys.VIRTUAL_FILE_ARRAY.`is`(dataId) -> virtualFile?.let { arrayOf(it) }
                PlatformCoreDataKeys.CONTEXT_COMPONENT.`is`(dataId) -> this
                else -> null
            }
        }
    }

    private abstract inner class SidecarAction(
        text: String,
        description: String,
        icon: Icon?
    ) : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class AddFolderAction : SidecarAction("Add Folder", "Add an external folder", AllIcons.General.Add) {
        override fun actionPerformed(event: AnActionEvent) {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Add Sidecar Folder")
                .withDescription("Choose a local folder to browse without adding it to the IntelliJ project model.")
            val folder = FileChooser.chooseFile(descriptor, project, null) ?: return
            service.addFolder(folder.path, folder.name)
        }
    }

    private inner class RemoveFolderAction(
        text: String = "Remove Folder"
    ) : SidecarAction(text, "Remove selected external root", AllIcons.General.Remove) {
        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedNode()?.item is ExternalRootItem
        }

        override fun actionPerformed(event: AnActionEvent) {
            removeSelectedRoot()
        }
    }

    private inner class RefreshAction : SidecarAction("Refresh", "刷新文件树", AllIcons.Actions.Refresh) {
        override fun actionPerformed(event: AnActionEvent) {
            rootNode.depthFirstEnumeration().asSequence()
                .filterIsInstance<SidecarTreeNode>()
                .forEach { it.childrenLoaded = false }
            reloadRoots()
        }
    }

    private inner class RevealAction : SidecarAction(
        "Reveal in Finder/Explorer",
        "Reveal selected file or folder",
        AllIcons.General.OpenDisk
    ) {
        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedPath() != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            revealSelected()
        }
    }

    private inner class OpenAction : SidecarAction("Open", "Open file or expand directory", AllIcons.Actions.MenuOpen) {
        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedPath() != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            openOrToggleSelected()
        }
    }

    private inner class CopyPathAction : SidecarAction("Copy Path", "Copy absolute path", AllIcons.Actions.Copy) {
        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedPath() != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            copySelectedPath()
        }
    }

    private inner class LocateOpenFileAction : SidecarAction(
        "Select Opened File",
        "如果当前打开的文件已加载到文件树中，则定位到该文件",
        AllIcons.General.Locate
    ) {
        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = FileEditorManager.getInstance(project).selectedFiles.isNotEmpty()
        }

        override fun actionPerformed(event: AnActionEvent) {
            FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let(::selectLoadedFile)
        }
    }

    private inner class ExpandAllAction : SidecarAction("Expand All", "Expand all loaded folders", AllIcons.Actions.Expandall) {
        override fun actionPerformed(event: AnActionEvent) {
            TreeUtil.expandAll(tree)
        }
    }

    private inner class CollapseAllAction : SidecarAction("Collapse All", "Collapse all folders", AllIcons.Actions.Collapseall) {
        override fun actionPerformed(event: AnActionEvent) {
            TreeUtil.collapseAll(tree, 0)
        }
    }

    private inner class OptionsAction : SidecarAction("Options", "文件工具窗口选项", AllIcons.Actions.More) {
        override fun actionPerformed(event: AnActionEvent) {
            val group = DefaultActionGroup(
                ToggleReadOnlyModeAction(),
                ToggleShowHiddenFilesAction()
            )
            val popup = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.POPUP, group)
            val component = event.inputEvent?.component ?: tree
            popup.component.show(component, 0, component.height)
        }
    }

    private inner class HideToolWindowAction : SidecarAction("Hide", "隐藏文件工具窗口", AllIcons.General.HideToolWindow) {
        override fun actionPerformed(event: AnActionEvent) {
            toolWindow.hide(null)
        }
    }

    private inner class ToggleReadOnlyModeAction : ToggleAction(
        "Read Only Mode",
        "Use a safe Sidecar-only context menu instead of the full IntelliJ project context menu",
        AllIcons.General.ReaderMode
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(event: AnActionEvent): Boolean = service.isReadOnlyMode()

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            service.setReadOnlyMode(state)
        }
    }

    private inner class ToggleShowHiddenFilesAction : ToggleAction(
        "Show Hidden Files",
        "在文件工具窗口中显示点文件和隐藏文件",
        AllIcons.Actions.Show
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(event: AnActionEvent): Boolean = service.isShowHiddenFiles()

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            service.setShowHiddenFiles(state)
        }
    }
}

private class SidecarTreeCellRenderer(private val project: Project) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: javax.swing.JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val item = (value as? SidecarTreeNode)?.item ?: return
        icon = iconFor(item)
        append(
            item.label,
            if (item is ExternalRootItem && item.virtualFile == null) {
                SimpleTextAttributes.ERROR_ATTRIBUTES
            } else {
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
        )
        toolTipText = item.path
    }

    private fun iconFor(item: SidecarNodeItem): Icon? = when (item) {
        is ExternalRootItem -> if (item.virtualFile == null) {
            AllIcons.General.Warning
        } else {
            AllIcons.Nodes.Folder
        }
        is VirtualFileItem -> IconUtil.getIcon(item.file, 0, project)
        is LoadingItem -> AllIcons.Actions.Refresh
        is SidecarRootItem -> null
    }
}

private val DEFAULT_TOOL_WINDOW_ICON: Icon = IconLoader.getIcon(
    "/icons/sidecarExplorer.svg",
    SidecarToolWindowFactory::class.java
)

private val SELECTED_TOOL_WINDOW_ICON: Icon = IconLoader.getIcon(
    "/icons/sidecarExplorerSelected.svg",
    SidecarToolWindowFactory::class.java
)
