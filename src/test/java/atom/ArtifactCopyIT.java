package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The artifact-copy atom, started the way the runner starts it, against the Jenkins-shaped fake in {@code tools/}.
 *
 * <p>These are the checks {@code tools/smoke.sh} made: the listing and byte-identical artifacts, nested and spaced
 * names, the glob narrowing (and the dot that must stay literal), build pinning, basic auth, a 401, and bad input.
 */
class ArtifactCopyIT {

    private Path module;
    private final List<Process> servers = new ArrayList<>();
    private int port;
    private Path log;

    @BeforeEach
    void setUp() throws Exception {
        module = Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
        start();
    }

    @AfterEach
    void tearDown() {
        servers.forEach(Process::destroy);
    }

    @Test
    @DisplayName("the whole build's artifacts are copied byte for byte, paths preserved")
    void copiesEveryArtifact() throws Exception {
        AtomResult run = Atom.of().input(input()).run();

        run.assertOk();
        assertEquals("4", run.field("filesCopied"), run.file("output.json"));
        assertEquals("42", run.field("sourceBuild"), run.file("output.json"));
        assertEquals("jar-bytes\n", run.file("copied/dist/app.jar"));
        assertEquals("deep\n", run.file("copied/reports/nested/deep.txt"), "the nested path was flattened");
        assertEquals("spaced\n", run.file("copied/reports/a file with spaces.txt"), "a name with spaces broke the copy");

        List<String> paths = requests();
        assertTrue(paths.stream().anyMatch(p -> p.contains("/job/upstream-app/lastSuccessfulBuild/api/json")),
                "the listing URL: " + paths);
        assertTrue(paths.stream().anyMatch(p -> p.contains("/artifact/reports/a%20file%20with%20spaces.txt")),
                "artifact paths must be encoded: " + paths);
    }

    @Test
    @DisplayName("a narrower glob selects fewer files, and a dot in the pattern stays literal")
    void narrowsWithAGlob() throws Exception {
        AtomResult one = Atom.of().input(input()).input("artifacts", "**/*.jar").run();
        one.assertOk();
        assertEquals("1", one.field("filesCopied"), "*.jar should select one file: " + one.file("output.json"));

        AtomResult literalDot = Atom.of().input(input()).input("artifacts", "dist/appXjar").run();
        literalDot.assertRefused();
        assertTrue(literalDot.file("output.json").contains("no artifact matched"),
                "a dot in the pattern must be literal, not a wildcard: " + literalDot.file("output.json"));
    }

    @Test
    @DisplayName("a pinned build number is read from number/<n>")
    void pinsABuildNumber() throws Exception {
        AtomResult run = Atom.of().input(input()).input("buildSelector", "number").input("buildNumber", "7").run();

        run.assertOk();
        assertTrue(requests().stream().anyMatch(p -> p.contains("/job/upstream-app/number/7/api/json")),
                "build number URL: " + requests());
    }

    @Test
    @DisplayName("matching nothing fails and names what the build actually has")
    void listsWhatTheBuildHas() throws Exception {
        AtomResult run = Atom.of().input(input()).input("artifacts", "**/*.zip").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("dist/app.jar"),
                "the available artifacts should be listed: " + run.file("output.json"));
    }

    @Test
    @DisplayName("auth goes out as basic, from the secret")
    void sendsBasicAuth() throws Exception {
        AtomResult run = Atom.of()
                .file(".secrets.json", "{\"token\": \"api-token\"}")
                .input(input())
                .input("username", "ci")
                .run();

        run.assertOk();
        Map<?, ?> first = Json.parseObject(Files.readString(log).lines().findFirst().orElse("{}"));
        String expected = "Basic " + Base64.getEncoder().encodeToString("ci:api-token".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, first.get("authorization"), "the token did not reach the server as basic auth: " + first);
    }

    @Test
    @DisplayName("401 says the token is the problem and is a USER error")
    void reportsA401() throws Exception {
        restart("--status", "401");
        AtomResult run = Atom.of()
                .file(".secrets.json", "{\"token\": \"bad\"}")
                .input(input())
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("read access"), "401 hint: " + run.file("output.json"));
        assertTrue(run.file("output.json").contains("\"errorType\":1"), "401 should be USER(1): " + run.file("output.json"));
    }

    @Test
    @DisplayName("bad input is refused before any request")
    void refusesBadInput() throws Exception {
        AtomResult noJob = Atom.of().input("{\"sourceUrl\": \"http://127.0.0.1:" + port + "\", \"sourceJob\": \"\"}")
                .run();
        noJob.assertRefused();
        assertTrue(noJob.file("output.json").contains("sourceJob is required"), noJob.file("output.json"));

        AtomResult noNumber = Atom.of().input(input()).input("buildSelector", "number").run();
        noNumber.assertRefused();
        assertTrue(noNumber.file("output.json").contains("buildNumber is required"), noNumber.file("output.json"));
    }

    private String input() {
        return "{\"sourceUrl\": \"http://127.0.0.1:" + port + "\", \"sourceJob\": \"upstream-app\"}";
    }

    private List<String> requests() throws Exception {
        return Files.readAllLines(log);
    }

    private void restart(String... args) throws Exception {
        start(args);
    }

    private void start(String... args) throws Exception {
        port = freePort();
        log = Files.createTempFile(Path.of(System.getProperty("atom.scratch", "target")), "fake-jenkins", ".log");
        List<String> command = new ArrayList<>(List.of("python3",
                module.resolve("tools/fake_jenkins.py").toString(),
                String.valueOf(port), log.toString()));
        command.addAll(List.of(args));
        servers.add(new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
        awaitPort(port);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitPort(int port) throws Exception {
        for (int i = 0; i < 200; i++) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                return;
            } catch (Exception notYet) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("the fake Jenkins did not listen on " + port);
    }
}
