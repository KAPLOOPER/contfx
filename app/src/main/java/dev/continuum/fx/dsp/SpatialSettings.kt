package dev.continuum.fx.dsp

/**
 * Spatial audio for headphones: the music played from virtual speakers placed around you in a
 * room you choose, the way it would reach your ears from real ones. Capture mode only, since it
 * mixes the ears and Direct mode's effect can't.
 *
 * Angles here are as you'd say them: degrees clockwise from straight ahead, so 30 is ahead and
 * to the right and -110 is behind on the left. Heights are degrees up from your ears' level.
 */
enum class SpatialLayout(val label: String) {
    /** Two speakers: the music as mixed, just out in front of you. */
    Stereo("Stereo"),
    /** The stereo mix opened up: its centre to a centre speaker, its space to surrounds. */
    Surround5("5.1"),
    Surround7("7.1"),
}

enum class SpeakerRole(val short: String, val label: String, val left: Boolean?) {
    FrontLeft("L", "Front left", true),
    FrontRight("R", "Front right", false),
    Centre("C", "Centre", null),
    SideLeft("Ls", "Side left", true),
    SideRight("Rs", "Side right", false),
    BackLeft("Lb", "Back left", true),
    BackRight("Rb", "Back right", false);

    val surround: Boolean get() = this == SideLeft || this == SideRight || this == BackLeft || this == BackRight
}

data class Speaker(
    val role: SpeakerRole,
    /** Degrees clockwise from straight ahead (-180 to 180). */
    val angle: Float,
    /** Degrees up from ear level (-30 to 60). */
    val height: Float = 0f,
    /** Metres from your head. */
    val distance: Float = 2f,
    val levelDb: Float = 0f,
)

/**
 * The room the speakers stand in. Reflections off its walls are what put sound outside your
 * head; how much of them, and of the room's tail, is up to you. [depth] runs front to back.
 */
enum class RoomKind(
    val label: String,
    val depth: Float,
    val width: Float,
    val height: Float,
    /** How much each wall soaks up (0 to 1). */
    val absorption: Float,
    /** How many bounces are worked out exactly; the tail covers the rest. */
    val order: Int,
    /** How long after the direct sound reflections are worked out for. */
    val earlyMs: Float,
    /** Each bounce dulls the sound above about this. */
    val dullHz: Float,
) {
    /** Just the speakers: no walls at all. */
    None("No room", 0f, 0f, 0f, 1f, 0, 0f, 0f),
    /** A treated mixing room: close, dry walls. */
    Studio("Studio", 5.5f, 4.2f, 2.8f, 0.55f, 2, 45f, 7000f),
    Living("Living room", 6.5f, 5f, 2.6f, 0.35f, 2, 60f, 8000f),
    Cinema("Cinema", 16f, 11f, 6f, 0.45f, 2, 110f, 5000f),
}

enum class HeadModel(val asset: String, val label: String) {
    Ku100("heads/ku100.hrir", "Neumann KU100"),
    Kemar("heads/kemar.hrir", "KEMAR"),
}

/** Scales the time between your ears: larger heads hear sideways sounds as further round. */
enum class HeadSize(val label: String, val scale: Float) {
    Small("Small", 0.92f),
    Medium("Medium", 1f),
    Large("Large", 1.08f),
}

