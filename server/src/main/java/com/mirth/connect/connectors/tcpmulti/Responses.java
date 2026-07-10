/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;

/** Small factory for the synthetic responses the dispatcher must return (never {@code null}). */
public final class Responses {

    private Responses() {}

    /**
     * Response for "no endpoint could be attempted" (empty candidate list, or all cooling/being
     * probed). QUEUED when the destination queue is enabled (so the engine retries), else ERROR.
     * Never null.
     */
    public static Response noEndpoint(boolean queueEnabled, String message) {
        return new Response(queueEnabled ? Status.QUEUED : Status.ERROR, null, message, "", false);
    }
}
