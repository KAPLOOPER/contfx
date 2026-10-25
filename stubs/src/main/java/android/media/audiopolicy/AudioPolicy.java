package android.media.audiopolicy;

import android.media.AudioFocusInfo;

/** Stand-in for the framework's system API class of the same name; see stubs/build.gradle.kts. */
public class AudioPolicy {
    public static abstract class AudioPolicyFocusListener {
        public void onAudioFocusGrant(AudioFocusInfo afi, int requestResult) {}
        public void onAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {}
        public void onAudioFocusRequest(AudioFocusInfo afi, int requestResult) {}
        public void onAudioFocusAbandon(AudioFocusInfo afi) {}
    }
}
