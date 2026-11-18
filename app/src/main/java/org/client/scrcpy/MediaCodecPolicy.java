package org.client.scrcpy;

/** Shared, unit-testable media codec policy for both setup directions. */
public final class MediaCodecPolicy {
    private MediaCodecPolicy() { }
    public static boolean isAllowed(String codec) {
        return "h264".equals(codec) || "h265".equals(codec)
                || "jpeg".equals(codec);
    }
}
