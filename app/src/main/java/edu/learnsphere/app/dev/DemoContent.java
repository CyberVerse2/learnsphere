package edu.learnsphere.app.dev;

import edu.learnsphere.app.dev.DemoStore.Passage;
import edu.learnsphere.core.model.Attempt;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.ChatRole;
import edu.learnsphere.core.model.ChatThread;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Confidence;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentError;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.FlagSource;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.MessageStatus;
import edu.learnsphere.core.model.PageSource;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.model.Visibility;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The demo profile's content: three courses written for the demo, with realistic modules, PDFs, passages,
 * concepts and questions of every type, plus a month of study history and a conversation with cited
 * answers.
 *
 * <ul>
 *   <li><b>Operating Systems Principles</b>: studying, about halfway through, reviews due today.</li>
 *   <li><b>Research Methods in Computing</b>: studying, just started.</li>
 *   <li><b>Organic Chemistry Basics</b>: being written here; PDFs in every import state (one with scanned
 *       pages to check), draft and approved questions of every type.</li>
 * </ul>
 */
final class DemoContent {

    static final String OPERATING_SYSTEMS = "5f1c2a9e-7b34-4d0e-9a61-0c2f8e4b7d11";
    static final String RESEARCH_METHODS = "a3d8e1f2-4c5b-4e6a-8f70-1b2c3d4e5f22";
    static final String ORGANIC_CHEMISTRY = "c7e9f0a1-2b3c-4d5e-8f9a-0b1c2d3e4f33";

    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long HOUR = Duration.ofHours(1).toMillis();

    private final DemoStore store;
    private final long now;
    private final Random random = new Random(20261027L);

    private DemoContent(DemoStore store) {
        this.store = store;
        this.now = store.now();
    }

    /** Fills {@code store} with the demo courses, history and conversation. */
    static void populate(DemoStore store) {
        DemoContent content = new DemoContent(store);
        content.operatingSystems();
        content.researchMethods();
        content.organicChemistry();
        content.history();
        content.conversation();
    }

    // ---------------------------------------------------------------------------------------------------
    // Operating Systems Principles
    // ---------------------------------------------------------------------------------------------------

