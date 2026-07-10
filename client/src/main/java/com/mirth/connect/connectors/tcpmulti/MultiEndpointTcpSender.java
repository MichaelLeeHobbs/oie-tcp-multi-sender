/*
 * Copyright (c) 2026 Multi-Endpoint TCP Sender contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.tcpmulti;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import net.miginfocom.swing.MigLayout;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.jdesktop.swingx.decorator.HighlighterFactory;

import com.mirth.connect.client.ui.Frame;
import com.mirth.connect.client.ui.Mirth;
import com.mirth.connect.client.ui.LoadedExtensions;
import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.client.ui.UIConstants;
import com.mirth.connect.client.ui.components.MirthCheckBox;
import com.mirth.connect.client.ui.components.MirthComboBox;
import com.mirth.connect.client.ui.components.MirthRadioButton;
import com.mirth.connect.client.ui.components.MirthSyntaxTextArea;
import com.mirth.connect.client.ui.components.MirthTable;
import com.mirth.connect.client.ui.components.MirthTextField;
import com.mirth.connect.client.ui.panels.connectors.ConnectorSettingsPanel;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorProperties;
import com.mirth.connect.model.transmission.TransmissionModeProperties;
import com.mirth.connect.plugins.BasicModeClientProvider;
import com.mirth.connect.plugins.TransmissionModeClientProvider;
import com.mirth.connect.plugins.TransmissionModePlugin;

/**
 * Administrator GUI for the Multi-Endpoint TCP Sender.
 *
 * <p>
 * Reuses the stock transmission-mode sub-panel machinery (public {@code TransmissionMode*} API) and
 * provides a functional panel for the multi-endpoint fields (endpoint table, strategy, health params)
 * plus the essential inherited TCP transport fields. The stock TCP sender's other sub-panels
 * (remote/local address fields, Test Connection, override-local-binding, check-remote-host, server
 * mode) are {@code private} on {@link com.mirth.connect.connectors.tcp.TcpSender} and cannot be
 * reused; full advanced-TCP-setting parity is a documented follow-up.
 * </p>
 */
public class MultiEndpointTcpSender extends ConnectorSettingsPanel implements ActionListener {

    private static final String[] STRATEGY_ITEMS = { Strategy.FAILOVER.name(), Strategy.STICKY.name() };
    private static final String[] ENDPOINT_COLUMNS = { "Host", "Port", "Enabled", "Priority", "Notes" };

    private final Frame parent;

    private TransmissionModeClientProvider defaultProvider;
    private TransmissionModeClientProvider transmissionModeProvider;
    private JComponent settingsPlaceHolder;
    private String selectedMode;
    private boolean modeLock = false;

    // Components
    private MirthComboBox transmissionModeComboBox;
    private JLabel sampleLabel;
    private JLabel sampleValue;
    private MirthTable endpointTable;
    private DefaultTableModel endpointModel;
    private JButton addButton;
    private JButton removeButton;
    private JButton upButton;
    private JButton downButton;
    private JComboBox<String> strategyComboBox;
    private MirthTextField failureThresholdField;
    private MirthTextField cooldownField;
    private MirthTextField responseTimeoutField;
    private JLabel sendTimeoutLabel;
    private MirthTextField sendTimeoutField;
    private MirthTextField bufferSizeField;
    private MirthRadioButton keepConnectionOpenYesRadio;
    private MirthRadioButton keepConnectionOpenNoRadio;
    private MirthCheckBox ignoreResponseCheckBox;
    private MirthRadioButton queueOnResponseTimeoutYesRadio;
    private MirthRadioButton queueOnResponseTimeoutNoRadio;
    private MirthRadioButton dataTypeTextRadio;
    private MirthRadioButton dataTypeBinaryRadio;
    private MirthComboBox charsetEncodingComboBox;
    private JLabel charsetEncodingLabel;
    private MirthSyntaxTextArea templateTextArea;

