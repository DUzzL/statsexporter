package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demotes the calling thread to the lowest CPU scheduling priority the OS
 * offers, so the mod's background work never competes seriously with the
 * server threads — without requiring server owners to add JVM flags.
 *
 * Java's own {@link Thread#setPriority} is honored natively on Windows but is
 * a no-op on Linux unless the JVM is started with -XX:ThreadPriorityPolicy=1,
 * which server owners rarely configure. So on Linux each mod thread demotes
 * itself directly through libc (via the Java FFM API — no JNI, no deps):
 *
 * <ul>
 *   <li>{@code setpriority(PRIO_PROCESS, 0, 19)} — weakest nice level; and</li>
 *   <li>{@code sched_setscheduler(0, SCHED_IDLE, &#123;0&#125;)} — the kernel's
 *       idle scheduling class, which only runs on CPU time no other thread
 *       wants. This is the strongest "never compete" guarantee Linux has.</li>
 * </ul>
 *
 * Both calls only ever LOWER the calling thread's own priority, which Linux
 * always permits — no root, no capabilities, works inside containers. Both
 * are per-thread on Linux (the kernel treats the "process" argument as a
 * task), so nothing outside the mod's own threads is affected. Threads
 * created by a demoted thread inherit its scheduling, which is how the JDK
 * HttpServer's internal dispatcher/timer threads are covered.
 *
 * If anything is unavailable (non-Linux OS, denied syscall, a future JDK
 * requiring --enable-native-access), this silently degrades to plain Java
 * thread priorities and logs once what it is using.
 */
final class ThreadDemotion {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatsExporterMod.MOD_ID);

    // From <sched.h> / <sys/resource.h> on Linux.
    private static final int SCHED_IDLE = 5;
    private static final int PRIO_PROCESS = 0;
    private static final int NICE_LOWEST = 19;

    private static final MethodHandle SCHED_SETSCHEDULER;
    private static final MethodHandle SETPRIORITY;
    private static final MemorySegment SCHED_PARAM_ZERO;

    private static final AtomicBoolean LOGGED_ONCE = new AtomicBoolean();

    static {
        MethodHandle schedSetscheduler = null;
        MethodHandle setpriority = null;
        MemorySegment schedParamZero = null;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            try {
                Linker linker = Linker.nativeLinker();
                schedSetscheduler = linker.downcallHandle(
                        linker.defaultLookup().find("sched_setscheduler").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                setpriority = linker.downcallHandle(
                        linker.defaultLookup().find("setpriority").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                // struct sched_param { int sched_priority; } — must be 0 for SCHED_IDLE.
                // Arena allocations are zero-initialized.
                schedParamZero = Arena.global().allocate(ValueLayout.JAVA_INT);
            } catch (Throwable t) {
                LOGGER.info("OS-level thread demotion unavailable ({}); using Java thread priorities only",
                        t.toString());
                schedSetscheduler = null;
                setpriority = null;
                schedParamZero = null;
            }
        }
        SCHED_SETSCHEDULER = schedSetscheduler;
        SETPRIORITY = setpriority;
        SCHED_PARAM_ZERO = schedParamZero;
    }

    private ThreadDemotion() {
    }

    /**
     * Demote the calling thread as far as the OS allows. Call as the first
     * statement on a newly started mod thread. Never throws.
     */
    static void demoteCurrentThread() {
        if (SCHED_SETSCHEDULER == null) {
            return; // not Linux, or native access unavailable — Thread.setPriority is all we have
        }
        boolean nice = false;
        boolean idle = false;
        try {
            nice = (int) SETPRIORITY.invokeExact(PRIO_PROCESS, 0, NICE_LOWEST) == 0;
            idle = (int) SCHED_SETSCHEDULER.invokeExact(0, SCHED_IDLE, SCHED_PARAM_ZERO) == 0;
        } catch (Throwable t) {
            // fall through — reported below on first call only
        }
        if (LOGGED_ONCE.compareAndSet(false, true)) {
            if (idle) {
                LOGGER.info("Stats Exporter threads demoted to SCHED_IDLE — they only run on otherwise-idle CPU");
            } else if (nice) {
                LOGGER.info("Stats Exporter threads demoted to nice {} (SCHED_IDLE not permitted here)", NICE_LOWEST);
            } else {
                LOGGER.info("OS-level thread demotion not permitted; using Java thread priorities only");
            }
        }
    }
}
