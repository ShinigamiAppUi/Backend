package streamix.admin

import streamix.api.AdminAuditEntry

interface AdminAuditRepository {
    fun append(entry: AdminAuditEntry)
    fun recent(limit: Int = 100): List<AdminAuditEntry>
    fun findByOperationId(operationId: String): List<AdminAuditEntry> =
        recent(500).filter { it.metadata["operationId"] == operationId }
}
