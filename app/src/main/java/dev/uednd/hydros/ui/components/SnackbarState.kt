package dev.uednd.hydros.ui.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SnackbarAction(val label: String, val onClick: () -> Unit)
internal enum class SnackbarEnd { TIMEOUT, DISMISSED, REPLACED }
internal data class SnackbarTicket(val id: Long, val completion: CompletableDeferred<SnackbarEnd> = CompletableDeferred())
internal data class SnackbarNotice(
    val id: Long,
    val text: String,
    val action: SnackbarAction? = null,
    val error: Boolean = false,
    val canDismissOutside: Boolean = false,
    val protected: Boolean = false, val ticket: SnackbarTicket = SnackbarTicket(id),
)

/** Single active notification state. IDs keep old timers/actions from dismissing a replacement. */
internal class HydrosSnackbarState {
    private var sequence = 0L
    private val mutable = MutableStateFlow<SnackbarNotice?>(null)
    val notice = mutable.asStateFlow()
    private val owners = MutableStateFlow<List<Any>>(emptyList())
    val modalOwners = owners.asStateFlow()
    fun show(text: String, action: SnackbarAction? = null, error: Boolean = false, protected: Boolean = false): SnackbarTicket? {
        if (text.isBlank() || (mutable.value?.protected == true && !protected)) return null
        mutable.value?.let { dismiss(it.id, SnackbarEnd.REPLACED) }
        val notice = SnackbarNotice(++sequence, text, action, error, protected = protected)
        mutable.value = notice
        return notice.ticket
    }
    suspend fun awaitDismissal(ticket: SnackbarTicket): SnackbarEnd = ticket.completion.await()
    fun unlock(id: Long) {
        mutable.value?.takeIf { it.id == id }?.let { mutable.value = it.copy(canDismissOutside = true) }
    }
    fun dismiss(id: Long, reason: SnackbarEnd = SnackbarEnd.DISMISSED) {
        val current = mutable.value?.takeIf { it.id == id } ?: return
        mutable.value = null
        current.ticket.completion.complete(reason)
    }
    fun dismissOutside() { mutable.value?.takeIf { it.canDismissOutside }?.let { dismiss(it.id) } }
    fun performAction(id: Long) {
        val current = mutable.value?.takeIf { it.id == id } ?: return
        dismiss(id)
        current.action?.onClick?.invoke()
    }
    fun registerModal(owner: Any) { owners.value = owners.value + owner }
    fun unregisterModal(owner: Any) { owners.value = owners.value.filterNot { it === owner } }
}
