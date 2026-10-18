package es.ulpgc.bigdata.a2.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.PowerSource;
import oshi.software.os.OperatingSystem;

/**
 * Collects a description of the machine and the JVM with OSHI (works on Windows, Linux and macOS) and
 * writes it to {@code env/<hostname>_<timestamp>.json}. The file name without extension is the
 * environment id ({@code env_id}) that every result file refers to.
 *
 * <p>Run it with the same JVM flags as the benchmarks (in particular -Xmx), so that the recorded JVM
 * flags and effective maximum heap are the ones the benchmarks use.
 *
 * <p>Usage: {@code java -cp target/benchmarks.jar es.ulpgc.bigdata.a2.util.EnvironmentInfo [--out-dir env]}.
 * Prints the env_id on the last line of standard output.
 */
public final class EnvironmentInfo {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private EnvironmentInfo() {
    }

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of("env");
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--out-dir")) {
                outDir = Path.of(args[++i]);
            } else {
                throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        String host = hostname();
        String envId = host + "_" + now.format(STAMP);
        Map<String, Object> info = collect(host, envId, now);
        Files.createDirectories(outDir);
        Path out = outDir.resolve(envId + ".json");
        Files.writeString(out, Json.write(info), StandardCharsets.UTF_8);
        System.out.println("Wrote " + out);
        System.out.println(envId);
    }

    /** Host name made safe for file names (letters, digits, '-' only). */
    public static String hostname() {
        String h = System.getenv("COMPUTERNAME");
        if (h == null || h.isBlank()) {
            try {
                h = InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                h = "unknown-host";
            }
        }
        return h.replaceAll("[^A-Za-z0-9-]", "-");
    }

    static Map<String, Object> collect(String host, String envId, OffsetDateTime now) {
        SystemInfo si = new SystemInfo();
        HardwareAbstractionLayer hw = si.getHardware();
        OperatingSystem os = si.getOperatingSystem();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("env_id", envId);
        root.put("hostname", host);
        root.put("collected_at", now.toString());

        CentralProcessor cpu = hw.getProcessor();
        CentralProcessor.ProcessorIdentifier id = cpu.getProcessorIdentifier();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("model", id.getName().trim());
        c.put("vendor", id.getVendor());
        c.put("microarchitecture", id.getMicroarchitecture());
        c.put("identifier", id.getIdentifier());
        c.put("physical_packages", cpu.getPhysicalPackageCount());
        c.put("physical_cores", cpu.getPhysicalProcessorCount());
        c.put("logical_processors", cpu.getLogicalProcessorCount());
        c.put("base_frequency_hz", id.getVendorFreq());
        c.put("max_frequency_hz", cpu.getMaxFreq());
        c.put("caches", caches(cpu));
        root.put("cpu", c);

        GlobalMemory mem = hw.getMemory();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_bytes", mem.getTotal());
        m.put("available_bytes_at_collection", mem.getAvailable());
        m.put("total_gib", round2(mem.getTotal() / (1024.0 * 1024 * 1024)));
        root.put("memory", m);

        Map<String, Object> o = new LinkedHashMap<>();
        o.put("family", os.getFamily());
        o.put("manufacturer", os.getManufacturer());
        o.put("version", os.getVersionInfo().getVersion());
        o.put("build", os.getVersionInfo().getBuildNumber());
        o.put("bitness", os.getBitness());
        o.put("os.name", System.getProperty("os.name"));
        o.put("os.version", System.getProperty("os.version"));
        o.put("os.arch", System.getProperty("os.arch"));
        root.put("os", o);

        root.put("power_sources", power(hw));
        root.put("jvm", jvm());
        root.put("libraries", libraryVersions());
        return root;
    }

    private static List<Object> caches(CentralProcessor cpu) {
        List<Object> list = new ArrayList<>();
        try {
            for (CentralProcessor.ProcessorCache pc : cpu.getProcessorCaches()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("level", (int) pc.getLevel());
                e.put("type", pc.getType().name().toLowerCase(Locale.ROOT));
                e.put("size_bytes", pc.getCacheSize());
                e.put("line_size_bytes", (int) pc.getLineSize());
                e.put("associativity", (int) pc.getAssociativity());
                list.add(e);
            }
        } catch (RuntimeException e) {
            // Cache information is optional; some platforms do not expose it.
        }
        return list;
    }

    private static List<Object> power(HardwareAbstractionLayer hw) {
        List<Object> list = new ArrayList<>();
        try {
            for (PowerSource ps : hw.getPowerSources()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", ps.getName());
                e.put("power_on_line", ps.isPowerOnLine());
                e.put("charging", ps.isCharging());
                e.put("remaining_capacity_percent", round2(ps.getRemainingCapacityPercent() * 100));
                list.add(e);
            }
        } catch (RuntimeException e) {
            // Desktops usually report no power source.
        }
        return list;
    }

    private static Map<String, Object> jvm() {
        Map<String, Object> j = new LinkedHashMap<>();
        for (String p : new String[] {"java.version", "java.vendor", "java.vm.name", "java.vm.version",
                "java.runtime.version", "java.home"}) {
            j.put(p, System.getProperty(p));
        }
        j.put("input_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        long maxHeap = Runtime.getRuntime().maxMemory();
        j.put("effective_max_heap_bytes", maxHeap);
        j.put("effective_max_heap_gib", round2(maxHeap / (1024.0 * 1024 * 1024)));
        j.put("available_processors", Runtime.getRuntime().availableProcessors());
        List<String> gcs = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcs.add(gc.getName());
        }
        j.put("garbage_collectors", gcs);
        return j;
    }

    /** Library versions, filled in by Maven resource filtering from the pom. */
    public static Map<String, Object> libraryVersions() {
        Map<String, Object> v = new LinkedHashMap<>();
        Properties p = new Properties();
        try (InputStream in = EnvironmentInfo.class.getResourceAsStream("/versions.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // leave empty
        }
        for (String k : new String[] {"jmh.version", "ejml.version", "oshi.version"}) {
            v.put(k.replace(".version", ""), p.getProperty(k, "unknown"));
        }
        return v;
    }

    private static double round2(double x) {
        return Math.round(x * 100.0) / 100.0;
    }
}
