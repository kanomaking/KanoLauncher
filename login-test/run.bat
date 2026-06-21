@echo off
REM KanoLauncher login tester - compiles and runs LoginTest.java
cd /d "%~dp0"
echo Compiling...
javac -cp "lib\gson-2.11.0.jar" -d out LoginTest.java
if errorlevel 1 (
  echo.
  echo Compile failed. Make sure Java 21+ is installed ^(run: java -version^).
  pause
  exit /b 1
)
echo Running...
echo.
java -cp "out;lib\gson-2.11.0.jar" LoginTest
echo.
pause
