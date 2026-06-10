@echo off
REM jukz test instance A — the HOST. Isolated run dir: fabric\run\clientA
REM Open a world here; jukz auto-hosts it. Read the share code from the pause menu -> "World info (jukz)".
cd /d "%~dp0"
call gradlew.bat runClientA --offline %*
