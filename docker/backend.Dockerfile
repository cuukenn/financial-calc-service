# 后端镜像：多阶段构建（Maven 构建 → JRE 运行），自包含，无需宿主机预装 JDK/Maven。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
COPY calc-bom/pom.xml calc-bom/pom.xml
COPY calc-common/pom.xml calc-common/pom.xml
COPY calc-domain/pom.xml calc-domain/pom.xml
COPY calc-rule/pom.xml calc-rule/pom.xml
COPY calc-engine/pom.xml calc-engine/pom.xml
COPY calc-testkit/pom.xml calc-testkit/pom.xml
COPY calc-server/pom.xml calc-server/pom.xml
# 先只拷 pom 下依赖（利用层缓存：源码变更不触发重新下载）
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline -pl calc-server -am || true

COPY calc-common calc-common
COPY calc-domain calc-domain
COPY calc-rule calc-rule
COPY calc-engine calc-engine
COPY calc-testkit calc-testkit
COPY calc-server calc-server
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl calc-server -am package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/calc-server/target/calc-server-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
