
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY render.yaml .
COPY src ./src
RUN mvn -B -ntp clean verify

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV SPRING_PROFILES_ACTIVE=prod
COPY --from=build /build/target/dukankhata-backend-0.0.1-SNAPSHOT.jar app.jar
RUN useradd --system --uid 10001 --user-group appuser \
    && usermod --append --groups 1000 appuser
USER appuser
EXPOSE 8080
ENTRYPOINT ["java","-Duser.timezone=Asia/Kolkata","-jar","app.jar"]
