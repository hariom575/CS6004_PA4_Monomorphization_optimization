
import soot.*;

import java.util.*;

/**
 * Entry point.
 *
 * Compile and run from the directory containing all .class files:
 *
 *   javac -cp soot-4.6.0-jar-with-dependencies.jar *.java
 *   java  -cp .:soot-4.6.0-jar-with-dependencies.jar ScalarReplacementMain . Test
 */
public class PA4 {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ScalarReplacementMain <process-dir> <main-class>");
            System.exit(1);
        }
        String processDir = args[0];
        String mainClass  = args[1];

        G.reset();

        // Register transformer BEFORE soot.Main.main() runs.
        PackManager.v().getPack("wjtp").add(
                new Transform("wjtp.scalar-replace",
                              new AnalysisTransformer()));

        soot.Main.main(new String[]{
            "-w",
            // KEY: "only-class" loads bytecode directly and never calls
            // JastAddJ (the Java-source resolver that reads
            // sun.boot.class.path, which is absent on Java 9+).
            "-src-prec",        "only-class",
            "-process-dir",     processDir,
            "-main-class",      mainClass,
            "-prepend-classpath",
            "-cp",              processDir,
            "-output-format",   "none",
            "-allow-phantom-refs",
            "-keep-line-number",
            "-p", "cg",         "enabled:true",
            "-p", "cg.cha",     "enabled:true",
            "-p", "cg.spark",   "enabled:false",
            mainClass
        });
    }
}