# компиляция
javac -encoding UTF-8 -cp "lib\jade.jar" -d out src\agents\*.java
# сервер
java -cp "out;lib\jade.jar" agents.Main --tourists 3 --items data\items.txt --capacity 12 --result result\result.txt
# клиент (в другом окне)
java -cp "out;lib\jade.jar" agents.ClientMain --host localhost --port 1099 --tourists 3