@echo off
setlocal

:: jukz rendezvous server — local development runner
:: Run from anywhere: just double-click or call from the repo root.

:: ── Configuration ────────────────────────────────────────────────────────────
set PORT=8080
set RENDEZVOUS_TTL_MS=90000
set RENDEZVOUS_RATE_LIMIT_PER_MIN=120
:: Uncomment and set to enable Bearer auth:
:: set RENDEZVOUS_AUTH_TOKEN=changeme
:: ─────────────────────────────────────────────────────────────────────────────

:: Resolve the directory this .bat lives in so cargo is always run from there.
set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

echo.
echo  jukz rendezvous server
echo  Listening on http://127.0.0.1:%PORT%
echo  Lease TTL: %RENDEZVOUS_TTL_MS% ms  ^|  heartbeat every %RENDEZVOUS_TTL_MS:~0,-3%0 ms  ^(TTL/3^)
echo.
echo  Smoke test ^(run in a second terminal^):
echo    curl -s http://127.0.0.1:%PORT%/healthz
echo    curl -s -X POST http://127.0.0.1:%PORT%/v1/announce ^
echo         -H "content-type: application/json" ^
echo         -d "{\"worldId\":\"3f2504e0-4f89-11d3-9a0c-0305e82c3301\",\"token\":{\"generation\":1,\"claimEpochMillis\":1,\"nodeId\":\"abababababababababababababababab\"},\"endpoints\":[{\"host\":\"127.0.0.1\",\"port\":25565}],\"heartbeatSeq\":0}"
echo    curl -s http://127.0.0.1:%PORT%/v1/worlds/3f2504e0-4f89-11d3-9a0c-0305e82c3301
echo.
echo  Point the mod at this server:
echo    rendezvous.url=http://127.0.0.1:%PORT%
echo  in %%APPDATA%%\.minecraft\config\jukz.properties
echo.
echo  Press Ctrl+C to stop.
echo.

cargo run
endlocal
