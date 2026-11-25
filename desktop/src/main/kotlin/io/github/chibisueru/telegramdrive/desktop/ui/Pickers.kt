package io.github.chibisueru.telegramdrive.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/** Native file dialogs (GTK on Linux, the Explorer dialog on Windows). */
object Pickers {
    var owner: Frame? = null

    init {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }

    fun pickFiles(title: String = "Upload files"): List<File> {
        val dialog = FileDialog(owner, title, FileDialog.LOAD).apply { isMultipleMode = true }
        dialog.isVisible = true
        return dialog.files?.toList().orEmpty()
    }

    /** One or more folders (Ctrl/Shift-click to pick several). */
    fun pickFolders(title: String): List<File> {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = true
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return emptyList()
        return chooser.selectedFiles.toList().ifEmpty { listOfNotNull(chooser.selectedFile) }
    }

    fun pickFolder(title: String, start: File? = null): File? {
        val chooser = JFileChooser(start).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}
