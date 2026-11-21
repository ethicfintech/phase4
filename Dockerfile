# Gateway image for the Ethicfin Peppol access point (phase4 + AE TDD reporting).
# Built and deployed by .github/workflows/deploy.yml on every push to master.
# ============================================================
# Stage 1: Build the WAR using Maven + Java 17
# ============================================================
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy pom files first for dependency caching
COPY pom.xml .
COPY phase4-lib/pom.xml phase4-lib/
COPY phase4-test/pom.xml phase4-test/
COPY phase4-profile-peppol/pom.xml phase4-profile-peppol/
COPY phase4-peppol-servlet/pom.xml phase4-peppol-servlet/
COPY phase4-peppol-client/pom.xml phase4-peppol-client/
COPY phase4-peppol-server-webapp/pom.xml phase4-peppol-server-webapp/
COPY phase4-dynamic-discovery/pom.xml phase4-dynamic-discovery/
COPY phase4-profile-bdew/pom.xml phase4-profile-bdew/
COPY phase4-bdew-client/pom.xml phase4-bdew-client/
COPY phase4-profile-cef/pom.xml phase4-profile-cef/
COPY phase4-cef-client/pom.xml phase4-cef-client/
COPY phase4-profile-dbnalliance/pom.xml phase4-profile-dbnalliance/
COPY phase4-dbnalliance-client/pom.xml phase4-dbnalliance-client/
COPY phase4-dbnalliance-servlet/pom.xml phase4-dbnalliance-servlet/
COPY phase4-dbnalliance-server-webapp/pom.xml phase4-dbnalliance-server-webapp/
COPY phase4-profile-edelivery2/pom.xml phase4-profile-edelivery2/
COPY phase4-edelivery2-client/pom.xml phase4-edelivery2-client/
COPY phase4-profile-entsog/pom.xml phase4-profile-entsog/
COPY phase4-entsog-client/pom.xml phase4-entsog-client/
COPY phase4-profile-euctp/pom.xml phase4-profile-euctp/
COPY phase4-euctp-client/pom.xml phase4-euctp-client/
COPY phase4-eudamed-client/pom.xml phase4-eudamed-client/
COPY phase4-profile-hredelivery/pom.xml phase4-profile-hredelivery/
COPY phase4-hredelivery-client/pom.xml phase4-hredelivery-client/
COPY phase4-hredelivery-servlet/pom.xml phase4-hredelivery-servlet/

# Download dependencies (cached if pom files unchanged)
RUN mvn dependency:go-offline -pl phase4-peppol-server-webapp -am -q 2>/dev/null || true

# Copy all source
COPY . .

# Build
RUN mvn clean install -pl phase4-peppol-server-webapp -am -DskipTests -q

# ============================================================
# Stage 2: Run in Tomcat 10 + Java 17
# ============================================================
FROM tomcat:10.1-jdk17

RUN rm -rf /usr/local/tomcat/webapps/*

COPY --from=builder /build/phase4-peppol-server-webapp/target/phase4-peppol-server-webapp-*.war \
     /usr/local/tomcat/webapps/ROOT.war

RUN mkdir -p /data/as4/phase4-dumps /certs

EXPOSE 8080

CMD ["catalina.sh", "run"]
