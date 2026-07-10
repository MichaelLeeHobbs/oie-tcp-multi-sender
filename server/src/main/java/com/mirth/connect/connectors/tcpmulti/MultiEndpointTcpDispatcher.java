/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mirth.connect.connectors.tcp.TcpDispatcher;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.server.util.TemplateValueReplacer;

/**
 * Multi-endpoint TCP destination connector. Subclasses the stock {@link TcpDispatcher} and overrides
 * <b>only</b> {@code send()} and {@code replaceConnectorProperties()}; all transport (MLLP framing,
 * keep-alive, ACK handling, TLS, socket lifecycle) is inherited.
 *
 * <p>
 * Per message the engine hands us a fresh clone of the properties (see
 * {@code DestinationConnector.process()} clone-then-replace flow), so mutating {@code remoteAddress}/
 * {@code remotePort} on the passed-in props is thread-safe.
 * </p>
 *
 * <h2>Failover policy (patient-safety critical)</h2>
 * <ul>
 * <li>{@code SENT} -> success, return.</li>
 * <li>Non-{@code SENT} <b>connect-phase</b> failure (nothing written) -> fail over to the next
 * endpoint. Detected via a whitelist of pre-write signatures.</li>
 * <li>Any other non-{@code SENT} outcome (post-write: write/IO error, ACK-read timeout, ...) ->
 * return unchanged. The engine queue retries the <b>same</b> endpoint; we never cross-deliver a
 * message that may already have been received (which would duplicate an HL7 order/result).</li>
 * </ul>
 */
public class MultiEndpointTcpDispatcher extends TcpDispatcher {

    private final Logger logger = LogManager.getLogger(getClass());
    private final TemplateValueReplacer replacer = new TemplateValueReplacer();

    // Per-deploy state (rebuilt in onDeploy).
    private volatile HealthRegistry health;
    private volatile AtomicReference<Integer> stickyCurrent;

    @Override
    public void onDeploy() throws ConnectorTaskException {
        super.onDeploy();

        MultiEndpointTcpDispatcherProperties props = (MultiEndpointTcpDispatcherProperties) getConnectorProperties();

        // Server-side backstop for the GUI check: imported channels bypass checkProperties().
        if (props.getStrategy() == Strategy.STICKY) {
            int threads = props.getDestinationConnectorProperties().getThreadCount();
            if (threads != 1) {
                throw new ConnectorTaskException("Multi-Endpoint TCP Sender: STICKY strategy requires the destination queue to use exactly 1 thread (configured: "
                        + threads + "). Multiple queue threads open multiple sockets to the sticky endpoint, "
                        + "defeating the single-connection guarantee.");
            }
        }

        int n = props.getEndpoints() == null ? 0 : props.getEndpoints().size();
        health = new HealthRegistry(n, props.getFailureThreshold(), props.getCooldownMillis());
        stickyCurrent = new AtomicReference<Integer>();
    }

    @Override
    public void replaceConnectorProperties(ConnectorProperties connectorProperties, ConnectorMessage connectorMessage) {
        // Let the parent replace template/local-binding (and the scratch remoteAddress/remotePort,
        // which send() will overwrite anyway).
        super.replaceConnectorProperties(connectorProperties, connectorMessage);

        MultiEndpointTcpDispatcherProperties props = (MultiEndpointTcpDispatcherProperties) connectorProperties;
        List<Endpoint> endpoints = props.getEndpoints();
        if (endpoints != null) {
            // Safe to mutate: clone() deep-copied the endpoint list for this message.
            for (Endpoint ep : endpoints) {
                ep.setHost(replacer.replaceValues(ep.getHost(), connectorMessage));
                ep.setPort(replacer.replaceValues(ep.getPort(), connectorMessage));
            }
        }
    }

