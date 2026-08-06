package com.inferqueue.domain;

public enum Tier {
    FREE,
    PREMIUM;

    /** Los clientes premium van al stream de prioridad, los free al standard. */
    public Priority defaultPriority() {
        return this == PREMIUM ? Priority.PRIORITY : Priority.STANDARD;
    }
}
