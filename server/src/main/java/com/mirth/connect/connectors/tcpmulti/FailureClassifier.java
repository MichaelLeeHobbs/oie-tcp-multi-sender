/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import org.apache.commons.lang3.StringUtils;

import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;

/**
 * Pure classification of a stock-TCP {@link Response} into "safe to fail over" vs "must stay". Kept
 * static/side-effect-free so it is unit-testable without constructing the dispatcher (whose field
 * initializers require a live engine).
 */
public final class FailureClassifier {

    private FailureClassifier() {}

    /**
     * A connect-phase (pre-write) failure means nothing was written to the endpoint, so re-sending to
     * a different endpoint cannot duplicate an already-delivered message. These signatures qualify:
     * <ul>
     * <li>{@code ConnectException} (connection <b>refused</b> — host up, port closed)</li>
     * <li>a connect <b>timeout</b> (host down / SYN blackholed) — surfaces as
     * {@code SocketTimeoutException: connect timed out}; the TCP handshake never completed, so nothing
     * was sent. This is the common "dead node" HA case and <b>must</b> fail over.</li>
     * <li>{@code "Remote address is blank"} / {@code "Remote port is invalid"} (misconfiguration)</li>
     * </ul>
     * Default-safe: anything else returns {@code false} — do NOT move. In particular an <b>ACK-read</b>
     * timeout ({@code "Read timed out"}) is <i>post-write</i>: the message may already have been received,
     * so the engine queue must retry the <b>same</b> endpoint rather than cross-deliver. The two timeouts
     * are told apart by the JDK's exception message ("connect timed out" vs "Read timed out").
     *
     * <p>
     * {@code SENT} is never a failure and always returns {@code false}.
     * </p>
     */
    public static boolean isConnectPhaseFailure(Response response) {
        if (response == null || response.getStatus() == Status.SENT) {
            return false;
        }
        String haystack = StringUtils.defaultString(response.getError()) + "\n"
                + StringUtils.defaultString(response.getStatusMessage());
        if (haystack.contains("ConnectException")
                || haystack.contains("Remote address is blank")
                || haystack.contains("Remote port is invalid")) {
            return true;
        }
        // Connect timeout (pre-write, safe to fail over) vs. ACK-read timeout (post-write, must NOT move).
        // Match only the connect form; "Read timed out" deliberately does not qualify.
        return haystack.toLowerCase(java.util.Locale.ROOT).contains("connect timed out");
    }
}
