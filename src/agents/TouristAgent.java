package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Турист. Знает содержимое только своего рюкзака.
 * Этап 1: принимает предложения предметов (при необходимости освобождая место).
 * Этап 2: обменивается предметами с туристами противоположной группы (лёгкие/тяжёлые).
 * Создаётся клиентом (ClientMain) при подключении к серверу.
 */
public class TouristAgent extends Agent {

    private static final class Item {
        final String id;
        final String name;
        final int g;

        Item(String id, String name, int g) {
            this.id = id;
            this.name = name;
            this.g = g;
        }
    }

    private static final class Choice {
        List<Item> set;
        int sum;
    }

    private enum Role {NONE, WAIT_REPLY, WAIT_DONE, WAIT_CONFIRM}

    private static final long WAIT_REPLY = 10000;
    private static final long WAIT_CONFIRM = 10000;
    private static final long WAIT_DONE = 60000;

    private final Map<String, Item> bag = new LinkedHashMap<>();
    private final Random rnd = new Random();

    private boolean active;      // менеджер активировал этого туриста
    private boolean balancing;   // идёт этап 2
    private boolean stopping;    // получена команда остановки
    private boolean finalSent;
    private int capacity;
    private double avg;
    private List<String> roster = new ArrayList<>();

    // текущая операция обмена
    private Role role = Role.NONE;
    private String opId;
    private String partner;
    private Item mine;           // мой предмет, отдаваемый в обмен
    private Item theirs;         // предмет партнёра
    private long deadline;
    private int opSeq;
    private long nextAttemptAt;

