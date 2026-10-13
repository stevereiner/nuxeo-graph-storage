# syntax=docker/dockerfile:1.7

# Nuxeo LTS 2025 server with the Web UI, its default repository in Neo4j or ArcadeDB (nuxeo-graph-storage), the rest in
# PostgreSQL. Everything is built from public source: no Nuxeo Connect account (the approach of aborroy/nuxeo-deployment,
# Apache 2.0). The versions are pinned in docker/.env. Build from the repository root (docker compose does it).

ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-21
ARG DISTRIB_IMAGE=azul/zulu-openjdk:21
ARG RUNTIME_IMAGE=oraclelinux:9-slim

# --- the Nuxeo source, at the pinned commit (use the commit of the Nuxeo clone the graph storage is built against) ---
FROM ${MAVEN_IMAGE} AS nuxeo-source

ARG NUXEO_GIT_REF

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace/nuxeo
RUN curl -fsSL "https://github.com/nuxeo/nuxeo/archive/${NUXEO_GIT_REF}.tar.gz" -o /tmp/nuxeo.tar.gz \
 && tar -xzf /tmp/nuxeo.tar.gz --strip-components=1 \
 && rm /tmp/nuxeo.tar.gz

# --- the Nuxeo server distribution, and the Nuxeo artifacts the graph storage builds against (shared Maven cache) ---
FROM nuxeo-source AS nuxeo-build

ARG BUILD_THREADS=6
ENV MAVEN_OPTS="-Xmx4g -Xms2g -XX:+TieredCompilation -XX:TieredStopAtLevel=1"

RUN --mount=type=cache,id=nuxeo-graph-maven,target=/root/.m2 \
    mvn -B -nsu install -N \
 && mvn -B -nsu install -N -f parent/pom.xml \
 && mvn -B -nsu install -Pdistrib -DskipTests -Dnuxeo.skip.enforcer=true -T"${BUILD_THREADS}" \
      -pl server/nuxeo-server-tomcat,modules/core/nuxeo-core-test,modules/core/nuxeo-core-storage-mem -am \
 && mkdir -p /out \
 && cp server/nuxeo-server-tomcat/target/nuxeo-server-tomcat-*.zip /out/nuxeo-server-tomcat.zip \
 && mvn -B -nsu -q -N -f parent/pom.xml help:evaluate -Dexpression=project.version -DforceStdout > /out/nuxeo-version.txt
# the BOMs, imported by the test dependencies of the graph storage (a separate step: the server build stays cached)
RUN --mount=type=cache,id=nuxeo-graph-maven,target=/root/.m2 \
    mvn -B -nsu install -f boms/pom.xml
# the SQL audit package (audit in PostgreSQL; without it, Nuxeo keeps the audit in memory)
RUN --mount=type=cache,id=nuxeo-graph-maven,target=/root/.m2 \
    mvn -B -nsu install -Pdistrib -DskipTests -Dnuxeo.skip.enforcer=true \
      -pl packages,modules/platform/nuxeo-platform-audit/nuxeo-platform-audit-sql,packages/nuxeo-audit-sql-package \
 && cp packages/nuxeo-audit-sql-package/target/nuxeo-audit-sql-package-*.zip /out/nuxeo-audit-sql-package.zip
# the OpenSearch search client package (search and fulltext in OpenSearch), with the modules it brings
RUN --mount=type=cache,id=nuxeo-graph-maven,target=/root/.m2 \
    mvn -B -nsu install -Pdistrib -DskipTests -Dnuxeo.skip.enforcer=true \
      -pl modules/runtime/nuxeo-runtime-opensearch1-embed,modules/runtime/nuxeo-runtime-opensearch1,modules/core/nuxeo-core-search-clients/nuxeo-search-client-opensearch1,modules/platform/nuxeo-platform-audit/nuxeo-platform-audit-opensearch1,modules/platform/rest-api/nuxeo-rest-opensearch1-passthrough,packages/nuxeo-search-client-opensearch1-package \
 && cp packages/nuxeo-search-client-opensearch1-package/target/nuxeo-search-client-opensearch1-package-*.zip \
      /out/nuxeo-search-client-opensearch1-package.zip

# --- the graph storage bundles, and their libraries ---
FROM ${MAVEN_IMAGE} AS graph-build

