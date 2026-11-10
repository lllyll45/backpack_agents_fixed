package agents;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        int tourists = 4;
        boolean isMainContainer = true;
        String mainHost = "localhost";
        double capacity = 12.0;

        if (args.length >= 1) {
            try {
                tourists = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Некорректное количество туристов.");
                return;
            }
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("client")) {
            isMainContainer = false;
        }

        if (args.length >= 3 && !isMainContainer) {
            mainHost = args[2];
        }

        if (args.length >= 2 && isMainContainer) {
            try {
                capacity = Double.parseDouble(args[1].replace(',', '.'));
            } catch (NumberFormatException e) {
                System.err.println("Некорректная вместимость рюкзака.");
                return;
            }
        }

        if (tourists < 1) {
            System.err.println("Количество туристов должно быть не меньше одного.");
            return;
        }

        if (capacity <= 0) {
            System.err.println("Вместимость рюкзака должна быть положительной.");
            return;
        }

        String prefix = getComputerPrefix();
        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        StringBuilder agents = new StringBuilder();

        if (isMainContainer) {
            List<String> items;

            try {
                Path itemsPath = Path.of("data", "items.txt");
                if (!Files.exists(itemsPath)) {
                    System.err.println("Файл data/items.txt не найден.");
                    return;
                }

                items = Files.readAllLines(itemsPath)
                        .stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .filter(line -> !line.startsWith("#"))
                        .toList();

            } catch (Exception e) {
                System.err.println("Ошибка чтения файла предметов: " + e.getMessage());
                return;
            }

            if (items.isEmpty()) {
                System.err.println("Файл items.txt не содержит предметов.");
                return;
            }

            agents.append("manager:agents.ManagerAgent(")
                    .append(tourists)
                    .append(",")
                    .append(capacity)
                    .append(")");

            for (String item : items) {
                String[] parts = item.split("\\s+", 2);
                if (parts.length != 2) {
                    System.err.println("Некорректная строка в items.txt: " + item);
                    return;
                }

                double weight;
                try {
                    weight = Double.parseDouble(parts[0].replace(',', '.'));
                } catch (NumberFormatException e) {
                    System.err.println("Некорректная масса предмета: " + item);
                    return;
                }

                String name = parts[1].trim();
                if (weight <= 0 || name.isEmpty()) {
                    System.err.println("Некорректные данные предмета: " + item);
                    return;
                }

                String agentName = name.replaceAll("[^a-zA-Zа-яА-Я0-9_]", "_");
                agents.append(";item_")
                        .append(agentName)
                        .append(":agents.ItemAgent(")
                        .append(weight)
                        .append(")");
            }
        } else {
            for (int i = 1; i <= tourists; i++) {
                if (agents.length() > 0) agents.append(';');

                agents.append(prefix)
                        .append("_tourist_")
                        .append(suffix)
                        .append('_')
                        .append(i)
                        .append(":agents.TouristAgent");
            }
        }

        List<String> jadeArgs = new ArrayList<>();

        if (isMainContainer) {
            jadeArgs.add("-gui");
        } else {
            jadeArgs.add("-container");
            jadeArgs.add("-container-name");
            jadeArgs.add("Client_" + suffix);
            jadeArgs.add("-host");
            jadeArgs.add(mainHost);
        }

        jadeArgs.add("-agents");
        jadeArgs.add(agents.toString());

        System.out.println("Компьютер: " + prefix);
        System.out.println("Режим: " + (isMainContainer ? "СЕРВЕР" : "КЛИЕНТ"));
        System.out.println("Количество туристов: " + tourists);
        System.out.println("Вместимость рюкзака: " + capacity + " кг");
        System.out.println("Запускаемые агенты: " + agents);

        jade.Boot.main(jadeArgs.toArray(new String[0]));
    }

    private static String getComputerPrefix() {
        try {
            return InetAddress.getLocalHost()
                    .getHostName()
                    .replaceAll("[^a-zA-Z0-9]", "");
        } catch (Exception e) {
            return "PC";
        }
    }
}