    public MultiEndpointTcpSender() {
        this.parent = PlatformUI.MIRTH_FRAME;
        initComponents();
        initLayout();

        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<String>();
        model.addElement("Basic TCP");
        selectedMode = "Basic TCP";
        for (String pluginPointName : LoadedExtensions.getInstance().getTransmissionModePlugins().keySet()) {
            model.addElement(pluginPointName);
            if (pluginPointName.equals("MLLP")) {
                defaultProvider = LoadedExtensions.getInstance().getTransmissionModePlugins().get(pluginPointName).createProvider();
            }
        }
        transmissionModeComboBox.setModel(model);

        parent.setupCharsetEncodingForConnector(charsetEncodingComboBox);
    }

    @Override
    public String getConnectorName() {
        return new MultiEndpointTcpDispatcherProperties().getName();
    }

    @Override
    public ConnectorProperties getProperties() {
        MultiEndpointTcpDispatcherProperties properties = new MultiEndpointTcpDispatcherProperties();

        if (transmissionModeProvider != null) {
            properties.setTransmissionModeProperties((TransmissionModeProperties) transmissionModeProvider.getProperties());
        }

        properties.setServerMode(false);
        properties.setOverrideLocalBinding(false);

        properties.setEndpoints(readEndpointsFromTable());
        properties.setStrategy(Strategy.valueOf((String) strategyComboBox.getSelectedItem()));
        properties.setFailureThreshold(NumberUtils.toInt(failureThresholdField.getText(), 3));
        properties.setCooldownMillis(NumberUtils.toLong(cooldownField.getText(), 30_000L));

        properties.setResponseTimeout(responseTimeoutField.getText());
        properties.setSendTimeout(sendTimeoutField.getText());
        properties.setBufferSize(bufferSizeField.getText());
        properties.setKeepConnectionOpen(keepConnectionOpenYesRadio.isSelected());
        properties.setIgnoreResponse(ignoreResponseCheckBox.isSelected());
        properties.setQueueOnResponseTimeout(queueOnResponseTimeoutYesRadio.isSelected());
        properties.setDataTypeBinary(dataTypeBinaryRadio.isSelected());
        properties.setCharsetEncoding(parent.getSelectedEncodingForConnector(charsetEncodingComboBox));
        properties.setTemplate(templateTextArea.getText());

        return properties;
    }

    @Override
    public void setProperties(ConnectorProperties properties) {
        MultiEndpointTcpDispatcherProperties props = (MultiEndpointTcpDispatcherProperties) properties;

        TransmissionModeProperties modeProps = props.getTransmissionModeProperties();
        String name = "Basic TCP";
        if (modeProps != null && LoadedExtensions.getInstance().getTransmissionModePlugins().containsKey(modeProps.getPluginPointName())) {
            name = modeProps.getPluginPointName();
        }
        modeLock = true;
        transmissionModeComboBox.setSelectedItem(name);
        transmissionModeComboBoxActionPerformed();
        modeLock = false;
        selectedMode = name;
        if (transmissionModeProvider != null) {
            transmissionModeProvider.setProperties(modeProps);
        }

        writeEndpointsToTable(props.getEndpoints());
        strategyComboBox.setSelectedItem(props.getStrategy() == null ? Strategy.FAILOVER.name() : props.getStrategy().name());
        failureThresholdField.setText(String.valueOf(props.getFailureThreshold()));
        cooldownField.setText(String.valueOf(props.getCooldownMillis()));

        responseTimeoutField.setText(props.getResponseTimeout());
        sendTimeoutField.setText(props.getSendTimeout());
        bufferSizeField.setText(props.getBufferSize());
        if (props.isKeepConnectionOpen()) {
            keepConnectionOpenYesRadio.setSelected(true);
        } else {
            keepConnectionOpenNoRadio.setSelected(true);
        }
        keepConnectionOpenActionPerformed();
        ignoreResponseCheckBox.setSelected(props.isIgnoreResponse());
        if (props.isQueueOnResponseTimeout()) {
            queueOnResponseTimeoutYesRadio.setSelected(true);
        } else {
            queueOnResponseTimeoutNoRadio.setSelected(true);
        }

        if (props.isDataTypeBinary()) {
            dataTypeBinaryRadio.setSelected(true);
        } else {
            dataTypeTextRadio.setSelected(true);
        }
        dataTypeRadioActionPerformed();

        parent.setPreviousSelectedEncodingForConnector(charsetEncodingComboBox, props.getCharsetEncoding());
        templateTextArea.setText(props.getTemplate());
    }

