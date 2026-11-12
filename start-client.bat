@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo Запуск клиента
java -cp "out;lib\jade.jar" agents.ClientMain --host localhost --port 1099 --tourists 3

pause