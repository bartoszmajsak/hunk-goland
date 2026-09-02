package dev.hunk.goland.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.Locale;

public final class HunkConfigurable implements Configurable {
    private JBTextField host;
    private JBTextField port;
    private JBTextField executable;
    private JBTextField sessionId;
    private JBCheckBox diagnosticsEnabled;
    private JPanel panel;

    @Override
    public String getDisplayName() {
        return "Hunk Session";
    }

    @Override
    public JComponent createComponent() {
        host = new JBTextField();
        port = new JBTextField();
        executable = new JBTextField();
        sessionId = new JBTextField();
        diagnosticsEnabled = new JBCheckBox(
                "Enable diagnostic logging (never logs comment bodies or tokens)"
        );
        diagnosticsEnabled.setToolTipText(
                "Adds Hunk session requests, snapshot counts, persistence polling, and PTY stages "
                        + "to GoLand's idea.log."
        );
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Daemon host:"), host)
                .addLabeledComponent(new JBLabel("Daemon port:"), port)
                .addLabeledComponent(new JBLabel("Hunk executable:"), executable)
                .addLabeledComponent(new JBLabel("Preferred session ID (optional):"), sessionId)
                .addComponent(diagnosticsEnabled)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        var state = HunkSettings.getInstance().getState();
        return !host.getText().trim().equals(state.host)
                || !port.getText().trim().equals(Integer.toString(state.port))
                || !executable.getText().trim().equals(state.executable)
                || !sessionId.getText().trim().equals(state.sessionId)
                || diagnosticsEnabled.isSelected() != state.diagnosticsEnabled;
    }

    @Override
    public void apply() throws ConfigurationException {
        var normalizedHost = host.getText().trim();
        if (!isLoopbackHost(normalizedHost)) {
            throw new ConfigurationException("Hunk Session only connects to a loopback host.");
        }
        final int normalizedPort;
        try {
            normalizedPort = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException error) {
            throw new ConfigurationException("Daemon port must be a number.");
        }
        if (normalizedPort < 1 || normalizedPort > 65535) {
            throw new ConfigurationException("Daemon port must be between 1 and 65535.");
        }

        var state = HunkSettings.getInstance().getState();
        state.host = normalizedHost;
        state.port = normalizedPort;
        state.executable = executable.getText().trim();
        state.sessionId = sessionId.getText().trim();
        state.diagnosticsEnabled = diagnosticsEnabled.isSelected();
    }

    @Override
    public void reset() {
        if (host == null) {
            return;
        }
        var state = HunkSettings.getInstance().getState();
        host.setText(state.host);
        port.setText(Integer.toString(state.port));
        executable.setText(state.executable);
        sessionId.setText(state.sessionId);
        diagnosticsEnabled.setSelected(state.diagnosticsEnabled);
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        host = null;
        port = null;
        executable = null;
        sessionId = null;
        diagnosticsEnabled = null;
    }

    private static boolean isLoopbackHost(String value) {
        var host = value.toLowerCase(Locale.ROOT);
        return host.startsWith("127.") || switch (host) {
            case "localhost", "::1", "[::1]" -> true;
            default -> false;
        };
    }
}
