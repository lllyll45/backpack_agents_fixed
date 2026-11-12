@echo off
chcp 65001 >nul
cd /d "%~dp0"

if not exist "out" mkdir out
if not exist "result" mkdir result

echo  Компиляция
javac -encoding UTF-8 -cp "lib\jade.jar" -d out src\agents\*.java
if errorlevel 1 (
    echo Ошибка компиляции.
    pause
    exit /b 1
)

echo.
echo  Запуск сервера
java -cp "out;lib\jade.jar" agents.Main --port 1099 --tourists 3 --items data\items.txt --capacity 12 --gui


pause