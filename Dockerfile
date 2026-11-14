# 一体化镜像：前端 dist 直接打进 Spring Boot jar 的静态资源（BOOT-INF/classes/static），
# 单容器单端口跑全部 —— 不需要独立的前端/nginx 服务。
#
# 三个阶段：
#   web-build     Node 构建前端产物（web/dist）
#   backend-build Maven 打包：把 dist 拷进 calc-server/src/main/resources/static 再 package，
#                 并按 layers.idx 拆分层（dependencies / loader / snapshot / application）
#   runtime       JRE 逐层 COPY，JarLauncher 启动
#
# 本地 mvn package（不走本 Dockerfile）时 static/ 可能为空 → 产出纯后端 jar（API 可用、无界面）；
# Release 工作流与 Docker 构建总是先产 dist 再打包，产物自带界面。
FROM node:20-alpine AS web-build
WORKDIR /build/web

COPY web/package.json web/package-lock.json ./
RUN npm ci

COPY web/ .
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend-build
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
# 前端产物进 jar 静态资源：Boot 自动从 classpath:/static 托管，index.html 即工作台入口
COPY --from=web-build /build/web/dist/ calc-server/src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl calc-server -am package -DskipTests

# 按 layers.idx 拆层（Boot 2.7 用 layertools jarmode）
RUN java -Djarmode=layertools -jar /build/calc-server/target/calc-server-*.jar extract \
    --destination /extracted

FROM eclipse-temurin:17-jre
WORKDIR /app
# 四层逐层 COPY：自上而下缓存命中率递减（依赖层几乎永不失效）
COPY --from=backend-build /extracted/dependencies/ ./
COPY --from=backend-build /extracted/spring-boot-loader/ ./
COPY --from=backend-build /extracted/snapshot-dependencies/ ./
COPY --from=backend-build /extracted/application/ ./
EXPOSE 8080
# 分层启动经 JarLauncher（Boot 2.7 的 loader 包无 .launch）
ENTRYPOINT ["java", "org.springframework.boot.loader.JarLauncher"]
