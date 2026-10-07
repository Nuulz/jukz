@echo off
setlocal EnableDelayedExpansion
::
:: jugar.bat — Windows equivalent of ./jugar
::
::   jugar                  asks which Minecraft version to open, rest from jugar.conf
::   jugar dev              uses Gradle dev client (offline account)
::   jugar local            starts the local Worker before opening the game
::   jugar release          uses the jar from the latest GitHub release
::   jugar 1.21.1           plays that Minecraft version
::   jugar --stop           shuts down the local Worker if it's running
::   jugar --help
::
cd /d "%~dp0"

:: ---------- read jugar.conf -------------------------------------------------------------------
set "MODO=prism"
set "SERVIDOR=produccion"
set "FORZAR_RELAY=no"
set "JAR=local"
set "COMPILAR=si"
set "INSTANCIA=jukz"
set "CUENTA="
set "NOMBRE=Player"
set "MINECRAFT=1.21.1"
set "RAM=4G"
set "CARPETA=run/play"

if exist jugar.conf (
    for /f "usebackq tokens=1,* delims==" %%A in ("jugar.conf") do (
        set "line=%%A"
        if not "!line:~0,1!"=="#" if not "%%B"=="" (
            set "%%A=%%B"
        )
    )
)

:: ---------- args ------------------------------------------------------------------------------
set "ELEGIDA=no"
:args_loop
if "%~1"=="" goto args_done
if /i "%~1"=="--help"   goto show_help
if /i "%~1"=="-h"       goto show_help
if /i "%~1"=="--stop"   goto stop_worker
if /i "%~1"=="dev"      set "MODO=dev" & shift & goto args_loop
if /i "%~1"=="prism"    set "MODO=prism" & shift & goto args_loop
if /i "%~1"=="produccion" set "SERVIDOR=produccion" & shift & goto args_loop
if /i "%~1"=="local"    set "SERVIDOR=local" & shift & goto args_loop
if /i "%~1"=="ninguno"  set "SERVIDOR=ninguno" & shift & goto args_loop
if /i "%~1"=="release"  set "JAR=release" & shift & goto args_loop
:: version number
set "_v=%~1"
if exist "fabric\versions\%_v%\" (
    set "MINECRAFT=%_v%"
    set "ELEGIDA=si"
    shift & goto args_loop
)
echo [jukz] Unknown option: %~1  (use --help)
exit /b 1
:args_done

:: ---------- help ------------------------------------------------------------------------------
:show_help
echo.
echo   jugar                  asks which Minecraft version to open
echo   jugar dev              Gradle dev client (offline account)
echo   jugar local            starts the local Worker first
echo   jugar release          uses the jar from the latest GitHub release
echo   jugar 1.21.1           plays that Minecraft version
echo   jugar --stop           shuts down the local Worker
echo.
exit /b 0

:: ---------- version picker -------------------------------------------------------------------
if "%ELEGIDA%"=="no" (
    set "_i=0"
    for /d %%D in (fabric\versions\*) do (
        set /a "_i+=1"
        set "_ver!_i!=%%~nxD"
        echo   !_i!) %%~nxD
    )
    set /p "_n=Version of Minecraft (Enter = %MINECRAFT%): "
    if not "!_n!"=="" (
        if defined "_ver!_n!" set "MINECRAFT=!_ver%_n%!"
    )
)

echo [jukz] Minecraft %MINECRAFT%

:: ---------- Worker ---------------------------------------------------------------------------
set "LOCAL_URL=http://127.0.0.1:18791"
set "WORKER_PID_FILE=.jugar-worker.pid"

:start_worker
if not "%SERVIDOR%"=="local" goto after_worker
curl -fs -m 2 "%LOCAL_URL%/healthz" >nul 2>&1
if %errorlevel%==0 (
    echo [jukz] Local Worker already running.
    goto after_worker
)
if not exist "rendezvous-worker\.dev.vars" (
    echo [jukz] Missing rendezvous-worker\.dev.vars  (see rendezvous-worker\README.md)
    exit /b 1
)
echo [jukz] Starting local Worker at %LOCAL_URL% ...
cd rendezvous-worker
start /b cmd /c "npx wrangler dev --port 18791 --local > ..\jugar-worker.log 2>&1"
cd ..
:: wait up to 30s
set "_w=0"
:wait_worker
timeout /t 1 /nobreak >nul
curl -fs -m 2 "%LOCAL_URL%/healthz" >nul 2>&1
if %errorlevel%==0 ( echo [jukz] Local Worker ready. & goto after_worker )
set /a "_w+=1"
if %_w% lss 30 goto wait_worker
echo [jukz] Local Worker did not start; check jugar-worker.log
exit /b 1
:after_worker

