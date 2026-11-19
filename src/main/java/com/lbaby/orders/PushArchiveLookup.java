package com.lbaby.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Service
@Profile("pos-shadow")
class PushArchiveLookup {
    enum Status { SCANNED, MATCHES, NO_MATCH, NO_ARCHIVES, NOT_CONFIGURED, READ_ERROR, TRUNCATED }
    record Coverage(LocalDate date, Instant observedAt, Status status, List<String> files, int filesCompleted) {}
    record Line(String file, long line, String kind, String logTime, List<String> markers) {}
    record Report(String source, LocalDate date, Instant observedAt, Status status,
                  List<String> files, int filesCompleted, List<Line> lines) {}

    private static final Pattern TIME=Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})");
    private static final List<String> MARKERS=List.of("pushFinishedOrder","MeituanController","MeituanServiceImpl",
            " ERROR "," WARN ","Lock wait timeout exceeded","Duplicate entry","NullPointerException",
            "MySQLTransactionRollbackException","SocketTimeoutException","订单已存在");
    private final Path source;

    PushArchiveLookup(@Value("${PUSH_LOG_ARCHIVE:}") String path) {
        source=path.isBlank() ? null : Path.of(path);
    }

    Report search(LocalDate date,String order) {
        var lines=new ArrayList<Line>();
        int[] remaining={0};
        var orderNumber=Pattern.compile("(?<![0-9])"+Pattern.quote(order)+"(?![0-9])");
        var coverage=scan(date,(file,number,text) -> {
            boolean hit=text.contains(order) && orderNumber.matcher(text).find();
            if (hit || remaining[0]>0) {
                if (lines.size()==256) throw new Limit();
                var time=TIME.matcher(text);
                lines.add(new Line(file,number,hit ? "ORDER_MATCH" : "CONTEXT",time.find() ? time.group(1) : null,
                        MARKERS.stream().filter(text::contains).map(String::strip).toList()));
                remaining[0]=hit ? 50 : remaining[0]-1;
            }
        });
        var status=coverage.status()==Status.SCANNED ? (lines.isEmpty() ? Status.NO_MATCH : Status.MATCHES) : coverage.status();
        return new Report("B_PUSH_CENTER_ARCHIVE",date,coverage.observedAt(),status,coverage.files(),coverage.filesCompleted(),List.copyOf(lines));
    }

    interface Visitor { void accept(String file,long number,String text) throws IOException; }

    Coverage scan(LocalDate date,Visitor visitor) {
        var observed=Instant.now();
        var files=new ArrayList<String>();
        var status=Status.NOT_CONFIGURED;
        int completed=0;
        if (source!=null) {
            var filename=Pattern.compile("b-push-center-server\\.log\\."+Pattern.quote(date.toString())+"\\.[A-Za-z0-9_.-]+\\.gz");
            try {
                boolean directory=Files.isDirectory(source);
                if (directory) {
                    try (var entries=Files.list(source)) {
                        entries.filter(p -> Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
                                .map(p -> p.getFileName().toString()).filter(n -> filename.matcher(n).matches())
                                .sorted().forEach(files::add);
                    }
                } else if (!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Archive path unavailable");
                } else if (filename.matcher(source.getFileName().toString()).matches()) {
                    files.add(source.getFileName().toString());
                }
                long characters=0;
                for (var file:files) {
                    var path=directory ? source.resolve(file) : source;
                    try (var gz=new GZIPInputStream(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS));
                         var reader=new BufferedReader(new InputStreamReader(gz,StandardCharsets.UTF_8))) {
                        long number=0;
                        String text;
                        while ((text=readLine(reader))!=null) {
                            number++;
                            characters+=text.length()+1;
                            if (characters>512L*1024*1024) throw new Limit();
                            visitor.accept(file,number,text);
                        }
                    }
                    completed++;
                }
                status=files.isEmpty() ? Status.NO_ARCHIVES : Status.SCANNED;
            } catch (Limit e) { status=Status.TRUNCATED; }
            catch (IOException | UncheckedIOException e) { status=Status.READ_ERROR; }
        }
        return new Coverage(date,observed,status,List.copyOf(files),completed);
    }

    static final class Limit extends IOException {}

    private static String readLine(BufferedReader reader) throws IOException {
        var line=new StringBuilder();
        int ch;
        while ((ch=reader.read())!=-1 && ch!='\n') {
            if (line.length()>=1024*1024) throw new Limit();
            line.append((char)ch);
        }
        return ch==-1 && line.isEmpty() ? null : line.toString();
    }
}
