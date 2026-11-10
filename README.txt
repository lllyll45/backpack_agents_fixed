2. Скомпилируй Java-файлы
javac -encoding UTF-8 -cp "lib\jade.jar" -d out src\agents\*.java


Если ошибок нет, переходи дальше.
3. Запусти программу
Для 4 туристов:
java -cp "out;lib\jade.jar" agents.Main 4

Во втором окне запусти клиентский контейнер:
java -cp "out;lib\jade.jar" agents.Main client localhost