    private void operatingSystems() {
        CourseBuilder c = course(OPERATING_SYSTEMS, "Operating Systems Principles",
                "How a computer shares itself between programs",
                "A first course on what an operating system does: running processes, deciding who gets the CPU, "
                        + "managing memory and keeping files safe on disk.",
                "Dr. Daniel Mensah", CourseOrigin.IMPORTED, CourseStatus.PUBLISHED, Visibility.SHAREABLE, 214, 3,
                2 * HOUR, 40);
        c.version(1, "First edition for the autumn term.", 52);
        c.version(2, "Added the file systems module and clearer scheduling examples.", 31);
        c.version(3, "Fixed two scheduling answers and added paging questions.", 9);

        ModuleBuilder m = c.module("Processes", "What a running program is, how the system tracks it, and threads.");
        String doc = m.document("Lecture 1 – Processes and Threads", "os-lecture-1-processes.pdf", 24, 2_418_000);
        Passage states = m.passage(doc, 4, "Process states",
                "A process moves between a small number of states. It is new while being created, ready while it "
                        + "waits for the CPU, running while its instructions execute, waiting while it needs an event "
                        + "such as finished input, and terminated once it exits. Only one process per core can be "
                        + "running at any moment.");
        Passage pcb = m.passage(doc, 7, "The process control block",
                "The operating system keeps a process control block for every process. It records the process "
                        + "state, the program counter, the CPU registers, scheduling information such as priority, "
                        + "memory-management information and the list of open files.");
        Passage switching = m.passage(doc, 11, "Context switching",
                "A context switch saves the state of the running process into its control block and loads the "
                        + "saved state of the next one. The time spent switching is pure overhead: no useful work "
                        + "happens while the registers and memory maps are swapped.");
        Passage threads = m.passage(doc, 17, "Threads",
                "A thread is a single sequence of execution inside a process. Threads of the same process share "
                        + "its code, data and open files but each has its own stack, registers and program counter, "
                        + "which makes creating and switching between threads cheaper than between processes.");
        String cStates = m.concept("Process states", "New, ready, running, waiting and terminated.");
        String cPcb = m.concept("Process control block", "What the system records about each process.");
        String cSwitch = m.concept("Context switching", "Saving one process's state and loading another's.");
        String cThreads = m.concept("Threads vs processes", "What threads share and what each keeps for itself.");
        m.mcq(cStates, states, "A process is waiting for a disk read to finish. Which state is it in?", "Waiting",
                "A process that needs an event, such as finished input, is in the waiting state until it happens.",
                "Ready", "Running", "Terminated");
        m.trueFalse(cStates, states, "On a single-core computer, two processes can be in the running state at the same time.",
                false, "Only one process per core can be running at any moment; the others are ready or waiting.");
        m.flashcard(cPcb, pcb, "Process control block", "The record the operating system keeps for each process: state, "
                + "program counter, registers, scheduling and memory information, and open files.");
        m.mcq(cPcb, pcb, "Which of these is not stored in a process control block?", "The source code of the program",
                "The control block holds the state needed to pause and resume the process, not the program's source.",
                "The program counter", "The list of open files", "The scheduling priority");
        m.cloze(cSwitch, switching, "The time spent on a context switch is pure ____: no useful work happens during it.",
                "overhead", "While registers and memory maps are swapped, no process makes progress.");
        m.shortAnswer(cSwitch, switching, "What happens during a context switch?",
                "The state of the running process is saved into its control block and the saved state of the next "
                        + "process is loaded.",
                "The system saves the running process's state into its control block, then loads the next one's.");
        m.mcq(cThreads, threads, "What do threads of the same process share?", "Code, data and open files",
                "Threads share the process's code, data and open files; each keeps its own stack and registers.",
                "Stacks and registers", "Program counters", "Nothing; each thread is isolated");
        m.trueFalse(cThreads, threads, "Switching between threads of one process is usually cheaper than switching between processes.",
                true, "Threads share the process's memory, so less state has to change on a switch.");

        m = c.module("CPU Scheduling", "How the system decides which ready process runs next, and the trade-offs.");
        doc = m.document("Lecture 2 – CPU Scheduling", "os-lecture-2-scheduling.pdf", 31, 3_102_000);
        Passage criteria = m.passage(doc, 3, "Scheduling criteria",
                "Schedulers are compared by CPU utilisation, throughput, turnaround time, waiting time and "
                        + "response time. Interactive systems care most about response time; batch systems care most "
                        + "about throughput and turnaround.");
        Passage rr = m.passage(doc, 12, "Round robin",
                "Round robin gives each ready process a fixed time slice, called a quantum, in turn. A process "
                        + "that does not finish within its quantum goes to the back of the ready queue. With a very "
                        + "large quantum round robin behaves like first-come, first-served; with a very small one, "
                        + "context-switch overhead dominates.");
        Passage sjf = m.passage(doc, 15, "Shortest job first",
                "Shortest job first runs the process with the smallest next CPU burst. It gives the minimum "
                        + "average waiting time for a set of processes, but the length of the next burst can only be "
                        + "estimated, usually from an average of previous bursts.");
        Passage priority = m.passage(doc, 19, "Priority scheduling",
                "Priority scheduling runs the highest-priority ready process. Low-priority processes can wait "
                        + "indefinitely, a problem called starvation. Ageing solves it by gradually raising the "
                        + "priority of processes that have waited a long time.");
        String cCriteria = m.concept("Scheduling criteria", "Utilisation, throughput, turnaround, waiting and response time.");
        String cRr = m.concept("Round robin", "Fixed time slices in turn, and choosing the quantum.");
        String cSjf = m.concept("Shortest job first", "Optimal average waiting time, with estimated bursts.");
        String cPriority = m.concept("Priority and starvation", "Running the most important work first, and ageing.");
        m.mcq(cCriteria, criteria, "Which criterion matters most for an interactive desktop system?", "Response time",
                "People notice how quickly the system reacts to them, so interactive systems optimise response time.",
                "Throughput", "Turnaround time", "CPU utilisation");
        m.mcq(cRr, rr, "What happens to a process that does not finish within its quantum under round robin?",
                "It goes to the back of the ready queue",
                "Round robin pre-empts the process at the end of its slice and moves it to the back of the queue.",
                "It runs until it finishes", "It is terminated", "It moves to the waiting state");
        m.trueFalse(cRr, rr, "Making the round robin quantum very small always improves performance.", false,
                "With a tiny quantum the system spends most of its time switching between processes.");
        m.cloze(cRr, rr, "With a very large quantum, round robin behaves like ____ scheduling.",
                "first-come, first-served", "If no process is ever pre-empted, processes simply run in arrival order.");
        m.mcq(cSjf, sjf, "Why is shortest job first hard to implement exactly?",
                "The length of the next CPU burst isn't known in advance",
                "The scheduler can only estimate the next burst, usually from previous bursts.",
                "It needs more than one CPU", "It can't pre-empt processes", "It ignores waiting time");
        m.flashcard(cSjf, sjf, "Shortest job first", "Runs the process with the smallest next CPU burst; gives the "
                + "minimum average waiting time but needs the burst length estimated.");
        m.shortAnswer(cPriority, priority, "What is starvation, and how does ageing prevent it?",
                "Starvation is when a low-priority process waits indefinitely; ageing gradually raises the priority "
                        + "of processes that have waited a long time.",
                "Ageing makes a long-waiting process's priority climb until it eventually runs.");
        m.trueFalse(cPriority, priority, "Ageing lowers the priority of processes that have waited a long time.", false,
                "Ageing raises the priority of long-waiting processes so they eventually run.");

        m = c.module("Memory Management", "Giving each process its own address space with paging.");
        doc = m.document("Lecture 3 – Memory Management", "os-lecture-3-memory.pdf", 38, 4_870_000);
        Passage paging = m.passage(doc, 6, "Paging",
                "Paging divides physical memory into fixed-size frames and each process's logical memory into "
                        + "pages of the same size. Any page can go into any free frame, so paging avoids external "
                        + "fragmentation, although the last page of a process may be partly unused.");
        Passage tlb = m.passage(doc, 13, "Page tables and the TLB",
                "A page table maps each page number to a frame number. Because the table lives in memory, every "
                        + "access would need two memory reads; the translation look-aside buffer caches recent "
                        + "translations so most accesses need only one.");
        Passage replacement = m.passage(doc, 22, "Page replacement",
                "When a page fault occurs and no frame is free, a victim page must be replaced. FIFO replaces the "
                        + "oldest page; LRU replaces the page that has not been used for the longest time, which "
                        + "approximates the optimal policy well in practice.");
        Passage thrashing = m.passage(doc, 29, "Thrashing",
                "A process thrashes when it spends more time paging than executing, because it has fewer frames "
                        + "than its working set needs. Reducing the number of processes in memory gives the rest "
                        + "enough frames again.");
        String cPaging = m.concept("Paging", "Frames, pages and why paging avoids external fragmentation.");
        String cTlb = m.concept("Page tables and the TLB", "Address translation and caching it.");
        String cReplace = m.concept("Page replacement", "FIFO, LRU and choosing a victim page.");
        String cThrash = m.concept("Thrashing", "When paging crowds out useful work.");
        m.mcq(cPaging, paging, "Which kind of fragmentation does paging avoid?", "External fragmentation",
                "Any page fits any free frame, so free memory never splinters into unusable gaps between blocks.",
                "Internal fragmentation", "Both kinds", "Neither");
        m.mcq(cTlb, tlb, "What does the translation look-aside buffer store?", "Recent page-to-frame translations",
                "The TLB caches recent translations so most memory accesses skip the page table lookup.",
                "Recently used pages", "The whole page table", "Free frames");
        m.flashcard(cTlb, tlb, "Translation look-aside buffer", "A small cache of recent page-to-frame translations, so "
                + "most memory accesses need one memory read instead of two.");
        m.mcq(cReplace, replacement, "Which page does LRU replace?", "The page unused for the longest time",
                "Least recently used picks the page whose last use is furthest in the past.",
                "The oldest page in memory", "The page used least often", "A random page");
        m.cloze(cReplace, replacement, "FIFO page replacement removes the ____ page in memory.", "oldest",
                "First in, first out: the page loaded earliest leaves first.");
        m.shortAnswer(cThrash, thrashing, "What is thrashing, and how can the system recover from it?",
                "Thrashing is spending more time paging than executing; reducing the number of processes in "
                        + "memory gives the others enough frames.",
                "Fewer processes in memory means each has enough frames for its working set.");
        m.trueFalse(cThrash, thrashing, "A thrashing process has more frames than its working set needs.", false,
                "Thrashing happens when a process has fewer frames than its working set needs.");

        m = c.module("File Systems", "How files and directories are stored, found and kept consistent.");
        doc = m.document("Lecture 4 – File Systems", "os-lecture-4-file-systems.pdf", 29, 3_560_000);
        Passage inode = m.passage(doc, 5, "Inodes",
                "Unix-style file systems describe each file with an inode holding its size, owner, permissions, "
                        + "timestamps and pointers to its data blocks. The file's name is not in the inode; it lives "
                        + "in the directory entry that points to it.");
        Passage allocation = m.passage(doc, 14, "Allocation methods",
                "Contiguous allocation stores a file in consecutive blocks and makes reading fast, but files are "
                        + "hard to grow. Linked allocation chains blocks together and grows easily but reads randomly "
                        + "slowly. Indexed allocation keeps all block pointers in one index block.");
        Passage journal = m.passage(doc, 23, "Journaling",
                "A journaling file system writes intended changes to a log before applying them. After a crash, "
                        + "the system replays or discards the logged changes, so the file system is consistent "
                        + "without a full check of the disk.");
        String cInode = m.concept("Inodes", "What an inode records, and where file names live.");
        String cAlloc = m.concept("Allocation methods", "Contiguous, linked and indexed allocation.");
        String cJournal = m.concept("Journaling", "Logging changes to survive crashes.");
        m.trueFalse(cInode, inode, "A file's name is stored in its inode.", false,
                "The name is in the directory entry that points to the inode.");
        m.mcq(cAlloc, allocation, "Which allocation method makes files hardest to grow?", "Contiguous allocation",
                "A contiguous file needs free blocks right after it to grow, which often aren't available.",
                "Linked allocation", "Indexed allocation", "All are equally hard");
        m.flashcard(cJournal, journal, "Journaling", "Writing intended changes to a log first, so after a crash the "
                + "file system can replay or discard them instead of checking the whole disk.");
        m.mcq(cJournal, journal, "What does a journaling file system write first?", "A log of the intended changes",
                "Changes go to the journal before the file system itself, so a crash leaves a record to recover from.",
                "The changed data blocks", "A full backup", "The directory entries");
    }

