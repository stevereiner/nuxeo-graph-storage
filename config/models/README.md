# Custom content models

Nuxeo XML contributions placed here (schemas, facets, document types, ...) are packaged into a bundle when the Docker
image is built (`org.integratedsemantics.nuxeo.custom.models`), so they are deployed on every start:

- every `*.xml` file in this folder is a component, listed in the bundle's `Nuxeo-Component` header;
- `schema/` holds the XSD files the contributions reference (`src="schema/<name>.xsd"`).

With no XML file here, no bundle is built. Rebuild the image after a change:
`docker compose -f docker/compose.neo4j.yaml build` (or `compose.arcadedb.yaml`: the same image).

Example, `my-model-contrib.xml`, with `schema/my-schema.xsd`:

```xml
<?xml version="1.0"?>
<component name="my.model">
  <require>org.nuxeo.ecm.core.CoreExtensions</require>

  <extension target="org.nuxeo.ecm.core.schema.TypeService" point="schema">
    <schema name="my-schema" prefix="my" src="schema/my-schema.xsd" />
  </extension>

  <extension target="org.nuxeo.ecm.core.schema.TypeService" point="doctype">
    <facet name="MyFacet">
      <schema name="my-schema" />
    </facet>
    <doctype name="MyDocument" extends="File">
      <schema name="my-schema" />
    </doctype>
  </extension>
</component>
```

Documents of a new type also need the Web UI's layouts and creation menu to show up there, as with any Nuxeo custom
type.
