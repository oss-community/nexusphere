package com.nexusphere.ledger.compliance.application;

import com.nexusphere.ledger.compliance.domain.model.ComplianceProfile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class ComplianceProfiles {

    public static final List<String> BUILT_IN = List.of("baseline", "eu", "us");
    public static final String DEFAULT = "baseline";

    private ComplianceProfiles() {
    }

    public static Map<String, ComplianceProfile> available(JsonMapper json, Path directory) {
        Map<String, ComplianceProfile> profiles = new LinkedHashMap<>();
        for (String id : BUILT_IN) {
            try (InputStream in = ComplianceProfiles.class.getResourceAsStream("/compliance/" + id + ".json")) {
                if (in == null) {
                    throw new IllegalStateException("The built-in compliance profile " + id + " is missing");
                }
                profiles.put(id, read(json, in.readAllBytes(), id, "built-in profile " + id));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (directory == null) {
            return profiles;
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("The compliance profile directory " + directory + " does not exist");
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - ".json".length());
                if (profiles.containsKey(id)) {
                    throw new IllegalStateException("The compliance profile " + file + " reuses the id " + id
                            + "; give your own profile a new id");
                }
                profiles.put(id, read(json, Files.readAllBytes(file), id, file.toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return profiles;
    }

    public static List<ComplianceProfile> select(Map<String, ComplianceProfile> available, List<String> ids) {
        List<String> wanted = ids == null ? List.of() : ids.stream().map(String::trim).filter(id -> !id.isEmpty())
                .distinct().toList();
        if (wanted.isEmpty()) {
            wanted = List.of(DEFAULT);
        }
        List<ComplianceProfile> selected = new ArrayList<>();
        for (String id : wanted) {
            ComplianceProfile profile = available.get(id);
            if (profile == null) {
                throw new IllegalStateException("There is no compliance profile " + id + "; available: "
                        + available.keySet());
            }
            selected.add(profile);
        }
        return selected;
    }

    private static ComplianceProfile read(JsonMapper json, byte[] bytes, String id, String source) {
        ComplianceProfile profile;
        try {
            profile = ComplianceProfile.parse(json.readTree(bytes));
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalStateException("The compliance profile " + source + " is not valid: " + e.getMessage(), e);
        }
        if (!profile.id().equals(id)) {
            throw new IllegalStateException("The compliance profile " + source + " has the id " + profile.id()
                    + " but must be named " + profile.id() + ".json");
        }
        return profile;
    }
}