    // ---------------------------------------------------------------------------------------------------
    // Research Methods in Computing
    // ---------------------------------------------------------------------------------------------------

    private void researchMethods() {
        CourseBuilder c = course(RESEARCH_METHODS, "Research Methods in Computing",
                "Asking good questions and answering them well",
                "How computing research is planned, carried out and reported: research questions, reviewing "
                        + "literature, designing experiments and working ethically with people and data.",
                "Dr. Hannah Iwu", CourseOrigin.IMPORTED, CourseStatus.PUBLISHED, Visibility.SHAREABLE, 152, 1,
                30 * HOUR, 12);
        c.version(1, "Course for the dissertation preparation module.", 12);

        ModuleBuilder m = c.module("Research Questions", "Turning a topic into a question you can answer.");
        String doc = m.document("Week 1 – From Topic to Question", "rm-week-1-questions.pdf", 18, 1_240_000);
        Passage rq = m.passage(doc, 5, "Good research questions",
                "A good research question is specific enough to answer within the project's time, grounded in "
                        + "existing work, and phrased so that evidence could support or contradict it. Broad topics "
                        + "such as 'AI in education' need narrowing to a population, an intervention and an outcome.");
        Passage hypothesis = m.passage(doc, 9, "Hypotheses",
                "A hypothesis is a testable prediction derived from the research question. The null hypothesis "
                        + "states that there is no effect; the study collects evidence to judge whether it can be "
                        + "rejected.");
        String cRq = m.concept("Research questions", "Specific, grounded and answerable questions.");
        String cHyp = m.concept("Hypotheses", "Testable predictions and the null hypothesis.");
        m.mcq(cRq, rq, "Which is the best-formed research question?",
                "Does automated feedback improve first-year students' scores on programming exercises?",
                "It names a population, an intervention and a measurable outcome.",
                "Is AI good for education?", "What is the future of programming?", "How do computers learn?");
        m.trueFalse(cHyp, hypothesis, "The null hypothesis predicts that there is an effect.", false,
                "The null hypothesis states that there is no effect.");
        m.flashcard(cHyp, hypothesis, "Null hypothesis", "The statement that there is no effect; a study gathers "
                + "evidence to judge whether it can be rejected.");

        m = c.module("Literature Review", "Finding, reading and organising what is already known.");
        doc = m.document("Week 2 – Reviewing the Literature", "rm-week-2-literature.pdf", 22, 1_610_000);
        Passage systematic = m.passage(doc, 7, "Systematic reviews",
                "A systematic review searches defined databases with a recorded search string, applies inclusion "
                        + "and exclusion criteria decided in advance, and reports how many sources were screened at "
                        + "each step so that others can repeat it.");
        String cSys = m.concept("Systematic reviews", "Repeatable searches with criteria set in advance.");
        m.mcq(cSys, systematic, "What makes a literature review systematic?", "Its search and criteria are recorded and repeatable",
                "Recording the search string and criteria lets other researchers repeat the review.",
                "It cites more than fifty papers", "It only uses journal articles", "It is written by several authors");
        m.cloze(cSys, systematic, "Inclusion and exclusion criteria are decided ____ the search.", "before",
                "Deciding criteria in advance stops results from shaping what gets included.");

        m = c.module("Experimental Design", "Controlling variables so results mean something.");
        doc = m.document("Week 3 – Designing Experiments", "rm-week-3-experiments.pdf", 26, 2_030_000);
        Passage variables = m.passage(doc, 4, "Variables",
                "The independent variable is what the researcher changes; the dependent variable is what is "
                        + "measured. Confounding variables also affect the outcome and must be controlled, for "
                        + "example through random assignment to conditions.");
        String cVar = m.concept("Variables", "Independent, dependent and confounding variables.");
        m.mcq(cVar, variables, "In a study comparing two code editors by task completion time, what is the dependent variable?",
                "Task completion time", "The dependent variable is what is measured.",
                "The code editor", "The participants", "The programming language");
        m.shortAnswer(cVar, variables, "Why do researchers randomly assign participants to conditions?",
                "Random assignment spreads confounding variables evenly across conditions so they don't explain the result.",
                "Randomising controls confounding variables.");

        m = c.module("Research Ethics", "Consent, privacy and doing no harm.");
        doc = m.document("Week 4 – Ethics in Practice", "rm-week-4-ethics.pdf", 15, 980_000);
        Passage consent = m.passage(doc, 3, "Informed consent",
                "Participants must understand what the study involves, what data is collected and how it is "
                        + "stored, and that they can withdraw at any time without giving a reason, before they agree "
                        + "to take part.");
        String cConsent = m.concept("Informed consent", "What participants must understand before agreeing.");
        m.trueFalse(cConsent, consent, "Participants who withdraw from a study must explain why.", false,
                "Participants can withdraw at any time without giving a reason.");
        m.flashcard(cConsent, consent, "Informed consent", "Agreement given after understanding the study, the data "
                + "collected and its storage, and the right to withdraw at any time.");
    }