WORKDIR /workspace/graph
COPY pom.xml ./
COPY nuxeo-core-storage-graph nuxeo-core-storage-graph
COPY nuxeo-core-storage-neo4j nuxeo-core-storage-neo4j
COPY nuxeo-core-storage-arcadedb nuxeo-core-storage-arcadedb
COPY nuxeo-graph-storage-core-tests nuxeo-graph-storage-core-tests
# after the Nuxeo build: it fills the shared Maven cache with the Nuxeo artifacts. Docker can clear that cache while
# keeping the Nuxeo build's layers: the Nuxeo build is then skipped, and its artifacts are missing here
COPY --from=nuxeo-build /out/nuxeo-version.txt /tmp/nuxeo-version.txt
RUN --mount=type=cache,id=nuxeo-graph-maven,target=/root/.m2 \
    version="$(tr -d '[:space:]' < /tmp/nuxeo-version.txt)" \
 && if [ ! -d "/root/.m2/repository/org/nuxeo/nuxeo-ecm/${version}" ]; then \
      echo "The Nuxeo ${version} artifacts are not in Docker's Maven cache (cleared since the Nuxeo build ran)." \
           "Rebuild the Nuxeo stage too: docker build --no-cache-filter nuxeo-build ... (see the README)" >&2; \
      exit 1; \
    fi \
 && mvn -B -nsu install -DskipTests \
      -pl .,nuxeo-core-storage-graph,nuxeo-core-storage-neo4j,nuxeo-core-storage-arcadedb \
 && mvn -B -nsu dependency:copy-dependencies -DincludeScope=runtime -DexcludeGroupIds=org.nuxeo,org.apache.tomcat,org.apache.tomcat.embed \
      -DoutputDirectory=/out/lib -pl nuxeo-core-storage-neo4j,nuxeo-core-storage-arcadedb \
 && mkdir -p /out/bundles \
 && cp nuxeo-core-storage-*/target/nuxeo-core-storage-*-[0-9]*.jar /out/bundles/ \
 && rm -f /out/bundles/*-tests.jar /out/lib/nuxeo-core-storage-*.jar

# --- the custom content models of config/models (XML contributions and their XSD files), as a bundle ---
FROM ${MAVEN_IMAGE} AS models-build

WORKDIR /workspace/models
COPY config/models/ ./
RUN mkdir -p /out/models \
 && if ls ./*.xml > /dev/null 2>&1; then \
      mkdir -p /tmp/bundle/META-INF /tmp/bundle/OSGI-INF \
      && cp ./*.xml /tmp/bundle/OSGI-INF/ \
      && find . -mindepth 1 -maxdepth 1 -type d -exec cp -r {} /tmp/bundle/ \; \
      && { printf '%s\n' 'Manifest-Version: 1.0' 'Bundle-ManifestVersion: 1' 'Bundle-Name: Custom content models' \
             'Bundle-SymbolicName: org.integratedsemantics.nuxeo.custom.models;singleton:=true'; \
           ls ./*.xml | sed 's|^\./||' | sort \
             | awk 'NR == 1 { printf "Nuxeo-Component: OSGI-INF/%s", $0; next } { printf ",\n OSGI-INF/%s", $0 } END { print "" }'; \
         } > /tmp/bundle/META-INF/MANIFEST.MF \
      && cd /tmp/bundle \
      && jar cfm /out/models/nuxeo-custom-models.jar META-INF/MANIFEST.MF $(ls | grep -v '^META-INF$') \
      && echo "custom models bundle:" && cat META-INF/MANIFEST.MF; \
    else echo "no custom models"; fi

# --- the Web UI marketplace package, from public source, for the Nuxeo version just built ---
FROM ${MAVEN_IMAGE} AS web-ui-build

ARG NUXEO_WEBUI_GIT_REF

# Node 20: the Web UI build needs Array.prototype.toSorted()
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
 && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace/web-ui
RUN curl -fsSL "https://github.com/nuxeo/nuxeo-web-ui/archive/${NUXEO_WEBUI_GIT_REF}.tar.gz" -o /tmp/web-ui.tar.gz \
 && tar -xzf /tmp/web-ui.tar.gz --strip-components=1 \
 && rm /tmp/web-ui.tar.gz

# the Web UI's parent is a released nuxeo-parent (behind Nuxeo Connect): use the one just built instead
COPY --from=nuxeo-build /out/nuxeo-version.txt /tmp/nuxeo-version.txt
RUN version="$(tr -d '[:space:]' < /tmp/nuxeo-version.txt)" \
 && test -n "$version" \
 && sed -i "/nuxeo-parent/{n;s|<version>[^<]*</version>|<version>${version}</version>|}" pom.xml

RUN --mount=type=cache,id=nuxeo-graph-maven,target=/root/.m2 \
    printf '%s\n' \
      '<settings>' \
      '  <mirrors><mirror><id>nuxeo-public-mirror</id><mirrorOf>maven-internal</mirrorOf>' \
      '    <url>https://packages.nuxeo.com/repository/maven-public/</url></mirror></mirrors>' \
      '  <profiles><profile><id>nuxeo-public</id>' \
      '    <repositories><repository><id>nuxeo-public</id>' \
      '      <url>https://packages.nuxeo.com/repository/maven-public/</url></repository></repositories>' \
      '    <pluginRepositories><pluginRepository><id>nuxeo-public</id>' \
      '      <url>https://packages.nuxeo.com/repository/maven-public/</url></pluginRepository></pluginRepositories>' \
      '  </profile></profiles>' \
      '  <activeProfiles><activeProfile>nuxeo-public</activeProfile></activeProfiles>' \
      '</settings>' > /tmp/settings.xml \
 && mvn -B -nsu -s /tmp/settings.xml install -DskipTests -DskipITs -pl plugin/web-ui/marketplace -am \
 && mkdir -p /out \
 && cp plugin/web-ui/marketplace/target/nuxeo-web-ui-marketplace-*.zip /out/nuxeo-web-ui-marketplace.zip

# --- the unpacked server ---
FROM ${DISTRIB_IMAGE} AS distribution

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*

COPY --from=nuxeo-build /out/nuxeo-server-tomcat.zip /tmp/nuxeo.zip
RUN mkdir /tmp/unzipped \
 && unzip -q -d /tmp/unzipped /tmp/nuxeo.zip \
 && mv /tmp/unzipped/* /distrib \
 && sed -i -e "s/^org.nuxeo.distribution.package.*/org.nuxeo.distribution.package=docker/" \
      /distrib/templates/common/config/distribution.properties \
 && mkdir -p /distrib/packages/backup /distrib/packages/store /distrib/packages/tmp \
 && chmod +x /distrib/bin/*ctl /distrib/bin/*.sh \
 && chmod -R g+rwX /distrib

