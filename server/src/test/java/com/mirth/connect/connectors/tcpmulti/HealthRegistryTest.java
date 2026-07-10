/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class HealthRegistryTest {

    @Test
    void breaker_tripsAtThreshold_andCoolsDown() {
        HealthRegistry h = new HealthRegistry(1, 3, 30_000L);
        long t0 = 1_000_000L;

        assertTrue(h.isUp(0, t0));
        h.recordFailure(0, t0);
        h.recordFailure(0, t0);
        assertTrue(h.isUp(0, t0), "2 failures (< threshold 3) should not trip");

        h.recordFailure(0, t0);
        assertFalse(h.isUp(0, t0), "3rd failure trips the breaker");
        assertFalse(h.isUp(0, t0 + 29_999L), "still cooling before cooldown elapses");
        assertTrue(h.isUp(0, t0 + 30_000L), "up again once cooldown elapses (auto-recovery)");
    }

    @Test
    void success_resetsBreaker() {
        HealthRegistry h = new HealthRegistry(1, 2, 30_000L);
        h.recordFailure(0, 0L);
        h.recordFailure(0, 0L);
        assertFalse(h.isUp(0, 0L));
        h.recordSuccess(0);
        assertTrue(h.isUp(0, 0L));
        assertEquals(0, h.getFailures(0));
    }

    @Test
    void beginProbe_healthyProceedsFreely() {
        HealthRegistry h = new HealthRegistry(1, 3, 30_000L);
        assertEquals(HealthRegistry.ProbeDecision.PROCEED_HEALTHY, h.beginProbe(0, 0L));
    }

    @Test
    void beginProbe_coolingIsSkipped() {
        HealthRegistry h = new HealthRegistry(1, 1, 30_000L);
        h.recordFailure(0, 0L); // trips immediately (threshold 1)
        assertEquals(HealthRegistry.ProbeDecision.SKIP_COOLING, h.beginProbe(0, 10_000L));
    }

    @Test
    void halfOpen_exactlyOneProber() throws Exception {
        final HealthRegistry h = new HealthRegistry(1, 1, 30_000L);
        h.recordFailure(0, 0L); // trips
        final long now = 40_000L; // past cooldown -> half-open

        int threads = 32;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger probers = new AtomicInteger(0);
        final AtomicInteger skippers = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        start.await();
                        HealthRegistry.ProbeDecision d = h.beginProbe(0, now);
                        if (d == HealthRegistry.ProbeDecision.PROCEED_PROBE) {
                            probers.incrementAndGet();
                        } else if (d == HealthRegistry.ProbeDecision.SKIP_PROBING) {
                            skippers.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        start.countDown();
        done.await();

        assertEquals(1, probers.get(), "exactly one thread may probe a half-open endpoint");
        assertEquals(threads - 1, skippers.get());

        // After the prober releases, the next probe attempt can acquire again.
        h.endProbe(0);
        assertEquals(HealthRegistry.ProbeDecision.PROCEED_PROBE, h.beginProbe(0, now));
    }

    @Test
    void atomicHealth_noDownWithZeroFailures_underConcurrency() throws Exception {
        final int threshold = 5;
        final HealthRegistry h = new HealthRegistry(1, threshold, 30_000L);
        int threads = 16;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger violations = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        start.await();
                        for (int k = 0; k < 1000; k++) {
                            h.recordFailure(0, 1L);
                            // Invariant: if downUntil is set (>0), failures must be >= threshold.
                            long downUntil = h.getDownUntil(0);
                            int failures = h.getFailures(0);
                            if (downUntil > 0 && failures < threshold) {
                                violations.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        start.countDown();
        done.await();

        assertEquals(0, violations.get(), "must never observe 'down with < threshold failures'");
    }
}
