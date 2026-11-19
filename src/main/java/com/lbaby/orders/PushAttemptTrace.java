package com.lbaby.orders;

import java.util.*;
import java.util.regex.Pattern;

/** Request boundaries are scoped to one archive, PID and worker. A thread name alone is reusable. */
final class PushAttemptTrace implements PushArchiveLookup.Visitor {
    record Attempt(String file,String time,long controllerLine,Long beforeLine,Long resultLine,Long afterLine,
                   String serviceOutcome,FailureEvidence failure) {}
    record FailureEvidence(long exceptionLine,String exceptionType,String safeMessage,Long frameLine,String businessMethod) {}
    private static final Pattern EXCEPTION=Pattern.compile(
            "((?:java\\.(?:lang|sql)|org\\.springframework\\.dao)\\.[A-Za-z]{1,80}Exception)(?::(.*))?");
    private static final Pattern FRAME=Pattern.compile(
            "at (cn\\.com\\.huoban168\\.b\\.push\\.center\\.(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*)\\([\\w$]+\\.java:\\d+\\)");
    private static final String LEDGER_MESSAGE="收益流水和销售流水不同";
    private static final Pattern HEADER=Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\w+\\s+(\\d+) --- \\[\\s*([^\\]]{1,100})\\]\\s+(\\S+)\\s+: ");
    private static final Pattern ROUTE=Pattern.compile(
            "^(Before|After) request \\[POST (/meituan/pushFinishedOrder(?:/fake)?)(?=[?,\\] ]|$)");
    private static final String ENTRY="准备推送的订单号为 order_id:";
    private static class Window {
        String route, time, outcome;
        Long start, entry, result;
        boolean ambiguous, otherEntry;
        FailureEvidence failure;
    }
    private final Map<String,Window> workers=new HashMap<>();
    private final List<Attempt> attempts=new ArrayList<>();
    private final String order;
    private final Map<String,String> results;
    private String file;
    // Continuations without a header belong only to the immediately preceding log event.
    private Window exceptionOwner;
    PushAttemptTrace(String order) {
        this.order=order;
        results=Map.of("订单"+order+"信息推送成功！","REPORTED_SUCCESS",
                "订单"+order+"信息推送失败！","REPORTED_FAILURE",order+"订单已存在","REPORTED_DUPLICATE");
    }

    public void accept(String archive,long number,String text) throws PushArchiveLookup.Limit {
        if (!archive.equals(file)) { exceptionOwner=null; flush(); file=archive; }
        var header=HEADER.matcher(text);
        if (!header.find()) {
            if (exceptionOwner!=null) readContinuation(number,text);
            return;
        }
        exceptionOwner=null;
        String worker=header.group(2)+"/"+header.group(3).strip(), logger=header.group(4);
        int start=header.end();
        if (logger.endsWith(".RequestLoggingFilter") &&
                (text.startsWith("Before request",start) || text.startsWith("After request",start))) {
            var route=ROUTE.matcher(text.substring(start,Math.min(text.length(),start+100)));
            var previous=workers.remove(worker);
            boolean matched=route.find();
            Long end=matched && "After".equals(route.group(1)) && previous!=null
                    && route.group(2).equals(previous.route) ? Long.valueOf(number) : null;
            save(previous,end);
            if (matched && "Before".equals(route.group(1))) {
                if (workers.size()>=4096) throw new PushArchiveLookup.Limit();
                var window=new Window();
                window.route=route.group(2); window.start=number;
                workers.put(worker,window);
            }
            return;
        }
        var window=workers.get(worker);
        if (logger.endsWith(".MeituanController") && text.startsWith(ENTRY,start)) {
            boolean target=text.substring(start+ENTRY.length()).strip().equals(order);
            if (window==null) {
                if (target) {
                    var orphan=new Window();
                    orphan.entry=number; orphan.time=header.group(1); save(orphan,null);
                }
            } else if (target) {
                window.ambiguous |= window.entry!=null || window.otherEntry;
                window.entry=number; window.time=header.group(1);
            } else { window.otherEntry=true; window.ambiguous |= window.entry!=null; }
        } else if (window!=null && window.entry!=null && logger.endsWith(".MeituanServiceImpl") && text.length()-start<160) {
            String outcome=results.get(text.substring(start).stripTrailing());
            if (outcome!=null) {
                window.ambiguous |= window.result!=null;
                window.result=number; window.outcome=outcome;
                if ("REPORTED_FAILURE".equals(outcome) && !window.ambiguous) exceptionOwner=window;
            }
        }
    }

    private void readContinuation(long number,String text) {
        var w=exceptionOwner;
        exceptionOwner=null;
        if (number-w.result>64 || text.length()>2048) return;
        String line=text.strip();
        if (w.failure==null) {
            if (line.isEmpty() && number==w.result+1) { exceptionOwner=w; return; }
            var error=EXCEPTION.matcher(line);
            if (!error.matches()) return;
            String message=LEDGER_MESSAGE.equals(Objects.toString(error.group(2),"").strip()) ? LEDGER_MESSAGE : null;
            w.failure=new FailureEvidence(number,error.group(1),message,null,null);
            exceptionOwner=w;
        } else if (line.startsWith("at ")) {
            var frame=FRAME.matcher(line);
            if (frame.matches()) {
                var e=w.failure;
                w.failure=new FailureEvidence(e.exceptionLine(),e.exceptionType(),e.safeMessage(),number,frame.group(1));
            } else if (line.matches("at [\\w.$/]+\\([^()]*\\)")) {
                exceptionOwner=w;
            }
        }
    }

    private void save(Window w,Long end) throws PushArchiveLookup.Limit {
        if (w==null || w.entry==null) return;
        if (attempts.size()>=256) throw new PushArchiveLookup.Limit();
        boolean linked=w.start!=null && end!=null && !w.ambiguous && w.result!=null;
        attempts.add(new Attempt(file,w.time,w.entry,w.start,linked ? w.result : null,end,
                linked ? w.outcome : "UNKNOWN",linked ? w.failure : null));
    }
    private void flush() throws PushArchiveLookup.Limit {
        for (var w:workers.values()) save(w,null);
        workers.clear();
    }
    List<Attempt> finish() throws PushArchiveLookup.Limit {
        flush();
        return sorted();
    }
    List<Attempt> sorted() {
        return attempts.stream().sorted(Comparator.comparing(Attempt::time).thenComparing(Attempt::file)
                .thenComparingLong(Attempt::controllerLine)).toList();
    }
}
