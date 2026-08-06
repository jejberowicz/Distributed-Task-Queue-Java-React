FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Se copia el pom primero para que la capa de dependencias quede cacheada
# y un cambio de código no vuelva a bajar todo el repositorio.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --uid 10001 inferqueue
COPY --from=build /build/target/inferqueue-*.jar app.jar
USER inferqueue
EXPOSE 8080
# Shutdown ordenado: el worker necesita terminar los jobs en vuelo antes de
# morir, así que el proceso Java tiene que ser PID 1 y recibir el SIGTERM.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
