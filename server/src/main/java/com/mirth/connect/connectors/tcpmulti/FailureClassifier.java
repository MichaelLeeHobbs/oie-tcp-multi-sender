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
     * a different endpoint cannot duplicate an already-delivered message. Only these signatures qualify:
     * <ul>
     * <li>{@code ConnectException} (connection refused)</li>
     * <li>{@code "Remote address is blank"}</li>
     * <li>{@code "Remote port is invalid"}</li>
     * </ul>
     * Default-safe: anything else (write/IO error, ACK-read timeout, connect <i>timeout</i> surfacing
     * as {@code SocketTimeoutException}, unknown) returns {@code false} — do NOT move.
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
        return haystack.contains("ConnectException")
                || haystack.contains("Remote address is blank")
                || haystack.contains("Remote port is invalid");
    }
}
