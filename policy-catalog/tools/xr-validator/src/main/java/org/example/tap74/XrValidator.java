package org.example.tap74;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Validates the TAP 7.4 catalog exactly as it is consumed by the reconciler. */
public final class XrValidator {
    private static final Path REPO_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path POLICIES_DIR = REPO_ROOT.resolve("src/main/xregistry/policies");
    private static final Path COMPANY_SCHEMAS_DIR = REPO_ROOT.resolve("src/main/xregistry/schemas");
    private static final Path DATASPACE_SCHEMAS_DIR = REPO_ROOT.resolve("dataspace/src/main/xregistry/schemas");
    private static final Path INVALID_FIXTURES_DIR = REPO_ROOT.resolve("tests/fixtures/invalid");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern VERSIONED_FILE = Pattern.compile("^(?<coordinate>.+)\\.(?<major>\\d+)\\.(?<minor>\\d+)$");

    private XrValidator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            printUsage();
            System.exit(1);
        }
        switch (args[0]) {
            case "validate" -> validateCatalog();
            case "semantic" -> checkVersionImmutability();
            case "schema-compat" -> checkSchemaCompatibility();
            default -> {
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void printUsage() {
        System.out.println("Usage: xr-validator <validate|semantic|schema-compat>");
    }

    private static void validateCatalog() throws Exception {
        var policies = listJsonFiles(POLICIES_DIR);
        var companySchemas = loadSchemas(COMPANY_SCHEMAS_DIR);
        var dataspaceSchemas = loadSchemas(DATASPACE_SCHEMAS_DIR);
        require(!policies.isEmpty(), "At least one policy file is required");
        require(!companySchemas.isEmpty(), "At least one company schema file is required");
        require(!dataspaceSchemas.isEmpty(), "At least one dataspace schema file is required");

        var policyIds = new TreeSet<String>();
        for (var policyFile : policies) {
            var policy = readJson(policyFile);
            validatePolicy(policyFile, policy, dataspaceSchemas, companySchemas, true);
            require(policyIds.add(text(policy, "policyDefinitionId")), "Duplicate policyDefinitionId: " + text(policy, "policyDefinitionId"));
        }
        validateNegativeFixtures(dataspaceSchemas, companySchemas);
        System.out.printf("Catalog validation completed successfully. policies=%d tier1Schemas=%d tier2Schemas=%d fixtures=%d at %s%n",
                policies.size(), dataspaceSchemas.size(), companySchemas.size(), listJsonFiles(INVALID_FIXTURES_DIR).size(), Instant.now());
    }

    private static List<SchemaDocument> loadSchemas(Path directory) throws IOException {
        var schemas = new ArrayList<SchemaDocument>();
        for (var path : listJsonFiles(directory)) {
            var envelope = readJson(path);
            for (var field : List.of("groupId", "resourceId", "versionId", "format", "schema")) {
                require(envelope.hasNonNull(field) && !envelope.path(field).asText().isBlank(), path.getFileName() + " is missing " + field);
            }
            require("JsonSchema/2020-12".equals(envelope.path("format").asText()), path.getFileName() + " must use JsonSchema/2020-12");
            var schema = OBJECT_MAPPER.readTree(envelope.path("schema").asText());
            var id = schema.path("$id").asText();
            require(!id.isBlank(), path.getFileName() + " schema needs a $id");
            try {
                var schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                        builder -> builder.schemas(Map.of(id, schema.toString())));
                schemaRegistry.getSchema(SchemaLocation.of(id));
            } catch (Exception exception) {
                throw new IllegalStateException(path.getFileName() + " has an unusable JSON Schema: " + exception.getMessage(), exception);
            }
            schemas.add(new SchemaDocument(path, id, schema));
        }
        return schemas;
    }

    private static void validatePolicy(Path source, JsonNode policy, List<SchemaDocument> tier1, List<SchemaDocument> tier2, boolean requireCatalogFilename) {
        for (var field : List.of("groupId", "resourceId", "versionId", "accessPolicy", "controlPolicy", "policyDefinitionId", "policyDefinition")) {
            require(policy.has(field), source.getFileName() + " is missing " + field);
        }
        if (requireCatalogFilename) {
            require(source.getFileName().toString().equals(coordinates(policy) + ".json"), source.getFileName() + " does not match its coordinates");
        }
        var expectedId = text(policy, "groupId") + "/" + text(policy, "resourceId") + "/" + text(policy, "versionId");
        require(text(policy, "policyDefinitionId").equals(expectedId), source.getFileName() + " has an invalid policyDefinitionId");
        var definition = policy.path("policyDefinition");
        require(definition.isObject(), source.getFileName() + " policyDefinition must be an object");
        require(text(definition, "@id").equals(text(policy, "policyDefinitionId")), source.getFileName() + " policyDefinition.@id must match policyDefinitionId");
        require("odrl:Set".equals(text(definition, "@type")), source.getFileName() + " must contain an odrl:Set policy definition");
        validateAgainstSchemas(source, definition, tier1);
        validateAgainstSchemas(source, definition, tier2);
        var operands = constraints(definition).stream().map(node -> text(node, "leftOperand")).collect(Collectors.toSet());
        if (policy.path("controlPolicy").asBoolean()) {
            require(operands.contains("UsagePurpose"), source.getFileName() + " control policy must declare UsagePurpose");
        }
    }

    private static void validateAgainstSchemas(Path source, JsonNode policyDefinition, List<SchemaDocument> schemas) {
        for (var schemaDocument : schemas) {
            var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                    builder -> builder.schemas(Map.of(schemaDocument.id(), schemaDocument.schema().toString())));
            var schema = registry.getSchema(SchemaLocation.of(schemaDocument.id()));
            var errors = schema.validate(policyDefinition.toString(), InputFormat.JSON);
            require(errors.isEmpty(), source.getFileName() + " violates " + schemaDocument.path().getFileName() + ": " + errors.stream().map(Error::getMessage).collect(Collectors.joining("; ")));
        }
    }

    private static void validateNegativeFixtures(List<SchemaDocument> tier1, List<SchemaDocument> tier2) throws IOException {
        var expectedFailures = Map.of("missing-usage-purpose.json", "UsagePurpose", "raw-bpn-constraint.json", "company-policy");
        for (var fixture : listJsonFiles(INVALID_FIXTURES_DIR)) {
            var expected = expectedFailures.get(fixture.getFileName().toString());
            require(expected != null, "Fixture has no expected failure: " + fixture.getFileName());
            try {
                validatePolicy(fixture, readJson(fixture), tier1, tier2, false);
                throw new IllegalStateException(fixture.getFileName() + " was accepted but must be rejected");
            } catch (IllegalStateException exception) {
                require(exception.getMessage().contains(expected), fixture.getFileName() + " failed for an unexpected reason: " + exception.getMessage());
            }
        }
    }

    private static void checkVersionImmutability() throws Exception {
        var range = resolveDiffRange();
        if (range.isBlank()) {
            System.out.println("Skipping immutability check because no diff base was found.");
            return;
        }
        var violations = runCommand(List.of("git", "diff", "--name-status", range)).lines()
                .filter(line -> !line.isBlank())
                .filter(line -> line.contains("src/main/xregistry/policies/"))
                .filter(line -> !line.startsWith("A\t") && !line.startsWith("D\t"))
                .toList();
        require(violations.isEmpty(), "Existing published policy versions may not be modified or renamed: " + violations);
        System.out.println("Version immutability check passed. Deletions are permitted for the S7 lifecycle scenario.");
    }

    private static void checkSchemaCompatibility() throws Exception {
        var range = resolveDiffRange();
        if (range.isBlank()) {
            System.out.println("Skipping schema compatibility check because no diff base was found.");
            return;
        }
        var changed = runCommand(List.of("git", "diff", "--name-only", range)).lines()
                .filter(path -> path.startsWith("src/main/xregistry/schemas/") || path.startsWith("dataspace/src/main/xregistry/schemas/"))
                .map(REPO_ROOT::resolve).filter(Files::exists).toList();
        var all = new ArrayList<Path>();
        all.addAll(listJsonFiles(COMPANY_SCHEMAS_DIR));
        all.addAll(listJsonFiles(DATASPACE_SCHEMAS_DIR));
        for (var next : changed) {
            var previous = previousVersion(next, all);
            if (previous == null) {
                continue;
            }
            var breaking = schemaBreaks(schemaBody(previous), schemaBody(next));
            if (!breaking.isEmpty()) {
                var nextVersion = version(next);
                var previousVersion = version(previous);
                require(nextVersion.major() > previousVersion.major(), next.getFileName() + " is breaking (" + String.join(", ", breaking) + ") and requires a major version bump from " + previous.getFileName());
            }
        }
        System.out.println("Schema compatibility check passed.");
    }

    private static List<String> schemaBreaks(JsonNode previous, JsonNode next) {
        var breaks = new ArrayList<String>();
        var previousRequired = strings(previous.path("required"));
        var nextRequired = strings(next.path("required"));
        nextRequired.stream().filter(field -> !previousRequired.contains(field)).forEach(field -> breaks.add("added required field " + field));
        var previousProperties = previous.path("properties");
        next.path("properties").fields().forEachRemaining(entry -> {
            var before = strings(previousProperties.path(entry.getKey()).path("enum"));
            var after = strings(entry.getValue().path("enum"));
            if (!before.isEmpty() && !after.isEmpty() && !after.containsAll(before)) {
                breaks.add("shrunk enum " + entry.getKey());
            }
        });
        return breaks;
    }

    private static Path previousVersion(Path next, List<Path> candidates) {
        var nextVersion = version(next);
        return candidates.stream().filter(candidate -> !candidate.equals(next))
                .filter(candidate -> version(candidate).coordinate().equals(nextVersion.coordinate()))
                .filter(candidate -> version(candidate).compareTo(nextVersion) < 0)
                .max(Comparator.comparing(XrValidator::version)).orElse(null);
    }

    private static Version version(Path path) {
        var name = path.getFileName().toString().replaceFirst("\\.json$", "");
        var matcher = VERSIONED_FILE.matcher(name);
        require(matcher.matches(), "Invalid versioned schema filename: " + path.getFileName());
        return new Version(matcher.group("coordinate"), Integer.parseInt(matcher.group("major")), Integer.parseInt(matcher.group("minor")));
    }

    private static JsonNode schemaBody(Path path) {
        try {
            return OBJECT_MAPPER.readTree(text(readJson(path), "schema"));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read schema " + path, exception);
        }
    }

    private static List<JsonNode> constraints(JsonNode definition) {
        var constraints = new ArrayList<JsonNode>();
        definition.path("permission").forEach(permission -> permission.path("constraint").forEach(constraints::add));
        return constraints;
    }

    private static Set<String> strings(JsonNode node) {
        var values = new TreeSet<String>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static JsonNode readJson(Path path) throws IOException {
        return OBJECT_MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", ""));
    }

    private static List<Path> listJsonFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static String resolveDiffRange() throws Exception {
        var base = System.getenv("GITHUB_BASE_REF");
        if (base != null && !base.isBlank()) {
            return "origin/" + base + "...HEAD";
        }
        try {
            runCommand(List.of("git", "rev-parse", "HEAD~1"));
            return "HEAD~1...HEAD";
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String runCommand(List<String> command) throws Exception {
        var process = new ProcessBuilder(command).directory(REPO_ROOT.toFile()).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new RuntimeException(String.join(" ", command) + ": " + output);
        }
        return output;
    }

    private static String coordinates(JsonNode policy) {
        return text(policy, "groupId") + "." + text(policy, "resourceId") + "." + text(policy, "versionId");
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record SchemaDocument(Path path, String id, JsonNode schema) {
    }

    private record Version(String coordinate, int major, int minor) implements Comparable<Version> {
        @Override
        public int compareTo(Version other) {
            var coordinateComparison = coordinate.compareTo(other.coordinate);
            if (coordinateComparison != 0) {
                return coordinateComparison;
            }
            var majorComparison = Integer.compare(major, other.major);
            return majorComparison != 0 ? majorComparison : Integer.compare(minor, other.minor);
        }
    }
}
