package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Общие константы и вспомогательные методы для всех агентов.
 * Тип сообщения передаётся в поле ontology, содержимое - строка с разделителем "|".
 * Масса везде хранится в граммах (int), чтобы не накапливать ошибки округления.
 */
public final class Common {
    private Common() {
    }

    /** Локальное имя агента-менеджера. */
    public static final String MANAGER = "manager";
    /** Тип сервиса в DF (жёлтые страницы), под которым регистрируются туристы. */
    public static final String TOURIST_SERVICE = "backpack-tourist";

    // ---- типы сообщений ----
    public static final String T_REGISTER = "REGISTER";        // предмет -> менеджер
    public static final String T_START = "START";              // менеджер -> предметы (начать поиск владельца)
    public static final String T_LATE = "LATE";                // менеджер -> предмет (опоздал с регистрацией)
    public static final String T_OWNER = "OWNER";              // предмет -> менеджер: OWNED / NOT_OWNED
    public static final String T_OFFER = "OFFER";              // предмет -> турист (PROPOSE), ответ ACCEPT/REJECT
    public static final String T_DROP = "DROP";                // турист -> предмет: "я тебя освободил"
    public static final String T_REVOKE = "REVOKE";            // предмет -> турист: принятие устарело, верни
    public static final String T_LOCK = "LOCK";                // турист -> предмет
    public static final String T_UNLOCK = "UNLOCK";            // турист -> предмет
    public static final String T_TRANSFER = "TRANSFER";        // турист -> предмет: новый владелец
    public static final String T_ACTIVATE = "ACTIVATE";        // менеджер -> турист (вместимость)
    public static final String T_REQUEST_BAG = "REQUEST_BAG";  // менеджер -> турист
    public static final String T_BAG_INFO = "BAG_INFO";        // турист -> менеджер: id|вес
    public static final String T_AVG = "AVG";                  // менеджер -> турист: среднее|состав
    public static final String T_EXCH = "EXCH";                // турист <-> турист (PROPOSE/ACCEPT/REJECT)
    public static final String T_EXCH_CONFIRM = "EXCH_CONFIRM";
    public static final String T_EXCH_DONE = "EXCH_DONE";
    public static final String T_EXCH_CANCEL = "EXCH_CANCEL";
    public static final String T_BAG_UPDATE = "BAG_UPDATE";    // турист -> менеджер: opId|id|вес
    public static final String T_STOP = "STOP";                // менеджер -> туристы
    public static final String T_FINAL = "FINAL";              // турист -> менеджер: id|вес|состав

    private static final Locale RU = Locale.forLanguageTag("ru-RU");

    /** AID по локальному имени (платформа достраивается автоматически). */
    public static AID local(String name) {
        return new AID(name, AID.ISLOCALNAME);
    }

    public static void send(Agent from, int performative, AID to, String type, String content) {
        send(from, performative, to, type, content, null);
    }

    public static void send(Agent from, int performative, AID to, String type, String content, String conv) {
        ACLMessage m = new ACLMessage(performative);
        m.addReceiver(to);
        m.setOntology(type);
        m.setContent(content);
        if (conv != null) {
            m.setConversationId(conv);
        }
        from.send(m);
    }

    public static void sendTo(Agent from, int performative, String localName, String type, String content, String conv) {
        send(from, performative, local(localName), type, content, conv);
    }

    /** Все туристы, зарегистрированные в DF, отсортированные по имени. */
    public static List<AID> findTourists(Agent a) {
        List<AID> res = new ArrayList<>();
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(TOURIST_SERVICE);
            template.addServices(sd);
            DFAgentDescription[] found = DFService.search(a, template);
            for (DFAgentDescription d : found) {
                res.add(d.getName());
            }
        } catch (FIPAException e) {
            // DF временно недоступен - вернём пустой список, попытка будет повторена
        }
        res.sort(Comparator.comparing(AID::getLocalName));
        return res;
    }

    /** Разбор аргументов вида "--key value" или "--key=value". */
    public static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                continue;
            }
            String k = a.substring(2);
            String v = "true";
            int eq = k.indexOf('=');
            if (eq >= 0) {
                v = k.substring(eq + 1);
                k = k.substring(0, eq);
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                v = args[++i];
            }
            m.put(k.toLowerCase(), v);
        }
        return m;
    }

    public static String[] split(String s) {
        return s.split("\\|", -1);
    }

    public static int toGrams(String kg) {
        return (int) Math.round(Double.parseDouble(kg.trim().replace(',', '.')) * 1000.0);
    }

    public static String kg(double grams) {
        return String.format(RU, "%.2f кг", grams / 1000.0);
    }
}
