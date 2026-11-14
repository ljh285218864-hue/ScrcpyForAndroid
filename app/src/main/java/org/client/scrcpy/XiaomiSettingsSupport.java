package org.client.scrcpy;

import java.util.Locale;

/** Xiaomi-family UI routing for official Android Settings intents. */
public final class XiaomiSettingsSupport {
    private XiaomiSettingsSupport() { }

    public static boolean isXiaomiFamily(String manufacturer, String brand) {
        return hasXiaomiFamilyName(manufacturer) || hasXiaomiFamilyName(brand);
    }

    private static boolean hasXiaomiFamilyName(String name) {
        if (name == null) return false;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("xiaomi") || normalized.equals("redmi")
                || normalized.equals("poco")
                || normalized.startsWith("xiaomi ")
                || normalized.startsWith("redmi ")
                || normalized.startsWith("poco ");
    }
}
