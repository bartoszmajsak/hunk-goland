package dev.hunk.goland.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PathMappingTest {
    @Test
    void mapsAFileToHunksSlashSeparatedRepositoryPath() {
        assertThat(PathMapping.repositoryRelative(Path.of("/repo"), Path.of("/repo/src/main.go")))
                .isEqualTo("src/main.go");
    }

    @Test
    void refusesFilesOutsideTheRepository() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PathMapping.repositoryRelative(
                        Path.of("/repo"),
                        Path.of("/other/main.go")
                ));
    }
}
