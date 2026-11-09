package dev.pohunek.companion.protocol

/** How a daemon's protocol version relates to the range this client speaks ([Wire.CLIENT_RANGE]). */
sealed interface VersionGap {
    /** The daemon speaks protocol [daemonProtocol], below the client's range: it runs an older Pohunek. */
    data class Older(val daemonProtocol: Int) : VersionGap

    /** The daemon speaks protocol [daemonProtocol], above the client's range: it runs a newer Pohunek. */
    data class Newer(val daemonProtocol: Int) : VersionGap

    /** The refusal or record names no usable version, so the side that is behind is not known. */
    data object Unknown : VersionGap
}

/** Classifies [daemonProtocol], the version a refusal or a discovery record names, against the client's range. */
fun versionGap(daemonProtocol: Int?): VersionGap = when {
    daemonProtocol == null -> VersionGap.Unknown
    daemonProtocol < Wire.CLIENT_RANGE.minimum -> VersionGap.Older(daemonProtocol)
    daemonProtocol > Wire.CLIENT_RANGE.maximum -> VersionGap.Newer(daemonProtocol)
    else -> VersionGap.Unknown
}

/** The gap a version refusal reports: the `v` of the daemon's refusal, or the version its response selected. */
val ProtocolClientException.VersionMismatch.gap: VersionGap get() = versionGap(daemonVersion)

/** The gap of a peer that `host.discover` classified as `version_mismatch`. */
val HostClass.VersionMismatch.gap: VersionGap get() = versionGap(daemonProtocolVersion)
