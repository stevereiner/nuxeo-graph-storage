package org.integratedsemantics.nuxeo.graph.neo4j;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * Tests the Neo4j repository through the standard {@link CoreSession} API, and checks the resulting graph.
 *
 * @since 0.1.0
 */
@RunWith(FeaturesRunner.class)
@Features(Neo4jRepositoryFeature.class)
public class TestNeo4jRepository {

    @Inject
    protected CoreSession session;

    @Inject
    protected CoreFeature coreFeature;

    @Inject
    protected TransactionalFeature txFeature;

    /** Runs a Cypher count query directly against Neo4j (sees committed data only). */
    protected long count(String cypher, Map<String, Object> params) {
        try (var s = Neo4jTestServer.start().session()) {
            return s.run(cypher, params).single().get(0).asLong();
        }
    }

    protected DocumentModel createFolder(String parentPath, String name) {
        var folder = session.createDocumentModel(parentPath, name, "Folder");
        folder.setPropertyValue("dc:title", name);
        return session.createDocument(folder);
    }

    protected DocumentModel createFile(String parentPath, String name, String title, String... subjects) {
        var file = session.createDocumentModel(parentPath, name, "File");
        file.setPropertyValue("dc:title", title);
        if (subjects.length > 0) {
            file.setPropertyValue("dc:subjects", subjects);
        }
        return session.createDocument(file);
    }

    protected static Blob blob(String content, String filename) {
        var blob = Blobs.createBlob(content);
        blob.setFilename(filename);
        return blob;
    }

    protected static Map<String, Serializable> filesEntry(Blob blob) {
        return Map.of("file", (Serializable) blob);
    }

    // note: fields of schemas without prefix (file, files) are stored under their bare name (content, files)

    @Test
    public void testCreateAndRead() throws IOException {
        var folder = createFolder("/", "folder");
        var file = session.createDocumentModel("/folder", "file", "File");
        file.setPropertyValue("dc:title", "My File");
        file.setPropertyValue("dc:subjects", new String[] { "red", "green" });
        file.setPropertyValue("file:content", (Serializable) blob("hello", "hello.txt"));
        file.setPropertyValue("files:files", (Serializable) List.of(filesEntry(blob("attached", "att.txt"))));
        file = session.createDocument(file);
        txFeature.nextTransaction();

        // read back through Nuxeo
        var doc = session.getDocument(new PathRef("/folder/file"));
        assertEquals(file.getId(), doc.getId());
        assertEquals("My File", doc.getPropertyValue("dc:title"));
        assertArrayEquals(new String[] { "red", "green" }, (Object[]) doc.getPropertyValue("dc:subjects"));
        var content = (Blob) doc.getPropertyValue("file:content");
        assertEquals("hello.txt", content.getFilename());
        assertEquals("hello", content.getString());
        var attached = (Blob) doc.getPropertyValue("files:files/0/file");
        assertEquals("att.txt", attached.getFilename());
        assertEquals(1, session.getChildren(folder.getRef()).size());

        // check the graph
        var ids = Map.<String, Object> of("id", file.getId(), "pid", folder.getId());
        assertEquals(1, count("""
                MATCH (:NxDocument:Nx_test {`ecm:id`: $id})-[:CHILD_OF]->(:NxDocument:Nx_test {`ecm:id`: $pid})
                RETURN count(*)""", ids));
        assertEquals(1, count("""
                MATCH (:NxDocument {`ecm:id`: $id})-[:PROP {key: 'content'}]->(c:NxComplex {name: 'hello.txt'})
                RETURN count(c)""", ids));
        assertEquals(1, count("""
                MATCH (:NxDocument {`ecm:id`: $id})-[:PROP {key: 'files', pos: 0}]->(:NxComplex)
                      -[:PROP {key: 'file'}]->(b:NxComplex {name: 'att.txt'})
                RETURN count(b)""", ids));
        // the root ACP is a 2-level complex list: ecm:acp -> acl -> aces
        assertTrue(count("""
                MATCH (:NxDocument:Nx_test {`ecm:primaryType`: 'Root'})-[:PROP {key: 'ecm:acp'}]->(:NxComplex)
                      -[:PROP {key: 'acl'}]->(ace:NxComplex)
                RETURN count(ace)""", Map.of()) >= 3);
    }

