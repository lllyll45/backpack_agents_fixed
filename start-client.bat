@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo Запуск клиента
java -cp "out;lib\jade.jar" agents.ClientMain --host 172.20.10.3 --local-host 172.20.10.2 --port 1099 --tourists 6

pause
