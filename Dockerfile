FROM gradle:8.14.6-jdk17 AS build

USER root
WORKDIR /workspace/app
COPY . /workspace/app
RUN ./gradlew clean :bootstrap:bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/app/bootstrap/build/libs/bootstrap-0.0.1-SNAPSHOT.jar /app/app.jar
COPY boot.sh /app/boot.sh
RUN chmod +x /app/boot.sh

EXPOSE 8080
ENTRYPOINT ["/app/boot.sh"]
