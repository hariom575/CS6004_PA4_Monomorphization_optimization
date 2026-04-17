import soot.*;

/**
 * Entry point.
 *
 * Compile:
 *   javac -cp soot-4.6.0-jar-with-dependencies.jar *.java
 *
 * Run (analysis + rewrite, output Jimple to sootOutput/):
 *   java -cp .:../soot-4.6.0-jar-with-dependencies.jar PA4 ../testcases/Test Test11
 *
 * The transformed .jimple files are written to ./sootOutput/
 * Open them to verify the rewrites (inlining, devirt, guarded calls).
 */
public class PA4 {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: PA4 <process-dir> <main-class>");
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
            // Write transformed Jimple to ./sootOutput/ so you can inspect it.
            // Change to "none" if you only want the analysis report.
            "-output-format",   "jimple",
            "-output-dir",      "sootOutput",
            "-allow-phantom-refs",
            "-keep-line-number",
            "-p", "jb",         "use-original-names:true",
            "-p", "cg",         "enabled:true",
            "-p", "cg.cha",     "enabled:true",
            "-p", "cg.spark",   "enabled:false",
            mainClass
        });

        System.out.println("\nTransformed Jimple written to: ./sootOutput/");
    }
}