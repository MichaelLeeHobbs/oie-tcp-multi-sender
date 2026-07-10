/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.security.AnyTypePermission;

/**
 * Approximates the channel export -> import cycle: serialize the properties to XML and read them back,
 * asserting {@code equals}. Uses a plain XStream (with the type-security relaxed) rather than the
 * engine's {@code ObjectXMLSerializer}, which needs a running server; the CI integration job exercises
 * the real serializer path.
 */
class SerializationRoundTripTest {

    @Test
    void xstream_exportImport_isEqual() {
        MultiEndpointTcpDispatcherProperties original = new MultiEndpointTcpDispatcherProperties();
        original.setStrategy(Strategy.STICKY);
        original.setFailureThreshold(5);
        original.setCooldownMillis(60_000L);
        original.setEndpoints(new java.util.ArrayList<Endpoint>(Arrays.asList(
                new Endpoint("ris-a", "6660", true, 0),
                new Endpoint("ris-b", "6661", false, 1))));

        XStream xstream = new XStream();
        xstream.addPermission(AnyTypePermission.ANY);

        String xml = xstream.toXML(original);
        Object restored = xstream.fromXML(xml);

        assertEquals(original, restored, "properties must survive an export/import round-trip unchanged");
    }
}
