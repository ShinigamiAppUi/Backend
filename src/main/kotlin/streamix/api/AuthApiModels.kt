package streamix.api

data class AuthSessionRequest(
    val provider: String? = null,
    val credential: String? = null
)
