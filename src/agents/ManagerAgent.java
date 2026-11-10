package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ManagerAgent extends Agent {
    private int expectedTourists = 2;
    private double capacity = 12.0;

    private final Map<String, Double> itemWeights = new LinkedHashMap<>();
    private final Map<String, String> itemOwners = new LinkedHashMap<>();
    private final Map<String, AID> itemAgents = new HashMap<>();
    private final Map<String, AID> tourists = new LinkedHashMap<>();
    private final Map<String, Double> touristWeights = new LinkedHashMap<>();

    private final Set<String> readyItems = new HashSet<>();
    private final Set<String> noImprovement = new HashSet<>();
    private final Map<String, Map<String, Double>> transactionReports = new HashMap<>();
    private final Map<String, Double> initialReports = new HashMap<>();

    private boolean distributionStarted;
    private boolean optimizationStarted;
    private boolean stopped;
    private int epoch;

    @Override
    protected void setup() {
        Object[] args = getArguments();

        if (args != null && args.length > 0) {
            try {
                expectedTourists = Integer.parseInt(args[0].toString());
            } catch (Exception ignored) {
            }
        }
        if (args != null && args.length > 1) {
            try {
                capacity = Double.parseDouble(args[1].toString().replace(',', '.'));
            } catch (Exception ignored) {
            }
        }

        loadItems();
        registerManager();

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg == null) {
                    block();
                    return;
                }
                ManagerAgent.this.handle(msg);
            }
        });

        // Менеджер сам обнаруживает туристов в DF.
        // Туристы не отправляют ему READY_TOURIST и не регистрируются у него.
        addBehaviour(new TickerBehaviour(this, 1000) {
            @Override
            protected void onTick() {
                if (stopped) return;
                discoverTourists();
                tryStartDistribution();
            }
        });

        log("Запущен. Ожидается туристов: " + expectedTourists +
                ", вместимость рюкзака: " + fmt(capacity) + " кг.");
    }

    private void handle(ACLMessage msg) {
        String content = msg.getContent() == null ? "" : msg.getContent();

        if (content.startsWith("READY_ITEM|")) {
            String[] p = content.split("\\|", 3);
            if (p.length == 3) {
                itemWeights.put(p[1], number(p[2]));
                itemOwners.putIfAbsent(p[1], null);
                itemAgents.put(p[1], msg.getSender());
                if (readyItems.add(p[1])) {
                    log("Обнаружен предмет " + p[1] +
                            ", масса " + fmt(number(p[2])) + " кг. Всего: " +
                            readyItems.size() + "/" + itemWeights.size());
                }
            }
            tryStartDistribution();

        } else if (content.startsWith("ITEM_OWNED|")) {
            String[] p = content.split("\\|", 3);
            if (p.length == 3 && itemOwners.containsKey(p[1])) {
                itemOwners.put(p[1], p[2]);
                log("Предмет " + p[1] + " теперь принадлежит туристу " + p[2]);
            }
            checkDistributionComplete();

        } else if (content.startsWith("ITEM_UNOWNED|")) {
            String[] p = content.split("\\|", 2);
            if (p.length == 2 && itemOwners.containsKey(p[1])) {
                itemOwners.put(p[1], null);
                log("Предмет " + p[1] + " временно остался без владельца.");
            }

        } else if (content.startsWith("BAG_MASS|")) {
            String[] p = content.split("\\|", 4);
            if (p.length >= 4) {
                handleBagMass(p[1], p[2], number(p[3]));
            }

        } else if (content.startsWith("NO_IMPROVEMENT|")) {
            String tourist = msg.getSender().getLocalName();
            if (!tourists.containsKey(tourist)) {
                log("Получено сообщение NO_IMPROVEMENT от неизвестного туриста " + tourist);
                return;
            }

            if (noImprovement.add(tourist)) {
                log("Турист " + tourist + " сообщил, что не нашёл улучшающих обменов. " +
                        "Отметили " + noImprovement.size() + "/" + tourists.size());
            }

            if (optimizationStarted && noImprovement.containsAll(tourists.keySet())) {
                stopSystem("все туристы исчерпали варианты улучшающих обменов");
            }
        }
    }

    private void discoverTourists() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("tourist");
        template.addServices(sd);

        try {
            DFAgentDescription[] found = DFService.search(this, template);
            for (DFAgentDescription description : found) {
                AID aid = description.getName();
                String localName = aid.getLocalName();
                if (!tourists.containsKey(localName) && tourists.size() < expectedTourists) {
                    tourists.put(localName, aid);
                    touristWeights.put(localName, 0.0);
                    log("Обнаружен турист " + localName + ". Всего: " +
                            tourists.size() + "/" + expectedTourists);
                }
            }
        } catch (FIPAException e) {
            log("Ошибка поиска туристов в DF: " + e.getMessage());
        }
    }

    private void tryStartDistribution() {
        if (distributionStarted || stopped) return;

        if (tourists.size() < expectedTourists ||
                itemWeights.isEmpty() ||
                readyItems.size() < itemWeights.size()) {
            return;
        }

        distributionStarted = true;

        String config = "DISTRIBUTION_CONFIG|" + capacity + "|" + serializeWeights();
        for (AID tourist : tourists.values()) {
            sendTo(tourist, config);
        }
        for (AID item : itemAgents.values()) {
            sendTo(item, "START_OFFER");
        }

        log("Все необходимые агенты обнаружены. Запущен первый этап распределения.");
    }

    private void checkDistributionComplete() {
        if (!distributionStarted || optimizationStarted || itemOwners.isEmpty()) return;

        for (String owner : itemOwners.values()) {
            if (owner == null) return;
        }

        optimizationStarted = true;
        touristWeights.clear();

        for (String tourist : tourists.keySet()) {
            touristWeights.put(tourist, calculateWeight(tourist));
        }

        for (Map.Entry<String, AID> entry : tourists.entrySet()) {
            sendTo(entry.getValue(),
                    "DISTRIBUTION_COMPLETE|" + serializeItemsFor(entry.getKey()));
        }

        log("Все предметы получили владельцев. Первый этап завершён.");
        log("Начинается второй этап — оптимизация распределения.");
        writeResult("начальное распределение");
    }

    private void handleBagMass(String transaction, String tourist, double weight) {
        if (!tourists.containsKey(tourist)) return;

        if ("INIT".equals(transaction)) {
            initialReports.put(tourist, weight);
            log("Получена начальная масса от " + tourist + ": " + fmt(weight) + " кг. " +
                    "Отчётов: " + initialReports.size() + "/" + tourists.size());

            if (initialReports.keySet().containsAll(tourists.keySet())) {
                touristWeights.putAll(initialReports);
                initialReports.clear();
                log("Получены начальные массы всех туристов.");
                broadcastState();
            }
            return;
        }

        Map<String, Double> reports =
                transactionReports.computeIfAbsent(transaction, key -> new LinkedHashMap<>());
        reports.put(tourist, weight);

        log("Получена новая масса от " + tourist + ": " + fmt(weight) +
                " кг. Транзакция " + transaction +
                ". Отчётов по обмену: " + reports.size() + "/2");

        // Один обмен затрагивает ровно двух туристов.
        if (reports.size() >= 2) {
            touristWeights.putAll(reports);
            transactionReports.remove(transaction);
            log("Оба участника обмена передали новые массы. Пересчитываю общее состояние.");
            broadcastState();
        }
    }

    private void broadcastState() {
        epoch++;
        noImprovement.clear();

        double avg = average();
        double diff = spread();

        String state = "GLOBAL_STATE|" + avg + "|" + diff + "|" +
                serializeTouristWeights() + "|" + epoch;

        for (AID tourist : tourists.values()) {
            sendTo(tourist, state);
        }

        log("Состояние после раунда " + epoch + ": средняя масса " +
                fmt(avg) + " кг, общий разброс " + fmt(diff) + " кг.");

        for (Map.Entry<String, Double> entry : touristWeights.entrySet()) {
            log(entry.getKey() + ": " + fmt(entry.getValue()) + " кг.");
        }

        writeResult("после оптимизации, раунд " + epoch);

        if (diff < 0.000001) {
            stopSystem("достигнуто равенство масс");
        }
    }

    private double calculateWeight(String tourist) {
        double sum = 0;
        for (Map.Entry<String, String> entry : itemOwners.entrySet()) {
            if (tourist.equals(entry.getValue())) {
                sum += itemWeights.getOrDefault(entry.getKey(), 0.0);
            }
        }
        return sum;
    }

    private double average() {
        if (touristWeights.isEmpty()) return 0;
        double sum = 0;
        for (double weight : touristWeights.values()) sum += weight;
        return sum / touristWeights.size();
    }

    private double spread() {
        if (touristWeights.isEmpty()) return 0;

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (double weight : touristWeights.values()) {
            min = Math.min(min, weight);
            max = Math.max(max, weight);
        }
        return max - min;
    }

    private String serializeItemsFor(String tourist) {
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : itemOwners.entrySet()) {
            if (tourist.equals(entry.getValue())) list.add(entry.getKey());
        }
        return list.isEmpty() ? "-" : String.join(";", list);
    }

    private String serializeWeights() {
        StringJoiner joiner = new StringJoiner(";");
        for (Map.Entry<String, Double> entry : itemWeights.entrySet()) {
            joiner.add(entry.getKey() + ":" + entry.getValue());
        }
        return joiner.toString();
    }

    private String serializeTouristWeights() {
        StringJoiner joiner = new StringJoiner(";");
        for (Map.Entry<String, Double> entry : touristWeights.entrySet()) {
            joiner.add(entry.getKey() + ":" + entry.getValue());
        }
        return joiner.toString();
    }

    private void stopSystem(String reason) {
        if (stopped) return;
        stopped = true;

        for (AID tourist : tourists.values()) {
            sendTo(tourist, "STOP|" + reason);
        }

        writeResult(reason);
        log("Работа завершена: " + reason);
    }

    private void writeResult(String reason) {
        File directory = new File("result");
        if (!directory.exists()) directory.mkdirs();

        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream("result/result.txt"), StandardCharsets.UTF_8))) {

            writer.println("=== Распределение туристического снаряжения ===");
            writer.println("Состояние: " + reason);
            writer.println();

            for (String tourist : tourists.keySet()) {
                writer.printf(Locale.US, "%s: %.2f кг%n",
                        tourist, calculateWeight(tourist));

                for (Map.Entry<String, String> entry : itemOwners.entrySet()) {
                    if (tourist.equals(entry.getValue())) {
                        writer.printf(Locale.US, "  - %s (%.2f кг)%n",
                                entry.getKey(), itemWeights.get(entry.getKey()));
                    }
                }
                writer.println();
            }

            writer.printf(Locale.US, "Средняя масса: %.2f кг%nРазброс: %.2f кг%n",
                    average(), spread());

        } catch (IOException e) {
            log("Не удалось записать result/result.txt: " + e.getMessage());
        }
    }

    private void loadItems() {
        File file = new File("data/items.txt");
        if (!file.exists()) {
            log("Не найден файл data/items.txt");
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] p = line.split("\\s+", 2);
                if (p.length == 2) {
                    itemWeights.put(p[1], number(p[0]));
                }
            }

            log("Из файла загружено предметов: " + itemWeights.size());
        } catch (Exception e) {
            log("Ошибка чтения файла предметов: " + e.getMessage());
        }
    }

    private void registerManager() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("manager");
        sd.setName("Backpack-Manager");
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            log("Не удалось зарегистрировать менеджера в DF: " + e.getMessage());
        }
    }

    private double number(String value) {
        try {
            return Double.parseDouble(value.replace(',', '.'));
        } catch (Exception e) {
            return 0;
        }
    }

    private String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private void log(String message) {
        System.out.printf("[%1$tT] [МЕНЕДЖЕР] %2$s%n", new Date(), message);
    }

    private void sendTo(AID to, String content) {
        if (to == null) return;
        ACLMessage message = new ACLMessage(ACLMessage.INFORM);
        message.addReceiver(to);
        message.setContent(content);
        send(message);
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException ignored) {
        }
    }
}
