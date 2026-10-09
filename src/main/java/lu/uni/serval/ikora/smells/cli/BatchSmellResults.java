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

import java.util.ArrayList;
import java.util.List;

/** Successful detections and individual failures from one project build. */
public class BatchSmellResults {
    private final List<DetectedTestFile> files = new ArrayList<>();
    private final List<Failure> errors = new ArrayList<>();

    public List<DetectedTestFile> getFiles() {
        return files;
    }

    public List<Failure> getErrors() {
        return errors;
    }

    public static class Failure {
        private final String sourcePath;
        private final String name;
        private final String type;
        private final String message;

        public Failure(RequestedTest request, Throwable error) {
            sourcePath = request.getFile().toString();
            name = request.getName();
            type = error.getClass().getSimpleName();
            message = String.valueOf(error.getMessage())
                    + (error.getCause() == null ? "" : ": " + error.getCause());
        }

        public String getSourcePath() { return sourcePath; }
        public String getName() { return name; }
        public String getType() { return type; }
        public String getMessage() { return message; }
    }
}
