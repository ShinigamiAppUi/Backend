package streamix.admin

import streamix.api.AdminReport
import streamix.api.ReportStatus

interface ReportRepository {
    fun list(page: Int = 1, perPage: Int = 30): List<AdminReport>
    fun find(id: String): AdminReport?
    fun create(report: AdminReport): AdminReport
    fun update(report: AdminReport): AdminReport
    fun count(status: ReportStatus? = null): Long
    fun countForUser(userId: String): Long
}
