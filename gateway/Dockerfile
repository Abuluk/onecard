FROM docker.1panel.live/library/eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918

RUN apk add --no-cache 'expat=2.10.0-r0'

WORKDIR /app
COPY target/*.jar app.jar

EXPOSE 8081 9081

ENTRYPOINT ["java", "-jar", "app.jar"]
