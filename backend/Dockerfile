FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml settings.xml ./
COPY src ./src
RUN mvn -B -s settings.xml -gs settings.xml -DskipTests package
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /app/data && chown -R 10001:10001 /app
COPY --from=build /app/target/procurement-0.1.0.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-Dfile.encoding=UTF-8","-jar","app.jar"]
