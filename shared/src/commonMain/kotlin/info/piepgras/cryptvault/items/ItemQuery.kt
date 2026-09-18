package info.piepgras.cryptvault.items

enum class SortKey { NAME, DATE, SIZE, KIND }

/** Search and sort over items, in memory (BUILD_BRIEF.md §2.2). */
object ItemQuery {

    /**
     * Case-insensitive match of every whitespace-separated term against title, file name, tags
     * and note. A term starting with `#` matches tags only.
     */
    fun matches(item: Item, query: String): Boolean {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        return terms.all { term ->
            if (term.startsWith("#") && term.length > 1) {
                val tag = term.drop(1)
                item.tags.any { it.contains(tag, ignoreCase = true) }
            } else {
                item.title.contains(term, ignoreCase = true) ||
                    item.name.contains(term, ignoreCase = true) ||
                    item.note.contains(term, ignoreCase = true) ||
                    item.tags.any { it.contains(term, ignoreCase = true) }
            }
        }
    }

    fun filter(items: List<Item>, query: String): List<Item> = items.filter { matches(it, query) }

    fun sort(items: List<Item>, key: SortKey, ascending: Boolean = true): List<Item> {
        val cmp: Comparator<Item> = when (key) {
            SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
            SortKey.DATE -> compareBy<Item> { it.modifiedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
            SortKey.SIZE -> compareBy<Item> { it.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
            SortKey.KIND -> compareBy<Item> { kindRank(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
        }
        return items.sortedWith(if (ascending) cmp else cmp.reversed())
    }

    /** Groups items by the coarse type shown in the browser. */
    fun kindRank(item: Item): Int = when {
        item.kind == ItemKind.NOTE -> 0
        item.mime.startsWith("image/") -> 1
        item.mime.startsWith("video/") -> 2
        item.mime.startsWith("audio/") -> 3
        item.mime == "application/pdf" || item.mime.startsWith("text/") -> 4
        else -> 5
    }

    /** All tags in use, most frequent first. */
    fun tags(items: List<Item>): List<String> =
        items.flatMap { it.tags }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
}
