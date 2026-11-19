@echo off
setlocal
set "JAVA_HOME=F:\ayd\order-project-jdk21\jdk-21.0.13+9"
if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo Project JDK 21 not found at "%JAVA_HOME%" 1>&2
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
pushd "%~dp0" || exit /b 1
where mvn.cmd >nul 2>&1
if errorlevel 1 (
    call "E:\apache-maven-3.9.1\bin\mvn.cmd" "-Dmaven.repo.local=%USERPROFILE%\.m2\repository" %*
) else (
    call mvn.cmd "-Dmaven.repo.local=%USERPROFILE%\.m2\repository" %*
)
set "result=%errorlevel%"
popd
exit /b %result%
