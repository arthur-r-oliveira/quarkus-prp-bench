FROM registry.access.redhat.com/ubi8/openjdk-17:1.20 AS builder
USER 0
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar

FROM registry.access.redhat.com/ubi8/openjdk-17-runtime:1.20
WORKDIR /app
COPY --from=builder /build/target/*-runner.jar app.jar
EXPOSE 8080 9200/udp 9201/udp
ENTRYPOINT ["java", "-jar", "app.jar"]
