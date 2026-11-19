package com.lbaby.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Two independent app JVMs, one H2 file. The only substituted production component is the model. */
class RestartCheckpointTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final Map<String,java.io.ByteArrayOutputStream> logs=new HashMap<>();
    private final Map<Process,Thread> readers=new HashMap<>();

    @TestConfiguration(proxyBeanMethods=false)
    static class ReplayConfiguration {
        @Bean @Primary
        SynchronousRepair interruptedWriter(SandboxPos pos) {
            return order -> {
                var reply=pos.push(order);
                System.out.println("SANDBOX_WRITE_COMMITTED");
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return reply;
            };
        }
        @Bean @Primary
        InvestigationModel replayPlanner() {
            return (ctx, schemas, timeout) -> {
                boolean repair = !ctx.previousTurn().isEmpty();
                String ref = repair ? "REPAIR-02" : "PUSH-01";
                var anchor = ctx.evidence().stream().filter(e -> e.kind().equals("LOG")
                        && e.source().equals("GATEWAY") && ref.equals(e.requestRef())).findFirst();
                var writer = ctx.evidence().stream().filter(e -> e.kind().equals("LOG")
                        && e.source().equals("POS_WRITER") && ref.equals(e.requestRef())).findFirst();
                var args = new LinkedHashMap<String,Object>();
                args.put("from","2026-11-18T02:00:00Z");
                args.put("until","2026-11-18T03:00:00Z");
                if (anchor.isEmpty()) {
                    args.put("source","GATEWAY");
                    return choice("searchOrderLogs",args);
                }
                if (writer.isEmpty()) {
                    if (!repair && ctx.remainingTools()!=5) throw new AssertionError("Lost checkpoint budget");
                    args.put("source","POS_WRITER"); args.put("anchor",anchor.get().id());
                    return choice("followLogReference",args);
                }
                return choice("finish",Map.of("answer",writer.get().excerpt(),"citations",List.of(anchor.get().id(),writer.get().id())));
            };
        }
        private InvestigationModel.Decision choice(String action,Map<String,?> args) {
            return new InvestigationModel.Decision(action,JSON.valueToTree(args),0);
        }
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(OrderApplication.class,ReplayConfiguration.class).run(args);
    }

    @Test
    void killedJvmResumesWithoutReplayingGatewayAndNextShiftCanFollowRepair() throws Exception {
        JsonNode paused;
        String casePath;
        String repairPath;
        var first = start("one");
        try {
            String base = url(first,"one");
            var incident = post(base,Map.of("orderId","123457"),"demo-ops-a");
            casePath="/"+incident.get("id").asText();
            paused=post(base+casePath+"/investigations",Map.of("question","第一次回传为什么失败？","stepLimit",1),"demo-ops-a");
            assertThat(paused.path("status").asText()).isEqualTo("PAUSED");
            var repairCase=post(base,Map.of("orderId","123456"),"demo-ops-a");
            var applications="/"+repairCase.get("id").asText()+"/repair-attempts";
            var app=post(base+applications,Map.of(),"demo-ops-a");
            repairPath=applications+"/"+app.get("id").asText();
            var confirm=HttpRequest.newBuilder(URI.create(base+repairPath+"/confirm"))
                    .header("Authorization","Bearer demo-ops-a").header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(app.get("target").toString())).build();
            client.sendAsync(confirm,HttpResponse.BodyHandlers.discarding());
            long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while (!wrote("one") && System.nanoTime()<end) Thread.sleep(100);
            assertThat(wrote("one")).isTrue();
        } finally {kill(first);}
        var next = start("two");
        try {
            String base=url(next,"two")+casePath+"/investigations/"+paused.get("id").asText();
            var resumed=post(base+"/resume",Map.of(),"demo-ops-a-next");
            assertThat(resumed.path("status").asText()).isEqualTo("FINISHED");
            assertThat(resumed.path("steps").findValuesAsText("action")).containsExactly("searchOrderLogs","followLogReference","finish");
            assertThat(resumed.path("steps").findValuesAsText("operator")).containsExactly("duty-a","next-shift-a","next-shift-a");
            assertThat(resumed.at("/steps/0")).isEqualTo(paused.at("/steps/0"));
            var follow=post(base+"/follow-ups",Map.of("question","那后来补进去的那次呢？"),"demo-ops-a-next");
            assertThat(follow.path("answer").asText()).contains("补单写入 POS 成功");
            assertThat(follow.path("steps").findValuesAsText("action")).containsExactly("followLogReference","finish");
            var now=post(base+"/follow-ups",Map.of("question","现在 POS 有单吗？"),"demo-ops-a-next");
            assertThat(now.path("steps").findValuesAsText("action")).containsExactly("queryPosOrder");
            var lookup=HttpRequest.newBuilder(URI.create(url(next,"two")+repairPath))
                    .header("Authorization","Bearer demo-ops-a-next").build();
            var recovered=JSON.readTree(client.send(lookup,HttpResponse.BodyHandlers.ofString()).body());
            assertThat(recovered.path("status").asText()).isEqualTo("VERIFIED");
            assertThat(recovered.at("/execution/callResult").asText()).isEqualTo("UNKNOWN");
            assertThat(recovered.at("/execution/checkedBy").asText()).isEqualTo("sandbox-recovery");
            assertThat(wrote("two")).isFalse();
            JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target","v1.4-restart-replay.json").toFile(),
                    Map.of("model","scripted offline replay","order","123457","paused",paused,
                            "afterProcessRestart",resumed,"repairFollowUp",follow,"currentPos",now));
        } finally {kill(next);}
    }

    private boolean wrote(String label) throws Exception {
        return logs.get(label).toString(java.nio.charset.Charset.defaultCharset())
                .contains("SANDBOX_WRITE_COMMITTED");
    }

    private Process start(String label) throws Exception {
        var java = Path.of(System.getProperty("java.home"),"bin","java").toString();
        var cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var pb=new ProcessBuilder(java,"-cp",cp,RestartCheckpointTest.class.getName(),"--server.port=0",
                "--spring.datasource.url=jdbc:h2:file:"+directory.resolve("cases")+";WRITE_DELAY=0");
        pb.environment().remove("DASHSCOPE_API_KEY");
        var process=pb.redirectErrorStream(true).start();
        var output=new java.io.ByteArrayOutputStream();
        logs.put(label,output);
        readers.put(process,Thread.startVirtualThread(() -> {
            try (var input=process.getInputStream()) { input.transferTo(output); }
            catch (java.io.IOException closedOnKill) { /* A forced process exit can close the pipe. */ }
        }));
        return process;
    }

    private String url(Process p,String label) throws Exception {
        var pattern=Pattern.compile("Tomcat started on port (\\d+)");
        var log=logs.get(label);
        long end=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while (p.isAlive() && System.nanoTime()<end) {
            var match=pattern.matcher(log.toString(java.nio.charset.Charset.defaultCharset()));
            if (match.find()) return "http://127.0.0.1:"+match.group(1)+"/cases";
            Thread.sleep(150);
        }
        throw new AssertionError("Application startup failed: "+log);
    }

    private JsonNode post(String url,Object body,String operator) throws Exception {
        var req=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Authorization","Bearer "+operator).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        var res=client.send(req,HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isBetween(200,201);
        return JSON.readTree(res.body());
    }

    private void kill(Process p) throws Exception {
        assertThat(p.destroyForcibly().waitFor(10,TimeUnit.SECONDS)).isTrue();
        assertThat(readers.get(p).join(Duration.ofSeconds(5))).isTrue();
    }
}
