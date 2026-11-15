package org.client.scrcpy;

/**
 * Explicit in-app camera scanner. A dedicated declared activity avoids
 * relying on other QR apps or OEM intent handlers on Xiaomi / vivo.
 * This is only for ScreenSync App pairing, not Android's own wireless-
 * debugging system QR scanner.
 */
public final class AppQrCaptureActivity
        extends com.journeyapps.barcodescanner.CaptureActivity {
}
