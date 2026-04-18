import java.util.Map;

import soot.Unit;
import soot.PatchingChain;
import soot.Scene;
import soot.SceneTransformer;
import soot.SootClass;
import soot.SootMethod;
import soot.jimple.internal.JAssignStmt;

    public class SampleSceneTransform extends SceneTransformer {
    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        for (SootClass sc : Scene.v().getApplicationClasses()) {
            int methodCount = sc.getMethods().size();
            for (SootMethod mtd : sc.getMethods()) {
                printMethodInfo(mtd);
            }
        }
    }

    static void printMethodInfo(SootMethod mtd) {
        System.out.println("METHOD: " + mtd.getDeclaringClass().getName() + "." + mtd.getName());
    }
}
