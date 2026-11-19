package com.lbaby.orders;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.zip.GZIPOutputStream;
import static org.assertj.core.api.Assertions.assertThat;

class PushArchiveTest {
    @TempDir Path dir;
    final LocalDate day=LocalDate.of(2026,11,11);
    Path write(int part,String data) throws IOException {
        var p=dir.resolve("b-push-center-server.log."+day+"."+part+".gz");
        try (var out=new GZIPOutputStream(Files.newOutputStream(p))) { out.write(data.getBytes(StandardCharsets.UTF_8)); }
        return p;
    }
    @Test void windowsOverlapContinueAcrossRotationsAndOmitUnrelatedPayloads() throws Exception {
        write(1,"1234567\n2026-11-11 10:00:00.001 INFO pushFinishedOrder 123456\nsecret=value\n");
        write(2,"123456\n"+"another order 999999 ERROR NullPointerException\n".repeat(51));
        var report=new PushArchiveLookup(dir.toString()).search(day,"123456");
        assertThat(report.status()).isEqualTo(PushArchiveLookup.Status.MATCHES);
        assertThat(report.filesCompleted()).isEqualTo(2);
        assertThat(report.lines()).hasSize(53);
        assertThat(report.lines().getFirst().line()).isEqualTo(2);
        assertThat(report.lines().getFirst().logTime()).isEqualTo("2026-11-11 10:00:00.001");
        assertThat(report.lines().getLast().line()).isEqualTo(51);
        assertThat(report.lines().getLast().kind()).isEqualTo("CONTEXT");
        assertThat(report.toString()).doesNotContain("999999","secret=value");
    }
    @Test void distinguishesUnavailableCoverageCorruptionAndLimits() throws Exception {
        var source=new PushArchiveLookup(dir.toString());
        assertThat(source.search(day,"1").status()).isEqualTo(PushArchiveLookup.Status.NO_ARCHIVES);
        var archive=write(1,"2\n");
        assertThat(source.search(day,"1").status()).isEqualTo(PushArchiveLookup.Status.NO_MATCH);
        Files.writeString(archive,"broken");
        assertThat(source.search(day,"1").status()).isEqualTo(PushArchiveLookup.Status.READ_ERROR);
        write(1,"1\n".repeat(257));
        assertThat(source.search(day,"1").status()).isEqualTo(PushArchiveLookup.Status.TRUNCATED);
    }
}
