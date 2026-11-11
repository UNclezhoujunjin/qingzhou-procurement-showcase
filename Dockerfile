# One cloud service serves both Vue assets and Java APIs, keeping cookies and CSRF same-origin.
FROM node:22-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --registry=https://registry.npmjs.org
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build/backend
COPY backend/pom.xml backend/settings.xml ./
RUN mvn -B -s settings.xml -gs settings.xml -DskipTests dependency:go-offline
COPY backend/src/ ./src/
COPY --from=frontend /build/frontend/dist/ ./src/main/resources/static/
RUN mvn -B -s settings.xml -gs settings.xml -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /app/backups && chown -R 10001:10001 /app
COPY --from=backend /build/backend/target/procurement-0.1.0.jar /app/app.jar
ENV SPRING_PROFILES_ACTIVE=cloud
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
USER 10001
EXPOSE 10000
ENTRYPOINT ["java","-Dfile.encoding=UTF-8","-jar","/app/app.jar"]
