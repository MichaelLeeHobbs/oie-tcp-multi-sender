/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure, side-effect-free endpoint selection. Produces an ordered list of endpoint <b>indices</b> to
 * attempt, given the strategy and a {@link HealthView}. Kept free of any engine dependency so it is
 * unit-testable without a running server.
 */
public final class EndpointSelector {

    /** Minimal read-only view of endpoint health, so the selector needs no engine types. */
    public interface HealthView {
        boolean isUp(int index, long now);
    }

    private EndpointSelector() {}

    /**
     * @param strategy      selection strategy
     * @param endpoints     configured endpoints (index-stable within a deploy)
     * @param health        health view
     * @param now           current time (ms)
     * @param stickyCurrent shared sticky cursor (only used for {@link Strategy#STICKY})
     * @return ordered endpoint indices to attempt (up-first); never null
     */
    public static List<Integer> select(Strategy strategy, List<Endpoint> endpoints, HealthView health,
            long now, AtomicReference<Integer> stickyCurrent) {
        if (endpoints == null || endpoints.isEmpty()) {
            return Collections.emptyList();
        }
        if (strategy == Strategy.STICKY) {
            return selectSticky(endpoints, health, now, stickyCurrent);
        }
        return selectFailover(endpoints, health, now);
    }

    /**
     * FAILOVER: all enabled endpoints, ordered by (up-first, priority asc, index asc). Re-evaluating
     * from the top on every message is what produces auto-failback to a recovered higher-priority
     * endpoint. Down (cooling) endpoints are retained at the tail as a last resort; the dispatcher's
     * probe gate prevents stampeding them.
     */
    static List<Integer> selectFailover(final List<Endpoint> endpoints, final HealthView health,
            final long now) {
        List<Integer> indices = new ArrayList<Integer>();
        for (int i = 0; i < endpoints.size(); i++) {
            if (endpoints.get(i).isEnabled()) {
                indices.add(i);
            }
        }
        indices.sort(new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                boolean upA = health.isUp(a, now);
                boolean upB = health.isUp(b, now);
                if (upA != upB) {
                    return upA ? -1 : 1; // up first
                }
                int pa = endpoints.get(a).getPriority();
                int pb = endpoints.get(b).getPriority();
                if (pa != pb) {
                    return Integer.compare(pa, pb); // lower priority value = higher precedence
                }
                return Integer.compare(a, b); // stable by index
            }
        });
        return indices;
    }

    /**
     * STICKY: a single endpoint. Reuse the current pin if it is still enabled and up; otherwise CAS to
     * the first enabled up endpoint (index order; sticky ignores priority). If none is up, fall back to
     * the first enabled endpoint so the message is still attempted/queued rather than silently dropped.
     */
    static List<Integer> selectSticky(List<Endpoint> endpoints, HealthView health, long now,
            AtomicReference<Integer> stickyCurrent) {
        Integer cur = stickyCurrent.get();
        if (cur != null && cur >= 0 && cur < endpoints.size() && endpoints.get(cur).isEnabled()
                && health.isUp(cur, now)) {
            return Collections.singletonList(cur);
        }
        // Re-pick: first enabled up endpoint.
        for (int i = 0; i < endpoints.size(); i++) {
            if (endpoints.get(i).isEnabled() && health.isUp(i, now)) {
                stickyCurrent.compareAndSet(cur, i);
                return Collections.singletonList(i);
            }
        }
        // None up: last resort, first enabled endpoint.
        for (int i = 0; i < endpoints.size(); i++) {
            if (endpoints.get(i).isEnabled()) {
                stickyCurrent.compareAndSet(cur, i);
                return Collections.singletonList(i);
            }
        }
        return Collections.emptyList();
    }
}
