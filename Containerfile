# Legacy / control variant: UBI8 + JDK 17, Maven Central, and the old remediation
# habit — `dnf update` in the image. Do not modernise the base tag or JDK; that
# destroys the measured comparison with Hardened Images (no package manager).
# See docs/demo-supply-chain.md.
FROM registry.access.redhat.com/ubi8/openjdk-17:1.20 AS builder
USER 0
WORKDIR /build
RUN dnf -y update && dnf clean all
COPY pom.xml .
COPY src src
RUN mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar

FROM registry.access.redhat.com/ubi8/openjdk-17-runtime:1.20
USER 0
RUN dnf -y update && dnf clean all
# ubi8/openjdk-17-runtime defaults to UID 185; restore after the rootful dnf step.
USER 185
WORKDIR /app
COPY --from=builder /build/target/*-runner.jar app.jar
EXPOSE 8080 9200/udp 9201/udp
ENTRYPOINT ["java", "-jar", "app.jar"]