# --- the runtime image: Nuxeo's own Docker layout (from its source) ---
FROM ${RUNTIME_IMAGE}

ARG NUXEO_GIT_REF
LABEL org.opencontainers.image.title="Nuxeo with graph storage"
LABEL org.opencontainers.image.description="Nuxeo LTS 2025 with the Web UI, its repository in Neo4j or ArcadeDB"
LABEL org.nuxeo.source.revision="${NUXEO_GIT_REF}"

COPY --from=nuxeo-source /workspace/nuxeo/docker/nuxeo/nuxeo-public.repo /etc/yum.repos.d/nuxeo-public.repo

# Java 21, and the tools of thumbnails and PDF previews (no FFmpeg: no video conversions)
RUN rpm --import https://repos.azulsystems.com/RPM-GPG-KEY-azulsystems \
 && rpm --install https://cdn.azul.com/zulu/bin/zulu-repo-1.0.0-1.noarch.rpm \
 && microdnf -y update \
 && microdnf -y install dnf shadow-utils \
 && dnf -y install epel-release \
 && dnf -y install ImageMagick ghostscript poppler-utils findutils procps unzip wget zulu21-jre-headless \
 && dnf clean all \
 && rm -rf /var/cache /var/tmp/*

# LibreOffice (office and text documents to PDF: previews, conversions), the version of Nuxeo's own image, from The
# Document Foundation's archive; Nuxeo runs it as the soffice command. Only for amd64, like Nuxeo's image.
ARG LIBREOFFICE_VERSION=7.5.9.2
ARG TARGETPLATFORM
RUN if [ "${TARGETPLATFORM:-linux/amd64}" = "linux/amd64" ]; then \
      dnf -y install tar \
      && curl -fsSL "https://downloadarchive.documentfoundation.org/libreoffice/old/${LIBREOFFICE_VERSION}/rpm/x86_64/LibreOffice_${LIBREOFFICE_VERSION}_Linux_x86-64_rpm.tar.gz" \
        | tar -C /tmp -xz \
      && dnf -y install /tmp/LibreOffice_${LIBREOFFICE_VERSION}*/RPMS/*.rpm \
      && home="/opt/libreoffice$(echo "${LIBREOFFICE_VERSION}" | cut -f 1,2 -d .)" \
      && ln -s "${home}/program/soffice" /usr/bin/soffice \
      && ln -s "${home}/program/soffice" /usr/bin/libreoffice \
      && rm -rf /tmp/LibreOffice_* \
      && dnf clean all \
      && rm -rf /var/cache /var/tmp/*; \
    fi

ENV JAVA_HOME=/usr/lib/jvm/java-21-zulu-openjdk-ca
ENV PATH=${JAVA_HOME}/bin:${PATH}

RUN chmod g=u /etc/passwd \
 && useradd -m -d /home/nuxeo -u 900 -s /bin/bash nuxeo

ENV LANG=en_US.utf8
ENV NUXEO_USER=nuxeo
ENV NUXEO_HOME=/opt/nuxeo/server
ENV NUXEO_CONF=/etc/nuxeo/nuxeo.conf
ENV PATH=${NUXEO_HOME}/bin:${PATH}

COPY --from=nuxeo-source --chown=900:0 /workspace/nuxeo/docker/nuxeo/rootfs/ /
COPY --from=distribution --chown=900:0 /distrib ${NUXEO_HOME}

# the graph storage: bundles, the libraries the server doesn't have yet (same versions: the build uses Nuxeo's), the
# templates, and the configuration from environment variables
COPY --from=graph-build --chown=900:0 /out/bundles/ ${NUXEO_HOME}/nxserver/bundles/
COPY --from=graph-build /out/lib/ /tmp/graph-lib/
RUN cd /tmp/graph-lib \
 && group_of() { unzip -p "$1" 'META-INF/maven/*/pom.properties' 2>/dev/null | sed -n 's/^groupId=//p' | head -1; } \
 && for jar in *.jar; do \
      base="$(echo "$jar" | sed -E 's/-[0-9][^/]*\.jar$//')"; \
      found=""; \
      for f in $(ls -d "${NUXEO_HOME}"/lib/"${base}"-[0-9]*.jar "${NUXEO_HOME}"/nxserver/lib/"${base}"-[0-9]*.jar \
                   "${NUXEO_HOME}"/nxserver/bundles/"${base}"-[0-9]*.jar 2>/dev/null); do \
        if [ "${f##*/}" = "$jar" ] || [ "$(group_of "$f")" = "$(group_of "$jar")" ]; then found="$f"; break; fi; \
      done; \
      if [ -n "$found" ]; then echo "server has ${found##*/}, skipping $jar"; \
      else cp "$jar" "${NUXEO_HOME}/nxserver/lib/"; echo "added $jar"; fi; \
    done \
 && rm -rf /tmp/graph-lib \
 && chown -R 900:0 "${NUXEO_HOME}/nxserver/lib"