    // ---------------------------------------------------------------------------------------------------
    // Organic Chemistry Basics (being created)
    // ---------------------------------------------------------------------------------------------------

    private void organicChemistry() {
        CourseBuilder c = course(ORGANIC_CHEMISTRY, "Organic Chemistry Basics",
                "Carbon, bonds and the reactions that build molecules",
                "An introduction to organic chemistry for first-year students: bonding and structure, functional "
                        + "groups, isomers and the main reaction types.",
                store.profileName, CourseOrigin.LOCAL, CourseStatus.DRAFT, Visibility.PRIVATE, 342, 0,
                5 * HOUR, 6);
        store.enrolled.remove(ORGANIC_CHEMISTRY);

        ModuleBuilder m = c.module("Bonding and Structure", "Covalent bonds, hybridisation and molecular shape.");
        String ready = m.document("Chapter 1 – Bonding and Structure", "ochem-ch1-bonding.pdf", 42, 6_220_000);
        Passage hybrid = m.passage(ready, 12, "Hybridisation",
                "In methane, carbon's one 2s and three 2p orbitals mix to form four equivalent sp3 hybrid orbitals. "
                        + "They point to the corners of a tetrahedron, giving bond angles of about 109.5 degrees.");
        Passage polarity = m.passage(ready, 18, "Bond polarity",
                "A covalent bond between atoms of different electronegativity is polar: the shared electrons sit "
                        + "closer to the more electronegative atom, which carries a partial negative charge.");
        String cHybrid = m.concept("Hybridisation", "sp3, sp2 and sp orbitals and the shapes they give.");
        String cPolar = m.concept("Bond polarity", "Electronegativity and partial charges.");
        m.status(ItemStatus.APPROVED).mcq(cHybrid, hybrid, "What is the H–C–H bond angle in methane?", "About 109.5°",
                "The four sp3 orbitals point to the corners of a tetrahedron.", "90°", "120°", "180°");
        m.status(ItemStatus.APPROVED).flashcard(cHybrid, hybrid, "sp3 hybridisation",
                "One s and three p orbitals mix into four equivalent orbitals arranged tetrahedrally.");
        m.status(ItemStatus.DRAFT).ai().trueFalse(cPolar, polarity,
                "In a polar bond the shared electrons sit closer to the less electronegative atom.", false,
                "They sit closer to the more electronegative atom, which becomes partly negative.");
        m.status(ItemStatus.DRAFT).ai().shortAnswer(cPolar, polarity, "Why is the O–H bond in water polar?",
                "Oxygen is more electronegative than hydrogen, so the shared electrons sit closer to oxygen, giving it a "
                        + "partial negative charge.", "Electronegativity difference makes the bond polar.");

        m = c.module("Functional Groups", "Recognising alcohols, carbonyls, acids and amines.");
        String scanned = m.document("Lab handout – Functional groups (scanned)", "ochem-functional-groups-scan.pdf", 12, 9_400_000);
        c.needsReview(scanned);
        String extracting = m.document("Chapter 2 – Functional Groups", "ochem-ch2-functional-groups.pdf", 120, 14_900_000);
        c.status(extracting, DocumentStatus.EXTRACTING, 14 / 120.0);
        Passage groups = m.passage(scanned, 2, "Common functional groups",
                "An alcohol contains an –OH group bonded to a saturated carbon. A carbonyl group is a carbon double "
                        + "bonded to oxygen; aldehydes have it at the end of a chain and ketones in the middle. "
                        + "Carboxylic acids combine a carbonyl and an –OH on the same carbon.");
        String cGroups = m.concept("Functional groups", "Alcohols, aldehydes, ketones, acids and amines.");
        m.status(ItemStatus.APPROVED).mcq(cGroups, groups, "Where is the carbonyl group in a ketone?",
                "In the middle of the carbon chain", "Aldehydes have the carbonyl at the end; ketones in the middle.",
                "At the end of the chain", "Bonded to an –OH group", "Outside the chain");
        m.status(ItemStatus.DRAFT).ai().cloze(cGroups, groups, "An alcohol contains an ____ group bonded to a saturated carbon.",
                "–OH", "The hydroxyl group defines an alcohol.");
        m.status(ItemStatus.DRAFT).ai().mcq(cGroups, groups, "Which group defines a carboxylic acid?",
                "A carbonyl and an –OH on the same carbon", "Carboxylic acids combine both on one carbon.",
                "Two –OH groups", "A carbon–nitrogen double bond", "An oxygen between two carbons");

        m = c.module("Isomerism", "Same formula, different molecules.");
        String reading = m.document("Isomers worksheet (scanned)", "ochem-isomers-scan.pdf", 30, 11_200_000);
        c.status(reading, DocumentStatus.READING, 1 / 3.0);
        String checking = m.document("Chapter 3 – Isomerism", "ochem-ch3-isomerism.pdf", 36, 5_100_000);
        c.status(checking, DocumentStatus.CHECKING, 0.1);
        String cIsomers = m.concept("Structural isomers", "Same molecular formula, different connectivity.");
        String cStereo = m.concept("Stereoisomers", "Same connectivity, different arrangement in space.");
        Passage isomers = m.passage(ready, 31, "Isomers",
                "Structural isomers share a molecular formula but connect their atoms differently, like butane and "
                        + "2-methylpropane. Stereoisomers have the same connections but a different arrangement in "
                        + "space.");
        m.status(ItemStatus.DRAFT).ai().flashcard(cIsomers, isomers, "Structural isomers",
                "Molecules with the same molecular formula whose atoms are connected differently.");
        m.status(ItemStatus.DRAFT).ai().trueFalse(cStereo, isomers, "Stereoisomers have different connectivity.", false,
                "Stereoisomers share connectivity and differ only in their arrangement in space.");
        m.status(ItemStatus.REJECTED).ai().mcq(cIsomers, isomers, "Which pair are structural isomers?",
                "Butane and 2-methylpropane", "Both are C4H10 with different connections.",
                "Ethane and ethene", "Methane and methanol", "Water and ice");

        m = c.module("Reactions", "Substitution, addition and elimination.");
        String indexing = m.document("Chapter 4 – Reaction Types", "ochem-ch4-reactions.pdf", 48, 7_300_000);
        c.status(indexing, DocumentStatus.INDEXING, 0.8);
        String queued = m.document("Problem set – Reactions", "ochem-problem-set-reactions.pdf", 8, 640_000);
        c.status(queued, DocumentStatus.QUEUED, 0);
        String failed = m.document("Past paper 2024 (locked)", "ochem-past-paper-2024.pdf", 0, 1_150_000);
        c.failed(failed, DocumentError.ENCRYPTED);
        String cAddition = m.concept("Addition reactions", "Adding across a double bond.");
        Passage addition = m.passage(ready, 38, "Addition",
                "In an addition reaction, atoms add across a carbon–carbon double bond, which becomes a single "
                        + "bond. Ethene reacts with bromine this way to give 1,2-dibromoethane.");
        m.status(ItemStatus.DRAFT).ai().mcq(cAddition, addition, "What happens to the C=C double bond in an addition reaction?",
                "It becomes a single bond", "Atoms add across the double bond, leaving a single bond.",
                "It becomes a triple bond", "It breaks completely", "It moves along the chain");
        m.status(ItemStatus.DRAFT).ai().shortAnswer(cAddition, addition, "What forms when ethene reacts with bromine?",
                "1,2-dibromoethane", "Bromine adds across the double bond.");
        String flagged = m.status(ItemStatus.APPROVED).cloze(cAddition, addition,
                "When ethene reacts with bromine, the product is 1,2-____ethane.", "dibromo",
                "Two bromine atoms add, one to each carbon.");
        Flag flag = new Flag(DemoStore.newId(), flagged, FlagReason.UNCLEAR,
                "The blank splits the word; ask for the whole name instead.", FlagSource.SELF, null, now - 3 * HOUR,
                now - 3 * HOUR);
        store.flags.put(flag.id(), flag);
        // The scanned handout's pages read by the study assistant, two still to check.
        Map<Integer, DocumentPage> scannedPages = new HashMap<>();
        scannedPages.put(1, page(scanned, 1, "Functional groups – lab handout\nName the group in each structure "
                + "below and give one example compound.", true));
        scannedPages.put(2, page(scanned, 2, groups.text(), true));
        scannedPages.put(3, page(scanned, 3, "Amines contain a nitrogen atom bonded to one, two or three carbon "
                + "groups. Primary amines have the form R–NH2.\nExample: methylamine, CH3NH2.", false));
        scannedPages.put(4, page(scanned, 4, "Esters form when a carboxylic acid reacts with an alcohol, releasing "
                + "water. They often smell fruity: ethyl ethanoate is used in nail varnish remover.", false));
        store.pages.put(scanned, scannedPages);
    }