    @Override
    public ConnectorProperties getDefaults() {
        MultiEndpointTcpDispatcherProperties props = new MultiEndpointTcpDispatcherProperties();
        if (defaultProvider != null) {
            props.setTransmissionModeProperties(defaultProvider.getDefaultProperties());
        }
        return props;
    }

    @Override
    public boolean checkProperties(ConnectorProperties properties, boolean highlight) {
        MultiEndpointTcpDispatcherProperties props = (MultiEndpointTcpDispatcherProperties) properties;
        boolean valid = true;

        if (transmissionModeProvider != null
                && !transmissionModeProvider.checkProperties(transmissionModeProvider.getProperties(), highlight)) {
            valid = false;
        }

        // >= 1 enabled endpoint; ports 1..65535 (numeric literals only); host non-blank.
        List<Endpoint> endpoints = props.getEndpoints();
        int enabledCount = 0;
        if (endpoints != null) {
            for (Endpoint ep : endpoints) {
                if (ep.isEnabled()) {
                    enabledCount++;
                }
                if (StringUtils.isBlank(ep.getHost())) {
                    valid = false;
                }
                String port = ep.getPort();
                // Skip range validation for templated ports (contain "${...}").
                if (port == null || (!port.contains("${") && !isValidPort(port))) {
                    valid = false;
                }
            }
        }
        if (enabledCount < 1) {
            valid = false;
            if (highlight) {
                endpointTable.setBackground(UIConstants.INVALID_COLOR);
            }
        } else {
            endpointTable.setBackground(null);
        }

        if (props.getFailureThreshold() < 1) {
            valid = false;
            if (highlight) {
                failureThresholdField.setBackground(UIConstants.INVALID_COLOR);
            }
        }
        if (props.getCooldownMillis() < 0) {
            valid = false;
            if (highlight) {
                cooldownField.setBackground(UIConstants.INVALID_COLOR);
            }
        }
        if (StringUtils.isBlank(responseTimeoutField.getText())) {
            valid = false;
            if (highlight) {
                responseTimeoutField.setBackground(UIConstants.INVALID_COLOR);
            }
        }
        if (StringUtils.isBlank(bufferSizeField.getText())) {
            valid = false;
            if (highlight) {
                bufferSizeField.setBackground(UIConstants.INVALID_COLOR);
            }
        }
        if (StringUtils.isBlank(templateTextArea.getText())) {
            valid = false;
            if (highlight) {
                templateTextArea.setBackground(UIConstants.INVALID_COLOR);
            }
        }

        // STICKY requires the destination queue to use exactly 1 thread. Read the real queue settings
        // from the fully-assembled connector properties (ConnectorPanel fills the queue tab in).
        if (props.getStrategy() == Strategy.STICKY) {
            int threadCount = 1;
            try {
                ConnectorProperties filled = getFilledProperties();
                if (filled instanceof MultiEndpointTcpDispatcherProperties) {
                    DestinationConnectorProperties dcp = ((MultiEndpointTcpDispatcherProperties) filled).getDestinationConnectorProperties();
                    threadCount = dcp.getThreadCount();
                    if (!dcp.isQueueEnabled()) {
                        parent.alertWarning(parent, "The destination queue is disabled. The Multi-Endpoint TCP Sender's \"never drop, queue instead\" behavior requires the queue to be enabled.");
                    }
                }
            } catch (Exception e) {
                // If the queue settings can't be read here, the server onDeploy enforces the rule.
            }
            if (threadCount != 1) {
                valid = false;
                if (highlight) {
                    strategyComboBox.setBackground(UIConstants.INVALID_COLOR);
                }
                parent.alertWarning(parent, "STICKY strategy requires the destination queue to use exactly 1 thread (currently " + threadCount + "). Set Queue Threads to 1 in the destination's queue settings.");
            } else {
                strategyComboBox.setBackground(null);
            }
        } else {
            strategyComboBox.setBackground(null);
        }

        return valid;
    }

