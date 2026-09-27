package com.mtechviral.musicfinderexample.core.model

/** Protocol used by the single active remote music source. */
enum class RemoteProtocol(val label: String) {
    SUBSONIC("Subsonic"),
    NAVIDROME("Navidrome"),
    WEBDAV("WebDAV"),
    EMBY("Emby"),
}

/** Persisted source identity. A change of endpoint does not change [id]. */
data class RemoteSource(
    val id: String,
    val protocol: RemoteProtocol,
    val displayName: String,
    val intranetUrl: String = "",
    val publicUrl: String = "",
    val username: String = "",
    val password: String = "",
    val rootPath: String = "",
    /** Selected Navidrome music folder or Emby music view; empty means all accessible music. */
    val libraryId: String = "",
    val serverIdentity: String? = null,
)
