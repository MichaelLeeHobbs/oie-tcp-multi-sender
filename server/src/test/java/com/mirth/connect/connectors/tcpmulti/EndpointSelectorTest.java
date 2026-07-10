/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class EndpointSelectorTest {

    /** Mutable fake health view backed by a boolean up[] array. */
    private static final class FakeHealth implements EndpointSelector.HealthView {
        final boolean[] up;
        FakeHealth(int n) { up = new boolean[n]; java.util.Arrays.fill(up, true); }
        @Override public boolean isUp(int index, long now) { return up[index]; }
    }

    private static List<Endpoint> endpoints(int... priorities) {
        List<Endpoint> list = new ArrayList<Endpoint>();
        for (int i = 0; i < priorities.length; i++) {
            list.add(new Endpoint("host" + i, String.valueOf(6000 + i), true, priorities[i]));
        }
        return list;
    }

    @Test
    void failover_ordersByPriorityThenIndex_whenAllUp() {
        List<Endpoint> eps = endpoints(2, 0, 1);
        List<Integer> order = EndpointSelector.select(Strategy.FAILOVER, eps, new FakeHealth(3), 0L, new AtomicReference<Integer>());
        assertEquals(List.of(1, 2, 0), order);
    }

    @Test
    void failover_putsUpEndpointsFirst() {
        List<Endpoint> eps = endpoints(0, 1, 2);
        FakeHealth h = new FakeHealth(3);
        h.up[0] = false; // highest-priority endpoint is down
        List<Integer> order = EndpointSelector.select(Strategy.FAILOVER, eps, h, 0L, new AtomicReference<Integer>());
        assertEquals(List.of(1, 2, 0), order, "down endpoint 0 should sort last");
    }

    @Test
    void failover_autoFailsBack_whenHigherPriorityRecovers() {
        List<Endpoint> eps = endpoints(0, 1, 2);
        FakeHealth h = new FakeHealth(3);
        AtomicReference<Integer> sticky = new AtomicReference<Integer>();

        h.up[0] = false;
        assertEquals(Integer.valueOf(1), EndpointSelector.select(Strategy.FAILOVER, eps, h, 0L, sticky).get(0));

        h.up[0] = true; // recovered
        assertEquals(Integer.valueOf(0), EndpointSelector.select(Strategy.FAILOVER, eps, h, 0L, sticky).get(0),
                "re-evaluating from the top each message should fail back to endpoint 0");
    }

    @Test
    void failover_excludesDisabledEndpoints() {
        List<Endpoint> eps = endpoints(0, 1, 2);
        eps.get(1).setEnabled(false);
        List<Integer> order = EndpointSelector.select(Strategy.FAILOVER, eps, new FakeHealth(3), 0L, new AtomicReference<Integer>());
        assertEquals(List.of(0, 2), order);
    }

    @Test
    void failover_emptyWhenNoneEnabled() {
        List<Endpoint> eps = endpoints(0, 1);
        eps.get(0).setEnabled(false);
        eps.get(1).setEnabled(false);
        assertTrue(EndpointSelector.select(Strategy.FAILOVER, eps, new FakeHealth(2), 0L, new AtomicReference<Integer>()).isEmpty());
    }

    @Test
    void sticky_pinsToCurrentWhileUp_thenRepicks() {
        List<Endpoint> eps = endpoints(0, 0, 0);
        FakeHealth h = new FakeHealth(3);
        AtomicReference<Integer> current = new AtomicReference<Integer>();

        // First pick -> index 0, and it is now pinned.
        assertEquals(List.of(0), EndpointSelector.select(Strategy.STICKY, eps, h, 0L, current));
        assertEquals(Integer.valueOf(0), current.get());

        // Still up -> stays pinned to 0.
        assertEquals(List.of(0), EndpointSelector.select(Strategy.STICKY, eps, h, 0L, current));

        // 0 goes down -> re-pick first up (1).
        h.up[0] = false;
        assertEquals(List.of(1), EndpointSelector.select(Strategy.STICKY, eps, h, 0L, current));
        assertEquals(Integer.valueOf(1), current.get());
    }

    @Test
    void sticky_returnsSingleCandidate() {
        List<Endpoint> eps = endpoints(0, 0, 0);
        List<Integer> out = EndpointSelector.select(Strategy.STICKY, eps, new FakeHealth(3), 0L, new AtomicReference<Integer>());
        assertEquals(1, out.size());
    }

    @Test
    void sticky_lastResortWhenNoneUp() {
        List<Endpoint> eps = endpoints(0, 0);
        FakeHealth h = new FakeHealth(2);
        h.up[0] = false;
        h.up[1] = false;
        List<Integer> out = EndpointSelector.select(Strategy.STICKY, eps, h, 0L, new AtomicReference<Integer>());
        assertEquals(1, out.size(), "should still attempt one endpoint so the message queues rather than dropping");
    }
}
