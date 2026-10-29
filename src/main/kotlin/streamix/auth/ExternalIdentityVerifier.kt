package streamix.auth

/**
 * Converts a provider-issued credential into a verified backend identity.
 *
 * Implementations must verify the credential cryptographically with the
 * provider's official verification mechanism. The HTTP layer must never trust
 * a client-supplied user id or subject as proof of identity.
 */
fun interface ExternalIdentityVerifier {
    fun verify(provider: String, credential: String): ExternalIdentity?
}
