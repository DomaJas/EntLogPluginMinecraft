package org.domaja.entitylogprotect;

public enum TeleportMode {
    BOTH,
    ALWAYS_TELEPORT,
    TELEPORT_CONFIRM;

    public static TeleportMode fromConfig(String value) {
        if (value == null) return ALWAYS_TELEPORT;
        switch (value.trim().toLowerCase()) {
            case "both":
                return BOTH;
            case "teleportconfirm":
                return TELEPORT_CONFIRM;
            case "alwaysteleport":
            default:
                return ALWAYS_TELEPORT;
        }
    }
}