    private DocumentPage page(String documentId, int number, String text, boolean reviewed) {
        return new DocumentPage(documentId, number, text, PageSource.AI_READ, reviewed, null, true, now - 2 * HOUR,
                now - 2 * HOUR);
    }

    // ---------------------------------------------------------------------------------------------------
    // Study history
    // ---------------------------------------------------------------------------------------------------

    /**
     * A month of reviews: Operating Systems most days (a run of recent days makes the streak), Research
     * Methods in the last week. Review states follow from the attempts; some fall due today.
     */
    private void history() {
        LocalDate today = store.today();
        List<LearningItem> os = store.studyItems(OPERATING_SYSTEMS);
        List<LearningItem> rm = store.studyItems(RESEARCH_METHODS);
        int osStarted = (int) Math.round(os.size() * 0.62);
        int rmStarted = 4;
        Set<LocalDate> restDays = Set.of(today.minusDays(29), today.minusDays(25), today.minusDays(22),
                today.minusDays(18), today.minusDays(17), today.minusDays(13), today.minusDays(9));
        for (int i = 0; i < osStarted; i++) {
            LearningItem item = os.get(i);
            LocalDate first = today.minusDays(28 - (i * 22L / Math.max(1, osStarted)));
            simulate(item, first, today, restDays, i);
        }
        for (int i = 0; i < rmStarted; i++) {
            simulate(rm.get(i), today.minusDays(5 - i), today, restDays, i + 3);
        }
        // Today's reviews so far.
        for (int i = 0; i < 6; i++) {
            LearningItem item = os.get(i * 2 % osStarted);
            attempt(item, store.startOfDay(today) + 8 * HOUR + i * 4 * 60_000L, random.nextDouble() < 0.83, "today");
        }
        for (LocalDate day = today.minusDays(30); !day.isAfter(today); day = day.plusDays(1)) {
            int reviews = store.reviewsOn(day);
            if (reviews > 0) {
                store.studyTime.put(day, reviews * (40_000L + random.nextInt(30_000)));
            }
        }
    }