    @Override
    public void resetInvalidProperties() {
        if (transmissionModeProvider != null) {
            transmissionModeProvider.resetInvalidProperties();
        }
        endpointTable.setBackground(null);
        strategyComboBox.setBackground(null);
        failureThresholdField.setBackground(null);
        cooldownField.setBackground(null);
        responseTimeoutField.setBackground(null);
        bufferSizeField.setBackground(null);
        templateTextArea.setBackground(null);
    }

    @Override
    public void actionPerformed(ActionEvent evt) {
        if (evt.getSource().equals(transmissionModeProvider)) {
            if (evt.getActionCommand().equals(TransmissionModeClientProvider.CHANGE_SAMPLE_LABEL_COMMAND)) {
                sampleLabel.setText(transmissionModeProvider.getSampleLabel());
            } else if (evt.getActionCommand().equals(TransmissionModeClientProvider.CHANGE_SAMPLE_VALUE_COMMAND)) {
                sampleValue.setText(transmissionModeProvider.getSampleValue());
            }
        }
    }

    private static boolean isValidPort(String port) {
        int p = NumberUtils.toInt(port, -1);
        return p >= 1 && p <= 65535;
    }

    private List<Endpoint> readEndpointsFromTable() {
        List<Endpoint> endpoints = new ArrayList<Endpoint>();
        for (int row = 0; row < endpointModel.getRowCount(); row++) {
            String host = String.valueOf(getCell(row, 0, ""));
            String port = String.valueOf(getCell(row, 1, ""));
            boolean enabled = Boolean.TRUE.equals(getCell(row, 2, Boolean.TRUE));
            int priority = NumberUtils.toInt(String.valueOf(getCell(row, 3, "0")), 0);
            String notes = String.valueOf(getCell(row, 4, ""));
            endpoints.add(new Endpoint(host, port, enabled, priority, notes));
        }
        return endpoints;
    }

    private Object getCell(int row, int col, Object fallback) {
        Object value = endpointModel.getValueAt(row, col);
        return value == null ? fallback : value;
    }

    private void writeEndpointsToTable(List<Endpoint> endpoints) {
        endpointModel.setRowCount(0);
        if (endpoints != null) {
            for (Endpoint ep : endpoints) {
                endpointModel.addRow(new Object[] { ep.getHost(), ep.getPort(), ep.isEnabled(), ep.getPriority(), ep.getNotes() });
            }
        }
    }

