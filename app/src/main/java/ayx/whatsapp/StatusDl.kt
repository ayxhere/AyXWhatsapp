package ayx.whatsapp

/**
 * Status delete pipeline. Deletes a status from BOTH the real WhatsApp
 * account (via Baileys) and this app's local cache (StatusData), so a
 * deleted status does not reappear on the next refresh.
 */
object StatusDl {
    suspend fun deleteEverywhere(id: String): Pair<Boolean, String> {
        if (id.isBlank()) return false to "No status id"
        val (ok, err) = GatewayClient.deleteStatus(id)   // real WhatsApp (status@broadcast revoke)
        // SD2: the local copy is removed ONLY when the WhatsApp-side delete
        // actually went out. The old flow tombstoned locally even on failure
        // and still reported a success-flavoured message — the status vanished
        // here but stayed on WhatsApp, which is exactly what the user kept
        // re-reporting. On failure the item stays (retry stays possible) and
        // the gateway's own error is surfaced via the caller's notify().
        if (!ok) return false to (if (err.isNotBlank()) "Couldn't delete status: $err" else "Couldn't delete status — check the connection and retry")
        StatusData.remove(id)                       // this app's cache
        return true to "Status deleted"
    }
}