data class SpatialSetup(
    val id: String,
    val name: String,
    val layout: SpatialLayout = SpatialLayout.Stereo,
    val speakers: List<Speaker> = defaultSpeakers(layout),
    val room: RoomKind = RoomKind.Studio,
    /** How strong the walls' reflections are, 0 to 1. */
    val reflections: Float = 0.5f,
    /** The room's tail, 0 (none) to 1. */
    val tail: Float = 0.15f,
    /** How long the tail takes to die away, seconds. */
    val decay: Float = 0.3f,
    /** How far the stereo mix is spread across the front speakers, 0.5 to 1.5. */
    val width: Float = 1f,
    /**
     * How much of the mix's space (its reverb and ambience) goes straight to the ears, as wide as
     * plain headphones, rather than through the speakers, 0 to 1.
     */
    val space: Float = 0.5f,
    val centreDb: Float = 0f,
    val surroundDb: Float = 0f,
    /** Below this, sound skips the room and the head and stays exactly as it was; 0 for never. */
    val bassHz: Int = 120,
    val head: HeadModel = HeadModel.Ku100,
    val headSize: HeadSize = HeadSize.Medium,
) {
    /** The tail's level, or null for none. */
    val tailDb: Float? get() = if (tail <= 0.01f) null else -32f + 24f * tail

    fun withLayout(next: SpatialLayout): SpatialSetup =
        if (next == layout) this else copy(layout = next, speakers = defaultSpeakers(next, speakers.firstOrNull()?.distance ?: 2f))

    fun withSpeaker(s: Speaker): SpatialSetup = copy(speakers = speakers.map { if (it.role == s.role) s else it })

    companion object {
        const val MIN_DISTANCE = 0.6f
        const val MAX_DISTANCE = 6f
        const val MIN_HEIGHT = -30f
        const val MAX_HEIGHT = 60f

        fun defaultSpeakers(layout: SpatialLayout, distance: Float = 2f): List<Speaker> = when (layout) {
            SpatialLayout.Stereo -> listOf(
                Speaker(SpeakerRole.FrontLeft, -30f, distance = distance),
                Speaker(SpeakerRole.FrontRight, 30f, distance = distance),
            )
            SpatialLayout.Surround5 -> listOf(
                Speaker(SpeakerRole.FrontLeft, -30f, distance = distance),
                Speaker(SpeakerRole.FrontRight, 30f, distance = distance),
                Speaker(SpeakerRole.Centre, 0f, distance = distance),
                Speaker(SpeakerRole.SideLeft, -110f, distance = distance),
                Speaker(SpeakerRole.SideRight, 110f, distance = distance),
            )
            SpatialLayout.Surround7 -> listOf(
                Speaker(SpeakerRole.FrontLeft, -30f, distance = distance),
                Speaker(SpeakerRole.FrontRight, 30f, distance = distance),
                Speaker(SpeakerRole.Centre, 0f, distance = distance),
                Speaker(SpeakerRole.SideLeft, -90f, distance = distance),
                Speaker(SpeakerRole.SideRight, 90f, distance = distance),
                Speaker(SpeakerRole.BackLeft, -145f, distance = distance),
                Speaker(SpeakerRole.BackRight, 145f, distance = distance),
            )
        }

        /** A pair of monitors in a treated room: the mix as made, out in front, barely any room. */
        val STUDIO = SpatialSetup(id = "studio", name = "Studio pair", reflections = 0.45f, tail = 0.12f, decay = 0.25f)

        /** For films: the centre for voices, the space around you, a slightly larger room. */
        val CINEMA = SpatialSetup(
            id = "cinema", name = "Cinema", layout = SpatialLayout.Surround7,
            speakers = defaultSpeakers(SpatialLayout.Surround7, 2.5f),
            room = RoomKind.Living, reflections = 0.35f, tail = 0.15f, decay = 0.35f, space = 0.35f,
        )
    }
}

/**
 * Every setup, which one is used by default, and which apps have their own (or have spatial
 * audio off). [enabled] is the switch for all of it.
 */
data class SpatialLibrary(
    val enabled: Boolean = false,
    val setups: List<SpatialSetup> = listOf(SpatialSetup.STUDIO, SpatialSetup.CINEMA),
    val defaultId: String = SpatialSetup.STUDIO.id,
    /** Package -> setup id, or [OFF]. */
    val apps: Map<String, String> = emptyMap(),
) {
    val default: SpatialSetup get() = setups.firstOrNull { it.id == defaultId } ?: setups.first()

    fun byId(id: String?): SpatialSetup? = setups.firstOrNull { it.id == id }

    /**
     * The setup for what's [playing]: an app's own when everything playing agrees on one (or on
     * none), otherwise the default. Null when spatial audio is off for it.
     */
    fun setupFor(playing: List<String>): SpatialSetup? {
        if (!enabled) return null
        val own = playing.map { apps[it] }.distinct()
        val id = if (own.size == 1 && own[0] != null) own[0] else defaultId
        if (id == OFF) return null
        return byId(id) ?: default
    }

    companion object {
        const val OFF = "off"
    }
}
