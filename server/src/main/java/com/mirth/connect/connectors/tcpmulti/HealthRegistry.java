/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Passive circuit-breaker health, keyed by endpoint <b>list-index</b> (never by resolved host:port, so
 * it survives per-message Velocity variation in host/port and stays bounded).
 *
 * <p>
 * Each endpoint's {@code (failures, downUntil)} pair is a single immutable {@link State} held in an
 * {@link AtomicReferenceArray} and updated by CAS, so the two values move together and can never race
 * into "down with zero failures". A separate per-endpoint {@code probing} flag implements the
 * half-open gate: after cooldown expires, exactly one thread re-probes the endpoint while others fall
 * back.
 * </p>
 *
 * <p>
 * Not cluster-safe: state is in-memory per engine (see README). One {@code HealthRegistry} is created
 * per connector deploy.
 * </p>
 */
public class HealthRegistry implements EndpointSelector.HealthView {

    /** Immutable per-endpoint health snapshot. */
    static final class State {
        final int failures;
        final long downUntil;

        State(int failures, long downUntil) {
            this.failures = failures;
            this.downUntil = downUntil;
        }
    }

    /** Result of the pre-send probe gate for one endpoint. */
    public enum ProbeDecision {
        /** Endpoint is healthy (breaker closed); proceed with no probe permit. */
        PROCEED_HEALTHY,
        /** Breaker tripped but cooldown expired; this thread won the half-open probe. Must {@link #endProbe(int)}. */
        PROCEED_PROBE,
        /** Breaker tripped and still cooling down; skip this endpoint. */
        SKIP_COOLING,
        /** Breaker tripped, cooldown expired, but another thread is already probing; skip. */
        SKIP_PROBING
    }

    private static final State HEALTHY = new State(0, 0L);

    private final int failureThreshold;
    private final long cooldownMillis;
    private final AtomicReferenceArray<State> states;
    private final AtomicBoolean[] probing;

    public HealthRegistry(int endpointCount, int failureThreshold, long cooldownMillis) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMillis = Math.max(0L, cooldownMillis);
        this.states = new AtomicReferenceArray<State>(endpointCount);
        this.probing = new AtomicBoolean[endpointCount];
        for (int i = 0; i < endpointCount; i++) {
            states.set(i, HEALTHY);
            probing[i] = new AtomicBoolean(false);
        }
    }

    public int size() {
        return states.length();
    }

    /**
     * Whether the endpoint is currently usable for ordering purposes: not in an active cooldown
     * window. A half-open (cooldown-expired) endpoint reports {@code true} so it sorts to the front
     * and gets probed; the actual single-prober gating happens in {@link #beginProbe(int, long)}.
     */
    @Override
    public boolean isUp(int index, long now) {
        State s = states.get(index);
        return now >= s.downUntil;
    }

    /**
     * Gate an endpoint before attempting a send. See {@link ProbeDecision}. Callers that receive
     * {@link ProbeDecision#PROCEED_PROBE} MUST call {@link #endProbe(int)} in a finally block.
     */
    public ProbeDecision beginProbe(int index, long now) {
        State s = states.get(index);
        if (s.failures < failureThreshold) {
            return ProbeDecision.PROCEED_HEALTHY;
        }
        // Breaker is tripped.
        if (now < s.downUntil) {
            return ProbeDecision.SKIP_COOLING;
        }
        // Cooldown expired -> half-open. Exactly one thread probes.
        if (probing[index].compareAndSet(false, true)) {
            return ProbeDecision.PROCEED_PROBE;
        }
        return ProbeDecision.SKIP_PROBING;
    }

    /** Release the half-open probe permit acquired via {@link ProbeDecision#PROCEED_PROBE}. */
    public void endProbe(int index) {
        probing[index].set(false);
    }

    /**
     * Record a successful send: reset the breaker to healthy.
     *
     * @return {@code true} iff this success recovered a tripped breaker (the endpoint was DOWN and is
     *         now back UP) — i.e. a state <b>transition</b> worth logging once, not per message.
     */
    public boolean recordSuccess(int index) {
        State old = states.getAndSet(index, HEALTHY);
        return old.failures >= failureThreshold;
    }

    /**
     * Record a (connect-phase) failure. Increments the failure count atomically; when it reaches the
     * threshold, opens the breaker for {@code cooldownMillis}. The {@code (failures, downUntil)} pair
     * is updated as one immutable unit via CAS.
     *
     * @return {@code true} iff this failure is the one that tripped the breaker (UP -> DOWN transition)
     *         — a state change worth logging once, as opposed to a repeat failure on an already-down
     *         endpoint (which returns {@code false}).
     */
    public boolean recordFailure(int index, long now) {
        while (true) {
            State old = states.get(index);
            int nf = old.failures + 1;
            long downUntil = (nf >= failureThreshold) ? now + cooldownMillis : old.downUntil;
            if (states.compareAndSet(index, old, new State(nf, downUntil))) {
                return old.failures < failureThreshold && nf >= failureThreshold;
            }
        }
    }

    // ---- test / introspection helpers ----

    public int getFailures(int index) {
        return states.get(index).failures;
    }

    public long getDownUntil(int index) {
        return states.get(index).downUntil;
    }
}
