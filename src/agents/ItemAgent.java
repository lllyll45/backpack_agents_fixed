package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Предмет. Знает только своё название, массу, текущего владельца и признак блокировки.
 * Сам регистрируется у менеджера и сам ищет владельца среди туристов (через DF).
 */
public class ItemAgent extends Agent {

    private enum State {WAIT_START, SEARCH, LATE}

    private static final long OFFER_TIMEOUT = 8000;

    private String itemName;
    private int grams;

    private State state = State.WAIT_START;
    private String owner;          // локальное имя владельца или null
    private boolean locked;        // предмет участвует в согласованном обмене

    private boolean pending;       // предложение отправлено, ответа ещё нет
    private String pendingId;
    private long pendingDeadline;
    private long nextOfferAt;
    private int offerSeq;

    private final Deque<AID> queue = new ArrayDeque<>();
    private final Random rnd = new Random();

    @Override
    protected void setup() {
        Object[] a = getArguments();
        itemName = a[0].toString();
        grams = Integer.parseInt(a[1].toString());

        // Предмет сам сообщает менеджеру о регистрации (Main этого не делает).
        Common.sendTo(this, ACLMessage.INFORM, Common.MANAGER, Common.T_REGISTER,
                "Привет, я зарегистрировался", null);
        System.out.println("[" + getLocalName() + "] предмет \"" + itemName + "\" (" + Common.kg(grams)
                + ") зарегистрирован, ждём старта");

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage m;
                while ((m = myAgent.receive()) != null) {
                    ItemAgent.this.handle(m);
                }
                search();
                block(100);
            }
        });
    }

    private void handle(ACLMessage m) {
        String type = m.getOntology();
        if (type == null) {
            return;
        }
        String sender = m.getSender().getLocalName();
        long now = System.currentTimeMillis();

        switch (type) {
            case Common.T_START: {
                if (state == State.WAIT_START) {
                    state = State.SEARCH;
                    nextOfferAt = now + rnd.nextInt(300);
                    System.out.println("[" + getLocalName() + "] старт поиска владельца для \"" + itemName + "\"");
                }
                break;
            }
            case Common.T_LATE: {
                state = State.LATE;
                System.out.println("[" + getLocalName() + "] регистрация закрыта, предмет \"" + itemName
                        + "\" не участвует в распределении");
                break;
            }
            case Common.T_OFFER: {
                boolean current = pending && pendingId != null && pendingId.equals(m.getConversationId());
                if (m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
                    if (current && owner == null) {
                        pending = false;
                        owner = sender;
                        locked = false;
                        System.out.println("[" + getLocalName() + "] \"" + itemName + "\" принят туристом " + sender);
                        notifyManager("OWNED");
                    } else {
                        System.out.println("[" + getLocalName() + "] устаревший ACCEPT от " + sender + " → REVOKE");
                        // ответ устарел (например, по таймауту предложили другому) - владелец должен вернуть предмет
                        Common.sendTo(this, ACLMessage.INFORM, sender, Common.T_REVOKE, "", null);
                    }
                } else if (current) {
                    pending = false;
                    nextOfferAt = queue.isEmpty() ? now + 300 + rnd.nextInt(500) : now + 20;
                }
                break;
            }
            case Common.T_DROP: {
                // турист освободил место и отдал этот предмет
                if (owner != null && owner.equals(sender)) {
                    System.out.println("[" + getLocalName() + "] \"" + itemName + "\" освобождён туристом " + sender);
                    owner = null;
                    locked = false;
                    queue.clear();
                    nextOfferAt = now + 200 + rnd.nextInt(400);
                    notifyManager("NOT_OWNED");
                }
                break;
            }
            case Common.T_LOCK: {
                if (owner != null && owner.equals(sender)) {
                    locked = true;
                }
                break;
            }
            case Common.T_UNLOCK: {
                if (owner != null && owner.equals(sender)) {
                    locked = false;
                }
                break;
            }
            case Common.T_TRANSFER: {
                // обмен: текущий владелец передаёт предмет новому
                if (owner != null && owner.equals(sender)) {
                    String prev = owner;
                    owner = m.getContent();
                    locked = false;
                    System.out.println("[" + getLocalName() + "] \"" + itemName + "\" передан: " + prev + " → " + owner);
                }
                break;
            }
            default:
                break;
        }
    }

    private void search() {
        if (state != State.SEARCH || owner != null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (pending) {
            if (now > pendingDeadline) {
                System.out.println("[" + getLocalName() + "] таймаут предложения \"" + itemName + "\", ищем дальше");
                pending = false;
                nextOfferAt = now;
            }
            return;
        }
        if (now < nextOfferAt) {
            return;
        }
        if (queue.isEmpty()) {
            List<AID> tourists = Common.findTourists(this);
            Collections.shuffle(tourists, rnd);
            queue.addAll(tourists);
            if (queue.isEmpty()) {
                nextOfferAt = now + 500;
                return;
            }
        }
        AID target = queue.poll();
        pendingId = getLocalName() + "-" + (++offerSeq);
        pending = true;
        pendingDeadline = now + OFFER_TIMEOUT;
        System.out.println("[" + getLocalName() + "] предлагаю \"" + itemName + "\" (" + Common.kg(grams)
                + ") туристу " + target.getLocalName());
        Common.send(this, ACLMessage.PROPOSE, target, Common.T_OFFER, itemName + "|" + grams, pendingId);
    }

    private void notifyManager(String what) {
        Common.sendTo(this, ACLMessage.INFORM, Common.MANAGER, Common.T_OWNER, what, null);
    }
}
