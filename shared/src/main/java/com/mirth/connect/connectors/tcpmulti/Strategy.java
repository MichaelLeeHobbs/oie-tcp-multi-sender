/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

/**
 * Endpoint selection strategy for the Multi-Endpoint TCP Sender.
 *
 * <p>
 * Round-robin is intentionally omitted from v1 (see README.md "Why not Round-Robin"). Only
 * {@link #FAILOVER} and {@link #STICKY} are supported.
 * </p>
 */
public enum Strategy {
    /**
     * Ordered by priority; always uses the highest-priority reachable endpoint and auto-fails-back
     * when a higher-priority one recovers. One active connection.
     */
    FAILOVER,

    /**
     * Pins to one endpoint until its circuit breaker trips, then pins to another. No priority, no
     * auto-failback. Requires the destination queue set to 1 thread.
     */
    STICKY;
}
