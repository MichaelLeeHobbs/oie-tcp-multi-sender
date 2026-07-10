/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;

class PropertiesCloneEqualsTest {

    private static MultiEndpointTcpDispatcherProperties sample() {
        MultiEndpointTcpDispatcherProperties p = new MultiEndpointTcpDispatcherProperties();
        p.setStrategy(Strategy.FAILOVER);
        p.setFailureThreshold(4);
        p.setCooldownMillis(45_000L);
        p.setEndpoints(new java.util.ArrayList<Endpoint>(Arrays.asList(
                new Endpoint("pacs-a", "6660", true, 0),
                new Endpoint("pacs-b", "6661", true, 1))));
        p.setTemplate("${message.encodedData}");
        return p;
    }

    @Test
    void name_isStableAndUnique() {
        assertEquals("TCP Sender (Multi-Endpoint)", new MultiEndpointTcpDispatcherProperties().getName());
    }

    @Test
    void protocol_staysTcp_soSslConfigReuseWorks() {
        assertEquals("TCP", new MultiEndpointTcpDispatcherProperties().getProtocol());
    }

    @Test
    void clone_returnsSubclassType_andDeepCopiesEndpoints() {
        MultiEndpointTcpDispatcherProperties original = sample();
        ConnectorProperties clone = original.clone();

        assertTrue(clone instanceof MultiEndpointTcpDispatcherProperties, "clone must retain the subclass type");
        MultiEndpointTcpDispatcherProperties typed = (MultiEndpointTcpDispatcherProperties) clone;

        assertEquals(2, typed.getEndpoints().size(), "endpoints must not be dropped by clone");
        assertNotSame(original.getEndpoints(), typed.getEndpoints(), "endpoint list must be a distinct instance");
        assertNotSame(original.getEndpoints().get(0), typed.getEndpoints().get(0), "each Endpoint must be a distinct instance");

        // Mutating the clone must not affect the original (proves deep copy).
        typed.getEndpoints().get(0).setHost("changed");
        assertEquals("pacs-a", original.getEndpoints().get(0).getHost());
    }

    @Test
    void clone_isEqualToOriginal() {
        MultiEndpointTcpDispatcherProperties original = sample();
        assertEquals(original, original.clone(), "a fresh clone must be equal (reflection-equals over new fields)");
    }

    @Test
    void notEqual_whenAnEndpointDiffers() {
        MultiEndpointTcpDispatcherProperties a = sample();
        MultiEndpointTcpDispatcherProperties b = sample();
        b.getEndpoints().get(1).setPort("9999");
        assertNotEquals(a, b);
    }

    @Test
    void notEqual_whenStrategyDiffers() {
        MultiEndpointTcpDispatcherProperties a = sample();
        MultiEndpointTcpDispatcherProperties b = sample();
        b.setStrategy(Strategy.STICKY);
        assertNotEquals(a, b);
    }

    @Test
    void copyConstructor_preservesInheritedTcpFields() {
        MultiEndpointTcpDispatcherProperties original = sample();
        original.setResponseTimeout("1234");
        original.setKeepConnectionOpen(true);
        MultiEndpointTcpDispatcherProperties copy = new MultiEndpointTcpDispatcherProperties(original);
        assertEquals("1234", copy.getResponseTimeout());
        assertTrue(copy.isKeepConnectionOpen());
        // transmissionModeProperties is shared by reference in the stock copy-ctor (same as stock TCP).
        assertSame(original.getTransmissionModeProperties(), copy.getTransmissionModeProperties());
    }

    @Test
    void endpoint_equalsAndHashCode() {
        Endpoint a = new Endpoint("h", "6660", true, 1);
        Endpoint b = new Endpoint("h", "6660", true, 1);
        Endpoint c = new Endpoint("h", "6661", true, 1);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
