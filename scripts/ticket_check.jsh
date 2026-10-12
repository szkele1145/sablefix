import dev.sfx.sablefix.scan.*;
import java.nio.file.*;
var info = TicketScan.read(Path.of("D:/sfx/proj/run/world2/data/sable_sub_level_force_load_tickets.dat"));
System.out.println("RESULT count=" + info.count() + " uuids=" + info.uuids().size() + " size=" + info.fileSize());
