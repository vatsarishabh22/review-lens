package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

private const val WIDGET_ID = "ReviewLens.Status"

class ReviewStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = WIDGET_ID
    override fun getDisplayName() = "Review Lens"
    override fun createWidget(project: Project): StatusBarWidget = ReviewStatusWidget(project)
}

/** "Reviewing #579 · 1 of 6 viewed" while a review is in progress, hidden otherwise; a click opens the Review panel. */
class ReviewStatusWidget(private val project: Project) : CustomStatusBarWidget {

    private val service = ReviewService.of(project)
    private val pill = Pill("", ReviewColors.review, ReviewColors.reviewFg).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Open the Review panel"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
            }
        })
    }

    override fun ID() = WIDGET_ID
    override fun getComponent() = pill

    override fun install(statusBar: StatusBar) {
        service.onChanged(this, ::refresh)
        refresh()
    }

    private fun refresh() {
        val meta = service.meta
        pill.isVisible = meta != null
        if (meta != null) pill.text = "Reviewing ${meta.target.ref ?: meta.target.label} · ${service.reviewedCount()} of ${service.changes.size} viewed"
    }

    override fun dispose() = Unit
}
