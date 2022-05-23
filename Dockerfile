FROM openjdk:14-slim
LABEL org.opencontainers.image.source "https://github.com/cghislai/resourceswatcher"

ADD target/resourcewatcher-jar-with-dependencies.jar /resourcewatcher.jar

ENTRYPOINT [ "/usr/local/openjdk-14/bin/java" , "-jar" ,  "/resourcewatcher.jar"]