COPY --chown=900:0 templates/ ${NUXEO_HOME}/templates/

# the Web UI, the SQL audit and the OpenSearch search client (their templates, sql-audit and opensearch1-search-client,
# are enabled through NUXEO_TEMPLATES in conf.d: this nuxeo.conf is temporary)
COPY --from=web-ui-build /out/nuxeo-web-ui-marketplace.zip /tmp/packages/nuxeo-web-ui-marketplace.zip
COPY --from=nuxeo-build /out/nuxeo-audit-sql-package.zip /out/nuxeo-search-client-opensearch1-package.zip /tmp/packages/
RUN chmod +x /docker-entrypoint.sh /install-packages.sh /nuxeo-run-dev.sh \
 && mkdir -p /etc/nuxeo /var/lib/nuxeo /var/log/nuxeo \
 && printf 'nuxeo.home=%s\nnuxeo.data.dir=/var/lib/nuxeo\nnuxeo.log.dir=/var/log/nuxeo\nnuxeo.tmp.dir=/tmp\nJAVA_HOME=%s\n' \
      "${NUXEO_HOME}" "${JAVA_HOME}" > /etc/nuxeo/nuxeo.conf \
 && "${NUXEO_HOME}/bin/nuxeoctl" mp-install /tmp/packages/nuxeo-web-ui-marketplace.zip \
      /tmp/packages/nuxeo-audit-sql-package.zip /tmp/packages/nuxeo-search-client-opensearch1-package.zip \
      --accept=true --relax=true \
 && rm -rf /tmp/packages /etc/nuxeo/nuxeo.conf \
 && chown -R 900:0 "${NUXEO_HOME}" /etc/nuxeo /var/lib/nuxeo /var/log/nuxeo

# last, so that changing them rebuilds only these layers: the configuration (compose also mounts config/conf.d, so that
# it can change without a rebuild), and the custom content models of config/models
COPY --chown=900:0 config/conf.d/ /etc/nuxeo/conf.d/
COPY --from=models-build --chown=900:0 /out/models/ ${NUXEO_HOME}/nxserver/bundles/

VOLUME /var/lib/nuxeo
VOLUME /var/log/nuxeo

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=240s --retries=10 \
  CMD wget -q -O /dev/null http://localhost:8080/nuxeo/runningstatus || exit 1

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["nuxeoctl", "console"]

USER 900
