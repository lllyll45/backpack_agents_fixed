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

public class ItemAgent extends Agent {
    private double weight = 1.0;
    private String owner;
    private String lockedBy;
    private String lockTransaction;
    private AID manager;
    private boolean distributionStarted;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            try {
                weight = Double.parseDouble(args[0].toString().replace(',', '.'));
            } catch (NumberFormatException e) {
                log("Некорректная масса в аргументах. Используется 1.0 кг.");
            }
        }

        register("item", "Backpack-Item");

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg == null) {
                    block();
                    return;
                }
                ItemAgent.this.handle(msg);
            }
        });

        addBehaviour(new TickerBehaviour(this, 1000) {
            @Override
            protected void onTick() {
                if (manager == null) {
                    manager = findService("manager");
                    if (manager != null) {
                        sendTo(manager, "READY_ITEM|" + itemName() + "|" + weight);
                    }
                }

            }
        });

        log("Запущен. Масса предмета: " + fmt(weight) + " кг");
    }

    private void handle(ACLMessage msg) {
        String content = msg.getContent() == null ? "" : msg.getContent();
        String sender = msg.getSender().getLocalName();

        if (content.equals("START_OFFER")) {
            distributionStarted = true;
            log("Готов к передаче по запросу менеджера.");
        } else if (content.startsWith("TAKE_ITEM|")) {
            String[] p = content.split("\\|", 2);
            if (p.length == 2 && p[1].equals(itemName()) && owner == null && lockedBy == null) {
                owner = sender;
                log("Предмет принят туристом " + sender + ".");
                reply(msg, "ITEM_ACCEPTED|" + itemName() + "|" + weight);
                reportOwner();
            } else {
                log("Отклонён запрос на принятие от " + sender + ": предмет уже занят или запрос некорректен.");
                reply(msg, "ITEM_TAKE_REFUSED|" + itemName());
            }
        } else if (content.startsWith("RELEASE_ITEM|")) {
            if (owner != null && owner.equals(sender) && lockedBy == null) {
                log("Турист " + sender + " освобождает предмет.");
                owner = null;
                reply(msg, "ITEM_RELEASED|" + itemName());
                reportOwner();
                log("Предмет освобождён и снова ищет владельца.");
            } else {
                log("Отказано в освобождении по запросу туриста " + sender +
                        ": предмет не принадлежит ему или заблокирован.");
                reply(msg, "ITEM_RELEASE_REFUSED|" + itemName());
            }
        } else if (content.startsWith("LOCK_ITEM|")) {
            String[] p = content.split("\\|", 2);
            if (p.length == 2 && sender.equals(owner) && lockedBy == null) {
                lockedBy = sender;
                lockTransaction = p[1];
                log("Заблокирован владельцем " + sender +
                        " для обмена. Транзакция: " + lockTransaction);
                reply(msg, "LOCK_OK|" + p[1] + "|" + itemName());
            } else {
                log("Не удалось заблокировать предмет по запросу " + sender + ".");
                reply(msg, "LOCK_REFUSED|" + (p.length > 1 ? p[1] : "-") + "|" + itemName());
            }
        } else if (content.startsWith("TRANSFER_OWNER|")) {
            String[] p = content.split("\\|", 3);
            if (p.length == 3 && sender.equals(lockedBy)
                    && sender.equals(owner) && p[1].equals(lockTransaction)) {
                String oldOwner = owner;
                owner = p[2];
                log("Владелец изменён: " + oldOwner + " -> " + owner +
                        ". Транзакция: " + p[1]);
                reply(msg, "OWNER_CHANGED|" + p[1] + "|" + itemName() + "|" + owner);
                reportOwner();
            } else {
                log("Передача владельца отклонена. Отправитель: " + sender);
                reply(msg, "TRANSFER_REFUSED|" + (p.length > 1 ? p[1] : "-") + "|" + itemName());
            }
        } else if (content.startsWith("UNLOCK_ITEM|")) {
            String[] p = content.split("\\|", 2);
            if (p.length == 2 && sender.equals(lockedBy) && p[1].equals(lockTransaction)) {
                lockedBy = null;
                lockTransaction = null;
                log("Блокировка снята. Транзакция: " + p[1]);
                reply(msg, "UNLOCK_OK|" + p[1] + "|" + itemName());
            } else {
                log("Не удалось снять блокировку. Запрос от " + sender);
                reply(msg, "UNLOCK_REFUSED|" + itemName());
            }
        }
    }

    private void reportOwner() {
        if (manager != null) {
            String message = owner == null
                    ? "ITEM_UNOWNED|" + itemName()
                    : "ITEM_OWNED|" + itemName() + "|" + owner;
            sendTo(manager, message);
        }
    }

    private String itemName() {
        String name = getLocalName();
        return name.startsWith("item_") ? name.substring(5) : name;
    }

    private void register(String type, String name) {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(type);
        sd.setName(name);
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private AID findService(String type) {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(type);
        template.addServices(sd);
        try {
            DFAgentDescription[] result = DFService.search(this, template);
            return result.length == 0 ? null : result[0].getName();
        } catch (FIPAException e) {
            return null;
        }
    }

    private void sendTo(AID to, String content) {
        if (to == null) return;
        ACLMessage message = new ACLMessage(ACLMessage.INFORM);
        message.addReceiver(to);
        message.setContent(content);
        send(message);
    }

    private void reply(ACLMessage original, String content) {
        ACLMessage response = original.createReply();
        response.setPerformative(ACLMessage.INFORM);
        response.setContent(content);
        send(response);
    }

    private String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private void log(String message) {
        System.out.printf("[%1$tT] [ПРЕДМЕТ %2$s] %3$s%n",
                new Date(), itemName(), message);
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException ignored) {
        }
    }
}
