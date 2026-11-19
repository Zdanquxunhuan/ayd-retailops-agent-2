package com.lbaby.orders;

import java.util.*;
import java.util.regex.Pattern;

/** Request boundaries are scoped to one archive, PID and worker. A thread name alone is reusable. */
final class PushAttemptTrace implements PushArchiveLookup.Visitor {
    record Attempt(String file,String time,long controllerLine,Long beforeLine,Long resultLine,Long afterLine,
                   String serviceOutcome) {}
    private static final Pattern HEADER=Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\w+\\s+(\\d+) --- \\[\\s*([^\\]]{1,100})\\]\\s+(\\S+)\\s+: ");
    private static final Pattern ROUTE=Pattern.compile(
            "^(Before|After) request \\[POST (/meituan/pushFinishedOrder(?:/fake)?)(?=[?,\\] ]|$)");
    private static final String ENTRY="准备推送的订单号为 order_id:";
    private static class Window {
        String route, time, outcome;
        Long start, entry, result;
        boolean ambiguous, otherEntry;
    }
    private final Map<String,Window> workers=new HashMap<>();
    private final List<Attempt> attempts=new ArrayList<>();
    private final String order;
    private final Map<String,String> results;
    private String file;
    PushAttemptTrace(String order) {
        this.order=order;
        results=Map.of("订单"+order+"信息推送成功！","REPORTED_SUCCESS",
                "订单"+order+"信息推送失败！","REPORTED_FAILURE",order+"订单已存在","REPORTED_DUPLICATE");
    }

    public void accept(String archive,long number,String text) throws PushArchiveLookup.Limit {
        if (!archive.equals(file)) { flush(); file=archive; }
        var header=HEADER.matcher(text);
        if (!header.find()) return;
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
            }
        }
    }

    private void save(Window w,Long end) throws PushArchiveLookup.Limit {
        if (w==null || w.entry==null) return;
        if (attempts.size()>=256) throw new PushArchiveLookup.Limit();
        boolean linked=w.start!=null && end!=null && !w.ambiguous && w.result!=null;
        attempts.add(new Attempt(file,w.time,w.entry,w.start,linked ? w.result : null,end,
                linked ? w.outcome : "UNKNOWN"));
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