    @Test
    public void testUpdate() throws IOException {
        createFolder("/", "folder");
        var file = session.createDocumentModel("/folder", "file", "File");
        file.setPropertyValue("dc:title", "v1");
        file.setPropertyValue("dc:subjects", new String[] { "red" });
        file.setPropertyValue("file:content", (Serializable) blob("hello", "hello.txt"));
        file.setPropertyValue("files:files", (Serializable) List.of(filesEntry(blob("a", "a.txt"))));
        file = session.createDocument(file);
        txFeature.nextTransaction();

        file = session.getDocument(file.getRef());
        file.setPropertyValue("dc:title", "v2");
        file.setPropertyValue("dc:subjects", new String[] { "red", "blue" });
        file.setPropertyValue("file:content/name", "renamed.txt"); // diff inside a complex value
        @SuppressWarnings("unchecked")
        var files = new ArrayList<>((List<Map<String, Serializable>>) file.getPropertyValue("files:files"));
        files.add(filesEntry(blob("b", "b.txt"))); // list append
        file.setPropertyValue("files:files", files);
        file.setPropertyValue("dc:description", "desc");
        session.saveDocument(file);
        txFeature.nextTransaction();

        var doc = session.getDocument(file.getRef());
        assertEquals("v2", doc.getPropertyValue("dc:title"));
        assertArrayEquals(new String[] { "red", "blue" }, (Object[]) doc.getPropertyValue("dc:subjects"));
        assertEquals("renamed.txt", ((Blob) doc.getPropertyValue("file:content")).getFilename());
        assertEquals("hello", ((Blob) doc.getPropertyValue("file:content")).getString());
        assertEquals("b.txt", ((Blob) doc.getPropertyValue("files:files/1/file")).getFilename());
        assertEquals("desc", doc.getPropertyValue("dc:description"));

        // remove values
        doc.setPropertyValue("dc:subjects", null);
        doc.setPropertyValue("file:content", null);
        doc.setPropertyValue("dc:description", null);
        session.saveDocument(doc);
        txFeature.nextTransaction();

        doc = session.getDocument(file.getRef());
        assertEquals(2, ((List<?>) doc.getPropertyValue("files:files")).size());
        assertNull(doc.getPropertyValue("dc:description"));
        assertNull(doc.getPropertyValue("file:content"));
        var subjects = (Object[]) doc.getPropertyValue("dc:subjects");
        assertTrue(subjects == null || subjects.length == 0);
        var ids = Map.<String, Object> of("id", file.getId());
        // a blob set to null is an all-null diff: like MongoDB's {}, an empty complex node remains
        assertEquals(0, count("""
                MATCH (:NxDocument {`ecm:id`: $id})-[:PROP {key: 'content'}]->(c) WHERE c.name IS NOT NULL
                RETURN count(c)""", ids));
        assertEquals(2, count("""
                MATCH (:NxDocument {`ecm:id`: $id})-[:PROP {key: 'files'}]->(c) RETURN count(c)""", ids));
        // no orphan complex nodes left behind
        assertEquals(0, count("MATCH (c:NxComplex:Nx_test) WHERE NOT ()-[:PROP]->(c) RETURN count(c)", Map.of()));
    }

