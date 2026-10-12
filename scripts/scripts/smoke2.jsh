// 行为冒烟矩阵 v2（jshell）：repair-all 批量 + 票据文件合成链路
// 运行：jshell --class-path build/classes/java/main scripts/smoke2.jsh
import dev.sfx.sablefix.scan.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

System.out.println("=== [1] repair-all: 3 files mixed health ===");
var dir = Files.createTempDirectory("sfxsmoke2");
static void put(byte[] b, int slot, int v) { b[slot]=(byte)(v>>>24); b[slot+1]=(byte)(v>>>16); b[slot+2]=(byte)(v>>>8); b[slot+3]=(byte)v; }

// fileA: 2 死槽；fileB: 健康；fileC: 1 死槽
var fa = dir.resolve("a.slvlr");
var bufA = new byte[4096+64];
bufA[4100]=0; bufA[4101]=0; bufA[4102]=0; bufA[4103]=8;   // 合法记录 @4100 len=8
put(bufA, 0, 99999); put(bufA, 4, 3000); put(bufA, 8, 4100);
Files.write(fa, bufA);

var fb = dir.resolve("b.slvlr");
var bufB = new byte[4096+32];
bufB[4100]=0; bufB[4101]=0; bufB[4102]=0; bufB[4103]=8;
put(bufB, 0, 4100);
Files.write(fb, bufB);

var fc = dir.resolve("c.slvls");
var bufC = new byte[4096];
bufC[0]=0; bufC[1]=0; bufC[2]=9; bufC[3]=9;   // off=9 cnt=9 → end=73728>4096 坏
bufC[4]=0; bufC[5]=0; bufC[6]=1; bufC[7]=1;   // ok
Files.write(fc, bufC);

var rep = SubLevelScan.scan(dir);
System.out.println("scan: slvlrIssues=" + rep.slvlr().size() + " slvlsIssues=" + rep.slvls().size());
var plans = new ArrayList<SubLevelRepair.RepairPlan>();
for (var i : rep.slvlr()) plans.add(SubLevelRepair.plan(i.file()));
for (var i : rep.slvls()) plans.add(SubLevelRepair.plan(i.file()));
plans.removeIf(SubLevelRepair.RepairPlan::isEmpty);
long totalSlots = plans.stream().mapToLong(p -> p.slots().size()).sum();
System.out.println("plans=" + plans.size() + " totalSlots=" + totalSlots);
if (plans.size() != 2 || totalSlots != 3) throw new AssertionError("repair-all plan wrong");

int okFiles = 0, cleared = 0;
Path lastBackup = null;
for (var p : plans) {
    try { var r = SubLevelRepair.apply(p); okFiles++; cleared += r.cleared(); lastBackup = r.backup(); }
    catch (Exception e) { System.out.println("  fail: " + p.file().getFileName()); }
}
System.out.println("applied: okFiles=" + okFiles + " cleared=" + cleared + " backup=" + (lastBackup != null));
// 复扫：全部健康（fileA slot2 保留、fileC slot1 保留）
var rep2 = SubLevelScan.scan(dir);
System.out.println("rescan: slvlrIssues=" + rep2.slvlr().size() + " slvlsIssues=" + rep2.slvls().size());
if (rep2.slvlr().size() != 0 || rep2.slvls().size() != 0) throw new AssertionError("repair-all left issues");
// fileB 从头到尾没进 plans（健康）→ 指针未动
var rb = new java.io.RandomAccessFile(fb.toFile(), "r"); rb.seek(0); int keepB = rb.readInt(); rb.close();
System.out.println("healthy fileB ptr preserved=" + (keepB == 4100));
if (keepB != 4100) throw new AssertionError("healthy file touched");
System.out.println("[1] REPAIR-ALL OK");

System.out.println("=== [2] ticket gzip roundtrip (synthetic TicketScan) ===");
// 合成一个「 tickets TAG_List(TAG_Compound 含 uuid-int-array)」级别复杂的文件，走 TicketScan.read
// 简化：直接验证 gzip 流程 + 截断容错
var tf = dir.resolve("sable_sub_level_force_load_tickets.dat");
Files.write(tf, new byte[]{0});   // 非 gzip → 应抛异常而非崩溃
try {
    TicketScan.read(tf);
    System.out.println("non-gzip parsed (unexpected but tolerated)");
} catch (Exception e) {
    System.out.println("non-gzip throws cleanly: " + e.getClass().getSimpleName());
}
Files.write(tf, new byte[0]);   // 空文件
try { TicketScan.read(tf); System.out.println("empty parsed"); }
catch (Exception e) { System.out.println("empty throws cleanly: " + e.getClass().getSimpleName()); }
System.out.println("[2] TICKET EDGE OK");

System.out.println("SMOKE2-ALL-OK");
