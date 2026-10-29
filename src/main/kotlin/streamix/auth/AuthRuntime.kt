package streamix.auth

import com.google.gson.Gson
import streamix.chat.ChatService
import streamix.chat.FileChatRepository
import streamix.chat.ChatRepository
import streamix.library.FileUserLibraryRepository
import streamix.library.UserLibraryRepository
import streamix.library.UserLibraryService
import streamix.notification.FileNotificationRepository
import streamix.notification.NotificationRepository
import streamix.notification.NotificationService
import streamix.notification.NotificationEventPublisher
import streamix.admin.AdminAccessService
import streamix.admin.AdminRoleRepository
import streamix.admin.FileAdminRoleRepository
import streamix.admin.AdminAuditRepository
import streamix.admin.FileAdminAuditRepository
import streamix.admin.ModerationRepository
import streamix.admin.FileModerationRepository
import streamix.admin.ReportRepository
import streamix.admin.FileReportRepository
import streamix.admin.AdminService
import streamix.admin.AnnouncementRepository
import streamix.admin.FileAnnouncementRepository
import streamix.admin.AnnouncementService
import streamix.social.FileSocialRepository
import streamix.social.SocialRepository
import streamix.social.SocialService
import streamix.giveaway.FileGiveawayService
import java.nio.file.Path

/**
 * Composition root for backend-owned authentication and user state.
 *
 * The default file store is intentionally replaceable. A production deployment
 * can provide another UserRepository/SessionRepository without changing the
 * Android API contracts or auth service.
 */
class AuthRuntime(
    dataRoot: Path,
    gson: Gson = Gson(),
    sessionTtlSeconds: Long = 60L * 60L * 24L * 30L,
    adminAuditRepository: AdminAuditRepository? = null
) {
    val users: UserRepository = FileUserRepository(
        file = dataRoot.resolve("users.json"),
        gson = gson
    )

    val sessions: SessionRepository = FileSessionRepository(
        file = dataRoot.resolve("sessions.json"),
        gson = gson
    )

    val auth: AuthService = AuthService(
        users = users,
        sessions = sessions,
        sessionTtlSeconds = sessionTtlSeconds
    )

    val giveawayService: FileGiveawayService = FileGiveawayService(dataRoot, gson)
    val userService: UserService = UserService(users) { userId -> giveawayService.premiumStatus(userId) }
    val library: UserLibraryRepository = FileUserLibraryRepository(dataRoot.resolve("user-library.json"), gson)
    val libraryService: UserLibraryService = UserLibraryService(library)
    val social: SocialRepository = FileSocialRepository(dataRoot.resolve("social"), users, gson)
    val socialService: SocialService = SocialService(social)
    val chat: ChatRepository = FileChatRepository(dataRoot.resolve("chat"), users, gson)
    val notification: NotificationRepository = FileNotificationRepository(
        dataRoot.resolve("notifications"),
        users,
        gson
    )
    val notificationService: NotificationService = NotificationService(notification)
    val notificationEvents: NotificationEventPublisher = NotificationEventPublisher(notificationService)
    val adminRoles: AdminRoleRepository = FileAdminRoleRepository(
        dataRoot.resolve("admin"),
        gson
    )
    val adminAccess: AdminAccessService = AdminAccessService(
        roles = adminRoles
    )
    val moderation: ModerationRepository = FileModerationRepository(dataRoot.resolve("admin"), gson)
    val adminAudit: AdminAuditRepository = adminAuditRepository ?: FileAdminAuditRepository(dataRoot.resolve("admin"), gson)
    val reports: ReportRepository = FileReportRepository(dataRoot.resolve("admin"), gson)
    val adminService: AdminService = AdminService(
        users = users,
        access = adminAccess,
        roles = adminRoles,
        moderation = moderation,
        reports = reports,
        audit = adminAudit
    )
    val announcements: AnnouncementRepository = FileAnnouncementRepository(dataRoot.resolve("admin"), gson)
    val announcementService: AnnouncementService = AnnouncementService(
        users = users,
        access = adminAccess,
        announcements = announcements,
        notifications = notificationEvents,
        audit = adminAudit
    )
    val chatService: ChatService = ChatService(chat, notificationService)
}
