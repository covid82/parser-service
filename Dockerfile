FROM openjdk:8-jre-alpine
RUN mkdir -p /opt/app
WORKDIR /opt/app
COPY ./target/scala-2.13/app.jar .
COPY ./start.sh .
ENTRYPOINT ["./start.sh"]