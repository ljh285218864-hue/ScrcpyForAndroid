package org.client.scrcpy;

import java.util.Locale;

/** Only the ScreenSync JSON API is a valid source of pairing credentials. */
public final class ApiResponsePolicy {
    private ApiResponsePolicy() { }

    public static boolean isJsonResponse(String contentType) {
        if (contentType == null) return false;
        String normalized = contentType.toLowerCase(Locale.US).trim();
        return normalized.equals("application/json")
                || normalized.startsWith("application/json;");
    }
}
