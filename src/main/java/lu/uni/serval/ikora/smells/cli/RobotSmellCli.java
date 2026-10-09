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

import lu.uni.serval.ikora.smells.SmellConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RobotSmellCli {
    public static void main(String[] args) {
        final PrintStream originalOutput = System.out;
        final PrintStream originalError = System.err;
        final PrintStream jsonOutput = new PrintStream(originalOutput, true, StandardCharsets.UTF_8);
        System.setErr(new PrintStream(originalError, true, StandardCharsets.UTF_8));
        // Builder/libraries may write diagnostic text to stdout. Keep JSON separate.
        System.setOut(System.err);
        int exitCode = 0;
        try {
            run(args, jsonOutput);
        } catch (Exception | StackOverflowError e) {
            System.err.println("Ikora CLI failed: " + e);
            e.printStackTrace(System.err);
            jsonOutput.println("{\"files\":[],\"error\":{\"type\":\""
                    + escapeJson(e.getClass().getSimpleName()) + "\",\"message\":\""
                    + escapeJson(String.valueOf(e.getMessage())) + "\"}}");
            exitCode = 1;
        } finally {
            System.setOut(originalOutput);
            System.setErr(originalError);
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void run(String[] args, PrintStream jsonOutput) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException(usage());
        }

        final Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Optional<String> testCaseName = Optional.empty();
        Optional<Path> testFile = Optional.empty();
        Optional<Path> batchFile = Optional.empty();
        for (int index = 1; index < args.length; index++) {
            final String argument = args[index];
            if (argument.startsWith("--testcase=") && argument.length() > "--testcase=".length()
                    && !testCaseName.isPresent()) {
                testCaseName = Optional.of(argument.substring("--testcase=".length()));
            } else if (argument.startsWith("--file=") && argument.length() > "--file=".length()
                    && !testFile.isPresent()) {
                testFile = Optional.of(Path.of(argument.substring("--file=".length())));
            } else if (argument.startsWith("--batch=") && argument.length() > "--batch=".length()
                    && !batchFile.isPresent()) {
                batchFile = Optional.of(Path.of(argument.substring("--batch=".length())));
            } else {
                throw new IllegalArgumentException("Invalid or duplicate option: " + argument + ". " + usage());
            }
        }
        final RobotProjectSmellAnalyzer analyzer = new RobotProjectSmellAnalyzer();
        final SmellConfiguration configuration = new SmellConfiguration();
        final List<DetectedTestFile> files;
        final List<BatchSmellResults.Failure> errors;
        if (batchFile.isPresent()) {
            if (testCaseName.isPresent() || testFile.isPresent()) {
                throw new IllegalArgumentException("--batch cannot be combined with --testcase or --file");
            }
            final List<RequestedTest> requests = readRequests(batchFile.get(), input);
            final BatchSmellResults batch = analyzer.analyzeBatch(input, configuration, requests);
            files = batch.getFiles();
            errors = batch.getErrors();
        } else {
            files = analyzer.analyze(input, configuration, testCaseName, testFile);
            errors = new ArrayList<>();
        }

        final StringBuilder json = new StringBuilder();
        json.append("{\"input\":\"")
                .append(escapeJson(input.toString()))
                .append("\",\"files\":[");

        for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
            final DetectedTestFile file = files.get(fileIndex);

            if (fileIndex > 0) {
                json.append(",");
            }

            json.append("{\"path\":\"")
                    .append(escapeJson(file.getPath()))
                    .append("\",\"tests\":[");

            for (int testIndex = 0; testIndex < file.getTests().size(); testIndex++) {
                final DetectedTestCase test = file.getTests().get(testIndex);
                if (testIndex > 0) {
                    json.append(",");
                }

                json.append("{\"name\":\"")
                        .append(escapeJson(test.getName()))
                        .append("\",\"sourcePath\":\"")
                        .append(escapeJson(test.getSourcePath()))
                        .append("\",\"startLine\":")
                        .append(test.getStartLine())
                        .append(",\"endLine\":")
                        .append(test.getEndLine())
                        .append(",\"smells\":[");

                for (int smellIndex = 0; smellIndex < test.getSmells().size(); smellIndex++) {
                    final DetectedSmell smell = test.getSmells().get(smellIndex);
                    if (smellIndex > 0) {
                        json.append(",");
                    }

                    json.append("{\"type\":\"")
                            .append(escapeJson(smell.getSmellType()))
                            .append("\",\"sourcePath\":\"")
                            .append(escapeJson(smell.getSourcePath()))
                            .append("\",\"startLine\":")
                            .append(smell.getStartLine())
                            .append(",\"endLine\":")
                            .append(smell.getEndLine())
                            .append(",\"nodeType\":\"")
                            .append(escapeJson(smell.getNodeType()))
                            .append("\"}");
                }

                json.append("]}");
            }

            json.append("]}");
        }

        json.append("]");
        if (batchFile.isPresent()) {
            // Exit zero means the batch completed; individual failures live in errors.
            json.append(",\"errors\":").append(new ObjectMapper().writeValueAsString(errors));
        }
        json.append("}");
        jsonOutput.println(json);
    }

    private static List<RequestedTest> readRequests(Path file, Path root) throws Exception {
        final JsonNode document = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(file.toFile());
        if (document == null || !document.isObject() || !document.path("tests").isArray()) {
            throw new IllegalArgumentException("Batch JSON must contain a tests array");
        }
        final List<RequestedTest> requests = new ArrayList<>();
        final Map<Path, Set<String>> seen = new HashMap<>();
        for (JsonNode test : document.get("tests")) {
            if (!test.isObject() || !test.path("file").isTextual() || test.path("file").asText().isEmpty()
                    || !test.path("name").isTextual() || test.path("name").asText().isEmpty()) {
                throw new IllegalArgumentException("Each batch test requires nonempty string file and name fields");
            }
            final Path relative = Path.of(test.get("file").asText());
            final Path resolved = root.resolve(relative).normalize();
            if (relative.isAbsolute() || !resolved.startsWith(root)) {
                throw new IllegalArgumentException("Batch file must be relative to the input directory: " + relative);
            }
            final String name = test.get("name").asText();
            if (!seen.computeIfAbsent(resolved, key -> new HashSet<>()).add(name)) {
                throw new IllegalArgumentException("Duplicate batch request: " + relative + " :: " + name);
            }
            requests.add(new RequestedTest(resolved, name));
        }
        return requests;
    }

    private static String escapeJson(String value) {
        final StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            switch (character) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\r': escaped.append("\\r"); break;
                case '\n': escaped.append("\\n"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }

    private static String usage() {
        return "Usage: java -jar ikora-smells.jar <robot-file-or-directory>"
                + " [--testcase=\"test case name\"] [--file=\"path relative to input directory\"]"
                + " OR java -jar ikora-smells.jar <repository-directory> --batch=requests.json";
    }
}
