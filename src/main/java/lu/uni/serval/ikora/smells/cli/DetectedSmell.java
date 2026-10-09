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

public class DetectedSmell {
    private final String smellType;
    private final String sourcePath;
    private final int startLine;
    private final int endLine;
    private final String nodeType;

    public DetectedSmell(String smellType, String sourcePath, int startLine, int endLine, String nodeType) {
        this.smellType = smellType;
        this.sourcePath = sourcePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.nodeType = nodeType;
    }

    public String getSmellType() {
        return smellType;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public String getNodeType() {
        return nodeType;
    }
}