    // @formatter:off
    private void initComponents() {
        transmissionModeComboBox = new MirthComboBox();
        transmissionModeComboBox.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent evt) { transmissionModeComboBoxActionPerformed(); }
        });
        sampleLabel = new JLabel("Sample Frame:");
        sampleValue = new JLabel();
        sampleValue.setForeground(new Color(153, 153, 153));
        sampleValue.setEnabled(false);
        settingsPlaceHolder = new JPanel();

        endpointModel = new DefaultTableModel(ENDPOINT_COLUMNS, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 2) return Boolean.class;
                if (columnIndex == 3) return Integer.class;
                return String.class;
            }
        };
        endpointTable = new MirthTable();
        endpointTable.setModel(endpointModel);
        endpointTable.setToolTipText("<html>Destinations to send to, tried in <b>priority</b> order (lower number = higher priority; ties broken by row order).<br>"
                + "<b>Host</b> and <b>Port</b> accept Velocity variables. Uncheck <b>Enabled</b> to keep a row on file without using it. <b>Notes</b> is free text for operators.</html>");
        // Match the styling of other OIE connector tables (striping, row height, single-select, no reorder).
        endpointTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        endpointTable.setRowHeight(UIConstants.ROW_HEIGHT);
        endpointTable.setFocusable(true);
        endpointTable.setSortable(false);
        endpointTable.setOpaque(true);
        endpointTable.setDragEnabled(false);
        endpointTable.getTableHeader().setReorderingAllowed(false);
        endpointTable.setShowGrid(true, true);
        endpointTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        endpointTable.setRowSelectionAllowed(true);
        endpointTable.setCustomEditorControls(true);
        if (Preferences.userNodeForPackage(Mirth.class).getBoolean("highlightRows", true)) {
            endpointTable.setHighlighters(HighlighterFactory.createAlternateStriping(UIConstants.HIGHLIGHTER_COLOR, UIConstants.BACKGROUND_COLOR));
        }
        // Keep the checkbox/number columns compact; Host and Notes take the remaining width.
        endpointTable.getColumnModel().getColumn(1).setMaxWidth(90);   // Port
        endpointTable.getColumnModel().getColumn(2).setMaxWidth(70);   // Enabled
        endpointTable.getColumnModel().getColumn(3).setMaxWidth(70);   // Priority

        addButton = new JButton("Add");
        addButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent evt) {
                endpointModel.addRow(new Object[] { "127.0.0.1", "6660", Boolean.TRUE, Integer.valueOf(0), "" });
            }
        });
        removeButton = new JButton("Remove");
        removeButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent evt) {
                int row = endpointTable.getSelectedRow();
                if (row >= 0) {
                    endpointModel.removeRow(endpointTable.convertRowIndexToModel(row));
                }
            }
        });
        upButton = new JButton("Up");
        upButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent evt) { moveRow(-1); }
        });
        downButton = new JButton("Down");
        downButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent evt) { moveRow(1); }
        });

        transmissionModeComboBox.setToolTipText("Framing / transmission mode. MLLP is standard for HL7 v2.x (VT … FS CR).");

        strategyComboBox = new JComboBox<String>(new DefaultComboBoxModel<String>(STRATEGY_ITEMS));
        strategyComboBox.setToolTipText("<html><b>FAILOVER</b>: always use the highest-priority reachable endpoint; auto-fails back when it recovers.<br>"
                + "<b>STICKY</b>: keep using one endpoint until it fails, then move to the next and stay (requires the destination queue set to 1 thread).</html>");

        failureThresholdField = new MirthTextField();
        failureThresholdField.setToolTipText("Consecutive connect failures before an endpoint is marked down and skipped.");
        cooldownField = new MirthTextField();
        cooldownField.setToolTipText("How long (ms) a down endpoint is skipped before a single probe is allowed. 0 = probe on every attempt.");
        responseTimeoutField = new MirthTextField();
        responseTimeoutField.setToolTipText("<html>Milliseconds to wait for a response (ACK) before giving up.<br>"
                + "Also the effective failover delay per unreachable endpoint — there is no separate connect timeout, so keep it modest.</html>");
        sendTimeoutField = new MirthTextField();
        sendTimeoutField.setToolTipText("Milliseconds an idle kept-open socket stays open before it is closed. Applies only with Keep Connection Open = Yes.");
        bufferSizeField = new MirthTextField();
        bufferSizeField.setToolTipText("Send buffer size, in bytes.");

        ButtonGroup keepOpenGroup = new ButtonGroup();
        keepConnectionOpenYesRadio = new MirthRadioButton("Yes");
        keepConnectionOpenNoRadio = new MirthRadioButton("No");
        keepConnectionOpenYesRadio.setBackground(Color.WHITE);
        keepConnectionOpenNoRadio.setBackground(Color.WHITE);
        keepConnectionOpenNoRadio.setSelected(true);
        keepOpenGroup.add(keepConnectionOpenYesRadio);
        keepOpenGroup.add(keepConnectionOpenNoRadio);
        String keepOpenTip = "Reuse the socket across messages instead of reconnecting for each one.";
        keepConnectionOpenYesRadio.setToolTipText(keepOpenTip);
        keepConnectionOpenNoRadio.setToolTipText(keepOpenTip);
        ActionListener keepOpenListener = new ActionListener() {
            public void actionPerformed(ActionEvent evt) { keepConnectionOpenActionPerformed(); }
        };
        keepConnectionOpenYesRadio.addActionListener(keepOpenListener);
        keepConnectionOpenNoRadio.addActionListener(keepOpenListener);

        ignoreResponseCheckBox = new MirthCheckBox("Ignore Response");
        ignoreResponseCheckBox.setBackground(Color.WHITE);
        ignoreResponseCheckBox.setToolTipText("Do not wait for or process a response after sending.");

        ButtonGroup queueOnRtGroup = new ButtonGroup();
        queueOnResponseTimeoutYesRadio = new MirthRadioButton("Yes");
        queueOnResponseTimeoutNoRadio = new MirthRadioButton("No");
        queueOnResponseTimeoutYesRadio.setBackground(Color.WHITE);
        queueOnResponseTimeoutNoRadio.setBackground(Color.WHITE);
        queueOnResponseTimeoutYesRadio.setSelected(true);
        queueOnRtGroup.add(queueOnResponseTimeoutYesRadio);
        queueOnRtGroup.add(queueOnResponseTimeoutNoRadio);
        String queueRtTip = "If a response times out, queue the message for retry instead of erroring it.";
        queueOnResponseTimeoutYesRadio.setToolTipText(queueRtTip);
        queueOnResponseTimeoutNoRadio.setToolTipText(queueRtTip);

        ButtonGroup dataTypeGroup = new ButtonGroup();
        dataTypeTextRadio = new MirthRadioButton("Text");
        dataTypeBinaryRadio = new MirthRadioButton("Binary");
        dataTypeTextRadio.setBackground(Color.WHITE);
        dataTypeBinaryRadio.setBackground(Color.WHITE);
        String dataTypeTip = "Text applies the encoding below; Binary sends raw bytes (Base64-decoded from the template).";
        dataTypeTextRadio.setToolTipText(dataTypeTip);
        dataTypeBinaryRadio.setToolTipText(dataTypeTip);
        dataTypeTextRadio.setSelected(true);
        dataTypeGroup.add(dataTypeTextRadio);
        dataTypeGroup.add(dataTypeBinaryRadio);
        ActionListener dataTypeListener = new ActionListener() {
            public void actionPerformed(ActionEvent evt) { dataTypeRadioActionPerformed(); }
        };
        dataTypeTextRadio.addActionListener(dataTypeListener);
        dataTypeBinaryRadio.addActionListener(dataTypeListener);

        charsetEncodingLabel = new JLabel("Encoding:");
        charsetEncodingComboBox = new MirthComboBox();
        charsetEncodingComboBox.setToolTipText("Character encoding used when Data Type = Text.");

        sendTimeoutLabel = new JLabel("Send Timeout (ms):");

        templateTextArea = new MirthSyntaxTextArea();
        templateTextArea.setToolTipText("The message content to send. Supports Velocity; default ${message.encodedData}.");
        templateTextArea.setBorder(BorderFactory.createEtchedBorder());
    }

    private void initLayout() {
        setBackground(Color.WHITE);
        setLayout(new MigLayout("insets 0, novisualpadding, hidemode 3, gap 12 6, fillx"));

        add(new JLabel("Transmission Mode:"), "right");
        add(transmissionModeComboBox, "h 22!, split 2");
        settingsPlaceHolder.setLayout(new MigLayout("insets 0, novisualpadding, hidemode 3, fill"));
        add(settingsPlaceHolder, "gapbefore 6, h 22!, wrap");
        add(sampleLabel, "right");
        add(sampleValue, "growx, sx, wrap");

        add(new JLabel("Endpoints:"), "right, aligny top");
        JScrollPane scroll = new JScrollPane(endpointTable);
        // The viewport area below the last row would otherwise show Swing's default gray; keep it white.
        scroll.getViewport().setBackground(Color.WHITE);
        add(scroll, "growx, h 120!, split 2, sx");
        JPanel buttons = new JPanel(new MigLayout("insets 0, wrap 1, novisualpadding"));
        buttons.setBackground(Color.WHITE);
        buttons.add(addButton, "growx");
        buttons.add(removeButton, "growx");
        buttons.add(upButton, "growx");
        buttons.add(downButton, "growx");
        add(buttons, "top, wrap");

        add(new JLabel("Strategy:"), "right");
        add(strategyComboBox, "w 160!, wrap");
        add(new JLabel("Failure Threshold:"), "right");
        add(failureThresholdField, "w 75!, wrap");
        add(new JLabel("Cooldown (ms):"), "right");
        add(cooldownField, "w 100!, wrap");

        // Inherited TCP transport fields — laid out in the same order/style as the stock TCP Sender.
        add(new JLabel("Keep Connection Open:"), "right");
        add(keepConnectionOpenYesRadio, "split 2");
        add(keepConnectionOpenNoRadio, "wrap");
        add(sendTimeoutLabel, "right");
        add(sendTimeoutField, "w 75!, wrap");
        add(new JLabel("Buffer Size (bytes):"), "right");
        add(bufferSizeField, "w 75!, wrap");
        add(new JLabel("Response Timeout (ms):"), "right");
        add(responseTimeoutField, "w 75!, split 2");
        add(ignoreResponseCheckBox, "gapbefore 8, wrap");
        add(new JLabel("Queue on Response Timeout:"), "right");
        add(queueOnResponseTimeoutYesRadio, "split 2");
        add(queueOnResponseTimeoutNoRadio, "wrap");

        add(new JLabel("Data Type:"), "right");
        add(dataTypeTextRadio, "split 2");
        add(dataTypeBinaryRadio, "wrap");
        add(charsetEncodingLabel, "right");
        add(charsetEncodingComboBox, "wrap");

        add(new JLabel("Template:"), "right, aligny top");
        add(templateTextArea, "w 425, h 105, grow, span, push");
    }
    // @formatter:on

    private void moveRow(int delta) {
        int viewRow = endpointTable.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        int row = endpointTable.convertRowIndexToModel(viewRow);
        int target = row + delta;
        if (target < 0 || target >= endpointModel.getRowCount()) {
            return;
        }
        endpointModel.moveRow(row, row, target);
        endpointTable.setRowSelectionInterval(target, target);
    }

    private void dataTypeRadioActionPerformed() {
        boolean text = dataTypeTextRadio.isSelected();
        charsetEncodingLabel.setEnabled(text);
        charsetEncodingComboBox.setEnabled(text);
    }

    private void keepConnectionOpenActionPerformed() {
        // Send Timeout only reaps idle *kept-open* sockets, so gray it out otherwise (matches stock TCP).
        boolean keepOpen = keepConnectionOpenYesRadio.isSelected();
        sendTimeoutLabel.setEnabled(keepOpen);
        sendTimeoutField.setEnabled(keepOpen);
    }

    private void transmissionModeComboBoxActionPerformed() {
        String name = (String) transmissionModeComboBox.getSelectedItem();

        if (!modeLock && transmissionModeProvider != null
                && !transmissionModeProvider.getDefaultProperties().equals(transmissionModeProvider.getProperties())) {
            if (JOptionPane.showConfirmDialog(parent, "Are you sure you would like to change the transmission mode and lose all of the current transmission properties?", "Select an Option", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                modeLock = true;
                transmissionModeComboBox.setSelectedItem(selectedMode);
                modeLock = false;
                return;
            }
        }

        selectedMode = name;
        if ("Basic TCP".equals(name)) {
            transmissionModeProvider = new BasicModeClientProvider();
        } else {
            for (TransmissionModePlugin plugin : LoadedExtensions.getInstance().getTransmissionModePlugins().values()) {
                if (plugin.getPluginPointName().equals(name)) {
                    transmissionModeProvider = plugin.createProvider();
                }
            }
        }

        if (transmissionModeProvider != null) {
            transmissionModeProvider.initialize(this);
            settingsPlaceHolder.removeAll();
            settingsPlaceHolder.add(transmissionModeProvider.getSettingsComponent());
            sampleLabel.setText(transmissionModeProvider.getSampleLabel());
            sampleValue.setText(transmissionModeProvider.getSampleValue());
        }
    }
}
