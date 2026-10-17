package io.github.vatsarishabh22.reviewlens

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator

/** ⭐/✅/🔄 beside changed files in the Project tree, and "⭐ n" beside folders still holding n unreviewed files. */
class TreeBadges : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val service = ReviewService.of(node.project ?: return).takeIf { it.isActive } ?: return
        val file = node.virtualFile ?: return
        val badge = if (file.isDirectory) {
            val dir = service.relativePath(file) ?: if (file.path == node.project.basePath) "" else return
            service.pendingUnder(dir).takeIf { it > 0 }?.let { "⭐ $it" }
        } else {
            service.changeFor(file)?.let { "${service.badge(it).symbol} ${it.status}" }
        }
        badge?.let { data.locationString = it }
    }
}
