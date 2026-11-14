# 后端镜像：多阶段构建（Maven 构建 → Spring Boot 分层解包 → JRE 运行），
# 自包含，无需宿主机预装 JDK/Maven。
#
# 分层（layered jar，2.4+ 默认 layers.idx）：dependencies / spring-boot-loader /
# snapshot-dependencies / application 四层逐层 COPY —— 业务代码改动只重建
# application 层（592K），27MB 的依赖层走 BuildKit 缓存，重建秒级。
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

# 按 layers.idx 拆层（Boot 2.7 用 layertools jarmode）
RUN java -Djarmode=layertools -jar /build/calc-server/target/calc-server-*.jar extract \
    --destination /extracted

FROM eclipse-temurin:17-jre
WORKDIR /app
# 四层逐层 COPY：自上而下缓存命中率递减（依赖层几乎永不失效）
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
EXPOSE 8080
# 分层启动经 JarLauncher（Boot 2.7 的 loader 包无 .launch）
ENTRYPOINT ["java", "org.springframework.boot.loader.JarLauncher"]
