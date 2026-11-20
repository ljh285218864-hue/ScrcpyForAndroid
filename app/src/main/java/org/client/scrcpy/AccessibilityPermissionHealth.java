package org.client.scrcpy;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * Read-only accessibility permission health. Never writes secure settings,
 * requests elevated privileges or attempts to re-enable a disabled service.
 */
public final class AccessibilityPermissionHealth {
    public static final String SYSTEM_OFF = "system_off";
    public static final String ENABLED_DISCONNECTED = "enabled_not_connected";
    public static final String CONNECTED = "connected";

    private AccessibilityPermissionHealth() { }

    public static boolean systemEnabled(Context context) {
        AccessibilityManager manager = (AccessibilityManager)
                context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null || !manager.isEnabled()) return false;
        List<AccessibilityServiceInfo> enabled =
                manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (enabled == null) return false;
        ComponentName ours = new ComponentName(
                context.getPackageName(), AccessibilityShotService.class.getName());
        for (AccessibilityServiceInfo info : enabled) {
            if (info == null) continue;
            ComponentName id = ComponentName.unflattenFromString(info.getId());
            if (ours.equals(id)) return true;
            ResolveInfo resolve = info.getResolveInfo();
            ServiceInfo service = resolve == null ? null : resolve.serviceInfo;
            if (service == null) continue;
            String qualified = service.name.startsWith(".")
                    ? service.packageName + service.name
                    : service.name;
            if (context.getPackageName().equals(service.packageName)
                    && AccessibilityShotService.class.getName().equals(qualified))
                return true;
        }
        return false;
    }

    public static String state(Context context) {
        if (!systemEnabled(context)) return SYSTEM_OFF;
        return AccessibilityShotService.ready() ? CONNECTED : ENABLED_DISCONNECTED;
    }
}
