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

import java.util.*;

public class TouristAgent extends Agent {
    private final List<String> myItems = new ArrayList<>();
    private final Map<String, Double> itemWeights = new HashMap<>();
    private final Map<String, Double> allTouristWeights = new LinkedHashMap<>();
    private final Set<String> triedPartners = new HashSet<>();

    private AID manager;
    private double capacity = 12.0;
    private double myWeight;
    private double avgWeight;
    private double currentDiff;
    private int epoch;

    private boolean distributionConfigured;
    private boolean distributionComplete;
    private boolean optimizationStarted;
    private boolean pendingDistribution;
    private boolean reportedNoImprovement;
    private boolean stopped;

    private String pendingNewItem;
    private String pendingOldItem;
    private AID pendingNewItemAgent;

    private String transaction;
    private String role;
    private String partnerName;
    private String ownGive;
    private String partnerGive;
    private double ownGiveWeight;
    private double partnerGiveWeight;

    private boolean waitingWeight;
    private boolean waitingOwnLock;
    private boolean waitingPartnerLock;
    private boolean waitingPartnerTransfer;
    private boolean waitingOwnTransfer;

    @Override
    protected void setup() {
        registerInDF();

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg == null) {
                    block();
                    return;
                }
                TouristAgent.this.handle(msg);
            }
        });

        addBehaviour(new TickerBehaviour(this, 1000) {
            @Override
            protected void onTick() {
                if (manager == null) {
                    manager = findManager();
                }
                if (stopped || !optimizationStarted || busy() || reportedNoImprovement) {
                    return;
                }
                tryInitiateExchange();
            }
        });

        log("Запущен. Ожидает настройки от менеджера.");
    }

    private void handle(ACLMessage msg) {
        String content = msg.getContent() == null ? "" : msg.getContent();
        String sender = msg.getSender().getLocalName();

        if (content.startsWith("DISTRIBUTION_CONFIG|")) {
            // Сообщение с конфигурацией приходит от менеджера.
            // Сохраняем его AID до отправки первого отчёта INIT.
            manager = msg.getSender();
            String[] p = content.split("\\|", 3);
            if (p.length == 3) {
                capacity = number(p[1]);
                parseWeights(p[2]);
                distributionConfigured = true;
                log("Получены настройки распределения. Вместимость рюкзака: " +
                        fmt(capacity) + " кг. Известно предметов: " + itemWeights.size());
            }

        } else if (content.startsWith("GET_BAG_SIGNATURE|")) {
            String item = content.substring("GET_BAG_SIGNATURE|".length());
            sendTo(msg.getSender(), "BAG_SIGNATURE|" + item + "|" + bagSignature());

        } else if (content.startsWith("OFFER_ITEM|")) {
            handleItemOffer(msg, content);

        } else if (content.startsWith("ITEM_RELEASED|")) {
            if (pendingDistribution) {
                log("Освободил предмет " + pendingOldItem +
                        ". Подтверждаю принятие " + pendingNewItem);
                myItems.remove(pendingOldItem);
                pendingOldItem = null;
                sendTo(pendingNewItemAgent, "TAKE_ITEM|" + pendingNewItem);
            }

        } else if (content.startsWith("ITEM_RELEASE_REFUSED|")) {
            log("Не удалось освободить " + pendingOldItem +
                    ". Предложение нового предмета отклонено.");
            resetPendingDistribution();

        } else if (content.startsWith("ITEM_ACCEPTED|")) {
            String[] p = content.split("\\|", 3);
            if (p.length == 3) {
                if (!myItems.contains(p[1])) myItems.add(p[1]);
                itemWeights.put(p[1], number(p[2]));
                recalculateWeight();
                log("ПРИНЯЛ предмет " + p[1] + ". Масса рюкзака: " + fmt(myWeight) + " кг");
                resetPendingDistribution();
            }

        } else if (content.startsWith("DISTRIBUTION_COMPLETE|")) {
            String items = content.substring("DISTRIBUTION_COMPLETE|".length());
            myItems.clear();
            if (!items.equals("-")) myItems.addAll(Arrays.asList(items.split(";")));
            recalculateWeight();
            distributionComplete = true;
            sendBagMass("INIT");
            log("Первоначальное распределение завершено. Предметов: " +
                    myItems.size() + ", масса рюкзака: " + fmt(myWeight) + " кг");

        } else if (content.startsWith("GLOBAL_STATE|")) {
            String[] p = content.split("\\|", 5);
            if (p.length >= 5) {
                avgWeight = number(p[1]);
                currentDiff = number(p[2]);
                parseTouristWeights(p[3]);
                epoch = (int) number(p[4]);
                myWeight = allTouristWeights.getOrDefault(getLocalName(), myWeight);
                optimizationStarted = true;
                triedPartners.clear();
                reportedNoImprovement = false;
                clearExchange();
                log("Получено состояние оптимизации №" + epoch +
                        ". Моя масса: " + fmt(myWeight) +
                        " кг; средняя: " + fmt(avgWeight) +
                        " кг; общий разброс: " + fmt(currentDiff) + " кг");
            }

        } else if (content.startsWith("REQUEST_WEIGHT|")) {
            sendTo(msg.getSender(), "WEIGHT_INFO|" + getLocalName() + "|" + myWeight);

        } else if (content.startsWith("WEIGHT_INFO|")) {
            handleWeightInfo(sender, content);

        } else if (content.startsWith("PROPOSE_SWAP|")) {
            handleProposal(msg, content);

        } else if (content.startsWith("ACCEPT_SWAP|")) {
            handleAccept(sender, content);

        } else if (content.startsWith("REJECT_SWAP|")) {
            log("Турист " + sender + " отклонил предложение обмена. " + content);
            triedPartners.add(sender);
            clearExchange();

        } else if (content.startsWith("LOCK_OK|")) {
            handleLockOk(content);

        } else if (content.startsWith("LOCK_REFUSED|")) {
            log("Не удалось заблокировать предмет для обмена с " + partnerName);
            if (partnerName != null) {
                sendTo(new AID(partnerName, AID.ISLOCALNAME),
                        "REJECT_SWAP|" + transaction + "|не удалось заблокировать предмет");
                triedPartners.add(partnerName);
            }
            clearExchange();

        } else if (content.startsWith("LOCK_PARTNER|")) {
            handlePartnerLock(msg, content);

        } else if (content.startsWith("PARTNER_LOCKED|")) {
            if ("INITIATOR".equals(role) && transaction != null &&
                    content.contains(transaction)) {
                log("Партнёр " + partnerName + " заблокировал свой предмет. Начинается обмен.");
                sendTo(new AID(partnerName, AID.ISLOCALNAME), "COMMIT_SWAP|" + transaction);
            }

        } else if (content.startsWith("COMMIT_SWAP|")) {
            if ("RECEIVER".equals(role) && transaction != null &&
                    content.contains(transaction)) {
                transferOwnItemToPartner();
            }

        } else if (content.startsWith("OWNER_CHANGED|")) {
            handleOwnerChanged(content);

        } else if (content.startsWith("PARTNER_TRANSFER_DONE|")) {
            if ("INITIATOR".equals(role) && transaction != null &&
                    content.contains(transaction)) {
                log("Партнёр завершил передачу своего предмета. Передаю свой предмет.");
                transferOwnItemToPartner();
            }

        } else if (content.startsWith("STOP|")) {
            stopped = true;
            log("Получена команда завершения: " +
                    content.substring("STOP|".length()));
        }
    }

    private void handleItemOffer(ACLMessage msg, String content) {
        String[] p = content.split("\\|", 3);
        if (p.length != 3) return;

        String offered = p[1];
        double offeredWeight = number(p[2]);

        log("Получил предложение предмета " + offered +
                " массой " + fmt(offeredWeight) + " кг от " +
                msg.getSender().getLocalName());

        if (!distributionConfigured || pendingDistribution || stopped) {
            log("Отклонил " + offered + ": турист пока не готов принять предмет.");
            sendTo(msg.getSender(), "REJECT_ITEM|" + offered + "|" + bagSignature());
            return;
        }

        if (myItems.contains(offered)) {
            log("Отклонил " + offered + ": предмет уже находится в рюкзаке.");
            sendTo(msg.getSender(), "REJECT_ITEM|" + offered + "|" + bagSignature());
            return;
        }

        double afterAdd = myWeight + offeredWeight;
        if (afterAdd <= capacity + 1e-9) {
            pendingDistribution = true;
            pendingNewItem = offered;
            pendingNewItemAgent = msg.getSender();
            log("Принимает " + offered + " без освобождения другого предмета. " +
                    "Расчётная масса: " + fmt(afterAdd) + " кг.");
            sendTo(pendingNewItemAgent, "TAKE_ITEM|" + offered);
            return;
        }

        String bestRemove = null;
        double bestDistance = Double.MAX_VALUE;

        for (String existing : myItems) {
            double newWeight = myWeight + offeredWeight -
                    itemWeights.getOrDefault(existing, 0.0);
            if (newWeight <= capacity + 1e-9) {
                double distance = Math.abs(capacity - newWeight);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestRemove = existing;
                }
            }
        }

        if (bestRemove == null) {
            log("Отклонил " + offered + ": нет предмета, освобождение которого " +
                    "позволит уложиться во вместимость " + fmt(capacity) + " кг.");
            sendTo(msg.getSender(), "REJECT_ITEM|" + offered + "|" + bagSignature());
            return;
        }

        pendingDistribution = true;
        pendingNewItem = offered;
        pendingOldItem = bestRemove;
        pendingNewItemAgent = msg.getSender();

        log("Для принятия " + offered + " выбрал освободить " + bestRemove +
                ". Расчётная масса после замены: " +
                fmt(myWeight + offeredWeight - itemWeights.getOrDefault(bestRemove, 0.0)) +
                " кг.");

        AID oldAgent = findItemAgent(bestRemove);
        if (oldAgent == null) {
            log("Не найден агент освобождаемого предмета " + bestRemove +
                    ". Отклоняю предложение.");
            resetPendingDistribution();
            sendTo(msg.getSender(), "REJECT_ITEM|" + offered + "|" + bagSignature());
        } else {
            sendTo(oldAgent, "RELEASE_ITEM|" + offered);
        }
    }


    private void tryInitiateExchange() {
        if (!distributionComplete || !optimizationStarted || busy()) return;

        List<String> candidates = new ArrayList<>();

        for (Map.Entry<String, Double> entry : allTouristWeights.entrySet()) {
            if (!entry.getKey().equals(getLocalName())
                    && (myWeight - avgWeight) * (entry.getValue() - avgWeight) < -1e-9
                    && !triedPartners.contains(entry.getKey())) {
                candidates.add(entry.getKey());
            }
        }

        Collections.sort(candidates);

        if (candidates.isEmpty()) {
            reportNoImprovement();
            return;
        }

        partnerName = candidates.get(0);

        // Для каждой пары определяется единственный возможный инициатор.
        String first = getLocalName().compareTo(partnerName) <= 0
                ? getLocalName() : partnerName;

        String second = getLocalName().compareTo(partnerName) <= 0
                ? partnerName : getLocalName();

        String elected = (epoch % 2 == 0) ? first : second;

        // Если инициировать должен партнёр, не застреваем на этой паре:
        // пробуем следующего кандидата при следующем тике.
        if (!getLocalName().equals(elected)) {
            log("Не выбран инициатором обмена с " + partnerName
                    + ". Перехожу к следующему кандидату.");

            triedPartners.add(partnerName);
            partnerName = null;
            return;
        }

        role = "INITIATOR";
        waitingWeight = true;
        transaction = getLocalName() + "_" + epoch + "_" + System.nanoTime();

        log("Инициирует обмен с " + partnerName
                + ". Моя масса: " + fmt(myWeight)
                + " кг, масса партнёра: "
                + fmt(allTouristWeights.getOrDefault(partnerName, 0.0))
                + " кг.");

        sendTo(new AID(partnerName, AID.ISLOCALNAME),
                "REQUEST_WEIGHT|" + transaction);
    }


    private void handleWeightInfo(String sender, String content) {
        if (!waitingWeight || !sender.equals(partnerName)) return;

        String[] p = content.split("\\|", 3);
        if (p.length < 3) {
            triedPartners.add(sender);
            clearExchange();
            return;
        }

        double partnerWeight = number(p[2]);
        ownGive = chooseItemToGive();

        if (ownGive == null) {
            log("Не найден предмет для предложения туристу " + sender);
            triedPartners.add(sender);
            clearExchange();
            return;
        }

        ownGiveWeight = itemWeights.getOrDefault(ownGive, 0.0);
        waitingWeight = false;
        waitingPartnerLock = true;

        log("Выбрал предмет " + ownGive + " (" + fmt(ownGiveWeight) +
                " кг) для обмена с " + sender + ".");
        sendTo(new AID(sender, AID.ISLOCALNAME),
                "PROPOSE_SWAP|" + transaction + "|" + ownGive + "|" +
                        ownGiveWeight + "|" + myWeight + "|" + partnerWeight);
    }

    private String chooseItemToGive() {
        String best = null;
        double bestDistance = Double.MAX_VALUE;

        for (String item : myItems) {
            double newWeight = myWeight -
                    itemWeights.getOrDefault(item, 0.0);
            double distance = Math.abs(newWeight - avgWeight);

            if (distance < bestDistance) {
                bestDistance = distance;
                best = item;
            }
        }
        return best;
    }

    private void handleProposal(ACLMessage msg, String content) {
        String sender = msg.getSender().getLocalName();
        String[] p = content.split("\\|", 6);

        if (p.length < 6 || busy()) {
            log("Отклонил предложение обмена от " + sender +
                    ": сейчас занят другой операцией или сообщение некорректно.");
            sendTo(msg.getSender(), "REJECT_SWAP|busy");
            return;
        }

        String tx = p[1];
        String offeredItem = p[2];
        double offeredWeight = number(p[3]);
        double initiatorWeight = number(p[4]);

        double beforeOwn = Math.abs(myWeight - avgWeight);
        String best = null;
        double bestDistance = Double.MAX_VALUE;

        for (String item : myItems) {
            double newWeight = myWeight -
                    itemWeights.getOrDefault(item, 0.0) + offeredWeight;
            double distance = Math.abs(newWeight - avgWeight);

            if (distance < bestDistance) {
                bestDistance = distance;
                best = item;
            }
        }

        if (best == null || bestDistance >= beforeOwn - 1e-9) {
            log("Отклонил обмен от " + sender +
                    ": получение " + offeredItem +
                    " не приближает массу рюкзака к средней.");
            sendTo(msg.getSender(),
                    "REJECT_SWAP|" + tx + "|моя масса не приближается к средней");
            return;
        }

        double takeWeight = itemWeights.getOrDefault(best, 0.0);
        double initiatorNew = initiatorWeight - offeredWeight + takeWeight;
        double receiverNew = myWeight - takeWeight + offeredWeight;

        if (!improvesGlobal(sender, initiatorNew, getLocalName(), receiverNew)) {
            log("Отклонил обмен от " + sender +
                    ": общий разброс масс не уменьшается.");
            sendTo(msg.getSender(),
                    "REJECT_SWAP|" + tx + "|общий разброс не уменьшается");
            return;
        }

        transaction = tx;
        partnerName = sender;
        role = "RECEIVER";
        ownGive = best;
        ownGiveWeight = takeWeight;
        partnerGive = offeredItem;
        partnerGiveWeight = offeredWeight;
        waitingPartnerLock = true;

        log("Согласен на обмен с " + sender + ". Отдаёт " + best +
                " (" + fmt(takeWeight) + " кг), получает " + offeredItem +
                " (" + fmt(offeredWeight) + " кг).");
        sendTo(msg.getSender(),
                "ACCEPT_SWAP|" + tx + "|" + best + "|" + takeWeight);
    }

    private void handleAccept(String sender, String content) {
        if (!"INITIATOR".equals(role) || !sender.equals(partnerName)) return;

        String[] p = content.split("\\|", 4);
        if (p.length < 4) return;

        partnerGive = p[2];
        partnerGiveWeight = number(p[3]);

        double newOwn = myWeight - ownGiveWeight + partnerGiveWeight;
        double partnerOld = allTouristWeights.getOrDefault(partnerName, 0.0);
        double partnerNew = partnerOld - partnerGiveWeight + ownGiveWeight;

        if (Math.abs(newOwn - avgWeight) >=
                Math.abs(myWeight - avgWeight) - 1e-9
                || !improvesGlobal(getLocalName(), newOwn, partnerName, partnerNew)) {
            log("Получил согласие от " + sender +
                    ", но отклоняет обмен после повторной проверки.");
            sendTo(new AID(partnerName, AID.ISLOCALNAME),
                    "REJECT_SWAP|" + transaction + "|инициатору обмен невыгоден");
            triedPartners.add(partnerName);
            clearExchange();
            return;
        }

        log("Турист " + sender + " СОГЛАСИЛСЯ. Обмениваются: " +
                ownGive + " -> " + sender + ", " +
                partnerGive + " -> " + getLocalName() + ".");
        waitingOwnLock = true;
        AID itemAgent = findItemAgent(ownGive);
        if (itemAgent == null) {
            log("Не найден агент предмета " + ownGive + ". Обмен отменён.");
            sendTo(new AID(partnerName, AID.ISLOCALNAME),
                    "REJECT_SWAP|" + transaction + "|не найден агент предмета");
            triedPartners.add(partnerName);
            clearExchange();
            return;
        }
        sendTo(itemAgent, "LOCK_ITEM|" + transaction);
    }

    private void handleLockOk(String content) {
        String[] p = content.split("\\|", 3);
        if (p.length < 3 || transaction == null || !p[1].equals(transaction)) return;

        if ("INITIATOR".equals(role) && waitingOwnLock) {
            waitingOwnLock = false;
            waitingPartnerLock = true;
            log("Свой предмет " + ownGive + " заблокирован. Запрашивает блокировку предмета партнёра.");
            sendTo(new AID(partnerName, AID.ISLOCALNAME),
                    "LOCK_PARTNER|" + transaction + "|" + ownGive + "|" + partnerGive);
        } else if ("RECEIVER".equals(role) && waitingOwnLock) {
            waitingOwnLock = false;
            waitingPartnerLock = false;
            log("Свой предмет " + ownGive + " заблокирован. Сообщает инициатору о готовности.");
            sendTo(new AID(partnerName, AID.ISLOCALNAME),
                    "PARTNER_LOCKED|" + transaction);
        }
    }

    private void handlePartnerLock(ACLMessage msg, String content) {
        String[] p = content.split("\\|", 4);
        if (p.length < 4 || !"RECEIVER".equals(role) ||
                !p[1].equals(transaction)) return;

        if (!p[3].equals(ownGive)) return;

        waitingOwnLock = true;
        AID itemAgent = findItemAgent(ownGive);
        if (itemAgent == null) {
            log("Не найден агент своего предмета " + ownGive +
                    ". Не удалось подготовить обмен.");
            sendTo(msg.getSender(), "REJECT_SWAP|" + transaction +
                    "|не найден агент предмета");
            clearExchange();
            return;
        }
        sendTo(itemAgent, "LOCK_ITEM|" + transaction);
    }

    private void transferOwnItemToPartner() {
        if (transaction == null || ownGive == null) return;

        waitingOwnTransfer = true;
        AID itemAgent = findItemAgent(ownGive);
        if (itemAgent == null) {
            log("Ошибка: не найден агент предмета " + ownGive +
                    " при передаче владельца.");
            return;
        }
        sendTo(itemAgent,
                "TRANSFER_OWNER|" + transaction + "|" + partnerName);
    }

    private void handleOwnerChanged(String content) {
        String[] p = content.split("\\|", 4);
        if (p.length < 4 || transaction == null ||
                !p[1].equals(transaction)) return;

        String changedItem = p[2];
        sendTo(findItemAgent(changedItem), "UNLOCK_ITEM|" + transaction);

        if ("RECEIVER".equals(role)) {
            myItems.remove(ownGive);
            if (!myItems.contains(partnerGive)) myItems.add(partnerGive);
            recalculateWeight();
            log("Передал " + ownGive + ", получил " + partnerGive +
                    ". Новая масса рюкзака: " + fmt(myWeight) + " кг.");
            sendBagMass(transaction);
            sendTo(new AID(partnerName, AID.ISLOCALNAME),
                    "PARTNER_TRANSFER_DONE|" + transaction + "|" + changedItem);
            waitingOwnTransfer = false;

            // Важно: получатель тоже должен освободить состояние транзакции,
            // иначе busy() навсегда останется true и он не сообщит NO_IMPROVEMENT.
            triedPartners.add(partnerName);
            clearExchange();

        } else if ("INITIATOR".equals(role)) {
            myItems.remove(ownGive);
            if (!myItems.contains(partnerGive)) myItems.add(partnerGive);
            recalculateWeight();
            log("Обмен завершён. Передал " + ownGive + ", получил " +
                    partnerGive + ". Новая масса рюкзака: " +
                    fmt(myWeight) + " кг.");
            sendBagMass(transaction);
            triedPartners.add(partnerName);
            clearExchange();
        }
    }

    private boolean improvesGlobal(String a, double newA, String b, double newB) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (Map.Entry<String, Double> entry : allTouristWeights.entrySet()) {
            double value = entry.getValue();
            if (entry.getKey().equals(a)) value = newA;
            if (entry.getKey().equals(b)) value = newB;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return max - min < currentDiff - 1e-9;
    }

    private void reportNoImprovement() {
        if (reportedNoImprovement || manager == null) return;
        reportedNoImprovement = true;
        log("Не осталось доступных партнёров для улучшающего обмена. Сообщает менеджеру.");
        sendTo(manager, "NO_IMPROVEMENT|" + getLocalName());
    }

    private void sendBagMass(String tx) {
        if (manager != null) {
            sendTo(manager, "BAG_MASS|" + tx + "|" +
                    getLocalName() + "|" + myWeight);
        }
    }

    private boolean busy() {
        return pendingDistribution || waitingWeight || waitingOwnLock ||
                waitingPartnerLock || waitingPartnerTransfer ||
                waitingOwnTransfer || transaction != null;
    }

    private void clearExchange() {
        transaction = null;
        role = null;
        partnerName = null;
        ownGive = null;
        partnerGive = null;
        ownGiveWeight = 0;
        partnerGiveWeight = 0;
        waitingWeight = false;
        waitingOwnLock = false;
        waitingPartnerLock = false;
        waitingPartnerTransfer = false;
        waitingOwnTransfer = false;
    }

    private void resetPendingDistribution() {
        pendingDistribution = false;
        pendingNewItem = null;
        pendingOldItem = null;
        pendingNewItemAgent = null;
    }

    private void recalculateWeight() {
        myWeight = 0;
        for (String item : myItems) {
            myWeight += itemWeights.getOrDefault(item, 0.0);
        }
    }

    private String bagSignature() {
        List<String> sorted = new ArrayList<>(myItems);
        Collections.sort(sorted);
        return Integer.toHexString(sorted.hashCode()) + "_" +
                String.format(Locale.US, "%.3f", myWeight);
    }

    private void parseWeights(String value) {
        itemWeights.clear();
        if (value == null || value.isEmpty()) return;

        for (String pair : value.split(";")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) itemWeights.put(kv[0], number(kv[1]));
        }
    }

    private void parseTouristWeights(String value) {
        allTouristWeights.clear();
        if (value == null || value.isEmpty()) return;

        for (String pair : value.split(";")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) allTouristWeights.put(kv[0], number(kv[1]));
        }
    }

    private double number(String value) {
        try {
            return Double.parseDouble(value.replace(',', '.'));
        } catch (Exception e) {
            return 0;
        }
    }

    private AID findItemAgent(String item) {
        if (item == null) return null;

        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("item");
        template.addServices(sd);

        try {
            for (DFAgentDescription d : DFService.search(this, template)) {
                if (d.getName().getLocalName().equals("item_" + item)) {
                    return d.getName();
                }
            }
        } catch (FIPAException ignored) {
        }
        return null;
    }

    // Публикация типа агента в DF — не регистрация у менеджера.
    // Менеджер самостоятельно обнаруживает такие записи поиском DF.
    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("tourist");
        sd.setName("Backpack-Tourist");
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            log("Не удалось опубликовать туриста в DF: " + e.getMessage());
        }
    }

    private AID findManager() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("manager");
        template.addServices(sd);

        try {
            DFAgentDescription[] result = DFService.search(this, template);
            if (result.length > 0) return result[0].getName();
        } catch (FIPAException ignored) {
        }
        return null;
    }

    private void sendTo(AID to, String content) {
        if (to == null) return;
        ACLMessage message = new ACLMessage(ACLMessage.INFORM);
        message.addReceiver(to);
        message.setContent(content);
        send(message);
    }

    private String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private void log(String message) {
        System.out.printf("[%1$tT] [ТУРИСТ %2$s] %3$s%n",
                new Date(), getLocalName(), message);
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException ignored) {
        }
    }
}
