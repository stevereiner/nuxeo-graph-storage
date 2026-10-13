package org.integratedsemantics.nuxeo.graph.neo4j;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.neo4j.Neo4jContainer;

/**
 * The Neo4j server used by the tests, shared by all the tests of the JVM.
 * <p>
 * By default a Neo4j Docker container is started with Testcontainers (image {@code nuxeo.test.neo4j.image}, else
 * {@code neo4j:<nuxeo.test.neo4j.version>}, set by the POM from {@code neo4j.version}). If the system property
 * {@code nuxeo.test.neo4j.uri} is set, that external server is used
 * instead (a local server, or AuraDB with a {@code neo4j+s://} URI); only its {@code Nx_test} nodes are touched.
 *
 * @since 0.1.0
 */
public final class Neo4jTestServer {

    private static final Logger log = LogManager.getLogger(Neo4jTestServer.class);

    public static final String TEST_URI_PROPERTY = "nuxeo.test.neo4j.uri";

    public static final String TEST_USERNAME_PROPERTY = "nuxeo.test.neo4j.username";

    public static final String TEST_PASSWORD_PROPERTY = "nuxeo.test.neo4j.password";

    public static final String TEST_IMAGE_PROPERTY = "nuxeo.test.neo4j.image";

    /** Tag of the default image, {@code neo4j:<version>}: the POM's {@code neo4j.version}. */
    public static final String TEST_VERSION_PROPERTY = "nuxeo.test.neo4j.version";

    private static Neo4jContainer container;

    private static Driver driver;

    private Neo4jTestServer() {
        // utility class
    }

    /**
     * Starts (or connects to) the test server, and sets the nuxeo.neo4j.* properties used by the repository.
     */
    public static synchronized Driver start() {
        if (driver != null) {
            return driver;
        }
        var uri = System.getProperty(TEST_URI_PROPERTY);
        var username = System.getProperty(TEST_USERNAME_PROPERTY, Neo4jRepository.DEFAULT_USERNAME);
        var password = System.getProperty(TEST_PASSWORD_PROPERTY, "");
        if (StringUtils.isBlank(uri)) {
            var image = System.getProperty(TEST_IMAGE_PROPERTY);
            if (StringUtils.isBlank(image)) {
                var version = System.getProperty(TEST_VERSION_PROPERTY);
                if (StringUtils.isBlank(version)) {
                    throw new IllegalStateException(
                            "Set " + TEST_IMAGE_PROPERTY + " or " + TEST_VERSION_PROPERTY + " (done by the POM)");
                }
                image = "neo4j:" + version;
            }
            log.info("Starting Neo4j container: {}", image);
            container = new Neo4jContainer(image).withoutAuthentication();
            container.start();
            uri = container.getBoltUrl();
            password = ""; // no authentication on the container
        } else {
            log.info("Using external Neo4j at: {}", uri);
        }
        System.setProperty(Neo4jRepository.URI_PROPERTY, uri);
        System.setProperty(Neo4jRepository.USERNAME_PROPERTY, username);
        System.setProperty(Neo4jRepository.PASSWORD_PROPERTY, password);
        // some Nuxeo tests leave transactions open on failure paths: don't let them block the next tests too long
        System.setProperty(Neo4jRepository.TRANSACTION_TIMEOUT_PROPERTY, "20");
        driver = GraphDatabase.driver(uri,
                password.isEmpty() ? AuthTokens.none() : AuthTokens.basic(username, password));
        Runtime.getRuntime().addShutdownHook(new Thread(Neo4jTestServer::stop));
        return driver;
    }

    /**
     * Deletes all the nodes of a repository. Only the nodes having the repository label are touched, so that an
     * external database holding other data can be used.
     */
    public static void clear(String repositoryName) {
        try (var session = start().session()) {
            session.run("MATCH (n:`Nx_%s`) DETACH DELETE n".formatted(repositoryName)).consume();
        }
    }

    private static synchronized void stop() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
        if (container != null) {
            container.stop();
            container = null;
        }
    }

}
