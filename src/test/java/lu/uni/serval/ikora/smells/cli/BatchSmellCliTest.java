package lu.uni.serval.ikora.smells.cli;

/*-
 * #%L
 * Ikora Smells
 * %%
 * Copyright (C) 2020 - 2021 University of Luxembourg
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lu.uni.serval.ikora.core.model.SourceFile;
import lu.uni.serval.ikora.core.model.SourceNode;
import lu.uni.serval.ikora.core.model.TestCase;
import lu.uni.serval.ikora.smells.SmellCheck;
import lu.uni.serval.ikora.smells.SmellConfiguration;
import lu.uni.serval.ikora.smells.SmellDetector;
import lu.uni.serval.ikora.smells.SmellMetric;
import lu.uni.serval.ikora.smells.SmellResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BatchSmellCliTest {
    @TempDir
    Path root;

    private void writeSuite(String file, String name) throws Exception {
        Files.writeString(root.resolve(file), "*** Settings ***\nResource    shared.robot\n"
                + "*** Test Cases ***\n" + name + "\n    Shared Keyword\n");
        Files.writeString(root.resolve("shared.robot"), "*** Keywords ***\nShared Keyword\n    Log    hello\n");
    }

    private List<String> smells(DetectedTestCase test) {
        return test.getSmells().stream().map(s -> s.getSmellType() + "|" + s.getSourcePath()
                + "|" + s.getStartLine() + "|" + s.getEndLine() + "|" + s.getNodeType())
                .sorted().collect(Collectors.toList());
    }

    @Test
    void buildsOnceAndMatchesSeparateInvocations() throws Exception {
        writeSuite("a.robot", "Same name");
        writeSuite("b.robot", "Same name");
        writeSuite("unrequested.robot", "Unrequested");
        final RobotProjectSmellAnalyzer analyzer = new RobotProjectSmellAnalyzer();
        final SmellConfiguration configuration = new SmellConfiguration();
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        final PrintStream original = System.err;
        final BatchSmellResults batch;
        try {
            System.setErr(new PrintStream(diagnostics, true, StandardCharsets.UTF_8));
            batch = analyzer.analyzeBatch(root, configuration, List.of(
                    new RequestedTest(Path.of("a.robot"), "Same name"),
                    new RequestedTest(Path.of("b.robot"), "Same name")));
        } finally {
            System.setErr(original);
        }
        assertEquals(1, diagnostics.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("Ikora CLI: building ")).count());
        assertTrue(batch.getErrors().isEmpty());
        assertEquals(2, batch.getFiles().size());
        for (DetectedTestFile file : batch.getFiles()) {
            final DetectedTestCase single = analyzer.analyze(root, configuration, Optional.of("Same name"),
                    Optional.of(Path.of(file.getPath()))).get(0).getTests().get(0);
            assertEquals(smells(single), smells(file.getTests().get(0)));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void workerFailureDoesNotDiscardOtherTests() throws Exception {
        writeSuite("broken.robot", "Broken");
        writeSuite("good.robot", "Good");
        final Field field = SmellDetector.class.getDeclaredField("smellChecks");
        field.setAccessible(true);
        final Map<SmellMetric.Type, SmellCheck> checks = (Map<SmellMetric.Type, SmellCheck>) field.get(null);
        final SmellCheck original = checks.get(SmellMetric.Type.MISSING_ASSERTION);
        try {
            checks.put(SmellMetric.Type.MISSING_ASSERTION, new SmellCheck() {
                public SmellResult computeMetric(TestCase test, SmellConfiguration configuration) {
                    if (test.getName().equals("Broken")) {
                        throw new IllegalStateException("Intentional worker failure for test");
                    }
                    return original.computeMetric(test, configuration);
                }
                public Set<SourceNode> collectInstances(SourceFile file, SmellConfiguration configuration) {
                    return original.collectInstances(file, configuration);
                }
            });
            final BatchSmellResults results = new RobotProjectSmellAnalyzer().analyzeBatch(root,
                    new SmellConfiguration(), List.of(
                            new RequestedTest(Path.of("broken.robot"), "Broken"),
                            new RequestedTest(Path.of("good.robot"), "Good")));
            assertEquals(1, results.getErrors().size());
            assertEquals("Broken", results.getErrors().get(0).getName());
            assertTrue(results.getErrors().get(0).getMessage().contains("18/19"));
            assertEquals(1, results.getFiles().size());
            assertEquals("Good", results.getFiles().get(0).getTests().get(0).getName());
        } finally {
            checks.put(SmellMetric.Type.MISSING_ASSERTION, original);
        }
    }

    private JsonNode runCli(String document) throws Exception {
        final Path requests = root.resolve("requests.json");
        Files.writeString(requests, document);
        final Method run = RobotSmellCli.class.getDeclaredMethod("run", String[].class, PrintStream.class);
        run.setAccessible(true);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        run.invoke(null, new String[]{root.toString(), "--batch=" + requests},
                new PrintStream(output, true, StandardCharsets.UTF_8));
        return new ObjectMapper().readTree(output.toByteArray());
    }

    @Test
    void reportsMissingTestsAlongsideSuccessAndHandlesQuotedNames() throws Exception {
        writeSuite("suite.robot", "Test A \"GUI\" 001");
        final JsonNode result = runCli("{\"tests\":["
                + "{\"file\":\"suite.robot\",\"name\":\"Test A \\\"GUI\\\" 001\"},"
                + "{\"file\":\"suite.robot\",\"name\":\"Absent\"},"
                + "{\"file\":\"absent.robot\",\"name\":\"Missing file\"}]}");
        assertEquals(1, result.get("files").size());
        assertEquals("Test A \"GUI\" 001", result.get("files").get(0).get("tests").get(0).get("name").asText());
        assertEquals(2, result.get("errors").size());
        assertEquals("Absent", result.get("errors").get(0).get("name").asText());
    }

    @Test
    void rejectsMalformedRequestsAndAllowsEmptyBatch() throws Exception {
        assertEquals(0, runCli("{\"tests\":[]}").get("files").size());
        for (String document : List.of("{}", "{\"tests\":[{\"file\":1,\"name\":\"A\"}]}",
                "{\"tests\":[{\"file\":\"../outside.robot\",\"name\":\"A\"}]}",
                "{\"tests\":[{\"file\":\"a.robot\",\"name\":\"A\"},{\"file\":\"a.robot\",\"name\":\"A\"}]}")) {
            final InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> runCli(document));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
        }
    }
}
