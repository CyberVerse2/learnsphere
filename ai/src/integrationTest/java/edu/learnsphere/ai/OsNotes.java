package edu.learnsphere.ai;

import edu.learnsphere.core.spi.Passage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A few pages of undergraduate operating-systems notes, split into passages the way the chunker would,
 * with questions the notes answer (and the passages that answer them) and questions they don't.
 */
public final class OsNotes {

    public static final String TITLE = "Operating Systems lecture notes";

    private static final String[][] PASSAGES = {
            {"1", "Processes and threads",
                    "A process is a program in execution. Each process has its own address space, which holds its code, "
                            + "its data, a heap for dynamic allocation and a stack for function calls, together with "
                            + "operating-system resources such as open files. The kernel keeps the information it needs about "
                            + "each process in a process control block (PCB): the process state, the saved CPU registers, "
                            + "scheduling information and memory-management information. A thread is a single sequence of "
                            + "execution inside a process. All threads of a process share the process's address space and open "
                            + "files, but each thread has its own program counter, registers and stack. Because threads share "
                            + "memory, creating a thread and switching between threads of the same process is much cheaper than "
                            + "doing the same for processes, but the threads must coordinate when they use shared data."},
            {"1", "Process states and context switches",
                    "During its life a process moves between states. A new process is admitted to the ready state, where "
                            + "it waits for the CPU. The scheduler dispatches a ready process, which then runs. A running process "
                            + "that requests I/O or waits for an event becomes blocked (also called waiting) until the event "
                            + "completes, and then returns to the ready state. A running process can also be preempted by a timer "
                            + "interrupt and put back in the ready queue. Switching the CPU from one process to another is called a "
                            + "context switch: the kernel saves the state of the old process in its PCB and loads the saved state "
                            + "of the new process. A context switch is pure overhead, because no useful work is done while it "
                            + "happens, and it also makes the CPU caches and the TLB less effective."},
            {"2", "CPU scheduling",
                    "The CPU scheduler decides which ready process runs next. First-come, first-served (FCFS) runs processes "
                            + "in arrival order; it is simple but short jobs can wait a long time behind a long one, which is "
                            + "called the convoy effect. Shortest-job-first (SJF) picks the process with the shortest next CPU "
                            + "burst and gives the minimum average waiting time, but it needs to predict burst lengths and a "
                            + "steady stream of short jobs can starve long ones. Round robin (RR) gives each ready process a fixed "
                            + "time slice, called the quantum, in turn; a process that is still running when its quantum expires "
                            + "is preempted and goes to the back of the ready queue. If the quantum is very large, round robin "
                            + "behaves like FCFS; if it is very small, the system spends most of its time on context switches."},
            {"3", "Race conditions and locks",
                    "A race condition occurs when two or more threads access shared data concurrently, at least one of them "
                            + "writes it, and the result depends on the order in which the accesses happen. The part of a program "
                            + "that accesses shared data is called a critical section, and correct programs ensure mutual "
                            + "exclusion: at most one thread is inside the critical section at a time. A mutex (lock) provides "
                            + "mutual exclusion: a thread acquires the lock before entering the critical section and releases it "
                            + "when it leaves, and only the thread holding the lock may release it. A semaphore is an integer "
                            + "counter with two atomic operations, wait (P), which decrements it and blocks when it would become "
                            + "negative, and signal (V), which increments it and wakes a waiting thread. A counting semaphore can "
                            + "let up to N threads use a pool of N resources at once."},
            {"3", "Deadlock",
                    "A deadlock is a situation in which a set of processes are all blocked, each waiting for a resource held "
                            + "by another process in the set, so none of them can ever continue. Deadlock can only occur when four "
                            + "conditions hold at the same time: mutual exclusion (a resource can be used by only one process at a "
                            + "time), hold and wait (a process holds resources while waiting for others), no preemption (resources "
                            + "cannot be taken away from a process) and circular wait (there is a cycle of processes, each waiting "
                            + "for a resource held by the next). Deadlock prevention breaks one of these conditions; a common "
                            + "technique is to number all resources and require every process to request them in increasing order, "
                            + "which makes a circular wait impossible."},
            {"4", "Paging",
                    "Paging divides physical memory into fixed-size blocks called frames and each process's logical address "
                            + "space into blocks of the same size called pages, typically 4 KB. Any page can be placed in any free "
                            + "frame, so paging avoids external fragmentation. A logical address is split into a page number and an "
                            + "offset; the page number is used as an index into the process's page table, which gives the frame "
                            + "number, and the offset is added to the start of that frame. Paging can still waste memory inside "
                            + "the last page of a process, because a process rarely needs an exact multiple of the page size; "
                            + "this waste is called internal fragmentation."},
            {"4", "The translation lookaside buffer",
                    "Because page tables are kept in main memory, every memory access would need an extra memory access just "
                            + "to read the page table. To avoid this, the memory-management unit has a translation lookaside "
                            + "buffer (TLB): a small, fast hardware cache of recently used page-table entries. On each memory "
                            + "access the page number is looked up in the TLB first. On a TLB hit the frame number is available "
                            + "immediately; on a TLB miss the page table in memory is read and the entry is added to the TLB. "
                            + "Because programs tend to access the same pages repeatedly (locality of reference), hit rates above "
                            + "99% are common, so the average cost of translation stays close to that of a single memory access."},
            {"5", "Virtual memory and page faults",
                    "Virtual memory lets a process run even when only part of it is in physical memory; the rest stays on "
                            + "disk. Each page-table entry has a valid bit that says whether the page is in memory. When a process "
                            + "accesses a page that is not in memory, the hardware raises a page fault. The operating system then "
                            + "finds a free frame (or chooses a victim page to evict), reads the missing page from disk into that "
                            + "frame, updates the page table, and restarts the instruction that caused the fault. Loading pages "
                            + "only when they are first accessed is called demand paging. If the processes in the system need "
                            + "more frames than are available, they page fault constantly and spend more time swapping pages than "
                            + "executing; this is called thrashing."},
            {"5", "Page replacement",
                    "When memory is full, a page fault requires the operating system to choose a page to evict. The optimal "
                            + "policy evicts the page that will not be used for the longest time; it cannot be implemented because "
                            + "it needs to know the future, but it is useful as a benchmark. FIFO evicts the page that has been in "
                            + "memory the longest; it is simple, but it can suffer from Belady's anomaly, where giving a process "
                            + "more frames causes more page faults. Least recently used (LRU) evicts the page that has not been "
                            + "used for the longest time, on the assumption that the recent past predicts the near future. Exact "
                            + "LRU is expensive, so real systems approximate it with a reference bit, for example the clock "
                            + "(second-chance) algorithm."},
    };

