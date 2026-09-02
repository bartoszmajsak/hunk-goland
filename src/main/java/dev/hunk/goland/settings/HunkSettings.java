package dev.hunk.goland.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.APP)
@State(name = "HunkSessionSettings", storages = @Storage("hunk-session.xml"))
public final class HunkSettings implements PersistentStateComponent<HunkSettings.Data> {
    public static final class Data {
        public String host = System.getProperty("hunk.session.host", "127.0.0.1");
        public int port = Integer.getInteger("hunk.session.port", 47657);
        public String executable = System.getProperty("hunk.executable", "hunk");
        public String sessionId = "";
        public boolean diagnosticsEnabled = Boolean.getBoolean("hunk.diagnostics");
    }

    private Data data = new Data();

    public static HunkSettings getInstance() {
        return ApplicationManager.getApplication().getService(HunkSettings.class);
    }

    @Override
    public @NotNull Data getState() {
        return data;
    }

    @Override
    public void loadState(@NotNull Data state) {
        data = state;
    }
}
