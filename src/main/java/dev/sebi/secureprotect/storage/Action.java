package dev.sebi.secureprotect.storage;

import java.util.EnumSet;
import java.util.Set;

public enum Action {
    BREAK(0),
    PLACE(1),
    INTERACT(2),
    TAKE(3),
    PUT(4);

    public static final Set<Action> BLOCK = EnumSet.of(BREAK, PLACE, INTERACT);
    public static final Set<Action> CONTAINER = EnumSet.of(TAKE, PUT);
    public static final Set<Action> ALL = EnumSet.allOf(Action.class);

    private static final Action[] BY_ID = values();

    public final int id;

    Action(int id) {
        this.id = id;
    }

    public static Action of(int id) {
        return BY_ID[id];
    }
}