    @Test
    public void testQuery() {
        var folder = createFolder("/", "folder");
        createFile("/folder", "f1", "doc 1", "red");
        createFile("/folder", "f2", "doc 2", "red", "blue");
        createFile("/folder", "f3", "other 3", "green");
        createFile("/", "f4", "doc 4");
        txFeature.nextTransaction();

        var docs = session.query("SELECT * FROM File WHERE ecm:parentId = '%s' ORDER BY dc:title DESC".formatted(
                folder.getId()));
        assertEquals(List.of("other 3", "doc 2", "doc 1"), docs.stream().map(d -> d.getTitle()).toList());

        assertEquals(3, session.query("SELECT * FROM File WHERE dc:title LIKE 'doc %'").size());
        assertEquals(3, session.query("SELECT * FROM File WHERE ecm:path STARTSWITH '/folder'").size());
        assertEquals(1, session.query("SELECT * FROM Document WHERE ecm:path = '/folder'").size());
        assertEquals(2, session.query("SELECT * FROM File WHERE dc:subjects = 'red'").size());
        assertEquals(3, session.query("SELECT * FROM File WHERE dc:subjects IN ('blue', 'green', 'red')").size());
        assertEquals(2, session.query("SELECT * FROM File WHERE dc:title = 'doc 1' OR dc:title = 'other 3'").size());
        assertEquals(1, session.query("SELECT * FROM File WHERE dc:title IN ('doc 1', 'nope')").size());
        // not translated to Cypher (NOT, mixin types): still correct thanks to the Java evaluation
        assertEquals(1, session.query("SELECT * FROM Document WHERE ecm:mixinType = 'Folderish'").size());
        assertEquals(1, session.query("SELECT * FROM File WHERE NOT dc:title LIKE 'doc %'").size());
        assertEquals(4, session.query("SELECT * FROM File WHERE ecm:isVersion = 0 AND ecm:isTrashed = 0").size());

        // paging and total count
        var page = session.query("SELECT * FROM File ORDER BY dc:title", null, 2, 1, true);
        assertEquals(List.of("doc 2", "doc 4"), page.stream().map(d -> d.getTitle()).toList());
        assertEquals(4, page.totalSize());

        // projection
        var rows = session.queryProjection("SELECT dc:title FROM File WHERE dc:subjects = 'blue'", 0, 0);
        assertEquals(1, rows.size());
        assertEquals("doc 2", rows.get(0).get("dc:title"));
    }

    /** Fulltext on one field, as the Web UI suggestion box queries it: word starts, case-insensitive. */
    @Test
    public void testFieldFulltext() {
        createFile("/", "f1", "Hello Graph World");
        createFile("/", "f2", "hello there");
        createFile("/", "f3", "Shellfish");
        createFile("/", "f4", "company-ontology-test.txt");
        txFeature.nextTransaction();

        java.util.function.ToIntFunction<String> count = q -> session.query(
                "SELECT * FROM File WHERE ecm:fulltext.dc:title LIKE '%s'".formatted(q)).size();
        assertEquals(2, count.applyAsInt("hello"));
        assertEquals(2, count.applyAsInt("HEL*"));
        assertEquals(1, count.applyAsInt("wor*"));
        assertEquals(1, count.applyAsInt("hello wor*"));
        assertEquals(1, count.applyAsInt("hello -graph"));
        assertEquals(0, count.applyAsInt("ell*")); // not a word start
        assertEquals(2, count.applyAsInt("hel")); // word start, with or without *
        assertEquals(1, count.applyAsInt("ontology*")); // words of a file name
        assertEquals(1, count.applyAsInt("txt"));
        assertEquals(1, count.applyAsInt("company test"));
        assertEquals(0, count.applyAsInt("acme"));
        // with other conditions, as the suggester does
        assertEquals(1, session.query("SELECT * FROM Document WHERE ecm:fulltext.dc:title LIKE 'shell*' "
                + "AND ecm:mixinType != 'HiddenInNavigation' AND ecm:isVersion = 0 AND ecm:isTrashed = 0 "
                + "AND ecm:parentId IS NOT NULL").size());
    }

    @Test
    public void testSecurity() {
        var visible = createFolder("/", "visible");
        createFolder("/", "hidden");
        var acp = visible.getACP();
        acp.addACE(ACL.LOCAL_ACL, new ACE("bob", SecurityConstants.READ, true));
        visible.setACP(acp, true);
        // remove the default Members read on the root for the hidden folder
        var hidden = session.getDocument(new PathRef("/hidden"));
        var hiddenAcp = hidden.getACP();
        hiddenAcp.addACE(ACL.LOCAL_ACL, ACE.BLOCK);
        hiddenAcp.addACE(ACL.LOCAL_ACL, new ACE(SecurityConstants.ADMINISTRATORS, SecurityConstants.EVERYTHING));
        hidden.setACP(hiddenAcp, true);
        txFeature.nextTransaction();

        var bobSession = coreFeature.getCoreSession("bob");
        var names = new HashSet<>(
                bobSession.query("SELECT * FROM Folder").stream().map(DocumentModel::getName).toList());
        assertTrue(names.contains("visible"));
        assertFalse(names.contains("hidden"));
    }

