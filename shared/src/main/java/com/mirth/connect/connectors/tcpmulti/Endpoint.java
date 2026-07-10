/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import java.io.Serializable;

/**
 * A single TCP endpoint (host/port) belonging to one logical destination.
 *
 * <p>
 * {@code host} and {@code port} are stored as strings so they can carry Velocity/JavaScript template
 * references (e.g. {@code ${remoteHost}}); they are resolved per message. {@code equals}/
 * {@code hashCode} are implemented because {@link MultiEndpointTcpDispatcherProperties#equals(Object)}
 * (inherited reflection-equals) compares the endpoint {@code List} element-by-element and the
 * Administrator's dirty-check relies on it.
 * </p>
 *
 * <p>
 * Note: {@code weight} (from SPEC's data model) is omitted because it only applies to round-robin,
 * which is excluded from v1.
 * </p>
 */
public class Endpoint implements Serializable {

    private static final long serialVersionUID = 1L;

    private String host;
    private String port;
    private boolean enabled;
    private int priority;
    /** Free-text operator note (e.g. "primary DC", "vendor A"). Informational only; never affects routing. */
    private String notes = "";

    public Endpoint() {
        this.host = "127.0.0.1";
        this.port = "6660";
        this.enabled = true;
        this.priority = 0;
        this.notes = "";
    }

    public Endpoint(String host, String port, boolean enabled, int priority) {
        this(host, port, enabled, priority, "");
    }

    public Endpoint(String host, String port, boolean enabled, int priority, String notes) {
        this.host = host;
        this.port = port;
        this.enabled = enabled;
        this.priority = priority;
        this.notes = notes == null ? "" : notes;
    }

    /** Deep-copy constructor. Endpoint holds only immutable/scalar fields, so this is a full copy. */
    public Endpoint(Endpoint other) {
        this.host = other.host;
        this.port = other.port;
        this.enabled = other.enabled;
        this.priority = other.priority;
        this.notes = other.notes;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getNotes() {
        return notes == null ? "" : notes;
    }

    public void setNotes(String notes) {
        this.notes = notes == null ? "" : notes;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Endpoint)) {
            return false;
        }
        Endpoint other = (Endpoint) obj;
        return enabled == other.enabled && priority == other.priority
                && java.util.Objects.equals(host, other.host)
                && java.util.Objects.equals(port, other.port)
                && java.util.Objects.equals(getNotes(), other.getNotes());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(host, port, enabled, priority, getNotes());
    }

    @Override
    public String toString() {
        return host + ":" + port + " (enabled=" + enabled + ", priority=" + priority + ")";
    }
}