:: ---------- URL -------------------------------------------------------------------------------
set "URL="
if "%SERVIDOR%"=="local"      set "URL=%LOCAL_URL%"
if "%SERVIDOR%"=="ninguno"    set "URL=none"

:: ---------- dev mode --------------------------------------------------------------------------
if not "%MODO%"=="dev" goto prism_mode

:: write jukz.properties
set "PROPS=fabric\%CARPETA%\config\jukz.properties"
if not exist "fabric\%CARPETA%\config" mkdir "fabric\%CARPETA%\config"
call :write_props "%PROPS%"

echo [jukz] Opening dev client as %NOMBRE% (fabric/%CARPETA%, %RAM%) ...
call gradlew.bat ":fabric:%MINECRAFT%:runPlay" -q "-Pjukz.runDir=%CARPETA%" "-Pjukz.username=%NOMBRE%" "-Pjukz.ram=%RAM%"
if "%SERVIDOR%"=="local" call :stop_worker_fn
exit /b 0

:: ---------- prism mode -----------------------------------------------------------------------
:prism_mode
where prismlauncher >nul 2>&1
if %errorlevel% neq 0 ( echo [jukz] prismlauncher not found on PATH. & exit /b 1 )
where jq >nul 2>&1
if %errorlevel% neq 0 ( echo [jukz] jq not found on PATH. & exit /b 1 )

set "PRISM_DATA=%APPDATA%\PrismLauncher"
if not "%INSTANCIA%"=="%INSTANCIA:1.21.1=%" goto inst_set
if not "%MINECRAFT%"=="1.21.1" set "INSTANCIA=%INSTANCIA%-%MINECRAFT%"
:inst_set
set "INST=%PRISM_DATA%\instances\%INSTANCIA%"
set "MODS=%INST%\.minecraft\mods"

if not exist "%INST%" call :create_instance
if not exist "%MODS%" mkdir "%MODS%"

:: owo version from stonecutter.properties.toml
for /f "tokens=*" %%L in ('powershell -NoProfile -Command ^
    "$mc=\"[\"\"!MINECRAFT!\"\"]\"; $f=0; foreach($l in Get-Content fabric/stonecutter.properties.toml){if($l -eq $mc){$f=1}elseif($l -match '^\['){$f=0}elseif($f -and $l -match '^deps\.owo'){($l -split '=',2)[1].Trim().Trim('\"');break}}" 2^>nul') do (
    set "OWO_VERSION=%%L"
)
if "%OWO_VERSION%"=="" ( echo [jukz] Could not find deps.owo for %MINECRAFT% & exit /b 1 )

call :bajar_mod fabric-api
call :bajar_mod fabric-language-kotlin
call :bajar_mod owo-lib "%OWO_VERSION%"
call :bajar_mod modmenu

if "%JAR%"=="release" (
    call :jar_del_release
    set "JAR_PATH=!_release_jar!"
) else (
    if "%COMPILAR%"=="si" (
        echo [jukz] Compiling mod ...
        call gradlew.bat ":fabric:%MINECRAFT%:build" -x test -q
        if !errorlevel! neq 0 ( echo [jukz] Build failed. & exit /b 1 )
    )
    for /f %%J in ('dir /b /o-d "fabric\versions\%MINECRAFT%\build\libs\jukz-*.jar" 2^>nul ^| findstr /v sources') do (
        set "JAR_PATH=fabric\versions\%MINECRAFT%\build\libs\%%J"
        goto jar_found
    )
    echo [jukz] No compiled jar found. Set COMPILAR=si in jugar.conf.
    exit /b 1
    :jar_found
)

del /q "%MODS%\jukz-*.jar" 2>nul
copy /y "%JAR_PATH%" "%MODS%\" >nul

call :stop_gradle
call :write_props "%INST%\.minecraft\config\jukz.properties"

set "_args=--launch %INSTANCIA%"
if not "%CUENTA%"=="" set "_args=%_args% --profile %CUENTA%"

echo [jukz] Opening Prism: instance %INSTANCIA%, server %SERVIDOR%
start "" prismlauncher %_args%
exit /b 0

:: ============ subroutines =====================================================================

:write_props
set "_f=%~1"
if not exist "%~dp1" mkdir "%~dp1"
if not exist "%_f%" type nul > "%_f%"
call :set_prop "%_f%" "rendezvous.url" "%URL%"
set "_relay=false"
if "%FORZAR_RELAY%"=="si" set "_relay=true"
call :set_prop "%_f%" "jukz.force-relay" "%_relay%"
exit /b 0

