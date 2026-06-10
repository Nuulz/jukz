@echo off
REM jukz test instance B — the GUEST. Isolated run dir: fabric\run\clientB
REM From the title screen use "Join via jukz" and paste the code that instance A is showing.
cd /d "%~dp0"
call gradlew.bat runClientB --offline %*
