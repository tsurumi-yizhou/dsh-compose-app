package dev.fughetta.dshcompose.ui.composer

/** Built off the UI thread and published once; message lookup never scans the session list. */
class ReferenceLabelIndex private constructor(private val root: Node) {
    private class Node {
        val children = mutableMapOf<Char, Node>()
        var terminal = false
    }

    internal fun matchEnd(text: String, start: Int): Int? {
        var node = root
        var end: Int? = null
        var offset = start
        while (offset < text.length) {
            node = node.children[text[offset]] ?: break
            offset++
            if (
                node.terminal &&
                    (offset == text.length ||
                        text[offset].isWhitespace() ||
                        text[offset] in "，。；：！？、,.;:!?)）")
            )
                end = offset
        }
        return end
    }

    companion object {
        val Empty = ReferenceLabelIndex(Node())

        fun build(labels: Sequence<String>): ReferenceLabelIndex {
            val root = Node()
            for (label in labels) {
                if (label.isBlank() || '\n' in label || '\r' in label) continue
                var node = root
                for (char in label) node = node.children.getOrPut(char) { Node() }
                node.terminal = true
            }
            return if (root.children.isEmpty()) Empty else ReferenceLabelIndex(root)
        }
    }
}
