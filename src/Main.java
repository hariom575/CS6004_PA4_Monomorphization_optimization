import soot.*;

/*
 * CS6004 PA4 - Monomorphization Optimization
 * Team: The Heap Farmer
 *   Hariom Mewada    (24m2137)
 *   Pushpendra Uikey (23b1023)
 *
 * Entry point for the monomorphization pass.
 *
 * Usage:
 *   java -cp .:soot-4.6.0-jar-with-dependencies.jar Main <process-dir> <main-class>
 *
 *   process-dir : folder with compiled .class files of the program
 *   main-class  : class that has main() method (e.g. Test1)
 *
 * We use cg.cha to build the call graph. Soot internally runs VTA
 * (Variable Type Analysis) on top of CHA, so the edges we get are
 * already VTA-refined. After that our own intra-proc PTA and k-object
 * sensitivity narrow the target sets further.
 *
 * Transformed .class files are written to sootOutput/
 */
public class Main {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: Main <process-dir> <main-class>");
            System.exit(1);
        }

        String processDir = args[0];
        String mainClass  = args[1];

        G.reset();

        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.mono", new MonomorphizationTransformer()));

        soot.Main.main(new String[]{
            "-w",
            "-src-prec",        "only-class",
            "-process-dir",     processDir,
            "-main-class",      mainClass,
            "-prepend-classpath",
            "-cp",              processDir,
            "-output-format",   "class",
            "-output-dir",      "sootOutput",
            "-allow-phantom-refs",
            "-keep-line-number",
            "-p", "jb",         "use-original-names:true",
            "-p", "cg",         "enabled:true",
            "-p", "cg.cha",     "enabled:true",
            "-p", "cg.spark",   "enabled:false",
            mainClass
        });

        System.out.println("Optimized class files written to: ./sootOutput/");
    }
}