    @Test
    public void testVersionsAndProxies() {
        var folder = createFolder("/", "folder");
        var file = createFile("/folder", "file", "v1");
        var versionRef = file.checkIn(VersioningOption.MAJOR, "first");
        var proxy = session.createProxy(versionRef, folder.getRef());
        txFeature.nextTransaction();

        var version = session.getDocument(versionRef);
        assertTrue(version.isVersion());
        assertEquals("1.0", version.getVersionLabel());
        assertEquals(1, session.getVersions(file.getRef()).size());
        assertEquals("v1", session.getDocument(proxy.getRef()).getTitle());
        assertEquals(1, session.query("SELECT * FROM File WHERE ecm:isVersion = 1").size());
        assertEquals(1, session.query("SELECT * FROM File WHERE ecm:isProxy = 1").size());

        assertEquals(1, count("""
                MATCH (:NxDocument {`ecm:id`: $v})-[:VERSION_OF]->(:NxDocument {`ecm:id`: $live})
                RETURN count(*)""", Map.of("v", version.getId(), "live", file.getId())));
        assertEquals(1, count("""
                MATCH (:NxDocument {`ecm:id`: $p})-[:PROXY_OF]->(:NxDocument {`ecm:id`: $v})
                RETURN count(*)""", Map.of("p", proxy.getId(), "v", version.getId())));
    }

    @Test
    public void testMoveCopyDelete() {
        createFolder("/", "a");
        var b = createFolder("/", "b");
        var file = createFile("/a", "file", "f");
        file.setPropertyValue("file:content", (Serializable) blob("x", "x.txt"));
        session.saveDocument(file);
        txFeature.nextTransaction();

        session.move(file.getRef(), b.getRef(), null);
        var copy = session.copy(file.getRef(), new PathRef("/a"), "copy");
        txFeature.nextTransaction();

        assertEquals("/b/file", session.getDocument(file.getRef()).getPathAsString());
        assertEquals("/a/copy", session.getDocument(copy.getRef()).getPathAsString());
        assertEquals(1, session.query("SELECT * FROM File WHERE ecm:path STARTSWITH '/b'").size());
        assertEquals(1, count("""
                MATCH (:NxDocument {`ecm:id`: $id})-[r:CHILD_OF]->(:NxDocument {`ecm:id`: $pid})
                RETURN count(r)""", Map.of("id", file.getId(), "pid", b.getId())));
        assertEquals(1, count("MATCH (:NxDocument {`ecm:id`: $id})-[r:CHILD_OF]->() RETURN count(r)",
                Map.of("id", file.getId())));

        session.removeDocument(b.getRef());
        txFeature.nextTransaction();
        assertFalse(session.exists(file.getRef()));
        assertTrue(session.exists(copy.getRef()));
        assertEquals(0, count("MATCH (d:Nx_test {`ecm:id`: $id}) RETURN count(d)", Map.of("id", file.getId())));
        assertEquals(0, count("MATCH (c:NxComplex:Nx_test) WHERE NOT ()-[:PROP]->(c) RETURN count(c)", Map.of()));
    }

    @Test
    public void testLocks() {
        var file = createFile("/", "file", "f");
        txFeature.nextTransaction();

        assertNull(session.getLockInfo(file.getRef()));
        var lock = session.setLock(file.getRef());
        assertNotNull(lock);
        assertEquals("Administrator", session.getLockInfo(file.getRef()).getOwner());
        var removed = session.removeLock(file.getRef());
        assertEquals("Administrator", removed.getOwner());
        assertNull(session.getLockInfo(file.getRef()));
    }

    @Test
    public void testScroll() {
        for (int i = 0; i < 5; i++) {
            createFile("/", "file" + i, "f" + i);
        }
        txFeature.nextTransaction();

        var ids = new HashSet<String>();
        var result = session.scroll("SELECT * FROM File", 2, 60);
        while (result.hasResults()) {
            assertTrue(result.getResults().size() <= 2);
            ids.addAll(result.getResults());
            result = session.scroll(result.getScrollId());
        }
        assertEquals(5, ids.size());
    }

}
