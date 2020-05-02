FROM openjdk:8-jre-alpine
RUN mkdir -p /opt/app
WORKDIR /opt/app
COPY ./target/scala-2.13/app.jar .
RUN chmod u+x /start.sh
COPY ./start.sh .
ENTRYPOINT ["./start.sh"]