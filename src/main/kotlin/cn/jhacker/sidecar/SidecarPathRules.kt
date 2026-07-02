package cn.jhacker.sidecar

import com.intellij.openapi.vfs.VirtualFile

object SidecarPathRules {
    private val ignoredNames = setOf(".git", ".idea", "build", "node_modules", "target")

    fun normalizePath(path: String): String = path.trim().trimEnd('/')

    fun isIgnored(file: VirtualFile, showHiddenFiles: Boolean): Boolean {
        if (file.isDirectory && file.name in ignoredNames) {
            return true
        }

        return !showHiddenFiles && file.name.startsWith(".")
    }

    fun compareFiles(left: VirtualFile, right: VirtualFile): Int {
        if (left.isDirectory != right.isDirectory) {
            return if (left.isDirectory) -1 else 1
        }
        return left.name.compareTo(right.name, ignoreCase = true)
    }

    fun compareNames(leftName: String, leftIsDirectory: Boolean, rightName: String, rightIsDirectory: Boolean): Int {
        if (leftIsDirectory != rightIsDirectory) {
            return if (leftIsDirectory) -1 else 1
        }
        return leftName.compareTo(rightName, ignoreCase = true)
    }
}
