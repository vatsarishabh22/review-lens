package io.github.vatsarishabh22.reviewlens

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * One PR's review state, kept across sessions in `.git/review-lens/<key>.json` (outside the working tree).
 * [reviewed] maps a path to the blob it had when marked reviewed, so a later change to that file un-reviews it.
 */
data class ReviewMeta(
    val target: ReviewTarget,
    val range: ScopeRange,
    val scopeLabel: String,
    val reviewed: Map<String, String> = emptyMap(),
) {
    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        private fun fileFor(git: GitClient, key: String): Path = git.gitDir().resolve("review-lens/$key.json")

        fun load(git: GitClient, key: String): ReviewMeta? =
            fileFor(git, key).takeIf(Files::exists)?.let { gson.fromJson(Files.readString(it), ReviewMeta::class.java) }

        fun save(git: GitClient, meta: ReviewMeta) {
            val file = fileFor(git, meta.target.key)
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(meta))
        }
    }
}

enum class Badge(val symbol: String) { TODO("⭐"), REVIEWED("✅"), CHANGED_SINCE("🔄") }
