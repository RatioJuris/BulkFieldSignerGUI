// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.util.ArrayList;
import java.util.List;

/**
 * The application-wide update state, confined to the Swing event thread. Signing and document processing
 * consult {@link #blocksOperations()}: a pending mandatory (Type A) update blocks them until it is installed,
 * and any installation in progress blocks them so files are never replaced mid-operation.
 */
public final class UpdateStateManager {
    public enum State { UP_TO_DATE, UPDATE_AVAILABLE, MANDATORY_UPDATE_REQUIRED, DOWNLOADING, VERIFYING, INSTALLING, RESTART_REQUIRED, UPDATE_FAILED }

    private final List<Runnable> listeners = new ArrayList<>();
    private State state = State.UP_TO_DATE;
    private boolean mandatory;
    private String detail = "";

    public State state() { return state; }
    /** True while a Type A update is required and not yet installed. */
    public boolean mandatory() { return mandatory; }
    public String detail() { return detail; }

    public boolean blocksOperations() { return mandatory || state == State.INSTALLING || state == State.RESTART_REQUIRED; }

    public void set(State next, boolean mandatoryRequired, String text) {
        state = next;
        mandatory = mandatoryRequired;
        detail = text == null ? "" : text;
        for (Runnable listener : List.copyOf(listeners)) listener.run();
    }

    public void addListener(Runnable listener) { listeners.add(listener); }
}
