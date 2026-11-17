package org.client.scrcpy;

/** Pure recovery policy. Never turns on ADB, captures a screen or grants VPN consent. */
public final class RecoveryDecision {
    private RecoveryDecision() { }

    public enum Result {
        DISABLED,
        NEEDS_PAIRING,
        WAITING_NETWORK,
        NEEDS_VPN_CONSENT,
        ALREADY_CONNECTED,
        START_IN_PROGRESS,
        CONNECT_AUTHORIZED_VPN
    }

    public static Result evaluate(boolean enabled, boolean paired, boolean configSaved,
                                  boolean underlayAvailable, boolean vpnConsent,
                                  boolean active, boolean starting) {
        if (!enabled) return Result.DISABLED;
        if (!paired || !configSaved) return Result.NEEDS_PAIRING;
        if (!underlayAvailable) return Result.WAITING_NETWORK;
        if (!vpnConsent) return Result.NEEDS_VPN_CONSENT;
        if (active) return Result.ALREADY_CONNECTED;
        if (starting) return Result.START_IN_PROGRESS;
        return Result.CONNECT_AUTHORIZED_VPN;
    }
}
