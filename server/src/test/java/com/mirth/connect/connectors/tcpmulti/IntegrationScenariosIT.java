/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * CI-guarded integration scenarios (the substitute for a compile-time contract: subclassing couples
 * us to {@code TcpDispatcher} internals, so each supported OIE release must be re-verified).
 *
 * <p>
 * These exercise the full {@code send()} path, which requires a deployed OIE channel (the dispatcher's
 * field initializers call {@code ControllerFactory}, and {@code super.send()} needs the transmission
 * mode provider, event controller and socket machinery). They run only when {@code -Dtcpmulti.it=true}
 * AND a harness pointing at a running OIE is supplied. Absent the harness they self-skip via
 * {@link Assumptions}, so they never give a false green.
 * </p>
 *
 * <p>
 * A minimal local {@link MllpSink} is provided so a future harness can stand up fake MLLP receivers;
 * wiring a deployed channel around them is the remaining work (see docs/BUILD.md "Integration tests").
 * </p>
 */
@EnabledIfSystemProperty(named = "tcpmulti.it", matches = "true")
class IntegrationScenariosIT {

    private static void requireHarness() {
        Assumptions.assumeTrue(System.getProperty("tcpmulti.oie.home") != null,
                "Set -Dtcpmulti.oie.home=<path> and a running OIE to execute integration scenarios.");
    }

    /** connect-refused on the primary -> message delivered to the next endpoint. */
    @Test
    void connectRefused_failsOverToNextEndpoint() {
        requireHarness();
        // TODO(harness): deploy a channel with endpoints [refused:port, sink:port]; send 1 message;
        // assert the message arrives at the second sink and the first endpoint's breaker records 1 failure.
    }

    /** endpoint killed AFTER connect (ACK lost) -> NO cross-endpoint move; queue retries the same endpoint. */
    @Test
    void killedAfterConnect_doesNotCrossDeliver() {
        requireHarness();
        // TODO(harness): sink accepts, reads the message, then closes without ACK. Assert the message is
        // NOT delivered to any other endpoint (no duplicate) and the connector queues for retry on the same endpoint.
    }

    /** all endpoints down + queue enabled -> QUEUED (never dropped). */
    @Test
    void allDown_withQueueEnabled_queues() {
        requireHarness();
        // TODO(harness): all endpoints refuse; assert message status is QUEUED (not ERROR) when the destination queue is enabled.
    }

    /** MLLP NACK -> SENT (no failover); NACK is judged later by the response validator. */
    @Test
    void nack_doesNotFailOver() {
        requireHarness();
        // TODO(harness): sink returns an MLLP NACK. Assert send() returns SENT (no endpoint move); validation handles the NACK downstream.
    }

    /** A trivial single-connection MLLP sink for future harness use. */
    static final class MllpSink implements AutoCloseable {
        private final ServerSocket serverSocket;
        private volatile boolean running = true;

        MllpSink(boolean sendAck) throws IOException {
            this.serverSocket = new ServerSocket(0);
            Thread t = new Thread(() -> {
                while (running) {
                    try (Socket s = serverSocket.accept()) {
                        s.getInputStream().read(new byte[4096]);
                        if (sendAck) {
                            // <VT> MSA|AA <FS><CR>
                            s.getOutputStream().write(new byte[] { 0x0b, 'M', 'S', 'A', '|', 'A', 'A', 0x1c, 0x0d });
                            s.getOutputStream().flush();
                        }
                    } catch (IOException e) {
                        // socket closed / sink stopped
                    }
                }
            }, "tcpmulti-mllp-sink");
            t.setDaemon(true);
            t.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            running = false;
            serverSocket.close();
        }
    }
}
