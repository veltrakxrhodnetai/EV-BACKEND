@echo off
echo ===================================
echo EV CSMS - Docker Startup Script
echo ===================================
echo.

cd /d "%~dp0"

:menu
echo.
echo Select an option:
echo 1. Start Database Only (for local dev)
echo 2. Start Full Stack (Database + Backend)
echo 3. Start with Admin UI (Database + Backend + Adminer)
echo 4. Stop All Services
echo 5. View Logs
echo 6. Rebuild and Restart Backend
echo 7. Reset Database (Clean Start)
echo 8. Exit
echo.

set /p choice="Enter your choice (1-8): "

if "%choice%"=="1" goto db_only
if "%choice%"=="2" goto full_stack
if "%choice%"=="3" goto with_admin
if "%choice%"=="4" goto stop
if "%choice%"=="5" goto logs
if "%choice%"=="6" goto rebuild
if "%choice%"=="7" goto reset
if "%choice%"=="8" goto end

echo Invalid choice. Please try again.
goto menu

:db_only
echo.
echo Starting PostgreSQL database...
docker-compose up postgres -d
echo.
echo Database started at localhost:5432
echo Username: evuser
echo Password: evpass
echo Database: evcsms
pause
goto menu

:full_stack
echo.
echo Starting Full Stack (Database + Backend)...
docker-compose up -d
echo.
echo Services started:
echo - PostgreSQL: localhost:5432
echo - Backend API: localhost:8080
echo.
echo Health check: http://localhost:8080/actuator/health
pause
goto menu

:with_admin
echo.
echo Starting Full Stack with Adminer...
docker-compose --profile tools up -d
echo.
echo Services started:
echo - PostgreSQL: localhost:5432
echo - Backend API: localhost:8080
echo - Adminer DB UI: localhost:8081
echo.
echo Access Adminer at http://localhost:8081
pause
goto menu

:stop
echo.
echo Stopping all services...
docker-compose down
echo Services stopped.
pause
goto menu

:logs
echo.
echo Select logs to view:
echo 1. Backend logs
echo 2. Database logs
echo 3. All logs
echo.
set /p log_choice="Enter choice (1-3): "

if "%log_choice%"=="1" docker-compose logs -f backend
if "%log_choice%"=="2" docker-compose logs -f postgres
if "%log_choice%"=="3" docker-compose logs -f
goto menu

:rebuild
echo.
echo Rebuilding and restarting backend...
docker-compose up -d --build backend
echo Backend rebuilt and restarted.
pause
goto menu

:reset
echo.
echo WARNING: This will delete all database data!
set /p confirm="Are you sure? (yes/no): "

if /i "%confirm%"=="yes" (
    echo Stopping services and removing volumes...
    docker-compose down -v
    echo.
    echo Starting fresh database...
    docker-compose up -d
    echo Database reset complete.
) else (
    echo Reset cancelled.
)
pause
goto menu

:end
echo.
echo Goodbye!
exit /b 0
