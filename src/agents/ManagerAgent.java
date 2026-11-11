package agents;

import jade.content.lang.Codec;
import jade.content.lang.sl.SLCodec;
import jade.content.onto.Ontology;
import jade.content.onto.basic.Action;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.JADEAgentManagement.JADEManagementOntology;
import jade.domain.JADEAgentManagement.ShutdownPlatform;
import jade.lang.acl.ACLMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Менеджер. Не распределяет предметы и не выбирает пары для обмена:
 * только считает предметы, собирает веса, считает среднее/разброс и решает, когда остановиться.
 */
public class ManagerAgent extends Agent {

    private enum Phase {REGISTRATION, WAIT_TOURISTS, DISTRIBUTION, COLLECT_BAGS, BALANCING, FINISHING, DONE}

    /** Тишина после последней регистрации предмета, после которой регистрация считается завершённой. */
    private static final long REG_SILENCE = 5000;
    /** Сколько ждать, если N_owned не меняется, прежде чем признать первый этап невыполнимым. */
    private static final long STAGE1_STALL = 30000;
    /** Сколько N_owned должно держаться равным N_total, чтобы исключить гонку уведомлений. */
    private static final long STABLE = 2000;
    /** Если за это время не завершилось ни одного обмена - останавливаемся. */
    private static final long IDLE_STOP = 20000;
    private static final long FINAL_WAIT = 30000;

    private int expected;
    private int capacity;
    private String resultPath;

    private Phase phase = Phase.REGISTRATION;

    // ---- этап 1: учёт предметов (только состояние "есть владелец / нет")
    private final Map<String, Boolean> items = new LinkedHashMap<>();
    private int nOwned;
    private long lastReg;
    private long lastProgress;
    private long stableSince;
    private long lastDfCheck;
    private long lastPrint;
    private int lastDfCount = -1;

    // ---- этап 2
    private List<AID> tourists = new ArrayList<>();
    private final Map<String, Integer> bags = new TreeMap<>();
    private final Map<String, Integer> initialBags = new TreeMap<>();
    private final Map<String, Map<String, Integer>> pendingOps = new LinkedHashMap<>();
    private final List<String> exchangeLog = new ArrayList<>();
    private double avg;
    private int f0;
    private int fBest;
    private int noImprove;   // счётчик C
    private int cMax;
    private int completed;
    private long lastExchange;

    // ---- завершение
    private final Map<String, String[]> finals = new TreeMap<>();
    private String stopReason = "";
    private long finishStart;

