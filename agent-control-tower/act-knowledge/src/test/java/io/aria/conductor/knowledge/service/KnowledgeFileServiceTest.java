package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.model.KnowledgeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeFileServiceTest {

    @TempDir
    Path tempDir;

    KnowledgeFileService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeFileService(tempDir.toString());
    }

    // ---- store / read round trip ----------------------------------------

    @Test
    void storeContent_writesFileUnderBasePathAndReturnsItsPath() {
        String returned = service.storeContent(
                KnowledgeType.PROMPT, "greeting", "v0.1.0", "Hello knowledge");

        Path written = Path.of(returned);
        assertThat(written).exists().isRegularFile();
        assertThat(written.toAbsolutePath().toString())
                .startsWith(tempDir.toAbsolutePath().toString());
        // layout: <base>/<type lowercase>/<name>/<version><ext>
        Path relative = tempDir.relativize(written);
        assertThat(relative.getNameCount()).isEqualTo(3);
        assertThat(relative.getName(0).toString()).isEqualTo("prompt");
        assertThat(relative.getName(1).toString()).isEqualTo("greeting");
        assertThat(relative.getName(2).toString()).isEqualTo("v0.1.0.md");
    }

    @Test
    void readContent_returnsExactlyWhatWasStored_includingUnicode() {
        String content = "# Titel\nUmlaute: äöü — dash, emoji \uD83D\uDE80\nline2";
        service.storeContent(KnowledgeType.GUIDELINE, "style-guide", "v1.0.0", content);

        Optional<String> read = service.readContent(KnowledgeType.GUIDELINE, "style-guide", "v1.0.0");

        assertThat(read).contains(content);
    }

    @Test
    void storeContent_sameCoordinates_overwritesPreviousContent() {
        service.storeContent(KnowledgeType.SCRIPT, "job", "v1.0.0", "print('old')");
        service.storeContent(KnowledgeType.SCRIPT, "job", "v1.0.0", "print('new')");

        assertThat(service.readContent(KnowledgeType.SCRIPT, "job", "v1.0.0"))
                .contains("print('new')");
    }

    @Test
    void storeContent_differentVersions_areIsolatedFiles() {
        service.storeContent(KnowledgeType.PROMPT, "p", "v0.1.0", "one");
        service.storeContent(KnowledgeType.PROMPT, "p", "v0.2.0", "two");

        assertThat(service.readContent(KnowledgeType.PROMPT, "p", "v0.1.0")).contains("one");
        assertThat(service.readContent(KnowledgeType.PROMPT, "p", "v0.2.0")).contains("two");
    }

    // ---- extension decision table -----------------------------------------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "SKILL,     v1.0.0.yaml",
            "SCRIPT,    v1.0.0.py",
            "PROMPT,    v1.0.0.md",
            "TOOL,      v1.0.0.yaml",
            "TEMPLATE,  v1.0.0.md",
            "GUIDELINE, v1.0.0.md",
            "WORKFLOW,  v1.0.0.md"
    })
    void storeContent_appliesTypeSpecificExtension(KnowledgeType type, String expectedFileName) {
        String returned = service.storeContent(type, "n", "v1.0.0", "c");

        assertThat(Path.of(returned).getFileName().toString()).isEqualTo(expectedFileName);
        assertThat(service.readContent(type, "n", "v1.0.0")).contains("c");
    }

    // ---- error / missing paths ---------------------------------------------

    @Test
    void readContent_missingFile_returnsEmptyOptional() {
        assertThat(service.readContent(KnowledgeType.PROMPT, "never-stored", "v9.9.9"))
                .isEmpty();
    }

    @Test
    void storeContent_basePathBlockedByRegularFile_throwsRuntimeException() throws IOException {
        // A regular file where the base directory should be makes createDirectories fail.
        Path blocking = tempDir.resolve("blocked");
        Files.writeString(blocking, "not a directory", StandardCharsets.UTF_8);
        KnowledgeFileService broken = new KnowledgeFileService(blocking.toString());

        assertThatThrownBy(() -> broken.storeContent(KnowledgeType.PROMPT, "x", "v1.0.0", "c"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to store knowledge content")
                .hasCauseInstanceOf(IOException.class);
    }

    // ---- deleteContent -------------------------------------------------------

    @Test
    void deleteContent_removesStoredFile() {
        String returned = service.storeContent(KnowledgeType.TOOL, "t", "v1.0.0", "spec");
        assertThat(Path.of(returned)).exists();

        service.deleteContent(KnowledgeType.TOOL, "t", "v1.0.0");

        assertThat(Path.of(returned)).doesNotExist();
        assertThat(service.readContent(KnowledgeType.TOOL, "t", "v1.0.0")).isEmpty();
    }

    @Test
    void deleteContent_missingFile_doesNotThrow() {
        assertThatCode(() -> service.deleteContent(KnowledgeType.TOOL, "ghost", "v1.0.0"))
                .doesNotThrowAnyException();
    }
}
