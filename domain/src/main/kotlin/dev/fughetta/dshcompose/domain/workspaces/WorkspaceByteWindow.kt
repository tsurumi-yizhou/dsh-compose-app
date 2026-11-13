package dev.fughetta.dshcompose.domain.workspaces

class WorkspaceByteWindow(
    val data: ByteArray,
    val offset: Long,
    val eof: Boolean,
    val version: String,
) {
    override fun toString() = "WorkspaceByteWindow(offset=$offset, bytes=${data.size}, eof=$eof)"
}
