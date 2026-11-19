package com.lbaby.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:eval18;DB_CLOSE_DELAY=-1","repair.sandbox-enabled=false",
        "fixture.orders=eval/v1.8/orders.json","fixture.logs=eval/v1.8/logs.json"})
class ComparativeEvalTest {
    record Scenario(String order,String question,String group,String expected,String required,int refLogs,boolean current) {}
    record Response(String answer,List<UUID> ids,List<String> actions,String status) {}
    record Row(String order,String question,String group,String arm,String expected,String label,boolean correct,
               boolean grounded,boolean falseCertainty,int relevantCitations,int citations,int reads,int excessReads,
               int modelCalls,int tokens,long elapsedMs,String status,String answer,List<String> actions,List<String> citedFacts) {}
    private static final DemoIdentity ACTOR=new DemoIdentity("duty-a","033101");
    private static final Set<String> LABELS=Set.of("PRESENT","ABSENT","MAPPING","SUCCESS","LOCK","DUPLICATE","LEDGER","UNKNOWN");
    private static final String FORMAT=" finish 的 answer 只填一个代号：PRESENT=现在有单、ABSENT=现在无单、MAPPING=门店映射缺失、SUCCESS=当次写入成功、LOCK=锁等待超时、DUPLICATE=当次已存在、LEDGER=流水不一致、UNKNOWN=证据不足。引用直接支持结论的证据ID。";
    @Autowired ObjectMapper json;
    @Autowired IncidentCases cases;
    @Autowired AgentRunner runner;
    @Autowired InvestigationTools tools;
    @Autowired OrderLogInvestigation logs;
    @Autowired CaseEvidence evidence;
    @MockitoBean InvestigationModel planner;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean FixtureOrderSources countedOrders;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean FixtureOrderLogs countedLogs;
    int calls,tokens;
    String providerFailure;