    @Override
    protected void setup() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType(Common.TOURIST_SERVICE);
            sd.setName(getLocalName());
            dfd.addServices(sd);
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
            doDelete();
            return;
        }
        System.out.println("[" + getLocalName() + "] турист подключился к серверу");

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage m;
                while ((m = myAgent.receive()) != null) {
                    TouristAgent.this.handle(m);
                }
                long now = System.currentTimeMillis();
                checkTimeout(now);
                if (balancing && !stopping && role == Role.NONE && now >= nextAttemptAt) {
                    tryInitiate(now);
                }
                if (stopping && role == Role.NONE && !finalSent) {
                    sendFinal();
                }
                block(50);
            }
        });
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException ignored) {
            // платформа уже останавливается
        }
    }

    // ------------------------------------------------------------------ диспетчер

    private void handle(ACLMessage m) {
        String type = m.getOntology();
        if (type == null) {
            return;
        }
        int perf = m.getPerformative();
        switch (type) {
            case Common.T_ACTIVATE:
                capacity = Integer.parseInt(m.getContent());
                active = true;
                System.out.println("[" + getLocalName() + "] активирован, вместимость рюкзака "
                        + Common.kg(capacity));
                break;
            case Common.T_OFFER:
                if (perf == ACLMessage.PROPOSE) {
                    onOffer(m);
                }
                break;
            case Common.T_REVOKE:
                if (bag.remove(m.getSender().getLocalName()) != null) {
                    System.out.println("[" + getLocalName() + "] REVOKE: убрал "
                            + m.getSender().getLocalName() + " из рюкзака, вес " + Common.kg(weight()));
                }
                break;
            case Common.T_REQUEST_BAG:
                balancing = false;
                System.out.println("[" + getLocalName() + "] отчёт веса менеджеру: " + Common.kg(weight()));
                Common.sendTo(this, ACLMessage.INFORM, Common.MANAGER, Common.T_BAG_INFO,
                        getLocalName() + "|" + weight(), null);
                break;
            case Common.T_AVG:
                onAvg(m);
                break;
            case Common.T_EXCH:
                if (perf == ACLMessage.PROPOSE) {
                    onExchPropose(m);
                } else if (perf == ACLMessage.ACCEPT_PROPOSAL) {
                    onExchAccept(m);
                } else if (perf == ACLMessage.REJECT_PROPOSAL) {
                    onExchReject(m);
                }
                break;
            case Common.T_EXCH_CONFIRM:
                onConfirm(m);
                break;
            case Common.T_EXCH_DONE:
                onDone(m);
                break;
            case Common.T_EXCH_CANCEL:
                onCancel(m);
                break;
            case Common.T_STOP:
                stopping = true;
                System.out.println("[" + getLocalName() + "] получена команда остановки");
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ этап 1

    /**
     * Предложение предмета от ItemAgent. Принимаем, если помещается; иначе пробуем освободить
     * место (убрать 1-3 своих предмета суммарной массой y < x так, чтобы W - y + x <= Wmax).
     * Условие y < x гарантирует, что каждое принятие увеличивает суммарную массу в рюкзаках,
     * поэтому первый этап не может зациклиться.
     */
    private void onOffer(ACLMessage m) {
        ACLMessage reply = m.createReply();
        String itemId = m.getSender().getLocalName();
        String[] p = Common.split(m.getContent());
        String name = p[0];
        int x = Integer.parseInt(p[1]);

        List<Item> release = null;
        if (active && !balancing && !stopping && !bag.containsKey(itemId)) {
            release = chooseRelease(x);
        }
        if (release == null) {
            System.out.println("[" + getLocalName() + "] отклонил \"" + name + "\" (" + Common.kg(x)
                    + "), вес сейчас " + Common.kg(weight()));
            reply.setPerformative(ACLMessage.REJECT_PROPOSAL);
            send(reply);
            return;
        }
        for (Item it : release) {
            System.out.println("[" + getLocalName() + "] освобождаю \"" + it.name + "\" (" + Common.kg(it.g)
                    + ") чтобы взять \"" + name + "\"");
            bag.remove(it.id);
            Common.sendTo(this, ACLMessage.INFORM, it.id, Common.T_DROP, getLocalName(), null);
        }
        bag.put(itemId, new Item(itemId, name, x));
        System.out.println("[" + getLocalName() + "] принял \"" + name + "\" (" + Common.kg(x)
                + "), вес рюкзака " + Common.kg(weight()));
        reply.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
        send(reply);
    }

    /** @return пустой список - помещается сразу; null - принять нельзя; иначе предметы к освобождению. */
    private List<Item> chooseRelease(int x) {
        int w = weight();
        if (w + x <= capacity) {
            return new ArrayList<>();
        }
        int need = w + x - capacity;   // нужно освободить минимум столько
        List<Item> items = new ArrayList<>(bag.values());
        int n = items.size();
        Choice best = null;
        for (int i = 0; i < n; i++) {
            best = consider(best, need, x, items.get(i));
            for (int j = i + 1; j < n; j++) {
                best = consider(best, need, x, items.get(i), items.get(j));
                for (int k = j + 1; k < n; k++) {
                    best = consider(best, need, x, items.get(i), items.get(j), items.get(k));
                }
            }
        }
        return best == null ? null : best.set;
    }

    private Choice consider(Choice best, int need, int x, Item... cand) {
        int sum = 0;
        for (Item it : cand) {
            sum += it.g;
        }
        if (sum < need || sum >= x) {
            return best;
        }
        if (best == null || cand.length < best.set.size()
                || (cand.length == best.set.size() && sum < best.sum)) {
            Choice c = new Choice();
            c.set = new ArrayList<>(Arrays.asList(cand));
            c.sum = sum;
            return c;
        }
        return best;
    }

    // ------------------------------------------------------------------ этап 2

    private void onAvg(ACLMessage m) {
        String[] p = Common.split(m.getContent());
        avg = Double.parseDouble(p[0]);
        roster = new ArrayList<>(Arrays.asList(p[1].split(",")));
        balancing = true;
        nextAttemptAt = System.currentTimeMillis() + 200 + rnd.nextInt(600);
        System.out.println("[" + getLocalName() + "] мой рюкзак " + Common.kg(weight())
                + ", средний вес " + Common.kg(avg)
                + (weight() < avg ? " -> лёгкая группа" : weight() > avg ? " -> тяжёлая группа" : " -> вне групп"));
    }

    /** Инициатор: выбирает свой предмет и случайного партнёра. */
    private void tryInitiate(long now) {
        int w = weight();
        if (roster.size() < 2 || w == avg || bag.isEmpty()) {
            backoff(now);
            return;
        }
        boolean light = w < avg;
        Item x = null;
        double bestD = Double.MAX_VALUE;
        for (Item it : bag.values()) {
            if (light) {
                // лёгкий отдаёт самый лёгкий предмет
                if (x == null || it.g < x.g) {
                    x = it;
                }
            } else {
                // тяжёлый отдаёт предмет, после которого вес ближе всего к среднему
                double d = Math.abs(w - it.g - avg);
                if (d < bestD) {
                    bestD = d;
                    x = it;
                }
            }
        }
        List<String> others = new ArrayList<>(roster);
        others.remove(getLocalName());
        if (x == null || others.isEmpty()) {
            backoff(now);
            return;
        }
        String target = others.get(rnd.nextInt(others.size()));

        opId = getLocalName() + "#" + (++opSeq);
        partner = target;
        mine = x;
        theirs = null;
        role = Role.WAIT_REPLY;
        deadline = now + WAIT_REPLY;
        System.out.println("[" + getLocalName() + "] предлагаю обмен " + opId + " туристу " + target
                + ": отдаю \"" + x.name + "\" (" + Common.kg(x.g) + "), группа "
                + (light ? "LIGHT" : "HEAVY"));
        Common.sendTo(this, ACLMessage.INFORM, x.id, Common.T_LOCK, getLocalName(), null);
        Common.sendTo(this, ACLMessage.PROPOSE, target, Common.T_EXCH,
                opId + "|" + (light ? "LIGHT" : "HEAVY") + "|" + x.id + "|" + x.name + "|" + x.g, opId);
    }

    /** Партнёр: сам выбирает предмет из своего рюкзака. */
    private void onExchPropose(ACLMessage m) {
        ACLMessage reply = m.createReply();
        String[] p = Common.split(m.getContent());
        String id = p[0];
        String group = p[1];
        Item x = new Item(p[2], p[3], Integer.parseInt(p[4]));
        int w = weight();

        boolean ok = balancing && !stopping && role == Role.NONE && !bag.isEmpty()
                && w != avg && !bag.containsKey(x.id);
        if (ok) {
            String myGroup = w < avg ? "LIGHT" : "HEAVY";
            ok = !myGroup.equals(group);   // обмен только между противоположными группами
        }
        Item y = null;
        if (ok) {
            double bestD = Double.MAX_VALUE;
            for (Item it : bag.values()) {
                double d = Math.abs(w - it.g + x.g - avg);
                if (d < bestD) {
                    bestD = d;
                    y = it;
                }
            }
            ok = y != null && (w - y.g + x.g) <= capacity;
        }
        if (!ok) {
            System.out.println("[" + getLocalName() + "] отклонил обмен " + id + " от "
                    + m.getSender().getLocalName());
            reply.setPerformative(ACLMessage.REJECT_PROPOSAL);
            send(reply);
            return;
        }
        opId = id;
        partner = m.getSender().getLocalName();
        mine = y;
        theirs = x;
        role = Role.WAIT_CONFIRM;
        deadline = System.currentTimeMillis() + WAIT_CONFIRM;
        System.out.println("[" + getLocalName() + "] принял обмен " + id + ": отдаю \"" + y.name
                + "\" (" + Common.kg(y.g) + "), получаю \"" + x.name + "\" (" + Common.kg(x.g) + ")");
        Common.sendTo(this, ACLMessage.INFORM, y.id, Common.T_LOCK, getLocalName(), null);
        reply.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
        reply.setContent(id + "|" + y.id + "|" + y.name + "|" + y.g);
        send(reply);
    }

    /** Инициатор получил выбранный партнёром предмет и проверяет свою вместимость. */
    private void onExchAccept(ACLMessage m) {
        String[] p = Common.split(m.getContent());
        String sender = m.getSender().getLocalName();
        if (role != Role.WAIT_REPLY || !p[0].equals(opId) || !sender.equals(partner)) {
            // устаревший ответ - сообщаем партнёру, чтобы он снял блокировку
            Common.sendTo(this, ACLMessage.INFORM, sender, Common.T_EXCH_CANCEL, p[0], p[0]);
            return;
        }
        Item y = new Item(p[1], p[2], Integer.parseInt(p[3]));
        int newW = weight() - mine.g + y.g;
        if (newW > capacity) {
            System.out.println("[" + getLocalName() + "] обмен " + opId + " отменён: не помещается "
                    + Common.kg(newW));
            Common.sendTo(this, ACLMessage.INFORM, partner, Common.T_EXCH_CANCEL, opId, opId);
            Common.sendTo(this, ACLMessage.INFORM, mine.id, Common.T_UNLOCK, getLocalName(), null);
            clearOp();
            backoff(System.currentTimeMillis());
            return;
        }
        theirs = y;
        role = Role.WAIT_DONE;
        deadline = System.currentTimeMillis() + WAIT_DONE;
        System.out.println("[" + getLocalName() + "] подтверждаю обмен " + opId + " с " + partner);
        Common.sendTo(this, ACLMessage.INFORM, partner, Common.T_EXCH_CONFIRM, opId, opId);
    }

    private void onExchReject(ACLMessage m) {
        String conv = m.getConversationId();
        if (role == Role.WAIT_REPLY && opId != null && opId.equals(conv)) {
            System.out.println("[" + getLocalName() + "] обмен " + opId + " отклонён партнёром");
            Common.sendTo(this, ACLMessage.INFORM, mine.id, Common.T_UNLOCK, getLocalName(), null);
            clearOp();
            backoff(System.currentTimeMillis());
        }
    }

    /** Партнёр: инициатор подтвердил - выполняем свою часть обмена. */
    private void onConfirm(ACLMessage m) {
        String id = m.getContent();
        String sender = m.getSender().getLocalName();
        if (role != Role.WAIT_CONFIRM || !id.equals(opId) || !sender.equals(partner)) {
            Common.sendTo(this, ACLMessage.INFORM, sender, Common.T_EXCH_CANCEL, id, id);
            return;
        }
        System.out.println("[" + getLocalName() + "] обмен " + opId + " с " + partner + ": отдал \""
                + mine.name + "\" (" + Common.kg(mine.g) + "), получил \"" + theirs.name + "\" ("
                + Common.kg(theirs.g) + ")");
        applyExchange();
        Common.sendTo(this, ACLMessage.INFORM, partner, Common.T_EXCH_DONE, opId, opId);
        reportWeight();
        clearOp();
        backoff(System.currentTimeMillis());
    }

    /** Инициатор: партнёр выполнил обмен - выполняем свою часть. */
    private void onDone(ACLMessage m) {
        String id = m.getContent();
        if (role != Role.WAIT_DONE || !id.equals(opId)) {
            return;
        }
        System.out.println("[" + getLocalName() + "] обмен " + opId + " с " + partner + ": отдал \""
                + mine.name + "\" (" + Common.kg(mine.g) + "), получил \"" + theirs.name + "\" ("
                + Common.kg(theirs.g) + ")");
        applyExchange();
        reportWeight();
        clearOp();
        backoff(System.currentTimeMillis());
    }

    private void onCancel(ACLMessage m) {
        String id = m.getContent();
        if (role != Role.NONE && id != null && id.equals(opId)) {
            Common.sendTo(this, ACLMessage.INFORM, mine.id, Common.T_UNLOCK, getLocalName(), null);
            clearOp();
            backoff(System.currentTimeMillis());
        }
    }

    private void checkTimeout(long now) {
        if (role == Role.NONE || now <= deadline) {
            return;
        }
        if (role == Role.WAIT_REPLY || role == Role.WAIT_DONE) {
            Common.sendTo(this, ACLMessage.INFORM, partner, Common.T_EXCH_CANCEL, opId, opId);
        }
        Common.sendTo(this, ACLMessage.INFORM, mine.id, Common.T_UNLOCK, getLocalName(), null);
        clearOp();
        backoff(now);
    }

    /** Меняем содержимое рюкзака и передаём свой предмет новому владельцу. */
    private void applyExchange() {
        Common.sendTo(this, ACLMessage.INFORM, mine.id, Common.T_TRANSFER, partner, null);
        bag.remove(mine.id);
        bag.put(theirs.id, theirs);
    }

    private void reportWeight() {
        Common.sendTo(this, ACLMessage.INFORM, Common.MANAGER, Common.T_BAG_UPDATE,
                opId + "|" + getLocalName() + "|" + weight(), null);
    }

    private void sendFinal() {
        finalSent = true;
        StringBuilder sb = new StringBuilder();
        for (Item it : bag.values()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(it.name).append('=').append(it.g);
        }
        Common.sendTo(this, ACLMessage.INFORM, Common.MANAGER, Common.T_FINAL,
                getLocalName() + "|" + weight() + "|" + sb, null);
        System.out.println("[" + getLocalName() + "] итоговый вес рюкзака " + Common.kg(weight()));
    }

    // ------------------------------------------------------------------ утилиты

    private int weight() {
        int s = 0;
        for (Item it : bag.values()) {
            s += it.g;
        }
        return s;
    }

    private void clearOp() {
        role = Role.NONE;
        opId = null;
        partner = null;
        mine = null;
        theirs = null;
    }

    private void backoff(long now) {
        nextAttemptAt = now + 100 + rnd.nextInt(400);
    }
}
