package org.integratedsemantics.nuxeo.graph.arcadedb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.remote.RemoteDatabase;
import com.arcadedb.server.ArcadeDBServer;

/**
 * The ArcadeDB server used by the tests, shared by all the tests of the JVM. Three modes:
 * <ul>
 * <li>external: if the system property {@code nuxeo.test.arcadedb.host} is set, that server is used (local or remote,
 * Docker or standalone); its database must exist and be dedicated to the tests,</li>
 * <li>{@code nuxeo.test.arcadedb.mode=container} (default): an ArcadeDB Docker container is started with
 * Testcontainers, image {@code nuxeo.test.arcadedb.image} (default {@code arcadedata/arcadedb:<client version>}),</li>
 * <li>{@code nuxeo.test.arcadedb.mode=inprocess}: an ArcadeDB server is started in the test JVM (no Docker).</li>
 * </ul>
 * In all modes the repository uses the remote (HTTP) API.
 *
 * @since 0.1.0
 */
public final class ArcadeDBTestServer {

    private static final Logger log = LogManager.getLogger(ArcadeDBTestServer.class);

    public static final String TEST_HOST_PROPERTY = "nuxeo.test.arcadedb.host";

    public static final String TEST_PORT_PROPERTY = "nuxeo.test.arcadedb.port";

    public static final String TEST_DATABASE_PROPERTY = "nuxeo.test.arcadedb.database";

    public static final String TEST_USERNAME_PROPERTY = "nuxeo.test.arcadedb.username";

    public static final String TEST_PASSWORD_PROPERTY = "nuxeo.test.arcadedb.password";

    public static final String TEST_MODE_PROPERTY = "nuxeo.test.arcadedb.mode";

    public static final String TEST_IMAGE_PROPERTY = "nuxeo.test.arcadedb.image";

    /** The ArcadeDB client version, set by the POM, used as default image tag. */
    public static final String TEST_VERSION_PROPERTY = "nuxeo.test.arcadedb.version";

    public static final String MODE_CONTAINER = "container";

    public static final String MODE_INPROCESS = "inprocess";

    private static final String TEST_ROOT_PASSWORD = "playwithdata";

    private static final int HTTP_PORT = 2480;

    /**
     * Transaction session expiry: Nuxeo transactions can be idle longer than the default 5s; but some Nuxeo tests leave
     * transactions open on failure paths, and an open transaction may hold the write lock: keep it short-ish.
     */
    private static final long SESSION_EXPIRE_SECONDS = 20;

    private static ArcadeDBServer server;

    private static GenericContainer<?> container;

    private static Path rootPath;

    private static boolean started;

    private ArcadeDBTestServer() {
        // utility class
    }

    /**
     * Starts (or connects to) the test server, and sets the nuxeo.arcadedb.* properties used by the repository.
     */
    public static synchronized void start() {
        if (started) {
            return;
        }
        var host = System.getProperty(TEST_HOST_PROPERTY);
        var database = System.getProperty(TEST_DATABASE_PROPERTY, "nuxeo-test");
        String port;
        String username;
        String password;
        if (StringUtils.isNotBlank(host)) {
            port = System.getProperty(TEST_PORT_PROPERTY, String.valueOf(HTTP_PORT));
            username = System.getProperty(TEST_USERNAME_PROPERTY, "root");
            password = System.getProperty(TEST_PASSWORD_PROPERTY, "");
            log.info("Using external ArcadeDB at: {}:{}/{}", host, port, database);
        } else {
            var mode = System.getProperty(TEST_MODE_PROPERTY, MODE_CONTAINER);
            if (MODE_CONTAINER.equals(mode)) {
                startContainer(database);
                host = container.getHost();
                port = String.valueOf(container.getMappedPort(HTTP_PORT));
            } else if (MODE_INPROCESS.equals(mode)) {
                port = String.valueOf(startInProcess(database));
                host = "localhost";
            } else {
                throw new IllegalArgumentException("Unknown " + TEST_MODE_PROPERTY + ": " + mode);
            }
            username = "root";
            password = TEST_ROOT_PASSWORD;
        }
        System.setProperty(ArcadeDBRepository.HOST_PROPERTY, host);
        System.setProperty(ArcadeDBRepository.PORT_PROPERTY, port);
        System.setProperty(ArcadeDBRepository.DATABASE_PROPERTY, database);
        System.setProperty(ArcadeDBRepository.USERNAME_PROPERTY, username);
        System.setProperty(ArcadeDBRepository.PASSWORD_PROPERTY, password);
        Runtime.getRuntime().addShutdownHook(new Thread(ArcadeDBTestServer::stop));
        started = true;
    }

