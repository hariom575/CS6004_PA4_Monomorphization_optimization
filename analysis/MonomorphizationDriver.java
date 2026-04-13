import org.slf4j.LoggerFactory;
import soot.*;
import soot.options.Options;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 * Monomorphization Pipeline Driver
 * ═══════════════════════════════════════════════════════════════════
 *
 * Orchestrates the full 5-step pipeline:
 *
 *   Step 1a  CHA  – Class Hierarchy Analysis (upper-bound targets)
 *   Step 1b  VTA  – Variable Type Analysis   (type-flow refinement)
 *   Step 2   PTA  – Points-To Analysis/Spark (allocation-site precision)
 *   Step 3   kObj – k-Object Sensitivity     (context-sensitive PTA)
 *   Step 4+5 RW   – Call-Site Rewriter       (MONO/POLY/MEGA transforms)
 *
 * Usage (command-line):
 *   java -jar monomorphization.jar \
 *        -cp <classpath> \
 *        -main <MainClass> \
 *        [-k <depth>]        (default: 2)
 *        [-no-inline]        (devirtualise only, no inlining)
 *        [-output <dir>]     (where to write transformed Jimple)
 *
 * Usage (programmatic):
 *   MonomorphizationDriver driver = new MonomorphizationDriver(config);
 *   driver.run();
 *   AnalysisStats stats = driver.getStats();
 */
public class MonomorphizationDriver {

    private static final Logger log =
        LoggerFactory.getLogger(MonomorphizationDriver.class);

    // ── Configuration ─────────────────────────────────────────────

    /** Classpath entries (colon-separated on Unix). */
    private final String classpath;

    /** Fully-qualified main class name. */
    private final String mainClass;

    /** k for k-object sensitivity (1 = 1-obj, 2 = 2-obj, …). */
    private final int k;

    /** Directory to write transformed output. */
    private final String outputDir;

    /** Shared statistics collector. */
    private final AnalysisStats stats = new AnalysisStats();

    // ── Constructor ────────────────────────────────────────────────

    public MonomorphizationDriver(String classpath,
                                   String mainClass,
                                   int k,
                                   String outputDir) {
        this.classpath = classpath;
        this.mainClass = mainClass;
        this.k         = k;
        this.outputDir = outputDir;
    }

    // ── Pipeline entry point ───────────────────────────────────────

    /**
     * Runs the full monomorphization pipeline and writes the
     * transformed class files to {@link #outputDir}.
     */
    public void run() {
        log.info("════════════════════════════════════════════");
        log.info(" Monomorphization Pipeline  (k={})", k);
        log.info("════════════════════════════════════════════");

        // ── 0. Initialise Soot scene ─────────────────────────────
        initialiseSoot();

        // ── 1a. CHA ──────────────────────────────────────────────
        log.info("── Step 1a: CHA ────────────────────────────");
        CHACallGraphBuilder cha = new CHACallGraphBuilder(stats);
        List<CallSiteInfo> sites = cha.buildAndCollect();
        cha.printCHASummary(sites);

        // ── 1b. VTA ──────────────────────────────────────────────
        log.info("── Step 1b: VTA ────────────────────────────");
        VTARefiner vta = new VTARefiner(stats);
        vta.refine(sites);
        vta.printVTASummary(sites);

        // ── 2. Custom Intra-Proc PTA ─────────────────────────────
        log.info("── Step 2:  Custom Intra-Proc PTA ──────────");
        CustomPTARefiner pta = new CustomPTARefiner(stats);
        pta.refine(sites);
        pta.printPTASummary(sites);

        // ── 3. k-Object Sensitivity ──────────────────────────────
        log.info("── Step 3:  {}-Object Sensitivity ──────────", k);
        KObjectSensitiveRefiner kObj = new KObjectSensitiveRefiner(k, stats, pta);
        kObj.refine(sites);

        // ── 4+5. Rewrite (MONO / POLY / MEGA) ───────────────────
        log.info("── Step 4+5: Call-Site Rewriting ───────────");
        CallSiteRewriter rewriter = new CallSiteRewriter(stats);
        rewriter.rewriteAll(sites);

        // ── 6. Emit transformed output ───────────────────────────
        log.info("── Writing output to {} ───────────────────", outputDir);
        writeOutput();

        // ── 7. Final report ──────────────────────────────────────
        log.info("\n{}", stats);
    }

    // ── Soot initialisation ───────────────────────────────────────

    private void initialiseSoot() {
        // Reset any previous Soot state
        G.reset();
        Scene scene = Scene.v();
        Options opts = Options.v();

        opts.set_prepend_classpath(true);
        opts.set_whole_program(true);
        opts.set_allow_phantom_refs(true);
        opts.set_soot_classpath(classpath);
        opts.set_output_dir(outputDir);
        opts.set_output_format(Options.output_format_class);

        // Application classes come from the user classpath
        opts.set_process_dir(List.of(classpath));

        // Entry point
        SootClass mainSootClass = scene.loadClassAndSupport(mainClass);
        mainSootClass.setApplicationClass();
        scene.setMainClass(mainSootClass);

        // Load all application classes
        scene.loadNecessaryClasses();

        log.info("[Init] Soot scene ready. {} application classes loaded.",
                 scene.getApplicationClasses().size());
    }

    // ── Output ────────────────────────────────────────────────────

    private void writeOutput() {
        try {
            PackManager.v().writeOutput();
            log.info("[Output] Written to {}.", outputDir);
        } catch (Exception e) {
            log.error("[Output] Failed: {}", e.getMessage(), e);
        }
    }

    // ── Accessors ─────────────────────────────────────────────────

    public AnalysisStats getStats() { return stats; }

    // ── Command-line entry point ──────────────────────────────────

    /**
     * Command-line invocation:
     *
     *   java -jar mono.jar -cp <cp> -main <cls> [-k <n>] [-out <dir>]
     */
    public static void main(String[] args) {
        String cp      = ".";
        String main    = null;
        int    kDepth  = 2;
        String outDir  = "sootOutput";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cp"   -> cp     = args[++i];
                case "-main" -> main   = args[++i];
                case "-k"    -> kDepth = Integer.parseInt(args[++i]);
                case "-out"  -> outDir = args[++i];
                default      -> log.warn("Unknown arg: {}", args[i]);
            }
        }

        if (main == null) {
            System.err.println("Usage: mono.jar -cp <classpath> -main <MainClass> [-k n] [-out dir]");
            System.exit(1);
        }

        new MonomorphizationDriver(cp, main, kDepth, outDir).run();
    }
}