    @Override
    public Response send(ConnectorProperties connectorProperties, ConnectorMessage message) throws InterruptedException {
        // Stop failing over if the connector is being halted.
        if (Thread.interrupted()) {
            throw new InterruptedException("Multi-Endpoint TCP Sender halted before send.");
        }

        MultiEndpointTcpDispatcherProperties props = (MultiEndpointTcpDispatcherProperties) connectorProperties;
        List<Endpoint> endpoints = props.getEndpoints();
        long now = System.currentTimeMillis();

        List<Integer> candidates = EndpointSelector.select(props.getStrategy(), endpoints, health, now, stickyCurrent);

        if (candidates.isEmpty()) {
            // NEVER return null.
            return noEndpointResponse("No enabled endpoints are configured for the Multi-Endpoint TCP Sender.");
        }

        Set<Integer> seen = new HashSet<Integer>();
        Response lastConnectFailure = null;

        for (Integer i : candidates) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Multi-Endpoint TCP Sender halted during failover.");
            }
            // Record each endpoint's health at most once per send() (so one message's queue retries
            // can't trip the breaker N times).
            if (!seen.add(i)) {
                continue;
            }

            HealthRegistry.ProbeDecision decision = health.beginProbe(i, now);
            if (decision == HealthRegistry.ProbeDecision.SKIP_COOLING
                    || decision == HealthRegistry.ProbeDecision.SKIP_PROBING) {
                continue;
            }
            boolean holdingProbe = decision == HealthRegistry.ProbeDecision.PROCEED_PROBE;

            try {
                Endpoint ep = endpoints.get(i);
                props.setRemoteAddress(ep.getHost());
                props.setRemotePort(ep.getPort());

                Response response = super.send(props, message);
                if (response == null) {
                    // Abnormal: stock TcpDispatcher never returns null, but be defensive and do NOT
                    // cross-deliver on an unknown outcome.
                    return noEndpointResponse("Underlying TCP dispatcher returned no response for endpoint "
                            + describe(ep) + ".");
                }

                if (response.getStatus() == Status.SENT) {
                    health.recordSuccess(i);
                    if (logger.isDebugEnabled()) {
                        logger.debug("[tcpmulti] sent via endpoint[" + i + "] " + describe(ep) + " on "
                                + getDestinationName() + ".");
                    }
                    return response;
                }

                if (FailureClassifier.isConnectPhaseFailure(response)) {
                    health.recordFailure(i, now);
                    lastConnectFailure = response;
                    if (props.getStrategy() == Strategy.STICKY) {
                        // STICKY runs single-threaded (enforced), so a plain clear is correct and
                        // avoids AtomicReference's identity-based CAS on boxed Integers.
                        stickyCurrent.set(null);
                    }
                    logger.warn("[tcpmulti] connect-phase failure on endpoint[" + i + "] " + describe(ep)
                            + " -> failing over. (" + summarize(response) + ")");
                    continue;
                }

                // Post-write / unrecognized outcome: DO NOT move. Return unchanged so the engine queue
                // retries the SAME endpoint (prevents duplicate delivery after a lost ACK).
                logger.warn("[tcpmulti] non-connect outcome on endpoint[" + i + "] " + describe(ep)
                        + " -> not failing over; engine queue will retry the same endpoint. ("
                        + summarize(response) + ")");
                return response;
            } finally {
                if (holdingProbe) {
                    health.endProbe(i);
                }
            }
        }

        // Exhausted the candidate list.
        if (lastConnectFailure != null) {
            // QUEUED/ERROR from the underlying dispatcher -> engine queues (if enabled).
            return lastConnectFailure;
        }
        // Everything was skipped (all endpoints cooling, or being probed by other threads).
        return noEndpointResponse("All endpoints are in cooldown or being probed by another thread.");
    }

    private Response noEndpointResponse(String message) {
        return Responses.noEndpoint(isQueueEnabled(), message);
    }

    private static String describe(Endpoint ep) {
        return ep.getHost() + ":" + ep.getPort();
    }

    private static String summarize(Response response) {
        return response.getStatus() + " / " + StringUtils.defaultString(response.getStatusMessage());
    }
}
