package agents;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.ContainerController;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * СЕРВЕР. Создаёт главный контейнер JADE, менеджера и агентов-предметов (данные из файла).
 * Туристы здесь не создаются - они появляются, когда подключается клиент (ClientMain).
 *
 * Параметры (все необязательные):
 *   --tourists N      сколько туристов ждать (необязательно; без параметра — авто по тишине)
 *   --items файл      файл с предметами (по умолчанию data/items.txt)
 *   --capacity кг     вместимость рюкзака (по умолчанию 12)
 *   --result файл     файл результата (по умолчанию result/result.txt)
 *   --host адрес      IP этого компьютера, который увидят клиенты (если несколько сетевых адаптеров)
 *   --port порт       порт платформы JADE (по умолчанию 1099)
 *   --gui             открыть JADE RMA GUI
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> o = Common.parseArgs(args);
        int tourists = Integer.parseInt(o.getOrDefault("tourists", "0")); // 0 = авто (по тишине подключения)
        String itemsFile = o.getOrDefault("items", "data/items.txt");
        int capacity = Common.toGrams(o.getOrDefault("capacity", "12"));
        String resultFile = o.getOrDefault("result", "result/result.txt");
        String port = o.getOrDefault("port", "1099");
        String host = o.get("host");

        List<String[]> items = readItems(itemsFile);
        if (items.isEmpty()) {
            System.out.println("Файл " + itemsFile + " не содержит предметов.");
            return;
        }
        long total = 0;
        for (String[] it : items) {
            int g = Integer.parseInt(it[1]);
            total += g;
            if (g > capacity) {
                System.out.println("Предмет \"" + it[0] + "\" (" + Common.kg(g) + ") тяжелее вместимости рюкзака.");
                return;
            }
        }
        if (tourists > 0 && total > (long) tourists * capacity) {
            System.out.println("Суммарная масса предметов " + Common.kg(total) + " больше общей вместимости "
                    + tourists + " рюкзаков (" + Common.kg((long) tourists * capacity) + "). Увеличьте --tourists.");
            return;
        }

        Runtime rt = Runtime.instance();
        rt.setCloseVM(true);
        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN, "true");
        p.setParameter(Profile.MAIN_PORT, port);
        if (host != null) {
            p.setParameter(Profile.MAIN_HOST, host);
            p.setParameter(Profile.LOCAL_HOST, host);
        }
        if (o.containsKey("gui")) {
            p.setParameter(Profile.GUI, "true");
        }
        ContainerController main = rt.createMainContainer(p);

        printAddresses(port);
        System.out.println("Предметов в файле: " + items.size() + ", общая масса " + Common.kg(total)
                + (tourists > 0 ? ". Ждём подключения туристов: " + tourists : ". Число туристов не задано — старт по тишине подключения"));

        // менеджер создаётся первым, чтобы предметам было кому регистрироваться
        main.createNewAgent(Common.MANAGER, ManagerAgent.class.getName(),
                new Object[]{tourists, capacity, resultFile}).start();

        int i = 1;
        for (String[] it : items) {
            main.createNewAgent("item_" + (i++), ItemAgent.class.getName(),
                    new Object[]{it[0], it[1]}).start();
        }
        // Main менеджеру ничего не сообщает: предметы регистрируются сами.
    }

    /** Формат строки: название;масса_кг   (строки с # и пустые пропускаются). */
    private static List<String[]> readItems(String file) throws Exception {
        List<String[]> res = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(file), StandardCharsets.UTF_8)) {
            line = line.replace("\uFEFF", "").trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int sep = line.lastIndexOf(';');
            if (sep < 0) {
                System.out.println("Пропущена строка без ';': " + line);
                continue;
            }
            String name = line.substring(0, sep).replaceAll("[|;=]", " ").trim();
            int grams = Common.toGrams(line.substring(sep + 1));
            res.add(new String[]{name, String.valueOf(grams)});
        }
        return res;
    }

    private static void printAddresses(String port) {
        System.out.println("Сервер запущен. Клиенты должны подключаться по одному из адресов (порт " + port + "):");
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(nis)) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        System.out.println("   " + a.getHostAddress() + "   (" + ni.getDisplayName() + ")");
                    }
                }
            }
        } catch (Exception ignored) {
            // адреса не обязательны для работы
        }
    }
}
