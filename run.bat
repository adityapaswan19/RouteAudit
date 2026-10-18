@echo off
echo Compiling RouteAudit...
if not exist out mkdir out
javac -d out src\*.java
if errorlevel 1 (
  echo.
  echo Compile failed. Make sure the JDK is installed ^(type: javac -version^).
  pause
  exit /b 1
)
echo Starting server at http://localhost:8080
start "" http://localhost:8080
java -cp out Main
pause