    private static void startContainer(String database) {
        var image = System.getProperty(TEST_IMAGE_PROPERTY);
        if (StringUtils.isBlank(image)) {
            var version = System.getProperty(TEST_VERSION_PROPERTY);
            if (StringUtils.isBlank(version)) {
                throw new IllegalStateException(
                        "Set " + TEST_IMAGE_PROPERTY + " or " + TEST_VERSION_PROPERTY + " (done by the POM)");
            }
            image = "arcadedata/arcadedb:" + version;
        }
        log.info("Starting ArcadeDB container: {}", image);
        var javaOpts = String.join(" ", //
                // a bounded heap: by default the JVM takes a quarter of the Docker VM's memory, and the server can grow
                // into it until the VM's OOM killer stops the container
                "-Xmx4g", //
                "-Darcadedb.server.rootPassword=" + TEST_ROOT_PASSWORD, //
                "-Darcadedb.server.defaultDatabases=" + database + "[root]", //
                "-Darcadedb.server.httpSessionExpireTimeout=" + SESSION_EXPIRE_SECONDS);
        var ready = Wait.forHttp("/api/v1/ready")
                        .forPort(HTTP_PORT)
                        .forStatusCodeMatching(code -> code >= 200 && code < 300)
                        .withStartupTimeout(Duration.ofMinutes(2));
        container = new GenericContainer<>(DockerImageName.parse(image)).withExposedPorts(HTTP_PORT)
                                                                        .withEnv("JAVA_OPTS", javaOpts)
                                                                        .waitingFor(ready);
        container.start();
    }

    private static int startInProcess(String database) {
        log.info("Starting in-process ArcadeDB server");
        try {
            rootPath = Files.createTempDirectory("arcadedb-test-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var config = new ContextConfiguration();
        config.setValue(GlobalConfiguration.SERVER_ROOT_PATH, rootPath.toString());
        config.setValue(GlobalConfiguration.SERVER_DATABASE_DIRECTORY, rootPath.resolve("databases").toString());
        config.setValue(GlobalConfiguration.SERVER_ROOT_PASSWORD, TEST_ROOT_PASSWORD);
        config.setValue(GlobalConfiguration.SERVER_HTTP_INCOMING_PORT, "2580-2599");
        config.setValue(GlobalConfiguration.SERVER_HTTPS_INCOMING_PORT, "2600-2619");
        config.setValue(GlobalConfiguration.SERVER_DEFAULT_DATABASES, database + "[root]");
        config.setValue(GlobalConfiguration.SERVER_METRICS, false);
        config.setValue(GlobalConfiguration.SERVER_HTTP_SESSION_EXPIRE_TIMEOUT, SESSION_EXPIRE_SECONDS);
        server = new ArcadeDBServer(config);
        server.start();
        return server.getHttpServer().getPort();
    }

    /** Opens a client on the test database. */
    public static RemoteDatabase openDatabase() {
        start();
        return new RemoteDatabase(System.getProperty(ArcadeDBRepository.HOST_PROPERTY),
                Integer.parseInt(System.getProperty(ArcadeDBRepository.PORT_PROPERTY)),
                System.getProperty(ArcadeDBRepository.DATABASE_PROPERTY),
                System.getProperty(ArcadeDBRepository.USERNAME_PROPERTY),
                System.getProperty(ArcadeDBRepository.PASSWORD_PROPERTY));
    }

    /**
     * Deletes all the documents and locks of the test database.
     */
    public static void clear() {
        try (var db = openDatabase()) {
            // only the existing types: on a new database the repository hasn't created them yet
            var types = List.of(ArcadeDBRepository.DOC_TYPE, ArcadeDBRepository.LOCK_TYPE);
            var existing = db.query("sql", "SELECT name FROM schema:types WHERE name IN :types",
                    Map.of("types", types)).stream().map(r -> (String) r.getProperty("name")).toList();
            for (var type : existing) {
                db.command("sql", "DELETE FROM " + type, Map.of());
            }
        }
    }

    private static synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
            FileUtils.deleteQuietly(rootPath.toFile());
        }
        if (container != null) {
            container.stop();
            container = null;
        }
    }

}
