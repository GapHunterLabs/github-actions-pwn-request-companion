package dev.gaphunter.githubactionspwnrequestcompanion.detect

/**
 * Small indentation-based YAML micro-scanner, same "not a real YAML
 * parser" discipline as this catalog's other YAML-scanning plugins
 * (each repo keeps its own copy -- no shared library between plugins).
 * Adds [splitMapEntries] (walking every immediate child KEY of a
 * mapping block, e.g. every job name under `jobs:`) on top of the
 * nested-key-path lookup ([findBlockBody]) `k8s-label-selector-
 * exposure-companion`'s own copy already has -- workflow YAML needs to
 * enumerate an unknown, arbitrary set of job names, not just follow a
 * fixed key path.
 */
object WorkflowYamlBlocks {

    /** Body lines of the block reached by following [keyPath] one key at a time -- same algorithm as this catalog's other indentation scanners (first match per step, reference-indent-of-first-body-line as the "still inside" threshold, correctly handling a `- ` list item written at the SAME column as its own key). */
    fun findBlockBody(lines: List<String>, keyPath: List<String>): List<String>? {
        var scope = lines
        for (key in keyPath) {
            val keyRegex = Regex("""^(\s*)${Regex.escape(key)}:\s*$""")
            val lineIndex = scope.indexOfFirst { keyRegex.containsMatchIn(it) }
            if (lineIndex < 0) return null
            val bodyStart = lineIndex + 1

            val referenceIndent = scope.drop(bodyStart).firstNotNullOfOrNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) null else line.length - line.trimStart().length
            } ?: return emptyList()

            var bodyEnd = bodyStart
            while (bodyEnd < scope.size && !isBlockEnd(scope[bodyEnd], referenceIndent - 1)) bodyEnd++
            scope = scope.subList(bodyStart, bodyEnd)
        }
        return scope
    }

    data class MapEntry(val key: String, val inlineValue: String, val bodyLines: List<String>, val lineIndexInScope: Int)

    /** Splits a mapping block into its immediate child entries -- every key found at the SAME (lowest) indent in [lines], each paired with any same-line scalar value and/or its own nested body. */
    fun splitMapEntries(lines: List<String>): List<MapEntry> {
        val keyIndent = lines.firstNotNullOfOrNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) null else line.length - line.trimStart().length
        } ?: return emptyList()

        val keyLineRegex = Regex("""^(\s*)([\w.\-/"']+):\s*(.*)$""")
        val entries = mutableListOf<MapEntry>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) { i++; continue }
            val indent = line.length - line.trimStart().length
            if (indent != keyIndent) { i++; continue }

            val match = keyLineRegex.find(line)
            if (match == null) { i++; continue }

            val bodyStart = i + 1
            var bodyEnd = bodyStart
            while (bodyEnd < lines.size) {
                val bTrimmed = lines[bodyEnd].trim()
                if (bTrimmed.isEmpty() || bTrimmed.startsWith("#")) { bodyEnd++; continue }
                val bIndent = lines[bodyEnd].length - lines[bodyEnd].trimStart().length
                if (bIndent <= keyIndent) break
                bodyEnd++
            }
            entries += MapEntry(match.groupValues[2].trim('"', '\''), match.groupValues[3].trim(), lines.subList(bodyStart, bodyEnd), i)
            i = bodyEnd
        }
        return entries
    }

    /** Splits a YAML list block (`- uses: ...` / `  with: ...` per item) into one raw line-group per `-` entry at the list's own top indent -- same technique as `k8s-label-selector-exposure-companion`'s own copy. */
    fun splitListItems(lines: List<String>): List<List<String>> {
        val items = mutableListOf<MutableList<String>>()
        var dashIndent = -1
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = line.length - line.trimStart().length
            if (trimmed.startsWith("- ") || trimmed == "-") {
                if (dashIndent == -1) dashIndent = indent
                if (indent == dashIndent) {
                    val rest = line.trimStart().removePrefix("-").let { if (it.startsWith(" ")) it.drop(1) else it }
                    items += mutableListOf(" ".repeat(dashIndent + 2) + rest)
                    continue
                }
            }
            if (items.isNotEmpty()) items.last() += line
        }
        return items
    }

    private fun isBlockEnd(line: String, indent: Int): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return false
        val lineIndent = line.length - line.trimStart().length
        return lineIndent <= indent
    }
}
