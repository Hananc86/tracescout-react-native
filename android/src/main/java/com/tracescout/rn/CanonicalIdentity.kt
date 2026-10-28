package com.tracescout.rn

import java.security.MessageDigest

/**
 * Evidence identity for an allowed node — contract the specification
 *
 * identity:= hash( routeId ‖ canonicalStructuralPath [ ‖ replayId ] )
 *
 * FORBIDDEN as inputs, all of them remount-sensitive or user data: React Native
 * view tags, Fiber IDs, native view instance IDs, object identity, pointers,
 * `identityHashCode`, ephemeral mount IDs, arbitrary React keys, and any
 * application content (text, symbols, account/transaction ids, URLs, form
 * values).
 *
 * A React `key` is excluded even though it looks like a stable handle: it is
 * routinely a database id, i.e. application data wearing a convenient shape.
 *
 * The path is canonical for the SERIALISED EVENT, not for the lifetime of a
 * native view instance. Android's snapshotter fell back to
 * `System.identityHashCode` for any view without an explicit id, so every
 * capture minted fresh identities: the cross-event union grew while the
 * per-event max stayed flat, and the server refused honest chunks with
 * `allowed_view_count_mismatch`. RN re-renders far more often than Android
 * recreates views, so the same mistake would fire harder here.
 */
object CanonicalIdentity {

    private const val SEP = "" // unit separator: cannot occur in a component type

    /**
 * The structural path from the route root: component type names and sibling
 * ordinals only. Nothing else may enter it.
 */
    fun structuralPath(segments: List<PathSegment>): String =
        segments.joinToString("/") { "${it.componentType}[${it.siblingOrdinal}]" }

    /**
 * `replayId`, when the developer supplied one, REPLACES the structural path
 * so a deliberate identity survives refactoring. It must be static — a
 * value derived from data would reintroduce exactly what the specification forbids.
 */
    fun identity(routeId: String, path: String, replayId: String? = null): String {
        val preImage = if (replayId != null) routeId + SEP + "replay:" + replayId
        else routeId + SEP + path
        return sha256Hex(preImage)
    }

    /** Hashed before the wire or storage — the pre-image never leaves the device. */
    private fun sha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    data class PathSegment(val componentType: String, val siblingOrdinal: Int)
}
