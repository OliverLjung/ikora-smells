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

import lu.uni.serval.ikora.core.BuildConfiguration;
import lu.uni.serval.ikora.core.builder.BuildResult;
import lu.uni.serval.ikora.core.builder.Builder;
import lu.uni.serval.ikora.core.error.ErrorManager;
import lu.uni.serval.ikora.core.error.UnhandledError;
import lu.uni.serval.ikora.core.model.Project;
import lu.uni.serval.ikora.core.model.SourceFile;
import lu.uni.serval.ikora.core.model.SourceNode;
import lu.uni.serval.ikora.core.model.TestCase;
import lu.uni.serval.ikora.smells.SmellConfiguration;
import lu.uni.serval.ikora.smells.SmellDetector;
import lu.uni.serval.ikora.smells.SmellResult;
import lu.uni.serval.ikora.smells.SmellResults;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class RobotProjectSmellAnalyzer {
    public List<DetectedTestFile> analyze(Path input, SmellConfiguration configuration) throws Exception {
        return analyze(input, configuration, Optional.empty());
    }

    public List<DetectedTestFile> analyze(Path input, SmellConfiguration configuration, Optional<String> testCaseName) throws Exception {
        return analyze(input, configuration, testCaseName, Optional.empty());
    }

    public List<DetectedTestFile> analyze(Path input, SmellConfiguration configuration,
                                        Optional<String> testCaseName, Optional<Path> testFile) throws Exception {
        final Path absoluteInput = input.toAbsolutePath().normalize();
        if (!Files.isDirectory(absoluteInput) && !Files.isRegularFile(absoluteInput)) {
            throw new IllegalArgumentException("Input does not exist or is not a file/directory: " + absoluteInput);
        }
        final Path buildTarget = Files.isRegularFile(absoluteInput) ? absoluteInput.getParent() : absoluteInput;
        final Optional<Path> selectedFile = testFile.map(path ->
                (path.isAbsolute() ? path : buildTarget.resolve(path)).toAbsolutePath().normalize())
                .or(() -> Files.isRegularFile(absoluteInput) ? Optional.of(absoluteInput) : Optional.empty());
        if (selectedFile.isPresent() && !Files.isRegularFile(selectedFile.get())) {
            throw new IllegalArgumentException("Selected test file does not exist: " + selectedFile.get());
        }
        System.err.println("Ikora CLI: building " + buildTarget
                + "; selected file=" + selectedFile.map(Path::toString).orElse("all files")
                + "; selected test=" + testCaseName.orElse("all tests"));
        final BuildResult build = buildProject(buildTarget);
        final List<DetectedTestFile> files = new ArrayList<>();
        final List<String> availableTests = new ArrayList<>();

        for (Project project : build.getProjects()) {
            if (project == null) {
                throw new IllegalStateException("Ikora returned a null project for " + buildTarget);
            }
            final List<SourceFile> sourceFiles = project.getSourceFiles().stream()
                    .filter(this::isRobotFile)
                    .sorted(Comparator.comparing(sourceFile -> sourceFile.getSource().getAbsolutePath()))
                    .collect(Collectors.toList());

            for (SourceFile sourceFile : sourceFiles) {
                final Path sourcePath = Path.of(sourceFile.getSource().getAbsolutePath()).toAbsolutePath().normalize();
                if (selectedFile.isPresent() && !sourcePath.equals(selectedFile.get())) {
                    continue;
                }

                sourceFile.getTestCases().forEach(testCase -> {
                    if (availableTests.size() < 20) {
                        availableTests.add(sourcePath + " :: " + testCase.getName());
                    }
                });

                final List<DetectedTestCase> tests = sourceFile.getTestCases().stream()
                        .filter(testCase -> matchesRequestedTestCase(testCase, testCaseName))
                        .sorted(Comparator.comparing(TestCase::getName))
                        .map(testCase -> analyzeTestCase(testCase, configuration))
                        .collect(Collectors.toList());

                if (!tests.isEmpty()) {
                    files.add(new DetectedTestFile(sourcePath.toString(), tests));
                }
            }
        }

        if (testCaseName.isPresent() && files.isEmpty()) {
            throw new IllegalArgumentException("Test case not found: " + testCaseName.get()
                    + "; build root=" + buildTarget
                    + "; selected file=" + selectedFile.map(Path::toString).orElse("all files")
                    + "; parsed tests (up to 20)=" + availableTests
                    + ". Check the build diagnostics on stderr for parse/import errors.");
        }
        files.sort(Comparator.comparing(DetectedTestFile::getPath));
        return files;
    }

    public BatchSmellResults analyzeBatch(Path input, SmellConfiguration configuration,
                                         List<RequestedTest> requests) throws Exception {
        final Path root = input.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Batch input must be a repository directory: " + root);
        }
        final BatchSmellResults results = new BatchSmellResults();
        if (requests.isEmpty()) {
            return results;
        }
        System.err.println("Ikora CLI: building " + root + "; batch requests=" + requests.size());
        final BuildResult build = buildProject(root);

        // Index once; retain duplicates so ambiguous test names become explicit failures.
        final Map<Path, Map<String, List<TestCase>>> testsByFile = new LinkedHashMap<>();
        for (Project project : build.getProjects()) {
            for (SourceFile file : project.getSourceFiles()) {
                if (!isRobotFile(file)) {
                    continue;
                }
                final Path path = Path.of(file.getSource().getAbsolutePath()).toAbsolutePath().normalize();
                final Map<String, List<TestCase>> tests = testsByFile.computeIfAbsent(path, key -> new LinkedHashMap<>());
                for (TestCase test : file.getTestCases()) {
                    tests.computeIfAbsent(test.getName(), key -> new ArrayList<>()).add(test);
                }
            }
        }

        final Map<Path, List<DetectedTestCase>> successes = new LinkedHashMap<>();
        for (RequestedTest request : requests) {
            final Path path = root.resolve(request.getFile()).normalize();
            final RequestedTest resolved = new RequestedTest(path, request.getName());
            try {
                final Map<String, List<TestCase>> tests = testsByFile.get(path);
                final List<TestCase> matches = tests == null ? null : tests.get(request.getName());
                if (matches == null || matches.size() != 1) {
                    throw new IllegalArgumentException("Expected one parsed test, found "
                            + (matches == null ? 0 : matches.size()) + ": " + path + " :: " + request.getName()
                            + ". Check stderr for parse/import errors.");
                }
                final DetectedTestCase detected = analyzeTestCase(matches.get(0), configuration);
                successes.computeIfAbsent(path, key -> new ArrayList<>()).add(detected);
            } catch (RuntimeException | StackOverflowError error) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Batch analysis interrupted", error);
                }
                System.err.println("Ikora CLI: failed " + path + " :: " + request.getName());
                error.printStackTrace(System.err);
                results.getErrors().add(new BatchSmellResults.Failure(resolved, error));
            }
        }
        successes.forEach((path, tests) -> results.getFiles().add(new DetectedTestFile(path.toString(), tests)));
        return results;
    }

    private BuildResult buildProject(Path root) throws Exception {
        final BuildResult build = Builder.build(root.toFile(), new BuildConfiguration(), true);
        if (build == null || build.getProjects() == null || build.getProjects().isEmpty()) {
            throw new IllegalStateException("Ikora did not build any projects from " + root);
        }
        reportBuildErrors(build);
        for (Project project : build.getProjects()) {
            if (project == null) {
                throw new IllegalStateException("Ikora returned a null project for " + root);
            }
        }
        return build;
    }

    private void reportBuildErrors(BuildResult build) {
        final ErrorManager errors = build.getErrors();
        if (errors == null || errors.isEmpty()) {
            return;
        }
        System.err.println("Ikora reported build errors; unresolved imports or keywords may affect the results.");
        for (lu.uni.serval.ikora.core.error.Error error : errors.inMemory().getAll()) {
            reportError("in-memory/build", error);
        }
        for (Project project : build.getProjects()) {
            if (project == null) {
                continue;
            }
            for (SourceFile sourceFile : project.getSourceFiles()) {
                for (lu.uni.serval.ikora.core.error.Error error : errors.in(sourceFile.getSource()).getAll()) {
                    reportError(sourceFile.getSource().getAbsolutePath(), error);
                }
            }
        }
    }

    private void reportError(String source, lu.uni.serval.ikora.core.error.Error error) {
        System.err.println(source + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
        if (error instanceof UnhandledError) {
            ((UnhandledError) error).getException().printStackTrace(System.err);
        }
    }

    private DetectedTestCase analyzeTestCase(TestCase testCase, SmellConfiguration configuration) {
        try {
            System.err.println("Ikora CLI: computing smells for " + testCase.getName()
                    + " in " + testCase.getSource().getAbsolutePath());
            final SmellDetector detector = SmellDetector.all();
            final SmellResults results = detector.computeMetrics(testCase, configuration);
            if (results.getNumberMetrics() != detector.getNumberMetrics()) {
                throw new IllegalStateException("Incomplete smell analysis for " + testCase.getName()
                        + " in " + testCase.getSource().getAbsolutePath()
                        + ": completed " + results.getNumberMetrics() + "/" + detector.getNumberMetrics()
                        + " metrics. Check stderr for smell-check worker exceptions.");
            }
            final List<DetectedSmell> smells = new ArrayList<>();

            for (SmellResult result : results) {
                final Set<SourceNode> nodes = result.getNodes();
                for (SourceNode node : nodes) {
                    final Path sourcePath = Path.of(node.getSource().getAbsolutePath()).toAbsolutePath().normalize();
                    smells.add(new DetectedSmell(
                            result.getType().name(),
                            sourcePath.toString(),
                            SourceNodeLocation.getStartLine(node),
                            SourceNodeLocation.getEndLine(node),
                            node.getClass().getSimpleName()
                    ));
                }
            }

            smells.sort(Comparator
                    .comparing(DetectedSmell::getSourcePath)
                    .thenComparingInt(DetectedSmell::getStartLine)
                    .thenComparingInt(DetectedSmell::getEndLine)
                    .thenComparing(DetectedSmell::getSmellType));

            final Path sourcePath = Path.of(testCase.getSource().getAbsolutePath()).toAbsolutePath().normalize();
            return new DetectedTestCase(
                    testCase.getName(),
                    sourcePath.toString(),
                    SourceNodeLocation.getStartLine(testCase),
                    SourceNodeLocation.getEndLine(testCase),
                    smells
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while computing smells for test case " + testCase.getName(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed smell analysis for " + testCase.getName()
                    + " in " + testCase.getSource().getAbsolutePath(), e);
        }
    }

    private boolean isRobotFile(SourceFile sourceFile) {
        final String fileName = sourceFile.getSource().getName();
        return fileName != null && fileName.endsWith(".robot");
    }

    private boolean matchesRequestedTestCase(TestCase testCase, Optional<String> testCaseName) {
        return !testCaseName.isPresent() || testCase.getName().equals(testCaseName.get());
    }
}
