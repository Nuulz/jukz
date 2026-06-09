@echo off
REM Fast-launch the jukz Fabric dev client (Minecraft 1.21.1).
REM --offline skips Gradle's dependency refresh; deps are already cached after the first build.
REM Pass extra Gradle args through, e.g.  run-client.bat --info
cd /d "%~dp0"
call gradlew.bat runClient --offline %*
