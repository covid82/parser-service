#!/usr/bin/env sh
git pull
set SBT_OPTS="-Xms512M -Xmx1024M -Xss2M -XX:MaxMetaspaceSize=1024M" sbt
sbt assembly
docker build -t parser-service .
docker tag parser-service:latest 489683348645.dkr.ecr.eu-west-1.amazonaws.com/parser-service:latest
docker push 489683348645.dkr.ecr.eu-west-1.amazonaws.com/parser-service:latest