:set_prop   file key value
powershell -NoProfile -Command ^
    "$f='%~1'; $k='%~2'; $v='%~3'; $lines=if(Test-Path $f){Get-Content $f}else{@()}; $found=$false; $out=foreach($l in $lines){if($l -match '^'+[regex]::Escape($k)+'='){\"$k=$v\";$found=$true}else{$l}}; if(-not $found){$out+=\"$k=$v\"}; $out | Set-Content $f" >nul 2>&1
exit /b 0

:create_instance
echo [jukz] Creating Prism instance "%INSTANCIA%" (Fabric %MINECRAFT%) ...
for /f %%L in ('curl -fs "https://meta.fabricmc.net/v2/versions/loader" ^| jq -r "[.[] | select(.stable)][0].version"') do set "_loader=%%L"
if "%_loader%"=="" ( echo [jukz] Could not get Fabric Loader version. & exit /b 1 )
if not exist "%MODS%" mkdir "%MODS%"
(
echo [General]
echo ConfigVersion=1.2
echo InstanceType=OneSix
echo name=%INSTANCIA%
echo iconKey=default
) > "%INST%\instance.cfg"
(
echo {
echo   "components": [
echo     { "uid": "net.minecraft", "version": "%MINECRAFT%", "important": true },
echo     { "uid": "net.fabricmc.intermediary", "version": "%MINECRAFT%", "dependencyOnly": true },
echo     { "uid": "net.fabricmc.fabric-loader", "version": "%_loader%" }
echo   ],
echo   "formatVersion": 1
echo }
) > "%INST%\mmc-pack.json"
exit /b 0

:bajar_mod   slug  [version]
set "_slug=%~1"
set "_ver=%~2"
if "%_ver%"=="" (
    set "_filtro=[.[] | select(.version_type == \"release\")][0]"
) else (
    set "_filtro=[.[] | select(.version_number == \"%_ver%\")][0]"
)
for /f "tokens=1,2" %%A in ('curl -fsG "https://api.modrinth.com/v2/project/%_slug%/version" --data-urlencode "loaders=[\"fabric\"]" --data-urlencode "game_versions=[\"%MINECRAFT%\"]" 2^>nul ^| jq -r "%_filtro% | .files | (map(select(.primary)) + .)[0] | \"\(.url) \(.filename)\""') do (
    set "_url=%%A"
    set "_name=%%B"
)
if "%_name%"=="" (
    dir /b "%MODS%\%_slug%-*.jar" >nul 2>&1 && ( echo [jukz] No Modrinth response; keeping existing %_slug%. & exit /b 0 )
    echo [jukz] Could not find %_slug% for %MINECRAFT%.
    exit /b 1
)
if exist "%MODS%\%_name%" exit /b 0
echo [jukz] Downloading %_name% ...
del /q "%MODS%\%_slug%-*.jar" 2>nul
curl -fsL "%_url%" -o "%MODS%\%_name%"
exit /b 0

:jar_del_release
set "_dir=.jugar-release"
if not exist "%_dir%" mkdir "%_dir%"
for /f "tokens=1,2" %%A in ('curl -fs "https://api.github.com/repos/Nuulz/jukz/releases/latest" ^| jq -r --arg mc "%MINECRAFT%" "([.assets[] | select(.name | endswith(\"+\" + $mc + \".jar\"))] + [.assets[] | select(.name | test(\"^jukz-[0-9.]+\\\\.jar$\"))])[0] | \"\(.browser_download_url) \(.name)\""') do (
    set "_rurl=%%A"
    set "_rname=%%B"
)
if "%_rname%"=="" (
    for /f %%J in ('dir /b /o-d "%_dir%\jukz-*.jar" 2^>nul') do ( set "_release_jar=%_dir%\%%J" & exit /b 0 )
    echo [jukz] No releases with jar on GitHub yet.
    exit /b 1
)
if not exist "%_dir%\%_rname%" (
    echo [jukz] Downloading %_rname% from latest release ...
    del /q "%_dir%\jukz-*.jar" 2>nul
    curl -fsL "%_rurl%" -o "%_dir%\%_rname%"
)
set "_release_jar=%_dir%\%_rname%"
exit /b 0

:stop_gradle
call gradlew.bat --stop -q >nul 2>&1
exit /b 0

:stop_worker
:stop_worker_fn
taskkill /f /im "wrangler*" /t >nul 2>&1
taskkill /f /im "workerd*" /t >nul 2>&1
echo [jukz] Local Worker stopped.
exit /b 0