    @Override
    protected void setup() {
        Object[] a = getArguments();
        expected = Integer.parseInt(a[0].toString());
        capacity = Integer.parseInt(a[1].toString());
        resultPath = a[2].toString();
        lastReg = System.currentTimeMillis();

        System.out.println("[manager] запущен. Ожидается туристов: " + expected
                + ", вместимость рюкзака: " + Common.kg(capacity));

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage m;
                while ((m = myAgent.receive()) != null) {
                    ManagerAgent.this.handle(m);
                }
                step(System.currentTimeMillis());
                block(100);
            }
        });
    }

    // ------------------------------------------------------------------ сообщения

    private void handle(ACLMessage m) {
        String type = m.getOntology();
        if (type == null) {
            return;
        }
        String sender = m.getSender().getLocalName();
        long now = System.currentTimeMillis();

        switch (type) {
            case Common.T_REGISTER:
                if (phase == Phase.REGISTRATION) {
                    if (!items.containsKey(sender)) {   // учитываем только уникальные сообщения
                        items.put(sender, Boolean.FALSE);
                        lastReg = now;                  // сброс таймера ожидания
                        System.out.println("[manager] регистрация предмета " + sender
                                + " (всего " + items.size() + ")");
                    }
                } else if (!items.containsKey(sender)) {
                    System.out.println("[manager] поздняя регистрация " + sender + " → LATE");
                    Common.sendTo(this, ACLMessage.REFUSE, sender, Common.T_LATE, "регистрация закрыта", null);
                }
                break;

            case Common.T_OWNER: {
                Boolean prev = items.get(sender);
                if (prev == null) {
                    break;
                }
                boolean owned = "OWNED".equals(m.getContent());
                if (prev != owned) {                    // повторные/устаревшие уведомления не меняют счётчик
                    items.put(sender, owned);
                    nOwned += owned ? 1 : -1;
                    lastProgress = now;
                    System.out.println("[manager] " + sender + " → " + (owned ? "OWNED" : "NOT_OWNED")
                            + " (N_owned=" + nOwned + "/" + items.size() + ")");
                }
                break;
            }

            case Common.T_BAG_INFO:
                if (phase == Phase.COLLECT_BAGS) {
                    String[] p = Common.split(m.getContent());
                    bags.put(p[0], Integer.parseInt(p[1]));
                    System.out.println("[manager] вес рюкзака " + p[0] + ": " + Common.kg(Integer.parseInt(p[1]))
                            + " (" + bags.size() + "/" + tourists.size() + ")");
                }
                break;

            case Common.T_BAG_UPDATE:
                if (phase == Phase.BALANCING || phase == Phase.FINISHING) {
                    onBagUpdate(m.getContent(), now);
                }
                break;

            case Common.T_FINAL:
                if (phase == Phase.FINISHING) {
                    String[] p = Common.split(m.getContent());
                    finals.put(p[0], p);
                    System.out.println("[manager] итоговые данные от " + p[0]
                            + " (" + finals.size() + "/" + tourists.size() + ")");
                }
                break;

            default:
                break;
        }
    }

    // ------------------------------------------------------------------ конечный автомат

    private void step(long now) {
        switch (phase) {
            case REGISTRATION:
                if (!items.isEmpty() && now - lastReg > REG_SILENCE) {
                    System.out.println("[manager] регистрация предметов завершена, N_total = " + items.size());
                    phase = Phase.WAIT_TOURISTS;
                }
                break;

            case WAIT_TOURISTS:
                if (now - lastDfCheck >= 1000) {
                    lastDfCheck = now;
                    List<AID> found = Common.findTourists(this);
                    if (found.size() != lastDfCount) {
                        lastDfCount = found.size();
                        System.out.println("[manager] подключено туристов: " + found.size() + " из " + expected);
                    }
                    if (found.size() >= expected) {
                        tourists = new ArrayList<>(found.subList(0, expected));
                        startDistribution(now);
                    }
                }
                break;

            case DISTRIBUTION:
                stepDistribution(now);
                break;

            case COLLECT_BAGS:
                if (bags.size() >= tourists.size()) {
                    startBalancing(now);
                }
                break;

            case BALANCING:
                if (now - lastExchange > IDLE_STOP) {
                    beginStop("обмены больше не происходят (нет подходящих пар за "
                            + IDLE_STOP / 1000 + " с)", now);
                }
                break;

            case FINISHING:
                if (finals.size() >= tourists.size() || now - finishStart > FINAL_WAIT) {
                    finish();
                }
                break;

            default:
                break;
        }
    }

    private void startDistribution(long now) {
        System.out.println("[manager] все туристы подключены. Начинается первоначальное распределение");
        for (AID t : tourists) {
            Common.send(this, ACLMessage.INFORM, t, Common.T_ACTIVATE, String.valueOf(capacity));
        }
        for (String itemId : items.keySet()) {
            Common.sendTo(this, ACLMessage.INFORM, itemId, Common.T_START, "", null);
        }
        phase = Phase.DISTRIBUTION;
        lastProgress = now;
        stableSince = 0;
    }

    private void stepDistribution(long now) {
        int total = items.size();
        if (now - lastPrint > 3000) {
            lastPrint = now;
            System.out.println("[manager] распределено предметов: " + nOwned + " из " + total);
        }
        if (nOwned == total) {
            if (stableSince == 0) {
                stableSince = now;
            } else if (now - stableSince >= STABLE) {
                System.out.println("[manager] N_owned = N_total = " + total + ": первый этап завершён");
                phase = Phase.COLLECT_BAGS;
                bags.clear();
                for (AID t : tourists) {
                    Common.send(this, ACLMessage.INFORM, t, Common.T_REQUEST_BAG, "");
                }
            }
        } else {
            stableSince = 0;
            if (now - lastProgress > STAGE1_STALL) {
                beginStop("не удалось разместить все предметы: распределено " + nOwned + " из " + total
                        + " (суммарная масса не помещается в рюкзаки с учётом вместимости)", now);
            }
        }
    }

    private void startBalancing(long now) {
        initialBags.putAll(bags);
        long sum = 0;
        for (int w : bags.values()) {
            sum += w;
        }
        avg = sum / (double) bags.size();
        f0 = spread();
        fBest = f0;
        noImprove = 0;
        cMax = (int) Math.ceil(Math.sqrt((double) tourists.size() * items.size()));
        lastExchange = now;

        StringBuilder roster = new StringBuilder();
        for (AID t : tourists) {
            if (roster.length() > 0) {
                roster.append(',');
            }
            roster.append(t.getLocalName());
        }
        System.out.println("[manager] второй этап. W_avg = " + Common.kg(avg) + ", F0 = " + Common.kg(f0)
                + ", C_max = " + cMax);
        phase = Phase.BALANCING;
        for (AID t : tourists) {
            Common.send(this, ACLMessage.INFORM, t, Common.T_AVG, avg + "|" + roster);
        }
        if (f0 == 0) {
            beginStop("рюкзаки уже одинаковы по весу", now);
        }
    }

    /** Менеджер ждёт отчёты обоих участников одной операции и только потом пересчитывает статистику. */
    private void onBagUpdate(String content, long now) {
        String[] p = Common.split(content);
        String op = p[0];
        Map<String, Integer> parts = pendingOps.computeIfAbsent(op, k -> new LinkedHashMap<>());
        parts.put(p[1], Integer.parseInt(p[2]));
        if (parts.size() < 2) {
            return;
        }
        pendingOps.remove(op);
        bags.putAll(parts);
        completed++;
        lastExchange = now;

        int f = spread();
        if (f < fBest) {
            fBest = f;
            noImprove = 0;
        } else {
            noImprove++;
        }
        StringBuilder who = new StringBuilder();
        for (Map.Entry<String, Integer> e : parts.entrySet()) {
            if (who.length() > 0) {
                who.append(" <-> ");
            }
            who.append(e.getKey()).append(" (").append(Common.kg(e.getValue())).append(")");
        }
        exchangeLog.add(String.format("%4d | %-60s | %-9s | %-9s | %d",
                completed, who, Common.kg(f), Common.kg(fBest), noImprove));
        System.out.println("[manager] обмен №" + completed + ": F = " + Common.kg(f) + ", F_best = "
                + Common.kg(fBest) + ", C = " + noImprove + "/" + cMax);

        if (phase == Phase.BALANCING) {
            if (f == 0) {
                beginStop("достигнут идеальный баланс (F = 0)", now);
            } else if (noImprove >= cMax) {
                beginStop("достигнут предел обменов без улучшения C_max = " + cMax, now);
            }
        }
    }

    private int spread() {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int w : bags.values()) {
            min = Math.min(min, w);
            max = Math.max(max, w);
        }
        return bags.isEmpty() ? 0 : max - min;
    }

    private void beginStop(String reason, long now) {
        System.out.println("[manager] остановка: " + reason);
        stopReason = reason;
        phase = Phase.FINISHING;
        finishStart = now;
        for (AID t : tourists) {
            Common.send(this, ACLMessage.INFORM, t, Common.T_STOP, "");
        }
    }

    // ------------------------------------------------------------------ итоги

    private void finish() {
        List<String> report = buildReport();
        try {
            Path path = Paths.get(resultPath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, report, StandardCharsets.UTF_8);
            System.out.println("[manager] результат записан в файл: " + path.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("[manager] не удалось записать файл результата: " + e);
            for (String line : report) {
                System.out.println(line);
            }
        }
        phase = Phase.DONE;
        shutdownPlatformLater();
    }

    private List<String> buildReport() {
        String line = "========================================================================";
        String thin = "------------------------------------------------------------------------";
        List<String> r = new ArrayList<>();
        r.add(line);
        r.add(" РАСПРЕДЕЛЕНИЕ ПРЕДМЕТОВ МЕЖДУ ТУРИСТАМИ - РЕЗУЛЬТАТ");
        r.add(line);
        r.add("Дата и время:           " + new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date()));
        r.add("Туристов (N):           " + tourists.size());
        r.add("Предметов (M):          " + items.size());
        r.add("Вместимость рюкзака:    " + Common.kg(capacity));
        r.add("C_max = ceil(sqrt(N*M)): " + (cMax > 0 ? String.valueOf(cMax) : "-"));
        r.add("Причина остановки:      " + stopReason);
        r.add("");

        r.add("1. РАСПРЕДЕЛЕНИЕ ПОСЛЕ ПЕРВОГО ЭТАПА");
        r.add(thin);
        if (initialBags.isEmpty()) {
            r.add("(первый этап не завершён)");
        } else {
            for (Map.Entry<String, Integer> e : initialBags.entrySet()) {
                r.add(String.format("%-40s %12s", e.getKey(), Common.kg(e.getValue())));
            }
            r.add(thin);
            r.add("Средний вес W_avg: " + Common.kg(avg) + ",  начальный разброс F0: " + Common.kg(f0));
        }
        r.add("");

        r.add("2. ЖУРНАЛ ОБМЕНОВ");
        r.add(thin);
        if (exchangeLog.isEmpty()) {
            r.add("(обменов не было)");
        } else {
            r.add(String.format("%4s | %-60s | %-9s | %-9s | %s", "№", "Участники (вес после обмена)",
                    "F", "F_best", "C"));
            r.addAll(exchangeLog);
        }
        r.add("");

        r.add("3. ИТОГОВОЕ СОДЕРЖИМОЕ РЮКЗАКОВ");
        r.add(thin);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        for (AID t : tourists) {
            String[] p = finals.get(t.getLocalName());
            if (p == null) {
                r.add(t.getLocalName() + ": итоговые данные не получены");
                continue;
            }
            int w = Integer.parseInt(p[1]);
            min = Math.min(min, w);
            max = Math.max(max, w);
            sum += w;
            r.add(t.getLocalName() + "  -  " + Common.kg(w));
            if (p.length > 2 && !p[2].isEmpty()) {
                for (String it : p[2].split(";")) {
                    int eq = it.lastIndexOf('=');
                    r.add("      * " + it.substring(0, eq) + " (" + Common.kg(Integer.parseInt(it.substring(eq + 1))) + ")");
                }
            } else {
                r.add("      (рюкзак пуст)");
            }
        }
        r.add("");

        r.add("4. ИТОГОВЫЕ ПОКАЗАТЕЛИ");
        r.add(thin);
        if (!finals.isEmpty() && min != Integer.MAX_VALUE) {
            r.add("Средний вес рюкзака:            " + Common.kg(sum / (double) finals.size()));
            r.add("Минимальный вес:                " + Common.kg(min));
            r.add("Максимальный вес:               " + Common.kg(max));
            r.add("Итоговый разброс F:             " + Common.kg(max - min));
        }
        if (!initialBags.isEmpty()) {
            r.add("Лучший достигнутый разброс:     " + Common.kg(fBest));
        }
        r.add("Выполнено обменов:              " + completed);
        r.add(line);
        return r;
    }

    private void shutdownPlatformLater() {
        addBehaviour(new WakerBehaviour(this, 3000) {
            @Override
            protected void onWake() {
                try {
                    Codec codec = new SLCodec();
                    Ontology jmo = JADEManagementOntology.getInstance();
                    getContentManager().registerLanguage(codec);
                    getContentManager().registerOntology(jmo);
                    ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
                    msg.addReceiver(getAMS());
                    msg.setLanguage(codec.getName());
                    msg.setOntology(jmo.getName());
                    getContentManager().fillContent(msg, new Action(getAID(), new ShutdownPlatform()));
                    send(msg);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
