/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;

class FailureClassifierTest {

    private static Response resp(Status status, String statusMessage, String error) {
        return new Response(status, null, statusMessage, error, false);
    }

    @Test
    void sent_isNeverAFailover() {
        assertFalse(FailureClassifier.isConnectPhaseFailure(resp(Status.SENT, "Message successfully sent.", "")));
    }

    @Test
    void nullResponse_doesNotFailover() {
        assertFalse(FailureClassifier.isConnectPhaseFailure(null));
    }

    @Test
    void connectException_isConnectPhase() {
        assertTrue(FailureClassifier.isConnectPhaseFailure(
                resp(Status.QUEUED, "ConnectException: Connection refused", "ConnectException: Connection refused (host:6660)")));
    }

    @Test
    void blankAddress_isConnectPhase() {
        assertTrue(FailureClassifier.isConnectPhaseFailure(
                resp(Status.QUEUED, "Exception: Remote address is blank.", "Remote address is blank.")));
    }

    @Test
    void invalidPort_isConnectPhase() {
        assertTrue(FailureClassifier.isConnectPhaseFailure(
                resp(Status.QUEUED, "Exception: Remote port is invalid.", "Remote port is invalid.")));
    }

    @Test
    void ackReadTimeout_isPostWrite_noFailover() {
        // Stock inner send() produces this message on a response-read timeout (post-write).
        assertFalse(FailureClassifier.isConnectPhaseFailure(
                resp(Status.QUEUED, "Timeout waiting for response", "SocketTimeoutException: Read timed out")));
    }

    @Test
    void connectTimeout_isConservativelyNotFailedOver() {
        // A connect timeout surfaces as SocketTimeoutException from the outer catch; deliberately NOT
        // whitelisted (default-safe: never risk a cross-endpoint duplicate).
        assertFalse(FailureClassifier.isConnectPhaseFailure(
                resp(Status.QUEUED, "SocketTimeoutException: connect timed out", "SocketTimeoutException: connect timed out")));
    }

    @Test
    void genericWriteError_isPostWrite_noFailover() {
        assertFalse(FailureClassifier.isConnectPhaseFailure(
                resp(Status.QUEUED, "IOException: Broken pipe", "IOException: Broken pipe")));
    }
}
