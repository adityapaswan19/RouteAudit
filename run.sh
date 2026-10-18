#!/bin/sh
# Compile and start RouteAudit. Run from inside this folder.
mkdir -p out
javac -d out src/*.java || { echo "Compile failed. Install the JDK (javac -version)."; exit 1; }
echo "Starting server at http://localhost:8080"
java -cp out Main
