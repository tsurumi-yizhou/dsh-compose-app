package dev.fughetta.dshcompose.domain.hosts

import dev.fughetta.dshcompose.domain.attachments.AttachmentsRepository
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.messages.HostRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** Identity and read-only state of one selected host; the data owner controls its lifetime. */
class ConnectedHost(
    val hostId: String,
    val scope: CoroutineScope,
    val state: StateFlow<ConnectionStatus>,
    val requests: StateFlow<HostRequests>,
    val hostEvents: SharedFlow<ToolPayload.Obj>,
    val messageAttachments: AttachmentsRepository,
    private val bindings: StateFlow<HostRepositories?>,
) {
    val repositories: HostRepositories?
        get() = bindings.value
}

class HostSelection(
    val state: StateFlow<ConnectionStatus>,
    private val selected: StateFlow<ConnectedHost?>,
) {
    val session: ConnectedHost?
        get() = selected.value
}
