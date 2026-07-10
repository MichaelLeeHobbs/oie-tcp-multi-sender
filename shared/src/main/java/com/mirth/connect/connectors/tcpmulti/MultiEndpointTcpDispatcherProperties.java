/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mirth.connect.connectors.tcp.TcpDispatcherProperties;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.util.DonkeyElement;

/**
 * Connector properties for the Multi-Endpoint TCP Sender.
 *
 * <p>
 * Extends the stock {@link TcpDispatcherProperties} and adds a list of {@link Endpoint}s plus the
 * selection {@link Strategy} and passive-health parameters. The inherited {@code remoteAddress}/
 * {@code remotePort} become per-send scratch fields written by
 * {@code MultiEndpointTcpDispatcher.send()} from the selected endpoint; they are not shown in the GUI.
 * </p>
 *
 * <p>
 * Namespaced under {@code com.mirth.connect.connectors.tcpmulti} so XStream's
 * {@code com.mirth.connect.connectors.**} wildcard auto-whitelists it (a private package would be
 * rejected on deserialization).
 * </p>
 */
@SuppressWarnings("serial")
public class MultiEndpointTcpDispatcherProperties extends TcpDispatcherProperties {

    public static final String NAME = "TCP Sender (Multi-Endpoint)";

    private List<Endpoint> endpoints;
    private Strategy strategy;
    private int failureThreshold;
    private long cooldownMillis;

    public MultiEndpointTcpDispatcherProperties() {
        super();
        this.endpoints = new ArrayList<Endpoint>();
        this.strategy = Strategy.FAILOVER;
        this.failureThreshold = 3;
        this.cooldownMillis = 30_000L;
    }

    /**
     * Copy constructor. Calls {@code super(props)} to copy every inherited TCP field, then
     * <b>deep-copies</b> the endpoint list (list + each {@link Endpoint}). Without the deep copy,
     * {@link #clone()} would slice to the parent type and silently drop every endpoint.
     */
    public MultiEndpointTcpDispatcherProperties(MultiEndpointTcpDispatcherProperties props) {
        super(props);
        this.endpoints = new ArrayList<Endpoint>();
        if (props.endpoints != null) {
            for (Endpoint ep : props.endpoints) {
                this.endpoints.add(new Endpoint(ep));
            }
        }
        this.strategy = props.strategy;
        this.failureThreshold = props.failureThreshold;
        this.cooldownMillis = props.cooldownMillis;
    }

    public List<Endpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<Endpoint> endpoints) {
        this.endpoints = endpoints;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public long getCooldownMillis() {
        return cooldownMillis;
    }

    public void setCooldownMillis(long cooldownMillis) {
        this.cooldownMillis = cooldownMillis;
    }

    @Override
    public String getName() {
        return NAME;
    }

    // getProtocol() is intentionally NOT overridden: it must remain "TCP" (inherited) so the engine
    // reuses the stock TCP SSL/TLS configuration lookup keyed on that protocol string.

    @Override
    public ConnectorProperties clone() {
        return new MultiEndpointTcpDispatcherProperties(this);
    }

    @Override
    public String toFormattedString() {
        StringBuilder builder = new StringBuilder();
        String newLine = "\n";
        builder.append("STRATEGY: ").append(strategy).append(newLine);
        builder.append("ENDPOINTS:").append(newLine);
        if (endpoints != null) {
            for (Endpoint ep : endpoints) {
                builder.append("  ").append(ep).append(newLine);
            }
        }
        builder.append(newLine).append("[CONTENT]").append(newLine);
        builder.append(getTemplate());
        return builder.toString();
    }

    /*
     * Migrations: the inherited migrate3_x_y methods are deliberately NOT re-declared. They carry
     * real logic (e.g. TcpDispatcherProperties.migrate3_1_0 maps processHL7ACK -> validateResponse);
     * an empty override would destroy that. This connector's fields are introduced at 4.5.0, so only
     * that hook is added, and it calls super first. It is defensive only (fresh configs already carry
     * the fields, and the dispatcher/selection code null-guards a missing endpoint list).
     */
    @Override
    public void migrate4_5_0(DonkeyElement element) {
        super.migrate4_5_0(element);
        element.addChildElementIfNotExists("strategy", Strategy.FAILOVER.name());
        element.addChildElementIfNotExists("failureThreshold", "3");
        element.addChildElementIfNotExists("cooldownMillis", "30000");
        if (element.getChildElement("endpoints") == null) {
            element.addChildElement("endpoints");
        }
    }

    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purgedProperties = super.getPurgedProperties();
        purgedProperties.put("endpointCount", endpoints == null ? 0 : endpoints.size());
        purgedProperties.put("strategy", strategy == null ? null : strategy.name());
        purgedProperties.put("failureThreshold", failureThreshold);
        purgedProperties.put("cooldownMillis", cooldownMillis);
        return purgedProperties;
    }

    // equals() is inherited from TcpDispatcherProperties (EqualsBuilder.reflectionEquals), which
    // reflects over the new fields too; the endpoint List comparison delegates to Endpoint.equals().
}