    /** A question the notes answer, with the passages that answer it. */
    public record Answerable(String question, Set<Integer> passages) {
    }

    public static final List<Answerable> ANSWERABLE = List.of(
            new Answerable("What is the difference between a process and a thread?", Set.of(1)),
            new Answerable("What happens during a context switch, and why is it considered overhead?", Set.of(2)),
            new Answerable("How does round-robin scheduling work, and what happens if the time quantum is too large?",
                    Set.of(3)),
            new Answerable("Why can shortest-job-first scheduling starve long processes?", Set.of(3)),
            new Answerable("What is a race condition?", Set.of(4)),
            new Answerable("How is a semaphore different from a mutex?", Set.of(4)),
            new Answerable("What four conditions must hold for a deadlock to happen?", Set.of(5)),
            new Answerable("what causes internal fragmentation with paging", Set.of(6)),
            new Answerable("Why does the TLB make address translation faster?", Set.of(7)),
            new Answerable("What does the operating system do when a page fault happens?", Set.of(8)),
            new Answerable("What is thrashing?", Set.of(8)),
            new Answerable("Explain how LRU page replacement chooses which page to evict.", Set.of(9)));

    public static final List<String> UNANSWERABLE = List.of(
            "How does the banker's algorithm avoid deadlock?",
            "What is the difference between a monolithic kernel and a microkernel?",
            "How does TCP congestion control work?",
            "Who wrote the first version of the Linux kernel?",
            "What is the default block size of the ext4 file system?",
            "How does multilevel feedback queue scheduling adjust priorities?",
            "What is the time complexity of quicksort?");

    private OsNotes() {
    }

    /** All passages, numbered from 1, with the document title, page and section. */
    public static List<Passage> passages() {
        List<Passage> passages = new ArrayList<>();
        for (int i = 0; i < PASSAGES.length; i++) {
            int page = Integer.parseInt(PASSAGES[i][0]);
            passages.add(new Passage(i + 1, "chunk-" + (i + 1), "doc-os", TITLE, page, page, PASSAGES[i][1],
                    PASSAGES[i][2], 0));
        }
        return passages;
    }

    /** {@code chosen} renumbered 1..n in the given order (as the ask pipeline numbers its top passages). */
    public static List<Passage> renumber(List<Passage> chosen) {
        List<Passage> result = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            Passage p = chosen.get(i);
            result.add(new Passage(i + 1, p.chunkId(), p.documentId(), p.documentTitle(), p.pageStart(), p.pageEnd(),
                    p.sectionTitle(), p.text(), p.relevance()));
        }
        return result;
    }

    /** The original passage number of a passage (from its chunk id). */
    public static int originalNumber(Passage passage) {
        return Integer.parseInt(passage.chunkId().substring("chunk-".length()));
    }
}
