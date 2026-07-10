/*
 * Generates a deployable OIE channel XML for the failover test: a Channel Reader source (injectable via
 * the REST send-message API) -> a Multi-Endpoint TCP Sender destination pointing at the sink's two ports.
 * Uses the REAL model + serializer so the XStream output is guaranteed valid.
 *
 * Compile + run against the extracted engine jars (see run-generate-channel in TESTING). Writes channel XML
 * to stdout.
 */
import java.util.ArrayList;
import java.util.List;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.Connector.Mode;
import com.mirth.connect.model.Filter;
import com.mirth.connect.model.Transformer;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.datatypes.raw.RawDataTypeProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorProperties;
import com.mirth.connect.connectors.vm.VmReceiverProperties;
import com.mirth.connect.connectors.tcpmulti.Endpoint;
import com.mirth.connect.connectors.tcpmulti.MultiEndpointTcpDispatcherProperties;
import com.mirth.connect.connectors.tcpmulti.Strategy;

public class GenerateChannel {
    // A connector's transformer must carry non-null inbound/outbound DataTypeProperties or deploy NPEs in
    // DataTypeFactory. RAW is a byte-passthrough datatype (no parsing) — exactly right for a TCP MLLP test.
    private static Transformer rawTransformer() {
        Transformer t = new Transformer();
        t.setInboundDataType("RAW");
        t.setOutboundDataType("RAW");
        t.setInboundProperties(new RawDataTypeProperties());
        t.setOutboundProperties(new RawDataTypeProperties());
        return t;
    }

    public static void main(String[] args) throws Exception {
        String strategy = args.length > 0 ? args[0] : "FAILOVER";

        ObjectXMLSerializer ser = ObjectXMLSerializer.getInstance();
        try { ser.init("4.5.2"); } catch (Throwable ignore) { /* may be pre-initialized */ }

        Channel channel = new Channel();
        channel.setId("tcpmulti-failover-test");
        channel.setName("tcpmulti-failover-test");
        channel.setRevision(1);

        // Source: Channel Reader (VM) — messages injected via REST land here.
        Connector src = new Connector();
        src.setMetaDataId(0);
        src.setName("sourceConnector");
        src.setMode(Mode.SOURCE);
        src.setEnabled(true);
        src.setFilter(new Filter());
        src.setTransformer(rawTransformer());
        VmReceiverProperties srcProps = new VmReceiverProperties();
        src.setTransportName(srcProps.getName());
        src.setProperties(srcProps);
        channel.setSourceConnector(src);

        // Destination: our Multi-Endpoint TCP Sender -> sink:6661 (pri 0), sink:6662 (pri 1).
        MultiEndpointTcpDispatcherProperties p = new MultiEndpointTcpDispatcherProperties();
        List<Endpoint> eps = new ArrayList<Endpoint>();
        eps.add(new Endpoint("sink", "6661", true, 0));
        eps.add(new Endpoint("sink", "6662", true, 1));
        p.setEndpoints(eps);
        p.setStrategy(Strategy.valueOf(strategy));
        p.setFailureThreshold(1);       // trip after a single failure (fast tests)
        p.setCooldownMillis(3000L);      // short cooldown so auto-failback is testable quickly
        p.setResponseTimeout("2000");    // short ACK wait for the 'hang' (lost-ACK) scenario
        p.setKeepConnectionOpen(false);
        DestinationConnectorProperties dcp = p.getDestinationConnectorProperties();
        dcp.setQueueEnabled(true);       // never-drop; queue retries
        dcp.setThreadCount(1);           // required for STICKY; fine for FAILOVER
        dcp.setRetryCount(0);            // this plugin + the queue own retries
        dcp.setSendFirst(false);

        Connector dst = new Connector();
        dst.setMetaDataId(1);
        dst.setName("Destination 1");
        dst.setMode(Mode.DESTINATION);
        dst.setEnabled(true);
        dst.setFilter(new Filter());
        dst.setTransformer(rawTransformer());
        dst.setResponseTransformer(rawTransformer());
        dst.setTransportName(p.getName());
        dst.setProperties(p);

        channel.getDestinationConnectors().add(dst);

        System.out.println(ser.serialize(channel));
    }
}
