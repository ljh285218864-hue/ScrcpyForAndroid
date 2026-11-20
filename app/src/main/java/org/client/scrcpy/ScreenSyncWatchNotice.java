package org.client.scrcpy;

import android.app.NotificationManager;
import android.content.Context;

/** Legacy cleanup only. No notifications or floating prompts for incoming
 * viewing requests. The request is shown ONLY inside ScreenSync while open.
 * Source-side long-term consent may approve the specific paired request
 * without issuing another notice.
 */
public final class ScreenSyncWatchNotice {
    private static final int LEGACY_NOTICE_ID = 8416;
    private ScreenSyncWatchNotice() {}

    public static void clear(Context context) {
        NotificationManager manager = (NotificationManager)
                context.getApplicationContext().getSystemService(
                        Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(LEGACY_NOTICE_ID);
    }
}