    /** Reviews {@code item} from {@code first} on, at growing intervals, and leaves its review state. */
    private void simulate(LearningItem item, LocalDate first, LocalDate today, Set<LocalDate> restDays, int salt) {
        LocalDate day = first;
        double interval = 1;
        int reps = 0;
        int lapses = 0;
        long lastReview = 0;
        String session = "history-" + salt;
        while (!day.isAfter(today.minusDays(1))) {
            if (restDays.contains(day)) {
                day = day.plusDays(1);
                continue;
            }
            boolean correct = random.nextDouble() < 0.78 + Math.min(0.15, reps * 0.03);
            long at = store.startOfDay(day) + (18 + random.nextInt(4)) * HOUR + random.nextInt(50) * 60_000L;
            attempt(item, at, correct, session + day);
            lastReview = at;
            reps++;
            if (correct) {
                interval = reps == 1 ? 1 : Math.min(21, interval * 2.3);
            } else {
                lapses++;
                interval = 1;
            }
            day = day.plusDays(Math.max(1, Math.round(interval)));
        }
        // Spread the next reviews so some are due today and the forecast has shape.
        long due;
        int bucket = Math.floorMod(salt * 7 + item.prompt().length(), 10);
        if (bucket < 4) {
            due = store.startOfDay(today) - (bucket + 1) * HOUR;
        } else {
            due = store.startOfDay(today.plusDays(bucket - 2 + (salt % 3)));
        }
        double stability = Math.max(1, interval);
        store.reviews.put(item.id(), new ReviewState(item.id(), lapses > 0 && reps < 3 ? ReviewPhase.RELEARNING
                : ReviewPhase.REVIEW, stability, 5 + random.nextDouble() * 2, due, lastReview == 0 ? null : lastReview,
                reps, lapses, (int) Math.round(interval), 0, store.startOfDay(first), lastReview == 0 ? now : lastReview));
    }

    private void attempt(LearningItem item, long at, boolean correct, String session) {
        Rating rating = correct ? (random.nextDouble() < 0.3 ? Rating.EASY : Rating.GOOD) : Rating.AGAIN;
        Confidence confidence = Confidence.values()[random.nextInt(Confidence.values().length)];
        GradedBy gradedBy = switch (item.type()) {
            case FLASHCARD -> GradedBy.SELF;
            case SHORT_ANSWER -> GradedBy.AI;
            default -> GradedBy.RULE;
        };
        store.attempts.add(new Attempt(DemoStore.newId(), item.id(), session, correct ? item.answer() : "",
                correct, correct ? 1 : 0, rating, confidence, false, 8_000 + random.nextInt(40_000), gradedBy, null,
                false, at, at));
    }

