FROM java:8-jre-alpine

ADD target/arx-deidentifier-1.0-SNAPSHOT.jar /srv/

ENTRYPOINT ["java", "-jar", "/srv/arx-deidentifier-1.0-SNAPSHOT.jar"]


