/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;

class ResponsesTest {

    @Test
    void noEndpoint_queueEnabled_returnsNonNullQueued() {
        Response r = Responses.noEndpoint(true, "no endpoints");
        assertNotNull(r);
        assertEquals(Status.QUEUED, r.getStatus());
    }

    @Test
    void noEndpoint_queueDisabled_returnsNonNullError() {
        Response r = Responses.noEndpoint(false, "no endpoints");
        assertNotNull(r);
        assertEquals(Status.ERROR, r.getStatus());
    }
}
