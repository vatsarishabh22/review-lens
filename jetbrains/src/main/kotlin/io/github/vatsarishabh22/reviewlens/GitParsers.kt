package io.github.vatsarishabh22.reviewlens

/** One file changed in the review scope. [newBlob] is the content at `<to>`; it is what "reviewed" records. */
data class FileChange(
    val path: String,
    val oldPath: String?,
    val status: Char,
    val newBlob: String,
    val added: Int?,
    val deleted: Int?,
) {
    val name get() = path.substringAfterLast('/')
    val dir get() = path.substringBeforeLast('/', "")
}

/** Joins `git diff --raw -z --no-abbrev -M` with `git diff --numstat -z -M` for the same range. */
fun parseChanges(raw: String, numstat: String): List<FileChange> {
    val counts = parseNumstat(numstat)
    val tokens = raw.split('\u0000').filter { it.isNotEmpty() }
    val changes = mutableListOf<FileChange>()
    var i = 0
    while (i < tokens.size) {
        val meta = tokens[i].split(' ') // :oldMode newMode oldBlob newBlob STATUS[score]
        val status = meta[4][0]
        val renamed = status == 'R' || status == 'C'
        val oldPath = if (renamed) tokens[i + 1] else null
        val path = tokens[i + if (renamed) 2 else 1]
        val count = counts[path]
        changes += FileChange(path, oldPath, status, meta[3], count?.first, count?.second)
        i += if (renamed) 3 else 2
    }
    return changes.sortedBy { it.path }
}

/** path -> (added, deleted); null counts mean a binary file. */
private fun parseNumstat(numstat: String): Map<String, Pair<Int?, Int?>> {
    val tokens = numstat.split('\u0000')
    val counts = mutableMapOf<String, Pair<Int?, Int?>>()
    var i = 0
    while (i < tokens.size) {
        val parts = tokens[i].split('\t')
        if (parts.size < 3) { i++; continue }
        // A rename leaves the path field empty and puts old and new paths in the next two tokens.
        val path = parts[2].ifEmpty { tokens[i + 2] }
        counts[path] = parts[0].toIntOrNull() to parts[1].toIntOrNull()
        i += if (parts[2].isEmpty()) 3 else 1
    }
    return counts
}

data class BlameCommit(val sha: String, val author: String, val time: Long, val summary: String)

/** [lines] maps a 1-based line number at `<to>` to the commit that last changed it. */
data class Blame(val lines: Map<Int, String>, val commits: Map<String, BlameCommit>)

private val BLAME_HEADER = Regex("^([0-9a-f]{40}) \\d+ (\\d+)(?: \\d+)?$")

/** Parses `git blame --porcelain`; commit details appear only on a commit's first line, so they are collected once. */
fun parseBlame(porcelain: String): Blame {
    val lines = mutableMapOf<Int, String>()
    val commits = mutableMapOf<String, BlameCommit>()
    var sha = ""
    val fields = mutableMapOf<String, String>()
    for (line in porcelain.lines()) {
        val header = BLAME_HEADER.find(line)
        when {
            header != null -> {
                sha = header.groupValues[1]
                lines[header.groupValues[2].toInt()] = sha
                fields.clear()
            }
            line.startsWith('\t') -> if (sha !in commits && "author" in fields) {
                commits[sha] = BlameCommit(sha, fields.getValue("author"), fields["author-time"]?.toLongOrNull() ?: 0, fields["summary"].orEmpty())
            }
            else -> line.split(' ', limit = 2).takeIf { it.size == 2 }?.let { fields[it[0]] = it[1] }
        }
    }
    return Blame(lines, commits)
}

/** One file's changed lines in a scope: [added] on the new side (range.to), [deleted] on the old side, 1-based. */
data class LineChanges(val oldPath: String?, val added: Set<Int>, val deleted: Set<Int>)

/**
 * Reads `git diff -U0 --no-prefix -M` into new path -> [LineChanges] (old path for a deleted file). Hunk headers are
 * `@@ -a[,b] +c[,d] @@`: b old lines from a were removed, d new lines from c were written; a count of 0 means none.
 */
fun parseChangedLines(diff: String): Map<String, LineChanges> {
    val result = LinkedHashMap<String, LineChanges>()
    var old: String? = null
    var new: String? = null
    val added = HashSet<Int>()
    val deleted = HashSet<Int>()
    fun flush() {
        (new ?: old)?.let { result[it] = LineChanges(old, added.toSet(), deleted.toSet()) }
        added.clear(); deleted.clear()
    }
    val hunk = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
    for (line in diff.lineSequence()) {
        when {
            line.startsWith("diff --git ") -> { flush(); old = null; new = null }
            line.startsWith("--- ") -> old = line.removePrefix("--- ").takeUnless { it == "/dev/null" }
            line.startsWith("+++ ") -> new = line.removePrefix("+++ ").takeUnless { it == "/dev/null" }
            else -> hunk.find(line)?.destructured?.let { (a, b, c, d) ->
                val from = a.toInt(); val count = b.ifEmpty { "1" }.toInt()
                val to = c.toInt(); val written = d.ifEmpty { "1" }.toInt()
                deleted += from until from + count
                added += to until to + written
            }
        }
    }
    flush()
    return result
}

