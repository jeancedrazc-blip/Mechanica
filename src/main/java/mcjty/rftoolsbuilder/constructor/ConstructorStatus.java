package mcjty.rftoolsbuilder.constructor;

public enum ConstructorStatus {
    IDLE,
    READY,
    AIMING,
    CHARGING,
    FIRING,
    WAITING_ENERGY,
    WAITING_MATERIAL,
    WAITING_CHUNK,
    PAUSED,
    BLOCKED,
    COMPLETE,
    ERROR
}
