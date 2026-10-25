// The privileged half of the volume panel, run by Shizuku as the shell user.
// Interface after Volume++ by noel-digital-fan (MIT); see THIRD_PARTY_NOTICES.md.
package dev.continuum.fx.volume;

interface IVolumeHelper {
    // Reserved by Shizuku for tearing the service down.
    void destroy() = 16777114;

    // Players making sound now, one per line as "piid|uid|package".
    List<String> players() = 1;

    // Sets one player's own volume, 0..1. True when it took.
    boolean setPlayerVolume(int piid, float volume) = 2;

    // Whether any process of the package is alive (paused and in the background still counts).
    boolean isPackageRunning(String packageName) = 3;

    // Ringer mode through Android's internal path ("NORMAL", "VIBRATE", "SILENT"), which leaves
    // Do Not Disturb alone. Returns the command's output.
    String setRingerMode(String mode) = 4;

    // Sets the TAKE_AUDIO_FOCUS app-op (ignore = true refuses the app focus). No longer used to
    // play alongside, since most players won't play when refused; kept to undo what earlier
    // builds set.
    String setPlaysAlongside(String packageName, boolean ignore) = 5;

    // Every package whose TAKE_AUDIO_FOCUS is set to ignore.
    List<String> playsAlongside() = 6;

    // The apps that play alongside others. With any, the helper looks after audio focus for the
    // phone (an audio focus policy); with none, Android does. "" when done, else why not.
    String setAlongside(in List<String> packages) = 8;

    // Loudness boost on the whole output in millibels; 0 releases it. "" on success, else an error.
    String setLoudnessGain(int millibels) = 7;
}