    // ---------------------------------------------------------------------------------------------------
    // Conversation
    // ---------------------------------------------------------------------------------------------------

    private void conversation() {
        long start = now - 26 * HOUR;
        ChatThread thread = new ChatThread(DemoStore.newId(), OPERATING_SYSTEMS, "Scheduling and threads", start,
                start + 20 * 60_000L);
        store.threads.put(thread.id(), thread);
        List<ChatMessage> list = new ArrayList<>();
        Passage rr = findPassage("Round robin");
        Passage criteria = findPassage("Scheduling criteria");
        Passage threads = findPassage("Threads");
        Passage switching = findPassage("Context switching");
        list.add(user(thread, "Why does a tiny time quantum make round robin slower?", start));
        list.add(assistant(thread, "Each time the quantum runs out, the system performs a context switch, and with a "
                + "very small quantum it switches so often that the overhead dominates [1]. Switching saves one "
                + "process's state and loads another's, and no useful work happens while that is going on [2]. A "
                + "good quantum is large enough that most interactive bursts finish within one slice, which keeps "
                + "response time low [3].", start + 40_000, MessageStatus.COMPLETE,
                List.of(cite(1, rr), cite(2, switching), cite(3, criteria))));
        list.add(user(thread, "So what do threads of the same process share?", start + 6 * 60_000L));
        list.add(assistant(thread, "Threads of one process share its code, its data and its open files, but each "
                + "thread keeps its own stack, registers and program counter [1]. Because so much is shared, "
                + "switching between threads of the same process is cheaper than switching between processes [1][2].",
                start + 6 * 60_000L + 35_000, MessageStatus.COMPLETE, List.of(cite(1, threads), cite(2, switching))));
        list.add(user(thread, "Who wrote the first version of the Linux kernel?", start + 19 * 60_000L));
        list.add(assistant(thread, "Your course material doesn't cover who wrote the first Linux kernel. Try asking "
                + "about how the kernel schedules processes or manages memory, which the lectures explain.",
                start + 19 * 60_000L + 20_000, MessageStatus.INSUFFICIENT, List.of()));
        store.messages.put(thread.id(), list);

        long earlier = now - 4 * DAY;
        ChatThread memory = new ChatThread(DemoStore.newId(), OPERATING_SYSTEMS, "Paging and the TLB", earlier, earlier);
        store.threads.put(memory.id(), memory);
        Passage tlb = findPassage("Page tables and the TLB");
        store.messages.put(memory.id(), List.of(
                user(memory, "Why does paging need a TLB?", earlier),
                assistant(memory, "The page table lives in memory, so without help every access would need two "
                        + "memory reads: one for the translation and one for the data. The TLB caches recent "
                        + "translations, so most accesses need only one [1].", earlier + 30_000,
                        MessageStatus.COMPLETE, List.of(cite(1, tlb)))));

        long rmAt = now - 2 * DAY;
        ChatThread rmThread = new ChatThread(DemoStore.newId(), RESEARCH_METHODS, "Choosing a question", rmAt, rmAt);
        store.threads.put(rmThread.id(), rmThread);
        Passage rq = findPassage("Good research questions");
        store.messages.put(rmThread.id(), List.of(
                user(rmThread, "How do I narrow a broad topic into a research question?", rmAt),
                assistant(rmThread, "Narrow it to a specific population, an intervention and an outcome you can "
                        + "measure, so that evidence could support or contradict it within the time you have [1].",
                        rmAt + 25_000, MessageStatus.COMPLETE, List.of(cite(1, rq)))));
    }

    private Passage findPassage(String section) {
        return store.passages.values().stream().filter(p -> p.section().equals(section)).findFirst().orElseThrow();
    }

    private Citation cite(int number, Passage passage) {
        Document document = store.documents.get(passage.documentId());
        return new Citation(number, passage.id(), passage.documentId(), document.title(), passage.page(),
                passage.page(), passage.text());
    }

    private static ChatMessage user(ChatThread thread, String text, long at) {
        return new ChatMessage(DemoStore.newId(), thread.id(), ChatRole.USER, text, MessageStatus.COMPLETE, List.of(), at);
    }

    private static ChatMessage assistant(ChatThread thread, String text, long at, MessageStatus status,
                                         List<Citation> citations) {
        return new ChatMessage(DemoStore.newId(), thread.id(), ChatRole.ASSISTANT, text, status, citations, at);
    }

    // ---------------------------------------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------------------------------------

    private CourseBuilder course(String id, String title, String subtitle, String description, String author,
                                 CourseOrigin origin, CourseStatus status, Visibility visibility, int hue, int version,
                                 long openedAgo, int createdDaysAgo) {
        long created = now - createdDaysAgo * DAY;
        Course course = new Course(id, title, subtitle, description, author, origin, visibility, status, hue, version,
                version > 0 ? now - 9 * DAY : null, "pkg-" + id.substring(0, 8), now - openedAgo, created,
                now - openedAgo);
        store.courses.put(id, course);
        store.enrolled.add(id);
        return new CourseBuilder(id);
    }

    private final class CourseBuilder {
        private final String courseId;
        private int position;

        CourseBuilder(String courseId) {
            this.courseId = courseId;
        }

