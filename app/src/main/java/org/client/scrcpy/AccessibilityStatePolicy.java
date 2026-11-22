package org.client.scrcpy;

/**
 * App-level interpretation of Android's accessibility state.
 *
 * There is deliberately no action that enables/disables accessibility.
 * An OS-enabled service can temporarily be unbound without the user's
 * permission being revoked; showing these two cases distinctly avoids
 * mistakenly asking for a new permission grant.
 */
public final class AccessibilityStatePolicy {
    public enum State {
        RUNNING,
        ENABLED_NOT_CONNECTED,
        DISABLED,
        UNKNOWN
    }

    private AccessibilityStatePolicy() { }

    static State evaluate(Boolean systemEnabled, boolean serviceConnected) {
        if (serviceConnected) return State.RUNNING;
        if (systemEnabled == null) return State.UNKNOWN;
        return systemEnabled ? State.ENABLED_NOT_CONNECTED : State.DISABLED;
    }
}
