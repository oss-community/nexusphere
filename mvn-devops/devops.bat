@echo off
rem mvn-devops launcher for Windows. Runs devops.sh with Git Bash (or Cygwin bash).
setlocal

set "DEVOPS_BASH="
if exist "%ProgramFiles%\Git\bin\bash.exe" set "DEVOPS_BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined DEVOPS_BASH if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "DEVOPS_BASH=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined DEVOPS_BASH if exist "C:\cygwin64\bin\bash.exe" set "DEVOPS_BASH=C:\cygwin64\bin\bash.exe"
if not defined DEVOPS_BASH (
  for /f "delims=" %%B in ('where bash 2^>nul') do if not defined DEVOPS_BASH set "DEVOPS_BASH=%%B"
)
if not defined DEVOPS_BASH (
  echo Git Bash was not found. Install Git for Windows from https://git-scm.com/download/win
  exit /b 1
)

"%DEVOPS_BASH%" "%~dp0devops.sh" %*
exit /b %ERRORLEVEL%
