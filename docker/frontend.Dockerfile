# 前端镜像：Node 构建静态产物 → nginx 托管并反代 /api 到后端容器。
FROM node:20-alpine AS build
WORKDIR /build

COPY web/package.json web/package-lock.json ./
RUN npm ci

COPY web/ .
RUN npm run build

FROM nginx:alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