        void version(int number, String notes, int daysAgo) {
            long at = now - daysAgo * DAY;
            store.versions.computeIfAbsent(courseId, k -> new ArrayList<>())
                    .add(new CourseVersion(DemoStore.newId(), courseId, number, notes, at, at));
        }

        ModuleBuilder module(String title, String summary) {
            CourseModule module = new CourseModule(DemoStore.newId(), courseId, title, summary, position++, now - 30 * DAY,
                    now - 30 * DAY);
            store.modules.put(module.id(), module);
            return new ModuleBuilder(courseId, module.id());
        }

        void status(String documentId, DocumentStatus status, double progress) {
            Document d = store.documents.get(documentId);
            store.documents.put(documentId, new Document(d.id(), d.courseId(), d.moduleId(), d.title(), d.fileName(),
                    d.fileSha256(), d.byteSize(), d.pageCount(), status, progress, null, null, d.createdAt(), now));
        }

        void needsReview(String documentId) {
            status(documentId, DocumentStatus.NEEDS_REVIEW, 1);
        }

        void failed(String documentId, DocumentError error) {
            Document d = store.documents.get(documentId);
            store.documents.put(documentId, new Document(d.id(), d.courseId(), d.moduleId(), d.title(), d.fileName(),
                    d.fileSha256(), d.byteSize(), d.pageCount(), DocumentStatus.FAILED, 0, null, error, d.createdAt(), now));
        }
    }

    private final class ModuleBuilder {
        private final String courseId;
        private final String moduleId;
        private final Map<String, String> conceptIds = new HashMap<>();
        private ItemStatus nextStatus = ItemStatus.APPROVED;
        private ItemOrigin nextOrigin = ItemOrigin.MANUAL;

        ModuleBuilder(String courseId, String moduleId) {
            this.courseId = courseId;
            this.moduleId = moduleId;
        }

        String document(String title, String fileName, int pages, long bytes) {
            String id = DemoStore.newId();
            String sha = String.format("%08x", fileName.hashCode()).repeat(8);
            store.documents.put(id, new Document(id, courseId, moduleId, title, fileName, sha, bytes, pages,
                    DocumentStatus.READY, 1, null, null, now - 20 * DAY, now - 20 * DAY));
            return id;
        }

        Passage passage(String documentId, int page, String section, String text) {
            Passage passage = new Passage(DemoStore.newId(), documentId, courseId, moduleId, page, section, text);
            store.passages.put(passage.id(), passage);
            return passage;
        }

        String concept(String name, String description) {
            Concept concept = new Concept(DemoStore.newId(), courseId, moduleId, name, description, now - 20 * DAY,
                    now - 20 * DAY);
            store.concepts.put(concept.id(), concept);
            conceptIds.put(name, concept.id());
            return concept.id();
        }

        ModuleBuilder status(ItemStatus status) {
            nextStatus = status;
            nextOrigin = ItemOrigin.MANUAL;
            return this;
        }

        ModuleBuilder ai() {
            nextOrigin = ItemOrigin.AI;
            return this;
        }

        String mcq(String concept, Passage source, String prompt, String answer, String explanation, String... distractors) {
            return item(concept, source, ItemType.MCQ, prompt, answer, explanation, List.of(distractors));
        }

        String trueFalse(String concept, Passage source, String statement, boolean truth, String explanation) {
            return item(concept, source, ItemType.TRUE_FALSE, statement, truth ? "True" : "False", explanation, List.of());
        }

        String flashcard(String concept, Passage source, String front, String back) {
            return item(concept, source, ItemType.FLASHCARD, front, back, "", List.of());
        }

        String cloze(String concept, Passage source, String text, String answer, String explanation) {
            return item(concept, source, ItemType.CLOZE, text, answer, explanation, List.of());
        }

        String shortAnswer(String concept, Passage source, String prompt, String answer, String explanation) {
            return item(concept, source, ItemType.SHORT_ANSWER, prompt, answer, explanation, List.of());
        }

        private String item(String concept, Passage source, ItemType type, String prompt, String answer,
                            String explanation, List<String> distractors) {
            String id = DemoStore.newId();
            ItemStatus status = nextStatus;
            ItemOrigin origin = nextOrigin;
            SupportStatus support = origin == ItemOrigin.AI ? SupportStatus.SUPPORTED : SupportStatus.UNCHECKED;
            Difficulty difficulty = switch (type) {
                case TRUE_FALSE, FLASHCARD -> Difficulty.EASY;
                case SHORT_ANSWER -> Difficulty.HARD;
                default -> prompt.length() > 70 ? Difficulty.HARD : Difficulty.MEDIUM;
            };
            long created = now - (origin == ItemOrigin.AI ? 2 : 25) * DAY;
            store.items.put(id, new LearningItem(id, courseId, moduleId, type, prompt, answer, explanation, distractors,
                    difficulty, source.id(), source.page(), origin, status, support, null, created, created));
            store.itemConcepts.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(concept);
            nextStatus = ItemStatus.APPROVED;
            nextOrigin = ItemOrigin.MANUAL;
            return id;
        }
    }

    /** Lower-cased words of {@code text} longer than three letters (for matching questions to passages). */
    static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() > 3 && !STOP_WORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private static final Set<String> STOP_WORDS = Set.of("what", "which", "when", "where", "does", "that", "this",
            "with", "from", "have", "make", "makes", "into", "about", "there", "their", "they", "them", "than",
            "then", "while", "would", "could", "should", "your", "explain", "between", "difference", "used");
}