    @Test void compare() throws Exception {
        String mode=System.getProperty("eval.mode","offline");
        assertThat(mode).isIn("offline","live");
        boolean live=mode.equals("live");
        String key=System.getenv("DASHSCOPE_API_KEY");
        if (live && (key==null || key.isBlank())) throw new IllegalStateException("Live evaluation requires DASHSCOPE_API_KEY");
        InvestigationModel delegate=live ? new QwenModel(json,key,System.getenv().getOrDefault("DASHSCOPE_BASE_URL",
                "https://dashscope.aliyuncs.com/compatible-mode/v1")) : new EvalReplay(json);
        when(planner.choose(any(),anyList(),any())).thenAnswer(inv->{
            calls++;
            try {
                var d=delegate.choose(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2));
                tokens+=d.tokens();
                return d;
            } catch (InvestigationModel.Unavailable e) {
                providerFailure=e.getMessage(); throw e;
            }
        });
        List<Scenario> dataset;
        try(var in=getClass().getResourceAsStream("/eval/v1.8/cases.json")) {
            dataset=List.of(json.readValue(in,Scenario[].class));
        }
        String selected=System.getProperty("eval.orders","");
        if (!selected.isBlank()) {
            var orders=Set.of(selected.split(","));
            dataset=dataset.stream().filter(s->orders.contains(s.order())).toList();
            assertThat(dataset.size()).isEqualTo(orders.size());
        }
        var rows=new ArrayList<Row>();
        int repetitions=Integer.getInteger("eval.repeats",1);
        assertThat(repetitions).isBetween(1,5);
        for (int repeat=0;repeat<repetitions;repeat++) {
            for (var s:dataset) for (String arm:List.of("A","B","C")) {
                calls=0;tokens=0;providerFailure=null;
                clearInvocations(countedOrders,countedLogs);
                long start=System.nanoTime();
                var incident=cases.create(s.order(),ACTOR);
                var result=switch(arm) {
                    case "A" -> rules(incident,s);
                    case "B" -> fixed(incident,s);
                    default -> investigate(incident,s);
                };
                long ms=Duration.ofNanos(System.nanoTime()-start).toMillis();
                if (providerFailure!=null) throw new IllegalStateException("Provider unavailable; benchmark incomplete: "+providerFailure);
                rows.add(score(s,arm,incident,result,ms));
            }
        }
        var path=Path.of("target","eval","v1.8-"+mode+(selected.isBlank()?"":"-subset")+".json");
        Files.createDirectories(path.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of("mode",mode,"model",live?"qwen-flash":"scripted replay",
                "suite","v1.8","repetitions",repetitions,"rows",rows));
        assertThat(rows).hasSize(dataset.size()*3*repetitions);
        if (!live) {
            assertThat(rows).filteredOn(r->r.arm().equals("C")).allMatch(Row::grounded);
            if (selected.isBlank()) assertThat(rows).filteredOn(r->r.arm().equals("B") && !r.correct()).isNotEmpty();
        }
    }

    private Response rules(IncidentCases.Incident c,Scenario s) {
        var pos=c.evidence().stream().filter(e->e.source().equals("POS_FIXTURE")).findFirst().orElseThrow();
        // A has no workflow for historical questions. It can only cite the present snapshot.
        String label=AgentRunner.currentPosQuestion(s.question())?switch(c.outcome()) {
            case POS_PRESENT->"PRESENT"; case POS_TIMEOUT,POS_DENIED->"UNKNOWN"; default->"ABSENT";
        }:"UNKNOWN";
        return new Response(label,List.of(pos.id()),List.of(),"FINISHED");
    }

    private Response fixed(IncidentCases.Incident c,Scenario s) {
        var facts=new ArrayList<>(c.evidence());
        var actions=new ArrayList<String>();
        for (String source:List.of("GATEWAY","POS_WRITER")) {
            var query=new OrderLogInvestigation.Search(source,Instant.parse("2026-11-18T02:00:00Z"),
                    Instant.parse("2026-11-18T02:10:00Z"),8);
            var page=logs.search(c.id(),ACTOR,query,null);
            facts.addAll(page.events());facts.add(page.searchEvidence());actions.add("search:"+source);
        }
        var ctx=new InvestigationModel.Context(s.question()+FORMAT,s.order(),"2026-11-18 Asia/Shanghai",
                facts,List.of(),List.of(),0,"");
        var schemas=tools.schemas().stream().filter(t->((Map<?,?>)t.get("function")).get("name").equals("finish")).toList();
        var d=planner.choose(ctx,schemas,Duration.ofSeconds(30));
        var seen=new LinkedHashMap<UUID,CaseEvidence.Item>();
        facts.forEach(e->seen.put(e.id(),e));
        var ids=new LinkedHashSet<UUID>();
        try {
            if (!d.action().equals("finish") || !d.args().path("citations").isArray()) throw new IllegalArgumentException();
            for(var node:d.args().path("citations")) {
                var id=UUID.fromString(node.asText());if(!seen.containsKey(id)) throw new IllegalArgumentException();
                ids.add(id);
            }
            if(ids.isEmpty() || ids.size()>16) throw new IllegalArgumentException();
            String proposed=InvestigationTools.string(d.args(),"answer");
            var answer=AgentRunner.currentPosQuestion(s.question()) ? proposed : AgentRunner.supportedAnswer(proposed,List.copyOf(ids),seen);
            return new Response(answer,List.copyOf(ids),actions,"FINISHED");
        } catch(IllegalArgumentException e) {return new Response("",List.of(),actions,"INVALID_ACTION");}
    }

    private Response investigate(IncidentCases.Incident c,Scenario s) {
        var run=runner.run(c.id(),ACTOR,s.question()+FORMAT,null,AgentRunner.Goal.AUTO,AgentRunner.MAX_TURNS);
        var actions=run.steps().stream().map(InvestigationHistory.Step::action).toList();
        return new Response(run.answer(),run.citations(),actions,run.status());
    }

    private Row score(Scenario s,String arm,IncidentCases.Incident c,Response r,long ms) {
        var cited=r.ids().stream().map(id->evidence.read(c.id(),id)).toList();
        String label=normalize(r.answer());
        boolean correct=r.status().equals("FINISHED") && label.equals(s.expected());
        int hits=(int)cited.stream().filter(e->direct(s.required(),e)).count();
        boolean certainty=s.expected().equals("UNKNOWN") && LABELS.contains(label) && !label.equals("UNKNOWN");
        int reads=(int)mockingDetails(countedOrders).getInvocations().stream()
                .filter(i->Set.of("queryPos","queryMeituan").contains(i.getMethod().getName())).count()
                +(int)mockingDetails(countedLogs).getInvocations().stream().filter(i->i.getMethod().getName().equals("search")).count();
        return new Row(s.order(),s.question(),s.group(),arm,s.expected(),label,correct,correct && hits>0,
                certainty,hits,cited.size(),reads,Math.max(0,reads-c.observations().size()-s.refLogs()),
                calls,tokens,ms,r.status(),r.answer(),r.actions(),cited.stream().map(e->
                e.kind()+"/"+e.source()+"/"+Objects.toString(e.requestRef(),"-")+"/"+e.excerpt()).toList());
    }

    private static boolean direct(String spec,CaseEvidence.Item item) {
        String[] parts=spec.split(":");
        return switch(parts[0]) {
            case "P" -> item.kind().equals("SNAPSHOT") && item.source().equals("POS_FIXTURE") && item.excerpt().equals(parts[1]);
            case "W" -> item.kind().equals("LOG") && item.source().equals("POS_WRITER") &&
                    parts[1].equals(item.requestRef()) && item.excerpt().contains(parts[2]);
            case "S" -> item.kind().equals("SEARCH") && item.source().equals(parts[1]) && item.excerpt().startsWith(parts[2]+" ");
            default -> throw new IllegalArgumentException(spec);
        };
    }
    static String normalize(String answer) {
        if (LABELS.contains(answer.strip())) return answer.strip();
        if (answer.startsWith("本次查询已在 POS 查到")) return "PRESENT";
        if (answer.startsWith("本次查询在 POS 未找到")) return "ABSENT";
        if (answer.contains("无法确认当次写入结果") || answer.contains("当前入库状态无法确认")) return "UNKNOWN";
        return "UNCLASSIFIED";
    }
}
