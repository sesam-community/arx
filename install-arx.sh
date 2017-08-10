#!/bin/bash
curl -o arx.jar http://arx.deidentifier.org/?ddownload=1921
mvn install:install-file -Dfile=arx.jar -DgroupId=org.deidentifier -DartifactId=arx -Dversion=3.6.0 -Dpackaging=jar
rm arx.jar
