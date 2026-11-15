package io.github.mihirraj.perflens.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The UI is a single HTML file with inline JavaScript and no build step, so nothing else would
 * notice a syntax error: the page would just sit on "Loading...". Uses Node when available.
 */
class UiScriptSyntaxTest {

    @Test
    void inlineScriptParses() throws Exception {
        String html;
        try (InputStream in = new ClassPathResource("perflens/ui/index.html").getInputStream()) {
            html = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
        int start = html.indexOf("<script>");
        int end = html.indexOf("</script>", start);
        assertThat(start).isPositive();
        String script = html.substring(start + "<script>".length(), end);

        Path file = Files.createTempFile("perflens-ui", ".js");
        Files.writeString(file, script);
        Process p;
        try {
            p = new ProcessBuilder("node", "--check", file.toString()).redirectErrorStream(true).start();
        } catch (java.io.IOException noNode) {
            assumeTrue(false, "node not installed; skipping UI syntax check");
            return;
        }
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.exitValue()).as("node --check output:%n%s", output).isZero();
        Files.deleteIfExists(file);
    }
}
