package at.esoren.vystormchat;

import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReportStoreTest {
    @TempDir Path dir;
    private final Logger log=Logger.getAnonymousLogger();
    private final UUID reporter=UUID.randomUUID(),target=UUID.randomUUID(),staff=UUID.randomUUID();
    private ReportStore store(){return new ReportStore(dir.resolve("reports.yml"),log,Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);}
    @Test void reportAndStaffHistorySurviveRestartWithoutAutomaticSanctions(){
        ReportStore s=store();assertEquals(ReportStore.Result.SAVED,s.submit(reporter,"Reporter",target,"Target","Repeated public spam",100_000));
        var r=s.list(100_000).getFirst();assertTrue(r.open(100_000));assertTrue(r.history().isEmpty());
        assertEquals(ReportStore.Result.SAVED,s.update(r.id(),staff,"note","Checked public channel",110_000));
        assertEquals(ReportStore.Result.SAVED,s.update(r.id(),staff,"resolve","Handled",120_000));
        var saved=store().get(r.id());assertTrue(saved.closed());assertEquals(2,saved.history().size());assertEquals(staff,saved.history().getFirst().actor());
        assertEquals(ReportStore.Result.SAVED,s.update(r.id(),staff,"reopen","New information",130_000));assertFalse(store().get(r.id()).closed());
    }
    @Test void cooldownAndDuplicateLimitsPersist(){
        ReportStore s=store();s.submit(reporter,"Reporter",target,"Target","reason",100_000);
        assertEquals(ReportStore.Result.LIMITED,store().submit(reporter,"Reporter",UUID.randomUUID(),"Other","reason",110_000));
        assertEquals(ReportStore.Result.LIMITED,store().submit(reporter,"Reporter",target,"Target","reason",170_000));
        assertEquals(ReportStore.Result.SAVED,store().submit(reporter,"Reporter",UUID.randomUUID(),"Other","reason",170_000));
    }
    @Test void invalidAndSelfReportsDoNotPersist(){
        ReportStore s=store();assertEquals(ReportStore.Result.INVALID,s.submit(reporter,"Reporter",reporter,"Reporter","reason",1));
        assertEquals(ReportStore.Result.INVALID,s.submit(reporter,"Reporter",target,"Target","x".repeat(501),1));
        assertEquals(ReportStore.Result.INVALID,s.submit(reporter,"Reporter",target,"Target","bad\nline",1));assertTrue(s.list(1).isEmpty());
    }
    @Test void expirationStopsReopenAndOldCasesArePrunedOnlyDuringNewSubmission(){
        ReportStore s=store();s.submit(reporter,"Reporter",target,"Target","reason",100_000);var r=s.list(100_000).getFirst();
        long later=100_000+ReportStore.RETENTION;assertFalse(r.open(later));
        assertEquals(ReportStore.Result.INVALID,s.update(r.id(),staff,"reopen","Too late",later));
        s.submit(reporter,"Reporter",target,"Target","new incident",later+ReportStore.RETENTION+1);
        assertNull(s.get(r.id()));assertEquals(1,store().list(later).size());
    }
    @Test void failedWriteCannotCreateOrChangeCase() throws Exception {
        ReportStore s=store();s.submit(reporter,"Reporter",target,"Target","reason",100_000);var r=s.list(100_000).getFirst();
        Files.delete(dir.resolve("reports.yml"));Files.createDirectory(dir.resolve("reports.yml"));Files.writeString(dir.resolve("reports.yml/keep"),"blocked");
        assertEquals(ReportStore.Result.UNAVAILABLE,s.update(r.id(),staff,"resolve","done",110_000));assertFalse(s.get(r.id()).closed());assertTrue(s.get(r.id()).history().isEmpty());
    }
    @Test void corruptFileIsRetainedAndNeverOverwritten() throws Exception {
        String bad="reports: [broken\n";Files.writeString(dir.resolve("reports.yml"),bad);
        ReportStore s=store();assertEquals(ReportStore.Result.UNAVAILABLE,s.submit(reporter,"Reporter",target,"Target","reason",100_000));
        assertEquals(bad,Files.readString(dir.resolve("reports.yml")));
    }
    @Test void historyCannotGrowWithoutLimit(){
        ReportStore s=store();s.submit(reporter,"Reporter",target,"Target","reason",100_000);var r=s.list(100_000).getFirst();
        for(int i=0;i<100;i++)assertEquals(ReportStore.Result.SAVED,s.update(r.id(),staff,"note","Note "+i,110_000+i));
        assertEquals(ReportStore.Result.LIMITED,s.update(r.id(),staff,"note","Overflow",120_000));assertEquals(100,store().get(r.id()).history().size());
    }
    @Test void requestsNeverClaimCompletedPunishment(){
        ReportStore s=store();s.submit(reporter,"Reporter",target,"Target","reason",100_000);var r=s.list(100_000).getFirst();
        assertEquals(ReportStore.Result.SAVED,s.update(r.id(),staff,"mute-request","30m",110_000));
        assertEquals("mute-request",store().get(r.id()).history().getFirst().kind());
        assertEquals(ReportStore.Result.INVALID,s.update(r.id(),staff,"mute-completed","30m",110_000));
    }
    @Test void durableMuteAndRemovalSurviveRestartAndPreserveStateOnDiskFailure() throws Exception {
        Path file=dir.resolve("mutes.yml");MuteStore s=new MuteStore(file,log,Runnable::run, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);s.load();
        var mute=new MuteStore.Mute(target,"Target",System.currentTimeMillis(),System.currentTimeMillis()+60_000,"reason","Staff");
        assertTrue(s.changeDurably(target,mute));MuteStore loaded=new MuteStore(file,log,Runnable::run, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);loaded.load();assertEquals(mute,loaded.active(target,System.currentTimeMillis()));
        Files.delete(file);Files.createDirectory(file);Files.writeString(file.resolve("keep"),"block");
        assertFalse(s.changeDurably(target,null));assertEquals(mute,s.active(target,System.currentTimeMillis()));
        Files.delete(file.resolve("keep"));Files.delete(file);assertTrue(s.changeDurably(target,null));
        loaded=new MuteStore(file,log,Runnable::run, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);loaded.load();assertNull(loaded.active(target,System.currentTimeMillis()));
    }
    @Test void unicodeTruncationKeepsSurrogatePairs(){assertEquals("🙂🙂…",Reports.shortText("🙂🙂🙂",2));}
}
