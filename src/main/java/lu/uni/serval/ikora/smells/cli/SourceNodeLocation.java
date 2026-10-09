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

import lu.uni.serval.ikora.core.model.SourceNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

final class SourceNodeLocation {
    private static final String[] START_LINE_METHODS = {"getStartLine", "getLine", "getLineNumber"};
    private static final String[] END_LINE_METHODS = {"getEndLine", "getLastLine", "getStopLine", "getLine", "getLineNumber"};
    private static final String[] START_NESTED_LOCATION_METHODS = {"getRange", "getLocation", "getPosition", "getToken", "getDefinitionToken", "getStart"};
    private static final String[] END_NESTED_LOCATION_METHODS = {"getRange", "getLocation", "getPosition", "getToken", "getDefinitionToken", "getEnd"};

    private SourceNodeLocation() {
    }

    static int getStartLine(SourceNode node) {
        final Optional<Integer> directLine = findLine(node, START_LINE_METHODS);
        if (directLine.isPresent()) {
            return directLine.get();
        }

        final Optional<Integer> nestedLine = findLine(node, START_NESTED_LOCATION_METHODS, START_LINE_METHODS);
        if (nestedLine.isPresent()) {
            return nestedLine.get();
        }

        return getEndLine(node);
    }

    static int getEndLine(SourceNode node) {
        final Optional<Integer> directLine = findLine(node, END_LINE_METHODS);
        if (directLine.isPresent()) {
            return directLine.get();
        }

        final Optional<Integer> nestedLine = findLine(node, END_NESTED_LOCATION_METHODS, END_LINE_METHODS);
        if (nestedLine.isPresent()) {
            return nestedLine.get();
        }

        return findLine(node, START_LINE_METHODS).orElse(-1);
    }

    private static Optional<Integer> findLine(Object target, String[] methodNames) {
        if (target == null) {
            return Optional.empty();
        }

        for (String methodName : methodNames) {
            final Optional<Integer> value = invokeForLine(target, methodName);
            if (value.isPresent()) {
                return value;
            }
        }

        return Optional.empty();
    }

    private static Optional<Integer> findLine(Object target, String[] nestedMethodNames, String[] lineMethodNames) {
        if (target == null) {
            return Optional.empty();
        }

        for (String methodName : nestedMethodNames) {
            final Optional<Object> nested = invoke(target, methodName);
            if (nested.isPresent()) {
                final Optional<Integer> value = findLine(unwrapOptional(nested.get()), lineMethodNames);
                if (value.isPresent()) {
                    return value;
                }

                final Optional<Integer> recursiveValue = findLine(unwrapOptional(nested.get()), nestedMethodNames, lineMethodNames);
                if (recursiveValue.isPresent()) {
                    return recursiveValue;
                }

                final Optional<Integer> tokenValue = extractTokenLine(unwrapOptional(nested.get()));
                if (tokenValue.isPresent()) {
                    return tokenValue;
                }
            }
        }

        return Optional.empty();
    }

    private static Optional<Integer> invokeForLine(Object target, String methodName) {
        final Optional<Object> value = invoke(target, methodName);
        if (!value.isPresent()) {
            return Optional.empty();
        }

        final Object resolvedValue = unwrapOptional(value.get());
        if (resolvedValue instanceof Number) {
            return Optional.of(((Number) resolvedValue).intValue());
        }

        return Optional.empty();
    }

    private static Optional<Object> invoke(Object target, String methodName) {
        try {
            final Method method = target.getClass().getMethod(methodName);
            return Optional.ofNullable(method.invoke(target));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            return Optional.empty();
        }
    }

    private static Object unwrapOptional(Object value) {
        if (value instanceof Optional) {
            return ((Optional<?>) value).orElse(null);
        }

        return value;
    }

    private static Optional<Integer> extractTokenLine(Object value) {
        final Optional<Object> nestedToken = invoke(value, "getDefinitionToken");
        if (!nestedToken.isPresent()) {
            return Optional.empty();
        }

        return findLine(unwrapOptional(nestedToken.get()), START_LINE_METHODS);
    }
}
