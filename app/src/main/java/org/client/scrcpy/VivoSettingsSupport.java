package org.client.scrcpy;

import java.util.Locale;

/**
 * Device-family detection for public Android Settings navigation.
 * No vendor-private activities, permission bypasses, or background-start hacks.
 */
public final class VivoSettingsSupport {
    private VivoSettingsSupport() { }

    public static boolean isVivoFamily(String manufacturer, String brand) {
        return matches(manufacturer) || matches(brand);
    }

    private static boolean matches(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("vivo") || normalized.equals("iqoo")
                || normalized.startsWith("vivo ")
                || normalized.startsWith("iqoo ");
    }
}
