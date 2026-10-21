package org.cqutosp.hydros.data.repository

import org.cqutosp.hydros.data.network.*
import org.cqutosp.hydros.data.ble.MacFormat
import org.cqutosp.hydros.ui.state.DeviceUi
import org.cqutosp.hydros.ui.state.SessionUi
import java.time.ZoneId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Check identifiers when supplied; absence never overrides a conflicting identifier. */
internal fun matchesSessionIdentity(auth: Credentials, device: DeviceUi, data: JsonObject): Boolean =
    listOf("deviceSnCode", "snCode").all { data.text(it)?.let { sn ->
        sn == device.id || MacFormat.normalize(sn)?.let { mac -> mac == MacFormat.normalize(device.id) } == true
    } != false } &&
        listOf("accountId", "orderAccountId").all { data.text(it)?.let { account -> account == auth.accountId } != false } &&
        data.text("projectId")?.let { it == auth.projectId } != false && data.flag("isOwner") != false

/** Both opening paths accept only the result associated with this opening attempt. */
internal fun confirmedOpening(auth: Credentials, session: SessionUi, response: ApiResponse<JsonElement>): JsonObject? {
    val data = response.data as? JsonObject ?: return null
    val order = data.text("orderNo")?.takeIf(String::isNotBlank) ?: return null
    if (!response.ok || data.number("state") != 1 || data.number("result") != 0 ||
        !matchesSessionIdentity(auth, session.device, data) ||
        session.orderNo.isNotBlank() && session.orderNo != order) return null
    val date = data.text("consumeDate") ?: return null
    if (session.consumeDate.isNotBlank()) return data.takeIf { date == session.consumeDate }
    val at = runCatching { parseDate(date).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() }.getOrNull()
        ?: return null
    // A later use must not become eligible merely because recovery was delayed.
    return data.takeIf { at in (session.startedAt - 120_000)..(session.startedAt + 120_000) }
}
