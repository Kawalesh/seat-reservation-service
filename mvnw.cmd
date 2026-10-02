@REM ----------------------------------------------------------------------------
@REM Maven Wrapper script for Windows
@REM ----------------------------------------------------------------------------

@echo off
setlocal

set "DIR=%~dp0"
if exist "%DIR%tools\apache-maven-3.9.9\bin\mvn.cmd" (
    "%DIR%tools\apache-maven-3.9.9\bin\mvn.cmd" %*
    exit /b %ERRORLEVEL%
)

mvn %*
exit /b %ERRORLEVEL%
