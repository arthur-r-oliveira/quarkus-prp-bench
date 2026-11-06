# Legacy / control variant: UBI9 + JDK 17, Maven Central, and the old remediation
# habit — package update in the image. ubi9/openjdk ships microdnf (not full dnf).
# Keep JDK 17 and the microdnf path; do not swap this stage onto hi/openjdk —
# that destroys the measured comparison with Hardened Images (no package manager).
# See docs/demo-supply-chain.md.
FROM registry.access.redhat.com/ubi9/openjdk-17:1.24 AS builder
USER 0
WORKDIR /build
RUN microdnf -y update && microdnf clean all
COPY pom.xml .
COPY src src
RUN mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar

FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:1.24
USER 0
RUN microdnf -y update && microdnf clean all
# ubi9/openjdk-17-runtime defaults to UID 185; restore after the rootful update.
USER 185
WORKDIR /app
COPY --from=builder /build/target/*-runner.jar app.jar
EXPOSE 8080 9200/udp 9201/udp
ENTRYPOINT ["java", "-jar", "app.jar"]
