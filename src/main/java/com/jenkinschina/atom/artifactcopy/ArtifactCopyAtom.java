package com.jenkinschina.atom.artifactcopy;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Copy archived artifacts from another build into this workspace.
 *
 * <p>The copyartifact plugin does this inside Jenkins, with the job model and the build's own fingerprinting. An
 * atom only has the workspace, but Jenkins serves artifacts over plain HTTP, so the step can be reproduced with
 * two requests: list what a build archived, then fetch the ones that match.
 */
public class ArtifactCopyAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String baseUrl = context.getParam("sourceUrl", "").trim();
        String job = context.getParam("sourceJob", "").trim();
        String selector = context.getParam("buildSelector", "lastSuccessfulBuild").trim();
        String buildNumber = context.getParam("buildNumber", "").trim();
        String patterns = context.getParam("artifacts", "**/*");
        String targetDir = context.getParam("targetDir", "copied");
        String username = context.getParam("username", "").trim();
        String token = context.getSecret("token");
        if (token == null) {
            token = context.getParam("token", "");
        }
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 60), 600));

        if (baseUrl.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "sourceUrl is required (e.g. https://jenkins.example.com)");
        }
        if (job.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "sourceJob is required");
        }
        if ("number".equals(selector) && buildNumber.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40003, "buildNumber is required when buildSelector is 'number'");
        }
        String buildRef = "number".equals(selector) ? "number/" + buildNumber : selector;
        Path target = workspace.resolve(targetDir).normalize();
        if (!target.startsWith(workspace)) {
            return AtomResult.error(ErrorType.USER, 40004, "targetDir must stay inside the workspace");
        }

        String root = baseUrl.replaceAll("/+$", "") + "/job/" + encodePath(job) + "/" + buildRef;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        String authorization = authorization(username, token);

        Reply listing = get(client, root + "/api/json?tree=artifacts%5BrelativePath%5D,number,result",
                authorization, timeoutSeconds);
        if (!listing.ok()) {
            return AtomResult.error(listing.type(), 40005,
                    "cannot read " + job + " @ " + selector + ": " + listing.describe()
                            + (listing.status == 404 ? " (check the job name and that the build exists)" : ""));
        }
        List<String> available = new ArrayList<>();
        Object artifacts = asMap(listing.bytes).get("artifacts");
        if (artifacts instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry && entry.get("relativePath") != null) {
                    available.add(String.valueOf(entry.get("relativePath")));
                }
            }
        }
        if (available.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40006,
                    job + " @ " + selector + " has no archived artifacts (the build must archive something first)");
        }

        List<String> wanted = new ArrayList<>();
        for (String candidate : available) {
            if (matchesAny(candidate, patterns)) {
                wanted.add(candidate);
            }
        }
        if (wanted.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40007, "no artifact matched '" + patterns + "'; that build has: "
                    + String.join(", ", available.subList(0, Math.min(available.size(), 8)))
                    + (available.size() > 8 ? " …" : ""));
        }

        long total = 0;
        for (String relative : wanted) {
            String url = root + "/artifact/" + encodePath(relative);
            Reply file = get(client, url, authorization, timeoutSeconds);
            if (!file.ok()) {
                return AtomResult.error(file.type(), 40008, "fetching " + relative + " failed: " + file.describe());
            }
            Path destination = target.resolve(relative).normalize();
            if (!destination.startsWith(target)) {
                return AtomResult.error(ErrorType.USER, 40009, "artifact path escapes targetDir: " + relative);
            }
            Files.createDirectories(destination.getParent() == null ? target : destination.getParent());
            Files.write(destination, file.bytes);
            total += file.bytes.length;
            context.log("[copy] " + relative + " (" + file.bytes.length + " bytes)");
        }

        return AtomResult.success()
                .data("filesCopied", String.valueOf(wanted.size()))
                .data("bytes", String.valueOf(total))
                .data("targetDir", targetDir)
                .data("sourceBuild", String.valueOf(text(asMap(listing.bytes), "number")))
                .data("sourceResult", text(asMap(listing.bytes), "result"));
    }

    private static String authorization(String username, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String pair = (username.isEmpty() ? "" : username) + ":" + token;
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private static Reply get(HttpClient client, String url, String authorization, int timeoutSeconds) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json, */*")
                .header("User-Agent", "jenkins-atom-artifact-copy")
                .GET();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), response.body());
    }

    /** Jenkins keeps artifact paths in URL space, so each segment is encoded but the slashes are not. */
    private static String encodePath(String value) {
        StringBuilder encoded = new StringBuilder();
        for (String segment : value.split("/")) {
            if (!encoded.isEmpty()) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    private static boolean matchesAny(String relative, String patterns) {
        for (String pattern : patterns.split("[,\\n]")) {
            String trimmed = pattern.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (glob(trimmed, relative)) {
                return true;
            }
        }
        return false;
    }

    /** {@code **} crosses directories, {@code *} and {@code ?} stop at a slash. */
    private static boolean glob(String pattern, String value) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i++;
                        if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') {
                            i++;
                            regex.append("?/?");
                        }
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%' -> regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return value.matches(regex.toString());
    }

    private record Reply(int status, byte[] bytes) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        ErrorType type() {
            return switch (status) {
                case 400, 401, 403, 404 -> ErrorType.USER;
                default -> ErrorType.THIRD_PART;
            };
        }

        String describe() {
            if (status == 401 || status == 403) {
                return "HTTP " + status + " (the token needs read access to that job)";
            }
            return "HTTP " + status;
        }
    }

    private static Map<?, ?> asMap(byte[] body) {
        try {
            Object parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
            return parsed instanceof Map<?, ?> map ? map : Map.of();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
