package agents;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.ContainerController;

import java.net.InetAddress;
import java.util.Map;

/**
 * Параметры:
 *   --host адрес       IP/имя компьютера с сервером (по умолчанию localhost)
 *   --port порт        порт платформы (по умолчанию 1099)
 *   --tourists K       сколько туристов создаёт этот клиент (по умолчанию 1)
 *   --name префикс     префикс имён туристов (по умолчанию имя компьютера + номер процесса)
 *   --local-host адрес собственный IP клиента, если у компьютера несколько адаптеров
 */
public class ClientMain {

    public static void main(String[] args) throws Exception {
        Map<String, String> o = Common.parseArgs(args);
        String host = o.getOrDefault("host", "localhost");
        String port = o.getOrDefault("port", "1099");
        int count = Integer.parseInt(o.getOrDefault("tourists", "1"));

        String prefix = o.get("name");
        if (prefix == null) {
            String pc;
            try {
                pc = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                pc = "pc";
            }
            prefix = "Tourist_" + pc + "_" + ProcessHandle.current().pid();
        }
        prefix = prefix.replaceAll("[^A-Za-z0-9_\\-]", "_");

        Runtime rt = Runtime.instance();
        rt.setCloseVM(true);
        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, host);
        p.setParameter(Profile.MAIN_PORT, port);
        if (o.containsKey("local-host")) {
            p.setParameter(Profile.LOCAL_HOST, o.get("local-host"));
        }

        System.out.println("Подключение к серверу " + host + ":" + port + " ...");
        ContainerController cc = rt.createAgentContainer(p);
        if (cc == null) {
            System.out.println("Не удалось подключиться к серверу.");
            return;
        }
        for (int i = 1; i <= count; i++) {
            cc.createNewAgent(prefix + "_" + i, TouristAgent.class.getName(), null).start();
        }
        System.out.println("Создано туристов: " + count + ". Ждём команд менеджера...");
    }
}
