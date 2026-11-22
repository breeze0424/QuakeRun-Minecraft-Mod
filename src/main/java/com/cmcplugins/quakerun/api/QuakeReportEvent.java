package com.cmcplugins.quakerun.api;

import net.minecraftforge.eventbus.api.Event;

/** Posted on the Forge event bus for each accepted new or revised EEW report. */
public final class QuakeReportEvent extends Event {
    private final QuakeData quake;

    public QuakeReportEvent(QuakeData quake) { this.quake = quake; }

    public QuakeData quake() { return quake; }
}
