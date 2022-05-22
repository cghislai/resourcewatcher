FROM openjdk:14-slim

ADD target/resourcewatcher-jar-with-dependencies.jar /resourcewatcher.jar

ENTRYPOINT [ "/usr/local/openjdk-14/bin/java" , "-jar" ,  "/resourcewatcher.jar"]
