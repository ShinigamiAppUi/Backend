package streamix.admin

import streamix.api.Announcement

interface AnnouncementRepository {
    fun list(page: Int = 1, perPage: Int = 30): List<Announcement>
    fun find(id: String): Announcement?
    fun create(announcement: Announcement): Announcement
    fun update(announcement: Announcement): Announcement
    fun delete(id: String): Boolean
}
