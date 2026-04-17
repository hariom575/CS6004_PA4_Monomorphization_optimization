import soot.*;

/**
 * Entry point for the monomorphization optimization pass.
 *
 * Usage (from the analysis/ directory):
 *   java -cp .:../soot-4.6.0-jar-with-dependencies.jar Main <process-dir> <main-class>
 *
 * process-dir : directory containing compiled .class files of the program to optimize
 * main-class  : name of the class containing main() (e.g. Test1)
 *
 * Output:
 *   Transformed class files are written to sootOutput/<ClassName>.class
 *   A text report of call-site classifications and rewrites is printed to stdout.
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
